package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.math.DistanceTransform
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.SeaConfig

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

    /**
     * The continental shelf, remapped onto the ocean floor *after* the percentile cut above has
     * already decided the coastline.
     *
     * Shaping the shelf earlier — as an extra depression on oceanic crust in [PlateStage], before
     * [percentile] ever runs — was tried first and reverted: any depth strong enough to read on
     * the map also moved the threshold, so it reshuffled which cells were land, closing straits
     * into land bridges and merging landmasses that should have stayed apart. Working from the
     * coastline the cut has already fixed, and touching only cells [SeaLevelResult.isLand] already
     * marked as water, gets the same shallow margin without moving a single land cell.
     *
     * Three bands, keyed on distance-to-land in cells:
     *  - Out to `shelfWidth`: a shallow plateau, linearly lerped from -0.02 right at the coast to
     *    `-shelfDepth` at the plateau's outer edge. Both ends sit shallower than the -0.12 cut
     *    [ClimateStage] uses for `SHALLOW_OCEAN`, so the whole plateau reads as shallow water,
     *    with a gentle slope across it rather than a dead-flat shelf.
     *  - From `shelfWidth` to `2 * shelfWidth`: a smoothstep back down from `-shelfDepth` to
     *    whatever the *original*, unshelved depth at that cell already was — the continental
     *    slope, meeting the plateau on one side and the natural sea floor on the other.
     *  - Beyond `2 * shelfWidth`: untouched. The natural sea floor is deep enough on its own once
     *    clear of the coast (see `ContinentalShelfTest`'s `shelfWidth = 0` control); this only
     *    needed to fix the margin, not the abyss.
     */
    fun apply(height: FloatField, seaLevel: Float, sea: SeaConfig): SeaLevelResult {
        val base = apply(height, seaLevel)
        if (sea.shelfWidth <= 0f) return base

        val w = base.relativeElevation.width
        val h = base.relativeElevation.height
        val land = base.isLand

        val dist = FloatArray(w * h) { DistanceTransform.INFINITE }
        val label = IntArray(w * h) { -1 }
        for (i in 0 until w * h) {
            if (land[i]) {
                dist[i] = 0f
                label[i] = i
            }
        }
        if (base.landCellCount > 0) DistanceTransform.run(w, h, dist, label)

        val width = sea.shelfWidth
        val plateauFloor = -sea.shelfDepth
        val coastDepth = -0.02f
        val remapped = FloatField(w, h)
        base.relativeElevation.data.copyInto(remapped.data)

        for (i in 0 until w * h) {
            if (land[i]) continue
            val d = dist[i]
            val original = base.relativeElevation.data[i]
            remapped.data[i] = when {
                d <= width -> {
                    val t = (d / width).coerceIn(0f, 1f)
                    coastDepth + t * (plateauFloor - coastDepth)
                }
                d <= 2f * width -> {
                    val t = ((d - width) / width).coerceIn(0f, 1f)
                    val smooth = t * t * (3f - 2f * t)
                    plateauFloor + smooth * (original - plateauFloor)
                }
                else -> original
            }
        }

        return base.copy(relativeElevation = remapped)
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
