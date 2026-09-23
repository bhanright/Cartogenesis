package com.cartogenesis.cartography.geometry

import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The grid a layer lies on, with the ground it stands for: how many cells across and down, and
 * how many kilometres each cell is wide and tall.
 *
 * Every bearing the guard reads is a bearing on the ground, `atan2(dy * cellHeightKm, dx *
 * cellWidthKm)`, with both spacings read from the world's own configuration. On this map a cell is
 * twice as wide as it is tall at every square grid (the world is an equirectangular projection
 * twice as wide as it is tall), so the grid's diagonals do not lie at 45 and 135 degrees on the
 * ground but at `atan(cellHeight / cellWidth)` — 26.565 degrees — and its mirror, 153.435. The
 * guard's tests read the ratio off the config at every grid rather than assume it.
 *
 * The grid wraps east to west and stops north and south. Bearings are undirected, 0 to 180
 * degrees, 0 east-west, increasing toward the south (rows count downward).
 */
internal class GridFrame(
    val cellsAcross: Int,
    val cellsDown: Int,
    val cellWidthKm: Double,
    val cellHeightKm: Double
) {
    val cellCount: Int get() = cellsAcross * cellsDown
    val worldWidthKm: Double get() = cellsAcross * cellWidthKm

    /** The grid diagonal's bearing on the ground, in degrees: 26.565 on this map's cells. */
    val diagonalDegrees: Double = Math.toDegrees(atan(cellHeightKm / cellWidthKm))

    /**
     * The grid's four bearings on the ground, in degrees: east-west, the south-east diagonal,
     * north-south and the north-east diagonal (a line rising to the east runs at 180 minus the
     * south-east one).
     */
    val gridBearings: DoubleArray =
        doubleArrayOf(0.0, diagonalDegrees, 90.0, 180.0 - diagonalDegrees)

    /**
     * How far apart the grid's own lines lie across each of [gridBearings], in kilometres: the
     * cell's height across a row, its width across a column, and the spacing of the diagonal
     * lattice lines, `width * height / sqrt(width^2 + height^2)`, across a diagonal. It is the
     * resolution of the raster across a line at that bearing: a run of length `L` lying within one
     * of these of the line is indistinguishable, on this grid, from lying exactly on it.
     */
    val latticeSpacingKm: DoubleArray = doubleArrayOf(
        cellHeightKm,
        cellWidthKm * cellHeightKm / hypot(cellWidthKm, cellHeightKm),
        cellWidthKm,
        cellWidthKm * cellHeightKm / hypot(cellWidthKm, cellHeightKm)
    )

    /**
     * Which pairs of [gridBearings] meet at a right angle on the sheet.
     *
     * The map is displayed one cell to a square pixel (the pane draws the raster with one scale for
     * both axes), so a reader sees the grid's own frame: east-west meets north-south at 90 degrees
     * there and on the ground, and the two diagonals, which meet at 126.87 degrees on the ground,
     * meet at 90 degrees on the sheet. A corner is judged as the reader sees it.
     */
    val perpendicularPairs: List<Pair<Int, Int>> = listOf(0 to 2, 1 to 3)

    /** The column and row of a cell, and back. */
    fun columnOf(cell: Int): Int = cell % cellsAcross
    fun rowOf(cell: Int): Int = cell / cellsAcross

    /** The undirected ground bearing of a step of [dxKm], [dyKm], in degrees in 0 until 180. */
    fun bearingDegrees(dxKm: Double, dyKm: Double): Double {
        var degrees = Math.toDegrees(atan2(dyKm, dxKm))
        while (degrees < 0.0) degrees += 180.0
        while (degrees >= 180.0) degrees -= 180.0
        return degrees
    }

    /** The smallest undirected difference between two bearings, in degrees, 0 to 90. */
    fun bearingGapDegrees(a: Double, b: Double): Double {
        val difference = abs(a - b) % 180.0
        return if (difference > 90.0) 180.0 - difference else difference
    }

    /** A copy of this frame at a different grid over the same world. */
    fun atGrid(cellsAcross: Int, cellsDown: Int): GridFrame = GridFrame(
        cellsAcross, cellsDown,
        this.cellWidthKm * this.cellsAcross / cellsAcross,
        this.cellHeightKm * this.cellsDown / cellsDown
    )

    override fun toString(): String =
        "%dx%d, cell %.3f x %.3f km (height/width %.4f, diagonal %.3f deg)".format(
            cellsAcross, cellsDown, cellWidthKm, cellHeightKm, cellHeightKm / cellWidthKm,
            diagonalDegrees
        )

    companion object {
        fun of(config: WorldGenConfig): GridFrame =
            GridFrame(config.width, config.height, config.cellWidthKm, config.cellHeightKm)

        const val DEGREES_PER_RADIAN = 180.0 / PI
    }
}
