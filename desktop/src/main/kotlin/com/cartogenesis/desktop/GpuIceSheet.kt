package com.cartogenesis.desktop

import com.cartogenesis.worldgen.pipeline.IceSheetAccelerator
import org.lwjgl.opengl.GL43C

/**
 * Draws the ice sheet's profile and the flow down its surface on the graphics card.
 *
 * Rule 8's seam for `IceSheet`, and the simplest of the three this program has: one dispatch that
 * writes the thickness from the Vialov profile, a barrier, and a second that reads the surface
 * those thicknesses make and picks each cell's steepest descent. There is no iteration and no
 * convergence to lose, so unlike `GpuOcean` there is nothing here a driver could compound an error
 * through — the only difference between this and the CPU is how the two round one square root and
 * one division, which is what `IceSheetParityTest` measures.
 *
 * The two halves have to be separate dispatches rather than one. The flow at a cell reads its
 * neighbours' thicknesses, and a neighbour may be in another work group, so the whole grid's
 * thickness has to be written and made visible before any of it is read back.
 */
class GpuIceSheet private constructor(override val name: String) : IceSheetAccelerator {

    /** What probing this machine found: an accelerator, or the reason there is not one. */
    class Result(val accelerator: GpuIceSheet?, val unavailableBecause: String?)

    /** The compiled passes, or zero if they never compiled. Written once, on the probe. */
    private var profileProgram = 0
    private var flowProgram = 0

    override suspend fun sheet(
        cellsAcross: Int,
        cellsDown: Int,
        marginDistanceKm: FloatArray,
        nearestMarginCell: IntArray,
        bedRelative: FloatArray,
        onTheSheet: BooleanArray,
        metresPerRootKilometre: Float,
        metresPerFieldUnit: Float,
        cellHeightInCellWidths: Float,
        cellSpanKm: Float
    ): IceSheetAccelerator.Sheet? {
        if (cellsAcross <= 0 || cellsDown <= 0) return null
        val cellCount = cellsAcross.toLong() * cellsDown
        if (cellCount != marginDistanceKm.size.toLong() ||
            cellCount != nearestMarginCell.size.toLong() ||
            cellCount != bedRelative.size.toLong() ||
            cellCount != onTheSheet.size.toLong()
        ) return null

        return GlContext.run("Ice sheet profile") {
            if (profileProgram == 0 || flowProgram == 0) return@run null

            val buffers = IntArray(6)
            GL43C.glGenBuffers(buffers)
            try {
                // A boolean has no storage width the card agrees on, so the mask crosses as words.
                val sheetWords = IntArray(onTheSheet.size) { if (onTheSheet[it]) 1 else 0 }
                upload(buffers[MARGIN_BINDING], MARGIN_BINDING, marginDistanceKm)
                upload(buffers[NEAREST_BINDING], NEAREST_BINDING, nearestMarginCell)
                upload(buffers[BED_BINDING], BED_BINDING, bedRelative)
                upload(buffers[SHEET_BINDING], SHEET_BINDING, sheetWords)
                upload(buffers[OUT_BINDING], OUT_BINDING, FloatArray(marginDistanceKm.size))
                upload(buffers[FLOW_BINDING], FLOW_BINDING, IntArray(marginDistanceKm.size))
                if (GL43C.glGetError() != GL43C.GL_NO_ERROR) return@run null

                val groupsAcross = (cellsAcross + WORK_GROUP_SIDE - 1) / WORK_GROUP_SIDE
                val groupsDown = (cellsDown + WORK_GROUP_SIDE - 1) / WORK_GROUP_SIDE

                GL43C.glUseProgram(profileProgram)
                GL43C.glUniform1i(uniform(profileProgram, "uWidth"), cellsAcross)
                GL43C.glUniform1i(uniform(profileProgram, "uHeight"), cellsDown)
                GL43C.glUniform1f(
                    uniform(profileProgram, "uMetresPerRootKm"), metresPerRootKilometre
                )
                GL43C.glUniform1f(
                    uniform(profileProgram, "uMetresPerFieldUnit"), metresPerFieldUnit
                )
                GL43C.glUniform1f(uniform(profileProgram, "uCellSpanKm"), cellSpanKm)
                GL43C.glDispatchCompute(groupsAcross, groupsDown, 1)
                // The flow pass reads thicknesses other work groups have just written.
                GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT)

                val thickness = FloatArray(marginDistanceKm.size)
                GL43C.glMemoryBarrier(GL43C.GL_BUFFER_UPDATE_BARRIER_BIT)
                GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buffers[OUT_BINDING])
                GL43C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0L, thickness)

                GL43C.glUseProgram(flowProgram)
                GL43C.glUniform1i(uniform(flowProgram, "uWidth"), cellsAcross)
                GL43C.glUniform1i(uniform(flowProgram, "uHeight"), cellsDown)
                GL43C.glUniform1f(
                    uniform(flowProgram, "uMetresPerFieldUnit"), metresPerFieldUnit
                )
                GL43C.glUniform1f(uniform(flowProgram, "uRowScale"), cellHeightInCellWidths)
                GL43C.glDispatchCompute(groupsAcross, groupsDown, 1)
                GL43C.glMemoryBarrier(GL43C.GL_BUFFER_UPDATE_BARRIER_BIT)

                val receiver = IntArray(marginDistanceKm.size)
                GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buffers[FLOW_BINDING])
                GL43C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0L, receiver)
                if (GL43C.glGetError() == GL43C.GL_NO_ERROR) {
                    IceSheetAccelerator.Sheet(thickness, receiver)
                } else null
            } finally {
                // However the run ended, the buffers go back: the context outlives every
                // generation that uses it.
                GL43C.glDeleteBuffers(buffers)
            }
        }
    }

    private fun upload(buffer: Int, binding: Int, data: FloatArray) {
        GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buffer)
        GL43C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, data, GL43C.GL_DYNAMIC_COPY)
        GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, buffer)
    }

    private fun upload(buffer: Int, binding: Int, data: IntArray) {
        GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buffer)
        GL43C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, data, GL43C.GL_DYNAMIC_COPY)
        GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, buffer)
    }

    private fun uniform(program: Int, name: String) = GL43C.glGetUniformLocation(program, name)

    companion object {
        /** Storage binding points, matching the `layout(binding = ...)` lines in the sources. */
        private const val MARGIN_BINDING = 0
        private const val NEAREST_BINDING = 1
        private const val BED_BINDING = 2
        private const val SHEET_BINDING = 3
        private const val OUT_BINDING = 4

        /**
         * The receivers get a buffer of their own rather than being written back over the bed.
         *
         * They cannot share one: a cell's flow reads its eight neighbours' surfaces, and a
         * neighbour is usually in another work group, so writing an answer over an input a
         * neighbour has not finished reading is a race whose result depends on the scheduler.
         */
        private const val FLOW_BINDING = 5

        /** The side of a work group, in invocations; the same figure is in both `local_size`s. */
        private const val WORK_GROUP_SIDE = 16

        /** How long a driver may take over one small compute shader before it is given up on. */
        private const val COMPILE_TIMEOUT_SECONDS = 30L

        /** Takes the shared offscreen context and compiles both passes, or says why it cannot. */
        fun createOrNull(): Result {
            val context = GlContext.ensure()
            val device = context.device
                ?: return Result(null, context.unavailableBecause ?: "unknown failure")

            val sheet = GpuIceSheet(device)
            val compiled = GlContext.run(
                "Compiling the ice sheet profile",
                timeoutSeconds = COMPILE_TIMEOUT_SECONDS
            ) {
                sheet.profileProgram = GlContext.compileCompute(PROFILE_SOURCE)
                sheet.flowProgram = GlContext.compileCompute(FLOW_SOURCE)
                sheet.profileProgram != 0 && sheet.flowProgram != 0
            }
            if (compiled != true) return Result(null, "the ice sheet shaders would not compile")
            return Result(sheet, null)
        }

        /**
         * The thickness: the dome's surface over this cell, less the bed, and never less than
         * nothing.
         *
         * `IceSheet.surfaceMetres` in one line, with the margin's own bed floored at the waterline
         * for the reason that function gives. `precise` throughout, so a driver free to fuse the
         * multiply and add does not walk away from the reference at the third decimal.
         */
        private val PROFILE_SOURCE = """
            #version 430
            layout(local_size_x = 16, local_size_y = 16) in;

            layout(std430, binding = 0) readonly buffer Margin { float marginKm[]; };
            layout(std430, binding = 1) readonly buffer Nearest { int nearest[]; };
            layout(std430, binding = 2) readonly buffer Bed { float bed[]; };
            layout(std430, binding = 3) readonly buffer Sheet { uint onTheSheet[]; };
            layout(std430, binding = 4) writeonly buffer Out { float thickness[]; };

            uniform int uWidth;
            uniform int uHeight;
            uniform float uMetresPerRootKm;
            uniform float uMetresPerFieldUnit;
            uniform float uCellSpanKm;

            void main() {
                int x = int(gl_GlobalInvocationID.x);
                int y = int(gl_GlobalInvocationID.y);
                if (x >= uWidth || y >= uHeight) return;
                int cell = y * uWidth + x;
                if (onTheSheet[cell] == 0u) { thickness[cell] = 0.0; return; }

                // The mean of the plastic curve over the cell, not its value at the cell's
                // middle: `IceSheet.profileMetres`, with the roots factored out as that function
                // spells them, which is what keeps the two within a float's own precision.
                float far = marginKm[cell];
                precise float profile = 0.0;
                if (far > 0.0) {
                    float near = max(far - uCellSpanKm, 0.0);
                    precise float rootFar = sqrt(far);
                    precise float rootNear = sqrt(near);
                    precise float mean =
                        (near + rootNear * rootFar + far) / (rootNear + rootFar);
                    profile = (2.0 / 3.0) * uMetresPerRootKm * mean;
                }
                int from = nearest[cell];
                precise float marginBed =
                    from < 0 ? 0.0 : max(bed[from] * uMetresPerFieldUnit, 0.0);
                precise float surface = marginBed + profile;
                thickness[cell] = max(surface - bed[cell] * uMetresPerFieldUnit, 0.0);
            }
        """.trimIndent()

        /**
         * The flow: the neighbour of the eight that the ice *surface* falls to fastest per unit of
         * ground walked, and -1 where none of them is lower.
         *
         * `IceSheet.steepestDescent`, including its tie-break to the lower cell index, which is
         * not a detail: a tie here decides a bearing, and a bearing decides where a trough goes.
         */
        private val FLOW_SOURCE = """
            #version 430
            layout(local_size_x = 16, local_size_y = 16) in;

            layout(std430, binding = 2) readonly buffer Bed { float bed[]; };
            layout(std430, binding = 3) readonly buffer Sheet { uint onTheSheet[]; };
            layout(std430, binding = 4) readonly buffer Out { float thickness[]; };
            layout(std430, binding = 5) writeonly buffer Flow { int receiver[]; };

            uniform int uWidth;
            uniform int uHeight;
            uniform float uMetresPerFieldUnit;
            uniform float uRowScale;

            float surfaceAt(int cell) {
                return bed[cell] + thickness[cell] / uMetresPerFieldUnit;
            }

            void main() {
                int x = int(gl_GlobalInvocationID.x);
                int y = int(gl_GlobalInvocationID.y);
                if (x >= uWidth || y >= uHeight) return;
                int cell = y * uWidth + x;
                if (onTheSheet[cell] == 0u) { receiver[cell] = -1; return; }

                float here = surfaceAt(cell);
                int best = -1;
                float bestGradient = 0.0;
                for (int rowStep = -1; rowStep <= 1; ++rowStep) {
                    int ny = y + rowStep;
                    if (ny < 0 || ny >= uHeight) continue;
                    for (int columnStep = -1; columnStep <= 1; ++columnStep) {
                        if (rowStep == 0 && columnStep == 0) continue;
                        int nx = (x + columnStep + uWidth) % uWidth;
                        int neighbour = ny * uWidth + nx;
                        precise float fall = here - surfaceAt(neighbour);
                        if (fall <= 0.0) continue;
                        float across = float(columnStep);
                        float down = float(rowStep) * uRowScale;
                        precise float walked = sqrt(across * across + down * down);
                        precise float gradient = fall / walked;
                        if (gradient > bestGradient ||
                            (gradient == bestGradient && neighbour < best)) {
                            bestGradient = gradient;
                            best = neighbour;
                        }
                    }
                }
                receiver[cell] = best;
            }
        """.trimIndent()
    }
}
