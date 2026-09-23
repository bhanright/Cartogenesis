package com.cartogenesis.worldgen.math

import com.cartogenesis.worldgen.model.FloatField

/**
 * Separable box blur with running sums: O(width * height) per pass regardless of radius.
 *
 * ### Why the running sum is a double over a float field
 *
 * The window is not re-summed at each cell. It is primed once at the start of a row or a column
 * and then slid, subtracting what leaves and adding what enters, so every rounding the sum makes
 * stays in it until the sweep ends. Over a row that is one rounding per cell carried across at
 * most [FloatField.width] cells; over a column, across [FloatField.height] of them, and a column
 * of an equirectangular world runs from the wettest ground on it to the driest. By the time the
 * sweep reaches the pole the sum has had the whole tropics added to it and taken out again, and
 * what is left is a residue of that cancellation whose size is set by the *tropics* and whose
 * value depends on which column it is — which is a stripe, drawn column by column, in a field
 * where the answer is a smooth ramp to nothing.
 *
 * A double accumulator is the repair, and [errorBound] is its contract. Each slide is two
 * operations, so before the `n`th output of a sweep the sum has been rounded at most
 * `2 * radius + 1 + 2 * n` times, each time by at most half an ulp of the largest partial sum on
 * the line; in the worst case the drift grows linearly with the line's length. In a float that
 * worst case is thousands of float roundings of the result at the end of a 2048-row column. The
 * roundings of a real field are not correlated, and the usual *estimate* of their walk, `sqrt(n)`
 * roundings rather than `n`, is the size the fault had in practice; it is an estimate and not a
 * bound. In a double each rounding is at most `2^-53` of the partial sum, and the worst case down
 * a 2048-row column comes to about a hundred-thousandth of one float rounding of the result. That
 * still decides the stored float where the exact answer lies that close to a rounding boundary,
 * and near zero, where a float's spacing is finer than the sum's residue: a cell whose exact answer
 * is zero can come out as a residue of that size, of either sign, instead of zero. See
 * docs/DESIGN_LEDGER.md, X1b, for what the float residue did to the polar ice and for the
 * measurements.
 *
 * Re-summing the window every N rows was the alternative and was declined: it costs
 * `(2 * radius + 1) / N` extra additions per cell, where a wider accumulator adds no operations
 * at all, the sum being one scalar either way.
 */
object BoxBlur {

    /** Half an ulp of a float, `2^-24`: the most one rounding to float can be out, as a share. */
    const val FLOAT_HALF_ULP = 5.9604644775390625e-8

    /** Half an ulp of a double, `2^-53`: the same for one double operation. */
    const val DOUBLE_HALF_ULP = 1.1102230246251565e-16

    /**
     * Half the spacing of the smallest float subnormals, `2^-150`: the most one rounding to float
     * can be out in absolute terms where the relative bound fails, next to zero.
     */
    const val FLOAT_SUBNORMAL_HALF_SPACING = 7.006492321624085e-46

    /**
     * The worst error one sweep's sliding average makes *before* it is rounded to a float, as a
     * share of the largest magnitude on the line it slides along.
     *
     * [radius] is the window's half-width in cells and [lineCells] the length of the row or
     * column. The running sum is primed with `2 * radius + 1` additions and slid with two
     * operations per output, so it is rounded at most `2 * radius + 1 + 2 * lineCells` times, each
     * by at most [DOUBLE_HALF_ULP] of the largest partial sum. A partial sum holds at most one
     * window of values plus the drift itself, so divided by the window it is at most the line's
     * largest magnitude over `1 - operations * DOUBLE_HALF_ULP`. The normalisation multiplies by a
     * rounded reciprocal and rounds the product, two more roundings of the average. A worst case
     * throughout, and so a bound rather than an estimate.
     */
    fun slidingAverageShareOfLargest(radius: Int, lineCells: Int): Double {
        val operations = 2.0 * radius + 1.0 + 2.0 * lineCells
        val sumDrift = operations * DOUBLE_HALF_ULP / (1.0 - operations * DOUBLE_HALF_ULP)
        val normalisation = (1.0 + DOUBLE_HALF_ULP) * (1.0 + DOUBLE_HALF_ULP)
        return sumDrift * normalisation + (normalisation - 1.0)
    }

    /**
     * How far a blurred cell may stand from the exact convolution of the same windows, in the
     * field's own units.
     *
     * [largestMagnitude] is the largest absolute value in the field before the blur; [passes],
     * [radius] and [longestLineCells] are the blur's, with the larger radius and the longer side
     * of the grid where they differ. Each sweep adds its sliding average's error, `d` of
     * [slidingAverageShareOfLargest], and one rounding to float, at most [FLOAT_HALF_ULP] of the
     * result plus [FLOAT_SUBNORMAL_HALF_SPACING]; a box average never enlarges an error already in
     * its input, because its weights are nonnegative and sum to one. With `A` the largest
     * magnitude, `e` the error so far and `beta = d + FLOAT_HALF_ULP * (1 + d)`, one sweep takes
     * `e` to at most `e + (A + e) * beta + eta`, and over `S = 2 * passes` sweeps that closes to
     * `(A + eta / beta) * ((1 + beta)^S - 1)`. It grows with the grid only through `d`, which at
     * any grid this generator runs is a hundred-thousandth of a float rounding or less.
     */
    fun errorBound(
        largestMagnitude: Double,
        passes: Int,
        radius: Int,
        longestLineCells: Int
    ): Double {
        val averageShare = slidingAverageShareOfLargest(radius, longestLineCells)
        val beta = averageShare + FLOAT_HALF_ULP * (1.0 + averageShare)
        var growth = 1.0
        repeat(2 * passes) { growth *= 1.0 + beta }
        return (largestMagnitude + FLOAT_SUBNORMAL_HALF_SPACING / beta) * (growth - 1.0)
    }

    /**
     * Box passes that approximate a Gaussian.
     *
     * Three, because summing three box windows is already close enough to a Gaussian that a fourth
     * moves nothing anybody can see — the central limit theorem converging fast on a bounded
     * kernel — and each pass is another two sweeps of the whole grid.
     */
    const val PASSES_FOR_GAUSSIAN = 3

    /**
     * Blurs [field] in place with a square window of `2 * radius + 1` cells a side, [passes] times.
     *
     * [radius] is in cells and a radius of zero or less is a no-op. X wraps, so the blur joins up
     * across the map's east-west seam; Y clamps at the poles.
     */
    fun apply(field: FloatField, radius: Int, passes: Int = PASSES_FOR_GAUSSIAN) {
        apply(field, radius, radius, passes)
    }

    /**
     * The same blur with a window that is [radiusAcross] cells wide and [radiusDown] cells tall.
     *
     * For a window that is the same *length* on the ground in both axes, which is not the same
     * window in cells: an equirectangular map is twice as wide as it is tall, so a 512 by 512 grid
     * has cells 23 km across and 12 km down and a square window in cells smooths twice as far east
     * as it does south. A caller that means kilometres wants this one.
     */
    fun apply(
        field: FloatField,
        radiusAcross: Int,
        radiusDown: Int,
        passes: Int = PASSES_FOR_GAUSSIAN
    ) {
        if (radiusAcross <= 0 && radiusDown <= 0) return
        val scratch = FloatArray(field.data.size)
        repeat(passes) {
            if (radiusAcross > 0) horizontal(field, radiusAcross, scratch)
            if (radiusDown > 0) vertical(field, radiusDown, scratch)
        }
    }

    private fun horizontal(field: FloatField, radius: Int, scratch: FloatArray) {
        val cellsAcross = field.width
        val cellsDown = field.height
        val data = field.data
        val windowCells = 2 * radius + 1
        val inverseWindowCells = 1.0 / windowCells

        for (row in 0 until cellsDown) {
            val rowStart = row * cellsAcross
            // A running sum, primed on the window centred at column zero and then slid one cell at
            // a time: what leaves the window on the left is subtracted, what enters on the right
            // is added, so the cost per cell does not grow with the radius. In a double, because
            // nothing re-sums the window and every rounding it makes stays in it to the end of the
            // sweep — see the class comment.
            var sum = 0.0
            for (offset in -radius..radius) {
                sum += data[rowStart + wrap(offset, cellsAcross)]
            }
            for (column in 0 until cellsAcross) {
                scratch[rowStart + column] = (sum * inverseWindowCells).toFloat()
                sum -= data[rowStart + wrap(column - radius, cellsAcross)]
                sum += data[rowStart + wrap(column + radius + 1, cellsAcross)]
            }
        }
        scratch.copyInto(data, 0, 0, data.size)
    }

    private fun vertical(field: FloatField, radius: Int, scratch: FloatArray) {
        val cellsAcross = field.width
        val cellsDown = field.height
        val data = field.data
        val windowCells = 2 * radius + 1
        val inverseWindowCells = 1.0 / windowCells

        for (column in 0 until cellsAcross) {
            // The pass the residue was visible in: a column runs from the wettest ground on the
            // map to the driest, so what a float sum carried down it was the tropics' own last
            // bits, read as weather at the pole. See the class comment.
            var sum = 0.0
            for (offset in -radius..radius) {
                sum += data[clampRow(offset, cellsDown) * cellsAcross + column]
            }
            for (row in 0 until cellsDown) {
                scratch[row * cellsAcross + column] = (sum * inverseWindowCells).toFloat()
                sum -= data[clampRow(row - radius, cellsDown) * cellsAcross + column]
                sum += data[clampRow(row + radius + 1, cellsDown) * cellsAcross + column]
            }
        }
        scratch.copyInto(data, 0, 0, data.size)
    }

    /** Wraps a column index into the grid, because the map joins up east to west. */
    private fun wrap(column: Int, cellsAcross: Int): Int {
        val remainder = column % cellsAcross
        return if (remainder < 0) remainder + cellsAcross else remainder
    }

    /** Clamps a row index to the grid: the map does not join up over the poles. */
    private fun clampRow(row: Int, cellsDown: Int): Int = row.coerceIn(0, cellsDown - 1)
}
