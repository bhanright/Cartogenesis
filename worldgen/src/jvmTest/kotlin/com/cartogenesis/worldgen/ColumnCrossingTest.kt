package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.pipeline.ColumnCrossing
import com.cartogenesis.worldgen.pipeline.ColumnWater
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * One layer's crossing of a cell, its sources and sinks solved together ([ColumnCrossing]): it
 * conserves its water, it ends at the layer's own balance however long the crossing, and in
 * between it stands near a fine Runge-Kutta march of the same budget.
 *
 * The control is the arithmetic the march ran before W2b (`chargedInTurn`): the whole crossing's
 * evaporation first, then the cap clipped, then the sinks by a backward step. It conserves too,
 * but a long crossing leaves the layer far from its balance.
 */
class ColumnCrossingTest {

    private companion object {
        const val DAY_SECONDS = 86_400.0

        /**
         * How far the crossing's end may stand from the fine march's, as a share of the column's
         * distance from its balance at the start, plus a ten-thousandth of the cap for the
         * reference's own rounding: two hundredths. Each of the crossing's secant steps relaxes
         * the column by a quarter of an e-folding at most, and misses by the sinks' curvature over
         * it, 0.018 at worst on the cases below; one backward Euler step over the whole crossing
         * misses a linear sink alone by up to 0.16 of the same distance, and the control below by
         * up to four times the distance.
         */
        const val END_SHARE_OF_DISTANCE = 0.02
        const val REFERENCE_SLACK_SHARE = 1.0e-4

        /** The fine march's steps per crossing. */
        const val REFERENCE_STEPS = 200_000
    }

    /**
     * A layer's crossing in the march's units: the free troposphere over a tropical sea, raining by
     * the column's law; the boundary layer over the sea, evaporating on its own humidity and mixing
     * out; and the boundary layer lifted up a tropical and a cold slope.
     */
    private class Case(
        val name: String,
        /** The law's saturated column and the water it counts beside the layer's; zero for no law. */
        val lawSaturatedMm: Double,
        val lawOffsetMm: Double,
        val holdableMm: Double,
        val sourceMmPerDay: Double,
        val exchangePerDay: Double,
        val evaporationCeilingMm: Double,
        val evaporationPerDay: Double,
        val liftMps: Double,
        val airTemperatureC: Double,
        val groundPressureKpa: Double,
        val startShare: Double
    )

    private val cases = listOf(
        Case("the free troposphere over a tropical sea, arriving dry", 66.0, 20.0, 44.0, 5.0, 0.0, 0.0, 0.0, 0.0, 26.0, 101.3, 0.30),
        // The wettest a free troposphere arrives: where the single column's cap held it, 0.78 of the
        // whole column's saturation, its own share 0.72.
        Case("the free troposphere over a tropical sea, arriving at the column's old cap", 66.0, 20.0, 44.0, 5.0, 0.0, 0.0, 0.0, 0.0, 26.0, 101.3, 0.72),
        Case("the boundary layer over a tropical sea", 0.0, 0.0, 28.0, 3.0, 1.1, 26.0, 0.73, 0.0, 26.0, 101.3, 0.40),
        Case("the boundary layer lifted up a tropical slope", 0.0, 0.0, 25.0, 3.0, 1.1, 0.0, 0.0, 0.03, 24.0, 95.0, 0.60),
        Case("the boundary layer lifted up a tropical slope, arriving wet", 0.0, 0.0, 25.0, 3.0, 1.1, 0.0, 0.0, 0.03, 24.0, 95.0, 0.97),
        Case("the boundary layer lifted up a cold slope", 0.0, 0.0, 5.5, 1.0, 1.1, 0.0, 0.0, 0.05, 0.0, 85.0, 0.8),
        Case("a calm boundary layer whose sources outrun its cap", 0.0, 0.0, 28.0, 40.0, 0.1, 0.0, 0.0, 0.0, 26.0, 101.3, 0.5)
    )

    private fun crossingFor(case: Case, seconds: Double): ColumnCrossing = ColumnCrossing().apply {
        this.seconds = seconds
        sourceKgPerM2PerSecond = case.sourceMmPerDay / DAY_SECONDS
        lawSaturatedMm = case.lawSaturatedMm
        lawOffsetMm = case.lawOffsetMm
        holdableMm = case.holdableMm
        rainSuppression = 1.0
        exchangePerSecond = case.exchangePerDay / DAY_SECONDS
        evaporationPerSecond = case.evaporationPerDay / DAY_SECONDS
        evaporationCeilingMm = case.evaporationCeilingMm
        liftMps = case.liftMps
        airTemperatureC = case.airTemperatureC
        groundPressureKpa = case.groundPressureKpa
        layerDepthM = ColumnWater.boundaryLayerDepthM(case.airTemperatureC, case.groundPressureKpa)
        descentKgPerM2PerSecond = 0.0
        conversionPerSecond = 1.0e-3
        falloutPerSecond = 1.0e-3
        vapor = case.startShare * holdableMm
        cloud = 0.0
        falling = 0.0
    }

    /** The budget marched in small Runge-Kutta steps, the cap held by condensing what passes it. */
    private fun fineEndMm(case: Case, seconds: Double): Double {
        val probe = crossingFor(case, seconds)
        val holdable = probe.holdableMm
        val source = probe.sourceKgPerM2PerSecond
        fun rate(water: Double) = source - probe.sinkRate(water.coerceAtLeast(0.0))
        var water = minOf(probe.vapor, holdable)
        val step = seconds / REFERENCE_STEPS
        repeat(REFERENCE_STEPS) {
            val k1 = rate(water)
            val k2 = rate(water + 0.5 * step * k1)
            val k3 = rate(water + 0.5 * step * k2)
            val k4 = rate(water + step * k3)
            water = minOf(water + step * (k1 + 2 * k2 + 2 * k3 + k4) / 6.0, holdable)
        }
        return water
    }

    /** The layer's balance: where its sinks take its source, or the cap. */
    private fun balanceMm(case: Case): Double {
        val probe = crossingFor(case, 1.0)
        if (probe.sinkRate(probe.holdableMm) <= probe.sourceKgPerM2PerSecond) return probe.holdableMm
        var low = 0.0
        var high = probe.holdableMm
        repeat(200) {
            val middle = 0.5 * (low + high)
            if (probe.sinkRate(middle) > probe.sourceKgPerM2PerSecond) high = middle else low = middle
        }
        return 0.5 * (low + high)
    }

    /**
     * The march's arithmetic before W2b: the whole crossing's sources first, the cap clipped, then
     * the sinks by one backward step over the crossing.
     */
    private fun chargedInTurn(case: Case, seconds: Double): Double {
        val probe = crossingFor(case, seconds)
        val start = minOf(probe.vapor + probe.sourceKgPerM2PerSecond * seconds, probe.holdableMm)
        var low = 0.0
        var high = start
        repeat(200) {
            val middle = 0.5 * (low + high)
            if (middle + seconds * probe.sinkRate(middle) > start) high = middle else low = middle
        }
        return 0.5 * (low + high)
    }

    private val crossingDays = listOf(0.01, 0.06, 0.25, 1.0, 3.0, 10.0, 60.0)

    @Test
    fun `every crossing conserves its water`() {
        val random = Random(20261016)
        var worst = 0.0
        repeat(20_000) {
            val crossing = ColumnCrossing().apply {
                seconds = DAY_SECONDS * exp(random.nextDouble(-6.0, 5.0))
                sourceKgPerM2PerSecond = random.nextDouble(0.0, 30.0) / DAY_SECONDS
                val freeLayer = random.nextBoolean()
                lawSaturatedMm = if (freeLayer) random.nextDouble(0.5, 90.0) else 0.0
                lawOffsetMm = if (freeLayer) random.nextDouble(0.0, 0.4) * lawSaturatedMm else 0.0
                holdableMm = if (freeLayer) lawSaturatedMm - lawOffsetMm else random.nextDouble(0.5, 35.0)
                condensateFallsAtOnce = freeLayer
                rainSuppression = random.nextDouble(0.0, 1.0)
                exchangePerSecond = if (freeLayer) 0.0 else random.nextDouble(0.0, 3.0) / DAY_SECONDS
                evaporationPerSecond = if (!freeLayer && random.nextBoolean()) random.nextDouble(0.0, 2.0) / DAY_SECONDS else 0.0
                evaporationCeilingMm = random.nextDouble(0.0, 1.2) * holdableMm
                airTemperatureC = random.nextDouble(-30.0, 32.0)
                groundPressureKpa = random.nextDouble(55.0, 102.0)
                layerDepthM = ColumnWater.boundaryLayerDepthM(airTemperatureC, groundPressureKpa)
                liftMps = if (!freeLayer && random.nextBoolean()) random.nextDouble(0.0, 0.1) else 0.0
                descentKgPerM2PerSecond = if (liftMps == 0.0 && random.nextBoolean()) random.nextDouble(0.0, 20.0) / DAY_SECONDS else 0.0
                conversionPerSecond = random.nextDouble(0.0, 2.0e-3)
                falloutPerSecond = 1.0e-3
                vapor = random.nextDouble(0.0, 1.3) * holdableMm
                cloud = random.nextDouble(0.0, 5.0)
                falling = random.nextDouble(0.0, 2.0)
            }
            crossing.solve()
            assertTrue(crossing.vapor >= 0.0 && crossing.cloud >= 0.0 && crossing.falling >= 0.0, "a reservoir went below nothing")
            assertTrue(crossing.vapor <= crossing.holdableMm * (1 + 1e-12), "the layer ended over its cap")
            worst = maxOf(worst, abs(crossing.waterIn - crossing.waterOut) / crossing.waterIn)
        }
        println("CROSSING closure: worst of 20,000 random crossings %.2e of the water in".format(worst))
        assertTrue(worst < 1.0e-12, "a crossing leaves %.2e of its water unaccounted".format(worst))
    }

    @Test
    fun `a long crossing ends at the column's own balance, and the control does not`() {
        val longest = 1.0e4 * DAY_SECONDS
        val misses = ArrayList<String>()
        var controlWorst = 0.0
        for (case in cases) {
            val balance = balanceMm(case)
            val crossing = crossingFor(case, longest).also { it.solve() }
            val control = chargedInTurn(case, longest)
            println("CROSSING %s over 10,000 days: %.4f mm against its balance %.4f; charged in turn %.4f".format(case.name, crossing.vapor, balance, control))
            if (abs(crossing.vapor - balance) > 1.0e-6 * (balance + 1.0)) misses += case.name
            controlWorst = maxOf(controlWorst, abs(control - balance) / crossingFor(case, 1.0).holdableMm)
        }
        assertTrue(misses.isEmpty(), "long crossings away from their balance: $misses")
        assertTrue(controlWorst > 0.1, "the control should end a tenth of its cap or more from the balance; %.3f".format(controlWorst))
    }

    @Test
    fun `between the limits the crossing stands near a fine march, and the control does not`() {
        var worst = 0.0
        var controlWorst = 0.0
        for (case in cases) {
            val balance = balanceMm(case)
            for (days in crossingDays) {
                val seconds = days * DAY_SECONDS
                val crossing = crossingFor(case, seconds)
                val start = minOf(crossing.vapor, crossing.holdableMm)
                crossing.solve()
                val fine = fineEndMm(case, seconds)
                val control = chargedInTurn(case, seconds)
                val distance = abs(start - balance) + REFERENCE_SLACK_SHARE * crossing.holdableMm
                val share = abs(crossing.vapor - fine) / distance
                val controlShare = abs(control - fine) / distance
                println("CROSSING %s, %.2f days: %.3f mm against the fine march's %.3f (%.3f of the distance); charged in turn %.3f (%.3f)"
                    .format(case.name, days, crossing.vapor, fine, share, control, controlShare))
                worst = maxOf(worst, share)
                controlWorst = maxOf(controlWorst, controlShare)
            }
        }
        println("CROSSING worst %.3f of the column's distance from its balance (bar %.2f); the control %.3f".format(worst, END_SHARE_OF_DISTANCE, controlWorst))
        assertTrue(worst <= END_SHARE_OF_DISTANCE, "the crossing stands %.3f of its distance from the fine march".format(worst))
        assertTrue(controlWorst > END_SHARE_OF_DISTANCE, "the control should miss the fine march by more than the bar")
    }
}
