package com.cartogenesis.worldgen.pipeline

/**
 * Whether turning one cell of water into land would cut the water around it in two.
 *
 * Both passes that move the shoreline after the sea-level cut need this and neither can do without
 * it, because nothing downstream can undo an enclosure either of them makes:
 * [SeaLevelStage.drainDrownedBasins] runs before both and only on the basins the percentile drowned,
 * and `ErosionConfig.outletIncision` ran two stages earlier. A bay one of them sealed would be a
 * lake below sea level for good.
 *
 * The test is the digital-topology one for a simple point, on the eight-connected water the rest of
 * this generator uses. The water in the ring of eight neighbours, read round the cell, either forms
 * one unbroken run or it does not. One run — or none, for a pocket of a single cell — and the cell
 * is a notch in the shore: fill it, and every drop of water that could reach another before can
 * still reach it, round the outside. Two runs or more and the cell is a neck between two pieces of
 * water: a bay's mouth, a strait, the throat of a sound.
 *
 * It is only binding if the fill it is asked about happens before its neighbour is asked, so both
 * callers fill in a fixed order rather than simultaneously. Two cells across the mouth of a bay each
 * look safe on the old mask and are fatal together; taken in order, the first is filled and the
 * second then sees the bay on one side and the sea on the other.
 */
internal object WaterTopology {

    fun severs(
        isLand: BooleanArray,
        row: Int,
        column: Int,
        cellsAcross: Int,
        cellsDown: Int
    ): Boolean {
        var runs = 0
        var previousWasWater = !isLand[ringCell(row, column, cellsAcross, cellsDown, RING_ROWS.size - 1)]
        for (step in RING_ROWS.indices) {
            val water = !isLand[ringCell(row, column, cellsAcross, cellsDown, step)]
            if (water && !previousWasWater) runs++
            previousWasWater = water
        }
        return runs > 1
    }

    private fun ringCell(
        row: Int,
        column: Int,
        cellsAcross: Int,
        cellsDown: Int,
        step: Int
    ): Int {
        val neighbourRow = (row + RING_ROWS[step]).coerceIn(0, cellsDown - 1)
        val neighbourColumn = (column + RING_COLUMNS[step] + cellsAcross) % cellsAcross
        return neighbourRow * cellsAcross + neighbourColumn
    }

    /** The eight neighbours in a circle, starting north and turning clockwise. */
    private val RING_ROWS = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)
    private val RING_COLUMNS = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
}
