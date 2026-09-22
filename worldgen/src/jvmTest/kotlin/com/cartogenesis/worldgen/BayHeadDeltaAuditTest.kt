package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.DepositionLog
import com.cartogenesis.worldgen.pipeline.ErosionStage
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import kotlinx.coroutines.runBlocking
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What deposition does to standing water on the author's world, at the grid he exports at.
 *
 * In the audit tier, with `GlaciationAuditTest` and `RealmIdRangeAuditTest`, for the reason those
 * are: it generates 2048 worlds, and erodes two of them a second time for the deposition log, and
 * the per-merge tier cannot afford that. `BayHeadDeltaTest` carries the same measurements over the
 * whole world at 1024.
 *
 * Three worlds of seed 718106: aggradation graded to the slope the river needs (E6's rule), the
 * ungraded rule before it, and no deposition at all. The case finds the window of the author's
 * own crop size where the ungraded world holds the most standing water that the no-deposition
 * world does not ([pondedWindow]): the control's own worst valley, chosen on the control, with
 * the graded world then measured in it. It asserts one thing, that the finder has a subject, and
 * prints the rest: the standing water in that window and over the whole world under all three
 * settings, the ringed water in the window (a finding since T3, because a long curved lake fits a
 * circular band), and for every lake the no-deposition world lacks, what deposition lies round
 * its shore, by mechanism ([shoreSpoilOfNewLakes]).
 *
 * Why the water is printed and not asserted: on this tree the deposited worlds hold more standing
 * water than the no-deposition world in that window under both rules alike, and most of the
 * lakes they make and the bare world lacks are ringed with lake-fan spoil rather than
 * floodplain spoil, which is not the graded rule's to move. What removed E6's moats was the unit
 * fix in `headroom`, a bug and not a switch, so the flag has no control that discriminates in the
 * count of standing water at 1024 or at 2048 as measured. The history of this case, the windows
 * each finder chose and the figures, are in docs/DESIGN_LEDGER.md, rows E6, T3 and T4; what the
 * fans leave is in docs/TODO.md.
 */
class BayHeadDeltaAuditTest {

    private fun config(graded: Boolean): WorldGenConfig {
        val base = WorldGenConfig(seed = 718106L, width = 512, height = 512, seaLevel = 0.62f)
        val authored = base.copy(
            tectonics = base.tectonics.copy(plateCount = 14),
            nations = base.nations.copy(nationCount = 12)
        ).atResolution(2048, 2048)
        return authored.copy(erosion = authored.erosion.copy(gradedAggradation = graded))
    }

    @Test
    fun `what deposition ponds on the author's world, and what lies round it`() {
        val now = WorldGenerationEngine.generateBlocking(config(true))
        val was = WorldGenerationEngine.generateBlocking(config(false))
        val bare = WorldGenerationEngine.generateBlocking(
            config(true).let { it.copy(erosion = it.erosion.copy(deposition = false)) }
        )

        // The window is chosen on the ungraded world, which is the one the artefact is in. Choosing
        // it on the graded world would be choosing the window with the least to find in it.
        val window = pondedWindow(was, bare)
        val wasRings = ringedWaterMask(was)
        val nowRings = ringedWaterMask(now)

        val wasRingCells = window.count(was) { wasRings[it] }
        val nowRingCells = window.count(now) { nowRings[it] }
        val wasWater = lakeCells(was, window)
        val nowWater = lakeCells(now, window)
        val bareWater = lakeCells(bare, window)
        println(
            ("E6 AUDIT 718106 at 2048, the window where the ungraded spoil ponds the most " +
                "water, %s: %d lake cells in a ring before, %d after; %d lake cells in all " +
                "before, %d after, against %d with no deposition; whole world %d before, %d " +
                "after, against %d")
                .format(
                    window, wasRingCells, nowRingCells, wasWater, nowWater, bareWater,
                    lakeCells(was), lakeCells(now), lakeCells(bare)
                )
        )

        // What lies round the shore of every lake deposition made, on both settings. The log lives
        // inside the erosion stage, so each world is eroded once more, from the same plates the
        // engine eroded it from, which comes out cell for cell the same (checked in the reader).
        listOf(
            "graded" to shoreSpoilOfNewLakes(now, bare, config(true)),
            "ungraded" to shoreSpoilOfNewLakes(was, bare, config(false))
        ).forEach { (setting, lakes) ->
            val totals = DoubleArray(4)
            lakes.forEach { lake -> for (m in 1..3) totals[m] += lake.laidByMechanismHeightUnits[m] }
            println(
                ("E6 SHORES 718106 at 2048, $setting: %d lakes the no-deposition world lacks, " +
                    "%d cells of water; gross deposition on their shores, in height units: " +
                    "sea lobe %.4f, lake fan %.4f, floodplain %.4f")
                    .format(lakes.size, lakes.sumOf { it.cells }, totals[1], totals[2], totals[3])
            )
            lakes.sortedByDescending { it.cells }.forEach { println("E6 SHORES   $it") }
        }

        // The one clause. Without a valley where the ungraded spoil ponds water the bare world
        // does not, the rest of this case is measuring nothing, and it should say so rather than
        // print a census of an empty set.
        assertTrue(
            wasWater > bareWater,
            "the ungraded control was expected to hold more standing water than the same valley " +
                "with no deposition at all and held $wasWater against $bareWater, so this case " +
                "has no subject"
        )
        val bothRinged = window.count(now) { wasRings[it] && nowRings[it] }
        println(
            ("E6 FINDING 718106 at 2048: %d cells of ringed standing water in the window before " +
                "and %d after, %d of them the same cells, so the ring count does not tell the two " +
                "settings apart on this world and is reported rather than asserted")
                .format(wasRingCells, nowRingCells, bothRinged)
        )
    }

    // ------------------------------------------------------------------ the shores

    /** A lake the no-deposition world lacks, and the gross deposition round its shore. */
    private class ShoreSpoil(
        val lakeId: Int,
        val cells: Int,
        /** Of [cells], how many are dry land on the no-deposition world. */
        val dryOnBareCells: Int,
        /** Land cells within [SHORE_REACH_CELLS] of the lake that are not themselves lake. */
        val shoreCells: Int,
        /**
         * Everything laid on the shore cells over every round, by mechanism, indexed by
         * `DepositionLog`'s marks; gross, in the height field's own units, before any cut took
         * it back off.
         */
        val laidByMechanismHeightUnits: DoubleArray
    ) {
        override fun toString() =
            ("lake %d, %d cells (%d dry on the no-deposition world), %d shore cells carrying " +
                "sea lobe %.4f, lake fan %.4f, floodplain %.4f").format(
                lakeId, cells, dryOnBareCells, shoreCells,
                laidByMechanismHeightUnits[DepositionLog.SEA_LOBE.toInt()],
                laidByMechanismHeightUnits[DepositionLog.LAKE_FAN.toInt()],
                laidByMechanismHeightUnits[DepositionLog.FLOODPLAIN.toInt()]
            )
    }

    /**
     * Every lake on [world] that is mostly dry land on [bare], with what the deposition log says
     * was laid round its shore, by mechanism.
     *
     * A history and not a provenance: the log is gross, summed over every round, and a cut that
     * took spoil back off is not in it; and a shore ring says what was laid near the lake, not
     * which cell holds it up. The sill that holds a lake is the whole reach from its lip to the
     * first ground already below it, which is what `breach` cuts, and reading that reach off the
     * finished world's flow field was tried and walked through the lake itself. What the ring is
     * good for is telling a lake ringed with fan spoil from one ringed with floodplain spoil,
     * which is the question the graded rule can answer for.
     *
     * A lake counts as deposition's when more than half its cells are dry land on the
     * no-deposition world; a lake both worlds hold whose shore moved a few cells is the sea
     * level's, cut at a different percentile once the spoil is on the map.
     */
    private fun shoreSpoilOfNewLakes(world: WorldMap, bare: WorldMap, config: WorldGenConfig): List<ShoreSpoil> {
        val plates = PlateStage.generate(config, TerrainStage.generate(config))
        val log = DepositionLog(world.width * world.height)
        val eroded = runBlocking {
            ErosionStage.apply(config, plates.height, plates.upliftRateMmPerYear, null, null, log)
        }
        var differing = 0
        for (cell in eroded.height.data.indices) {
            if (eroded.height.data[cell] != world.elevation.data[cell]) differing++
        }
        check(differing == 0) { "the logged erosion differs from the generated world on $differing cells" }

        val w = world.width
        val h = world.height
        val lakes = world.rivers.lakes
        val dryOnBare = HashMap<Int, Int>()
        for (cell in 0 until w * h) {
            if (lakes.isLake(cell) && bare.sea.isLand[cell] && !bare.rivers.lakes.isLake(cell)) {
                dryOnBare[lakes.lakeId[cell]] = (dryOnBare[lakes.lakeId[cell]] ?: 0) + 1
            }
        }
        val newLakes = lakes.lakes.filter { (dryOnBare[it.id] ?: 0) * 2 > it.cellCount }
        if (newLakes.isEmpty()) return emptyList()

        // The shore ring of each new lake: land, not lake, within reach of one of its cells. One
        // pass over the grid, looking at the neighbourhood of every land cell that is not water.
        val shoreOf = IntArray(w * h) { -1 }
        val wanted = newLakes.associate { it.id to it }
        for (cell in 0 until w * h) {
            if (!world.sea.isLand[cell] || lakes.isLake(cell)) continue
            val x = cell % w
            val y = cell / w
            var nearest = -1
            for (dy in -SHORE_REACH_CELLS..SHORE_REACH_CELLS) {
                val ny = y + dy
                if (ny < 0 || ny >= h) continue
                for (dx in -SHORE_REACH_CELLS..SHORE_REACH_CELLS) {
                    var nx = (x + dx) % w
                    if (nx < 0) nx += w
                    val n = ny * w + nx
                    if (lakes.isLake(n) && wanted.containsKey(lakes.lakeId[n])) nearest = lakes.lakeId[n]
                }
            }
            shoreOf[cell] = nearest
        }
        return newLakes.map { lake ->
            val laid = DoubleArray(4)
            var shoreCells = 0
            for (cell in 0 until w * h) {
                if (shoreOf[cell] != lake.id) continue
                shoreCells++
                for (mechanism in 1..3) laid[mechanism] += log.laidByMechanism[mechanism][cell].toDouble()
            }
            ShoreSpoil(
                lakeId = lake.id,
                cells = lake.cellCount,
                dryOnBareCells = dryOnBare[lake.id] ?: 0,
                shoreCells = shoreCells,
                laidByMechanismHeightUnits = laid
            )
        }
    }

    // ------------------------------------------------------------------ the window

    /** A rectangle of cells, half open on the right and the bottom. */
    private class Window(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        inline fun count(world: WorldMap, wanted: (Int) -> Boolean): Int {
            var n = 0
            for (y in top until bottom) for (x in left until right) {
                if (wanted(y * world.width + x)) n++
            }
            return n
        }

        /** How much weight lies inside this window, off a [summedArea] table. */
        fun sum(summed: DoubleArray, w: Int): Double {
            val stride = w + 1
            return summed[bottom * stride + right] - summed[top * stride + right] -
                summed[bottom * stride + left] + summed[top * stride + left]
        }

        override fun toString() = "[$left,$top,$right,$bottom]"
    }

    /**
     * Where a window of the author's own crop size holds the most standing water the ungraded
     * spoil made: cells under a lake on the ungraded world and not on the no-deposition world.
     *
     * The sweep `NaturalGalleryTest` and `DebugMapDump` use for their crops, counting every cell
     * off a summed-area table taken once, so a window costs four reads however big it is; the
     * stride is [SEARCH_STEP_CELLS]. Two earlier finders and where they landed are in the T4 row.
     */
    private fun pondedWindow(ungraded: WorldMap, bare: WorldMap): Window {
        val w = ungraded.width
        val h = ungraded.height
        val ponded = DoubleArray(w * h) { cell ->
            if (ungraded.rivers.lakes.isLake(cell) && !bare.rivers.lakes.isLake(cell)) 1.0 else 0.0
        }
        val sums = summedArea(ponded, w, h)
        var best = Window(0, 0, WINDOW_WIDTH_CELLS, WINDOW_HEIGHT_CELLS)
        var bestPonded = -1.0
        var top = 0
        while (top + WINDOW_HEIGHT_CELLS <= h) {
            var left = 0
            while (left + WINDOW_WIDTH_CELLS <= w) {
                val window = Window(left, top, left + WINDOW_WIDTH_CELLS, top + WINDOW_HEIGHT_CELLS)
                val here = window.sum(sums, w)
                if (here > bestPonded) {
                    bestPonded = here
                    best = window
                }
                left += SEARCH_STEP_CELLS
            }
            top += SEARCH_STEP_CELLS
        }
        return best
    }

    /**
     * The running totals a window's sum is read off, with a row and a column of zeroes above and
     * to the left so a window touching an edge needs no special case.
     */
    private fun summedArea(weight: DoubleArray, w: Int, h: Int): DoubleArray {
        val summed = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSoFar = 0.0
            for (x in 0 until w) {
                rowSoFar += weight[y * w + x]
                summed[(y + 1) * (w + 1) + x + 1] = summed[y * (w + 1) + x + 1] + rowSoFar
            }
        }
        return summed
    }

    private fun lakeCells(world: WorldMap, window: Window): Int =
        window.count(world) { world.rivers.lakes.isLake(it) }

    private fun lakeCells(world: WorldMap): Int {
        var n = 0
        for (cell in 0 until world.width * world.height) if (world.rivers.lakes.isLake(cell)) n++
        return n
    }

    // ------------------------------------------------------------------ what a ring is

    /**
     * Every cell of standing water lying in a band about a centre, far longer than it is thick and
     * well out from that centre, over the whole world.
     *
     * The centre is fitted to the water rather than assumed to be the river mouth: the author's
     * crescents are not concentric with a mouth at all — measured against the mouth they score
     * nothing — but with the ground they lie on. It is Kasa's algebraic circle fit, the centre and
     * radius that minimise the squared error of `x^2 + y^2 = 2ax + 2by + c` over the component's
     * cells, which is a three-by-three linear solve in one pass over them. The previous form of
     * this test searched a lattice of candidate centres instead, which costs the component's size
     * times the square of its span and was affordable only because it ran inside one small window;
     * the whole world needs a fit that costs one pass, and a least-squares circle is the published
     * way to take one. A short refinement on the cells' own lattice follows it, because what the
     * clauses below read is the spread of radii and not the algebraic residual.
     *
     * A component crossing the map's east-west seam is measured on its raw columns and so reads as
     * a body the width of the world, which the span bound below then rejects. That is a blind spot
     * one meridian wide and it is left: a ring is a landform tens of cells across, and unwrapping a
     * component about an arbitrary member to catch the handful that straddle the seam would be
     * more arithmetic than the case it buys.
     */
    private fun ringedWaterMask(world: WorldMap): BooleanArray {
        val w = world.width
        val h = world.height
        val lakes = world.rivers.lakes
        val ring = BooleanArray(w * h)
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val members = ArrayList<Int>()
        for (start in 0 until w * h) {
            if (seen[start] || !lakes.isLake(start)) continue
            var top = 0
            stack[top++] = start
            seen[start] = true
            members.clear()
            while (top > 0) {
                val cell = stack[--top]
                members.add(cell)
                neighbours(w, h, cell) { n ->
                    if (lakes.isLake(n) && !seen[n]) { seen[n] = true; stack[top++] = n }
                }
            }
            if (members.size < SMALLEST_RING_CELLS) continue

            var lowX = w
            var highX = 0
            var lowY = h
            var highY = 0
            members.forEach {
                val x = it % w
                val y = it / w
                if (x < lowX) lowX = x
                if (x > highX) highX = x
                if (y < lowY) lowY = y
                if (y > highY) highY = y
            }
            val span = maxOf(highX - lowX, highY - lowY) + 1
            // A band no thicker than MAX_RING_THICKNESS_CELLS whose outer diameter is `span`
            // covers at most `pi * span * thickness` cells — the annulus area, with the outer and
            // inner radii summing to at most `span`. A component holding more than that is a body
            // of water and not a band, and the clauses below would reject it after the fit rather
            // than before. Bounding it here is what makes a sweep of the whole world affordable.
            if (members.size > PI * span * MAX_RING_THICKNESS_CELLS) continue

            val centre = circleCentre(members, w)
            val fitted = tightestBand(members, w, centre.first, centre.second)
            val thickness = fitted.first.coerceAtLeast(1.0)
            val radius = fitted.second
            if (thickness <= MAX_RING_THICKNESS_CELLS && members.size >= 4 * thickness &&
                radius >= 2 * thickness
            ) {
                members.forEach { ring[it] = true }
            }
        }
        return ring
    }

    /**
     * Kasa's algebraic circle fit over the cells of one component: the centre of the circle whose
     * squared error in `x^2 + y^2 = 2ax + 2by + c` is least, by the normal equations.
     *
     * Falls back on the centroid where the cells are collinear, which makes the solve singular; a
     * straight line of water has no centre to be concentric with and the band clauses reject it on
     * its radius.
     */
    private fun circleCentre(members: List<Int>, w: Int): Pair<Double, Double> {
        var meanX = 0.0
        var meanY = 0.0
        members.forEach { meanX += (it % w).toDouble(); meanY += (it / w).toDouble() }
        meanX /= members.size
        meanY /= members.size
        // Taken about the centroid, which conditions the solve and drops the constant term.
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        var sxz = 0.0
        var syz = 0.0
        members.forEach {
            val x = it % w - meanX
            val y = it / w - meanY
            val z = x * x + y * y
            sxx += x * x
            syy += y * y
            sxy += x * y
            sxz += x * z
            syz += y * z
        }
        val determinant = sxx * syy - sxy * sxy
        if (abs(determinant) < 1e-9) return meanX to meanY
        val a = (sxz * syy - syz * sxy) / (2 * determinant)
        val b = (syz * sxx - sxz * sxy) / (2 * determinant)
        return (meanX + a) to (meanY + b)
    }

    /**
     * The tightest band the component fits into about a centre near [centreX], [centreY], as its
     * thickness and its mean radius.
     *
     * A short refinement on the cells' own lattice, because the clauses read the spread of radii
     * and Kasa's fit minimises an algebraic residual, which is not quite the same thing and is
     * known to pull the centre in on an arc that covers less than a full turn — which the author's
     * crescents do.
     */
    private fun tightestBand(
        members: List<Int>,
        w: Int,
        centreX: Double,
        centreY: Double
    ): Pair<Double, Double> {
        var bestX = centreX
        var bestY = centreY
        var best = bandAbout(members, w, bestX, bestY)
        var step = REFINEMENT_START_CELLS
        while (step >= 1.0) {
            var moved = true
            while (moved) {
                moved = false
                for (stepY in -1..1) for (stepX in -1..1) {
                    if (stepX == 0 && stepY == 0) continue
                    val x = bestX + stepX * step
                    val y = bestY + stepY * step
                    val here = bandAbout(members, w, x, y)
                    if (here.first < best.first) {
                        best = here
                        bestX = x
                        bestY = y
                        moved = true
                    }
                }
            }
            step /= 2.0
        }
        return best
    }

    /** The thickness and mean radius of the band [members] fills about one centre. */
    private fun bandAbout(
        members: List<Int>,
        w: Int,
        centreX: Double,
        centreY: Double
    ): Pair<Double, Double> {
        var low = Double.MAX_VALUE
        var high = 0.0
        members.forEach {
            val dx = it % w - centreX
            val dy = it / w - centreY
            val r = sqrt(dx * dx + dy * dy)
            if (r < low) low = r
            if (r > high) high = r
        }
        return (high - low) to ((high + low) / 2)
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
        /**
         * The window's size in cells, which is the author's own crop of the southern rift:
         * `[1010,1650,1165,1900]` was 155 wide and 250 deep at 2048. The rectangle is kept and
         * only its position is looked for, so what the case measures is the same amount of ground
         * it always measured.
         */
        const val WINDOW_WIDTH_CELLS = 155
        const val WINDOW_HEIGHT_CELLS = 250

        /**
         * The stride the window is swept on. Sixteen cells at 2048 is a tenth of the window's
         * width, so the best position is found to within a tenth of a window; a crop only has to
         * hold the artefact, not be centred on it to the cell.
         */
        const val SEARCH_STEP_CELLS = 16

        /**
         * How far from a lake's cells its shore ring reaches, in cells. Two is one cell of shore
         * and one behind it, enough to hold the spoil a fan or a floodplain left at the water's
         * edge and not so much that a neighbouring valley's spoil is counted with it.
         */
        const val SHORE_REACH_CELLS = 2

        /**
         * The smallest component the band clauses will look at, in cells. Below this a handful of
         * cells fits a circular band by arithmetic rather than by shape.
         */
        const val SMALLEST_RING_CELLS = 24

        /** The thickest a band may be and still be a ring rather than a body of water, in cells. */
        const val MAX_RING_THICKNESS_CELLS = 12.0

        /**
         * How far the refinement's first step reaches, in cells. Halved down to one, so the search
         * covers the ground between the algebraic centre and the tightest one at a cost that is
         * logarithmic in the distance rather than square in the span.
         */
        const val REFINEMENT_START_CELLS = 8.0
    }
}
