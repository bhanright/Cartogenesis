package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.pipeline.FlowRouting
import com.cartogenesis.worldgen.pipeline.RiverStage
import com.cartogenesis.worldgen.pipeline.Runoff
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The water a lake and a river are given: a closed basin's rain stays in it, and a wet catchment
 * carries more than a merely damp one. Both on hand-made worlds ([HandMadeWorlds]), where the one
 * answer being asked about is the only thing that can differ.
 *
 * See docs/DESIGN_LEDGER.md, chunk 6, for what each clause failed on before.
 */
class WaterReceivedTest {

    /**
     * Two closed basins in a chain, west to east, draining to a sea on the east. The upper one
     * lies in hot desert on a few millimetres of rain, too little to hold even one cell of water,
     * so the balance closes it as a playa; the lower one lies in steppe below it.
     *
     * The rain accumulated for the balance is taken over the routing as the fill left it, when
     * the upper basin still spilled into the lower; so the lower basin's pour point counted the
     * upper basin's rain as well, although by the same stage's own decision that rain never
     * leaves the playa. The lower basin's inflow must be exactly the rain on the ground whose
     * routing reaches it once the upper basin is closed, and none of the upper catchment's.
     */
    @Test
    fun `a closed basin upstream sends none of its rain to the basin below`() {
        val config = HandMadeWorlds.config()
        // Sea in the four columns about the date line, so the land is one strip that meets it at
        // both ends; the west end sheds its first two columns into the western sea.
        fun land(x: Int, y: Int) = x in 2..61
        val profile = listOf(2 to 0.20f, 4 to 0.30f, 12 to 0.05f, 22 to 0.15f, 34 to 0.02f, 46 to 0.08f, 61 to 0.005f)
        fun ground(x: Int, y: Int): Float {
            if (!land(x, y)) return -0.1f
            val rim = when {
                y < 6 -> (6 - y) * 0.08f
                y > 57 -> (y - 57) * 0.08f
                else -> 0f
            }
            val (x0, h0) = profile.last { it.first <= x }
            val (x1, h1) = profile.firstOrNull { it.first > x } ?: (x0 to h0)
            val along = if (x1 == x0) 0f else (x - x0).toFloat() / (x1 - x0)
            // A slight fall toward the middle row, so each rim has one lowest notch and each basin
            // spills through one cell, as a basin the fill raised does; a rim dead level along
            // its whole length would spill through all of it at once.
            val towardTheNotch = abs(y - 32) * 0.0005f
            return h0 + (h1 - h0) * along + rim + towardTheNotch
        }
        val desert = { x: Int, _: Int -> x < 22 }
        val sea = HandMadeWorlds.sea(config, ::land, ::ground)
        val climate = HandMadeWorlds.climate(
            config,
            rainMm = { x, y -> if (desert(x, y)) 6f else 50f },
            summerC = { x, y -> if (desert(x, y)) 40f else 18f },
            winterC = { x, y -> if (desert(x, y)) 25f else 2f }
        )

        val solved = RiverStage.solvedBasins(config, sea, climate)
        val upper = solved.basins.single { HandMadeWorlds.cellAt(config, 12, 32) in it.cells }
        val lower = solved.basins.single { HandMadeWorlds.cellAt(config, 34, 32) in it.cells }
        assertTrue(upper.endorheic, "the upper basin was meant to close; it overflows, so the fixture asks nothing")
        assertTrue(upper.catchmentRainMm > 0f, "the upper basin has no rain on it, and with none the old order passes too")
        assertTrue(upper.exits.isNotEmpty() && lower.exits.isNotEmpty(), "a basin with no exit")
        assertTrue(
            reaches(solved.routingBeforeClosing, sea.isLand, upper.exits.first(), lower.cells.toHashSet()),
            "the upper basin's water does not reach the lower one on the routing the fill left, so the fixture asks nothing"
        )

        val lowerCells = lower.cells.toHashSet()
        val reachingMm = rainReaching(solved.routingAfterClosing, sea.isLand, climate.precipitationMm.data, lowerCells)
        println(
            "CLOSED BASIN upper %d cells, %d exits, %.0f mm of rain, closed; lower %d cells, %d exits, inflow %.0f against %.0f reaching it"
                .format(upper.cells.size, upper.exits.size, upper.catchmentRainMm, lower.cells.size, lower.exits.size, lower.catchmentRainMm, reachingMm)
        )
        assertEquals(
            reachingMm, lower.catchmentRainMm.toDouble(), reachingMm * 1e-5,
            "the lower basin was given %.0f mm of rain where %.0f reaches it: %.0f too many, against the %.0f the closed basin above it keeps"
                .format(lower.catchmentRainMm, reachingMm, lower.catchmentRainMm - reachingMm, upper.catchmentRainMm)
        )
    }

    /**
     * The same question on a routing drawn by hand, where a basin has two exits and a path that
     * leaves it and comes back. Basin A, five cells of a row, is closed by the desert heat; one of
     * its exits drains into basin B below it early in the drainage, and the other drains away from
     * B late in it, at the end of a long chain of ground that feeds A. One more cell of A drains out
     * of it and straight back in.
     *
     * Ordered by where its last exit falls in the drainage, A came after B, and B was solved on the
     * stale rain with A's still in it. Solved in a topological order of the basins' own graph, B is
     * given the rain that reaches it and no more. A's inflow is the rain of every cell whose water
     * enters A, counted once: the path that leaves and returns is not an exit.
     */
    @Test
    fun `a basin with two exits is solved before the basin one of them feeds`() {
        val config = HandMadeWorlds.config()
        fun cell(x: Int, y: Int) = HandMadeWorlds.cellAt(config, x, y)
        val sea = HandMadeWorlds.sea(config, { x, _ -> x != 63 }) { x, _ -> if (x != 63) 0.1f else -0.1f }
        val basinA = (10..14).map { cell(it, 30) }
        val basinB = (10..14).map { cell(it, 35) }
        val filled = FloatField(config.width, config.height)
        for (index in filled.data.indices) filled.data[index] = sea.relativeElevation.data[index]
        for (index in basinA + basinB) filled.data[index] += 0.1f
        val receiver = IntArray(config.width * config.height) { -1 }
        fun route(from: Int, to: Int) { receiver[from] = to }
        // A: (10,30) to (11,30), which leaves by (11,29) and (12,29) and comes back in at (12,30),
        // the early exit, whose water runs down column 12 into B at (12,35).
        route(cell(10, 30), cell(11, 30))
        route(cell(11, 30), cell(11, 29))
        route(cell(11, 29), cell(12, 29))
        route(cell(12, 29), cell(12, 30))
        route(cell(12, 30), cell(12, 31))
        for (y in 31..34) route(cell(12, y), cell(12, y + 1))
        // The late exit, (13,30), at the end of a chain along row 25 that turns down column 14 into
        // (14,30); it leaves the world northward at (13,29).
        for (x in 16..55) route(cell(x, 25), cell(x - 1, 25))
        route(cell(15, 25), cell(14, 25))
        for (y in 25..29) route(cell(14, y), cell(14, y + 1))
        route(cell(14, 30), cell(13, 30))
        route(cell(13, 30), cell(13, 29))
        // B drains east along its row and on to the sea.
        for (x in 10..62) route(cell(x, 35), cell(x + 1, 35))
        val climate = HandMadeWorlds.climate(config, rainMm = { _, _ -> 50f }, summerC = { _, _ -> 40f }, winterC = { _, _ -> 25f })

        val rank = IntArray(receiver.size)
        FlowRouting.drainageOrder(config.width, config.height, sea.isLand, receiver, sea.landCellCount)
            .forEachIndexed { order, index -> rank[index] = order }
        assertTrue(
            rank[cell(13, 30)] > rank[cell(14, 35)] && rank[cell(12, 30)] < rank[cell(14, 35)],
            "the fixture's ranks are not the defect's: A's exits at %d and %d, B's at %d"
                .format(rank[cell(12, 30)], rank[cell(13, 30)], rank[cell(14, 35)])
        )

        val solved = RiverStage.solvedBasinsOn(config, sea, climate, filled, receiver)
        val a = solved.basins.single { cell(10, 30) in it.cells }
        val b = solved.basins.single { cell(10, 35) in it.cells }
        assertEquals(setOf(cell(12, 30), cell(13, 30)), a.exits.toSet(), "A's exits")
        assertTrue(a.endorheic, "A was meant to close")
        assertTrue(
            reaches(solved.routingBeforeClosing, sea.isLand, cell(12, 30), b.cells.toHashSet()),
            "A's water does not reach B on the routing the fill left"
        )
        val rain = climate.precipitationMm.data
        val intoA = rainReaching(solved.routingBeforeClosing, sea.isLand, rain, a.cells.toHashSet())
        val intoB = rainReaching(solved.routingAfterClosing, sea.isLand, rain, b.cells.toHashSet())
        println("TWO EXITS A given %.0f against %.0f entering it; B given %.0f against %.0f reaching it".format(a.catchmentRainMm, intoA, b.catchmentRainMm, intoB))
        assertEquals(intoA, a.catchmentRainMm.toDouble(), 1e-3, "A's inflow counted the path that leaves and returns twice, or missed an exit")
        assertEquals(intoB, b.catchmentRainMm.toDouble(), 1e-3, "B was given rain that stops in A")
        assertTrue(solved.basins.indexOf(a) < solved.basins.indexOf(b), "B was solved before A, which feeds it")
    }

    /**
     * Two closed basins that feed each other, each by a different exit, in a forest of cells: A's
     * western exit runs down into B and on out of B's western exit to the edge of the world, and
     * B's eastern exit runs up into A and on out of A's eastern exit. Neither is upstream of the
     * other, so there is no order to solve them in; both lie in hot desert and both close.
     *
     * The graph's leftovers were appended in index order, so A was solved first on rain that
     * still counted B's eastern cells, which B, once closed, keeps. Solved together to a fixed
     * point, each is given the rain that reaches it once the other has closed: its own cells and
     * the chain of ground running into it.
     */
    @Test
    fun `two basins that feed each other are solved together`() {
        val config = HandMadeWorlds.config()
        fun cell(x: Int, y: Int) = HandMadeWorlds.cellAt(config, x, y)
        val sea = HandMadeWorlds.sea(config, { x, _ -> x != 63 }) { x, _ -> if (x != 63) 0.1f else -0.1f }
        val basinA = (10..14).map { cell(it, 30) }
        val basinB = (10..14).map { cell(it, 35) }
        val filled = FloatField(config.width, config.height)
        for (index in filled.data.indices) filled.data[index] = sea.relativeElevation.data[index]
        for (index in basinA + basinB) filled.data[index] += 0.1f
        val receiver = IntArray(config.width * config.height) { -1 }
        fun route(from: Int, to: Int) { receiver[from] = to }
        // A: its west drains to (10,30), which leaves south down column 10 into B at (10,35); its
        // east drains to (14,30), which leaves east at (15,30) and off the world.
        route(cell(11, 30), cell(10, 30)); route(cell(12, 30), cell(11, 30)); route(cell(13, 30), cell(14, 30))
        route(cell(10, 30), cell(10, 31))
        for (y in 31..34) route(cell(10, y), cell(10, y + 1))
        route(cell(14, 30), cell(15, 30))
        // B: its west drains to (10,35), which leaves west at (9,35) and off the world; its east
        // drains to (14,35), which leaves north up column 14 into A at (14,30).
        route(cell(11, 35), cell(10, 35)); route(cell(12, 35), cell(11, 35)); route(cell(13, 35), cell(14, 35))
        route(cell(10, 35), cell(9, 35))
        route(cell(14, 35), cell(14, 34))
        for (y in 34 downTo 32) route(cell(14, y), cell(14, y - 1))
        route(cell(14, 31), cell(14, 30))
        val climate = HandMadeWorlds.climate(config, rainMm = { _, _ -> 50f }, summerC = { _, _ -> 40f }, winterC = { _, _ -> 25f })

        val solved = RiverStage.solvedBasinsOn(config, sea, climate, filled, receiver)
        val a = solved.basins.single { cell(12, 30) in it.cells }
        val b = solved.basins.single { cell(12, 35) in it.cells }
        assertEquals(setOf(cell(10, 30), cell(14, 30)), a.exits.toSet(), "A's exits")
        assertEquals(setOf(cell(10, 35), cell(14, 35)), b.exits.toSet(), "B's exits")
        assertTrue(reaches(solved.routingBeforeClosing, sea.isLand, cell(10, 30), b.cells.toHashSet()), "A does not feed B")
        assertTrue(reaches(solved.routingBeforeClosing, sea.isLand, cell(14, 35), a.cells.toHashSet()), "B does not feed A")
        assertTrue(a.endorheic && b.endorheic, "both basins were meant to close")
        val rain = climate.precipitationMm.data
        val intoA = rainReaching(solved.routingAfterClosing, sea.isLand, rain, a.cells.toHashSet())
        val intoB = rainReaching(solved.routingAfterClosing, sea.isLand, rain, b.cells.toHashSet())
        println(
            "FEEDING EACH OTHER %d group(s) solved together; A given %.0f against %.0f reaching it, B given %.0f against %.0f"
                .format(solved.groupsSolvedTogether, a.catchmentRainMm, intoA, b.catchmentRainMm, intoB)
        )
        assertEquals(intoA, a.catchmentRainMm.toDouble(), 1e-3, "A was given rain that stops in B")
        assertEquals(intoB, b.catchmentRainMm.toDouble(), 1e-3, "B was given rain that stops in A")
        assertEquals(1, solved.groupsSolvedTogether, "the two basins were not found feeding each other")
    }

    /** Whether the water from [start] enters [cells] on [routing]. */
    private fun reaches(routing: IntArray, isLand: BooleanArray, start: Int, cells: Set<Int>): Boolean {
        var cell = routing[start]
        var steps = 0
        while (cell >= 0 && isLand[cell] && steps++ < routing.size) {
            if (cell in cells) return true
            cell = routing[cell]
        }
        return false
    }

    /** The rain on every land cell whose water enters [cells] on [routing], the cells' own included. */
    private fun rainReaching(routing: IntArray, isLand: BooleanArray, rainMm: FloatArray, cells: Set<Int>): Double {
        var total = 0.0
        for (start in isLand.indices) {
            if (!isLand[start]) continue
            if (start in cells || reaches(routing, isLand, start, cells)) total += rainMm[start]
        }
        return total
    }

    /**
     * Two islands the same shape, one under 1,500 mm of rain and one under 3,000. Every cell of
     * each drains to its own coast, so the discharge the two deliver to the sea is each island's
     * cells times its weight, and the ratio between them is the ratio [Runoff.annualWeightMm]
     * gives the two rainfalls, which is two.
     *
     * The discharge was weighted by `ClimateResult.precipitation`, which is clamped at 1,200 mm, so
     * both islands delivered the same water and the ratio was one: a trunk draining rainforest was
     * drawn, ranked and split as if it drained a temperate plain.
     */
    @Test
    fun `a catchment twice as wet carries twice the water`() {
        val config = HandMadeWorlds.config()
        fun island(y: Int) = when (y) {
            in 8..24 -> 16
            in 40..56 -> 48
            else -> -1
        }
        fun land(x: Int, y: Int) = island(y) >= 0 && x in 10..40
        // A low dome on each island, highest at its centre, so every cell falls to the coast.
        fun ground(x: Int, y: Int): Float {
            if (!land(x, y)) return -0.1f
            val acrossKm = abs(x - 25) * config.cellWidthKm
            val downKm = abs(y - island(y)) * config.cellHeightKm
            return (0.4 - 0.4 * maxOf(acrossKm / (16 * config.cellWidthKm), downKm / (9 * config.cellHeightKm))).toFloat()
        }
        val sea = HandMadeWorlds.sea(config, ::land, ::ground)
        val climate = HandMadeWorlds.climate(config, rainMm = { _, y -> if (y < 32) 1500f else 3000f })
        val world = RiverStage.generate(config, sea, climate)

        var northMm = 0.0
        var southMm = 0.0
        for (cell in sea.isLand.indices) {
            if (!sea.isLand[cell]) continue
            val receiver = world.flowTarget[cell]
            if (receiver >= 0 && sea.isLand[receiver]) continue
            if (cell / config.width < 32) northMm += world.flowAccumulation.data[cell] else southMm += world.flowAccumulation.data[cell]
        }
        val expected = (Runoff.annualWeightMm(3000f) / Runoff.annualWeightMm(1500f)).toDouble()
        println("RUNOFF the 3,000 mm island delivers %.4f times the 1,500 mm one's water, against %.4f".format(southMm / northMm, expected))
        assertEquals(expected, southMm / northMm, 1e-4, "the wetter island's discharge against the drier one's")
    }
}
