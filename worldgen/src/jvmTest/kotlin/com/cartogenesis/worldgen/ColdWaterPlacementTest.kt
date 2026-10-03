package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.OceanHeat
import com.cartogenesis.worldgen.pipeline.OceanStage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Where the rising water puts the sea's cold, against where Earth has it, on the four standard
 * worlds at [SharedWorlds.DETAIL_ROWS]. Both figures move with the cell, measured at 256 rows at
 * Q2b: an equatorial basin is a run of water within four degrees of the equator, and a strait a
 * cell or two wide joins or parts two of them, so seed 7's basins' stress times length read -669
 * N/m² km there against -323 here; and the equatorial tongue reaches seed 42's eastern shore on
 * these cells and not on those (docs/DESIGN_LEDGER.md, Q2b).
 *
 * Two bars, each taken from Earth before any world was read:
 *  - **The coldest subtropical eastern-boundary water lies within Earth's upwelling systems'
 *    latitudes**, in each hemisphere that has a basin there. The four systems' upwelling zones as
 *    Abrahams, Schlegel and Smit (2021, *Front. Mar. Sci.* 8, 626411) take them from the studies
 *    before them: the California Current's 33.88 to 42.31 N, the Canary's 18.89 to 32.63 N, the
 *    Humboldt's 10.15 to 37.62 S and the Benguela's 16.39 to 30.13 S, together 10.15 to 42.31
 *    degrees from the equator. Searched for from the equatorial band's edge to 50 degrees, so the
 *    band asked for is narrower than the one searched.
 *
 *    **Its domain was clarified after the worlds were read.** It measures the subtropics' coastal
 *    Ekman upwelling, and its search began at 5 degrees, inside the equatorial closure's own band
 *    (`OceanStage.subsurfaceTemperatures`), so once the equator had a cold tongue it caught the
 *    tongue where it met a shore, at 5.1 N on seed 42: two phenomena in one search. The search now
 *    starts outside twice the equatorial deformation radius, `2 sqrt(c/2β)`, in degrees on the
 *    planet at hand, 7.9 at this generator's radius. Earth's band is still the pass window, and it
 *    can still fail: subtropical upwelling outside it fails it.
 *  - **An equatorial basin is colder in its east than its west, by the share of Earth's contrast
 *    its trades' tilt asks.** The equatorial Pacific's warm pool is near 29 C and its cold tongue
 *    near 25 C in the annual mean, a zonal difference of about 4 C across the basin (Karnauskas,
 *    Seager, Kaplan, Kushnir and Cane 2009, *J. Climate* 22, 4316-4321). For a temperature that
 *    falls evenly across a basin the eastern third's mean stands two thirds of that below the
 *    western third's. The thermocline's tilt that makes the contrast is the along-equator stress
 *    integrated across the basin (`h² - H² = (2/ρg') ∫τ dx`), so a basin is asked for
 *    `(2/3) × 4 C × (τ L) / (τ_P L_P)`: `τ` its mean eastward stress along the equator, `L` its
 *    length, and the Pacific's `τ_P L_P` the Wyrtki and Meyers climatology's 0.025 N/m² at 110 W and
 *    0.055 at 140 W (as McPhaden and Taft 1988, *J. Phys. Oceanogr.* 18, 1713-1732, quote it),
 *    averaged to 0.040 westward, over its equatorial width from 120 E to 80 W, 17,800 km. Two points
 *    in the strong central and eastern Pacific stand for the whole, so the Pacific's stress is if
 *    anything overstated and the bar understated a little. Taken over the runs of water within 4
 *    degrees of the equator, with their `τ L` averaged by length.
 *
 *    **This bar was corrected after the worlds were read.** Set first by length alone, it held the
 *    trades at the Pacific's everywhere, which left the stress out of a tilt that is the stress's
 *    integral: seed 1234's main equatorial basin, 6,275 km of water under a mean stress of
 *    -0.0065 N/m² where the regional wind all but cancels the trades, was asked for 0.94 C of a
 *    tongue its trades cannot tilt. It can still fail: a basin under strong trades with no tongue
 *    fails it, and a westerly basin whose east is warmer passes only as far as its stress says.
 *
 *    **It fails today and is recorded** ([EQUATORIAL_TONGUE_SHALLOW]): seeds 7, 42 and 99 give 1.77,
 *    2.01 and 1.72 C against 2.84, 3.21 and 3.38, 51 to 63% of their margins. They stood at 2.19,
 *    2.28 and 1.98 until the tilt was made to keep the warm layer's volume, its mean depth rather
 *    than the mean of its square, which deepens the thermocline and draws less from beneath it; the
 *    trades' leg turning through zero at the equator moved them by 0.02 C at most. And 1.17, 1.31
 *    and 1.14 before the water beneath the thermocline was taken from its base. The one-layer
 *    closure's water beneath the thermocline is still warmer than Earth's, and the regional wind's
 *    equatorial gales inflate `τ L` in some basins (docs/TODO.md).
 *
 * Shown failing on 3875e7a's ocean, which had no rising water (the equator's eastern thirds warmer
 * than its western on seed 7, and colder by 0.1 to 0.6 C on the other three), and on this branch's
 * first closure, which had no equatorial thermocline.
 */
class ColdWaterPlacementTest : BorrowsSharedWorlds() {

    private companion object {
        const val COLD_COAST_EQUATORWARD_DEGREES = 10.15f
        const val COLD_COAST_POLEWARD_DEGREES = 42.31f
        const val SEARCH_POLEWARD_DEGREES = 50f

        /** The equatorial closure's band on either side of the equator, in its own deformation radii. */
        const val EQUATORIAL_RADII_EXCLUDED = 2.0

        /**
         * Failing on square cells since Q2: seed 42's coldest northern eastern-boundary water, -2.22
         * C, lies at 7.9 degrees, in the equatorial cold tongue that now reaches the basin's eastern
         * shore colder than the subtropical upwelling north of it (the tongue's eastern thirds read
         * -2.03 C against -1.61 on the 512 by 512 grid, where the coldest stood at 26.5 degrees). The
         * cause is not isolated; the ocean is still solved on its own grid of cells twice as wide as
         * tall on the ground, which is the switch's next chunk (docs/DESIGN_LEDGER.md, Q2).
         */
        const val TONGUE_REACHES_THE_EASTERN_BOUNDARY =
            "the currents: on square cells seed 42's coldest northern eastern-boundary water is the equatorial tongue's"

        const val EQUATORIAL_TONGUE_SHALLOW =
            "the currents: the equatorial cold tongue is shallower than its trades ask, the one-layer closure's deep water too warm"
        const val EQUATORIAL_BAND_DEGREES = 4f
        const val PACIFIC_ZONAL_CONTRAST_C = 4.0
        const val PACIFIC_EQUATORIAL_WIDTH_KM = 17_800.0
        /** The mean of Wyrtki and Meyers' 0.025 and 0.055 N/m², westward. */
        const val PACIFIC_EQUATORIAL_STRESS_N_PER_M2 = -0.040
        const val THIRDS_OF_AN_EVEN_FALL = 2.0 / 3.0
        val SEEDS = SharedWorlds.STANDARD_SEEDS
    }

    @Test
    fun `the coldest subtropical eastern-boundary water lies within Earth's upwelling systems' latitudes`() {
        val failures = ArrayList<String>()
        for (seed in SEEDS) {
            val world = SharedWorlds.world(WorldGenConfig.forRows(seed, SharedWorlds.DETAIL_ROWS))
            for (hemisphere in listOf(1f, -1f)) {
                val (coldestC, latitude) = coldestEasternBoundary(world, hemisphere) ?: continue
                println("COLD WATER seed $seed ${if (hemisphere > 0) "north" else "south"}: coldest eastern-boundary water %.2f C at %.1f degrees".format(coldestC, latitude))
                if (abs(latitude) !in COLD_COAST_EQUATORWARD_DEGREES..COLD_COAST_POLEWARD_DEGREES) {
                    failures += "seed $seed: the coldest eastern-boundary water, %.2f C, lies at %.1f degrees".format(coldestC, latitude)
                }
            }
        }
        // Re-recorded at L1, whose rifts are Earth's half-grabens and the same at every grid (docs/DESIGN_LEDGER.md, L1).
        KnownFailures.expect(TONGUE_REACHES_THE_EASTERN_BOUNDARY, "seed 42: the coldest eastern-boundary water, -2.23 C, lies at 7.9 degrees") {
            if (failures.isNotEmpty()) throw RecordedViolation(failures.joinToString("\n"), failures.joinToString("; "))
        }
    }

    @Test
    fun `an equatorial basin is colder in its east than its west by its trades' share of the Pacific's contrast`() {
        val failures = ArrayList<String>()
        val shortOf = ArrayList<String>()
        for (seed in SEEDS) {
            val world = SharedWorlds.world(WorldGenConfig.forRows(seed, SharedWorlds.DETAIL_ROWS))
            val thirds = equatorialThirds(world) ?: continue
            val marginC = THIRDS_OF_AN_EVEN_FALL * PACIFIC_ZONAL_CONTRAST_C * thirds.meanStressLength /
                (PACIFIC_EQUATORIAL_STRESS_N_PER_M2 * PACIFIC_EQUATORIAL_WIDTH_KM)
            val contrastC = thirds.westC - thirds.eastC
            println("COLD WATER seed $seed equator: eastern thirds %+.2f C, western thirds %+.2f C, colder by %.2f C against %.2f for basins' mean stress times length %+.0f N/m² km"
                .format(thirds.eastC, thirds.westC, contrastC, marginC, thirds.meanStressLength))
            if (!(contrastC >= marginC)) {
                failures += "seed $seed: the equator's east is colder than its west by %.2f C, under the %.2f its basins' trades ask".format(contrastC, marginC)
                shortOf += "seed $seed %.2f of %.2f C".format(contrastC, marginC)
            }
        }
        // Re-recorded on square cells at Q2, the ocean still solved on its own grid (docs/DESIGN_LEDGER.md, Q2).
        // Re-recorded at L1, whose rifts are Earth's half-grabens and the same at every grid (docs/DESIGN_LEDGER.md, L1).
        KnownFailures.expect(EQUATORIAL_TONGUE_SHALLOW, "seed 7 1.92 of 3.08 C; seed 42 1.97 of 3.07 C; seed 99 1.76 of 3.47 C") {
            if (failures.isNotEmpty()) throw RecordedViolation(failures.joinToString("\n"), shortOf.joinToString("; "))
        }
    }

    /** The coldest anomaly at the eastern end of a basin-long run of water between the search latitudes in one hemisphere, and its latitude; null with no such run. */
    private fun coldestEasternBoundary(world: WorldMap, hemisphere: Float): Pair<Float, Float>? {
        val across = world.width
        val down = world.height
        var coldest = Float.POSITIVE_INFINITY
        var at = Float.NaN
        val scale = world.config.scale
        val searchEquatorward = (EQUATORIAL_RADII_EXCLUDED *
            OceanHeat.deformationRadiusMeters(0.0, scale.radiusMeters) / scale.metersPerDegreeLatitude).toFloat()
        for (row in 0 until down) {
            val latitude = ClimateStage.latitudeOf(row, down)
            if (latitude * hemisphere !in searchEquatorward..SEARCH_POLEWARD_DEGREES) continue
            for ((start, length) in basinRuns(world, row)) {
                val eastEnd = row * across + (start + length - 1) % across
                if (world.ocean.anomaly.data[eastEnd] < coldest) { coldest = world.ocean.anomaly.data[eastEnd]; at = latitude }
            }
        }
        return if (at.isNaN()) null else coldest to at
    }

    /**
     * The equatorial runs' eastern and western thirds' mean anomaly, degrees Celsius, and their
     * mean along-equator stress times length, `τ L` in N/m² km, averaged by length.
     */
    private class Thirds(val eastC: Double, val westC: Double, val meanStressLength: Double)

    /**
     * [Thirds] over every basin-long run of water within [EQUATORIAL_BAND_DEGREES] of the equator,
     * each run's stress the ocean's own annual stress ([OceanStage.annualStress]) read on the map's
     * cells; null with no such run.
     */
    private fun equatorialThirds(world: WorldMap): Thirds? {
        val across = world.width
        val stress = OceanStage.annualStress(world.config, world.sea, across, world.height)
        var east = 0.0
        var west = 0.0
        var count = 0
        var stressLengthByLength = 0.0
        var lengthSum = 0.0
        for (row in 0 until world.height) {
            if (abs(ClimateStage.latitudeOf(row, world.height)) > EQUATORIAL_BAND_DEGREES) continue
            for ((start, length) in basinRuns(world, row)) {
                val lengthKm = length * world.config.scale.cellWidthKm(across)
                val meanStress = (0 until length).sumOf { stress.eastward[row * across + (start + it) % across] } / length
                stressLengthByLength += meanStress * lengthKm * lengthKm
                lengthSum += lengthKm
                for (k in 0 until length / 3) {
                    west += world.ocean.anomaly.data[row * across + (start + k) % across]
                    east += world.ocean.anomaly.data[row * across + (start + length - 1 - k) % across]
                    count++
                }
            }
        }
        return if (count == 0) null else Thirds(east / count, west / count, stressLengthByLength / lengthSum)
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
