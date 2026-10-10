package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.BoundaryLayer
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.PressureWind
import com.cartogenesis.worldgen.pipeline.SeaLevelResult
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The boundary layer's wind in the sphere's metric, near the poles.
 *
 * The gradient's east-west part is the pressure's change along a row over that row's own ground,
 * `cos(latitude)` of the equator's, so near a pole any departure a row holds along its length is a
 * steep gradient. The pressure must therefore be smooth on the sphere before it is differentiated;
 * a field that changes at a coarse cell's edge is not, and with the true metric such fields drove
 * the polar rows' winds to thousands of meters a second (docs/DESIGN_LEDGER.md, A1-2). The guard:
 * the same continent laid across a pole and at 40 degrees, the fastest departure from the belts
 * poleward of 80 degrees is no faster than the fastest about the other, where the Coriolis
 * parameter is two-thirds as large and the same gradient drives a faster geostrophic wind; and the
 * same pressure with its polar rows carried up row by row, each keeping its coarse row's waves to
 * the last row before the pole, fails it.
 */
class PressureWindPoleTest {

    private val rows = 256
    private val config = WorldGenConfig.forRows(42L, rows)
    private val columns = config.width

    /** Two continents of 1,500 km, about 80 N and about 40 N. */
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

    private val atmosphere = ClimateStage.atmosphere(config, sea)

    /** The fastest departure of [wind] from the belts poleward of 80 degrees and elsewhere, meters a second. */
    private fun fastest(wind: PressureWind.Vectors, half: BoundaryLayer.Half): Pair<Double, Double> {
        var polar = 0.0
        var elsewhere = 0.0
        for (cell in wind.eastwardMps.indices) {
            val row = cell / columns
            val east = (wind.eastwardMps[cell] - half.beltEastwardMps[row]).toDouble()
            val south = (wind.southwardMps[cell] - half.beltSouthwardMps[row]).toDouble()
            // Over land the belts' own wind is slowed and turned too; only the sea's departure is the eddies'.
            if (sea.isLand[cell]) continue
            val speed = sqrt(east * east + south * south)
            if (abs(ClimateStage.latitudeOf(row, rows)) > 80f) polar = maxOf(polar, speed) else elsewhere = maxOf(elsewhere, speed)
        }
        return polar to elsewhere
    }

    /**
     * The control: the pressure carried up, with each ground row poleward of 80 degrees given its
     * coarse row's values, cell by cell, as a field carried up row by row would hold them. A field regular at the pole holds its waves there in proportion to the
     * distance from the pole; this one holds them to the last row.
     */
    private fun polarRowsKept(half: BoundaryLayer.Half): FloatArray {
        val coarse = atmosphere.remap.coarse
        val field = half.eddyPressurePa.copyOf()
        for (row in 0 until rows) {
            if (abs(ClimateStage.latitudeOf(row, rows)) <= 80f) continue
            val coarseRow = row * coarse.rows / rows
            for (column in 0 until columns) {
                val coarseColumn = column * coarse.columns / columns
                field[row * columns + column] = half.eddyPressureCoarsePa[coarseRow * coarse.columns + coarseColumn].toFloat()
            }
        }
        return field
    }

    @Test
    fun `the wind over a polar continent is no faster than over the same continent at 40 degrees`() {
        val half = atmosphere.julyHalf
        val (polar, elsewhere) = fastest(PressureWind.Vectors(half.eastwardMps, half.southwardMps), half)
        val kept = PressureWind.surfaceWind(config, sea, polarRowsKept(half), half.beltEastwardMps, half.beltSouthwardMps)
        val (keptPolar, keptElsewhere) = fastest(kept, half)
        println("PRESSURE POLE fastest eddy wind over the sea poleward of 80 degrees %.2f m/s, elsewhere %.2f; with the polar rows kept row by row %.1f and %.1f"
            .format(polar, elsewhere, keptPolar, keptElsewhere))
        assertTrue(polar <= elsewhere, "the polar wind runs at $polar m/s against $elsewhere about the continent at 40 degrees")
        assertTrue(keptPolar > keptElsewhere, "the control with the polar rows kept passes, so the guard shows nothing")
    }

    /**
     * About the pole the carried-up pressure is regular: its first row holds the slope across the
     * pole, one zonal wave, and nothing shorter, as a field smooth on the sphere must.
     */
    @Test
    fun `the carried-up pressure is one value at the pole with a slope across it`() {
        val field = atmosphere.julyHalf.eddyPressurePa
        val range = field.max() - field.min()
        val waves = (1..8).map { wave ->
            var real = 0.0
            var imaginary = 0.0
            for (column in 0 until columns) {
                val angle = 2 * PI * wave * column / columns
                real += field[column] * cos(angle)
                imaginary += field[column] * sin(angle)
            }
            2 * sqrt(real * real + imaginary * imaginary) / columns / range
        }
        val shorter = waves.drop(1).max()
        println("PRESSURE POLE the first row's first wave %.4f of the field's range, the largest shorter one %.2e".format(waves[0], shorter))
        assertTrue(shorter < POLAR_ROW_TOLERANCE, "the polar row holds a zonal wave shorter than the first of $shorter of the range")
    }

    private companion object {
        /**
         * A thousandth of the field's range: what the polar row's waves shorter than the first may
         * hold of a series that takes one value at the pole.
         */
        const val POLAR_ROW_TOLERANCE = 1e-3
    }
}
