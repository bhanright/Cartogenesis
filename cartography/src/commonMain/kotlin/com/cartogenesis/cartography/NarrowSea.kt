package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.WorldMap

/**
 * Sea too narrow to hold two shores: water drawn as water, with no coast inked along it.
 *
 * The coast is inked on both of its sides, the raster's cell on the landward side and the traced
 * line centred on the boundary, and a strip of sea one cell wide has a shore on each side of it and
 * nothing between them. Inked both ways it is not water at all but a near-black line: the channel
 * the drowned-basin breach cuts from an inland arm of the sea to the coast
 * (`SeaLevelStage.drainDrownedBasins`), which on seed 1 at 2048 runs along the southern foot of the
 * east-west range for over a thousand kilometres, and the one-cell inlets drowned valleys leave up
 * every sunken coast, each inked into a tuft. See docs/DESIGN_LEDGER.md, the water-colors row.
 *
 * The test is the one [com.cartogenesis.worldgen.pipeline.LakeResult.openWater] makes for a lake,
 * for the same reason: a cell of sea that belongs to no two-by-two square of sea has no open water
 * between facing shores, and the cell *is* the channel. Such a cell stays sea in every other
 * respect — its colour is the ocean ramp's at its depth, a river still ends at it — and only the
 * coast treats it as a bank, so the ink runs round the open water and across a channel's mouth,
 * and the channel itself is drawn as the water it is, the width of a river.
 */
object NarrowSea {

    /**
     * True on every sea cell of [isLand] that belongs to no two-by-two square of sea, row-major and
     * [cellsAcross] wide, wrapping east-west because the world is a cylinder. False on land.
     */
    fun mask(isLand: BooleanArray, cellsAcross: Int): BooleanArray {
        val open = BooleanArray(isLand.size)
        val narrow = BooleanArray(isLand.size)
        if (cellsAcross <= 0) return narrow
        val rowCount = isLand.size / cellsAcross
        for (row in 0 until rowCount - 1) {
            for (column in 0 until cellsAcross) {
                val here = row * cellsAcross + column
                if (isLand[here]) continue
                val eastColumn = if (column + 1 == cellsAcross) 0 else column + 1
                val east = row * cellsAcross + eastColumn
                val south = here + cellsAcross
                val southEast = (row + 1) * cellsAcross + eastColumn
                if (isLand[east] || isLand[south] || isLand[southEast]) continue
                open[here] = true
                open[east] = true
                open[south] = true
                open[southEast] = true
            }
        }
        for (cell in isLand.indices) narrow[cell] = !isLand[cell] && !open[cell]
        return narrow
    }

    /**
     * What the coast is drawn round: land, and the sea [mask] finds too narrow to have a coast of
     * its own. One entry per cell, row-major.
     */
    fun banks(world: WorldMap): BooleanArray {
        val isLand = world.sea.isLand
        val narrow = mask(isLand, world.width)
        return BooleanArray(isLand.size) { isLand[it] || narrow[it] }
    }
}
