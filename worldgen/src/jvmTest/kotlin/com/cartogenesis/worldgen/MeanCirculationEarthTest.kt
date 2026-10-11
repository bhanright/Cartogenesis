package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.HadleyCirculation
import com.cartogenesis.worldgen.pipeline.Season
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test

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
        KnownFailures.expect("A1-6a: the ridge stands outside 25 to 40 degrees", "none") {
            if (placed.isNotEmpty()) throw RecordedViolation("ridges outside 25 to 40 degrees: $placed", "${placed.size} of 12")
        }
        KnownFailures.expect("A1-6a: the summer ridge stands equatorward of the winter ridge", "none") {
            if (moved.isNotEmpty()) throw RecordedViolation("the ridge does not move poleward in summer: $moved", "${moved.size} of 6")
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
        KnownFailures.expect("A1-6a: the ITCZ migrates outside Earth's range", "none") {
            if (complaints.isNotEmpty()) throw RecordedViolation("the ITCZ's seasonal range: $complaints", "${complaints.size} of 3")
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
                        HadleyCirculation.LOWER_BRANCH_PA / 9.80665
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
        KnownFailures.expect("A1-6a: the winter Hadley cell does not carry three times the summer cell's mass", "none") {
            if (complaints.isNotEmpty()) throw RecordedViolation("the cells' asymmetry: $complaints", "${complaints.size} of 6")
        }
    }
}
