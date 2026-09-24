package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.model.WorldScale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Where a cell of the world lands on the drawn sheet, and which cell a point of the sheet is over.
 *
 * The sheet is drawn at the world's true shape: one of its pixels covers the same ground east-west
 * as north-south, so a coast, a disc of land or a scale bar laid either way along it reads true.
 * The grid need not be that shape. On a grid as many cells tall as wide over a world twice as wide
 * as it is tall, a cell is twice as wide on the ground as it is tall, and it takes two pixels of the
 * sheet across and one down. The two counts come from the grid's own cell sizes on the ground,
 * [WorldGenConfig.cellWidthKm] and [WorldGenConfig.cellHeightKm], and are never written down as a
 * two: a grid whose cells are square on the ground gets one pixel a cell each way from the same
 * arithmetic, and nothing that draws through this has to change when the grid does.
 *
 * The rule every drawing path keeps: a cell's colour is decided once, at the cell, and copied into
 * its pixels exactly ([sheetRow], [expand]) — never interpolated, so a biome, a realm or a style's
 * colour is never blended with its neighbour's. The ink laid over the raster — coasts, rivers, the
 * graticule, glyphs, lettering — is placed through [sheetX] and [sheetY] and drawn at its own width
 * on the sheet, so a finished picture is never stretched.
 *
 * [kilometresPerPixel] is the one scale the sheet has, the same both ways by construction.
 */
class SheetGeometry(
    /** Columns of the world's grid. */
    val cellsAcross: Int,
    /** Rows of the world's grid. */
    val cellsDown: Int,
    /** How many pixels of the sheet one cell covers east-west: 1 or more, a whole number. */
    val pixelsPerCellAcross: Int,
    /** How many pixels of the sheet one cell covers north-south: 1 or more, a whole number. */
    val pixelsPerCellDown: Int,
    /** Ground kilometres one pixel of the sheet covers, east-west and north-south alike. */
    val kilometresPerPixel: Double
) {
    init {
        require(cellsAcross > 0 && cellsDown > 0) { "a sheet of $cellsAcross x $cellsDown cells" }
        require(pixelsPerCellAcross >= 1 && pixelsPerCellDown >= 1) {
            "a cell of $pixelsPerCellAcross x $pixelsPerCellDown pixels"
        }
    }

    /** The sheet's width in pixels: every column, each [pixelsPerCellAcross] wide. */
    val widthPixels: Int get() = cellsAcross * pixelsPerCellAcross

    /** The sheet's height in pixels: every row, each [pixelsPerCellDown] tall. */
    val heightPixels: Int get() = cellsDown * pixelsPerCellDown

    /** Pixels in the whole sheet. */
    val pixelCount: Int get() = widthPixels * heightPixels

    /** Whether a cell is one pixel of the sheet, so the raster is already the sheet. */
    val isCellForPixel: Boolean get() = pixelsPerCellAcross == 1 && pixelsPerCellDown == 1

    /** A position in cell coordinates across the grid (columns, fractional) as sheet pixels. */
    fun sheetX(cellX: Float): Float = cellX * pixelsPerCellAcross

    /** A position in cell coordinates down the grid (rows, fractional) as sheet pixels. */
    fun sheetY(cellY: Float): Float = cellY * pixelsPerCellDown

    /**
     * The column under [sheetX] pixels across the sheet.
     *
     * Wrapped east-west, because the map is a cylinder: a point a pixel past the right-hand edge is
     * over the first column, and one a pixel short of the left-hand edge over the last.
     */
    fun columnAt(sheetX: Float): Int {
        val column = floor(sheetX / pixelsPerCellAcross).toInt()
        return column.mod(cellsAcross)
    }

    /** The row under [sheetY] pixels down the sheet, held to the grid: a pole is not a seam. */
    fun rowAt(sheetY: Float): Int =
        floor(sheetY / pixelsPerCellDown).toInt().coerceIn(0, cellsDown - 1)

    /** The row-major index of the cell under a point of the sheet, in sheet pixels. */
    fun cellAt(sheetX: Float, sheetY: Float): Int = rowAt(sheetY) * cellsAcross + columnAt(sheetX)

    /**
     * The cell under a point given as fractions of the sheet, 0 to 1 across and down: how a label
     * is stored, so it stays on the same ground at any zoom and at any export size.
     */
    fun cellAtFraction(acrossSheet: Float, downSheet: Float): Int =
        cellAt(acrossSheet * widthPixels, downSheet * heightPixels)

    /**
     * One row of the sheet's pixels, [sheetRowIndex] from the top, copied out of the per-cell
     * raster [cellPixels] (row-major, one colour per cell) into [into], which holds [widthPixels].
     *
     * Exact duplication: each cell's colour is written into each of its pixels unchanged.
     */
    fun sheetRow(cellPixels: IntArray, sheetRowIndex: Int, into: IntArray) {
        val rowStart = (sheetRowIndex / pixelsPerCellDown) * cellsAcross
        var at = 0
        for (column in 0 until cellsAcross) {
            val colour = cellPixels[rowStart + column]
            repeat(pixelsPerCellAcross) { into[at++] = colour }
        }
    }

    /**
     * The whole per-cell raster [cellPixels] as the sheet's pixels, row-major, [pixelCount] long.
     *
     * The cell-for-pixel case hands the raster back unchanged rather than copying it.
     */
    fun expand(cellPixels: IntArray): IntArray {
        require(cellPixels.size == cellsAcross * cellsDown) {
            "raster is ${cellPixels.size} cells, not ${cellsAcross * cellsDown}"
        }
        if (isCellForPixel) return cellPixels
        val sheet = IntArray(pixelCount)
        val row = IntArray(widthPixels)
        for (sheetRowIndex in 0 until heightPixels) {
            sheetRow(cellPixels, sheetRowIndex, row)
            row.copyInto(sheet, sheetRowIndex * widthPixels)
        }
        return sheet
    }

    companion object {

        /** The sheet for [world]'s own grid. */
        fun of(world: WorldMap): SheetGeometry = of(world.config)

        /** The sheet for [config]'s grid. */
        fun of(config: WorldGenConfig): SheetGeometry =
            of(config.scale, config.width, config.height)

        /**
         * The sheet for a grid [cellsAcross] by [cellsDown] over a world of [scale].
         *
         * The narrower side of a cell on the ground is one pixel, and the other side is as many
         * pixels as it is times the narrower. On this project's grids, whose sides are powers of two
         * over a world twice as wide as it is tall, that ratio is itself a power of two and exact in
         * floating point; a grid whose cells are not a whole number of pixels on the true-shape
         * sheet is refused rather than drawn a pixel off somewhere.
         */
        fun of(scale: WorldScale, cellsAcross: Int, cellsDown: Int): SheetGeometry {
            val cellWidthKm = scale.cellWidthKm(cellsAcross)
            val cellHeightKm = scale.cellHeightKm(cellsDown)
            val pixelKm = minOf(cellWidthKm, cellHeightKm)
            return SheetGeometry(
                cellsAcross, cellsDown,
                wholePixels(cellWidthKm / pixelKm), wholePixels(cellHeightKm / pixelKm),
                pixelKm
            )
        }

        /**
         * A sheet a cell to a pixel, for a grid whose cells are square on the ground,
         * [kilometresPerCell] a side: what a synthetic picture with no world behind it is drawn on.
         */
        fun cellForPixel(cellsAcross: Int, cellsDown: Int, kilometresPerCell: Double): SheetGeometry =
            SheetGeometry(cellsAcross, cellsDown, 1, 1, kilometresPerCell)

        private fun wholePixels(ratio: Double): Int {
            val whole = ratio.roundToInt()
            require(abs(ratio - whole) <= WHOLE_PIXEL_TOLERANCE) {
                "a cell $ratio pixels across is not a whole number of pixels on the true-shape sheet"
            }
            return whole
        }

        /**
         * How far a cell's pixel count may sit from a whole number and still be taken as it.
         *
         * A millionth: the ratio is exact on every grid this project generates, and this only
         * absorbs the last bit of a world width that is not a power of two.
         */
        private const val WHOLE_PIXEL_TOLERANCE = 1e-6
    }
}
