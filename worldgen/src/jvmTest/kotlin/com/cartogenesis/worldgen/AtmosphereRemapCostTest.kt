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
 * down must stay under the measured exception's hundredth of a world; carrying up, the forcing's
 * filter and the coarse operators are printed. The filter and the operators are work on the coarse
 * grid's 45,000 cells, not the map's, and are the dry model's to budget.
 */
class AtmosphereRemapCostTest {

    private companion object {
        const val WARM_UP_RUNS = 2
        const val MEASURED_RUNS = 5

        /** The share of a world at the application's grid above which per-cell work is worth a graphics path (rule 8). */
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
    fun `carrying down costs under a hundredth of a world`() {
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
        // The application's grid, 2,048 by 1,024: `secondsAt` takes the rows.
        val worldSeconds = GenerationTime.secondsAt(1024)
        fun share(milliseconds: Double, perUpdate: Int = 1) = calls * perUpdate * milliseconds / 1000.0 / worldSeconds
        println(("ATMOSPHERE COST at 2048 by 1024 (the application's grid) onto ${coarse.rows} by ${coarse.columns}: the area mean %.1f ms, " +
            "the forcing with its filter %.1f ms, carrying one field up %.1f ms, the four coarse operators %.2f ms")
            .format(downMs, forcingMs, upMs, operatorsMs))
        println(("ATMOSPHERE COST per world of %.1f s at the design's $calls updates: the area mean %.2f%%, the forcing's filter on the coarse grid %.2f%%, " +
            "carrying up %.2f%% (${FIELDS_UP_PER_UPDATE} fields), the coarse operators %.2f%%")
            .format(worldSeconds, share(downMs) * 100, share(forcingMs - downMs) * 100, share(upMs, FIELDS_UP_PER_UPDATE) * 100, share(operatorsMs) * 100))
        // The per-cell work rule 8 is about: carrying down is under the hundredth and stays on the
        // processor; carrying up is over it, and is what `GpuAtmosphere` exists for.
        assertTrue(share(downMs) < WORTH_A_DEVICE_SHARE, "carrying down takes ${share(downMs) * 100}% of a world")
    }
}
