package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.math.LongMinHeap
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Sea too narrow to be drawn as sea, and the way a river's water runs down it to the open water.
 *
 * The lowstand cut a valley to every shore and the sea came back up them, so a coast is fringed
 * with drowned valleys: rias, the Chesapeake's arms, the Potomac below its fall line. Where the
 * flooded valley is wider than a cell, `DrownedValleys` keeps it as sea, and most are kept, because
 * the width it asks of a valley is half a cell. Most of what it keeps is one or two cells wide and
 * tens of cells long: on seed 1 at 2,048 rows, 328 of the 399 drawn rivers that reach the sea reach
 * it at the head of such a channel, some of them thirty cells, three hundred kilometers, up from
 * the open water (docs/DESIGN_LEDGER.md, RM1).
 *
 * The map cannot draw that water as sea. A coast is inked on each shore, and a channel no wider
 * than the coast's pen has no water left between the two inks, so the drawing runs the coast across
 * the channel's mouth instead and paints the channel itself as a thread of sea color
 * (`NarrowSea` in `:cartography`, which reads this object's rule). A river traced only as far as
 * the head of the channel then stops at that thread, and on the map it stops short of the coast.
 *
 * What the water does is plain: the river's discharge runs on down its own drowned valley to the
 * open sea, and at a width under the coast's pen that valley *is* the river's channel on the map,
 * as the Thames below London and the Hudson below Albany are on any small-scale map. So the tracer
 * carries a course on through sea too narrow to be drawn, along the shortest way on the ground to
 * the open water, and the course ends where the map draws the sea. This is the same thought as a
 * lake one cell wide (`LakeResult.openWater`), measured against the coast's pen rather than against
 * two cells, because the pen is what decides whether the drawing shows water between two shores.
 * Where a valley widens into a pool the drawing can show and narrows again below it, the course
 * crosses the pool and goes on, as a river crosses a lake ([passage]).
 *
 * Both computations here are a block scan and a shortest-path walk over a graph, work that stays on
 * the processor by the project's rule for graph walks and priority queues.
 */
object SeaChannels {

    /**
     * The narrowest sea the map draws as sea, as a share of the world's width: one 2,048th, which
     * is the coast's pen (`MapRasterizer.coastPenPixels` in `:cartography`).
     *
     * The pen is held at one pixel of a sheet 2,048 pixels wide, where it was matched to the
     * raster's one inked cell of coast, and as that share of the sheet at every other size: 19.6 km
     * on Earth's 40,075 km. Water no wider than it would be inked shut by the coast drawn along
     * each of its shores. It is a cartographic figure and not a physical one, and it is here rather
     * than in the drawing because the tracer must stop where the drawing's sea begins and the
     * drawing reads the generator, not the other way round; `NarrowSeaTest` holds the pen and this
     * share to one figure.
     */
    const val NARROWEST_DRAWN_SEA_SHARE_OF_WORLD_WIDTH: Float = 1f / 2048f

    /**
     * The pen never draws finer than one pixel of the sheet, because below that a line is a gray
     * suggestion rather than a coast; on a sheet narrower than 2,048 pixels the narrowest sea the
     * map draws is that pixel.
     */
    private const val FINEST_PEN_PIXELS = 1f

    /**
     * The block of sea, [columns] cells across and [rows] down, that a sea cell must belong to for
     * the map to draw it as open sea: the fewest cells whose span exceeds the narrowest drawn sea,
     * each way on the true-shape sheet.
     */
    class OpenBlock(val columns: Int, val rows: Int)

    /**
     * [OpenBlock] for [config]'s grid, counted in the pixels of its true-shape sheet, where the
     * narrower side of a cell is one pixel and the other side as many as it is times the narrower:
     * the arithmetic the drawing sizes the coast's pen in, so the two agree to the pixel.
     */
    fun openBlock(config: WorldGenConfig): OpenBlock {
        val cellWidthKm = config.scale.cellWidthKm(config.width)
        val cellHeightKm = config.scale.cellHeightKm(config.height)
        val pixelKm = minOf(cellWidthKm, cellHeightKm)
        val pixelsPerCellAcross = (cellWidthKm / pixelKm).roundToInt().coerceAtLeast(1)
        val pixelsPerCellDown = (cellHeightKm / pixelKm).roundToInt().coerceAtLeast(1)
        val penPixels = narrowestDrawnSeaPixels(config.width * pixelsPerCellAcross)
        return OpenBlock(
            cellsWiderThan(penPixels, pixelsPerCellAcross),
            cellsWiderThan(penPixels, pixelsPerCellDown)
        )
    }

    /** The narrowest drawn sea, in pixels of a sheet [sheetWidthPixels] wide. */
    fun narrowestDrawnSeaPixels(sheetWidthPixels: Int): Float =
        (sheetWidthPixels * NARROWEST_DRAWN_SEA_SHARE_OF_WORLD_WIDTH).coerceAtLeast(FINEST_PEN_PIXELS)

    /** The fewest cells, each [pixelsPerCell] of the sheet, whose span exceeds [pixels]. */
    fun cellsWiderThan(pixels: Float, pixelsPerCell: Int): Int = floor(pixels / pixelsPerCell).toInt() + 1

    /** The sea [config]'s map cannot draw as sea, over the land mask [isLand]: see [mask]. */
    fun narrow(config: WorldGenConfig, isLand: BooleanArray): BooleanArray {
        val block = openBlock(config)
        return mask(isLand, config.width, block.columns, block.rows)
    }

    /**
     * The sea a river's water passes through on its way to the sea the map draws: every cell of
     * [narrow] sea, and every pool, which is open sea in a body that touches the rest of the sea only
     * through narrow sea and is no larger than the largest lake Earth has
     * (`SeaConfig.enclosedSeaMaxKm2`). One entry per cell, row-major.
     *
     * A pool is where a drowned valley widens for a few cells and narrows again before the coast:
     * the drawing inks a coast round it and across the channel below it, so on the map it is a pond
     * a cell or two inland, and a course that ended there would end in a pond with the sea in sight.
     * Its water goes on to the sea down the channel, so the course does too. The bound is the
     * enclosure rule's own: a body larger than any lake Earth has is a sea whatever joins it to the
     * ocean, and a river that reaches it has reached the sea. The largest body of open sea is the
     * ocean and is never a pool.
     */
    fun passage(config: WorldGenConfig, isLand: BooleanArray, narrow: BooleanArray): BooleanArray {
        val cellsAcross = config.width
        val cellsDown = config.height
        val cellCount = cellsAcross * cellsDown
        val largestLakeCells = (config.sea.enclosedSeaMaxKm2(config.scale) / config.squareKilometresPerCell).toInt()
        val body = IntArray(cellCount) { -1 }
        val bodySizes = ArrayList<Int>()
        val frontier = IntArray(cellCount)
        for (start in 0 until cellCount) {
            if (isLand[start] || narrow[start] || body[start] >= 0) continue
            val id = bodySizes.size
            var frontierSize = 0
            var found = 0
            body[start] = id
            frontier[frontierSize++] = start
            while (frontierSize > 0) {
                val cell = frontier[--frontierSize]
                found++
                FlowRouting.forEachNeighbour(cellsAcross, cellsDown, cell % cellsAcross, cell / cellsAcross) { neighbor ->
                    if (!isLand[neighbor] && !narrow[neighbor] && body[neighbor] < 0) {
                        body[neighbor] = id
                        frontier[frontierSize++] = neighbor
                    }
                }
            }
            bodySizes.add(found)
        }
        var ocean = -1
        for (id in bodySizes.indices) if (ocean < 0 || bodySizes[id] > bodySizes[ocean]) ocean = id
        return BooleanArray(cellCount) { cell ->
            narrow[cell] || (body[cell] >= 0 && body[cell] != ocean && bodySizes[body[cell]] <= largestLakeCells)
        }
    }

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
                if (!blockIsAllSea(isLand, cellsAcross, left, top, blockColumns, blockRows)) continue
                for (row in top until top + blockRows) {
                    for (column in left until left + blockColumns) open[at(column, row)] = true
                }
            }
        }
        for (cell in isLand.indices) narrow[cell] = !isLand[cell] && !open[cell]
        return narrow
    }

    /**
     * [mask]'s answer for the one cell [cell], read off the blocks that could hold it rather than
     * off the whole grid: for a caller that asks about a course's last cell and nothing else.
     */
    fun isNarrow(isLand: BooleanArray, cellsAcross: Int, cell: Int, blockColumns: Int, blockRows: Int): Boolean {
        if (isLand[cell]) return false
        val rowCount = isLand.size / cellsAcross
        val row = cell / cellsAcross
        val column = cell % cellsAcross
        for (top in maxOf(0, row - blockRows + 1)..minOf(row, rowCount - blockRows)) {
            for (offset in 0 until blockColumns) {
                val left = (column - offset + cellsAcross) % cellsAcross
                if (blockIsAllSea(isLand, cellsAcross, left, top, blockColumns, blockRows)) return false
            }
        }
        return true
    }

    private fun blockIsAllSea(
        isLand: BooleanArray,
        cellsAcross: Int,
        left: Int,
        top: Int,
        blockColumns: Int,
        blockRows: Int
    ): Boolean {
        for (row in top until top + blockRows) {
            for (column in left until left + blockColumns) {
                if (isLand[row * cellsAcross + column % cellsAcross]) return false
            }
        }
        return true
    }

    /**
     * How the water in [passage] runs on to the sea the map draws: for each cell, the neighbor it
     * runs on to, and how far it still has to go.
     */
    class SeawardRoutes(
        /** For each cell of passage, the next cell on its way; -1 on every other cell. */
        val nextCell: IntArray,
        /**
         * For each cell of passage, the length of the way on to the sea the map draws, in cell
         * widths ([WorldGenConfig.groundSteps]); infinite on every other cell. It falls strictly
         * along [nextCell], so ordering by it, longest first, puts every cell before the cell it
         * runs on to.
         */
        val toOpenSeaCellWidths: FloatArray
    )

    /**
     * For every cell of [passage], the neighbor its water runs on to: one step along the shortest
     * way on the ground, through passage, to the nearest sea the map draws, which is sea outside
     * [passage]. -1 on every other cell, and on passage with no such sea at the end of it.
     *
     * The shortest way and not the deepest, because the shelf lies over a drowned valley at one
     * depth from its head to its mouth: the shelf's wedge is laid by distance from land, and every
     * cell of a channel a cell or two wide is the same distance from it. Lengths are on the ground
     * ([WorldGenConfig.groundSteps]), so a step down a column is as long as it is, and the distance
     * strictly falls along every step, so the walk cannot loop. Ties fall to the lower cell index,
     * through the same packing every ordering in this pipeline uses.
     */
    fun seawardSteps(config: WorldGenConfig, isLand: BooleanArray, passage: BooleanArray): SeawardRoutes {
        val cellsAcross = config.width
        val cellsDown = config.height
        val cellCount = cellsAcross * cellsDown
        val steps = config.groundSteps
        val toOpenSea = FloatArray(cellCount) { Float.POSITIVE_INFINITY }
        val seaward = IntArray(cellCount) { -1 }
        val frontier = LongMinHeap()
        fun isOpenSea(cell: Int) = !isLand[cell] && !passage[cell]

        for (cell in 0 until cellCount) {
            if (!passage[cell]) continue
            FlowRouting.forEachNeighbourWithDistance(
                cellsAcross, cellsDown, cell % cellsAcross, cell / cellsAcross, steps
            ) { neighbor, length ->
                if (isOpenSea(neighbor) && length < toOpenSea[cell]) toOpenSea[cell] = length
            }
            if (toOpenSea[cell] < Float.POSITIVE_INFINITY) frontier.push(FlowRouting.encode(toOpenSea[cell], cell))
        }
        while (!frontier.isEmpty()) {
            val packed = frontier.pop()
            val cell = FlowRouting.decodeIndex(packed)
            if (packed != FlowRouting.encode(toOpenSea[cell], cell)) continue
            FlowRouting.forEachNeighbourWithDistance(
                cellsAcross, cellsDown, cell % cellsAcross, cell / cellsAcross, steps
            ) { neighbor, length ->
                if (!passage[neighbor]) return@forEachNeighbourWithDistance
                val through = toOpenSea[cell] + length
                if (through < toOpenSea[neighbor]) {
                    toOpenSea[neighbor] = through
                    frontier.push(FlowRouting.encode(through, neighbor))
                }
            }
        }

        for (cell in 0 until cellCount) {
            if (!passage[cell] || toOpenSea[cell] == Float.POSITIVE_INFINITY) continue
            var best = Float.POSITIVE_INFINITY
            FlowRouting.forEachNeighbourWithDistance(
                cellsAcross, cellsDown, cell % cellsAcross, cell / cellsAcross, steps
            ) { neighbor, length ->
                val beyond = when {
                    isOpenSea(neighbor) -> 0f
                    passage[neighbor] -> toOpenSea[neighbor]
                    else -> Float.POSITIVE_INFINITY
                }
                val through = beyond + length
                if (through < best || (through == best && neighbor < seaward[cell])) {
                    best = through
                    seaward[cell] = neighbor
                }
            }
        }
        return SeawardRoutes(seaward, toOpenSea)
    }

    /**
     * The discharge every cell carries once the [passage] carries it on: [landDischarge] on land,
     * as the routing accumulated it, and on each cell of passage everything the land sends straight
     * into it ([flowTarget]) and everything the passage above it carries down ([routes]). In
     * [landDischarge]'s own units; nought on the sea the map draws.
     *
     * So two rivers that meet in a drowned valley run on below the meeting as one river carrying
     * both, which is what makes the course below a confluence the widest of the courses that meet
     * there, in a valley as on land.
     */
    fun dischargeCarriedDown(
        isLand: BooleanArray,
        passage: BooleanArray,
        routes: SeawardRoutes,
        flowTarget: IntArray,
        landDischarge: FloatArray
    ): FloatArray {
        val carried = FloatArray(landDischarge.size) { cell -> if (isLand[cell]) landDischarge[cell] else 0f }
        for (cell in carried.indices) {
            if (!isLand[cell]) continue
            val below = flowTarget[cell]
            if (below >= 0 && passage[below]) carried[below] += landDischarge[cell]
        }
        // Farthest from the sea first, packed so that one ascending sort gives that order: a
        // positive float's bits sort as the float does, so their complement sorts the other way,
        // and ties fall to the lower cell index.
        val upstreamFirst = ArrayList<Long>()
        for (cell in carried.indices) {
            if (passage[cell] && routes.nextCell[cell] >= 0) {
                val farther = Int.MAX_VALUE.toLong() - routes.toOpenSeaCellWidths[cell].toRawBits().toLong()
                upstreamFirst.add((farther shl 32) or cell.toLong())
            }
        }
        val order = upstreamFirst.toLongArray()
        order.sort()
        for (packed in order) {
            val cell = (packed and 0xFFFFFFFFL).toInt()
            val below = routes.nextCell[cell]
            if (passage[below]) carried[below] += carried[cell]
        }
        return carried
    }
}
