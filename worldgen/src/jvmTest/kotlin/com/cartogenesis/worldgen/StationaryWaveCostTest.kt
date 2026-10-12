package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.WaveFixtures.DEGREES
import com.cartogenesis.worldgen.model.WorldScale
import com.cartogenesis.worldgen.pipeline.AtmosphereLevels
import com.cartogenesis.worldgen.pipeline.SphericalGrid
import com.cartogenesis.worldgen.pipeline.StationaryWaveModel
import com.cartogenesis.worldgen.pipeline.WaveDamping
import com.cartogenesis.worldgen.pipeline.WaveForcing
import com.cartogenesis.worldgen.pipeline.ZonalBasicState
import kotlin.test.Test

/**
 * What the stationary-wave model costs on the atmosphere's grid for Earth's planet, against a world at
 * the application's grid: the measurement rule 8 decides a graphics path on.
 *
 * Nothing reads the model yet, so the counts per world are the design's (its sections 4 and 11): the
 * basic state is fixed within a climate run, so each calendar half's waves are factored once per run
 * and then solved by back-substitution at each of about ten coupling updates; a default world runs four
 * climates (`PressureWindCostTest` counts them). Run in the audit tier, whose pool has the machine's
 * threads, as a world's generation does; the per-merge tier's workers have two each.
 */
class StationaryWaveCostTest {

    private companion object {
        const val WARM_UP_RUNS = 1
        const val MEASURED_RUNS = 3

        /** The share of a world at the application's grid above which work is worth a graphics path (rule 8). */
        const val WORTH_A_DEVICE_SHARE = 0.01

        const val UPDATES_PER_CLIMATE = 10
        const val HALVES = 2
        const val CLIMATES_PER_WORLD = 4

        /** A complex number's two doubles, in bytes. */
        const val BYTES_PER_COMPLEX = 16

        /** The most factor storage measured here: the audit tier's heap is eight gigabytes, shared with a world. */
        const val KEEPABLE_FACTOR_BYTES = 1_000_000_000L
    }

    private fun millisecondsEach(body: () -> Unit): Double {
        repeat(WARM_UP_RUNS) { body() }
        val started = System.nanoTime()
        repeat(MEASURED_RUNS) { body() }
        return (System.nanoTime() - started) / 1e6 / MEASURED_RUNS
    }

    @Test
    fun `the model's factoring and solving, against a world`() {
        val scale = WorldScale()
        val grid = SphericalGrid.forAtmosphere(scale)
        val worldSeconds = GenerationTime.secondsAt(1024)
        val factorings = HALVES * CLIMATES_PER_WORLD
        val solves = UPDATES_PER_CLIMATE * HALVES * CLIMATES_PER_WORLD
        var fourLevelShare = 0.0
        for (levels in listOf(AtmosphereLevels.equalMass(4), AtmosphereLevels.equalMass(8), AtmosphereLevels.equalMass(16))) {
            val state = ZonalBasicState.jablonowskiWilliamson(grid, levels)
            val model = StationaryWaveModel(state, WaveDamping.forWorlds(levels, state.surfaceDensity))
            val shape = WaveFixtures.ellipse(grid, 25 * DEGREES, 1.6, 10 * DEGREES, 20 * DEGREES)
            val column = DoubleArray(grid.cellCount) { 2.0 / WaveFixtures.SECONDS_PER_DAY * shape[it] }
            val forcing = WaveForcing(WaveForcing.heatingFromColumn(levels, column, WaveFixtures::deepProfile))
            val factorBytes = model.waves.count().toLong() * grid.rows * 3 * model.blockSize * model.blockSize * BYTES_PER_COMPLEX
            val oneShotMs = millisecondsEach { model.solve(forcing) }
            if (factorBytes > KEEPABLE_FACTOR_BYTES) {
                // Sixteen levels' factors are 2.9 GB: they are not kept, and each solve factors again.
                val share = solves * oneShotMs / 1000.0 / worldSeconds
                println(("STATIONARY WAVE COST ${levels.levelCount} levels on ${grid.rows} by ${grid.columns} (blocks of ${model.blockSize}): factoring and solving at once %.0f ms; " +
                    "its factors would be %.0f MB and are not kept. Per world of %.1f s at the design's $solves solves: %.2f%%").format(oneShotMs, factorBytes / 1e6, worldSeconds, 100 * share))
                continue
            }
            var factored: StationaryWaveModel.Factored? = null
            val factorMs = millisecondsEach { factored = model.factorize() }
            val solveMs = millisecondsEach { factored!!.solve(forcing) }
            factored = null
            val share = (factorings * factorMs + solves * solveMs) / 1000.0 / worldSeconds
            if (levels.levelCount == 4) fourLevelShare = share
            println(("STATIONARY WAVE COST ${levels.levelCount} levels on ${grid.rows} by ${grid.columns} (waves ${model.waves.first} to ${model.waves.last}, blocks of ${model.blockSize}): " +
                "factoring %.0f ms, a back-substitution %.1f ms, factoring and solving at once %.0f ms; the factors %.0f MB. Per world of %.1f s at the design's " +
                "$factorings factorings and $solves solves: %.2f%%").format(factorMs, solveMs, oneShotMs, factorBytes / 1e6, worldSeconds, 100 * share))
        }
        // Over the measured exception's hundredth at the design's counts: the model wants a graphics
        // path, or fewer solves, before a world calls it (docs/TODO.md). Reported, not held, until the
        // coupling sets how often it is called.
        println("STATIONARY WAVE COST: the four-level model at the design's counts is %.1f times rule 8's hundredth of a world".format(fourLevelShare / WORTH_A_DEVICE_SHARE))
    }
}
