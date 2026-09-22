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
 * At 512, but for the one clause that cannot be: a rule that answers to the sheet rather than to
 * the grid can only be shown to by drawing two grids at one sheet, so the last case generates a
 * 1024 world as well. The same measurements at 2048, on the author's own world, are
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
         * the same representative fraction and so the same budget per square kilometre of land,
         * and the land area cancels between the budget and the density reported against it. What
         * is left is the last chain each grid could not fit, which is **not bounded** in general —
         * a refused chain can be a trunk and its tributaries and run to thousands of kilometres,
         * and a thin network can run out of courses before the budget runs out. So five per cent
         * is a regression tolerance for networks as rich as these and not a guarantee: the
         * headroom measured on these seeds is 21 to 37 km against budgets near 35 000, and the
         * spread between grids reads 0.0 to 0.1 per cent. The cases below print the headroom, so a
         * world that ran out of network says so rather than passing quietly.
         */
        const val ACROSS_RESOLUTIONS_BAND = 0.05
    }

    /**
     * One seed at one grid, the way the application reaches a grid above 512.
     *
     * Through [WorldGenConfig.atResolution], which carries the tectonic widths measured in cells;
     * constructing the config at 1024 outright would leave a 512-calibrated belt half as wide and
     * the two grids would not be the same world.
     */
    private fun world(seed: Long, side: Int = SIDE): WorldMap =
        WorldGenerationEngine.generateBlocking(
            WorldGenConfig(seed = seed, width = SIDE, height = SIDE).atResolution(side, side)
        )

    /** The sheet a whole world of [cellsAcross] cells is shown on in a [PANE_PIXELS_ACROSS] pane. */
    private fun paneSheet(cellsAcross: Int): MapSheet =
        MapSheet.onScreen(PANE_PIXELS_ACROSS / cellsAcross)

    // ---- the Earth figure --------------------------------------------------------------------

    @Test
    fun `the reference's two tiers agree under the law that carries them`() {
        // Natural Earth's 1:10M tier, 0.004025 km of drawn river per km2 of land, carried to the
        // 1:50M tier by Töpfer's square root: 0.001800 against the 0.001707 measured there, which
        // is five and a half per cent, and is the whole of what the constant's KDoc claims.
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
            // A hair of room for the summation: acceptance adds chain by chain and this adds
            // course by course, over different orders, so the two totals differ in the last bits.
            assertTrue(
                chosen.drawnKilometres <= chosen.budgetKilometres * (1.0 + 1e-9),
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
                // Without this the clause above would pass on a reconstruction that found no
                // trunks at all, which is the one way it could be wrong and look right.
                assertTrue(
                    tributaries > 0,
                    "seed $seed drew ${chosen.drawnCount} courses and not one of them joined " +
                        "another, so the closure clause examined nothing"
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

    // ---- the crowding lattice, against the same selection without it ---------------------------

    @Test
    fun `the crowding lattice spreads the ink, and the same budget without it does not`() {
        SEEDS.forEach { seed ->
            val map = world(seed)
            val sheet = MapSheet.UNGENERALISED
            val spread = RiverSelection.select(map, sheet)
            // The control: the same Earth budget and the same discharge ranking, with the lattice
            // switched off. It is the only thing that differs, so a difference is the lattice's.
            val unspread = RiverSelection.select(map, sheet, spreadByCrowdingLattice = false)
            val byTheLaw = RiverSelection.drawnOn(map, sheet, RiverInk.RADICAL_LAW)

            val fullestSpread = fullestSquare(map, drawnEnds(map, spread), spread.crowdingPitchKm)
            val fullestUnspread =
                fullestSquare(map, drawnEnds(map, unspread), spread.crowdingPitchKm)
            val fullestLaw = fullestSquare(
                map, byTheLaw.map { it.cells[it.cells.size - 1] }, spread.crowdingPitchKm
            )
            println(
                "X1C $seed crowding, on a ${spread.crowdingPitchKm.round()} km lattice: " +
                    "the lattice on draws ${spread.drawnCount} courses with $fullestSpread in its " +
                    "fullest square; off, ${unspread.drawnCount} with $fullestUnspread; the " +
                    "radical law ${byTheLaw.size} with $fullestLaw. Natural Earth at 1:50M draws " +
                    "2.41 courses per million km2, one to every 645 km of spacing."
            )
            assertTrue(
                fullestSpread < fullestUnspread,
                "seed $seed put $fullestSpread courses in its fullest square with the lattice on " +
                    "and $fullestUnspread with it off, so the lattice is doing nothing"
            )
        }
    }

    // ---- that the budget is spent on the biggest rivers ------------------------------------------

    @Test
    fun `the largest river on the map is always drawn`() {
        SEEDS.forEach { seed ->
            val map = world(seed)
            listOf(MapSheet.UNGENERALISED, paneSheet(map.width)).forEach { sheet ->
                val chosen = RiverSelection.select(map, sheet)
                val biggest = map.rivers.rivers.indices
                    .maxByOrNull { RiverSelection.peakWidthRatio(map.rivers.rivers[it]) }!!
                assertTrue(
                    chosen.drawn[biggest],
                    "seed $seed left out the river carrying the most water on the map"
                )
                // And the ink is spent on big rivers rather than spread over small ones: the mean
                // peak of a drawn course against the mean peak of one left out. Printed, because
                // what separates the two distributions is the world's own hypsometry.
                val drawnPeak = chosen.drawn.indices.filter { chosen.drawn[it] }
                    .map { RiverSelection.peakWidthRatio(map.rivers.rivers[it]).toDouble() }
                val leftPeak = chosen.drawn.indices.filter { !chosen.drawn[it] }
                    .map { RiverSelection.peakWidthRatio(map.rivers.rivers[it]).toDouble() }
                println(
                    "X1C $seed at ${sheet.pixelsPerCell} px per cell: mean peak width ratio " +
                        "${(drawnPeak.average() * 1000).oneDecimal()}e-3 over ${drawnPeak.size} " +
                        "drawn against ${(leftPeak.average() * 1000).oneDecimal()}e-3 over " +
                        "${leftPeak.size} left out"
                )
            }
        }
    }

    /** Where each drawn course ends: a mouth on the water, or the junction it joins its trunk at. */
    private fun drawnEnds(map: WorldMap, chosen: RiverSelection.Selection): List<Int> =
        chosen.drawn.indices.filter { chosen.drawn[it] }
            .map { map.rivers.rivers[it].cells.last() }

    /** How many of [ends] fall in the fullest square of a [pitchKm] lattice on the ground. */
    private fun fullestSquare(map: WorldMap, ends: List<Int>, pitchKm: Double): Int {
        val perSquare = HashMap<Long, Int>()
        ends.forEach { end ->
            val across = (end % map.width * map.config.cellWidthKm / pitchKm).toLong()
            val down = (end / map.width * map.config.cellHeightKm / pitchKm).toLong()
            val key = (down shl 32) or across
            perSquare[key] = (perSquare[key] ?: 0) + 1
        }
        return perSquare.values.maxOrNull() ?: 0
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
