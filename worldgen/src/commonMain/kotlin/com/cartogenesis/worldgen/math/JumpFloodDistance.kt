package com.cartogenesis.worldgen.math

import com.cartogenesis.worldgen.concurrent.parallelChunks
import kotlin.math.sqrt

/**
 * Euclidean distance to the nearest source cell, with nearest-source label propagation, by jump
 * flooding. The X axis wraps; the Y axis does not, exactly as [DistanceTransform] behaves.
 *
 * This is the drop-in replacement for the chamfer transform wherever the *distance itself* is
 * read — the marine blend's distance from water, the shelf's distance from land, the boundary
 * profiles' distance from a plate edge. A chamfer transform can only step along the eight
 * directions a square grid offers, so what it measures is an octagonal metric: it agrees with
 * Euclid along the axes and along the diagonals and overstates it by up to 8.2% at the bearings
 * in between (22.5 degrees, where a straight line of length 1 costs `(1 - t) + sqrt(2) * t`
 * to walk). Every contour of such a field is therefore an octagon rather than a circle, and every
 * feature whose width is read off that field — a plateau's rim, the shelf break — inherits the
 * facets. They are what `TODO.md` calls the octagonal streaks.
 *
 * Jump flooding propagates *the source's coordinates* rather than an accumulated path length, so
 * the distance it reports is `sqrt(dx^2 + dy^2)` to a real cell: no metric error at all, only the
 * risk that a cell never hears about the source that is actually nearest. That risk is what the
 * halving step schedule addresses — a cell looks at its eight neighbours `step` cells away, then
 * `step / 2`, and so on — and the schedule here brackets the standard one with a step-1 pass at
 * each end (the "1+JFA+1" variant), which repairs the thin diagonal wedges plain JFA is known to
 * leave. `JumpFloodDistanceTest` checks the result against a brute-force nearest-source search on
 * randomly seeded grids and finds it exact, not merely close.
 *
 * Deterministic and platform-independent: the whole search is integer arithmetic on squared
 * distances, ties go to the lower source index, and each pass reads the previous buffer and writes
 * its own cell, so splitting the rows across cores cannot change the answer.
 */
object JumpFloodDistance {

    /** Same sentinel [DistanceTransform] uses, so a call site swaps one for the other unchanged. */
    const val INFINITE = 1e18f

    /** No source has claimed this cell yet, in the buffers the flood passes between each other. */
    private const val NO_SOURCE = -1

    /**
     * @param dist pre-seeded with 0 at source cells and [INFINITE] elsewhere; overwritten with
     *   the distance to the nearest source, in cells. Cells with no source anywhere keep
     *   [INFINITE].
     * @param label pre-seeded with a source id at source cells and -1 elsewhere; overwritten with
     *   the id of the nearest source. The id is whatever the caller seeded — a cell index, a plate
     *   id — and is carried, not recomputed.
     */
    fun run(width: Int, height: Int, dist: FloatArray, label: IntArray) {
        val cellCount = width * height
        if (cellCount == 0) return

        // The labels as the caller seeded them. The flood itself moves cell indices, because it
        // needs the source's coordinates to measure to; the label is looked up at the end.
        val seedLabel = label.copyOf()

        var nearestSource = IntArray(cellCount) { if (dist[it] == 0f) it else NO_SOURCE }
        var anySource = false
        for (cell in 0 until cellCount) {
            if (nearestSource[cell] >= 0) { anySource = true; break }
        }
        if (!anySource) return

        var nextNearestSource = IntArray(cellCount)
        for (stepCells in schedule(width, height)) {
            pass(width, height, stepCells, nearestSource, nextNearestSource)
            val previous = nearestSource
            nearestSource = nextNearestSource
            nextNearestSource = previous
        }

        for (cell in 0 until cellCount) {
            val source = nearestSource[cell]
            if (source < 0) continue
            val squared = squaredDistance(width, cell % width, cell / width, source)
            dist[cell] = sqrt(squared.toDouble()).toFloat()
            label[cell] = seedLabel[source]
        }
    }

    /**
     * A step-1 pass, then the halving powers of two, then a second step-1 pass, in cells.
     *
     * The powers start at the first one at or above half the larger side, which is what makes the
     * flood reach across the whole grid; the two step-1 passes at the ends are cheap insurance
     * against the small errors the bare schedule leaves.
     */
    private fun schedule(width: Int, height: Int): IntArray {
        var largestStepCells = 1
        while (largestStepCells < maxOf(width, height) / 2) {
            largestStepCells = largestStepCells shl 1
        }

        // The two bracketing step-1 passes, plus one per halving from the largest step down to 1.
        var passCount = 2
        var stepCells = largestStepCells
        while (stepCells >= 1) { passCount++; stepCells = stepCells shr 1 }

        val steps = IntArray(passCount)
        var next = 0
        steps[next++] = 1
        stepCells = largestStepCells
        while (stepCells >= 1) { steps[next++] = stepCells; stepCells = stepCells shr 1 }
        steps[next] = 1
        return steps
    }

    /**
     * One flood pass: every cell adopts the nearest of its own source and the sources held by the
     * eight cells [stepCells] away, writing the winner into [nextNearestSource].
     */
    private fun pass(
        width: Int,
        height: Int,
        stepCells: Int,
        nearestSource: IntArray,
        nextNearestSource: IntArray
    ) {
        // Reads `nearestSource`, writes only its own cell of `nextNearestSource`, so the rows split
        // cleanly and the result does not depend on how they were split.
        parallelChunks(0, height) { startRow, endRow ->
            for (row in startRow until endRow) {
                for (column in 0 until width) {
                    val cell = row * width + column
                    var best = nearestSource[cell]
                    var bestSquared =
                        if (best < 0) Int.MAX_VALUE
                        else squaredDistance(width, column, row, best)
                    for (rowStep in -1..1) {
                        val neighbourRow = row + rowStep * stepCells
                        if (neighbourRow < 0 || neighbourRow >= height) continue
                        val neighbourRowStart = neighbourRow * width
                        for (columnStep in -1..1) {
                            if (columnStep == 0 && rowStep == 0) continue
                            var neighbourColumn = (column + columnStep * stepCells) % width
                            if (neighbourColumn < 0) neighbourColumn += width
                            val candidate = nearestSource[neighbourRowStart + neighbourColumn]
                            if (candidate < 0) continue
                            val candidateSquared =
                                squaredDistance(width, column, row, candidate)
                            // Ties to the lower cell index: two sources exactly as far away is
                            // common on a grid, and which one wins decides the label.
                            if (candidateSquared < bestSquared ||
                                (candidateSquared == bestSquared && candidate < best)
                            ) {
                                bestSquared = candidateSquared
                                best = candidate
                            }
                        }
                    }
                    nextNearestSource[cell] = best
                }
            }
        }
    }

    /**
     * Squared distance in cells from ([column], [row]) to cell [source], taking the short way round
     * in x. Squared, and so an exact integer: the comparisons the flood makes never need the root.
     */
    private fun squaredDistance(width: Int, column: Int, row: Int, source: Int): Int {
        var acrossCells = column - source % width
        if (acrossCells < 0) acrossCells = -acrossCells
        if (acrossCells > width - acrossCells) acrossCells = width - acrossCells
        val downCells = row - source / width
        return acrossCells * acrossCells + downCells * downCells
    }
}
