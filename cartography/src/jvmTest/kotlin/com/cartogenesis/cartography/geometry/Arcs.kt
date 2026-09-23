package com.cartogenesis.cartography.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Detector 4: stretches of outline that are perfect circular arcs, and sets of them sharing a
 * centre.
 *
 * Two frames, because a compass can be swung in either. A disc stamped by a Euclidean distance on
 * the ground is round in kilometres and twice as tall as wide on the sheet; one stamped in cells is
 * round on the sheet and squashed on the ground. So every stretch is fitted as a circle on the
 * ground and again on the sheet.
 *
 * The fit is Kasa's algebraic circle — the centre and radius minimising `x^2 + y^2 + D x + E y + F`
 * over the points, a three-by-three solve, which `BayHeadDeltaAuditTest` uses in `:worldgen` —
 * refined by Gauss-Newton on the geometric distance, since Kasa's residual is not a distance and
 * pulls the centre inward on a partial arc. The residual is then judged in cells along each
 * point's own radius, which on the ground frame is `sqrt((cos a * width)^2 + (sin a * height)^2)`
 * kilometres at radial angle `a`: that is what "within a cell" means on a grid whose cell is
 * twice as wide as it is tall.
 *
 * A stretch is an arc when it covers at least [MINIMUM_COVERAGE_DEGREES] of its circle, its radius
 * is at least [MINIMUM_RADIUS_CELLS], and its points lie within [MAXIMUM_RMS_CELLS] of the circle
 * on average and [MAXIMUM_DEVIATION_CELLS] at worst. Windows of several lengths are slid along each
 * outline; those that pass are merged into arcs.
 */
internal object Arcs {

    /**
     * The least share of a turn a stretch must cover, in degrees: a third of a circle.
     *
     * A shorter arc of a large circle is indistinguishable from a gently curved coast within a
     * cell; a third of a turn is where the circle's centre is pinned by the arc itself. The stamps
     * this project drew — half-disc lobes, semicircles, cirque bowls — each cover half a turn
     * and more.
     */
    const val MINIMUM_COVERAGE_DEGREES = 120.0

    /**
     * The smallest radius judged, in cells (of the cell's larger side on the ground frame).
     *
     * From the control ensemble: `GeometryControlTest` fits circles to every stretch of hundreds
     * of natural islands, and below this they fit — a natural blob a few cells across is a circle
     * to within a quarter of a cell once rasterised, as round as a stamped disc (0.11 cells at a
     * radius of 4, 0.22 at 7, 0.27 at 9, and none past 9). Under it a stamped disc cannot be told
     * from a lake by its outline alone, and the guard does not pretend to.
     */
    const val MINIMUM_RADIUS_CELLS = 10.0

    /**
     * The largest root-mean-square distance from the circle, in cells along the radius.
     *
     * A traced raster circle departs from its true circle by the staircase and the midpoint cuts:
     * `GeometryControlTest` measures 0.18 to 0.24 cells on stamped discs of every radius from 4 to
     * 24 cells, on the ground and on the sheet. A quarter of a cell admits that and little else.
     */
    const val MAXIMUM_RMS_CELLS = 0.25

    /** The largest single departure from the circle, in cells along the radius: the one cell of G4. */
    const val MAXIMUM_DEVIATION_CELLS = 1.0

    /** How far apart two arcs' centres may be and still be concentric, in cells. */
    const val CONCENTRIC_CENTRE_CELLS = 1.0

    /** How many arcs about one centre make concentric rings rather than a circle drawn twice. */
    const val CONCENTRIC_RINGS = 3

    /** Where along the outline the points are taken, in cells of the smaller side. */
    private const val SAMPLE_SPACING_CELLS = 0.5

    /** The window lengths slid along an outline, in samples. */
    private val WINDOWS = intArrayOf(16, 24, 36, 54, 81, 122, 183, 275, 412, 618)

    enum class Frame { GROUND, SHEET }

    class Arc(
        val frame: Frame,
        val outline: Int,
        val centreXCells: Double,
        val centreYCells: Double,
        val radiusCells: Double,
        val coverageDegrees: Double,
        val rmsCells: Double
    ) {
        fun describe(grid: GridFrame): String {
            var column = centreXCells % grid.cellsAcross
            if (column < 0) column += grid.cellsAcross
            return "%s arc about (%d,%d), radius %.1f cells, %.0f degrees, %.2f cells rms".format(
                frame.name.lowercase(), column.toInt(), centreYCells.toInt(), radiusCells,
                coverageDegrees, rmsCells
            )
        }
    }

    class Result(val arcs: List<Arc>, val concentricSets: List<List<Arc>>)

    /**
     * How many arcs each of [lines] lines carries: the larger of its ground and its sheet fits, since
     * one round stretch is usually found in both frames and is one arc.
     */
    fun perLine(result: Result, lines: Int): IntArray {
        val ground = IntArray(lines)
        val sheet = IntArray(lines)
        for (arc in result.arcs) if (arc.frame == Frame.GROUND) ground[arc.outline]++ else sheet[arc.outline]++
        return IntArray(lines) { maxOf(ground[it], sheet[it]) }
    }

    fun measure(outlines: List<Outline>, frame: GridFrame): Result {
        val arcs = ArrayList<Arc>()
        outlines.forEachIndexed { index, outline ->
            val samples = resample(outline, SAMPLE_SPACING_CELLS * minOf(frame.cellWidthKm, frame.cellHeightKm))
            if (samples.first.size < WINDOWS.first()) return@forEachIndexed
            for (fitFrame in Frame.entries) arcs.addAll(arcsOf(samples, outline.closed, index, fitFrame, frame))
        }
        return Result(arcs, concentric(arcs))
    }

    /** Points every [spacingKm] along an outline, in kilometres. */
    fun resample(outline: Outline, spacingKm: Double): Pair<DoubleArray, DoubleArray> {
        val xs = DoubleArrayBuilder()
        val ys = DoubleArrayBuilder()
        val count = outline.vertexCount
        val last = if (outline.closed) count else count - 1
        var carried = 0.0
        for (vertex in 0 until last) {
            val next = (vertex + 1) % count
            val ax = outline.xKm[vertex]
            val ay = outline.yKm[vertex]
            val bx = outline.xKm[next]
            val by = outline.yKm[next]
            val length = lengthOf(bx - ax, by - ay)
            var at = carried
            while (at < length) {
                xs.add(ax + (bx - ax) * at / length)
                ys.add(ay + (by - ay) * at / length)
                at += spacingKm
            }
            carried = at - length
        }
        return xs.toArray() to ys.toArray()
    }

    private fun arcsOf(
        samples: Pair<DoubleArray, DoubleArray>,
        closed: Boolean,
        outline: Int,
        fitFrame: Frame,
        grid: GridFrame
    ): List<Arc> {
        // Into the frame the fit is done in: kilometres, or cells.
        val xs: DoubleArray
        val ys: DoubleArray
        if (fitFrame == Frame.GROUND) {
            xs = samples.first
            ys = samples.second
        } else {
            xs = DoubleArray(samples.first.size) { samples.first[it] / grid.cellWidthKm }
            ys = DoubleArray(samples.second.size) { samples.second[it] / grid.cellHeightKm }
        }
        val count = xs.size
        val found = ArrayList<Arc>()
        val covered = BooleanArray(count)
        for (window in WINDOWS.reversed()) {
            if (window > count) continue
            val stride = maxOf(1, window / 4)
            val starts = if (closed) count else count - window + 1
            var start = 0
            while (start < starts) {
                val fit = if (covered[start]) null else fitWindow(xs, ys, start, window, count, fitFrame, grid)
                if (fit != null) {
                    for (step in 0 until window) covered[(start + step) % count] = true
                    found.add(
                        Arc(
                            fitFrame, outline,
                            if (fitFrame == Frame.GROUND) fit.centreX / grid.cellWidthKm else fit.centreX,
                            if (fitFrame == Frame.GROUND) fit.centreY / grid.cellHeightKm else fit.centreY,
                            fit.radiusCells, fit.coverageDegrees, fit.rmsCells
                        )
                    )
                }
                start += stride
            }
        }
        return found
    }

    private class Fit(
        val centreX: Double,
        val centreY: Double,
        val radiusCells: Double,
        val coverageDegrees: Double,
        val rmsCells: Double
    )

    private fun fitWindow(
        xs: DoubleArray,
        ys: DoubleArray,
        start: Int,
        window: Int,
        count: Int,
        fitFrame: Frame,
        grid: GridFrame
    ): Fit? {
        var meanX = 0.0
        var meanY = 0.0
        for (step in 0 until window) {
            val at = (start + step) % count
            meanX += xs[at]
            meanY += ys[at]
        }
        meanX /= window
        meanY /= window
        // Kasa's normal equations about the window's own centroid, which conditions the solve.
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        var sxz = 0.0
        var syz = 0.0
        for (step in 0 until window) {
            val at = (start + step) % count
            val x = xs[at] - meanX
            val y = ys[at] - meanY
            val z = x * x + y * y
            sxx += x * x
            syy += y * y
            sxy += x * y
            sxz += x * z
            syz += y * z
        }
        val determinant = sxx * syy - sxy * sxy
        if (abs(determinant) < 1e-12) return null
        var centreX = (sxz * syy - syz * sxy) / (2 * determinant)
        var centreY = (syz * sxx - sxz * sxy) / (2 * determinant)
        // Most windows are nowhere near a circle, and a pass over Kasa's own circle says so before
        // the refinement is paid for: a spread of distances several times the bar cannot be
        // refined down to it, since Kasa's centre is already close on anything that is an arc.
        run {
            var sum = 0.0
            var sumSquares = 0.0
            for (step in 0 until window) {
                val at = (start + step) % count
                val distance = lengthOf(xs[at] - meanX - centreX, ys[at] - meanY - centreY)
                sum += distance
                sumSquares += distance * distance
            }
            val mean = sum / window
            val spread = sqrt((sumSquares / window - mean * mean).coerceAtLeast(0.0))
            val cell = if (fitFrame == Frame.GROUND) maxOf(grid.cellWidthKm, grid.cellHeightKm) else 1.0
            if (spread > QUICK_REJECT_RMS_CELLS * cell) return null
            if (mean / cell < MINIMUM_RADIUS_CELLS / 2) return null
        }
        // Gauss-Newton on the geometric distance, a few steps from Kasa's start.
        var radius = 0.0
        repeat(GEOMETRIC_STEPS) {
            var sumDistance = 0.0
            var jxx = 0.0
            var jyy = 0.0
            var jxy = 0.0
            var jx = 0.0
            var jy = 0.0
            var meanUx = 0.0
            var meanUy = 0.0
            for (step in 0 until window) {
                val at = (start + step) % count
                val dx = xs[at] - meanX - centreX
                val dy = ys[at] - meanY - centreY
                val distance = sqrt(dx * dx + dy * dy)
                if (distance > 0) { meanUx += dx / distance; meanUy += dy / distance }
                sumDistance += distance
            }
            radius = sumDistance / window
            meanUx /= window
            meanUy /= window
            for (step in 0 until window) {
                val at = (start + step) % count
                val dx = xs[at] - meanX - centreX
                val dy = ys[at] - meanY - centreY
                val distance = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-12)
                // The residual's gradient with the radius eliminated at its optimum, the mean
                // distance.
                val gx = -dx / distance + meanUx
                val gy = -dy / distance + meanUy
                val residual = distance - radius
                jxx += gx * gx
                jyy += gy * gy
                jxy += gx * gy
                jx += gx * residual
                jy += gy * residual
            }
            val det = jxx * jyy - jxy * jxy
            if (abs(det) < 1e-18) return@repeat
            centreX -= (jx * jyy - jy * jxy) / det
            centreY -= (jy * jxx - jx * jxy) / det
        }
        // Coverage: the angle the window's points sweep about the centre, unwrapped.
        var swept = 0.0
        var previousAngle = Double.NaN
        var sumSquares = 0.0
        var worst = 0.0
        for (step in 0 until window) {
            val at = (start + step) % count
            val dx = xs[at] - meanX - centreX
            val dy = ys[at] - meanY - centreY
            val angle = atan2(dy, dx)
            if (!previousAngle.isNaN()) {
                var turn = angle - previousAngle
                while (turn > PI) turn -= 2 * PI
                while (turn < -PI) turn += 2 * PI
                swept += turn
            }
            previousAngle = angle
            val distance = sqrt(dx * dx + dy * dy)
            val cellAlongRadius = if (fitFrame == Frame.GROUND && distance > 0) {
                val ux = dx / distance
                val uy = dy / distance
                sqrt(ux * ux * grid.cellWidthKm * grid.cellWidthKm + uy * uy * grid.cellHeightKm * grid.cellHeightKm)
            } else 1.0
            val departure = abs(distance - radius) / cellAlongRadius
            sumSquares += departure * departure
            if (departure > worst) worst = departure
        }
        val coverage = Math.toDegrees(abs(swept))
        val radiusCells = if (fitFrame == Frame.GROUND) radius / maxOf(grid.cellWidthKm, grid.cellHeightKm) else radius
        val rms = sqrt(sumSquares / window)
        if (coverage < MINIMUM_COVERAGE_DEGREES || radiusCells < MINIMUM_RADIUS_CELLS ||
            rms > MAXIMUM_RMS_CELLS || worst > MAXIMUM_DEVIATION_CELLS
        ) return null
        return Fit(centreX + meanX, centreY + meanY, radiusCells, coverage, rms)
    }

    /** Groups of at least [CONCENTRIC_RINGS] arcs of distinct radii sharing a centre within a cell. */
    private fun concentric(arcs: List<Arc>): List<List<Arc>> {
        val sets = ArrayList<List<Arc>>()
        val used = BooleanArray(arcs.size)
        for (anchor in arcs.indices) {
            if (used[anchor]) continue
            val group = arcs.indices.filter { other ->
                arcs[other].frame == arcs[anchor].frame &&
                    lengthOf(arcs[other].centreXCells - arcs[anchor].centreXCells,
                        arcs[other].centreYCells - arcs[anchor].centreYCells) <= CONCENTRIC_CENTRE_CELLS
            }
            val distinctRadii = group.map { arcs[it].radiusCells }.sorted()
                .fold(ArrayList<Double>()) { kept, radius ->
                    if (kept.isEmpty() || radius - kept.last() >= 1.0) kept.add(radius)
                    kept
                }
            if (distinctRadii.size >= CONCENTRIC_RINGS) {
                group.forEach { used[it] = true }
                sets.add(group.map { arcs[it] })
            }
        }
        return sets
    }

    private const val GEOMETRIC_STEPS = 6

    /** A window whose distances from Kasa's centre spread this far, in cells, is not refined. */
    private const val QUICK_REJECT_RMS_CELLS = 3.0
}
