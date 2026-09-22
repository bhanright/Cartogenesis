package com.cartogenesis.cartography

import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import kotlin.math.abs
import kotlin.test.Test

import kotlin.test.assertTrue

/**
 * X1c: how much river a sheet draws, and whether that is Earth's figure.
 *
 * The reference and its derivation are in [RiverSelection]'s own comment; what is measured here is
 * that a generated map lands on it, that the network stays whole while it does, and that the answer
 * belongs to the sheet's scale rather than to the grid the world was generated on — which is the
 * one thing the radical law on the traced count cannot do, and is shown failing below.
 *
 * All at 512. The same measurements at 1024 and 2048, on the author's own world, are
 * `RiverSelectionAuditTest` in `:desktop`.
 */
class RiverSelectionTest {

    private companion object {
        /** The four standard seeds, at the grid every per-merge guard in this repository uses. */
        val SEEDS = listOf(7L, 42L, 1234L, 99L)
        const val SIDE = 512

        /**
         * A laptop's pane, across. The world is always drawn whole, so this over the grid is the
         * pixels a cell covers and [MapSheet.onScreen] quantises it to a half-octave band.
         */
        const val PANE_PIXELS_ACROSS = 900f

        /**
         * How far a generated world's drawn density may sit from the Earth figure.
         *
         * Half of it and half again: a factor of 1.5 either way. Three things move the comparison
         * and none of them is the selection rule. The reference's own two tiers disagree by 3 per
         * cent when one is carried to the other by the law. Earth's land area is quoted with
         * Antarctica in it and the same figure without is a tenth larger. And a generated world is
         * not Earth — its land is 38 per cent of a sphere against Earth's 29, its courses are cut
         * at a hundred kilometres where the reference's mean drawn course is seven hundred, and how
         * much of its budget the last course overshoots by depends on how long that course is. What
         * a bar this wide still catches is the thing worth catching: a map drawing five times or a
         * fifth of the ink a published map at its scale draws.
         */
        const val DENSITY_BAND = 1.5

        /**
         * How far two generation resolutions' densities may sit from each other at one output scale.
         *
         * Much tighter, because this one is arithmetic rather than geography: the two sheets have
         * the same representative fraction and so the same budget per square kilometre of land, and
         * the only slack is the last course each fits — at most one course of a hundred kilometres
         * or more against a budget of tens of thousands — plus what the two grids' coastlines make
         * of the same world's land area. Five per cent covers both with room to spare.
         */
        const val ACROSS_RESOLUTIONS_BAND = 0.05
    }

    private fun world(seed: Long, side: Int = SIDE): WorldMap =
        WorldGenerationEngine.generateBlocking(
            WorldGenConfig(seed = seed, width = side, height = side)
        )

    /** The sheet a whole world of [cellsAcross] cells is shown on in a [PANE_PIXELS_ACROSS] pane. */
    private fun paneSheet(cellsAcross: Int): MapSheet =
        MapSheet.onScreen(PANE_PIXELS_ACROSS / cellsAcross)

    // ---- the Earth figure --------------------------------------------------------------------

    @Test
    fun `the reference's two tiers agree under the law that carries them`() {
        // Natural Earth's 1:10M tier, 0.004025 km of drawn river per km2 of land, carried to the
        // 1:50M tier by Töpfer's square root, against the 0.001707 measured there.
        val fromTheFinerTier = 0.004025 * kotlin.math.sqrt(10.0 / 50.0)
        val measured = RiverSelection.DRAWN_RIVER_KM_PER_SQUARE_KM_AT_FIFTY_MILLION
        val disagreement = abs(fromTheFinerTier - measured) / measured
        println(
            "X1C reference: 1:50M measures ${measured.sig()} km/km2, " +
                "1:10M carried by the law gives ${fromTheFinerTier.sig()}, " +
                "disagreeing by ${(disagreement * 100).oneDecimal()}%"
        )
        assertTrue(
            disagreement < 0.06,
            "the two tiers of the reference disagree by ${(disagreement * 100).oneDecimal()}%, " +
                "which is more than the constant claims"
        )
        // And the density is a falling function of the denominator: a smaller map draws less.
        assertTrue(
            RiverSelection.drawnRiverKmPerSquareKm(22.1e6) >
                RiverSelection.drawnRiverKmPerSquareKm(50e6),
            "a 1:22M sheet asks for no more ink than a 1:50M one"
        )
    }

    @Test
    fun `a generated map draws Earth's ink at its own scale`() {
        SEEDS.forEach { seed ->
            val map = world(seed)
            listOf(
                "export" to MapSheet.UNGENERALISED,
                "pane" to paneSheet(map.width)
            ).forEach { (where, sheet) ->
                val chosen = RiverSelection.select(map, sheet)
                val wanted = RiverSelection.drawnRiverKmPerSquareKm(chosen.denominator)
                val drawn = chosen.drawnKmPerSquareKm
                println(
                    "X1C $seed at $SIDE, $where: 1:${(chosen.denominator / 1e6).oneDecimal()}M, " +
                        "${chosen.drawnCount} of ${map.rivers.rivers.size} courses, " +
                        "${chosen.drawnKilometres.round()} of ${chosen.tracedKilometres.round()} km " +
                        "over ${(chosen.landAreaSquareKm / 1e6).oneDecimal()} M km2 land: " +
                        "${drawn.sig()} km/km2 against Earth's ${wanted.sig()}, " +
                        "crowding pitch ${chosen.crowdingPitchKm.round()} km"
                )
                assertTrue(
                    drawn <= wanted * DENSITY_BAND && drawn >= wanted / DENSITY_BAND,
                    "seed $seed draws ${drawn.sig()} km/km2 where a published map at " +
                        "1:${(chosen.denominator / 1e6).oneDecimal()}M draws ${wanted.sig()}"
                )
            }
        }
    }

    // ---- the budget and the network ----------------------------------------------------------

    @Test
    fun `the budget is never overspent and nothing that would fit is left out`() {
        SEEDS.forEach { seed ->
            val map = world(seed)
            val chosen = RiverSelection.select(map, MapSheet.UNGENERALISED)
            assertTrue(
                chosen.drawnKilometres <= chosen.budgetKilometres,
                "seed $seed drew ${chosen.drawnKilometres.round()} km against a budget of " +
                    "${chosen.budgetKilometres.round()}"
            )
            // Everything left out was left out because it did not fit, chain and all: a course is
            // only ever skipped on the budget once the crowded pass has run.
            val spent = chosen.drawnKilometres
            var cheapestRefused = Double.MAX_VALUE
            chosen.drawn.indices.forEach { course ->
                if (chosen.drawn[course]) return@forEach
                var cost = 0.0
                var link = course
                while (link != RiverSelection.NO_TRUNK && !chosen.drawn[link]) {
                    cost += chosen.courseKilometres[link]
                    link = chosen.trunkOf[link]
                }
                cheapestRefused = minOf(cheapestRefused, cost)
            }
            val headroom = chosen.budgetKilometres - spent
            println(
                "X1C $seed budget ${chosen.budgetKilometres.round()} km, spent ${spent.round()}, " +
                    "headroom ${headroom.round()} km, cheapest refused chain " +
                    "${if (cheapestRefused == Double.MAX_VALUE) 0.0 else cheapestRefused}"
            )
            if (cheapestRefused != Double.MAX_VALUE) {
                assertTrue(
                    cheapestRefused > headroom,
                    "seed $seed left ${headroom.round()} km of budget unspent with a " +
                        "${cheapestRefused.round()} km chain refused"
                )
            }
        }
    }

    @Test
    fun `no drawn tributary hangs off a river that is not drawn`() {
        SEEDS.forEach { seed ->
            val map = world(seed)
            listOf(MapSheet.UNGENERALISED, paneSheet(map.width)).forEach { sheet ->
                val chosen = RiverSelection.select(map, sheet)
                var tributaries = 0
                chosen.drawn.indices.forEach { course ->
                    if (!chosen.drawn[course]) return@forEach
                    val trunk = chosen.trunkOf[course]
                    if (trunk == RiverSelection.NO_TRUNK) return@forEach
                    tributaries++
                    assertTrue(
                        chosen.drawn[trunk],
                        "seed $seed drew course $course into course $trunk, which is not drawn"
                    )
                }
                println(
                    "X1C $seed at ${sheet.pixelsPerCell} px per cell: ${chosen.drawnCount} drawn, " +
                        "$tributaries of them joining a drawn trunk"
                )
            }
        }
    }

    // ---- the discriminator: the same scale from two grids --------------------------------------

    @Test
    fun `one pane draws the same density from a 512 world and a 1024 world`() {
        val densities = HashMap<String, MutableMap<Int, Double>>()
        listOf(SIDE, 2 * SIDE).forEach { side ->
            // One world alive at a time: reduced to the two figures each rule is read for before
            // the next is generated.
            val map = world(7L, side)
            val sheet = paneSheet(side)
            val scale = MapScale.representativeFractionDenominator(map.config.scale, side, sheet.pixelsPerCell)
            val earth = RiverSelection.select(map, sheet)
            val byTheLaw = RiverSelection.drawnOn(map, sheet, RiverInk.RADICAL_LAW)
            val lawKilometres = byTheLaw.sumOf { RiverSelection.courseKilometres(map, it) }
            densities.getOrPut("Earth's density") { HashMap() }[side] = earth.drawnKmPerSquareKm
            densities.getOrPut("radical law") { HashMap() }[side] =
                lawKilometres / earth.landAreaSquareKm
            println(
                "X1C seed 7 at $side in a ${PANE_PIXELS_ACROSS.toInt()} px pane " +
                    "(${sheet.pixelsPerCell} px per cell, 1:${(scale / 1e6).oneDecimal()}M): " +
                    "Earth's density draws ${earth.drawnCount} courses, " +
                    "${earth.drawnKilometres.round()} km, ${earth.drawnKmPerSquareKm.sig()} km/km2; " +
                    "the radical law draws ${byTheLaw.size} courses, ${lawKilometres.round()} km, " +
                    "${(lawKilometres / earth.landAreaSquareKm).sig()} km/km2 " +
                    "(${map.rivers.rivers.size} traced, ${earth.tracedKilometres.round()} km)"
            )
        }

        val law = densities.getValue("radical law")
        val earth = densities.getValue("Earth's density")
        val lawGap = disagreement(law.getValue(SIDE), law.getValue(2 * SIDE))
        val earthGap = disagreement(earth.getValue(SIDE), earth.getValue(2 * SIDE))
        println(
            "X1C one pane, two grids: the radical law's densities disagree by " +
                "${(lawGap * 100).oneDecimal()}%, Earth's density by ${(earthGap * 100).oneDecimal()}%"
        )
        // The control, and the reason this chunk exists: the same map at the same size on the same
        // screen, drawn from two grids, is two different maps under a rule that keeps a share of
        // whatever was traced.
        assertTrue(
            lawGap > ACROSS_RESOLUTIONS_BAND,
            "the radical law agreed across grids to ${(lawGap * 100).oneDecimal()}%, so this " +
                "measurement cannot tell the two rules apart"
        )
        assertTrue(
            earthGap <= ACROSS_RESOLUTIONS_BAND,
            "one pane drew ${(earthGap * 100).oneDecimal()}% more ink from one grid than the other"
        )
    }

    // ---- what the crowding rule spreads, printed ------------------------------------------------

    @Test
    fun `how the drawn mouths are spread, against the reference's own spacing`() {
        SEEDS.forEach { seed ->
            val map = world(seed)
            val sheet = MapSheet.UNGENERALISED
            val chosen = RiverSelection.select(map, sheet)
            listOf(RiverInk.EARTH_DENSITY, RiverInk.RADICAL_LAW).forEach { rule ->
                val drawn = RiverSelection.drawnOn(map, sheet, rule)
                val perSquare = HashMap<Long, Int>()
                drawn.forEach { river ->
                    val end = river.cells[river.cells.size - 1]
                    val across = (end % map.width * map.config.cellWidthKm / chosen.crowdingPitchKm)
                        .toLong()
                    val down = (end / map.width * map.config.cellHeightKm / chosen.crowdingPitchKm)
                        .toLong()
                    val key = (down shl 32) or across
                    perSquare[key] = (perSquare[key] ?: 0) + 1
                }
                val fullest = perSquare.values.maxOrNull() ?: 0
                println(
                    "X1C $seed, ${rule.name}: ${drawn.size} courses over ${perSquare.size} squares " +
                        "of the ${chosen.crowdingPitchKm.round()} km crowding lattice, " +
                        "fullest square $fullest, mean ${(drawn.size.toDouble() /
                            maxOf(1, perSquare.size)).oneDecimal()}. Natural Earth at 1:50M has " +
                        "2.41 courses per million km2, which is one to every 645 km of spacing."
                )
            }
        }
    }

    private fun disagreement(first: Double, second: Double): Double =
        abs(first - second) / maxOf(first, second)

    private fun Double.round(): Long = kotlin.math.round(this).toLong()

    private fun Double.oneDecimal(): String {
        val tenths = kotlin.math.round(this * 10.0).toLong()
        return "${tenths / 10}.${abs(tenths % 10)}"
    }

    /** Four significant figures below one, which is where every density here lives. */
    private fun Double.sig(): String {
        val millionths = kotlin.math.round(this * 1e6).toLong()
        return "${millionths}e-6"
    }
}
