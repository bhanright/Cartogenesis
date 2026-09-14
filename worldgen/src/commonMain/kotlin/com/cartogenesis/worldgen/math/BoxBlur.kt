package com.cartogenesis.worldgen.math

import com.cartogenesis.worldgen.model.FloatField

/** Separable box blur with running sums: O(width * height) per pass regardless of radius. */
object BoxBlur {

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
        val inverseWindowCells = 1f / windowCells

        for (row in 0 until cellsDown) {
            val rowStart = row * cellsAcross
            // A running sum, primed on the window centred at column zero and then slid one cell at
            // a time: what leaves the window on the left is subtracted, what enters on the right
            // is added, so the cost per cell does not grow with the radius.
            var sum = 0f
            for (offset in -radius..radius) {
                sum += data[rowStart + wrap(offset, cellsAcross)]
            }
            for (column in 0 until cellsAcross) {
                scratch[rowStart + column] = sum * inverseWindowCells
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
        val inverseWindowCells = 1f / windowCells

        for (column in 0 until cellsAcross) {
            var sum = 0f
            for (offset in -radius..radius) {
                sum += data[clampRow(offset, cellsDown) * cellsAcross + column]
            }
            for (row in 0 until cellsDown) {
                scratch[row * cellsAcross + column] = sum * inverseWindowCells
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
