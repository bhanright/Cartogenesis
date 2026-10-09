package com.cartogenesis.desktop

import com.cartogenesis.worldgen.pipeline.AtmosphereAccelerator
import com.cartogenesis.worldgen.pipeline.AtmosphereRemap
import com.cartogenesis.worldgen.pipeline.DoubleFourierCoefficients
import org.lwjgl.opengl.GL43C
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Carries fields between the map's grid and the atmosphere's on the graphics card.
 *
 * The algorithms are the processor's ([AtmosphereRemap.areaMean] and [AtmosphereRemap.evaluate]).
 * Carrying down is one invocation per coarse cell, summing the ground cells it overlaps row by row
 * with the remap's own weights, in the processor's order. Carrying up is two passes: one invocation
 * per ground row and zonal wave sums the meridional series down the continued meridian, and one per
 * ground cell sums the zonal waves along its row. The processor uses fast transforms for both; the
 * card sums them directly, which is the same series read at the same points.
 *
 * Every angle is read from a table built on the processor in double precision, indexed by an exact
 * integer: the colatitude of ground row `t` is `pi (2t + 1) / 2H`, so wave `k` there turns by
 * `2 pi k (2t + 1) / 4H`, and the table's entry `k (2t + 1) mod 4H`; the longitudes likewise. A
 * device's own `cos` of a large argument is not held to anything, and the table is.
 *
 * It does not promise the processor's answer to the last bit: the card sums in single precision.
 */
class GpuAtmosphere private constructor(override val name: String) : AtmosphereAccelerator {

    /** What probing this machine found: an accelerator, or the reason there is not one. */
    class Result(val accelerator: GpuAtmosphere?, val unavailableBecause: String?)

    /** The three compiled kernels, or zero if they never compiled. Written once, on the probe. */
    private var areaMeanProgram = 0
    private var meridianProgram = 0
    private var rowProgram = 0

    override suspend fun areaMean(remap: AtmosphereRemap, ground: FloatArray): FloatArray? {
        val coarse = remap.coarse
        if (ground.size != remap.groundRows * remap.groundColumns) return null
        // Each coarse line's weights over its own total, so the kernel's sum is the mean.
        val columnSpans = spans(remap.columnOverlaps)
        val rowSpans = spans(remap.rowOverlaps)
        val columnWeights = normalized(remap.columnOverlaps)
        val rowWeights = normalized(remap.rowOverlaps)
        return GlContext.run("Carrying a field to the atmosphere") {
            if (areaMeanProgram == 0) return@run null
            val buffers = IntArray(6)
            GL43C.glGenBuffers(buffers)
            try {
                upload(buffers[0], ground)
                upload(buffers[1], columnSpans)
                upload(buffers[2], columnWeights)
                upload(buffers[3], rowSpans)
                upload(buffers[4], rowWeights)
                upload(buffers[5], FloatArray(coarse.cellCount))
                if (GL43C.glGetError() != GL43C.GL_NO_ERROR) return@run null
                for (binding in buffers.indices) GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, buffers[binding])
                GL43C.glUseProgram(areaMeanProgram)
                GL43C.glUniform1i(GL43C.glGetUniformLocation(areaMeanProgram, "uGroundColumns"), remap.groundColumns)
                GL43C.glUniform1i(GL43C.glGetUniformLocation(areaMeanProgram, "uCoarseColumns"), coarse.columns)
                GL43C.glUniform1i(GL43C.glGetUniformLocation(areaMeanProgram, "uCoarseRows"), coarse.rows)
                GL43C.glDispatchCompute(groups(coarse.columns, WORK_GROUP_SIDE), groups(coarse.rows, WORK_GROUP_SIDE), 1)
                GL43C.glMemoryBarrier(GL43C.GL_BUFFER_UPDATE_BARRIER_BIT)
                val result = FloatArray(coarse.cellCount)
                GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buffers[5])
                GL43C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0L, result)
                if (GL43C.glGetError() == GL43C.GL_NO_ERROR) result else null
            } finally {
                GL43C.glDeleteBuffers(buffers)
            }
        }
    }

    override suspend fun toGround(remap: AtmosphereRemap, coefficients: DoubleFourierCoefficients): FloatArray? {
        val groundRows = remap.groundRows
        val groundColumns = remap.groundColumns
        val zonalCount = coefficients.zonalCount
        val interleaved = FloatArray(2 * coefficients.real.size)
        for (index in coefficients.real.indices) {
            interleaved[2 * index] = coefficients.real[index].toFloat()
            interleaved[2 * index + 1] = coefficients.imaginary[index].toFloat()
        }
        val meridianTable = turnTable(4 * groundRows)
        val rowTable = turnTable(2 * groundColumns)
        return GlContext.run("Carrying a field up from the atmosphere") {
            if (meridianProgram == 0 || rowProgram == 0) return@run null
            val buffers = IntArray(5)
            GL43C.glGenBuffers(buffers)
            try {
                upload(buffers[0], interleaved)
                upload(buffers[1], meridianTable)
                upload(buffers[2], FloatArray(2 * groundRows * zonalCount))
                upload(buffers[3], rowTable)
                upload(buffers[4], FloatArray(groundRows * groundColumns))
                if (GL43C.glGetError() != GL43C.GL_NO_ERROR) return@run null
                for (binding in buffers.indices) GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, buffers[binding])

                GL43C.glUseProgram(meridianProgram)
                GL43C.glUniform1i(GL43C.glGetUniformLocation(meridianProgram, "uZonalCount"), zonalCount)
                GL43C.glUniform1i(GL43C.glGetUniformLocation(meridianProgram, "uCoarseRows"), coefficients.coarseRows)
                GL43C.glUniform1i(GL43C.glGetUniformLocation(meridianProgram, "uGroundRows"), groundRows)
                GL43C.glDispatchCompute(groups(groundRows * zonalCount, LINE_GROUP_SIZE), 1, 1)
                // The row pass reads what every meridian invocation wrote.
                GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT)

                GL43C.glUseProgram(rowProgram)
                GL43C.glUniform1i(GL43C.glGetUniformLocation(rowProgram, "uZonalCount"), zonalCount)
                GL43C.glUniform1i(GL43C.glGetUniformLocation(rowProgram, "uCoarseColumns"), coefficients.coarseColumns)
                GL43C.glUniform1i(GL43C.glGetUniformLocation(rowProgram, "uGroundRows"), groundRows)
                GL43C.glUniform1i(GL43C.glGetUniformLocation(rowProgram, "uGroundColumns"), groundColumns)
                GL43C.glDispatchCompute(groups(groundColumns, WORK_GROUP_SIDE), groups(groundRows, WORK_GROUP_SIDE), 1)
                GL43C.glMemoryBarrier(GL43C.GL_BUFFER_UPDATE_BARRIER_BIT)

                val result = FloatArray(groundRows * groundColumns)
                GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buffers[4])
                GL43C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0L, result)
                if (GL43C.glGetError() == GL43C.GL_NO_ERROR) result else null
            } finally {
                GL43C.glDeleteBuffers(buffers)
            }
        }
    }

    private fun upload(buffer: Int, data: FloatArray) {
        GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buffer)
        GL43C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, data, GL43C.GL_DYNAMIC_DRAW)
    }

    private fun upload(buffer: Int, data: IntArray) {
        GL43C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, buffer)
        GL43C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, data, GL43C.GL_STATIC_DRAW)
    }

    companion object {
        /** A two-dimensional work group's side, as `GpuOcean`'s: 16 by 16 is 256, which every device allows. */
        private const val WORK_GROUP_SIDE = 16

        /** A one-dimensional work group's size: 256 again. */
        private const val LINE_GROUP_SIZE = 256

        /** How long a driver may take over one small compute shader before it is given up on. */
        private const val COMPILE_TIMEOUT_SECONDS = 30L

        private fun groups(count: Int, size: Int) = (count + size - 1) / size

        /** Each coarse line's first ground line, how many it overlaps and where its weights start, as an ivec4 apiece. */
        private fun spans(overlaps: AtmosphereRemap.Overlaps): IntArray {
            val spans = IntArray(4 * overlaps.first.size)
            for (line in overlaps.first.indices) {
                spans[4 * line] = overlaps.first[line]
                spans[4 * line + 1] = overlaps.count[line]
                spans[4 * line + 2] = overlaps.offset[line]
            }
            return spans
        }

        /** Each weight over its own coarse line's total, in double before it is rounded. */
        private fun normalized(overlaps: AtmosphereRemap.Overlaps): FloatArray {
            val weights = FloatArray(overlaps.weight.size)
            for (line in overlaps.first.indices) {
                for (index in 0 until overlaps.count[line]) {
                    val at = overlaps.offset[line] + index
                    weights[at] = (overlaps.weight[at] / overlaps.total[line]).toFloat()
                }
            }
            return weights
        }

        /** `cos` and `sin` of `2 pi j / period` for every j, interleaved, computed in double. */
        private fun turnTable(period: Int): FloatArray {
            val table = FloatArray(2 * period)
            for (index in 0 until period) {
                val angle = 2.0 * PI * index / period
                table[2 * index] = cos(angle).toFloat()
                table[2 * index + 1] = sin(angle).toFloat()
            }
            return table
        }

        /**
         * Takes the shared offscreen context and compiles the three kernels, or returns null with a
         * reason if this machine cannot offer what is needed.
         */
        fun createOrNull(): Result {
            val context = GlContext.ensure()
            val device = context.device
                ?: return Result(null, context.unavailableBecause ?: "unknown failure")
            val atmosphere = GpuAtmosphere(device)
            val compiled = GlContext.run("Compiling the atmosphere's remapping", timeoutSeconds = COMPILE_TIMEOUT_SECONDS) {
                atmosphere.areaMeanProgram = GlContext.compileCompute(AREA_MEAN_SOURCE)
                atmosphere.meridianProgram = GlContext.compileCompute(MERIDIAN_SOURCE)
                atmosphere.rowProgram = GlContext.compileCompute(ROW_SOURCE)
                true
            } ?: return Result(null, "the atmosphere's shaders would not compile")
            return if (compiled) Result(atmosphere, null) else Result(null, "the atmosphere's shaders would not compile")
        }

        /**
         * One coarse cell's area-weighted mean: along each overlapping ground row first, then down
         * the rows, the processor's order, with the weights already over their line's total.
         */
        private val AREA_MEAN_SOURCE = """
            #version 430
            layout(local_size_x = 16, local_size_y = 16) in;

            layout(std430, binding = 0) readonly buffer Ground { float ground[]; };
            layout(std430, binding = 1) readonly buffer ColumnSpans { ivec4 columnSpans[]; };
            layout(std430, binding = 2) readonly buffer ColumnWeights { float columnWeights[]; };
            layout(std430, binding = 3) readonly buffer RowSpans { ivec4 rowSpans[]; };
            layout(std430, binding = 4) readonly buffer RowWeights { float rowWeights[]; };
            layout(std430, binding = 5) writeonly buffer Coarse { float coarse[]; };

            uniform int uGroundColumns;
            uniform int uCoarseColumns;
            uniform int uCoarseRows;

            void main() {
                int column = int(gl_GlobalInvocationID.x);
                int row = int(gl_GlobalInvocationID.y);
                if (column >= uCoarseColumns || row >= uCoarseRows) return;
                ivec4 across = columnSpans[column];
                ivec4 down = rowSpans[row];
                precise float total = 0.0;
                for (int line = 0; line < down.y; line++) {
                    int groundRow = down.x + line;
                    precise float rowSum = 0.0;
                    for (int index = 0; index < across.y; index++) {
                        precise float term = ground[groundRow * uGroundColumns + across.x + index] * columnWeights[across.z + index];
                        rowSum = rowSum + term;
                    }
                    precise float weighted = rowSum * rowWeights[down.z + line];
                    total = total + weighted;
                }
                coarse[row * uCoarseColumns + column] = total;
            }
        """.trimIndent()

        /**
         * One ground row's value of one zonal wave: the meridional series summed over its waves
         * -J..J at the row's colatitude. The table index starts at wave -J's turn, made non-negative
         * without a modulus of a negative number, which GLSL leaves undefined.
         */
        private val MERIDIAN_SOURCE = """
            #version 430
            layout(local_size_x = 256) in;

            layout(std430, binding = 0) readonly buffer Coefficients { vec2 coefficients[]; };
            layout(std430, binding = 1) readonly buffer MeridianTable { vec2 meridianTable[]; };
            layout(std430, binding = 2) writeonly buffer Along { vec2 along[]; };

            uniform int uZonalCount;
            uniform int uCoarseRows;
            uniform int uGroundRows;

            void main() {
                int index = int(gl_GlobalInvocationID.x);
                if (index >= uGroundRows * uZonalCount) return;
                int groundRow = index / uZonalCount;
                int wave = index - groundRow * uZonalCount;
                int period = 4 * uGroundRows;
                int step = (2 * groundRow + 1) % period;
                int turn = (period - (uCoarseRows * step) % period) % period;
                precise vec2 total = vec2(0.0);
                for (int meridional = 0; meridional <= 2 * uCoarseRows; meridional++) {
                    vec2 coefficient = coefficients[meridional * uZonalCount + wave];
                    vec2 rotation = meridianTable[turn];
                    precise float real = coefficient.x * rotation.x - coefficient.y * rotation.y;
                    precise float imaginary = coefficient.x * rotation.y + coefficient.y * rotation.x;
                    total = total + vec2(real, imaginary);
                    turn = (turn + step) % period;
                }
                along[index] = total;
            }
        """.trimIndent()

        /**
         * One ground cell: the zonal waves summed along its row at its longitude, each wave's
         * weight in a real field's half spectrum as `DoubleFourierCoefficients.zonalWeight` gives it.
         */
        private val ROW_SOURCE = """
            #version 430
            layout(local_size_x = 16, local_size_y = 16) in;

            layout(std430, binding = 2) readonly buffer Along { vec2 along[]; };
            layout(std430, binding = 3) readonly buffer RowTable { vec2 rowTable[]; };
            layout(std430, binding = 4) writeonly buffer Ground { float ground[]; };

            uniform int uZonalCount;
            uniform int uCoarseColumns;
            uniform int uGroundRows;
            uniform int uGroundColumns;

            void main() {
                int column = int(gl_GlobalInvocationID.x);
                int row = int(gl_GlobalInvocationID.y);
                if (column >= uGroundColumns || row >= uGroundRows) return;
                int period = 2 * uGroundColumns;
                int step = 2 * column + 1;
                int turn = 0;
                precise float total = 0.0;
                for (int wave = 0; wave < uZonalCount; wave++) {
                    float weight = (wave == 0 || 2 * wave == uCoarseColumns) ? 1.0 : 2.0;
                    vec2 value = along[row * uZonalCount + wave];
                    vec2 rotation = rowTable[turn];
                    precise float real = value.x * rotation.x - value.y * rotation.y;
                    precise float term = weight * real;
                    total = total + term;
                    turn = (turn + step) % period;
                }
                ground[row * uGroundColumns + column] = total;
            }
        """.trimIndent()
    }
}
