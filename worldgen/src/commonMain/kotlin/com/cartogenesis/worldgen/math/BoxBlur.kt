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
 * Measured on the author's seed 969495 at 2048 with the rain blur's own radius of 16: the cold
 * half's precipitation is *exactly zero* over the polar rows before the blur and came out of it
 * reading between -0.0060 and +0.0050 mm a year, sign alternating column by column, against a snow
 * balance there of 0.008 to 0.019 mm a year and a smallest meaningful balance of 0.01. The residue
 * was over half of the frozen mask's column flips at the pole. See docs/DESIGN_LEDGER.md, X1b, for
 * the table and for what it moved.
 *
 * A double accumulator is the repair. The error a sliding sum carries after `n` updates is a walk
 * of `n` roundings, each at most half an ulp of the sum, so it stands at about `sqrt(n)` times
 * `2^-24` of the largest value in float and `sqrt(n)` times `2^-53` of it in double — five hundred
 * million times smaller, and so far under the last bit of the float it is stored in that it cannot
 * reach the result at any grid this generator runs. Re-summing the window every N rows was the
 * alternative and was declined: it costs `(2 * radius + 1) / N` extra additions per cell, where a
 * wider accumulator costs nothing measurable, the sum being one scalar either way.
 */
object BoxBlur {

    /**
     * Half an ulp of a float, `2^-24` — the most one rounding of a float result can be out, as a
     * share of that result.
     *
     * Named here because it is what the accuracy contract below is stated in, and what
     * `BoxBlurTest` derives its bound from rather than choosing a tolerance.
     */
    const val FLOAT_HALF_ULP = 5.9604645e-8f

    /**
     * How far a blurred cell may stand from the exact convolution of the same window, as a share
     * of the largest magnitude anywhere in the field.
     *
     * Two sweeps a pass, each of which rounds its result into the float field once, and nothing
     * else: with the sum kept in a double, the sum's own drift over a whole column is
     * `height * 2^-53` of the largest value, which is under a thousandth of one float rounding at
     * any grid this generator runs. So the bound is the roundings, `2 * passes * FLOAT_HALF_ULP`,
     * and it does not grow with the radius or with the grid.
     *
     * Stated as a share of the field's largest value and not of the cell's own, because a box
     * window mixes the whole window into every cell and a cell whose exact answer is zero still
     * carries the arithmetic of the wet ground the window reached over.
     */
    fun toleranceShareOfLargest(passes: Int): Float = 2f * passes * FLOAT_HALF_ULP

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
