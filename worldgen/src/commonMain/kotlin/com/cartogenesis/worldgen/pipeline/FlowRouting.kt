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
        // Elevation packed above the cell index so a plain primitive sort orders cells by height.
        val ordered = LongArray(landCellCount)
        var n = 0
        for (i in 0 until width * height) {
            if (!isLand[i]) continue
            accumulation.data[i] = weightOf(i)
            ordered[n++] = encode(filled.data[i], i)
        }

        ordered.sort()
        // Highest first, so a cell's own total is final before it passes water downstream.
        for (k in ordered.indices.reversed()) {
            val i = decodeIndex(ordered[k])
            val t = flowTarget[i]
            if (t >= 0 && isLand[t]) {
                accumulation.data[t] += accumulation.data[i]
            }
        }
        return accumulation
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
