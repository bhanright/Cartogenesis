package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * E6: what a river leaves at the head of a bay is a delta plain, not a terrace with moats round it.
 *
 * The author, on seed 718106 at 2048 after H5b: *"this valley still has some pretty significant
 * issues."* Top to bottom his crop of the southern rift held a lake with a saw-tooth fringe of thin
 * bars, two straight bars of water at forty-five degrees, a flat terrace with a rounded-square
 * pocket in it, and below that two concentric crescent moats. Measured with `DepositionLog` and
 * against a world with no deposition at all, they turn out to be three different things:
 *
 *  - **The moats and the pocket are the spoil's**, and the cause is not a fan at all. `headroom`
 *    measured its margin in shoreline-relative units and the caller spent it as a height, so an
 *    alluvial dam could stand `1 / landRange` times higher than the no-uphill rule allows — about
 *    four times on these worlds — and the rule it bounds has a *flat* for its fixed point anyway.
 *    Twelve rounds of creeping toward a flat leave a near-plane whose residual hollows pond into
 *    shapes with no landform behind them. In the author's window: 563 lake cells lying in arcs
 *    about a fitted centre before, 0 after; 2282 lake cells in all before, 1121 after, against
 *    1535 on the same ground with no deposition.
 *  - **The saw-tooth bars are almost none of them the spoil's.** 145 lake cells lie in thin bars at
 *    a grid or diagonal bearing in that window; 102 of them are there with deposition switched off
 *    entirely. E6 takes the deposition's share from 43 cells to 5.
 *  - **The straight seaward front is not deposition's at all.** Cut the deposition-off terrain at
 *    the *deposited* world's own sea level and the longest straight run of shore in the window is
 *    31 cells — exactly what the deposited world measures. Deposition moved the sea level onto a
 *    different contour of a planar rift shoulder; the shoulder is E4's, and so is the pale bench of
 *    constant width down the valley's west side. Out of E6's scope, and recorded in `TODO.md`.
 *
 * Nothing is asserted here. The two halves E6 owns were measured against `gradedAggradation =
 * false`, the rule as it was, and none of the whole-world measurements discriminates at the grids
 * this class can afford; the case below says why and prints them. The measurement in the
 * author's own window at 2048 is `BayHeadDeltaAuditTest`'s, which prints it too and asserts only
 * that its finder has a subject. Both are in the audit tier.
 */
class BayHeadDeltaTest {

    private val seeds = listOf(718106L, 59758L)

    private fun config(seed: Long, size: Int): WorldGenConfig {
        val base = WorldGenConfig(seed = seed, width = 512, height = 512, seaLevel = 0.62f)
        val authored = base.copy(
            tectonics = base.tectonics.copy(plateCount = 14),
            nations = base.nations.copy(nationCount = 12)
        )
        return if (size == 512) authored else authored.atResolution(size, size)
    }

    /**
     * Everything E6 measured on the whole world, reported, and the reason none of it is asserted
     * here.
     *
     * Four whole-world measurements were written for this chunk and **not one of them
     * discriminates away from the grid the author was looking at**, which is worth saying once and
     * in full rather than quietly dropping:
     *
     *  - **Standing water against a world that lays no sediment.** At 1024 the ungraded rule holds
     *    1.75x and 1.04x of it on the two seeds and the graded rule 1.74x and 1.16x — the graded
     *    rule holds *more* on one of them. In the author's own window at 2048 the same measure
     *    reads 2282 cells before and 1121 after against a floor of 1535, which is the artefact; the
     *    whole world averages it away.
     *  - **Rings of water**: standing water in a band about a fitted centre, far longer than thick.
     *    563 cells before and 0 after in his window at 2048; at 1024 over the whole world, 59
     *    against 59 on one seed and 429 against 426 on the other, because at that grid every long
     *    curved lake fits a circular band and the measure finds lakes rather than moats.
     *  - **Rising steps along the trunk**: the share of a river's last twelve steps that go uphill,
     *    lake crossings excluded. 19.4% -> 15.9% and 15.0% -> 14.5%: it moves the right way every
     *    time and nowhere near far enough to hold a bar, because most of those steps are the D8
     *    chain crossing ground the fill raised rather than the delta plain.
     *  - **Thin bars at a grid bearing** — the saw-teeth and the forty-five degree comb — are not
     *    the deposition's to move: 145 cells in his window before, 107 after, and 102 of them are
     *    there with deposition switched off entirely.
     *
     * Rule 5's own instruction for this case is to say so and fall back to the render, which is
     * `desktop/build/deltas/e6-valley-718106-after.png` against the author's crop. The guard that
     * does discriminate runs at his own grid, in the audit tier, as `BayHeadDeltaAuditTest`.
     */
    @Test
    fun `what the whole world measures, reported`() {
        seeds.forEach { seed ->
            val bare = lakeCells(
                WorldGenerationEngine.generateBlocking(
                    config(seed, GRID).let {
                        it.copy(erosion = it.erosion.copy(deposition = false))
                    }
                )
            )
            val now = WorldGenerationEngine.generateBlocking(config(seed, GRID))
            val was = WorldGenerationEngine.generateBlocking(
                config(seed, GRID).let {
                    it.copy(erosion = it.erosion.copy(gradedAggradation = false))
                }
            )
            println(
                ("E6 seed %d at %d: standing water %d with no deposition, %d ungraded (%.2fx), " +
                    "%d graded (%.2fx); rings %d -> %d cells; rising trunk steps %.2f%% -> %.2f%%")
                    .format(
                        seed, GRID, bare, lakeCells(was), lakeCells(was).toDouble() / bare,
                        lakeCells(now), lakeCells(now).toDouble() / bare,
                        arcs(was), arcs(now), uphill(was) * 100, uphill(now) * 100
                    )
            )
        }
    }

    private fun lakeCells(world: WorldMap): Int {
        val lakes = world.rivers.lakes
        var n = 0
        for (i in 0 until world.width * world.height) if (lakes.isLake(i)) n++
        return n
    }

    /** Lake cells lying in a band about a fitted centre, far longer than thick and well out. */
    private fun arcs(world: WorldMap): Int {
        // A physical thickness, not a count of cells: twelve at 2048 is three at 512, and a band
        // twelve cells thick on a 512 grid is a lake rather than a ring.
        val thickest = ARC_THICKNESS * world.width / 2048.0
        val w = world.width
        val h = world.height
        val lakes = world.rivers.lakes
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var total = 0
        for (start in 0 until w * h) {
            if (seen[start] || !lakes.isLake(start)) continue
            var top = 0
            stack[top++] = start
            seen[start] = true
            val members = ArrayList<Int>()
            while (top > 0) {
                val c = stack[--top]
                members.add(c)
                neighbours(w, h, c) { n ->
                    if (lakes.isLake(n) && !seen[n]) { seen[n] = true; stack[top++] = n }
                }
            }
            if (members.size < 24 * world.width / 2048.0) continue
            val xs = members.map { it % w }
            val ys = members.map { it / w }
            val span = maxOf(xs.max() - xs.min(), ys.max() - ys.min()) + 1
            var best = Double.MAX_VALUE
            var bestRadius = 0.0
            var cx = xs.min() - 2 * span
            while (cx <= xs.max() + 2 * span) {
                var cy = ys.min() - 2 * span
                while (cy <= ys.max() + 2 * span) {
                    var lo = Double.MAX_VALUE
                    var hi = 0.0
                    members.forEach {
                        val dx = (it % w - cx).toDouble()
                        val dy = (it / w - cy).toDouble()
                        val r = sqrt(dx * dx + dy * dy)
                        if (r < lo) lo = r
                        if (r > hi) hi = r
                    }
                    if (hi - lo < best) { best = hi - lo; bestRadius = (hi + lo) / 2 }
                    cy += 2
                }
                cx += 2
            }
            val thickness = best.coerceAtLeast(1.0)
            if (thickness <= thickest && members.size >= 4 * thickness &&
                bestRadius >= 2 * thickness
            ) total += members.size
        }
        return total
    }

    /** The share of a drawn river's last steps to the sea that rise instead of falling. */
    private fun uphill(world: WorldMap): Double {
        val height = world.erosion.height.data
        var rising = 0
        var steps = 0
        world.rivers.rivers.forEach { river ->
            val cells = river.cells
            if (cells.size < TRUNK + 2) return@forEach
            for (k in cells.size - TRUNK until cells.size - 1) {
                val a = cells[k]
                val b = cells[k + 1]
                // Steps across standing water are flat by construction — the chain is drawn on the
                // fill, not on the ground — and counting them measures the lake, not the plain.
                if (!world.sea.isLand[b] || world.rivers.lakes.isLake(a) ||
                    world.rivers.lakes.isLake(b)
                ) continue
                steps++
                if (height[b] > height[a]) rising++
            }
        }
        return if (steps == 0) 0.0 else rising.toDouble() / steps
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
        /** The thickest band that still reads as a ring rather than as a lake. */
        const val ARC_THICKNESS = 12.0

        /** The grid the ring guard runs at: the artefact is a feature of the author's 2048 world. */
        const val GRID = 1024

        /** How many of a river's last steps count as "the plain". */
        const val TRUNK = 12

    }
}
