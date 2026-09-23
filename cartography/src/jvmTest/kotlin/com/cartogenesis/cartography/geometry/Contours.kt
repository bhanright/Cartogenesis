package com.cartogenesis.cartography.geometry

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * One traced line, in kilometres on the ground.
 *
 * `x` runs east from the western edge of the map and `y` south from its northern edge, and a line
 * that crosses the east-west seam is carried on across it rather than cut, so `x` may run past
 * the world's width or below zero. A [closed] line returns to its first point without repeating
 * it. A [belt] is a closed line that goes once round the world rather than enclosing anything —
 * the edge of a polar cap, or of a band that circles the globe — and has no inside to measure.
 * An open line ends where the map does, at a pole, or where the layer stops being defined.
 */
internal class Outline(
    val xKm: DoubleArray,
    val yKm: DoubleArray,
    val closed: Boolean,
    val belt: Boolean,
    /** Which level of a family of level lines this is, or 0 for a region's boundary. */
    val level: Int = 0
) {
    /** The same line, marked as the [level]th of its family. */
    fun atLevel(level: Int): Outline = Outline(xKm, yKm, closed, belt, level)

    val vertexCount: Int get() = xKm.size

    /** A ring that encloses something: closed, and not a belt. */
    val isRing: Boolean get() = closed && !belt

    fun lengthKm(): Double {
        var total = 0.0
        val last = if (closed) vertexCount else vertexCount - 1
        for (vertex in 0 until last) {
            val next = (vertex + 1) % vertexCount
            total += hypot(xKm[next] - xKm[vertex], yKm[next] - yKm[vertex])
        }
        return total
    }

    /** The shoelace area, in square kilometres, signed by the ring's direction. Zero if not a ring. */
    fun signedAreaKm2(): Double {
        if (!isRing) return 0.0
        var twice = 0.0
        for (vertex in 0 until vertexCount) {
            val next = (vertex + 1) % vertexCount
            twice += xKm[vertex] * yKm[next] - xKm[next] * yKm[vertex]
        }
        return twice / 2.0
    }

    /** The same line in the grid's own frame, cells across and down, for the measures a reader
     * judges on the sheet. */
    fun inCells(frame: GridFrame): Pair<DoubleArray, DoubleArray> =
        DoubleArray(vertexCount) { xKm[it] / frame.cellWidthKm } to
            DoubleArray(vertexCount) { yKm[it] / frame.cellHeightKm }

    fun centroidKm(): Pair<Double, Double> = xKm.average() to yKm.average()
}

/**
 * The boundary of a region of the grid, or a level line of a field on it, traced as lines.
 *
 * Marching squares over the lattice of cell centres, which is the trace the map's own coastline
 * uses ([com.cartogenesis.cartography.Shoreline.trace]); where it differs from that one it is on
 * purpose. It wraps east to west, since the world does and a body lying across the seam is one
 * body. It carries no line along the northern and southern edges, because the polar rows are
 * where the map stops, not a shore; a region running off the top of the map leaves an open line
 * that ends there. Where four cells meet in a checkerboard it joins the diagonal inside cells,
 * so ground touching corner to corner is one region, which is what the flow routing assumes.
 *
 * A region's boundary crosses each cell edge at its midpoint, so its vertices lie on the
 * half-cell lattice and a boundary running along a row comes out exactly straight — which is
 * what the detectors look for. A field's level line crosses each edge where linear
 * interpolation puts the level.
 *
 * Every segment is oriented with the region on the same side, so a ring's signed area tells an
 * outer boundary from a hole: [OUTER_RING_SIGN] is the sign an outer boundary carries.
 */
internal object Contours {

    /** The sign of [Outline.signedAreaKm2] on the outer boundary of a region; holes carry the other. */
    const val OUTER_RING_SIGN = -1.0

    /** The boundary of the cells [inside] marks, where [valid] (if given) says the layer exists. */
    fun ofMask(inside: BooleanArray, frame: GridFrame, valid: BooleanArray? = null): List<Outline> =
        trace(frame, inside, null, 0f, valid)

    /** Where [field] crosses [level]; `field >= level` is inside. */
    fun ofField(
        field: FloatArray,
        level: Float,
        frame: GridFrame,
        valid: BooleanArray? = null
    ): List<Outline> {
        val inside = BooleanArray(field.size) { field[it] >= level }
        return trace(frame, inside, field, level, valid)
    }

    private fun trace(
        frame: GridFrame,
        inside: BooleanArray,
        field: FloatArray?,
        level: Float,
        valid: BooleanArray?
    ): List<Outline> {
        val across = frame.cellsAcross
        val down = frame.cellsDown
        val cellCount = across * down
        // Horizontal edges join a cell to its eastern neighbour and are numbered by the western
        // cell; vertical edges join a cell to its southern neighbour and follow them, numbered by
        // the northern cell.
        val edgeCount = 2 * cellCount
        // Two edge-indexed tables, reused from trace to trace and put back to NONE afterwards by
        // the segments that wrote them, because a census traces a 2048 grid a few hundred times.
        val tables = edgeTables(edgeCount)
        val segmentStartingAt = tables.first
        val segmentEndingAt = tables.second
        var starts = IntArray(1024)
        var ends = IntArray(1024)
        var segments = 0

        fun addSegment(fromEdge: Int, toEdge: Int) {
            if (segments == starts.size) {
                starts = starts.copyOf(segments * 2)
                ends = ends.copyOf(segments * 2)
            }
            starts[segments] = fromEdge
            ends[segments] = toEdge
            segmentStartingAt[fromEdge] = segments
            segmentEndingAt[toEdge] = segments
            segments++
        }

        val edges = IntArray(4)
        val entering = BooleanArray(4)
        val crossed = IntArray(4)
        val corners = IntArray(4)
        for (blockRow in 0 until down - 1) {
            for (blockColumn in 0 until across) {
                val east = if (blockColumn + 1 == across) 0 else blockColumn + 1
                val topLeft = blockRow * across + blockColumn
                val topRight = blockRow * across + east
                val bottomRight = (blockRow + 1) * across + east
                val bottomLeft = (blockRow + 1) * across + blockColumn
                if (valid != null &&
                    !(valid[topLeft] && valid[topRight] && valid[bottomRight] && valid[bottomLeft])
                ) continue
                corners[0] = topLeft
                corners[1] = topRight
                corners[2] = bottomRight
                corners[3] = bottomLeft
                // Clockwise on the sheet: top, right, bottom, left, each walked from the first
                // corner named to the second.
                edges[0] = topLeft
                edges[1] = cellCount + topRight
                edges[2] = bottomLeft
                edges[3] = cellCount + topLeft
                var count = 0
                for (side in 0 until 4) {
                    val from = inside[corners[side]]
                    val to = inside[corners[(side + 1) % 4]]
                    if (from != to) {
                        crossed[count] = side
                        entering[count] = to
                        count++
                    }
                }
                when (count) {
                    0 -> Unit
                    2 -> {
                        val (into, outOf) =
                            if (entering[0]) crossed[0] to crossed[1] else crossed[1] to crossed[0]
                        addSegment(edges[into], edges[outOf])
                    }
                    4 -> {
                        // A saddle. Each edge where the walk enters the region is joined to the
                        // edge before it, which leaves the two inside corners connected.
                        for (index in 0 until 4) {
                            if (!entering[index]) continue
                            val previous = (index + 3) % 4
                            addSegment(edges[crossed[index]], edges[crossed[previous]])
                        }
                    }
                    else -> error("marching squares crossed $count edges of one block")
                }
            }
        }

        // Where along the edge from cell [from] to cell [to] the line crosses, 0 to 1: halfway for a
        // region, where linear interpolation puts the level for a field.
        fun crossingFraction(from: Int, to: Int): Double {
            if (field == null) return HALF_A_CELL
            val span = field[to] - field[from]
            if (span == 0f) return HALF_A_CELL
            return ((level - field[from]) / span).toDouble().coerceIn(0.0, 1.0)
        }

        fun crossingX(edge: Int): Double {
            if (edge < cellCount) {
                val column = edge % across
                val fraction = crossingFraction(edge, (edge / across) * across + (column + 1) % across)
                return column + HALF_A_CELL + fraction
            }
            return (edge - cellCount) % across + HALF_A_CELL
        }

        fun crossingY(edge: Int): Double {
            if (edge < cellCount) return edge / across + HALF_A_CELL
            val north = edge - cellCount
            val fraction = crossingFraction(north, north + across)
            return north / across + HALF_A_CELL + fraction
        }

        val visited = BooleanArray(segments)
        val lines = ArrayList<Outline>()

        fun walkFrom(first: Int, closedLoop: Boolean) {
            val xs = DoubleArrayBuilder()
            val ys = DoubleArrayBuilder()
            var segment = first
            var previousX = crossingX(starts[first])
            xs.add(previousX)
            ys.add(crossingY(starts[first]))
            while (true) {
                visited[segment] = true
                val edge = ends[segment]
                var x = crossingX(edge)
                // Carried across the seam rather than jumping a world's width back.
                while (x - previousX > across / 2.0) x -= across
                while (previousX - x > across / 2.0) x += across
                val next = segmentStartingAt[edge]
                if (closedLoop && next == first) {
                    val offset = x - xs[0]
                    val belt = abs(offset) > across / 2.0
                    lines.add(outlineOf(xs, ys, frame, closed = true, belt = belt))
                    return
                }
                xs.add(x)
                ys.add(crossingY(edge))
                previousX = x
                if (next == NONE || visited[next]) {
                    lines.add(outlineOf(xs, ys, frame, closed = false, belt = false))
                    return
                }
                segment = next
            }
        }

        // Open lines first, from the end that has nothing before it.
        for (segment in 0 until segments) {
            if (!visited[segment] && segmentEndingAt[starts[segment]] == NONE) walkFrom(segment, false)
        }
        for (segment in 0 until segments) {
            if (!visited[segment]) walkFrom(segment, true)
        }
        for (segment in 0 until segments) {
            segmentStartingAt[starts[segment]] = NONE
            segmentEndingAt[ends[segment]] = NONE
        }
        return lines
    }

    private val reusedTables = ThreadLocal<Pair<IntArray, IntArray>>()

    private fun edgeTables(edgeCount: Int): Pair<IntArray, IntArray> {
        val held = reusedTables.get()
        if (held != null && held.first.size == edgeCount) return held
        val fresh = IntArray(edgeCount) { NONE } to IntArray(edgeCount) { NONE }
        reusedTables.set(fresh)
        return fresh
    }

    private fun outlineOf(
        xs: DoubleArrayBuilder,
        ys: DoubleArrayBuilder,
        frame: GridFrame,
        closed: Boolean,
        belt: Boolean
    ): Outline = Outline(
        DoubleArray(xs.size) { xs[it] * frame.cellWidthKm },
        DoubleArray(ys.size) { ys[it] * frame.cellHeightKm },
        closed, belt
    )

    private const val NONE = -1
    private const val HALF_A_CELL = 0.5
}

/** A growable array of doubles, so a long line is not boxed a vertex at a time. */
internal class DoubleArrayBuilder {
    private var values = DoubleArray(64)
    var size = 0
        private set

    fun add(value: Double) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size++] = value
    }

    operator fun get(index: Int): Double = values[index]

    fun toArray(): DoubleArray = values.copyOf(size)
}

/** Euclidean length of a vector, for readability where a pair of differences is at hand. */
internal fun lengthOf(dx: Double, dy: Double): Double = sqrt(dx * dx + dy * dy)
