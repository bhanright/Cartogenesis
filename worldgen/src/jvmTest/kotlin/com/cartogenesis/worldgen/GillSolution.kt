package com.cartogenesis.worldgen

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Gill's (1980) analytic solution for heating symmetric about the equator, his section 4, in his
 * nondimensional units: lengths in the equatorial Rossby radius `(c / 2 beta)^(1/2)`, the heating
 * `Q = F(x) exp(-y^2 / 4)` with `F = cos(k x)` for `|x| < L` and zero beyond, `k = pi / (2 L)`, and the
 * same decay rate [epsilon] on momentum and heat. Read in the paper (Q. J. R. Meteorol. Soc. 106,
 * 447-462), equations (2.6) to (2.9), (3.1) to (3.14), (4.1) to (4.8).
 *
 * The response is a Kelvin wave carried east at decay rate `epsilon`,
 * `q0' + epsilon q0 = -F` and zero west of the heating, and the first planetary wave carried west at
 * `3 epsilon`, `q2' - 3 epsilon q2 = F` and zero east of it; then
 *
 * `p = (q0 + q2 (1 + y^2)) e / 2`, `u = (q0 + q2 (y^2 - 3)) e / 2`, `v = (F + 4 epsilon q2) y e`,
 *
 * `e = exp(-y^2 / 4)`, in the signs Gill gives at the surface for positive heating, with the long-wave
 * approximation he makes (`epsilon v` dropped from the northward momentum). The equations are the same
 * at every `x`, so on a planet whose equator is [circumference] Rossby radii round the periodic answer
 * is the sum of the unbounded one's images, which is taken here out to where they no longer matter.
 */
class GillSolution(val epsilon: Double, val halfWidth: Double, val circumference: Double) {

    private val wavenumber = PI / (2 * halfWidth)

    /** Gill's `F(x)` at [x], one image. */
    fun forcingProfile(x: Double): Double = if (abs(x) < halfWidth) cos(wavenumber * x) else 0.0

    /** `x` wrapped onto the planet's circle about the heating, in `[-circumference/2, circumference/2)`. */
    fun wrap(x: Double): Double {
        var wrapped = x - circumference * (x / circumference).roundToInt()
        if (wrapped >= circumference / 2) wrapped -= circumference
        return wrapped
    }

    /** The Kelvin wave's amplitude for one unbounded image, by quadrature of `q0 = -int_{-L}^x exp(-eps (x - s)) F(s) ds`. */
    private fun kelvinOne(x: Double): Double {
        if (x <= -halfWidth) return 0.0
        val upper = minOf(x, halfWidth)
        return -integrate(-halfWidth, upper) { s -> exp(-epsilon * (x - s)) * forcingProfile(s) }
    }

    /** The planetary wave's for one image, `q2 = -int_x^L exp(-3 eps (s - x)) F(s) ds`. */
    private fun rossbyOne(x: Double): Double {
        if (x >= halfWidth) return 0.0
        val lower = maxOf(x, -halfWidth)
        return -integrate(lower, halfWidth) { s -> exp(-3 * epsilon * (s - x)) * forcingProfile(s) }
    }

    private fun images(x: Double, one: (Double) -> Double): Double {
        var sum = 0.0
        for (image in -IMAGES..IMAGES) sum += one(wrap(x) + image * circumference)
        return sum
    }

    /** `q0` at [x], periodic. */
    fun kelvin(x: Double) = images(x, ::kelvinOne)

    /** `q2` at [x], periodic. */
    fun rossby(x: Double) = images(x, ::rossbyOne)

    /** The heating's profile summed over images (one image holds it all when the planet is wider than it). */
    fun forcing(x: Double) = images(x) { forcingProfile(it) }

    fun pressure(x: Double, y: Double): Double {
        val envelope = exp(-y * y / 4)
        return 0.5 * (kelvin(x) + rossby(x) * (1 + y * y)) * envelope
    }

    fun eastward(x: Double, y: Double): Double {
        val envelope = exp(-y * y / 4)
        return 0.5 * (kelvin(x) + rossby(x) * (y * y - 3)) * envelope
    }

    fun northward(x: Double, y: Double): Double = (forcing(x) + 4 * epsilon * rossby(x)) * y * exp(-y * y / 4)

    private fun integrate(from: Double, to: Double, function: (Double) -> Double): Double {
        if (to <= from) return 0.0
        // Simpson's rule; the integrands are smooth on the interval.
        val steps = QUADRATURE_STEPS
        val width = (to - from) / steps
        var sum = function(from) + function(to)
        for (step in 1 until steps) sum += function(from + step * width) * (if (step % 2 == 1) 4 else 2)
        return sum * width / 3
    }

    private companion object {
        /** Images either side: the Kelvin wave's weakest decay round a planet is `exp(-epsilon * circumference)`. */
        const val IMAGES = 3

        /** Simpson intervals for each integral, even; the profile is a single cosine arch. */
        const val QUADRATURE_STEPS = 400
    }
}
