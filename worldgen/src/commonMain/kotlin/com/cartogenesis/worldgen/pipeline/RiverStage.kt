package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlinx.serialization.Serializable

@Serializable
data class River(
    /** Cell indices from source to mouth. */
    val cells: IntArray,
    /**
     * How wide the channel runs at each point, as a fraction of the widest river on the map: 0 at
     * the smallest channel drawn and 1 at the mouth of the biggest, on the square root of discharge.
     * [RiverWidth] derives it and says why it is a ratio rather than a length.
     *
     * Empty only in a save written before rivers were sized this way, which carries a width in
     * cells under the old rule instead and so has nothing here to read; `WorldSections` fills it
     * in from the accumulation that same save carries.
     */
    val widthRatio: FloatArray = FloatArray(0)
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
    val playa: BooleanArray = BooleanArray(lakeId.size),
    /** Cells across the grid, so a lake's own shape can be read off [lakeId]. */
    val cellsAcross: Int
) {
    /**
     * Where a lake is at least two cells across, and so is water rather than the river filling it.
     *
     * A lake reaches its spill level over every cell of its basin, and at the far ends of a basin
     * that includes the channel that feeds it: a reach of river standing an inch under its own
     * flood is, to the flood-filling, indistinguishable from the middle of the lake. Physically
     * that is right, and cartographically it is not. A strip of water one cell wide has no open
     * water between its banks at all — the cell *is* the channel — and at the grids this generator
     * draws, a cell is a twelve-thousand-kilometre world over 512 to 2048, which is 23 down to 6
     * kilometres: a water body one cell wide is a great river's width (the Amazon's mouth is about
     * ten), not a lake's.
     *
     * Drawn as a lake, such a strip is worse than useless. It is painted in the lake's flat water
     * and no river is drawn over it, so a trunk carrying a whole catchment arrives as the widest
     * channel on the map, crosses as a one-pixel thread of standing water — a dotted line, where
     * the strip runs diagonally and the cells touch only at their corners — and leaves as the
     * widest channel on the map again. That thread is what William saw between two thick rivers on
     * seed 298405 at 1024 (F15); the lake it belongs to is 61 cells sprawled over 27 by 18.
     *
     * So the tracer and the renderer stop at *open* water and run through the rest, which puts the
     * river back where the map needs it and leaves the lake itself exactly where the physics put
     * it: the strip is still water, still painted, and now has its own river drawn along it.
     *
     * Two cells across is the whole of the test — a cell belonging to some 2x2 square of its own
     * lake — because two cells is the least that can have water between two facing shores.
     */
    val openWater: BooleanArray = openWaterMask(lakeId, cellsAcross)

    fun isLake(cell: Int): Boolean = lakeId[cell] != NO_LAKE

    /** True where [cell] is lake wide enough to be drawn as water; see [openWater]. */
    fun isOpenWater(cell: Int): Boolean = openWater[cell]

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

/**
 * Every lake cell that belongs to some 2x2 square of its own lake, and both of that square's
 * neighbours with it. See [LakeResult.openWater] for what the test means and why it is this one.
 *
 * One pass over the grid, reading each cell and the three to its east and south, so a lake's
 * shape is decided once for the whole map rather than looked up per river or per drawn segment.
 * The east neighbour wraps, because the world is a cylinder; the south does not.
 */
private fun openWaterMask(lakeId: IntArray, cellsAcross: Int): BooleanArray {
    val open = BooleanArray(lakeId.size)
    if (cellsAcross <= 0) return open
    val rowCount = lakeId.size / cellsAcross
    for (y in 0 until rowCount - 1) {
        for (x in 0 until cellsAcross) {
            val here = y * cellsAcross + x
            val id = lakeId[here]
            if (id == LakeResult.NO_LAKE) continue
            val eastX = if (x + 1 == cellsAcross) 0 else x + 1
            val east = y * cellsAcross + eastX
            val south = here + cellsAcross
            val southEast = south - x + eastX
            if (lakeId[east] != id || lakeId[south] != id || lakeId[southEast] != id) continue
            open[here] = true
            open[east] = true
            open[south] = true
            open[southEast] = true
        }
    }
    return open
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
        val rivers = traceRivers(config, sea, flow, flowTarget, lakes)

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
        if (!cfg.enabled) return LakeResult(lakeId, emptyList(), playa, w)

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
        // The other two scratch arrays the re-routing needs. `settled` is stamped with the basin's
        // own number rather than a boolean, so it never has to be cleared; `pathKey` is only ever
        // read for cells the current basin has just written.
        val settled = IntArray(w * h) { -1 }
        val pathKey = FloatArray(w * h)
        var basinMark = 0

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
            LakeWaterBalance.routeIntoWater(
                w, h, ground, pending, water, member.size, flowTarget,
                settled, basinMark++, pathKey, config.seed
            )
        }

        return LakeResult(lakeId, lakes, playa, w)
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

    /**
     * Draws the channels, and stops each one at open water.
     *
     * A lake is not a reach of river and must not be drawn as one. What is under a lake is the
     * depression-filled surface, which inside the basin is flat to within the 1e-6 the fill nudges
     * each cell of a flat by as the flood passes over it — and the flood passes over equal ground in
     * cell-index order, so that nudge grows from west to east and from north to south. D8 then reads
     * a gradient of exactly one nudge per cell pointing due east or due south, and beats every
     * diagonal because a diagonal's drop is divided by the root of two. The result is a channel
     * running dead straight from one shore of a lake to the other, and since every row of the lake
     * does the same thing, several of them in parallel. Measured on seed 59758 at 2048: four
     * horizontal runs of 36 to 44 cells across the same 2163-cell lake, and on 718106 a 45-cell one.
     *
     * None of that is a fact about the terrain — it is the fill's bookkeeping showing through — so
     * a river ends at the shore. The cell it enters the water at is kept, so the line touches the
     * lake rather than stopping a step short of it, and the outflow below the lake becomes a channel
     * of its own: open-water cells are struck out of the channel mask above, which leaves the first
     * cell below the outlet with nothing upstream of it and so makes it a source in its own right.
     *
     * *Open* water, not every lake cell, and [LakeResult.openWater] says why: a lake at its spill
     * level covers the channel that feeds it as well as its own bed, and where that strip is one
     * cell wide it is the river, has no room for the parallel scan lines above, and has to be drawn
     * as the river or it appears as a thread joining two thick channels (F15).
     *
     * Courses are traced from the head with the longest way down to the water, not from the head
     * carrying the most water. Both orders draw the same network — every channel cell is claimed by
     * somebody, and what the order decides is only which course claims which reach — but only this
     * one makes a `River` the thing it is named after. A catchment's longest watercourse is its
     * river, and the biggest headwater is very often a short fat one joining it partway down; rank
     * by flow and the trunk is split between a course that starts in the wrong place and a
     * "tributary" that is really the river's own upper half. M1 measured the drawn courses at 0.484
     * of the watercourses they stand for at 512 and 0.408 at 2048, where 1.0 is the definition.
     */
    private fun traceRivers(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        flow: FlowResult,
        flowTarget: IntArray,
        lakes: LakeResult
    ): List<River> {
        val w = config.width
        val h = config.height
        val cfg = config.rivers
        if (sea.landCellCount == 0) return emptyList()

        val accumulation = flow.accumulation
        // sourceThreshold is expressed against the whole world's runoff, so river density stays
        // consistent as resolution or sea level changes.
        val threshold = (flow.totalRunoff * cfg.sourceThreshold).coerceAtLeast(1e-4f)

        // Open water is not channel. A playa is: it is dry ground most of the year and the river
        // across it is a real one.
        val isChannel = BooleanArray(w * h) {
            sea.isLand[it] && !lakes.isOpenWater(it) && accumulation.data[it] >= threshold
        }

        val hasUpstream = BooleanArray(w * h)
        for (i in 0 until w * h) {
            if (!isChannel[i]) continue
            val t = flowTarget[i]
            if (t >= 0 && isChannel[t]) hasUpstream[t] = true
        }

        val courseBelow = lengthsToTheWater(w * h, isChannel, flowTarget)

        // Headwaters, the farthest from the water first, so a river claims its own longest
        // watercourse before any tributary can take part of it.
        var sourceCount = 0
        for (i in 0 until w * h) {
            if (isChannel[i] && !hasUpstream[i]) sourceCount++
        }
        val sources = LongArray(sourceCount)
        var s = 0
        for (i in 0 until w * h) {
            if (isChannel[i] && !hasUpstream[i]) {
                sources[s++] = (courseBelow[i].toLong() shl 32) or i.toLong()
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
                if (!sea.isLand[next] || lakes.isOpenWater(next)) {
                    path.add(next) // the river mouth, on the sea or on a lake shore
                    break
                }
                current = next
            }

            if (path.size < cfg.minLength) {
                // Release only the cells this trace claimed, never a trunk it merely touched.
                for (n in 0 until claimedByThisRiver) claimed[path[n]] = false
                continue
            }

            rivers.add(River(path.toIntArray()))
        }

        // Sized last, because the scale a channel is measured against is the whole network's: what
        // makes a trunk a trunk is that it carries more than anything else on this map.
        return RiverWidth.sized(rivers, accumulation.data)
    }

    /**
     * How many channel cells lie between each channel cell and the water it ends at, itself
     * included: the length of the watercourse below it.
     *
     * The flow targets are a forest — every cell has one receiver, strictly lower on the filled
     * surface — so the answer for a cell is one more than the answer for its receiver, and the
     * whole field falls out of one memoised walk per unvisited cell. The walk is bounded by the
     * grid for the same reason the trace below is: a routing bug that made a ring would otherwise
     * hang the generator rather than draw something odd.
     */
    private fun lengthsToTheWater(
        cellCount: Int,
        isChannel: BooleanArray,
        flowTarget: IntArray
    ): IntArray {
        val below = IntArray(cellCount)
        val walked = ArrayList<Int>()
        for (start in 0 until cellCount) {
            if (!isChannel[start] || below[start] != 0) continue
            walked.clear()
            var cell = start
            while (cell >= 0 && isChannel[cell] && below[cell] == 0 && walked.size < cellCount) {
                walked.add(cell)
                cell = flowTarget[cell]
            }
            var length = if (cell >= 0 && isChannel[cell]) below[cell] else 0
            for (k in walked.indices.reversed()) {
                length++
                below[walked[k]] = length
            }
        }
        return below
    }

}
