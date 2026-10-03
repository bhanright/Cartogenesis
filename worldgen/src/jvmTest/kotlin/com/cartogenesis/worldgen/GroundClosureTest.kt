package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.pipeline.GroundCells
import com.cartogenesis.worldgen.pipeline.GroundClosure
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Assert.assertTrue

/**
 * The two-height closure's arithmetic, held to the definitions it is written from, one cell at a
 * time and without a world (docs/DESIGN_LEDGER.md, E1a).
 *
 * Every clause compares a closed form or a solve against the quantity it stands for, computed the
 * long way: the steady Roering hillslope's mean height against a quadrature of its profile, the
 * rate a solve returns against its own equation, a steady block against the uplift it balances,
 * the head's support area against Montgomery and Dietrich's criterion, and the hypsometry's fill
 * against its own inverse and against the ground it must reproduce. Each tolerance is a few orders
 * above the double's precision and below anything a cell's height could show; each clause names a
 * control that would fail it.
 */
class GroundClosureTest {

    /**
     * The mean height of the steady profile is `integral_0^1 t u(beta t) dt`, written out as a
     * quadrature of the gradient's own definition, over thirteen decades of `beta`: from creep on a
     * gentle slope to a hillslope at its critical gradient. The control is linear creep's `beta / 3`,
     * the form `h` tends to, which is off by more than a tenth past `beta = 1`.
     */
    @Test
    fun `the hillslope's mean height is the mean of its steady profile`() {
        var worst = 0.0
        var linearWorst = 0.0
        for (decade in -60..40) {
            val beta = 10.0.pow(decade / 10.0)
            val quadrature = geometricSimpson { t -> t * profileGradient(beta * t) }
            val closed = GroundClosure.meanHeightShare(beta)
            worst = maxOf(worst, abs(closed - quadrature) / quadrature)
            linearWorst = maxOf(linearWorst, abs(beta / 3.0 - quadrature) / quadrature)
        }
        println("CLOSURE h(beta) against quadrature: worst relative error %.2e; linear creep's %.2e".format(worst, linearWorst))
        assertTrue("h(beta) is off its profile's mean by $worst", worst <= RELATIVE_TOLERANCE)
        assertTrue("the control does not fail: linear creep's form reads $linearWorst", linearWorst > CONTROL_SPREAD)
    }

    /** The slope the solve's Newton step uses is the derivative of the height it solves. */
    @Test
    fun `the mean height's slope is its derivative`() {
        var worst = 0.0
        for (decade in -50..40) {
            val beta = 10.0.pow(decade / 10.0)
            val step = beta * DIFFERENCE_STEP
            val difference = (GroundClosure.meanHeightShare(beta + step) - GroundClosure.meanHeightShare(beta - step)) / (2 * step)
            val slope = GroundClosure.meanHeightShareSlope(beta)
            worst = maxOf(worst, abs(slope - difference) / abs(difference))
        }
        assertTrue("h'(beta) is off the centred difference by $worst", worst <= DERIVATIVE_TOLERANCE)
    }

    /**
     * A stretch of hillslope from the divide to the foot is the whole hillslope, whose mean height is
     * `S_c L h`; and any stretch's mean is the mean of the heights along it, the profile integrated
     * twice by quadrature.
     */
    @Test
    fun `a stretch's mean height is the mean of the profile over it`() {
        var worst = 0.0
        for (rate in listOf(1e-7, 1e-5, 1e-4, 1e-3)) {
            for (length in listOf(50.0, 400.0, 3_000.0)) {
                val beta = rate * length / (GroundClosure.HILLSLOPE_DIFFUSIVITY_M2_PER_YEAR * GroundClosure.CRITICAL_HILLSLOPE_GRADIENT)
                val whole = GroundClosure.CRITICAL_HILLSLOPE_GRADIENT * length * GroundClosure.meanHeightShare(beta)
                val stretch = GroundClosure.stretchMeanHeightMetres(rate, length, 0.0, 1.0)
                worst = maxOf(worst, abs(stretch - whole) / whole)
                for ((from, to) in listOf(0.0 to 0.25, 0.4 to 0.6, 0.75 to 1.0)) {
                    val brute = simpson(QUADRATURE_INTERVALS) { share ->
                        val xi = from + (to - from) * share
                        GroundClosure.CRITICAL_HILLSLOPE_GRADIENT * length *
                            simpson(QUADRATURE_INTERVALS) { s -> (1 - xi) * profileGradient(beta * (xi + (1 - xi) * s)) }
                    }
                    val measured = GroundClosure.stretchMeanHeightMetres(rate, length, from, to)
                    worst = maxOf(worst, abs(measured - brute) / brute)
                }
            }
        }
        assertTrue("a stretch's mean height is off its profile by $worst", worst <= STRETCH_TOLERANCE)
    }

    /**
     * Each solve returns a rate that satisfies its own equation, to a part in a billion of the
     * relief it was handed, and none when it was handed none.
     */
    @Test
    fun `each solve satisfies its own equation`() {
        var worst = 0.0
        var stretchWorst = 0.0
        for (target in listOf(0.01, 1.0, 50.0, 400.0, 2_500.0)) {
            for (length in listOf(30.0, 200.0, 1_500.0, 12_000.0)) {
                for (network in listOf(0.0, 0.7, 3.4)) {
                    val rate = GroundClosure.channelRateMetresPerYear(target, length, network, ERODIBILITY, ROUND_YEARS)
                    val residual = GroundClosure.channelResidualMetres(rate, target, length, network, ERODIBILITY, ROUND_YEARS)
                    worst = maxOf(worst, abs(residual) / target)
                }
                val stretchRate = GroundClosure.stretchRateMetresPerYear(target, length, 0.3, 0.6, ROUND_YEARS)
                val stretchResidual =
                    GroundClosure.stretchMeanHeightMetres(stretchRate, length, 0.3, 0.6) + stretchRate * ROUND_YEARS - target
                stretchWorst = maxOf(stretchWorst, abs(stretchResidual) / target)
            }
        }
        println("CLOSURE solves: worst residual %.2e of the relief (channel), %.2e (stretch)".format(worst, stretchWorst))
        assertTrue("a channel cell's solve misses its equation by $worst of the relief", worst <= SOLVE_TOLERANCE)
        assertTrue("a hillslope cell's solve misses its equation by $stretchWorst", stretchWorst <= STRETCH_SOLVE_TOLERANCE)
        assertEquals(0.0, GroundClosure.channelRateMetresPerYear(0.0, 200.0, 1.0, ERODIBILITY, ROUND_YEARS))
        assertEquals(0.0, GroundClosure.channelRateMetresPerYear(-5.0, 200.0, 1.0, ERODIBILITY, ROUND_YEARS))
    }

    /**
     * A steady block: a channel cell whose bed is cut by the uplift each round, as a trunk at grade
     * is, settles where its interfluves lower at the uplift's rate and stand at the steady relief for
     * that rate. The closure is a relaxation toward that state, and this is its fixed point.
     */
    @Test
    fun `a block under steady uplift settles at the uplift's rate and its steady relief`() {
        for (upliftMetresPerYear in listOf(2e-5, 1e-4, 5e-4)) {
            val length = 300.0
            val network = 2.0
            var relief = 0.0
            var rate = 0.0
            repeat(STEADY_ROUNDS) {
                rate = GroundClosure.channelRateMetresPerYear(
                    relief + upliftMetresPerYear * ROUND_YEARS, length, network, ERODIBILITY, ROUND_YEARS
                )
                relief = relief + upliftMetresPerYear * ROUND_YEARS - rate * ROUND_YEARS
            }
            val steady = GroundClosure.steadyReliefMetres(upliftMetresPerYear, length, network, ERODIBILITY)
            println("CLOSURE steady block U=%.1e m/yr: rate %.4e, relief %.2f m against %.2f".format(upliftMetresPerYear, rate, relief, steady))
            assertTrue("the block lowers at $rate against an uplift of $upliftMetresPerYear", abs(rate / upliftMetresPerYear - 1) <= STEADY_TOLERANCE)
            assertTrue("the block stands at $relief m against its steady $steady", abs(relief / steady - 1) <= STEADY_TOLERANCE)
        }
    }

    /**
     * The head's support area meets Montgomery and Dietrich's criterion on the gradient it was
     * found with: the steeper of the regional slope and the steady hillslope's gradient at the foot
     * of a slope `sqrt(A)` long. Neither slope nor wear forms no head at all.
     */
    @Test
    fun `the head's support area meets the criterion on its own gradient`() {
        var worst = 0.0
        for (threshold in listOf(1.1e4, 2.2e5, 2.2e6)) {
            for (regional in listOf(0.0, 0.003, 0.05, 0.4)) {
                for (rate in listOf(0.0, 1e-7, 1e-5, 3e-4)) {
                    val area = GroundClosure.headSupportAreaSquareMetres(threshold, regional, rate)
                    if (regional == 0.0 && rate == 0.0) {
                        assertTrue("a head formed on ground with no slope and no wear", area.isInfinite())
                        continue
                    }
                    val hillslope = GroundClosure.CRITICAL_HILLSLOPE_GRADIENT * GroundClosure.gradientShare(
                        rate * sqrt(area) / (GroundClosure.HILLSLOPE_DIFFUSIVITY_M2_PER_YEAR * GroundClosure.CRITICAL_HILLSLOPE_GRADIENT)
                    )
                    val gradient = maxOf(regional, hillslope)
                    val criterion = area * gradient.pow(GRADIENT_EXPONENT)
                    worst = maxOf(worst, abs(criterion / threshold - 1))
                }
            }
        }
        assertTrue("the head's area misses the criterion by $worst", worst <= HEAD_TOLERANCE)
    }

    /**
     * The in-cell network's factor is the frozen renormalization: half of `E / K'` an octave from
     * the head's length to the hillslopes' reach, and nothing at or inside one octave.
     */
    @Test
    fun `the network factor is half an octave's relief per octave`() {
        assertEquals(0.0, GroundClosure.networkFactor(0.3))
        assertEquals(0.0, GroundClosure.networkFactor(1.0))
        assertEquals(0.5, GroundClosure.networkFactor(2.0), 1e-12)
        assertEquals(1.0, GroundClosure.networkFactor(4.0), 1e-12)
        assertEquals(0.5 * ln(37.0) / ln(2.0), GroundClosure.networkFactor(37.0), 1e-12)
    }

    /**
     * The hypsometry is one curve read both ways: the bed rise a fill reaches is the rise that
     * takes that fill, a fill to twice the interfluves' relief leaves a flat cell whose bed is its
     * ground, and the curve's mean, the bed's share at the bed and the rest spread evenly to twice
     * the relief, is the ground.
     */
    @Test
    fun `a fill and the bed rise it reaches are one curve read both ways`() {
        var worst = 0.0
        for (share in listOf(0.0, 0.003, 0.2, 0.9)) {
            for (relief in listOf(1.0, 80.0, 900.0)) {
                for (volume in listOf(0.001, 0.5, 30.0, 400.0, 5_000.0)) {
                    val rise = GroundClosure.bedRiseForFill(volume, share, relief)
                    val back = GroundClosure.fillForBedRise(rise, share, relief)
                    worst = maxOf(worst, abs(back - volume) / volume)
                }
                assertEquals(relief * (1 + share), GroundClosure.fillForBedRise(2 * relief, share, relief), 1e-9 * relief)
                // The curve itself: the share of the cell below a height z over the bed is the
                // bed's share and the hillslopes' spread evenly to twice the relief. The fill to a
                // rise is the integral of that share, and the mean height over the bed, the integral
                // of what lies above, is the ground's `(1 - s) R`.
                val below = { z: Double -> share + (1 - share) * (z / (2 * relief)).coerceIn(0.0, 1.0) }
                for (rise in listOf(0.1 * relief, relief, 1.9 * relief)) {
                    val integral = rise * simpson(QUADRATURE_INTERVALS) { t -> below(t * rise) }
                    worst = maxOf(worst, abs(GroundClosure.fillForBedRise(rise, share, relief) - integral) / integral)
                }
                val meanAboveBed = 2 * relief * simpson(QUADRATURE_INTERVALS) { t -> 1 - below(t * 2 * relief) }
                assertEquals((1 - share) * relief, meanAboveBed, 1e-9 * relief, "the hypsometry's mean is not the ground")
            }
        }
        assertTrue("the fill and the rise disagree by $worst", worst <= RELATIVE_TOLERANCE)

        // On the cells themselves: a cell filled to its brim is one height, and the ground took
        // every bit of the volume the walk handed it, less what its floats could not hold.
        val cells = GroundCells(1, floatArrayOf(0.5f))
        cells.bed[0] = 0.48f
        cells.bedShare[0] = 0.1f
        val ground = floatArrayOf(0.5f)
        val brim = cells.reliefAboveBed(0, ground) * (1 + 0.1)
        cells.lay(floatArrayOf(brim.toFloat()), ground)
        assertEquals(ground[0], cells.bed[0], 2e-7f, "a cell filled to its brim keeps two heights")
    }

    /**
     * A change in the bed's share between rounds divides the same ground differently and moves no
     * material: the interfluves the round notes stand where the ground and the bed put them for the
     * new share, so the share's ground is the ground; and a round that cuts nothing and lowers
     * nothing leaves every ground where it was.
     */
    @Test
    fun `a change of the channel's width is a repartition of the same ground`() {
        val cells = GroundCells(1, floatArrayOf(0.62f))
        cells.bed[0] = 0.60f
        val ground = floatArrayOf(0.62f)
        for (share in listOf(0.001f, 0.05f, 0.3f, 0.8f)) {
            cells.bedShare[0] = share
            val interfluve = cells.bed[0] + cells.reliefAboveBed(0, ground)
            val rebuilt = share * cells.bed[0] + (1 - share) * interfluve
            assertEquals(ground[0].toDouble(), rebuilt, 1e-7, "share $share does not divide the ground it was given")
        }
        // A cut followed by its ground: the volume returned is what the ground lost, to the float.
        cells.bedShare[0] = 0.05f
        val before = ground[0]
        cells.bed[0] -= 0.01f
        val volume = cells.groundFollowsCut(0, 0.01, ground)
        assertEquals((before - ground[0]).toDouble(), volume, 0.0, "the cut's volume is not what the ground lost")
        assertEquals(0.05 * 0.01, volume, FLOAT_STEP_NEAR_ONE)
    }

    /**
     * The steady gradient's definition, `(sqrt(1 + 4 s^2) - 1) / (2 s)`, written as the same number
     * without the cancellation that would make the reference less exact than what it checks.
     */
    private fun profileGradient(s: Double): Double = 2 * s / (1 + sqrt(1 + 4 * s * s))

    /**
     * Simpson's rule over panels whose widths grow geometrically from the origin, where a steep
     * hillslope's gradient turns over within `1 / beta` of the divide and a uniform rule would not
     * see it.
     */
    private fun geometricSimpson(f: (Double) -> Double): Double {
        var total = 0.0
        var from = 0.0
        var to = FIRST_PANEL
        while (from < 1.0) {
            val width = to - from
            total += width * simpson(PANEL_INTERVALS) { share -> f(from + width * share) }
            from = to
            to = minOf(1.0, to * PANEL_GROWTH)
        }
        return total
    }

    private fun simpson(intervals: Int, f: (Double) -> Double): Double {
        val h = 1.0 / intervals
        var sum = f(0.0) + f(1.0)
        for (i in 1 until intervals) sum += f(i * h) * if (i % 2 == 1) 4 else 2
        return sum * h / 3
    }

    private companion object {
        /** Simpson's rule over this many intervals is exact to about 1e-13 on these smooth integrands. */
        const val QUADRATURE_INTERVALS = 2_000

        /** The geometric rule's first panel, its growth and the intervals in each: 1e-9 to 1 in 69 panels. */
        const val FIRST_PANEL = 1e-9
        const val PANEL_GROWTH = 1.35
        const val PANEL_INTERVALS = 200

        /** One step of a float near one, the most a stored height can be off what was asked of it. */
        const val FLOAT_STEP_NEAR_ONE = 1.2e-7

        /** A part in ten billion: the closed forms against a quadrature good to 1e-13. */
        const val RELATIVE_TOLERANCE = 1e-10

        /** A centred difference at a relative step of 1e-5 is good to about 1e-9 of the slope. */
        const val DIFFERENCE_STEP = 1e-5
        const val DERIVATIVE_TOLERANCE = 1e-7

        /** Four-point Gauss-Legendre on a smooth integrand, against a nested quadrature. */
        const val STRETCH_TOLERANCE = 1e-6

        /** What linear creep's form must miss by for the first clause's control to be a control. */
        const val CONTROL_SPREAD = 0.1

        const val SOLVE_TOLERANCE = 1e-9
        const val STRETCH_SOLVE_TOLERANCE = 1e-6
        const val STEADY_TOLERANCE = 1e-6
        const val HEAD_TOLERANCE = 1e-6

        /** Enough rounds for the slowest block here to settle to the steady tolerance. */
        const val STEADY_ROUNDS = 4_000

        const val ERODIBILITY = 1e-6
        const val ROUND_YEARS = 336_476.4
        const val GRADIENT_EXPONENT = 1.65
    }
}
