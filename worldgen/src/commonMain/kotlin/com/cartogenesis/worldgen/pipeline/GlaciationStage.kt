package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.math.JumpFloodDistance
import com.cartogenesis.worldgen.math.LongMinHeap
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.GlaciationConfig
import com.cartogenesis.worldgen.model.IsostasyConfig
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.noise.PerlinNoise
import kotlin.math.sqrt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * What one run of the ice moved, in the units the elevation field itself is kept in.
 *
 * Unlike the hydraulic pass's [RoundMass] this is not a budget that has to balance. Ice is not a
 * conveyor: a glacier exports rock flour to the sea and to the outwash plain for ten thousand years
 * and hands back only the till at its snout, so excavation exceeding deposition by two orders of
 * magnitude is the correct answer rather than a leak. It is reported because an unbalanced budget
 * that nobody looks at is how a stage quietly removes a tenth of a continent.
 */
internal data class GlacialMass(
    /**
     * The deepest the ice load pushed the crust down, in metres, and zero where the load is off.
     *
     * A tenth of the sheet's own thickness at its margin and 28% of it well inside, which is
     * `iceDensity / mantleDensity` once the plate has flattened out under a load broader than its
     * own flexural parameter.
     */
    val iceDepressionMetres: Float,
    /**
     * The sheet's own thickness at every cell, in metres, and zero off the sheet regime — the
     * Vialov profile [IceSheet] draws, which is what the load, the surface and the outlet cuts are
     * all measured from. An observer for the guards; the pipeline reads the *surface* instead, off
     * the elevation field.
     */
    val iceThicknessMetres: FloatArray,
    /** How far each frozen cell stands from the ice margin, in kilometres: the profile's argument. */
    val marginDistanceKm: FloatArray,
    /** The sheet regime's own mask, so a guard can ask a question of the sheet and not of the ice. */
    val onTheSheet: BooleanArray,
    /** Which neighbour the ice flows to down its own surface, and -1 off the sheet. */
    val sheetFlowReceiver: IntArray,
    /** Cells whose elevation is the top of the ice rather than the rock. */
    val iceSurfaceCells: Int,
    /** Outlet glaciers found, cells their troughs cut, and the deepest cut, in metres. */
    val outlets: Int,
    val outletCells: Int,
    val deepestOutletCutMetres: Float,
    val frozenCells: Int,
    /** Frozen cells with enough local relief for the ice to be channelled into a valley. */
    val channelledCells: Int,
    val glacierCells: Int,
    /** Separate glaciers left, and how many cells were dropped for running parallel to a stronger
     * one's trough. */
    val trunks: Int,
    val parallelCellsDropped: Int,
    /** Frozen cells handed to the sheet regime instead: flat ground, scoured rather than grooved. */
    val sheetCells: Int,
    /** The stage's whole allowance of standing water, in cells, and what each regime spent. */
    val lakeBudget: Int,
    /** Cells cut into a valley basin, and how many separate basins they form. */
    val basinCells: Int,
    val basins: Int,
    /** Candidate basins refused: no valley floor, too straight, too small, or the budget was spent. */
    val basinsWithNoFloor: Int,
    val basinsTooStraight: Int,
    val basinsTooSmall: Int,
    val basinsOverBudget: Int,
    /** Cells cut into a scour basin, and how many separate basins they form. */
    val scourCells: Int,
    val scourBasins: Int,
    /**
     * Which cut basin each cell's floor belongs to, and -1 on every cell that is not one.
     *
     * The valley basins are numbered first, `0` until [basins], and the sheet's scour basins
     * follow them. An observer for the shape guards — `GlaciationTest` reads a basin's outline and
     * the hypsometry of its floor off this — and nothing in the pipeline reads it at all.
     */
    val basinFloor: IntArray,
    val cirques: Int,
    val moraines: Int,
    val riegels: Int,
    /** Rock taken off the land: troughs, cirques, sheet scour and all. */
    val excavated: Double,
    /** Till laid back down at the snouts. */
    val deposited: Double,
    /** Sea floor taken out of the fjord basins, which is neither of the above. */
    val submarine: Double
)

/**
 * Step 3b: let the ice have its turn at the terrain.
 *
 * Everything upstream of here is the work of rock and running water, and the two of them cannot
 * between them produce any of the landforms a cold world is recognised by. Water cuts a V, because
 * it cuts at a point and the walls stand at whatever angle they can; ice fills its valley wall to
 * wall and cuts across the whole section at once, which is a U. Water grades everywhere toward its
 * outlet and can never leave a hollow in its own bed; ice is a solid being shoved from behind and
 * will happily gouge a basin a hundred metres below the lip it has to climb to get out, which is
 * why the recently glaciated parts of the world — Finland, Canada, the Lake District, Patagonia —
 * are stippled with lakes and the unglaciated parts are not. That contrast is this stage's whole
 * purpose, and `GlaciationTest` measures it.
 *
 * ### Where it runs, and why here
 *
 * Between sea level and the ocean currents, which is two stages before the climate that decides
 * where ice belongs. That is not an oversight, it is the ordering problem: climate is computed from
 * the terrain, so terrain that ice is going to carve has to be carved before the climate reads it,
 * or the biomes, the rivers and the lakes would all be answers about a world that no longer exists.
 *
 * The way out is that everything ice depends on can be computed before this stage runs, because
 * the climate stage reads only the terrain and the terrain is already here. So the engine runs a
 * whole *provisional* climate — the same temperature curve, the same maritime and current
 * anomalies, the same two seasonal moisture marches, all of it [ClimateStage]'s own code — on the
 * pre-glaciation terrain, and hands this stage the snow balance that comes out of it. Ice is where
 * that balance is positive. The final climate still runs after the carving, on the carved terrain,
 * and it is the one the map shows; the provisional one exists only to say where the ice was.
 *
 * The cruder mask this replaced — a provisional mean annual temperature at or below freezing —
 * cannot tell a snowy highland from a frozen desert and so froze every cold interior on the map.
 * That rule is still here, behind `ClimateConfig.snowBalance`, as the control the guard needs. See
 * docs/DESIGN_LEDGER.md, H2.
 *
 * ### Two regimes, decided by relief
 *
 * Ice does not do one thing. A *valley glacier* is ice confined between rock walls: it is steered
 * by the valley it fills, it is thickest and fastest on the axis, and it planes a U across the
 * section — troughs, cirques, riegels, a moraine at the snout. An *ice sheet* is ice that has
 * drowned the landscape it sits on. It is not steered by the drainage network at all, because the
 * drainage network is a kilometre beneath it; it scours broadly, strips a province down to bedrock
 * and leaves a low irregular country pitted with basins whose shapes follow the rock rather than
 * any flow line. That is the Canadian Shield, Finland and the Laurentian lake country, and it is
 * not a set of valleys.
 *
 * The first version of this stage ran the valley machinery everywhere the ice was, which on flat
 * ground meant running it along the D8 flow grid. Flow paths on a plain are straight and parallel
 * and cross at 45 degrees, so the troughs came out as straight parallel grooves and the basins
 * between their recessional moraines came out as straight lines of water: a wire mesh of lakes at
 * 0, 45 and 90 degrees over the whole cold lowland. The grid was showing through, because on a
 * plain the flow network *is* the grid and nothing else was deciding where the ice cut.
 *
 * So the regimes are split on the one physical quantity that separates them, the relief of the
 * ground: the elevation range within [GlaciationConfig.reliefWindow] valley-widths, against
 * [GlaciationConfig.valleyReliefMetres] of local relief. Above that line the ice is channelled and
 * everything below still applies. Below it the ice is a sheet, and the sheet regime owes nothing
 * to the flow network: a smooth hummocky lowering, and basins thresholded out of a seeded
 * low-frequency noise field pulled toward the hollows the ground already has. The result is blobs
 * of varied size and orientation, which is what glaciated shield country looks like, and there is
 * no direction in it for the grid to line up with.
 *
 * ### Basins are regions, not lines
 *
 * The split above was reviewed at 1024 and shipped, and at 2048 the author found the comb again one
 * level down: clusters of five to fifteen parallel bars of water at 0 and 45 degrees on the
 * piedmont at the foot of a range, and a fan of them radiating from a confluence. The relief test
 * calls a piedmont channelled, because a mountain stands inside its window; the flow paths across a
 * piedmont are straight and parallel; and the over-deepened basins were cut *per cell along the
 * flow path*, so each of those paths got a hollow exactly as wide as itself.
 *
 * That is not a threshold that was set too low, it is the wrong shape of thing. A hollow drawn
 * along a line inherits the line's shape, so every guard that passes a few more paths brings the
 * comb back. So a basin is a **region** rather than a line, paid for out of a fixed allowance of
 * standing water shared with the sheet. The trough itself is still cut cell by cell — a valley
 * *is* a line — but nothing that holds water is. See [cutBasins].
 *
 * ### A basin takes its shape from the ground
 *
 * The first version of that region was still a grid shape, and at 2048 the author found it: the
 * ground within a trough half-width of the path, opened by three-by-three blocks and peeled to its
 * area cap ring by four-connected ring. A tube around a D8 path is a ruled bar, because a D8 path
 * runs dead straight for tens of cells; a union of three-by-three blocks has edges at 0 and 90
 * degrees; four-connected rings are Manhattan diamonds and their contours meet at 45. What that
 * drew was a level slab with a straight edge and, where a tributary's bar crossed the trunk's, a
 * cross. See docs/DESIGN_LEDGER.md, I2.
 *
 * A basin's shape comes from the valley it sits in, so the ground is asked at every step: the
 * footprint is the *valley floor*, bounded by the height the valley walls stand at as well as by
 * the trough's half-width; the area cap keeps the *lowest* cells rather than the innermost ones,
 * so the outline is a contour; and the floor is a bowl by Euclidean distance from that rim rather
 * than a plate at one level. [cutBasins], [keepLowestCells] and [cutBowl] in turn.
 *
 * ### What it does not touch
 *
 * [SeaLevelResult.isLand] and [SeaLevelResult.shorelineHeight], neither of them, ever. The
 * coastline is
 * a percentile cut through the whole field and moving one cell of it moves every other — the reason
 * [SeaLevelStage]'s shelf remap is careful to touch only water is the same reason this is careful
 * to touch only land. A fjord in the strict sense is a trough that the sea has *drowned*, and
 * drowning it would mean re-cutting that percentile; what is modelled instead is the trough graded
 * down to the waterline and, outside the mouth, the over-deepened basin on the sea floor with the
 * shelf standing beyond it as a sill. That runs after the shelf remap — this stage is handed the
 * remapped result — so nothing re-flattens it.
 */
object GlaciationStage {

    /**
     * Every length, depth and area the ice carves with, converted out of [WorldScale] and the grid
     * once, where the stage reads them.
     *
     * The section holds kilometres, metres and square kilometres; the carving works in cells and in
     * shares of the land's relief. This is the whole of the translation between the two, in one
     * place, so a reader can see every unit the stage spends and a guard can read each of them
     * back through the scale it came from.
     */
    internal class Carving(config: WorldGenConfig) {

        private val scale = config.scale
        private val glaciation = config.glaciation
        private val squareKilometresPerCell = config.squareKilometresPerCell

        /** Half-width of the widest trough, in cells. */
        val valleyWidthCells: Float = config.cellsFor(glaciation.valleyWidthKm)

        /** The shortest channelled path that may become a trough, in whole cells. */
        val minTroughLengthCells: Int = config.wholeCellsFor(glaciation.minTroughLengthKm, atLeast = 2)

        /** How far a glacier runs past the freezing line, in whole cells. */
        val runOutCells: Int = config.wholeCellsFor(glaciation.runOutKm)

        /** The furthest one reach may run before the next basin, in cells. */
        val basinSpacingCells: Float = config.cellsFor(glaciation.basinSpacingKm)

        /** Radius of the bowl bitten out of a glacier's head, in cells. */
        val cirqueRadiusCells: Float = config.cellsFor(glaciation.cirqueRadiusKm)

        /** How far out to sea a fjord basin reaches, in whole cells. */
        val fjordReachCells: Int = config.wholeCellsFor(glaciation.fjordReachKm)

        /** Local relief a valley glacier needs, as a share of the land's relief. */
        val valleyRelief: Float = scale.reliefShareOfMetres(glaciation.valleyReliefMetres)

        /** The sheet's own lowering and basin depth, in the same shares. */
        val sheetLowering: Float = scale.reliefShareOfMetres(glaciation.sheetLoweringMetres)
        val sheetBasinDepth: Float = scale.reliefShareOfMetres(glaciation.sheetBasinDepthMetres)

        /** How far above its bed the ice surface in a trough stands, in the same shares. */
        val valleyIceThickness: Float =
            scale.reliefShareOfMetres(glaciation.valleyIceThicknessMetres).coerceAtLeast(1e-6f)

        /** A valley glacier's cuts and spoil, in the same shares. */
        val deepening: Float = scale.reliefShareOfMetres(glaciation.deepeningMetres)
        val overDeepening: Float = scale.reliefShareOfMetres(glaciation.overDeepeningMetres)
        val basinDrop: Float = scale.reliefShareOfMetres(glaciation.basinDropMetres)
        val cirqueDepth: Float = scale.reliefShareOfMetres(glaciation.cirqueDepthMetres)
        val moraineHeight: Float = scale.reliefShareOfMetres(glaciation.moraineHeightMetres)
        val riegelHeight: Float = scale.reliefShareOfMetres(glaciation.riegelHeightMetres)

        /** A fjord basin is cut into the sea floor, so it is read off the sea's half of the ruler. */
        val fjordDepth: Float = scale.depthShareOfMetres(glaciation.fjordDepthMetres)

        /**
         * The smallest and largest basin the ice may cut, as counts of cells on this grid.
         *
         * Four and 41 at 512, 67 and 671 at 2048 - the same two lakes on the ground either way,
         * which is the point of holding them as areas.
         */
        val minBasinCells: Int =
            (glaciation.minLakeAreaKm2 / squareKilometresPerCell).toInt().coerceAtLeast(4)
        val maxBasinCells: Int =
            (glaciation.maxLakeAreaKm2 / squareKilometresPerCell).toInt().coerceAtLeast(minBasinCells)
    }

    suspend fun apply(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        /**
         * The provisional snow balance, in millimetres of water equivalent a year, or null to fall
         * back to the plain temperature mask. See [ClimateStage.provisionalSnowBalance].
         */
        snowBalance: FloatField? = null,
        /** Somewhere other than the CPU for the sheet's profile and surface flow; see rule 8. */
        accelerator: IceSheetAccelerator? = null
    ): SeaLevelResult = apply(config, sea, snowBalance, accelerator, onBudget = null)

    /**
     * @param onBudget handed this stage's mass tally on the way out. An observer, like erosion's:
     *   passing it changes nothing about the world.
     */
    internal suspend fun apply(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        snowBalance: FloatField?,
        accelerator: IceSheetAccelerator?,
        onBudget: ((GlacialMass) -> Unit)?
    ): SeaLevelResult {
        val carving = Carving(config)
        /*
         * Between the passes below, so a reader who presses Stop while the ice is being cut is
         * answered within one walk of the grid rather than at the end of the stage. Each pass is a
         * whole-map walk — at 2048 that is four million cells — and there is nothing finer inside
         * one worth interrupting.
         */
        suspend fun stopIfAsked() = currentCoroutineContext().ensureActive()

        val glaciation = config.glaciation
        // The same object back, so every `===` guard downstream sees an untouched sea stage and
        // the whole world is reproduced bit for bit. This is the control the guard needs.
        if (!glaciation.enabled || sea.landCellCount == 0) return sea

        val cellsAcross = config.width
        val cellsDown = config.height
        val cellCount = cellsAcross * cellsDown
        val isLand = sea.isLand
        val relative = sea.relativeElevation.data

        // Where the ice is. Two rules, and which one applies is `ClimateConfig.snowBalance`:
        //
        //  - the balance, when the engine has run a provisional climate and handed one over. A
        //    cell is frozen where a year's snow outlasts a year's melt, so a cold dry interior is
        //    bare ground with no glacier to carve it and a wet maritime highland carries ice a
        //    long way down its flanks.
        //  - otherwise the older rule: a provisional annual mean at or below
        //    [GlaciationConfig.freezingC], which called every cold place frozen whether or not
        //    any snow ever reached it. See docs/DESIGN_LEDGER.md, H2.
        var frozenCount = 0
        val frozen = BooleanArray(cellCount)
        if (snowBalance != null) {
            for (cell in 0 until cellCount) {
                if (isLand[cell] && snowBalance.data[cell] > 0f) {
                    frozen[cell] = true
                    frozenCount++
                }
            }
        } else {
            val temperature = ClimateStage.buildTemperature(config, sea)
            for (cell in 0 until cellCount) {
                if (isLand[cell] && temperature.data[cell] <= glaciation.freezingC) {
                    frozen[cell] = true
                    frozenCount++
                }
            }
        }
        if (frozenCount == 0) return sea
        stopIfAsked()

        // The ice follows the water's own network. A glacier occupies the valley a river cut before
        // the cold came, which is both what really happens and what makes the result legible: the
        // trough is where the map already had a valley.
        val filled =
            FlowRouting.fillDepressions(cellsAcross, cellsDown, isLand, sea.relativeElevation)
        val directions = FlowRouting.flowDirections(
            cellsAcross,
            cellsDown,
            isLand,
            sea.relativeElevation,
            filled,
            config.seed,
            config.facetRouting
        )
        val order =
            FlowRouting.drainageOrder(cellsAcross, cellsDown, isLand, directions, sea.landCellCount)

        // How much frozen ground drains through each cell — the ice's own catchment, as distinct
        // from the water's. Accumulated along [FlowRouting.drainageOrder] rather than with
        // [FlowRouting.accumulate], for the reason that order exists: the height-sorted walk can
        // hand a cell its load after it has already been passed, and a lost contribution here is a
        // glacier that stops for no reason.
        val ice = FloatArray(cellCount)
        for (cell in 0 until cellCount) if (frozen[cell]) ice[cell] = 1f
        for (rank in order.indices) {
            val cell = order[rank]
            val receiver = directions[cell]
            if (receiver >= 0 && isLand[receiver]) ice[receiver] += ice[cell]
        }

        // The elevation range within a couple of trough-widths, which is the question "is there a
        // valley here?" asked of every cell at once. Water counts at the waterline rather than at
        // its own depth, so a coast standing over deep ocean does not read as relief it does not
        // have, while a headland standing over the sea does.
        stopIfAsked()
        val reliefRadius = (glaciation.reliefWindow * carving.valleyWidthCells).toInt().coerceIn(2, 64)
        val relief =
            localRelief(cellsAcross, cellsDown, relative, reliefRadius, glaciation.reliefWindowOctagon)
        // Straight, with nothing between the share and the field. `relative` is a cell's altitude
        // over `WorldScale.highestLandMetres` since S2, and `valleyRelief` is a depth in metres
        // over the same figure, so the two are already in one another's units. Before S2 the field
        // was renormalised to whatever range the world's own land occupied and this had to be
        // multiplied by that range, which was near 1 by construction and is why nobody noticed it
        // was a measurement.
        val channelThreshold = carving.valleyRelief
        var channelledCells = 0
        val channelled = BooleanArray(cellCount)
        for (cell in 0 until cellCount) {
            if (isLand[cell] && relief[cell] >= channelThreshold) {
                channelled[cell] = true
                if (frozen[cell]) channelledCells++
            }
        }

        // The denominator is the frozen ground, not the land: see [GlaciationConfig.minCatchment].
        val frozenLand = frozenCount.toFloat()
        val glacier = BooleanArray(cellCount)
        val strength = FloatArray(cellCount)
        // Cells travelled since the ice left frozen ground. A snout sits below its own snowline —
        // that is what an ablation zone is — so the trough is allowed this far past the mask and
        // not one cell further.
        val runOut = IntArray(cellCount) { Int.MAX_VALUE }

        // Which ice field each cell's ice came out of, and how big that field is. A glacier has to
        // be one of the few paths draining *its own* ice field, not merely a large number against
        // the planet's total: see [GlaciationConfig.trunkCatchment].
        val field = frozenFields(cellsAcross, cellsDown, frozen)
        val fieldOf = IntArray(cellCount) { field.id[it] }
        for (rank in order.indices) {
            val cell = order[rank]
            val receiver = directions[cell]
            if (fieldOf[cell] >= 0 && receiver >= 0 && isLand[receiver] &&
                fieldOf[receiver] < 0
            ) {
                fieldOf[receiver] = fieldOf[cell]
            }
        }

        // Everything that could carry a trough: enough ice, close enough to the frozen ground, and
        // standing in channelled country. Whether it actually does is the length test below.
        stopIfAsked()
        val candidate = BooleanArray(cellCount)
        for (rank in order.indices) {
            val cell = order[rank]
            if (frozen[cell]) runOut[cell] = 0
            val share = ice[cell] / frozenLand
            val fieldId = fieldOf[cell]
            val fieldShare = if (fieldId >= 0) ice[cell] / field.size[fieldId].toFloat() else 0f
            if (share >= glaciation.minCatchment && fieldShare >= glaciation.trunkCatchment &&
                runOut[cell] <= carving.runOutCells && channelled[cell]
            ) {
                candidate[cell] = true
            }
            // Propagated for every cell that the ice has reached rather than only for the ones
            // that qualified, so a trough interrupted by one flat or thin-iced cell can still find
            // its snout on the other side.
            val receiver = directions[cell]
            if (runOut[cell] != Int.MAX_VALUE && receiver >= 0 && isLand[receiver]) {
                val nextRunOut = if (frozen[receiver]) 0 else runOut[cell] + 1
                if (nextRunOut < runOut[receiver]) runOut[receiver] = nextRunOut
            }
        }

        // How long the channelled path through each candidate is, head to snout. A trough is a long
        // landform; twenty cells of upstream ice in a hollow on a plain is not one, and before this
        // test existed every such hollow was carved a full U-valley and dammed at both ends.
        //
        // Their *shape* is carried along with them, because a length alone cannot tell a valley
        // from a ruled line: the head the longest upstream chain starts at, the snout it ends at,
        // and the ground distance walked between them. A path that runs dead straight at one of the
        // eight D8 bearings has walked exactly the straight-line distance, and that is the comb of
        // parallel gullies down a range front — see [GlaciationConfig.minSinuosity].
        stopIfAsked()
        val upstream = IntArray(cellCount)
        val upLength = FloatArray(cellCount)
        val head = IntArray(cellCount) { -1 }
        for (rank in order.indices) {
            val cell = order[rank]
            if (!candidate[cell]) continue
            if (upstream[cell] == 0) {
                upstream[cell] = 1
                head[cell] = cell
            }
            val receiver = directions[cell]
            if (receiver >= 0 && candidate[receiver] && upstream[cell] + 1 > upstream[receiver]) {
                upstream[receiver] = upstream[cell] + 1
                val stepCells =
                    if (isDiagonal(cell, receiver, cellsAcross)) DIAGONAL_STEP_CELLS else 1f
                upLength[receiver] = upLength[cell] + stepCells
                head[receiver] = head[cell]
            }
        }
        val downstream = IntArray(cellCount)
        val downLength = FloatArray(cellCount)
        val snout = IntArray(cellCount) { -1 }
        for (rank in order.indices.reversed()) {
            val cell = order[rank]
            if (!candidate[cell]) continue
            downstream[cell] = 1
            downLength[cell] = 0f
            snout[cell] = cell
            val receiver = directions[cell]
            if (receiver >= 0 && candidate[receiver] && downstream[receiver] + 1 > downstream[cell]) {
                downstream[cell] = downstream[receiver] + 1
                val stepCells =
                    if (isDiagonal(cell, receiver, cellsAcross)) DIAGONAL_STEP_CELLS else 1f
                downLength[cell] = downLength[receiver] + stepCells
                snout[cell] = snout[receiver]
            }
        }

        var glacierCells = 0
        for (cell in 0 until cellCount) {
            if (!candidate[cell]) continue
            if (upstream[cell] + downstream[cell] - 1 < carving.minTroughLengthCells) continue
            val wander = sinuosity(
                head[cell], snout[cell], upLength[cell] + downLength[cell], cellsAcross
            )
            if (wander < glaciation.minSinuosity) {
                continue
            }
            glacier[cell] = true
            glacierCells++
            // Ice thickness, as a proxy: a glacier draining twenty times the ground is not twenty
            // times as deep, so the root rather than the share itself.
            //
            // Floored, and the floor is load-bearing. Everything this stage cuts is scaled by this
            // number, the over-deepening included, and an over-deepening scaled to a fifth is a
            // basin shallower than [LakesConfig.minDepth] — which is to say a basin that the river
            // stage will not see as a lake, on a glacier that was carved anyway.
            strength[cell] = sqrt((ice[cell] / frozenLand) / glaciation.fullCatchment).coerceIn(MIN_THICKNESS, 1f)
        }

        // No two glaciers of the same bearing within a trough of each other. Ice that close together
        // is one glacier, and a rank of them is the comb.
        stopIfAsked()
        val suppressed = suppressParallel(
            glaciation,
            carving, cellsAcross, cellsDown, glacier, directions, ice, strength, order
        )
        glacierCells -= suppressed.cells

        // The other regime's ground, settled here rather than after the carving because the water
        // budget below is measured against it. Everything frozen that the valley machinery was not
        // allowed to touch is under sheet ice, and sheet ice does not follow the drainage net — so
        // nothing the scour reads is `directions`, `order` or `ice`. That is the whole point: there
        // is no flow grid in it to show through.
        var sheetCells = 0
        val sheet = BooleanArray(cellCount)
        for (cell in 0 until cellCount) {
            if (frozen[cell] && !channelled[cell] && !glacier[cell]) {
                sheet[cell] = true
                sheetCells++
            }
        }

        // The sheet as a body rather than a mask: how far each cell of the ice stands from the
        // margin, how thick the plastic profile makes it there, and which way the *surface* of
        // that profile falls. See [IceSheet] for the equation and its one constant.
        //
        // The distance is measured over the whole frozen body, because a sheet's margin is where
        // the ice ends and not where this stage's own regime split falls; the thickness is kept
        // only over the sheet regime, because ice confined between rock walls is a valley glacier
        // with its own thickness ([GlaciationConfig.valleyIceThicknessMetres]) and is not a
        // kilometre-thick plateau standing over the landscape.
        stopIfAsked()
        val metresPerRootKm =
            IceSheet.metresPerRootKilometre(config.isostasy.iceDensity, config.isostasy.gravity)
        val margin = IceSheet.marginDistanceKm(config, frozen)
        val marginDistanceKm = margin.distanceKm

        // And which of that ice is a *sheet*. The profile is a sheet's and only a sheet's, so a
        // frozen body smaller than [IceSheet.SMALLEST_SHEET_SQUARE_KM] is left as the frozen
        // ground it was: no thickness, no surface, no load and no outlet. Measured on the whole
        // frozen body rather than on the sheet regime's share of it, because what glaciology's
        // definition is about is the mass of ice, not which part of it this stage is carving by
        // which rule. See that constant for the render this clause was written from.
        val smallestSheetCells = IceSheet.SMALLEST_SHEET_SQUARE_KM / config.squareKilometresPerCell
        val sheetBody = BooleanArray(cellCount)
        for (cell in 0 until cellCount) {
            if (!sheet[cell]) continue
            val body = field.id[cell]
            if (body >= 0 && field.size[body] >= smallestSheetCells) sheetBody[cell] = true
        }

        val accelerated = accelerator?.sheet(
            cellsAcross, cellsDown, marginDistanceKm, margin.nearestCell, relative, sheetBody,
            metresPerRootKm, config.scale.highestLandMetres,
            config.cellHeightInCellWidths.toFloat(), config.cellWidthKm.toFloat()
        )
        val iceThicknessMetres = accelerated?.thicknessMetres
            ?: IceSheet.profile(
                margin, relative, sheetBody, metresPerRootKm, config.scale.highestLandMetres,
                config.cellWidthKm.toFloat()
            )
        val surfaceFlow = accelerated?.flowReceiver
            ?: IceSheet.flowReceivers(
                cellsAcross, cellsDown, relative, iceThicknessMetres, sheetBody,
                config.scale.highestLandMetres, config.cellHeightInCellWidths.toFloat()
            )

        // How much standing water this world's ice is allowed, in cells, and how small and how
        // large one body of it may be. All three are map fractions, so the same world at 512, 1024
        // and 2048 is offered the same lake country rather than four times as much of it each time
        // the grid doubles — see [GlaciationConfig.sheetLakeShare] and its two neighbours.
        //
        // One budget for both regimes. The valley basins are taken out of it first and the sheet
        // gets the remainder, which is what stops the two of them each spending a full allowance on
        // the same world. The denominator is the frozen flat ground, with a floor at a quarter of
        // all frozen ground so that an ice field which is nothing but mountains still has an
        // allowance to spend on its valley floors.
        val minBasinCells = carving.minBasinCells
        val maxBasinCells = carving.maxBasinCells
        val lakeBudget =
            (glaciation.sheetLakeShare * maxOf(sheetCells, frozenCount / 4).toFloat()).toInt()

        // How far down the staircase each cell is.
        //
        // Two things advance it, and they simply add: how far the ice has run (in cells, over
        // [GlaciationConfig.basinSpacingKm]) and how far it has fallen (in elevation, over
        // [GlaciationConfig.basinDropMetres]). A reach ends when the sum passes the next whole number, so
        // whichever runs out first ends it — a long flat reach on a plain, a short one on a
        // mountainside. Measured from the head of the longest feeder rather than the nearest, so a
        // tributary joining halfway down does not restart the count.
        stopIfAsked()
        val spacing = carving.basinSpacingCells.coerceAtLeast(2f)
        val drop = carving.basinDrop.coerceAtLeast(1e-4f)
        val progress = FloatArray(cellCount)
        for (rank in order.indices) {
            val cell = order[rank]
            if (!glacier[cell]) continue
            val receiver = directions[cell]
            if (receiver >= 0 && glacier[receiver]) {
                val step = progress[cell] +
                    (if (isDiagonal(cell, receiver, cellsAcross)) DIAGONAL_STEP_CELLS else 1f) / spacing +
                    (relative[cell] - relative[receiver]).coerceAtLeast(0f) / drop
                if (step > progress[receiver]) progress[receiver] = step
            }
        }

        // The long profile, in reaches: each one an over-deepened basin followed by a step. Real
        // troughs are stepped like this — the ice scours hardest where it is confined and thickest
        // and rides over the harder bars between — and it is the step at the lower end of a reach
        // that makes the basin a lake rather than merely a dip.
        val reach = IntArray(cellCount)
        for (cell in 0 until cellCount) if (glacier[cell]) reach[cell] = progress[cell].toInt()

        // Carving proper. Every stamp is computed from the *original* surface and combined with a
        // minimum, so overlapping glaciers compose in any order and the result does not depend on
        // which cell was visited first.
        stopIfAsked()
        val carved = relative.copyOf()

        // The trough, and *only* the trough: a graded U following the ground down, cut by the
        // ordinary amount. The over-deepened basins used to be cut here too, cell by cell along the
        // flow path, each reach's floor flattened to the lowest ground in it — and that is what
        // built the comb. A basin cut per cell along a D8 path is exactly as wide as the path is,
        // which on a rank of parallel gullies down a piedmont is a rank of parallel straight bars
        // of water. Basins are now regions, cut below, and they are the only thing that holds
        // water.
        for (cell in 0 until cellCount) {
            if (!glacier[cell]) continue
            // Wall to wall: the ice lowers the whole cross-section toward its bed on a parabola,
            // untouched at the rim and flat at the floor. That parabola is the U.
            //
            // *Across* the flow and one cell thick along it, which is not a detail. Stamped as a
            // disc instead — the obvious thing, and what this did first — the floor reaches a
            // valley-width in every direction, including forward down the long profile, and
            // quietly planes off whatever it was supposed to stand above. A cross-section is a
            // cross-section.
            val bed = (relative[cell] - carving.deepening * strength[cell]).coerceAtLeast(0f)
            swath(
                cellsAcross, cellsDown, cell, flowOf(cell, directions, glacier, cellsAcross, cellsDown),
                valleyHalfWidth(carving, strength[cell]), glaciation.floorShare, bed,
                carving.valleyIceThickness * strength[cell], isLand, relative, carved
            )
        }

        // Cirques: the armchair hollow a glacier bites out of the mountain it starts on. Every head
        // of the ice network gets one, which is what puts tarns at the tops of the valleys.
        val fedByIce = BooleanArray(cellCount)
        for (cell in 0 until cellCount) {
            if (!glacier[cell]) continue
            val receiver = directions[cell]
            if (receiver >= 0 && glacier[receiver]) fedByIce[receiver] = true
        }
        var cirques = 0
        for (cell in 0 until cellCount) {
            if (!glacier[cell] || fedByIce[cell]) continue
            cirques++
            val depth = carving.cirqueDepth * maxOf(strength[cell], 0.5f)
            bowl(
                cellsAcross, cellsDown, cell, carving.cirqueRadiusCells.coerceAtLeast(1f), glaciation.floorShare,
                (relative[cell] - depth).coerceAtLeast(0f),
                carving.valleyIceThickness * maxOf(strength[cell], 0.5f), isLand, relative, carved
            )
        }

        // The over-deepened basins, as regions rather than as cells along a line. See [cutBasins].
        stopIfAsked()
        // Which basin each cut cell belongs to. Written by both regimes, read by nothing in the
        // pipeline: it leaves through [GlacialMass] so the shape guards can measure a basin.
        val basinFloor = IntArray(cellCount) { -1 }
        val basins = cutBasins(
            glaciation,
            carving, cellsAcross, cellsDown, isLand, frozen, glacier, directions, order, reach, progress,
            strength, ice, carved, minBasinCells, maxBasinCells, lakeBudget, basinFloor
        )

        // The sheet's basins get whatever the valleys left of the allowance.
        var scourCells = 0
        var scourBasins = 0
        val sheetBudget = (lakeBudget - basins.cells).coerceAtLeast(0)
        if (glaciation.sheetScour && sheetCells >= minBasinCells) {
            val tally = scour(
                config, glaciation, carving, cellsAcross, cellsDown, sheet, sheetCells, surfaceFlow,
                isLand, relative, carved,
                minBasinCells, maxBasinCells, sheetBudget, basinFloor, basins.basins
            )
            scourCells = tally.cells
            scourBasins = tally.basins
        }

        // The outlets, and with them the fjords. After both sets of basins, because an outlet
        // trough is a valley cross-section and has to be able to cut through ground the scour has
        // already lowered rather than be undone by it.
        stopIfAsked()
        val outlets = cutOutletTroughs(
            glaciation, carving, config.scale, cellsAcross, cellsDown, sheet, sheetCells,
            surfaceFlow, iceThicknessMetres, directions, isLand, relative, carved
        )

        if (glacierCells == 0 && scourCells == 0 && outlets.cells == 0) return sea

        var excavated = 0.0
        for (cell in 0 until cellCount) {
            if (isLand[cell]) excavated += (relative[cell] - carved[cell]).toDouble()
        }

        // Terminal moraines. The one thing ice gives back: everything it was dragging is dumped
        // where it stops, in a ridge across the valley mouth, and the ridge dams the trough behind
        // it. Taken as a maximum rather than a sum where two snouts overlap, so the result cannot
        // depend on the order they were laid in.
        val moraine = FloatArray(cellCount)
        var moraines = 0
        var riegels = 0
        for (cell in 0 until cellCount) {
            if (!glacier[cell]) continue
            val receiver = directions[cell]
            val ends = receiver < 0 || !glacier[receiver]
            if (ends) {
                // A snout in the sea leaves no ridge: the till goes straight into the water. Only
                // a glacier that melts on land builds a dam.
                if (receiver >= 0 && !isLand[receiver]) continue
                moraines++
                bar(
                    cellsAcross, cellsDown, cell, flowOf(cell, directions, glacier, cellsAcross, cellsDown),
                    valleyHalfWidth(carving, strength[cell]) * 1.15f,
                    till(carving.moraineHeight, strength[cell]), isLand, moraine
                )
            } else if (reach[receiver] != reach[cell] && carving.riegelHeight > 0f) {
                // A recessional moraine, at the lower end of every reach. Off by default now that a
                // basin is a region closed by its own rim: see [GlaciationConfig.riegelHeight] for
                // why a bar of till one cell thick across the flow could only add straight water.
                riegels++
                bar(
                    cellsAcross, cellsDown, cell, flowOf(cell, directions, glacier, cellsAcross, cellsDown),
                    valleyHalfWidth(carving, strength[cell]),
                    till(carving.riegelHeight, strength[cell]), isLand, moraine
                )
            }
        }
        var deposited = 0.0
        for (cell in 0 until cellCount) {
            if (moraine[cell] <= 0f) continue
            // What the field actually took, never what it was asked to take: the terrain is float
            // and a small enough increment rounds away, exactly as the hydraulic pass's budget has
            // to allow for.
            val before = carved[cell]
            carved[cell] = (before.toDouble() + moraine[cell].toDouble()).toFloat()
            deposited += carved[cell].toDouble() - before.toDouble()
        }

        // The drowned half of a fjord: the basin the ice scoured below the waterline, with the
        // shelf left standing beyond it as the sill. Water only, and after the shelf remap, so
        // there is nothing left to re-flatten it.
        var submarine = 0.0
        if (glaciation.fjords && carving.fjordReachCells > 0) {
            val stamp = IntArray(cellCount)
            val queue = IntArray((2 * carving.fjordReachCells + 1) * (2 * carving.fjordReachCells + 1))
            val queueDistance = IntArray(queue.size)
            var mouthId = 0
            for (cell in 0 until cellCount) {
                if (!glacier[cell]) continue
                val receiver = directions[cell]
                if (receiver < 0 || isLand[receiver]) continue
                submarine += fjord(
                    cellsAcross, cellsDown, receiver, carving.fjordReachCells, carving.fjordDepth * strength[cell],
                    isLand, carved, stamp, ++mouthId, queue, queueDistance
                )
            }
        }

        // And the weight of it. Ice standing on the crust holds the crust down, which is why
        // Greenland's bed lies below sea level under three kilometres of ice and why Scandinavia,
        // which lost its own sheet ten thousand years ago, is still coming back up at a centimetre
        // a year. The load is handed to the same flexure the hydraulic rounds use; what the map
        // shows of the rebound is the ground the *former* ice has already let go, since only the
        // ice that is still here is weighed. See `IsostasyConfig.iceLoad` and docs/DESIGN_LEDGER.md, S2.
        val iceDepression = iceLoadDepression(config, frozen, isLand, carved, iceThicknessMetres)

        // And the ice itself, last of all: the surface the rest of the pipeline reads is the top
        // of the sheet, not the rock under it.
        //
        // This is the whole of I1's climate claim and it needed no change to the climate. The
        // elevation field is what `ClimateStage` reads an altitude off, what the renderer shades
        // and what the Elevation view draws; where a sheet stands, the ground the air touches is
        // its surface. Put the thickness on and the existing lapse rate makes the dome colder than
        // its bed by [IceSheet.surfaceCoolingC] without being told that ice exists — which is the
        // test that this is the right place for it. Greenland's summit is cold because it is three
        // kilometres up.
        //
        // It goes on *after* the load, and the load is now applied in full rather than the share
        // of it the surface used to be given. Before the thickness existed the bend had to be
        // discounted by the ice's own profile, because a bed pressed down half a kilometre and
        // then filled with the ice that pressed it has a surface that has not moved; with the ice
        // actually here that discount would count the same ice twice. The two together are Airy's
        // arithmetic done once: the bed sinks by 28% of the thickness and the surface stands 72%
        // of it above where the bare ground was.
        var iceSurfaceCells = 0
        val metresPerFieldUnit = config.scale.highestLandMetres
        for (cell in 0 until cellCount) {
            if (!sheet[cell] || iceThicknessMetres[cell] <= 0f) continue
            carved[cell] += iceThicknessMetres[cell] / metresPerFieldUnit
            iceSurfaceCells++
        }

        onBudget?.invoke(
            GlacialMass(
                iceDepressionMetres = iceDepression,
                iceThicknessMetres = iceThicknessMetres,
                marginDistanceKm = marginDistanceKm,
                onTheSheet = sheet,
                sheetFlowReceiver = surfaceFlow,
                iceSurfaceCells = iceSurfaceCells,
                outlets = outlets.outlets,
                outletCells = outlets.cells,
                deepestOutletCutMetres = outlets.deepestMetres,
                frozenCells = frozenCount,
                channelledCells = channelledCells,
                glacierCells = glacierCells,
                trunks = suppressed.trunks,
                parallelCellsDropped = suppressed.cells,
                sheetCells = sheetCells,
                lakeBudget = lakeBudget,
                basinCells = basins.cells,
                basins = basins.basins,
                basinsWithNoFloor = basins.noFloor,
                basinsTooStraight = basins.tooStraight,
                basinsTooSmall = basins.tooSmall,
                basinsOverBudget = basins.overBudget,
                scourCells = scourCells,
                scourBasins = scourBasins,
                basinFloor = basinFloor,
                cirques = cirques,
                moraines = moraines,
                riegels = riegels,
                excavated = excavated,
                deposited = deposited,
                submarine = submarine
            )
        )

        return sea.copy(relativeElevation = FloatField(cellsAcross, cellsDown, carved))
    }

    /**
     * Presses the crust down under the ice standing on it, in place, and reports the deepest bend.
     *
     * The thickness is [IceSheet]'s: the plastic profile, `sqrt` of the distance from the margin,
     * with the basal shear stress Cuffey and Paterson measure. Until I1 it was a flat two
     * thousand metres ramped to nothing over four hundred kilometres of margin, both of them
     * settings — the crudest thing that could be true of a sheet, and deliberately so, because S2
     * had no profile to read. It has one now, so the two settings are gone and the load is the ice
     * that is actually standing there. The consequence
     * the guard reads is Airy's: a sheet deep enough for the plate to have flattened out under it
     * depresses its bed by `iceDensity / mantleDensity` of its own thickness, 917 over 3,300,
     * which is 28%.
     *
     * The bend is spent on the shoreline-relative field this stage is already rewriting, since
     * that is what the rest of the pipeline reads and the erosion stage's own height field is two
     * stages upstream and must not be touched. It goes on water as well as land: a sheet grounded
     * below the waterline depresses the floor under it exactly as one on land does.
     *
     * All of the bend reaches the field, where before it was discounted by the ice's own profile.
     * That discount existed because the ice was not on the map: a bed pressed down and then filled
     * with the ice that pressed it has a surface that has not moved, and with only a mask to work
     * from the only way to say so was to withhold the bend. The caller now adds the thickness
     * itself, so withholding it too would sink the same sheet twice.
     */
    private fun iceLoadDepression(
        config: WorldGenConfig,
        frozen: BooleanArray,
        isLand: BooleanArray,
        carved: FloatArray,
        iceThicknessMetres: FloatArray
    ): Float {
        val isostasy = config.isostasy
        if (!isostasy.enabled || !isostasy.flexure || !isostasy.iceLoad) return 0f
        val cellsAcross = config.width
        val cellsDown = config.height
        val cellCount = cellsAcross * cellsDown
        if (frozen.none { it }) return 0f

        val load = FloatArray(cellCount)
        for (cell in 0 until cellCount) {
            if (!frozen[cell]) continue
            load[cell] = Isostasy.loadPascals(
                iceThicknessMetres[cell], isostasy.iceDensity, isostasy.gravity
            )
        }

        Isostasy.Flexure(config).deflectionMetres(load, load)

        // Referred to the ground the ice is nowhere near, which is where the datum belongs for a
        // load that covers a few per cent of a planet. The filter carries no zero-frequency term,
        // so its answer sums to nothing over the whole map — which means a sheet pressing its own
        // bed down half a kilometre lifts every cell of the far hemisphere by ten or twenty metres
        // to pay for it, and that is an artefact of where the datum was put rather than anything
        // the mantle does. Subtracting the mean over the ice-free ground puts it back: the far
        // field reads nothing, the moat around the sheet reads what it should, and the bed under
        // the sheet reads the difference.
        var awayFromIce = 0.0
        var awayCells = 0
        for (cell in 0 until cellCount) {
            if (frozen[cell]) continue
            awayFromIce += load[cell].toDouble()
            awayCells++
        }
        val farField = if (awayCells == 0) 0f else (awayFromIce / awayCells).toFloat()

        // And faded out over the distance a plate actually carries a load, which is a few flexural
        // parameters. Beyond that the answer this filter gives is not the plate's: a transform on a
        // grid that wraps has no far field to lose the load into, so what should die away over two
        // hundred kilometres instead spreads over the whole map as a metre or two of tilt. The
        // taper puts the boundary where the physics puts it — `Isostasy.Flexure` has the number —
        // and leaves the ground beyond it exactly where the ice found it.
        val distanceToIce = FloatArray(cellCount) { JumpFloodDistance.INFINITE }
        val nearestIce = IntArray(cellCount) { -1 }
        for (cell in 0 until cellCount) {
            if (!frozen[cell]) continue
            distanceToIce[cell] = 0f
            nearestIce[cell] = cell
        }
        JumpFloodDistance.run(cellsAcross, cellsDown, distanceToIce, nearestIce)
        val reachCells = (
            FLEXURAL_PARAMETERS_OF_REACH * Isostasy.Flexure(config).flexuralParameterMetres /
                (config.cellWidthKm * 1_000.0)
            ).toFloat().coerceAtLeast(1f)
        for (cell in 0 until cellCount) {
            val fade = (1f - distanceToIce[cell] / reachCells).coerceIn(0f, 1f)
            load[cell] = (load[cell] - farField) * fade
        }

        // The bend is a change in altitude, and the field this stage works in is piecewise: a land
        // cell is measured against the land's half of the ruler and a water cell against the sea's,
        // so each converts through its own.
        //
        // All of it reaches the field, which is the change I1 makes here. Until the ice had a
        // thickness this field was the only surface there was, so the bend under a cap had to be
        // withheld in proportion to the ice standing on it: a bed pressed down and then filled
        // with the ice that pressed it has a surface that has not moved, and a mask cannot say so
        // any other way. What was measured then still holds — spending the whole bend on the
        // surface with no ice on the map dropped the ice share of land from 8.0% to 6.2% and
        // raised the lake share from 2.6% to 3.6%, because a bed was being read as a surface. The
        // repair is to put the ice on the map rather than to hide the bend, and the caller now
        // does: the bed goes down by all of this and the surface comes back up by the thickness.
        val scale = config.scale
        var deepest = 0f
        for (cell in 0 until cellCount) {
            val bend = load[cell]
            if (bend > deepest) deepest = bend
            val surfaceBend = bend
            // Sub-metre bends are dropped, and not for speed. A flexure is a filter over the whole
            // grid, so its answer is non-zero in every cell of the map however far from the ice it
            // is — and "this cell was touched by the glaciation stage" is a question three guards
            // ask by comparing the field before and after. A bend of a few centimetres a thousand
            // kilometres from the nearest sheet is not a landform and must not read as one: without
            // this floor `SnowBalanceTest` counts every land cell on the map as carved.
            if (surfaceBend > -MIN_MEANINGFUL_BEND_METRES &&
                surfaceBend < MIN_MEANINGFUL_BEND_METRES
            ) continue
            carved[cell] -=
                if (isLand[cell]) scale.reliefShareOfMetres(surfaceBend)
                else scale.depthShareOfMetres(surfaceBend)
        }
        return deepest
    }

    /** What the outlet troughs did, for the tally. */
    private class OutletTally(val outlets: Int, val cells: Int, val deepestMetres: Float)

    /**
     * The troughs the sheet's outlets cut, which are the fjords before the sea reaches them.
     *
     * An ice sheet does not drain evenly round its rim. Its surface is a dome, so its flow
     * converges, and where the converging ice meets a valley in the bed it is funnelled into it:
     * the discharge that was spread over a hundred kilometres of sheet goes through one gap, and
     * what comes out is a fast outlet glacier cutting a trough far deeper than the valley was.
     * Jakobshavn drains 6.5% of Greenland through a gap a few kilometres wide; the Lambert drains
     * a sixth of East Antarctica. That is why a fjord coast is deep, straight-walled and hung with
     * tributary valleys, and why the fjords of Norway, Chile and the Antarctic Peninsula all sit
     * where a former sheet's outlets were rather than evenly round the coast. K4 floods them; this
     * cuts them.
     *
     * So: the sheet's own flow is accumulated down its surface, the cells where it *leaves* the
     * sheet carry that whole discharge, and the ones carrying more than
     * [GlaciationConfig.outletCatchment] of the sheet cut a trough down the bed's own valley from
     * there. The cut is the valley regime's cross-section, unchanged — a graded U, bounded by the
     * burial rule so rock standing above the ice surface is untouched — with the *sheet's* ice
     * thickness in place of the valley glacier's, which is the whole difference between a valley
     * and an outlet and is [IceSheet]'s figure rather than a constant of this stage's.
     *
     * The depth is [OUTLET_CUT_OF_ICE_THICKNESS] of that thickness. Sognefjord is 1,308 m deep and
     * the Fennoscandian sheet stood 2-3 km over its head at the last maximum (Patton and others,
     * *Deglaciation of the Eurasian ice sheet complex*, Quat. Sci. Rev. 169, 2017), which is a
     * half; Skelton Inlet's 1,933 m under East Antarctic ice of about 3,500 m is the same figure
     * again. It is a ratio taken off Earth's deepest fjords and the ice that cut them, not a knob.
     */
    private fun cutOutletTroughs(
        glaciation: GlaciationConfig,
        carving: Carving,
        scale: com.cartogenesis.worldgen.model.WorldScale,
        cellsAcross: Int,
        cellsDown: Int,
        sheet: BooleanArray,
        sheetCells: Int,
        surfaceFlow: IntArray,
        iceThicknessMetres: FloatArray,
        directions: IntArray,
        isLand: BooleanArray,
        relative: FloatArray,
        carved: FloatArray
    ): OutletTally {
        if (!glaciation.outletTroughs || sheetCells <= 0) return OutletTally(0, 0, 0f)
        val cellCount = cellsAcross * cellsDown

        // The discharge, in cells of sheet drained. Accumulated down the *surface*, highest first,
        // so every cell has its own load before it hands it on — the same reason the valley
        // regime's ice is accumulated along `FlowRouting.drainageOrder` and not in index order.
        val discharge = FloatArray(cellCount)
        // And the thickest ice anywhere up its own flow line, which is the ice the outlet is
        // *delivering*. The thickness at the outlet itself is the wrong figure and was measured
        // being wrong: an outlet sits at the margin, where the profile is thin by construction, so
        // reading it there says a trough is cut by the last few hundred metres of ice rather than
        // by the sheet behind it. What is funnelled through the gap came from the interior.
        val feeding = FloatArray(cellCount)
        var sheetRank = 0
        val bySurface = LongArray(sheetCells)
        for (cell in 0 until cellCount) {
            if (!sheet[cell]) continue
            discharge[cell] = 1f
            feeding[cell] = iceThicknessMetres[cell]
            bySurface[sheetRank++] = FlowRouting.encode(
                relative[cell] + iceThicknessMetres[cell] / scale.highestLandMetres, cell
            )
        }
        bySurface.sort()
        for (rank in bySurface.indices.reversed()) {
            val cell = FlowRouting.decodeIndex(bySurface[rank])
            val receiver = surfaceFlow[cell]
            if (receiver < 0 || !sheet[receiver]) continue
            discharge[receiver] += discharge[cell]
            if (feeding[cell] > feeding[receiver]) feeding[receiver] = feeding[cell]
        }

        val minDischarge = glaciation.outletCatchment * sheetCells
        var outlets = 0
        var cut = 0
        var deepestMetres = 0f
        for (cell in 0 until cellCount) {
            if (!sheet[cell] || discharge[cell] < minDischarge) continue
            val leaves = surfaceFlow[cell]
            // An outlet is where the ice leaves the sheet. A cell whose surface still falls onto
            // more sheet is in the middle of the flow, not at the end of it.
            if (leaves >= 0 && sheet[leaves]) continue
            outlets++
            val deliveredMetres = feeding[cell]
            val cutMetres = OUTLET_CUT_OF_ICE_THICKNESS * deliveredMetres
            if (cutMetres > deepestMetres) deepestMetres = cutMetres
            val depth = scale.reliefShareOfMetres(cutMetres)
            val burial = scale.reliefShareOfMetres(deliveredMetres).coerceAtLeast(1e-6f)
            // Down the bed's own valley from there, as far as the ice runs past its margin. The
            // bed's network is the right one here and not the surface's: past the margin there is
            // no sheet left to have a surface, and what the outlet glacier follows is the valley.
            var walked = leaves
            var steps = 0
            while (walked >= 0 && isLand[walked] && steps <= carving.runOutCells) {
                val bed = (relative[walked] - depth).coerceAtLeast(0f)
                swath(
                    cellsAcross, cellsDown, walked,
                    flowOf(walked, directions, sheet, cellsAcross, cellsDown),
                    valleyHalfWidth(carving, OUTLET_WIDTH_STRENGTH), glaciation.floorShare, bed,
                    burial, isLand, relative, carved
                )
                cut++
                walked = directions[walked]
                steps++
            }
        }
        return OutletTally(outlets, cut, deepestMetres)
    }

    /**
     * How deep an outlet trough is cut, as a share of the ice thickness that cut it.
     *
     * Sognefjord's 1,308 m under 2-3 km of Fennoscandian ice, and Skelton Inlet's 1,933 m under
     * about 3,500 m of East Antarctic ice: both a half. See [cutOutletTroughs].
     */
    private const val OUTLET_CUT_OF_ICE_THICKNESS = 0.5f

    /**
     * How wide an outlet trough is, on the valley regime's own half-width scale.
     *
     * A quarter, which through [valleyHalfWidth]'s square root is half the widest trough this
     * stage cuts. An outlet glacier is a confined, fast stream of ice and its trough is narrow for
     * its depth — Sognefjord is 205 km long and 4.5 km wide — where a valley glacier's fills the
     * valley it found. The same function sets both so the two cannot drift apart.
     */
    private const val OUTLET_WIDTH_STRENGTH = 0.25f

    /** What the scour did, for the tally. */
    private class ScourTally(val cells: Int, val basins: Int)

    /** What the valley regime's basins came to, for the tally. */
    private class BasinTally(
        val cells: Int,
        val basins: Int,
        val noFloor: Int,
        val tooStraight: Int,
        val tooSmall: Int,
        val overBudget: Int
    )

    /**
     * The over-deepened basins of the valley regime, cut as *regions* rather than cell by cell
     * along the flow path.
     *
     * This is the fix the 2048 render forced, and it is a change of kind rather than of degree.
     * Cutting a basin per cell down a D8 path makes a hollow exactly as wide as the path, and a
     * rank of parallel paths — which is what a piedmont at the foot of a range carries, and what
     * the whole flat world carries — makes a rank of parallel straight hollows. No threshold on the
     * paths can fix that, because the hollow inherits its shape from the line it was drawn along.
     * A region cannot: it has a shape of its own, and the shape is checked before anything is cut.
     *
     * For each stretch of ice that shares a reach:
     *
     *  1. **Frozen ground only.** Nothing is hollowed out in the run-out past the snowline. A snout
     *     below its own snowline leaves a terminal moraine and an outwash plain, not a staircase of
     *     rock basins — and the run-out is exactly the flat piedmont where the flow paths run
     *     straight and parallel.
     *  2. **Straightness.** If the ice walked the straight-line distance from the head of the
     *     stretch to its lip it repeated one D8 step the whole way. That is the grid, not a valley.
     *  3. **The valley floor**: everything within the trough's half-width of the path by Euclidean
     *     distance — a union of discs, so the tube itself has no bearing — *and standing no higher
     *     than the lowest ground on that path plus the depth of the cut*, and claimed by no
     *     stronger basin. The height bound is what the walls of the valley are for. Without it the
     *     footprint is a tube around a D8 path; a D8 path runs dead straight for tens of cells at
     *     one of eight bearings, and a tube around a straight line is a ruled bar whatever metric
     *     drew it. With it the footprint stops where the ground climbs, so its edge is a contour.
     *  4. **Filled from the bottom**: the lowest cells of that floor, taken outward from its
     *     deepest point in order of height until [GlaciationConfig.maxLakeAreaKm2] is reached. The
     *     area cap peels by height, not by ring — see [keepLowestCells] — so what is kept is
     *     connected by construction and its outline is one contour of the ground.
     *  5. **Sized**: under [GlaciationConfig.minLakeAreaKm2] it is not worth cutting.
     *  6. **Not a bar**, as a last check on the finished shape: nothing two cells or less across
     *     and four or more long on any grid bearing survives.
     *  7. **Within budget**, shared with the sheet: see [GlaciationConfig.sheetLakeShare].
     *
     * The floor is then cut from the lowest cell of the region *and its rim*, so the basin is
     * closed the same way a scour basin is, and shaped as a bowl by Euclidean distance from that
     * rim — see [cutBowl]. No till is needed to dam it, which is why
     * [GlaciationConfig.riegelHeightMetres] is now zero.
     */
    private fun cutBasins(
        glaciation: GlaciationConfig,
        carving: Carving,
        cellsAcross: Int,
        cellsDown: Int,
        isLand: BooleanArray,
        frozen: BooleanArray,
        glacier: BooleanArray,
        directions: IntArray,
        order: IntArray,
        reach: IntArray,
        progress: FloatArray,
        strength: FloatArray,
        ice: FloatArray,
        carved: FloatArray,
        minCells: Int,
        maxCells: Int,
        budget: Int,
        basinFloor: IntArray
    ): BasinTally {
        val cellCount = cellsAcross * cellsDown
        if (budget < minCells) return BasinTally(0, 0, 0, 0, 0, 0)

        val seed = BooleanArray(cellCount)
        for (cell in 0 until cellCount) {
            if (!glacier[cell] || !frozen[cell]) continue
            if (progress[cell] - reach[cell] < glaciation.basinShare) seed[cell] = true
        }

        // One stretch of ice per reach, tributaries included, joined along the flow.
        val segment = IntArray(cellCount) { -1 }
        val queue = IntArray(cellCount)
        val segIce = ArrayList<Float>()
        val segCount = ArrayList<Int>()
        val segFirst = ArrayList<Int>()
        for (start in 0 until cellCount) {
            if (!seed[start] || segment[start] >= 0) continue
            val id = segCount.size
            var head = 0
            var tail = 0
            queue[tail++] = start
            segment[start] = id
            var count = 0
            var maxIce = 0f
            while (head < tail) {
                val walked = queue[head++]
                count++
                if (ice[walked] > maxIce) maxIce = ice[walked]
                val receiver = directions[walked]
                if (receiver >= 0 && seed[receiver] && segment[receiver] < 0 && reach[receiver] == reach[walked]) {
                    segment[receiver] = id
                    queue[tail++] = receiver
                }
                FlowRouting.forEachNeighbour(
                    cellsAcross, cellsDown, walked % cellsAcross, walked / cellsAcross
                ) { neighbourCell ->
                    if (seed[neighbourCell] && segment[neighbourCell] < 0 && directions[neighbourCell] == walked &&
                        reach[neighbourCell] == reach[walked]
                    ) {
                        segment[neighbourCell] = id
                        queue[tail++] = neighbourCell
                    }
                }
            }
            segIce.add(maxIce)
            segCount.add(count)
            segFirst.add(start)
        }
        if (segCount.isEmpty()) return BasinTally(0, 0, 0, 0, 0, 0)

        val offset = IntArray(segCount.size + 1)
        for (segmentIndex in segCount.indices) {
            offset[segmentIndex + 1] = offset[segmentIndex] + segCount[segmentIndex]
        }
        val fill = IntArray(segCount.size)
        val packed = IntArray(offset[segCount.size])
        for (cell in 0 until cellCount) {
            val segmentId = segment[cell]
            if (segmentId < 0) continue
            packed[offset[segmentId] + fill[segmentId]] = cell
            fill[segmentId] = fill[segmentId] + 1
        }

        // How far the ice walked inside its own stretch, and where it started, so the straightness
        // of the stretch can be asked the same question [GlaciationConfig.minSinuosity] asks of a
        // whole trough. Accumulated along the drainage order, which runs heads first.
        val chain = FloatArray(cellCount)
        val chainHead = IntArray(cellCount) { -1 }
        for (index in order.indices) {
            val cell = order[index]
            val segmentId = segment[cell]
            if (segmentId < 0) continue
            if (chainHead[cell] < 0) chainHead[cell] = cell
            val receiver = directions[cell]
            if (receiver >= 0 && segment[receiver] == segmentId) {
                val distance = chain[cell] +
                    (if (isDiagonal(cell, receiver, cellsAcross)) DIAGONAL_STEP_CELLS else 1f)
                if (distance > chain[receiver]) {
                    chain[receiver] = distance
                    chainHead[receiver] = chainHead[cell]
                }
            }
        }

        // Strongest first, so the ground goes to the glacier that carries the most ice and the
        // tie is broken by position rather than by the order the grid was walked in.
        val ranked = Array(segCount.size) { it }
        ranked.sortWith(compareByDescending<Int> { segIce[it] }.thenBy { segFirst[it] })

        val own = IntArray(cellCount) { -1 }
        val region = IntArray(cellCount) { -1 }
        val visited = IntArray(cellCount) { -1 }
        val rimDistance = FloatArray(cellCount)
        val footList = IntArray(cellCount)
        val regionList = IntArray(cellCount)
        val heap = LongMinHeap(maxCells * 8 + 16)

        var spent = 0
        var basins = 0
        var noFloor = 0
        var tooStraight = 0
        var tooSmall = 0
        var overBudget = 0

        for (segmentId in ranked) {
            if (budget - spent < minCells) {
                overBudget++
                continue
            }
            val from = offset[segmentId]
            val until = offset[segmentId + 1]

            var outlet = packed[from]
            for (index in from until until) if (chain[packed[index]] > chain[outlet]) outlet = packed[index]
            if (sinuosity(chainHead[outlet], outlet, chain[outlet], cellsAcross) < glaciation.minSinuosity) {
                tooStraight++
                continue
            }

            // What the ice takes out of this reach — and, because it is the same number, how far
            // above the valley's own floor the ground still belongs to the basin: a cut this deep
            // can put that much of the valley under the basin's rim and not a metre more.
            var thickness = 0f
            for (index in from until until) thickness += strength[packed[index]]
            thickness /= (until - from).toFloat()
            val basinDepth = (carving.deepening + carving.overDeepening) * thickness

            var lowestOnPath = Float.MAX_VALUE
            for (index in from until until) {
                if (carved[packed[index]] < lowestOnPath) lowestOnPath = carved[packed[index]]
            }
            val highestFloor = lowestOnPath + basinDepth

            // The valley floor: within the trough's half-width of the path, a union of Euclidean
            // discs so the tube has no bearing of its own, and under the height the walls of the
            // valley set. Claimed exclusively, so two basins never share ground.
            var footCount = 0
            for (index in from until until) {
                val walked = packed[index]
                val radius = valleyHalfWidth(carving, strength[walked])
                val centreColumn = walked % cellsAcross
                val centreRow = walked / cellsAcross
                val span = radius.toInt() + 1
                val radiusSquared = radius * radius
                for (rowOffset in -span..span) {
                    val neighbourRow = centreRow + rowOffset
                    if (neighbourRow < 0 || neighbourRow >= cellsDown) continue
                    for (columnOffset in -span..span) {
                        if ((columnOffset * columnOffset + rowOffset * rowOffset).toFloat() > radiusSquared) continue
                        var neighbourColumn = (centreColumn + columnOffset) % cellsAcross
                        if (neighbourColumn < 0) neighbourColumn += cellsAcross
                        val neighbour = neighbourRow * cellsAcross + neighbourColumn
                        if (!isLand[neighbour] || own[neighbour] >= 0) continue
                        if (carved[neighbour] > highestFloor) continue
                        own[neighbour] = segmentId
                        region[neighbour] = segmentId
                        footList[footCount++] = neighbour
                    }
                }
            }
            if (footCount < minCells) {
                for (index in 0 until footCount) own[footList[index]] = -1
                noFloor++
                continue
            }

            // Filled from the bottom up to the area cap, which leaves a contour for an outline.
            val regionCount = keepLowestCells(
                cellsAcross, cellsDown, footList, footCount, region, segmentId, visited,
                carved, maxCells, heap, regionList
            )
            if (regionCount < minCells) {
                for (index in 0 until footCount) own[footList[index]] = -1
                tooSmall++
                continue
            }
            if (isStraightBar(regionList, regionCount, cellsAcross)) {
                for (index in 0 until footCount) own[footList[index]] = -1
                tooStraight++
                continue
            }
            if (spent + regionCount > budget) {
                for (index in 0 until footCount) own[footList[index]] = -1
                overBudget++
                continue
            }

            rimDistanceCells(
                cellsAcross, cellsDown, regionList, regionCount, region, segmentId, rimDistance
            )
            cutBowl(
                cellsAcross, cellsDown, regionList, regionCount, region, segmentId, basinDepth,
                isLand, carved, rimDistance
            )
            for (index in 0 until regionCount) basinFloor[regionList[index]] = basins
            spent += regionCount
            basins++
        }
        return BasinTally(spent, basins, noFloor, tooStraight, tooSmall, overBudget)
    }

    /**
     * Whether a body of cells is a narrow straight run along one of the four grid bearings.
     *
     * The shape the author has now reported three times: a bar of water a cell or two across and
     * five to fifteen long, lying at exactly 0, 45 or 90 degrees, in ranks. Measured as the extent
     * of the body along and across each bearing, so a bar is caught whatever its length and a blob
     * that merely happens to be elongated is not.
     */
    private fun isStraightBar(cells: IntArray, count: Int, cellsAcross: Int): Boolean {
        if (count < BAR_LENGTH) return false
        val anchor = cells[0] % cellsAcross
        val alongMin = IntArray(BEARINGS) { Int.MAX_VALUE }
        val alongMax = IntArray(BEARINGS) { Int.MIN_VALUE }
        val acrossMin = IntArray(BEARINGS) { Int.MAX_VALUE }
        val acrossMax = IntArray(BEARINGS) { Int.MIN_VALUE }
        for (index in 0 until count) {
            val cell = cells[index]
            val row = cell / cellsAcross
            val column = anchor + offsetFrom(anchor, cell % cellsAcross, cellsAcross)
            for (bearing in 0 until BEARINGS) {
                val along = alongBearingOf(column, row, bearing)
                val across = acrossBearingOf(column, row, bearing)
                if (along < alongMin[bearing]) alongMin[bearing] = along
                if (along > alongMax[bearing]) alongMax[bearing] = along
                if (across < acrossMin[bearing]) acrossMin[bearing] = across
                if (across > acrossMax[bearing]) acrossMax[bearing] = across
            }
        }
        for (bearing in 0 until BEARINGS) {
            val length =
                if (isDiagonalBearing(bearing)) (alongMax[bearing] - alongMin[bearing]) / 2 + 1
                else alongMax[bearing] - alongMin[bearing] + 1
            val across = acrossMax[bearing] - acrossMin[bearing] + 1
            if (across <= BAR_WIDTH && length >= BAR_LENGTH) return true
        }
        return false
    }

    /**
     * Keeps the lowest [cap] cells of a footprint, taken outward from its deepest cell in order of
     * height, and leaves [stamp] marking exactly those.
     *
     * This is the area cap, and it peels by height rather than by ring. A basin holds water from
     * its bottom up and stops at one level, so the outline of what it keeps is a contour of the
     * ground underneath it — a shape the terrain chose. Peeling rings off the rim instead shrinks
     * a region toward its own medial axis, and on a square grid a medial axis is a grid shape
     * however the ground runs: four-connected rings are Manhattan diamonds and their contours meet
     * at 45 degrees. What is kept is also connected by construction, because the walk only ever
     * steps out of a cell it has already taken.
     *
     * @param cells the footprint, left as it was found.
     * @param stamp marks the footprint with [marker] on the way in and the kept cells on the way
     *   out; everything the cap left outside is set to -1.
     * @param visited scratch, stamped with [marker] as the walk queues each cell, so it never has
     *   to be cleared between basins.
     * @param kept receives the cells that survived, deepest first.
     * @return how many cells were kept.
     */
    private fun keepLowestCells(
        cellsAcross: Int,
        cellsDown: Int,
        cells: IntArray,
        count: Int,
        stamp: IntArray,
        marker: Int,
        visited: IntArray,
        carved: FloatArray,
        cap: Int,
        heap: LongMinHeap,
        kept: IntArray
    ): Int {
        var deepest = cells[0]
        for (index in 1 until count) {
            val cell = cells[index]
            if (carved[cell] < carved[deepest]) deepest = cell
        }
        heap.push(FlowRouting.encode(carved[deepest], deepest))
        visited[deepest] = marker
        var keptCount = 0
        while (!heap.isEmpty() && keptCount < cap) {
            val cell = FlowRouting.decodeIndex(heap.pop())
            kept[keptCount++] = cell
            FlowRouting.forEachNeighbour(
                cellsAcross, cellsDown, cell % cellsAcross, cell / cellsAcross
            ) { neighbour ->
                if (stamp[neighbour] == marker && visited[neighbour] != marker) {
                    visited[neighbour] = marker
                    heap.push(FlowRouting.encode(carved[neighbour], neighbour))
                }
            }
        }
        while (!heap.isEmpty()) heap.pop()
        for (index in 0 until count) stamp[cells[index]] = -1
        for (index in 0 until keptCount) stamp[kept[index]] = marker
        return keptCount
    }

    /**
     * Euclidean distance, in cells, from each cell of a basin to the nearest cell outside it.
     *
     * The field the floor is shaped by, and the reason it is Euclidean rather than a walk inward
     * from the rim: a four-connected walk measures the Manhattan metric, whose contours are
     * diamonds, so a floor cut from one has facets at 45 degrees however round the basin is.
     *
     * Measured by [JumpFloodDistance] over a window around the basin and not over the whole map,
     * because a basin holds at most [GlaciationConfig.maxLakeAreaKm2] of ground and a 2048 grid is
     * four million cells. The window is padded on every side by a quarter of the basin's longer
     * side plus one, because the flood's x axis wraps: a cell of the basin lies at most half the
     * basin's shorter side from its own rim — walk toward the nearest edge of the basin's box and
     * you have left the basin before you cross it — so a pad that wide leaves every wrapped-around
     * rim cell further off than the true one, and the wrap cannot win.
     *
     * @param stamp marks the basin's own cells with [marker]. Everything else is rim, the ground
     *   beyond the poles included, since a basin reaching the top of the map is bounded by it
     *   exactly as one reaching a hillside is.
     * @param rimDistance receives the distance at each of [cells] and is read nowhere else.
     */
    private fun rimDistanceCells(
        cellsAcross: Int,
        cellsDown: Int,
        cells: IntArray,
        count: Int,
        stamp: IntArray,
        marker: Int,
        rimDistance: FloatArray
    ) {
        // Column offsets are all taken from one anchor, so a basin straddling the date line is one
        // box rather than two at opposite edges of the map.
        val anchorColumn = cells[0] % cellsAcross
        var leftOffset = 0
        var rightOffset = 0
        var topRow = Int.MAX_VALUE
        var bottomRow = Int.MIN_VALUE
        for (index in 0 until count) {
            val cell = cells[index]
            val columnOffset = offsetFrom(anchorColumn, cell % cellsAcross, cellsAcross)
            if (columnOffset < leftOffset) leftOffset = columnOffset
            if (columnOffset > rightOffset) rightOffset = columnOffset
            val row = cell / cellsAcross
            if (row < topRow) topRow = row
            if (row > bottomRow) bottomRow = row
        }
        val boxWidth = rightOffset - leftOffset + 1
        val boxHeight = bottomRow - topRow + 1
        val pad = maxOf(boxWidth, boxHeight) / 4 + 1
        val windowWidth = boxWidth + 2 * pad
        val windowHeight = boxHeight + 2 * pad
        val distance = FloatArray(windowWidth * windowHeight)
        val nearestRim = IntArray(windowWidth * windowHeight)
        for (windowRow in 0 until windowHeight) {
            val row = topRow - pad + windowRow
            for (windowColumn in 0 until windowWidth) {
                val windowCell = windowRow * windowWidth + windowColumn
                var insideBasin = false
                if (row >= 0 && row < cellsDown) {
                    var column = (anchorColumn + leftOffset - pad + windowColumn) % cellsAcross
                    if (column < 0) column += cellsAcross
                    insideBasin = stamp[row * cellsAcross + column] == marker
                }
                distance[windowCell] = if (insideBasin) JumpFloodDistance.INFINITE else 0f
                nearestRim[windowCell] = if (insideBasin) -1 else windowCell
            }
        }
        JumpFloodDistance.run(windowWidth, windowHeight, distance, nearestRim)
        for (index in 0 until count) {
            val cell = cells[index]
            val windowColumn =
                offsetFrom(anchorColumn, cell % cellsAcross, cellsAcross) - leftOffset + pad
            val windowRow = cell / cellsAcross - topRow + pad
            rimDistance[cell] = distance[windowRow * windowWidth + windowColumn]
        }
    }

    /** How far east [column] lies of [anchorColumn], taking the short way round the world. */
    private fun offsetFrom(anchorColumn: Int, column: Int, cellsAcross: Int): Int {
        var offset = column - anchorColumn
        if (offset > cellsAcross / 2) offset -= cellsAcross
        if (offset < -cellsAcross / 2) offset += cellsAcross
        return offset
    }

    /**
     * Cuts a basin floor out of a region: a bowl below the lowest cell of the region *and its rim*,
     * so nothing around it can drain it, deepest in the middle and grading to nothing at the edge.
     *
     * The profile is a paraboloid in [rimDistance] — at a fraction `s` of the way in from the rim
     * to the deepest point the cut takes `1 - (1 - s)^2` of the way down. A paraboloid is the one
     * bowl whose area per unit of depth is constant, so no level of it holds more of the floor than
     * any other and there is no plate in it to read as a slab; Hutchinson (*A Treatise on
     * Limnology*, 1957) puts real lake basins at a volume development of 0.6 to 1.2 about the
     * paraboloid's 1.0, so it is also the middle of what a lake basin does. The saucer this
     * replaced took the full cut at every cell more than two cells inside the rim, which on a basin
     * a hundred cells across is a dead-level floor over nineteen twentieths of its area.
     *
     * ### What the profile is a share *of*, and why that is the whole of I2b
     *
     * The first version of this spent the profile as a height: a cell's floor was `base - depth *
     * profile` and nothing else, so the finished floor was a function of [rimDistance] alone and
     * every cell at one distance from the rim stood at *exactly* one height. On a small basin
     * there are very few distances: 718106's 167-cell basin at (488,25) at 1024 has four of them —
     * 1, 1.414, 2 and 2.236 cells — so the floor came out as four terraces, the outermost holding
     * 131 of the 167 cells, which is the 78.4% within a metre of one height that I2b was opened
     * on. The ground it was cut out of had never been read at all.
     *
     * So the profile is spent as a *share of the ground*, which is the rule I2 had already written
     * for the trough's cross-section next door and this had been missed out of: a cell is taken
     * `profile` of the way from where it stands down to `base - depth`, and keeps `1 - profile` of
     * its own relief. At the deepest point that is the level bowl exactly as before, so the basin
     * is closed by construction on the same argument; at the rim the ground is left where it was;
     * and nowhere is the answer a function of the distance field alone, so no two cells share a
     * height unless the ground already did. A cell standing high above the basin's floor is also
     * cut deeper than one standing on it, which is what ice does to a bump.
     *
     * @return how many cells the cut actually lowered.
     */
    private fun cutBowl(
        cellsAcross: Int,
        cellsDown: Int,
        cells: IntArray,
        count: Int,
        stamp: IntArray,
        marker: Int,
        depth: Float,
        isLand: BooleanArray,
        carved: FloatArray,
        rimDistance: FloatArray
    ): Int {
        var base = Float.MAX_VALUE
        var deepestFromRim = 0f
        for (index in 0 until count) {
            val cell = cells[index]
            if (carved[cell] < base) base = carved[cell]
            if (rimDistance[cell] > deepestFromRim) deepestFromRim = rimDistance[cell]
            // Land only. A basin that reaches the coast has the sea for a neighbour, and reading
            // the sea floor as its rim would say the floor has to be cut below the ocean — which,
            // clamped at the waterline, plates the whole basin flat at sea level.
            forEachOrthogonal(cellsAcross, cellsDown, cell % cellsAcross, cell / cellsAcross) { neighbour ->
                if (stamp[neighbour] != marker && isLand[neighbour] &&
                    carved[neighbour] < base
                ) {
                    base = carved[neighbour]
                }
            }
        }
        if (deepestFromRim <= 0f) return 0
        var lowered = 0
        for (index in 0 until count) {
            val cell = cells[index]
            val here = carved[cell]
            val towardTheRim = 1f - (rimDistance[cell] / deepestFromRim).coerceIn(0f, 1f)
            // How much of the way from the ground as it stands down to the level bowl this cell is
            // taken. One at the deepest point, nothing at the rim, and a paraboloid between: the
            // same profile as before, spent as a *share of the ground* rather than as a height.
            val excavated = 1f - towardTheRim * towardTheRim
            val target = (here - excavated * ((here - base) + depth)).coerceAtLeast(0f)
            if (target < here) {
                carved[cell] = target
                lowered++
            }
        }
        return lowered
    }

    /** Widest a body of water may be and still count as a bar, in cells. */
    private const val BAR_WIDTH = 2

    /** Shortest run along one bearing that makes a narrow body a bar rather than a blob. */
    private const val BAR_LENGTH = 4

    /**
     * The grid bearings a shape can line up with: east, south-east, south and north-east.
     *
     * Four rather than eight, because a bearing and its opposite are one line. These are the only
     * directions a square grid offers, so they are the only directions an artefact of one can lie
     * along, and every shape test in this file and in `GlaciationTest` is asked of all four.
     */
    internal const val BEARINGS = 4

    /**
     * How far along bearing [bearing] the cell at ([column], [row]) lies, in half-cells on a
     * diagonal and whole cells on an axis.
     *
     * [column] must already have had the world's wrap taken out of it against a common anchor —
     * see [offsetFrom] — or two cells either side of the date line will read as being half a world
     * apart. On a diagonal the coordinate steps by two per cell, which is why a *length* read off
     * it is halved and a *width* is not.
     */
    internal fun alongBearingOf(column: Int, row: Int, bearing: Int): Int = when (bearing) {
        0 -> column
        1 -> column + row
        2 -> row
        else -> column - row
    }

    /** How far across bearing [bearing] that same cell lies, on the same terms. */
    internal fun acrossBearingOf(column: Int, row: Int, bearing: Int): Int = when (bearing) {
        0 -> row
        1 -> column - row
        2 -> column
        else -> column + row
    }

    /** Whether [bearing] runs at 45 degrees, where one cell is two steps of the coordinate. */
    internal fun isDiagonalBearing(bearing: Int): Boolean = bearing == 1 || bearing == 3

    /** The connected fields of frozen ground, and how many cells each holds. */
    private class FrozenFields(val id: IntArray, val size: IntArray, val count: Int)

    /** What the parallel rule threw away, for the tally. */
    private class Suppression(val cells: Int, val trunks: Int, val kept: Int)

    /**
     * Connected regions of frozen ground, eight-connected, so an ice cap and a cold massif on the
     * far side of the world are told apart. Eight rather than four, because a snowfield joined only
     * at a corner is still one snowfield.
     */
    private fun frozenFields(cellsAcross: Int, cellsDown: Int, frozen: BooleanArray): FrozenFields {
        val cellCount = cellsAcross * cellsDown
        val id = IntArray(cellCount) { -1 }
        val sizes = ArrayList<Int>()
        val queue = IntArray(cellCount)
        for (start in 0 until cellCount) {
            if (!frozen[start] || id[start] >= 0) continue
            val label = sizes.size
            var headIdx = 0
            var tail = 0
            queue[tail++] = start
            id[start] = label
            var count = 0
            while (headIdx < tail) {
                val cell = queue[headIdx++]
                count++
                val column = cell % cellsAcross
                val row = cell / cellsAcross
                FlowRouting.forEachNeighbour(cellsAcross, cellsDown, column, row) { neighbour ->
                    if (frozen[neighbour] && id[neighbour] < 0) {
                        id[neighbour] = label
                        queue[tail++] = neighbour
                    }
                }
            }
            sizes.add(count)
        }
        return FrozenFields(id, sizes.toIntArray(), sizes.size)
    }

    /**
     * How far a path wandered, against the straight line between its ends.
     *
     * One means it did not wander at all, which on this grid means it repeated the same D8 step
     * from beginning to end. That is not a valley.
     */
    private fun sinuosity(head: Int, snout: Int, length: Float, cellsAcross: Int): Float {
        if (head < 0 || snout < 0 || length <= 0f) return 0f
        var columnOffset = (snout % cellsAcross) - (head % cellsAcross)
        if (columnOffset > cellsAcross / 2) columnOffset -= cellsAcross
        if (columnOffset < -cellsAcross / 2) columnOffset += cellsAcross
        val rowOffset = (snout / cellsAcross) - (head / cellsAcross)
        val straight = sqrt((columnOffset * columnOffset + rowOffset * rowOffset).toFloat())
        if (straight < 1e-3f) return Float.MAX_VALUE
        return length / straight
    }

    /**
     * Drops ice that runs beside a stronger glacier at the same bearing.
     *
     * The ground is offered to the ice in order of how much of it there is: the cell carrying the
     * most claims its trough first, and a cell that finds itself already inside a stronger glacier's
     * trough, pointing the same way, is not a second glacier. It is the same one, or it is a gully
     * that would have been swallowed.
     *
     * Cell by cell, and that is the correction rather than the first instinct. Grouping the ice into
     * flow-connected chains and suppressing whole chains does nothing at all, because a comb of
     * gullies down a range front all drain into the same channel at the bottom: the comb and its
     * trunk are one connected chain, so there is never a second chain to drop. What has to be
     * compared is the ground each part of the ice occupies.
     *
     * Orientation rather than direction: the bearings are compared as doubled angles, so two
     * troughs on opposite sides of a divide flowing apart down the same lineament count as
     * parallel, which they are.
     */
    private fun suppressParallel(
        glaciation: GlaciationConfig,
        carving: Carving,
        cellsAcross: Int,
        cellsDown: Int,
        glacier: BooleanArray,
        directions: IntArray,
        ice: FloatArray,
        strength: FloatArray,
        order: IntArray
    ): Suppression {
        val cellCount = cellsAcross * cellsDown
        if (glaciation.parallelSpacing <= 0f) {
            return Suppression(0, countTrunks(cellsAcross, cellsDown, glacier, directions), 0)
        }

        // Every bearing read once, before anything is dropped, so that what one cell is compared
        // against cannot depend on which cells were dropped before it.
        val orientX = FloatArray(cellCount)
        val orientY = FloatArray(cellCount)
        var neighbour = 0
        for (cell in 0 until cellCount) {
            if (!glacier[cell]) continue
            neighbour++
            val flow = flowOf(cell, directions, glacier, cellsAcross, cellsDown)
            val axisX = unpackX(flow)
            val axisY = unpackY(flow)
            // Doubled angle: (x, y) -> (x^2 - y^2, 2xy), so a bearing and its reverse agree and a
            // dot product of 0.707 between two of them is 22.5 degrees between the originals.
            orientX[cell] = axisX * axisX - axisY * axisY
            orientY[cell] = 2f * axisX * axisY
        }
        if (neighbour == 0) return Suppression(0, 0, 0)

        // The branches. At every confluence the feeder carrying the most ice continues the branch it
        // was already on and the others begin their own, which is the ordinary main-stem
        // decomposition of a river network — so a branch is one gully from its head to the point
        // where it gives itself up to something larger.
        //
        // Branches rather than cells, and that too is a correction rather than a first instinct.
        // Dropping individual cells cuts holes in the middle of troughs, and a trough with holes in
        // it is a *chain* of short bars where there was one long lake: measured on seed 718106 at
        // 1024, suppressing by cell took the count of parallel bars of water from 114 up to 281. A
        // gully dropped from its head to its confluence leaves nothing behind to fragment.
        val dominant = IntArray(cellCount) { -1 }
        for (cell in 0 until cellCount) {
            if (!glacier[cell]) continue
            var best = -1
            var bestIce = -1f
            FlowRouting.forEachNeighbour(
                cellsAcross, cellsDown, cell % cellsAcross, cell / cellsAcross
            ) { neighbourCell ->
                if (glacier[neighbourCell] && directions[neighbourCell] == cell &&
                    ice[neighbourCell] > bestIce
                ) {
                    bestIce = ice[neighbourCell]
                    best = neighbourCell
                }
            }
            dominant[cell] = best
        }
        val branch = IntArray(cellCount) { -1 }
        val branchIce = ArrayList<Float>()
        val branchStart = ArrayList<Int>()
        val branchCount = ArrayList<Int>()
        for (index in order.indices) {
            val cell = order[index]
            if (!glacier[cell]) continue
            val distance = dominant[cell]
            val branchId = if (distance >= 0 && branch[distance] >= 0) {
                branch[distance]
            } else {
                branchIce.add(0f)
                branchStart.add(cell)
                branchCount.add(0)
                branchIce.size - 1
            }
            branch[cell] = branchId
            branchCount[branchId] = branchCount[branchId] + 1
            if (ice[cell] > branchIce[branchId]) branchIce[branchId] = ice[cell]
        }

        // Cells of each branch, laid out contiguously so no per-branch allocation is needed.
        val offset = IntArray(branchCount.size + 1)
        for (branchId in branchCount.indices) offset[branchId + 1] = offset[branchId] + branchCount[branchId]
        val fill = IntArray(branchCount.size)
        val packed = IntArray(neighbour)
        for (cell in 0 until cellCount) {
            val branchId = branch[cell]
            if (branchId < 0) continue
            packed[offset[branchId] + fill[branchId]] = cell
            fill[branchId] = fill[branchId] + 1
        }

        // Strongest first, and the branch's first cell breaks a tie, so nothing here depends on the
        // order the grid happened to be walked in.
        val ranked = Array(branchCount.size) { it }
        ranked.sortWith(compareByDescending<Int> { branchIce[it] }.thenBy { branchStart[it] })

        val claimed = BooleanArray(cellCount)
        val claimX = FloatArray(cellCount)
        val claimY = FloatArray(cellCount)
        var dropped = 0
        for (branchId in ranked) {
            val from = offset[branchId]
            val until = offset[branchId + 1]
            var conflict = 0
            for (index in from until until) {
                val walked = packed[index]
                if (!claimed[walked]) continue
                if (orientX[walked] * claimX[walked] + orientY[walked] * claimY[walked] >= PARALLEL_BEARING_COSINE) conflict++
            }
            if (conflict * 3 > until - from) {
                for (index in from until until) glacier[packed[index]] = false
                dropped += until - from
                continue
            }
            for (index in from until until) {
                val walked = packed[index]
                stampClaim(
                    cellsAcross,
                    cellsDown,
                    walked,
                    valleyHalfWidth(carving, strength[walked]) * glaciation.parallelSpacing,
                    orientX[walked], orientY[walked], claimed, claimX, claimY
                )
            }
        }
        return Suppression(dropped, countTrunks(cellsAcross, cellsDown, glacier, directions), 0)
    }

    /** How many separate glaciers are left, counting a chain and its feeders as one. */
    private fun countTrunks(cellsAcross: Int, cellsDown: Int, glacier: BooleanArray, directions: IntArray): Int {
        val cellCount = cellsAcross * cellsDown
        val seen = BooleanArray(cellCount)
        val queue = IntArray(cellCount)
        var trunks = 0
        for (start in 0 until cellCount) {
            if (!glacier[start] || seen[start]) continue
            trunks++
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true
            while (head < tail) {
                val cell = queue[head++]
                val receiver = directions[cell]
                if (receiver >= 0 && glacier[receiver] && !seen[receiver]) {
                    seen[receiver] = true
                    queue[tail++] = receiver
                }
                FlowRouting.forEachNeighbour(
                    cellsAcross, cellsDown, cell % cellsAcross, cell / cellsAcross
                ) { neighbourCell ->
                    if (glacier[neighbourCell] && !seen[neighbourCell] &&
                        directions[neighbourCell] == cell
                    ) {
                        seen[neighbourCell] = true
                        queue[tail++] = neighbourCell
                    }
                }
            }
        }
        return trunks
    }

    /** Marks the ground a glacier occupies, with the orientation it occupies it at. */
    private fun stampClaim(
        cellsAcross: Int,
        cellsDown: Int,
        centre: Int,
        radius: Float,
        orientX: Float,
        orientY: Float,
        claimed: BooleanArray,
        claimX: FloatArray,
        claimY: FloatArray
    ) {
        val centreColumn = centre % cellsAcross
        val centreRow = centre / cellsAcross
        val span = radius.toInt() + 1
        val radiusSquared = radius * radius
        for (rowOffset in -span..span) {
            val neighbourRow = centreRow + rowOffset
            if (neighbourRow < 0 || neighbourRow >= cellsDown) continue
            for (columnOffset in -span..span) {
                if ((columnOffset * columnOffset + rowOffset * rowOffset).toFloat() > radiusSquared) continue
                var neighbourColumn = (centreColumn + columnOffset) % cellsAcross
                if (neighbourColumn < 0) neighbourColumn += cellsAcross
                val cell = neighbourRow * cellsAcross + neighbourColumn
                // First claim stands, and the strongest trunk claims first.
                if (claimed[cell]) continue
                claimed[cell] = true
                claimX[cell] = orientX
                claimY[cell] = orientY
            }
        }
    }

    /** Twenty-two and a half degrees, as a dot product of doubled-angle orientations. */
    private const val PARALLEL_BEARING_COSINE = 0.7071f

    /**
     * The span of the land, so every depth in [GlaciationConfig] can be a fraction of it.
     *
     * [SeaLevelStage] already normalises land to 0..1, so this is very nearly 1 — but it is
     * computed rather than assumed, because a config that never reaches the highest ground would
     * otherwise silently rescale every cut this stage makes.
     */
    /**
     * The elevation range inside an octagonal window of about [radius] cells around every cell:
     * relief, as the one measurement that separates ground a glacier is channelled by from ground
     * it is not.
     *
     * Water counts at the waterline rather than at its own depth. A cliff standing over the sea is
     * relief and a shallow shelf beside a plain is not, and reading the sea floor would make every
     * coast look alpine.
     *
     * ### Why the window is not a square, which is F30's finding
     *
     * A sliding extremum does not change while the same summit stays inside the window, so the
     * field it makes is a plateau around every summit and the plateau's *edge* is the set of cells
     * where that summit leaves the window — which is the window's own outline, turned inside out.
     * With a square window that outline is four straight lines `2 * radius` cells long, 53 at 1024
     * and 105 at 2048, and `channelled` — this field against one threshold — inherits them whole.
     * The sheet mask is what `channelled` leaves, so the sheet's own edges ran dead straight at 0
     * and 90 degrees, and a scour basin clipped to that mask carried a ruled edge with it. That is
     * why I2's outline guard reported the scour basins instead of asserting on them.
     *
     * The cure is the window TODO.md costed: an octagon, which is a square dilated by a diamond
     * and so is still four separable passes' worth of arithmetic per extremum rather than the
     * `radius^2` a disc would cost. Two of the passes run along the grid's diagonals, which on a
     * cylinder is exactly a column pass on a sheared copy: the diagonal through `(x, y)` is the
     * set `((x + y) mod width, y)`, one line per column and every line exactly as long as the map
     * is tall, so there is no seam to special-case.
     *
     * The two radii are set so the octagon is as close to a circle as an octagon gets: a regular
     * one, whose corner stands `sec(22.5 degrees)` = 1.0824 times its flat. Solving `square +
     * 2 * diagonal` against `(square + diagonal) * sqrt(2)` for that ratio gives
     * [OCTAGON_SQUARE_SHARE] and [OCTAGON_DIAGONAL_OVER_SQUARE]. What is left is a window whose
     * furthest and nearest points differ by 8.2% instead of a square's 41%, and whose longest
     * straight facet is `2 * radius * tan(22.5 degrees)` = 0.83 of the radius rather than twice it
     * — a fifth of what it was. It is not a circle and it is not claimed to be; the residual facet
     * is measured on the sheet mask's own outline in `GlacialBasinShapeTest`.
     *
     * Each pass is a monotonic-deque sliding window, so the cost is a constant per cell rather
     * than the square of the radius. East-west wraps, north-south clamps, exactly as the rest of
     * the pipeline treats the grid.
     */
    internal fun localRelief(
        cellsAcross: Int,
        cellsDown: Int,
        relative: FloatArray,
        radius: Int,
        octagon: Boolean
    ): FloatArray {
        val cellCount = cellsAcross * cellsDown
        val surface = FloatArray(cellCount) { relative[it].coerceAtLeast(0f) }
        val squareRadius = if (octagon) (OCTAGON_SQUARE_SHARE * radius).toInt().coerceAtLeast(1) else radius
        val diagonalRadius =
            if (octagon) (OCTAGON_DIAGONAL_OVER_SQUARE * squareRadius).toInt().coerceAtLeast(1) else 0
        val deque = IntArray(cellsAcross + cellsDown + 4 * (radius + 1))
        // Three buffers, not four: the first pass's scratch is free again once the highest field
        // has landed in the second, so the lowest is computed through the same one.
        val scratch = FloatArray(cellCount)
        val highest = octagonExtreme(
            cellsAcross, cellsDown, surface, squareRadius, diagonalRadius, true, deque,
            scratch, FloatArray(cellCount)
        )
        val lowest = octagonExtreme(
            cellsAcross, cellsDown, surface, squareRadius, diagonalRadius, false, deque,
            scratch, surface
        )
        val out = FloatArray(cellCount)
        for (cell in 0 until cellCount) out[cell] = highest[cell] - lowest[cell]
        return out
    }

    /**
     * The largest (or smallest) value of [src] inside an octagon of [squareRadius] plus twice
     * [diagonalRadius] cells about every cell, by four separable passes.
     *
     * Dilating by a square and then by a diamond is dilating by their Minkowski sum, which is the
     * octagon; the order does not matter and neither pass ever sees the whole shape. [first] and
     * [second] are scratch of the grid's own size, ping-ponged between the passes, and one of them
     * is returned.
     */
    private fun octagonExtreme(
        cellsAcross: Int,
        cellsDown: Int,
        src: FloatArray,
        squareRadius: Int,
        diagonalRadius: Int,
        wantMax: Boolean,
        deque: IntArray,
        first: FloatArray,
        second: FloatArray
    ): FloatArray {
        slideAcross(cellsAcross, cellsDown, src, first, squareRadius, wantMax, deque)
        slideDown(cellsAcross, cellsDown, first, second, squareRadius, wantMax, deque)
        // A diagonal radius of zero is the square window F30 found, kept as that finding's own
        // control: see `GlaciationConfig.reliefWindowOctagon`.
        if (diagonalRadius <= 0) return second
        slideDiagonal(cellsAcross, cellsDown, second, first, diagonalRadius, wantMax, deque, true)
        slideDiagonal(cellsAcross, cellsDown, first, second, diagonalRadius, wantMax, deque, false)
        return second
    }

    /** One sliding extremum along each row, wrapping east to west. */
    private fun slideAcross(
        cellsAcross: Int,
        cellsDown: Int,
        src: FloatArray,
        dst: FloatArray,
        radius: Int,
        wantMax: Boolean,
        deque: IntArray
    ) {
        val pad = FloatArray(cellsAcross + 2 * radius)
        for (row in 0 until cellsDown) {
            val base = row * cellsAcross
            for (index in pad.indices) {
                var column = (index - radius) % cellsAcross
                if (column < 0) column += cellsAcross
                pad[index] = src[base + column]
            }
            slide(pad, 2 * radius + 1, wantMax, deque) { offset, value -> dst[base + offset] = value }
        }
    }

    /** One sliding extremum down each column, clamping at the poles. */
    private fun slideDown(
        cellsAcross: Int,
        cellsDown: Int,
        src: FloatArray,
        dst: FloatArray,
        radius: Int,
        wantMax: Boolean,
        deque: IntArray
    ) {
        val pad = FloatArray(cellsDown + 2 * radius)
        for (column in 0 until cellsAcross) {
            for (index in pad.indices) {
                val row = (index - radius).coerceIn(0, cellsDown - 1)
                pad[index] = src[row * cellsAcross + column]
            }
            slide(pad, 2 * radius + 1, wantMax, deque) { offset, value ->
                dst[offset * cellsAcross + column] = value
            }
        }
    }

    /**
     * One sliding extremum along each diagonal: south-east if [downRight], south-west otherwise.
     *
     * A diagonal on a cylinder is a column of a sheared copy of the map, so there are exactly as
     * many diagonals as there are columns and each is exactly as long as the map is tall. Rows are
     * clamped at the poles, as [slideDown] clamps them, and columns wrap by construction.
     */
    private fun slideDiagonal(
        cellsAcross: Int,
        cellsDown: Int,
        src: FloatArray,
        dst: FloatArray,
        radius: Int,
        wantMax: Boolean,
        deque: IntArray,
        downRight: Boolean,
        ) {
        val pad = FloatArray(cellsDown + 2 * radius)
        for (line in 0 until cellsAcross) {
            for (index in pad.indices) {
                val row = (index - radius).coerceIn(0, cellsDown - 1)
                var column = (line + if (downRight) row else -row) % cellsAcross
                if (column < 0) column += cellsAcross
                pad[index] = src[row * cellsAcross + column]
            }
            slide(pad, 2 * radius + 1, wantMax, deque) { offset, value ->
                var column = (line + if (downRight) offset else -offset) % cellsAcross
                if (column < 0) column += cellsAcross
                dst[offset * cellsAcross + column] = value
            }
        }
    }

    /**
     * The square's share of an octagonal window's axis radius, and the diamond's share of the
     * square's.
     *
     * A square of radius `a` dilated by a diamond of `k` diagonal steps reaches `a + 2k` along an
     * axis and `(a + k) * sqrt(2)` along a diagonal. A regular octagon has the second over the
     * first at `sec(22.5 degrees)` = 1.08239, which solves to `k = 0.4421 a` and an axis radius of
     * `1.8842 a`. See [localRelief].
     */
    private const val OCTAGON_SQUARE_SHARE = 1f / 1.8842f
    private const val OCTAGON_DIAGONAL_OVER_SQUARE = 0.4421f

    /**
     * One sliding-window extreme over [src], emitting `src.size - span + 1` values.
     *
     * The deque holds indices whose values are still candidates, in decreasing order for a maximum
     * and increasing for a minimum, so the answer is always at its head.
     */
    private inline fun slide(
        src: FloatArray,
        span: Int,
        wantMax: Boolean,
        deque: IntArray,
        emit: (Int, Float) -> Unit
    ) {
        var head = 0
        var tail = 0
        var out = 0
        for (cell in src.indices) {
            while (tail > head &&
                (if (wantMax) src[deque[tail - 1]] <= src[cell] else src[deque[tail - 1]] >= src[cell])
            ) {
                tail--
            }
            deque[tail++] = cell
            if (deque[head] <= cell - span) head++
            if (cell >= span - 1) emit(out++, src[deque[head]])
        }
    }

    /**
     * Ice-sheet scour: what happens to frozen ground that has no valley in it.
     *
     * Two things, neither of which knows the flow network exists.
     *
     * The first is a gentle, noisy lowering of the whole province — sheet ice strips a shield to
     * bedrock and leaves it hummocky. The second is the basins, and they are the lakes. A
     * low-frequency seeded fBm decides where they go, nudged toward ground that is already concave
     * by [GlaciationConfig.sheetConcavity], and the candidates are taken at a quantile of that
     * score. What that leaves is blobs: irregular, of varied size, of varied orientation, with
     * nothing in their shape that refers to the grid, because nothing that made them did.
     *
     * Which candidates become lakes is then a matter of the allowance rather than of the quantile.
     * The blobs are ranked by how strongly the score chose them and taken in that order until the
     * budget [GlaciationConfig.sheetLakeShare] sets is spent; one under
     * [GlaciationConfig.minLakeAreaKm2] is passed over, and one over
     * [GlaciationConfig.maxLakeAreaKm2] is peeled inward until it fits, because a world map has
     * no business carrying a lake several times the size of Superior.
     *
     * Every basin is closed *by construction*. Its floor is cut from the lowest ground in the blob
     * **and its one-cell rim**, so no cell on the rim can be lower than the floor and the river
     * stage is guaranteed to find a depression rather than a channel. Even the shallowest part of
     * the floor stands [GlaciationConfig.sheetBasinDepthMetres] × 0.45 below that rim, comfortably clear
     * of [LakesConfig.minDepth].
     */
    private fun scour(
        config: WorldGenConfig,
        glaciation: GlaciationConfig,
        carving: Carving,
        cellsAcross: Int,
        cellsDown: Int,
        sheet: BooleanArray,
        sheetCells: Int,
        /** Where the ice at each sheet cell flows to, down its own surface. See [IceSheet]. */
        surfaceFlow: IntArray,
        isLand: BooleanArray,
        relative: FloatArray,
        carved: FloatArray,
        minCells: Int,
        maxCells: Int,
        budget: Int,
        basinFloor: IntArray,
        firstBasinNumber: Int
    ): ScourTally {
        val cellCount = cellsAcross * cellsDown
        // Seeded off the world seed, so the pattern is this world's and is reproduced exactly on
        // any platform that runs the same arithmetic.
        val basinNoise = PerlinNoise(config.seed * 31L + 0x91E5L)
        val hummockNoise = PerlinNoise(config.seed * 31L + 0x27C3L)
        val period = glaciation.sheetBasinCycles.toInt().coerceAtLeast(2)
        val hummockPeriod = (period * 3).coerceAtLeast(4)

        // The hummocky lowering first, so that the basins below are cut against ground that has
        // already been planed and their rims cannot turn out to be lower than their floors.
        //
        // Streamlined along the ice's own flow, which is I1's. A sheet's bed is not hummocky in an
        // isotropic way: it carries flutes, megaflutes and drumlin fields, all of them elongated
        // along the direction the ice was moving, and the Laurentide's are the classic ones —
        // Canada's drumlin swarms fan out from the ice divides because the ice did. The noise
        // itself stays isotropic and periodic, which is what keeps the east-west seam continuous
        // and keeps the grid out of it; what makes the pattern lineated is that each cell is given
        // the *mean of the noise along its own flow line*, [STREAMLINE_CELLS] steps down the
        // surface's steepest descent. Averaging along a direction stretches the features in it, so
        // the lowering varies slowly along the flow and at the noise's own scale across it. The
        // flow comes from the ice surface and the surface is radial about the dome, so the
        // lineations are radial about the dome without anything having been told to draw a radius.
        val lowering = carving.sheetLowering
        if (lowering > 0f) {
            for (cell in 0 until cellCount) {
                if (!sheet[cell]) continue
                var sum = 0f
                var samples = 0
                var walked = cell
                while (samples <= STREAMLINE_CELLS && walked >= 0 && sheet[walked]) {
                    val column = (walked % cellsAcross).toFloat()
                    val row = (walked / cellsAcross).toFloat()
                    sum += 0.5f + 0.5f * hummockNoise.fbm(
                        column * hummockPeriod / cellsAcross, row * hummockPeriod / cellsDown,
                        3, hummockPeriod, hummockPeriod
                    )
                    samples++
                    walked = surfaceFlow[walked]
                }
                val neighbour = sum / samples
                val target = (relative[cell] - lowering * (0.35f + 0.65f * neighbour)).coerceAtLeast(0f)
                if (target < carved[cell]) carved[cell] = target
            }
        }

        val depth = carving.sheetBasinDepth
        if (depth <= 0f || budget < minCells) return ScourTally(0, 0)

        // How hollow each cell is against the ground around it, and the scale of that hollowness
        // over the whole province, so the concavity term can be weighed against a 0..1 noise
        // without a constant nobody could justify.
        val meanRadius = (carving.valleyWidthCells * 0.5f).toInt().coerceIn(2, 24)
        val concavity = FloatArray(cellCount)
        var concavityScale = 0.0
        for (cell in 0 until cellCount) {
            if (!sheet[cell]) continue
            val centreColumn = cell % cellsAcross
            val centreRow = cell / cellsAcross
            var sum = 0f
            var neighbour = 0
            for (rowOffset in -meanRadius..meanRadius) {
                val neighbourRow = centreRow + rowOffset
                if (neighbourRow < 0 || neighbourRow >= cellsDown) continue
                for (columnOffset in -meanRadius..meanRadius) {
                    var neighbourColumn = (centreColumn + columnOffset) % cellsAcross
                    if (neighbourColumn < 0) neighbourColumn += cellsAcross
                    sum += carved[neighbourRow * cellsAcross + neighbourColumn].coerceAtLeast(0f)
                    neighbour++
                }
            }
            val walked = sum / neighbour - carved[cell].coerceAtLeast(0f)
            concavity[cell] = walked
            concavityScale += if (walked < 0f) -walked.toDouble() else walked.toDouble()
        }
        val concavityNorm = (3.0 * concavityScale / sheetCells).toFloat().coerceAtLeast(1e-6f)

        // The basin score, and the quantile that nominates the candidate hollows.
        // A quantile from a histogram rather than a fixed threshold on the noise:
        // a fixed one makes one seed a lake district and the next one bare, for no reason anybody
        // could point at on the map.
        val raw = FloatArray(cellCount)
        for (cell in 0 until cellCount) {
            if (!sheet[cell]) continue
            val column = (cell % cellsAcross).toFloat()
            val row = (cell / cellsAcross).toFloat()
            val neighbour = 0.5f + 0.5f * basinNoise.fbm(
                column * period / cellsAcross, row * period / cellsDown, 3, period, period
            )
            raw[cell] = neighbour + glaciation.sheetConcavity * (concavity[cell] / concavityNorm).coerceIn(-1f, 1f)
        }
        // Smoothed before it is cut, and this is not cosmetic. The concavity of eroded ground
        // varies cell to cell, so an unsmoothed score threshold shatters every blob into a spray
        // of three- and four-cell fragments, all of them below [GlaciationConfig.minLakeAreaKm2]
        // and none of them a lake — measured on seed 718106, a fifth of the cells the quantile
        // chose survived into a basin. A basin is a landform, so the field that chooses it is read
        // at a landform's scale.
        val score = FloatArray(cellCount)
        val blurRadius = SCORE_BLUR
        for (cell in 0 until cellCount) {
            if (!sheet[cell]) continue
            val centreColumn = cell % cellsAcross
            val centreRow = cell / cellsAcross
            var sum = 0f
            var neighbour = 0
            for (rowOffset in -blurRadius..blurRadius) {
                val neighbourRow = centreRow + rowOffset
                if (neighbourRow < 0 || neighbourRow >= cellsDown) continue
                for (columnOffset in -blurRadius..blurRadius) {
                    var neighbourColumn = (centreColumn + columnOffset) % cellsAcross
                    if (neighbourColumn < 0) neighbourColumn += cellsAcross
                    val other = neighbourRow * cellsAcross + neighbourColumn
                    if (!sheet[other]) continue
                    sum += raw[other]
                    neighbour++
                }
            }
            score[cell] = if (neighbour > 0) sum / neighbour else raw[cell]
        }
        val histogram = IntArray(SCORE_BINS)
        for (cell in 0 until cellCount) {
            if (!sheet[cell]) continue
            val bin = (((score[cell] + 1f) / 3f) * SCORE_BINS).toInt().coerceIn(0, SCORE_BINS - 1)
            histogram[bin]++
        }
        // The quantile is asked for more ground than the budget will pay for — the candidates, not
        // the answer. Which of them actually becomes a lake is settled below, by how strongly the
        // score chose them and by what is left of the allowance, so that the knob means the share
        // of the province that ends up *under water* rather than the share that was considered.
        val wanted = (budget * SELECTION_HEADROOM).toInt().coerceIn(minCells, sheetCells)
        var bin = SCORE_BINS - 1
        var running = 0
        while (bin > 0 && running + histogram[bin] <= wanted) {
            running += histogram[bin]
            bin--
        }
        val cut = (bin.toFloat() / SCORE_BINS) * 3f - 1f

        // Blobs: four-connected, because eight-connectivity would thread two separate basins
        // together through a single diagonal touch and a chain of those is exactly the artefact
        // this stage is being rid of.
        val blob = IntArray(cellCount) { -1 }
        val queue = IntArray(cellCount)
        val blobFirst = ArrayList<Int>()
        val blobCount = ArrayList<Int>()
        val blobScore = ArrayList<Float>()
        for (start in 0 until cellCount) {
            if (!sheet[start] || blob[start] >= 0 || score[start] < cut) continue
            val id = blobCount.size
            var head = 0
            var tail = 0
            queue[tail++] = start
            blob[start] = id
            var count = 0
            var sum = 0.0
            while (head < tail) {
                val walked = queue[head++]
                count++
                sum += score[walked].toDouble()
                forEachOrthogonal(cellsAcross, cellsDown, walked % cellsAcross, walked / cellsAcross) { neighbour ->
                    if (blob[neighbour] < 0 && sheet[neighbour] && score[neighbour] >= cut) {
                        blob[neighbour] = id
                        queue[tail++] = neighbour
                    }
                }
            }
            blobFirst.add(start)
            blobCount.add(count)
            blobScore.add((sum / count).toFloat())
        }
        if (blobCount.isEmpty()) return ScourTally(0, 0)

        val offset = IntArray(blobCount.size + 1)
        for (blobId in blobCount.indices) offset[blobId + 1] = offset[blobId] + blobCount[blobId]
        val fill = IntArray(blobCount.size)
        val packed = IntArray(offset[blobCount.size])
        for (cell in 0 until cellCount) {
            val blobId = blob[cell]
            if (blobId < 0) continue
            packed[offset[blobId] + fill[blobId]] = cell
            fill[blobId] = fill[blobId] + 1
        }

        // The strongest hollows first, so that what the allowance buys is the lake country the
        // score is surest about rather than whichever blob the grid was walked into first. The
        // blob's own starting cell breaks a tie, so nothing here depends on the walk order either.
        val ranked = Array(blobCount.size) { it }
        ranked.sortWith(compareByDescending<Int> { blobScore[it] }.thenBy { blobFirst[it] })

        val members = IntArray(cellCount)
        val kept = IntArray(cellCount)
        val visited = IntArray(cellCount) { -1 }
        val rimDistance = FloatArray(cellCount)
        val heap = LongMinHeap(maxCells * 8 + 16)
        var spent = 0
        var cells = 0
        var basins = 0
        for (blobId in ranked) {
            if (budget - spent < minCells) break
            val blobCells = offset[blobId + 1] - offset[blobId]
            if (blobCells < minCells) continue
            for (index in 0 until blobCells) members[index] = packed[offset[blobId] + index]
            // The same area cap the valley basins take, by height rather than by ring, so a scour
            // basin's outline is a contour of the shield it sits on. See [keepLowestCells].
            val count = keepLowestCells(
                cellsAcross, cellsDown, members, blobCells, blob, blobId, visited,
                carved, maxCells, heap, kept
            )
            if (count < minCells || spent + count > budget) continue
            // The same last check on the finished shape the valley basins get. The noise that
            // nominates a blob has no bearing of its own, but a thresholded field can still leave
            // a filament, and one measured 17 cells long and one wide on seed 7 at 1024: a line of
            // water drawn along nothing, which is the artefact this whole stage keeps producing.
            if (isStraightBar(kept, count, cellsAcross)) continue
            rimDistanceCells(cellsAcross, cellsDown, kept, count, blob, blobId, rimDistance)
            cells += cutBowl(
                cellsAcross, cellsDown, kept, count, blob, blobId, depth, isLand, carved, rimDistance
            )
            for (index in 0 until count) basinFloor[kept[index]] = firstBasinNumber + basins
            spent += count
            basins++
        }
        return ScourTally(cells, basins)
    }

    /**
     * How much more ground the basin score is allowed to nominate than the budget can pay for.
     *
     * The quantile has to offer a choice or the ranking below it has nothing to choose between;
     * offer too much and the cut sinks into ground the score never really liked. Two and a half
     * times the allowance is enough that the lakes are the hollows the noise and the terrain agree
     * on.
     */
    private const val SELECTION_HEADROOM = 2.5f

    /**
     * How many cells of its own flow line a sheet cell averages its hummock noise over.
     *
     * The elongation of the lineations, in cells, and it is a landform's figure rather than a
     * knob: Earth's drumlins run 1-2 km long against 400-600 m wide and its megaflutes run tens of
     * kilometres, so a length-to-width ratio between three and ten covers the family (Clark,
     * Hughes and others, *Size and shape characteristics of drumlins*, Quat. Sci. Rev. 28, 2009,
     * measure a mean elongation of 2.9 with a long tail past 10). The noise's own features are
     * [GlaciationConfig.sheetBasinCycles] * 3 cycles across the map, which at 1024 is a few cells,
     * so six cells of averaging puts the ratio in the middle of that range at every grid this
     * program draws — and the ratio is what a reader sees, not the length.
     */
    private const val STREAMLINE_CELLS = 6

    /** The four orthogonal neighbours, wrapping east-west and stopping at the poles. */
    private inline fun forEachOrthogonal(
        cellsAcross: Int,
        cellsDown: Int,
        column: Int,
        row: Int,
        body: (Int) -> Unit
    ) {
        var left = column - 1
        if (left < 0) left += cellsAcross
        var right = column + 1
        if (right >= cellsAcross) right -= cellsAcross
        body(row * cellsAcross + left)
        body(row * cellsAcross + right)
        if (row > 0) body((row - 1) * cellsAcross + column)
        if (row < cellsDown - 1) body((row + 1) * cellsAcross + column)
    }

    /** Bins the basin score is quantiled in; the score itself runs -1..2. */
    private const val SCORE_BINS = 512

    /**
     * Radius, in cells, of the blur applied to the basin score before it is cut.
     *
     * A fixed few cells rather than a scaled length, because what it is smoothing away is
     * cell-scale noise in the concavity — an artefact of the grid the field is sampled on, which
     * is the same size whatever the map is.
     */
    private const val SCORE_BLUR = 2

    /**
     * How high a bar of till stands, given the ice that left it.
     *
     * Only half of it scales with the glacier, which is not the same rule the scouring follows and
     * is deliberate. A dam's job is to hold water back, and the water it holds is the height it
     * stands *above* the bed — so a bar scaled the whole way down with the ice stands a thousandth
     * of the elevation range, which is a quarter of [LakesConfig.minDepth] and therefore no lake at
     * all. Measured on seed 42 before this: four and a half thousand recessional moraines and a
     * hundred and fifty of the resulting ponds one or two cells across. Till supply does not fall
     * away with ice volume the way erosive power does; a small glacier in a small valley leaves a
     * small valley's worth of moraine, which is plenty to dam it.
     */
    private fun till(height: Float, strength: Float): Float = height * (0.5f + 0.5f * strength)

    /**
     * How far up the sides the ice reaches, in cells. Wider for a bigger glacier, but slowly — the
     * root again, since a trough draining four times the ground is about twice the valley.
     */
    private fun valleyHalfWidth(carving: Carving, strength: Float): Float =
        (carving.valleyWidthCells * sqrt(strength)).coerceAtLeast(1f)

    /**
     * The flow direction at a glacier cell, as a unit vector, for orienting its cross-section.
     *
     * Downstream where there is a downstream; at a snout, the direction the ice arrived from, so
     * the terminal cross-section lies the same way as the one before it rather than collapsing.
     */
    private fun flowOf(
        cell: Int,
        directions: IntArray,
        glacier: BooleanArray,
        cellsAcross: Int,
        cellsDown: Int
    ): Long {
        val receiver = directions[cell]
        if (receiver >= 0) return step(cell, receiver, cellsAcross)
        var from = -1
        FlowRouting.forEachNeighbour(cellsAcross, cellsDown, cell % cellsAcross, cell / cellsAcross) { neighbour ->
            if (from < 0 && glacier[neighbour] && directions[neighbour] == cell) from = neighbour
        }
        return if (from >= 0) step(from, cell, cellsAcross) else pack(1f, 0f)
    }

    /** The unit vector from [from] to [to], packed into a long so no object is allocated. */
    private fun step(from: Int, to: Int, cellsAcross: Int): Long {
        var columnOffset = (to % cellsAcross) - (from % cellsAcross)
        if (columnOffset > cellsAcross / 2) columnOffset -= cellsAcross
        if (columnOffset < -cellsAcross / 2) columnOffset += cellsAcross
        val rowOffset = (to / cellsAcross) - (from / cellsAcross)
        val length = sqrt((columnOffset * columnOffset + rowOffset * rowOffset).toFloat()).coerceAtLeast(1e-6f)
        return pack(columnOffset / length, rowOffset / length)
    }

    private fun pack(column: Float, row: Float): Long =
        (column.toRawBits().toLong() shl 32) or (row.toRawBits().toLong() and 0xFFFFFFFFL)

    private fun unpackX(packed: Long): Float = Float.fromBits((packed ushr 32).toInt())

    private fun unpackY(packed: Long): Float = Float.fromBits(packed.toInt())

    /**
     * Lowers the line of cells *across* the flow toward [floorValue]: fully over the middle
     * [flatShare] of the half-width and on a parabola out to the untouched ground at the rim, and
     * only as far as the ice standing [iceThickness] over the bed actually reaches.
     *
     * One cell thick along the flow — [ALONG_REACH] either side of the perpendicular — so that what
     * a cell writes is its own cross-section and nothing of its neighbours'. Every glacier cell
     * stamps one, and consecutive stamps tile the trough between them.
     *
     * See [cutShare] for the second half-width, the one measured in height rather than in cells,
     * and [GlaciationConfig.valleyIceThicknessMetres] for why it had to exist.
     */
    private fun swath(
        cellsAcross: Int,
        cellsDown: Int,
        centre: Int,
        flow: Long,
        radius: Float,
        flatShare: Float,
        floorValue: Float,
        iceThickness: Float,
        isLand: BooleanArray,
        original: FloatArray,
        carved: FloatArray
    ) {
        val axisX = unpackX(flow)
        val axisY = unpackY(flow)
        val centreColumn = centre % cellsAcross
        val centreRow = centre / cellsAcross
        val span = radius.toInt() + 1
        val flat = radius * flatShare.coerceIn(0f, 0.9f)
        val wall = (radius - flat).coerceAtLeast(1e-4f)
        for (rowOffset in -span..span) {
            val neighbourRow = centreRow + rowOffset
            if (neighbourRow < 0 || neighbourRow >= cellsDown) continue
            for (columnOffset in -span..span) {
                val along = columnOffset * axisX + rowOffset * axisY
                if (along > ALONG_REACH || along < -ALONG_REACH) continue
                val across = kotlin.math.abs(columnOffset * -axisY + rowOffset * axisX)
                if (across > radius) continue
                var neighbourColumn = (centreColumn + columnOffset) % cellsAcross
                if (neighbourColumn < 0) neighbourColumn += cellsAcross
                val cell = neighbourRow * cellsAcross + neighbourColumn
                if (!isLand[cell]) continue
                val here = original[cell]
                if (here <= floorValue) continue
                val share = cutShare(across, flat, wall, here - floorValue, iceThickness)
                val target = here - (here - floorValue) * share
                if (target < carved[cell]) carved[cell] = target
            }
        }
    }

    /**
     * How much of the way down to the bed a cell of a glacier's cross-section is taken, from zero
     * to one.
     *
     * Two things share it and they multiply, because the ice has to be both *beside* a cell and
     * *over* it to plane it:
     *
     *  - how far across the section the cell lies. One over the flat floor, then a parabola out to
     *    nothing at the rim, which is the U.
     *  - how deeply the cell is buried, as `1 - aboveTheBed / iceThickness`. The floor of the
     *    valley is under the whole thickness and is planed; the shoulder is barely under the ice
     *    and is barely touched; rock standing above the ice surface is not touched at all.
     *
     * The second is what was missing, and its absence is what made the slab the author found on
     * 364673: the cut was decided by distance alone, so a cell of the section lying on a ridge a
     * kilometre above the valley floor was planed down to the valley floor, and a hundred and
     * fifty kilometres of cross-section came out at one height with a straight edge at the flow's
     * own grid bearing. It also means the finished floor is never *exactly* level — a cell still
     * standing `d` above the bed keeps `d^2 / iceThickness` of that — so the ground's own texture
     * survives the planing instead of being replaced by a plate.
     */
    private fun cutShare(
        across: Float,
        flat: Float,
        wall: Float,
        aboveTheBed: Float,
        iceThickness: Float
    ): Float {
        val underTheIce = (1f - aboveTheBed / iceThickness).coerceIn(0f, 1f)
        if (across <= flat) return underTheIce
        val towardTheRim = (across - flat) / wall
        return underTheIce * (1f - towardTheRim * towardTheRim)
    }

    /**
     * Lowers a disc toward [floorValue] on a parabola: the floor at the centre, the original ground
     * untouched at the rim.
     *
     * The cross-section this leaves is the U, and it is written as a minimum against [carved] so
     * that a cell inside two glaciers' reach takes the deeper of the two answers whichever order
     * they arrive in. [original] rather than [carved] is read on the way in, so a stamp is a pure
     * function of the surface this stage was handed.
     */
    private fun bowl(
        cellsAcross: Int,
        cellsDown: Int,
        centre: Int,
        radius: Float,
        flatShare: Float,
        floorValue: Float,
        iceThickness: Float,
        isLand: BooleanArray,
        original: FloatArray,
        carved: FloatArray
    ) {
        val centreColumn = centre % cellsAcross
        val centreRow = centre / cellsAcross
        val span = radius.toInt() + 1
        val flat = radius * flatShare.coerceIn(0f, 0.9f)
        val wall = (radius - flat).coerceAtLeast(1e-4f)
        for (rowOffset in -span..span) {
            val neighbourRow = centreRow + rowOffset
            if (neighbourRow < 0 || neighbourRow >= cellsDown) continue
            for (columnOffset in -span..span) {
                val distance = sqrt((columnOffset * columnOffset + rowOffset * rowOffset).toFloat())
                if (distance > radius) continue
                var neighbourColumn = (centreColumn + columnOffset) % cellsAcross
                if (neighbourColumn < 0) neighbourColumn += cellsAcross
                val cell = neighbourRow * cellsAcross + neighbourColumn
                if (!isLand[cell]) continue
                val here = original[cell]
                if (here <= floorValue) continue
                // Flat across the middle, then the parabola up to the rim, and all of it shared by
                // how deeply the cell lies under the ice. The flat is the whole difference between
                // a U and a V, and it is not a cosmetic one: a floor that comes to a point one cell
                // wide is a floor no over-deepened basin can hold water in, because
                // [LakesConfig.minLakeAreaKm2] asks for a body of water rather than a puddle. The
                // burial share is why the headwall above a cirque survives it: see [cutShare].
                val share = cutShare(distance, flat, wall, here - floorValue, iceThickness)
                val target = here - (here - floorValue) * share
                if (target < carved[cell]) carved[cell] = target
            }
        }
    }

    /**
     * A bar of till laid *across* the valley, thickest on the axis and thinning to nothing at the
     * valley sides.
     *
     * Across, for the same reason the cross-section is: a round heap of the same radius reaches
     * back up the trough as far as it reaches sideways, and fills in the basin it was supposed to
     * dam. Taken as a maximum where two bars overlap rather than a sum, so nothing depends on the
     * order they were laid in.
     */
    private fun bar(
        cellsAcross: Int,
        cellsDown: Int,
        centre: Int,
        flow: Long,
        radius: Float,
        height: Float,
        isLand: BooleanArray,
        moraine: FloatArray
    ) {
        if (height <= 0f) return
        val axisX = unpackX(flow)
        val axisY = unpackY(flow)
        val centreColumn = centre % cellsAcross
        val centreRow = centre / cellsAcross
        val span = radius.toInt() + 1
        for (rowOffset in -span..span) {
            val neighbourRow = centreRow + rowOffset
            if (neighbourRow < 0 || neighbourRow >= cellsDown) continue
            for (columnOffset in -span..span) {
                val along = columnOffset * axisX + rowOffset * axisY
                if (along > ALONG_REACH || along < -ALONG_REACH) continue
                val across = kotlin.math.abs(columnOffset * -axisY + rowOffset * axisX)
                if (across > radius) continue
                var neighbourColumn = (centreColumn + columnOffset) % cellsAcross
                if (neighbourColumn < 0) neighbourColumn += cellsAcross
                val cell = neighbourRow * cellsAcross + neighbourColumn
                if (!isLand[cell]) continue
                val acrossFraction = across / radius
                val thickness = height * (1f - acrossFraction * acrossFraction)
                if (thickness > moraine[cell]) moraine[cell] = thickness
            }
        }
    }

    /**
     * Deepens the water in front of a marine snout, deepest at the mouth and fading out over
     * [reachCells] cells, so the shelf beyond stands as the sill.
     *
     * Breadth-first over water from the receiving cell, stamped rather than collected in a set, for
     * the same reason the delta fan is: no hash order may reach the terrain.
     *
     * @return how much sea floor was taken out, for the tally.
     */
    private fun fjord(
        cellsAcross: Int,
        cellsDown: Int,
        start: Int,
        reach: Int,
        depth: Float,
        isLand: BooleanArray,
        carved: FloatArray,
        stamp: IntArray,
        id: Int,
        queue: IntArray,
        queueDistance: IntArray
    ): Double {
        if (depth <= 0f || isLand[start]) return 0.0
        var removed = 0.0
        var head = 0
        var tail = 0
        queue[tail] = start
        queueDistance[tail] = 0
        tail++
        stamp[start] = id

        while (head < tail) {
            val cell = queue[head]
            val distance = queueDistance[head]
            head++

            val target = -depth * (1f - distance.toFloat() / (reach + 1f))
            if (target < carved[cell]) {
                removed += (carved[cell] - target).toDouble()
                carved[cell] = target
            }

            if (distance >= reach) continue
            val centreColumn = cell % cellsAcross
            val centreRow = cell / cellsAcross
            for (rowOffset in -1..1) {
                val neighbourRow = centreRow + rowOffset
                if (neighbourRow < 0 || neighbourRow >= cellsDown) continue
                for (columnOffset in -1..1) {
                    if (columnOffset == 0 && rowOffset == 0) continue
                    var neighbourColumn = (centreColumn + columnOffset) % cellsAcross
                    if (neighbourColumn < 0) neighbourColumn += cellsAcross
                    val neighbour = neighbourRow * cellsAcross + neighbourColumn
                    if (stamp[neighbour] == id || isLand[neighbour]) continue
                    stamp[neighbour] = id
                    if (tail < queue.size) {
                        queue[tail] = neighbour
                        queueDistance[tail] = distance + 1
                        tail++
                    }
                }
            }
        }
        return removed
    }

    /**
     * The smallest bend of the crust under an ice load that is worth writing into the terrain, in
     * metres. See [iceLoadDepression].
     */
    private const val MIN_MEANINGFUL_BEND_METRES = 1f

    /**
     * How far past an ice sheet's margin its own bend is carried, in flexural parameters.
     *
     * Three, which is where a plate's answer to a load has fallen to a few per cent of its peak:
     * the first zero crossing of the flexure of a line load is at three quarters of a flexural
     * parameter and the forebulge at one, and by three there is nothing left to draw. See
     * [iceLoadDepression].
     */
    private const val FLEXURAL_PARAMETERS_OF_REACH = 3.0

    /** The least thickness any glacier is credited with. See where [GlacialMass] is filled in. */
    private const val MIN_THICKNESS = 0.5f

    /**
     * How far along the flow a cross-section reaches, in cells.
     *
     * Wide enough that a diagonal step's section still meets its neighbour's — the perpendicular to
     * a diagonal passes between cells — and narrow enough that a basin cannot write over the step
     * below it, which is the whole reason the section is oriented at all.
     */
    private const val ALONG_REACH = 0.75f

    private const val DIAGONAL_STEP_CELLS = 1.41421356f

    /** Neighbours differ by one row *and* one column only when the step was diagonal. */
    private fun isDiagonal(from: Int, to: Int, width: Int): Boolean =
        (from / width != to / width) && (from % width != to % width)
}
