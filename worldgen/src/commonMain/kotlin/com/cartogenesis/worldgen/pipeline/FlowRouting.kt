package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.math.LongMinHeap

/**
 * Where water goes: filling the hollows, picking the downhill neighbour, and adding up what
 * arrives.
 *
 * Shared, because two stages need the same answer at different times. Erosion needs it to know
 * which cells carry enough water to cut a valley, before there is a sea or a climate; rivers need
 * it afterwards, weighted by real rainfall, to decide which channels to draw. Having one copy
 * means the valleys the erosion carves are the valleys the rivers later find.
 *
 * Everything here takes a plain land mask rather than a [SeaLevelResult], since erosion runs before
 * the sea level is chosen and has to supply a provisional one.
 */
internal object FlowRouting {

    /**
     * Raised by this much per step when flooding a flat, so filled ground still has a gradient.
     *
     * In the elevation field's own units. Small enough to be invisible on any map, large enough
     * that a float can still tell two neighbouring cells of a filled lake apart, which is what
     * gives the routing below a direction to take across one.
     */
    private const val FLAT_GRADIENT_STEP = 1e-6f

    /**
     * Raises every hollow to the level of its lowest outlet, so no cell is left without a downhill
     * path. Priority-flood (Barnes et al.): start from the outlets and work inward, always taking
     * the lowest cell still on the frontier.
     */
    fun fillDepressions(
        width: Int,
        height: Int,
        isLand: BooleanArray,
        elevation: FloatField
    ): FloatField {
        val filled = elevation.copy()
        val visited = BooleanArray(width * height)
        val heap = LongMinHeap(width * 4)

        for (cell in visited.indices) {
            if (!isLand[cell]) visited[cell] = true
        }

        fun seedOutlet(cell: Int) {
            if (visited[cell]) return
            visited[cell] = true
            heap.push(encode(filled.data[cell], cell))
        }

        // Outlets: land touching the sea, plus land running off the top and bottom edges.
        for (row in 0 until height) {
            for (column in 0 until width) {
                val cell = row * width + column
                if (!isLand[cell]) continue
                if (row == 0 || row == height - 1) {
                    seedOutlet(cell)
                    continue
                }
                forEachNeighbour(width, height, column, row) { neighbour ->
                    if (!isLand[neighbour]) seedOutlet(cell)
                }
            }
        }

        while (!heap.isEmpty()) {
            val lowest = heap.pop()
            val cell = decodeIndex(lowest)
            val cellElevation = filled.data[cell]
            val cellColumn = cell % width
            val cellRow = cell / width

            forEachNeighbour(width, height, cellColumn, cellRow) { neighbour ->
                if (!visited[neighbour]) {
                    visited[neighbour] = true
                    if (filled.data[neighbour] <= cellElevation) {
                        filled.data[neighbour] = cellElevation + FLAT_GRADIENT_STEP
                    }
                    heap.push(encode(filled.data[neighbour], neighbour))
                }
            }
        }
        return filled
    }

    /** Steepest-descent neighbour per land cell, or -1 where the water leaves the map. */
    fun flowDirections(
        width: Int,
        height: Int,
        isLand: BooleanArray,
        elevation: FloatField,
        filled: FloatField
    ): IntArray {
        val steepestNeighbour = IntArray(width * height) { -1 }
        for (row in 0 until height) {
            for (column in 0 until width) {
                val cell = row * width + column
                if (!isLand[cell]) continue

                var bestNeighbour = -1
                var bestDrop = 0f
                val here = filled.data[cell]
                forEachNeighbourWithDistance(width, height, column, row) { neighbour, distance ->
                    // Ocean neighbours use the true elevation, so coastal cells drain to the sea.
                    val there =
                        if (isLand[neighbour]) filled.data[neighbour]
                        else elevation.data[neighbour]
                    val drop = (here - there) / distance
                    if (drop > bestDrop) {
                        bestDrop = drop
                        bestNeighbour = neighbour
                    }
                }
                steepestNeighbour[cell] = bestNeighbour
            }
        }
        return steepestNeighbour
    }

    /**
     * Every land cell, lowest first, ordered by the depression-filled surface.
     *
     * Walking this backwards visits each cell before anything it drains into, which is what lets a
     * single pass carry a running total — water, or the sediment that water is carrying —
     * downstream without recursion and without a stack that a long river could overflow.
     */
    fun heightOrder(
        width: Int,
        height: Int,
        isLand: BooleanArray,
        filled: FloatField,
        landCellCount: Int
    ): IntArray {
        // Elevation packed above the cell index so a plain primitive sort orders cells by height.
        val ordered = LongArray(landCellCount)
        var written = 0
        for (cell in 0 until width * height) {
            if (!isLand[cell]) continue
            ordered[written++] = encode(filled.data[cell], cell)
        }
        ordered.sort()
        return IntArray(landCellCount) { decodeIndex(ordered[it]) }
    }

    /**
     * Adds each cell's own contribution to everything downstream of it.
     *
     * Walked in order of height rather than by recursion, so a cell's total is final before it
     * passes anything on, and no stack can overflow on a long river.
     */
    fun accumulate(
        width: Int,
        height: Int,
        isLand: BooleanArray,
        filled: FloatField,
        flowTarget: IntArray,
        landCellCount: Int,
        weightOf: (Int) -> Float
    ): FloatField {
        val accumulation = FloatField(width, height)
        for (cell in 0 until width * height) {
            if (isLand[cell]) accumulation.data[cell] = weightOf(cell)
        }

        val order = heightOrder(width, height, isLand, filled, landCellCount)
        // Highest first, so a cell's own total is final before it passes water downstream.
        for (rank in order.indices.reversed()) {
            val cell = order[rank]
            val receiver = flowTarget[cell]
            if (receiver >= 0 && isLand[receiver]) {
                accumulation.data[receiver] += accumulation.data[cell]
            }
        }
        return accumulation
    }

    /**
     * The basins the fill had to raise, one entry per basin.
     *
     * Every array here is indexed by basin rather than by cell, so nothing that reads it has to
     * carry a grid-sized side table to work out which hollow a cell belongs to.
     */
    class Spillways(
        val count: Int,
        /** The rim cell each basin spills over, or -1 where it has none the water can cut. */
        val spill: IntArray,
        /** The level of that rim: the height the fill raised the whole basin to. */
        val level: FloatArray,
        /** The lowest true ground in the basin: the level at which it holds no water at all. */
        val floor: FloatArray,
        val cells: IntArray,
        /** The largest basin by area, and how deep the fill stands over its lowest ground. */
        val largest: Int,
        val largestCells: Int,
        val largestDepth: Float,
        /** The deepest fill anywhere on the map: how far the water stands over the lowest ground. */
        val deepest: Float
    )

    /**
     * Finds every depression the fill had to raise and the rim cell each one spills over.
     *
     * A basin filled to its rim is a lake with an overflow, and the overflow has a knickpoint at
     * the lip. What decides the lake's size is how fast that lip wears down, not how big the hollow
     * behind it is — Bonneville emptied through Red Rock Pass because the pass gave way, and what
     * was left was Great Salt Lake. So the interesting cell is the rim, and the interesting
     * quantity is how far it may fall: to the floor of its own basin and no further, because below
     * that there is no lake left to drain.
     *
     * Ponded cells are grouped by connectivity, seeded in ascending index order and walked with an
     * explicit stack, so the labelling is one specific labelling rather than any valid one. The
     * spill of a basin is the lowest cell outside it that a cell inside it drains to, ties broken
     * on the cell index — no set, no map, and nothing that depends on a hash.
     *
     * @param elevation the true ground, before the fill raised anything.
     * @param filled the surface the routing actually runs on.
     * @param pondDepth how far the fill must stand above the ground before a cell counts as water
     *   rather than as a flat the epsilon-fill nudged.
     */
    fun spillways(
        width: Int,
        height: Int,
        isLand: BooleanArray,
        elevation: FloatArray,
        filled: FloatArray,
        flowTarget: IntArray,
        pondDepth: Float
    ): Spillways {
        val cellCount = width * height
        val ponded = BooleanArray(cellCount) { isLand[it] && filled[it] - elevation[it] > pondDepth }
        val seen = BooleanArray(cellCount)
        var stack = IntArray(1024)

        var spills = IntArray(64)
        var levels = FloatArray(64)
        var floors = FloatArray(64)
        var counts = IntArray(64)
        var basins = 0

        var largest = -1
        var largestCells = 0
        var largestDepth = 0f
        var deepest = 0f

        for (start in 0 until cellCount) {
            if (!ponded[start] || seen[start]) continue

            var stackTop = 0
            stack[stackTop++] = start
            seen[start] = true

            var cells = 0
            var basinFloor = Float.MAX_VALUE
            var spill = -1
            var spillLevel = Float.MAX_VALUE

            while (stackTop > 0) {
                val cell = stack[--stackTop]
                cells++
                if (elevation[cell] < basinFloor) basinFloor = elevation[cell]

                // Where this cell's water leaves the basin. The lowest such exit is the rim the
                // fill levelled the whole basin up to; the others are higher ground the epsilon
                // gradient happens to touch.
                val receiver = flowTarget[cell]
                if (receiver >= 0 && isLand[receiver] && !ponded[receiver]) {
                    val exitLevel = filled[receiver]
                    if (exitLevel < spillLevel || (exitLevel == spillLevel && receiver < spill)) {
                        spillLevel = exitLevel
                        spill = receiver
                    }
                }

                forEachNeighbour(width, height, cell % width, cell / width) { neighbour ->
                    if (ponded[neighbour] && !seen[neighbour]) {
                        seen[neighbour] = true
                        if (stackTop == stack.size) stack = stack.copyOf(stack.size * 2)
                        stack[stackTop++] = neighbour
                    }
                }
            }

            if (basins == spills.size) {
                spills = spills.copyOf(basins * 2)
                levels = levels.copyOf(basins * 2)
                floors = floors.copyOf(basins * 2)
                counts = counts.copyOf(basins * 2)
            }
            spills[basins] = spill
            levels[basins] = if (spill >= 0) spillLevel else basinFloor
            floors[basins] = basinFloor
            counts[basins] = cells
            val fillDepth = if (spill >= 0) spillLevel - basinFloor else 0f
            if (cells > largestCells) {
                largest = basins
                largestCells = cells
                largestDepth = fillDepth
            }
            if (fillDepth > deepest) deepest = fillDepth
            basins++
        }

        return Spillways(
            basins, spills, levels, floors, counts, largest, largestCells, largestDepth, deepest
        )
    }

    /**
     * Land cells in an order where nothing appears before everything that drains into it — sources
     * first, mouths last.
     *
     * [heightOrder] is nearly this and is not good enough for sediment. Its key biases the
     * elevation by four before taking the raw bits, and at that magnitude a float's last place is
     * about 5e-7, so two cells whose real heights differ by less than that sort as equal and the
     * tie falls to the cell index. Water does not care — flow accumulation off by one cell in ten
     * thousand is invisible — but a load handed to a cell the walk has already passed is *lost*,
     * and the mass budget caught it: two to ten cells a round, three percent of the sediment.
     *
     * A topological sort of the flow network has no such tolerance. D8 gives each cell one
     * downstream neighbour and every step is strictly downhill, so the network is a forest and
     * Kahn's algorithm orders it exactly. Cells are seeded and drained in ascending index order, so
     * the result is one specific order rather than any valid one.
     */
    fun drainageOrder(
        width: Int,
        height: Int,
        isLand: BooleanArray,
        flowTarget: IntArray,
        landCellCount: Int
    ): IntArray {
        val cellCount = width * height
        val feeding = IntArray(cellCount)
        for (cell in 0 until cellCount) {
            if (!isLand[cell]) continue
            val receiver = flowTarget[cell]
            if (receiver >= 0 && isLand[receiver]) feeding[receiver]++
        }

        val order = IntArray(landCellCount)
        var tail = 0
        for (cell in 0 until cellCount) {
            if (isLand[cell] && feeding[cell] == 0) order[tail++] = cell
        }
        var head = 0
        while (head < tail) {
            val receiver = flowTarget[order[head++]]
            if (receiver >= 0 && isLand[receiver] && --feeding[receiver] == 0) order[tail++] = receiver
        }

        // A cycle would leave cells unplaced, and the strictly-downhill rule says there can be
        // none. Belt and braces: anything left over still gets its turn, at the end.
        if (tail < landCellCount) {
            for (cell in 0 until cellCount) {
                if (isLand[cell] && feeding[cell] > 0) order[tail++] = cell
            }
        }
        return order
    }

    /**
     * Packs elevation into the high bits and the cell index into the low, so sorting or heaping the
     * longs orders cells by height.
     *
     * The bias exists because elevation is measured from the shoreline and so goes negative at sea,
     * and the sign bit of a negative float does not sort as a smaller integer. Adding a constant
     * first keeps every value positive and the ordering honest.
     */
    fun encode(elevation: Float, index: Int): Long {
        val bits = (elevation + ELEVATION_BIAS).toRawBits()
        return (bits.toLong() shl 32) or index.toLong()
    }

    fun decodeIndex(encoded: Long): Int = (encoded and 0xFFFFFFFFL).toInt()

    /**
     * Added to an elevation before its bits are packed by [encode].
     *
     * Elevation is measured from the shoreline and so goes negative at sea, and the sign bit of a
     * negative float does not sort as a smaller integer. Four is comfortably above the deepest
     * water the shoreline-relative field can hold, which is -1.
     */
    private const val ELEVATION_BIAS = 4f

    /**
     * Length of a diagonal step, in cells, for the drop-per-distance comparison in
     * [flowDirections]. Written out rather than taken from `sqrt`, because every world ever
     * generated took the steepest neighbour by this exact float.
     */
    const val DIAGONAL_STEP_CELLS = 1.41421356f

    inline fun forEachNeighbour(
        width: Int,
        height: Int,
        column: Int,
        row: Int,
        action: (index: Int) -> Unit
    ) {
        for (rowStep in -1..1) {
            val neighbourRow = row + rowStep
            if (neighbourRow < 0 || neighbourRow >= height) continue
            for (columnStep in -1..1) {
                if (columnStep == 0 && rowStep == 0) continue
                var neighbourColumn = (column + columnStep) % width
                if (neighbourColumn < 0) neighbourColumn += width
                action(neighbourRow * width + neighbourColumn)
            }
        }
    }

    inline fun forEachNeighbourWithDistance(
        width: Int,
        height: Int,
        column: Int,
        row: Int,
        action: (index: Int, distance: Float) -> Unit
    ) {
        for (rowStep in -1..1) {
            val neighbourRow = row + rowStep
            if (neighbourRow < 0 || neighbourRow >= height) continue
            for (columnStep in -1..1) {
                if (columnStep == 0 && rowStep == 0) continue
                var neighbourColumn = (column + columnStep) % width
                if (neighbourColumn < 0) neighbourColumn += width
                val distance =
                    if (columnStep != 0 && rowStep != 0) DIAGONAL_STEP_CELLS else 1f
                action(neighbourRow * width + neighbourColumn, distance)
            }
        }
    }
}
