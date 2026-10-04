package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.WorldMap
import kotlin.math.floor

/**
 * Sea too narrow to hold two shores: water drawn as water, with no coast inked along it.
 *
 * The traced coast is stroked centred on the boundary between land and sea, so half its pen falls
 * on the water, and a channel has a shore on each side. Where the channel is no wider than one
 * whole pen, the two halves meet and nothing of the water is left: it is drawn as a near-black
 * line. That was the channel the drowned-basin breach once cut from an inland arm of the sea to
 * the coast, which on seed 1 at 2048 ran along the southern foot of the east-west range, and it is
 * every inlet a drowned valley leaves up a sunken coast, each inked into a tuft. See docs/DESIGN_LEDGER.md, the water-colors row.
 *
 * So a sea cell is open only when it belongs to some block of sea wider than one pen both ways on
 * the sheet, and a sea cell in no such block is a bank to the coast: the ink runs round the open
 * water and across a channel's mouth, and the channel itself is drawn as the water it is, about
 * the width of a river. It stays sea in every other respect — its colour is the ocean ramp's at its
 * depth, and a river still ends at it. The same thought as a lake's open water
 * (`LakeResult.openWater`), measured against the pen that would otherwise fill it; and measured on
 * the true-shape sheet, where a row is half as tall as a column is wide, so a channel running east
 * to west and one running north to south are held to the same width on the ground.
 */
object NarrowSea {

    /** The sea [world]'s coast is not drawn along: see [mask]. One entry per cell, row-major. */
    fun of(world: WorldMap): BooleanArray {
        val sheet = SheetGeometry.of(world)
        val penPixels = MapRasterizer.coastPenPixels(sheet.widthPixels)
        return mask(
            world.sea.isLand, world.width,
            cellsWiderThanPen(penPixels, sheet.pixelsPerCellAcross),
            cellsWiderThanPen(penPixels, sheet.pixelsPerCellDown)
        )
    }

    /** What the coast is drawn round: land, and the sea [of] finds too narrow for a coast. */
    fun banks(world: WorldMap): BooleanArray {
        val isLand = world.sea.isLand
        val narrow = of(world)
        return BooleanArray(isLand.size) { isLand[it] || narrow[it] }
    }

    /**
     * How many cells, each [pixelsPerCell] of the sheet, a strip of water needs to be wider than a
     * pen [penPixels] wide: the fewest whose width exceeds it.
     */
    internal fun cellsWiderThanPen(penPixels: Float, pixelsPerCell: Int): Int =
        floor(penPixels / pixelsPerCell).toInt() + 1

    /**
     * True on every sea cell of [isLand] that belongs to no block of sea [blockColumns] across and
     * [blockRows] down, row-major and [cellsAcross] wide, wrapping east-west because the world is a
     * cylinder. False on land.
     */
    fun mask(isLand: BooleanArray, cellsAcross: Int, blockColumns: Int, blockRows: Int): BooleanArray {
        val narrow = BooleanArray(isLand.size)
        if (cellsAcross <= 0) return narrow
        val rowCount = isLand.size / cellsAcross
        val open = BooleanArray(isLand.size)
        fun at(column: Int, row: Int) = row * cellsAcross + column % cellsAcross
        for (top in 0..rowCount - blockRows) {
            for (left in 0 until cellsAcross) {
                var allSea = true
                for (row in top until top + blockRows) {
                    for (column in left until left + blockColumns) {
                        if (isLand[at(column, row)]) {
                            allSea = false
                            break
                        }
                    }
                    if (!allSea) break
                }
                if (!allSea) continue
                for (row in top until top + blockRows) {
                    for (column in left until left + blockColumns) open[at(column, row)] = true
                }
            }
        }
        for (cell in isLand.indices) narrow[cell] = !isLand[cell] && !open[cell]
        return narrow
    }
}
