package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.MoistureMarch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The march carries water over a pole as it carries it anywhere else: under a wind that crosses the
 * pole without converging, the air turning as a rigid body about an axis tilted by `alpha` from the
 * pole's, over an all-sea world at one temperature, the rows next to each pole rain no further from
 * the planet's mean than the rows at the equator do under the same wind.
 *
 * Over a sea of one temperature under a wind that converges nowhere, every place of the sphere is
 * every other, so the rain is the planet's mean everywhere and what departs from it is the march's
 * own error; at `alpha` 90 degrees the wind runs straight over both poles and through the equator's
 * rows at the meridians half way between, so both places are on the same path and the equator's
 * departure is the march's error for the same wind at a place with no pole. At `alpha` zero the wind
 * runs along the rows and the march is exact, the control that the fixture is fair.
 *
 * Shown failing on the march that held a cell's water at its own center's speed, where a cell beside
 * the line the zonal wind parts along takes water in through its face and leaves it at half the speed
 * it leaves by (`MoistureMarch`'s leaving speed): at 256 rows and `alpha` 90 the polar rows rained
 * 2,959 mm against a mean of 710 and the equator's 763 to 776, and single cells beside the meridian
 * the wind runs up to the north pole along 580,426 mm.
 */
class MoistureMarchPoleTest {

    private companion object {
        const val ROWS = 256
        const val WIND_MPS = 7.5
        const val ROWS_EACH_SIDE = 2
    }

    @Test
    fun `the march rains over a pole as it rains at the equator under the same wind`() {
        val config = WorldGenConfig.forRows(42L, ROWS)
        val failures = ArrayList<String>()
        for (alphaDegrees in listOf(90.0, 0.0)) {
            val rain = rainUnder(config, alphaDegrees * PI / 180)
            val mean = areaMean(config, rain)
            val polar = (0 until ROWS_EACH_SIDE).map { rowMean(config, rain, it) } +
                (config.height - ROWS_EACH_SIDE until config.height).map { rowMean(config, rain, it) }
            val equator = (config.height / 2 - ROWS_EACH_SIDE until config.height / 2 + ROWS_EACH_SIDE).map { rowMean(config, rain, it) }
            val polarDeparture = polar.maxOf { abs(it - mean) }
            val equatorDeparture = equator.maxOf { abs(it - mean) }
            println("MARCH POLE alpha %.0f: mean %.1f mm; the polar rows %s, the equator's %s; departures %.1f and %.1f"
                .format(alphaDegrees, mean, polar.joinToString(" ") { "%.0f".format(it) }, equator.joinToString(" ") { "%.0f".format(it) }, polarDeparture, equatorDeparture))
            if (alphaDegrees == 0.0) {
                assertTrue(polarDeparture < 1.0 && equatorDeparture < 1.0, "the march departs from the mean under a wind along the rows")
            } else if (polarDeparture > equatorDeparture) {
                failures += "alpha %.0f: the polar rows depart by %.1f mm, the equator's by %.1f".format(alphaDegrees, polarDeparture, equatorDeparture)
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /** The march's half-year rain under the rigid rotation about an axis tilted by [alpha] radians, over a sea at one temperature. */
    private fun rainUnder(config: WorldGenConfig, alpha: Double): FloatArray {
        val cells = config.width * config.height
        val east = FloatArray(cells)
        val south = FloatArray(cells)
        for (cell in 0 until cells) {
            val latitude = ClimateStage.latitudeOf(cell / config.width, config.height) * PI / 180
            val longitude = (cell % config.width + 0.5) * 2 * PI / config.width
            east[cell] = (WIND_MPS * (cos(latitude) * cos(alpha) + sin(latitude) * cos(longitude) * sin(alpha))).toFloat()
            south[cell] = (WIND_MPS * sin(longitude) * sin(alpha)).toFloat()
        }
        fun season() = MoistureMarch.Season(
            airTemperatureC = FloatArray(cells) { 15f },
            seaSurfaceC = FloatArray(cells) { 15f },
            seaIce = BooleanArray(cells),
            eastwardMps = east,
            southwardMps = south,
            largeScaleAscentMps = null,
            inversionSuppression = null,
            extraterrestrialOfRow = DoubleArray(config.height) { 10.0 }
        )
        return MoistureMarch.run(
            MoistureMarch.Inputs(config, BooleanArray(cells), FloatArray(cells) { -0.5f }, FloatArray(cells), 0f, 0.0)
        ) { season() to season() }.julyHalfRainMm.data
    }

    private fun rowMean(config: WorldGenConfig, rain: FloatArray, row: Int): Double =
        (0 until config.width).sumOf { rain[row * config.width + it].toDouble() } / config.width

    private fun areaMean(config: WorldGenConfig, rain: FloatArray): Double {
        var total = 0.0
        var area = 0.0
        for (cell in rain.indices) {
            val weight = cos(ClimateStage.latitudeOf(cell / config.width, config.height) * PI / 180)
            total += weight * rain[cell]
            area += weight
        }
        return total / area
    }
}
