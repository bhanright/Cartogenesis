package com.cartogenesis.worldgen.math

import kotlin.math.sqrt

/**
 * How far a step to each of a cell's eight neighbours goes on the ground, in cell widths.
 *
 * A cell of this map is not square: on a grid as many cells tall as wide over a world twice as
 * wide as it is tall, a row is half as tall as a column is wide. So a step east or west is one cell
 * width, a step north or south is [cellHeightInCellWidths] of one, and a diagonal step is the
 * hypotenuse of the two. Every slope a stage takes to a neighbour divides by one of these, and every
 * length it adds up along a path is a sum of them; counted as one, one and the square root of two,
 * which is a square cell's, a north- or south-facing slope reads at half its gradient and a path
 * running north is counted twice as long as the ground it crosses.
 *
 * The unit stays the cell width, so a slope east-west is what it always was and a stage's
 * coefficients, calibrated in cell widths, keep their meaning; only the ratio between the axes is
 * the ground's.
 */
class GroundSteps(
    /** `cellHeightKm / cellWidthKm`: a half on this project's square grids. */
    val cellHeightInCellWidths: Double
) {
    /** A step along a row, to the east or the west. */
    val eastWest: Float = 1f

    /** A step along a column, to the north or the south. */
    val northSouth: Float = cellHeightInCellWidths.toFloat()

    /** A step to a diagonal neighbour. */
    val diagonal: Float = sqrt(1.0 + cellHeightInCellWidths * cellHeightInCellWidths).toFloat()

    /** The length of a step of [columnStep] columns and [rowStep] rows, each -1, 0 or 1. */
    fun of(columnStep: Int, rowStep: Int): Float = when {
        columnStep != 0 && rowStep != 0 -> diagonal
        rowStep != 0 -> northSouth
        else -> eastWest
    }

    /**
     * The length of the step between two neighbouring cells of a grid [width] cells across,
     * whichever way round the east-west seam it runs.
     */
    fun between(from: Int, to: Int, width: Int): Float {
        val sameRow = from / width == to / width
        val sameColumn = from % width == to % width
        return when {
            !sameRow && !sameColumn -> diagonal
            !sameRow -> northSouth
            else -> eastWest
        }
    }

    /** The length of a step of [columnStep] columns and [rowStep] rows of any size, in cell widths. */
    fun lengthOf(columnStep: Double, rowStep: Double): Double {
        val downCellWidths = rowStep * cellHeightInCellWidths
        return sqrt(columnStep * columnStep + downCellWidths * downCellWidths)
    }
}
