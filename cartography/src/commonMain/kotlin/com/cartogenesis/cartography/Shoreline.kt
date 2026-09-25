package com.cartogenesis.cartography

import kotlin.math.max
import kotlin.math.sqrt

/**
 * The edge between land and water, as a line rather than as a staircase of cells.
 *
 * The raster draws the coast by inking the landward cell of every land-water pair, which is right
 * at one pixel to the cell and wrong everywhere else: shrink it and the line thins to nothing,
 * enlarge it and the reader is looking at the grid rather than at the country. A coast is a line,
 * and a line survives being scaled. So the same boundary is traced off the land mask as polylines
 * on the half-cell lattice, generalised for the scale it will be seen at, and stroked over the
 * raster; the fill underneath stays the raster's.
 *
 * The trace is marching squares over the grid of cell centres. Every vertex of the result lies
 * halfway between two neighbouring cells, one of them land and one of them water, so the line runs
 * exactly along the boundary the raster inks and the two agree wherever both are drawn.
 *
 * Where four cells meet in a checkerboard the contour can be closed two ways, and this closes it so
 * that land touching corner to corner stays one coast rather than two: an isthmus a cell wide reads
 * as an isthmus, which is what the flow routing already assumes when it lets a river run diagonally
 * across one.
 */
object Shoreline {

    /**
     * The coastline of [isLand] as polylines in cell coordinates, where cell `(i, j)`'s centre is
     * at `(i + 0.5, j + 0.5)` — the frame [MapRasterizer.overlay] draws rivers in.
     *
     * Each polyline is `x, y, x, y, …`. A ring closes on itself, repeating its first point as its
     * last; a coast that runs off the sheet's edge is an open chain that stops there. The outermost
     * half cell of the grid carries no contour, because a contour needs a cell on both sides of it.
     */
    fun trace(isLand: BooleanArray, cellsAcross: Int, cellsDown: Int): List<FloatArray> {
        val blocksAcross = cellsAcross - 1
        val blocksDown = cellsDown - 1
        if (blocksAcross < 1 || blocksDown < 1) return emptyList()

        // Two bits per block: which of its (at most two) contour segments have been walked.
        val walked = ByteArray(blocksAcross * blocksDown)
        val lines = ArrayList<FloatArray>()

        for (blockRow in 0 until blocksDown) {
            for (blockColumn in 0 until blocksAcross) {
                val corners = cornersAt(isLand, cellsAcross, blockColumn, blockRow)
                val sides = SEGMENTS[corners]
                var slot = 0
                while (slot < sides.size / SIDES_PER_SEGMENT) {
                    val block = blockRow * blocksAcross + blockColumn
                    if (walked[block].toInt() and (1 shl slot) == 0) {
                        lines.add(
                            chainFrom(
                                isLand, cellsAcross, blocksAcross, blocksDown, walked,
                                blockColumn, blockRow, sides[slot * SIDES_PER_SEGMENT]
                            )
                        )
                    }
                    slot++
                }
            }
        }
        return lines
    }

    /**
     * [line] with every vertex whose removal moves the line less than [tolerance] taken out, by
     * Douglas-Peucker. [tolerance] is in whatever units [line]'s coordinates are, which for the
     * drawn coast is the whole sheet's pixels; see [of].
     *
     * The recursion is an explicit stack rather than a call stack: a coastline at 4096 can run to
     * tens of thousands of vertices in one ring, and the worst case for this algorithm is a
     * recursion as deep as the line is long.
     *
     * The two ends are always kept, so a ring stays closed and an open chain still reaches the edge
     * of the sheet.
     */
    fun simplified(line: FloatArray, tolerance: Float): FloatArray {
        val vertices = line.size / 2
        if (vertices < 3 || tolerance <= 0f) return line

        val keep = BooleanArray(vertices)
        keep[0] = true
        keep[vertices - 1] = true

        val stack = ArrayDeque<Int>()
        stack.addLast(0)
        stack.addLast(vertices - 1)
        while (stack.isNotEmpty()) {
            val last = stack.removeLast()
            val first = stack.removeLast()
            if (last <= first + 1) continue

            var farthest = -1
            var farthestDistance = tolerance
            for (vertex in first + 1 until last) {
                val distance = distanceToSegment(
                    line[vertex * 2], line[vertex * 2 + 1],
                    line[first * 2], line[first * 2 + 1],
                    line[last * 2], line[last * 2 + 1]
                )
                if (distance > farthestDistance) {
                    farthestDistance = distance
                    farthest = vertex
                }
            }
            if (farthest < 0) continue

            keep[farthest] = true
            stack.addLast(first)
            stack.addLast(farthest)
            stack.addLast(farthest)
            stack.addLast(last)
        }

        var kept = 0
        for (vertex in 0 until vertices) if (keep[vertex]) kept++
        val simplified = FloatArray(kept * 2)
        var at = 0
        for (vertex in 0 until vertices) {
            if (!keep[vertex]) continue
            simplified[at++] = line[vertex * 2]
            simplified[at++] = line[vertex * 2 + 1]
        }
        return simplified
    }

    /**
     * The whole coast of [isLand], a grid of [geometry]'s cells, traced and generalised for
     * [sheet], as polylines in cell coordinates.
     *
     * Generalised on the sheet rather than on the grid: each line is carried onto the true-shape
     * sheet, simplified there to [MapSheet.simplifyTolerancePixels], and carried back. A cell is not
     * the same size both ways on the ground, and a tolerance of so many cells would give away twice
     * as much of a coast running north-south as of one running east-west; a pixel of the sheet is
     * the same ground both ways. The carrying is a multiply and a divide by whole pixel counts, so a
     * kept vertex comes back to the half-cell lattice it was traced on exactly.
     *
     * Islands smaller than the tolerance in both directions are dropped rather than simplified: at
     * that scale their outline is a dot, and a dot drawn in the coastline's ink reads as a mark on
     * the paper. The raster still fills them, so the land is not lost — only its outline is, which
     * is what generalising a coast means.
     */
    fun of(isLand: BooleanArray, geometry: SheetGeometry, sheet: MapSheet): List<FloatArray> {
        val tolerancePixels = sheet.simplifyTolerancePixels
        val coast = ArrayList<FloatArray>()
        trace(isLand, geometry.cellsAcross, geometry.cellsDown).forEach { line ->
            val onTheSheet = scaled(line, geometry.pixelsPerCellAcross, geometry.pixelsPerCellDown)
            if (spans(onTheSheet) >= tolerancePixels) {
                val kept = simplified(onTheSheet, tolerancePixels)
                coast.add(unscaled(kept, geometry.pixelsPerCellAcross, geometry.pixelsPerCellDown))
            }
        }
        return coast
    }

    /** [line] with every x multiplied by [acrossFactor] and every y by [downFactor]. */
    private fun scaled(line: FloatArray, acrossFactor: Int, downFactor: Int): FloatArray {
        if (acrossFactor == 1 && downFactor == 1) return line
        return FloatArray(line.size) { at ->
            if (at % 2 == 0) line[at] * acrossFactor else line[at] * downFactor
        }
    }

    /** [scaled] undone. */
    private fun unscaled(line: FloatArray, acrossFactor: Int, downFactor: Int): FloatArray {
        if (acrossFactor == 1 && downFactor == 1) return line
        return FloatArray(line.size) { at ->
            if (at % 2 == 0) line[at] / acrossFactor else line[at] / downFactor
        }
    }

    /** The longer side of a polyline's bounding box, in its own units. */
    private fun spans(line: FloatArray): Float {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var at = 0
        while (at < line.size) {
            val x = line[at]
            val y = line[at + 1]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            at += 2
        }
        return max(maxX - minX, maxY - minY)
    }

    /**
     * Distance from a point to a segment, in the units of their coordinates — to the segment
     * itself, not to the infinite line through it.
     *
     * The difference matters for the guarantee this algorithm is worth having for. Douglas-Peucker
     * is often written with the perpendicular distance to the line, and then a vertex sitting off
     * the *end* of a short segment measures as close when it is not; the band the simplified line
     * is promised to stay inside is then not a band at all. Clamping to the segment costs two
     * multiplications and makes the Hausdorff distance from the original genuinely bounded by the
     * tolerance, which is what `GeneralisationTest` measures.
     */
    internal fun distanceToSegment(
        x: Float, y: Float,
        fromX: Float, fromY: Float,
        toX: Float, toY: Float
    ): Float {
        val runX = toX - fromX
        val runY = toY - fromY
        val lengthSquared = runX * runX + runY * runY
        // A segment whose ends coincide is a point, and the distance to it is the distance to it.
        val along =
            if (lengthSquared < SHORTEST_REAL_SEGMENT_SQUARED) 0f
            else (((x - fromX) * runX + (y - fromY) * runY) / lengthSquared).coerceIn(0f, 1f)
        val awayX = x - (fromX + along * runX)
        val awayY = y - (fromY + along * runY)
        return sqrt(awayX * awayX + awayY * awayY)
    }

    /**
     * Below this squared length a segment is treated as a point, in the coordinates' units squared.
     *
     * A millionth of a millionth: every real vertex here sits on a half-cell lattice, so the
     * shortest segment the trace can produce is half a cell — half a pixel or more on the sheet —
     * and nothing legitimate comes near this. It exists only so the projection below cannot divide
     * by zero.
     */
    private const val SHORTEST_REAL_SEGMENT_SQUARED = 1e-12f

    /**
     * One contour, walked from a segment of one block to wherever it leads.
     *
     * The walk goes forward from the seed segment until the contour closes on itself or runs off
     * the grid, and then, if it ran off, back from the seed the other way with the points reversed
     * onto the front. That is what makes an island one ring and a coast that leaves the sheet one
     * chain, rather than two halves meeting at whichever block the outer loop happened to reach
     * first.
     */
    private fun chainFrom(
        isLand: BooleanArray,
        cellsAcross: Int,
        blocksAcross: Int,
        blocksDown: Int,
        walked: ByteArray,
        seedColumn: Int,
        seedRow: Int,
        seedEntry: Int
    ): FloatArray {
        val forward = PointList()
        forward.add(pointX(seedColumn, seedEntry), pointY(seedRow, seedEntry))
        val closed = walk(
            isLand, cellsAcross, blocksAcross, blocksDown, walked,
            seedColumn, seedRow, seedEntry, forward
        )
        if (closed) return forward.toFloatArray()

        // The contour did not come back, so the seed was somewhere in the middle of an open chain
        // and the rest of it lies the other way: out of the seed block across the side the forward
        // walk came in by, into the block that shares that side.
        val backward = PointList()
        var column = seedColumn
        var row = seedRow
        var side = seedEntry
        when (side) {
            TOP -> { row--; side = BOTTOM }
            RIGHT -> { column++; side = LEFT }
            BOTTOM -> { row++; side = TOP }
            else -> { column--; side = RIGHT }
        }
        if (column in 0 until blocksAcross && row in 0 until blocksDown) {
            walk(
                isLand, cellsAcross, blocksAcross, blocksDown, walked,
                column, row, side, backward
            )
        }
        return backward.reversedFollowedBy(forward)
    }

    /**
     * Follows the contour out of block ([blockColumn], [blockRow]) from [entry], appending each
     * point it reaches to [into]. True when the walk came back to a segment it had already taken,
     * which is how a closed ring ends; false when it left the grid.
     */
    private fun walk(
        isLand: BooleanArray,
        cellsAcross: Int,
        blocksAcross: Int,
        blocksDown: Int,
        walked: ByteArray,
        blockColumn: Int,
        blockRow: Int,
        entry: Int,
        into: PointList
    ): Boolean {
        var column = blockColumn
        var row = blockRow
        var side = entry
        while (true) {
            val corners = cornersAt(isLand, cellsAcross, column, row)
            val sides = SEGMENTS[corners]
            val slot = slotContaining(sides, side)
            if (slot < 0) return false

            val block = row * blocksAcross + column
            val mark = 1 shl slot
            if (walked[block].toInt() and mark != 0) return true
            walked[block] = (walked[block].toInt() or mark).toByte()

            val exit =
                if (sides[slot * SIDES_PER_SEGMENT] == side) sides[slot * SIDES_PER_SEGMENT + 1]
                else sides[slot * SIDES_PER_SEGMENT]
            into.add(pointX(column, exit), pointY(row, exit))

            when (exit) {
                TOP -> { row--; side = BOTTOM }
                RIGHT -> { column++; side = LEFT }
                BOTTOM -> { row++; side = TOP }
                else -> { column--; side = RIGHT }
            }
            if (column < 0 || row < 0 || column >= blocksAcross || row >= blocksDown) return false
        }
    }

    /** Which of a case's segments touches [side], or -1 when none does. */
    private fun slotContaining(sides: IntArray, side: Int): Int {
        var slot = 0
        while (slot < sides.size / SIDES_PER_SEGMENT) {
            if (sides[slot * SIDES_PER_SEGMENT] == side ||
                sides[slot * SIDES_PER_SEGMENT + 1] == side
            ) {
                return slot
            }
            slot++
        }
        return -1
    }

    /**
     * The four cells around block ([blockColumn], [blockRow]) as a bit per corner: 1 north-west,
     * 2 north-east, 4 south-east, 8 south-west, set where the cell is land.
     *
     * The sixteen values this can take are the sixteen entries of [SEGMENTS], which is what makes
     * that table a plain lookup rather than a decision.
     */
    private fun cornersAt(
        isLand: BooleanArray,
        cellsAcross: Int,
        blockColumn: Int,
        blockRow: Int
    ): Int {
        val northWest = blockRow * cellsAcross + blockColumn
        var corners = 0
        if (isLand[northWest]) corners = corners or NORTH_WEST_IS_LAND
        if (isLand[northWest + 1]) corners = corners or NORTH_EAST_IS_LAND
        if (isLand[northWest + cellsAcross + 1]) corners = corners or SOUTH_EAST_IS_LAND
        if (isLand[northWest + cellsAcross]) corners = corners or SOUTH_WEST_IS_LAND
        return corners
    }

    /**
     * Where a crossing of one side of a block sits, in cell coordinates.
     *
     * A block spans the centres of the four cells at ([blockColumn], [blockRow]) and their east,
     * south and south-east neighbours, so its north-west corner is at `blockColumn + 0.5`. A
     * crossing is the midpoint of a side: half a cell along it, and on the side itself.
     */
    private fun pointX(blockColumn: Int, side: Int): Float = when (side) {
        TOP, BOTTOM -> blockColumn + 1f
        RIGHT -> blockColumn + 1.5f
        else -> blockColumn + 0.5f
    }

    private fun pointY(blockRow: Int, side: Int): Float = when (side) {
        LEFT, RIGHT -> blockRow + 1f
        BOTTOM -> blockRow + 1.5f
        else -> blockRow + 0.5f
    }

    private const val TOP = 0
    private const val RIGHT = 1
    private const val BOTTOM = 2
    private const val LEFT = 3

    /** A segment is a pair of sides, so [SEGMENTS] holds two entries for each of them. */
    private const val SIDES_PER_SEGMENT = 2

    /** The bit each corner of a block sets in [cornersAt]'s answer, clockwise from the north-west. */
    private const val NORTH_WEST_IS_LAND = 1
    private const val NORTH_EAST_IS_LAND = 2
    private const val SOUTH_EAST_IS_LAND = 4
    private const val SOUTH_WEST_IS_LAND = 8

    /**
     * Which sides of a block the contour crosses, for each of the sixteen land patterns, as pairs.
     *
     * Cases 5 and 10 are the checkerboards, and are the only ones with two segments and the only
     * ones with a choice. Both are resolved so that the *land* stays connected across the diagonal:
     * the contour is cut around the two water corners in case 5 and around the two land-free
     * corners in case 10. Resolving them the other way would cut an isthmus a cell wide into two
     * islands.
     */
    private val SEGMENTS: Array<IntArray> = arrayOf(
        intArrayOf(),                                   // 0: all water
        intArrayOf(LEFT, TOP),                          // 1: north-west
        intArrayOf(TOP, RIGHT),                         // 2: north-east
        intArrayOf(LEFT, RIGHT),                        // 3: the northern pair
        intArrayOf(RIGHT, BOTTOM),                      // 4: south-east
        intArrayOf(TOP, RIGHT, BOTTOM, LEFT),           // 5: north-west and south-east
        intArrayOf(TOP, BOTTOM),                        // 6: the eastern pair
        intArrayOf(LEFT, BOTTOM),                       // 7: all but the south-west
        intArrayOf(BOTTOM, LEFT),                       // 8: south-west
        intArrayOf(TOP, BOTTOM),                        // 9: the western pair
        intArrayOf(LEFT, TOP, RIGHT, BOTTOM),           // 10: north-east and south-west
        intArrayOf(RIGHT, BOTTOM),                      // 11: all but the south-east
        intArrayOf(LEFT, RIGHT),                        // 12: the southern pair
        intArrayOf(TOP, RIGHT),                         // 13: all but the north-east
        intArrayOf(LEFT, TOP),                          // 14: all but the north-west
        intArrayOf()                                    // 15: all land
    )

    /** A growable list of points, kept as raw floats so a long coast is not a million boxes. */
    private class PointList {
        // Enough for a small island's whole ring without a copy; a mainland coast doubles from
        // here a dozen times, which is nothing against the trace itself.
        private var points = FloatArray(64)
        private var size = 0

        fun add(x: Float, y: Float) {
            if (size + 2 > points.size) points = points.copyOf(points.size * 2)
            points[size++] = x
            points[size++] = y
        }

        fun toFloatArray(): FloatArray = points.copyOf(size)

        /** This list's points in reverse, then [rest]'s in order: the two halves of one chain. */
        fun reversedFollowedBy(rest: PointList): FloatArray {
            val joined = FloatArray(size + rest.size)
            var at = 0
            var from = size - 2
            while (from >= 0) {
                joined[at++] = points[from]
                joined[at++] = points[from + 1]
                from -= 2
            }
            for (index in 0 until rest.size) joined[at++] = rest.points[index]
            return joined
        }
    }
}
