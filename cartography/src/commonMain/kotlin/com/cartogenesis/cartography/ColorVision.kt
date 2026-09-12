package com.cartogenesis.cartography

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The instrument the palettes are measured with: colour difference, and colour vision.
 *
 * F6 asks two questions that cannot be answered by looking — whether two colours are still *told
 * apart* by a reader with dichromatic vision, and whether text is legible against the ground it is
 * printed on — and both have standard answers with numbers attached. This is those answers, and it
 * lives beside the palettes rather than inside one test because two modules ask them: the map
 * style's guard (`ClearStyleTest`, in this module) and the chrome's (`ChromeContrastTest`, in
 * `:ui`). Two implementations of CIEDE2000 would be two implementations free to disagree, and the
 * whole point of a stated margin is that the two guards state the same one.
 *
 * Nothing in the application draws with this. It is a ruler.
 */
object ColorVision {

    /**
     * A form of colour blindness, as a matrix over linear light.
     *
     * From Machado, Oliveira and Fernandes, *A Physiologically-based Model for Simulation of Color
     * Vision Deficiency* (IEEE TVCG 15(6), 2009), table 1, at severity 1.0 — the full dichromacy,
     * which is the case worth designing against. Their model is defined on **linear** RGB, so
     * [simulate] decodes the sRGB transfer function, applies the matrix and encodes it again;
     * applying one of these straight to gamma-encoded bytes, which is a common shortcut, produces
     * visibly wrong colours and would make every margin below a fiction.
     */
    enum class Deficiency(internal val matrix: DoubleArray) {
        /** Red-blind: roughly 1% of men. */
        PROTANOPIA(
            doubleArrayOf(
                0.152286, 1.052583, -0.204868,
                0.114503, 0.786281, 0.099216,
                -0.003882, -0.048116, 1.051998
            )
        ),

        /** Green-blind: roughly 1% of men, and the commonest severe form. */
        DEUTERANOPIA(
            doubleArrayOf(
                0.367322, 0.860646, -0.227968,
                0.280085, 0.672501, 0.047413,
                -0.011820, 0.042940, 0.968881
            )
        ),

        /** Blue-blind: rare, and the one a blue-yellow ramp would fail. Measured, not designed for. */
        TRITANOPIA(
            doubleArrayOf(
                1.255528, -0.076749, -0.178779,
                -0.078411, 0.930809, 0.147602,
                0.004733, 0.691367, 0.303900
            )
        )
    }

    /** [argb] as a reader with this deficiency sees it, packed the same way. */
    fun simulate(argb: Int, deficiency: Deficiency): Int {
        val r = linear((argb shr 16) and 0xFF)
        val g = linear((argb shr 8) and 0xFF)
        val b = linear(argb and 0xFF)
        val m = deficiency.matrix
        return (0xFF shl 24) or
            (encode(m[0] * r + m[1] * g + m[2] * b) shl 16) or
            (encode(m[3] * r + m[4] * g + m[5] * b) shl 8) or
            encode(m[6] * r + m[7] * g + m[8] * b)
    }

    /**
     * The CIEDE2000 colour difference between two packed colours, in D65.
     *
     * CIE 142-2001, written out rather than approximated: the lightness, chroma and hue terms with
     * their weighting functions, and the rotation term that stops the blues from being overstated.
     * The scale is the perceptual one everyone quotes — about 1 is the smallest difference a
     * trained eye finds under laboratory conditions, 2 to 3 is what a printer will argue about,
     * and 5 upward is two colours anybody would call different at a glance.
     */
    fun deltaE2000(first: Int, second: Int): Double {
        val a = lab(first)
        val b = lab(second)
        val kL = 1.0
        val kC = 1.0
        val kH = 1.0

        val c1 = hypot(a[1], a[2])
        val c2 = hypot(b[1], b[2])
        val cBar = (c1 + c2) / 2.0
        val cBar7 = cBar.pow(7.0)
        val g = 0.5 * (1.0 - sqrt(cBar7 / (cBar7 + POW25_7)))

        val a1 = (1.0 + g) * a[1]
        val a2 = (1.0 + g) * b[1]
        val c1p = hypot(a1, a[2])
        val c2p = hypot(a2, b[2])
        val h1 = angle(a[2], a1)
        val h2 = angle(b[2], a2)

        val dL = b[0] - a[0]
        val dC = c2p - c1p
        val dh = when {
            c1p * c2p == 0.0 -> 0.0
            else -> {
                var d = h2 - h1
                if (d > 180.0) d -= 360.0
                if (d < -180.0) d += 360.0
                d
            }
        }
        val dH = 2.0 * sqrt(c1p * c2p) * sin(radians(dh / 2.0))

        val lBar = (a[0] + b[0]) / 2.0
        val cBarP = (c1p + c2p) / 2.0
        val hBar = when {
            c1p * c2p == 0.0 -> h1 + h2
            abs(h1 - h2) <= 180.0 -> (h1 + h2) / 2.0
            h1 + h2 < 360.0 -> (h1 + h2 + 360.0) / 2.0
            else -> (h1 + h2 - 360.0) / 2.0
        }

        val t = 1.0 - 0.17 * cos(radians(hBar - 30.0)) +
            0.24 * cos(radians(2.0 * hBar)) +
            0.32 * cos(radians(3.0 * hBar + 6.0)) -
            0.20 * cos(radians(4.0 * hBar - 63.0))

        val theta = 30.0 * exp(-(((hBar - 275.0) / 25.0).pow(2.0)))
        val cBarP7 = cBarP.pow(7.0)
        val rc = 2.0 * sqrt(cBarP7 / (cBarP7 + POW25_7))
        val sL = 1.0 + (0.015 * (lBar - 50.0).pow(2.0)) / sqrt(20.0 + (lBar - 50.0).pow(2.0))
        val sC = 1.0 + 0.045 * cBarP
        val sH = 1.0 + 0.015 * cBarP * t
        val rt = -sin(radians(2.0 * theta)) * rc

        val termL = dL / (kL * sL)
        val termC = dC / (kC * sC)
        val termH = dH / (kH * sH)
        return sqrt(termL * termL + termC * termC + termH * termH + rt * termC * termH)
    }

    /** The same, through a simulation: what the difference is *worth* to a dichromatic reader. */
    fun deltaE2000(first: Int, second: Int, deficiency: Deficiency): Double =
        deltaE2000(simulate(first, deficiency), simulate(second, deficiency))

    /**
     * The WCAG 2.1 contrast ratio between two colours, from 1 to 21.
     *
     * 4.5 is AA for body text, 7 is AAA, and 3 is the bar for a control's own boundary rather than
     * for anything read (1.4.11). The relative luminance is the standard's own — the same linear
     * decode as above, weighted 0.2126, 0.7152, 0.0722.
     */
    fun contrast(first: Int, second: Int): Double {
        val a = luminance(first)
        val b = luminance(second)
        val lighter = maxOf(a, b)
        val darker = minOf(a, b)
        return (lighter + 0.05) / (darker + 0.05)
    }

    fun luminance(argb: Int): Double =
        0.2126 * linear((argb shr 16) and 0xFF) +
            0.7152 * linear((argb shr 8) and 0xFF) +
            0.0722 * linear(argb and 0xFF)

    /** CIE L*a*b* under D65, as a three-element array. */
    private fun lab(argb: Int): DoubleArray {
        val r = linear((argb shr 16) and 0xFF)
        val g = linear((argb shr 8) and 0xFF)
        val b = linear(argb and 0xFF)
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
        val y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        return doubleArrayOf(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    private fun f(t: Double): Double =
        if (t > EPSILON) t.pow(1.0 / 3.0) else t / (3.0 * KAPPA * KAPPA) + 4.0 / 29.0

    /** The sRGB transfer function, decoded. */
    private fun linear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    /** And encoded again, back to a byte. */
    private fun encode(value: Double): Int {
        val c = value.coerceIn(0.0, 1.0)
        val v = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1.0 / 2.4) - 0.055
        return (v * 255.0 + 0.5).toInt().coerceIn(0, 255)
    }

    private fun hypot(a: Double, b: Double): Double = sqrt(a * a + b * b)

    private fun angle(b: Double, a: Double): Double {
        if (a == 0.0 && b == 0.0) return 0.0
        val degrees = atan2(b, a) * 180.0 / PI
        return if (degrees < 0.0) degrees + 360.0 else degrees
    }

    private fun radians(degrees: Double): Double = degrees * PI / 180.0

    private const val POW25_7 = 6103515625.0
    private const val KAPPA = 6.0 / 29.0
    private val EPSILON = (6.0 / 29.0).pow(3.0)
}
