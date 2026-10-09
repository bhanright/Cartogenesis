package com.cartogenesis.worldgen.math

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A discrete Fourier transform of one complex line of any length, by Stockham's self-sorting
 * mixed-radix algorithm.
 *
 * [Fft1D] takes powers of two only, which the map's own grid always is; the atmosphere's grid is
 * sized by the planet instead and has 5-smooth longitude counts (300, 360), so its transforms need
 * radices of three and five as well. A length is factored into fours, then twos, threes and fives,
 * and any prime above five, each one butterfly stage; a prime above five is still exact, at the cost
 * of a direct sum over its own length, so no length is refused. Each stage reads one buffer and
 * writes the other in order, so no bit-reversal pass is needed.
 *
 * The convention is the usual one: [forward] computes `X_k = sum_j x_j exp(-2 pi i j k / n)` and
 * [inverse] the same sum with `+i`, neither scaled, so a round trip multiplies by [length]. A plan
 * holds only its tables and is safe to share between threads; every call brings its own scratch.
 */
class ComplexFft(val length: Int) {

    init {
        require(length >= 1) { "an FFT needs at least one sample, got $length" }
    }

    /** The radix of each stage, fours first. */
    private val radices: IntArray = stageRadices(length)

    /** `cos(2 pi j / n)` and `sin(2 pi j / n)` for every j: every twiddle is one of these. */
    private val cosTable = DoubleArray(length) { cos(2.0 * PI * it / length) }
    private val sinTable = DoubleArray(length) { sin(2.0 * PI * it / length) }

    /** Scratch for one transform: the buffer each stage writes while reading the other. */
    class Scratch(length: Int) {
        internal val real = DoubleArray(length)
        internal val imaginary = DoubleArray(length)
        internal val branchReal = DoubleArray(length)
        internal val branchImaginary = DoubleArray(length)
    }

    /** Transforms [real] and [imaginary] in place, `exp(-i ...)`, unscaled. */
    fun forward(real: DoubleArray, imaginary: DoubleArray, scratch: Scratch = Scratch(length)) =
        transform(real, imaginary, scratch, sign = -1.0)

    /** Transforms [real] and [imaginary] in place, `exp(+i ...)`, unscaled. */
    fun inverse(real: DoubleArray, imaginary: DoubleArray, scratch: Scratch = Scratch(length)) =
        transform(real, imaginary, scratch, sign = 1.0)

    /**
     * Stockham's decimation in frequency. At a stage with stride `s` and `n = length / s` left, each
     * radix-p butterfly takes the p samples `x[t + s (q + r m)]`, `m = n / p`, transforms them, and
     * writes output `k` times the twiddle `exp(sign 2 pi i k q / n)` to `y[t + s (p q + k)]`.
     */
    private fun transform(real: DoubleArray, imaginary: DoubleArray, scratch: Scratch, sign: Double) {
        require(real.size >= length && imaginary.size >= length) { "the line is shorter than $length" }
        if (length == 1) return
        var fromReal = real
        var fromImaginary = imaginary
        var toReal = scratch.real
        var toImaginary = scratch.imaginary
        var stride = 1
        var remaining = length
        for (radix in radices) {
            val quarter = remaining / radix
            when (radix) {
                2 -> radixTwo(fromReal, fromImaginary, toReal, toImaginary, stride, quarter, sign)
                4 -> radixFour(fromReal, fromImaginary, toReal, toImaginary, stride, quarter, sign)
                3 -> radixThree(fromReal, fromImaginary, toReal, toImaginary, stride, quarter, sign)
                5 -> radixFive(fromReal, fromImaginary, toReal, toImaginary, stride, quarter, sign)
                else -> radixAny(fromReal, fromImaginary, toReal, toImaginary, stride, quarter, radix, sign, scratch)
            }
            val swapReal = fromReal
            val swapImaginary = fromImaginary
            fromReal = toReal
            fromImaginary = toImaginary
            toReal = swapReal
            toImaginary = swapImaginary
            stride *= radix
            remaining = quarter
        }
        if (fromReal !== real) {
            fromReal.copyInto(real, 0, 0, length)
            fromImaginary.copyInto(imaginary, 0, 0, length)
        }
    }

    private fun radixTwo(
        inReal: DoubleArray, inImaginary: DoubleArray, outReal: DoubleArray, outImaginary: DoubleArray,
        stride: Int, quarter: Int, sign: Double
    ) {
        for (q in 0 until quarter) {
            val twiddle = q * stride
            val twiddleReal = cosTable[twiddle]
            val twiddleImaginary = sign * sinTable[twiddle]
            val first = stride * q
            val second = stride * (q + quarter)
            val out = stride * 2 * q
            for (t in 0 until stride) {
                val aReal = inReal[first + t]
                val aImaginary = inImaginary[first + t]
                val bReal = inReal[second + t]
                val bImaginary = inImaginary[second + t]
                outReal[out + t] = aReal + bReal
                outImaginary[out + t] = aImaginary + bImaginary
                val differenceReal = aReal - bReal
                val differenceImaginary = aImaginary - bImaginary
                outReal[out + stride + t] = differenceReal * twiddleReal - differenceImaginary * twiddleImaginary
                outImaginary[out + stride + t] = differenceReal * twiddleImaginary + differenceImaginary * twiddleReal
            }
        }
    }

    private fun radixFour(
        inReal: DoubleArray, inImaginary: DoubleArray, outReal: DoubleArray, outImaginary: DoubleArray,
        stride: Int, quarter: Int, sign: Double
    ) {
        for (q in 0 until quarter) {
            val step = q * stride
            val w1Real = cosTable[step]
            val w1Imaginary = sign * sinTable[step]
            val w2Real = cosTable[2 * step]
            val w2Imaginary = sign * sinTable[2 * step]
            val w3Real = cosTable[3 * step]
            val w3Imaginary = sign * sinTable[3 * step]
            val in0 = stride * q
            val in1 = stride * (q + quarter)
            val in2 = stride * (q + 2 * quarter)
            val in3 = stride * (q + 3 * quarter)
            val out = stride * 4 * q
            for (t in 0 until stride) {
                val aReal = inReal[in0 + t]
                val aImaginary = inImaginary[in0 + t]
                val bReal = inReal[in1 + t]
                val bImaginary = inImaginary[in1 + t]
                val cReal = inReal[in2 + t]
                val cImaginary = inImaginary[in2 + t]
                val dReal = inReal[in3 + t]
                val dImaginary = inImaginary[in3 + t]
                val sumAcReal = aReal + cReal
                val sumAcImaginary = aImaginary + cImaginary
                val differenceAcReal = aReal - cReal
                val differenceAcImaginary = aImaginary - cImaginary
                val sumBdReal = bReal + dReal
                val sumBdImaginary = bImaginary + dImaginary
                // (b - d) turned a quarter by the transform's sign: sign i (b - d).
                val turnedReal = -sign * (bImaginary - dImaginary)
                val turnedImaginary = sign * (bReal - dReal)
                outReal[out + t] = sumAcReal + sumBdReal
                outImaginary[out + t] = sumAcImaginary + sumBdImaginary
                val y1Real = differenceAcReal + turnedReal
                val y1Imaginary = differenceAcImaginary + turnedImaginary
                outReal[out + stride + t] = y1Real * w1Real - y1Imaginary * w1Imaginary
                outImaginary[out + stride + t] = y1Real * w1Imaginary + y1Imaginary * w1Real
                val y2Real = sumAcReal - sumBdReal
                val y2Imaginary = sumAcImaginary - sumBdImaginary
                outReal[out + 2 * stride + t] = y2Real * w2Real - y2Imaginary * w2Imaginary
                outImaginary[out + 2 * stride + t] = y2Real * w2Imaginary + y2Imaginary * w2Real
                val y3Real = differenceAcReal - turnedReal
                val y3Imaginary = differenceAcImaginary - turnedImaginary
                outReal[out + 3 * stride + t] = y3Real * w3Real - y3Imaginary * w3Imaginary
                outImaginary[out + 3 * stride + t] = y3Real * w3Imaginary + y3Imaginary * w3Real
            }
        }
    }

    /** Three points: `b_k = sum_r a_r w^(r k)`, `w = exp(sign 2 pi i / 3)`, each output then turned by its twiddle. */
    private fun radixThree(
        inReal: DoubleArray, inImaginary: DoubleArray, outReal: DoubleArray, outImaginary: DoubleArray,
        stride: Int, quarter: Int, sign: Double
    ) {
        val rootSine = sign * SINE_OF_A_THIRD
        for (q in 0 until quarter) {
            val step = q * stride
            val w1Real = cosTable[step]
            val w1Imaginary = sign * sinTable[step]
            val w2Real = cosTable[2 * step]
            val w2Imaginary = sign * sinTable[2 * step]
            val in0 = stride * q
            val in1 = stride * (q + quarter)
            val in2 = stride * (q + 2 * quarter)
            val out = stride * 3 * q
            for (t in 0 until stride) {
                val aReal = inReal[in0 + t]
                val aImaginary = inImaginary[in0 + t]
                val sumReal = inReal[in1 + t] + inReal[in2 + t]
                val sumImaginary = inImaginary[in1 + t] + inImaginary[in2 + t]
                val differenceReal = inReal[in1 + t] - inReal[in2 + t]
                val differenceImaginary = inImaginary[in1 + t] - inImaginary[in2 + t]
                outReal[out + t] = aReal + sumReal
                outImaginary[out + t] = aImaginary + sumImaginary
                // a0 + cos(2 pi / 3) (a1 + a2), and i sin(2 pi / 3) (a1 - a2) either side of it.
                val middleReal = aReal - 0.5 * sumReal
                val middleImaginary = aImaginary - 0.5 * sumImaginary
                val turnedReal = -rootSine * differenceImaginary
                val turnedImaginary = rootSine * differenceReal
                val y1Real = middleReal + turnedReal
                val y1Imaginary = middleImaginary + turnedImaginary
                val y2Real = middleReal - turnedReal
                val y2Imaginary = middleImaginary - turnedImaginary
                outReal[out + stride + t] = y1Real * w1Real - y1Imaginary * w1Imaginary
                outImaginary[out + stride + t] = y1Real * w1Imaginary + y1Imaginary * w1Real
                outReal[out + 2 * stride + t] = y2Real * w2Real - y2Imaginary * w2Imaginary
                outImaginary[out + 2 * stride + t] = y2Real * w2Imaginary + y2Imaginary * w2Real
            }
        }
    }

    /** Five points, as [radixThree] with `w = exp(sign 2 pi i / 5)`. */
    private fun radixFive(
        inReal: DoubleArray, inImaginary: DoubleArray, outReal: DoubleArray, outImaginary: DoubleArray,
        stride: Int, quarter: Int, sign: Double
    ) {
        val sine1 = sign * SINE_OF_A_FIFTH
        val sine2 = sign * SINE_OF_TWO_FIFTHS
        for (q in 0 until quarter) {
            val step = q * stride
            val in0 = stride * q
            val in1 = stride * (q + quarter)
            val in2 = stride * (q + 2 * quarter)
            val in3 = stride * (q + 3 * quarter)
            val in4 = stride * (q + 4 * quarter)
            val out = stride * 5 * q
            for (t in 0 until stride) {
                val aReal = inReal[in0 + t]
                val aImaginary = inImaginary[in0 + t]
                val sum14Real = inReal[in1 + t] + inReal[in4 + t]
                val sum14Imaginary = inImaginary[in1 + t] + inImaginary[in4 + t]
                val sum23Real = inReal[in2 + t] + inReal[in3 + t]
                val sum23Imaginary = inImaginary[in2 + t] + inImaginary[in3 + t]
                val difference14Real = inReal[in1 + t] - inReal[in4 + t]
                val difference14Imaginary = inImaginary[in1 + t] - inImaginary[in4 + t]
                val difference23Real = inReal[in2 + t] - inReal[in3 + t]
                val difference23Imaginary = inImaginary[in2 + t] - inImaginary[in3 + t]
                outReal[out + t] = aReal + sum14Real + sum23Real
                outImaginary[out + t] = aImaginary + sum14Imaginary + sum23Imaginary
                val middle1Real = aReal + COSINE_OF_A_FIFTH * sum14Real + COSINE_OF_TWO_FIFTHS * sum23Real
                val middle1Imaginary = aImaginary + COSINE_OF_A_FIFTH * sum14Imaginary + COSINE_OF_TWO_FIFTHS * sum23Imaginary
                val middle2Real = aReal + COSINE_OF_TWO_FIFTHS * sum14Real + COSINE_OF_A_FIFTH * sum23Real
                val middle2Imaginary = aImaginary + COSINE_OF_TWO_FIFTHS * sum14Imaginary + COSINE_OF_A_FIFTH * sum23Imaginary
                // i (s1 d14 + s2 d23) and i (s2 d14 - s1 d23): multiplying by i swaps the parts.
                val turned1Real = -(sine1 * difference14Imaginary + sine2 * difference23Imaginary)
                val turned1Imaginary = sine1 * difference14Real + sine2 * difference23Real
                val turned2Real = -(sine2 * difference14Imaginary - sine1 * difference23Imaginary)
                val turned2Imaginary = sine2 * difference14Real - sine1 * difference23Real
                writeTurned(outReal, outImaginary, out + stride + t, middle1Real + turned1Real, middle1Imaginary + turned1Imaginary, step, sign)
                writeTurned(outReal, outImaginary, out + 2 * stride + t, middle2Real + turned2Real, middle2Imaginary + turned2Imaginary, 2 * step, sign)
                writeTurned(outReal, outImaginary, out + 3 * stride + t, middle2Real - turned2Real, middle2Imaginary - turned2Imaginary, 3 * step, sign)
                writeTurned(outReal, outImaginary, out + 4 * stride + t, middle1Real - turned1Real, middle1Imaginary - turned1Imaginary, 4 * step, sign)
            }
        }
    }

    /** Writes [real] + i [imaginary] turned by the twiddle at table index [twiddle] to [slot]. */
    private fun writeTurned(outReal: DoubleArray, outImaginary: DoubleArray, slot: Int, real: Double, imaginary: Double, twiddle: Int, sign: Double) {
        val twiddleReal = cosTable[twiddle]
        val twiddleImaginary = sign * sinTable[twiddle]
        outReal[slot] = real * twiddleReal - imaginary * twiddleImaginary
        outImaginary[slot] = real * twiddleImaginary + imaginary * twiddleReal
    }

    /** Any radix by the direct sum, whose angles `2 pi r k / p` are the table's at the stride `length / p`. */
    private fun radixAny(
        inReal: DoubleArray, inImaginary: DoubleArray, outReal: DoubleArray, outImaginary: DoubleArray,
        stride: Int, quarter: Int, radix: Int, sign: Double, scratch: Scratch
    ) {
        val branchReal = scratch.branchReal
        val branchImaginary = scratch.branchImaginary
        val rootStep = length / radix
        for (q in 0 until quarter) {
            for (t in 0 until stride) {
                for (branch in 0 until radix) {
                    branchReal[branch] = inReal[stride * (q + branch * quarter) + t]
                    branchImaginary[branch] = inImaginary[stride * (q + branch * quarter) + t]
                }
                for (output in 0 until radix) {
                    var sumReal = 0.0
                    var sumImaginary = 0.0
                    for (branch in 0 until radix) {
                        val angle = (branch * output % radix) * rootStep
                        val rotationReal = cosTable[angle]
                        val rotationImaginary = sign * sinTable[angle]
                        sumReal += branchReal[branch] * rotationReal - branchImaginary[branch] * rotationImaginary
                        sumImaginary += branchReal[branch] * rotationImaginary + branchImaginary[branch] * rotationReal
                    }
                    val twiddle = (output * q * stride) % length
                    val twiddleReal = cosTable[twiddle]
                    val twiddleImaginary = sign * sinTable[twiddle]
                    val slot = stride * (radix * q + output) + t
                    outReal[slot] = sumReal * twiddleReal - sumImaginary * twiddleImaginary
                    outImaginary[slot] = sumReal * twiddleImaginary + sumImaginary * twiddleReal
                }
            }
        }
    }

    companion object {
        /** `sin(2 pi / 3)`, the radix-three butterfly's one irrational. */
        private val SINE_OF_A_THIRD = sin(2.0 * PI / 3.0)

        /** `cos` and `sin` of `2 pi / 5` and `4 pi / 5`, the radix-five butterfly's. */
        private val COSINE_OF_A_FIFTH = cos(2.0 * PI / 5.0)
        private val COSINE_OF_TWO_FIFTHS = cos(4.0 * PI / 5.0)
        private val SINE_OF_A_FIFTH = sin(2.0 * PI / 5.0)
        private val SINE_OF_TWO_FIFTHS = sin(4.0 * PI / 5.0)

        /** The prime factors of [number], smallest first, with repeats. */
        fun primeFactors(number: Int): IntArray {
            val factors = ArrayList<Int>()
            var remaining = number
            var divisor = 2
            while (remaining > 1 && divisor <= sqrt(remaining.toDouble()).toInt()) {
                while (remaining % divisor == 0) {
                    factors.add(divisor)
                    remaining /= divisor
                }
                divisor++
            }
            if (remaining > 1) factors.add(remaining)
            return factors.toIntArray()
        }

        /** The stages for [number]: its twos paired into fours, then what is left, smallest first. */
        private fun stageRadices(number: Int): IntArray {
            val primes = primeFactors(number)
            val twos = primes.count { it == 2 }
            val stages = ArrayList<Int>()
            repeat(twos / 2) { stages.add(4) }
            if (twos % 2 == 1) stages.add(2)
            primes.filter { it != 2 }.forEach { stages.add(it) }
            return stages.toIntArray()
        }

        /** Whether [number] has no prime factor above five: a length the transform takes in stages of 2, 3, 4 and 5. */
        fun isFiveSmooth(number: Int): Boolean = number >= 1 && primeFactors(number).all { it <= 5 }

        /** The smallest 5-smooth number at least [number]. */
        fun nextFiveSmooth(number: Int): Int {
            var candidate = number.coerceAtLeast(1)
            while (!isFiveSmooth(candidate)) candidate++
            return candidate
        }
    }
}
