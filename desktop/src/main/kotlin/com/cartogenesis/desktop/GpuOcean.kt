package com.cartogenesis.desktop

import com.cartogenesis.worldgen.pipeline.OceanAccelerator
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import org.lwjgl.opengl.GL43C

/**
 * Solves the coarse stream function on the graphics card.
 *
 * The algorithm is the CPU one, unchanged. Red-black Gauss-Seidel colours the grid by the parity of
 * column plus row, so no two cells of one colour are neighbours and a whole colour can be updated
 * at once from the other colour's current values. That is one dispatch per colour and two per
 * relaxation pass, with a memory barrier between them because the second colour reads cells the
 * first colour's other work groups wrote.
 *
 * Two things this deliberately does not do:
 *
 * It does not relax the whole grid in one dispatch and call it Jacobi. Over-relaxation above one
 * is only legal on Gauss-Seidel; applied to Jacobi it diverges, and the CPU solver's own comment
 * says so. Halving the dispatch count would cost the configured omega of 1.7 and with it most of
 * the convergence the 3000 passes buy.
 *
 * It does not promise the CPU's answer to the last bit. The card is free to round differently, so
 * the currents that come back are the same circulation and not the same numbers — which is why
 * choosing this path makes a world carry its ocean in the save rather than be regenerated from
 * its seed.
 */
class GpuOcean private constructor(override val name: String) : OceanAccelerator {

    /** What probing this machine found: an accelerator, or the reason there is not one. */
    class Result(val accelerator: GpuOcean?, val unavailableBecause: String?)

    /** The compiled relaxation, or zero if it never compiled. Written once, on the probe. */
    private var relaxProgram = 0

    override suspend fun solve(
        cellsAcross: Int,
        cellsDown: Int,
        isWater: BooleanArray,
        forcing: FloatArray,
        passes: Int,
        overRelaxation: Float
    ): FloatArray? {
        // Red-black needs the two colours to stay independent across the seam, and on an odd-width
        // cylinder column 0 and column width-1 have the same parity and are neighbours.
        if (cellsAcross <= 0 || cellsAcross % 2 != 0 || cellsDown <= 0 || passes < 0) return null
        val cellCount = cellsAcross.toLong() * cellsDown
        if (cellCount != isWater.size.toLong() || cellCount != forcing.size.toLong()) return null

        // Who is waiting for this batch, so the loop below can find out whether they still are. A
        // batch of passes is one blocking call on the context's own thread and nothing inside it
        // suspends, so a generation cancelled while it runs would otherwise be discovered only
        // once every pass had finished. Giving up looks like a decline, which is the seam's own
        // way of saying "not me".
        val stillWanted = currentCoroutineContext()[Job]
        return GlContext.run("Ocean currents") {
            if (relaxProgram == 0) return@run null

            val buffers = IntArray(3)
            GL43C.glGenBuffers(buffers)
            val waterBuffer = buffers[WATER_BINDING]
            val forcingBuffer = buffers[FORCING_BINDING]
            val streamBuffer = buffers[STREAM_BINDING]

            try {
                // A boolean has no storage width the card agrees on, so the mask crosses as words.
                val waterWords = IntArray(isWater.size) { if (isWater[it]) 1 else 0 }
                GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, waterBuffer)
                GL43C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, waterWords, GL43C.GL_STATIC_DRAW)
                GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, forcingBuffer)
                GL43C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, forcing, GL43C.GL_STATIC_DRAW)
                // The solve starts from a stream function of zero, as the CPU's array does.
                GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, streamBuffer)
                GL43C.glBufferData(
                    GL43C.GL_SHADER_STORAGE_BUFFER, FloatArray(forcing.size), GL43C.GL_DYNAMIC_COPY
                )
                if (GL43C.glGetError() != GL43C.GL_NO_ERROR) return@run null

                GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, WATER_BINDING, waterBuffer)
                GL43C.glBindBufferBase(
                    GL43C.GL_SHADER_STORAGE_BUFFER, FORCING_BINDING, forcingBuffer
                )
                GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, STREAM_BINDING, streamBuffer)

                GL43C.glUseProgram(relaxProgram)
                GL43C.glUniform1i(uniform("uWidth"), cellsAcross)
                GL43C.glUniform1i(uniform("uHeight"), cellsDown)
                GL43C.glUniform1f(uniform("uOverRelaxation"), overRelaxation)
                val colourUniform = uniform("uColour")

                val groupsAcross = (cellsAcross + WORK_GROUP_SIDE - 1) / WORK_GROUP_SIDE
                val groupsDown = (cellsDown + WORK_GROUP_SIDE - 1) / WORK_GROUP_SIDE
                repeat(passes) {
                    if (stillWanted?.isActive == false) return@run null
                    for (colour in 0..1) {
                        GL43C.glUniform1i(colourUniform, colour)
                        GL43C.glDispatchCompute(groupsAcross, groupsDown, 1)
                        // The next colour reads neighbours other work groups have just written.
                        GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT)
                    }
                }

                // And the readback below reads what all of them wrote.
                GL43C.glMemoryBarrier(GL43C.GL_BUFFER_UPDATE_BARRIER_BIT)
                val coarseStream = FloatArray(forcing.size)
                GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, streamBuffer)
                GL43C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0L, coarseStream)
                if (GL43C.glGetError() == GL43C.GL_NO_ERROR) coarseStream else null
            } finally {
                // However the run ended, the three buffers go back: the context outlives every
                // generation that uses it, and a solve that walked out without freeing them would
                // leave it a little smaller each time.
                GL43C.glDeleteBuffers(buffers)
            }
        }
    }

    private fun uniform(name: String) = GL43C.glGetUniformLocation(relaxProgram, name)

    companion object {
        /** Storage binding points, matching the `layout(binding = ...)` lines in [SOURCE]. */
        private const val WATER_BINDING = 0
        private const val FORCING_BINDING = 1
        private const val STREAM_BINDING = 2

        /**
         * The side of a work group, in invocations. 16x16 is 256, which every device supporting
         * compute shaders is required to allow.
         *
         * The same figure is written into `layout(local_size_x...)` in [SOURCE]; a shader's
         * declaration cannot read a Kotlin constant, so the two are kept in step by hand.
         */
        private const val WORK_GROUP_SIDE = 16

        /** How long a driver may take over one small compute shader before it is given up on. */
        private const val COMPILE_TIMEOUT_SECONDS = 30L

        /**
         * Takes the shared offscreen context and compiles the relaxation, or returns null with a
         * reason if this machine cannot offer what is needed.
         */
        fun createOrNull(): Result {
            val context = GlContext.ensure()
            val device = context.device
                ?: return Result(null, context.unavailableBecause ?: "unknown failure")

            val ocean = GpuOcean(device)
            ocean.relaxProgram = GlContext.run(
                "Compiling the ocean relaxation",
                timeoutSeconds = COMPILE_TIMEOUT_SECONDS
            ) {
                GlContext.compileCompute(SOURCE)
            } ?: return Result(null, "the ocean shader would not compile")
            return Result(ocean, null)
        }

        /**
         * One relaxation pass over one colour: the cell that satisfies the discrete Poisson
         * equation given its four neighbours, blended toward by [overRelaxation].
         *
         * The world is a cylinder, so columns wrap and rows clamp — a row 0 cell reads itself as
         * its northern neighbour, which is what the CPU reference does and what keeps the pole a
         * reflecting wall rather than a hole. Land pins the stream function at zero, which is what
         * turns a coast into something the circulation has to close against.
         */
        private val SOURCE = """
            #version 430
            layout(local_size_x = 16, local_size_y = 16) in;

            layout(std430, binding = 0) readonly buffer Water { uint water[]; };
            layout(std430, binding = 1) readonly buffer Forcing { float forcing[]; };
            layout(std430, binding = 2) buffer Stream { float stream[]; };

            uniform int uWidth;
            uniform int uHeight;
            uniform int uColour;
            uniform float uOverRelaxation;

            void main() {
                int x = int(gl_GlobalInvocationID.x);
                int y = int(gl_GlobalInvocationID.y);
                if (x >= uWidth || y >= uHeight || ((x + y) & 1) != uColour) return;

                int cell = y * uWidth + x;
                if (water[cell] == 0u) { stream[cell] = 0.0; return; }

                int east = (x + 1) % uWidth;
                int west = (x + uWidth - 1) % uWidth;
                int north = max(y - 1, 0);
                int south = min(y + 1, uHeight - 1);

                // Held to the reference's own order and its separate multiplies: a driver free to
                // fuse or reassociate these would drift a little further from the CPU with every
                // one of the three thousand passes.
                precise float neighbourSum = stream[y * uWidth + east] + stream[y * uWidth + west]
                    + stream[north * uWidth + x] + stream[south * uWidth + x];
                precise float relaxed = (neighbourSum - forcing[cell]) * 0.25;
                precise float here = stream[cell];
                stream[cell] = here + (relaxed - here) * uOverRelaxation;
            }
        """.trimIndent()
    }
}
