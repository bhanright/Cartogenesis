package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.AtmosphereCoupling
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
         * Boundary layers a default world solves dry: the ocean's stress's, once. The climate runs,
         * the provisional weather the hydraulic rounds cut with twice
         * (`HydraulicErosion.provisionalWeather`), the glaciation's provisional snow balance and the
         * finished climate, each factor one and couple it to their march.
         */
        const val BOUNDARY_LAYERS_PER_WORLD = 1
        const val COUPLED_CLIMATES_PER_WORLD = 4

        const val ROWS = 1024

        /** The coupled loop's laps a climate run, 13 to 18 on the standard worlds at 512 and 1,024 rows (`AtmosphereCouplingTest`). */
        const val LAPS_PER_CLIMATE = 16
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

    /**
     * What one lap of the coupled loop costs the atmosphere at the application's grid, and its
     * part per map cell: both halves' condensation carried down as heating, both solved by
     * back-substitution, each half's pressure carried up and balanced, its convergence carried down
     * and its ascent carried up. A report and a recorded figure: the loop's laps a climate run come
     * from `AtmosphereCouplingTest` and are given here as [LAPS_PER_CLIMATE].
     */
    @Test
    fun `a coupling lap's per-cell work, against a world`() {
        val config = WorldGenConfig.forRows(7L, 512).atResolution(2 * ROWS, ROWS)
        val sea = madeSea(config.width, config.height)
        val zonal = ClimateStage.zonalClimate(config, sea)
        val marine = ClimateStage.marineAirFraction(config, sea)
        val belts = listOf(ClimateStage.beltWindOfRows(config, true), ClimateStage.beltWindOfRows(config, false))
        val factoredAt = System.nanoTime()
        val solver = BoundaryLayer.Solver(config, sea, zonal, marine, belts[0], belts[1], keptWaves = Int.MAX_VALUE)
        val factorMs = (System.nanoTime() - factoredAt) / 1e6
        val coupling = AtmosphereCoupling(solver, { _ -> error("not marched") })
        val condensation = FloatArray(config.width * config.height) { cell ->
            if (sea.isLand[cell]) 1_000f + 500f * sin(cell * 0.01).toFloat() else 1_200f
        }
        var downMs = 0.0
        var solveMs = 0.0
        var upMs = 0.0
        var ascentMs = 0.0
        repeat(WARM_UP_RUNS + MEASURED_RUNS) { run ->
            val started = System.nanoTime()
            val july = coupling.latentHeatingOf(condensation)
            val january = coupling.latentHeatingOf(condensation)
            val carriedDown = System.nanoTime()
            val responses = solver.solve(july, january)
            val solved = System.nanoTime()
            for (half in listOf(responses.julyHalf, responses.januaryHalf)) {
                val ground = responses.remap.outputToGround(half.eddyPressureCoarsePa)
                PressureWind.surfaceWind(config, sea, ground, half.beltEastwardMps, half.beltSouthwardMps)
            }
            val balanced = System.nanoTime()
            BoundaryLayer.groundAscentMps(responses, responses.julyHalf)
            BoundaryLayer.groundAscentMps(responses, responses.januaryHalf)
            val ascended = System.nanoTime()
            if (run >= WARM_UP_RUNS) {
                downMs += (carriedDown - started) / 1e6 / MEASURED_RUNS
                // The solve carries up and balances once itself; what is left is the back-substitution.
                solveMs += ((solved - carriedDown) - (balanced - solved)) / 1e6 / MEASURED_RUNS
                upMs += (balanced - solved) / 1e6 / MEASURED_RUNS
                ascentMs += (ascended - balanced) / 1e6 / MEASURED_RUNS
            }
        }
        val worldSeconds = GenerationTime.secondsAt(ROWS)
        val perCellMs = downMs + upMs + ascentMs
        val laps = COUPLED_CLIMATES_PER_WORLD * LAPS_PER_CLIMATE
        val perCellShare = perCellMs * laps / 1000.0 / worldSeconds
        val wholeShare = ((perCellMs + solveMs) * laps + factorMs * COUPLED_CLIMATES_PER_WORLD) / 1000.0 / worldSeconds
        println(
            ("COUPLING COST at %d rows: factoring %.0f ms; a lap %.0f ms carrying the heating down, %.0f ms back-substituting, " +
                "%.0f ms carrying the pressure up and balancing it, %.0f ms the ascent; %d laps a world (%d a climate run), " +
                "%.2f%% of a %.1f s world in all and %.2f%% on the map's cells")
                .format(ROWS, factorMs, downMs, solveMs, upMs, ascentMs, laps, LAPS_PER_CLIMATE, wholeShare * 100, worldSeconds, perCellShare * 100)
        )
        KnownFailures.expect("A1-5: the coupled loop's per-cell work is over a hundredth of a world", "10%") {
            if (perCellShare >= WORTH_A_DEVICE_SHARE) {
                throw RecordedViolation(
                    "the coupled loop's per-cell work takes %.2f%% of a world, over the hundredth rule 8 allows without a graphics path".format(perCellShare * 100),
                    "%.0f%%".format(perCellShare * 100)
                )
            }
        }
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
