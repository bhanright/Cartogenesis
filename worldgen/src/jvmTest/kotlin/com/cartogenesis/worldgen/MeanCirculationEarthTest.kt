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
 * - **The highs' strength and latitude.** The NCEP/NCAR reanalysis's long-term monthly mean
 *   sea-level pressure, 1991 to 2020 (NOAA PSL, `slp.mon.ltm.nc`, read through its OPeNDAP service
 *   with its land mask), averaged over the half-years April to September and October to March: in
 *   each half and hemisphere the highest point over the sea at 15 to 45 degrees stands 11.9 and
 *   12.3 hPa over the planet's area mean in the half about July (north, south) and 12.7 and 11.0 in
 *   the half about January, at 35.0, 32.5, 35.0 and 32.5 degrees. The worlds' four figures pooled
 *   over the seeds stand within Earth's span ([HIGH_STRENGTH_LEAST_HPA] to
 *   [HIGH_STRENGTH_MOST_HPA]; [HIGH_LATITUDE_NEAREST] to [HIGH_LATITUDE_FARTHEST]), each read over its
 *   own planet's area mean: the worlds' is referred to 1,013.25 hPa, Earth's reanalysis reads 1,011.5.
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

        /** Earth's highs over the planet's mean, the least and the most of its four half-and-hemisphere figures (NCEP/NCAR, 1991 to 2020), hPa. */
        const val HIGH_STRENGTH_LEAST_HPA = 11.0
        const val HIGH_STRENGTH_MOST_HPA = 12.7

        /** The latitudes of Earth's same four highs, the nearest and the farthest, degrees. */
        const val HIGH_LATITUDE_NEAREST = 32.5
        const val HIGH_LATITUDE_FARTHEST = 35.0

        /** The highs are sought over the sea between these, degrees from the equator: the subtropics. */
        const val HIGH_SEARCH_NEAREST = 15f
        const val HIGH_SEARCH_FARTHEST = 45f
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
                for (column in 0 until world.width) sum += half.seaLevelPressurePa(row * world.width + column)
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
        KnownFailures.expect("A1-6c: a winter ridge stands a row equatorward of 25 degrees", "seed 42 July south 24.5; seed 969495 July south 24.3; seed 969495 January north 23.8; seed 7 January north 24.5") {
            if (placed.isNotEmpty()) throw RecordedViolation("ridges outside 25 to 40 degrees: $placed", placed.joinToString("; "))
        }
        // Recorded at W2h: seed 42's southern ridge moves 10.4 degrees between its winter and its
        // summer, a row past the ten allowed, its winter edge by sector a degree nearer the equator
        // (docs/TODO.md, "What W2h's climate moved in the deep tier").
        KnownFailures.expect("W2h: a ridge moves a row past ten degrees between its seasons", "seed 42 south summer minus winter 10.4") {
            if (moved.isNotEmpty()) throw RecordedViolation("the ridge does not move poleward in summer: $moved", moved.joinToString("; "))
        }
    }

    /**
     * In each half and hemisphere the highest sea-level pressure over the sea at
     * [HIGH_SEARCH_NEAREST] to [HIGH_SEARCH_FARTHEST] degrees, over the world's own area mean, and its
     * latitude; [withWaves] false reads the circulation's pressure alone, without the stationary
     * waves' (the control).
     */
    private fun highs(world: WorldMap, withWaves: Boolean): List<Pair<Double, Double>> {
        val atmosphere = checkNotNull(ClimateStage.coupledAtmosphere(world.config, world.sea, world.ocean)).coupling.atmosphere
        val found = ArrayList<Pair<Double, Double>>()
        for (julyHalf in listOf(true, false)) {
            val half = atmosphere.half(julyHalf)
            fun pressureAt(cell: Int) = if (withWaves) half.seaLevelPressurePa(cell) else half.beltPressurePa[cell]
            var weighted = 0.0
            var area = 0.0
            for (row in 0 until world.height) {
                val weight = cos(ClimateStage.latitudeOf(row, world.height) * PI / 180)
                for (column in 0 until world.width) { weighted += weight * pressureAt(row * world.width + column); area += weight }
            }
            val mean = weighted / area
            for (north in listOf(true, false)) {
                var highest = Double.NEGATIVE_INFINITY
                var at = 0.0
                for (row in 0 until world.height) {
                    val latitude = ClimateStage.latitudeOf(row, world.height)
                    if ((latitude > 0f) != north || abs(latitude) < HIGH_SEARCH_NEAREST || abs(latitude) > HIGH_SEARCH_FARTHEST) continue
                    for (column in 0 until world.width) {
                        val cell = row * world.width + column
                        if (world.sea.isLand[cell]) continue
                        if (pressureAt(cell) > highest) { highest = pressureAt(cell); at = abs(latitude).toDouble() }
                    }
                }
                found += (highest - mean) / 100.0 to at
            }
        }
        return found
    }

    @Test
    fun `the subtropical highs stand as strong as Earth's, and where Earth's do`() {
        val strengths = ArrayList<Double>()
        val latitudes = ArrayList<Double>()
        val beltStrengths = ArrayList<Double>()
        for (seed in SEEDS) {
            val world = world(seed)
            val highs = highs(world, withWaves = true)
            println("MEAN CIRCULATION seed %d highs over the area mean (July north, July south, January north, January south): %s"
                .format(seed, highs.joinToString("; ") { "%.1f hPa at %.1f".format(it.first, it.second) }))
            strengths += highs.map { it.first }
            latitudes += highs.map { it.second }
            beltStrengths += highs(world, withWaves = false).map { it.first }
        }
        val strength = strengths.average()
        val latitude = latitudes.average()
        println("MEAN CIRCULATION highs pooled: %.2f hPa at %.1f degrees (Earth %.1f to %.1f hPa at %.1f to %.1f); the circulation's pressure alone %.2f hPa"
            .format(strength, latitude, HIGH_STRENGTH_LEAST_HPA, HIGH_STRENGTH_MOST_HPA, HIGH_LATITUDE_NEAREST, HIGH_LATITUDE_FARTHEST, beltStrengths.average()))
        assertTrue(strength in HIGH_STRENGTH_LEAST_HPA..HIGH_STRENGTH_MOST_HPA, "the highs stand %.2f hPa over the mean pooled, Earth's %.1f to %.1f".format(strength, HIGH_STRENGTH_LEAST_HPA, HIGH_STRENGTH_MOST_HPA))
        // The control: the zonal circulation's pressure alone, which makes ridges and no highs.
        assertTrue(beltStrengths.average() !in HIGH_STRENGTH_LEAST_HPA..HIGH_STRENGTH_MOST_HPA, "the control's ridges stand as strong as Earth's highs, so the clause shows nothing")
        // Recorded at W2h, where it was written: the winter hemisphere's highest pressure over the
        // sea stands at 22 to 26 degrees on every seed, with the winter ridge, six to ten degrees
        // equatorward of Earth's (the ridge's own clause above; docs/TODO.md).
        KnownFailures.expect("W2h: the subtropical highs stand equatorward of Earth's", "28.9") {
            if (latitude < HIGH_LATITUDE_NEAREST || latitude > HIGH_LATITUDE_FARTHEST) {
                throw RecordedViolation("the highs pooled at %.1f degrees, Earth's %.1f to %.1f".format(latitude, HIGH_LATITUDE_NEAREST, HIGH_LATITUDE_FARTHEST), "%.1f".format(latitude))
            }
        }
    }

    @Test
    fun `each summer hemisphere's sea holds a closed subtropical high`() {
        val missing = ArrayList<String>()
        for (seed in SEEDS) {
            val world = world(seed)
            val atmosphere = checkNotNull(ClimateStage.coupledAtmosphere(world.config, world.sea, world.ocean)).coupling.atmosphere
            for (julyHalf in listOf(true, false)) {
                val half = atmosphere.half(julyHalf)
                val pressure = FloatArray(world.width * world.height) { half.seaLevelPressurePa(it).toFloat() }
                val highs = SubtropicalHighs.find(world, pressure, atmosphere.remap)
                val name = if (julyHalf) "July" else "January"
                println("MEAN CIRCULATION seed $seed $name highs: " + highs.ifEmpty { listOf("none") }.joinToString("; "))
                // Northern summer is the half about July; southern summer the half about January.
                if (highs.none { (it.latitudeDegrees > 0.0) == julyHalf }) missing += "seed $seed $name ${if (julyHalf) "north" else "south"}"
            }
        }
        KnownFailures.expect("A1-6b: a summer hemisphere's sea holds no closed high", "seed 42 July north; seed 969495 January south; seed 7 July north") {
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
        // other five shifts are 4.8 to 8.8 (docs/TODO.md). Armed again at W2c, on whose climate every
        // jet moves within the ten degrees (docs/DESIGN_LEDGER.md, W2c).
        assertTrue(moved.isEmpty(), "the jet does not move poleward in summer: $moved")
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
        KnownFailures.expect("A1-6b: the ITCZ stands outside Earth's positions in a half", "seed 42 January 2.7; seed 7 July -2.0; seed 7 January -9.8") {
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
        // flux equator (docs/DESIGN_LEDGER.md, A1-6a and A1-6b). Recorded at W2b: the open sea now
        // rains by its column's humidity alone, and seed 42's wettest ocean row stands near the
        // equator in both halves, at 1.3 N and 1.7 S (docs/TODO.md).
        KnownFailures.expect("W2b: seed 42's ocean rains most near the equator in both halves", "seed 42 2.5") {
            if (complaints.isNotEmpty()) throw RecordedViolation("the ITCZ's seasonal range: $complaints", complaints.joinToString("; "))
        }
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
        KnownFailures.expect("A1-6a: the winter Hadley cell does not carry three times the summer cell's mass", "5 of 6") {
            if (complaints.isNotEmpty()) throw RecordedViolation("the cells' asymmetry: $complaints", "${complaints.size} of 6")
        }
    }
}
