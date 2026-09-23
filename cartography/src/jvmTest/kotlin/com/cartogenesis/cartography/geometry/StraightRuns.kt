package com.cartogenesis.cartography.geometry

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * One straight run of a traced line: the chord Douglas-Peucker keeps between two vertices it did
 * not remove, in kilometres, with its undirected ground bearing.
 *
 * [outline] names the line it came from and [order] its place along it, so runs that follow one
 * another can be told from runs that merely lie near each other.
 */
internal class Run(
    val fromXKm: Double,
    val fromYKm: Double,
    val toXKm: Double,
    val toYKm: Double,
    val outline: Int,
    val order: Int,
    /** The vertices of its line the run starts and ends at. */
    val fromVertex: Int = 0,
    val toVertex: Int = 0
) {
    val lengthKm: Double = lengthOf(toXKm - fromXKm, toYKm - fromYKm)
    var bearingDegrees: Double = 0.0
        private set

    fun withBearing(frame: GridFrame): Run {
        bearingDegrees = frame.bearingDegrees(toXKm - fromXKm, toYKm - fromYKm)
        return this
    }

    /**
     * How far the run's two ends lie apart across the line through its start at [bearingDegrees],
     * in kilometres: zero for a run lying exactly along that bearing.
     */
    fun offsetAcrossKm(bearingDegrees: Double): Double {
        val radians = Math.toRadians(bearingDegrees)
        val dx = toXKm - fromXKm
        val dy = toYKm - fromYKm
        return abs(-dx * sin(radians) + dy * cos(radians))
    }

    val midXKm: Double get() = (fromXKm + toXKm) / 2.0
    val midYKm: Double get() = (fromYKm + toYKm) / 2.0
}

/**
 * A traced line broken into straight runs, at a tolerance stated in kilometres.
 *
 * A raster boundary is a staircase whose every step lies on an axis, so the steps themselves say
 * nothing about the country; what a reader sees is the line the staircase approximates. Douglas-
 * Peucker with a tolerance of [TOLERANCE_CELL_WIDTHS] of a cell's width recovers it. The traced
 * staircase of a straight line at any bearing lies within half the cell's width across that line
 * of the line itself, and that width is at most one cell width (across a north-south line); the
 * chord Douglas-Peucker draws runs between two traced vertices, each of which may lie that far off
 * the line, so a vertex between them may lie up to a whole cell width from the chord. A tolerance
 * of one cell width and a tenth is therefore the least at which any straight stretch of ground
 * comes out as one run, its bearing the line's and not the steps'. `GeometryControlTest` shows
 * straight edges at arbitrary bearings coming out as one run within a degree.
 */
internal object StraightRuns {

    /**
     * The simplification tolerance, in cell widths — the larger of the cell's two sides.
     * See the object's own comment for the derivation.
     */
    const val TOLERANCE_CELL_WIDTHS = 1.1

    fun toleranceKm(frame: GridFrame): Double =
        TOLERANCE_CELL_WIDTHS * maxOf(frame.cellWidthKm, frame.cellHeightKm)

    /** Every run of every line in [outlines], in order along each. */
    fun of(outlines: List<Outline>, frame: GridFrame, toleranceKm: Double = toleranceKm(frame)): List<Run> {
        val runs = ArrayList<Run>()
        outlines.forEachIndexed { index, outline -> runs.addAll(of(outline, index, frame, toleranceKm)) }
        return runs
    }

    /** The runs of one line, in order along it. */
    fun of(outline: Outline, index: Int, frame: GridFrame, toleranceKm: Double = toleranceKm(frame)): List<Run> {
        val count = outline.vertexCount
        if (count < 2) return emptyList()
        val xs = outline.xKm
        val ys = outline.yKm
        val kept: List<Int>
        if (outline.closed) {
            // A ring has no ends, so it is cut at two vertices far apart and each half simplified
            // with both its ends kept: the vertex furthest from the first, and the vertex furthest
            // from that one.
            var far = 0
            var farthest = -1.0
            for (vertex in 0 until count) {
                val distance = lengthOf(xs[vertex] - xs[0], ys[vertex] - ys[0])
                if (distance > farthest) { farthest = distance; far = vertex }
            }
            var other = 0
            farthest = -1.0
            for (vertex in 0 until count) {
                val distance = lengthOf(xs[vertex] - xs[far], ys[vertex] - ys[far])
                if (distance > farthest) { farthest = distance; other = vertex }
            }
            val first = minOf(far, other)
            val second = maxOf(far, other)
            if (first == second) return emptyList()
            // Walked as one open line from `first` round to `first` again, the far vertex kept.
            val order = IntArray(count + 1) { (first + it) % count }
            val split = second - first
            val keep = BooleanArray(count + 1)
            keep[0] = true
            keep[split] = true
            keep[count] = true
            simplify(xs, ys, order, 0, split, toleranceKm, keep)
            simplify(xs, ys, order, split, count, toleranceKm, keep)
            kept = (0..count).filter { keep[it] }.map { order[it] }
        } else {
            val order = IntArray(count) { it }
            val keep = BooleanArray(count)
            keep[0] = true
            keep[count - 1] = true
            simplify(xs, ys, order, 0, count - 1, toleranceKm, keep)
            kept = (0 until count).filter { keep[it] }
        }
        val runs = ArrayList<Run>(kept.size)
        for (at in 0 until kept.size - 1) {
            val from = kept[at]
            val to = kept[at + 1]
            runs.add(Run(xs[from], ys[from], xs[to], ys[to], index, at, from, to).withBearing(frame))
        }
        return runs
    }

    /**
     * Douglas-Peucker between positions [first] and [last] of [order], marking what it keeps.
     * An explicit stack, since a long coast is a recursion as deep as it is long in the worst case.
     */
    private fun simplify(
        xs: DoubleArray,
        ys: DoubleArray,
        order: IntArray,
        first: Int,
        last: Int,
        toleranceKm: Double,
        keep: BooleanArray
    ) {
        val stack = ArrayDeque<Int>()
        stack.addLast(first)
        stack.addLast(last)
        while (stack.isNotEmpty()) {
            val end = stack.removeLast()
            val start = stack.removeLast()
            if (end <= start + 1) continue
            val ax = xs[order[start]]
            val ay = ys[order[start]]
            val bx = xs[order[end]]
            val by = ys[order[end]]
            var farthest = toleranceKm
            var farthestAt = -1
            for (position in start + 1 until end) {
                val distance = distanceToSegmentKm(xs[order[position]], ys[order[position]], ax, ay, bx, by)
                if (distance > farthest) { farthest = distance; farthestAt = position }
            }
            if (farthestAt < 0) continue
            keep[farthestAt] = true
            stack.addLast(start)
            stack.addLast(farthestAt)
            stack.addLast(farthestAt)
            stack.addLast(end)
        }
    }

    /** Distance from a point to a segment — the segment, not the line through it. */
    fun distanceToSegmentKm(
        x: Double, y: Double,
        fromX: Double, fromY: Double,
        toX: Double, toY: Double
    ): Double {
        val runX = toX - fromX
        val runY = toY - fromY
        val lengthSquared = runX * runX + runY * runY
        val along = if (lengthSquared <= 0.0) 0.0
        else (((x - fromX) * runX + (y - fromY) * runY) / lengthSquared).coerceIn(0.0, 1.0)
        return lengthOf(x - (fromX + along * runX), y - (fromY + along * runY))
    }
}
