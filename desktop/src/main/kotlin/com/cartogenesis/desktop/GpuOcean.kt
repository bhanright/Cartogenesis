package com.cartogenesis.desktop

import com.cartogenesis.worldgen.pipeline.OceanAccelerator
import com.cartogenesis.worldgen.pipeline.OceanStencil
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import org.lwjgl.opengl.GL43C

/**
 * Relaxes the ocean's two problems, its circulation and its heat, on the graphics card.
 *
 * The algorithm is the processor's, unchanged ([com.cartogenesis.worldgen.pipeline.OceanCirculation.relax]).
 * Red-black Gauss-Seidel colours the grid by the parity of column plus row, so no two cells of one
 * colour are neighbours and a whole colour can be updated at once from the other colour's current
 * values. That is one dispatch per colour and two per relaxation pass, with a memory barrier
 * between them because the second colour reads cells the first colour's other work groups wrote.
 *
 * It does not relax the whole grid in one dispatch and call it Jacobi: Gauss-Seidel's eigenvalues
 * are Jacobi's squared, which is what keeps this operator's relaxation from diverging.
 *
 * It does not promise the processor's answer to the last bit. The card is free to round
 * differently, so the currents that come back are the same circulation and not the same numbers,
 * which is why choosing this path makes a world carry its ocean in the save rather than be
 * regenerated from its seed.
 */
class GpuOcean private constructor(override val name: String) : OceanAccelerator {

    /** What probing this machine found: an accelerator, or the reason there is not one. */
    class Result(val accelerator: GpuOcean?, val unavailableBecause: String?)

    /** The compiled relaxation, or zero if it never compiled. Written once, on the probe. */
    private var relaxProgram = 0

    override suspend fun solve(
        stencil: OceanStencil,
        start: FloatArray,
        passes: Int
    ): FloatArray? {
        val cellsAcross = stencil.cellsAcross
        val cellsDown = stencil.cellsDown
        // Red-black needs the two colours to stay independent across the seam, and on an odd-width
        // cylinder column 0 and column width-1 have the same parity and are neighbours.
        if (cellsAcross <= 0 || cellsAcross % 2 != 0 || cellsDown <= 0 || passes < 0) return null
        val cellCount = cellsAcross.toLong() * cellsDown
        if (cellCount != stencil.isWater.size.toLong() || cellCount != start.size.toLong()) return null

        // Who is waiting for this batch, so the loop below can find out whether they still are. A
        // batch of passes is one blocking call on the context's own thread and nothing inside it
        // suspends, so a generation cancelled while it runs would otherwise be discovered only
        // once every pass had finished. Giving up looks like a decline, and the caller checks
        // whether it was wanted before falling back.
        val stillWanted = currentCoroutineContext()[Job]
        return GlContext.run("Ocean currents") {
            if (relaxProgram == 0) return@run null

            val buffers = IntArray(4)
            GL43C.glGenBuffers(buffers)
            try {
                // A boolean has no storage width the card agrees on, so the mask crosses as words.
                val waterWords = IntArray(stencil.isWater.size) { if (stencil.isWater[it]) 1 else 0 }
                upload(buffers[WATER_BINDING], waterWords)
                upload(buffers[FORCING_BINDING], stencil.forcing)
                upload(buffers[STREAM_BINDING], start, GL43C.GL_DYNAMIC_COPY)
                // Four weights a cell, east, west, north and south, as one vec4 per cell.
                val weights = FloatArray(start.size * WEIGHTS_PER_CELL)
                for (cell in start.indices) {
                    weights[cell * WEIGHTS_PER_CELL] = stencil.eastWeight[cell]
                    weights[cell * WEIGHTS_PER_CELL + 1] = stencil.westWeight[cell]
                    weights[cell * WEIGHTS_PER_CELL + 2] = stencil.northWeight[cell]
                    weights[cell * WEIGHTS_PER_CELL + 3] = stencil.southWeight[cell]
                }
                upload(buffers[WEIGHTS_BINDING], weights)
                if (GL43C.glGetError() != GL43C.GL_NO_ERROR) return@run null
                for (binding in buffers.indices) {
                    GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, buffers[binding])
                }

                GL43C.glUseProgram(relaxProgram)
                GL43C.glUniform1i(uniform("uWidth"), cellsAcross)
                GL43C.glUniform1i(uniform("uHeight"), cellsDown)
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
                val stream = FloatArray(start.size)
                GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buffers[STREAM_BINDING])
                GL43C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0L, stream)
                if (GL43C.glGetError() == GL43C.GL_NO_ERROR) stream else null
            } finally {
                // However the run ended, the buffers go back: the context outlives every
                // generation that uses it.
                GL43C.glDeleteBuffers(buffers)
            }
        }
    }

    private fun upload(buffer: Int, data: IntArray) {
        GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buffer)
        GL43C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, data, GL43C.GL_STATIC_DRAW)
    }

    private fun upload(buffer: Int, data: FloatArray, usage: Int = GL43C.GL_STATIC_DRAW) {
        GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buffer)
        GL43C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, data, usage)
    }

    private fun uniform(name: String) = GL43C.glGetUniformLocation(relaxProgram, name)

    companion object {
        /** Storage binding points, matching the `layout(binding = ...)` lines in [SOURCE]. */
        private const val WATER_BINDING = 0
        private const val FORCING_BINDING = 1
        private const val STREAM_BINDING = 2
        private const val WEIGHTS_BINDING = 3

        /** East, west, north and south: one vec4 of weights per cell. */
        private const val WEIGHTS_PER_CELL = 4

        /**
         * The side of a work group, in invocations. 16x16 is 256, which every device supporting
         * compute shaders is required to allow. The same figure is written into
         * `layout(local_size_x...)` in [SOURCE]; a shader's declaration cannot read a Kotlin
         * constant, so the two are kept in step by hand.
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
         * One relaxation pass over one color: every water cell of the color set to the value that
         * satisfies its balance given its four neighbors, `e x_east + w x_west + n x_north +
         * s x_south - f`. Columns wrap; an edge row's weight toward its pole is zero; land is held
         * at zero.
         *
         * Columns wrap; beyond either pole ψ is zero, a wall; land pins ψ at zero.
         */
        private val SOURCE = """
            #version 430
            layout(local_size_x = 16, local_size_y = 16) in;

            layout(std430, binding = 0) readonly buffer Water { uint water[]; };
            layout(std430, binding = 1) readonly buffer Forcing { float forcing[]; };
            layout(std430, binding = 2) buffer Stream { float stream[]; };
            layout(std430, binding = 3) readonly buffer Weights { vec4 weights[]; };

            uniform int uWidth;
            uniform int uHeight;
            uniform int uColour;

            void main() {
                int x = int(gl_GlobalInvocationID.x);
                int y = int(gl_GlobalInvocationID.y);
                if (x >= uWidth || y >= uHeight || ((x + y) & 1) != uColour) return;

                int cell = y * uWidth + x;
                if (water[cell] == 0u) { stream[cell] = 0.0; return; }

                int east = (x + 1) % uWidth;
                int west = (x + uWidth - 1) % uWidth;
                float streamNorth = y > 0 ? stream[cell - uWidth] : 0.0;
                float streamSouth = y + 1 < uHeight ? stream[cell + uWidth] : 0.0;
                vec4 weight = weights[cell];

                // Held to the reference's own order and its separate multiplies: a driver free to
                // fuse or reassociate these would drift a little further from the processor with
                // every pass.
                precise float eastTerm = weight.x * stream[y * uWidth + east];
                precise float westTerm = weight.y * stream[y * uWidth + west];
                precise float northTerm = weight.z * streamNorth;
                precise float southTerm = weight.w * streamSouth;
                precise float relaxed = ((eastTerm + westTerm) + northTerm) + southTerm - forcing[cell];
                stream[cell] = relaxed;
            }
        """.trimIndent()
    }
}
