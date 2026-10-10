package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.WorldMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The coast as the shoreline's own level line: where the ground crosses the waterline, placed
 * between two cells by how high the one stands and how deep the other lies, rather than halfway
 * between them or on the landward cell.
 *
 * Both drawings of the coast are taken from it. The raster inks every cell by its distance on the
 * sheet to this line ([inkAt]), on land and water alike, so a shore facing west takes the same ink
 * as one facing east; the overlay traces it as polylines ([Shoreline.of]). Before it, the raster
 * inked a land cell only where the water lay east or south of it, which left every shore facing
 * north or west bare, and the overlay ran along the half-cell lattice, so every coast on the map
 * was a staircase of the grid's own edges (docs/DESIGN_LEDGER.md, G2).
 *
 * The line is marching squares over the lattice of cell centres, round [banks]: land, and sea too
 * narrow for a coast of its own ([NarrowSea]). Where a bank cell stands at or above the waterline
 * and its water neighbour below it, the crossing is where the straight line between the two
 * altitudes reaches zero ([shareTowardWater]); that is the field's own contour to within the
 * interpolation, which is exact on a uniform slope. Where the two disagree with the mask — a
 * narrow channel's bank lies below the water, or ground the ice cut below the waterline meets
 * the sea — the ground cannot say where the shore is and the crossing is halfway, as it always
 * was. Checkerboards keep the land connected across the diagonal, as the flow routing assumes.
 *
 * [metres] is each cell's altitude in metres, read off the half of the ruler its own side of the
 * shoreline uses, row-major, one entry a cell; [banks] is the mask the coast runs round.
 */
class CoastLine(
    val banks: BooleanArray,
    val metres: FloatArray,
    val cellsAcross: Int,
    val cellsDown: Int
) {
    init {
        require(banks.size == cellsAcross * cellsDown && metres.size == banks.size) {
            "a coast of ${banks.size} and ${metres.size} cells on a $cellsAcross x $cellsDown grid"
        }
    }

    /**
     * Where the shoreline crosses the edge from bank cell [bank] to water cell [water], as a share
     * of the way from the bank's centre to the water's: see [shareTowardWater].
     */
    fun crossingShare(bank: Int, water: Int): Float = shareTowardWater(metres[bank], metres[water])

    /**
     * How strongly cell ([column], [row]) takes the coast's ink, 0 to 1: full on the line and
     * falling to nothing [reachPixels] away from it, measured on the true-shape sheet whose cells
     * are [pixelsAcross] by [pixelsDown] pixels, so the pen is as wide on the ground whichever way
     * the coast runs. The line is the one [Shoreline.of] traces before its smoothing; east-west it
     * wraps, as the world does, and along the northern and southern edges there is none.
     */
    fun inkAt(column: Int, row: Int, pixelsAcross: Int, pixelsDown: Int): Float {
        val reachPixels = reachPixels(pixelsAcross, pixelsDown)
        val nearest = nearestPixels(column, row, pixelsAcross, pixelsDown, reachPixels)
        return inkAtDistance(nearest, reachPixels)
    }

    /**
     * The distance on the sheet from cell ([column], [row])'s centre to the line, or [reachPixels]
     * where the line is no nearer than that. Only the blocks of four cells that can hold a piece of
     * line within the reach are searched: those whose span comes within it, which on cells as wide
     * on the sheet as they are tall are the four that meet at the centre.
     */
    internal fun nearestPixels(column: Int, row: Int, pixelsAcross: Int, pixelsDown: Int, reachPixels: Float): Float {
        val pointX = (column + HALF_A_CELL) * pixelsAcross
        val pointY = (row + HALF_A_CELL) * pixelsDown
        val extraColumns = ceil(reachPixels / pixelsAcross).toInt() - 1
        val extraRows = ceil(reachPixels / pixelsDown).toInt() - 1
        var nearest = reachPixels
        for (blockRow in row - 1 - extraRows..row + extraRows) {
            if (blockRow < 0 || blockRow >= cellsDown - 1) continue
            for (blockColumn in column - 1 - extraColumns..column + extraColumns) {
                nearest = min(nearest, blockDistance(blockColumn, blockRow, pointX, pointY, pixelsAcross, pixelsDown))
            }
        }
        return nearest
    }

    /**
     * The distance on the sheet from ([pointX], [pointY]) to the line's segments inside the block
     * whose north-west corner is the centre of cell ([blockColumn], [blockRow]), or infinity where
     * the line does not cross it. [blockColumn] may run off either side of the grid; the block it
     * names is the one round the seam, placed where it was asked for.
     */
    private fun blockDistance(
        blockColumn: Int,
        blockRow: Int,
        pointX: Float,
        pointY: Float,
        pixelsAcross: Int,
        pixelsDown: Int
    ): Float {
        val west = blockColumn.mod(cellsAcross)
        val east = (blockColumn + 1).mod(cellsAcross)
        val northWest = blockRow * cellsAcross + west
        val northEast = blockRow * cellsAcross + east
        val southEast = (blockRow + 1) * cellsAcross + east
        val southWest = (blockRow + 1) * cellsAcross + west
        // Clockwise from the north-west, the bit order of Shoreline.SEGMENTS.
        val pattern = (if (banks[northWest]) 1 else 0) or (if (banks[northEast]) 2 else 0) or
            (if (banks[southEast]) 4 else 0) or (if (banks[southWest]) 8 else 0)
        val sides = Shoreline.SEGMENTS[pattern]
        if (sides.isEmpty()) return Float.POSITIVE_INFINITY
        val corners = intArrayOf(northWest, northEast, southEast, southWest)
        var nearest = Float.POSITIVE_INFINITY
        var slot = 0
        while (slot < sides.size) {
            val fromX = crossingX(blockColumn, corners, sides[slot]) * pixelsAcross
            val fromY = crossingY(blockRow, corners, sides[slot]) * pixelsDown
            val toX = crossingX(blockColumn, corners, sides[slot + 1]) * pixelsAcross
            val toY = crossingY(blockRow, corners, sides[slot + 1]) * pixelsDown
            nearest = min(nearest, Shoreline.distanceToSegment(pointX, pointY, fromX, fromY, toX, toY))
            slot += Shoreline.SIDES_PER_SEGMENT
        }
        return nearest
    }

    /**
     * Where the line crosses [side] of the block at [blockColumn], in cell coordinates across the
     * grid; [corners] are the block's four cells, clockwise from the north-west.
     */
    internal fun crossingX(blockColumn: Int, corners: IntArray, side: Int): Float {
        val west = blockColumn + HALF_A_CELL
        return when (side) {
            Shoreline.TOP -> west + towardSecond(corners[0], corners[1])
            Shoreline.BOTTOM -> west + towardSecond(corners[3], corners[2])
            Shoreline.RIGHT -> west + 1f
            else -> west
        }
    }

    /** [crossingX]'s partner down the grid. */
    internal fun crossingY(blockRow: Int, corners: IntArray, side: Int): Float {
        val north = blockRow + HALF_A_CELL
        return when (side) {
            Shoreline.LEFT -> north + towardSecond(corners[0], corners[3])
            Shoreline.RIGHT -> north + towardSecond(corners[1], corners[2])
            Shoreline.BOTTOM -> north + 1f
            else -> north
        }
    }

    /**
     * Where the line crosses the edge from cell [first] to cell [second], as a share of the way
     * from the first's centre: the crossing measured from whichever of the two is the bank.
     */
    private fun towardSecond(first: Int, second: Int): Float =
        if (banks[first]) crossingShare(first, second) else 1f - crossingShare(second, first)

    companion object {
        /** Halfway between two cell centres: where the line crosses when the ground cannot say. */
        const val HALFWAY = 0.5f

        private const val HALF_A_CELL = 0.5f

        /**
         * The coast of [world], round the banks the drawing draws it round ([NarrowSea.banks]),
         * with each cell's altitude read off its own side of the ruler.
         */
        fun of(world: WorldMap): CoastLine {
            val isLand = world.sea.isLand
            val relative = world.sea.relativeElevation.data
            val scale = world.config.scale
            val metres = FloatArray(relative.size) { cell ->
                if (isLand[cell]) scale.metresAboveShoreline(relative[cell])
                else scale.metresBelowShoreline(relative[cell])
            }
            return CoastLine(NarrowSea.banks(world), metres, world.width, world.height)
        }

        /**
         * Where the waterline crosses between a bank cell [bankMetres] above it and a water cell
         * [waterMetres] below it, as a share of the way from the bank's centre to the water's: the
         * zero of the straight line through the two altitudes, 0 on the bank's centre and short of
         * 1. [HALFWAY] where the two do not straddle the waterline, which the mask can ask for and
         * the ground cannot answer.
         */
        fun shareTowardWater(bankMetres: Float, waterMetres: Float): Float =
            if (bankMetres >= 0f && waterMetres < 0f) bankMetres / (bankMetres - waterMetres) else HALFWAY

        /**
         * How far from the line the coast's ink reaches, in pixels of the sheet: one cell, taken
         * along its longer side on the sheet. Under [inkAtDistance]'s fall the ink across a straight
         * coast adds up to as much as one whole cell of it, the weight the one-cell line before it
         * carried, and no cell is ever more than a cell's own size from the line it is inked for.
         */
        fun reachPixels(pixelsAcross: Int, pixelsDown: Int): Float = max(pixelsAcross, pixelsDown).toFloat()

        /**
         * The ink a cell takes at [distancePixels] from the line: full on it, nothing at
         * [reachPixels], and a smoothstep between, whose fall is symmetric about the half so the
         * ink's weight across the line is centred on it.
         */
        fun inkAtDistance(distancePixels: Float, reachPixels: Float): Float =
            1f - Engraving.smoothstep(0f, reachPixels, distancePixels)

        /**
         * The marching squares' table the coast is traced with ([Shoreline.SEGMENTS]), flattened
         * for a device: four entries for each of the sixteen patterns of bank corners, the sides
         * of its first segment and then its second, -1 where it has none. Handed to the shader
         * rather than written into it, so the two paths cannot resolve a checkerboard differently.
         */
        fun segmentTable(): IntArray = IntArray(PATTERNS * SIDES_PER_PATTERN) { entry ->
            Shoreline.SEGMENTS[entry / SIDES_PER_PATTERN].getOrElse(entry % SIDES_PER_PATTERN) { NO_SIDE }
        }

        /** Every pattern of four corners, each bank or water. */
        const val PATTERNS = 16

        /** Two segments at most in a block, each a pair of sides. */
        const val SIDES_PER_PATTERN = 4

        /** [segmentTable]'s entry where a pattern has no segment. */
        const val NO_SIDE = -1
    }
}
