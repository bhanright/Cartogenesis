package com.cartogenesis.worldgen.pipeline

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * What a cell's ground does between its channel and its divides: the half of the erosion that a
 * grid of kilometre cells cannot resolve, written as a law instead of left to the cell size.
 *
 * A cell carries two heights. Its **bed** is the trunk channel at the cell's outlet, the surface
 * the water is routed over and the stream-power law cuts. Its **ground** is the mean height of all
 * its land, the surface the shoreline, the climate and the plate read. Between the two stand the
 * cell's own hillslopes and, on a cell larger than a channel head's support area, its own
 * unresolved channel network, and their relief above the bed is what this file supplies:
 *
 *     ground = s_b * bed + (1 - s_b) * interfluve
 *     interfluve - bed = S_c * L_h * h(beta) + (E / K') * N(r)
 *
 * where `s_b` is the share of the cell the trunk's own bed covers, `E` the rate the interfluves
 * are lowering, the first term the mean height of a steady Roering hillslope of length `L_h`
 * eroding at `E`, and the second the mean height of the in-cell network's beds above the trunk.
 * Both terms are means over the cell's land, each point counted once: a point's height above the
 * trunk is its hillslope's height above the channel it drains to plus that channel's height above
 * the trunk, and the two terms are those two pieces averaged by area.
 *
 * **Why two heights.** With one height per cell, every point of a cell is lowered at the rate of
 * the trunk crossing it, so a coarse cell, whose trunk drains more ground and so cuts faster than
 * the small channels a fine grid would resolve inside it, takes more off its whole area than the
 * same ground loses on a finer grid: measured, the stage's denudation fell by half from 256 to 1,024
 * rows on the same plates (docs/TODO.md, E1's diagnosis). With two, the trunk cuts the bed alone,
 * and the ground follows it only as fast as its own hillslopes and channels can carry the relief
 * down, which is a property of the ground and not of the cell.
 *
 * **Transient as a relaxation.** The relief is taken to be the steady relief for the rate the
 * interfluves are lowering at now, and the rate is found by backward Euler: the relief at the end
 * of a round is the relief it opened with, plus what the bed was cut, less what the interfluves
 * lost, `R_ss(E) + E * dt = R + cut`. That is a quasi-steady closure, not a resolved hillslope
 * evolution; how far it departs from one is what the benchmark guard measures.
 *
 * Everything here is in metres, square metres and years, and a cell's geometry arrives already
 * converted from `WorldScale` and the grid. Every function is a pure function of its arguments, one
 * cell at a time, which is the shape the graphics-card path behind [ErosionAccelerator] will take
 * (the device kernel is a later chunk's; this file is the processor's answer it is held to).
 *
 * See docs/DESIGN_LEDGER.md, E1a, for the design, its review and the figures.
 */
internal object GroundClosure {

    /**
     * The hillslope transport coefficient, in square metres a year: Roering, Kirchner and Dietrich
     * (*Evidence for nonlinear, diffusive sediment transport on hillslopes*, Water Resources
     * Research 35, 1999), calibrated on the Oregon Coast Range's soil-mantled hillslopes.
     *
     * Theirs is the law, `q = D S / (1 - (S / S_c)^2)`: linear creep on gentle ground, flux growing
     * without bound as the gradient nears the critical one. One coefficient for every rock and
     * climate, as the stream-power coefficient is one; a range of a factor of ten is reported
     * across later sites, and this is the calibration the law was published with.
     */
    const val HILLSLOPE_DIFFUSIVITY_M2_PER_YEAR = 0.0032

    /**
     * The gradient soil-mantled hillslopes cannot stand past, Roering, Kirchner and Dietrich's
     * 1.25 (51 degrees) from the same calibration.
     */
    const val CRITICAL_HILLSLOPE_GRADIENT = 1.25

    /**
     * How much one octave of a cell's own channel network adds to the mean height of its ground
     * above its trunk, in units of `E / K'`.
     *
     * **A discrete renormalization on the D8 grid, frozen here before any grid was compared.** The
     * in-cell term must carry exactly the relief the next-finer grid would resolve, or the ground
     * stands at a different height on every grid. Refine a channel cell of side `2h` into the 2-by-2
     * block of cells of side `h` a grid twice as fine draws there:
     *
     * - *The geometry D8 specifies.* A trunk crossing the block straight occupies two of the four
     *   cells, along a row or a column or on the block's diagonal. Each of the other two holds only
     *   its own ground and drains to a trunk cell beside it: by an orthogonal step `h` in every
     *   case, because on the diagonal both trunk cells are orthogonal neighbours of each side cell,
     *   and on the straight trunk the orthogonal step is steeper than the diagonal one at steady
     *   state (the trunk falls far less over a cell than a side cell stands above it).
     * - *The incision law.* The implicit update `z' = z_r' + (z + E dt - z_r') / (1 + F)` with
     *   `F = K' dt sqrt(A) / step` holds a cell at steady state `E dt / F = (E / K') step / sqrt(A)`
     *   above its receiver. A side cell drains its own ground, `A = h^2`, by a step of `h`, so it
     *   stands `E / K'` above the trunk; the two trunk cells average to the trunk's bed at the
     *   block's centre, which is the coarse cell's bed, to first order in the trunk's own fall.
     * - *The area weights.* Four cells of equal ground, so the block's mean bed stands
     *   `(0 + 0 + 1 + 1) / 4 = 1/2` of `E / K'` above the coarse cell's bed.
     *
     * The hillslope term is the same on both grids so long as both cells' hillslope reach, `X`,
     * exceeds the head's support length, so the in-cell network term grows by exactly this much per
     * halving: `N(r) = (1/2) log2(r)`, octaves counted from the head's length `sqrt(A_c)` up to `X`,
     * and nothing at `r <= 1`, where hillslopes reach the trunk directly. A turning trunk occupies
     * three cells of the four and leaves one side cell, a quarter; a source cell's block is three
     * side cells round an outlet; this takes the straight crossing as the D8 grid's typical octave
     * and does not average over configurations, so it is a stated geometry rather than a fit. The
     * continuum form the design began from, the mean of `ln(x / x_c)` over a channel whose area
     * grows as its length squared, adds `ln 2 = 0.69` an octave in the limit; D8's own octave adds
     * less because its side cells sit a single step from the trunk.
     */
    const val NETWORK_RELIEF_PER_OCTAVE = 0.5

    /**
     * Channel width per square root of mean annual discharge, in metres per `(m^3/s)^(1/2)`.
     *
     * The exponent is Leopold and Maddock's one half, which Wohl and David (*Consistency of
     * scaling relations among bedrock and alluvial channels*, JGR 113, 2008) find holds for bedrock
     * channels as for alluvial ones once mean annual flow is accounted for. The coefficient is
     * anchored on large gauged rivers at their mean annual flow: the Rhine at Basel, about 200 m
     * wide at 1,050 m^3/s (6.2), the Mississippi at Vicksburg, about 1,000 m at 17,000 (7.7), and
     * the Amazon at Obidos, about 2.3 km at 170,000 (5.6), whose mean is 6.5. It decides only the
     * share of a cell the trunk's bed covers, which is a few hundredths on every grid this program
     * draws below 4,096 rows, so it is the least consequential constant in the closure.
     */
    const val CHANNEL_WIDTH_METRES_PER_ROOT_DISCHARGE = 6.5

    /**
     * The share of rainfall that reaches the rivers, Earth's: about 40,000 of the 110,000 cubic
     * kilometres that fall on land each year (Trenberth and others 2007), the figure the lakes'
     * water balance cites. The erosion's weight is rainfall standing in for runoff (see [Runoff]);
     * a width wants a discharge, so this is where the two part.
     */
    const val RUNOFF_SHARE_OF_RAINFALL = 40_000.0 / 110_000.0

    /** Seconds in a year, for a discharge in cubic metres a second. */
    const val SECONDS_PER_YEAR = 365.25 * 86_400.0

    /** Square metres in a square kilometre. */
    const val SQUARE_METRES_PER_SQUARE_KILOMETRE = 1e6

    /**
     * Below this `beta` the hillslope functions are read off their series rather than their closed
     * forms, whose terms cancel there: the closed form for `h` subtracts terms of order `beta` to
     * leave one of order `beta^3`, and at 1e-3 it has lost seven of its sixteen digits. The series
     * (see [CATALAN]) converges for `beta < 1/2` at a ratio of `4 beta^2` a term, so at a tenth its
     * [SERIES_TERMS] terms are exact to the double, where the closed form has lost two digits.
     */
    private const val SERIES_BELOW_BETA = 0.1

    /** Terms of the series: `(4 * 0.1^2)^24` is 1e-19, below the double's last digit. */
    private const val SERIES_TERMS = 24

    /**
     * The Catalan numbers, `C_k = (2k)! / ((k + 1)! k!)`. The steady gradient is their generating
     * function: `u(s) = sum (-1)^k C_k s^(2k + 1)`, since `u` solves `s u^2 + u - s = 0`, which
     * gives the hillslope's mean height and its cumulative gradient as series term by term.
     */
    private val CATALAN = DoubleArray(SERIES_TERMS).also { numbers ->
        numbers[0] = 1.0
        for (k in 1 until SERIES_TERMS) numbers[k] = numbers[k - 1] * 2.0 * (2 * k - 1) / (k + 1)
    }

    /** Newton steps a solve may take; every solve here converges monotonically in under a dozen. */
    private const val MAX_SOLVER_STEPS = 60

    /**
     * Where a solve stops: when a step moves the rate by less than this share of itself, which is
     * the double's own precision with a few bits to spare.
     */
    private const val SOLVER_RELATIVE_STEP = 1e-13

    /** Montgomery and Dietrich's exponent, as `ChannelInitiation` holds it. */
    private const val HEAD_GRADIENT_EXPONENT = ChannelInitiation.GRADIENT_EXPONENT

    /**
     * The steady Roering hillslope's gradient at a point, over the critical gradient:
     * `u(s) = (sqrt(1 + 4 s^2) - 1) / (2 s)` at `s = E x / (D S_c)`, `x` the distance from the
     * divide. Written `2 s / (1 + sqrt(1 + 4 s^2))`, which is the same number without the
     * cancellation. Tends to `s` on gentle ground and to one at the critical gradient.
     */
    fun gradientShare(s: Double): Double = 2.0 * s / (1.0 + sqrt(1.0 + 4.0 * s * s))

    /**
     * The mean height of a steady Roering hillslope above its foot, over `S_c L`, as a function of
     * `beta = E L / (D S_c)`: the area-weighted mean over a hillslope of uniform width,
     * `h(beta) = [ (beta / 2) sqrt(1 + 4 beta^2) + asinh(2 beta) / 4 - beta ] / (2 beta^2)`, which
     * is `integral_0^1 t u(beta t) dt`. Tends to `beta / 3` (linear creep's parabola) and to one
     * half (a planar slope at the critical gradient).
     */
    fun meanHeightShare(beta: Double): Double {
        if (beta < SERIES_BELOW_BETA) {
            // integral_0^1 t u(beta t) dt = sum (-1)^k C_k beta^(2k + 1) / (2k + 3).
            val squared = beta * beta
            var power = beta
            var sum = 0.0
            for (k in 0 until SERIES_TERMS) {
                val term = CATALAN[k] * power / (2 * k + 3)
                sum += if (k % 2 == 0) term else -term
                power *= squared
            }
            return sum
        }
        val root = sqrt(1.0 + 4.0 * beta * beta)
        val numerator = 0.5 * beta * root + 0.25 * asinh(2.0 * beta) - beta
        return numerator / (2.0 * beta * beta)
    }

    /** `d h / d beta`, from the closed form: `(sqrt(1 + 4 beta^2) - 1) / (2 beta^2) - 2 h / beta`. */
    fun meanHeightShareSlope(beta: Double): Double {
        if (beta < SERIES_BELOW_BETA) {
            val squared = beta * beta
            var power = 1.0
            var sum = 0.0
            for (k in 0 until SERIES_TERMS) {
                val term = CATALAN[k] * (2 * k + 1) * power / (2 * k + 3)
                sum += if (k % 2 == 0) term else -term
                power *= squared
            }
            return sum
        }
        val root = sqrt(1.0 + 4.0 * beta * beta)
        return (root - 1.0) / (2.0 * beta * beta) - 2.0 * meanHeightShare(beta) / beta
    }

    /**
     * The in-cell network's factor `N` for a hillslope reach [octaveRatio] times the head's support
     * length: [NETWORK_RELIEF_PER_OCTAVE] per octave, zero at or below one. See that constant.
     */
    fun networkFactor(octaveRatio: Double): Double =
        if (octaveRatio > 1.0) NETWORK_RELIEF_PER_OCTAVE * ln(octaveRatio) / LN_TWO else 0.0

    /**
     * The steady relief of a channel cell's interfluves above its bed, in metres, for interfluves
     * lowering at [rateMetresPerYear]: a hillslope [hillslopeLengthMetres] long and the in-cell
     * network's [network] factor (see [networkFactor]) at an erodibility of
     * [erodibilityPerYear], `K'`.
     *
     * `K'` is the stream-power coefficient the trunk is cut with, times the cover's factor and the
     * square root of the cell's own runoff weight: the in-cell channels drain the cell's own ground
     * at its own rainfall, so their discharge is that weight times their area, under the same
     * normalization the trunk's accumulation carries.
     */
    fun steadyReliefMetres(
        rateMetresPerYear: Double,
        hillslopeLengthMetres: Double,
        network: Double,
        erodibilityPerYear: Double
    ): Double {
        val beta = rateMetresPerYear * hillslopeLengthMetres /
            (HILLSLOPE_DIFFUSIVITY_M2_PER_YEAR * CRITICAL_HILLSLOPE_GRADIENT)
        val hillslope = CRITICAL_HILLSLOPE_GRADIENT * hillslopeLengthMetres * meanHeightShare(beta)
        val channels = if (network > 0.0 && erodibilityPerYear > 0.0) rateMetresPerYear / erodibilityPerYear * network else 0.0
        return hillslope + channels
    }

    /**
     * The rate a channel cell's interfluves lower at over a round of [years], in metres a year: the
     * root of `R_ss(E) + E * years = targetMetres`, where [targetMetres] is the relief the round
     * opened with plus what the bed was cut (see the class note), and zero where that is not
     * positive.
     *
     * Newton's method from the linear-creep guess. The left side is increasing and concave in `E`
     * (the hillslope's mean height is concave in its rate), and the guess lies at or below the root
     * because `h(beta) <= beta / 3`, so every step lands at or below the root and the iterates rise
     * to it monotonically: no bracket can be left and no step can overshoot.
     */
    fun channelRateMetresPerYear(
        targetMetres: Double,
        hillslopeLengthMetres: Double,
        network: Double,
        erodibilityPerYear: Double,
        years: Double
    ): Double {
        if (targetMetres <= 0.0) return 0.0
        val length = hillslopeLengthMetres
        val channelPerRate = if (network > 0.0 && erodibilityPerYear > 0.0) network / erodibilityPerYear else 0.0
        val betaPerRate = length / (HILLSLOPE_DIFFUSIVITY_M2_PER_YEAR * CRITICAL_HILLSLOPE_GRADIENT)
        val linearPerRate = length * length / (3.0 * HILLSLOPE_DIFFUSIVITY_M2_PER_YEAR)
        var rate = targetMetres / (linearPerRate + channelPerRate + years)
        repeat(MAX_SOLVER_STEPS) {
            val beta = rate * betaPerRate
            val residual = CRITICAL_HILLSLOPE_GRADIENT * length * meanHeightShare(beta) +
                rate * (channelPerRate + years) - targetMetres
            val slope = length * length * meanHeightShareSlope(beta) / HILLSLOPE_DIFFUSIVITY_M2_PER_YEAR +
                channelPerRate + years
            val step = residual / slope
            rate -= step
            if (abs(step) <= SOLVER_RELATIVE_STEP * rate) return rate
        }
        return rate
    }

    /**
     * The residual of [channelRateMetresPerYear]'s equation at [rateMetresPerYear], in metres, for
     * the guards that hold the solve to its own equation.
     */
    fun channelResidualMetres(
        rateMetresPerYear: Double,
        targetMetres: Double,
        hillslopeLengthMetres: Double,
        network: Double,
        erodibilityPerYear: Double,
        years: Double
    ): Double = steadyReliefMetres(rateMetresPerYear, hillslopeLengthMetres, network, erodibilityPerYear) +
        rateMetresPerYear * years - targetMetres

    /**
     * The cumulative gradient of a steady Roering hillslope, `C(xi) = integral_0^xi u(beta t) dt`,
     * over `S_c L`: `G(2 beta xi) / (2 beta)` with
     * `G(x) = sqrt(1 + x^2) - ln((1 + sqrt(1 + x^2)) / 2) - 1`. The height of the point `xi` of the
     * way from the divide above the foot is `S_c L (C(1) - C(xi))`.
     */
    private fun cumulativeGradientShare(beta: Double, share: Double): Double {
        val x = 2.0 * beta * share
        val g = if (x < 2.0 * SERIES_BELOW_BETA) {
            // G(x) = 2 integral_0^(x/2) u(s) ds = 2 sum (-1)^k C_k (x/2)^(2k + 2) / (2k + 2).
            val half = 0.5 * x
            val squared = half * half
            var power = squared
            var sum = 0.0
            for (k in 0 until SERIES_TERMS) {
                val term = CATALAN[k] * power / (k + 1)
                sum += if (k % 2 == 0) term else -term
                power *= squared
            }
            sum
        } else {
            val root = sqrt(1.0 + x * x)
            root - ln(0.5 * (1.0 + root)) - 1.0
        }
        return if (beta > 0.0) g / (2.0 * beta) else 0.0
    }

    /**
     * `I(x) = integral_0^x G(y) dy`, which is
     * `x sqrt(1 + x^2) / 2 - asinh(x) / 2 - x ln((1 + sqrt(1 + x^2)) / 2)`, read off its series
     * `4 sum (-1)^k C_k (x/2)^(2k + 3) / ((2k + 2)(2k + 3))` where the closed form cancels.
     */
    private fun integratedCumulative(x: Double): Double {
        if (x < 2.0 * SERIES_BELOW_BETA) {
            val half = 0.5 * x
            val squared = half * half
            var power = squared * half
            var sum = 0.0
            for (k in 0 until SERIES_TERMS) {
                val term = CATALAN[k] * power / ((2 * k + 2) * (2 * k + 3))
                sum += if (k % 2 == 0) term else -term
                power *= squared
            }
            return 4.0 * sum
        }
        val root = sqrt(1.0 + x * x)
        return 0.5 * x * root - 0.5 * asinh(x) - x * ln(0.5 * (1.0 + root))
    }

    /**
     * The mean height above its foot, in metres, of the stretch of a steady Roering hillslope
     * [slopeLengthMetres] long, eroding at [rateMetresPerYear], that lies between [fromShare] and
     * [toShare] of the way down from the divide: the height of a cell below every channel head,
     * whose ground is a stretch of the hillslope that runs from its divides to the first channel
     * downstream. In closed form: the height at `xi` is `S_c L (C(1) - C(xi))`, and the mean of `C`
     * over the stretch is `(I(2 beta b) - I(2 beta a)) / ((2 beta)^2 (b - a))`.
     */
    fun stretchMeanHeightMetres(
        rateMetresPerYear: Double,
        slopeLengthMetres: Double,
        fromShare: Double,
        toShare: Double
    ): Double {
        val beta = rateMetresPerYear * slopeLengthMetres /
            (HILLSLOPE_DIFFUSIVITY_M2_PER_YEAR * CRITICAL_HILLSLOPE_GRADIENT)
        if (beta <= 0.0 || toShare <= fromShare) return 0.0
        val top = cumulativeGradientShare(beta, 1.0)
        val twiceBeta = 2.0 * beta
        val meanBelow = (integratedCumulative(twiceBeta * toShare) - integratedCumulative(twiceBeta * fromShare)) /
            (twiceBeta * twiceBeta * (toShare - fromShare))
        return CRITICAL_HILLSLOPE_GRADIENT * slopeLengthMetres * (top - meanBelow)
    }

    /**
     * The rate a cell below every channel head lowers at over a round of [years], in metres a
     * year: the root of `H(E) + E * years = targetMetres`, `H` the stretch's mean height
     * ([stretchMeanHeightMetres]) and [targetMetres] the cell's ground above the foot it drains to,
     * that foot as this round left it.
     *
     * Newton's method again, from the linear-creep guess, which lies at or below the root for the
     * reason [channelRateMetresPerYear] gives (the gradient is at most its linear value, so the
     * height is too); the derivative is a centred difference, these cells being few.
     */
    fun stretchRateMetresPerYear(
        targetMetres: Double,
        slopeLengthMetres: Double,
        fromShare: Double,
        toShare: Double,
        years: Double
    ): Double {
        if (targetMetres <= 0.0 || slopeLengthMetres <= 0.0) return 0.0
        val meanSquare = (toShare * toShare * toShare - fromShare * fromShare * fromShare) /
            (3.0 * (toShare - fromShare))
        val linearPerRate = slopeLengthMetres * slopeLengthMetres * (1.0 - meanSquare) /
            (2.0 * HILLSLOPE_DIFFUSIVITY_M2_PER_YEAR)
        var rate = targetMetres / (linearPerRate + years)
        repeat(MAX_SOLVER_STEPS) {
            val residual = stretchMeanHeightMetres(rate, slopeLengthMetres, fromShare, toShare) +
                rate * years - targetMetres
            val nudge = rate * DIFFERENCE_STEP
            val slope = (stretchMeanHeightMetres(rate + nudge, slopeLengthMetres, fromShare, toShare) -
                stretchMeanHeightMetres(rate - nudge, slopeLengthMetres, fromShare, toShare)) / (2.0 * nudge) + years
            val step = residual / slope
            rate -= step
            if (abs(step) <= DIFFERENCE_STEP * rate) return rate
        }
        return rate
    }

    /**
     * The geometric support area of a channel head, in square metres: the hollow a slope must
     * gather before the water running off it cuts a channel, from Montgomery and Dietrich's
     * criterion `A S^1.65 >= threshold` ([ChannelInitiation]), with [thresholdSquareMetres] that
     * criterion's constant times the cover's factor over the cell's runoff weight against Earth's
     * mean (so the area is ground, and the runoff is already in the threshold).
     *
     * **The gradient is the head's own, reconstructed, and never a cell-to-cell slope alone.** A
     * channel head stands on a hillslope, and a hillslope eroding at `E` stands at a gradient set by
     * its rate and its length: the steady Roering gradient at the foot of a slope `sqrt(A)` long,
     * `S_c u(E sqrt(A) / (D S_c))`. A slope read between cell centres is the regional tilt at the
     * grid's own length, gentler on a coarser grid wherever the ground is rough, which would drift
     * the head's area with the cell; the reconstructed gradient carries no length but the head's
     * own. Where the ground has not begun to erode, as on a stamped plain in the first round, the
     * regional gradient [resolvedGradient] is all the slope there is, so the head stands on the
     * steeper of the two, and the area is the smaller of the two roots: `A S(A)^1.65` rises with
     * `A` on each, so the steeper slope reaches the threshold first. Infinite where neither has any
     * slope: no head forms on ground that neither falls nor wears.
     */
    fun headSupportAreaSquareMetres(
        thresholdSquareMetres: Double,
        resolvedGradient: Double,
        rateMetresPerYear: Double
    ): Double {
        val onRegionalSlope =
            if (resolvedGradient > 0.0) thresholdSquareMetres / resolvedGradient.pow(HEAD_GRADIENT_EXPONENT)
            else Double.POSITIVE_INFINITY
        if (rateMetresPerYear <= 0.0) return onRegionalSlope
        // In beta = E L / (D S_c), with A = L^2, the criterion reads
        // beta^2 u(beta)^1.65 = threshold E^2 / (D^2 S_c^3.65), whose left side rises from zero
        // as beta^3.65 to beta^2. Solved in ln(beta) by Newton: the left side's log has the slope
        // 2 + 1.65 / sqrt(1 + 4 beta^2), which falls with beta, so the log is concave and the
        // linear-creep guess below the root rises to it monotonically.
        val diffusivity = HILLSLOPE_DIFFUSIVITY_M2_PER_YEAR
        val critical = CRITICAL_HILLSLOPE_GRADIENT
        val logTarget = ln(thresholdSquareMetres) + 2.0 * ln(rateMetresPerYear) -
            2.0 * ln(diffusivity) - (2.0 + HEAD_GRADIENT_EXPONENT) * ln(critical)
        var logBeta = logTarget / (2.0 + HEAD_GRADIENT_EXPONENT)
        repeat(MAX_SOLVER_STEPS) {
            val beta = exp(logBeta)
            val root = sqrt(1.0 + 4.0 * beta * beta)
            val residual = 2.0 * logBeta + HEAD_GRADIENT_EXPONENT * ln(gradientShare(beta)) - logTarget
            val step = residual / (2.0 + HEAD_GRADIENT_EXPONENT / root)
            logBeta -= step
            if (abs(step) <= SOLVER_RELATIVE_STEP * 1e3) {
                val length = exp(logBeta) * diffusivity * critical / rateMetresPerYear
                return minOf(onRegionalSlope, length * length)
            }
        }
        val length = exp(logBeta) * diffusivity * critical / rateMetresPerYear
        return minOf(onRegionalSlope, length * length)
    }

    /**
     * The volume of fill, per unit of the cell's area, that raises a cell's bed by [riseOfBed]:
     * the cell's hypsometry is its bed's share [bedShare] at the bed and the rest spread evenly
     * from the bed to twice the interfluves' [reliefAboveBed] above it, whose mean is the ground.
     * Every height is in one unit and the volume comes back in it.
     *
     * The one volume model of the two heights: what fills a cell from the bottom (spoil, a lake, the
     * sea) fills it on this curve, so the ground rises by the volume and the bed by the level the
     * same volume reaches. Past `2 R` the cell is flat and both rise together.
     */
    fun fillForBedRise(riseOfBed: Double, bedShare: Double, reliefAboveBed: Double): Double {
        if (riseOfBed <= 0.0) return 0.0
        if (reliefAboveBed <= 0.0 || bedShare >= 1.0) return riseOfBed
        val brim = 2.0 * reliefAboveBed
        return if (riseOfBed <= brim) {
            bedShare * riseOfBed + (1.0 - bedShare) * riseOfBed * riseOfBed / (4.0 * reliefAboveBed)
        } else {
            reliefAboveBed * (1.0 + bedShare) + (riseOfBed - brim)
        }
    }

    /** The inverse of [fillForBedRise]: how far a fill of [volume] raises the bed. */
    fun bedRiseForFill(volume: Double, bedShare: Double, reliefAboveBed: Double): Double {
        if (volume <= 0.0) return 0.0
        if (reliefAboveBed <= 0.0 || bedShare >= 1.0) return volume
        val brimVolume = reliefAboveBed * (1.0 + bedShare)
        return if (volume <= brimVolume) {
            2.0 * volume / (bedShare + sqrt(bedShare * bedShare + (1.0 - bedShare) * volume / reliefAboveBed))
        } else {
            2.0 * reliefAboveBed + (volume - brimVolume)
        }
    }

    /**
     * The share of a cell the trunk's bed covers: a channel [widthMetres] wide along
     * [channelLengthMetres] of the cell's [cellAreaSquareMetres], at most all of it.
     */
    fun bedShareOf(widthMetres: Double, channelLengthMetres: Double, cellAreaSquareMetres: Double): Double =
        (widthMetres * channelLengthMetres / cellAreaSquareMetres).coerceIn(0.0, 1.0)

    /** A mean-annual channel width, in metres, for a discharge in cubic metres a second. */
    fun channelWidthMetres(dischargeCubicMetresPerSecond: Double): Double =
        CHANNEL_WIDTH_METRES_PER_ROOT_DISCHARGE * sqrt(dischargeCubicMetresPerSecond.coerceAtLeast(0.0))

    private fun asinh(x: Double): Double = ln(x + sqrt(x * x + 1.0))

    private val LN_TWO = ln(2.0)

    /** The relative step of the centred difference in [stretchRateMetresPerYear], and its stopping rule. */
    private const val DIFFERENCE_STEP = 1e-7

}
