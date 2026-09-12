package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.math.Fft2D
import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.noise.PerlinNoise
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * The random slope field the user thinks of as the "normal map": per-cell surface normals derived
 * from noise, stored as the two components of the slope rather than as the normal itself.
 *
 * Each field holds how much the surface rises per cell along that axis — [gradientX] eastward,
 * [gradientY] southward, in the same units the height field is in. [normalAt] turns the pair back
 * into a unit normal where a caller wants one; [TerrainStage.integrate] wants the gradients.
 */
class NormalField(val gradientX: FloatField, val gradientY: FloatField) {
    val width: Int get() = gradientX.width
    val height: Int get() = gradientX.height

    /** Unit surface normal at a cell, as (x, y, z) — the classic tangent-space normal. */
    fun normalAt(x: Int, y: Int): FloatArray {
        val normalX = -gradientX[x, y]
        val normalY = -gradientY[x, y]
        val length = sqrt(normalX * normalX + normalY * normalY + 1f)
        return floatArrayOf(normalX / length, normalY / length, 1f / length)
    }
}

/** What [TerrainStage] hands the rest of the pipeline. */
data class TerrainResult(
    /** The slope field the height below was integrated from, one entry per cell, row-major. */
    val normals: NormalField,
    /** Integrated elevation, normalised to 0..1 over the whole grid, row-major. */
    val height: FloatField
)

/**
 * Step 1 of the pipeline: build a random gradient field, then recover the height field it
 * describes.
 *
 * The two gradient components are independent noise, so the field is not conservative — it is not
 * the exact derivative of any surface. Frankot–Chellappa handles that by returning the surface
 * whose gradient is the closest least-squares match, which is why this produces smooth continuous
 * terrain rather than the streaking artifacts of naive row-by-row summation.
 */
object TerrainStage {

    /**
     * Radius of the box blur [smooth] blends the integrated surface toward, in cells.
     *
     * One, because the integration output is already smooth and this pass exists only to take the
     * last of the per-cell noise off it; a wider window would cost the terrain its fine relief for
     * nothing.
     */
    private const val SMOOTHING_RADIUS_CELLS = 1

    /** Cells in the [SMOOTHING_RADIUS_CELLS] window, which is what the running sum divides by. */
    private const val SMOOTHING_WINDOW_CELLS =
        (2 * SMOOTHING_RADIUS_CELLS + 1) * (2 * SMOOTHING_RADIUS_CELLS + 1)

    fun generate(config: WorldGenConfig): TerrainResult {
        val normals = buildNormalField(config)
        val height = integrate(normals)
        smooth(height, config.terrain.smoothing)
        return TerrainResult(normals, height.normalize())
    }

    /**
     * The southward slope's noise seed, derived from the world seed.
     *
     * The two axes must not share a stream: one field used for both would slope the same way east
     * and south at every cell, and integrate to a plane of diagonal corrugations rather than to
     * terrain. Any mix that decorrelates them would do; this is the conventional odd multiplier
     * and a small odd offset.
     */
    private fun southwardNoiseSeed(worldSeed: Long): Long = worldSeed * 31 + 17

    private fun buildNormalField(config: WorldGenConfig): NormalField {
        val terrain = config.terrain
        val cellsAcross = config.width
        val cellsDown = config.height

        val eastwardNoise = PerlinNoise(config.seed)
        val southwardNoise = PerlinNoise(southwardNoiseSeed(config.seed))

        // The noise lattice tiles this many times across the map, in both axes, which is what
        // makes the world wrap east to west; dividing by the grid turns a cell index into a
        // position on that lattice.
        val latticeCyclesAcrossMap = terrain.baseFrequency
        val latticeStepX = latticeCyclesAcrossMap.toFloat() / cellsAcross
        val latticeStepY = latticeCyclesAcrossMap.toFloat() / cellsDown

        // Filled row-band per core. fbm() keeps no state between calls, and each cell writes only
        // its own index, so this produces exactly the same field as a sequential fill.
        val eastwardGradient = FloatField(cellsAcross, cellsDown)
        val southwardGradient = FloatField(cellsAcross, cellsDown)
        parallelChunks(0, cellsDown) { startRow, endRow ->
            for (row in startRow until endRow) {
                var cell = row * cellsAcross
                for (column in 0 until cellsAcross) {
                    eastwardGradient.data[cell] = terrain.gradientStrength * eastwardNoise.fbm(
                        column * latticeStepX,
                        row * latticeStepY,
                        terrain.octaves,
                        latticeCyclesAcrossMap,
                        latticeCyclesAcrossMap,
                        terrain.lacunarity,
                        terrain.gain
                    )
                    southwardGradient.data[cell] = terrain.gradientStrength * southwardNoise.fbm(
                        column * latticeStepX,
                        row * latticeStepY,
                        terrain.octaves,
                        latticeCyclesAcrossMap,
                        latticeCyclesAcrossMap,
                        terrain.lacunarity,
                        terrain.gain
                    )
                    cell++
                }
            }
        }
        return NormalField(eastwardGradient, southwardGradient)
    }

    /**
     * Frankot–Chellappa integration: the surface whose gradient is the closest least-squares
     * match to [normals], as a height field in the same units the gradients are in.
     *
     * In the frequency domain that surface is
     *
     *     Z = -i (wx * P + wy * Q) / (wx^2 + wy^2),   Z(0, 0) = 0
     *
     * where P and Q are the two gradient components' spectra and wx, wy are angular frequencies in
     * radians per cell. Z is accumulated in place over P rather than into a third pair of buffers.
     * At export resolutions these arrays dominate the app's memory: six of them at 4096x4096 is
     * over 800MB, which no device will grant.
     */
    fun integrate(normals: NormalField): FloatField {
        val cellsAcross = normals.width
        val cellsDown = normals.height
        val cellCount = cellsAcross * cellsDown
        val fft = Fft2D(cellsAcross, cellsDown)

        // P and Q of the formula above, once the two transforms below have run.
        val eastwardSpectrumReal = DoubleArray(cellCount) { normals.gradientX.data[it].toDouble() }
        val eastwardSpectrumImaginary = DoubleArray(cellCount)
        val southwardSpectrumReal = DoubleArray(cellCount) { normals.gradientY.data[it].toDouble() }
        val southwardSpectrumImaginary = DoubleArray(cellCount)

        fft.forward(eastwardSpectrumReal, eastwardSpectrumImaginary)
        fft.forward(southwardSpectrumReal, southwardSpectrumImaginary)

        for (row in 0 until cellsDown) {
            // Frequencies above the Nyquist row stand for the negative frequencies below zero.
            val cyclesDown = if (row <= cellsDown / 2) row else row - cellsDown
            val radiansPerCellDown = 2.0 * PI * cyclesDown / cellsDown
            for (column in 0 until cellsAcross) {
                val cyclesAcross =
                    if (column <= cellsAcross / 2) column else column - cellsAcross
                val radiansPerCellAcross = 2.0 * PI * cyclesAcross / cellsAcross
                val angularMagnitudeSquared =
                    radiansPerCellAcross * radiansPerCellAcross +
                        radiansPerCellDown * radiansPerCellDown
                val cell = row * cellsAcross + column
                if (angularMagnitudeSquared == 0.0) {
                    // DC term: the arbitrary constant of integration.
                    eastwardSpectrumReal[cell] = 0.0
                    eastwardSpectrumImaginary[cell] = 0.0
                    continue
                }

                val projectedSlopeReal =
                    radiansPerCellAcross * eastwardSpectrumReal[cell] +
                        radiansPerCellDown * southwardSpectrumReal[cell]
                val projectedSlopeImaginary =
                    radiansPerCellAcross * eastwardSpectrumImaginary[cell] +
                        radiansPerCellDown * southwardSpectrumImaginary[cell]
                // Z, written back over P: multiplying by -i swaps the parts and negates the new
                // imaginary one. From here the eastward pair holds the surface, not the slope.
                eastwardSpectrumReal[cell] = projectedSlopeImaginary / angularMagnitudeSquared
                eastwardSpectrumImaginary[cell] = -projectedSlopeReal / angularMagnitudeSquared
            }
        }

        fft.inverse(eastwardSpectrumReal, eastwardSpectrumImaginary)
        return FloatField(
            cellsAcross,
            cellsDown,
            FloatArray(cellCount) { eastwardSpectrumReal[it].toFloat() }
        )
    }

    /**
     * Blends [field] toward a box-blurred copy of itself, in place.
     *
     * [amount] is the share of the blurred copy in the result, clamped to 0..1: 0 leaves the field
     * untouched, 1 replaces it outright. Cheap, and the integration output is already smooth.
     */
    private fun smooth(field: FloatField, amount: Float) {
        if (amount <= 0f) return
        val blurred = FloatField(field.width, field.height)
        for (row in 0 until field.height) {
            for (column in 0 until field.width) {
                var sum = 0f
                for (offsetY in -SMOOTHING_RADIUS_CELLS..SMOOTHING_RADIUS_CELLS) {
                    for (offsetX in -SMOOTHING_RADIUS_CELLS..SMOOTHING_RADIUS_CELLS) {
                        sum += field.sample(column + offsetX, row + offsetY)
                    }
                }
                blurred[column, row] = sum / SMOOTHING_WINDOW_CELLS.toFloat()
            }
        }
        val blurredShare = amount.coerceIn(0f, 1f)
        for (cell in field.data.indices) {
            field.data[cell] =
                field.data[cell] * (1f - blurredShare) + blurred.data[cell] * blurredShare
        }
    }
}
