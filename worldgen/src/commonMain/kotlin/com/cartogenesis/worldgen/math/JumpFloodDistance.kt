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
 * A grid's cells need not be square, and on this project's they are not: an equirectangular map is
 * twice as wide as it is tall, so at 512 by 512 a cell is 23.4 km across and 11.7 km down. Distance
 * measured in cells is therefore not distance on the ground, and a caller that wants the ground's
 * answer passes [run]'s `cellHeightInCellWidths`, which scales the y term of every comparison the
 * flood makes. At 1 the answer is in cells, as it always was.
 *
 * Deterministic and platform-independent: the whole search is comparisons of squared distances,
 * which are exact in a double for any grid this program draws and for a row scale of 1 are exactly
 * the integers they used to be; ties go to the lower source index; and each pass reads the previous
 * buffer and writes its own cell, so splitting the rows across cores cannot change the answer.
 */
object JumpFloodDistance {

    /** Same sentinel [DistanceTransform] uses, so a call site swaps one for the other unchanged. */
    const val INFINITE = 1e18f

    /** No source has claimed this cell yet, in the buffers the flood passes between each other. */
    private const val NO_SOURCE = -1

    /**
     * @param dist pre-seeded with 0 at source cells and [INFINITE] elsewhere; overwritten with the
     *   distance to the nearest source, in *cell widths*. Cells with no source anywhere keep
     *   [INFINITE]. Multiplying by `WorldGenConfig.cellWidthKm` turns the answer into kilometres,
     *   whatever the row scale.
     * @param label pre-seeded with a source id at source cells and -1 elsewhere; overwritten with
     *   the id of the nearest source. The id is whatever the caller seeded — a cell index, a plate
     *   id — and is carried, not recomputed.
     * @param cost what each cell is really looking for the nearest of, given a candidate source
     *   and the squared distance to it in cell widths; null, the default, is the distance itself
     *   and the flood is the plain Euclidean one described above. A cost that *adds* something to
     *   the source — a height the source stands at, a head start it was given — makes this an
     *   additively weighted flood, whose regions are no longer Voronoi cells and whose winner at a
     *   cell need not be the source nearest it. `dist` and `label` still come back measured to and
     *   named after the winner. Such a flood is not exact the way the plain one is: the schedule
     *   can miss a source whose cost only just wins, and what that leaves is a cell whose answer is
     *   a little too large rather than one that is wrong about which source exists. See
     *   `IceSheet.marginDistanceKm` for the case it was added for.
     * @param cellHeightInCellWidths how tall a row is as a fraction of how wide a column is —
     *   `cellHeightKm / cellWidthKm`, a half on this project's grids. At 1 the flood measures in
     *   cells and every figure is exactly what it was before the parameter existed; below 1 a step
     *   down the map counts for less than a step across it, which is what makes the distance a
     *   length on the ground rather than a count of cells.
     */
    fun run(
        width: Int,
        height: Int,
        dist: FloatArray,
        label: IntArray,
        cellHeightInCellWidths: Double = 1.0,
        cost: ((Int, Double) -> Double)? = null
    ) {
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
            pass(
                width, height, stepCells, cellHeightInCellWidths, cost,
                nearestSource, nextNearestSource
            )
            val previous = nearestSource
            nearestSource = nextNearestSource
            nextNearestSource = previous
        }

        for (cell in 0 until cellCount) {
            val source = nearestSource[cell]
            if (source < 0) continue
            val squared =
                squaredDistance(width, cell % width, cell / width, source, cellHeightInCellWidths)
            dist[cell] = sqrt(squared).toFloat()
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
        cellHeightInCellWidths: Double,
        cost: ((Int, Double) -> Double)?,
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
                        if (best < 0) Double.MAX_VALUE
                        else keyOf(
                            width, column, row, best, cellHeightInCellWidths, cost
                        )
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
                            val candidateSquared = keyOf(
                                width, column, row, candidate, cellHeightInCellWidths, cost
                            )
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
     * What one candidate is worth at a cell: the squared distance to it, or what [cost] makes of
     * that, so the two kinds of flood differ in one expression rather than in two copies of the
     * pass.
     */
    private inline fun keyOf(
        width: Int,
        column: Int,
        row: Int,
        source: Int,
        cellHeightInCellWidths: Double,
        noinline cost: ((Int, Double) -> Double)?
    ): Double {
        val squared = squaredDistance(width, column, row, source, cellHeightInCellWidths)
        return if (cost == null) squared else cost(source, squared)
    }

    /**
     * Squared distance in cell widths from ([column], [row]) to cell [source], taking the short way
     * round in x and counting a row as [cellHeightInCellWidths] of a column.
     *
     * Squared, because the comparisons the flood makes never need the root. Both terms are whole
     * numbers of cells before the scale is applied and stay well inside a double's exact range on
     * any grid this program draws, so at a scale of 1 this is the integer arithmetic it replaced,
     * to the bit.
     */
    private fun squaredDistance(
        width: Int,
        column: Int,
        row: Int,
        source: Int,
        cellHeightInCellWidths: Double
    ): Double {
        var acrossCells = column - source % width
        if (acrossCells < 0) acrossCells = -acrossCells
        if (acrossCells > width - acrossCells) acrossCells = width - acrossCells
        val downCellWidths = (row - source / width) * cellHeightInCellWidths
        return acrossCells.toDouble() * acrossCells + downCellWidths * downCellWidths
    }
}
