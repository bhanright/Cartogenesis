package com.cartogenesis.ui

import androidx.compose.ui.geometry.Offset
import com.cartogenesis.cartography.SheetGeometry
import com.cartogenesis.worldgen.model.WorldScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The map pane's arithmetic from a click to the cell under it, on the true-shape sheet.
 *
 * The pane draws the sheet — a 2048 world is a 4096 by 2048 picture — fitted, zoomed and panned by
 * [MapCamera], and a click comes back through [MapCamera.sheetFractionAt] and
 * [SheetGeometry.cellAtFraction]. Asked at the four corners, at the centre and either side of the
 * east-west seam, on a pane of another shape than the sheet so the letterboxing is part of it, and
 * with the camera zoomed and panned. The control is the same click read as though the sheet were
 * square, a cell to a pixel both ways, which is what the pane assumed before: it lands on another
 * row than the one under the pointer.
 */
class SheetCameraTest {

    private val grid = SheetGeometry.of(WorldScale(), CELLS, CELLS)
    private val sheetWidth = grid.widthPixels.toFloat()
    private val sheetHeight = grid.heightPixels.toFloat()

    /** The cell whose centre a point of the sheet, in sheet pixels, is drawn at on the pane. */
    private fun clickOn(camera: MapCamera, column: Int, row: Int): Offset {
        val acrossSheet = (column + HALF) * grid.pixelsPerCellAcross / sheetWidth
        val downSheet = (row + HALF) * grid.pixelsPerCellDown / sheetHeight
        return camera.screenAt(acrossSheet, downSheet, PANE_WIDTH, PANE_HEIGHT, sheetWidth, sheetHeight)
    }

    private fun cellUnder(camera: MapCamera, click: Offset): Int? {
        val fraction = camera.sheetFractionAt(
            click, PANE_WIDTH, PANE_HEIGHT, sheetWidth, sheetHeight
        ) ?: return null
        return grid.cellAtFraction(fraction.x, fraction.y)
    }

    @Test
    fun `a click returns the cell under it at the corners, the centre and across the seam`() {
        assertEquals(2, grid.pixelsPerCellAcross, "a square grid's cell is two sheet pixels wide")
        assertEquals(1, grid.pixelsPerCellDown)
        val last = CELLS - 1
        val places = listOf(
            0 to 0, last to 0, 0 to last, last to last, CELLS / 2 to CELLS / 2,
            // Either side of the east-west seam, on one row.
            last to CELLS / 3, 0 to CELLS / 3
        )
        // Zoomed in, the named places are all off the pane, so the zoomed camera is also asked at
        // a lattice of cells across the whole grid, of which only those it shows are clicked.
        val lattice = (0 until CELLS step LATTICE_STEP).flatMap { column ->
            (0 until CELLS step LATTICE_STEP).map { row -> column to row }
        }
        val fitted = MapCamera()
        val zoomed = MapCamera().apply { about(Offset(310f, 170f), 3.5f, Offset(-40f, 25f)) }
        listOf(fitted, zoomed).forEach { camera ->
            var clicked = 0
            (places + lattice).forEach { (column, row) ->
                val click = clickOn(camera, column, row)
                val cell = cellUnder(camera, click)
                // Off the pane is not a click on the map; everything else must come back.
                if (click.x in 0f..PANE_WIDTH && click.y in 0f..PANE_HEIGHT) {
                    clicked++
                    assertEquals(
                        row * CELLS + column, cell,
                        "a click at $click on cell ($column, $row) came back as $cell, zoom ${camera.zoom}"
                    )
                }
            }
            // The fitted camera shows the whole sheet, so every place is a click; the zoomed one
            // shows about a sixth of the columns and two fifths of the rows.
            val fewest = if (camera === fitted) places.size + lattice.size else FEWEST_ZOOMED_CLICKS
            assertTrue(
                clicked >= fewest,
                "only $clicked of the cells asked were on the pane at zoom ${camera.zoom}, " +
                    "against at least $fewest"
            )
        }
    }

    @Test
    fun `the sheet's east and west edges are one seam, and a pole is not`() {
        // A point a hair past the right-hand edge of the sheet is over the first column, and a
        // hair short of the left-hand edge over the last: the map is a cylinder.
        assertEquals(0, grid.columnAt(sheetWidth + 0.25f))
        assertEquals(CELLS - 1, grid.columnAt(-0.25f))
        assertEquals(0, grid.cellAtFraction(1f, 0f))
        // Past the bottom is still the bottom row, never the top one.
        assertEquals(CELLS - 1, grid.rowAt(sheetHeight + 3f))
        // And a click in the letterbox beside the sheet is not on the map at all.
        val camera = MapCamera()
        assertNull(camera.sheetFractionAt(Offset(PANE_WIDTH / 2f, 1f), PANE_WIDTH, PANE_HEIGHT, sheetWidth, sheetHeight))
    }

    @Test
    fun `read as a square sheet, the same click lands on another row`() {
        // The pane fitted a square picture before: the control reads the click that way.
        val camera = MapCamera()
        val column = CELLS / 4
        val row = CELLS / 5
        val click = clickOn(camera, column, row)
        val squareFit = MapCamera.fitOf(PANE_WIDTH, PANE_HEIGHT, CELLS.toFloat(), CELLS.toFloat())
        val squareOffsetX = (PANE_WIDTH - CELLS * squareFit) / 2f
        val squareOffsetY = (PANE_HEIGHT - CELLS * squareFit) / 2f
        val squareColumn = ((click.x - squareOffsetX) / squareFit).toInt()
        val squareRow = ((click.y - squareOffsetY) / squareFit).toInt()
        assertEquals(row * CELLS + column, cellUnder(camera, click))
        assertNotEquals(
            row * CELLS + column, squareRow * CELLS + squareColumn,
            "the square reading found the right cell, so this click cannot tell the two apart"
        )
    }

    private companion object {
        const val CELLS = 512

        /** A pane taller than the sheet's shape, so the sheet is letterboxed above and below. */
        const val PANE_WIDTH = 900f
        const val PANE_HEIGHT = 700f

        /** From a cell's index to its centre. */
        const val HALF = 0.5f

        /** Every sixteenth column and row: 1,024 cells over a 512 grid. */
        const val LATTICE_STEP = 16

        /**
         * The zoomed camera shows columns 133 to 278 and rows 0 to 214 of the 512 grid, which holds
         * 9 by 14 of the lattice's cells: 126. Well under that, so a pane or zoom rounding a little
         * differently still passes, and far over the none that were clicked before.
         */
        const val FEWEST_ZOOMED_CLICKS = 60
    }
}
