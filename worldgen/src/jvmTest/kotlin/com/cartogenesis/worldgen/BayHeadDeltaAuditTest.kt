package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * E6's one discriminating guard, at the grid the artefact lives at.
 *
 * In the audit tier, with `GlaciationAuditTest` and `RealmIdRangeAuditTest`, for the reason those
 * are: it generates 2048 worlds and the per-merge tier cannot afford them. `BayHeadDeltaTest`
 * carries the same measurements over the whole world at 1024 and records that none of them
 * discriminates there — a ring of water round a delta is a few hundred cells on a map of four
 * million, and at 1024 every long curved lake fits a circular band well enough to be counted as
 * one. At 2048, in a window cropped round the artefact, it is unambiguous.
 *
 * What the guard is about: aggradation whose fixed point is a flat dams its own valley. The spoil
 * fills the floor to a plane, the depression fill ponds whatever hollows are left in it, and the
 * reader sees water in shapes no landform explains — on seed 718106's southern rift, a
 * rounded-square pocket and two concentric crescent moats. Aggradation graded to the slope the
 * river needs lays the same material along the channel and dams nothing.
 *
 * **T3: the window is found, not written down.** It was the author's own crop of the southern
 * rift, `[1010,1650,1165,1900]`, taken off a world that predates F35 — and F35 moved every world
 * above 512, so the crop stopped pointing at the rift. By the nightly tier of 2026-09-14 onwards
 * it held no standing water at all in any of the three worlds this case generates, which made the
 * control read nothing against nothing and the guard's own first clause report that it proved
 * nothing. A window fixed in cells cannot survive a change to where the ground is; the artefact
 * can be looked for instead. [ringedWaterMask] marks every cell of standing water that lies in a
 * band about a centre, and [densestWindow] sweeps a window of the author's own size over the
 * ungraded world for wherever most of it is, the way `NaturalGalleryTest` and `DebugMapDump`
 * choose their crops. The control therefore has a subject on whatever world the generator makes.
 */
class BayHeadDeltaAuditTest {

    private fun config(outline: Boolean): WorldGenConfig {
        val base = WorldGenConfig(seed = 718106L, width = 512, height = 512, seaLevel = 0.62f)
        val authored = base.copy(
            tectonics = base.tectonics.copy(plateCount = 14),
            nations = base.nations.copy(nationCount = 12)
        ).atResolution(2048, 2048)
        return authored.copy(erosion = authored.erosion.copy(gradedAggradation = outline))
    }

    @Test
    fun `no ring of standing water in the author's valley`() {
        val now = WorldGenerationEngine.generateBlocking(config(true))
        val was = WorldGenerationEngine.generateBlocking(config(false))
        val bare = WorldGenerationEngine.generateBlocking(
            config(true).let { it.copy(erosion = it.erosion.copy(deposition = false)) }
        )

        // The window is chosen on the ungraded world, which is the one the artefact is in. Choosing
        // it on the graded world would be choosing the window with the least to find in it.
        val wasRings = ringedWaterMask(was)
        val wasStanding = BooleanArray(was.width * was.height) { was.rivers.lakes.isLake(it) }
        val window = densestWindow(was, wasRings, wasStanding)
        val nowRings = ringedWaterMask(now)

        val wasRingCells = window.count(was) { wasRings[it] }
        val nowRingCells = window.count(now) { nowRings[it] }
        val wasWater = lakeCells(was, window)
        val nowWater = lakeCells(now, window)
        val bareWater = lakeCells(bare, window)
        println(
            ("E6 AUDIT 718106 at 2048, the window holding the most ringed water on the ungraded " +
                "world, %s: %d lake cells in a ring before, %d after; %d lake cells in all " +
                "before, %d after, against %d with no deposition")
                .format(window, wasRingCells, nowRingCells, wasWater, nowWater, bareWater)
        )

        // The ring count is reported, not asserted, and the reason is that it has no control in
        // the tree. What removes the crescents is the unit muddle in `headroom` — a relative margin
        // spent as a height, so an alluvial dam could stand four times higher than the no-uphill
        // rule allows — and that is a bug, not a switch: measured by reverting it by hand, the
        // author's window holds 563 cells of ringed water and none with it closed. Putting a
        // config flag on "use the wrong units" to give the guard a control would be keeping a bug
        // alive to prove it is dead.
        //
        // What is asserted is the claim underneath the shape, which does have a control: a valley
        // whose rivers lay sediment must not hold more standing water than the same valley whose
        // rivers lay none. Aggradation to a flat dams its own floor and the fill ponds the hollows
        // that are left; aggradation graded to the slope the river needs lays the same material
        // along the channel and dams nothing.
        assertTrue(
            wasWater > bareWater,
            "the ungraded control was expected to hold more standing water than the same valley " +
                "with no deposition at all and held $wasWater against $bareWater, so this guard " +
                "proves nothing"
        )
        assertTrue(
            nowWater <= bareWater,
            "the valley holds $nowWater cells of standing water where the same ground with no " +
                "deposition at all holds $bareWater: the spoil is still damming it"
        )
        // The ring clause stands only where the ungraded world put a ring in the window to find.
        // Where it did not, there is nothing for the graded world to be better than, and the
        // clause would be asserting zero against zero — which is the state the fixed window decayed
        // into and the reason it was replaced. Said out loud rather than passed silently.
        if (wasRingCells == 0) {
            println(
                "E6 AUDIT 718106 at 2048: no window on the ungraded world holds a ring of " +
                    "standing water, so the ring clause proves nothing on this world and is not " +
                    "asserted; the water-balance clauses above it have their control and are"
            )
        } else {
            assertTrue(
                nowRingCells == 0,
                "$nowRingCells cells of standing water still lie in a ring in the window the " +
                    "ungraded world put $wasRingCells in"
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

        /** How many marked cells lie inside this window, off a [summedArea] table. */
        fun sum(summed: IntArray, w: Int): Int {
            val stride = w + 1
            return summed[bottom * stride + right] - summed[top * stride + right] -
                summed[bottom * stride + left] + summed[top * stride + left]
        }

        override fun toString() = "[$left,$top,$right,$bottom]"
    }

    /**
     * Where a window of the author's own size holds the most ringed standing water.
     *
     * The sweep `NaturalGalleryTest` and `DebugMapDump` use for their crops: the window only has
     * to be a good one, not the best one, and it is chosen off the world's own fields so the same
     * ground comes out again after a change that does not move it. The stride is [SEARCH_STEP].
     *
     * Those two sample every fourth cell inside the window to keep the sweep affordable; this one
     * counts every cell, because what it is looking for is a few hundred cells in four million and
     * a stride through the count could step over all of them. It can afford to: the counts come
     * off a summed-area table taken once over the mask, so a window costs four reads however big
     * it is, rather than its own area.
     *
     * Scored on the ringed water first and on all the standing water second. The second is not
     * only a tie-break: where the ungraded world has no ring anywhere, every window scores nothing
     * on the first and the window still has to be a valley, because the two clauses that do have a
     * control are about how much water a valley holds. The window with the most standing water in
     * it is that valley.
     */
    private fun densestWindow(
        world: WorldMap,
        ringed: BooleanArray,
        standing: BooleanArray
    ): Window {
        val w = world.width
        val h = world.height
        val ringedSums = summedArea(ringed, w, h)
        val standingSums = summedArea(standing, w, h)

        var best = Window(0, 0, WINDOW_WIDTH, WINDOW_HEIGHT)
        var bestRinged = -1
        var bestStanding = -1
        var top = 0
        while (top + WINDOW_HEIGHT <= h) {
            var left = 0
            while (left + WINDOW_WIDTH <= w) {
                val window = Window(left, top, left + WINDOW_WIDTH, top + WINDOW_HEIGHT)
                val ringedHere = window.sum(ringedSums, w)
                val standingHere = window.sum(standingSums, w)
                if (ringedHere > bestRinged ||
                    (ringedHere == bestRinged && standingHere > bestStanding)
                ) {
                    bestRinged = ringedHere
                    bestStanding = standingHere
                    best = window
                }
                left += SEARCH_STEP
            }
            top += SEARCH_STEP
        }
        return best
    }

    /**
     * The running totals a window's count is read off, with a row and a column of zeroes above and
     * to the left so a window touching an edge needs no special case.
     */
    private fun summedArea(mask: BooleanArray, w: Int, h: Int): IntArray {
        val summed = IntArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSoFar = 0
            for (x in 0 until w) {
                if (mask[y * w + x]) rowSoFar++
                summed[(y + 1) * (w + 1) + x + 1] = summed[y * (w + 1) + x + 1] + rowSoFar
            }
        }
        return summed
    }

    private fun lakeCells(world: WorldMap, window: Window): Int =
        window.count(world) { world.rivers.lakes.isLake(it) }

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
            // A band no thicker than MAX_RING_THICKNESS whose outer diameter is `span` covers at
            // most `pi * span * thickness` cells — the annulus area, with the outer and inner radii
            // summing to at most `span`. A component holding more than that is a body of water and
            // not a band, and the clauses below would reject it after the fit rather than before.
            // Bounding it here is what makes a sweep of the whole world affordable.
            if (members.size > PI * span * MAX_RING_THICKNESS) continue

            val centre = circleCentre(members, w)
            val fitted = tightestBand(members, w, centre.first, centre.second)
            val thickness = fitted.first.coerceAtLeast(1.0)
            val radius = fitted.second
            if (thickness <= MAX_RING_THICKNESS && members.size >= 4 * thickness &&
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
        const val WINDOW_WIDTH = 155
        const val WINDOW_HEIGHT = 250

        /**
         * The stride the window is swept on. Sixteen cells at 2048 is a tenth of the window's
         * width, so the best position is found to within a tenth of a window; a crop only has to
         * hold the artefact, not be centred on it to the cell.
         */
        const val SEARCH_STEP = 16

        /**
         * The smallest component the band clauses will look at, in cells. Below this a handful of
         * cells fits a circular band by arithmetic rather than by shape.
         */
        const val SMALLEST_RING_CELLS = 24

        /** The thickest a band may be and still be a ring rather than a body of water, in cells. */
        const val MAX_RING_THICKNESS = 12.0

        /**
         * How far the refinement's first step reaches, in cells. Halved down to one, so the search
         * covers the ground between the algebraic centre and the tightest one at a cost that is
         * logarithmic in the distance rather than square in the span.
         */
        const val REFINEMENT_START_CELLS = 8.0
    }
}
