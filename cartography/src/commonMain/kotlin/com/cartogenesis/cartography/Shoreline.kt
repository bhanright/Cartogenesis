package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.WorldMap
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The edge between land and water, as a line rather than as a staircase of cells.
 *
 * The raster's coast is a cell wide, which is right at one pixel to the cell and wrong everywhere
 * else: shrink it and the line thins to nothing, enlarge it and the reader is looking at the grid
 * rather than at the country. A coast is a line, and a line survives being scaled. So the same
 * shoreline the raster inks ([CoastLine]) is traced as polylines, smoothed along its own length,
 * generalised for the scale it will be seen at, and stroked over the raster; the fill underneath
 * stays the raster's.
 *
 * The trace is marching squares over the grid of cell centres. Each vertex lies on the edge between
 * a cell of the mask and one outside it, where [CoastLine] puts the waterline: between the two by
 * their altitudes, so the line follows the ground between cells rather than the grid's edges.
 * Traced without a [CoastLine], every vertex lies halfway, on the half-cell lattice: the line the
 * drawing stroked until G2, kept as the census's control for it (docs/DESIGN_LEDGER.md, G2).
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
     * Each vertex is where [crossings] puts the waterline on its edge, or halfway along the edge
     * where there is no [crossings] to ask; [crossings] describes the same mask as [isLand].
     */
    fun trace(
        isLand: BooleanArray,
        cellsAcross: Int,
        cellsDown: Int,
        crossings: CoastLine? = null
    ): List<FloatArray> {
        val blocksAcross = cellsAcross - 1
        val blocksDown = cellsDown - 1
        if (blocksAcross < 1 || blocksDown < 1) return emptyList()
        val grid = Grid(isLand, cellsAcross, blocksAcross, blocksDown, crossings)

        // Two bits per block: which of its (at most two) contour segments have been walked.
        val walked = ByteArray(blocksAcross * blocksDown)
        val lines = ArrayList<FloatArray>()

        for (blockRow in 0 until blocksDown) {
            for (blockColumn in 0 until blocksAcross) {
                val corners = grid.cornersAt(blockColumn, blockRow)
                val sides = SEGMENTS[corners]
                var slot = 0
                while (slot < sides.size / SIDES_PER_SEGMENT) {
                    val block = blockRow * blocksAcross + blockColumn
                    if (walked[block].toInt() and (1 shl slot) == 0) {
                        lines.add(chainFrom(grid, walked, blockColumn, blockRow, sides[slot * SIDES_PER_SEGMENT]))
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
     * [line] smoothed along its own length: each vertex moved to the mean of the line about it,
     * every point of every segment weighted by a Gaussian of [sigma] in its distance along the
     * line from the vertex. Isotropic by construction, since nothing in it knows which way the grid
     * runs; what it takes out are the bends a trace makes at every cell it crosses. The vertices
     * stay the line's own, one for one, so a later simplification still picks among them.
     *
     * The mean is over the line itself, integrated in pieces no longer than an eighth of [sigma],
     * and not over its vertices alone: a vertex is then moved at most as far as the mean distance
     * along the line under the Gaussian, `sigma * sqrt(2 / pi)` ([largestShiftOf]), which it reaches
     * only where the line runs straight out and straight back. Weighting the vertices instead lets
     * a sparse trace move one further, since a neighbour a whole sigma away can carry as much
     * weight as the vertex itself.
     *
     * A ring ([line] repeating its first point as its last) is smoothed round its seam and still
     * closes; an open chain keeps its two ends where they were, the window narrowing toward them so
     * the line still reaches the edge it ran off.
     */
    fun smoothedAlongTheCurve(line: FloatArray, sigma: Float): FloatArray {
        val count = line.size / 2
        if (count < 3 || sigma <= 0f) return line
        val ring = line[0] == line[line.size - 2] && line[1] == line[line.size - 1]
        // A ring's repeated last point is its first; it is smoothed once and copied back.
        val distinct = if (ring) count - 1 else count
        if (distinct < 3) return line
        val segments = if (ring) distinct else distinct - 1
        val stepLength = FloatArray(segments) { segment ->
            val next = (segment + 1) % distinct
            lengthOf(line[next * 2] - line[segment * 2], line[next * 2 + 1] - line[segment * 2 + 1])
        }
        val alongFromStart = FloatArray(distinct)
        for (vertex in 1 until distinct) alongFromStart[vertex] = alongFromStart[vertex - 1] + stepLength[vertex - 1]
        val totalLength = stepLength.sum()

        val smoothed = line.copyOf()
        for (vertex in 0 until distinct) {
            // An open chain's window is cut to the line left on the shorter side, both sides alike;
            // a ring's to half its length each way, so no stretch of it is counted twice.
            val width = if (ring) sigma else min(sigma, min(alongFromStart[vertex], totalLength - alongFromStart[vertex]) / WINDOW_SIGMAS)
            if (width <= 0f) continue
            val reach = if (ring) min(WINDOW_SIGMAS * width, totalLength / 2f) else WINDOW_SIGMAS * width
            val pieceLength = width / PIECES_PER_SIGMA
            var weightSum = 0.0
            var sumX = 0.0
            var sumY = 0.0
            for (direction in intArrayOf(-1, 1)) {
                var from = vertex
                var travelled = 0f
                while (travelled < reach) {
                    val to = if (ring) (from + direction).mod(distinct) else from + direction
                    if (to < 0 || to >= distinct) break
                    val segmentLength = stepLength[if (direction > 0) from else to]
                    val counted = min(segmentLength, reach - travelled)
                    if (segmentLength > 0f) {
                        val pieces = ceil(counted / pieceLength).toInt().coerceAtLeast(1)
                        for (piece in 0 until pieces) {
                            val alongSegment = (piece + HALF) * counted / pieces
                            val alongLine = travelled + alongSegment
                            val weight = exp(-(alongLine * alongLine) / (2.0 * width * width)) * (counted / pieces)
                            val share = alongSegment / segmentLength
                            weightSum += weight
                            sumX += weight * (line[from * 2] + share * (line[to * 2] - line[from * 2]))
                            sumY += weight * (line[from * 2 + 1] + share * (line[to * 2 + 1] - line[from * 2 + 1]))
                        }
                    }
                    travelled += segmentLength
                    from = to
                }
            }
            if (weightSum > 0.0) {
                smoothed[vertex * 2] = (sumX / weightSum).toFloat()
                smoothed[vertex * 2 + 1] = (sumY / weightSum).toFloat()
            }
        }
        if (ring) {
            smoothed[smoothed.size - 2] = smoothed[0]
            smoothed[smoothed.size - 1] = smoothed[1]
        }
        return smoothed
    }

    /**
     * The furthest [smoothedAlongTheCurve] at [sigma] moves a vertex: the mean distance along the
     * line under the Gaussian, `sigma * sqrt(2 / pi)`, which a vertex reaches only where the line
     * runs straight out and straight back. The window's cut at three sigmas only lowers it.
     */
    fun largestShiftOf(sigma: Float): Float = sigma * sqrt(2f / PI.toFloat())

    /**
     * How far along the line [smoothedAlongTheCurve] spreads a vertex, as a share of one cell's
     * longer side on the sheet. Half a cell takes out the bend a trace makes at every edge it
     * crosses, which is a cell's length of line or less, and holds every vertex within
     * `0.5 * sqrt(2 / pi)`, 0.40 of a cell, of the shoreline it was traced on.
     */
    const val SMOOTHING_SIGMA_CELLS = 0.5f

    /**
     * How many of the Gaussian's sigmas its window reaches each way: past three the weight left is
     * under three thousandths of the whole, and cutting it there only shortens the reach.
     */
    private const val WINDOW_SIGMAS = 3f

    /**
     * How finely the line is integrated, in pieces to a sigma: at eight the midpoint rule's error
     * on a Gaussian's mass and first moment is under a part in a thousand.
     */
    private const val PIECES_PER_SIGMA = 8f

    private const val HALF = 0.5f

    /**
     * The whole coast of [world], traced where [CoastLine] puts the shoreline, smoothed along its
     * length and generalised for [sheet], as polylines in cell coordinates: what the overlay strokes.
     */
    fun of(world: WorldMap, sheet: MapSheet): List<FloatArray> =
        of(CoastLine.of(world), SheetGeometry.of(world), sheet)

    /**
     * The coast [coast] describes, on a grid of [geometry]'s cells, traced, smoothed along its
     * length by [SMOOTHING_SIGMA_CELLS] of a cell and generalised for [sheet], as polylines in cell
     * coordinates.
     *
     * Smoothed and generalised on the sheet rather than on the grid: each line is carried onto the
     * true-shape sheet, smoothed and simplified there, and carried back. A cell is not the same size
     * both ways on the ground, and a tolerance of so many cells would give away twice as much of a
     * coast running north-south as of one running east-west; a pixel of the sheet is the same
     * ground both ways. The simplification is to [MapSheet.simplifyTolerancePixels].
     *
     * Islands smaller than the tolerance in both directions are dropped rather than simplified: at
     * that scale their outline is a dot, and a dot drawn in the coastline's ink reads as a mark on
     * the paper. The raster still fills them, so the land is not lost — only its outline is, which
     * is what generalising a coast means.
     */
    fun of(coast: CoastLine, geometry: SheetGeometry, sheet: MapSheet): List<FloatArray> {
        val tolerancePixels = sheet.simplifyTolerancePixels
        val sigmaPixels = SMOOTHING_SIGMA_CELLS * CoastLine.reachPixels(geometry.pixelsPerCellAcross, geometry.pixelsPerCellDown)
        val lines = ArrayList<FloatArray>()
        trace(coast.banks, geometry.cellsAcross, geometry.cellsDown, coast).forEach { line ->
            val onTheSheet = scaled(line, geometry.pixelsPerCellAcross, geometry.pixelsPerCellDown)
            if (spans(onTheSheet) >= tolerancePixels) {
                val kept = simplified(smoothedAlongTheCurve(onTheSheet, sigmaPixels), tolerancePixels)
                lines.add(unscaled(kept, geometry.pixelsPerCellAcross, geometry.pixelsPerCellDown))
            }
        }
        return lines
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

    /** Euclidean length of a vector, where a pair of differences is at hand. */
    private fun lengthOf(dx: Float, dy: Float): Float = sqrt(dx * dx + dy * dy)

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
     * A millionth of a millionth. A crossing lies strictly short of the water cell's centre, so two
     * vertices of one trace never coincide, and nothing legitimate comes near this. It exists only
     * so the projection below cannot divide by zero.
     */
    private const val SHORTEST_REAL_SEGMENT_SQUARED = 1e-12f

    /** What a trace walks: the mask, its blocks and where each crossing lies. */
    private class Grid(
        val isLand: BooleanArray,
        val cellsAcross: Int,
        val blocksAcross: Int,
        val blocksDown: Int,
        val crossings: CoastLine?
    ) {
        /** The cells of the block ([blockColumn], [blockRow]), clockwise from the north-west. */
        fun cellsOf(blockColumn: Int, blockRow: Int): IntArray {
            val northWest = blockRow * cellsAcross + blockColumn
            return intArrayOf(northWest, northWest + 1, northWest + cellsAcross + 1, northWest + cellsAcross)
        }

        /**
         * The four cells around block ([blockColumn], [blockRow]) as a bit per corner: 1 north-west,
         * 2 north-east, 4 south-east, 8 south-west, set where the cell is land.
         *
         * The sixteen values this can take are the sixteen entries of [SEGMENTS], which is what makes
         * that table a plain lookup rather than a decision.
         */
        fun cornersAt(blockColumn: Int, blockRow: Int): Int {
            val northWest = blockRow * cellsAcross + blockColumn
            var corners = 0
            if (isLand[northWest]) corners = corners or NORTH_WEST_IS_LAND
            if (isLand[northWest + 1]) corners = corners or NORTH_EAST_IS_LAND
            if (isLand[northWest + cellsAcross + 1]) corners = corners or SOUTH_EAST_IS_LAND
            if (isLand[northWest + cellsAcross]) corners = corners or SOUTH_WEST_IS_LAND
            return corners
        }

        /**
         * Where the crossing of one side of a block sits, in cell coordinates. A block spans the
         * centres of the four cells at ([blockColumn], [blockRow]) and their east, south and
         * south-east neighbours, so its north-west corner is at `blockColumn + 0.5`; a crossing is
         * on the side itself, where [crossings] puts it, or at its midpoint.
         */
        fun pointX(blockColumn: Int, blockRow: Int, side: Int): Float {
            val coast = crossings ?: return when (side) {
                TOP, BOTTOM -> blockColumn + 1f
                RIGHT -> blockColumn + 1.5f
                else -> blockColumn + 0.5f
            }
            return coast.crossingX(blockColumn, cellsOf(blockColumn, blockRow), side)
        }

        fun pointY(blockColumn: Int, blockRow: Int, side: Int): Float {
            val coast = crossings ?: return when (side) {
                LEFT, RIGHT -> blockRow + 1f
                BOTTOM -> blockRow + 1.5f
                else -> blockRow + 0.5f
            }
            return coast.crossingY(blockRow, cellsOf(blockColumn, blockRow), side)
        }
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
        grid: Grid,
        walked: ByteArray,
        seedColumn: Int,
        seedRow: Int,
        seedEntry: Int
    ): FloatArray {
        val forward = PointList()
        forward.add(grid.pointX(seedColumn, seedRow, seedEntry), grid.pointY(seedColumn, seedRow, seedEntry))
        val closed = walk(grid, walked, seedColumn, seedRow, seedEntry, forward)
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
        if (column in 0 until grid.blocksAcross && row in 0 until grid.blocksDown) {
            walk(grid, walked, column, row, side, backward)
        }
        return backward.reversedFollowedBy(forward)
    }

    /**
     * Follows the contour out of block ([blockColumn], [blockRow]) from [entry], appending each
     * point it reaches to [into]. True when the walk came back to a segment it had already taken,
     * which is how a closed ring ends; false when it left the grid.
     */
    private fun walk(
        grid: Grid,
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
            val corners = grid.cornersAt(column, row)
            val sides = SEGMENTS[corners]
            val slot = slotContaining(sides, side)
            if (slot < 0) return false

            val block = row * grid.blocksAcross + column
            val mark = 1 shl slot
            if (walked[block].toInt() and mark != 0) return true
            walked[block] = (walked[block].toInt() or mark).toByte()

            val exit =
                if (sides[slot * SIDES_PER_SEGMENT] == side) sides[slot * SIDES_PER_SEGMENT + 1]
                else sides[slot * SIDES_PER_SEGMENT]
            into.add(grid.pointX(column, row, exit), grid.pointY(column, row, exit))

            when (exit) {
                TOP -> { row--; side = BOTTOM }
                RIGHT -> { column++; side = LEFT }
                BOTTOM -> { row++; side = TOP }
                else -> { column--; side = RIGHT }
            }
            if (column < 0 || row < 0 || column >= grid.blocksAcross || row >= grid.blocksDown) return false
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

    internal const val TOP = 0
    internal const val RIGHT = 1
    internal const val BOTTOM = 2
    internal const val LEFT = 3

    /** A segment is a pair of sides, so [SEGMENTS] holds two entries for each of them. */
    internal const val SIDES_PER_SEGMENT = 2

    /** The bit each corner of a block sets in [Grid.cornersAt]'s answer, clockwise from the north-west. */
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
     * islands. The raster's coast reads the same table ([CoastLine]), and the shader a copy of it.
     */
    internal val SEGMENTS: Array<IntArray> = arrayOf(
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
