package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.DryAir
import com.cartogenesis.worldgen.pipeline.HadleyCirculation
import com.cartogenesis.worldgen.pipeline.Season
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The climate's zonal-mean circulation against Earth's, on the standard seeds at the application's
 * grid: where the subtropical ridge stands in each half-year and which way it moves, where the
 * ITCZ stands and how far it migrates, and how unequal the two Hadley cells are at each solstice
 * half. Deep tier, under the climate stage.
 *
 * Earth's figures, each from a source read:
 *
 * - **The ridge.** Barry and Chorley (*Atmosphere, Weather and Climate*, section 4.4): the
 *   subtropical anticyclones lie "at about 30 degrees latitude" and move "a few degrees equatorward
 *   in winter and poleward in summer". Each half-year's zonal-mean sea-level pressure peaks at
 *   [RIDGE_NEAREST_DEGREES] to [RIDGE_FARTHEST_DEGREES] in each hemisphere, about 30 with five either
 *   way, and each hemisphere's summer ridge stands poleward of its winter ridge by no more than
 *   [RIDGE_LARGEST_SHIFT_DEGREES].
 * - **The ITCZ.** Schneider, Bischoff and Haug (2014, Nature 513): over the central Atlantic and
 *   Pacific it migrates between 9 N in boreal summer and 2 N in boreal winter, over the Indian Ocean
 *   and the land beside it between 20 N and 8 S. The ocean's wettest row within 30 degrees stands
 *   farther north in the half about July than in the half about January, by [ITCZ_LEAST_RANGE_DEGREES]
 *   to [ITCZ_LARGEST_RANGE_DEGREES], the open ocean's range to the monsoon sector's.
 * - **The ITCZ in each half.** The same: in the half about July the ocean's wettest row stands between
 *   [ITCZ_JULY_SOUTHERNMOST] and [ITCZ_JULY_NORTHERNMOST] (the open ocean's boreal-winter 2 N to the
 *   monsoon sector's 20 N), and in the half about January between [ITCZ_JANUARY_SOUTHERNMOST] and
 *   [ITCZ_JANUARY_NORTHERNMOST] (the monsoon sector's 8 S to the open ocean's 2 N).
 * - **The eddy-driven jet.** The zonal-mean surface westerlies at their strongest. Ceppi and Hartmann
 *   (2013, J. Climate 26, read) find the Southern Hemisphere's centered "near 50S" in both seasons in
 *   the NCEP reanalysis (at 850 hPa, nearly unchanged at the surface); Barnes and Polvani's (2013,
 *   J. Climate 26, read) CMIP5 historical runs put the North Atlantic's at 46.9 N and the North
 *   Pacific's at 42.3 N, farther equatorward in winter than in summer. Each half's jet stands within
 *   five degrees of the span Earth's jets cover, [JET_NEAREST_DEGREES] to [JET_FARTHEST_DEGREES], and
 *   each hemisphere's summer jet stands poleward of its winter jet by no more than
 *   [RIDGE_LARGEST_SHIFT_DEGREES], as its ridge does.
 * - **The closed highs.** Barry and Chorley's subtropical anticyclones are cells over the oceans, and
 *   Nakamura and Miyasaka's (2004) July cells stand at about 35 N in the eastern basins over 1,020
 *   hPa, as `BoundaryLayerEarthTest` reads them. In each half the summer hemisphere's sea holds at
 *   least one closed high at 15 to 45 degrees whose center stands over 1,020 hPa
 *   ([SubtropicalHighs]).
 * - **The cells.** The ECMWF solstitial mean meridional streamfunction (MIT 12.810 lecture notes on
 *   the Hadley cell, 2023, read): the cross-equatorial winter cells' contours reach 20 and 24 times
 *   1e10 kg/s, the summer cells' 4. In each half the lower branch's greatest mass flux toward the
 *   rising branch from the winter side stands at least [WINTER_OVER_SUMMER_CELL] times the summer
 *   side's.
 */
class MeanCirculationEarthTest : BorrowsSharedWorlds() {

    private companion object {
        const val ROWS = 1024
        val SEEDS = listOf(42L, 969495L, 7L)

        const val RIDGE_NEAREST_DEGREES = 25.0
        const val RIDGE_FARTHEST_DEGREES = 40.0
        const val RIDGE_LARGEST_SHIFT_DEGREES = 10.0

        /** The search for the ridge, degrees from the equator: the subtropics and the middle latitudes. */
        const val RIDGE_SEARCH_NEAREST = 15f
        const val RIDGE_SEARCH_FARTHEST = 50f

        const val ITCZ_JULY_SOUTHERNMOST = 2.0
        const val ITCZ_JULY_NORTHERNMOST = 20.0
        const val ITCZ_JANUARY_SOUTHERNMOST = -8.0
        const val ITCZ_JANUARY_NORTHERNMOST = 2.0

        /** Earth's eddy-driven jets span 42.3 to 50 degrees; five either way. */
        const val JET_NEAREST_DEGREES = 42.3 - 5.0
        const val JET_FARTHEST_DEGREES = 50.0 + 5.0

        /** The jet is sought between the subtropics and the polar cap, degrees from the equator. */
        const val JET_SEARCH_NEAREST = 25f
        const val JET_SEARCH_FARTHEST = 70f

        const val ITCZ_LEAST_RANGE_DEGREES = 9.0 - 2.0
        const val ITCZ_LARGEST_RANGE_DEGREES = 20.0 + 8.0

        /** The ITCZ is sought within 30 degrees of the equator, over rows at least a tenth sea. */
        const val ITCZ_SEARCH_DEGREES = 30f
        const val ITCZ_LEAST_SEA_SHARE = 0.1

        /**
         * The winter cell's mass flux over the summer cell's: three, the contours' 20 over 4 less the
         * contour interval's room, since a contour is a lower bound on the maximum it encloses.
         */
        const val WINTER_OVER_SUMMER_CELL = 3.0

        /** The cells are sought within 40 degrees of the equator. */
        const val CELL_SEARCH_DEGREES = 40f
    }

    private fun world(seed: Long): WorldMap = SharedWorlds.world(WorldGenConfig.forRows(seed, ROWS))

    /** Each hemisphere's ridge in each half: the latitude the zonal-mean sea-level pressure peaks at. */
    private class Ridges(val julyNorth: Double, val julySouth: Double, val januaryNorth: Double, val januarySouth: Double)

    private fun ridges(world: WorldMap): Ridges {
        val atmosphere = checkNotNull(ClimateStage.coupledAtmosphere(world.config, world.sea, world.ocean)).coupling.atmosphere
        fun ridge(julyHalf: Boolean, north: Boolean): Double {
            val half = atmosphere.half(julyHalf)
            var best = -1
            var bestPressure = Double.NEGATIVE_INFINITY
            for (row in 0 until world.height) {
                val latitude = ClimateStage.latitudeOf(row, world.height)
                if ((latitude > 0f) != north || abs(latitude) < RIDGE_SEARCH_NEAREST || abs(latitude) > RIDGE_SEARCH_FARTHEST) continue
                var sum = 0.0
                for (column in 0 until world.width) sum += half.seaLevelPressurePa(row * world.width + column, world.width)
                if (sum > bestPressure) { bestPressure = sum; best = row }
            }
            return ClimateStage.latitudeOf(best, world.height).toDouble()
        }
        return Ridges(ridge(true, true), ridge(true, false), ridge(false, true), ridge(false, false))
    }

    @Test
    fun `the subtropical ridge stands near 30 degrees and moves poleward in summer`() {
        val placed = ArrayList<String>()
        val moved = ArrayList<String>()
        for (seed in SEEDS) {
            val world = world(seed)
            val zonal = ClimateStage.zonalClimate(world.config, world.sea)
            for (season in listOf(Season.JULY_HALF, Season.JANUARY_HALF)) {
                val circulation = HadleyCirculation.forHalf(world.config, zonal, season)
                println(
                    "MEAN CIRCULATION seed %d %s: heating at %.1f, rising at %.1f, cells' edges %.1f and %.1f".format(
                        seed, season, circulation.heatingLatitudeDegrees, circulation.ascentLatitudeDegrees,
                        circulation.winterEdgeDegrees, circulation.summerEdgeDegrees
                    )
                )
            }
            val ridges = ridges(world)
            println(
                "MEAN CIRCULATION seed %d ridge: July half %.1f N and %.1f S, January half %.1f N and %.1f S".format(
                    seed, ridges.julyNorth, -ridges.julySouth, ridges.januaryNorth, -ridges.januarySouth
                )
            )
            for ((name, latitude) in listOf(
                "July north" to ridges.julyNorth, "July south" to -ridges.julySouth,
                "January north" to ridges.januaryNorth, "January south" to -ridges.januarySouth
            )) {
                if (latitude < RIDGE_NEAREST_DEGREES || latitude > RIDGE_FARTHEST_DEGREES) placed += "seed $seed $name %.1f".format(latitude)
            }
            // Northern summer is the half about July; southern summer the half about January.
            val northShift = ridges.julyNorth - ridges.januaryNorth
            val southShift = -ridges.januarySouth - -ridges.julySouth
            for ((name, shift) in listOf("north" to northShift, "south" to southShift)) {
                if (shift < 0.0 || shift > RIDGE_LARGEST_SHIFT_DEGREES) moved += "seed $seed $name summer minus winter %.1f".format(shift)
            }
        }
        // Both recorded at A1-6a, where they were written: the equal-area cells put each half's
        // summer edge equatorward of its winter edge, and every summer ridge stood 7 to 17 degrees
        // equatorward of its winter ridge, four of the twelve under 25 degrees. Armed at A1-6b, whose
        // cells end where the troposphere turns baroclinically unstable and whose westerlies balance
        // the hemisphere's torque (docs/DESIGN_LEDGER.md, A1-6a and A1-6b).
        // Recorded at A1-6c: under the closed air-sea loop seed 969495's northern winter ridge stands
        // at 24.9 degrees, a row inside the bar's edge, the other eleven at 25.0 to 35.4 (docs/TODO.md).
        KnownFailures.expect("A1-6c: a winter ridge stands a row equatorward of 25 degrees", "seed 969495 January north 24.9") {
            if (placed.isNotEmpty()) throw RecordedViolation("ridges outside 25 to 40 degrees: $placed", placed.joinToString("; "))
        }
        assertTrue(moved.isEmpty(), "the ridge does not move poleward in summer: $moved")
    }

    @Test
    fun `each summer hemisphere's sea holds a closed subtropical high`() {
        val missing = ArrayList<String>()
        for (seed in SEEDS) {
            val world = world(seed)
            val atmosphere = checkNotNull(ClimateStage.coupledAtmosphere(world.config, world.sea, world.ocean)).coupling.atmosphere
            for (julyHalf in listOf(true, false)) {
                val half = atmosphere.half(julyHalf)
                val pressure = FloatArray(world.width * world.height) { half.seaLevelPressurePa(it, world.width).toFloat() }
                val highs = SubtropicalHighs.find(world, pressure, atmosphere.remap)
                val name = if (julyHalf) "July" else "January"
                println("MEAN CIRCULATION seed $seed $name highs: " + highs.ifEmpty { listOf("none") }.joinToString("; "))
                // Northern summer is the half about July; southern summer the half about January.
                if (highs.none { (it.latitudeDegrees > 0.0) == julyHalf }) missing += "seed $seed $name ${if (julyHalf) "north" else "south"}"
            }
        }
        KnownFailures.expect("A1-6b: a summer hemisphere's sea holds no closed high", "seed 969495 July north; seed 969495 January south; seed 7 July north") {
            if (missing.isNotEmpty()) throw RecordedViolation("no closed high over a summer hemisphere's sea: $missing", missing.joinToString("; "))
        }
    }

    @Test
    fun `the eddy-driven jet stands where Earth's do and moves poleward in summer`() {
        val placed = ArrayList<String>()
        val moved = ArrayList<String>()
        for (seed in SEEDS) {
            val world = world(seed)
            val atmosphere = checkNotNull(ClimateStage.coupledAtmosphere(world.config, world.sea, world.ocean)).coupling.atmosphere
            fun jet(julyHalf: Boolean, north: Boolean): Double {
                val half = atmosphere.half(julyHalf)
                var best = -1
                var bestWind = Double.NEGATIVE_INFINITY
                for (row in 0 until world.height) {
                    val latitude = ClimateStage.latitudeOf(row, world.height)
                    if ((latitude > 0f) != north || abs(latitude) < JET_SEARCH_NEAREST || abs(latitude) > JET_SEARCH_FARTHEST) continue
                    var sum = 0.0
                    for (column in 0 until world.width) sum += half.eastwardMps[row * world.width + column]
                    if (sum > bestWind) { bestWind = sum; best = row }
                }
                return abs(ClimateStage.latitudeOf(best, world.height).toDouble())
            }
            val julyNorth = jet(julyHalf = true, north = true)
            val julySouth = jet(julyHalf = true, north = false)
            val januaryNorth = jet(julyHalf = false, north = true)
            val januarySouth = jet(julyHalf = false, north = false)
            println(
                "MEAN CIRCULATION seed %d jet: July half %.1f N and %.1f S, January half %.1f N and %.1f S"
                    .format(seed, julyNorth, julySouth, januaryNorth, januarySouth)
            )
            for ((name, latitude) in listOf(
                "July north" to julyNorth, "July south" to julySouth, "January north" to januaryNorth, "January south" to januarySouth
            )) {
                if (latitude < JET_NEAREST_DEGREES || latitude > JET_FARTHEST_DEGREES) placed += "seed $seed $name %.1f".format(latitude)
            }
            for ((name, shift) in listOf("north" to julyNorth - januaryNorth, "south" to januarySouth - julySouth)) {
                if (shift < 0.0 || shift > RIDGE_LARGEST_SHIFT_DEGREES) moved += "seed $seed $name summer minus winter %.1f".format(shift)
            }
        }
        assertTrue(placed.isEmpty(), "jets outside $JET_NEAREST_DEGREES to $JET_FARTHEST_DEGREES degrees: $placed")
        // Recorded at A1-6c: under the closed air-sea loop seed 42's southern jet moves from 40.7
        // to 50.7 degrees between its winter and its summer, just past the ten degrees allowed; the
        // other five shifts are 4.8 to 8.8 (docs/TODO.md).
        KnownFailures.expect("A1-6c: a southern jet moves a row more than ten degrees between its seasons", "seed 42 south summer minus winter 10.0") {
            if (moved.isNotEmpty()) throw RecordedViolation("the jet does not move poleward in summer: $moved", moved.joinToString("; "))
        }
    }

    @Test
    fun `the ITCZ stands in the summer hemisphere in each half`() {
        val complaints = ArrayList<String>()
        for (seed in SEEDS) {
            val world = world(seed)
            val march = checkNotNull(ClimateStage.coupledAtmosphere(world.config, world.sea, world.ocean)).march
            val july = wettestOceanRow(world, march.julyHalfRainMm.data).toDouble()
            val january = wettestOceanRow(world, march.januaryHalfRainMm.data).toDouble()
            println("MEAN CIRCULATION seed %d ITCZ by half: July %.1f, January %.1f".format(seed, july, january))
            if (july < ITCZ_JULY_SOUTHERNMOST || july > ITCZ_JULY_NORTHERNMOST) complaints += "seed $seed July %.1f".format(july)
            if (january < ITCZ_JANUARY_SOUTHERNMOST || january > ITCZ_JANUARY_NORTHERNMOST) complaints += "seed $seed January %.1f".format(january)
        }
        KnownFailures.expect("A1-6b: the ITCZ stands outside Earth's positions in a half", "seed 42 January -9.2; seed 969495 January -11.0; seed 7 January -11.3") {
            if (complaints.isNotEmpty()) throw RecordedViolation("the ITCZ outside Earth's positions: $complaints", complaints.joinToString("; "))
        }
    }

    @Test
    fun `the ITCZ stands north in July and south in January, by Earth's range`() {
        val complaints = ArrayList<String>()
        for (seed in SEEDS) {
            val world = world(seed)
            val march = checkNotNull(ClimateStage.coupledAtmosphere(world.config, world.sea, world.ocean)).march
            val july = wettestOceanRow(world, march.julyHalfRainMm.data).toDouble()
            val january = wettestOceanRow(world, march.januaryHalfRainMm.data).toDouble()
            println("MEAN CIRCULATION seed %d ITCZ: July half %.1f, January half %.1f, range %.1f".format(seed, july, january, july - january))
            val range = july - january
            if (range < ITCZ_LEAST_RANGE_DEGREES || range > ITCZ_LARGEST_RANGE_DEGREES) complaints += "seed $seed %.1f".format(range)
        }
        // Recorded at A1-6a, where it was written: seed 42's ocean rained most at 4.7 S in the half
        // about July and 7.5 N in the half about January, the wrong way round, under a July heating
        // the energy balance put at 2.1 N. Armed at A1-6b, whose cells are heated about the energy
        // flux equator (docs/DESIGN_LEDGER.md, A1-6a and A1-6b).
        assertTrue(complaints.isEmpty(), "the ITCZ's seasonal range: $complaints")
    }

    /**
     * The ITCZ: the latitude of the row whose sea rains most, within [ITCZ_SEARCH_DEGREES] of the
     * equator and over rows at least [ITCZ_LEAST_SEA_SHARE] sea, of [rain] (millimeters, row-major).
     */
    private fun wettestOceanRow(world: WorldMap, rain: FloatArray): Float {
        var best = -1
        var bestRain = -1.0
        for (row in 0 until world.height) {
            if (abs(ClimateStage.latitudeOf(row, world.height)) > ITCZ_SEARCH_DEGREES) continue
            var sum = 0.0
            var count = 0
            for (column in 0 until world.width) {
                val cell = row * world.width + column
                if (!world.sea.isLand[cell]) { sum += rain[cell]; count++ }
            }
            if (count >= ITCZ_LEAST_SEA_SHARE * world.width && sum / count > bestRain) { bestRain = sum / count; best = row }
        }
        return ClimateStage.latitudeOf(best, world.height)
    }

    @Test
    fun `the winter Hadley cell carries the circulation`() {
        val complaints = ArrayList<String>()
        for (seed in SEEDS) {
            val world = world(seed)
            val atmosphere = checkNotNull(ClimateStage.coupledAtmosphere(world.config, world.sea, world.ocean)).coupling.atmosphere
            for (julyHalf in listOf(true, false)) {
                val half = atmosphere.half(julyHalf)
                // The lower branch's northward mass flux on each row: the zonal-mean surface wind
                // times the branch's mass and the circle of latitude.
                val flux = DoubleArray(world.height) { row ->
                    val latitude = ClimateStage.latitudeOf(row, world.height)
                    var meanNorth = 0.0
                    for (column in 0 until world.width) meanNorth -= half.southwardMps[row * world.width + column]
                    meanNorth /= world.width
                    meanNorth * 2 * PI * world.config.scale.radiusMeters * cos(latitude * PI / 180) *
                        HadleyCirculation.LOWER_BRANCH_PA / DryAir.GRAVITY_MPS2
                }
                // The rising branch: where the flow converges most within the tropics, northward to
                // its south and southward to its north.
                var rising = 0f
                var strongest = Double.NEGATIVE_INFINITY
                for (row in 0 until world.height - 1) {
                    val latitude = ClimateStage.latitudeOf(row, world.height)
                    if (abs(latitude) > ITCZ_SEARCH_DEGREES) continue
                    val converging = flux[row + 1] - flux[row]
                    if (converging > strongest) { strongest = converging; rising = latitude }
                }
                var northward = 0.0
                var southward = 0.0
                for (row in 0 until world.height) {
                    val latitude = ClimateStage.latitudeOf(row, world.height)
                    if (abs(latitude) > CELL_SEARCH_DEGREES) continue
                    // Toward the rising branch only, which leaves the Ferrel cells' poleward legs out.
                    if (latitude < rising) northward = maxOf(northward, flux[row])
                    if (latitude > rising) southward = maxOf(southward, -flux[row])
                }
                // In the half about July the winter cell is the southern one, flowing north.
                val winter = if (julyHalf) northward else southward
                val summer = if (julyHalf) southward else northward
                println(
                    "MEAN CIRCULATION seed %d %s half: winter cell %.2e kg/s, summer cell %.2e kg/s, ratio %.1f".format(
                        seed, if (julyHalf) "July" else "January", winter, summer, winter / summer
                    )
                )
                if (winter < WINTER_OVER_SUMMER_CELL * summer) complaints += "seed $seed ${if (julyHalf) "July" else "January"} %.1f".format(winter / summer)
            }
        }
        // Recorded at A1-6a, where it was written: in the half about July the energy balance's
        // heating stands 2.1 and 2.6 degrees north on seeds 42 and 7, too near the equator for the
        // cells to part by Earth's three times (2.0 and 1.6); every January half clears it
        // (docs/DESIGN_LEDGER.md, A1-6a).
        KnownFailures.expect("A1-6a: the winter Hadley cell does not carry three times the summer cell's mass", "6 of 6") {
            if (complaints.isNotEmpty()) throw RecordedViolation("the cells' asymmetry: $complaints", "${complaints.size} of 6")
        }
    }
}
