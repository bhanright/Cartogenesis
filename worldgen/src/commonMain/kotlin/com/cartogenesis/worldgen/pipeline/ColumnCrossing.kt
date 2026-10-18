package com.cartogenesis.worldgen.pipeline

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.expm1
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * One layer's water budget over one crossing of a cell, its sources and its sinks solved together:
 * the moisture march's per-cell physics ([MoistureMarch]), for the boundary layer and for the free
 * troposphere above it alike.
 *
 * # The budget
 *
 * The layer's vapor `W`, its cloud `C` and its falling hydrometeors `F`, kilograms per square meter
 * (millimeters), over a crossing of [seconds]:
 *
 *     dW/dt = E - S(W) - K + V
 *     dC/dt = L(W) + K - V - c C
 *     dF/dt = c C - f F
 *
 * with `E` what comes in at a steady rate ([sourceKgPerM2PerSecond]): the sea's evaporation at a dry
 * layer, the ground's return, what the other layer mixes in; `S(W)` the layer's sinks, which are
 * four:
 * - the column's rain by Bretherton, Peters and Back's law at the humidity [lawOffsetMm] and the
 *   layer's water make over [lawSaturatedMm], slowed by the marine inversion's lid
 *   ([rainSuppression]): the free troposphere's;
 * - the lifted condensation `L(W)`, the layer's air lifted at [liftMps] and condensing above its own
 *   condensation level ([ColumnWater.boundaryLayerLiftYield]): the boundary layer's;
 * - the layer's air mixed out into the other layer, linear in its water at [exchangePerSecond];
 * - the sea's evaporation withheld as the layer moistens, `k_E min(W, W_sea)` at
 *   [evaporationPerSecond] up to [evaporationCeilingMm], so the bulk formula's `rho C_E U (q_s - q)`
 *   reads the layer's own humidity and stops where the air is as wet as the sea's surface;
 *
 * `K` the condensation at the cap, where the layer holds all it can ([holdableMm]); `V` the cloud
 * descending air evaporates; and `c` and `f` Smith and Barstad's (2004) two delays. Whatever leaves
 * one reservoir enters another, falls, leaves for the other layer or was never evaporated, so the
 * crossing conserves its water by construction, to the doubles' rounding.
 *
 * # Why one solve
 *
 * Charged in turn (the sources first, then the cap, then the lifting, then the rain), a crossing
 * many times the column's own relaxation time puts its whole evaporation in before any sink acts,
 * condenses everything over the cap at once and leaves the column far below the balance its
 * sources and sinks would hold it at (docs/DESIGN_LEDGER.md, W2b).
 *
 * # How it is solved
 *
 * The sinks rise monotonically with `W`, so the layer's balance `S(W_eq) = E` has one root, found
 * by a bracketed Newton's method. The layer then advances toward it in steps, each the exact
 * solution of the budget linearized along its secant from where the layer stands to that balance,
 * `W(t) = W_eq + (W - W_eq) exp(-k t)` with `k = (S(W) - S(W_eq)) / (W - W_eq)`: a step leaves at
 * the true rate, `E - S(W)`, and ends at the true balance however long it is, so the crossing is
 * exact as it goes to nothing and as it grows without bound. Between the two a step misses by the
 * sinks' curvature over the distance it closes, so a crossing long against the layer's relaxation
 * is taken in steps, the first relaxing it by a fifth of an e-folding ([MAX_RELAXATION_PER_STEP])
 * and each after it longer as the distance left shortens (`ColumnCrossingTest`).
 *
 * The cap is a bound inside the solve, not a clip after it: where the sources outrun the sinks at
 * the cap, the layer climbs along its secant to the cap, stays there, and condenses the excess for
 * the rest of the crossing; a layer arriving over the cap condenses the difference as it arrives.
 *
 * What the sinks take is split between their paths at each step's mean, and the cloud's growth,
 * lifted and capped, enters the delays at a steady rate over the crossing, which they solve exactly;
 * a layer that carries no condensate ([condensateFallsAtOnce]) rains its growth where it forms.
 *
 * Reused: one instance per layer's march, its fields set before [solve] and read after it. The
 * arithmetic is per cell and reads nothing of its neighbors, the form a device's kernel takes.
 */
internal class ColumnCrossing {

    // What the crossing reads.

    /** The crossing's length, seconds. */
    var seconds = 0.0

    /** Everything that comes in at a steady rate, kilograms per square meter per second. */
    var sourceKgPerM2PerSecond = 0.0

    /**
     * The saturated column the rain law's humidity divides by, millimeters; zero for a layer the law
     * does not rain ([MoistureMarch.lawHumidity]).
     */
    var lawSaturatedMm = 0.0

    /** The water the law's humidity counts beside the layer's own, millimeters. */
    var lawOffsetMm = 0.0

    /** What the layer holds at most: the cap, millimeters. */
    var holdableMm = 0.0

    /** The marine inversion's lid on the column's rain, 0..1. */
    var rainSuppression = 1.0

    /** The layer's water mixed out into the other layer, per second of it. */
    var exchangePerSecond = 0.0

    /**
     * The sea's evaporation the layer withholds per millimeter of its water, per second
     * (`rho C_E U / M`), up to [evaporationCeilingMm], the water at which the layer's air is as wet as
     * the sea's surface and the bulk formula stops; zero off the open sea.
     */
    var evaporationPerSecond = 0.0
    var evaporationCeilingMm = 0.0

    /**
     * The rise the layer's air is carried up the ground at, meters a second, and the air it is read
     * on: the surface's temperature, the ground's pressure and the layer's depth
     * ([ColumnWater.boundaryLayerLiftYield]). Zero where nothing lifts it.
     */
    var liftMps = 0.0
    var airTemperatureC = 0.0
    var groundPressureKpa = ColumnWater.SEA_LEVEL_PRESSURE_KPA
    var layerDepthM = 0.0

    /**
     * What descending air can take up, kilograms per square meter per second: it evaporates the
     * cloud carried into it.
     */
    var descentKgPerM2PerSecond = 0.0

    /** The cloud's conversion to falling hydrometeors and their fallout, per second. */
    var conversionPerSecond = 0.0
    var falloutPerSecond = 0.0

    /** Whether what condenses here rains here, for a layer that carries no cloud. */
    var condensateFallsAtOnce = false

    // The layer, read on the way in and left on the way out, millimeters.

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

    /** Vapor mixed out into the other layer. */
    var exchanged = 0.0

    /** The sea's evaporation the layer's own humidity withheld from [sourced]. */
    var evaporationWithheld = 0.0

    /** Cloud the descent evaporated, net of what the cap condensed beside it. */
    var cloudEvaporated = 0.0

    /** The crossing's mean vapor, millimeters, for what reads the layer over the whole crossing. */
    var meanVapor = 0.0

    /** The water in at the start and the water out at the end, everything that left included: equal but for rounding. */
    var waterIn = 0.0
    var waterOut = 0.0

    // The vapor's advance over the crossing: where it ends, what its sinks took by each path, what
    // the cap condensed while the layer was held there, and the layer integrated over time.
    private var water = 0.0
    private val pathSunk = DoubleArray(PATHS)
    private var cappedWhileHeld = 0.0
    private var waterTime = 0.0

    // Each path's share of the sinks at the column [shareAt] was last asked about; and, held while a
    // crossing is solved, the sinks by path where it started and their shares at its balance.
    private val pathShare = DoubleArray(PATHS)
    private val startPaths = DoubleArray(PATHS)
    private val balancePaths = DoubleArray(PATHS)

    // The lift's yield per meter and its slope, filled by [ColumnWater.boundaryLayerLiftYield].
    private val yield = DoubleArray(2)

    fun solve() {
        remembering = true
        rememberedCount = 0
        rememberedNext = 0
        dryRainKnown = false
        solveCrossing()
        remembering = false
    }

    private fun solveCrossing() {
        val crossing = seconds
        sourced = sourceKgPerM2PerSecond * crossing
        waterIn = vapor + cloud + falling + sourced
        columnRain = 0.0; condensateRain = 0.0; lifted = 0.0; capped = 0.0
        exchanged = 0.0; evaporationWithheld = 0.0
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

        // A layer arriving over the cap condenses the difference as it arrives.
        var start = vapor.coerceAtLeast(0.0)
        var cappedOnArrival = 0.0
        if (start > holdableMm) {
            cappedOnArrival = start - holdableMm
            start = holdableMm
        }

        if (!advanceShort(start, supply)) advanceInSteps(start, supply)
        meanVapor = (waterTime / crossing).coerceIn(0.0, holdableMm)
        // What the sinks took, the layer's water balance closed on its end: so the crossing
        // conserves whatever the rounding of the steps above.
        val end = water.coerceIn(0.0, holdableMm)
        val sunk = (start + supply * crossing - end - cappedWhileHeld).coerceAtLeast(0.0)
        val pathTotal = pathSunk.sum()
        capped = cappedOnArrival + cappedWhileHeld
        // The descent's evaporation of cloud and the cap's condensation into cloud are the same
        // exchange run both ways; only their difference moves water.
        val cancelled = minOf(cloudEvaporated, capped)
        cloudEvaporated -= cancelled
        capped -= cancelled

        // What the sinks took, by their paths in the shares they took it.
        if (pathTotal > 0.0) {
            columnRain = sunk * pathSunk[RAIN] / pathTotal
            lifted = sunk * pathSunk[LIFT] / pathTotal
            exchanged = sunk * pathSunk[EXCHANGE] / pathTotal
            evaporationWithheld = sunk - columnRain - lifted - exchanged
        } else {
            columnRain = sunk
        }
        vapor = start + supply * crossing - cappedWhileHeld - sunk

        val carriedCloud = cloud - cloudEvaporated
        val grown = lifted + capped
        if (condensateFallsAtOnce) {
            condensateRain = carriedCloud + falling + grown
            cloud = 0.0
            falling = 0.0
        } else if (carriedCloud > 0.0 || falling > 0.0 || grown > 0.0) {
            // The cloud's growth enters the delays at a steady rate over the crossing.
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
        waterOut = vapor + cloud + falling + columnRain + condensateRain + exchanged + evaporationWithheld
    }

    /**
     * The crossing as one exponential Euler step, `W_1 = W_0 + t (1 - e^-kt) / (kt) (E - S(W_0))`
     * with `k = S'(W_0)`, where the sinks' slope at both ends relaxes the layer by no more than
     * [MAX_SHORT_RELAXATION] over it and the layer stays under its cap: most crossings of an
     * ordinary wind. Returns false, having set nothing, where it does not apply.
     */
    private fun advanceShort(start: Double, supply: Double): Boolean {
        evaluateSinks(start)
        val startSinks = sinksNow
        val startSlope = slopeNow
        if (!(startSlope * seconds <= MAX_SHORT_RELAXATION)) return false
        pathNow.copyInto(startPaths)
        val end = start + seconds * relaxedShare(startSlope * seconds) * (supply - startSinks)
        if (end < 0.0 || end > holdableMm) return false
        evaluateSinks(end)
        if (!(slopeNow * seconds <= MAX_SHORT_RELAXATION)) return false
        val sunk = start + supply * seconds - end
        val bothEnds = startSinks + sinksNow
        for (path in 0 until PATHS) {
            pathSunk[path] = if (bothEnds > 0.0) sunk * (startPaths[path] + pathNow[path]) / bothEnds else if (path == RAIN) sunk else 0.0
        }
        water = end
        cappedWhileHeld = 0.0
        waterTime = 0.5 * (start + end) * seconds
        return true
    }

    /** Adds [sunk] to the paths in the shares the sinks take it at [waterMm]. */
    private fun chargePaths(sunk: Double, waterMm: Double) {
        shareAt(waterMm)
        for (path in 0 until PATHS) pathSunk[path] += sunk * pathShare[path]
    }

    /**
     * Advances the layer from [start] over the crossing in steps of its own relaxation, each along
     * the secant from where it stands to its balance under the cap, or to the cap where the sources
     * outrun the sinks there; the first step's length is read off the faster of the first secant and
     * the sinks' slope at the balance ([MAX_RELAXATION_PER_STEP]), and each step after it longer by
     * the root of how far the layer's distance from its balance has fallen.
     */
    private fun advanceInSteps(start: Double, supply: Double) {
        val atCap = sinkRate(holdableMm)
        val heldAtCap = supply >= atCap && holdableMm > 0.0
        val balance = if (heldAtCap) holdableMm else balanceMm(supply, start)
        val firstRate = if (heldAtCap) climbRate(start, atCap) else secantRate(start, balance)
        evaluateSinks(balance)
        val balanceSinks = sinksNow
        for (path in 0 until PATHS) {
            balancePaths[path] = if (balanceSinks > 0.0) pathNow[path] / balanceSinks else if (path == RAIN) 1.0 else 0.0
        }
        val fastestRate = maxOf(firstRate, slopeNow.takeIf { it.isFinite() } ?: firstRate)
        val startDistance = abs(start - balance)
        val firstStep = if (fastestRate > 0.0) MAX_RELAXATION_PER_STEP / fastestRate else seconds
        var step: Double
        var elapsed = 0.0
        water = start
        pathSunk.fill(0.0)
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
                    chargePaths(water + supply * climbTime - climbEnd, climbMean)
                    waterTime += climbMean * climbTime
                    water = climbEnd
                }
                val heldTime = step - climbTime
                if (heldTime > 0.0) {
                    chargePaths(atCap * heldTime, holdableMm)
                    cappedWhileHeld += (supply - atCap) * heldTime
                    waterTime += holdableMm * heldTime
                    water = holdableMm
                }
            } else {
                if (abs(water - balance) <= BALANCE_TOLERANCE * (holdableMm + 1.0)) {
                    // At its balance for the rest of the crossing: the sinks take the supply.
                    val rest = seconds - elapsed
                    for (path in 0 until PATHS) pathSunk[path] += supply * rest * balancePaths[path]
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
                chargePaths(water + supply * step - end, mean)
                waterTime += mean * step
                water = end
            }
            elapsed += step
        }
    }

    /** Every sink of the vapor at a layer of [waterMm], kilograms per square meter per second. */
    fun sinkRate(waterMm: Double): Double {
        evaluateSinks(waterMm)
        return sinksNow
    }

    // The sinks at the layer [evaluateSinks] was last asked about, kilograms per square meter per
    // second, by path and together, and their slope, per second.
    private val pathNow = DoubleArray(PATHS)
    private var sinksNow = 0.0
    private var slopeNow = 0.0

    // While [solve] runs, the sinks it has evaluated are kept, by the layer's water they were
    // evaluated at: the solve asks again at the same few waters (the cap, the start, the balance,
    // and the balance's root search once for the highest and once for the lowest root), and an
    // evaluation costs exponentials. The sinks are a function of the water alone while the fields
    // above hold, and those do not change inside a solve, so the answer is the same bits.
    private var remembering = false
    private var rememberedCount = 0
    private var rememberedNext = 0
    private val rememberedWater = LongArray(REMEMBERED)
    private val rememberedPaths = DoubleArray(REMEMBERED * PATHS)
    private val rememberedSinks = DoubleArray(REMEMBERED)
    private val rememberedSlope = DoubleArray(REMEMBERED)

    // The rain law's value at a dry column, which depends on the law's two fields and not on the water.
    private var dryRainKnown = false
    private var dryRain = 0.0

    /** The sinks, by path, and their slope at [waterMm] in one pass. */
    private fun evaluateSinks(waterMm: Double) {
        if (!remembering) {
            computeSinks(waterMm)
            return
        }
        val bits = waterMm.toRawBits()
        for (entry in 0 until rememberedCount) {
            if (rememberedWater[entry] == bits) {
                for (path in 0 until PATHS) pathNow[path] = rememberedPaths[entry * PATHS + path]
                sinksNow = rememberedSinks[entry]
                slopeNow = rememberedSlope[entry]
                return
            }
        }
        computeSinks(waterMm)
        val entry = rememberedNext
        rememberedWater[entry] = bits
        for (path in 0 until PATHS) rememberedPaths[entry * PATHS + path] = pathNow[path]
        rememberedSinks[entry] = sinksNow
        rememberedSlope[entry] = slopeNow
        rememberedNext = (entry + 1) % REMEMBERED
        if (rememberedCount < REMEMBERED) rememberedCount++
    }

    private fun computeSinks(waterMm: Double) {
        pathNow.fill(0.0)
        slopeNow = 0.0
        if (lawSaturatedMm > 0.0 && rainSuppression > 0.0) {
            val grown = exp(MoistureMarch.COLUMN_RAIN_PER_HUMIDITY * ((waterMm + lawOffsetMm) / lawSaturatedMm - MoistureMarch.COLUMN_RAIN_HUMIDITY_OFFSET))
            val dry = if (remembering && dryRainKnown) dryRain else exp(
                MoistureMarch.COLUMN_RAIN_PER_HUMIDITY * (lawOffsetMm / lawSaturatedMm - MoistureMarch.COLUMN_RAIN_HUMIDITY_OFFSET)
            ).also { if (remembering) { dryRain = it; dryRainKnown = true } }
            if (waterMm > 0.0) pathNow[RAIN] = rainSuppression * (grown - dry) / SECONDS_PER_DAY
            slopeNow += rainSuppression * grown * MoistureMarch.COLUMN_RAIN_PER_HUMIDITY / lawSaturatedMm / SECONDS_PER_DAY
        }
        if (liftMps > 0.0 && waterMm > 0.0) {
            ColumnWater.boundaryLayerLiftYield(
                airTemperatureC, groundPressureKpa, layerDepthM, waterMm / ColumnWater.BOUNDARY_LAYER_MASS_KG_PER_M2, yield
            )
            pathNow[LIFT] = liftMps * yield[0]
            slopeNow += liftMps * yield[1]
        }
        if (exchangePerSecond > 0.0) {
            pathNow[EXCHANGE] = exchangePerSecond * waterMm.coerceAtLeast(0.0)
            slopeNow += exchangePerSecond
        }
        if (evaporationPerSecond > 0.0) {
            pathNow[EVAPORATION] = evaporationPerSecond * waterMm.coerceIn(0.0, evaporationCeilingMm)
            if (waterMm < evaporationCeilingMm) slopeNow += evaporationPerSecond
        }
        sinksNow = pathNow.sum()
    }

    /** Sets [pathShare] to each path's share of the sinks at [waterMm]; all the rain's where nothing sinks. */
    private fun shareAt(waterMm: Double) {
        evaluateSinks(waterMm)
        for (path in 0 until PATHS) {
            pathShare[path] = if (sinksNow > 0.0) pathNow[path] / sinksNow else if (path == RAIN) 1.0 else 0.0
        }
    }

    /** The sinks' secant between [waterMm] and the [balance] under the cap, per second. */
    private fun secantRate(waterMm: Double, balance: Double): Double =
        if (abs(waterMm - balance) > BALANCE_TOLERANCE * (holdableMm + 1.0)) {
            (sinkRate(waterMm) - sinkRate(balance)) / (waterMm - balance)
        } else {
            evaluateSinks(balance)
            slopeNow
        }

    /** The sinks' secant between [waterMm] and the cap, where they take [atCap], per second. */
    private fun climbRate(waterMm: Double, atCap: Double): Double {
        val gap = holdableMm - waterMm
        return if (gap > 0.0) (atCap - sinkRate(waterMm)) / gap else 0.0
    }

    /**
     * The balance under the cap a layer starting at [startMm] relaxes to with [supply] coming in
     * (kg m⁻² s⁻¹). Where the sinks are flat, `S(W) = E` holds over a range and the layer stops at
     * the near end of it: the highest such layer when it starts above them, the lowest when below,
     * and where it is when inside.
     */
    private fun balanceMm(supply: Double, startMm: Double): Double {
        val highest = boundaryMm(supply, highest = true)
        if (startMm >= highest) return highest
        if (sinkRate(startMm) >= supply) return startMm
        return boundaryMm(supply, highest = false)
    }

    /**
     * Where the sinks cross [supply] under the cap: the highest layer whose sinks take no more than
     * it, or the lowest whose sinks take at least it. Newton's method inside a bracket that each
     * step narrows, falling back to bisection where a step would leave it.
     */
    private fun boundaryMm(supply: Double, highest: Boolean): Double {
        var low = 0.0
        var high = holdableMm
        val tolerance = BALANCE_TOLERANCE * (holdableMm + 1.0)
        var water = high
        for (iteration in 0 until NEWTON_ITERATIONS) {
            evaluateSinks(water)
            val excess = sinksNow - supply
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

        /**
         * How many evaluations of the sinks a solve keeps: eight, the few waters a crossing returns
         * to (the cap, the start, the balance) and the root search's path for the balance, which a
         * solve walks twice; a search that runs longer forgets its oldest points, which only costs
         * the evaluation again.
         */
        private const val REMEMBERED = 8

        /** The sinks' paths, as [pathSunk] and [pathShare] index them. */
        private const val RAIN = 0
        private const val LIFT = 1
        private const val EXCHANGE = 2
        private const val EVAPORATION = 3
        private const val PATHS = 4

        /**
         * How far the first of a crossing's steps may relax the layer, in e-foldings of the faster
         * of its secant and the sinks' slope at the balance: a fifth. A secant step from where the
         * layer stands is exact in its first rate and in its end, and misses between them by the
         * sinks' curvature over the step; at a fifth of an e-folding, the later steps lengthening as
         * the distance left shortens, the crossing's end stands within 0.016 of the layer's distance
         * from its balance of a fine march's on the test's cases, and at a quarter within 0.020.
         */
        private const val MAX_RELAXATION_PER_STEP = 0.2

        /**
         * How far a crossing taken as one exponential Euler step may relax the layer, in e-foldings
         * of the sinks' slope at either end: a quarter, where the step's miss is a few thousandths
         * of the layer's distance from its balance.
         */
        private const val MAX_SHORT_RELAXATION = 0.25

        /**
         * The most steps a crossing takes, a ceiling and not a count: steps that lengthen as the
         * distance they leave shortens reach the balance to the doubles' rounding in a few dozen,
         * and the last step takes what is left of the crossing whatever its length.
         */
        private const val MAX_STEPS = 32

        /**
         * Below this rise of the sinks between the layer and the cap, as a share of the layer's net
         * filling, the climb to the cap is taken as straight: the secant's rate is too small to
         * divide by.
         */
        private const val FLAT_SHARE = 1.0e-12

        /** Newton's method's ceiling, and its stopping step as a share of the cap plus a millimeter. */
        private const val NEWTON_ITERATIONS = 100
        private const val BALANCE_TOLERANCE = 1.0e-12

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
