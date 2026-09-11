package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.pow
import kotlinx.serialization.Serializable

@Serializable
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
@Serializable
data class Lake(
    val id: Int,
    val cellCount: Int,
    /**
     * Elevation of the water surface. At the basin's spill level where the lake overflows, and
     * below it where evaporation holds the water down — see [endorheic].
     */
    val surfaceElevation: Float,
    /** Where the lake overflows toward the sea, or would if it reached the brim. */
    val outletCell: Int,
    /**
     * True when the lake has no outlet: its catchment's runoff cannot fill the basin to the brim
     * against evaporation, so the surface stands below [spillElevation], rivers end here and
     * nothing leaves. The Caspian and the Great Salt Lake, rather than Lake Erie.
     */
    val endorheic: Boolean = false,
    /** The brim — where the surface would sit if the basin were full. */
    val spillElevation: Float = 0f
)

data class LakeResult(
    /** Lake id per cell, or [NO_LAKE]. */
    val lakeId: IntArray,
    val lakes: List<Lake>,
    /**
     * The floor of a basin too dry to hold standing water at all: bare, seasonally flooded, salt
     * where the inflow has been evaporating for long enough. Eyre in a dry year, Etosha, Bonneville.
     * Recorded per cell so a later chunk can draw the flats; nothing renders them yet.
     */
    val playa: BooleanArray = BooleanArray(lakeId.size)
) {
    fun isLake(cell: Int): Boolean = lakeId[cell] != NO_LAKE

    fun isPlaya(cell: Int): Boolean = playa[cell]

    /** The water surface over a cell, which is the spill level only where the lake overflows. */
    fun surfaceAt(cell: Int): Float {
        val id = lakeId[cell]
        return if (id == NO_LAKE) 0f else lakes[id].surfaceElevation
    }

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
 * Step 5: fill depressions so water never dead-ends inland, route every cell downhill, size the
 * lakes the fill implies against what their catchments can keep wet, accumulate rainfall downstream,
 * and trace the resulting channels to the coast — or to a lake with no way out of it.
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

        // Lakes are sized before the water is accumulated, because an endorheic basin changes the
        // answer: nothing leaves it, so every cell downstream of its rim loses that whole catchment
        // and the river that used to be drawn below a desert basin stops being drawn. The balance
        // itself needs a catchment total of its own, in millimetres, which is the same accumulation
        // run over the rainfall rather than over the runoff weight.
        val catchmentRain = if (config.lakes.enabled && config.lakes.waterBalance) {
            FlowRouting.accumulate(
                w, h, sea.isLand, filled, flowTarget, sea.landCellCount
            ) { i -> climate.precipitationMm.data[i] }
        } else null

        val lakes = findLakes(config, sea, climate, filled, flowTarget, catchmentRain)
        val flow = accumulateFlow(w, h, sea, climate, filled, flowTarget)
        val rivers = traceRivers(config, sea, flow, flowTarget)

        return RiverResult(filled, flow.accumulation, flowTarget, rivers, lakes)
    }

    /**
     * Finds the basins the flood had to raise, and works out how much water each of them holds.
     *
     * A cell is under water when the filled surface sits meaningfully above the real ground. The
     * threshold matters: epsilon-filling nudges every cell along the flood path upward by a hair,
     * and those increments accumulate over a long flat run, so a naive `filled > raw` test would
     * flag half a continent. `LakesConfig.minDepth` has to clear that accumulated noise.
     *
     * That gives the basin's footprint *at its spill level*, which is where the lake sits only if
     * the catchment can keep it there. [LakeWaterBalance] decides that, and this is where a basin
     * that cannot becomes endorheic: the lake shrinks to the level its inflow can sustain against
     * evaporation (or disappears entirely, leaving a playa), the basin's flow targets are re-pointed
     * at the water rather than at the rim, and the water that runs in stops running out — which is
     * why [flowTarget] is taken by this function and modified, rather than being read.
     */
    private fun findLakes(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        climate: ClimateResult,
        filled: FloatField,
        flowTarget: IntArray,
        catchmentRain: FloatField?
    ): LakeResult {
        val w = config.width
        val h = config.height
        val cfg = config.lakes
        val lakeId = IntArray(w * h) { LakeResult.NO_LAKE }
        val playa = BooleanArray(w * h)
        if (!cfg.enabled) return LakeResult(lakeId, emptyList(), playa)

        val ground = sea.relativeElevation
        val submerged = BooleanArray(w * h) { i ->
            sea.isLand[i] && (filled.data[i] - ground.data[i]) >= cfg.minDepth
        }

        // Potential evaporation is a per-cell property of the climate, not of any basin, so it is
        // worth computing once rather than once per candidate level.
        val evaporation = if (catchmentRain == null) null else FloatArray(w * h) { i ->
            if (!sea.isLand[i]) 0f else LakeWaterBalance.potentialEvaporationMm(
                climate.summerTemperature.data[i],
                climate.winterTemperature.data[i],
                cfg.evaporationScale
            )
        }

        val lakes = ArrayList<Lake>()
        val stack = ArrayDeque<Int>()
        val member = ArrayList<Int>()
        // A basin can end up with no lake on it — too small, or too dry — so membership is tracked
        // apart from the lake ids, which are only for cells that finish under water.
        val visited = BooleanArray(w * h)
        // Reused across basins. [LakeWaterBalance.routeIntoWater] empties it as it goes, so it is
        // all-false again by the time the next basin fills it.
        val pending = BooleanArray(w * h)

        for (start in 0 until w * h) {
            if (!submerged[start] || visited[start]) continue

            member.clear()
            stack.addLast(start)
            visited[start] = true
            while (stack.isNotEmpty()) {
                val cell = stack.removeLast()
                member.add(cell)
                FlowRouting.forEachNeighbour(w, h, cell % w, cell / w) { n ->
                    if (submerged[n] && !visited[n]) {
                        visited[n] = true
                        stack.addLast(n)
                    }
                }
            }

            // Too small to read as water; hand it back to the land.
            if (member.size < cfg.minCells) continue

            // The brim, and the cell the water leaves through — wherever the basin drains to dry
            // ground.
            val spill = member.maxOf { filled.data[it] }
            var outlet = member[0]
            for (cell in member) {
                val target = flowTarget[cell]
                if (target >= 0 && !submerged[target]) {
                    outlet = cell
                    break
                }
            }

            if (catchmentRain == null || evaporation == null) {
                fillToBrim(lakeId, lakes, member, spill, outlet)
                continue
            }

            // The basin's own cells, lowest ground first: its hypsometry, and the order the water
            // covers them in. Sorted through the elevation-keyed packing every other ordering in
            // this pipeline uses, so equal heights fall to the lower cell index on every platform.
            val ordered = LongArray(member.size)
            for (k in member.indices) ordered[k] = FlowRouting.encode(ground.data[member[k]], member[k])
            ordered.sort()

            val n = member.size
            val sortedGround = FloatArray(n)
            val rainPrefix = FloatArray(n + 1)
            val evaporationPrefix = FloatArray(n + 1)
            for (k in 0 until n) {
                val cell = FlowRouting.decodeIndex(ordered[k])
                sortedGround[k] = ground.data[cell]
                rainPrefix[k + 1] = rainPrefix[k] + climate.precipitationMm.data[cell]
                evaporationPrefix[k + 1] = evaporationPrefix[k] + evaporation[cell]
            }

            // Every cell of the basin drains out through its pour point, so the accumulation there
            // is the whole catchment — the basin plus every slope that feeds it.
            var catchment = 0f
            for (cell in member) {
                val here = catchmentRain.data[cell]
                if (here > catchment) catchment = here
            }

            val balance = LakeWaterBalance.solve(
                sortedGround, rainPrefix, evaporationPrefix,
                catchment, spill, cfg.minDepth, cfg.runoffFraction
            )

            if (balance.atSpill) {
                fillToBrim(lakeId, lakes, member, spill, outlet)
                continue
            }

            // Endorheic. Take the water back to the balance level, hand the rest of the basin
            // floor back to the land, and make the basin a sink: no outlet river, and the rivers
            // that used to run below it were carrying water that never leaves.
            val id = lakes.size
            val wet = IntArray(balance.submergedCells) { FlowRouting.decodeIndex(ordered[it]) }

            val water: IntArray
            if (balance.submergedCells >= cfg.minCells) {
                for (cell in wet) lakeId[cell] = id
                lakes.add(Lake(id, wet.size, balance.surface, outlet, true, spill))
                water = wet
            } else {
                // Not enough water to read as a lake. What is left is a playa: the flat floor of
                // the basin, dry most of the year and briefly a sheet of water after rain. At
                // least the ground within one minimum depth of the lowest cell, so a basin whose
                // balance is zero still gets the flat it plainly has.
                val floor = sortedGround[0]
                var count = balance.submergedCells
                while (count < n && sortedGround[count] <= floor + cfg.minDepth) count++
                water = IntArray(count.coerceAtLeast(1)) { FlowRouting.decodeIndex(ordered[it]) }
                for (cell in water) playa[cell] = true
            }

            for (cell in member) pending[cell] = true
            LakeWaterBalance.routeIntoWater(w, h, ground, pending, water, member.size, flowTarget)
        }

        return LakeResult(lakeId, lakes, playa)
    }

    /** The old answer, and still the right one wherever the basin overflows: full to the brim. */
    private fun fillToBrim(
        lakeId: IntArray,
        lakes: MutableList<Lake>,
        member: List<Int>,
        spill: Float,
        outlet: Int
    ) {
        val id = lakes.size
        for (cell in member) lakeId[cell] = id
        lakes.add(Lake(id, member.size, spill, outlet, endorheic = false, spillElevation = spill))
    }

    /**
     * Priority-flood (Barnes et al.): grow inland from the coast, raising any cell that sits below
     * the lowest path already reached so it drains rather than ponding.
     */
    private fun fillDepressions(width: Int, height: Int, sea: SeaLevelResult): FloatField =
        FlowRouting.fillDepressions(width, height, sea.isLand, sea.relativeElevation)

    private fun computeFlowDirections(
        width: Int,
        height: Int,
        sea: SeaLevelResult,
        filled: FloatField
    ): IntArray =
        FlowRouting.flowDirections(width, height, sea.isLand, sea.relativeElevation, filled)

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
        var totalRunoff = 0f
        val accumulation = FlowRouting.accumulate(
            width, height, sea.isLand, filled, flowTarget, sea.landCellCount
        ) { i ->
            val weight = runoffWeight(climate.precipitation.data[i])
            totalRunoff += weight
            weight
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
            val source = FlowRouting.decodeIndex(sources[k])

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

}
