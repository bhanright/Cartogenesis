package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.DeltaFan
import com.cartogenesis.worldgen.pipeline.DepositionLog
import com.cartogenesis.worldgen.pipeline.FlowRouting
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.SeaLevelStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import com.cartogenesis.worldgen.pipeline.erodeBlocking
import com.cartogenesis.worldgen.pipeline.growFan
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * E5: a delta is a fan, and its outline follows the distance from the mouth, not the grid.
 *
 * The author, looking at seed 718106 at 2048: *"some of these deltas and features in your example
 * are too square and artificial looking."* At the mouth of a rift valley his crop shows a lobe that
 * is a geometrically perfect half-disc and, beside it, two flat rafts of new land with dead-straight
 * edges and right-angle corners, one with the river running across it.
 *
 * The measurement that found the cause is [`which mechanism makes which shape`]: with the
 * mechanism of every raised cell recorded, the two artefacts turn out to be two different bugs in
 * the same line of reasoning. `fan` enumerated cells breadth-first and handed the acceptance rule
 * the *step count* of that walk as though it were a distance; over eight neighbours a step count is
 * the Chebyshev metric, whose iso-lines are squares.
 *
 *  - The **lacustrine fan** accepts "any ponded cell" and never consults the distance at all, so it
 *    took the whole `2R+1` square around its inflow. That is the raft, and at 2048 it is a
 *    forty-nine-cell square.
 *  - The **sea lobe** does shape itself, by a cosine of the angle to its trunk — but it compares
 *    that Euclidean shape against the Chebyshev count, which divides the reach by
 *    `max(|cos φ|, |sin φ|)`. That is the half-disc, with its corners pulled out along the
 *    diagonals. Its only irregularity was a per-cell hash, one cell deep, which nobody can see at
 *    2048 and which twelve rounds of rebuilding average away.
 *  - The **floodplain and alluvial-fan** case is not a fan walk at all: it lays material on the one
 *    cell the drainage order is standing on. It makes no squares and is not touched here.
 *
 * Each guard below is measured against `deltaOutline = false`, which is the code as E1 left it.
 */
class DeltaOutlineTest {

    private val seeds = listOf(718106L, 59758L)

    /**
     * The author's own settings, at a size the per-merge tier can afford. The artefacts were seen
     * at 2048; `deltaReach` is scaled by `atResolution`, so the same lobe is six cells across here
     * and twenty-four there, and every figure below is expressed against the reach rather than
     * against the cell.
     */
    private fun config(seed: Long, size: Int = 512): WorldGenConfig {
        val base = WorldGenConfig(seed = seed, width = 512, height = 512, seaLevel = 0.62f)
        val authored = base.copy(
            tectonics = base.tectonics.copy(plateCount = 14),
            nations = base.nations.copy(nationCount = 12)
        )
        return if (size == 512) authored else authored.atResolution(size, size)
    }

    private class Run(config: WorldGenConfig) {
        val seed = config.seed
        val w = config.width
        val h = config.height
        val reach = config.erosion.deltaReach
        val log = DepositionLog(w * h)
        val height: FloatField
        val isLand: BooleanArray

        init {
            val uplift = PlateStage.generate(config, TerrainStage.generate(config)).height
            height = erodeBlocking(config, uplift, log).height
            isLand = SeaLevelStage.apply(height, config.seaLevel).isLand
        }

        fun mask(mechanism: Byte) = BooleanArray(w * h) { log.mechanism[it] == mechanism }
    }

    // ------------------------------------------------------------------ measurement and squares

    /**
     * The measurement the chunk was dispatched to make, and the guard that came out of it.
     *
     * **No grid squares.** Over the perimeter of everything one mechanism raised — every unit edge
     * between a raised cell and a cell it did not raise — the share that lies in a straight
     * axis-aligned run longer than the lobe's own reach must be under the bar. The reach is half a
     * lobe's width, so the bar asks that no delta have a straight coast longer than a quarter of
     * its own span, which is already generous: Earth's deltas have none at all outside the
     * artificial ones.
     */
    @Test
    fun `which mechanism makes which shape`() {
        var worstLobe = 0.0
        var worstFan = 0.0
        var controlWorst = 0.0
        seeds.forEach { seed ->
            val square = Run(config(seed).let { it.copy(erosion = it.erosion.copy(deltaOutline = false)) })
            val curved = Run(config(seed))
            listOf("square" to square, "curved" to curved).forEach { (name, run) ->
                val lobe = straightness(run, run.mask(DepositionLog.SEA_LOBE))
                val fan = straightness(run, run.mask(DepositionLog.LAKE_FAN))
                val plain = run.log.mechanism.count { it == DepositionLog.FLOODPLAIN }
                println(
                    ("E5 %s seed %d %s: sea lobe %d cells, perimeter %d, %.1f%% in runs > %d " +
                        "(longest %d); lake fan %d cells, perimeter %d, %.1f%% in runs (longest " +
                        "%d); floodplain %d cells").format(
                        name, seed, "${run.w}", lobe.cells, lobe.perimeter, lobe.share * 100,
                        runLimit(run.reach), lobe.longest, fan.cells, fan.perimeter, fan.share * 100,
                        fan.longest, plain
                    )
                )
                if (name == "square") {
                    controlWorst = maxOf(controlWorst, lobe.share, fan.share)
                } else {
                    worstLobe = maxOf(worstLobe, lobe.share)
                    worstFan = maxOf(worstFan, fan.share)
                }
            }
        }
        println(
            "E5 straight runs: worst share of perimeter in a run longer than the reach — " +
                "control %.1f%%, sea lobes %.1f%%, lake fans %.1f%%"
                .format(controlWorst * 100, worstLobe * 100, worstFan * 100)
        )
        assertTrue(
            controlWorst > STRAIGHT_BAR,
            "the square-fan control was expected to fail this guard and its worst share was " +
                "${controlWorst * 100}%, under the ${STRAIGHT_BAR * 100}% bar, so the guard " +
                "proves nothing"
        )
        assertTrue(
            worstLobe <= STRAIGHT_BAR && worstFan <= STRAIGHT_BAR,
            "sea lobes put ${worstLobe * 100}% and lake fans ${worstFan * 100}% of their " +
                "perimeter in straight grid-axis runs longer than one lobe reach, over the " +
                "${STRAIGHT_BAR * 100}% bar"
        )
    }

    /**
     * The share of a mask's perimeter that lies in a straight run longer than one lobe reach.
     *
     * A perimeter is counted in unit edges rather than in cells, because a cell on a diagonal
     * staircase has two boundary edges and a cell on a straight coast has one, and it is the edges
     * that are straight or not. Horizontal edges run in x and vertical edges in y; a run is a
     * maximal set of collinear consecutive edges on the same side of the mask.
     */
    private fun straightness(run: Run, mask: BooleanArray): Straight {
        val w = run.w
        val h = run.h
        val cells = mask.count { it }
        if (cells == 0) return Straight(0, 0, 0.0, 0)
        var perimeter = 0
        var inRuns = 0
        var longest = 0
        val limit = runLimit(run.reach)

        // Horizontal edges: for each row boundary and each side, walk the columns.
        for (y in 0 until h) {
            for (side in 0..1) {
                val dy = if (side == 0) -1 else 1
                var length = 0
                // One extra column, wrapping, so a run that crosses the seam is not cut in two.
                for (step in 0..w) {
                    val x = step % w
                    val c = y * w + x
                    val ny = y + dy
                    val open = mask[c] &&
                        (ny < 0 || ny >= h || !mask[ny * w + x])
                    if (open && step < w) {
                        length++
                        perimeter++
                    } else {
                        if (length > limit) inRuns += length
                        if (length > longest) longest = length
                        length = 0
                    }
                }
            }
        }
        // Vertical edges: for each column boundary and each side, walk the rows.
        for (x in 0 until w) {
            for (side in 0..1) {
                val dx = if (side == 0) -1 else 1
                var length = 0
                for (y in 0..h) {
                    val open = if (y >= h) {
                        false
                    } else {
                        var nx = (x + dx) % w
                        if (nx < 0) nx += w
                        mask[y * w + x] && !mask[y * w + nx]
                    }
                    if (open) {
                        length++
                        perimeter++
                    } else {
                        if (length > limit) inRuns += length
                        if (length > longest) longest = length
                        length = 0
                    }
                }
            }
        }
        return Straight(
            cells, perimeter,
            if (perimeter == 0) 0.0 else inRuns.toDouble() / perimeter, longest
        )
    }

    /**
     * The longest straight grid-axis run a fan is allowed before it counts against it.
     *
     * One lobe reach — half a lobe's width — except that a *smooth* curve rasterised onto a square
     * grid has straight runs of its own: a circle of radius R runs flat across its top for about
     * `2·sqrt(2R)` cells, which is where its curve deviates by less than half a cell. At the 2048
     * the artefact was found at, where the reach is twenty-four, that is fourteen cells and the
     * reach is the binding limit; at the 512 this test can afford it is seven against a reach of
     * six, and measuring a rasterised circle as a square would be measuring the grid rather than
     * the fan. So the limit is the larger of the two.
     */
    private fun runLimit(reach: Int): Int {
        val raster = kotlin.math.ceil(2.0 * sqrt(2.0 * reach)).toInt()
        return maxOf(reach, raster)
    }

    private class Straight(
        val cells: Int,
        val perimeter: Int,
        val share: Double,
        val longest: Int
    )

    // ------------------------------------------------------------------------- discs and harmonics

    /**
     * **No perfect discs.** Each lobe's rim, measured as a radius about its own apex, has to vary
     * round the compass and it has to vary in something other than its first harmonic.
     *
     * Both halves are needed and neither on its own would do. A half-disc has a constant radius
     * over its sector, so it fails the max-against-min ratio. A cosine lobe — which is exactly what
     * the old `lobeReach` drew — varies by a factor of two and a half between its nose and its
     * flanks, and would sail through a ratio test while still being a shape drawn with a compass.
     *
     * The plan's second clause was "its harmonic content is not all in the zeroth and first order",
     * and that measurement was written, run, and **does not discriminate**: the reach is a
     * *rectified* cosine — `max(cos θ, 0)`, because a lobe may not reach a negative distance behind
     * itself — and half-wave rectification puts a great deal of energy into the even harmonics all
     * by itself. Measured over twenty-four mouths on open water, a rim with the wobble switched off
     * leaves 15.7% of its radius above the first harmonic and a rim with the wobble on leaves
     * 12.1%: the control scores *higher* than the thing it is meant to be worse than. The figure is
     * still printed below, because a measurement that cannot tell two things apart is worth saying
     * out loud once.
     *
     * What does discriminate is the same idea stated where the rectification cannot reach it: every
     * shape a compass can draw about an axis — a disc, a cosine lobe, a rectified cosine lobe — is
     * the same on both sides of that axis. So the second measurement finds the lobe's own axis from
     * its first harmonic, reflects the rim in it, and asks how far the two sides differ. A coast
     * differs; a compass does not.
     */
    @Test
    fun `a lobe is not a disc and not a cosine`() {
        // On open water, so what is measured is the outline the code draws and not where the
        // coastline happened to stop it. A lobe in a world is clipped on three sides by the land it
        // is growing out of, and a radius measured into the land is a measurement of the land.
        val w = 128
        val h = 128
        val apex = 64 * w + 64
        val reach = 24f
        // Three outlines, two of them the failures this guard exists to catch. The disc is a rim
        // with no direction and no wobble, which is what a lobe about a mouth on a grid axis came
        // out as; the cosine is the rim the old `lobeReach` drew, in Euclidean form — its whole
        // shape is a0 + a1 cos, so nothing of it lies above the first harmonic.
        val shapes = mapOf(
            "shaped" to Triple(1f, 0f, DeltaFan.WOBBLE),
            "cosine lobe" to Triple(1f, 0f, 0f),
            "half-disc" to Triple(0f, 0f, 0f)
        )
        val ratio = HashMap<String, Double>()
        val residual = HashMap<String, Double>()
        val asymmetry = HashMap<String, Double>()
        repeat(TRIALS) { trial ->
            val hash = DeltaFan.hash(4242L, trial)
            shapes.forEach { (name, form) ->
                val (outX, outY, wobble) = form
                val sediment = FloatArray(w * h)
                val scratch = DeltaFan.Scratch(w * h, reach.toInt())
                val rim = DeltaFan.Rim(
                    apex = apex, width = w, reach = reach, outX = outX, outY = outY,
                    hash = hash, grooved = false, wobble = wobble
                )
                growFan(
                    w, h, budget = 1e9, rim = rim, scratch = scratch, id = trial + 1,
                    surfaceOf = FloatArray(w * h) { -1f }, sediment = sediment,
                    settled = FloatArray(w * h), toRelative = 1f, wholeCells = false,
                    log = null,
                    mark = DepositionLog.SEA_LOBE,
                    accepts = { true }, advance = { 1f }, levelOf = { _, _ -> 0f }
                )
                val shape = rimOf(w, h, apex, BooleanArray(w * h) { sediment[it] > 0f })
                    ?: return@forEach
                // The worst case of each, which for the shaped lobe is its smallest figure and for
                // a control is its largest: a control that failed only on average would not be one.
                if (name == "shaped") {
                    ratio[name] = minOf(ratio[name] ?: Double.MAX_VALUE, shape.ratio)
                    residual[name] = minOf(residual[name] ?: Double.MAX_VALUE, shape.residual)
                    asymmetry[name] = minOf(asymmetry[name] ?: Double.MAX_VALUE, shape.asymmetry)
                } else {
                    ratio[name] = maxOf(ratio[name] ?: 0.0, shape.ratio)
                    residual[name] = maxOf(residual[name] ?: 0.0, shape.residual)
                    asymmetry[name] = maxOf(asymmetry[name] ?: 0.0, shape.asymmetry)
                }
            }
        }
        shapes.keys.sorted().forEach { name ->
            println(
                ("E5 rim over %d mouths on open water, %s: max/min %.2f, %.1f%% above the first " +
                    "harmonic, %.1f%% asymmetric about its own axis").format(
                    TRIALS, name, ratio.getValue(name), residual.getValue(name) * 100,
                    asymmetry.getValue(name) * 100
                )
            )
        }
        assertTrue(
            ratio.getValue("half-disc") < RATIO_BAR,
            "the half-disc control was expected to fail the ratio half of this guard and its rim " +
                "varied by ${ratio.getValue("half-disc")}, over the $RATIO_BAR bar"
        )
        assertTrue(
            asymmetry.getValue("cosine lobe") < ASYMMETRY_BAR &&
                asymmetry.getValue("half-disc") < ASYMMETRY_BAR,
            "the two compass-drawn controls were expected to fail the asymmetry half of this " +
                "guard and measured ${asymmetry.getValue("cosine lobe") * 100}% and " +
                "${asymmetry.getValue("half-disc") * 100}%, over the ${ASYMMETRY_BAR * 100}% bar"
        )
        assertTrue(
            ratio.getValue("shaped") >= RATIO_BAR,
            "a lobe's rim varies by only ${ratio.getValue("shaped")} between its longest and " +
                "shortest radius, under the $RATIO_BAR bar: that is a shape drawn with a compass"
        )
        assertTrue(
            asymmetry.getValue("shaped") >= ASYMMETRY_BAR,
            "a lobe's rim differs by only ${asymmetry.getValue("shaped") * 100}% between its two " +
                "sides, under the ${ASYMMETRY_BAR * 100}% bar: a disc, a cosine lobe and a " +
                "rectified cosine lobe all measure zero here"
        )
    }

    /**
     * The same measurement on the lobes of a real world, where it is a report rather than a guard.
     *
     * Reported and not asserted because a lobe in a world is clipped: it grows out of a coast, so
     * the radius in three directions out of four is the distance to the land behind it and not the
     * rim the code drew. Restricting the bins to rim cells that face open water removes most of
     * that, but not all of it — a lobe growing into a bay is bounded by the bay — and what is left
     * is a measurement of the coastline as much as of the fan. The guard above measures the same
     * two numbers where nothing else can get at them.
     */
    @Test
    fun `lobe rims in a world`() {
        seeds.forEach { seed ->
            val square = Run(config(seed).let { it.copy(erosion = it.erosion.copy(deltaOutline = false)) })
            val curved = Run(config(seed))
            listOf("square" to square, "curved" to curved).forEach { (name, run) ->
                val shapes = rims(run)
                println(
                    ("E5 %s seed %d: %d lobes with a measurable rim, median max/min %.2f, median " +
                        "residual above the first harmonic %.1f%%").format(
                        name, seed, shapes.size, median(shapes.map { it.ratio }),
                        median(shapes.map { it.residual }) * 100
                    )
                )
            }
        }
    }

    /**
     * One lobe's rim: how much it varies, how much of it is not a disc plus a cosine, and how
     * different its two sides are.
     */
    private class RimShape(
        val cells: Int,
        val ratio: Double,
        val residual: Double,
        val asymmetry: Double
    )

    private fun rims(run: Run): List<RimShape> {
        val w = run.w
        val h = run.h
        val mask = run.mask(DepositionLog.SEA_LOBE)
        // Grouped by the apex the log recorded, not by connected component. Two mouths a few cells
        // apart grow lobes that touch, and a rim measured about one apex over a blob that belongs
        // to three of them measures the blob and not the outline. Ordered by cell index throughout,
        // so nothing here depends on a hash iteration order.
        val byApex = sortedMapOf<Int, MutableList<Int>>()
        for (c in 0 until w * h) {
            if (!mask[c]) continue
            val a = run.log.apex[c]
            if (a < 0) continue
            byApex.getOrPut(a) { ArrayList() }.add(c)
        }
        val out = ArrayList<RimShape>()
        for ((apex, members) in byApex) {
            // Big enough that a rim can be measured round it at all: fewer cells than this and the
            // angular bins are mostly empty and the numbers are noise rather than shape.
            if (members.size < 2 * run.reach) continue
            val group = BooleanArray(w * h)
            members.forEach { group[it] = true }
            // Only the edge that faces open water. The edge that faces the land the lobe grew out
            // of is the coastline's shape and not the fan's.
            val free = BooleanArray(w * h)
            members.forEach { c ->
                neighbours(w, h, c) { n -> if (!group[n] && !run.isLand[n]) free[c] = true }
            }
            out.add(rimOf(w, h, apex, free) ?: continue)
        }
        return out
    }

    /**
     * The rim of one set of cells about one apex: how much the radius varies round the compass, and
     * how much of it is left over once a disc and a cosine have been fitted to it.
     *
     * The residual is the discriminating half. A half-disc has one radius over its sector and a
     * cosine lobe has `a₀ + a₁cos θ` exactly, so both leave nothing; a rim with harmonics in it
     * leaves them.
     */
    private fun rimOf(w: Int, h: Int, apex: Int, rim: BooleanArray): RimShape? {
        val ax = apex % w
        val ay = apex / w
        val radius = DoubleArray(BINS) { -1.0 }
        var cells = 0
        for (c in 0 until w * h) {
            if (!rim[c]) continue
            cells++
            val dx = wrap(c % w - ax, w).toDouble()
            val dy = (c / w - ay).toDouble()
            val r = sqrt(dx * dx + dy * dy)
            if (r <= 0.0) continue
            // atan2 only to choose a bucket; nothing the world depends on is computed from it.
            var a = kotlin.math.atan2(dy, dx) / (2 * Math.PI)
            if (a < 0) a += 1.0
            val bin = (a * BINS).toInt().coerceIn(0, BINS - 1)
            if (r > radius[bin]) radius[bin] = r
        }
        val filled = (0 until BINS).filter { radius[it] > 0.0 }
        if (filled.size < BINS / 4) return null
        val ratio = radius.filter { it > 0.0 }.let { it.max() / it.min() }

        // Least squares for a0 + a1 cos t + b1 sin t over the occupied bins. Three unknowns, so
        // the normal equations are a 3x3 solve and Cramer's rule is the whole of it.
        var s00 = 0.0; var s0c = 0.0; var s0s = 0.0
        var scc = 0.0; var scs = 0.0; var sss = 0.0
        var y0 = 0.0; var yc = 0.0; var ys = 0.0
        filled.forEach { bin ->
            val t = 2 * Math.PI * (bin + 0.5) / BINS
            val c = kotlin.math.cos(t)
            val s = kotlin.math.sin(t)
            val r = radius[bin]
            s00 += 1.0; s0c += c; s0s += s
            scc += c * c; scs += c * s; sss += s * s
            y0 += r; yc += r * c; ys += r * s
        }
        val fit = solve3(
            arrayOf(
                doubleArrayOf(s00, s0c, s0s),
                doubleArrayOf(s0c, scc, scs),
                doubleArrayOf(s0s, scs, sss)
            ),
            doubleArrayOf(y0, yc, ys)
        ) ?: return null
        var sq = 0.0
        var mean = 0.0
        filled.forEach { bin ->
            val t = 2 * Math.PI * (bin + 0.5) / BINS
            val model = fit[0] + fit[1] * kotlin.math.cos(t) + fit[2] * kotlin.math.sin(t)
            val e = radius[bin] - model
            sq += e * e
            mean += radius[bin]
        }
        mean /= filled.size
        val residual = if (mean <= 0.0) 0.0 else sqrt(sq / filled.size) / mean

        // How different the two sides of the lobe are.
        //
        // The axis is the direction of the fitted first harmonic — for a lobe that is the way its
        // trunk was pointing — snapped to the nearest half-bin, which makes the reflection map bin
        // centres exactly onto bin centres and keeps the discretisation out of the answer. Every
        // shape a compass can draw about an axis is the same on both sides of it: a disc, a cosine
        // lobe, a rectified cosine lobe. A coast is not.
        val axis = kotlin.math.atan2(fit[2], fit[1])
        val k = Math.round(axis / (Math.PI / BINS)).toInt()
        var asq = 0.0
        var pairs = 0
        filled.forEach { bin ->
            var mirror = (k - bin - 1) % BINS
            if (mirror < 0) mirror += BINS
            if (radius[mirror] <= 0.0) return@forEach
            val d = radius[bin] - radius[mirror]
            asq += d * d
            pairs++
        }
        val asymmetry =
            if (pairs < BINS / 4 || mean <= 0.0) 0.0 else sqrt(asq / pairs) / mean
        return RimShape(cells, ratio, residual, asymmetry)
    }

    // ------------------------------------------------------------------------------ depth bending

    /**
     * **Depth bends the outline.** A synthetic coast with a shelf on one side of the mouth and deep
     * water on the other: the lobe has to reach further over the shelf.
     *
     * Driven straight at the fan walk rather than through a world, because a world cannot be asked
     * to put a shelf on one side of a river and a trench on the other. Twenty-four mouths with
     * different hashes, because one mouth's harmonic wobble can make either side the longer on its
     * own; the control is the same twenty-four walks with the depth term switched off, which is
     * what the breadth-first walk did — it never looked at the water at all.
     */
    @Test
    fun `a fan reaches further over a shelf than into deep water`() {
        val w = 96
        val h = 96
        val apexX = 32
        val apexY = 48
        val reach = 24f
        val shelf = 0.2f
        val deep = 6f

        var bentSum = 0.0
        var flatSum = 0.0
        var bentWorst = Double.MAX_VALUE
        repeat(TRIALS) { trial ->
            val hash = DeltaFan.hash(9001L, trial)
            listOf(true, false).forEach { bent ->
                val surface = FloatArray(w * h)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        surface[y * w + x] = if (y < apexY) -shelf else -deep
                    }
                }
                val sediment = FloatArray(w * h)
                val settled = FloatArray(w * h)
                val scratch = DeltaFan.Scratch(w * h, reach.toInt())
                val rim = DeltaFan.Rim(
                    apex = apexY * w + apexX, width = w, reach = reach,
                    outX = 1f, outY = 0f, hash = hash, grooved = false
                )
                growFan(
                    w, h, budget = 1e9, rim = rim, scratch = scratch, id = trial + 1,
                    surfaceOf = surface, sediment = sediment, settled = settled,
                    toRelative = 1f, wholeCells = false, log = null,
                    mark = DepositionLog.SEA_LOBE,
                    accepts = { true },
                    advance = { c -> if (bent) 1f - surface[c] else 1f },
                    levelOf = { _, _ -> 0f }
                )
                var overShelf = 0.0
                var overDeep = 0.0
                for (c in 0 until w * h) {
                    if (sediment[c] <= 0f) continue
                    val dx = (c % w - apexX).toDouble()
                    val dy = (c / w - apexY).toDouble()
                    val r = sqrt(dx * dx + dy * dy)
                    if (dy < 0) overShelf = maxOf(overShelf, r) else if (dy > 0) overDeep = maxOf(overDeep, r)
                }
                val ratio = overShelf / overDeep.coerceAtLeast(1e-9)
                if (bent) {
                    bentSum += ratio
                    bentWorst = minOf(bentWorst, ratio)
                } else {
                    flatSum += ratio
                }
            }
        }
        val bent = bentSum / TRIALS
        val flat = flatSum / TRIALS
        println(
            ("E5 depth bending over %d mouths: shelf reach against deep-water reach %.2fx with the " +
                "depth term, %.2fx without it (worst single mouth %.2fx)")
                .format(TRIALS, bent, flat, bentWorst)
        )
        assertTrue(
            flat < DEPTH_BAR,
            "the depth-blind control was expected to fail this guard and reached ${flat}x further " +
                "over the shelf, over the ${DEPTH_BAR}x bar"
        )
        assertTrue(
            bent >= DEPTH_BAR,
            "the fan reached only ${bent}x further over a shelf than into water thirty times as " +
                "deep, under the ${DEPTH_BAR}x bar"
        )
    }

    // -------------------------------------------------------------------- area against catchment

    /**
     * Reported, not asserted: how a lobe's area grows with the catchment behind it.
     *
     * Syvitski and Saito, *Global and Planetary Change* 57 (2007) 261-282, regress the subaerial
     * area of the world's deltas against the drainage area of the river that built each one and
     * find the relation strongly positive and sublinear — a river with ten times the basin does not
     * get ten times the delta, because what a delta is made of is the sediment load, which itself
     * grows sublinearly with basin area (their `Qs` relation), and because a bigger river reaches
     * deeper water. What is checked here is the exponent of a least-squares fit of log(lobe area)
     * against log(catchment) over every lobe on the two seeds: it should be positive and it should
     * be under one. Nothing is asserted on the value, because the sample is two worlds.
     */
    @Test
    fun `lobe area against catchment`() {
        seeds.forEach { seed ->
            val run = Run(config(seed))
            val points = lobeAreas(run)
            if (points.size < 4) {
                println("E5 area/catchment seed $seed: ${points.size} lobes, too few to fit")
                return@forEach
            }
            var sx = 0.0; var sy = 0.0
            points.forEach { sx += ln(it.first); sy += ln(it.second) }
            val mx = sx / points.size
            val my = sy / points.size
            var num = 0.0
            var den = 0.0
            points.forEach {
                val dx = ln(it.first) - mx
                num += dx * (ln(it.second) - my)
                den += dx * dx
            }
            val exponent = if (den == 0.0) 0.0 else num / den
            println(
                ("E5 area/catchment seed %d: %d lobes, catchment %.4f%%-%.4f%% of land, area " +
                    "%d-%d cells, log-log exponent %.2f (Syvitski & Saito 2007: positive and " +
                    "sublinear)").format(
                    seed, points.size, points.minOf { it.first } * 100,
                    points.maxOf { it.first } * 100, points.minOf { it.second }.toInt(),
                    points.maxOf { it.second }.toInt(), exponent
                )
            )
        }
    }

    /** Each lobe's catchment as a share of the land, against its area in cells. */
    private fun lobeAreas(run: Run): List<Pair<Double, Double>> {
        val w = run.w
        val h = run.h
        val mask = run.mask(DepositionLog.SEA_LOBE)
        val sea = SeaLevelStage.apply(run.height, 0.62f)
        val filled = FlowRouting.fillDepressions(w, h, sea.isLand, sea.relativeElevation)
        val flow =
            FlowRouting.flowDirections(w, h, sea.isLand, sea.relativeElevation, filled, run.seed)
        val area = FlowRouting.accumulate(w, h, sea.isLand, filled, flow, sea.landCellCount) { 1f }
        val land = sea.landCellCount.toFloat()

        val component = IntArray(w * h) { -1 }
        val stack = IntArray(w * h)
        val out = ArrayList<Pair<Double, Double>>()
        var id = 0
        for (start in 0 until w * h) {
            if (!mask[start] || component[start] >= 0) continue
            var top = 0
            stack[top++] = start
            component[start] = id
            var cells = 0
            var catchment = 0f
            while (top > 0) {
                val c = stack[--top]
                cells++
                neighbours(w, h, c) { n ->
                    if (mask[n] && component[n] < 0) {
                        component[n] = id
                        stack[top++] = n
                    }
                    // The biggest river arriving anywhere on the lobe is the one that built it.
                    if (sea.isLand[n] && !mask[n] && area.data[n] > catchment) catchment = area.data[n]
                }
            }
            id++
            if (cells >= 4 && catchment > 0f) out.add((catchment / land).toDouble() to cells.toDouble())
        }
        return out
    }

    // --------------------------------------------------------------------------------- machinery

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        return if (sorted.isEmpty()) 0.0 else sorted[sorted.size / 2]
    }

    private fun solve3(m: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
        val det = determinant(m)
        if (abs(det) < 1e-9) return null
        val out = DoubleArray(3)
        for (col in 0 until 3) {
            val c = Array(3) { r -> m[r].copyOf() }
            for (r in 0 until 3) c[r][col] = rhs[r]
            out[col] = determinant(c) / det
        }
        return out
    }

    private fun determinant(m: Array<DoubleArray>): Double =
        m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
            m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
            m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])

    private fun wrap(dx: Int, w: Int): Int = when {
        dx > w / 2 -> dx - w
        dx < -w / 2 -> dx + w
        else -> dx
    }

    private inline fun neighbours(w: Int, h: Int, cell: Int, action: (Int) -> Unit) {
        val x = cell % w
        val y = cell / w
        for (dy in -1..1) {
            val ny = y + dy
            if (ny < 0 || ny >= h) continue
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                var nx = (x + dx) % w
                if (nx < 0) nx += w
                action(ny * w + nx)
            }
        }
    }

    private companion object {
        /** Angular buckets a rim is measured in. */
        const val BINS = 32

        /**
         * The most of a fan's perimeter that may lie in straight grid-axis runs longer than one
         * lobe reach — which is half a lobe's width, so this is asking that no delta have a
         * straight coast longer than a quarter of its own span.
         *
         * What a real delta has is none at all, so the bar is set between the two measured
         * populations rather than from a figure in the world: at 512 the square fans put 2.0% and
         * 1.8% of their perimeter in such runs and the curved ones put 0.7% and 0.0%, and 1.2% is
         * about the geometric midpoint — 1.7 times under the worst square and 1.7 times over the
         * worst curve. The gap is far wider at the grid the author was looking at, where the square
         * a lacustrine fan takes is forty-nine cells on a side rather than thirteen: see the
         * ledger's 2048 figures.
         */
        const val STRAIGHT_BAR = 0.012

        /** The least a rim's longest radius may exceed its shortest. */
        const val RATIO_BAR = 1.5

        /**
         * The least a rim's two sides, reflected in its own axis, must differ by, as a share of its
         * mean radius.
         *
         * Set between the two measured populations rather than picked. Over twenty-four mouths on
         * open water the compass-drawn controls measure at most 3.6% — all of it the rasterising of
         * a smooth curve onto a square grid, since both shapes are exactly symmetric — and the
         * shaped rim measures at least 10.2%. The bar is the geometric midpoint of those two, 1.7
         * times the worst a compass can manage and 1.7 times under the worst a coast does.
         */
        const val ASYMMETRY_BAR = 0.06

        /** How much further over a shelf than into deep water. */
        const val DEPTH_BAR = 1.5

        /** Mouths measured in the synthetic depth test. */
        const val TRIALS = 24
    }
}
