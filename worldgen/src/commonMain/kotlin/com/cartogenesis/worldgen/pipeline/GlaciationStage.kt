package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.GlaciationConfig
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.noise.PerlinNoise
import kotlin.math.sqrt

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
    /** Candidate basins refused: too narrow, too straight, too small, or the budget was spent. */
    val basinsTooNarrow: Int,
    val basinsTooStraight: Int,
    val basinsTooSmall: Int,
    val basinsOverBudget: Int,
    /** Cells cut into a scour basin, and how many separate basins they form. */
    val scourCells: Int,
    val scourBasins: Int,
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
 * Before H2 the mask was cruder: a provisional mean annual temperature at or below freezing, which
 * cannot tell a snowy highland from a frozen desert and so froze every cold interior on the map.
 * That rule is still here, behind `ClimateConfig.snowBalance`, as the control the guard needs.
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
 * [GlaciationConfig.valleyRelief] of the land's range. Above that line the ice is channelled and
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
 * comb back. So a basin is now a **region**: the ground within a trough half-width of the ice's
 * path, opened — eroded by a cell and dilated back — so that what survives is a union of
 * three-by-three blocks and is three cells wide everywhere it exists; refused outright if it has no
 * such block in it, if the ice walked a straight D8 line to make it, or if the finished shape is a
 * bar; peeled inward if it is larger than any lake on Earth in proportion; and paid for out of a
 * fixed allowance of standing water shared with the sheet. The trough itself is still cut cell by
 * cell — a valley *is* a line — but nothing that holds water is. See [cutBasins].
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

    fun apply(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        /**
         * The provisional snow balance, in millimetres of water equivalent a year, or null to fall
         * back to the pre-H2 temperature mask. See [snowBalance] and
         * [ClimateStage.provisionalSnowBalance].
         */
        snowBalance: FloatField? = null
    ): SeaLevelResult = apply(config, sea, snowBalance, onBudget = null)

    /**
     * @param onBudget handed this stage's mass tally on the way out. An observer, like erosion's:
     *   passing it changes nothing about the world.
     */
    internal fun apply(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        snowBalance: FloatField?,
        onBudget: ((GlacialMass) -> Unit)?
    ): SeaLevelResult {
        val cfg = config.glaciation
        // The same object back, so every `===` guard downstream sees an untouched sea stage and
        // the whole world is reproduced bit for bit. This is the control the guard needs.
        if (!cfg.enabled || sea.landCellCount == 0) return sea

        val w = config.width
        val h = config.height
        val size = w * h
        val isLand = sea.isLand
        val relative = sea.relativeElevation.data

        // Where the ice is. Two rules, and which one applies is `ClimateConfig.snowBalance`:
        //
        //  - the balance, when the engine has run a provisional climate and handed one over. A
        //    cell is frozen where a year's snow outlasts a year's melt, so a cold dry interior is
        //    bare ground with no glacier to carve it and a wet maritime highland carries ice a
        //    long way down its flanks. That is the H2 mask.
        //  - the pre-H2 rule otherwise: a provisional annual mean at or below
        //    [GlaciationConfig.freezingC], which called every cold place frozen whether or not
        //    any snow ever reached it.
        var frozenCount = 0
        val frozen = BooleanArray(size)
        if (snowBalance != null) {
            for (i in 0 until size) {
                if (isLand[i] && snowBalance.data[i] > 0f) {
                    frozen[i] = true
                    frozenCount++
                }
            }
        } else {
            val temperature = ClimateStage.buildTemperature(config, sea)
            for (i in 0 until size) {
                if (isLand[i] && temperature.data[i] <= cfg.freezingC) {
                    frozen[i] = true
                    frozenCount++
                }
            }
        }
        if (frozenCount == 0) return sea

        // The ice follows the water's own network. A glacier occupies the valley a river cut before
        // the cold came, which is both what really happens and what makes the result legible: the
        // trough is where the map already had a valley.
        val filled = FlowRouting.fillDepressions(w, h, isLand, sea.relativeElevation)
        val directions = FlowRouting.flowDirections(w, h, isLand, sea.relativeElevation, filled)
        val order = FlowRouting.drainageOrder(w, h, isLand, directions, sea.landCellCount)

        // How much frozen ground drains through each cell — the ice's own catchment, as distinct
        // from the water's. Accumulated along [FlowRouting.drainageOrder] rather than with
        // [FlowRouting.accumulate], for the reason that order exists: the height-sorted walk can
        // hand a cell its load after it has already been passed, and a lost contribution here is a
        // glacier that stops for no reason.
        val ice = FloatArray(size)
        for (i in 0 until size) if (frozen[i]) ice[i] = 1f
        for (k in order.indices) {
            val i = order[k]
            val t = directions[i]
            if (t >= 0 && isLand[t]) ice[t] += ice[i]
        }

        // The elevation range within a couple of trough-widths, which is the question "is there a
        // valley here?" asked of every cell at once. Water counts at the waterline rather than at
        // its own depth, so a coast standing over deep ocean does not read as relief it does not
        // have, while a headland standing over the sea does.
        val landRange = landRange(isLand, relative)
        val reliefRadius = (cfg.reliefWindow * cfg.valleyWidthCells).toInt().coerceIn(2, 64)
        val relief = localRelief(w, h, relative, reliefRadius)
        val channelThreshold = cfg.valleyRelief * landRange
        var channelledCells = 0
        val channelled = BooleanArray(size)
        for (i in 0 until size) {
            if (isLand[i] && relief[i] >= channelThreshold) {
                channelled[i] = true
                if (frozen[i]) channelledCells++
            }
        }

        // The denominator is the frozen ground, not the land: see [GlaciationConfig.minCatchment].
        val frozenLand = frozenCount.toFloat()
        val glacier = BooleanArray(size)
        val strength = FloatArray(size)
        // Cells travelled since the ice left frozen ground. A snout sits below its own snowline —
        // that is what an ablation zone is — so the trough is allowed this far past the mask and
        // not one cell further.
        val runOut = IntArray(size) { Int.MAX_VALUE }

        // Which ice field each cell's ice came out of, and how big that field is. A glacier has to
        // be one of the few paths draining *its own* ice field, not merely a large number against
        // the planet's total: see [GlaciationConfig.trunkCatchment].
        val field = frozenFields(w, h, frozen)
        val fieldOf = IntArray(size) { field.id[it] }
        for (k in order.indices) {
            val i = order[k]
            val t = directions[i]
            if (fieldOf[i] >= 0 && t >= 0 && isLand[t] && fieldOf[t] < 0) fieldOf[t] = fieldOf[i]
        }

        // Everything that could carry a trough: enough ice, close enough to the frozen ground, and
        // standing in channelled country. Whether it actually does is the length test below.
        val candidate = BooleanArray(size)
        for (k in order.indices) {
            val i = order[k]
            if (frozen[i]) runOut[i] = 0
            val share = ice[i] / frozenLand
            val f = fieldOf[i]
            val fieldShare = if (f >= 0) ice[i] / field.size[f].toFloat() else 0f
            if (share >= cfg.minCatchment && fieldShare >= cfg.trunkCatchment &&
                runOut[i] <= cfg.runOutCells && channelled[i]
            ) {
                candidate[i] = true
            }
            // Propagated for every cell that the ice has reached rather than only for the ones
            // that qualified, so a trough interrupted by one flat or thin-iced cell can still find
            // its snout on the other side.
            val t = directions[i]
            if (runOut[i] != Int.MAX_VALUE && t >= 0 && isLand[t]) {
                val next = if (frozen[t]) 0 else runOut[i] + 1
                if (next < runOut[t]) runOut[t] = next
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
        val upstream = IntArray(size)
        val upLength = FloatArray(size)
        val head = IntArray(size) { -1 }
        for (k in order.indices) {
            val i = order[k]
            if (!candidate[i]) continue
            if (upstream[i] == 0) {
                upstream[i] = 1
                head[i] = i
            }
            val t = directions[i]
            if (t >= 0 && candidate[t] && upstream[i] + 1 > upstream[t]) {
                upstream[t] = upstream[i] + 1
                upLength[t] = upLength[i] + (if (isDiagonal(i, t, w)) DIAGONAL else 1f)
                head[t] = head[i]
            }
        }
        val downstream = IntArray(size)
        val downLength = FloatArray(size)
        val snout = IntArray(size) { -1 }
        for (k in order.indices.reversed()) {
            val i = order[k]
            if (!candidate[i]) continue
            downstream[i] = 1
            downLength[i] = 0f
            snout[i] = i
            val t = directions[i]
            if (t >= 0 && candidate[t] && downstream[t] + 1 > downstream[i]) {
                downstream[i] = downstream[t] + 1
                downLength[i] = downLength[t] + (if (isDiagonal(i, t, w)) DIAGONAL else 1f)
                snout[i] = snout[t]
            }
        }

        var glacierCells = 0
        for (i in 0 until size) {
            if (!candidate[i]) continue
            if (upstream[i] + downstream[i] - 1 < cfg.minTroughLengthCells) continue
            if (sinuosity(head[i], snout[i], upLength[i] + downLength[i], w) < cfg.minSinuosity) {
                continue
            }
            glacier[i] = true
            glacierCells++
            // Ice thickness, as a proxy: a glacier draining twenty times the ground is not twenty
            // times as deep, so the root rather than the share itself.
            //
            // Floored, and the floor is load-bearing. Everything this stage cuts is scaled by this
            // number, the over-deepening included, and an over-deepening scaled to a fifth is a
            // basin shallower than [LakesConfig.minDepth] — which is to say a basin that the river
            // stage will not see as a lake, on a glacier that was carved anyway.
            strength[i] = sqrt((ice[i] / frozenLand) / cfg.fullCatchment).coerceIn(MIN_THICKNESS, 1f)
        }

        // No two glaciers of the same bearing within a trough of each other. Ice that close together
        // is one glacier, and a rank of them is the comb.
        val suppressed = suppressParallel(cfg, w, h, glacier, directions, ice, strength, order)
        glacierCells -= suppressed.cells

        // The other regime's ground, settled here rather than after the carving because the water
        // budget below is measured against it. Everything frozen that the valley machinery was not
        // allowed to touch is under sheet ice, and sheet ice does not follow the drainage net — so
        // nothing the scour reads is `directions`, `order` or `ice`. That is the whole point: there
        // is no flow grid in it to show through.
        var sheetCells = 0
        val sheet = BooleanArray(size)
        for (i in 0 until size) {
            if (frozen[i] && !channelled[i] && !glacier[i]) {
                sheet[i] = true
                sheetCells++
            }
        }

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
        val minBasinCells = (cfg.minLakeShareOfMap * size).toInt().coerceAtLeast(4)
        val maxBasinCells = (cfg.maxLakeShareOfMap * size).toInt().coerceAtLeast(minBasinCells)
        val lakeBudget =
            (cfg.sheetLakeShare * maxOf(sheetCells, frozenCount / 4).toFloat()).toInt()

        // How far down the staircase each cell is.
        //
        // Two things advance it, and they simply add: how far the ice has run (in cells, over
        // [GlaciationConfig.basinSpacingCells]) and how far it has fallen (in elevation, over
        // [GlaciationConfig.basinDrop]). A reach ends when the sum passes the next whole number, so
        // whichever runs out first ends it — a long flat reach on a plain, a short one on a
        // mountainside. Measured from the head of the longest feeder rather than the nearest, so a
        // tributary joining halfway down does not restart the count.
        val spacing = cfg.basinSpacingCells.coerceAtLeast(2f)
        val drop = cfg.basinDrop.coerceAtLeast(1e-4f)
        val progress = FloatArray(size)
        for (k in order.indices) {
            val i = order[k]
            if (!glacier[i]) continue
            val t = directions[i]
            if (t >= 0 && glacier[t]) {
                val step = progress[i] +
                    (if (isDiagonal(i, t, w)) DIAGONAL else 1f) / spacing +
                    (relative[i] - relative[t]).coerceAtLeast(0f) / drop
                if (step > progress[t]) progress[t] = step
            }
        }

        // The long profile, in reaches: each one an over-deepened basin followed by a step. Real
        // troughs are stepped like this — the ice scours hardest where it is confined and thickest
        // and rides over the harder bars between — and it is the step at the lower end of a reach
        // that makes the basin a lake rather than merely a dip.
        val reach = IntArray(size)
        for (i in 0 until size) if (glacier[i]) reach[i] = progress[i].toInt()

        // Carving proper. Every stamp is computed from the *original* surface and combined with a
        // minimum, so overlapping glaciers compose in any order and the result does not depend on
        // which cell was visited first.
        val carved = relative.copyOf()

        // The trough, and *only* the trough: a graded U following the ground down, cut by the
        // ordinary amount. The over-deepened basins used to be cut here too, cell by cell along the
        // flow path, each reach's floor flattened to the lowest ground in it — and that is what
        // built the comb. A basin cut per cell along a D8 path is exactly as wide as the path is,
        // which on a rank of parallel gullies down a piedmont is a rank of parallel straight bars
        // of water. Basins are now regions, cut below, and they are the only thing that holds
        // water.
        for (i in 0 until size) {
            if (!glacier[i]) continue
            // Wall to wall: the ice lowers the whole cross-section toward its bed on a parabola,
            // untouched at the rim and flat at the floor. That parabola is the U.
            //
            // *Across* the flow and one cell thick along it, which is not a detail. Stamped as a
            // disc instead — the obvious thing, and what this did first — the floor reaches a
            // valley-width in every direction, including forward down the long profile, and
            // quietly planes off whatever it was supposed to stand above. A cross-section is a
            // cross-section.
            val bed = (relative[i] - cfg.deepening * strength[i]).coerceAtLeast(0f)
            swath(
                w, h, i, flowOf(i, directions, glacier, w, h),
                valleyHalfWidth(cfg, strength[i]), cfg.floorShare, bed,
                isLand, relative, carved
            )
        }

        // Cirques: the armchair hollow a glacier bites out of the mountain it starts on. Every head
        // of the ice network gets one, which is what puts tarns at the tops of the valleys.
        val fedByIce = BooleanArray(size)
        for (i in 0 until size) {
            if (!glacier[i]) continue
            val t = directions[i]
            if (t >= 0 && glacier[t]) fedByIce[t] = true
        }
        var cirques = 0
        for (i in 0 until size) {
            if (!glacier[i] || fedByIce[i]) continue
            cirques++
            val depth = cfg.cirqueDepth * maxOf(strength[i], 0.5f)
            bowl(
                w, h, i, cfg.cirqueRadiusCells.coerceAtLeast(1f), cfg.floorShare,
                (relative[i] - depth).coerceAtLeast(0f), isLand, relative, carved
            )
        }

        // The over-deepened basins, as regions rather than as cells along a line. See [cutBasins].
        val basins = cutBasins(
            cfg, w, h, isLand, frozen, glacier, directions, order, reach, progress,
            strength, ice, carved, minBasinCells, maxBasinCells, lakeBudget
        )

        // The sheet's basins get whatever the valleys left of the allowance.
        var scourCells = 0
        var scourBasins = 0
        val sheetBudget = (lakeBudget - basins.cells).coerceAtLeast(0)
        if (cfg.sheetScour && sheetCells >= minBasinCells) {
            val tally = scour(
                config, cfg, w, h, sheet, sheetCells, isLand, relative, landRange, carved,
                minBasinCells, maxBasinCells, sheetBudget
            )
            scourCells = tally.cells
            scourBasins = tally.basins
        }

        if (glacierCells == 0 && scourCells == 0) return sea

        var excavated = 0.0
        for (i in 0 until size) {
            if (isLand[i]) excavated += (relative[i] - carved[i]).toDouble()
        }

        // Terminal moraines. The one thing ice gives back: everything it was dragging is dumped
        // where it stops, in a ridge across the valley mouth, and the ridge dams the trough behind
        // it. Taken as a maximum rather than a sum where two snouts overlap, so the result cannot
        // depend on the order they were laid in.
        val moraine = FloatArray(size)
        var moraines = 0
        var riegels = 0
        for (i in 0 until size) {
            if (!glacier[i]) continue
            val t = directions[i]
            val ends = t < 0 || !glacier[t]
            if (ends) {
                // A snout in the sea leaves no ridge: the till goes straight into the water. Only
                // a glacier that melts on land builds a dam.
                if (t >= 0 && !isLand[t]) continue
                moraines++
                bar(
                    w, h, i, flowOf(i, directions, glacier, w, h),
                    valleyHalfWidth(cfg, strength[i]) * 1.15f,
                    till(cfg.moraineHeight, strength[i]), isLand, moraine
                )
            } else if (reach[t] != reach[i] && cfg.riegelHeight > 0f) {
                // A recessional moraine, at the lower end of every reach. Off by default now that a
                // basin is a region closed by its own rim: see [GlaciationConfig.riegelHeight] for
                // why a bar of till one cell thick across the flow could only add straight water.
                riegels++
                bar(
                    w, h, i, flowOf(i, directions, glacier, w, h),
                    valleyHalfWidth(cfg, strength[i]),
                    till(cfg.riegelHeight, strength[i]), isLand, moraine
                )
            }
        }
        var deposited = 0.0
        for (i in 0 until size) {
            if (moraine[i] <= 0f) continue
            // What the field actually took, never what it was asked to take: the terrain is float
            // and a small enough increment rounds away, exactly as the hydraulic pass's budget has
            // to allow for.
            val before = carved[i]
            carved[i] = (before.toDouble() + moraine[i].toDouble()).toFloat()
            deposited += carved[i].toDouble() - before.toDouble()
        }

        // The drowned half of a fjord: the basin the ice scoured below the waterline, with the
        // shelf left standing beyond it as the sill. Water only, and after the shelf remap, so
        // there is nothing left to re-flatten it.
        var submarine = 0.0
        if (cfg.fjords && cfg.fjordReachCells > 0) {
            val stamp = IntArray(size)
            val queue = IntArray((2 * cfg.fjordReachCells + 1) * (2 * cfg.fjordReachCells + 1))
            val queueDistance = IntArray(queue.size)
            var mouthId = 0
            for (i in 0 until size) {
                if (!glacier[i]) continue
                val t = directions[i]
                if (t < 0 || isLand[t]) continue
                submarine += fjord(
                    w, h, t, cfg.fjordReachCells, cfg.fjordDepth * strength[i],
                    isLand, carved, stamp, ++mouthId, queue, queueDistance
                )
            }
        }

        onBudget?.invoke(
            GlacialMass(
                frozenCells = frozenCount,
                channelledCells = channelledCells,
                glacierCells = glacierCells,
                trunks = suppressed.trunks,
                parallelCellsDropped = suppressed.cells,
                sheetCells = sheetCells,
                lakeBudget = lakeBudget,
                basinCells = basins.cells,
                basins = basins.basins,
                basinsTooNarrow = basins.tooNarrow,
                basinsTooStraight = basins.tooStraight,
                basinsTooSmall = basins.tooSmall,
                basinsOverBudget = basins.overBudget,
                scourCells = scourCells,
                scourBasins = scourBasins,
                cirques = cirques,
                moraines = moraines,
                riegels = riegels,
                excavated = excavated,
                deposited = deposited,
                submarine = submarine
            )
        )

        return sea.copy(relativeElevation = FloatField(w, h, carved))
    }

    /** What the scour did, for the tally. */
    private class ScourTally(val cells: Int, val basins: Int)

    /** What the valley regime's basins came to, for the tally. */
    private class BasinTally(
        val cells: Int,
        val basins: Int,
        val tooNarrow: Int,
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
     *  3. **The footprint**, everything within the trough's half-width of the path that no stronger
     *     basin has already claimed — so two basins never share ground and a weak one beside a
     *     strong one simply does not exist.
     *  4. **Opened**: eroded by one cell and dilated back. Whatever survives is a union of three-by-
     *     three blocks, so it is nowhere narrower than three cells *by construction* rather than by
     *     a threshold that could be argued with. A one-cell filament off the side of a trough has
     *     no such block in it and vanishes; a basin that is nothing but filament has no core at all
     *     and is refused.
     *  5. **Sized**: under [GlaciationConfig.minLakeShareOfMap] of the map it is not worth cutting;
     *     over [GlaciationConfig.maxLakeShareOfMap] it is peeled inward ring by ring until it fits.
     *  6. **Not a bar**, as a last check on the finished shape: nothing two cells or less across and
     *     four or more long on any grid bearing survives. After the opening this cannot fire, which
     *     is the point of asserting it — a shape guard that can only be satisfied by construction.
     *  7. **Within budget**, shared with the sheet: see [GlaciationConfig.sheetLakeShare].
     *
     * The floor is then cut from the lowest cell of the region *and its rim*, so the basin is
     * closed the same way a scour basin is, and saucered by distance from the rim so it is not a
     * slab. No till is needed to dam it, which is why [GlaciationConfig.riegelHeight] is now zero.
     */
    private fun cutBasins(
        cfg: GlaciationConfig,
        w: Int,
        h: Int,
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
        budget: Int
    ): BasinTally {
        val size = w * h
        if (budget < minCells) return BasinTally(0, 0, 0, 0, 0, 0)

        val seed = BooleanArray(size)
        for (i in 0 until size) {
            if (!glacier[i] || !frozen[i]) continue
            if (progress[i] - reach[i] < cfg.basinShare) seed[i] = true
        }

        // One stretch of ice per reach, tributaries included, joined along the flow.
        val segment = IntArray(size) { -1 }
        val queue = IntArray(size)
        val segIce = ArrayList<Float>()
        val segCount = ArrayList<Int>()
        val segFirst = ArrayList<Int>()
        for (start in 0 until size) {
            if (!seed[start] || segment[start] >= 0) continue
            val id = segCount.size
            var head = 0
            var tail = 0
            queue[tail++] = start
            segment[start] = id
            var count = 0
            var maxIce = 0f
            while (head < tail) {
                val c = queue[head++]
                count++
                if (ice[c] > maxIce) maxIce = ice[c]
                val t = directions[c]
                if (t >= 0 && seed[t] && segment[t] < 0 && reach[t] == reach[c]) {
                    segment[t] = id
                    queue[tail++] = t
                }
                FlowRouting.forEachNeighbour(w, h, c % w, c / w) { nb ->
                    if (seed[nb] && segment[nb] < 0 && directions[nb] == c &&
                        reach[nb] == reach[c]
                    ) {
                        segment[nb] = id
                        queue[tail++] = nb
                    }
                }
            }
            segIce.add(maxIce)
            segCount.add(count)
            segFirst.add(start)
        }
        if (segCount.isEmpty()) return BasinTally(0, 0, 0, 0, 0, 0)

        val offset = IntArray(segCount.size + 1)
        for (b in segCount.indices) offset[b + 1] = offset[b] + segCount[b]
        val fill = IntArray(segCount.size)
        val packed = IntArray(offset[segCount.size])
        for (i in 0 until size) {
            val s = segment[i]
            if (s < 0) continue
            packed[offset[s] + fill[s]] = i
            fill[s] = fill[s] + 1
        }

        // How far the ice walked inside its own stretch, and where it started, so the straightness
        // of the stretch can be asked the same question [GlaciationConfig.minSinuosity] asks of a
        // whole trough. Accumulated along the drainage order, which runs heads first.
        val chain = FloatArray(size)
        val chainHead = IntArray(size) { -1 }
        for (k in order.indices) {
            val i = order[k]
            val s = segment[i]
            if (s < 0) continue
            if (chainHead[i] < 0) chainHead[i] = i
            val t = directions[i]
            if (t >= 0 && segment[t] == s) {
                val d = chain[i] + (if (isDiagonal(i, t, w)) DIAGONAL else 1f)
                if (d > chain[t]) {
                    chain[t] = d
                    chainHead[t] = chainHead[i]
                }
            }
        }

        // Strongest first, so the ground goes to the glacier that carries the most ice and the
        // tie is broken by position rather than by the order the grid was walked in.
        val ranked = Array(segCount.size) { it }
        ranked.sortWith(compareByDescending<Int> { segIce[it] }.thenBy { segFirst[it] })

        val own = IntArray(size) { -1 }
        val core = IntArray(size) { -1 }
        val region = IntArray(size) { -1 }
        val inset = IntArray(size)
        val footList = IntArray(size)
        val regionList = IntArray(size)

        var spent = 0
        var basins = 0
        var tooNarrow = 0
        var tooStraight = 0
        var tooSmall = 0
        var overBudget = 0

        for (s in ranked) {
            if (budget - spent < minCells) {
                overBudget++
                continue
            }
            val from = offset[s]
            val until = offset[s + 1]

            var outlet = packed[from]
            for (k in from until until) if (chain[packed[k]] > chain[outlet]) outlet = packed[k]
            if (sinuosity(chainHead[outlet], outlet, chain[outlet], w) < cfg.minSinuosity) {
                tooStraight++
                continue
            }

            var footCount = 0
            for (k in from until until) {
                val c = packed[k]
                val radius = valleyHalfWidth(cfg, strength[c])
                val cx = c % w
                val cy = c / w
                val span = radius.toInt() + 1
                val r2 = radius * radius
                for (dy in -span..span) {
                    val ny = cy + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -span..span) {
                        if ((dx * dx + dy * dy).toFloat() > r2) continue
                        var nx = (cx + dx) % w
                        if (nx < 0) nx += w
                        val n = ny * w + nx
                        if (!isLand[n] || own[n] >= 0) continue
                        own[n] = s
                        footList[footCount++] = n
                    }
                }
            }

            // Eroded by one: the cells whose whole three-by-three block is inside the footprint.
            var coreCount = 0
            for (k in 0 until footCount) {
                val c = footList[k]
                val cy = c / w
                if (cy == 0 || cy == h - 1) continue
                val cx = c % w
                var solid = true
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        var nx = (cx + dx) % w
                        if (nx < 0) nx += w
                        if (own[(cy + dy) * w + nx] != s) solid = false
                    }
                }
                if (solid) {
                    core[c] = s
                    coreCount++
                }
            }
            if (coreCount == 0) {
                for (k in 0 until footCount) own[footList[k]] = -1
                tooNarrow++
                continue
            }

            // Dilated back: the union of those blocks, which is three cells wide everywhere.
            var regionCount = 0
            for (k in 0 until footCount) {
                val c = footList[k]
                if (core[c] != s) continue
                val cx = c % w
                val cy = c / w
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        var nx = (cx + dx) % w
                        if (nx < 0) nx += w
                        val n = (cy + dy) * w + nx
                        if (region[n] != s) {
                            region[n] = s
                            regionList[regionCount++] = n
                        }
                    }
                }
            }

            if (regionCount < minCells) {
                for (k in 0 until footCount) own[footList[k]] = -1
                tooSmall++
                continue
            }
            regionCount =
                peelToCap(w, h, regionList, regionCount, region, s, inset, queue, maxCells)
            if (regionCount < minCells) {
                for (k in 0 until footCount) own[footList[k]] = -1
                tooSmall++
                continue
            }
            if (isStraightBar(regionList, regionCount, w)) {
                for (k in 0 until footCount) own[footList[k]] = -1
                tooStraight++
                continue
            }
            if (spent + regionCount > budget) {
                for (k in 0 until footCount) own[footList[k]] = -1
                overBudget++
                continue
            }

            var thickness = 0f
            for (k in from until until) thickness += strength[packed[k]]
            thickness /= (until - from).toFloat()
            val depth = (cfg.deepening + cfg.overDeepening) * thickness
            cutSaucer(w, h, regionList, regionCount, region, s, depth, isLand, carved, inset)
            spent += regionCount
            basins++
        }
        return BasinTally(spent, basins, tooNarrow, tooStraight, tooSmall, overBudget)
    }

    /**
     * Whether a body of cells is a narrow straight run along one of the four grid bearings.
     *
     * The shape the author has now reported three times: a bar of water a cell or two across and
     * five to fifteen long, lying at exactly 0, 45 or 90 degrees, in ranks. Measured as the extent
     * of the body along and across each bearing, so a bar is caught whatever its length and a blob
     * that merely happens to be elongated is not.
     */
    private fun isStraightBar(cells: IntArray, count: Int, w: Int): Boolean {
        if (count < BAR_LENGTH) return false
        val anchor = cells[0] % w
        val uMin = IntArray(4) { Int.MAX_VALUE }
        val uMax = IntArray(4) { Int.MIN_VALUE }
        val vMin = IntArray(4) { Int.MAX_VALUE }
        val vMax = IntArray(4) { Int.MIN_VALUE }
        for (k in 0 until count) {
            val c = cells[k]
            val y = c / w
            var dx = (c % w) - anchor
            if (dx > w / 2) dx -= w
            if (dx < -w / 2) dx += w
            val x = anchor + dx
            // East, south-east, south, north-east: the along coordinate and the across coordinate
            // of each. On a diagonal the along coordinate steps by two per cell, which is why the
            // length is halved and the width is not.
            val u = intArrayOf(x, x + y, y, x - y)
            val v = intArrayOf(y, x - y, x, x + y)
            for (a in 0 until 4) {
                if (u[a] < uMin[a]) uMin[a] = u[a]
                if (u[a] > uMax[a]) uMax[a] = u[a]
                if (v[a] < vMin[a]) vMin[a] = v[a]
                if (v[a] > vMax[a]) vMax[a] = v[a]
            }
        }
        for (a in 0 until 4) {
            val diagonal = a == 1 || a == 3
            val length =
                if (diagonal) (uMax[a] - uMin[a]) / 2 + 1 else uMax[a] - uMin[a] + 1
            val across = vMax[a] - vMin[a] + 1
            if (across <= BAR_WIDTH && length >= BAR_LENGTH) return true
        }
        return false
    }

    /**
     * How far inside the region each of its cells lies, as a breadth-first walk inward from the
     * rim: the distance transform the saucered floor and the peeling both read.
     */
    private fun insetDistance(
        w: Int,
        h: Int,
        cells: IntArray,
        count: Int,
        stamp: IntArray,
        marker: Int,
        inset: IntArray,
        queue: IntArray
    ) {
        var tail = 0
        for (k in 0 until count) {
            val c = cells[k]
            var edge = false
            forEachOrthogonal(w, h, c % w, c / w) { n -> if (stamp[n] != marker) edge = true }
            if (edge) {
                inset[c] = 0
                queue[tail++] = c
            } else {
                inset[c] = -1
            }
        }
        var head = 0
        while (head < tail) {
            val c = queue[head++]
            val d = inset[c] + 1
            forEachOrthogonal(w, h, c % w, c / w) { n ->
                if (stamp[n] == marker && inset[n] < 0) {
                    inset[n] = d
                    queue[tail++] = n
                }
            }
        }
    }

    /**
     * Shrinks a basin to at most [cap] cells by peeling whole rings off its rim, and leaves the
     * distance transform of whatever is left behind it.
     *
     * Rings rather than a truncation, because what has to survive is a *basin*: taking the first
     * `cap` cells of a list would leave a ragged half of one. What the peeling models is real
     * enough — the ice scoured the whole hollow, and the water stands only in the deepest part of
     * it — so the ground outside the cap keeps its scour and loses its lake.
     */
    private fun peelToCap(
        w: Int,
        h: Int,
        cells: IntArray,
        count: Int,
        stamp: IntArray,
        marker: Int,
        inset: IntArray,
        queue: IntArray,
        cap: Int
    ): Int {
        insetDistance(w, h, cells, count, stamp, marker, inset, queue)
        if (count <= cap) return count
        var ring = 0
        var kept = count
        while (kept > cap) {
            val next = ring + 1
            var n = 0
            for (k in 0 until count) if (inset[cells[k]] >= next) n++
            if (n == 0) break
            ring = next
            kept = n
        }
        if (ring == 0) return count
        var n = 0
        for (k in 0 until count) {
            val c = cells[k]
            if (inset[c] >= ring) cells[n++] = c else stamp[c] = -1
        }
        insetDistance(w, h, cells, n, stamp, marker, inset, queue)
        return n
    }

    /**
     * Cuts a basin floor out of a region: level below the lowest cell of the region *and its rim*,
     * so no cell around it can drain it, and saucered by distance from that rim so the floor is a
     * bowl rather than a slab dropped into the ground.
     *
     * @return how many cells the cut actually lowered.
     */
    private fun cutSaucer(
        w: Int,
        h: Int,
        cells: IntArray,
        count: Int,
        stamp: IntArray,
        marker: Int,
        depth: Float,
        isLand: BooleanArray,
        carved: FloatArray,
        inset: IntArray
    ): Int {
        var base = Float.MAX_VALUE
        for (k in 0 until count) {
            val c = cells[k]
            if (carved[c] < base) base = carved[c]
            // Land only. A basin that reaches the coast has the sea for a neighbour, and reading
            // the sea floor as its rim would say the floor has to be cut below the ocean — which,
            // clamped at the waterline, plates the whole basin flat at sea level.
            forEachOrthogonal(w, h, c % w, c / w) { n ->
                if (stamp[n] != marker && isLand[n] && carved[n] < base) base = carved[n]
            }
        }
        var lowered = 0
        for (k in 0 until count) {
            val c = cells[k]
            val f = (inset[c].coerceAtLeast(0) / 2f).coerceIn(0f, 1f)
            val target = (base - depth * (0.45f + 0.55f * f)).coerceAtLeast(0f)
            if (target < carved[c]) {
                carved[c] = target
                lowered++
            }
        }
        return lowered
    }

    /** Widest a body of water may be and still count as a bar, in cells. */
    private const val BAR_WIDTH = 2

    /** Shortest run along one bearing that makes a narrow body a bar rather than a blob. */
    private const val BAR_LENGTH = 4

    /** The connected fields of frozen ground, and how many cells each holds. */
    private class FrozenFields(val id: IntArray, val size: IntArray, val count: Int)

    /** What the parallel rule threw away, for the tally. */
    private class Suppression(val cells: Int, val trunks: Int, val kept: Int)

    /**
     * Connected regions of frozen ground, eight-connected, so an ice cap and a cold massif on the
     * far side of the world are told apart. Eight rather than four, because a snowfield joined only
     * at a corner is still one snowfield.
     */
    private fun frozenFields(w: Int, h: Int, frozen: BooleanArray): FrozenFields {
        val size = w * h
        val id = IntArray(size) { -1 }
        val sizes = ArrayList<Int>()
        val queue = IntArray(size)
        for (start in 0 until size) {
            if (!frozen[start] || id[start] >= 0) continue
            val label = sizes.size
            var headIdx = 0
            var tail = 0
            queue[tail++] = start
            id[start] = label
            var count = 0
            while (headIdx < tail) {
                val c = queue[headIdx++]
                count++
                val cx = c % w
                val cy = c / w
                FlowRouting.forEachNeighbour(w, h, cx, cy) { n ->
                    if (frozen[n] && id[n] < 0) {
                        id[n] = label
                        queue[tail++] = n
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
    private fun sinuosity(head: Int, snout: Int, length: Float, w: Int): Float {
        if (head < 0 || snout < 0 || length <= 0f) return 0f
        var dx = (snout % w) - (head % w)
        if (dx > w / 2) dx -= w
        if (dx < -w / 2) dx += w
        val dy = (snout / w) - (head / w)
        val straight = sqrt((dx * dx + dy * dy).toFloat())
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
        cfg: GlaciationConfig,
        w: Int,
        h: Int,
        glacier: BooleanArray,
        directions: IntArray,
        ice: FloatArray,
        strength: FloatArray,
        order: IntArray
    ): Suppression {
        val size = w * h
        if (cfg.parallelSpacing <= 0f) return Suppression(0, countTrunks(w, h, glacier, directions), 0)

        // Every bearing read once, before anything is dropped, so that what one cell is compared
        // against cannot depend on which cells were dropped before it.
        val orientX = FloatArray(size)
        val orientY = FloatArray(size)
        var n = 0
        for (i in 0 until size) {
            if (!glacier[i]) continue
            n++
            val flow = flowOf(i, directions, glacier, w, h)
            val ox = unpackX(flow)
            val oy = unpackY(flow)
            // Doubled angle: (x, y) -> (x^2 - y^2, 2xy), so a bearing and its reverse agree and a
            // dot product of 0.707 between two of them is 22.5 degrees between the originals.
            orientX[i] = ox * ox - oy * oy
            orientY[i] = 2f * ox * oy
        }
        if (n == 0) return Suppression(0, 0, 0)

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
        val dominant = IntArray(size) { -1 }
        for (i in 0 until size) {
            if (!glacier[i]) continue
            var best = -1
            var bestIce = -1f
            FlowRouting.forEachNeighbour(w, h, i % w, i / w) { nb ->
                if (glacier[nb] && directions[nb] == i && ice[nb] > bestIce) {
                    bestIce = ice[nb]
                    best = nb
                }
            }
            dominant[i] = best
        }
        val branch = IntArray(size) { -1 }
        val branchIce = ArrayList<Float>()
        val branchStart = ArrayList<Int>()
        val branchCount = ArrayList<Int>()
        for (k in order.indices) {
            val i = order[k]
            if (!glacier[i]) continue
            val d = dominant[i]
            val b = if (d >= 0 && branch[d] >= 0) {
                branch[d]
            } else {
                branchIce.add(0f)
                branchStart.add(i)
                branchCount.add(0)
                branchIce.size - 1
            }
            branch[i] = b
            branchCount[b] = branchCount[b] + 1
            if (ice[i] > branchIce[b]) branchIce[b] = ice[i]
        }

        // Cells of each branch, laid out contiguously so no per-branch allocation is needed.
        val offset = IntArray(branchCount.size + 1)
        for (b in branchCount.indices) offset[b + 1] = offset[b] + branchCount[b]
        val fill = IntArray(branchCount.size)
        val packed = IntArray(n)
        for (i in 0 until size) {
            val b = branch[i]
            if (b < 0) continue
            packed[offset[b] + fill[b]] = i
            fill[b] = fill[b] + 1
        }

        // Strongest first, and the branch's first cell breaks a tie, so nothing here depends on the
        // order the grid happened to be walked in.
        val ranked = Array(branchCount.size) { it }
        ranked.sortWith(compareByDescending<Int> { branchIce[it] }.thenBy { branchStart[it] })

        val claimed = BooleanArray(size)
        val claimX = FloatArray(size)
        val claimY = FloatArray(size)
        var dropped = 0
        for (b in ranked) {
            val from = offset[b]
            val until = offset[b + 1]
            var conflict = 0
            for (k in from until until) {
                val c = packed[k]
                if (!claimed[c]) continue
                if (orientX[c] * claimX[c] + orientY[c] * claimY[c] >= PARALLEL_COS) conflict++
            }
            if (conflict * 3 > until - from) {
                for (k in from until until) glacier[packed[k]] = false
                dropped += until - from
                continue
            }
            for (k in from until until) {
                val c = packed[k]
                stampClaim(
                    w, h, c, valleyHalfWidth(cfg, strength[c]) * cfg.parallelSpacing,
                    orientX[c], orientY[c], claimed, claimX, claimY
                )
            }
        }
        return Suppression(dropped, countTrunks(w, h, glacier, directions), 0)
    }

    /** How many separate glaciers are left, counting a chain and its feeders as one. */
    private fun countTrunks(w: Int, h: Int, glacier: BooleanArray, directions: IntArray): Int {
        val size = w * h
        val seen = BooleanArray(size)
        val queue = IntArray(size)
        var trunks = 0
        for (start in 0 until size) {
            if (!glacier[start] || seen[start]) continue
            trunks++
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true
            while (head < tail) {
                val c = queue[head++]
                val t = directions[c]
                if (t >= 0 && glacier[t] && !seen[t]) {
                    seen[t] = true
                    queue[tail++] = t
                }
                FlowRouting.forEachNeighbour(w, h, c % w, c / w) { nb ->
                    if (glacier[nb] && !seen[nb] && directions[nb] == c) {
                        seen[nb] = true
                        queue[tail++] = nb
                    }
                }
            }
        }
        return trunks
    }

    /** Marks the ground a glacier occupies, with the orientation it occupies it at. */
    private fun stampClaim(
        w: Int,
        h: Int,
        centre: Int,
        radius: Float,
        orientX: Float,
        orientY: Float,
        claimed: BooleanArray,
        claimX: FloatArray,
        claimY: FloatArray
    ) {
        val cx = centre % w
        val cy = centre / w
        val span = radius.toInt() + 1
        val r2 = radius * radius
        for (dy in -span..span) {
            val ny = cy + dy
            if (ny < 0 || ny >= h) continue
            for (dx in -span..span) {
                if ((dx * dx + dy * dy).toFloat() > r2) continue
                var nx = (cx + dx) % w
                if (nx < 0) nx += w
                val c = ny * w + nx
                // First claim stands, and the strongest trunk claims first.
                if (claimed[c]) continue
                claimed[c] = true
                claimX[c] = orientX
                claimY[c] = orientY
            }
        }
    }

    /** Twenty-two and a half degrees, as a dot product of doubled-angle orientations. */
    private const val PARALLEL_COS = 0.7071f

    /**
     * The span of the land, so every depth in [GlaciationConfig] can be a fraction of it.
     *
     * [SeaLevelStage] already normalises land to 0..1, so this is very nearly 1 — but it is
     * computed rather than assumed, because a config that never reaches the highest ground would
     * otherwise silently rescale every cut this stage makes.
     */
    private fun landRange(isLand: BooleanArray, relative: FloatArray): Float {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (i in relative.indices) {
            if (!isLand[i]) continue
            val v = relative[i]
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        return if (hi <= lo) 1f else hi - lo
    }

    /**
     * The elevation range inside a square window of [radius] cells around every cell: relief, as
     * the one measurement that separates ground a glacier is channelled by from ground it is not.
     *
     * Water counts at the waterline rather than at its own depth. A cliff standing over the sea is
     * relief and a shallow shelf beside a plain is not, and reading the sea floor would make every
     * coast look alpine.
     *
     * Separable, and each pass is a monotonic-deque sliding window, so the cost is a constant per
     * cell rather than the square of the radius — which matters, because at export resolution the
     * window is fifty cells across and the naive form would be a second of wall clock on its own.
     * East-west wraps, north-south clamps, exactly as the rest of the pipeline treats the grid.
     */
    private fun localRelief(w: Int, h: Int, relative: FloatArray, radius: Int): FloatArray {
        val size = w * h
        val surface = FloatArray(size) { relative[it].coerceAtLeast(0f) }
        val span = 2 * radius + 1
        val rowMin = FloatArray(size)
        val rowMax = FloatArray(size)

        val rowPad = FloatArray(w + 2 * radius)
        val deque = IntArray(maxOf(rowPad.size, h + 2 * radius))
        for (y in 0 until h) {
            val base = y * w
            for (k in rowPad.indices) {
                var nx = (k - radius) % w
                if (nx < 0) nx += w
                rowPad[k] = surface[base + nx]
            }
            slide(rowPad, span, true, deque) { o, v -> rowMax[base + o] = v }
            slide(rowPad, span, false, deque) { o, v -> rowMin[base + o] = v }
        }

        val out = FloatArray(size)
        val colPad = FloatArray(h + 2 * radius)
        val colHi = FloatArray(h)
        for (x in 0 until w) {
            for (k in colPad.indices) colPad[k] = rowMax[(k - radius).coerceIn(0, h - 1) * w + x]
            slide(colPad, span, true, deque) { o, v -> colHi[o] = v }
            for (k in colPad.indices) colPad[k] = rowMin[(k - radius).coerceIn(0, h - 1) * w + x]
            slide(colPad, span, false, deque) { o, v -> out[o * w + x] = colHi[o] - v }
        }
        return out
    }

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
        for (i in src.indices) {
            while (tail > head &&
                (if (wantMax) src[deque[tail - 1]] <= src[i] else src[deque[tail - 1]] >= src[i])
            ) {
                tail--
            }
            deque[tail++] = i
            if (deque[head] <= i - span) head++
            if (i >= span - 1) emit(out++, src[deque[head]])
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
     * [GlaciationConfig.minLakeShareOfMap] of the map is passed over, and one over
     * [GlaciationConfig.maxLakeShareOfMap] is peeled inward until it fits, because a world map has
     * no business carrying a lake several times the size of Superior.
     *
     * Every basin is closed *by construction*. Its floor is cut from the lowest ground in the blob
     * **and its one-cell rim**, so no cell on the rim can be lower than the floor and the river
     * stage is guaranteed to find a depression rather than a channel. Even the shallowest part of
     * the floor stands [GlaciationConfig.sheetBasinDepth] × 0.45 below that rim, comfortably clear
     * of [LakesConfig.minDepth].
     */
    private fun scour(
        config: WorldGenConfig,
        cfg: GlaciationConfig,
        w: Int,
        h: Int,
        sheet: BooleanArray,
        sheetCells: Int,
        isLand: BooleanArray,
        relative: FloatArray,
        landRange: Float,
        carved: FloatArray,
        minCells: Int,
        maxCells: Int,
        budget: Int
    ): ScourTally {
        val size = w * h
        // Seeded off the world seed, so the pattern is this world's and is reproduced exactly on
        // any platform that runs the same arithmetic.
        val basinNoise = PerlinNoise(config.seed * 31L + 0x91E5L)
        val hummockNoise = PerlinNoise(config.seed * 31L + 0x27C3L)
        val period = cfg.sheetBasinCycles.toInt().coerceAtLeast(2)
        val hummockPeriod = (period * 3).coerceAtLeast(4)

        // The hummocky lowering first, so that the basins below are cut against ground that has
        // already been planed and their rims cannot turn out to be lower than their floors.
        val lowering = cfg.sheetLowering * landRange
        if (lowering > 0f) {
            for (i in 0 until size) {
                if (!sheet[i]) continue
                val x = (i % w).toFloat()
                val y = (i / w).toFloat()
                val n = 0.5f + 0.5f * hummockNoise.fbm(
                    x * hummockPeriod / w, y * hummockPeriod / h, 3, hummockPeriod, hummockPeriod
                )
                val target = (relative[i] - lowering * (0.35f + 0.65f * n)).coerceAtLeast(0f)
                if (target < carved[i]) carved[i] = target
            }
        }

        val depth = cfg.sheetBasinDepth * landRange
        if (depth <= 0f || budget < minCells) return ScourTally(0, 0)

        // How hollow each cell is against the ground around it, and the scale of that hollowness
        // over the whole province, so the concavity term can be weighed against a 0..1 noise
        // without a constant nobody could justify.
        val meanRadius = (cfg.valleyWidthCells * 0.5f).toInt().coerceIn(2, 24)
        val concavity = FloatArray(size)
        var concavityScale = 0.0
        for (i in 0 until size) {
            if (!sheet[i]) continue
            val cx = i % w
            val cy = i / w
            var sum = 0f
            var n = 0
            for (dy in -meanRadius..meanRadius) {
                val ny = cy + dy
                if (ny < 0 || ny >= h) continue
                for (dx in -meanRadius..meanRadius) {
                    var nx = (cx + dx) % w
                    if (nx < 0) nx += w
                    sum += carved[ny * w + nx].coerceAtLeast(0f)
                    n++
                }
            }
            val c = sum / n - carved[i].coerceAtLeast(0f)
            concavity[i] = c
            concavityScale += if (c < 0f) -c.toDouble() else c.toDouble()
        }
        val concavityNorm = (3.0 * concavityScale / sheetCells).toFloat().coerceAtLeast(1e-6f)

        // The basin score, and the quantile that nominates the candidate hollows.
        // A quantile from a histogram rather than a fixed threshold on the noise:
        // a fixed one makes one seed a lake district and the next one bare, for no reason anybody
        // could point at on the map.
        val raw = FloatArray(size)
        for (i in 0 until size) {
            if (!sheet[i]) continue
            val x = (i % w).toFloat()
            val y = (i / w).toFloat()
            val n = 0.5f + 0.5f * basinNoise.fbm(x * period / w, y * period / h, 3, period, period)
            raw[i] = n + cfg.sheetConcavity * (concavity[i] / concavityNorm).coerceIn(-1f, 1f)
        }
        // Smoothed before it is cut, and this is not cosmetic. The concavity of eroded ground
        // varies cell to cell, so an unsmoothed score threshold shatters every blob into a spray
        // of three- and four-cell fragments, all of them below [GlaciationConfig.minLakeShareOfMap]
        // and none of them a lake — measured on seed 718106, a fifth of the cells the quantile
        // chose survived into a basin. A basin is a landform, so the field that chooses it is read
        // at a landform's scale.
        val score = FloatArray(size)
        val blurRadius = SCORE_BLUR
        for (i in 0 until size) {
            if (!sheet[i]) continue
            val cx = i % w
            val cy = i / w
            var sum = 0f
            var n = 0
            for (dy in -blurRadius..blurRadius) {
                val ny = cy + dy
                if (ny < 0 || ny >= h) continue
                for (dx in -blurRadius..blurRadius) {
                    var nx = (cx + dx) % w
                    if (nx < 0) nx += w
                    val j = ny * w + nx
                    if (!sheet[j]) continue
                    sum += raw[j]
                    n++
                }
            }
            score[i] = if (n > 0) sum / n else raw[i]
        }
        val histogram = IntArray(SCORE_BINS)
        for (i in 0 until size) {
            if (!sheet[i]) continue
            val bin = (((score[i] + 1f) / 3f) * SCORE_BINS).toInt().coerceIn(0, SCORE_BINS - 1)
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
        val blob = IntArray(size) { -1 }
        val queue = IntArray(size)
        val blobFirst = ArrayList<Int>()
        val blobCount = ArrayList<Int>()
        val blobScore = ArrayList<Float>()
        for (start in 0 until size) {
            if (!sheet[start] || blob[start] >= 0 || score[start] < cut) continue
            val id = blobCount.size
            var head = 0
            var tail = 0
            queue[tail++] = start
            blob[start] = id
            var count = 0
            var sum = 0.0
            while (head < tail) {
                val c = queue[head++]
                count++
                sum += score[c].toDouble()
                forEachOrthogonal(w, h, c % w, c / w) { n ->
                    if (blob[n] < 0 && sheet[n] && score[n] >= cut) {
                        blob[n] = id
                        queue[tail++] = n
                    }
                }
            }
            blobFirst.add(start)
            blobCount.add(count)
            blobScore.add((sum / count).toFloat())
        }
        if (blobCount.isEmpty()) return ScourTally(0, 0)

        val offset = IntArray(blobCount.size + 1)
        for (b in blobCount.indices) offset[b + 1] = offset[b] + blobCount[b]
        val fill = IntArray(blobCount.size)
        val packed = IntArray(offset[blobCount.size])
        for (i in 0 until size) {
            val b = blob[i]
            if (b < 0) continue
            packed[offset[b] + fill[b]] = i
            fill[b] = fill[b] + 1
        }

        // The strongest hollows first, so that what the allowance buys is the lake country the
        // score is surest about rather than whichever blob the grid was walked into first. The
        // blob's own starting cell breaks a tie, so nothing here depends on the walk order either.
        val ranked = Array(blobCount.size) { it }
        ranked.sortWith(compareByDescending<Int> { blobScore[it] }.thenBy { blobFirst[it] })

        val members = IntArray(size)
        val inset = IntArray(size)
        var spent = 0
        var cells = 0
        var basins = 0
        for (b in ranked) {
            if (budget - spent < minCells) break
            var count = offset[b + 1] - offset[b]
            if (count < minCells) continue
            for (k in 0 until count) members[k] = packed[offset[b] + k]
            count = peelToCap(w, h, members, count, blob, b, inset, queue, maxCells)
            if (count < minCells || spent + count > budget) continue
            cells += cutSaucer(w, h, members, count, blob, b, depth, isLand, carved, inset)
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

    /** The four orthogonal neighbours, wrapping east-west and stopping at the poles. */
    private inline fun forEachOrthogonal(w: Int, h: Int, x: Int, y: Int, body: (Int) -> Unit) {
        var left = x - 1
        if (left < 0) left += w
        var right = x + 1
        if (right >= w) right -= w
        body(y * w + left)
        body(y * w + right)
        if (y > 0) body((y - 1) * w + x)
        if (y < h - 1) body((y + 1) * w + x)
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
    private fun valleyHalfWidth(cfg: GlaciationConfig, strength: Float): Float =
        (cfg.valleyWidthCells * sqrt(strength)).coerceAtLeast(1f)

    /**
     * The flow direction at a glacier cell, as a unit vector, for orienting its cross-section.
     *
     * Downstream where there is a downstream; at a snout, the direction the ice arrived from, so
     * the terminal cross-section lies the same way as the one before it rather than collapsing.
     */
    private fun flowOf(
        i: Int,
        directions: IntArray,
        glacier: BooleanArray,
        w: Int,
        h: Int
    ): Long {
        val t = directions[i]
        if (t >= 0) return step(i, t, w)
        var from = -1
        FlowRouting.forEachNeighbour(w, h, i % w, i / w) { n ->
            if (from < 0 && glacier[n] && directions[n] == i) from = n
        }
        return if (from >= 0) step(from, i, w) else pack(1f, 0f)
    }

    /** The unit vector from [from] to [to], packed into a long so no object is allocated. */
    private fun step(from: Int, to: Int, w: Int): Long {
        var dx = (to % w) - (from % w)
        if (dx > w / 2) dx -= w
        if (dx < -w / 2) dx += w
        val dy = (to / w) - (from / w)
        val length = sqrt((dx * dx + dy * dy).toFloat()).coerceAtLeast(1e-6f)
        return pack(dx / length, dy / length)
    }

    private fun pack(x: Float, y: Float): Long =
        (x.toRawBits().toLong() shl 32) or (y.toRawBits().toLong() and 0xFFFFFFFFL)

    private fun unpackX(v: Long): Float = Float.fromBits((v ushr 32).toInt())

    private fun unpackY(v: Long): Float = Float.fromBits(v.toInt())

    /**
     * Lowers the line of cells *across* the flow toward [floorValue] on a parabola: flat over the
     * middle [flatShare] of the half-width, climbing to the untouched ground at the rim.
     *
     * One cell thick along the flow — [ALONG_REACH] either side of the perpendicular — so that what
     * a cell writes is its own cross-section and nothing of its neighbours'. Every glacier cell
     * stamps one, and consecutive stamps tile the trough between them.
     */
    private fun swath(
        w: Int,
        h: Int,
        centre: Int,
        flow: Long,
        radius: Float,
        flatShare: Float,
        floorValue: Float,
        isLand: BooleanArray,
        original: FloatArray,
        carved: FloatArray
    ) {
        val fx = unpackX(flow)
        val fy = unpackY(flow)
        val cx = centre % w
        val cy = centre / w
        val span = radius.toInt() + 1
        val flat = radius * flatShare.coerceIn(0f, 0.9f)
        val wall = (radius - flat).coerceAtLeast(1e-4f)
        for (dy in -span..span) {
            val ny = cy + dy
            if (ny < 0 || ny >= h) continue
            for (dx in -span..span) {
                val along = dx * fx + dy * fy
                if (along > ALONG_REACH || along < -ALONG_REACH) continue
                val across = kotlin.math.abs(dx * -fy + dy * fx)
                if (across > radius) continue
                var nx = (cx + dx) % w
                if (nx < 0) nx += w
                val c = ny * w + nx
                if (!isLand[c]) continue
                val here = original[c]
                if (here <= floorValue) continue
                val target = if (across <= flat) {
                    floorValue
                } else {
                    val t = (across - flat) / wall
                    floorValue + (here - floorValue) * t * t
                }
                if (target < carved[c]) carved[c] = target
            }
        }
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
        w: Int,
        h: Int,
        centre: Int,
        radius: Float,
        flatShare: Float,
        floorValue: Float,
        isLand: BooleanArray,
        original: FloatArray,
        carved: FloatArray
    ) {
        val cx = centre % w
        val cy = centre / w
        val span = radius.toInt() + 1
        val flat = radius * flatShare.coerceIn(0f, 0.9f)
        val wall = (radius - flat).coerceAtLeast(1e-4f)
        for (dy in -span..span) {
            val ny = cy + dy
            if (ny < 0 || ny >= h) continue
            for (dx in -span..span) {
                val distance = sqrt((dx * dx + dy * dy).toFloat())
                if (distance > radius) continue
                var nx = (cx + dx) % w
                if (nx < 0) nx += w
                val c = ny * w + nx
                if (!isLand[c]) continue
                val here = original[c]
                if (here <= floorValue) continue
                // Flat across the middle, then the parabola up to the rim. The flat is the whole
                // difference between a U and a V, and it is not a cosmetic one: a floor that comes
                // to a point one cell wide is a floor no over-deepened basin can hold water in,
                // because [LakesConfig.minCells] asks for a body of water rather than a puddle.
                val target = if (distance <= flat) {
                    floorValue
                } else {
                    val t = (distance - flat) / wall
                    floorValue + (here - floorValue) * t * t
                }
                if (target < carved[c]) carved[c] = target
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
        w: Int,
        h: Int,
        centre: Int,
        flow: Long,
        radius: Float,
        height: Float,
        isLand: BooleanArray,
        moraine: FloatArray
    ) {
        if (height <= 0f) return
        val fx = unpackX(flow)
        val fy = unpackY(flow)
        val cx = centre % w
        val cy = centre / w
        val span = radius.toInt() + 1
        for (dy in -span..span) {
            val ny = cy + dy
            if (ny < 0 || ny >= h) continue
            for (dx in -span..span) {
                val along = dx * fx + dy * fy
                if (along > ALONG_REACH || along < -ALONG_REACH) continue
                val across = kotlin.math.abs(dx * -fy + dy * fx)
                if (across > radius) continue
                var nx = (cx + dx) % w
                if (nx < 0) nx += w
                val c = ny * w + nx
                if (!isLand[c]) continue
                val t = across / radius
                val thickness = height * (1f - t * t)
                if (thickness > moraine[c]) moraine[c] = thickness
            }
        }
    }

    /**
     * Deepens the water in front of a marine snout, deepest at the mouth and fading out over
     * [reach] cells, so the shelf beyond stands as the sill.
     *
     * Breadth-first over water from the receiving cell, stamped rather than collected in a set, for
     * the same reason the delta fan is: no hash order may reach the terrain.
     *
     * @return how much sea floor was taken out, for the tally.
     */
    private fun fjord(
        w: Int,
        h: Int,
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
            val c = queue[head]
            val d = queueDistance[head]
            head++

            val target = -depth * (1f - d.toFloat() / (reach + 1f))
            if (target < carved[c]) {
                removed += (carved[c] - target).toDouble()
                carved[c] = target
            }

            if (d >= reach) continue
            val cx = c % w
            val cy = c / w
            for (dy in -1..1) {
                val ny = cy + dy
                if (ny < 0 || ny >= h) continue
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    var nx = (cx + dx) % w
                    if (nx < 0) nx += w
                    val n = ny * w + nx
                    if (stamp[n] == id || isLand[n]) continue
                    stamp[n] = id
                    if (tail < queue.size) {
                        queue[tail] = n
                        queueDistance[tail] = d + 1
                        tail++
                    }
                }
            }
        }
        return removed
    }

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

    private const val DIAGONAL = 1.41421356f

    /** Neighbours differ by one row *and* one column only when the step was diagonal. */
    private fun isDiagonal(from: Int, to: Int, width: Int): Boolean =
        (from / width != to / width) && (from % width != to % width)
}
