package com.cartogenesis.cartography

import com.cartogenesis.worldgen.BorrowsSharedWorlds
import com.cartogenesis.worldgen.SharedWorlds
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The raster's own marks are as wide one way as the other on the sheet.
 *
 * The raster decides one color a cell and the sheet copies it into every pixel the cell covers
 * ([SheetGeometry.expand]). On a grid as many cells tall as wide a cell is two pixels of the sheet
 * across and one down, so a coast the raster inks one cell wide is two pixels wide where it runs
 * north-south and one tall where it runs east-west; on the square cells of a grid twice as many
 * cells across as down it is one pixel either way. Read off the drawn sheet, not off the grid:
 * the fantasy view drawn with its coast and without, the cells that differ copied onto the sheet
 * as the picture is, and every run of ink along a row and down a column measured. A run along a
 * row crosses a mark running north-south, and a run down a column one running east-west, so the
 * thinnest run each way is the mark's width each way, and the commonest run is its usual width.
 *
 * On the gallery's world at 512 rows both agree, one pixel each way. The control is the same seed
 * on the 512 by 512 grid, whose sheet is the same 1024 pixels across: there no run along a row is
 * shorter than two pixels, and the clause fails on it.
 */
class RasterMarkWidthTest : BorrowsSharedWorlds() {

    /** The runs of ink a world's raster coast leaves on its sheet, along the rows and down the columns. */
    private class InkRuns(val alongRows: IntArray, val downColumns: IntArray) {
        val thinnestAcross: Int get() = alongRows.minOrNull() ?: 0
        val thinnestDown: Int get() = downColumns.minOrNull() ?: 0
        val commonestAcross: Int get() = commonest(alongRows)
        val commonestDown: Int get() = commonest(downColumns)

        /** Whether the mark is as wide east-west as north-south: its thinnest and its usual width. */
        val agree: Boolean
            get() = thinnestAcross == thinnestDown && commonestAcross == commonestDown

        override fun toString(): String =
            "%d runs along the rows, thinnest %d px, commonest %d px, mean %.2f; %d down the columns, thinnest %d px, commonest %d px, mean %.2f"
                .format(
                    alongRows.size, thinnestAcross, commonestAcross, alongRows.average(),
                    downColumns.size, thinnestDown, commonestDown, downColumns.average()
                )

        private fun commonest(runs: IntArray): Int =
            runs.toList().groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 0
    }

    /** The raster coast of [world] as its sheet draws it, measured into runs. */
    private fun coastRuns(world: WorldMap): InkRuns {
        val withCoast = MapRasterizer.rasterize(world, RenderOptions(view = MapView.FANTASY, showCoastline = true))
        val without = MapRasterizer.rasterize(world, RenderOptions(view = MapView.FANTASY, showCoastline = false))
        val inkedCells = IntArray(withCoast.size) { if (withCoast[it] != without[it]) INK else PAPER }
        val sheet = SheetGeometry.of(world)
        val onTheSheet = sheet.expand(inkedCells)
        val across = sheet.widthPixels
        val down = sheet.heightPixels
        val alongRows = ArrayList<Int>()
        for (y in 0 until down) {
            var run = 0
            for (x in 0 until across) {
                if (onTheSheet[y * across + x] == INK) run++ else if (run > 0) { alongRows.add(run); run = 0 }
            }
            if (run > 0) alongRows.add(run)
        }
        val downColumns = ArrayList<Int>()
        for (x in 0 until across) {
            var run = 0
            for (y in 0 until down) {
                if (onTheSheet[y * across + x] == INK) run++ else if (run > 0) { downColumns.add(run); run = 0 }
            }
            if (run > 0) downColumns.add(run)
        }
        return InkRuns(alongRows.toIntArray(), downColumns.toIntArray())
    }

    @Test
    fun `the raster's coast is as wide north-south as east-west on square cells`() {
        val square = coastRuns(TestWorlds.gallery)
        println("MARKS the gallery's world at 512 rows of square cells: $square")
        val halfHeight = coastRuns(SharedWorlds.world(HALF_HEIGHT_CONTROL))
        println("MARKS the same seed on the 512 by 512 grid: $halfHeight")

        assertTrue(square.alongRows.isNotEmpty() && square.downColumns.isNotEmpty(), "the raster inked no coast")
        assertTrue(
            !halfHeight.agree,
            "the control, whose cells are two pixels across, draws its coast as wide both ways, so the " +
                "clause cannot see a two-pixel mark: $halfHeight"
        )
        assertTrue(
            square.agree,
            "on square cells the raster's coast is drawn wider one way than the other: $square"
        )
    }

    private companion object {
        const val INK = 1
        const val PAPER = 0

        /** The gallery's seed on a grid as many cells tall as wide: cells two pixels across. */
        val HALF_HEIGHT_CONTROL = WorldGenConfig(seed = 234475L, width = 512, height = 512)
    }
}
