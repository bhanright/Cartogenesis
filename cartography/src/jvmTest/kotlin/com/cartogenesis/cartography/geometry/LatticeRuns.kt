package com.cartogenesis.cartography.geometry

/**
 * A stretch of a traced line lying along one of the grid's own lines: a row, a column or a
 * diagonal. [first] and [last] are vertex indices of the line it came from, [lengthKm] the
 * distance between them and [steps] that distance in steps of the grid along its bearing.
 */
internal class Stretch(
    val bearingIndex: Int,
    val first: Int,
    val last: Int,
    val lengthKm: Double,
    val steps: Double
)

/**
 * Where a traced line runs exactly along a grid line, and where two such stretches meet square.
 *
 * Exactly means every traced vertex of the stretch, not just its two ends, lies within
 * [BAND_CELLS] of one line of the grid — one row, one column or one diagonal of cells. The trace
 * puts a region's vertices on the half-cell lattice, so a side a stamp laid along a row is a run of
 * vertices with one `y` and nothing else is; a natural edge crossing the rows at any angle steps
 * off its line within a few cells, the flat stretch a curve makes where it touches a row being the
 * curvature argument's `2 * sqrt(2 R)` cells and no longer. That is the grid's own geometry and
 * nothing else, which the looser test of a straight run's two ends lying on one line is not:
 * Douglas-Peucker's runs at the tolerance that swallows a staircase can span a wandering stretch of
 * natural coast whose ends happen to fall on one row.
 *
 * A right-angle corner is a stretch along one grid bearing followed within [CORNER_GAP_VERTICES]
 * vertices — the trace's own corner cut, or a stamp's one-cell tip — by a stretch along a bearing
 * square to it on the sheet ([GridFrame.perpendicularPairs]), both at least [CORNER_STEPS] steps
 * of the grid long. A corner pair is a stretch with such a corner at each end: a stamp's side with
 * its two corners.
 */
internal object LatticeRuns {

    /**
     * How far off one grid line a vertex may lie and still be on it, in cells across the line.
     * A quarter admits the rounding of an interpolated level line and nothing that has stepped to
     * the next lattice line, which is half a cell away at the least (a corner cut's midpoint).
     */
    const val BAND_CELLS = 0.25

    /** How many vertices may lie between the two stretches of a corner: the corner cut, a tip. */
    const val CORNER_GAP_VERTICES = 3

    /**
     * The shortest stretch that can make a right-angle corner, in steps of the grid.
     *
     * From the control ensemble: `GeometryControlTest` prints every corner pair its natural
     * outlines make with arms of any length, and the longest shortest arm among them is four steps
     * — the raster's own corner where a curve turns across a row and a column within a few cells.
     * Six stands clear of it; the stamps' sides run eight steps and more.
     */
    const val CORNER_STEPS = 6.0

    /** The line's position across each grid bearing, in cells, and its position along it. */
    private fun across(bearingIndex: Int, columns: Double, rows: Double): Double = when (bearingIndex) {
        0 -> rows
        2 -> columns
        1 -> columns - rows
        else -> columns + rows
    }

    /** Every maximal stretch of [outline] along each grid bearing at least [minimumSteps] long. */
    fun of(outline: Outline, frame: GridFrame, minimumSteps: Double): List<Stretch> {
        val count = outline.vertexCount
        if (count < 2) return emptyList()
        val (columns, rows) = outline.inCells(frame)
        val stretches = ArrayList<Stretch>()
        // A ring is walked twice over so a stretch may run through its first vertex; a stretch may
        // not be longer than the line itself. An open line, a belt round the world among them, is
        // walked once.
        val ring = outline.isRing
        val walked = if (ring) 2 * count else count
        for (bearing in frame.gridBearings.indices) {
            val position = DoubleArray(walked) { across(bearing, columns[it % count], rows[it % count]) }
            // Two monotonic deques over the window hold its least and greatest position.
            val low = ArrayDeque<Int>()
            val high = ArrayDeque<Int>()
            var start = 0
            var recordedThrough = -1
            val seen = HashSet<Int>()
            fun record(from: Int, to: Int) {
                if (to - from < 1 || from >= count && ring) return
                if (!seen.add(from % count)) return
                val a = from % count
                val b = to % count
                val lengthKm = lengthOf(outline.xKm[b] - outline.xKm[a], outline.yKm[b] - outline.yKm[a])
                val steps = lengthKm / frame.latticeSpacingAlong(bearing)
                if (steps >= minimumSteps) stretches.add(Stretch(bearing, a, b, lengthKm, steps))
            }
            for (end in 0 until walked) {
                while (low.isNotEmpty() && position[low.last()] >= position[end]) low.removeLast()
                low.addLast(end)
                while (high.isNotEmpty() && position[high.last()] <= position[end]) high.removeLast()
                high.addLast(end)
                while (position[high.first()] - position[low.first()] > BAND_CELLS || end - start >= count) {
                    // The window [start, end - 1] was the longest valid one ending before `end`.
                    // Only the first time: a later start is a shorter window inside this one.
                    if (end - 1 > start && end - 1 != recordedThrough && !(ring && start >= count)) {
                        record(start, end - 1)
                        recordedThrough = end - 1
                    }
                    start++
                    while (low.first() < start) low.removeFirst()
                    while (high.first() < start) high.removeFirst()
                }
            }
            if (!ring) record(start, walked - 1)
        }
        return stretches
    }

    /** The right-angle corners among [stretches] of one line of [count] vertices, as index pairs. */
    fun corners(
        stretches: List<Stretch>,
        count: Int,
        closed: Boolean,
        frame: GridFrame,
        minimumSteps: Double = CORNER_STEPS
    ): List<Pair<Stretch, Stretch>> {
        val long = stretches.filter { it.steps >= minimumSteps }
        val found = ArrayList<Pair<Stretch, Stretch>>()
        for (before in long) for (after in long) {
            if (before === after) continue
            val square = frame.perpendicularPairs.any { (a, b) ->
                (before.bearingIndex == a && after.bearingIndex == b) || (before.bearingIndex == b && after.bearingIndex == a)
            }
            if (!square) continue
            var gap = after.first - before.last
            if (closed && gap < -count / 2) gap += count
            if (closed && gap > count / 2) gap -= count
            if (gap in -1..CORNER_GAP_VERTICES) found.add(before to after)
        }
        return found
    }

    /** How many stretches among [corners] have a corner at each end. */
    fun cornerPairs(corners: List<Pair<Stretch, Stretch>>): List<Stretch> {
        val endingInACorner = corners.map { it.first }.toSet()
        val startingFromACorner = corners.map { it.second }.toSet()
        return endingInACorner.filter { it in startingFromACorner }
    }
}
