package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.math.BoxBlur
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.PressureWind
import com.cartogenesis.worldgen.pipeline.SeaLevelResult
import com.cartogenesis.worldgen.pipeline.SphericalGrid
import com.cartogenesis.worldgen.pipeline.SphericalOperators
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The pressure wind in the sphere's metric, near the poles.
 *
 * The gradient's east-west part is the pressure's change along a row over that row's own ground,
 * `cos(latitude)` of the equator's, so near a pole any departure a row holds along its length is a
 * steep gradient. The pressure must therefore be smooth on the sphere before it is differentiated;
 * a blur counted in cells is not, and with the true metric it drove the polar rows' winds to
 * thousands of meters a second (docs/DESIGN_LEDGER.md, A1-2). The guard: the same continent laid
 * across a pole and at 40 degrees, the fastest wind poleward of 80 degrees is no faster than the
 * fastest about the other, where the Coriolis parameter is two-thirds as large and the same
 * gradient drives a faster geostrophic wind; and the same wind from the cell-counted blur fails it.
 */
class PressureWindPoleTest {

    private val rows = 256
    private val config = WorldGenConfig.forRows(42L, rows)
    private val columns = config.width

    /** Two continents of 1,500 km, about 80 N and about 40 N, warmer than the sea by 15 C, over a zonal profile. */
    private fun isLand(cell: Int): Boolean {
        val latitude = ClimateStage.latitudeOf(cell / columns, rows) * PI / 180
        val longitude = (cell % columns + 0.5) * 2 * PI / columns
        val here = SphereFields.unit(latitude, longitude)
        return listOf(80.0 to 1.0, 40.0 to 4.0).any { (centerLatitude, centerLongitude) ->
            val center = SphereFields.unit(centerLatitude * PI / 180, centerLongitude)
            val angle = acos(SphereFields.dot(here, center).coerceIn(-1.0, 1.0))
            angle * config.scale.radiusMeters < 1_500_000.0 * (1 + 0.2 * sin(4 * longitude))
        }
    }

    private val sea = BooleanArray(columns * rows) { isLand(it) }.let { land ->
        SeaLevelResult(shorelineHeight = 0f, isLand = land, relativeElevation = FloatField(columns, rows), landCellCount = land.count { it })
    }

    private val temperature = FloatField(columns, rows).also { field ->
        for (cell in field.data.indices) {
            val latitude = ClimateStage.latitudeOf(cell / columns, rows) * PI / 180
            field.data[cell] = (27 - 47 * sin(latitude) * sin(latitude) + if (sea.isLand[cell]) 15.0 else 0.0).toFloat()
        }
    }

    /** The fastest wind poleward of 80 degrees and the fastest elsewhere, meters a second. */
    private fun fastest(wind: PressureWind.Vectors): Pair<Double, Double> {
        var polar = 0.0
        var elsewhere = 0.0
        for (cell in wind.eastwardMps.indices) {
            val speed = sqrt((wind.eastwardMps[cell] * wind.eastwardMps[cell] + wind.southwardMps[cell] * wind.southwardMps[cell]).toDouble())
            if (abs(ClimateStage.latitudeOf(cell / columns, rows)) > 80f) polar = maxOf(polar, speed) else elsewhere = maxOf(elsewhere, speed)
        }
        return polar to elsewhere
    }

    @Test
    fun `the wind over a polar continent is no faster than over the same continent at 40 degrees`() {
        val (polar, elsewhere) = fastest(PressureWind.surfaceWind(config, sea, PressureWind.pressureAnomalyHpa(config, temperature)))

        // The control: the same anomaly blurred in cells, as the pressure was before it was smoothed on the sphere.
        val boxed = FloatField(columns, rows)
        for (row in 0 until rows) {
            var sum = 0.0
            for (column in 0 until columns) sum += temperature.data[row * columns + column]
            val mean = sum / columns
            for (column in 0 until columns) {
                boxed.data[row * columns + column] = (-(temperature.data[row * columns + column] - mean) * PressureWind.HPA_PER_KELVIN).toFloat()
            }
        }
        val radiusKm = PressureWind.rossbyRadiusKm()
        BoxBlur.apply(boxed, radiusAcross = config.wholeCellsFor(radiusKm), radiusDown = kotlin.math.round(config.rowsFor(radiusKm)).toInt(), passes = BoxBlur.PASSES_FOR_GAUSSIAN)
        val (boxedPolar, boxedElsewhere) = fastest(PressureWind.surfaceWind(config, sea, boxed))
        println("PRESSURE POLE fastest poleward of 80 degrees %.1f m/s, elsewhere %.1f; blurred in cells %.1f and %.1f".format(polar, elsewhere, boxedPolar, boxedElsewhere))
        assertTrue(polar <= elsewhere, "the polar wind runs at $polar m/s against $elsewhere about the continent at 40 degrees")
        assertTrue(boxedPolar > boxedElsewhere, "the cell-counted blur's control passes, so the guard shows nothing")
    }

    /** The smoothing on the sphere keeps the anomaly's area integral and stays inside its range. */
    @Test
    fun `the smoothing conserves and makes no new extremes`() {
        val grid = SphericalGrid.forGround(columns, rows, config.scale)
        val field = DoubleArray(columns * rows) { if (sea.isLand[it]) 1.0 else 0.0 }
        val smoothed = SphericalOperators(grid).diffuse(field, PressureWind.rossbyRadiusKm() * 1_000.0, 4)
        var before = 0.0
        var after = 0.0
        for (cell in field.indices) {
            before += field[cell] * grid.cellAreaSquareMeters[cell / columns]
            after += smoothed[cell] * grid.cellAreaSquareMeters[cell / columns]
        }
        println("PRESSURE POLE smoothing: integral moved by %.2e, range %.3e to %.6f".format(abs(after - before) / before, smoothed.min(), smoothed.max()))
        assertTrue(abs(after - before) / before < 1e-12, "the smoothing moves the integral by ${(after - before) / before}")
        assertTrue(smoothed.min() >= -1e-12 && smoothed.max() <= 1 + 1e-12, "the smoothing overshoots its input's range")
        // And the first row about the pole is near one value: its spread along the row is under a
        // tenth of the continent's step, where the input's is the whole step.
        val firstRow = (0 until columns).map { smoothed[it] }
        println("PRESSURE POLE smoothing: the first row spans %.4f to %.4f".format(firstRow.min(), firstRow.max()))
        assertTrue(firstRow.max() - firstRow.min() < 0.1, "the polar row keeps a spread of ${firstRow.max() - firstRow.min()}")
    }
}
