package com.cartogenesis.worldgen.math

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A discrete Fourier transform of one complex line of any length, by mixed-radix Cooley-Tukey.
 *
 * [Fft1D] takes powers of two only, which the map's own grid always is; the atmosphere's grid is
 * sized by the planet instead and has 5-smooth longitude counts (360, 480), so its transforms need
 * radices of three and five as well. A length is factored into primes and each prime is one
 * butterfly stage; a prime above five is still exact, at the cost of a direct sum over its own
 * length, so no length is refused.
 *
 * The convention is the usual one: [forward] computes `X_k = sum_j x_j exp(-2 pi i j k / n)` and
 * [inverse] the same sum with `+i`, neither scaled, so a round trip multiplies by [length]. A plan
 * holds only its twiddle table and is safe to share between threads; every call brings its own
 * scratch.
 */
class ComplexFft(val length: Int) {

    init {
        require(length >= 1) { "an FFT needs at least one sample, got $length" }
    }

    /** The prime factors of [length], smallest first: one butterfly stage each. */
    private val radices: IntArray = primeFactors(length)

    /** `exp(-2 pi i j / n)` for every j, the only angles any stage needs. */
    private val cosTable = DoubleArray(length) { cos(2.0 * PI * it / length) }
    private val sinTable = DoubleArray(length) { -sin(2.0 * PI * it / length) }

    /** Scratch for one transform: the recursion writes its sub-transforms here. */
    class Scratch(length: Int) {
        internal val real = DoubleArray(length)
        internal val imaginary = DoubleArray(length)
        /** One butterfly's inputs; the combines never interleave, so one pair serves every stage. */
        internal val branchReal = DoubleArray(length)
        internal val branchImaginary = DoubleArray(length)
    }

    /** Transforms [real] and [imaginary] in place, `exp(-i ...)`, unscaled. */
    fun forward(real: DoubleArray, imaginary: DoubleArray, scratch: Scratch = Scratch(length)) =
        transform(real, imaginary, scratch, inverse = false)

    /** Transforms [real] and [imaginary] in place, `exp(+i ...)`, unscaled. */
    fun inverse(real: DoubleArray, imaginary: DoubleArray, scratch: Scratch = Scratch(length)) =
        transform(real, imaginary, scratch, inverse = true)

    private fun transform(real: DoubleArray, imaginary: DoubleArray, scratch: Scratch, inverse: Boolean) {
        require(real.size >= length && imaginary.size >= length) { "the line is shorter than $length" }
        if (length == 1) return
        recurse(real, imaginary, 0, 1, length, scratch, 0, 0, inverse)
        for (index in 0 until length) {
            real[index] = scratch.real[index]
            imaginary[index] = scratch.imaginary[index]
        }
    }

    /**
     * Decimation in time: the transform of [count] samples read from [inputReal] at [inputOffset]
     * with [inputStride] is written to [scratch]'s line from [outputOffset]. The first factor's
     * [count] / r sub-transforms are written side by side and then combined in place, which works
     * because for each output index the r values one butterfly reads are exactly the r slots it
     * writes.
     */
    private fun recurse(
        inputReal: DoubleArray, inputImaginary: DoubleArray, inputOffset: Int, inputStride: Int, count: Int,
        scratch: Scratch, outputOffset: Int, stage: Int, inverse: Boolean
    ) {
        val outputReal = scratch.real
        val outputImaginary = scratch.imaginary
        if (count == 1) {
            outputReal[outputOffset] = inputReal[inputOffset]
            outputImaginary[outputOffset] = inputImaginary[inputOffset]
            return
        }
        val radix = radices[stage]
        val subCount = count / radix
        for (branch in 0 until radix) {
            recurse(
                inputReal, inputImaginary, inputOffset + branch * inputStride, inputStride * radix, subCount,
                scratch, outputOffset + branch * subCount, stage + 1, inverse
            )
        }
        // The twiddle of `exp(-2 pi i q k / count)` is the table's entry q k (length / count).
        val tableStep = length / count
        val sign = if (inverse) -1.0 else 1.0
        val branchReal = scratch.branchReal
        val branchImaginary = scratch.branchImaginary
        for (index in 0 until subCount) {
            for (branch in 0 until radix) {
                val slot = outputOffset + branch * subCount + index
                val twiddle = (branch * index * tableStep) % length
                val twiddleReal = cosTable[twiddle]
                val twiddleImaginary = sign * sinTable[twiddle]
                val valueReal = outputReal[slot]
                val valueImaginary = outputImaginary[slot]
                branchReal[branch] = valueReal * twiddleReal - valueImaginary * twiddleImaginary
                branchImaginary[branch] = valueReal * twiddleImaginary + valueImaginary * twiddleReal
            }
            butterfly(branchReal, branchImaginary, radix, sign, outputReal, outputImaginary, outputOffset + index, subCount)
        }
    }

    /**
     * The r-point transform of [branchReal] and [branchImaginary], written to the r outputs at
     * [firstSlot] and every [slotStride] after it. Two is written out; any other radix is the
     * direct sum, whose angles `2 pi q s / r` are the table's at the stride `length / r`.
     */
    private fun butterfly(
        branchReal: DoubleArray, branchImaginary: DoubleArray, radix: Int, sign: Double,
        outputReal: DoubleArray, outputImaginary: DoubleArray, firstSlot: Int, slotStride: Int
    ) {
        if (radix == 2) {
            outputReal[firstSlot] = branchReal[0] + branchReal[1]
            outputImaginary[firstSlot] = branchImaginary[0] + branchImaginary[1]
            outputReal[firstSlot + slotStride] = branchReal[0] - branchReal[1]
            outputImaginary[firstSlot + slotStride] = branchImaginary[0] - branchImaginary[1]
            return
        }
        val tableStep = length / radix
        for (output in 0 until radix) {
            var sumReal = 0.0
            var sumImaginary = 0.0
            for (branch in 0 until radix) {
                val angle = (branch * output % radix) * tableStep
                val rotationReal = cosTable[angle]
                val rotationImaginary = sign * sinTable[angle]
                sumReal += branchReal[branch] * rotationReal - branchImaginary[branch] * rotationImaginary
                sumImaginary += branchReal[branch] * rotationImaginary + branchImaginary[branch] * rotationReal
            }
            outputReal[firstSlot + output * slotStride] = sumReal
            outputImaginary[firstSlot + output * slotStride] = sumImaginary
        }
    }

    companion object {
        /** The prime factors of [number], smallest first, with repeats. */
        fun primeFactors(number: Int): IntArray {
            val factors = ArrayList<Int>()
            var remaining = number
            var divisor = 2
            while (remaining > 1) {
                while (remaining % divisor == 0) {
                    factors.add(divisor)
                    remaining /= divisor
                }
                divisor++
                if (divisor.toLong() * divisor > remaining && remaining > 1) {
                    factors.add(remaining)
                    remaining = 1
                }
            }
            return factors.toIntArray()
        }

        /** Whether [number] has no prime factor above five: a length the transform takes in stages of 2, 3 and 5. */
        fun isFiveSmooth(number: Int): Boolean = number >= 1 && primeFactors(number).all { it <= 5 }

        /** The smallest 5-smooth number at least [number]. */
        fun nextFiveSmooth(number: Int): Int {
            var candidate = number.coerceAtLeast(1)
            while (!isFiveSmooth(candidate)) candidate++
            return candidate
        }
    }
}
