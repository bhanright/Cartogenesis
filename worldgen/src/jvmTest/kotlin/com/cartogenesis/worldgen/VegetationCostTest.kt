package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.pipeline.VegetationDensity
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What the vegetation density and the permafrost mask cost, at every grid the generator offers.
 *
 * Rule 8 asks for a graphics path on any per-cell work, and this is per-cell work: twelve clamps,
 * a square root, a hyperbolic tangent and an exponential at every land cell. This is the
 * measurement the decision is made on, in the manner of `PressureWindCostTest`, the snow balance's
 * and the jump flood's — all of which were measured and declined, and all of which are recorded as
 * such rather than being left as an open gap.
 *
 * Measured on made fields rather than on a generated world, because the question is what the
 * arithmetic costs and not what a world costs: a world at 2048 spends minutes in erosion before it
 * reaches the climate at all.
 *
 * The tint's canopy is a different question and got a different answer — the field travels to the
 * graphics device and the shader reads it per pixel, because the raster path already existed and
 * the field had to reach it either way. See `RasterRecipe.vegetation`.
 */
class VegetationCostTest {

    private companion object {
        val grids = listOf(512, 1024, 2048)

        /** Runs per grid: two to let the just-in-time compiler settle, the rest measured. */
        const val WARM_UP_RUNS = 2
        const val MEASURED_RUNS = 5

        /**
         * The share of a 2048 world's whole generation above which this work would be worth a
         * graphics path.
         *
         * One per cent, rule 8's own figure and the same bar `PressureWindCostTest` uses: below it
         * a kernel cannot pay for the device round trip, the parity test and the second
         * implementation in two shading languages.
         */
        const val WORTH_A_DEVICE_SHARE = 0.01

        /**
         * How many vegetation fields one default generation builds.
         *
         * Three, counted at the call sites: the field is built inside
         * `ClimateStage.generateWithSeasonalMm`, which runs twice as the provisional weather the
         * hydraulic rounds cut with and once as the finished climate. The glaciation's provisional
         * snow balance runs the seasonal fields without it.
         */
        const val VEGETATION_FIELDS_PER_WORLD = 3
    }

    @Test
    fun `the vegetation field costs a fraction of a world`() {
        grids.forEach { side ->
            val land = BooleanArray(side * side) { true }
            val annual = madeTemperature(side, swingC = 0f)
            val warmest = madeTemperature(side, swingC = 12f)
            val coldest = madeTemperature(side, swingC = -12f)
            val rainfall = madeRainfall(side)
            fun run() =
                VegetationDensity.field(land, annual, warmest, coldest, rainfall)

            repeat(WARM_UP_RUNS) { run() }
            val started = System.nanoTime()
            repeat(MEASURED_RUNS) { run() }
            val millisecondsEach = (System.nanoTime() - started) / 1e6 / MEASURED_RUNS
            val perWorldMs = millisecondsEach * VEGETATION_FIELDS_PER_WORLD
            println(
                "VEGETATION COST at %d: %.1f ms over a whole grid of land, %.1f ms for the %d a world builds"
                    .format(side, millisecondsEach, perWorldMs, VEGETATION_FIELDS_PER_WORLD)
            )
            if (side == 2048) {
                val worldSeconds = GenerationTime.secondsAt(2048)
                val share = perWorldMs / 1000.0 / worldSeconds
                println("VEGETATION COST: %.3f%% of a %.1f s world at 2048, measured in this run".format(share * 100, worldSeconds))
                assertTrue(
                    share < WORTH_A_DEVICE_SHARE,
                    ("the vegetation field takes %.2f%% of a world at 2048, above the %.0f%% at " +
                        "which rule 8 asks for a graphics path rather than a measurement")
                        .format(share * 100, WORTH_A_DEVICE_SHARE * 100)
                )
            }
        }
    }

    /** A smooth pole-to-equator profile with a seasonal offset, warm in the middle rows. */
    private fun madeTemperature(side: Int, swingC: Float): FloatField {
        val field = FloatField(side, side)
        for (row in 0 until side) {
            val southward = (row + 0.5f) / side
            val equatorward = sin(southward * Math.PI.toFloat())
            for (column in 0 until side) {
                field.data[row * side + column] = -25f + 50f * equatorward + swingC
            }
        }
        return field
    }

    /** And a rainfall that runs from desert to rainforest across the map, so no branch is free. */
    private fun madeRainfall(side: Int): FloatField {
        val field = FloatField(side, side)
        for (cell in 0 until side * side) {
            field.data[cell] = 20f + 3_000f * ((cell % side).toFloat() / side)
        }
        return field
    }
}
