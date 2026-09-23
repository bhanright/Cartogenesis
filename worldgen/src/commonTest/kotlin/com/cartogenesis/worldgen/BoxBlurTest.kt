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
 * Both clauses are against a convolution of the same windows summed from scratch in double for
 * every cell, and both bounds are derived rather than chosen: [BoxBlur.errorBound] for the blur,
 * the same argument for the reference's own few roundings, and for the sign the argument in the
 * second clause. On the float accumulator this replaced, the measured error was 8.6 times the
 * first bound and the field went 865,000 times further below zero than the second allows.
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
    private val sweeps = 2 * passes
    private val longestLineCells = maxOf(cellsAcross, cellsDown)

    @Test
    fun `a wet to dry column blurs to the exact convolution, within the arithmetic's own bound`() {
        val field = wetToDryColumn()
        val largest = field.data.max().toDouble()
        val exact = exactConvolution(field, radius, passes)
        val blurred = FloatField(cellsAcross, cellsDown, field.data.copyOf())
        BoxBlur.apply(blurred, radius = radius, passes = passes)

        // The reference is a double sum too: 2 * radius + 1 additions and a division a cell, the
        // sweep the blur's own share describes when it primes and never slides.
        val referenceShare = BoxBlur.slidingAverageShareOfLargest(radius, lineCells = 0)
        var referenceGrowth = 1.0
        repeat(sweeps) { referenceGrowth *= 1.0 + referenceShare }
        val bound = BoxBlur.errorBound(largest, passes, radius, longestLineCells) +
            largest * (referenceGrowth - 1.0)
        var worst = 0.0
        var worstCell = 0
        for (cell in 0 until cellsAcross * cellsDown) {
            val difference = abs(blurred.data[cell].toDouble() - exact[cell])
            if (difference > worst) {
                worst = difference
                worstCell = cell
            }
        }
        println("BOXBLUR worst difference $worst against the bound $bound")
        assertTrue(
            worst <= bound,
            "the blur stands $worst from the exact convolution at row ${worstCell / cellsAcross} " +
                "column ${worstCell % cellsAcross}, over the bound $bound " +
                "(the field's largest value is $largest)"
        )
    }

    /**
     * A box average of nonnegative numbers is nonnegative, and rounding to float keeps a sign and
     * rounds zero to zero, so nothing below zero here comes from the float. It comes from the
     * double sum: a cell whose exact answer is zero is the wet ground added and taken away again,
     * and what the sum keeps of that can be a residue of either sign. So the clause is a floor,
     * not zero, and the floor is that residue's own bound. One sweep can lower the most negative
     * value by the sliding average's error, at most `d` of the largest magnitude, and its rounding
     * to float can enlarge a negative by [BoxBlur.FLOAT_HALF_ULP] of itself plus the subnormal
     * spacing; over `S` sweeps that is at most `S * (1 + FLOAT_HALF_ULP)^S * (d * A + eta)`, with
     * `A` the largest magnitude any sweep sees.
     */
    @Test
    fun `a field with nothing negative in it blurs to no less than the double sum's residue`() {
        val field = wetToDryColumn()
        assertTrue(field.data.min() >= 0f, "the fixture is nonnegative")
        val largest = field.data.max().toDouble()
        val blurred = FloatField(cellsAcross, cellsDown, field.data.copyOf())
        BoxBlur.apply(blurred, radius = radius, passes = passes)

        val largestAnySweepSees =
            largest + BoxBlur.errorBound(largest, passes, radius, longestLineCells)
        val averageShare = BoxBlur.slidingAverageShareOfLargest(radius, longestLineCells)
        var floatGrowth = 1.0
        repeat(sweeps) { floatGrowth *= 1.0 + BoxBlur.FLOAT_HALF_ULP }
        val floor = -sweeps * floatGrowth *
            (averageShare * largestAnySweepSees + BoxBlur.FLOAT_SUBNORMAL_HALF_SPACING)
        val lowest = blurred.data.min().toDouble()
        println("BOXBLUR lowest value $lowest against the floor $floor")
        assertTrue(
            lowest >= floor,
            "a nonnegative field blurred to $lowest, under the double sum's residue floor $floor"
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
