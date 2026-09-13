package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.math.JumpFloodDistance
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.SeaConfig
import com.cartogenesis.worldgen.model.WorldGenConfig

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

    /**
     * Land, water and the shoreline-relative field, for a shoreline already decided.
     *
     * [minHeight] and [maxHeight] are handed in rather than measured, so that the post-cut outlet
     * pass can re-cut a field it has just lowered a few sill cells in and get, for every cell it
     * did not touch, the same float it got the first time. Both ends of the range are properties
     * of the world the cut was taken from, not of the working copy.
     */
    private fun cutAt(
        height: FloatField,
        threshold: Float,
        maxHeight: Float,
        minHeight: Float = height.min()
    ): SeaLevelResult {
        val size = height.data.size
        val isLand = BooleanArray(size)
        val relative = FloatField(height.width, height.height)

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
     * The whole cut: the percentile, the two rules that decide which water is sea, the grading the
     * waves have done since the sea stopped rising, and the continental shelf under all of it.
     *
     * In that order, and the order is the argument. The percentile decides the coastline; [enclose]
     * and [drainDrownedBasins] decide which of the water below it the ocean can actually reach;
     * [LittoralGrading] moves the shoreline itself, so it has to run before anything is measured
     * from it; and the shelf remap is measured from it and touches only water, so it runs last.
     *
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
    fun apply(height: FloatField, config: WorldGenConfig): SeaLevelResult {
        val sea = config.sea
        // Today's stand, always: the lowstand belongs to the rounds that carved the terrain this
        // is cutting, not to the map that is drawn.
        val cut = apply(height, config.seaLevel)
        val enclosed = if (sea.enclosedSeaIsLand) enclose(cut, height, sea) else cut
        // H5b: and the basins the line above just turned into land get their outlets cut, once,
        // now that there is a shoreline for them to be measured against. See [drainDrownedBasins].
        val drained =
            if (sea.enclosedSeaIsLand && sea.postCutOutlet) {
                drainDrownedBasins(enclosed, height, config)
            } else {
                enclosed
            }
        // And then the six thousand years since the sea stopped rising, in which the waves grade
        // the coasts that are low enough to be graded and leave the rest alone. See
        // [LittoralGrading]; it runs here because everything downstream reads the mask, and before
        // the shelf below because the shelf is measured from the coastline this leaves.
        // The enclosure rule is not run again over what it leaves: the grading only ever turns water
        // into land, and never a cell whose filling would cut the water around it in two, so no body
        // of water can be enclosed by it. See `LittoralGrading.severs`, and `LittoralCoastTest`,
        // which counts the bodies the ocean cannot reach on both sides of the pass.
        val base = LittoralGrading.apply(
            drained,
            sea,
            landRelief = height.max() - drained.threshold,
            seaRelief = drained.threshold - height.min()
        )
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
     * The most times the outlet of a converted basin is cut, after the cut.
     *
     * A ceiling rather than a count: the loop stops as soon as a pass finds nothing left to cut,
     * which on most seeds is well inside it. What the ceiling is for is the case that does not stop
     * quickly — a sill standing high above the shoreline, which the outflow takes down by one
     * stream-power bite per pass exactly as a knickpoint retreats over successive floods.
     *
     * Eight, from where the retreat stops rather than from where any guard turns green. Measured on
     * seed 718106 at 512, the largest drowned basin's filled area over the passes runs 1883, 1195,
     * 985, 838, 663, 515, 405, 366, 366 cells, its surface coming down from 0.227 of the land's
     * relief above the shoreline to 0.018 — flat from the seventh, and a ninth moves neither
     * figure. Seed 99's largest goes 1486 cells to 141 in a single pass, because its sill has the
     * power to reach the waterline and the basin becomes an arm of the sea, and then to 88 and no
     * further. Seed 43's does not move at all, because its outflow cannot cut its sill, which is
     * the Caspian's own situation and the case this rule exists to leave alone.
     *
     * Eight rather than the twelve rounds the notch gets inside the hydraulic pass, for a reason of
     * cost and not of principle: each pass is a priority flood and a D8 route over the whole grid,
     * and four more would buy nothing the curve above says is left to buy.
     */
    private const val POST_CUT_PASSES = 8

    /**
     * Cuts the outlet of every basin the enclosure rule just made, on the far side of the cut.
     *
     * [enclose] hands the river stage a hollow whose floor lies below sea level and whose rim is
     * ordinary land, and the depression fill then raises the hollow to that rim — which can be a
     * great deal wider than the water that was there, because the ground around a coastal saucer is
     * low. On seed 718106 at 512 the result was a lake covering 0.62% of the land, two and a half
     * times the Caspian's share of Earth's, and at 2048 a Caspian-shaped lake filling a coastal
     * rift trough. Neither mechanism that sizes the other lakes can reach it. E1's notch runs
     * inside the hydraulic rounds, while that ground is still under the provisional sea: there is
     * no lip for it to cut and no outflow to cut with. E2's water balance cannot drain a floor that
     * is already below sea level, because there is nowhere for the water to go.
     *
     * So this is E1's breach again, on the same terms — [FlowRouting.spillways] over the filled
     * surface, stream power with `ErosionConfig.outletIncisionRatio` and the basin's whole
     * catchment as the discharge, drop limits in shoreline-relative units converted to the height
     * field's own once at the point of cutting — with two differences, both of which follow from
     * *where* it is running rather than from a change of mind about the physics.
     *
     *  - **Only the drowned basins.** A basin whose floor stands above the cut is E1's, was worked
     *    by twelve rounds of the notch, and is none of this pass's business; cutting it again here
     *    would drain the world's ordinary lakes a thirteenth time. The test is the basin's own
     *    floor: below the shoreline, and it is one of the ones the enclosure made.
     *  - **The notch may reach the waterline.** Inside the rounds the cut stops at the sea, which
     *    is the base level a river grades to. Here the water behind the sill stands *below* the
     *    sea and the river crossing the sill is grading to that, so the sea is not the floor — the
     *    basin's own is. Where the outflow has the power to take the sill under the waterline, the
     *    sill becomes water, the basin joins the ocean at the next labelling, and what the map
     *    shows is an arm of the sea with a narrow mouth: a sound, a ria, the Bosphorus and the
     *    Black Sea behind it. Where it has not, the sill stands lower than it did and the basin
     *    keeps whatever the water balance then allows — a lake below sea level, which is the
     *    Caspian, the Dead Sea and the Qattara.
     *
     * The terrain the cut makes lives in [SeaLevelResult.relativeElevation] and not in
     * `ErosionResult.height`, which this stage is handed and must not rewrite: erosion is a stage
     * of its own with its own reuse guard and its own section in a save, and a stage that edited
     * its predecessor's result would be recomputed away the next time anything upstream changed.
     * That is the same seam [GlaciationStage] carves its troughs through, and the relative field is
     * what the renderer, the climate and the river stage all read. The height field is used here
     * only as the working copy the drop limits are spent against, so that a rate written per unit
     * of the land's relief means the same thing at every grid.
     *
     * What the notch takes leaves the model, as the closing breach's spoil does and for the same
     * reason: there is no walk left to carry it downstream, the sediment ledger belongs to the
     * hydraulic rounds, and this runs two stages after they closed. `DepositionTest`'s budget is
     * measured over those rounds and is untouched by anything here.
     *
     * Deterministic, and re-runnable: the pass reads only the height field and the config, so
     * `WorldGenerationEngine`'s stage reuse gets the same answer as a fresh generation
     * (`IncrementalReuseTest` varies the sea section, which is where the switch lives).
     */
    private fun drainDrownedBasins(
        enclosed: SeaLevelResult,
        height: FloatField,
        config: WorldGenConfig
    ): SeaLevelResult {
        val w = height.width
        val h = height.height
        val threshold = enclosed.threshold
        // Both ends of the world's own range, so that re-cutting the working copy leaves every
        // untouched cell on the float it already had.
        val maxHeight = height.max()
        val minHeight = height.min()
        val landRange = (maxHeight - threshold).coerceAtLeast(1e-6f)

        var current = enclosed
        var working: FloatField? = null
        repeat(POST_CUT_PASSES) {
            if (current.landCellCount == 0) return current
            // A copy, because the breach lowers the surface it is handed along with the terrain and
            // the next pass takes its own from the re-cut.
            val relative = current.relativeElevation.copy()
            val isLand = current.isLand
            val filled = FlowRouting.fillDepressions(w, h, isLand, relative)
            val directions = FlowRouting.flowDirections(w, h, isLand, relative, filled)
            val area = FlowRouting.accumulate(
                w, h, isLand, filled, directions, current.landCellCount
            ) { 1f }
            val notch = FlowRouting.spillways(
                w, h, isLand, relative.data, filled.data, directions, HydraulicErosion.POND_DEPTH
            )

            // Everything standing clear of the cut belongs to the notch inside the rounds. Marking
            // the spill rather than filtering the arrays keeps this one specific set of basins
            // rather than a renumbering of them.
            var drowned = 0
            for (b in 0 until notch.count) {
                if (notch.floor[b] >= 0f) notch.spill[b] = -1
                else if (notch.spill[b] >= 0) drowned++
            }
            if (drowned == 0) return current

            val field = working ?: height.copy().also { working = it }
            val cut = HydraulicErosion.breach(
                config.erosion, w, notch, isLand, relative.data, filled.data, directions,
                area.data, current.landCellCount.toFloat(), landRange, field.data,
                settled = null, load = null, belowSea = true
            )
            // Nothing left that the outflow can take off a sill: every basin still here is one the
            // water cannot open, and another pass would only cost a priority flood.
            if (cut.cells == 0) return current
            current = enclose(
                cutAt(field, threshold, maxHeight, minHeight), field, config.sea
            )
        }
        return current
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
