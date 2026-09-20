package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.PressureWind
import com.cartogenesis.worldgen.pipeline.SeaLevelResult
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What the pressure field and the wind it drives cost, at every grid the generator offers.
 *
 * Rule 8 asks for a graphics path on any per-cell work, and the pressure field, its gradient and
 * the geostrophic turn are all per cell. This is the measurement that decision is made on, in the
 * manner of `SnowBalance`'s and the jump flood's — both of which were measured and declined, and
 * both of which are recorded as such in the ledger rather than being left as an open gap.
 *
 * Measured on a made temperature field rather than a generated world, because the question is what
 * the arithmetic costs and not what a world costs: a world at 2048 spends minutes in erosion before
 * it reaches the climate at all, and timing the pair inside that would be measuring the erosion.
 * The field is the one the stage really hands it — a smooth zonal profile with a continent-sized
 * warm anomaly on it — so the box blur's running sums do the same work they would on a real world.
 */
class PressureWindCostTest {

    private companion object {
        val grids = listOf(512, 1024, 2048)

        /** Runs per grid: one to let the just-in-time compiler settle, the rest measured. */
        const val WARM_UP_RUNS = 2
        const val MEASURED_RUNS = 5

        /**
         * The share of a 2048 world's whole generation above which this work would be worth a
         * graphics path.
         *
         * One per cent. Below that a kernel cannot pay for the device round trip, the parity test
         * and the second implementation in two shading languages — which is the same bar the jump
         * flood and the snow balance were declined against.
         */
        const val WORTH_A_DEVICE_SHARE = 0.01

        /**
         * A 2048 world's generation, in seconds, as `GenerationSpeedTest` last reported it.
         *
         * Quoted rather than measured here so this test stays a measurement of one piece of
         * arithmetic; the share below is an order-of-magnitude comparison and does not need the
         * whole pipeline run again to make its point.
         */
        const val WORLD_AT_2048_SECONDS = 180.0
    }

    @Test
    fun `the pressure field and its wind cost a fraction of a world`() {
        grids.forEach { side ->
            val config = WorldGenConfig(seed = 7L, width = side, height = side)
            val temperature = madeTemperature(side)
            val sea = madeSea(side)
            repeat(WARM_UP_RUNS) { run(config, sea, temperature) }
            val started = System.nanoTime()
            repeat(MEASURED_RUNS) { run(config, sea, temperature) }
            val millisecondsEach =
                (System.nanoTime() - started) / 1e6 / MEASURED_RUNS
            // Three per season plus one for the ocean's stress: what a whole generation pays.
            val perWorldMs = millisecondsEach * 4
            println(
                ("PRESSURE WIND COST at %d: %.1f ms for one season's field and wind, %.1f ms for " +
                    "the four a world builds (%.3f%% of a %.0f s world at 2048)")
                    .format(
                        side, millisecondsEach, perWorldMs,
                        perWorldMs / 1000.0 / WORLD_AT_2048_SECONDS * 100, WORLD_AT_2048_SECONDS
                    )
            )
            if (side == 2048) {
                val share = perWorldMs / 1000.0 / WORLD_AT_2048_SECONDS
                assertTrue(
                    share < WORTH_A_DEVICE_SHARE,
                    ("the pressure wind takes %.2f%% of a world at 2048, above the %.0f%% at " +
                        "which rule 8 asks for a graphics path rather than a measurement")
                        .format(share * 100, WORTH_A_DEVICE_SHARE * 100)
                )
            }
        }
    }

    private fun run(config: WorldGenConfig, sea: SeaLevelResult, temperature: FloatField) {
        val pressure = PressureWind.pressureAnomalyHpa(config, temperature)
        PressureWind.surfaceWind(config, sea, pressure)
    }

    /** A zonal profile with a continent-sized warm anomaly on it, in degrees Celsius. */
    private fun madeTemperature(side: Int): FloatField {
        val field = FloatField(side, side)
        for (row in 0 until side) {
            val northward = (row.toDouble() / side - 0.5) * 2.0
            val zonalC = 27.0 - 47.0 * northward * northward
            for (column in 0 until side) {
                val anomalyC = 12.0 * sin(column * 2.0 * Math.PI / side)
                field.data[row * side + column] = (zonalC + anomalyC).toFloat()
            }
        }
        return field
    }

    /** Half the map land, in one block, which is all [PressureWind.surfaceWind] reads of it. */
    private fun madeSea(side: Int): SeaLevelResult {
        val isLand = BooleanArray(side * side) { it % side < side / 2 }
        return SeaLevelResult(
            shorelineHeight = 0f,
            isLand = isLand,
            relativeElevation = FloatField(side, side),
            landCellCount = isLand.count { it }
        )
    }
}
