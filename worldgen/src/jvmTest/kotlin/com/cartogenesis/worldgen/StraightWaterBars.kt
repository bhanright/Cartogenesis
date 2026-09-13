package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.LakeResult
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Standing water shaped like a ruled line: the artefact F18 was dispatched to remove, measured.
 *
 * William saw one on seed 298405 at 1024 and called it "this diagonal rectangle section of river"
 * — 53 cells laid along one diagonal with square ends, in a straight-walled trench. F15 diagnosed
 * it and left it: the trench is ordinary stream-power incision along a reach the plain
 * steepest-of-eight rule ran dead straight over an apron smooth at the cell scale, and the reach
 * then ponded behind its own lip. Nothing else about a lake tells the two apart — it is the right
 * size, in the right place, at the right level — so the shape is the measurement.
 *
 * A body counts as a bar when **every one of its cells lies within [MAX_OFFSET_CELLS] of one
 * straight line** and it is at least [MIN_BAR_CELLS] cells **long**. Both halves are needed. A
 * short stub two cells wide satisfies the first on its own — a five-cell puddle is within a cell of
 * plenty of lines — and length without straightness is just a lake in a valley, which is what most
 * lakes are.
 *
 * "Within a cell and a bit of *one* line" is asked as it reads: does such a line exist? That is the
 * narrowest strip of the plane holding every cell, and the narrowest strip is bounded by an edge of
 * the convex hull (the rotating-calipers result), so it is found exactly rather than estimated —
 * the least-squares axis through the cells is one particular line and answers a weaker question.
 * Half the strip's width is the furthest any cell sits from the line down its middle.
 */
internal object StraightWaterBars {

    /**
     * How far a cell may lie from the line and still count as on it.
     *
     * F15's own figure, from the body William pointed at: its 53 cells all sit within 1.2 cells of
     * one line. A little over one cell rather than exactly one, because a bar laid on a diagonal is
     * a staircase and the staircase itself is half a cell wide either side of its own line.
     */
    const val MAX_OFFSET_CELLS = 1.2f

    /**
     * The shortest run that reads as ruled rather than as a lake that happens to be narrow.
     *
     * Twenty cells is F15's census bar, and on this map it is a real distance: a cell is 23 km at
     * 512 down to 6 km at 2048, so twenty of them is 120 to 470 km of water in a line straight to
     * within a cell. Earth has nothing of the sort outside a rift, and a rift lake is neither
     * straight to a cell nor uniform in width.
     */
    const val MIN_BAR_CELLS = 20

    /** One body of standing water, with the two figures that decide whether it is ruled. */
    class WaterBody(
        val lakeId: Int,
        val cellCount: Int,
        /** The furthest any of its cells lies from the best line through it, in cells. */
        val offsetFromTheLine: Float,
        /** How far it runs along that line, in cells, both ends included. */
        val lengthAlongTheLine: Float,
        val centreX: Float,
        val centreY: Float
    ) {
        val isRuledBar: Boolean
            get() = offsetFromTheLine <= MAX_OFFSET_CELLS && lengthAlongTheLine >= MIN_BAR_CELLS

        override fun toString(): String =
            "lake $lakeId: $cellCount cells at (${centreX.toInt()},${centreY.toInt()}), " +
                "%.2f cells off a line it runs %.1f cells along".format(
                    offsetFromTheLine, lengthAlongTheLine
                )
    }

    /** Every lake body on the world, measured. Cells are grouped by lake id, which is per basin. */
    fun bodiesOf(world: WorldMap): List<WaterBody> {
        val cellsAcross = world.width
        val lakeId = world.rivers.lakes.lakeId
        val cellsByLake = HashMap<Int, MutableList<Int>>()
        for (cell in lakeId.indices) {
            val id = lakeId[cell]
            if (id == LakeResult.NO_LAKE) continue
            cellsByLake.getOrPut(id) { ArrayList() }.add(cell)
        }
        return cellsByLake.entries.sortedBy { it.key }.map { (id, cells) ->
            measure(id, cells, cellsAcross)
        }
    }

    /** The bars on a world, worst first, which is what a failing guard prints. */
    fun ruledBarsOf(world: WorldMap): List<WaterBody> =
        bodiesOf(world).filter { it.isRuledBar }.sortedByDescending { it.lengthAlongTheLine }

    private fun measure(lakeId: Int, cells: List<Int>, cellsAcross: Int): WaterBody {
        // Unwrapped against the first cell, because the world is a cylinder and a body sitting on
        // the seam would otherwise measure as two clumps a map's width apart.
        val originColumn = cells[0] % cellsAcross
        val xs = FloatArray(cells.size)
        val ys = FloatArray(cells.size)
        cells.forEachIndexed { k, cell ->
            var column = cell % cellsAcross
            if (column - originColumn > cellsAcross / 2) column -= cellsAcross
            if (originColumn - column > cellsAcross / 2) column += cellsAcross
            xs[k] = column.toFloat()
            ys[k] = (cell / cellsAcross).toFloat()
        }

        val hull = convexHull(xs, ys)
        var narrowestStrip = Float.MAX_VALUE
        var lengthAlongTheStrip = 1f
        for (corner in hull.indices) {
            val (edgeX, edgeY) = hull[corner]
            val (nextX, nextY) = hull[(corner + 1) % hull.size]
            var alongX = nextX - edgeX
            var alongY = nextY - edgeY
            val edgeLength = hypot(alongX.toDouble(), alongY.toDouble()).toFloat()
            if (edgeLength < 1e-9f) continue
            alongX /= edgeLength
            alongY /= edgeLength
            var widest = 0f
            var nearEnd = Float.MAX_VALUE
            var farEnd = -Float.MAX_VALUE
            for (k in xs.indices) {
                val fromEdgeX = xs[k] - edgeX
                val fromEdgeY = ys[k] - edgeY
                val across = abs(-fromEdgeX * alongY + fromEdgeY * alongX)
                if (across > widest) widest = across
                val along = fromEdgeX * alongX + fromEdgeY * alongY
                if (along < nearEnd) nearEnd = along
                if (along > farEnd) farEnd = along
            }
            if (widest < narrowestStrip) {
                narrowestStrip = widest
                lengthAlongTheStrip = farEnd - nearEnd + 1f
            }
        }
        // A body of one or two cells has no hull to run calipers on, and no length either.
        if (hull.size < 3) {
            narrowestStrip = 0f
            lengthAlongTheStrip = cells.size.toFloat()
        }

        return WaterBody(
            lakeId = lakeId,
            cellCount = cells.size,
            // The line down the middle of the strip, so the furthest cell is half the width away.
            offsetFromTheLine = narrowestStrip / 2f,
            lengthAlongTheLine = lengthAlongTheStrip,
            centreX = xs.average().toFloat(),
            centreY = ys.average().toFloat()
        )
    }

    /** Andrew's monotone chain over the distinct cell centres, counter-clockwise. */
    private fun convexHull(xs: FloatArray, ys: FloatArray): List<Pair<Float, Float>> {
        val corners = xs.indices.map { xs[it] to ys[it] }.distinct()
            .sortedWith(compareBy({ it.first }, { it.second }))
        if (corners.size < 3) return corners

        fun turn(from: Pair<Float, Float>, via: Pair<Float, Float>, to: Pair<Float, Float>): Float =
            (via.first - from.first) * (to.second - from.second) -
                (via.second - from.second) * (to.first - from.first)

        fun halfHull(inOrder: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
            val chain = ArrayList<Pair<Float, Float>>()
            for (corner in inOrder) {
                while (chain.size >= 2 &&
                    turn(chain[chain.size - 2], chain[chain.size - 1], corner) <= 0f
                ) {
                    chain.removeAt(chain.size - 1)
                }
                chain.add(corner)
            }
            chain.removeAt(chain.size - 1)
            return chain
        }

        return halfHull(corners) + halfHull(corners.reversed())
    }
}
