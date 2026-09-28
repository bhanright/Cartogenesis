package com.cartogenesis.worldgen.math

import kotlin.math.sqrt

/**
 * The distance from source cells to every cell reachable from them through permitted cells, on
 * the ground: the eikonal equation `|∇d| = 1` solved by fast marching (Sethian 1996, *PNAS* 93,
 * 1591-1595), columns wrapping and rows not.
 *
 * Fast marching and not a walk over the grid's neighbors, which would measure an octagonal metric
 * and draw its facets into whatever reads the distance (see [DistanceTransform]). Each cell's
 * distance is the upwind solution of `((d - a)/Δx)² + ((d - b)/Δy)² = 1` from its settled
 * neighbors across and along, so a front from a straight source line at any bearing is carried
 * exactly, the errors being first order and confined to where a front turns a corner or spreads
 * from a point. A path steps only between cells sharing a side, so two permitted cells touching at
 * a corner alone are not joined: a strip of forbidden cells a single cell wide, even on a diagonal,
 * keeps its two sides apart.
 */
object FastMarchingDistance {

    /** The distance of a cell no path reaches. */
    const val UNREACHED = Float.POSITIVE_INFINITY

    /**
     * Fills [distance] with each permitted cell's distance, in the unit of [cellWidth] and
     * [cellHeight], from the nearest source, [UNREACHED] where no path through permitted cells
     * reaches it. [distance] comes in holding zero at the sources and [UNREACHED] elsewhere;
     * [permitted] says which cells a path may cross, and a source's own entry is not read.
     */
    fun run(cellsAcross: Int, cellsDown: Int, distance: FloatArray, permitted: BooleanArray, cellWidth: Double, cellHeight: Double) {
        val cells = cellsAcross * cellsDown
        val settled = BooleanArray(cells) { distance[it] == 0f }
        val heap = LongMinHeap()
        fun offer(cell: Int) {
            val tentative = upwind(cell, cellsAcross, cellsDown, distance, settled, cellWidth, cellHeight)
            if (tentative < distance[cell]) {
                distance[cell] = tentative
                // A non-negative float's bits order as the float does, so the distance leads the key.
                heap.push((tentative.toRawBits().toLong() shl 32) or cell.toLong())
            }
        }
        fun offerNeighbors(cell: Int) {
            val row = cell / cellsAcross
            val column = cell - row * cellsAcross
            val east = row * cellsAcross + if (column + 1 == cellsAcross) 0 else column + 1
            val west = row * cellsAcross + if (column == 0) cellsAcross - 1 else column - 1
            if (permitted[east] && !settled[east]) offer(east)
            if (permitted[west] && !settled[west]) offer(west)
            if (row > 0 && permitted[cell - cellsAcross] && !settled[cell - cellsAcross]) offer(cell - cellsAcross)
            if (row + 1 < cellsDown && permitted[cell + cellsAcross] && !settled[cell + cellsAcross]) offer(cell + cellsAcross)
        }
        for (cell in 0 until cells) if (settled[cell]) offerNeighbors(cell)
        while (!heap.isEmpty()) {
            val entry = heap.pop()
            val cell = (entry and CELL_MASK).toInt()
            if (settled[cell]) continue
            settled[cell] = true
            offerNeighbors(cell)
        }
    }

    /**
     * The upwind solution at [cell] from its settled neighbors: `a` the nearer across, `b` the
     * nearer along the column; the two-sided solution where both are settled and it comes out no
     * nearer than either, the one-sided one otherwise.
     */
    private fun upwind(
        cell: Int,
        cellsAcross: Int,
        cellsDown: Int,
        distance: FloatArray,
        settled: BooleanArray,
        cellWidth: Double,
        cellHeight: Double
    ): Float {
        val row = cell / cellsAcross
        val column = cell - row * cellsAcross
        val east = row * cellsAcross + if (column + 1 == cellsAcross) 0 else column + 1
        val west = row * cellsAcross + if (column == 0) cellsAcross - 1 else column - 1
        var across = Double.POSITIVE_INFINITY
        if (settled[east]) across = distance[east].toDouble()
        if (settled[west]) across = minOf(across, distance[west].toDouble())
        var along = Double.POSITIVE_INFINITY
        if (row > 0 && settled[cell - cellsAcross]) along = distance[cell - cellsAcross].toDouble()
        if (row + 1 < cellsDown && settled[cell + cellsAcross]) along = minOf(along, distance[cell + cellsAcross].toDouble())
        val oneSided = minOf(across + cellWidth, along + cellHeight)
        if (across == Double.POSITIVE_INFINITY || along == Double.POSITIVE_INFINITY) return oneSided.toFloat()
        val widthSquared = cellWidth * cellWidth
        val heightSquared = cellHeight * cellHeight
        val discriminant = widthSquared + heightSquared - (across - along) * (across - along)
        if (discriminant < 0.0) return oneSided.toFloat()
        val twoSided = (across * heightSquared + along * widthSquared + cellWidth * cellHeight * sqrt(discriminant)) /
            (widthSquared + heightSquared)
        return (if (twoSided >= maxOf(across, along)) twoSided else oneSided).toFloat()
    }

    /** The low half of a heap entry, where the cell's index is kept. */
    private const val CELL_MASK = 0xFFFF_FFFFL
}
