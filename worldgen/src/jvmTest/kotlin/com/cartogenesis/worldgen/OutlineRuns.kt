package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.pipeline.GlaciationStage
import kotlin.math.PI
import kotlin.math.pow

/**
 * How straight the edge of a region on the grid is, and how straight it is allowed to be.
 *
 * I2 wrote this to ask it of the basins the ice cuts; F30 asks the same question of lakes, and the
 * question is the same question, so the instrument is one instrument rather than two copies that
 * drift apart. A region here is whatever a membership array marks — `GlacialMass.basinFloor` for a
 * basin, `LakeResult.lakeId` for a lake — and the two figures are the longest run its outline makes
 * along one of the grid's bearings, and the run its own size explains.
 */
internal object OutlineRuns {

    /** The longest unbroken run an outline makes along one grid bearing, and where it is. */
    class Run(val cells: Int, val bearing: Int, val column: Int, val row: Int)

    /**
     * The longest unbroken run of a region's *outline* along one grid bearing, and which bearing.
     *
     * [com.cartogenesis.worldgen.pipeline.GlaciationStage]'s own bar test asks whether a whole body
     * is a ruled bar; this asks the same question of its edge, which is what a reader sees, and
     * asks it in the same coordinates — `GlaciationStage.alongBearingOf` and `acrossBearingOf` are
     * that instrument's, shared rather than copied. An outline cell is a region cell with a
     * non-region cell orthogonally beside it; a run is a set of outline cells sharing an across
     * coordinate and consecutive in the along coordinate, which steps by one on an axis and by two
     * on a diagonal.
     *
     * [cells] are the region's cells as row-major indices, [membership] the array they are marked
     * in and [of] the value that marks them. The grid wraps east to west and does not wrap north
     * to south, as every other field on this map does — and the polar rows where it does not wrap
     * are *not* an outline: a body running off the top of the map has no shore there, only a map
     * that stops, and counting that straight row as its edge would read every polar sea as ruled.
     * F30 measured 254 cells of exactly that on 364673 before the rule was written down.
     */
    fun longestOutlineRun(
        cells: List<Int>,
        membership: IntArray,
        of: Int,
        cellsAcross: Int,
        cellsDown: Int
    ): Run {
        val anchorColumn = cells[0] % cellsAcross
        val outline = cells.filter { cell ->
            val column = cell % cellsAcross
            val row = cell / cellsAcross
            membership[row * cellsAcross + (column + cellsAcross - 1) % cellsAcross] != of ||
                membership[row * cellsAcross + (column + 1) % cellsAcross] != of ||
                (row > 0 && membership[(row - 1) * cellsAcross + column] != of) ||
                (row < cellsDown - 1 && membership[(row + 1) * cellsAcross + column] != of)
        }
        var longest = 0
        var atBearing = 0
        var atCell = cells[0]
        for (bearing in 0 until GlaciationStage.BEARINGS) {
            val step = if (GlaciationStage.isDiagonalBearing(bearing)) 2 else 1
            // Across in the high half and along in the low, so sorting the longs groups each line
            // of the outline and orders it, and the scan below is a single pass. The cell index
            // rides in the bottom bits of a parallel array so a run can name where it is.
            val lines = LongArray(outline.size) { index ->
                val cell = outline[index]
                val row = cell / cellsAcross
                var columnOffset = (cell % cellsAcross) - anchorColumn
                if (columnOffset > cellsAcross / 2) columnOffset -= cellsAcross
                if (columnOffset < -cellsAcross / 2) columnOffset += cellsAcross
                val column = anchorColumn + columnOffset
                val across = GlaciationStage.acrossBearingOf(column, row, bearing)
                val along = GlaciationStage.alongBearingOf(column, row, bearing)
                ((across + COORDINATE_BIAS).toLong() shl 32) or
                    ((along + COORDINATE_BIAS).toLong() and 0xFFFFFFFFL)
            }
            val order = lines.indices.sortedBy { lines[it] }
            val byBearing = IntArray(outline.size) { outline[order[it]] }
            lines.sort()
            var run = 0
            var previousAcross = Long.MIN_VALUE
            var previousAlong = Long.MIN_VALUE
            for (index in lines.indices) {
                val packed = lines[index]
                val across = packed ushr 32
                val along = packed and 0xFFFFFFFFL
                run = if (across == previousAcross && along == previousAlong + step) run + 1 else 1
                if (run > longest) {
                    longest = run
                    atBearing = bearing
                    atCell = byBearing[index]
                }
                previousAcross = across
                previousAlong = along
            }
        }
        return Run(longest, atBearing, atCell % cellsAcross, atCell / cellsAcross)
    }

    /**
     * The longest run a region of [cells] cells may make along one grid bearing, in cells.
     *
     * A smooth curve drawn on a square grid makes straight runs of its own, and how long they are
     * is a question about its curvature: a circle of radius `R` cells rises half a cell over a
     * chord of `sqrt(R)`, so its outline runs about `2 * sqrt(R)` cells along an axis before it
     * steps. A round body of `n` cells therefore shows a run of `2 * (n / pi)^(1/4)` — nine cells
     * for the 671-cell cap a 2048 grid gives
     * [com.cartogenesis.worldgen.model.GlaciationConfig.maxLakeAreaKm2], and fourteen for a lake of
     * four thousand. That is the floor for any shape and is not a defect; it is the grid, and a
     * rougher shore than a circle's runs *shorter*, not longer.
     *
     * What is allowed above it is [STRAIGHTEST_SHORE_OVER_A_CIRCLE], and it comes off Earth's
     * straightest lake shores, which are the graben ones. Tanganyika is 32,900 km2, an equivalent
     * radius of 102 km, and its western scarp runs about 100 km without a bend worth drawing
     * (Hutchinson, *A Treatise on Limnology*, 1957, on the graben lakes); at the 5.9 km a cell of a
     * 2048 map measures across that is a radius of 17.3 cells against a straight run of 17, which
     * is 2.05 times the 8.3 cells its own circle would have run. So Earth's straightest big lake
     * shore is about twice as straight as a circle, and it lies along a fault, which has no reason
     * to fall on one of a grid's three bearings. Three leaves that a margin.
     *
     * The derivation is a lake's throughout — Tanganyika is a lake and the curvature argument is
     * about a shoreline — so F30's bar for a lake shore and I2's for a basin floor's rim are the
     * same number for the same reason, and are one function here rather than two constants that
     * happen to agree today.
     */
    fun allowedRunCells(cells: Int): Float =
        STRAIGHTEST_SHORE_OVER_A_CIRCLE * 2f * (cells / PI).pow(0.25).toFloat()

    /** How straight Earth's straightest lake shore is against a circle of its own size. */
    const val STRAIGHTEST_SHORE_OVER_A_CIRCLE = 3f

    /**
     * The smallest body [allowedRunCells] says anything about, in cells.
     *
     * The bar is `3 * 2 * (n / pi)^(1/4)` and a body of `n` cells is `2 * sqrt(n / pi)` across, so
     * the bar stays *wider than the body itself* until `(n / pi)^(1/4)` passes 3 — that is, until
     * `n` passes `3^4 * pi`, which is 254.5. Under that size a body can only break the bar by being
     * longer than it is wide, which is a different defect with a different instrument: whether a
     * body of water is a thin ruled bar is `RuledLines`' question and is asked next door, of a
     * strip within a cell and a bit of one line and twenty cells long. The two divide here rather
     * than overlapping and disagreeing about the same puddle.
     *
     * `GlacialBasinShapeTest` asks its own question of every cut basin whatever the size, and does
     * not read this: its basins come out at two thirds of the bar either way, so nothing there
     * turns on where the two cases divide.
     */
    const val SMALLEST_BODY_THE_BAR_BINDS = 255

    /**
     * Added to a bearing coordinate before it is packed, so the negative ones — a north-east
     * coordinate is `column - row` — sort as smaller longs rather than as larger.
     */
    private const val COORDINATE_BIAS = 1 shl 24
}
