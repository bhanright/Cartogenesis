package com.cartogenesis.cartography.geometry

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Detector 1 read off a layer's components rather than its lines: which way each one points.
 *
 * A lobe, a lake or a basin that is longer than it is wide has an axis, and a population of them
 * built by an operator with a preferred bearing points along it — a fan grown in front of a river
 * that arrived along one of the eight grid steps points along that step, whatever the lobe's own
 * outline does. The outline test cannot see that, because a lobe's rim curves through every
 * bearing. So each measured ring's major axis is taken from its area's second moments, on the
 * ground, and the rings pointing within [BearingIsotropy.BIN_HALF_WIDTH_DEGREES] of each grid
 * bearing are counted against those in the two neighbouring bins, exactly as the outline test
 * weighs length, with the same effect size and the census's corrected level. Each ring is one
 * independent unit, so the spread is the binomial's.
 */
internal object ComponentAxes {

    /**
     * How much longer than wide a ring must be for its axis to count, as the ratio of the square
     * roots of its second moments' two principal values. A round ring has no axis and a lumpy one
     * an axis its lumps choose, so the bar stands well above 1; a half-disc, the lobe a fan grows,
     * is 1.89 (its second moments are `R^2 / 4` along its straight side and `0.0699 R^2` across
     * it), and 1.3 keeps every lobe while leaving out rings within a third of round.
     */
    const val ELONGATION_FOR_AN_AXIS = 1.3

    class Result(
        val bearingIndex: Int,
        val gridBearingDegrees: Double,
        val central: Int,
        val flanks: Int,
        val ratio: Double,
        val lowerRatio: Double,
        val minimum: Int,
        val outcome: Outcome
    ) {
        override fun toString(): String =
            "%.1f deg: %d rings against %d either side, %.2fx (lower %.2fx)%s".format(
                gridBearingDegrees, central, flanks, ratio, lowerRatio,
                if (outcome == Outcome.INSUFFICIENT) " [insufficient: needs $minimum]" else ""
            )
    }

    /** The major axis of a ring on the ground, in degrees, and its elongation; null if degenerate. */
    fun axisOf(outline: Outline): Pair<Double, Double>? {
        val count = outline.vertexCount
        if (!outline.isRing || count < 3) return null
        val originX = outline.xKm[0]
        val originY = outline.yKm[0]
        var twiceArea = 0.0
        var sumX = 0.0
        var sumY = 0.0
        var sumXX = 0.0
        var sumYY = 0.0
        var sumXY = 0.0
        for (vertex in 0 until count) {
            val next = (vertex + 1) % count
            val x0 = outline.xKm[vertex] - originX
            val y0 = outline.yKm[vertex] - originY
            val x1 = outline.xKm[next] - originX
            val y1 = outline.yKm[next] - originY
            val cross = x0 * y1 - x1 * y0
            twiceArea += cross
            sumX += (x0 + x1) * cross
            sumY += (y0 + y1) * cross
            sumXX += (x0 * x0 + x0 * x1 + x1 * x1) * cross
            sumYY += (y0 * y0 + y0 * y1 + y1 * y1) * cross
            sumXY += (x0 * y1 + 2 * x0 * y0 + 2 * x1 * y1 + x1 * y0) * cross
        }
        if (twiceArea == 0.0) return null
        val area = twiceArea / 2
        val centreX = sumX / (6 * area)
        val centreY = sumY / (6 * area)
        val momentXX = sumXX / (12 * area) - centreX * centreX
        val momentYY = sumYY / (12 * area) - centreY * centreY
        val momentXY = sumXY / (24 * area) - centreX * centreY
        val half = (momentXX + momentYY) / 2
        val spread = sqrt(((momentXX - momentYY) / 2) * ((momentXX - momentYY) / 2) + momentXY * momentXY)
        val major = half + spread
        val minor = half - spread
        if (minor <= 0.0) return null
        var degrees = Math.toDegrees(0.5 * atan2(2 * momentXY, momentXX - momentYY))
        if (degrees < 0) degrees += 180.0
        return degrees to sqrt(major / minor)
    }

    /** Every grid bearing's count of ring axes against its neighbours, for one layer. */
    fun measure(outlines: List<Outline>, rings: List<ComponentShapes.Ring>, frame: GridFrame, familySize: Int): List<Result> {
        val z = Statistics.zFor(familySize)
        val minimum = BearingIsotropy.minimumBlocks(z)
        val axes = rings.filter { it.measured }.mapNotNull { ring -> axisOf(outlines[ring.index]) }
            .filter { it.second >= ELONGATION_FOR_AN_AXIS }.map { it.first }
        val half = BearingIsotropy.BIN_HALF_WIDTH_DEGREES
        return frame.gridBearings.indices.map { index ->
            val grid = frame.gridBearings[index]
            val gaps = axes.map { frame.bearingGapDegrees(it, grid) }
            val central = gaps.count { it <= half }
            val flanks = gaps.count { it > half && it <= 3 * half }
            val ratio = (central + 1.0) / ((flanks + 2.0) / 2.0)
            val counted = central + flanks
            // Wilson's lower bound on the central share, turned back into a ratio of bins.
            val lowerShare = if (counted == 0) 0.0 else {
                val share = central.toDouble() / counted
                val centre = share + z * z / (2 * counted)
                val width = z * sqrt(share * (1 - share) / counted + z * z / (4.0 * counted * counted))
                ((centre - width) / (1 + z * z / counted)).coerceAtLeast(0.0)
            }
            val lowerRatio = if (lowerShare >= 1.0) Double.MAX_VALUE else 2 * lowerShare / (1 - lowerShare)
            val outcome = when {
                counted < minimum -> Outcome.INSUFFICIENT
                ratio >= BearingIsotropy.EFFECT_RATIO && lowerRatio > 1.0 -> Outcome.VIOLATION
                else -> Outcome.CLEAN
            }
            Result(index, grid, central, flanks, ratio, lowerRatio, minimum, outcome)
        }
    }
}

/**
 * Which way a drawn line's shores face, and how much of each facing is drawn.
 *
 * A drawing of a coast has no reason to ink a shore facing east more than one facing west. The
 * raster coast is read off the rendering itself — the map drawn with the coast and without it,
 * and the cells that differ — so what is measured is the ink and not a reconstruction of it.
 * [shores] counts, for each facing (north, east, south, west), the land cells with water on that
 * side, and [drawn] those of them the ink covers. The least-drawn facing is held to within
 * [BearingIsotropy.EFFECT_RATIO] of the most, the same effect size a bearing is held to.
 */
internal class FacingShares(val shores: IntArray, val drawn: IntArray) {
    private val shares: DoubleArray get() = DoubleArray(4) { if (shores[it] == 0) 0.0 else drawn[it].toDouble() / shores[it] }

    fun outcome(): Outcome = when {
        shores.any { it < MINIMUM_SHORES } -> Outcome.INSUFFICIENT
        shares.min() * BearingIsotropy.EFFECT_RATIO < shares.max() -> Outcome.VIOLATION
        else -> Outcome.CLEAN
    }

    fun describe(): String = FACINGS.indices.joinToString("; ", prefix = "shores drawn by facing: ") {
        "%s %.0f%% of %d".format(FACINGS[it], 100 * shares[it], shores[it])
    }

    companion object {
        val FACINGS = listOf("north", "east", "south", "west")

        /**
         * The fewest shore cells a facing needs: at a hundred, a drawn share is known to within
         * about a tenth either way (the binomial's two standard errors at a half), well inside the
         * factor of 1.5 the bar reads.
         */
        const val MINIMUM_SHORES = 100

        /** The facings of [land]'s shores and which of them [inked] covers. */
        fun of(land: BooleanArray, inked: BooleanArray, frame: GridFrame): FacingShares {
            val shores = IntArray(4)
            val drawn = IntArray(4)
            for (cell in land.indices) {
                if (!land[cell]) continue
                val column = frame.columnOf(cell)
                val row = frame.rowOf(cell)
                val neighbours = intArrayOf(
                    if (row > 0) cell - frame.cellsAcross else -1,
                    row * frame.cellsAcross + (column + 1) % frame.cellsAcross,
                    if (row < frame.cellsDown - 1) cell + frame.cellsAcross else -1,
                    row * frame.cellsAcross + (column - 1 + frame.cellsAcross) % frame.cellsAcross
                )
                for (facing in 0 until 4) {
                    val neighbour = neighbours[facing]
                    if (neighbour < 0 || land[neighbour]) continue
                    shores[facing]++
                    if (inked[cell]) drawn[facing]++
                }
            }
            return FacingShares(shores, drawn)
        }
    }
}
