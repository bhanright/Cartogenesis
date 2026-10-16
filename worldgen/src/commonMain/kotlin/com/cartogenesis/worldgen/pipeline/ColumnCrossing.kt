package com.cartogenesis.worldgen.pipeline

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.expm1
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * One column's water budget over one crossing of a cell, its sources and its sinks solved
 * together: the moisture march's per-cell physics ([MoistureMarch]).
 *
 * # The budget
 *
 * The column's vapor `W`, its cloud `C` and its falling hydrometeors `F`, kilograms per square
 * meter (millimeters), over a crossing of [seconds]:
 *
 *     dW/dt = E - S(W) - K + V
 *     dC/dt = L(W) + K - V - c C
 *     dF/dt = c C - f F
 *
 * with `E` the sea's evaporation or the ground's return ([sourceKgPerM2PerSecond]); `S(W)` the
 * column's sinks, the column's rain at its relative humidity `P(W / W_s)` slowed by the marine
 * inversion's lid ([rainSuppression]) plus the lifted condensation `L(W)`; `K` the condensation at
 * the cap, where the column holds all it can before its surface air saturates ([holdableMm]); `V`
 * the cloud the descending air evaporates; and `c` and `f` Smith and Barstad's (2004) two delays.
 * The rain is the column's own rain and what falls out of `F`; whatever leaves one reservoir
 * enters another or falls, so the crossing conserves its water by construction, to the doubles'
 * rounding.
 *
 * # Why one solve
 *
 * Charged in turn (the sources first, then the cap, then the lifting, then the rain), a crossing
 * many times the column's own relaxation time puts its whole evaporation in before any sink acts,
 * condenses everything over the cap at once and leaves the column far below the balance its
 * sources and sinks would hold it at: a calm tropical cell, crossed in two months, held 25 mm where
 * the same cell charged in steps of an ordinary crossing held 38 (docs/DESIGN_LEDGER.md, W2).
 *
 * # How it is solved
 *
 * The vapor's sinks are monotone and convex in `W` (the rain law is an exponential; the lifting's
 * saturated share rises ever faster toward saturation), so the column's balance `S(W_eq) = E` has
 * one root, found by a bracketed Newton's method. The column then advances toward it in steps,
 * each the exact solution of the budget linearized along its secant from where the column stands
 * to that balance, `W(t) = W_eq + (W - W_eq) exp(-k t)` with `k = (S(W) - S(W_eq)) / (W - W_eq)`:
 * a step leaves at the true rate, `E - S(W)`, and ends at the true balance however long it is, so
 * the crossing is exact as it goes to nothing and as it grows without bound. Between the two a
 * step misses by the sinks' curvature over the distance it closes, so a crossing long against the
 * column's relaxation is taken in steps, the first relaxing it by a quarter of an e-folding
 * ([MAX_RELAXATION_PER_STEP]) and each after it longer as the distance left shortens, each one evaluation of
 * the sinks, since the balance is the same for all of them; the end then stands within two
 * hundredths of the column's distance from its balance of a fine Runge-Kutta march's
 * (`ColumnCrossingTest`). A single backward Euler step over the crossing, by contrast, is exact
 * only at the two ends, and at a crossing near the relaxation time leaves 16% of that distance.
 *
 * The cap is a bound inside the solve, not a clip after it: where the sources outrun the sinks at
 * the cap, the column climbs along its secant to the cap, stays there, and condenses the excess for
 * the rest of the crossing; a column arriving over the cap condenses the difference as it arrives.
 *
 * What the sinks take is split between the column's rain and the lifting at the crossing's mean
 * column, and the cloud's growth, lifted and capped, enters the delays at a steady rate over the
 * crossing, which they solve exactly.
 *
 * Reused: one instance per season's march, its fields set before [solve] and read after it.
 */
internal class ColumnCrossing {

    // What the crossing reads.

    /** The crossing's length, seconds. */
    var seconds = 0.0

    /** The sea's evaporation or the ground's return, kilograms per square meter per second. */
    var sourceKgPerM2PerSecond = 0.0

    /** The saturated column the rain law divides by, millimeters ([ColumnWater.saturatedColumnMm]). */
    var saturatedMm = 0.0

    /** What the column holds when its surface air saturates: the cap, millimeters. */
    var holdableMm = 0.0

    /** The marine inversion's lid on the column's rain, 0..1. */
    var rainSuppression = 1.0

    /**
     * Smith and Barstad's `C_w w` for the column's lifting, kilograms per square meter per second:
     * the condensation of saturated air at the rise the march charges; zero where nothing lifts it.
     */
    var liftingKgPerM2PerSecond = 0.0

    /**
     * The humidity, the column's water over [holdableMm], over which lifted air condenses, in the
     * column's saturated share alone ([saturatedShare]); zero condenses on the whole column at its
     * humidity, `L = C_w w W / W_hold`.
     */
    var criticalHumidity = 0.0

    /**
     * What descending air can take up, `C_w |w|`, kilograms per square meter per second: it
     * evaporates the cloud carried into it. Not the column's own rain, which the rain law already
     * gives as what reaches the ground.
     */
    var descentKgPerM2PerSecond = 0.0

    /** The cloud's conversion to falling hydrometeors and their fallout, per second. */
    var conversionPerSecond = 0.0
    var falloutPerSecond = 0.0

    // The column, read on the way in and left on the way out, millimeters.

    var vapor = 0.0
    var cloud = 0.0
    var falling = 0.0

    // What the crossing did, millimeters of column water.

    /** What the sources put in. */
    var sourced = 0.0

    /** The column's own rain, by its rain law. */
    var columnRain = 0.0

    /** The cloud and falling water's rain. */
    var condensateRain = 0.0

    /** Vapor lifted into cloud, and condensed at the cap. */
    var lifted = 0.0
    var capped = 0.0

    /** Cloud the descent evaporated, net of what the cap condensed beside it. */
    var cloudEvaporated = 0.0

    /** The crossing's mean column, millimeters, for what reads the column over the whole crossing. */
    var meanVapor = 0.0

    /** The water in at the start and the water out at the end, rain included: equal but for rounding. */
    var waterIn = 0.0
    var waterOut = 0.0

    // The vapor's advance over the crossing: where it ends, what its sinks took by each path,
    // what the cap condensed while the column was held there, and the column integrated over time.
    private var water = 0.0
    private var rainSunk = 0.0
    private var liftSunk = 0.0
    private var cappedWhileHeld = 0.0
    private var waterTime = 0.0

    fun solve() {
        val crossing = seconds
        sourced = sourceKgPerM2PerSecond * crossing
        waterIn = vapor + cloud + falling + sourced
        columnRain = 0.0; condensateRain = 0.0; lifted = 0.0; capped = 0.0
        cloudEvaporated = 0.0
        if (crossing <= 0.0) {
            meanVapor = vapor
            waterOut = waterIn
            return
        }

        // The descent evaporates the carried cloud at its capacity while there is cloud, against the
        // cloud's own conversion: `dC/dt = -c C - D` runs dry at `ln(1 + c C / D) / c`.
        val descent = descentKgPerM2PerSecond
        if (descent > 0.0 && cloud > 0.0) {
            val dryAfter = if (conversionPerSecond > 0.0) {
                ln(1.0 + conversionPerSecond * cloud / descent) / conversionPerSecond
            } else cloud / descent
            cloudEvaporated = minOf(cloud, descent * minOf(dryAfter, crossing))
        }
        val supply = sourceKgPerM2PerSecond + cloudEvaporated / crossing

        // A column arriving over the cap condenses the difference as it arrives.
        var start = vapor.coerceAtLeast(0.0)
        var cappedOnArrival = 0.0
        if (start > holdableMm) {
            cappedOnArrival = start - holdableMm
            start = holdableMm
        }

        if (!advanceShort(start, supply)) advanceInSteps(start, supply)
        meanVapor = (waterTime / crossing).coerceIn(0.0, holdableMm)
        // What the sinks took, the column's water balance closed on its end: so the crossing
        // conserves whatever the rounding of the steps above.
        val end = water.coerceIn(0.0, holdableMm)
        val sunk = (start + supply * crossing - end - cappedWhileHeld).coerceAtLeast(0.0)
        val rainShare = if (rainSunk + liftSunk > 0.0) (rainSunk / (rainSunk + liftSunk)).coerceIn(0.0, 1.0) else 1.0
        capped = cappedOnArrival + cappedWhileHeld
        // The descent's evaporation of cloud and the cap's condensation into cloud are the same
        // exchange run both ways; only their difference moves water.
        val cancelled = minOf(cloudEvaporated, capped)
        cloudEvaporated -= cancelled
        capped -= cancelled

        // What the sinks took: the column's rain and the lifting into cloud.
        columnRain = sunk * rainShare
        lifted = sunk - columnRain
        vapor = start + supply * crossing - cappedWhileHeld - sunk

        // The cloud's growth enters the delays at a steady rate over the crossing; a column with
        // no cloud, no falling water and none grown has nothing for them to do.
        val carriedCloud = cloud - cloudEvaporated
        val grown = lifted + capped
        if (carriedCloud > 0.0 || falling > 0.0 || grown > 0.0) {
            val growthPerSecond = grown / crossing
            val conversionLeft = exp(-conversionPerSecond * crossing)
            val falloutLeft = exp(-falloutPerSecond * crossing)
            val chain = chainIntegral(conversionPerSecond, falloutPerSecond, crossing, conversionLeft, falloutLeft)
            val cloudEnd = carriedCloud * conversionLeft + growthPerSecond * crossing * relaxedShare(conversionPerSecond * crossing, conversionLeft)
            val fromGrowth = growthPerSecond * (crossing * relaxedShare(falloutPerSecond * crossing, falloutLeft) - chain)
            val fallingEnd = falling * falloutLeft + carriedCloud * conversionPerSecond * chain + fromGrowth.coerceAtLeast(0.0)
            condensateRain = (carriedCloud + falling + grown - cloudEnd - fallingEnd).coerceAtLeast(0.0)
            cloud = cloudEnd
            falling = fallingEnd
        } else {
            condensateRain = 0.0
            cloud = 0.0
            falling = 0.0
        }
        waterOut = vapor + cloud + falling + columnRain + condensateRain
    }

    /**
     * The crossing as one exponential Euler step, `W_1 = W_0 + t (1 - e^-kt) / (kt) (E - S(W_0))`
     * with `k = S'(W_0)`, where the sinks' slope at both ends relaxes the column by no more than
     * [MAX_SHORT_RELAXATION] over it and the column stays under its cap: most crossings of an
     * ordinary wind, which the stepped solve would take in one secant step to the same answer at
     * the cost of the balance's root. Returns false, having set nothing, where it does not apply.
     */
    private fun advanceShort(start: Double, supply: Double): Boolean {
        evaluateSinks(start)
        val startRain = rainNow
        val startSinks = rainNow + liftNow
        val startSlope = slopeNow
        if (!(startSlope * seconds <= MAX_SHORT_RELAXATION)) return false
        val end = start + seconds * relaxedShare(startSlope * seconds) * (supply - startSinks)
        if (end < 0.0 || end > holdableMm) return false
        evaluateSinks(end)
        if (!(slopeNow * seconds <= MAX_SHORT_RELAXATION)) return false
        val endSinks = rainNow + liftNow
        val sunk = start + supply * seconds - end
        val rainShare = if (startSinks + endSinks > 0.0) (startRain + rainNow) / (startSinks + endSinks) else 1.0
        water = end
        rainSunk = sunk * rainShare
        liftSunk = sunk - rainSunk
        cappedWhileHeld = 0.0
        waterTime = 0.5 * (start + end) * seconds
        return true
    }

    /**
     * Advances the column from [start] over the crossing in steps of its own relaxation, each along
     * the secant from where it stands to its balance under the cap, or to the cap where the sources
     * outrun the sinks there; the first step's length is read off the faster of the first secant
     * and the sinks' slope at the balance, where a convex sink relaxes fastest
     * ([MAX_RELAXATION_PER_STEP]), and each step after it longer by the root of how far the
     * column's distance from its balance has fallen.
     */
    private fun advanceInSteps(start: Double, supply: Double) {
        val atCap = sinkRate(holdableMm)
        val heldAtCap = supply >= atCap && holdableMm > 0.0
        val balance = if (heldAtCap) holdableMm else balanceMm(supply, start)
        val firstRate = if (heldAtCap) climbRate(start, atCap) else secantRate(start, balance)
        evaluateSinks(balance)
        val balanceSinks = rainNow + liftNow
        val balanceRainShare = if (balanceSinks > 0.0) rainNow / balanceSinks else 1.0
        val fastestRate = maxOf(firstRate, slopeNow.takeIf { it.isFinite() } ?: firstRate)
        // Each step relaxes the column by [MAX_RELAXATION_PER_STEP] at the fastest rate, lengthened
        // by the root of how far its distance from its balance has fallen since the start: a secant
        // step misses by the sinks' curvature over the distance it closes, so a column nearer its
        // balance can take a longer step for the same miss. In proportion to the distance itself
        // the steps outran the curvature on columns arriving far below their balance.
        val startDistance = abs(start - balance)
        val firstStep = if (fastestRate > 0.0) MAX_RELAXATION_PER_STEP / fastestRate else seconds
        var step: Double
        var elapsed = 0.0
        water = start
        rainSunk = 0.0
        liftSunk = 0.0
        cappedWhileHeld = 0.0
        waterTime = 0.0
        for (index in 0 until MAX_STEPS) {
            if (elapsed >= seconds) break
            val distance = abs(water - balance)
            step = when {
                index == MAX_STEPS - 1 || distance <= 0.0 -> seconds - elapsed
                else -> minOf(firstStep * sqrt(startDistance / distance), seconds - elapsed)
            }
            if (heldAtCap) {
                val fromStart = sinkRate(water)
                val gap = holdableMm - water
                val rate = climbRate(water, atCap)
                val untilCap = when {
                    gap <= 0.0 -> 0.0
                    rate * gap <= FLAT_SHARE * (supply - fromStart) -> gap / (supply - fromStart)
                    else -> ln((supply - fromStart) / (supply - atCap)) / rate
                }
                val climbTime = minOf(untilCap, step)
                if (climbTime > 0.0) {
                    val climbEnd: Double
                    val climbMean: Double
                    if (rate * climbTime > 0.0) {
                        val beyond = water + (supply - fromStart) / rate
                        val relaxed = -expm1(-rate * climbTime)
                        climbEnd = if (untilCap <= step) holdableMm else beyond - (beyond - water) * (1.0 - relaxed)
                        climbMean = beyond - (beyond - water) * relaxed / (rate * climbTime)
                    } else {
                        climbEnd = if (untilCap <= step) holdableMm else water + (supply - fromStart) * climbTime
                        climbMean = 0.5 * (water + climbEnd)
                    }
                    val sunkHere = water + supply * climbTime - climbEnd
                    val share = rainShareAt(climbMean)
                    rainSunk += sunkHere * share
                    liftSunk += sunkHere * (1.0 - share)
                    waterTime += climbMean * climbTime
                    water = climbEnd
                }
                val heldTime = step - climbTime
                if (heldTime > 0.0) {
                    val share = rainShareAt(holdableMm)
                    rainSunk += atCap * heldTime * share
                    liftSunk += atCap * heldTime * (1.0 - share)
                    cappedWhileHeld += (supply - atCap) * heldTime
                    waterTime += holdableMm * heldTime
                    water = holdableMm
                }
            } else {
                if (abs(water - balance) <= BALANCE_TOLERANCE * (holdableMm + 1.0)) {
                    // At its balance for the rest of the crossing: the sinks take the supply.
                    val rest = seconds - elapsed
                    rainSunk += supply * rest * balanceRainShare
                    liftSunk += supply * rest * (1.0 - balanceRainShare)
                    waterTime += balance * rest
                    water = balance
                    break
                }
                val rate = (sinkRate(water) - balanceSinks) / (water - balance)
                val end: Double
                val mean: Double
                if (rate * step > 0.0) {
                    val relaxed = -expm1(-rate * step)
                    end = balance + (water - balance) * (1.0 - relaxed)
                    mean = balance + (water - balance) * relaxed / (rate * step)
                } else {
                    end = water
                    mean = water
                }
                val sunkHere = water + supply * step - end
                val share = rainShareAt(mean)
                rainSunk += sunkHere * share
                liftSunk += sunkHere * (1.0 - share)
                waterTime += mean * step
                water = end
            }
            elapsed += step
        }
    }

    /** Every sink of the vapor at a column of [waterMm], kilograms per square meter per second. */
    fun sinkRate(waterMm: Double): Double = columnRainRate(waterMm) + liftingRate(waterMm)

    // The sinks at the column [evaluateSinks] was last asked about: the column's rain and the
    // lifting, kilograms per square meter per second, and their slope, per second.
    private var rainNow = 0.0
    private var liftNow = 0.0
    private var slopeNow = 0.0

    /**
     * The sinks and their slope at [waterMm] in one pass, one exponential for the rain law and one
     * root for the lifting's saturated share, where [sinkRate] and [sinkSlope] each take their own.
     */
    private fun evaluateSinks(waterMm: Double) {
        rainNow = 0.0
        liftNow = 0.0
        slopeNow = 0.0
        if (saturatedMm > 0.0 && rainSuppression > 0.0) {
            val grown = exp(MoistureMarch.COLUMN_RAIN_PER_HUMIDITY * (waterMm / saturatedMm - MoistureMarch.COLUMN_RAIN_HUMIDITY_OFFSET))
            if (waterMm > 0.0) rainNow = rainSuppression * (grown - DRY_COLUMN_RAIN) / SECONDS_PER_DAY
            slopeNow += rainSuppression * grown * MoistureMarch.COLUMN_RAIN_PER_HUMIDITY / saturatedMm / SECONDS_PER_DAY
        }
        if (liftingKgPerM2PerSecond > 0.0 && holdableMm > 0.0) {
            val humidity = waterMm / holdableMm
            if (waterMm > 0.0) liftNow = liftingKgPerM2PerSecond * saturatedShare(humidity, criticalHumidity)
            slopeNow += liftingKgPerM2PerSecond * saturatedShareSlope(humidity, criticalHumidity) / holdableMm
        }
    }

    /** The sinks' secant between [waterMm] and the [balance] under the cap, per second. */
    private fun secantRate(waterMm: Double, balance: Double): Double =
        if (abs(waterMm - balance) > BALANCE_TOLERANCE * (holdableMm + 1.0)) {
            (sinkRate(waterMm) - sinkRate(balance)) / (waterMm - balance)
        } else sinkSlope(balance)

    /** The sinks' secant between [waterMm] and the cap, where they take [atCap], per second. */
    private fun climbRate(waterMm: Double, atCap: Double): Double {
        val gap = holdableMm - waterMm
        return if (gap > 0.0) (atCap - sinkRate(waterMm)) / gap else 0.0
    }

    /** The column's rain at [waterMm] under the lid, kilograms per square meter per second. */
    private fun columnRainRate(waterMm: Double): Double {
        if (saturatedMm <= 0.0 || rainSuppression <= 0.0 || waterMm <= 0.0) return 0.0
        return rainSuppression * MoistureMarch.columnRainMmPerDay(waterMm / saturatedMm) / SECONDS_PER_DAY
    }

    private fun liftingRate(waterMm: Double): Double {
        if (liftingKgPerM2PerSecond <= 0.0 || holdableMm <= 0.0 || waterMm <= 0.0) return 0.0
        return liftingKgPerM2PerSecond * saturatedShare(waterMm / holdableMm, criticalHumidity)
    }

    /** The sinks' slope at [waterMm], per second, for Newton's method. */
    private fun sinkSlope(waterMm: Double): Double {
        var slope = 0.0
        if (saturatedMm > 0.0 && rainSuppression > 0.0) {
            slope += rainSuppression * exp(MoistureMarch.COLUMN_RAIN_PER_HUMIDITY * (waterMm / saturatedMm - MoistureMarch.COLUMN_RAIN_HUMIDITY_OFFSET)) *
                MoistureMarch.COLUMN_RAIN_PER_HUMIDITY / saturatedMm / SECONDS_PER_DAY
        }
        if (liftingKgPerM2PerSecond > 0.0 && holdableMm > 0.0) {
            slope += liftingKgPerM2PerSecond * saturatedShareSlope(waterMm / holdableMm, criticalHumidity) / holdableMm
        }
        return slope
    }

    /** The column's rain's share of the sinks at [waterMm]; all of it where nothing sinks. */
    private fun rainShareAt(waterMm: Double): Double {
        evaluateSinks(waterMm)
        val total = rainNow + liftNow
        return if (total > 0.0) rainNow / total else 1.0
    }

    /**
     * The balance under the cap a column starting at [startMm] relaxes to with [supply] coming in
     * (kg m⁻² s⁻¹). Where the sinks are flat, `S(W) = E` holds over a range and the column stops at
     * the near end of it: the highest such column when it starts above them, the lowest when below,
     * and where it is when inside. The column's rain rises everywhere, so the range is one column
     * but for the doubles' rounding.
     */
    private fun balanceMm(supply: Double, startMm: Double): Double {
        val highest = boundaryMm(supply, highest = true)
        if (startMm >= highest) return highest
        if (sinkRate(startMm) >= supply) return startMm
        return boundaryMm(supply, highest = false)
    }

    /**
     * Where the sinks cross [supply] under the cap: the highest column whose sinks take no more
     * than it, or the lowest whose sinks take at least it. Newton's method inside a bracket that
     * each step narrows, falling back to bisection where a step would leave it.
     */
    private fun boundaryMm(supply: Double, highest: Boolean): Double {
        var low = 0.0
        var high = holdableMm
        val tolerance = BALANCE_TOLERANCE * (holdableMm + 1.0)
        var water = high
        for (iteration in 0 until NEWTON_ITERATIONS) {
            evaluateSinks(water)
            val excess = rainNow + liftNow - supply
            val above = if (highest) excess > 0.0 else excess >= 0.0
            if (above) high = water else low = water
            if (high - low <= tolerance) break
            val slope = slopeNow
            var next = if (slope > 0.0 && slope.isFinite()) water - excess / slope else Double.NaN
            if (!(next > low && next < high)) next = 0.5 * (low + high)
            if (abs(next - water) <= tolerance) return next
            water = next
        }
        return if (highest) low else high
    }

    companion object {
        private const val SECONDS_PER_DAY = 86_400.0

        /** The rain law's value at a dry column, which [MoistureMarch.columnRainMmPerDay] subtracts. */
        private val DRY_COLUMN_RAIN = exp(-MoistureMarch.COLUMN_RAIN_PER_HUMIDITY * MoistureMarch.COLUMN_RAIN_HUMIDITY_OFFSET)

        /**
         * How far the first of a crossing's steps may relax the column, in e-foldings of the faster
         * of its secant and the sinks' slope at the balance: a fifth. A secant step from where the
         * column stands is exact in its first rate and in its end, and misses between them by the
         * sinks' curvature over the step; at a fifth of an e-folding, the later steps lengthening as
         * the distance left shortens, the crossing's end stands within 0.016 of the column's distance
         * from its balance of a fine march's on the test's cases, and at a quarter within 0.020.
         */
        private const val MAX_RELAXATION_PER_STEP = 0.2

        /**
         * How far a crossing taken as one exponential Euler step may relax the column, in
         * e-foldings of the sinks' slope at either end: a quarter, where the step's miss is a few
         * thousandths of the column's distance from its balance, and an ordinary crossing of the
         * 512-row grid, a tenth of an e-folding or less, takes it.
         */
        private const val MAX_SHORT_RELAXATION = 0.25


        /**
         * The most steps a crossing takes, a ceiling and not a count: steps that lengthen as the
         * distance they leave shortens reach the balance to the doubles' rounding in a few dozen,
         * and the last step takes what is left of the crossing whatever its length.
         */
        private const val MAX_STEPS = 32

        /**
         * Below this rise of the sinks between the column and the cap, as a share of the column's
         * net filling, the climb to the cap is taken as straight: the secant's rate is too small to
         * divide by.
         */
        private const val FLAT_SHARE = 1.0e-12

        /** Newton's method's ceiling, and its stopping step as a share of the cap plus a millimeter. */
        private const val NEWTON_ITERATIONS = 100
        private const val BALANCE_TOLERANCE = 1.0e-12

        /**
         * Sundqvist, Berge and Kristjansson's (1989) saturated share of a column at humidity
         * [humidity] over the critical [criticalHumidity]: `b = 1 - sqrt((1 - f) / (1 - f_c))`, none
         * at or under `f_c` and all of it at saturation (Grundner and others 2024, equation 3, with
         * the saturation humidity at one). With `f_c` zero it is the linear share `f` the march's
         * lifting read before ([MoistureMarch]).
         */
        fun saturatedShare(humidity: Double, criticalHumidity: Double): Double {
            if (criticalHumidity <= 0.0) return humidity.coerceIn(0.0, 1.0)
            if (humidity <= criticalHumidity) return 0.0
            if (humidity >= 1.0) return 1.0
            return 1.0 - sqrt((1.0 - humidity) / (1.0 - criticalHumidity))
        }

        private fun saturatedShareSlope(humidity: Double, criticalHumidity: Double): Double {
            if (criticalHumidity <= 0.0) return if (humidity < 1.0) 1.0 else 0.0
            if (humidity <= criticalHumidity) return 0.0
            val dry = (1.0 - humidity).coerceAtLeast(MIN_DRY_SHARE)
            return 0.5 / sqrt(dry * (1.0 - criticalHumidity))
        }

        /** The least unsaturated share the slope is taken at, where it grows without bound. */
        private const val MIN_DRY_SHARE = 1.0e-12

        /** `(1 - e^-x) / x`, one at nothing. */
        private fun relaxedShare(x: Double): Double = if (x > 1.0e-9) -expm1(-x) / x else 1.0 - 0.5 * x

        /**
         * The same with `e^-x` in hand as [left]: read off it where `1 - e^-x` keeps its digits,
         * from `expm1` where `x` is small enough to lose them.
         */
        private fun relaxedShare(x: Double, left: Double): Double = when {
            x > SMALL_RELAXATION -> (1.0 - left) / x
            else -> relaxedShare(x)
        }

        /** Under this exponent `1 - e^-x` is taken by `expm1`, where subtracting would cost digits. */
        private const val SMALL_RELAXATION = 0.1

        /**
         * `int_0^t e^-f(t-s) e^-cs ds`, what the second of two delays in series holds per unit put
         * through the first: `(e^-ct - e^-ft) / (f - c)`, and `t e^-ct` where the rates meet, with
         * `e^-ct` and `e^-ft` in hand as [conversionLeft] and [falloutLeft]. Exact over any crossing,
         * so the delays filter the same on every grid.
         */
        private fun chainIntegral(
            conversionPerSecond: Double,
            falloutPerSecond: Double,
            seconds: Double,
            conversionLeft: Double,
            falloutLeft: Double
        ): Double {
            val difference = falloutPerSecond - conversionPerSecond
            return if (abs(difference) * seconds < EQUAL_RATES) {
                seconds * conversionLeft
            } else {
                (conversionLeft - falloutLeft) / difference
            }
        }

        /** Below this product of the rates' difference and the time, the two are taken as equal. */
        private const val EQUAL_RATES = 1.0e-6
    }
}
