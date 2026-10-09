package com.cartogenesis.worldgen

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Whether a field carried up from a coarse grid still shows the coarse grid's period: the design's
 * phase statistic (notes of the atmosphere's design, section 9, clause 11).
 *
 * Every ground cell sits at some phase within the coarse cell it falls in. The absolute second
 * difference, down the map or along it, is averaged at each phase; an interpolant that treats every
 * phase alike gives the same mean at each, and one with a node, a kink or a seam at the coarse
 * cell's edge gives one phase far more curvature than the rest. The figure is the largest phase mean
 * over the mean of all of them, against a bar of one plus three of the phase means' standard errors.
 * Neighboring cells are correlated, so the standard error is a floor and not a test of accuracy:
 * this detects the grid's trace and nothing else.
 */
internal object CoarsePeriod {

    class Reading(val ratio: Double, val bar: Double) {
        val passes: Boolean get() = ratio <= bar
        override fun toString() = "x%.3f (bar %.3f)".format(ratio, bar)
    }

    /**
     * The statistic down the map ([alongRows] false) or along it, for [field] on a ground grid of
     * [columns] by [rows] carried up from a coarse grid of [coarseLines] lines in that direction.
     * Rows within [polarRowsSkipped] of a pole are left out.
     */
    fun reading(field: FloatArray, columns: Int, rows: Int, coarseLines: Int, alongRows: Boolean, polarRowsSkipped: Int = 2): Reading {
        val groundLines = if (alongRows) columns else rows
        val phases = maxOf(2, groundLines / coarseLines)
        val sums = DoubleArray(phases)
        val squares = DoubleArray(phases)
        val counts = LongArray(phases)
        for (row in polarRowsSkipped until rows - polarRowsSkipped) {
            for (column in 0 until columns) {
                val position = if (alongRows) column else row
                val curvature = if (alongRows) {
                    val west = field[row * columns + (column + columns - 1) % columns]
                    val east = field[row * columns + (column + 1) % columns]
                    abs(west - 2 * field[row * columns + column] + east).toDouble()
                } else {
                    if (row == 0 || row == rows - 1) continue
                    abs(field[(row - 1) * columns + column] - 2 * field[row * columns + column] + field[(row + 1) * columns + column]).toDouble()
                }
                val coarsePosition = (position + 0.5) * coarseLines / groundLines
                val phase = floor((coarsePosition - floor(coarsePosition)) * phases).toInt().coerceIn(0, phases - 1)
                sums[phase] += curvature
                squares[phase] += curvature * curvature
                counts[phase]++
            }
        }
        val means = DoubleArray(phases) { sums[it] / counts[it] }
        val overall = means.average()
        val worstError = (0 until phases).maxOf { phase ->
            val variance = squares[phase] / counts[phase] - means[phase] * means[phase]
            sqrt(maxOf(variance, 0.0) / counts[phase])
        }
        return Reading(means.max() / overall, 1.0 + 3.0 * worstError / overall)
    }
}
