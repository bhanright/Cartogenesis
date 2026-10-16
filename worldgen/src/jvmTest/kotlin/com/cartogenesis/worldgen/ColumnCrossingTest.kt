package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.pipeline.ColumnCrossing
import com.cartogenesis.worldgen.pipeline.MoistureMarch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One column's crossing of a cell, its sources and sinks solved together ([ColumnCrossing]):
 * it conserves its water, it ends at the column's own balance however long the crossing, and in
 * between it stands near a fine Runge-Kutta march of the same budget.
 *
 * The control is the arithmetic the march ran before (`chargedInTurn`): the whole crossing's
 * evaporation first, then the cap clipped, then the lifting, then the column's rain by a backward
 * step. It conserves too, but a long crossing leaves the column far from its balance.
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

    /** A tropical sea, a lifted tropical land column, and a cold one, each in the march's units. */
    private class Case(
        val name: String,
        val saturatedMm: Double,
        val sourceMmPerDay: Double,
        val liftingMmPerDay: Double,
        val criticalHumidity: Double,
        val startShare: Double
    )

    private val cases = listOf(
        Case("tropical sea, arriving dry", 66.0, 5.0, 0.0, 0.0, 0.40),
        Case("tropical sea, arriving at the cap", 66.0, 5.0, 0.0, 0.0, 1.0),
        Case("tropical land, lifted and gated", 55.0, 3.0, 20.0, 0.75, 0.55),
        Case("tropical land, lifted and gated, arriving wet", 55.0, 3.0, 20.0, 0.75, 0.95),
        Case("cold land, lifted on the whole column", 8.0, 1.0, 6.0, 0.0, 0.7),
        Case("a calm sea whose sources outrun the cap", 66.0, 40.0, 0.0, 0.0, 0.5)
    )

    private fun crossingFor(case: Case, seconds: Double): ColumnCrossing = ColumnCrossing().apply {
        this.seconds = seconds
        sourceKgPerM2PerSecond = case.sourceMmPerDay / DAY_SECONDS
        saturatedMm = case.saturatedMm
        holdableMm = MoistureMarch.HOLDABLE_SHARE * case.saturatedMm
        rainSuppression = 1.0
        liftingKgPerM2PerSecond = case.liftingMmPerDay / DAY_SECONDS
        criticalHumidity = case.criticalHumidity
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

    /** The column's balance: where its sinks take its source, or the cap. */
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

    /** The march's arithmetic before: sources, the cap clipped, the lifting, then a backward step of rain. */
    private fun chargedInTurn(case: Case, seconds: Double): Double {
        val probe = crossingFor(case, seconds)
        val holdable = probe.holdableMm
        var water = probe.vapor + probe.sourceKgPerM2PerSecond * seconds
        if (water > holdable) water = holdable
        if (probe.liftingKgPerM2PerSecond > 0.0) water *= exp(-probe.liftingKgPerM2PerSecond / holdable * seconds)
        val start = water
        repeat(200) {
            val rain = MoistureMarch.columnRainMmPerDay(water / case.saturatedMm) / DAY_SECONDS * seconds
            val slope = 1.0 + MoistureMarch.COLUMN_RAIN_PER_HUMIDITY / case.saturatedMm *
                exp(MoistureMarch.COLUMN_RAIN_PER_HUMIDITY * (water / case.saturatedMm - MoistureMarch.COLUMN_RAIN_HUMIDITY_OFFSET)) / DAY_SECONDS * seconds
            water = (water - (water + rain - start) / slope).coerceAtLeast(0.0)
        }
        return water
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
                saturatedMm = random.nextDouble(0.5, 90.0)
                holdableMm = MoistureMarch.HOLDABLE_SHARE * saturatedMm
                rainSuppression = random.nextDouble(0.0, 1.0)
                liftingKgPerM2PerSecond = if (random.nextBoolean()) random.nextDouble(0.0, 50.0) / DAY_SECONDS else 0.0
                criticalHumidity = if (random.nextBoolean()) 0.75 else 0.0
                descentKgPerM2PerSecond = if (liftingKgPerM2PerSecond == 0.0 && random.nextBoolean()) random.nextDouble(0.0, 20.0) / DAY_SECONDS else 0.0
                descentTakesColumnRain = random.nextBoolean()
                conversionPerSecond = random.nextDouble(0.0, 2.0e-3)
                falloutPerSecond = 1.0e-3
                vapor = random.nextDouble(0.0, 1.3) * holdableMm
                cloud = random.nextDouble(0.0, 5.0)
                falling = random.nextDouble(0.0, 2.0)
            }
            crossing.solve()
            assertTrue(crossing.vapor >= 0.0 && crossing.cloud >= 0.0 && crossing.falling >= 0.0, "a reservoir went below nothing")
            assertTrue(crossing.vapor <= crossing.holdableMm * (1 + 1e-12), "the column ended over its cap")
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

    @Test
    fun `the saturated share is Sundqvist's, none under the critical humidity`() {
        assertEquals(0.0, ColumnCrossing.saturatedShare(0.75, 0.75), 0.0)
        assertEquals(0.0, ColumnCrossing.saturatedShare(0.5, 0.75), 0.0)
        assertEquals(1.0, ColumnCrossing.saturatedShare(1.0, 0.75), 0.0)
        assertEquals(1.0 - kotlin.math.sqrt(0.1 / 0.25), ColumnCrossing.saturatedShare(0.9, 0.75), 1e-15)
        assertEquals(0.6, ColumnCrossing.saturatedShare(0.6, 0.0), 0.0)
    }
}
