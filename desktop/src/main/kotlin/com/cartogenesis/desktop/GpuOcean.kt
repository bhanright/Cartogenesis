package com.cartogenesis.desktop

import com.cartogenesis.worldgen.pipeline.OceanAccelerator
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import org.lwjgl.opengl.GL43C

/** Red-black stream-function relaxation on the shared offscreen graphics context. */
class GpuOcean private constructor(override val name: String) : OceanAccelerator {
    /** The accelerator, or the reason this device cannot provide it. */
    class Result(val accelerator: GpuOcean?, val unavailableBecause: String?)

    private var program = 0

    override suspend fun solve(
        cellsAcross: Int,
        cellsDown: Int,
        isWater: BooleanArray,
        forcing: FloatArray,
        passes: Int,
        overRelaxation: Float
    ): FloatArray? {
        // An odd-width cylinder joins cells of the same colour across its seam.
        if (cellsAcross <= 0 || cellsAcross % 2 != 0 || cellsDown <= 0) return null
        val cellCount = cellsAcross.toLong() * cellsDown
        if (cellCount != isWater.size.toLong() || cellCount != forcing.size.toLong()) return null
        val stillWanted = currentCoroutineContext()[Job]
        return GlContext.run("Ocean currents") {
            if (program == 0) return@run null
            val buffers = IntArray(3)
            GL43C.glGenBuffers(buffers)
            try {
                val waterWords = IntArray(isWater.size) { if (isWater[it]) 1 else 0 }
                GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buffers[0])
                GL43C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, waterWords, GL43C.GL_STATIC_DRAW)
                GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buffers[1])
                GL43C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, forcing, GL43C.GL_STATIC_DRAW)
                GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buffers[2])
                GL43C.glBufferData(
                    GL43C.GL_SHADER_STORAGE_BUFFER, FloatArray(forcing.size), GL43C.GL_DYNAMIC_COPY
                )
                if (GL43C.glGetError() != GL43C.GL_NO_ERROR) return@run null
                for (binding in buffers.indices) {
                    GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, buffers[binding])
                }
                GL43C.glUseProgram(program)
                GL43C.glUniform1i(GL43C.glGetUniformLocation(program, "uWidth"), cellsAcross)
                GL43C.glUniform1i(GL43C.glGetUniformLocation(program, "uHeight"), cellsDown)
                GL43C.glUniform1f(GL43C.glGetUniformLocation(program, "uOmega"), overRelaxation)
                val colourUniform = GL43C.glGetUniformLocation(program, "uColour")
                val groupsAcross = (cellsAcross + WORK_GROUP_SIDE - 1) / WORK_GROUP_SIDE
                val groupsDown = (cellsDown + WORK_GROUP_SIDE - 1) / WORK_GROUP_SIDE
                repeat(passes) {
                    if (stillWanted?.isActive == false) return@run null
                    for (colour in 0..1) {
                        GL43C.glUniform1i(colourUniform, colour)
                        GL43C.glDispatchCompute(groupsAcross, groupsDown, 1)
                        // The next colour reads neighbours written by other work groups.
                        GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT)
                    }
                }
                GL43C.glMemoryBarrier(GL43C.GL_BUFFER_UPDATE_BARRIER_BIT)
                val stream = FloatArray(forcing.size)
                GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buffers[2])
                GL43C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0L, stream)
                if (GL43C.glGetError() == GL43C.GL_NO_ERROR) stream else null
            } finally {
                GL43C.glDeleteBuffers(buffers)
            }
        }
    }

    companion object {
        /** 16 squared is 256 invocations, within the compute baseline; matches the shader. */
        private const val WORK_GROUP_SIDE = 16

        /** Probe the shared context and compile the relaxation, returning a reason on decline. */
        fun createOrNull(): Result {
            val context = GlContext.ensure()
            val device = context.device ?: return Result(null, context.unavailableBecause)
            val ocean = GpuOcean(device)
            ocean.program = GlContext.run("Compiling ocean relaxation") {
                GlContext.compileCompute(SOURCE)
            } ?: return Result(null, "the ocean shader would not compile")
            return Result(ocean, null)
        }

        private val SOURCE = """
            #version 430
            layout(local_size_x = 16, local_size_y = 16) in;
            layout(std430, binding = 0) readonly buffer Water { uint water[]; };
            layout(std430, binding = 1) readonly buffer Forcing { float forcing[]; };
            layout(std430, binding = 2) buffer Stream { float stream[]; };
            uniform int uWidth;
            uniform int uHeight;
            uniform int uColour;
            uniform float uOmega;

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
                // Preserve the CPU's addition order; contraction otherwise compounds over passes.
                precise float sum = stream[y * uWidth + east] + stream[y * uWidth + west]
                    + stream[north * uWidth + x] + stream[south * uWidth + x];
                precise float relaxed = (sum - forcing[cell]) * 0.25;
                precise float updated = stream[cell] + (relaxed - stream[cell]) * uOmega;
                stream[cell] = updated;
            }
        """.trimIndent()
    }
}
