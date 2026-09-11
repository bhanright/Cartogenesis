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

    /**
     * Bins used to *bracket* the sea-level percentile before it is resolved exactly; see
     * [percentile]. The width of a bin no longer decides the answer, only how many cells the
     * second pass has to look at.
     */
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

    /**
     * The height below which [fraction] of the world lies — resolved exactly, not to the nearest
     * histogram bin.
     *
     * The histogram only brackets the answer. Taking the bracketing bin's lower edge as the
     * threshold, as this did before, floods only the cells below that bin and leaves every cell
     * *inside* it above water, so the land fraction overshoots by whatever share of the map the bin
     * holds — and that share is not small. Measured on seed 42 at 128, the bin the sea level falls
     * in holds 2.4% of the map with crust-pair profiles and 4.8% without: a lump of ocean floor at
     * a near-uniform depth, which is exactly the sort of place a sea-level cut lands. Whether the
     * slider came out accurate was therefore luck of where the target fell inside that lump —
     * `PipelineTest` asks it to be good to 2% of the map and had been passing on 0.708 against
     * 0.700 — and with the crust-pair profiles the luck ran out at 0.721. A second pass over the
     * few hundred cells of the one bracketing bin picks the cut that puts exactly the right number
     * of cells below it: one extra scan, an array a few thousand floats long, and 0.700 on the
     * nose with the profiles on or off.
     */
    private fun percentile(height: FloatField, fraction: Float): Float {
        val lo = height.min()
        val hi = height.max()
        if (hi - lo <= 0f) return lo

        val scale = (BINS - 1) / (hi - lo)
        fun binOf(value: Float) = ((value - lo) * scale).toInt().coerceIn(0, BINS - 1)

        val histogram = IntArray(BINS)
        for (v in height.data) histogram[binOf(v)]++

        val target = (height.data.size * fraction).toLong()
        if (target <= 0L) return lo

        var below = 0L
        var bracket = BINS - 1
        for (bin in 0 until BINS) {
            if (below + histogram[bin] >= target) {
                bracket = bin
                break
            }
            below += histogram[bin]
        }

        val bucket = FloatArray(histogram[bracket])
        if (bucket.isEmpty()) return hi
        var n = 0
        for (v in height.data) {
            if (binOf(v) == bracket) bucket[n++] = v
        }
        bucket.sort()
        // Everything strictly below the returned value is sea, so the value wanted is the one with
        // exactly `target` cells beneath it: `below` of them under the bracket, the rest inside it.
        val index = (target - below).toInt().coerceIn(0, bucket.size - 1)
        return bucket[index]
    }
}
