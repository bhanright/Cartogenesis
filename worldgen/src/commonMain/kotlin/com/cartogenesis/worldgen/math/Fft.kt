package com.cartogenesis.worldgen.math

import kotlin.math.PI
import com.cartogenesis.worldgen.concurrent.parallelChunks
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place radix-2 FFT over a strided view of an array, so 2D transforms can run over rows and
 * columns without copying. Twiddle factors are tabulated once per length.
 */
class Fft1D(private val n: Int) {

    private val cosTable = DoubleArray(n / 2) { cos(2.0 * PI * it / n) }
    private val sinTable = DoubleArray(n / 2) { sin(2.0 * PI * it / n) }

    init {
        require(n > 1 && (n and (n - 1)) == 0) { "FFT length must be a power of two, got $n" }
    }

    fun transform(re: DoubleArray, im: DoubleArray, offset: Int, stride: Int, inverse: Boolean) {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val a = offset + i * stride
                val b = offset + j * stride
                var t = re[a]; re[a] = re[b]; re[b] = t
                t = im[a]; im[a] = im[b]; im[b] = t
            }
        }

        var len = 2
        while (len <= n) {
            val half = len shr 1
            val step = n / len
            var block = 0
            while (block < n) {
                for (k in 0 until half) {
                    val tw = k * step
                    val wr = cosTable[tw]
                    val wi = if (inverse) sinTable[tw] else -sinTable[tw]

                    val i0 = offset + (block + k) * stride
                    val i1 = offset + (block + k + half) * stride

                    val vr = re[i1] * wr - im[i1] * wi
                    val vi = re[i1] * wi + im[i1] * wr
                    val ur = re[i0]
                    val ui = im[i0]

                    re[i0] = ur + vr
                    im[i0] = ui + vi
                    re[i1] = ur - vr
                    im[i1] = ui - vi
                }
                block += len
            }
            len = len shl 1
        }
    }
}

/** 2D complex FFT for power-of-two grids, row-major. */
class Fft2D(private val width: Int, private val height: Int) {

    private val rowPlan = Fft1D(width)
    private val colPlan = Fft1D(height)

    fun forward(re: DoubleArray, im: DoubleArray) = run(re, im, inverse = false)

    fun inverse(re: DoubleArray, im: DoubleArray) {
        run(re, im, inverse = true)
        val scale = 1.0 / (width.toDouble() * height.toDouble())
        parallelChunks(0, re.size) { start, end ->
            for (i in start until end) {
                re[i] *= scale
                im[i] *= scale
            }
        }
    }

    /**
     * A separable 2D transform: every row, then every column.
     *
     * Both passes are split across cores. Within a pass the lines are genuinely independent — each
     * touches only its own offsets and stride, and the plans hold no mutable state — so the result
     * is identical to running them in order. The two passes must stay sequential with respect to
     * each other, since the columns read what the rows wrote.
     */
    private fun run(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        require(re.size == width * height && im.size == width * height) {
            "buffers must be $width x $height"
        }
        parallelChunks(0, height) { start, end ->
            for (y in start until end) {
                rowPlan.transform(re, im, offset = y * width, stride = 1, inverse = inverse)
            }
        }
        parallelChunks(0, width) { start, end ->
            for (x in start until end) {
                colPlan.transform(re, im, offset = x, stride = width, inverse = inverse)
            }
        }
    }
}
