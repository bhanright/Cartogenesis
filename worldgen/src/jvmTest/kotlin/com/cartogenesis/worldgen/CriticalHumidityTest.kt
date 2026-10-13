package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.pipeline.MoistureMarch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The march's lifted condensation under a critical humidity ([MoistureMarch.columnAfterLiftMm]):
 * lifted air condenses only in the column's saturated share, Sundqvist and others' (1989)
 * `b = 1 - sqrt((1 - f) / (1 - f_c))`, at Zhao and Carr's (1997) thresholds.
 *
 * The control is the law the march ran before, `C_w w W / W_hold`, which condenses at any humidity:
 * the first clause fails on it.
 */
class CriticalHumidityTest {

    private val holdableMm = 50.0

    /** Smith and Barstad's source for saturated air at 25 C rising at a centimeter a second, kg/m2/s. */
    private val upliftKgPerM2PerSecond = 0.0134 * 0.01

    /** The law before the gate, over a crossing of [seconds]: the column water left. */
    private fun ungatedAfterLiftMm(startMm: Double, seconds: Double): Double =
        startMm * exp(-upliftKgPerM2PerSecond / holdableMm * seconds)

    @Test
    fun `a column at or under its critical humidity does not condense however long it is lifted`() {
        val critical = MoistureMarch.CRITICAL_HUMIDITY_OVER_LAND
        for (humidity in listOf(0.2, 0.5, critical)) {
            val start = humidity * holdableMm
            val after = MoistureMarch.columnAfterLiftMm(start, holdableMm, critical, upliftKgPerM2PerSecond, 1.0e7)
            assertEquals(start, after, 0.0, "a column at $humidity of its holdable water condensed")
        }
        // The control: the law before the gate takes a column at half its holdable water down by a fifth
        // over a day of lifting.
        val control = ungatedAfterLiftMm(0.5 * holdableMm, 86_400.0)
        assertTrue(0.5 * holdableMm - control > 0.05 * holdableMm, "the ungated control should condense below the critical humidity")
    }

    @Test
    fun `lifted for long the column falls to its critical humidity and not below`() {
        for (critical in listOf(MoistureMarch.CRITICAL_HUMIDITY_OVER_LAND, MoistureMarch.CRITICAL_HUMIDITY_OVER_SEA)) {
            val after = MoistureMarch.columnAfterLiftMm(holdableMm, holdableMm, critical, upliftKgPerM2PerSecond, 1.0e9)
            assertTrue(after >= critical * holdableMm, "fell below the critical humidity: $after")
            assertEquals(critical * holdableMm, after, 1.0e-3 * holdableMm)
        }
    }

    @Test
    fun `the crossing's exact answer is the equation's integral`() {
        val critical = MoistureMarch.CRITICAL_HUMIDITY_OVER_LAND
        for (startShare in listOf(1.0, 0.95, 0.8)) {
            for (seconds in listOf(1_000.0, 10_000.0, 200_000.0)) {
                // Runge-Kutta in small steps of dW/dt = -C_w w b(W / W_hold).
                fun rate(water: Double): Double {
                    val humidity = water / holdableMm
                    if (humidity <= critical) return 0.0
                    return -upliftKgPerM2PerSecond * (1.0 - sqrt((1.0 - humidity) / (1.0 - critical)))
                }
                val steps = 200_000
                val step = seconds / steps
                var water = startShare * holdableMm
                for (index in 0 until steps) {
                    val k1 = rate(water)
                    val k2 = rate(water + 0.5 * step * k1)
                    val k3 = rate(water + 0.5 * step * k2)
                    val k4 = rate(water + step * k3)
                    water += step * (k1 + 2 * k2 + 2 * k3 + k4) / 6.0
                }
                val exact = MoistureMarch.columnAfterLiftMm(startShare * holdableMm, holdableMm, critical, upliftKgPerM2PerSecond, seconds)
                assertTrue(abs(exact - water) < 1.0e-6 * holdableMm, "from $startShare over $seconds s: $exact against $water")
            }
        }
    }

    @Test
    fun `a saturated column condenses at Smith and Barstad's whole rate at first`() {
        val seconds = 1.0
        val after = MoistureMarch.columnAfterLiftMm(holdableMm, holdableMm, MoistureMarch.CRITICAL_HUMIDITY_OVER_SEA, upliftKgPerM2PerSecond, seconds)
        assertEquals(upliftKgPerM2PerSecond * seconds, holdableMm - after, 1.0e-2 * upliftKgPerM2PerSecond * seconds)
    }
}
