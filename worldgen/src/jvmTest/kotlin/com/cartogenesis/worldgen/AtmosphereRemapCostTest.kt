package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldScale
import com.cartogenesis.worldgen.pipeline.AtmosphereRemap
import com.cartogenesis.worldgen.pipeline.SphericalGrid
import com.cartogenesis.worldgen.pipeline.SphericalOperators
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What carrying fields to and from the atmosphere's grid costs on the processor, and what the
 * coarse operators cost, against a world at the application's grid: the measurement rule 8's
 * graphics path is decided on.
 *
 * Nothing in the pipeline carries a field yet, so the count per world is the design's: each
 * coupling update of each calendar half carries the forcing down and four fields up, ten updates a
 * climate run, and a default world runs four climates (`PressureWindCostTest` counts them). Carrying
 * down and the coarse operators must stay under the measured exception's hundredth of a world;
 * carrying up is printed, and is over it, which is why `GpuAtmosphere` exists.
 */
class AtmosphereRemapCostTest {

    private companion object {
        const val WARM_UP_RUNS = 2
        const val MEASURED_RUNS = 5

        /** The share of a 2048 world above which per-cell work is worth a graphics path (rule 8). */
        const val WORTH_A_DEVICE_SHARE = 0.01

        /** Coupling updates a climate run makes, by the design's estimate (its section 11). */
        const val UPDATES_PER_CLIMATE = 10

        /** Calendar halves each update is made for. */
        const val HALVES = 2

        /** Climate runs a default world makes, counted at the call sites in `PressureWindCostTest`. */
        const val CLIMATES_PER_WORLD = 4

        /** Fields carried up each update and half: the pressure, the vertical motion, the storm activity and the wind's driver. */
        const val FIELDS_UP_PER_UPDATE = 4
    }

    private fun millisecondsEach(body: () -> Unit): Double {
        repeat(WARM_UP_RUNS) { body() }
        val started = System.nanoTime()
        repeat(MEASURED_RUNS) { body() }
        return (System.nanoTime() - started) / 1e6 / MEASURED_RUNS
    }

    @Test
    fun `carrying down and the coarse operators cost under a hundredth of a world`() {
        val scale = WorldScale()
        val coarse = SphericalGrid.forAtmosphere(scale)
        val remap = AtmosphereRemap(2048, 1024, coarse)
        val ground = FloatArray(2048 * 1024) { cell ->
            val latitude = PI / 2 - (cell / 2048 + 0.5) * PI / 1024
            val longitude = (cell % 2048 + 0.5) * 2 * PI / 2048
            (20 * cos(latitude) + 8 * sin(3 * longitude) * cos(latitude)).toFloat()
        }
        val forcing = remap.forcing(ground)
        val operators = SphericalOperators(coarse)
        val downMs = millisecondsEach { remap.areaMean(ground) }
        val forcingMs = millisecondsEach { remap.forcing(ground) }
        val upMs = millisecondsEach { remap.toGround(forcing) }
        val operatorsMs = millisecondsEach {
            val gradient = operators.gradient(forcing)
            operators.divergence(gradient)
            operators.curl(gradient)
            operators.laplacian(forcing)
        }
        val calls = UPDATES_PER_CLIMATE * HALVES * CLIMATES_PER_WORLD
        val worldSeconds = GenerationTime.secondsAt(2048)
        val downShare = calls * forcingMs / 1000.0 / worldSeconds
        val upShare = calls * FIELDS_UP_PER_UPDATE * upMs / 1000.0 / worldSeconds
        val operatorShare = calls * operatorsMs / 1000.0 / worldSeconds
        println(("ATMOSPHERE COST at 2048 by 1024 onto ${coarse.rows} by ${coarse.columns}: the area mean %.1f ms, the forcing with its filter %.1f ms, " +
            "carrying one field up %.1f ms, the four coarse operators %.2f ms").format(downMs, forcingMs, upMs, operatorsMs))
        println(("ATMOSPHERE COST per world of %.1f s at the design's $calls updates: down %.2f%%, up %.2f%% (${FIELDS_UP_PER_UPDATE} fields), " +
            "the coarse operators %.3f%% an update").format(worldSeconds, downShare * 100, upShare * 100, operatorShare * 100))
        assertTrue(downShare < WORTH_A_DEVICE_SHARE, "carrying forcing down takes ${downShare * 100}% of a world")
        assertTrue(operatorShare < WORTH_A_DEVICE_SHARE, "the coarse operators take ${operatorShare * 100}% of a world")
    }
}
