package com.cartogenesis.cartography.geometry

import kotlin.math.ln
import kotlin.math.sqrt

/** What one detector said about one layer of one world. */
internal enum class Outcome {
    /** Measured, and within the bar. */
    CLEAN,

    /** Measured, and past the bar. */
    VIOLATION,

    /** Too little of the layer to measure at the stated power: reported, and never a pass. */
    INSUFFICIENT,

    /** The detector asks nothing of this kind of layer: which way a region's shores face, say. */
    NOT_APPLICABLE
}

/**
 * The few pieces of statistics the detectors share.
 *
 * The census tests many things at once — every layer, every detector, every grid bearing, every
 * world — so a bar set at an ordinary significance would be crossed somewhere by chance on every
 * run. Each test therefore spends a share of one family-wide error rate, [FAMILY_ERROR_RATE],
 * divided evenly among the tests the census makes (Bonferroni, which assumes nothing about how
 * the tests depend on one another).
 */
internal object Statistics {

    /**
     * The chance the whole census may flag something natural, summed over every test in it.
     *
     * One in a hundred: a guard that cried wolf once in a hundred runs of the per-merge tier would
     * be ignored within a month, and one that never could would have no power left.
     */
    const val FAMILY_ERROR_RATE = 0.01

    /** The one-sided normal quantile a single test of a family of [familySize] must pass. */
    fun zFor(familySize: Int): Double = normalQuantile(1.0 - FAMILY_ERROR_RATE / familySize.coerceAtLeast(1))

    /**
     * The standard normal quantile, by Acklam's rational approximation (relative error under
     * 1.2e-9 over the whole range), which is far finer than anything read off it here.
     */
    fun normalQuantile(probability: Double): Double {
        require(probability > 0.0 && probability < 1.0)
        val a = doubleArrayOf(-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
            1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00)
        val b = doubleArrayOf(-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
            6.680131188771972e+01, -1.328068155288572e+01)
        val c = doubleArrayOf(-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
            -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00)
        val d = doubleArrayOf(7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00,
            3.754408661907416e+00)
        val low = 0.02425
        return when {
            probability < low -> {
                val q = sqrt(-2 * ln(probability))
                (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                    ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1)
            }
            probability <= 1 - low -> {
                val q = probability - 0.5
                val r = q * q
                (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q /
                    (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1)
            }
            else -> {
                val q = sqrt(-2 * ln(1 - probability))
                -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                    ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1)
            }
        }
    }

    /** The sample standard deviation. */
    fun standardDeviation(values: DoubleArray): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        var sum = 0.0
        values.forEach { sum += (it - mean) * (it - mean) }
        return sqrt(sum / (values.size - 1))
    }
}
