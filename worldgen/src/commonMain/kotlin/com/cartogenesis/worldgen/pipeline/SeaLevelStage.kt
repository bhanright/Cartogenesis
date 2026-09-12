package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.math.JumpFloodDistance
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

    /**
     * The cut, optionally taken at a stand [lowstand] of the land's relief below where the
     * percentile puts it.
     *
     * That second argument is the whole of H5 on the erosion side. The hydraulic rounds call this
     * once per round to find the base level they grade to, and handing them a lower one is what
     * lets a valley continue below today's shoreline: see [com.cartogenesis.worldgen.model.SeaConfig.lowstand].
     * At zero the arithmetic is what it always was, to the last bit.
     */
    fun apply(height: FloatField, seaLevel: Float, lowstand: Float = 0f): SeaLevelResult {
        val fraction = seaLevel.coerceIn(0f, 1f)
        val cut = percentile(height, fraction)
        val maxHeight = height.max()
        // Relief measured from the cut to the highest ground, which is `landRange` below: the same
        // unit `HydraulicErosion` holds its own rates in, so a stand of 1.5% means the same
        // fraction of the same thing at every grid.
        val threshold =
            if (lowstand > 0f) cut - lowstand * (maxHeight - cut).coerceAtLeast(1e-6f) else cut

        return cutAt(height, threshold, maxHeight)
    }

    /** Land, water and the shoreline-relative field, for a shoreline already decided. */
    private fun cutAt(height: FloatField, threshold: Float, maxHeight: Float): SeaLevelResult {
        val size = height.data.size
        val isLand = BooleanArray(size)
        val relative = FloatField(height.width, height.height)

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
        // Today's stand, always: the lowstand belongs to the rounds that carved the terrain this
        // is cutting, not to the map that is drawn.
        val base =
            if (sea.enclosedSeaIsLand) enclose(apply(height, seaLevel), height, sea)
            else apply(height, seaLevel)
        if (sea.shelfWidth <= 0f) return base

        val w = base.relativeElevation.width
        val h = base.relativeElevation.height
        val land = base.isLand

        // Euclidean distance to the nearest land cell, by jump flooding: the three bands below are
        // read straight off it, so the shelf break is one of this field's iso-contours and used to
        // inherit the octagon the chamfer transform's contours are. See [JumpFloodDistance].
        val dist = FloatArray(w * h) { JumpFloodDistance.INFINITE }
        val label = IntArray(w * h) { -1 }
        for (i in 0 until w * h) {
            if (land[i]) {
                dist[i] = 0f
                label[i] = i
            }
        }
        if (base.landCellCount > 0) JumpFloodDistance.run(w, h, dist, label)

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
     * Water the ocean cannot reach is not sea.
     *
     * The percentile cut is a statement about the height field and nothing else, so every hollow
     * below it comes out as ocean whether or not a drop of ocean could get there. Near a coast the
     * hydraulic rounds leave a great many such hollows one cell across, and a D8 river ends at the
     * first one it meets: measured with deposition switched off entirely, so that no delta could be
     * blamed for it, a third of every seed's river mouths ended in one, and seed 59758 at 2048
     * carried 475 separate bodies of water outside the ocean.
     *
     * What this does is the smallest thing that can be true: label the water, find the ocean, and
     * mark everything else land at the height it already stands at. Nothing is raised, nothing is
     * moved, and no coastline the ocean actually touches changes by a cell. Where the water goes
     * next is the river stage's business — the depression fill raises each of these hollows to its
     * lowest outlet and the water balance decides whether it keeps a lake or dries to a playa,
     * which is the right way round: a lake below sea level is a real landform (the Caspian is 28 m
     * down, the Dead Sea 430) and the generator now has a way to produce one.
     *
     * Two details that are not incidental:
     *
     *  - **Eight-connectivity, wrapping in x.** Every neighbour walk in this generator is the
     *    eight-cell one and the map is a cylinder, so this has to be as well. It also decides the
     *    answer for the case that made the earlier attempt at this fail: E4's rift gulfs hang off
     *    the ocean through sills that can be a single cell wide, and under four-connectivity a
     *    diagonal sill would read as closed and the whole chain of gulfs would be turned into
     *    lakes.
     *  - **The ocean is the largest body, not the one on some edge.** The map has no edge in x and
     *    its poles are land as often as not.
     *
     * The cut itself is left exactly where the percentile puts it, and the alternative was written
     * and reverted. Marking this water as land raises `landCellCount` by 3.2% of the map before the
     * lowstand and 4.6% after it (seed 42 at 512), so `PipelineTest`'s land-fraction promise can be
     * kept by solving for the rank at which the *ocean* covers what the slider asks for rather than
     * reading it off the histogram: a cheap fixed-point loop, since the ocean's size is monotone in
     * the rank. It works on that number and wrecks the map. A deeper cut drowns the low ground the
     * segmented rift keeps between its half-grabens: on seed 59758 `RiftSegmentationTest` went from
     * four bodies of water and five land bridges to one body, no bridges and a corridor flooded end
     * to end — the canal E4 exists to break up, arriving by a different door. The promise is instead
     * restated where it is measured: the water this marks as land is still water, so what the slider
     * governs is the land that is not under standing water, and `PipelineTest` says so.
     *
     * A converted cell's [SeaLevelResult.relativeElevation] is renormalised into the land's units —
     * negative, since it is below the shoreline, but scaled by the same `landRange` its new
     * neighbours are. Leaving it in the sea's units would have made the hollow look several times
     * deeper or shallower than it is to the depression fill, which compares it against those
     * neighbours.
     */
    private fun enclose(base: SeaLevelResult, height: FloatField, sea: SeaConfig): SeaLevelResult {
        val w = height.width
        val h = height.height
        val size = w * h
        val water = size - base.landCellCount
        if (water == 0) return base

        // Body id per water cell, -1 on land. One flood fill per body over an explicit stack: the
        // largest body is most of the map and recursion would not survive it.
        val body = IntArray(size) { -1 }
        val stack = IntArray(water)
        val cells = ArrayList<Int>()
        var bodies = 0
        var ocean = -1
        var oceanCells = 0
        for (start in 0 until size) {
            if (base.isLand[start] || body[start] >= 0) continue
            val id = bodies++
            var top = 0
            body[start] = id
            stack[top++] = start
            var found = 0
            while (top > 0) {
                val c = stack[--top]
                found++
                FlowRouting.forEachNeighbour(w, h, c % w, c / w) { n ->
                    if (!base.isLand[n] && body[n] < 0) {
                        body[n] = id
                        stack[top++] = n
                    }
                }
            }
            cells.add(found)
            if (found > oceanCells) {
                oceanCells = found
                ocean = id
            }
        }
        if (bodies <= 1) return base

        // Anything bigger than the largest lake Earth has is a sea, whatever the connectivity says.
        // See [SeaConfig.enclosedSeaMaxShare], which carries the measurements this cap comes from.
        val cap = (size * sea.enclosedSeaMaxShare).toInt()
        val isLand = base.isLand.copyOf()
        val relative = base.relativeElevation.copy()
        val landRange = (height.max() - base.threshold).coerceAtLeast(1e-6f)
        var landCells = base.landCellCount
        for (i in 0 until size) {
            val id = body[i]
            if (id < 0 || id == ocean || cells[id] > cap) continue
            isLand[i] = true
            landCells++
            relative.data[i] = (height.data[i] - base.threshold) / landRange
        }

        return SeaLevelResult(base.threshold, isLand, relative, landCells)
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
    private fun percentile(height: FloatField, fraction: Float): Float =
        thresholdAtRank(height, (height.data.size * fraction).toLong())

    /** The same, addressed by a cell count rather than by a fraction. */
    private fun thresholdAtRank(height: FloatField, target: Long): Float {
        val lo = height.min()
        val hi = height.max()
        if (hi - lo <= 0f) return lo

        val scale = (BINS - 1) / (hi - lo)
        fun binOf(value: Float) = ((value - lo) * scale).toInt().coerceIn(0, BINS - 1)

        val histogram = IntArray(BINS)
        for (v in height.data) histogram[binOf(v)]++

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
