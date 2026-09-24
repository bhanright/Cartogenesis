package com.cartogenesis.worldgen.math

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.model.FloatField
import kotlin.math.ceil
import kotlin.math.exp

/**
 * A Gaussian blur whose spread is a length on the ground, one standard deviation per axis.
 *
 * The one kernel with no preferred direction that is also separable. A two-dimensional Gaussian
 * is the product of two one-dimensional ones, and that product is round only when the two share a
 * standard deviation *in the units the ground is measured in*. This map's cells are not square — at
 * 512 by 512 a cell is 23.4 km across and 11.7 km down — so the same spread on the ground is twice
 * as many rows as columns, and the caller hands in each axis's spread in its own cells. With that
 * done the kernel is the circle rule 13 asks for, to the sampling of its taps, where a box blur's
 * square support and a single radius for both axes gave a rectangle twice as long east-west as
 * north-south.
 *
 * The taps are the continuous Gaussian sampled at cell centres, carried out to
 * [TRUNCATION_SIGMAS] standard deviations and normalised to one, so a constant field is left
 * exactly constant. X wraps and Y clamps at the poles, as [BoxBlur] does. The sums are in double
 * and every output cell is written by one iteration only, so the result does not depend on how the
 * rows are split across cores.
 */
object GaussianBlur {

    /**
     * How far out the kernel is carried, in standard deviations.
     *
     * Four, where the tap weighs `exp(-8)`, three ten-thousandths of the centre's: the step the cut
     * leaves in the kernel is then below anything a continental margin's share or a belt's uplift
     * could show, where at three the last tap is a hundredth of the centre and the cut would be a
     * faint ring around every sharp feature the kernel smooths.
     */
    const val TRUNCATION_SIGMAS = 4.0

    /**
     * Blurs [field] in place: a Gaussian of [sigmaAcrossCells] columns east-west and
     * [sigmaDownCells] rows north-south.
     *
     * A standard deviation of zero or less leaves that axis alone. The caller converts one spread
     * on the ground into the two, `kilometres / cellWidthKm` and `kilometres / cellHeightKm`, which
     * is what makes the kernel round on the ground.
     */
    fun apply(field: FloatField, sigmaAcrossCells: Double, sigmaDownCells: Double) {
        if (sigmaAcrossCells > 0.0) across(field, weights(sigmaAcrossCells))
        if (sigmaDownCells > 0.0) down(field, weights(sigmaDownCells))
    }

    /**
     * The normalised taps of a one-dimensional Gaussian of [sigmaCells], from `-radius` to
     * `+radius` with the radius [TRUNCATION_SIGMAS] standard deviations rounded up.
     */
    fun weights(sigmaCells: Double): DoubleArray {
        val radius = ceil(TRUNCATION_SIGMAS * sigmaCells).toInt().coerceAtLeast(1)
        val taps = DoubleArray(2 * radius + 1)
        var total = 0.0
        for (offset in -radius..radius) {
            val standardised = offset / sigmaCells
            val weight = exp(-0.5 * standardised * standardised)
            taps[offset + radius] = weight
            total += weight
        }
        for (tap in taps.indices) taps[tap] /= total
        return taps
    }

    private fun across(field: FloatField, taps: DoubleArray) {
        val cellsAcross = field.width
        val cellsDown = field.height
        val radius = taps.size / 2
        val data = field.data
        val blurred = FloatArray(data.size)
        parallelChunks(0, cellsDown) { startRow, endRow ->
            // The row with the wrap written out either side, so the taps never take a remainder.
            val padded = DoubleArray(cellsAcross + 2 * radius)
            for (row in startRow until endRow) {
                val rowStart = row * cellsAcross
                for (slot in padded.indices) {
                    padded[slot] = data[rowStart + wrap(slot - radius, cellsAcross)].toDouble()
                }
                for (column in 0 until cellsAcross) {
                    var sum = 0.0
                    for (tap in taps.indices) sum += taps[tap] * padded[column + tap]
                    blurred[rowStart + column] = sum.toFloat()
                }
            }
        }
        blurred.copyInto(data)
    }

    private fun down(field: FloatField, taps: DoubleArray) {
        val cellsAcross = field.width
        val cellsDown = field.height
        val radius = taps.size / 2
        val data = field.data
        val blurred = FloatArray(data.size)
        parallelChunks(0, cellsDown) { startRow, endRow ->
            // Whole rows at a time, so the inner loop walks memory in order.
            val sums = DoubleArray(cellsAcross)
            for (row in startRow until endRow) {
                sums.fill(0.0)
                for (tap in taps.indices) {
                    val sourceStart = (row + tap - radius).coerceIn(0, cellsDown - 1) * cellsAcross
                    val weight = taps[tap]
                    for (column in 0 until cellsAcross) sums[column] += weight * data[sourceStart + column]
                }
                val rowStart = row * cellsAcross
                for (column in 0 until cellsAcross) blurred[rowStart + column] = sums[column].toFloat()
            }
        }
        blurred.copyInto(data)
    }

    /** Wraps a column index into the grid, because the map joins up east to west. */
    private fun wrap(column: Int, cellsAcross: Int): Int {
        val remainder = column % cellsAcross
        return if (remainder < 0) remainder + cellsAcross else remainder
    }
}
