package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.FloatField

data class SeaLevelResult(
    /** The raw height value that the shoreline sits at. */
    val threshold: Float,
    val isLand: BooleanArray,
    /**
     * Elevation relative to the shoreline: 0..1 above sea level for land, -1..0 for water.
     * This is what climate, rivers and rendering all work from.
     */
    val relativeElevation: FloatField,
    val landCellCount: Int
)

/**
 * Step 3: flood everything below a chosen elevation percentile. The threshold comes from a
 * histogram rather than a sort so that moving the sea-level slider stays fast even at export
 * resolutions.
 */
object SeaLevelStage {

    private const val BINS = 4096

    fun apply(height: FloatField, seaLevel: Float): SeaLevelResult {
        val fraction = seaLevel.coerceIn(0f, 1f)
        val threshold = percentile(height, fraction)

        val size = height.data.size
        val isLand = BooleanArray(size)
        val relative = FloatField(height.width, height.height)

        val maxHeight = height.max()
        val minHeight = height.min()
        val landRange = (maxHeight - threshold).coerceAtLeast(1e-6f)
        val seaRange = (threshold - minHeight).coerceAtLeast(1e-6f)

        var landCells = 0
        for (i in 0 until size) {
            val v = height.data[i]
            if (v >= threshold) {
                isLand[i] = true
                landCells++
                relative.data[i] = (v - threshold) / landRange
            } else {
                relative.data[i] = (v - threshold) / seaRange
            }
        }

        return SeaLevelResult(threshold, isLand, relative, landCells)
    }

    private fun percentile(height: FloatField, fraction: Float): Float {
        val lo = height.min()
        val hi = height.max()
        if (hi - lo <= 0f) return lo

        val histogram = IntArray(BINS)
        val scale = (BINS - 1) / (hi - lo)
        for (v in height.data) {
            histogram[((v - lo) * scale).toInt().coerceIn(0, BINS - 1)]++
        }

        val target = (height.data.size * fraction).toLong()
        var cumulative = 0L
        for (bin in 0 until BINS) {
            cumulative += histogram[bin]
            if (cumulative >= target) {
                return lo + bin / scale
            }
        }
        return hi
    }
}
