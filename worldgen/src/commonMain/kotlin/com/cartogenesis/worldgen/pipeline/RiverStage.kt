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
     * the smallest channel drawn and 1 at the mouth of the biggest, on the square root of the flow.
     * [RiverWidth] derives it and says why it is a ratio rather than a length.
     *
     * Empty only between tracing a course and sizing it, which cannot happen until every course is
     * traced: the scale a reach is measured against is the whole network's. No river outside
     * [RiverWidth.sizedByFlow] is ever handed on with this empty.
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
 *
 * See REALISM_PLAN.md, E1 and E2, and GEOGRAPHY.md for what each rule is judged against.
 */
object RiverStage {

    /**
     * The smallest total runoff a source may be asked to carry, so that a world with almost no
     * rain on it still draws the few channels it has rather than every land cell at once.
     */
    internal const val MIN_SOURCE_FLOW = 1e-4f

    /**
     * Runoff a cell contributes over and above its own rainfall.
     *
     * A floor rather than a physical term: an arid upland still gathers a trickle from snowmelt
     * and the odd storm, and without it a desert range contributes exactly nothing and the river
     * that leaves it disappears at its head.
     */
    private const val RUNOFF_FLOOR = 0.05f

    /**
     * Every land cell's water and the channels it makes: the depression-filled surface, the D8
     * target of each cell, the flow accumulated down that tree, the lakes the fill implies, and
     * the rivers traced over the result.
     *
     * [sea] gives the land mask and the ground the water runs over; [climate] gives the rainfall,
     * both as the 0..1 field the runoff weight is taken from and as the millimetres the lake water
     * balance needs. Every per-cell array is row-major at `config.width` by `config.height`.
     */
    fun generate(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        climate: ClimateResult
    ): RiverResult {
        val cellsAcross = config.width
        val cellsDown = config.height

        val filled = fillDepressions(cellsAcross, cellsDown, sea)
        val flowTarget = computeFlowDirections(cellsAcross, cellsDown, sea, filled)

        // Lakes are sized before the water is accumulated, because an endorheic basin changes the
        // answer: nothing leaves it, so every cell downstream of its rim loses that whole catchment
        // and the river that used to be drawn below a desert basin stops being drawn. The balance
        // itself needs a catchment total of its own, in millimetres, which is the same accumulation
        // run over the rainfall rather than over the runoff weight.
        val catchmentRainMm = if (config.lakes.enabled && config.lakes.waterBalance) {
            FlowRouting.accumulate(
                cellsAcross, cellsDown, sea.isLand, filled, flowTarget, sea.landCellCount
            ) { cell -> climate.precipitationMm.data[cell] }
        } else null

        val lakes = findLakes(config, sea, climate, filled, flowTarget, catchmentRainMm)
        val flow = accumulateFlow(cellsAcross, cellsDown, sea, climate, filled, flowTarget)
        val rivers = traceRivers(config, sea, flow, flowTarget, lakes)

        return RiverResult(filled, flow.accumulation, flowTarget, rivers, lakes)
    }

    /**
     * Finds the basins the flood had to raise, and works out how much water each of them holds.
     *
     * A cell is under water when the filled surface sits meaningfully above the real ground. The
     * threshold matters: epsilon-filling nudges every cell along the flood path upward by a hair,
     * and those increments accumulate over a long flat run, so a naive `filled > raw` test would
     * flag half a continent. `LakesConfig.minDepthMetres` has to clear that accumulated noise.
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
        catchmentRainMm: FloatField?
    ): LakeResult {
        val cellsAcross = config.width
        val cellsDown = config.height
        val cellCount = cellsAcross * cellsDown
        val lakesConfig = config.lakes
        // The two thresholds a lake has to clear, converted out of the world's own scale once: a
        // depth in metres as a share of the land's relief, and an area in square kilometres as a
        // count of cells of this grid.
        val minDepth = config.scale.reliefShareOfMetres(lakesConfig.minDepthMetres)
        val minLakeCells =
            (lakesConfig.minLakeAreaKm2 / config.squareKilometresPerCell).toInt().coerceAtLeast(1)
        val lakeId = IntArray(cellCount) { LakeResult.NO_LAKE }
        val playa = BooleanArray(cellCount)
        if (!lakesConfig.enabled) return LakeResult(lakeId, emptyList(), playa, cellsAcross)

        val ground = sea.relativeElevation
        val submerged = BooleanArray(cellCount) { cell ->
            sea.isLand[cell] &&
                (filled.data[cell] - ground.data[cell]) >= minDepth
        }

        // Potential evaporation is a per-cell property of the climate, not of any basin, so it is
        // worth computing once rather than once per candidate level.
        val evaporationMm = if (catchmentRainMm == null) null else FloatArray(cellCount) { cell ->
            if (!sea.isLand[cell]) 0f else LakeWaterBalance.potentialEvaporationMm(
                climate.summerTemperature.data[cell],
                climate.winterTemperature.data[cell],
                lakesConfig.evaporationScale
            )
        }

        val lakes = ArrayList<Lake>()
        val toVisit = ArrayDeque<Int>()
        val basinCells = ArrayList<Int>()
        // A basin can end up with no lake on it — too small, or too dry — so membership is tracked
        // apart from the lake ids, which are only for cells that finish under water.
        val visited = BooleanArray(cellCount)
        // Reused across basins. [LakeWaterBalance.routeIntoWater] empties it as it goes, so it is
        // all-false again by the time the next basin fills it.
        val pending = BooleanArray(cellCount)
        // The other two scratch arrays the re-routing needs. `settled` is stamped with the basin's
        // own number rather than a boolean, so it never has to be cleared; `pathKey` is only ever
        // read for cells the current basin has just written.
        val settled = IntArray(cellCount) { -1 }
        val pathKey = FloatArray(cellCount)
        var basinMark = 0

        for (start in 0 until cellCount) {
            if (!submerged[start] || visited[start]) continue

            basinCells.clear()
            toVisit.addLast(start)
            visited[start] = true
            while (toVisit.isNotEmpty()) {
                val cell = toVisit.removeLast()
                basinCells.add(cell)
                FlowRouting.forEachNeighbour(
                    cellsAcross, cellsDown, cell % cellsAcross, cell / cellsAcross
                ) { neighbour ->
                    if (submerged[neighbour] && !visited[neighbour]) {
                        visited[neighbour] = true
                        toVisit.addLast(neighbour)
                    }
                }
            }

            // Too small to read as water; hand it back to the land.
            if (basinCells.size < minLakeCells) continue

            // The brim, and the cell the water leaves through — wherever the basin drains to dry
            // ground.
            val spillElevation = basinCells.maxOf { filled.data[it] }
            var outletCell = basinCells[0]
            for (cell in basinCells) {
                val target = flowTarget[cell]
                if (target >= 0 && !submerged[target]) {
                    outletCell = cell
                    break
                }
            }

            if (catchmentRainMm == null || evaporationMm == null) {
                fillToBrim(lakeId, lakes, basinCells, spillElevation, outletCell)
                continue
            }

            // The basin's own cells, lowest ground first: its hypsometry, and the order the water
            // covers them in. Sorted through the elevation-keyed packing every other ordering in
            // this pipeline uses, so equal heights fall to the lower cell index on every platform.
            val byGround = LongArray(basinCells.size)
            for (rank in basinCells.indices) {
                byGround[rank] = FlowRouting.encode(ground.data[basinCells[rank]], basinCells[rank])
            }
            byGround.sort()

            val basinCellCount = basinCells.size
            val sortedGround = FloatArray(basinCellCount)
            val rainPrefixMm = FloatArray(basinCellCount + 1)
            val evaporationPrefixMm = FloatArray(basinCellCount + 1)
            for (rank in 0 until basinCellCount) {
                val cell = FlowRouting.decodeIndex(byGround[rank])
                sortedGround[rank] = ground.data[cell]
                rainPrefixMm[rank + 1] =
                    rainPrefixMm[rank] + climate.precipitationMm.data[cell]
                evaporationPrefixMm[rank + 1] =
                    evaporationPrefixMm[rank] + evaporationMm[cell]
            }

            // Every cell of the basin drains out through its pour point, so the accumulation there
            // is the whole catchment — the basin plus every slope that feeds it.
            var catchmentMm = 0f
            for (cell in basinCells) {
                val here = catchmentRainMm.data[cell]
                if (here > catchmentMm) catchmentMm = here
            }

            val balance = LakeWaterBalance.solve(
                sortedGround, rainPrefixMm, evaporationPrefixMm,
                catchmentMm, spillElevation, minDepth, lakesConfig.runoffFraction
            )

            if (balance.atSpill) {
                fillToBrim(lakeId, lakes, basinCells, spillElevation, outletCell)
                continue
            }

            // Endorheic. Take the water back to the balance level, hand the rest of the basin
            // floor back to the land, and make the basin a sink: no outlet river, and the rivers
            // that used to run below it were carrying water that never leaves.
            val id = lakes.size
            val balancedCells = IntArray(balance.submergedCells) {
                FlowRouting.decodeIndex(byGround[it])
            }

            val waterCells: IntArray
            if (balance.submergedCells >= minLakeCells) {
                for (cell in balancedCells) lakeId[cell] = id
                lakes.add(
                    Lake(
                        id, balancedCells.size, balance.surface, outletCell,
                        endorheic = true, spillElevation = spillElevation
                    )
                )
                waterCells = balancedCells
            } else {
                // Not enough water to read as a lake. What is left is a playa: the flat floor of
                // the basin, dry most of the year and briefly a sheet of water after rain. At
                // least the ground within one minimum depth of the lowest cell, so a basin whose
                // balance is zero still gets the flat it plainly has.
                val floor = sortedGround[0]
                var flatCells = balance.submergedCells
                while (flatCells < basinCellCount &&
                    sortedGround[flatCells] <= floor + minDepth
                ) {
                    flatCells++
                }
                waterCells = IntArray(flatCells.coerceAtLeast(1)) {
                    FlowRouting.decodeIndex(byGround[it])
                }
                for (cell in waterCells) playa[cell] = true
            }

            for (cell in basinCells) pending[cell] = true
            LakeWaterBalance.routeIntoWater(
                cellsAcross, cellsDown, ground, pending, waterCells, basinCells.size, flowTarget,
                settled, basinMark++, pathKey, config.seed
            )
        }

        return LakeResult(lakeId, lakes, playa, cellsAcross)
    }

    /** The right answer wherever the basin overflows: water to the brim over every basin cell. */
    private fun fillToBrim(
        lakeId: IntArray,
        lakes: MutableList<Lake>,
        basinCells: List<Int>,
        spillElevation: Float,
        outletCell: Int
    ) {
        val id = lakes.size
        for (cell in basinCells) lakeId[cell] = id
        lakes.add(
            Lake(
                id, basinCells.size, spillElevation, outletCell,
                endorheic = false, spillElevation = spillElevation
            )
        )
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

    /**
     * Runoff a single cell contributes, from its rainfall on the 0..1 scale. See [RUNOFF_FLOOR]
     * for why an arid cell still contributes something.
     *
     * Internal rather than private because `NationStage` has to reach the same figure to decide
     * which cells are on a river, and the two must not drift apart.
     */
    internal fun runoffWeight(precipitation: Float): Float = RUNOFF_FLOOR + precipitation

    private fun accumulateFlow(
        cellsAcross: Int,
        cellsDown: Int,
        sea: SeaLevelResult,
        climate: ClimateResult,
        filled: FloatField,
        flowTarget: IntArray
    ): FlowResult {
        var totalRunoff = 0f
        val accumulation = FlowRouting.accumulate(
            cellsAcross, cellsDown, sea.isLand, filled, flowTarget, sea.landCellCount
        ) { cell ->
            val weight = runoffWeight(climate.precipitation.data[cell])
            totalRunoff += weight
            weight
        }
        return FlowResult(accumulation, totalRunoff)
    }

    /**
     * Draws the channels, and stops each one at open water.
     *
     * A lake is not a reach of river and must not be drawn as one. What is under a lake is the
     * depression-filled surface, which inside the basin is flat to within the hair the fill nudges
     * each cell of a flat by as the flood passes over it — and the flood passes over equal ground
     * in cell-index order, so that nudge grows from west to east and from north to south. D8 then
     * reads a gradient of exactly one nudge per cell pointing due east or due south, and beats
     * every diagonal because a diagonal's drop is divided by the root of two. The result is a
     * channel running dead straight from one shore of a lake to the other, and since every row of
     * the lake does the same thing, several of them in parallel. See REALISM_PLAN.md, "Render
     * review after Track E", for the runs that were measured.
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
        val cellsAcross = config.width
        val cellsDown = config.height
        val cellCount = cellsAcross * cellsDown
        val riverConfig = config.rivers
        if (sea.landCellCount == 0) return emptyList()

        val accumulation = flow.accumulation
        // sourceFlowShare is expressed against the whole world's runoff, so river density stays
        // consistent as resolution or sea level changes.
        val sourceFlow =
            (flow.totalRunoff * riverConfig.sourceFlowShare).coerceAtLeast(MIN_SOURCE_FLOW)

        // Open water is not channel. A playa is: it is dry ground most of the year and the river
        // across it is a real one.
        val isChannel = BooleanArray(cellCount) { cell ->
            sea.isLand[cell] && !lakes.isOpenWater(cell) && accumulation.data[cell] >= sourceFlow
        }

        val hasUpstream = BooleanArray(cellCount)
        for (cell in 0 until cellCount) {
            if (!isChannel[cell]) continue
            val target = flowTarget[cell]
            if (target >= 0 && isChannel[target]) hasUpstream[target] = true
        }

        val courseBelow = lengthsToTheWater(cellCount, isChannel, flowTarget)

        // Headwaters, the farthest from the water first, so a river claims its own longest
        // watercourse before any tributary can take part of it.
        var sourceCount = 0
        for (cell in 0 until cellCount) {
            if (isChannel[cell] && !hasUpstream[cell]) sourceCount++
        }
        // The length of the course below a head in the high half of the key and the cell index in
        // the low half, so one sort puts the farthest head last and ties fall to the lower cell
        // index on every platform. A count of cells is never negative, so it sorts in the same
        // order as its values — unlike FlowRouting.encode, which carries elevations.
        val sourcesByCourseLength = LongArray(sourceCount)
        var written = 0
        for (cell in 0 until cellCount) {
            if (isChannel[cell] && !hasUpstream[cell]) {
                sourcesByCourseLength[written++] =
                    (courseBelow[cell].toLong() shl 32) or cell.toLong()
            }
        }
        sourcesByCourseLength.sort()

        val claimed = BooleanArray(cellCount)
        val rivers = ArrayList<River>()

        for (rank in sourcesByCourseLength.indices.reversed()) {
            if (rivers.size >= riverConfig.maxRivers) break
            val source = FlowRouting.decodeIndex(sourcesByCourseLength[rank])

            val path = ArrayList<Int>()
            var claimedByThisRiver = 0
            var current = source
            var stepsTaken = 0
            while (current >= 0 && stepsTaken++ < cellCount) {
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

            if (path.size < riverConfig.minLengthCells) {
                // Release only the cells this trace claimed, never a trunk it merely touched.
                for (step in 0 until claimedByThisRiver) claimed[path[step]] = false
                continue
            }

            rivers.add(River(path.toIntArray()))
        }

        // Sized last, because the scale a channel is measured against is the whole network's: what
        // makes a trunk a trunk is that it carries more than anything else on this map.
        return RiverWidth.sizedByFlow(rivers, accumulation.data)
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
