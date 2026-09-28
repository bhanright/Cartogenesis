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
 *  - **The coldest subtropical eastern-boundary water lies within Earth's upwelling systems'
 *    latitudes**, in each hemisphere that has a basin there. The four systems' upwelling zones as
 *    Abrahams, Schlegel and Smit (2021, *Front. Mar. Sci.* 8, 626411) take them from the studies
 *    before them: the California Current's 33.88 to 42.31 N, the Canary's 18.89 to 32.63 N, the
 *    Humboldt's 10.15 to 37.62 S and the Benguela's 16.39 to 30.13 S, together 10.15 to 42.31
 *    degrees from the equator. Searched for over 5 to 50 degrees, so the band asked for is
 *    narrower than the one searched.
 *  - **An equatorial basin is colder in its east than its west, by a share of Earth's contrast
 *    set by its length.** The equatorial Pacific's warm pool is near 29 C and its cold tongue near
 *    25 C in the annual mean, a zonal difference of about 4 C across the basin (Karnauskas, Seager,
 *    Kaplan, Kushnir and Cane 2009, *J. Climate* 22, 4316-4321). For a temperature that falls
 *    evenly across a basin the eastern third's mean stands two thirds of that below the western
 *    third's; and the thermocline's tilt that makes the contrast is the trades' stress integrated
 *    across the basin, in proportion to its length, so a basin of length `L` is asked for
 *    `(2/3) × 4 C × L / 17,800 km`, the Pacific's equatorial width from 120 E to 80 W. Taken over
 *    the runs of water within 4 degrees of the equator, their lengths averaged. The scaling is by
 *    length alone: the depth of the thermocline and the strength of the trades are held at the
 *    Pacific's, and a one-layer ocean is asked for no less than that.
 *
 * Shown failing on 3875e7a's ocean, which had no rising water (the equator's eastern thirds warmer
 * than its western on seed 7, and colder by 0.1 to 0.6 C on the other three, under any margin the
 * Pacific scales to), and on this branch's first closure, which had no equatorial thermocline.
 */
class ColdWaterPlacementTest : BorrowsSharedWorlds() {

    private companion object {
        const val COLD_COAST_EQUATORWARD_DEGREES = 10.15f
        const val COLD_COAST_POLEWARD_DEGREES = 42.31f
        const val SEARCH_EQUATORWARD_DEGREES = 5f
        const val SEARCH_POLEWARD_DEGREES = 50f
        const val EQUATORIAL_BAND_DEGREES = 4f
        const val PACIFIC_ZONAL_CONTRAST_C = 4.0
        const val PACIFIC_EQUATORIAL_WIDTH_KM = 17_800.0
        const val THIRDS_OF_AN_EVEN_FALL = 2.0 / 3.0
        val SEEDS = listOf(7L, 42L, 1234L, 99L)
    }

    @Test
    fun `the coldest subtropical eastern-boundary water lies within Earth's upwelling systems' latitudes`() {
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
    fun `an equatorial basin is colder in its east than its west by its share of the Pacific's contrast`() {
        val failures = ArrayList<String>()
        for (seed in SEEDS) {
            val world = SharedWorlds.world(WorldGenConfig(seed = seed, width = 512, height = 512))
            val thirds = equatorialThirds(world) ?: continue
            val marginC = THIRDS_OF_AN_EVEN_FALL * PACIFIC_ZONAL_CONTRAST_C * thirds.meanLengthKm / PACIFIC_EQUATORIAL_WIDTH_KM
            val contrastC = thirds.westC - thirds.eastC
            println("COLD WATER seed $seed equator: eastern thirds %+.2f C, western thirds %+.2f C, colder by %.2f C against %.2f for basins %.0f km long"
                .format(thirds.eastC, thirds.westC, contrastC, marginC, thirds.meanLengthKm))
            if (!(contrastC >= marginC)) failures += "seed $seed: the equator's east is colder than its west by %.2f C, under the %.2f its basins' length asks".format(contrastC, marginC)
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /** The coldest anomaly at the eastern end of a basin-long run of water between the search latitudes in one hemisphere, and its latitude; null with no such run. */
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

    /** The equatorial runs' eastern and western thirds' mean anomaly, degrees Celsius, and the runs' mean length in kilometers. */
    private class Thirds(val eastC: Double, val westC: Double, val meanLengthKm: Double)

    /** [Thirds] over every basin-long run of water within [EQUATORIAL_BAND_DEGREES] of the equator; null with none. */
    private fun equatorialThirds(world: WorldMap): Thirds? {
        val across = world.width
        var east = 0.0
        var west = 0.0
        var count = 0
        var lengthSum = 0.0
        var runs = 0
        for (row in 0 until world.height) {
            if (abs(ClimateStage.latitudeOf(row, world.height)) > EQUATORIAL_BAND_DEGREES) continue
            for ((start, length) in basinRuns(world, row)) {
                lengthSum += length * world.config.scale.cellWidthKm(across)
                runs++
                for (k in 0 until length / 3) {
                    west += world.ocean.anomaly.data[row * across + (start + k) % across]
                    east += world.ocean.anomaly.data[row * across + (start + length - 1 - k) % across]
                    count++
                }
            }
        }
        return if (count == 0) null else Thirds(east / count, west / count, lengthSum / runs)
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
