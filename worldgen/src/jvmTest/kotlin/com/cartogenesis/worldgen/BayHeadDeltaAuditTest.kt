package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
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
 * one. At 2048, in the window the author cropped, it is unambiguous: 563 cells before, none after.
 *
 * What the guard is about: aggradation whose fixed point is a flat dams its own valley. The spoil
 * fills the floor to a plane, the depression fill ponds whatever hollows are left in it, and the
 * reader sees water in shapes no landform explains — on seed 718106's southern rift, a
 * rounded-square pocket and two concentric crescent moats. Aggradation graded to the slope the
 * river needs lays the same material along the channel and dams nothing.
 */
class BayHeadDeltaAuditTest {

    /** The author's own crop of the southern rift, in cells at 2048. */
    private val window = intArrayOf(1010, 1650, 1165, 1900)

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
        val nowRings = rings(now)
        val wasRings = rings(was)
        val nowWater = lakeCells(now)
        val wasWater = lakeCells(was)
        val bare = WorldGenerationEngine.generateBlocking(
            config(true).let { it.copy(erosion = it.erosion.copy(deposition = false)) }
        )
        println(
            ("E6 AUDIT 718106 at 2048, the author's window: %d lake cells in a ring before, %d " +
                "after; %d lake cells in all before, %d after, against %d with no deposition")
                .format(wasRings, nowRings, wasWater, nowWater, lakeCells(bare))
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
            wasWater > lakeCells(bare),
            "the ungraded control was expected to hold more standing water than the same valley " +
                "with no deposition at all and held $wasWater against ${lakeCells(bare)}, so this " +
                "guard proves nothing"
        )
        assertTrue(
            nowWater <= lakeCells(bare),
            "the valley holds $nowWater cells of standing water where the same ground with no " +
                "deposition at all holds ${lakeCells(bare)}: the spoil is still damming it"
        )
        assertTrue(
            nowRings == 0,
            "$nowRings cells of standing water still lie in a ring in the author's valley"
        )
    }

    private fun lakeCells(world: WorldMap): Int {
        val w = world.width
        val lakes = world.rivers.lakes
        var n = 0
        for (y in window[1] until window[3]) for (x in window[0] until window[2]) {
            if (lakes.isLake(y * w + x)) n++
        }
        return n
    }

    /**
     * Standing water lying in a band about a centre, far longer than it is thick and well out from
     * that centre.
     *
     * The centre is searched for rather than assumed to be the river mouth: the author's crescents
     * are not concentric with a mouth at all — measured against the mouth they score nothing — but
     * with the ground they lie on.
     */
    private fun rings(world: WorldMap): Int {
        val w = world.width
        val h = world.height
        val lakes = world.rivers.lakes
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var total = 0
        for (sy in window[1] until window[3]) for (sx in window[0] until window[2]) {
            val start = sy * w + sx
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
            if (members.size < 24) continue
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
                    cy += 3
                }
                cx += 3
            }
            val thickness = best.coerceAtLeast(1.0)
            if (thickness <= 12.0 && members.size >= 4 * thickness &&
                bestRadius >= 2 * thickness
            ) total += members.size
        }
        return total
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
}
