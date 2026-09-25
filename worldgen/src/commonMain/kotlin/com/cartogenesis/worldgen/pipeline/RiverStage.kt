package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.sqrt
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
     * widest channel on the map again. That thread is what the author saw between two thick rivers on
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
    /**
     * The discharge each cell carries, in millimetre-cells: every upstream cell's rainfall in
     * millimetres a year, held at [Runoff.FLOOR_MM] where drier, summed down the routing.
     */
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
 * See docs/DESIGN_LEDGER.md, E1 and E2, and GEOGRAPHY.md for what each rule is judged against.
 */
object RiverStage {

    /**
     * Every land cell's water and the channels it makes: the depression-filled surface, the D8
     * target of each cell, the flow accumulated down that tree, the lakes the fill implies, and
     * the rivers traced over the result.
     *
     * [sea] gives the land mask and the ground the water runs over; [climate] gives the rainfall in
     * millimetres a year, which both the discharge and the lake water balance are weighted by.
     * Every per-cell array is row-major at `config.width` by `config.height`, and
     * [RiverResult.flowAccumulation] is in millimetre-cells: [Runoff.annualWeightMm] summed over
     * every cell upstream.
     */
    fun generate(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        climate: ClimateResult
    ): RiverResult {
        val routed = routeAndSizeLakes(config, sea, climate, solved = null)
        val flow = accumulateFlow(
            config.width, config.height, sea, climate, routed.filled, routed.flowTarget
        )
        val isChannel = ChannelInitiation.channelMask(
            config, sea.isLand, sea.landCellCount, sea.relativeElevation, routed.filled,
            routed.flowTarget, climate
        ) { routed.lakes.isOpenWater(it) }
        val rivers = traceRivers(config, sea, flow, routed.flowTarget, routed.lakes, isChannel)

        return RiverResult(routed.filled, flow, routed.flowTarget, rivers, routed.lakes)
    }

    private class Routed(val filled: FloatField, val flowTarget: IntArray, val lakes: LakeResult)

    /**
     * The filled surface, the routing over it, and the lakes it implies, with the routing inside
     * every closed basin re-pointed at the water the balance leaves in it.
     *
     * Lakes are sized before the water is accumulated, because an endorheic basin changes the
     * answer: nothing leaves it, so every cell downstream of its rim loses that whole catchment
     * and the river that used to be drawn below a desert basin stops being drawn. The balance
     * itself needs a catchment total of its own, in millimetres of rain, which is the same
     * accumulation run over the rainfall rather than over the runoff weight.
     */
    private fun routeAndSizeLakes(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        climate: ClimateResult,
        solved: MutableList<SolvedBasin>?
    ): Routed {
        val cellsAcross = config.width
        val cellsDown = config.height
        val filled = fillDepressions(cellsAcross, cellsDown, sea)
        val flowTarget = computeFlowDirections(
            cellsAcross, cellsDown, sea, filled, config.seed, config.cellHeightInCellWidths,
            config.facetRouting, config.flatPotential
        )
        val catchmentRainMm = if (config.lakes.enabled && config.lakes.waterBalance) {
            FlowRouting.accumulate(
                cellsAcross, cellsDown, sea.isLand, filled, flowTarget, sea.landCellCount
            ) { cell -> climate.precipitationMm.data[cell] }
        } else null
        val lakes = findLakes(config, sea, climate, filled, flowTarget, catchmentRainMm, solved)
        return Routed(filled, flowTarget, lakes)
    }

    /**
     * One closed basin as the water balance was handed it: its cells at spill level, the catchment
     * rainfall in millimetre-cells that fed it, and whether the balance closed it.
     */
    internal class SolvedBasin(val cells: IntArray, val catchmentRainMm: Float, val endorheic: Boolean)

    /**
     * Every closed basin of the world [sea] and [climate] describe, in the order the balance solved
     * them, with the inflow each was given — the one figure the water balance takes from outside
     * the basin, and the one a basin upstream can wrongly add to. For the guard that reads it;
     * the stage itself never needs the list.
     */
    internal fun solvedBasins(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        climate: ClimateResult
    ): List<SolvedBasin> {
        val solved = ArrayList<SolvedBasin>()
        routeAndSizeLakes(config, sea, climate, solved)
        return solved
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
     *
     * **Upstream basins first, and a closed one takes its rain with it.** [catchmentRainMm] is
     * accumulated once, over the routing as the fill left it, when every basin still spills; so at
     * a lower basin's pour point it counts the rain of every basin above it. A basin the balance
     * closes never sends that rain on, so the moment one is closed its catchment is taken out of
     * every cell below its old spill, before any basin further down is solved. That needs the
     * basins in drainage order, sources first, which is the order of their last exits in
     * [FlowRouting.drainageOrder]: a basin that spills into another passes its water through that
     * one's exits, so its own come earlier. Solved in cell-index order with the field left
     * as it was, a playa upstream of a lake fed the lake rain it had already evaporated. The lakes
     * are still numbered in cell-index order, as they always were.
     *
     * [solved], where given, receives each basin as the balance was handed it; see [solvedBasins].
     */
    private fun findLakes(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        climate: ClimateResult,
        filled: FloatField,
        flowTarget: IntArray,
        catchmentRainMm: FloatField?,
        solved: MutableList<SolvedBasin>?
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

        // Every basin big enough to read as water, in cell-index order. A basin can end up with no
        // lake on it — too dry — so membership is tracked apart from the lake ids, which are only
        // for cells that finish under water.
        val basins = ArrayList<IntArray>()
        val toVisit = ArrayDeque<Int>()
        val basinCells = ArrayList<Int>()
        val visited = BooleanArray(cellCount)
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
            basins.add(basinCells.toIntArray())
        }

        // The brim, and the cell the water leaves through — wherever the basin drains to dry
        // ground. Read before any basin is re-pointed; a basin's own routing is the only routing
        // its solution changes, so the answer for the others stands.
        val spillElevation = FloatArray(basins.size) { basin -> basins[basin].maxOf { filled.data[it] } }
        val outletCell = IntArray(basins.size) { basin ->
            val cells = basins[basin]
            cells.firstOrNull { cell ->
                val target = flowTarget[cell]
                target >= 0 && !submerged[target]
            } ?: cells[0]
        }

        if (catchmentRainMm == null) {
            val lakes = ArrayList<Lake>()
            for (basin in basins.indices) {
                fillToBrim(lakeId, lakes, basins[basin], spillElevation[basin], outletCell[basin])
            }
            return LakeResult(lakeId, lakes, playa, cellsAcross)
        }

        // Potential evaporation is a per-cell property of the climate, not of any basin, so it is
        // worth computing once rather than once per candidate level.
        val evaporationMm = FloatArray(cellCount) { cell ->
            if (!sea.isLand[cell]) 0f else LakeWaterBalance.potentialEvaporationMm(
                climate.summerTemperature.data[cell],
                climate.winterTemperature.data[cell],
                lakesConfig.evaporationScale
            )
        }

        // Where each basin's water leaves it: every cell of the basin whose receiver lies outside
        // it. Usually one, the pour point on the rim; where a rim is level for several cells the
        // flat can drain across more than one, and the catchment is then the sum over all of them.
        val inBasin = IntArray(cellCount) { -1 }
        for (basin in basins.indices) for (cell in basins[basin]) inBasin[cell] = basin
        // An exit whose water comes back into the basin further down is not where it leaves, and
        // counting it would count its water twice.
        fun leavesForGood(exit: Int, basin: Int): Boolean {
            var cell = flowTarget[exit]
            var steps = 0
            while (cell >= 0 && sea.isLand[cell] && steps++ < cellCount) {
                if (inBasin[cell] == basin) return false
                cell = flowTarget[cell]
            }
            return true
        }
        val exitsOf = Array(basins.size) { basin ->
            basins[basin].filter { cell ->
                val target = flowTarget[cell]
                (target < 0 || inBasin[target] != basin) && leavesForGood(cell, basin)
            }.toIntArray()
        }
        val drainageRank = IntArray(cellCount)
        FlowRouting.drainageOrder(cellsAcross, cellsDown, sea.isLand, flowTarget, sea.landCellCount)
            .forEachIndexed { rank, cell -> drainageRank[cell] = rank }
        // A basin spilling into another passes its water through that one's exits, so its own
        // last exit comes earlier in the drainage order.
        val upstreamFirst = basins.indices.sortedWith(
            compareBy<Int> { basin -> exitsOf[basin].maxOfOrNull { drainageRank[it] } ?: 0 }.thenBy { it }
        )

        // What the balance made of each basin: its water cells, or null where it overflows, and
        // whether that water is a lake or a playa.
        val waterOf = arrayOfNulls<IntArray>(basins.size)
        val waterIsPlaya = BooleanArray(basins.size)
        val surfaceOf = FloatArray(basins.size)

        // Reused across basins. [LakeWaterBalance.routeIntoWater] empties it as it goes, so it is
        // all-false again by the time the next basin fills it.
        val pending = BooleanArray(cellCount)
        // The other two scratch arrays the re-routing needs. `settled` is stamped with the basin's
        // own number rather than a boolean, so it never has to be cleared; `pathKey` is only ever
        // read for cells the current basin has just written.
        val settled = IntArray(cellCount) { -1 }
        val pathKey = FloatArray(cellCount)
        var basinMark = 0

        for (basin in upstreamFirst) {
            val cells = basins[basin]
            // The basin's own cells, lowest ground first: its hypsometry, and the order the water
            // covers them in. Sorted through the elevation-keyed packing every other ordering in
            // this pipeline uses, so equal heights fall to the lower cell index on every platform.
            val byGround = LongArray(cells.size)
            for (rank in cells.indices) {
                byGround[rank] = FlowRouting.encode(ground.data[cells[rank]], cells[rank])
            }
            byGround.sort()

            val basinCellCount = cells.size
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

            // Every cell of the basin drains out through its exits, so the accumulation there is
            // the whole catchment — the basin plus every slope that feeds it, less every closed
            // basin above it.
            var catchmentMm = 0f
            for (exit in exitsOf[basin]) catchmentMm += catchmentRainMm.data[exit]
            val balance = LakeWaterBalance.solve(
                sortedGround, rainPrefixMm, evaporationPrefixMm,
                catchmentMm, spillElevation[basin], minDepth, lakesConfig.runoffFraction
            )
            solved?.add(SolvedBasin(cells.copyOf(), catchmentMm, endorheic = !balance.atSpill))
            if (balance.atSpill) continue

            // Endorheic. Take the water back to the balance level, hand the rest of the basin
            // floor back to the land, and make the basin a sink: no outlet river, and the rivers
            // that used to run below it were carrying water that never leaves.
            val waterCells: IntArray
            if (balance.submergedCells >= minLakeCells) {
                waterCells = IntArray(balance.submergedCells) { FlowRouting.decodeIndex(byGround[it]) }
                surfaceOf[basin] = balance.surface
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
                waterIsPlaya[basin] = true
            }
            waterOf[basin] = waterCells

            // The catchment stops at this basin, so nothing below its old spill receives it —
            // taken out along the path the water used to leave by, before that path is re-pointed.
            for (exit in exitsOf[basin]) {
                val leaving = catchmentRainMm.data[exit]
                var below = flowTarget[exit]
                var steps = 0
                while (below >= 0 && sea.isLand[below] && steps++ < cellCount) {
                    catchmentRainMm.data[below] =
                        (catchmentRainMm.data[below] - leaving).coerceAtLeast(0f)
                    below = flowTarget[below]
                }
            }

            for (cell in cells) pending[cell] = true
            LakeWaterBalance.routeIntoWater(
                cellsAcross, cellsDown, ground, pending, waterCells, cells.size, flowTarget,
                settled, basinMark++, pathKey, config.seed, config.cellHeightInCellWidths
            )
        }

        val lakes = ArrayList<Lake>()
        for (basin in basins.indices) {
            val water = waterOf[basin]
            when {
                water == null ->
                    fillToBrim(lakeId, lakes, basins[basin], spillElevation[basin], outletCell[basin])
                waterIsPlaya[basin] -> for (cell in water) playa[cell] = true
                else -> {
                    val id = lakes.size
                    for (cell in water) lakeId[cell] = id
                    lakes.add(
                        Lake(
                            id, water.size, surfaceOf[basin], outletCell[basin],
                            endorheic = true, spillElevation = spillElevation[basin]
                        )
                    )
                }
            }
        }
        return LakeResult(lakeId, lakes, playa, cellsAcross)
    }

    /** The right answer wherever the basin overflows: water to the brim over every basin cell. */
    private fun fillToBrim(
        lakeId: IntArray,
        lakes: MutableList<Lake>,
        basinCells: IntArray,
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
        filled: FloatField,
        seed: Long,
        cellHeightInCellWidths: Double,
        byFacet: Boolean,
        overPotential: Boolean
    ): IntArray = FlowRouting.flowDirections(
        width, height, sea.isLand, sea.relativeElevation, filled, seed, cellHeightInCellWidths,
        byFacet, overPotential
    )

    /**
     * The discharge every cell carries, in millimetre-cells: [Runoff.annualWeightMm] summed over
     * the cell and everything upstream of it.
     *
     * The absolute rainfall and not `ClimateResult.precipitation`, the 0..1 copy clamped at
     * `ClimateStage.REFERENCE_MM`, because a trunk draining rainforest at three thousand millimetres
     * carries two and a half times what one at twelve hundred does, and the clamped copy drew,
     * ranked and split the two as the same river. `NationStage` reads its riverine threshold off
     * the same helper, so the threshold and the discharge are in one unit. See
     * docs/DESIGN_LEDGER.md, chunk 6.
     */
    private fun accumulateFlow(
        cellsAcross: Int,
        cellsDown: Int,
        sea: SeaLevelResult,
        climate: ClimateResult,
        filled: FloatField,
        flowTarget: IntArray
    ): FloatField = FlowRouting.accumulate(
        cellsAcross, cellsDown, sea.isLand, filled, flowTarget, sea.landCellCount
    ) { cell -> Runoff.annualWeightMm(climate.precipitationMm.data[cell]) }

    /**
     * Whether a headwater is a lake's outflow rather than a scratch on a hillside: some neighbour
     * of it is open water that drains into it.
     *
     * The exemption `RiverConfig.shortestDrawnCourseKm` names. A cell whose only upstream water is
     * a lake has no channel above it and so is a head like any other, and where the lake sits a few
     * cells from the trunk its course is shorter than the drawing asks for — but everything the
     * lake drains comes down it, so a short one is a great river and not a rill. I3's finisher found
     * the case on seed 7 at 512: cell 191210, one cell of narrow water at the outflow end of a
     * twelve-cell lake, with a drawn trunk one cell below and no line between them.
     *
     * Asked of the neighbours rather than of the flow targets, because the lake's own cells are
     * re-pointed at the water inside an endorheic basin and a brim-full lake's outlet cell drains
     * *to* this one: either way the water above a true outflow is beside it.
     */
    private fun drainsALake(
        cellsAcross: Int,
        cellsDown: Int,
        head: Int,
        flowTarget: IntArray,
        lakes: LakeResult
    ): Boolean {
        var found = false
        FlowRouting.forEachNeighbour(
            cellsAcross, cellsDown, head % cellsAcross, head / cellsAcross
        ) { neighbour ->
            if (lakes.isOpenWater(neighbour) && flowTarget[neighbour] == head) found = true
        }
        return found
    }

    /** The ground one D8 step covers, in kilometres, on a grid whose cells are not square. */
    private fun stepKilometres(
        from: Int,
        to: Int,
        cellsAcross: Int,
        cellWidthKm: Float,
        cellHeightKm: Float,
        diagonalKm: Float
    ): Float {
        var columnStep = (to % cellsAcross) - (from % cellsAcross)
        if (columnStep > cellsAcross / 2) columnStep -= cellsAcross
        if (columnStep < -cellsAcross / 2) columnStep += cellsAcross
        val rowStep = (to / cellsAcross) - (from / cellsAcross)
        return when {
            columnStep != 0 && rowStep != 0 -> diagonalKm
            columnStep != 0 -> cellWidthKm
            else -> cellHeightKm
        }
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
     * the lake does the same thing, several of them in parallel. See docs/DESIGN_LEDGER.md, "Render
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
     *
     * [isChannel] is [ChannelInitiation]'s, and since R1 the network is every reach the ground can
     * cut a channel in rather than every reach carrying a share of the world's runoff. What is left
     * here of the drawing is two rules and both are cartographic: a course shorter than
     * `RiverConfig.shortestDrawnCourseKm` is not given a line of its own, and neither is a course
     * that ends in water with one cell of land above the mouth, which has no reach left once the
     * ink is stopped at the shore. There is no cap on the
     * number of courses; a count of courses was a limit on the drawing masquerading as a fact about
     * the world, and at four hundred it drew half of a 2048 world's watercourses and nearly all of
     * a 512 one's.
     */
    private fun traceRivers(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        flow: FloatField,
        flowTarget: IntArray,
        lakes: LakeResult,
        isChannel: BooleanArray
    ): List<River> {
        val cellsAcross = config.width
        val cellsDown = config.height
        val cellCount = cellsAcross * cellsDown
        val riverConfig = config.rivers
        if (sea.landCellCount == 0) return emptyList()

        val accumulation = flow
        val cellWidthKm = config.cellWidthKm.toFloat()
        val cellHeightKm = config.cellHeightKm.toFloat()
        val diagonalKm = sqrt(cellWidthKm * cellWidthKm + cellHeightKm * cellHeightKm)

        // The water a course ends at: the sea, or a lake's own surface. A strip of lake one cell
        // wide is not open water and is a reach of the river, for the reason [LakeResult.openWater]
        // gives.
        fun isTheWater(cell: Int): Boolean = !sea.isLand[cell] || lakes.isOpenWater(cell)

        val hasUpstream = BooleanArray(cellCount)
        for (cell in 0 until cellCount) {
            if (!isChannel[cell]) continue
            val target = flowTarget[cell]
            if (target >= 0 && isChannel[target]) hasUpstream[target] = true
        }

        val courseBelowKm = kilometresToTheWater(
            cellCount, cellsAcross, isChannel, flowTarget, cellWidthKm, cellHeightKm, diagonalKm
        )

        // Headwaters, the farthest from the water first, so a river claims its own longest
        // watercourse before any tributary can take part of it.
        var sourceCount = 0
        for (cell in 0 until cellCount) {
            if (isChannel[cell] && !hasUpstream[cell]) sourceCount++
        }
        // The kilometres below a head packed above the cell index, through the same encoding
        // every other ordering in this pipeline uses, so one sort puts the farthest head last and
        // ties fall to the lower cell index on every platform.
        val sourcesByCourseLength = LongArray(sourceCount)
        var written = 0
        for (cell in 0 until cellCount) {
            if (isChannel[cell] && !hasUpstream[cell]) {
                sourcesByCourseLength[written++] =
                    FlowRouting.encode(courseBelowKm[cell], cell)
            }
        }
        sourcesByCourseLength.sort()

        val claimed = BooleanArray(cellCount)
        val rivers = ArrayList<River>()

        for (rank in sourcesByCourseLength.indices.reversed()) {
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
                if (isTheWater(next)) {
                    path.add(next) // the river mouth, on the sea or on a lake shore
                    break
                }
                current = next
            }

            var courseKm = 0f
            for (step in 0 until path.size - 1) {
                courseKm += stepKilometres(
                    path[step], path[step + 1], cellsAcross, cellWidthKm, cellHeightKm, diagonalKm
                )
            }
            val tooShortToDraw = courseKm < riverConfig.shortestDrawnCourseKm && !drainsALake(
                cellsAcross, cellsDown, source, flowTarget, lakes
            )
            // A course that ends in water and holds one cell of land has no reach to draw. The
            // mouth is the water the last cell on land drains into, and what carries ink is the
            // land above it, cut back half a stroke from the shore so the round cap is tangent to
            // the coast (`MapRasterizer.trimmedAtTheShore`). With a single cell on land there is
            // no vertex above it to pull the end back to, so the line is drawn whole and finishes
            // at the centre of the water with half a stroke of cap beside it.
            //
            // Only the lake exemption above ever offers one: a single step is one cell of ground
            // and the length rule drops it otherwise. Seed 42 at 512 has the case — cell 139041,
            // one cell of land standing in a 449-cell lake with the lake's own water above it and
            // below it — and it is what the exemption is not for. Water crossing a rock in a lake
            // is not a watercourse; the exemption is for an outflow carrying a lake's water on.
            val noReachOnLand = path.size < 3 && isTheWater(path[path.size - 1])
            if (tooShortToDraw || noReachOnLand) {
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
     * How far it is from each channel cell to the water it ends at, in kilometres: the length of
     * the watercourse below it.
     *
     * **Kilometres and not cells, and the difference is not cosmetic.** This world is twice as wide
     * as it is tall on a square grid, so a cell is half as tall as it is wide — 23.4 km across and
     * 11.7 down at 512 — and a course of six cells running north is shorter ground than a course of
     * five running east. Ranked by cells, the tracer would hand the map's longest course to the
     * branch with the most *steps* in it, and where the shorter-in-cells branch was longer in
     * kilometres the length rule below would then drop the one it had preferred and draw the other.
     * Seed 7 at 512 had one: a six-cell head whose course was 58 km, under the hundred the drawing
     * asks for, releasing its claim to a five-cell head of 117 km. Measured on the ground there is
     * no such case, because the two rules are then asking the same question.
     *
     * The flow targets are a forest — every cell has one receiver, strictly lower on the filled
     * surface — so the answer for a cell is its own step plus the answer for its receiver, and the
     * whole field falls out of one memoised walk per unvisited cell. The walk is bounded by the
     * grid for the same reason the trace above is: a routing bug that made a ring would otherwise
     * hang the generator rather than draw something odd.
     */
    private fun kilometresToTheWater(
        cellCount: Int,
        cellsAcross: Int,
        isChannel: BooleanArray,
        flowTarget: IntArray,
        cellWidthKm: Float,
        cellHeightKm: Float,
        diagonalKm: Float
    ): FloatArray {
        val below = FloatArray(cellCount)
        val walked = ArrayList<Int>()
        for (start in 0 until cellCount) {
            if (!isChannel[start] || below[start] != 0f) continue
            walked.clear()
            var cell = start
            while (cell >= 0 && isChannel[cell] && below[cell] == 0f && walked.size < cellCount) {
                walked.add(cell)
                cell = flowTarget[cell]
            }
            var kilometres = if (cell >= 0 && isChannel[cell]) below[cell] else 0f
            for (k in walked.indices.reversed()) {
                val here = walked[k]
                val next = if (k + 1 < walked.size) walked[k + 1] else cell
                kilometres += if (next < 0) {
                    cellHeightKm
                } else {
                    stepKilometres(
                        here, next, cellsAcross, cellWidthKm, cellHeightKm, diagonalKm
                    )
                }
                below[here] = kilometres
            }
        }
        return below
    }

}
