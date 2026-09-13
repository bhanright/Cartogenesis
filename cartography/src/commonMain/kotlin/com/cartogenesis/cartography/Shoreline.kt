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

        for (blockY in 0 until blocksDown) {
            for (blockX in 0 until blocksAcross) {
                val case = caseAt(isLand, cellsAcross, blockX, blockY)
                val sides = SEGMENTS[case]
                var slot = 0
                while (slot < sides.size / 2) {
                    val block = blockY * blocksAcross + blockX
                    if (walked[block].toInt() and (1 shl slot) == 0) {
                        lines.add(chainFrom(isLand, cellsAcross, blocksAcross, blocksDown, walked,
                            blockX, blockY, sides[slot * 2]))
                    }
                    slot++
                }
            }
        }
        return lines
    }

    /**
     * [line] with every vertex whose removal moves the line less than [toleranceCells] taken out,
     * by Douglas-Peucker.
     *
     * The recursion is an explicit stack rather than a call stack: a coastline at 4096 can run to
     * tens of thousands of vertices in one ring, and the worst case for this algorithm is a
     * recursion as deep as the line is long.
     *
     * The two ends are always kept, so a ring stays closed and an open chain still reaches the edge
     * of the sheet.
     */
    fun simplified(line: FloatArray, toleranceCells: Float): FloatArray {
        val vertices = line.size / 2
        if (vertices < 3 || toleranceCells <= 0f) return line

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
            var farthestDistance = toleranceCells
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
     * The whole coast of [isLand], traced and generalised for [sheet].
     *
     * Islands smaller than the tolerance in both directions are dropped rather than simplified: at
     * that scale their outline is a dot, and a dot drawn in the coastline's ink reads as a mark on
     * the paper. The raster still fills them, so the land is not lost — only its outline is, which
     * is what generalising a coast means.
     */
    fun of(isLand: BooleanArray, cellsAcross: Int, cellsDown: Int, sheet: MapSheet): List<FloatArray> {
        val tolerance = sheet.simplifyToleranceCells
        val coast = ArrayList<FloatArray>()
        trace(isLand, cellsAcross, cellsDown).forEach { line ->
            if (spans(line) >= tolerance) coast.add(simplified(line, tolerance))
        }
        return coast
    }

    /** The longer side of a polyline's bounding box, in cells. */
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
     * Distance from a point to a segment, in cells — to the segment itself, not to the infinite
     * line through it.
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
            if (lengthSquared < 1e-12f) 0f
            else (((x - fromX) * runX + (y - fromY) * runY) / lengthSquared).coerceIn(0f, 1f)
        val dx = x - (fromX + along * runX)
        val dy = y - (fromY + along * runY)
        return sqrt(dx * dx + dy * dy)
    }

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
        seedX: Int,
        seedY: Int,
        seedEntry: Int
    ): FloatArray {
        val forward = PointList()
        forward.add(pointX(seedX, seedEntry), pointY(seedY, seedEntry))
        val closed = walk(
            isLand, cellsAcross, blocksAcross, blocksDown, walked,
            seedX, seedY, seedEntry, forward
        )
        if (closed) return forward.toFloatArray()

        // The contour did not come back, so the seed was somewhere in the middle of an open chain
        // and the rest of it lies the other way: out of the seed block across the side the forward
        // walk came in by, into the block that shares that side.
        val backward = PointList()
        var x = seedX
        var y = seedY
        var side = seedEntry
        when (side) {
            TOP -> { y--; side = BOTTOM }
            RIGHT -> { x++; side = LEFT }
            BOTTOM -> { y++; side = TOP }
            else -> { x--; side = RIGHT }
        }
        if (x in 0 until blocksAcross && y in 0 until blocksDown) {
            walk(isLand, cellsAcross, blocksAcross, blocksDown, walked, x, y, side, backward)
        }
        return backward.reversedFollowedBy(forward)
    }

    /**
     * Follows the contour out of block ([blockX], [blockY]) from [entry], appending each point it
     * reaches to [into]. True when the walk came back to a segment it had already taken, which is
     * how a closed ring ends; false when it left the grid.
     */
    private fun walk(
        isLand: BooleanArray,
        cellsAcross: Int,
        blocksAcross: Int,
        blocksDown: Int,
        walked: ByteArray,
        blockX: Int,
        blockY: Int,
        entry: Int,
        into: PointList
    ): Boolean {
        var x = blockX
        var y = blockY
        var side = entry
        while (true) {
            val case = caseAt(isLand, cellsAcross, x, y)
            val sides = SEGMENTS[case]
            val slot = slotContaining(sides, side)
            if (slot < 0) return false

            val block = y * blocksAcross + x
            val mark = 1 shl slot
            if (walked[block].toInt() and mark != 0) return true
            walked[block] = (walked[block].toInt() or mark).toByte()

            val exit = if (sides[slot * 2] == side) sides[slot * 2 + 1] else sides[slot * 2]
            into.add(pointX(x, exit), pointY(y, exit))

            when (exit) {
                TOP -> { y--; side = BOTTOM }
                RIGHT -> { x++; side = LEFT }
                BOTTOM -> { y++; side = TOP }
                else -> { x--; side = RIGHT }
            }
            if (x < 0 || y < 0 || x >= blocksAcross || y >= blocksDown) return false
        }
    }

    /** Which of a case's segments touches [side], or -1 when none does. */
    private fun slotContaining(sides: IntArray, side: Int): Int {
        var slot = 0
        while (slot < sides.size / 2) {
            if (sides[slot * 2] == side || sides[slot * 2 + 1] == side) return slot
            slot++
        }
        return -1
    }

    /**
     * The four cells around block ([blockX], [blockY]) as a bit per corner: 1 north-west, 2
     * north-east, 4 south-east, 8 south-west, set where the cell is land.
     */
    private fun caseAt(isLand: BooleanArray, cellsAcross: Int, blockX: Int, blockY: Int): Int {
        val northWest = blockY * cellsAcross + blockX
        var case = 0
        if (isLand[northWest]) case = case or 1
        if (isLand[northWest + 1]) case = case or 2
        if (isLand[northWest + cellsAcross + 1]) case = case or 4
        if (isLand[northWest + cellsAcross]) case = case or 8
        return case
    }

    private fun pointX(blockX: Int, side: Int): Float = when (side) {
        TOP, BOTTOM -> blockX + 1f
        RIGHT -> blockX + 1.5f
        else -> blockX + 0.5f
    }

    private fun pointY(blockY: Int, side: Int): Float = when (side) {
        LEFT, RIGHT -> blockY + 1f
        BOTTOM -> blockY + 1.5f
        else -> blockY + 0.5f
    }

    private const val TOP = 0
    private const val RIGHT = 1
    private const val BOTTOM = 2
    private const val LEFT = 3

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
