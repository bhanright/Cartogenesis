package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.WaveFixtures.DEGREES
import com.cartogenesis.worldgen.model.WorldScale
import com.cartogenesis.worldgen.pipeline.AtmosphereLevels
import com.cartogenesis.worldgen.pipeline.SphericalGrid
import com.cartogenesis.worldgen.pipeline.StationaryWaveModel
import com.cartogenesis.worldgen.pipeline.WaveForcing
import com.cartogenesis.worldgen.pipeline.ZonalBasicState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The stationary-wave model against the published solutions it is built to reproduce, each paper
 * read: Gill (1980), Hoskins and Karoly (1981) and Rodwell and Hoskins (2001). The experiments are in
 * [WaveBenchmarks]; the ladder of grids, the level counts and the damping's sensitivity are in the
 * audit tier's `StationaryWaveReport`. Hoskins and Karoly's and Rodwell and Hoskins' basic states were
 * observed climatologies the papers do not tabulate; here the jets are Jablonowski and Williamson's,
 * so their figures are compared for where things lie and their sign, and their sizes are reported.
 */
class StationaryWaveBenchmarkTest {

    private val radius = WorldScale().radiusMeters

    /**
     * How far a decay rate may stand from the beta-plane's: Gill's beta-plane departs from the sphere
     * by `1 - sin(phi) / phi` at the planetary wave's lobes, `y = sqrt(3)` of the first mode's Rossby
     * radius (22 degrees on Earth's planet), 2.4%; twice that.
     */
    private val decayTolerance = 2 * (1 - sin(22 * DEGREES) / (22 * DEGREES))

    @Test
    fun `Gill's heating on a resting atmosphere`() {
        for (scale in listOf(1.0, 4.0)) {
            val rows = if (scale == 1.0) 90 else 180
            val figures = WaveBenchmarks.gill(radius * scale, rows, AtmosphereLevels.equalMass(4))
            val epsilon = figures.epsilon
            val exactRossby = WaveBenchmarks.exactRossbyDecay(epsilon)
            println(("GILL planet x%.0f, $rows rows: mode 1 at %.1f m/s, Rossby radius %.0f km (%.3f of the radius); against Gill's analytic " +
                "solution within %.0f radii of the equator, geopotential %.4f and eastward wind %.4f (relative RMS); Kelvin wave's decay east %.4f " +
                "(Gill %.3f), planetary wave's west %.4f (Gill's long waves %.3f, the full damped wave %.4f), west over east %.3f").format(scale, figures.speedMetersPerSecond,
                figures.rossbyRadiusMeters / 1e3, figures.rossbyRadiusMeters / (radius * scale), WaveBenchmarks.GILL_COMPARED_RADII,
                figures.fieldError, figures.eastwardError, figures.kelvinDecay, epsilon, figures.rossbyDecay, 3 * epsilon, exactRossby, figures.decayRatio))
            // Gill's long-wave approximation drops `epsilon v` against terms of order one, so his fields
            // can stand off the full equations' by about epsilon.
            assertTrue(figures.fieldError < epsilon && figures.eastwardError < epsilon, "planet x$scale: fields ${figures.fieldError}, ${figures.eastwardError}")
            assertTrue(abs(figures.kelvinDecay / epsilon - 1) < decayTolerance, "planet x$scale: the Kelvin wave decays at ${figures.kelvinDecay}")
            assertTrue(abs(figures.rossbyDecay / exactRossby - 1) < decayTolerance, "planet x$scale: the planetary wave decays at ${figures.rossbyDecay}")
        }
    }

    @Test
    fun `Hoskins and Karoly's mountain and heating`() {
        val rows = 120
        val grid = SphericalGrid(rows, 2 * rows, radius)
        val levels = AtmosphereLevels.equalMass(5)
        val upper = WaveFixtures.nearestLevel(levels, 30_000.0)
        val angular = WaveBenchmarks.spin * WaveBenchmarks.SUPER_ROTATION_SHARE
        val epsilon = sqrt(angular / (2 * (WaveBenchmarks.spin + angular)))
        val superRotation = ZonalBasicState.solidBodyRotation(grid, levels, radius * angular)
        val jets = ZonalBasicState.jablonowskiWilliamson(grid, levels)
        for ((name, state) in listOf("super-rotation" to superRotation, "Jablonowski and Williamson's jets" to jets)) {
            val response = StationaryWaveModel(state, WaveBenchmarks.hoskinsKarolyMountainDamping(levels, radius))
                .solve(WaveForcing(surfaceHeightMeters = WaveBenchmarks.hoskinsKarolyMountain(grid)))
            val train = WaveBenchmarks.waveTrain(grid, response.geopotential[upper], 30 * DEGREES, PI, WaveBenchmarks.MOUNTAIN_RADIUS)
            val theory = if (state === superRotation) 2 * PI * radius * epsilon
            else WaveBenchmarks.stationaryWavelengthMeters(state, upper, train.meanLatitudeDegrees * DEGREES)
            println(("HOSKINS-KAROLY mountain on $name: the 300 hPa train " +
                train.extrema.joinToString { "(%.0fN %.0fE %+.0f)".format(Math.toDegrees(it.latitude), Math.toDegrees(it.longitude), it.value) } +
                "; off its great circle %.1f degrees at most, %.1f on average; wavelength %.0f km against the stationary %.0f km (%+.1f%%)")
                .format(train.worstOffCircleDegrees, train.meanOffCircleDegrees, train.wavelengthMeters / 1e3, theory / 1e3, 100 * (train.wavelengthMeters / theory - 1)))
            // The extrema are cells, so each spacing of a quarter wavelength or so is read to a cell's
            // 1.5 degrees, and the barotropic theory holds for the column's equivalent-barotropic part
            // only; a tenth either way is what the reading can tell apart.
            assertTrue(train.extrema.size >= 3, "$name: a train of ${train.extrema.size}")
            assertTrue(abs(train.wavelengthMeters / theory - 1) < TRAIN_TOLERANCE, "$name: wavelength ${train.wavelengthMeters} against $theory")
            assertTrue(train.worstOffCircleDegrees < GREAT_CIRCLE_TOLERANCE_DEGREES, "$name: ${train.worstOffCircleDegrees} degrees off the great circle")
        }
        for (latitude in listOf(45.0, 15.0)) {
            val response = StationaryWaveModel(jets, WaveBenchmarks.hoskinsKarolyHeatDamping(levels, radius))
                .solve(WaveBenchmarks.hoskinsKarolyHeating(grid, levels, latitude * DEGREES))
            val row = (0 until rows).minByOrNull { abs(grid.latitudeRadians[it] - latitude * DEGREES) }!!
            val surface = (0 until grid.columns).map { response.surfacePressurePa[row * grid.columns + it] }
            val trough = surface.indices.minByOrNull { surface[it] }!!
            val troughOffset = Math.toDegrees((trough + 0.5) * grid.columnSpacingRadians) - 180
            val lowest = response.northwardAtCenters(levels.levelCount - 1)
            val sourceCell = row * grid.columns + grid.columns / 2
            val lowWind = lowest[sourceCell]
            val lowTemperature = response.temperature[levels.interiorCount - 1][sourceCell]
            println(("HOSKINS-KAROLY deep source at %.0fN on Jablonowski and Williamson's jets: surface trough %.2f hPa at %+.0f degrees from the source (theirs %s); " +
                "at the source the 900 hPa northward wind %+.2f m/s and the 800 hPa temperature %+.2f K (theirs %s)").format(
                latitude, surface[trough] / 100, troughOffset,
                if (latitude == 45.0) "6.5 mb at +21, +18 in their text" else "2.6 mb at -14", lowWind, lowTemperature,
                if (latitude == 45.0) "-3.7 m/s and -2.9 K at 900 hPa" else "+2.2 m/s and +2.1 K"))
            if (latitude == 45.0) {
                // Their figure 2b: in mid-latitudes the heating is balanced by cold air carried from the
                // pole, so the trough lies east of the source, with northerlies and cold air at it.
                // Their four deep 45 N cases put the trough 18 to 25 degrees east.
                assertTrue(troughOffset in 10.0..30.0 && lowWind < 0 && lowTemperature < 0, "45 N: trough at $troughOffset, wind $lowWind, temperature $lowTemperature")
            }
        }
    }

    @Test
    fun `Rodwell and Hoskins' monsoon in westerlies`() {
        for (jet in listOf(ZonalBasicState.JW_JET_SPEED_MPS, 20.0)) {
            val rows = 120
            val grid = SphericalGrid(rows, 2 * rows, radius)
            val levels = AtmosphereLevels.equalMass(4)
            val state = ZonalBasicState.jablonowskiWilliamson(grid, levels, jet)
            val figures = WaveBenchmarks.monsoon(state, WaveBenchmarks.rodwellHoskinsDamping(levels, radius))
            println(("RODWELL-HOSKINS jets of %.0f m/s: at 35 N the strongest descent %.2f hPa/h at %.0f hPa, %+.0f degrees from the heating's longitude " +
                "(theirs about 0.75 to 1 hPa/h at 300 to 500 hPa, 15 to 30 degrees west); the lowest level's equatorward flow %.2f m/s at %+.0f, poleward %.2f at %+.0f " +
                "(theirs equatorward at -15, poleward at about +10)").format(jet, figures.descentHpaPerHour, figures.descentPressureHpa, figures.descentOffsetDegrees,
                figures.equatorwardMps, figures.equatorwardOffsetDegrees, figures.polewardMps, figures.polewardOffsetDegrees))
            // Their signature: the descent ten degrees poleward of the heating lies west of it, with
            // equatorward flow beneath on the west (Sverdrup balance), and it is of their size within a
            // factor of two either way.
            assertTrue(figures.descentOffsetDegrees < 0 && figures.equatorwardOffsetDegrees < 0, "the descent at ${figures.descentOffsetDegrees}, the equatorward flow at ${figures.equatorwardOffsetDegrees}")
            assertTrue(figures.descentHpaPerHour in 0.4..2.0, "descent of ${figures.descentHpaPerHour} hPa/h")
        }
    }

    private companion object {
        const val TRAIN_TOLERANCE = 0.1
        const val GREAT_CIRCLE_TOLERANCE_DEGREES = 10.0
    }
}
