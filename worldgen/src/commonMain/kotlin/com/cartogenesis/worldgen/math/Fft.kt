package com.cartogenesis.worldgen.math

import kotlin.math.PI
import com.cartogenesis.worldgen.concurrent.parallelChunks
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place radix-2 FFT over a strided view of an array, so 2D transforms can run over rows and
 * columns without copying. Twiddle factors are tabulated once per length.
 */
class Fft1D(private val length: Int) {

    private val cosTable = DoubleArray(length / 2) { cos(2.0 * PI * it / length) }
    private val sinTable = DoubleArray(length / 2) { sin(2.0 * PI * it / length) }

    init {
        require(length > 1 && (length and (length - 1)) == 0) {
            "FFT length must be a power of two, got $length"
        }
    }

    /**
     * Transforms one line of [length] complex samples in place.
     *
     * The line is the elements at [offset], `offset + stride`, and so on: [stride] 1 walks a row of
     * a row-major grid and [stride] `width` walks a column. [real] and [imaginary] are the two
     * halves of every sample and are both overwritten. [inverse] only flips the sign of the twiddle
     * factors' imaginary part; the 1/n scaling is the caller's, and [Fft2D.inverse] applies it.
     */
    fun transform(
        real: DoubleArray,
        imaginary: DoubleArray,
        offset: Int,
        stride: Int,
        inverse: Boolean
    ) {
        // Decimation in time needs the samples in bit-reversed order first. `reversed` is carried
        // across iterations as the bit-reversal of `index`, incremented by adding one from the top.
        var reversed = 0
        for (index in 1 until length) {
            var bit = length shr 1
            while (reversed and bit != 0) {
                reversed = reversed xor bit
                bit = bit shr 1
            }
            reversed = reversed or bit
            if (index < reversed) {
                val atIndex = offset + index * stride
                val atReversed = offset + reversed * stride
                var swapped = real[atIndex]
                real[atIndex] = real[atReversed]
                real[atReversed] = swapped
                swapped = imaginary[atIndex]
                imaginary[atIndex] = imaginary[atReversed]
                imaginary[atReversed] = swapped
            }
        }

        // Then the butterflies, doubling the block they combine until one block spans the line.
        var blockLength = 2
        while (blockLength <= length) {
            val halfBlock = blockLength shr 1
            val twiddleStride = length / blockLength
            var blockStart = 0
            while (blockStart < length) {
                for (pair in 0 until halfBlock) {
                    val twiddle = pair * twiddleStride
                    val twiddleReal = cosTable[twiddle]
                    val twiddleImaginary =
                        if (inverse) sinTable[twiddle] else -sinTable[twiddle]

                    val evenIndex = offset + (blockStart + pair) * stride
                    val oddIndex = offset + (blockStart + pair + halfBlock) * stride

                    val rotatedReal =
                        real[oddIndex] * twiddleReal - imaginary[oddIndex] * twiddleImaginary
                    val rotatedImaginary =
                        real[oddIndex] * twiddleImaginary + imaginary[oddIndex] * twiddleReal
                    val evenReal = real[evenIndex]
                    val evenImaginary = imaginary[evenIndex]

                    real[evenIndex] = evenReal + rotatedReal
                    imaginary[evenIndex] = evenImaginary + rotatedImaginary
                    real[oddIndex] = evenReal - rotatedReal
                    imaginary[oddIndex] = evenImaginary - rotatedImaginary
                }
                blockStart += blockLength
            }
            blockLength = blockLength shl 1
        }
    }
}

/** 2D complex FFT for power-of-two grids, row-major. */
class Fft2D(private val width: Int, private val height: Int) {

    private val rowTransform = Fft1D(width)
    private val columnTransform = Fft1D(height)

    /** Transforms the grid in place. Unscaled, so `inverse(forward(f))` is `f` times `width * height`. */
    fun forward(real: DoubleArray, imaginary: DoubleArray) =
        run(real, imaginary, inverse = false)

    /** Transforms the grid back in place, including the 1 / (width * height) the round trip needs. */
    fun inverse(real: DoubleArray, imaginary: DoubleArray) {
        run(real, imaginary, inverse = true)
        val roundTripScale = 1.0 / (width.toDouble() * height.toDouble())
        parallelChunks(0, real.size) { start, end ->
            for (cell in start until end) {
                real[cell] *= roundTripScale
                imaginary[cell] *= roundTripScale
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
    private fun run(real: DoubleArray, imaginary: DoubleArray, inverse: Boolean) {
        require(real.size == width * height && imaginary.size == width * height) {
            "buffers must be $width x $height"
        }
        parallelChunks(0, height) { startRow, endRow ->
            for (row in startRow until endRow) {
                rowTransform.transform(
                    real,
                    imaginary,
                    offset = row * width,
                    stride = 1,
                    inverse = inverse
                )
            }
        }
        parallelChunks(0, width) { startColumn, endColumn ->
            for (column in startColumn until endColumn) {
                columnTransform.transform(
                    real,
                    imaginary,
                    offset = column,
                    stride = width,
                    inverse = inverse
                )
            }
        }
    }
}
