package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.BoundaryClass
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import org.junit.Assert.assertTrue

/**
 * The continents' configuration over many worlds, as Earth's supercontinent cycle would scatter it
 * (docs/DESIGN_LEDGER.md, TH1-1; the maintainer's decisions for TH1): the largest landmass's share,
 * Korčák's K, the passive share of the crust's edge and the collision share of the boundaries are
 * phase-dependent, so they are read as distributions over a predeclared ensemble rather than held
 * per world.
 *
 * The categories are [CrustMeasures.Phase]'s, defined before the ensemble was first run: assembled
 * where one landmass holds three quarters of the land, highly dispersed under 0.39, intermediate
 * between. The frequencies they are compared with are a calibration hypothesis, not a measurement:
 * about a quarter assembled, as Pangaea stood for about 150 of the cycle's 640 Myr (Nance, Evans and
 * Murphy 2022), and the remaining three quarters split 60 to 15 between intermediate and highly
 * dispersed on no better evidence than Earth's last cycle spending most of its dispersal near the
 * middle. Each count is reported with its binomial interval.
 *
 * The ensemble fails where every world looks alike (the largest landmass's share spread over less
 * than the intermediate band's own width between its tenth and ninetieth percentiles), where a
 * category never appears, or where one appears so much more often than the hypothesis says that a
 * binomial count as large would come up less than once in a thousand ensembles. The same is asked of
 * an independent set of validation seeds, so the calibration seeds cannot have been fitted.
 *
 * The plate stage alone, at 512 rows: the configuration is the history's, and erosion moves no
 * landmass's share. Main's plates made one character of world on every seed (the design's measures:
 * the largest landmass 0.37 to 0.94 of the land on three seeds, with no history to place it in a
 * cycle at all).
 */
class ContinentalConfigurationEnsembleTest {

    @Test
    fun `the calibration seeds span the cycle`() = assertSpansTheCycle("calibration", CALIBRATION_SEEDS)

    @Test
    fun `the validation seeds span the cycle`() = assertSpansTheCycle("validation", VALIDATION_SEEDS)

    private class Reading(val largest: Double, val korcak: Double, val passive: Double, val collision: Double)

    private fun read(seed: Long): Reading {
        val config = WorldGenConfig.forRows(seed, ROWS)
        val plates = PlateStage.generate(config, TerrainStage.generate(config))
        val land = CrustMeasures.landBeforeErosion(config, plates)
        val masses = CrustMeasures.landmasses(config, land)
        val edge = CrustMeasures.crustEdgeCells(config, plates)
        val classes = CrustMeasures.boundaryClassShares(plates)
        return Reading(
            largest = masses.largestShare,
            korcak = masses.korcakK,
            passive = CrustMeasures.shareFartherThan(config, plates, edge, PASSIVE_KM),
            collision = classes[BoundaryClass.COLLISION_PLATEAU.ordinal]
        )
    }

    private fun assertSpansTheCycle(name: String, seeds: List<Long>) {
        val readings = seeds.map { seed ->
            read(seed).also {
                println(
                    "ENSEMBLE %s seed %d: largest %.3f (%s), K %.3f, passive %.3f, collision %.3f"
                        .format(Locale.ROOT, name, seed, it.largest, CrustMeasures.phaseOf(it.largest), it.korcak, it.passive, it.collision)
                )
            }
        }
        fun describe(label: String, values: List<Double>) {
            val sorted = values.filter { !it.isNaN() }.sorted()
            fun at(share: Double) = sorted[((sorted.size - 1) * share).toInt()]
            println(
                "ENSEMBLE %s %s: min %.3f, p10 %.3f, p25 %.3f, median %.3f, p75 %.3f, p90 %.3f, max %.3f (n %d)"
                    .format(Locale.ROOT, name, label, sorted.first(), at(0.1), at(0.25), at(0.5), at(0.75), at(0.9), sorted.last(), sorted.size)
            )
        }
        describe("largest landmass share", readings.map { it.largest })
        describe("Korcak K", readings.map { it.korcak })
        describe("passive share", readings.map { it.passive })
        describe("collision share", readings.map { it.collision })

        val n = readings.size
        val complaints = ArrayList<String>()
        for (phase in CrustMeasures.Phase.entries) {
            val count = readings.count { CrustMeasures.phaseOf(it.largest) == phase }
            val target = TARGET_SHARES.getValue(phase)
            val (low, high) = wilson(count, n)
            val tail = upperTail(count, n, target)
            println(
                "ENSEMBLE %s %s: %d of %d, %.2f (95%% interval %.2f-%.2f) against the hypothesis's %.2f; a count this high or higher %.4f"
                    .format(Locale.ROOT, name, phase, count, n, count.toDouble() / n, low, high, target, tail)
            )
            if (count == 0) complaints.add("$phase never appears")
            if (tail < GROSS_EXCESS_PROBABILITY) complaints.add("$phase appears $count of $n times")
        }
        val sorted = readings.map { it.largest }.sorted()
        val spread = sorted[((n - 1) * 0.9).toInt()] - sorted[((n - 1) * 0.1).toInt()]
        val alike = CrustMeasures.ASSEMBLED_LARGEST_SHARE - CrustMeasures.DISPERSED_LARGEST_SHARE
        if (spread < alike) complaints.add("the largest landmass's share spreads $spread between its tenth and ninetieth percentiles")
        assertTrue("the $name ensemble does not span the cycle: " + complaints.joinToString("; "), complaints.isEmpty())
    }

    /** Wilson's 95% interval for [count] successes in [n]. */
    private fun wilson(count: Int, n: Int): Pair<Double, Double> {
        val p = count.toDouble() / n
        val z2 = Z_95 * Z_95
        val center = (p + z2 / (2 * n)) / (1 + z2 / n)
        val half = Z_95 * sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / (1 + z2 / n)
        return (center - half).coerceAtLeast(0.0) to (center + half).coerceAtMost(1.0)
    }

    /** The chance of [count] or more successes in [n] draws of probability [p]. */
    private fun upperTail(count: Int, n: Int, p: Double): Double {
        var sum = 0.0
        for (k in count..n) {
            var logTerm = ln(p) * k + ln(1 - p) * (n - k)
            for (i in 1..k) logTerm += ln((n - k + i).toDouble()) - ln(i.toDouble())
            sum += exp(logTerm)
        }
        return sum.coerceAtMost(1.0)
    }

    private companion object {
        const val ROWS = 512

        /** Predeclared before the first run: thirty seeds for the calibration, thirty more held apart. */
        val CALIBRATION_SEEDS: List<Long> = (1L..30L).toList()
        val VALIDATION_SEEDS: List<Long> = (1001L..1030L).toList()

        val TARGET_SHARES = mapOf(
            CrustMeasures.Phase.ASSEMBLED to 0.25,
            CrustMeasures.Phase.INTERMEDIATE to 0.60,
            CrustMeasures.Phase.DISPERSED to 0.15
        )

        /** Below this chance of a count so high, a category is grossly over-represented: once in a thousand ensembles. */
        const val GROSS_EXCESS_PROBABILITY = 0.001

        /** A crust edge farther than this from any plate boundary is passive: the design's measure. */
        const val PASSIVE_KM = 150.0

        /** The normal deviate of a two-sided 95% interval. */
        const val Z_95 = 1.959964
    }
}
