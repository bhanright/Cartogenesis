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

    /** Raised by this much per step when flooding a flat, so filled ground still has a gradient. */
    private const val EPSILON = 1e-6f

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

        for (i in visited.indices) {
            if (!isLand[i]) visited[i] = true
        }

        fun seed(i: Int) {
            if (visited[i]) return
            visited[i] = true
            heap.push(encode(filled.data[i], i))
        }

        // Outlets: land touching the sea, plus land running off the top and bottom edges.
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                if (!isLand[i]) continue
                if (y == 0 || y == height - 1) {
                    seed(i)
                    continue
                }
                forEachNeighbour(width, height, x, y) { n ->
                    if (!isLand[n]) seed(i)
                }
            }
        }

        while (!heap.isEmpty()) {
            val current = heap.pop()
            val ci = decodeIndex(current)
            val cElevation = filled.data[ci]
            val cx = ci % width
            val cy = ci / width

            forEachNeighbour(width, height, cx, cy) { n ->
                if (!visited[n]) {
                    visited[n] = true
                    if (filled.data[n] <= cElevation) {
                        filled.data[n] = cElevation + EPSILON
                    }
                    heap.push(encode(filled.data[n], n))
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
        val target = IntArray(width * height) { -1 }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                if (!isLand[i]) continue

                var best = -1
                var bestDrop = 0f
                val here = filled.data[i]
                forEachNeighbourWithDistance(width, height, x, y) { n, distance ->
                    // Ocean neighbours use the true elevation, so coastal cells drain to the sea.
                    val there = if (isLand[n]) filled.data[n] else elevation.data[n]
                    val drop = (here - there) / distance
                    if (drop > bestDrop) {
                        bestDrop = drop
                        best = n
                    }
                }
                target[i] = best
            }
        }
        return target
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
        var n = 0
        for (i in 0 until width * height) {
            if (!isLand[i]) continue
            ordered[n++] = encode(filled.data[i], i)
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
        for (i in 0 until width * height) {
            if (isLand[i]) accumulation.data[i] = weightOf(i)
        }

        val order = heightOrder(width, height, isLand, filled, landCellCount)
        // Highest first, so a cell's own total is final before it passes water downstream.
        for (k in order.indices.reversed()) {
            val i = order[k]
            val t = flowTarget[i]
            if (t >= 0 && isLand[t]) {
                accumulation.data[t] += accumulation.data[i]
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
        val size = width * height
        val ponded = BooleanArray(size) { isLand[it] && filled[it] - elevation[it] > pondDepth }
        val seen = BooleanArray(size)
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

        for (start in 0 until size) {
            if (!ponded[start] || seen[start]) continue

            var top = 0
            stack[top++] = start
            seen[start] = true

            var cells = 0
            var basinFloor = Float.MAX_VALUE
            var spill = -1
            var spillLevel = Float.MAX_VALUE

            while (top > 0) {
                val c = stack[--top]
                cells++
                if (elevation[c] < basinFloor) basinFloor = elevation[c]

                // Where this cell's water leaves the basin. The lowest such exit is the rim the
                // fill levelled the whole basin up to; the others are higher ground the epsilon
                // gradient happens to touch.
                val t = flowTarget[c]
                if (t >= 0 && isLand[t] && !ponded[t]) {
                    val level = filled[t]
                    if (level < spillLevel || (level == spillLevel && t < spill)) {
                        spillLevel = level
                        spill = t
                    }
                }

                forEachNeighbour(width, height, c % width, c / width) { n ->
                    if (ponded[n] && !seen[n]) {
                        seen[n] = true
                        if (top == stack.size) stack = stack.copyOf(stack.size * 2)
                        stack[top++] = n
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
            val depth = if (spill >= 0) spillLevel - basinFloor else 0f
            if (cells > largestCells) {
                largest = basins
                largestCells = cells
                largestDepth = depth
            }
            if (depth > deepest) deepest = depth
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
        val size = width * height
        val feeding = IntArray(size)
        for (i in 0 until size) {
            if (!isLand[i]) continue
            val t = flowTarget[i]
            if (t >= 0 && isLand[t]) feeding[t]++
        }

        val order = IntArray(landCellCount)
        var tail = 0
        for (i in 0 until size) {
            if (isLand[i] && feeding[i] == 0) order[tail++] = i
        }
        var head = 0
        while (head < tail) {
            val t = flowTarget[order[head++]]
            if (t >= 0 && isLand[t] && --feeding[t] == 0) order[tail++] = t
        }

        // A cycle would leave cells unplaced, and the strictly-downhill rule says there can be
        // none. Belt and braces: anything left over still gets its turn, at the end.
        if (tail < landCellCount) {
            for (i in 0 until size) {
                if (isLand[i] && feeding[i] > 0) order[tail++] = i
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

    private const val ELEVATION_BIAS = 4f

    inline fun forEachNeighbour(
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        action: (index: Int) -> Unit
    ) {
        for (dy in -1..1) {
            val ny = y + dy
            if (ny < 0 || ny >= height) continue
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                var nx = (x + dx) % width
                if (nx < 0) nx += width
                action(ny * width + nx)
            }
        }
    }

    inline fun forEachNeighbourWithDistance(
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        action: (index: Int, distance: Float) -> Unit
    ) {
        for (dy in -1..1) {
            val ny = y + dy
            if (ny < 0 || ny >= height) continue
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                var nx = (x + dx) % width
                if (nx < 0) nx += width
                val distance = if (dx != 0 && dy != 0) 1.41421356f else 1f
                action(ny * width + nx, distance)
            }
        }
    }
}
