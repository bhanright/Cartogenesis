package com.cartogenesis.worldgen

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

        val basins = RiverStage.solvedBasins(config, sea, climate)
        val upper = basins.single { HandMadeWorlds.cellAt(config, 12, 32) in it.cells }
        val lower = basins.single { HandMadeWorlds.cellAt(config, 34, 32) in it.cells }
        assertTrue(upper.endorheic, "the upper basin was meant to close; it overflows, so the fixture asks nothing")
        assertTrue(upper.catchmentRainMm > 0f, "the upper basin has no rain on it, and with none the old order passes too")

        // What reaches the lower basin once everything is routed: every land cell whose path
        // enters it, and the rain on each.
        val world = RiverStage.generate(config, sea, climate)
        val lowerCells = lower.cells.toHashSet()
        val exits = lower.cells.count { val target = world.flowTarget[it]; target >= 0 && target !in lowerCells }
        println("CLOSED BASIN the lower basin's water leaves it at $exits cells once routed")
        var reachingMm = 0.0
        for (start in sea.isLand.indices) {
            if (!sea.isLand[start]) continue
            var cell = start
            var steps = 0
            while (cell >= 0 && sea.isLand[cell] && cell !in lowerCells && steps++ < sea.isLand.size) {
                cell = world.flowTarget[cell]
            }
            if (cell in lowerCells) reachingMm += climate.precipitationMm.data[start]
        }
        println(
            "CLOSED BASIN upper %d cells, %.0f mm of rain, closed; lower %d cells, inflow %.0f against %.0f reaching it"
                .format(upper.cells.size, upper.catchmentRainMm, lower.cells.size, lower.catchmentRainMm, reachingMm)
        )
        assertEquals(
            reachingMm, lower.catchmentRainMm.toDouble(), reachingMm * 1e-5,
            "the lower basin was given %.0f mm of rain where %.0f reaches it: %.0f too many, against the %.0f the closed basin above it keeps"
                .format(lower.catchmentRainMm, reachingMm, lower.catchmentRainMm - reachingMm, upper.catchmentRainMm)
        )
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
