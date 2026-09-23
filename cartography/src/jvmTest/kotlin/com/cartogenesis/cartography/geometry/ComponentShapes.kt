package com.cartogenesis.cartography.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Detectors 2 and 3, and the local subshapes of 1: what one closed outline — a lake, a basin, an
 * ice body, a lobe — looks like against the stamps the grid makes.
 *
 * Four figures per ring. Its **rectangularity**: its area over the area of its smallest bounding
 * rectangle at any orientation, and whether that rectangle lies along the grid. Its **longest
 * aligned side**: the longest run lying exactly along a grid bearing, against the run a circle of
 * its own area makes there. Its **right-angle corners**: consecutive runs lying exactly along two
 * grid bearings that are square to one another on the sheet. And its **longest straight run** at
 * any bearing, against its own size, which is what a nearest-seed partition's facets show.
 *
 * A ring is measured only when it is big enough for its shape to be more than its raster: see
 * [MINIMUM_AREA_CELLS], [MINIMUM_WIDTH_CELLS] and [MINIMUM_VERTICES].
 */
internal object ComponentShapes {

    /**
     * How close to a grid line a run's two ends must lie to count as lying exactly along it, in
     * lattice spacings across the line.
     *
     * A stamp's side runs along one row, column or diagonal of the lattice, and the trace puts
     * its vertices on the half-cell lattice, so both ends lie on the same lattice line and the
     * offset is zero. Half a spacing admits the midpoint cut at each end and nothing that crosses
     * a lattice line: a run that steps across one is a staircase, which is what a natural edge at
     * a small angle to the grid is.
     */
    const val EXACT_OFFSET_SPACINGS = 0.5

    /**
     * How much longer than a circle's own the longest aligned side may be.
     *
     * The derivation is `OutlineRuns.STRAIGHTEST_SHORE_OVER_A_CIRCLE`'s, carried here rather than
     * shared because that instrument lives in `:worldgen`'s tests: Earth's straightest large lake
     * shore, Tanganyika's western scarp, runs about 100 km without a bend worth drawing at an
     * equivalent radius of 102 km (Hutchinson, *A Treatise on Limnology*, 1957), which is 2.05
     * times the run a circle of its own area makes at the same grid; three leaves a margin. A fault
     * has no reason to fall on one of the grid's bearings, so a side that long *and* aligned is
     * the grid. The circle's own run is measured by this same instrument on a rasterised disc
     * ([DiscFigures]), so the comparison is like for like.
     */
    const val STRAIGHTEST_SHORE_OVER_A_CIRCLE = 3.0

    /**
     * The bar on rectangularity for a ring aligned with the grid: area over bounding rectangle.
     *
     * From the control ensemble, not from any world: see `GeometryControlTest`, which prints the
     * largest fill any natural control of admissible size reached and asserts it below this. An
     * ellipse fills pi/4 = 0.785 of its bounding rectangle and a rougher shape fills less; the
     * stamps fill 0.95 and more. Set between the two.
     */
    const val RECTANGLE_FILL = 0.9

    /**
     * The shortest run that can make a right-angle corner, in lattice spacings along its own
     * bearing. From the controls: see `GeometryControlTest`.
     */
    const val CORNER_RUN_SPACINGS = 4.0

    /**
     * How long, as a share of the ring's equivalent diameter, the longest straight run at any
     * bearing may be. From the controls: see `GeometryControlTest`.
     */
    const val LONGEST_RUN_OVER_DIAMETER = 0.6

    /**
     * The smallest ring measured, in cells of area.
     *
     * A ring's rectangularity and its aligned side say something about the ground only when the
     * raster's own corners do not decide them. The natural controls' fill stays under
     * [RECTANGLE_FILL] from this size up; `GeometryControlTest` shows it.
     */
    const val MINIMUM_AREA_CELLS = 64.0

    /** The narrowest a ring's bounding rectangle may be and still be measured, in cells. */
    const val MINIMUM_WIDTH_CELLS = 4.0

    /** The fewest traced vertices a measured ring may have. */
    const val MINIMUM_VERTICES = 24

    class Ring(
        val index: Int,
        val areaKm2: Double,
        val areaCells: Double,
        val centreXKm: Double,
        val centreYKm: Double,
        val measured: Boolean,
        val fill: Double,
        val rectangleAngleDegrees: Double,
        val rectangleLongCells: Double,
        val rectangleShortCells: Double,
        val alignedRectangle: Boolean,
        val longestRunKm: Double,
        val equivalentDiameterKm: Double,
        val longestAlignedKm: Double,
        val longestAlignedBearing: Int,
        val alignedAllowanceKm: Double,
        val rightAngles: Int,
        val cornerPairs: Int
    ) {
        val isRectangle: Boolean get() = measured && fill >= RECTANGLE_FILL
        val isAlignedStamp: Boolean get() = isRectangle && alignedRectangle
        val hasLongAlignedSide: Boolean get() = measured && longestAlignedKm > alignedAllowanceKm
        val hasFacets: Boolean get() = measured && longestRunKm > LONGEST_RUN_OVER_DIAMETER * equivalentDiameterKm
        val hasCornerPair: Boolean get() = measured && cornerPairs > 0

        fun where(frame: GridFrame): String {
            var column = (centreXKm / frame.cellWidthKm) % frame.cellsAcross
            if (column < 0) column += frame.cellsAcross
            return "(%d,%d)".format(column.toInt(), (centreYKm / frame.cellHeightKm).toInt())
        }

        fun describe(frame: GridFrame): String =
            "ring at %s, %.0f cells: fill %.2f%s, longest aligned side %.0f km against %.0f allowed, %d right angles, %d corner pairs, longest run %.2f of its diameter".format(
                where(frame), areaCells, fill, if (alignedRectangle) " aligned" else "",
                longestAlignedKm, alignedAllowanceKm, rightAngles, cornerPairs,
                if (equivalentDiameterKm > 0) longestRunKm / equivalentDiameterKm else 0.0
            )
    }

    /** Every outer ring of [outlines], measured. Holes and open lines are not components. */
    fun measure(outlines: List<Outline>, frame: GridFrame, discs: DiscFigures = DiscFigures.of(frame)): List<Ring> {
        val tolerance = StraightRuns.toleranceKm(frame)
        val rings = ArrayList<Ring>()
        outlines.forEachIndexed { index, outline ->
            if (!outline.isRing) return@forEachIndexed
            val signed = outline.signedAreaKm2()
            if (signed * Contours.OUTER_RING_SIGN <= 0.0) return@forEachIndexed
            val areaKm2 = abs(signed)
            val areaCells = areaKm2 / (frame.cellWidthKm * frame.cellHeightKm)
            val (centreX, centreY) = outline.centroidKm()
            val (cellsX, cellsY) = outline.inCells(frame)
            val rectangle = MinimumRectangle.of(cellsX, cellsY)
            val runs = StraightRuns.of(outline, index, frame, tolerance)
            val measured = areaCells >= MINIMUM_AREA_CELLS &&
                rectangle.shortSide >= MINIMUM_WIDTH_CELLS &&
                outline.vertexCount >= MINIMUM_VERTICES

            var longestRun = 0.0
            var longestAligned = 0.0
            var longestAlignedBearing = -1
            val alignedTo = IntArray(runs.size) { -1 }
            runs.forEachIndexed { at, run ->
                if (run.lengthKm > longestRun) longestRun = run.lengthKm
                val bearing = alignedBearing(run, frame)
                alignedTo[at] = bearing
                if (bearing >= 0 && run.lengthKm > longestAligned) {
                    longestAligned = run.lengthKm
                    longestAlignedBearing = bearing
                }
            }
            val corners = BooleanArray(runs.size)
            var rightAngles = 0
            for (at in runs.indices) {
                val next = (at + 1) % runs.size
                if (next == at) continue
                if (isRightAngle(runs[at], alignedTo[at], runs[next], alignedTo[next], frame)) {
                    corners[at] = true
                    rightAngles++
                }
            }
            var pairs = 0
            for (at in runs.indices) {
                val previous = (at - 1 + runs.size) % runs.size
                if (corners[previous] && corners[at] && runs.size > 2) pairs++
            }
            val radiusKm = sqrt(areaKm2 / PI)
            val allowance = if (longestAlignedBearing >= 0)
                STRAIGHTEST_SHORE_OVER_A_CIRCLE * discs.longestAlignedKm(radiusKm, longestAlignedBearing)
            else Double.MAX_VALUE
            val angle = rectangle.angleDegrees
            val tolerated = Math.toDegrees(atan(1.0 / rectangle.longSide.coerceAtLeast(1.0)))
                .coerceAtLeast(MINIMUM_ALIGNMENT_DEGREES)
            val offGrid = minOf(angle % 45.0, 45.0 - angle % 45.0)
            rings.add(
                Ring(
                    index, areaKm2, areaCells, centreX, centreY, measured,
                    fill = if (rectangle.area > 0) areaCells / rectangle.area else 0.0,
                    rectangleAngleDegrees = angle,
                    rectangleLongCells = rectangle.longSide,
                    rectangleShortCells = rectangle.shortSide,
                    alignedRectangle = offGrid <= tolerated,
                    longestRunKm = longestRun,
                    equivalentDiameterKm = 2 * radiusKm,
                    longestAlignedKm = longestAligned,
                    longestAlignedBearing = longestAlignedBearing,
                    alignedAllowanceKm = allowance,
                    rightAngles = rightAngles,
                    cornerPairs = pairs
                )
            )
        }
        return rings
    }

    /** The grid bearing [run] lies exactly along, or -1. */
    fun alignedBearing(run: Run, frame: GridFrame): Int {
        for (index in frame.gridBearings.indices) {
            val spacing = frame.latticeSpacingKm[index]
            if (frame.bearingGapDegrees(run.bearingDegrees, frame.gridBearings[index]) > 45.0) continue
            if (run.offsetAcrossKm(frame.gridBearings[index]) <= EXACT_OFFSET_SPACINGS * spacing) return index
        }
        return -1
    }

    /** Whether two consecutive runs make a right angle on the sheet, both lying along the grid. */
    fun isRightAngle(first: Run, firstBearing: Int, second: Run, secondBearing: Int, frame: GridFrame): Boolean {
        if (firstBearing < 0 || secondBearing < 0) return false
        val square = frame.perpendicularPairs.any { (a, b) ->
            (firstBearing == a && secondBearing == b) || (firstBearing == b && secondBearing == a)
        }
        if (!square) return false
        return first.lengthKm >= CORNER_RUN_SPACINGS * frame.latticeSpacingAlong(firstBearing) &&
            second.lengthKm >= CORNER_RUN_SPACINGS * frame.latticeSpacingAlong(secondBearing)
    }

    /**
     * The least tolerance on a rectangle's alignment, in degrees: under it, the minimum-area
     * rectangle's own angle is not known that well on a traced outline.
     */
    private const val MINIMUM_ALIGNMENT_DEGREES = 1.0
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

/**
 * The longest aligned run a disc on the ground makes, by radius and grid bearing, measured by the
 * same trace and the same runs as every layer: the circle's own figure the aligned-side bar is a
 * multiple of. Built once per frame over a geometric ladder of radii and interpolated between.
 */
internal class DiscFigures private constructor(
    private val radiiKm: DoubleArray,
    private val longestKm: Array<DoubleArray>
) {
    fun longestAlignedKm(radiusKm: Double, bearingIndex: Int): Double {
        val figures = longestKm[bearingIndex]
        if (radiusKm <= radiiKm.first()) return figures.first()
        if (radiusKm >= radiiKm.last()) {
            // Past the ladder the figure grows as the root of the radius, the curvature argument.
            return figures.last() * sqrt(radiusKm / radiiKm.last())
        }
        val upper = radiiKm.indexOfFirst { it >= radiusKm }
        val lower = upper - 1
        val share = (kotlin.math.ln(radiusKm) - kotlin.math.ln(radiiKm[lower])) /
            (kotlin.math.ln(radiiKm[upper]) - kotlin.math.ln(radiiKm[lower]))
        return figures[lower] + share * (figures[upper] - figures[lower])
    }

    companion object {
        private val cache = HashMap<String, DiscFigures>()

        /** Radii from two cells to [LARGEST_RADIUS_CELLS] cells, in cell widths, a quarter octave apart. */
        private const val LARGEST_RADIUS_CELLS = 256.0
        private const val SMALLEST_RADIUS_CELLS = 2.0
        private const val STEPS_PER_OCTAVE = 4

        @Synchronized
        fun of(frame: GridFrame): DiscFigures = cache.getOrPut(
            "${frame.cellWidthKm}/${frame.cellHeightKm}"
        ) { build(frame) }

        private fun build(frame: GridFrame): DiscFigures {
            val radii = ArrayList<Double>()
            var radiusCells = SMALLEST_RADIUS_CELLS
            while (radiusCells <= LARGEST_RADIUS_CELLS) {
                radii.add(radiusCells * frame.cellWidthKm)
                radiusCells *= Math.pow(2.0, 1.0 / STEPS_PER_OCTAVE)
            }
            val longest = Array(frame.gridBearings.size) { DoubleArray(radii.size) }
            radii.forEachIndexed { step, radiusKm ->
                // A frame just big enough, with the same cells. Several offsets of the centre
                // against the lattice, and the longest over them, since where a circle's top falls
                // within its row moves its flat run by a cell.
                val across = (2 * radiusKm / frame.cellWidthKm).toInt() + 8
                val down = (2 * radiusKm / frame.cellHeightKm).toInt() + 8
                val local = GridFrame(across, down, frame.cellWidthKm, frame.cellHeightKm)
                for (offset in listOf(0.0, 0.25, 0.5, 0.75)) {
                    val centreX = (across / 2.0 + offset) * frame.cellWidthKm
                    val centreY = (down / 2.0 + offset) * frame.cellHeightKm
                    val mask = Controls.disc(local, radiusKm, centreX, centreY, inCells = false)
                    val runs = StraightRuns.of(Contours.ofMask(mask, local), local)
                    for (run in runs) {
                        val bearing = ComponentShapes.alignedBearing(run, local)
                        if (bearing >= 0 && run.lengthKm > longest[bearing][step]) longest[bearing][step] = run.lengthKm
                    }
                }
                // A bearing the disc shows no aligned run on at all falls back on the lattice's own
                // step along it, the least an aligned run can be.
                for (bearing in longest.indices) {
                    if (longest[bearing][step] == 0.0) longest[bearing][step] = frame.latticeSpacingAlong(bearing)
                }
            }
            return DiscFigures(radii.toDoubleArray(), longest)
        }
    }
}
