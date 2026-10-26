package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.BoundaryClass
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.TectonicHistory
import com.cartogenesis.worldgen.pipeline.TerrainStage
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import org.junit.Assert.assertTrue

/**
 * The continents' configuration over many worlds, as Earth's supercontinent cycle would scatter it
 * (docs/DESIGN_LEDGER.md, TH1-1): the largest landmass's share,
 * Korčák's K, the passive share of the crust's edge and the collision share of the boundaries are
 * phase-dependent, so they are read as distributions over a predeclared ensemble rather than held
 * per world.
 *
 * The categories are [CrustMeasures.Phase]'s, defined before the ensemble was first run: assembled
 * where one landmass holds three quarters of the land, as Meert's definition of a supercontinent
 * has it (Evans, Li and Murphy 2016), highly dispersed under 0.39, intermediate between. The
 * frequencies they are compared with are a calibration hypothesis, not a measurement: about a
 * quarter assembled, and the remaining three quarters split 60 to 15 between intermediate and
 * highly dispersed on no better evidence than Earth's last cycle spending most of its dispersal
 * near the middle. The history draws its worlds' phases from Earth's own cycles, a supercontinent
 * standing for 0.31 of the cycle (`TectonicsConfig.assembledShareOfCycle`), and the counts are
 * reported against the hypothesis, not fitted to it. Each count is reported with its binomial
 * interval.
 *
 * The land's hemisphere is read too, the share of all land in each world's most land-rich
 * hemisphere ([CrustMeasures.landHemisphereShare]), with the time since the world's breakup began:
 * Earth's is 0.811 some 200 Myr after Pangea began to part. A world as far into its dispersal as
 * Earth, its breakup [EARTH_LIKE_DISPERSAL_MYR] Myr ago or more, crowds its land into one hemisphere
 * no more than Earth does, on the median, give or take half the way to the 0.886 the TH1-1 history's
 * worlds read there, its continents crawling at a third of Earth's speeds (docs/DESIGN_LEDGER.md,
 * TH1-1b): the bar is midway, [CROWDED_HEMISPHERE_BAR].
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

    private class Reading(
        val largest: Double,
        val korcak: Double,
        val passive: Double,
        val collision: Double,
        val hemisphere: Double,
        /** When the world's supercontinent began to part, Myr ago; NaN if it has not. */
        val breakupMyrAgo: Double
    )

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
            collision = classes[BoundaryClass.COLLISION_PLATEAU.ordinal],
            hemisphere = CrustMeasures.landHemisphereShare(config, land),
            breakupMyrAgo = TectonicHistory.run(config).breakupMyrAgo
        )
    }

    private fun assertSpansTheCycle(name: String, seeds: List<Long>) {
        val readings = seeds.map { seed ->
            read(seed).also {
                println(
                    "ENSEMBLE %s seed %d: largest %.3f (%s), K %.3f, passive %.3f, collision %.3f, land hemisphere %.3f, breakup %.0f Myr ago"
                        .format(Locale.ROOT, name, seed, it.largest, CrustMeasures.phaseOf(it.largest), it.korcak, it.passive, it.collision,
                            it.hemisphere, it.breakupMyrAgo)
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
        describe("land hemisphere", readings.map { it.hemisphere })

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
        val atOrUnderEarth = readings.count { it.hemisphere <= CrustMeasures.EARTH_LAND_HEMISPHERE_SHARE }
        val (earthLow, earthHigh) = wilson(atOrUnderEarth, n)
        println(
            "ENSEMBLE %s land hemisphere at or under Earth's %.3f: %d of %d, %.2f (95%% interval %.2f-%.2f)"
                .format(Locale.ROOT, name, CrustMeasures.EARTH_LAND_HEMISPHERE_SHARE, atOrUnderEarth, n, atOrUnderEarth.toDouble() / n, earthLow, earthHigh)
        )
        for ((from, to) in DISPERSAL_BINS_MYR) {
            val bin = readings.filter {
                if (from < 0.0) it.breakupMyrAgo.isNaN() else !it.breakupMyrAgo.isNaN() && it.breakupMyrAgo >= from && it.breakupMyrAgo < to
            }.map { it.hemisphere }.sorted()
            if (bin.isEmpty()) continue
            println(
                "ENSEMBLE %s land hemisphere where the breakup began %s: median %.3f of %d"
                    .format(Locale.ROOT, name, if (from < 0.0) "not yet" else "%.0f to %.0f Myr ago".format(Locale.ROOT, from, to), bin[bin.size / 2], bin.size)
            )
        }
        val dispersedAsEarth = readings.filter { !it.breakupMyrAgo.isNaN() && it.breakupMyrAgo >= EARTH_LIKE_DISPERSAL_MYR }.map { it.hemisphere }.sorted()
        if (dispersedAsEarth.isNotEmpty()) {
            val median = dispersedAsEarth[dispersedAsEarth.size / 2]
            if (median > CROWDED_HEMISPHERE_BAR) {
                complaints.add("worlds whose breakup began $EARTH_LIKE_DISPERSAL_MYR Myr ago or more crowd ${"%.3f".format(Locale.ROOT, median)} of their land into one hemisphere on the median")
            }
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

        /** As far into the cycle as Earth, near enough: Pangea began to part about 200 Myr ago (Zhong et al. 2007's 175, Evans et al. 2016's 200). */
        const val EARTH_LIKE_DISPERSAL_MYR = 150.0

        /** Midway between Earth's 0.811 and the TH1-1 history's 0.886 at that age: see the class. */
        const val CROWDED_HEMISPHERE_BAR = 0.849

        /** The spans of time since the breakup the land's hemisphere is reported over; a start below zero is the worlds not yet broken. */
        val DISPERSAL_BINS_MYR = listOf(-1.0 to 0.0, 0.0 to 50.0, 50.0 to 100.0, 100.0 to 150.0, 150.0 to 201.0)

        /** The normal deviate of a two-sided 95% interval. */
        const val Z_95 = 1.959964
    }
}
