package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.math.LongMinHeap
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.pow

data class River(
    /** Cell indices from source to mouth. */
    val cells: IntArray,
    /** Rendering width per point, in cells. */
    val widths: FloatArray
) {
    val length: Int get() = cells.size
}

/**
 * Standing fresh water in a basin the terrain never drains.
 *
 * These are exactly the depressions the priority-flood raises: the flood lifts them to their spill
 * elevation so water can leave, which is hydrologically right but leaves the ground beneath a river
 * not sloping downhill. Recognising them as lakes is what makes that honest — the river runs into
 * the lake, the lake drains at its outlet, and nothing pretends to flow uphill.
 */
data class Lake(
    val id: Int,
    val cellCount: Int,
    /** Elevation of the water surface, which is the basin's spill level. */
    val surfaceElevation: Float,
    /** Where the lake overflows toward the sea. */
    val outletCell: Int
)

data class LakeResult(
    /** Lake id per cell, or [NO_LAKE]. */
    val lakeId: IntArray,
    val lakes: List<Lake>
) {
    fun isLake(cell: Int): Boolean = lakeId[cell] != NO_LAKE

    companion object {
        const val NO_LAKE = -1
    }
}

data class RiverResult(
    /** Depression-filled elevation — every land cell has a downhill path to the sea. */
    val filledElevation: FloatField,
    /** Upstream drainage area, weighted by rainfall. */
    val flowAccumulation: FloatField,
    /**
     * Index of the cell each land cell drains into. -1 means the water leaves the world, which
     * only happens on the polar rows where there is no further downhill cell.
     */
    val flowTarget: IntArray,
    val rivers: List<River>,
    val lakes: LakeResult
)

/**
 * Step 5: fill depressions so water never dead-ends inland, route every cell downhill, accumulate
 * rainfall downstream, and trace the resulting channels to the coast.
 */
object RiverStage {

    /**
     * The nudge that gives filled cells a downhill path. Small enough that long filled runs cannot
     * meaningfully distort terrain, large enough to stay well clear of float rounding at these
     * elevations.
     */
    private const val EPSILON = 1e-6f

    /** Shifts elevation positive so raw float bits sort in the same order as the values. */
    private const val ELEVATION_BIAS = 2f

    fun generate(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        climate: ClimateResult
    ): RiverResult {
        val w = config.width
        val h = config.height

        val filled = fillDepressions(w, h, sea)
        val flowTarget = computeFlowDirections(w, h, sea, filled)
        val flow = accumulateFlow(w, h, sea, climate, filled, flowTarget)
        val lakes = findLakes(config, sea, filled, flowTarget)
        val rivers = traceRivers(config, sea, flow, flowTarget)

        return RiverResult(filled, flow.accumulation, flowTarget, rivers, lakes)
    }

    /**
     * Finds the basins the flood had to raise, and calls them lakes.
     *
     * A cell is under water when the filled surface sits meaningfully above the real ground. The
     * threshold matters: epsilon-filling nudges every cell along the flood path upward by a hair,
     * and those increments accumulate over a long flat run, so a naive `filled > raw` test would
     * flag half a continent. [LakesConfig.minDepth] has to clear that accumulated noise.
     */
    private fun findLakes(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        filled: FloatField,
        flowTarget: IntArray
    ): LakeResult {
        val w = config.width
        val h = config.height
        val cfg = config.lakes
        val lakeId = IntArray(w * h) { LakeResult.NO_LAKE }
        if (!cfg.enabled) return LakeResult(lakeId, emptyList())

        val submerged = BooleanArray(w * h) { i ->
            sea.isLand[i] && (filled.data[i] - sea.relativeElevation.data[i]) >= cfg.minDepth
        }

        val lakes = ArrayList<Lake>()
        val stack = ArrayDeque<Int>()
        val member = ArrayList<Int>()

        for (start in 0 until w * h) {
            if (!submerged[start] || lakeId[start] != LakeResult.NO_LAKE) continue

            member.clear()
            stack.addLast(start)
            lakeId[start] = lakes.size
            while (stack.isNotEmpty()) {
                val cell = stack.removeLast()
                member.add(cell)
                forEachNeighbour(w, h, cell % w, cell / w) { n ->
                    if (submerged[n] && lakeId[n] == LakeResult.NO_LAKE) {
                        lakeId[n] = lakes.size
                        stack.addLast(n)
                    }
                }
            }

            if (member.size < cfg.minCells) {
                // Too small to read as water; hand it back to the land.
                member.forEach { lakeId[it] = LakeResult.NO_LAKE }
                continue
            }

            // The surface is the spill level; the outlet is wherever it drains to dry ground.
            val surface = member.maxOf { filled.data[it] }
            var outlet = member[0]
            for (cell in member) {
                val target = flowTarget[cell]
                if (target >= 0 && !submerged[target]) {
                    outlet = cell
                    break
                }
            }
            lakes.add(Lake(lakes.size, member.size, surface, outlet))
        }

        return LakeResult(lakeId, lakes)
    }

    /**
     * Priority-flood (Barnes et al.): grow inland from the coast, raising any cell that sits below
     * the lowest path already reached so it drains rather than ponding.
     */
    private fun fillDepressions(width: Int, height: Int, sea: SeaLevelResult): FloatField {
        val elevation = sea.relativeElevation
        val filled = elevation.copy()
        val visited = BooleanArray(width * height)
        val heap = LongMinHeap(width * 4)

        for (i in visited.indices) {
            if (!sea.isLand[i]) visited[i] = true
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
                if (!sea.isLand[i]) continue
                if (y == 0 || y == height - 1) {
                    seed(i)
                    continue
                }
                forEachNeighbour(width, height, x, y) { n ->
                    if (!sea.isLand[n]) seed(i)
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

    private fun computeFlowDirections(
        width: Int,
        height: Int,
        sea: SeaLevelResult,
        filled: FloatField
    ): IntArray {
        val target = IntArray(width * height) { -1 }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                if (!sea.isLand[i]) continue

                var best = -1
                var bestDrop = 0f
                val here = filled.data[i]
                forEachNeighbourWithDistance(width, height, x, y) { n, distance ->
                    // Ocean neighbours use the true elevation, so coastal cells drain to the sea.
                    val there = if (sea.isLand[n]) filled.data[n] else sea.relativeElevation.data[n]
                    val drop = (here - there) / distance
                    if (drop > bestDrop) {
                        bestDrop = drop
                        best = n
                    }
                }
                // Interior cells always have a lower neighbour after depression filling; only the
                // polar rows, which drain off the map, can legitimately come up empty.
                target[i] = best
            }
        }
        return target
    }

    /**
     * @param totalRunoff sum of the per-cell rainfall *inputs*. Not the sum of the accumulation
     *   field — that counts every cell's water again at each downstream cell, which would inflate
     *   any threshold derived from it by roughly the mean flow-path length.
     */
    private class FlowResult(val accumulation: FloatField, val totalRunoff: Float)

    /** Rainfall a single cell contributes, with a floor so arid uplands still feed a trickle. */
    private fun runoffWeight(precipitation: Float): Float = 0.05f + precipitation

    private fun accumulateFlow(
        width: Int,
        height: Int,
        sea: SeaLevelResult,
        climate: ClimateResult,
        filled: FloatField,
        flowTarget: IntArray
    ): FlowResult {
        val accumulation = FloatField(width, height)
        var totalRunoff = 0f
        // Elevation packed above the cell index so a plain primitive sort orders cells by height.
        val ordered = LongArray(sea.landCellCount)
        var n = 0
        for (i in 0 until width * height) {
            if (!sea.isLand[i]) continue
            val weight = runoffWeight(climate.precipitation.data[i])
            accumulation.data[i] = weight
            totalRunoff += weight
            ordered[n++] = encode(filled.data[i], i)
        }

        ordered.sort()
        // Highest first, so a cell's own total is final before it passes water downstream.
        for (k in ordered.indices.reversed()) {
            val i = decodeIndex(ordered[k])
            val t = flowTarget[i]
            if (t >= 0 && sea.isLand[t]) {
                accumulation.data[t] += accumulation.data[i]
            }
        }
        return FlowResult(accumulation, totalRunoff)
    }

    private fun traceRivers(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        flow: FlowResult,
        flowTarget: IntArray
    ): List<River> {
        val w = config.width
        val h = config.height
        val cfg = config.rivers
        if (sea.landCellCount == 0) return emptyList()

        val accumulation = flow.accumulation
        // sourceThreshold is expressed against the whole world's runoff, so river density stays
        // consistent as resolution or sea level changes.
        val threshold = (flow.totalRunoff * cfg.sourceThreshold).coerceAtLeast(1e-4f)

        val isChannel = BooleanArray(w * h) { sea.isLand[it] && accumulation.data[it] >= threshold }

        val hasUpstream = BooleanArray(w * h)
        for (i in 0 until w * h) {
            if (!isChannel[i]) continue
            val t = flowTarget[i]
            if (t >= 0 && isChannel[t]) hasUpstream[t] = true
        }

        // Headwaters, largest first, so trunk rivers claim their course before tributaries do.
        var sourceCount = 0
        for (i in 0 until w * h) {
            if (isChannel[i] && !hasUpstream[i]) sourceCount++
        }
        val sources = LongArray(sourceCount)
        var s = 0
        for (i in 0 until w * h) {
            if (isChannel[i] && !hasUpstream[i]) {
                sources[s++] = (accumulation.data[i].toRawBits().toLong() shl 32) or
                    i.toLong()
            }
        }
        sources.sort()

        val claimed = BooleanArray(w * h)
        val rivers = ArrayList<River>()

        for (k in sources.indices.reversed()) {
            if (rivers.size >= cfg.maxRivers) break
            val source = decodeIndex(sources[k])

            val path = ArrayList<Int>()
            var claimedByThisRiver = 0
            var current = source
            var guard = 0
            while (current >= 0 && guard++ < w * h) {
                path.add(current)
                // Joining an existing channel: keep this cell so the tributary visually connects,
                // then stop rather than redrawing the trunk.
                if (claimed[current]) break
                claimed[current] = true
                claimedByThisRiver++

                val next = flowTarget[current]
                if (next < 0) break
                if (!sea.isLand[next]) {
                    path.add(next) // the river mouth
                    break
                }
                current = next
            }

            if (path.size < cfg.minLength) {
                // Release only the cells this trace claimed, never a trunk it merely touched.
                for (n in 0 until claimedByThisRiver) claimed[path[n]] = false
                continue
            }

            val cells = path.toIntArray()
            val widths = FloatArray(cells.size) { idx ->
                // Width in cells, so it stays the same fraction of the map at any resolution.
                // A gentle power keeps big trunks from swamping the map: a river carrying a
                // thousand times more water than a headwater is only a few times wider.
                val ratio = accumulation.data[cells[idx]] / threshold
                (0.55f * ratio.pow(0.28f)).coerceIn(0.5f, 2.8f)
            }
            rivers.add(River(cells, widths))
        }
        return rivers
    }

    private inline fun forEachNeighbour(
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

    private inline fun forEachNeighbourWithDistance(
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

    private fun encode(elevation: Float, index: Int): Long {
        val bits = (elevation + ELEVATION_BIAS).toRawBits()
        return (bits.toLong() shl 32) or index.toLong()
    }

    private fun decodeIndex(encoded: Long): Int = (encoded and 0xFFFFFFFFL).toInt()
}
