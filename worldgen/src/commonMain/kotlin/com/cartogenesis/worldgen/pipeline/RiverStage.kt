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
    /**
     * Where the lake overflows, or would if it reached its brim: toward the sea, or, for a pocket of
     * a closed basin, over the saddle into the pocket beside it.
     */
    val outletCell: Int,
    /**
     * True when the lake has no outlet: its catchment's runoff cannot fill the basin to the brim
     * against evaporation, so the surface stands below [spillElevation], rivers end here and
     * nothing leaves. The Caspian and the Great Salt Lake, rather than Lake Erie. A pocket of a
     * closed basin that is full to its saddle and spills into the next pocket is not endorheic:
     * Utah Lake, draining into Great Salt Lake.
     */
    val endorheic: Boolean = false,
    /**
     * The brim: where the surface would sit if the basin were full, or, for a pocket of a closed
     * basin, the saddle it spills over.
     */
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
        solved: SolvedBasins?
    ): Routed {
        val cellsAcross = config.width
        val cellsDown = config.height
        val filled = fillDepressions(cellsAcross, cellsDown, sea)
        val flowTarget = computeFlowDirections(
            cellsAcross, cellsDown, sea, filled, config.seed, config.cellHeightInCellWidths,
            FlowRouting.smoothFieldPeriodCells(config),
            config.facetRouting, config.flatPotential
        )
        val catchmentRainMm = if (config.lakes.enabled && config.lakes.waterBalance) {
            FlowRouting.accumulate(
                cellsAcross, cellsDown, sea.isLand, filled, flowTarget, sea.landCellCount
            ) { cell -> climate.precipitationMm.data[cell] }
        } else null
        val lakes = findLakes(config, sea, climate, filled, flowTarget, catchmentRainMm, solved)
        solved?.routingAfterClosing = flowTarget
        return Routed(filled, flowTarget, lakes)
    }

    /**
     * One closed basin as the water balance was handed it: its cells at spill level, the cells its
     * water leaves by, the catchment rainfall in millimetre-cells that fed it, and whether the
     * balance closed it.
     */
    internal class SolvedBasin(
        val cells: IntArray,
        val exits: IntArray,
        val catchmentRainMm: Float,
        val endorheic: Boolean,
        /**
         * The rain the balance handed the basin's pockets, summed over all of them, in
         * millimeter-cells: what their catchments send them, before the runoff share is taken.
         */
        val pocketRainMm: Double
    )

    /**
     * Every closed basin of a world in the order the balance solved them, the routing as the fill
     * left it before any basin was closed, and the routing after. For the guards that read them;
     * the stage itself never needs the list.
     */
    internal class SolvedBasins {
        val basins = ArrayList<SolvedBasin>()
        /** How many groups of basins feeding each other were solved together. */
        var groupsSolvedTogether = 0
        var routingBeforeClosing = IntArray(0)
        var routingAfterClosing = IntArray(0)
    }

    /** [SolvedBasins] for the world [sea] and [climate] describe, routed as [generate] routes it. */
    internal fun solvedBasins(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        climate: ClimateResult
    ): SolvedBasins {
        val solved = SolvedBasins()
        routeAndSizeLakes(config, sea, climate, solved)
        return solved
    }

    /**
     * [SolvedBasins] over a routing given by hand: [filled] decides which cells lie under water at
     * spill level and [flowTarget] where each cell drains, so a guard can draw a basin whose exits
     * and paths the terrain would take a whole world to produce. [flowTarget] is not modified.
     */
    internal fun solvedBasinsOn(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        climate: ClimateResult,
        filled: FloatField,
        flowTarget: IntArray
    ): SolvedBasins {
        val routing = flowTarget.copyOf()
        val catchmentRainMm = FlowRouting.accumulate(
            config.width, config.height, sea.isLand, filled, routing, sea.landCellCount
        ) { cell -> climate.precipitationMm.data[cell] }
        val solved = SolvedBasins()
        findLakes(config, sea, climate, filled, routing, catchmentRainMm, solved)
        solved.routingAfterClosing = routing
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
     * the catchment can keep it there. [LakePockets] decides that, hollow by hollow, and this is
     * where a basin that cannot becomes endorheic: each of its pockets holds the water its own
     * catchment can sustain against evaporation off its own surface, or a playa where it holds none,
     * a pocket full to its saddle spills its surplus into the next, and two become one lake only
     * where both reach their saddle. The basin's flow targets are re-pointed at its terminal waters
     * rather than at the rim, and the water that runs in stops running out — which is why
     * [flowTarget] is taken by this function and modified, rather than being read. A lake is
     * numbered basin by basin in cell-index order, and within a basin by its lowest cell.
     *
     * **Upstream basins first, and a closed one takes its rain with it.** [catchmentRainMm] is
     * accumulated once, over the routing as the fill left it, when every basin still spills; so at
     * a lower basin's pour point it counts the rain of every basin above it. A basin the balance
     * closes never sends that rain on, so the moment one is closed its catchment is taken out of
     * every cell below its old spill, before any basin further down is solved. That needs the
     * basins upstream first, which is a topological order of the graph whose edges run from each
     * basin to the basin its exits' water reaches next on the routing the fill left; a group of
     * basins that feed each other is solved together, to a fixed point. See
     * [basinGroupsUpstreamFirst]. Solved in cell-index order with the field left
     * as it was, a playa upstream of a lake fed the lake rain it had already evaporated.
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
        solved: SolvedBasins?
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
        // Upstream first: the basins as a graph, an edge from each to the basin the water from each
        // of its exits reaches next, read off the routing as the fill left it, and walked in
        // topological order. Ordering by where a basin's exits fall in the drainage order is not
        // enough once a basin has two: its exit into a lower basin can come early and its other
        // exit late, and the lower basin would then be solved first on rain that is still stale.
        // Two basins can also feed each other, each by a different exit, and such a group has no
        // upstream member to solve first; it is solved together, below.
        val routingBeforeClosing = flowTarget.copyOf()
        val groupsUpstreamFirst = basinGroupsUpstreamFirst(
            basins.size, exitsOf, inBasin, routingBeforeClosing, sea.isLand
        )
        solved?.routingBeforeClosing = routingBeforeClosing

        // What the balance made of each closed basin: its bodies of standing water and its playas,
        // or null where the basin overflows at its brim.
        val bodiesOf = arrayOfNulls<List<StandingWater>>(basins.size)

        // Reused across basins. [LakeWaterBalance.routeIntoWater] empties it as it goes, so it is
        // all-false again by the time the next basin fills it.
        val pending = BooleanArray(cellCount)
        // The other two scratch arrays the re-routing needs. `settled` is stamped with the basin's
        // own number rather than a boolean, so it never has to be cleared; `pathKey` is only ever
        // read for cells the current basin has just written.
        val settled = IntArray(cellCount) { -1 }
        val pathKey = FloatArray(cellCount)
        var basinMark = 0
        // Scratch for [LakePockets.build], all -1 between basins.
        val localIndex = IntArray(cellCount) { -1 }
        // Scratch for [leafSuppliesOf]: the basin's own water carried on each cell below it, all
        // zero between calls, and the cells that call set.
        val carriedOwnMm = DoubleArray(cellCount)
        val carriedTouched = ArrayList<Int>()

        // A basin's pockets, built once however many times a group re-solves it. Sorted through the
        // elevation-keyed packing every other ordering in this pipeline uses, so equal heights fall
        // to the lower cell index on every platform.
        val pocketsCache = arrayOfNulls<LakePockets>(basins.size)
        fun pocketsOf(basin: Int): LakePockets = pocketsCache[basin] ?: run {
            val cells = basins[basin]
            val byGround = LongArray(cells.size) { FlowRouting.encode(ground.data[cells[it]], cells[it]) }
            byGround.sort()
            LakePockets.build(
                cellsAcross, cellsDown, cells, byGround, ground.data, climate.precipitationMm.data,
                evaporationMm, lakesConfig.runoffFraction, spillElevation[basin], localIndex
            ).also { pocketsCache[basin] = it }
        }

        // The runoff each of a basin's leaf pockets receives, in millimeter-cells: the share of the
        // rain on every basin cell that drains to it, and of everything [field] carries in from
        // outside the basin at the cells its water enters by. A basin in [absorbing] keeps its
        // water, so what it would have sent is not counted.
        fun leafSuppliesOf(basin: Int, pockets: LakePockets, field: FloatArray, absorbing: (Int) -> Boolean): DoubleArray {
            // The water below the basin on the routing the fill left that is the basin's own: a
            // path that leaves the basin across a level rim can turn back into it further along, and
            // what it carries back is an inflow only for what joined it on the way.
            carriedTouched.clear()
            for (cell in basins[basin]) {
                val target = routingBeforeClosing[cell]
                if (target < 0 || !sea.isLand[target] || inBasin[target] == basin) continue
                val leavingMm = field[cell].toDouble()
                var below = target
                var steps = 0
                while (below >= 0 && sea.isLand[below] && inBasin[below] != basin && steps++ < cellCount) {
                    if (carriedOwnMm[below] == 0.0) carriedTouched.add(below)
                    carriedOwnMm[below] += leavingMm
                    below = routingBeforeClosing[below]
                }
            }
            val supply = DoubleArray(pockets.pocketCount)
            val runoff = lakesConfig.runoffFraction.toDouble()
            for (index in pockets.layout.indices) {
                val cell = pockets.layout[index]
                var arrivingMm = climate.precipitationMm.data[cell].toDouble()
                FlowRouting.forEachNeighbour(cellsAcross, cellsDown, cell % cellsAcross, cell / cellsAcross) { neighbor ->
                    val from = inBasin[neighbor]
                    if (from == basin || !sea.isLand[neighbor] || routingBeforeClosing[neighbor] != cell) return@forEachNeighbour
                    if (from >= 0 && absorbing(from)) return@forEachNeighbour
                    arrivingMm += (field[neighbor] - carriedOwnMm[neighbor]).coerceAtLeast(0.0)
                }
                supply[pockets.leafOfLaidOut[index]] += runoff * arrivingMm
            }
            for (cell in carriedTouched) carriedOwnMm[cell] = 0.0
            return supply
        }
        fun inflowOf(basin: Int, field: FloatArray): Float {
            var catchmentMm = 0f
            for (exit in exitsOf[basin]) catchmentMm += field[exit]
            return catchmentMm
        }

        // Endorheic: the brim is not reached. Take each pocket's water to its own level, hand the rest
        // of the basin floor back to the land, and make the basin's terminal lakes and playas sinks:
        // no outlet river, and the rivers that used to run below it were carrying water that never
        // leaves. A pocket full to its saddle stays water and drains on over the saddle.
        val closed = BooleanArray(basins.size)
        fun close(basin: Int, pockets: LakePockets, water: LakePockets.Water) {
            val cells = basins[basin]
            val bodies = ArrayList<StandingWater>()
            val sinks = ArrayList<Int>()
            for (pocket in 0 until pockets.pocketCount) {
                val start = pockets.regionStart[pocket]
                val above = pockets.parent[pocket]
                val wouldSpillAt = if (above < 0) outletCell[basin] else pockets.saddleCell[above]
                if (water.holdsItsOwnLevel(pockets, pocket) && !pockets.isLeaf(pocket) &&
                    water.ownCellsUnderWater[pocket] == 0
                ) {
                    // Both children stand exactly at their saddle and the saddle itself stays dry:
                    // two lakes at one level, which meet only at the saddle, and each is its own
                    // body of water.
                    for (child in intArrayOf(pockets.firstChild[pocket], pockets.secondChild[pocket])) {
                        val childCells = pockets.layout.copyOfRange(pockets.regionStart[child], pockets.regionEnd[child])
                        val tooSmall = childCells.size < minLakeCells
                        bodies += StandingWater(
                            childCells, pockets.baseLevel(pocket), pockets.topLevel(pocket), wouldSpillAt,
                            endorheic = true, playa = tooSmall
                        )
                        childCells.forEach { sinks += it }
                    }
                } else if (water.holdsItsOwnLevel(pockets, pocket)) {
                    val ownUnder = water.ownCellsUnderWater[pocket]
                    val end = pockets.ownStart[pocket] + ownUnder
                    if (end - start >= minLakeCells) {
                        val surface = if (ownUnder > 0) {
                            (pockets.groundOfLaidOut(end - 1) + minDepth).coerceAtMost(pockets.topLevel(pocket))
                        } else {
                            pockets.baseLevel(pocket)
                        }
                        val waterCells = pockets.layout.copyOfRange(start, end)
                        bodies += StandingWater(waterCells, surface, pockets.topLevel(pocket), wouldSpillAt, endorheic = true, playa = false)
                        waterCells.forEach { sinks += it }
                    } else {
                        // Not enough water to read as a lake. What is left is a playa: the flat floor
                        // of the pocket, dry most of the year and briefly a sheet of water after rain.
                        // At least the ground within one minimum depth of the pocket's floor, so a
                        // pocket whose balance is zero still gets the flat it plainly has.
                        var flatEnd = maxOf(end, pockets.ownStart[pocket])
                        val floor = pockets.baseLevel(pocket)
                        while (flatEnd < pockets.regionEnd[pocket] && pockets.groundOfLaidOut(flatEnd) <= floor + minDepth) flatEnd++
                        if (flatEnd == start) flatEnd = start + 1
                        val flat = pockets.layout.copyOfRange(start, flatEnd)
                        bodies += StandingWater(flat, floor, pockets.topLevel(pocket), wouldSpillAt, endorheic = true, playa = true)
                        flat.forEach { sinks += it }
                    }
                } else if (water.full[pocket] && above >= 0 && !water.merged[above]) {
                    // Full to its saddle and spilling into the pocket across it: a lake with an
                    // outlet, whose water the routing carries on over the saddle.
                    if (pockets.regionEnd[pocket] - start >= minLakeCells) {
                        bodies += StandingWater(
                            pockets.layout.copyOfRange(start, pockets.regionEnd[pocket]), pockets.topLevel(pocket),
                            pockets.topLevel(pocket), wouldSpillAt, endorheic = false, playa = false
                        )
                    }
                }
            }
            bodiesOf[basin] = bodies
            closed[basin] = true
            for (cell in cells) pending[cell] = true
            LakeWaterBalance.routeIntoWater(
                cellsAcross, cellsDown, ground, pending, sinks.toIntArray(), cells.size, flowTarget,
                settled, basinMark++, pathKey, config.seed, config.cellHeightInCellWidths,
                FlowRouting.smoothFieldPeriodCells(config)
            )
        }

        // The rain reaching every cell on the routing the fill left, with every basin in
        // [absorbing] keeping what reaches it: the exact field a sequence of single closures
        // arrives at, and what a group of basins feeding each other is measured with.
        fun rainWithSinks(absorbing: (Int) -> Boolean): FloatArray {
            val routing = routingBeforeClosing.copyOf()
            for (cell in 0 until cellCount) {
                val basin = inBasin[cell]
                if (basin >= 0 && absorbing(basin)) routing[cell] = -1
            }
            return FlowRouting.accumulate(
                cellsAcross, cellsDown, sea.isLand, filled, routing, sea.landCellCount
            ) { cell -> climate.precipitationMm.data[cell] }.data
        }

        for (group in groupsUpstreamFirst) {
            if (group.size == 1) {
                val basin = group[0]
                val pockets = pocketsOf(basin)
                // Every cell of the basin drains out through its exits, so the accumulation there
                // is the whole catchment — the basin plus every slope that feeds it, less every
                // closed basin above it.
                val catchmentMm = inflowOf(basin, catchmentRainMm.data)
                val supplies = leafSuppliesOf(basin, pockets, catchmentRainMm.data) { closed[it] }
                val water = pockets.solve(supplies)
                solved?.basins?.add(
                    SolvedBasin(
                        basins[basin].copyOf(), exitsOf[basin].copyOf(), catchmentMm, endorheic = !water.rootFull,
                        pocketRainMm = supplies.sum() / lakesConfig.runoffFraction
                    )
                )
                if (water.rootFull) continue
                // The catchment stops at this basin, so nothing below its old spill receives it —
                // taken out along the path the water used to leave by, which is the routing before
                // any basin was closed: a closed basin below has re-pointed its own cells at its
                // water, and the rain being taken out was counted along the way the fill sent it.
                for (exit in exitsOf[basin]) {
                    val leaving = catchmentRainMm.data[exit]
                    var below = routingBeforeClosing[exit]
                    var steps = 0
                    while (below >= 0 && sea.isLand[below] && steps++ < cellCount) {
                        catchmentRainMm.data[below] =
                            (catchmentRainMm.data[below] - leaving).coerceAtLeast(0f)
                        below = routingBeforeClosing[below]
                    }
                }
                close(basin, pockets, water)
                continue
            }

            // Basins feeding each other, each by its own exit: none is upstream of the rest, and
            // whichever were solved first would be given the others' rain whether or not they keep
            // it. So the group is iterated to a fixed point. Each member's inflow is measured with
            // every closed basin keeping its water — those above the group, and the members closed
            // so far other than itself — and the members the balance then closes are the next
            // closed set. A closure only takes water away, so every inflow falls as the closed set
            // grows, and a basin the balance closes at one inflow it closes at any smaller one; so
            // the closed set only grows, and settles within as many passes as the group has
            // members. Each member is then solved on its inflow at the settled set.
            solved?.let { it.groupsSolvedTogether++ }
            var closedInGroup = BooleanArray(group.size)
            fun keepsItsWater(member: Int): (Int) -> Boolean = { basin ->
                closed[basin] || (basin != group[member] && group.indexOf(basin).let { it >= 0 && closedInGroup[it] })
            }
            var fields: List<FloatArray> = emptyList()
            for (pass in 0..group.size) {
                fields = List(group.size) { member -> rainWithSinks(keepsItsWater(member)) }
                val next = BooleanArray(group.size) { member ->
                    val pockets = pocketsOf(group[member])
                    !pockets.solve(leafSuppliesOf(group[member], pockets, fields[member], keepsItsWater(member))).rootFull
                }
                if (next.contentEquals(closedInGroup)) break
                closedInGroup = next
            }
            for (member in group.indices) {
                val basin = group[member]
                val pockets = pocketsOf(basin)
                val supplies = leafSuppliesOf(basin, pockets, fields[member], keepsItsWater(member))
                val water = pockets.solve(supplies)
                solved?.basins?.add(
                    SolvedBasin(
                        basins[basin].copyOf(), exitsOf[basin].copyOf(), inflowOf(basin, fields[member]),
                        endorheic = !water.rootFull, pocketRainMm = supplies.sum() / lakesConfig.runoffFraction
                    )
                )
                if (!water.rootFull) close(basin, pockets, water)
            }
            // Everything below the group sees its closures: the field re-taken whole, which the
            // single closures' subtractions would have arrived at one by one.
            rainWithSinks { closed[it] }.copyInto(catchmentRainMm.data)
        }

        // Lakes numbered basin by basin in cell-index order, and within a basin by the lowest cell
        // each body holds, so the numbering is the same on every platform.
        val lakes = ArrayList<Lake>()
        for (basin in basins.indices) {
            val bodies = bodiesOf[basin]
            if (bodies == null) {
                fillToBrim(lakeId, lakes, basins[basin], spillElevation[basin], outletCell[basin])
                continue
            }
            for (body in bodies.sortedBy { it.cells.min() }) {
                if (body.playa) {
                    for (cell in body.cells) playa[cell] = true
                    continue
                }
                val id = lakes.size
                for (cell in body.cells) lakeId[cell] = id
                lakes.add(
                    Lake(
                        id, body.cells.size, body.surface, body.outletCell,
                        endorheic = body.endorheic, spillElevation = body.spill
                    )
                )
            }
        }
        return LakeResult(lakeId, lakes, playa, cellsAcross)
    }

    /**
     * One body of water a closed basin's balance leaves: a pocket's lake at its own level, a pocket
     * full to its saddle and spilling on, or a playa. [spill] is the level it would spill at and
     * [outletCell] the cell it would spill over.
     */
    private class StandingWater(
        val cells: IntArray,
        val surface: Float,
        val spill: Float,
        val outletCell: Int,
        val endorheic: Boolean,
        val playa: Boolean
    )

    /**
     * The basins in groups, each group before any group its water reaches: each exit's path is
     * followed down [routing] to the first cell of another basin, which is an edge of the graph, or
     * to the sea, which is none. A group is a strongly connected set — basins whose water reaches
     * each other, which a forest of cells allows when each feeds the other by a different exit — and
     * is almost always one basin. Found by Tarjan's algorithm, which hands the groups out downstream
     * first, so the list is reversed; basins are taken in index order and edges in exit order, and
     * each group is sorted, so the answer is one specific order on every platform.
     */
    private fun basinGroupsUpstreamFirst(
        basinCount: Int,
        exitsOf: Array<IntArray>,
        inBasin: IntArray,
        routing: IntArray,
        isLand: BooleanArray
    ): List<IntArray> {
        val below = Array(basinCount) { ArrayList<Int>() }
        for (basin in 0 until basinCount) {
            for (exit in exitsOf[basin]) {
                var cell = routing[exit]
                var steps = 0
                while (cell >= 0 && isLand[cell] && steps++ < routing.size) {
                    val reached = inBasin[cell]
                    if (reached >= 0 && reached != basin) {
                        if (reached !in below[basin]) below[basin].add(reached)
                        break
                    }
                    cell = routing[cell]
                }
            }
        }

        // Tarjan, with an explicit stack of (basin, next edge) so a long chain of basins cannot
        // overflow the call stack.
        val index = IntArray(basinCount) { -1 }
        val lowLink = IntArray(basinCount)
        val onStack = BooleanArray(basinCount)
        val stack = ArrayList<Int>()
        val groups = ArrayList<IntArray>()
        var nextIndex = 0
        val walkBasin = IntArray(basinCount)
        val walkEdge = IntArray(basinCount)
        for (root in 0 until basinCount) {
            if (index[root] >= 0) continue
            var depth = 0
            walkBasin[0] = root
            walkEdge[0] = 0
            index[root] = nextIndex; lowLink[root] = nextIndex; nextIndex++
            stack.add(root); onStack[root] = true
            while (depth >= 0) {
                val basin = walkBasin[depth]
                if (walkEdge[depth] < below[basin].size) {
                    val next = below[basin][walkEdge[depth]++]
                    if (index[next] < 0) {
                        index[next] = nextIndex; lowLink[next] = nextIndex; nextIndex++
                        stack.add(next); onStack[next] = true
                        depth++
                        walkBasin[depth] = next
                        walkEdge[depth] = 0
                    } else if (onStack[next] && index[next] < lowLink[basin]) {
                        lowLink[basin] = index[next]
                    }
                    continue
                }
                if (lowLink[basin] == index[basin]) {
                    val group = ArrayList<Int>()
                    do {
                        val member = stack.removeAt(stack.size - 1)
                        onStack[member] = false
                        group.add(member)
                    } while (member != basin)
                    groups.add(group.sorted().toIntArray())
                }
                depth--
                if (depth >= 0) {
                    val parent = walkBasin[depth]
                    if (lowLink[basin] < lowLink[parent]) lowLink[parent] = lowLink[basin]
                }
            }
        }
        groups.reverse()
        return groups
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
        smoothFieldPeriodCells: Int,
        byFacet: Boolean,
        overPotential: Boolean
    ): IntArray = FlowRouting.flowDirections(
        width, height, sea.isLand, sea.relativeElevation, filled, seed, cellHeightInCellWidths,
        smoothFieldPeriodCells,
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
