package com.cartogenesis.cartography.geometry

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Detectors 2 and 3, and the local subshapes of 1: what a layer's outlines look like, place by
 * place, against the stamps the grid makes.
 *
 * Two kinds of place. A **ring** — a lake, a basin, an ice body, a lobe — is measured whole for
 * its rectangularity: its area over its smallest bounding rectangle on the ground, and whether that
 * rectangle lies along the grid. A **window** is about [WINDOW_CELL_WIDTHS] of any line, a ring's
 * or an open line's alike — a river course, a contour clipped at a sheet's edge or at the map's
 * pole — and is measured for the local shapes: its longest stretch lying exactly along a grid line
 * ([LatticeRuns]), its longest straight run at any bearing, its right-angle corners and corner
 * pairs, and its strongest crease, two straight runs meeting at an angle. Each is found on the whole
 * line and counted in the window its midpoint falls in, so no side is cut by a window's edge; the
 * window is only the unit of place. So one long grid-aligned reach cannot hide inside a long
 * natural river or a continent's coast: every window is held to the same bar, the natural controls'
 * own maximum over one window ([NaturalTails]), whatever the size of the thing it is a window of.
 */
internal object ComponentShapes {

    /**
     * The declared bar on rectangularity: area over the smallest bounding rectangle on the ground.
     *
     * An ellipse fills pi/4 = 0.785 of its bounding rectangle and a rougher shape fills less; a
     * rasterised rectangle fills 0.99 and more. The bar is set between the two, and the census holds
     * a ring to the larger of this and the natural controls' own figures ([NaturalTails]). Only a
     * ring whose rectangle also lies along the grid is a violation (see [rings]): a rectangle at any
     * other bearing on the ground is reported and not rejected, which is G2's point that a stamp
     * turned off the grid stops being flagged for alignment.
     */
    const val RECTANGLE_FILL = 0.9

    /**
     * How many cells across its narrow side a ring's bounding rectangle must be, measured across
     * the rectangle's long side ([GridFrame.cellAcrossKm]): nine.
     *
     * Each side of the rectangle rests on the traced outline's outermost points, and each of those
     * lies within half a cell of the edge it stands for, so the rectangle's narrow side is known to
     * one cell in the worst case and the fill to about one part in the width. The gap between the
     * bar and the roundest shape a natural ring makes, the ellipse's 0.785, is 0.115, and a worst
     * case of `1 / w` stays inside it from `w = 1 / 0.115 = 8.7`, so nine cells.
     */
    const val MINIMUM_WIDTH_CELLS = 9.0

    /**
     * The smallest ring measured for its fill, in cells of area: 64, the area of the smallest ring
     * the width gate admits when it is round, a disc nine cells across (`pi / 4 * 81 = 63.6`).
     *
     * It is a count of cells and not an area on the ground because the accident it guards against,
     * a small blob filling its rectangle, is the raster's: 64 cells are 17,600 km2 at 512 and
     * 1,100 km2 at 2048, and nine cells across a row are 105 km at 512 and 26 km at 2048.
     */
    const val MINIMUM_AREA_CELLS = 64.0

    /**
     * The fewest traced vertices a measured ring can have: 32. A ring nine cells across in both
     * directions crosses at least nine rows and nine columns of cell edges on each side, and the
     * trace puts a vertex on every edge it crosses, so it has 36 at the least; 32 leaves room for
     * the level lines' interpolated crossings that fall on a cell's corner and merge. Not a gate of
     * its own — every ring past the width gate has this many — but stated, as G4 asks, and checked.
     */
    const val MINIMUM_VERTICES = 32

    /**
     * How much line one window holds, in cell widths: 64, give or take half of that, since a line is
     * divided into whole windows of equal length.
     *
     * Any length would do so long as the controls are read in windows of the same length, since a
     * window's bar is the natural maximum over one window. At 64 a coast or a river is many windows,
     * so a stamp is judged against its own window and not averaged over its whole line, and at 2048
     * a window is 375 km of line, the size of a feature a reader picks out. A line shorter than a
     * window is one window of its own length, so no line goes unread.
     */
    const val WINDOW_CELL_WIDTHS = 64.0

    /**
     * The shortest straight run that can be an arm of a crease, in cell widths: four, at which a
     * run's direction is known to `atan(1.1 / 4)` = 15 degrees either way at the simplification's
     * tolerance ([StraightRuns.TOLERANCE_CELL_WIDTHS]). Under it, the turn between two runs is the
     * raster's own.
     */
    const val CREASE_ARM_CELL_WIDTHS = 4.0

    class Ring(
        val index: Int,
        val areaKm2: Double,
        val areaCells: Double,
        val centreXKm: Double,
        val centreYKm: Double,
        val measured: Boolean,
        val fill: Double,
        /** Whether the rectangle's long side lies along a grid bearing on the ground, to its own resolution. */
        val alignedRectangle: Boolean,
        /** The ground bearing of the rectangle's long side, in degrees. */
        val rectangleBearingDegrees: Double,
        val rectangleLongKm: Double,
        val rectangleShortKm: Double,
        /** Cells across the rectangle's narrow side. */
        val cellsAcross: Double
    ) {
        fun where(frame: GridFrame): String = cellAt(centreXKm, centreYKm, frame)

        fun describe(frame: GridFrame): String =
            "ring at %s, %.0f cells, fill %.3f, %s rectangle %.0f by %.0f km at %.1f deg, %.1f cells across".format(
                where(frame), areaCells, fill, if (alignedRectangle) "aligned" else "unaligned",
                rectangleLongKm, rectangleShortKm, rectangleBearingDegrees, cellsAcross
            )
    }

    /** One window of one line and what it holds. Positions are the midpoints of what they name. */
    class Window(
        val outline: Int,
        val atKm: Pair<Double, Double>,
        val lengthKm: Double,
        /** The longest stretch along one grid line, in steps of the grid along its bearing. */
        val alignedSteps: Double,
        val alignedBearing: Int,
        val alignedAtKm: Pair<Double, Double>,
        /** The longest straight run at any bearing, in cell widths. */
        val runCellWidths: Double,
        val runBearingDegrees: Double,
        val runAtKm: Pair<Double, Double>,
        val rightAngles: Int,
        val cornerPairs: Int,
        val cornerPairAtKm: Pair<Double, Double>,
        /** The strongest crease; see [creaseStrength]. Zero where no two arms long enough meet. */
        val creaseStrength: Double,
        val creaseTurnDegrees: Double,
        val creaseAtKm: Pair<Double, Double>,
        /** How many junctions of two runs both at least [CREASE_ARM_CELL_WIDTHS] long the window holds. */
        val creaseJunctions: Int
    )

    /** Every outer ring of [outlines], measured for its fill. */
    fun rings(outlines: List<Outline>, frame: GridFrame): List<Ring> {
        val rings = ArrayList<Ring>()
        outlines.forEachIndexed { index, outline ->
            if (!outline.isRing) return@forEachIndexed
            val signed = outline.signedAreaKm2()
            if (signed * Contours.OUTER_RING_SIGN <= 0.0) return@forEachIndexed
            val areaKm2 = abs(signed)
            val areaCells = areaKm2 / (frame.cellWidthKm * frame.cellHeightKm)
            val (centreX, centreY) = outline.centroidKm()
            // On the ground, as G2 asks: the smallest rectangle is not the same rectangle once the
            // cells are stretched to their true shape.
            val rectangle = MinimumRectangle.of(outline.xKm, outline.yKm)
            val cellsAcrossNarrowSide = if (rectangle.area <= 0.0) 0.0
            else rectangle.shortSide / frame.cellAcrossKm(rectangle.angleDegrees)
            val measured = areaCells >= MINIMUM_AREA_CELLS &&
                cellsAcrossNarrowSide >= MINIMUM_WIDTH_CELLS &&
                outline.vertexCount >= MINIMUM_VERTICES
            // Aligned when the long side lies within one cell of skew, over its own length, of a
            // grid bearing — the raster's own resolution of the rectangle's angle.
            val tolerated = Math.toDegrees(atan(frame.cellAcrossKm(rectangle.angleDegrees) / rectangle.longSide.coerceAtLeast(1e-9)))
                .coerceAtLeast(MINIMUM_ALIGNMENT_DEGREES)
            val offGrid = frame.gridBearings.minOf { frame.bearingGapDegrees(rectangle.angleDegrees, it) }
            rings.add(
                Ring(
                    index, areaKm2, areaCells, centreX, centreY, measured,
                    fill = if (rectangle.area > 0) areaKm2 / rectangle.area else 0.0,
                    alignedRectangle = offGrid <= tolerated,
                    rectangleBearingDegrees = rectangle.angleDegrees,
                    rectangleLongKm = rectangle.longSide,
                    rectangleShortKm = rectangle.shortSide,
                    cellsAcross = cellsAcrossNarrowSide
                )
            )
        }
        return rings
    }

    /** Every window of every line of [outlines], rings and open lines alike, measured. */
    fun windows(outlines: List<Outline>, frame: GridFrame): List<Window> {
        val tolerance = StraightRuns.toleranceKm(frame)
        val windowKm = WINDOW_CELL_WIDTHS * frame.cellWidthKm
        val shortestArm = CREASE_ARM_CELL_WIDTHS * frame.cellWidthKm
        val windows = ArrayList<Window>()
        outlines.forEachIndexed { index, outline ->
            val count = outline.vertexCount
            if (count < 2) return@forEachIndexed
            val wraps = outline.isRing
            // How far along the line each vertex lies, in km.
            val along = DoubleArray(count)
            for (vertex in 1 until count) {
                along[vertex] = along[vertex - 1] +
                    lengthOf(outline.xKm[vertex] - outline.xKm[vertex - 1], outline.yKm[vertex] - outline.yKm[vertex - 1])
            }
            val total = along[count - 1] + if (wraps) lengthOf(outline.xKm[0] - outline.xKm[count - 1], outline.yKm[0] - outline.yKm[count - 1]) else 0.0
            if (total <= 0.0) return@forEachIndexed
            // Whole windows of one length, the nearest whole number of them to the line's length.
            val slots = Math.round(total / windowKm).toInt().coerceAtLeast(1)
            val slotKm = total / slots
            fun slotOf(position: Double): Int = ((((position % total) + total) % total) / slotKm).toInt().coerceIn(0, slots - 1)
            fun midway(first: Int, last: Int): Double {
                var end = along[last]
                if (wraps && last < first) end += total
                return (along[first] + end) / 2
            }
            fun pointAt(first: Int, last: Int): Pair<Double, Double> =
                (outline.xKm[first] + outline.xKm[last]) / 2 to (outline.yKm[first] + outline.yKm[last]) / 2

            val alignedSteps = DoubleArray(slots)
            val alignedBearing = IntArray(slots) { -1 }
            val alignedAt = arrayOfNulls<Pair<Double, Double>>(slots)
            val stretches = LatticeRuns.of(outline, frame, 0.0)
            for (stretch in stretches) {
                val slot = slotOf(midway(stretch.first, stretch.last))
                if (stretch.steps > alignedSteps[slot]) {
                    alignedSteps[slot] = stretch.steps
                    alignedBearing[slot] = stretch.bearingIndex
                    alignedAt[slot] = pointAt(stretch.first, stretch.last)
                }
            }
            val runCells = DoubleArray(slots)
            val runBearing = DoubleArray(slots)
            val runAt = arrayOfNulls<Pair<Double, Double>>(slots)
            val runs = StraightRuns.of(outline, index, frame, tolerance)
            for (run in runs) {
                val slot = slotOf(midway(run.fromVertex, run.toVertex))
                val cells = run.lengthKm / frame.cellWidthKm
                if (cells > runCells[slot]) {
                    runCells[slot] = cells
                    runBearing[slot] = run.bearingDegrees
                    runAt[slot] = run.midXKm to run.midYKm
                }
            }
            val corners = LatticeRuns.corners(stretches, count, wraps, frame)
            val rightAngles = IntArray(slots)
            for ((before, _) in corners) rightAngles[slotOf(along[before.last])]++
            val pairs = IntArray(slots)
            val pairAt = arrayOfNulls<Pair<Double, Double>>(slots)
            for (side in LatticeRuns.cornerPairs(corners)) {
                val slot = slotOf(midway(side.first, side.last))
                pairs[slot]++
                pairAt[slot] = pointAt(side.first, side.last)
            }
            val crease = DoubleArray(slots)
            val creaseTurn = DoubleArray(slots)
            val creaseAt = arrayOfNulls<Pair<Double, Double>>(slots)
            val junctions = IntArray(slots)
            val pairsOfRuns = if (wraps && runs.size > 2) runs.size else runs.size - 1
            for (at in 0 until pairsOfRuns) {
                val first = runs[at]
                val second = runs[(at + 1) % runs.size]
                if (first.lengthKm < shortestArm || second.lengthKm < shortestArm) continue
                val turn = turnDegrees(first, second)
                val strength = creaseStrength(minOf(first.lengthKm, second.lengthKm), turn, tolerance)
                val slot = slotOf(along[first.toVertex])
                junctions[slot]++
                if (strength > crease[slot]) {
                    crease[slot] = strength
                    creaseTurn[slot] = turn
                    creaseAt[slot] = first.toXKm to first.toYKm
                }
            }
            for (slot in 0 until slots) {
                val vertex = firstAtOrPast(along, slotKm * (slot + 0.5))
                val here = outline.xKm[vertex] to outline.yKm[vertex]
                windows.add(
                    Window(
                        index, here, slotKm,
                        alignedSteps[slot], alignedBearing[slot], alignedAt[slot] ?: here,
                        runCells[slot], runBearing[slot], runAt[slot] ?: here,
                        rightAngles[slot], pairs[slot], pairAt[slot] ?: here,
                        crease[slot], creaseTurn[slot], creaseAt[slot] ?: here, junctions[slot]
                    )
                )
            }
        }
        return windows
    }

    /**
     * How strongly two straight runs meeting at [turnDegrees] make a crease: the shorter arm times
     * the turn in radians, over eight times the simplification's tolerance.
     *
     * Douglas-Peucker at tolerance `t` cuts a circle of radius `R` into chords whose sagitta is `t`:
     * a chord `L = R * theta` long, turning `theta = sqrt(8 t / R)` at each vertex, so `L * theta =
     * 8 t` whatever the radius. Every smooth curve is locally a circle, so the polygon Douglas-
     * Peucker makes of any smooth line has a strength of 1 at most at every junction, and a line
     * that bends by curving, however tightly, never reads more. Two straight arms meeting at an angle
     * — the edge of a pyramid, a level line crossing a ridge the grid drew — read their arms' length
     * times their turn, however long the arms are.
     */
    fun creaseStrength(shorterArmKm: Double, turnDegrees: Double, toleranceKm: Double): Double =
        shorterArmKm * Math.toRadians(turnDegrees) / (8.0 * toleranceKm)

    /** The first index of the ascending [values] at or past [target], or the last index. */
    private fun firstAtOrPast(values: DoubleArray, target: Double): Int {
        var low = 0
        var high = values.size - 1
        if (values[high] < target) return high
        while (low < high) {
            val middle = (low + high) ushr 1
            if (values[middle] >= target) high = middle else low = middle + 1
        }
        return low
    }

    /** The turn from one run's direction to the next's along the line, in degrees on the ground, 0 to 180. */
    fun turnDegrees(first: Run, second: Run): Double {
        val ax = first.toXKm - first.fromXKm
        val ay = first.toYKm - first.fromYKm
        val bx = second.toXKm - second.fromXKm
        val by = second.toYKm - second.fromYKm
        val cosine = ((ax * bx + ay * by) / (lengthOf(ax, ay) * lengthOf(bx, by))).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cosine))
    }

    /**
     * The least tolerance on a rectangle's alignment, in degrees: under it, the minimum-area
     * rectangle's own angle is not known that well on a traced outline.
     */
    private const val MINIMUM_ALIGNMENT_DEGREES = 1.0
}

/** A place on the grid, as `(column,row)`, from kilometres, with the seam wrapped. */
internal fun cellAt(xKm: Double, yKm: Double, frame: GridFrame): String {
    var column = (xKm / frame.cellWidthKm) % frame.cellsAcross
    if (column < 0) column += frame.cellsAcross
    return "(%d,%d)".format(column.toInt(), (yKm / frame.cellHeightKm).toInt())
}

/** The same place as a column and a row. */
internal fun cellOf(at: Pair<Double, Double>, frame: GridFrame): Pair<Int, Int> {
    var column = (at.first / frame.cellWidthKm) % frame.cellsAcross
    if (column < 0) column += frame.cellsAcross
    return column.toInt() to (at.second / frame.cellHeightKm).toInt()
}

/** The spacing of the lattice *along* a grid bearing: one step of the grid in that direction. */
internal fun GridFrame.latticeSpacingAlong(bearingIndex: Int): Double = when (bearingIndex) {
    0 -> cellWidthKm
    2 -> cellHeightKm
    else -> sqrt(cellWidthKm * cellWidthKm + cellHeightKm * cellHeightKm)
}

/**
 * The smallest-area rectangle bounding a set of points, at any orientation: rotating calipers over
 * the convex hull, since one side of the minimum rectangle lies along a hull edge (Freeman and
 * Shapira, 1975).
 */
internal class MinimumRectangle(val area: Double, val angleDegrees: Double, val longSide: Double, val shortSide: Double) {
    companion object {
        fun of(xs: DoubleArray, ys: DoubleArray): MinimumRectangle {
            val hull = ConvexHull.of(xs, ys)
            if (hull.size < 3) return MinimumRectangle(0.0, 0.0, 0.0, 0.0)
            var best = MinimumRectangle(Double.MAX_VALUE, 0.0, 0.0, 0.0)
            for (edge in hull.indices) {
                val (ax, ay) = hull[edge]
                val (bx, by) = hull[(edge + 1) % hull.size]
                val length = lengthOf(bx - ax, by - ay)
                if (length <= 0.0) continue
                val ux = (bx - ax) / length
                val uy = (by - ay) / length
                var minAlong = Double.MAX_VALUE
                var maxAlong = -Double.MAX_VALUE
                var minAcross = Double.MAX_VALUE
                var maxAcross = -Double.MAX_VALUE
                for ((px, py) in hull) {
                    val along = (px - ax) * ux + (py - ay) * uy
                    val across = -(px - ax) * uy + (py - ay) * ux
                    if (along < minAlong) minAlong = along
                    if (along > maxAlong) maxAlong = along
                    if (across < minAcross) minAcross = across
                    if (across > maxAcross) maxAcross = across
                }
                val width = maxAlong - minAlong
                val height = maxAcross - minAcross
                val area = width * height
                if (area < best.area) {
                    var angle = Math.toDegrees(atan2(uy, ux))
                    if (width < height) angle += 90.0
                    angle = ((angle % 180.0) + 180.0) % 180.0
                    best = MinimumRectangle(area, angle, maxOf(width, height), minOf(width, height))
                }
            }
            return best
        }
    }
}

/** Andrew's monotone chain, counter-clockwise, over distinct points. */
internal object ConvexHull {
    fun of(xs: DoubleArray, ys: DoubleArray): List<Pair<Double, Double>> {
        val points = xs.indices.map { xs[it] to ys[it] }.distinct()
            .sortedWith(compareBy({ it.first }, { it.second }))
        if (points.size < 3) return points
        fun turn(o: Pair<Double, Double>, a: Pair<Double, Double>, b: Pair<Double, Double>): Double =
            (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)
        val lower = ArrayList<Pair<Double, Double>>()
        for (point in points) {
            while (lower.size >= 2 && turn(lower[lower.size - 2], lower[lower.size - 1], point) <= 0) lower.removeAt(lower.size - 1)
            lower.add(point)
        }
        val upper = ArrayList<Pair<Double, Double>>()
        for (point in points.reversed()) {
            while (upper.size >= 2 && turn(upper[upper.size - 2], upper[upper.size - 1], point) <= 0) upper.removeAt(upper.size - 1)
            upper.add(point)
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }
}

