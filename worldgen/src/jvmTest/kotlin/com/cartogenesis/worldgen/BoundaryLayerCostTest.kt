package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.BoundaryLayer
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.PressureWind
import com.cartogenesis.worldgen.pipeline.SeaLevelResult
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What the boundary layer costs a world at the application's grid, 1,024 rows, and the part of it
 * that is per map cell.
 *
 * Rule 8 asks for a graphics path on any per-cell work. The boundary layer has two parts: the dry
 * model's solve on the atmosphere's grid (45,000 cells on Earth's planet, whatever the map), whose
 * card path is the wave solve's own chunk with the march's (docs/TODO.md); and the per-cell work on
 * the map, carrying the waves' pressure up by its double Fourier series and solving the drag
 * balance at every cell, two halves each. The second is what this guard holds to the measured
 * exception's hundredth of a world, in the manner of `SnowBalance`'s and the jump flood's; the
 * first it times and prints, and docs/TODO.md records.
 *
 * Measured on a made sea rather than a generated world, because the question is what the
 * arithmetic costs and not what a world costs; the model's work does not depend on the land.
 */
class BoundaryLayerCostTest {

    private companion object {
        /** Runs: one to let the just-in-time compiler settle, the rest measured. */
        const val WARM_UP_RUNS = 1
        const val MEASURED_RUNS = 3

        /**
         * The share of a world's generation above which per-cell work is worth a graphics path:
         * one per cent, the bar the jump flood and the snow balance were declined against.
         */
        const val WORTH_A_DEVICE_SHARE = 0.01

        /**
         * Boundary layers a default world solves: the provisional weather the hydraulic rounds cut
         * with, twice (`HydraulicErosion.provisionalWeather`), the glaciation's provisional snow
         * balance, and the finished climate, whose sea the ocean's stress reads first and the
         * climate then takes from `ClimateStage.atmosphere`'s last answer. Four.
         */
        const val BOUNDARY_LAYERS_PER_WORLD = 4

        const val ROWS = 1024
    }

    @Test
    fun `the boundary layer's per-cell work costs a fraction of a world`() {
        val config = WorldGenConfig.forRows(7L, 512).atResolution(2 * ROWS, ROWS)
        val sea = madeSea(config.width, config.height)
        val zonal = ClimateStage.zonalClimate(config, sea)
        val marine = ClimateStage.marineAirFraction(config, sea)
        val belts = listOf(ClimateStage.beltWindOfRows(config, true), ClimateStage.beltWindOfRows(config, false))
        var wholeMs = 0.0
        var groundMs = 0.0
        repeat(WARM_UP_RUNS + MEASURED_RUNS) { run ->
            val started = System.nanoTime()
            val atmosphere = BoundaryLayer.solve(config, sea, zonal, marine, belts[0], belts[1])
            val solved = System.nanoTime()
            // The per-cell part again on its own: both halves carried up and balanced.
            for (half in listOf(atmosphere.julyHalf, atmosphere.januaryHalf)) {
                val ground = atmosphere.remap.outputToGround(half.eddyPressureCoarsePa)
                PressureWind.surfaceWind(config, sea, ground, half.beltEastwardMps, half.beltSouthwardMps)
            }
            val carried = System.nanoTime()
            if (run >= WARM_UP_RUNS) {
                wholeMs += (solved - started) / 1e6 / MEASURED_RUNS
                groundMs += (carried - solved) / 1e6 / MEASURED_RUNS
            }
        }
        val worldSeconds = GenerationTime.secondsAt(ROWS)
        val wholeShare = wholeMs * BOUNDARY_LAYERS_PER_WORLD / 1000.0 / worldSeconds
        val groundShare = groundMs * BOUNDARY_LAYERS_PER_WORLD / 1000.0 / worldSeconds
        println(
            ("BOUNDARY LAYER COST at %d rows: %.0f ms a boundary layer, of which %.0f ms on the map's cells; " +
                "%d a world, %.2f%% and %.2f%% of a %.1f s world")
                .format(ROWS, wholeMs, groundMs, BOUNDARY_LAYERS_PER_WORLD, wholeShare * 100, groundShare * 100, worldSeconds)
        )
        assertTrue(
            groundShare < WORTH_A_DEVICE_SHARE,
            ("the boundary layer's per-cell work takes %.2f%% of a world, above the %.0f%% at which rule 8 asks " +
                "for a graphics path rather than a measurement").format(groundShare * 100, WORTH_A_DEVICE_SHARE * 100)
        )
    }

    /** A continent of a third of the longitudes from 60 S to 60 N, with a ridge in it. */
    private fun madeSea(columns: Int, rows: Int): SeaLevelResult {
        val isLand = BooleanArray(columns * rows) { cell ->
            cell % columns < columns / 3 && ClimateStage.latitudeOf(cell / columns, rows) in -60f..60f
        }
        val elevation = FloatField(columns, rows)
        for (cell in isLand.indices) {
            if (isLand[cell]) elevation.data[cell] = (0.2 * (1 + sin(cell % columns * 0.05))).toFloat()
        }
        return SeaLevelResult(shorelineHeight = 0f, isLand = isLand, relativeElevation = elevation, landCellCount = isLand.count { it })
    }
}
