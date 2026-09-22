package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.math.BoxBlur
import com.cartogenesis.worldgen.model.FloatField
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What the blur is allowed to invent.
 *
 * The fixture is the case the arithmetic fails on and the one the generator actually runs: a
 * column that goes from wet to dry. A box blur slides one running sum down the whole column
 * without ever re-summing it, so every rounding that sum makes is still in it when the sweep
 * reaches the dry end — and the size of those roundings is set by the wet end, which is a thousand
 * times larger than the answer being written. What comes out is a residue that depends on which
 * column it is: a stripe.
 *
 * Both clauses are against a convolution of the same window summed from scratch in double for
 * every cell, and the bound is [BoxBlur.toleranceShareOfLargest], derived from the float's own
 * half-ulp and the number of times a sweep rounds a result into the field. Nothing here is a
 * tolerance chosen until the test went green: on the float accumulator this replaced, the
 * measured error was eleven times the bound and the field went negative where its input was
 * nowhere negative at all.
 *
 * See docs/DESIGN_LEDGER.md, X1b, for what that residue was doing to the polar ice.
 */
class BoxBlurTest {

    /**
     * Tall and narrow, because the defect is a *column* defect and the bound has to be met at the
     * grid heights the generator runs: 2048 rows is the desktop's usual export.
     */
    private val cellsAcross = 96
    private val cellsDown = 2048

    /** Where the wet ground stops and the polar desert begins, in rows. */
    private val dryFromRow = 1600

    private val radius = 16
    private val passes = 2

    @Test
    fun `a wet to dry column blurs to the exact convolution, within the float's own rounding`() {
        val field = wetToDryColumn()
        val largest = field.data.max()
        val exact = exactConvolution(field, radius, passes)
        val blurred = FloatField(cellsAcross, cellsDown, field.data.copyOf())
        BoxBlur.apply(blurred, radius = radius, passes = passes)

        val bound = BoxBlur.toleranceShareOfLargest(passes) * largest
        var worst = 0.0
        var worstCell = 0
        for (cell in 0 until cellsAcross * cellsDown) {
            val difference = abs(blurred.data[cell].toDouble() - exact[cell])
            if (difference > worst) {
                worst = difference
                worstCell = cell
            }
        }
        assertTrue(
            worst <= bound,
            "the blur stands $worst from the exact convolution at row ${worstCell / cellsAcross} " +
                "column ${worstCell % cellsAcross}, over the bound $bound " +
                "(${BoxBlur.toleranceShareOfLargest(passes)} of the field's largest value $largest)"
        )
    }

    @Test
    fun `a field with nothing negative in it does not blur negative`() {
        val field = wetToDryColumn()
        val largest = field.data.max()
        assertTrue(field.data.min() >= 0f, "the fixture is nonnegative")
        val blurred = FloatField(cellsAcross, cellsDown, field.data.copyOf())
        BoxBlur.apply(blurred, radius = radius, passes = passes)

        // A box average of nonnegative numbers is nonnegative, so anything below zero here is
        // arithmetic and not the field. The bound is the same one, because a result rounded once
        // may land a half-ulp either side of an exact zero.
        val floor = -BoxBlur.toleranceShareOfLargest(passes) * largest
        val lowest = blurred.data.min()
        assertTrue(
            lowest >= floor,
            "a nonnegative field blurred to $lowest, under the rounding floor $floor"
        )
    }

    /**
     * A wet band over a dry one, with per-column variation in the wet part only.
     *
     * The dry rows are exactly zero rather than merely small, so that anything a column reads
     * there after the blur came out of the arithmetic: the exact answer below
     * [dryFromRow] + `radius * passes` is exactly zero too.
     */
    private fun wetToDryColumn(): FloatField {
        val field = FloatField(cellsAcross, cellsDown)
        var state = 0x9E3779B9u
        for (row in 0 until dryFromRow) {
            for (column in 0 until cellsAcross) {
                // A plain xorshift, so the fixture is the same number on every platform.
                state = state xor (state shl 13)
                state = state xor (state shr 17)
                state = state xor (state shl 5)
                val jitter = (state and 0xFFFFu).toFloat() / 0xFFFF
                field.data[row * cellsAcross + column] = 0.015f * (1f + 0.4f * jitter)
            }
        }
        return field
    }

    /** The same separable window, summed from scratch in double for every output cell. */
    private fun exactConvolution(field: FloatField, radius: Int, passes: Int): DoubleArray {
        val data = DoubleArray(field.data.size) { field.data[it].toDouble() }
        val scratch = DoubleArray(field.data.size)
        val windowCells = 2.0 * radius + 1.0
        repeat(passes) {
            for (row in 0 until cellsDown) {
                val rowStart = row * cellsAcross
                for (column in 0 until cellsAcross) {
                    var sum = 0.0
                    for (offset in -radius..radius) {
                        var sampled = (column + offset) % cellsAcross
                        if (sampled < 0) sampled += cellsAcross
                        sum += data[rowStart + sampled]
                    }
                    scratch[rowStart + column] = sum / windowCells
                }
            }
            scratch.copyInto(data)
            for (column in 0 until cellsAcross) {
                for (row in 0 until cellsDown) {
                    var sum = 0.0
                    for (offset in -radius..radius) {
                        val sampled = (row + offset).coerceIn(0, cellsDown - 1)
                        sum += data[sampled * cellsAcross + column]
                    }
                    scratch[row * cellsAcross + column] = sum / windowCells
                }
            }
            scratch.copyInto(data)
        }
        return data
    }
}
