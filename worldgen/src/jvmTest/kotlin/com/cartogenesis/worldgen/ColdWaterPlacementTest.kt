package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ClimateStage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Where the rising water puts the sea's cold, against where Earth has it, on the four standard
 * worlds at 512.
 *
 * Two bars, each taken from Earth before any world was read:
 *  - **The coldest subtropical eastern-boundary water lies at 15 to 35 degrees**, in each
 *    hemisphere that has a basin there: where the Canary, California, Humboldt and Benguela
 *    systems have their bands of most active upwelling (Chavez and Messié 2009, *Prog. Oceanogr.*
 *    83, 80-96), searched for over 12 to 42 degrees, so the band asked for is narrower than the one
 *    searched, as `OceanCurrentAuditTest` asks it of the author's world at 2048.
 *  - **An equatorial basin is colder on its eastern side than its western**, as the Pacific's and
 *    the Atlantic's cold tongues are: the mean anomaly of the eastern thirds of every east-west run
 *    of water within 4 degrees of the equator is below the western thirds'. A sign bar, since the
 *    tongue's depth on Earth, several degrees, comes of a zonally tilted equatorial thermocline this
 *    model does not have.
 *
 * Shown failing on 3875e7a's ocean, which had no rising water: seed 42's northern coldest coast lay
 * at 36.4 degrees and seed 7's equator was warmer in its east than its west.
 */
class ColdWaterPlacementTest : BorrowsSharedWorlds() {

    private companion object {
        const val COLD_COAST_EQUATORWARD_DEGREES = 15f
        const val COLD_COAST_POLEWARD_DEGREES = 35f
        const val SEARCH_EQUATORWARD_DEGREES = 12f
        const val SEARCH_POLEWARD_DEGREES = 42f
        const val EQUATORIAL_BAND_DEGREES = 4f
        val SEEDS = listOf(7L, 42L, 1234L, 99L)
    }

    @Test
    fun `the coldest subtropical eastern-boundary water lies at 15 to 35 degrees`() {
        val failures = ArrayList<String>()
        for (seed in SEEDS) {
            val world = SharedWorlds.world(WorldGenConfig(seed = seed, width = 512, height = 512))
            for (hemisphere in listOf(1f, -1f)) {
                val (coldestC, latitude) = coldestEasternBoundary(world, hemisphere) ?: continue
                println("COLD WATER seed $seed ${if (hemisphere > 0) "north" else "south"}: coldest eastern-boundary water %.2f C at %.1f degrees".format(coldestC, latitude))
                if (abs(latitude) !in COLD_COAST_EQUATORWARD_DEGREES..COLD_COAST_POLEWARD_DEGREES) {
                    failures += "seed $seed: the coldest eastern-boundary water, %.2f C, lies at %.1f degrees".format(coldestC, latitude)
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `an equatorial basin is colder in its east than its west`() {
        val failures = ArrayList<String>()
        for (seed in SEEDS) {
            val world = SharedWorlds.world(WorldGenConfig(seed = seed, width = 512, height = 512))
            val (eastC, westC) = equatorialThirds(world) ?: continue
            println("COLD WATER seed $seed equator: eastern thirds %+.2f C, western thirds %+.2f C".format(eastC, westC))
            if (!(eastC < westC)) failures += "seed $seed: the equator's eastern thirds %+.2f C against its western %+.2f".format(eastC, westC)
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /** The coldest anomaly at the eastern end of a basin-long run of water at 12 to 42 degrees in one hemisphere, and its latitude; null with no such run. */
    private fun coldestEasternBoundary(world: WorldMap, hemisphere: Float): Pair<Float, Float>? {
        val across = world.width
        val down = world.height
        var coldest = Float.POSITIVE_INFINITY
        var at = Float.NaN
        for (row in 0 until down) {
            val latitude = ClimateStage.latitudeOf(row, down)
            if (latitude * hemisphere !in SEARCH_EQUATORWARD_DEGREES..SEARCH_POLEWARD_DEGREES) continue
            for ((start, length) in basinRuns(world, row)) {
                val eastEnd = row * across + (start + length - 1) % across
                if (world.ocean.anomaly.data[eastEnd] < coldest) { coldest = world.ocean.anomaly.data[eastEnd]; at = latitude }
            }
        }
        return if (at.isNaN()) null else coldest to at
    }

    /** The equatorial runs' eastern and western thirds' mean anomaly; null with no equatorial basin. */
    private fun equatorialThirds(world: WorldMap): Pair<Double, Double>? {
        val across = world.width
        var east = 0.0
        var west = 0.0
        var count = 0
        for (row in 0 until world.height) {
            if (abs(ClimateStage.latitudeOf(row, world.height)) > EQUATORIAL_BAND_DEGREES) continue
            for ((start, length) in basinRuns(world, row)) {
                for (k in 0 until length / 3) {
                    west += world.ocean.anomaly.data[row * across + (start + k) % across]
                    east += world.ocean.anomaly.data[row * across + (start + length - 1 - k) % across]
                    count++
                }
            }
        }
        return if (count == 0) null else east / count to west / count
    }

    /** A row's runs of water between two shores at least [OceanSense.SHORTEST_BASIN_KM] long: start column and length. */
    private fun basinRuns(world: WorldMap, row: Int): List<Pair<Int, Int>> {
        val across = world.width
        val shortest = (OceanSense.SHORTEST_BASIN_KM / world.config.scale.cellWidthKm(across)).toInt()
        val isLand = world.sea.isLand
        val firstLand = (0 until across).firstOrNull { isLand[row * across + it] } ?: return emptyList()
        val runs = ArrayList<Pair<Int, Int>>()
        var offset = 1
        while (offset <= across) {
            if (isLand[row * across + (firstLand + offset) % across]) { offset++; continue }
            val start = offset
            while (offset <= across && !isLand[row * across + (firstLand + offset) % across]) offset++
            if (offset - start >= shortest) runs += (firstLand + start) % across to offset - start
        }
        return runs
    }
}
