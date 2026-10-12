package com.cartogenesis.worldgen

import kotlin.math.abs
import kotlin.math.ceil
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
 *
 * The standard error is taken over lines, not cells: the rows when the curvature is read down the
 * map, the columns when along it, each line's mean taken over the lines within a coarse period
 * either side of it, so the geography's own change across the map divides out. A line holds one
 * phase throughout, and the cells along it are nowhere near independent on a world's fields, whose
 * fronts and coasts run for thousands of kilometers; counted cell by cell, a few rows' features
 * made a phase of their own and failed the boundary layer's pressure by a part in a thousand
 * (docs/DESIGN_LEDGER.md, A1-4). Lines are still correlated with their neighbors, so the error
 * remains a floor and not a test of accuracy: this detects the grid's trace and nothing else.
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
        // Each line's mean curvature: down the map a line is a row and the curvature is read across
        // rows; along the map a line is a column and the curvature is read along the rows.
        val lineSums = DoubleArray(groundLines)
        val lineCounts = LongArray(groundLines)
        for (row in polarRowsSkipped until rows - polarRowsSkipped) {
            if (!alongRows && (row == 0 || row == rows - 1)) continue
            for (column in 0 until columns) {
                val curvature = if (alongRows) {
                    val west = field[row * columns + (column + columns - 1) % columns]
                    val east = field[row * columns + (column + 1) % columns]
                    abs(west - 2 * field[row * columns + column] + east).toDouble()
                } else {
                    abs(field[(row - 1) * columns + column] - 2 * field[row * columns + column] + field[(row + 1) * columns + column]).toDouble()
                }
                val line = if (alongRows) column else row
                lineSums[line] += curvature
                lineCounts[line]++
            }
        }
        val lineMeans = DoubleArray(groundLines) { if (lineCounts[it] == 0L) Double.NaN else lineSums[it] / lineCounts[it] }
        // Each line against the lines within a coarse period either side of it, which hold every
        // phase alike: what the geography does over a coarse cell's width divides out, and what
        // the coarse grid does from one phase to the next is left.
        val reach = ceil(groundLines.toDouble() / coarseLines).toInt()
        val sums = DoubleArray(phases)
        val squares = DoubleArray(phases)
        val counts = LongArray(phases)
        for (line in 0 until groundLines) {
            if (lineMeans[line].isNaN()) continue
            var local = 0.0
            var localCount = 0
            for (other in line - reach..line + reach) {
                val wrapped = if (alongRows) Math.floorMod(other, groundLines) else other
                if (wrapped < 0 || wrapped >= groundLines || lineMeans[wrapped].isNaN()) continue
                local += lineMeans[wrapped]
                localCount++
            }
            if (local <= 0.0) continue
            val lineMean = lineMeans[line] / (local / localCount)
            val coarsePosition = (line + 0.5) * coarseLines / groundLines
            val phase = floor((coarsePosition - floor(coarsePosition)) * phases).toInt().coerceIn(0, phases - 1)
            sums[phase] += lineMean
            squares[phase] += lineMean * lineMean
            counts[phase]++
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
