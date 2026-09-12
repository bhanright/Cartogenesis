package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.ErosionConfig
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.sqrt

/**
 * What one hydraulic round moved, in the height units the field itself is kept in.
 *
 * The three tallies are the mass budget: every scrap of material the incision took off the land
 * either settled somewhere else on the land, or went out to sea and left the model. [fieldDrop] is
 * the same quantity measured independently — how much the summed height field actually fell over
 * the round — so a mistake in the bookkeeping cannot hide behind the bookkeeping.
 */
internal data class RoundMass(
    val incised: Double,
    val deposited: Double,
    val lostToSea: Double,
    val fieldDrop: Double,
    /** Of [incised], how much the outlet notches took. Zero when the notch is switched off. */
    val notched: Double = 0.0,
    /** How many cells the notches were cut into. */
    val notchCells: Int = 0,
    /** How many depressions the fill had to raise this round. */
    val basins: Int = 0,
    /** Cells in the largest of them, and how deep the fill stands over its lowest ground. */
    val largestBasinCells: Int = 0,
    val largestBasinDepth: Float = 0f,
    /** The rim cell that basin spills over, or -1 if it has none. */
    val largestBasinSpill: Int = -1,
    /** The deepest fill anywhere on the map, over any basin's lowest ground. */
    val deepestBasin: Float = 0f
)

/**
 * Cutting valleys with running water, and putting the spoil back down.
 *
 * Thermal erosion answers a question about rock — how steeply it can stand before it fails — and
 * gives mountains their flanks. It does not answer the question about water, which is why rivers
 * were finding their way down terrain their own flow had never shaped: they ran in whatever
 * hollows the noise happened to leave rather than in valleys they had cut.
 *
 * This is stream-power incision, the standard of landscape-evolution models: a cell lowers in
 * proportion to the square root of the area draining through it times the slope it sits on. The
 * feedback is the point. A channel that cuts down gathers more water next round, which cuts it
 * deeper still, and the divides between channels sharpen as the channels fall away from them. That
 * is where V-shaped valleys, dendritic drainage and ridge lines come from, and none of them can be
 * had by smoothing.
 *
 * The other half is where the spoil goes. A river is a conveyor, not a drain: it carries what it
 * cuts until the gradient slackens and it can no longer hold it, and then it lays it down. That is
 * the difference between a landscape of nothing but valleys and one with floodplains along its
 * lower trunks, alluvial fans where a range meets the plain, and — the visible prize — deltas where
 * the biggest rivers meet the sea. Sediment is carried down the same D8 network the incision walks,
 * in one pass, sources first, so a cell knows what its tributaries brought it before it decides
 * whether to cut or to settle.
 *
 * One honest simplification remains: rainfall is uniform, because the climate depends on the
 * terrain that has not been shaped yet, and beginning that circle somewhere means beginning it with
 * flat rain.
 */
internal object HydraulicErosion {

    /**
     * How far the depression-filled surface must stand above real ground before a cell counts as
     * standing water rather than a flat the epsilon-fill nudged.
     *
     * Deliberately the same figure as `LakesConfig.minDepth`, and deliberately a constant rather
     * than a read of that setting: the lakes section is chosen long after erosion runs, and having
     * erosion read it would mean adding `lakes` to erosion's reuse guard so that moving a river
     * setting re-cut every valley.
     */
    private const val POND_DEPTH = 0.004f

    /**
     * The fall per cell of a freshly cut breach, in the shoreline-relative units the routing works
     * in.
     *
     * A notch has to slope, or the D8 step out of the basin has nowhere to go and the next round's
     * fill turns the whole channel back into part of the lake. It does not have to slope by much:
     * ten times the epsilon the depression fill itself uses is enough to give every cell along the
     * breach a strictly lower neighbour, and small enough that over the longest breach the map
     * allows it is a rounding error against the depth of the water it is letting out.
     */
    private const val NOTCH_GRADIENT = 1e-5f

    /**
     * How many times the outlets are cut again on the finished surface, once the spoil has been
     * laid on the rock and the last relaxation has run.
     *
     * One, because the spoil is laid once: this is the round of cutting the deposition never got,
     * not a licence to keep going until nothing stands anywhere. Measured at three instead, on
     * seeds 7, 42, 1234 and 718106 at 512 with the ice off, it takes seed 42 from four lakes to
     * none at all and seed 7 from five to one — a world with no standing water outside the glaciated
     * north is as wrong as one paved with it.
     */
    private const val CLOSING_BREACHES = 1

    /**
     * @param provisionalSeaLevel the fraction of the world that will end up under water. Erosion
     *   runs before the sea level is chosen, but water needs somewhere to go, so it works to the
     *   level the sea *will* take.
     * @param onRound handed the mass budget for each round as it closes. Diagnostics only; nothing
     *   here reads it back, so it cannot affect the world.
     * @param relax a few thermal sweeps, run after every round.
     *
     *   Not decoration, and not merely for looks. Incision on its own cuts a slot one cell wide,
     *   whose walls stand at whatever angle the arithmetic leaves them -- and a slot one cell wide
     *   is twice as steep on a grid twice as fine, so the world stops being the same world at
     *   different resolutions. Letting the walls fail between rounds caps them at the critical
     *   slope, which is a property of the map rather than the grid. It is also what actually
     *   happens: valley sides are worn back by mass wasting as fast as the river cuts down, which
     *   is why a valley is a V and not a slot.
     */
    suspend fun apply(
        config: WorldGenConfig,
        height: FloatField,
        provisionalSeaLevel: Float,
        onRound: ((RoundMass) -> Unit)? = null,
        relax: suspend (FloatField) -> FloatField
    ): FloatField {
        val cfg = config.erosion
        if (cfg.hydraulicRounds <= 0 || cfg.erodibility <= 0f) return height

        val w = config.width
        val h = config.height
        var working = height.copy()

        val carryingSediment = cfg.deposition
        val reach = cfg.deltaReach.coerceAtLeast(0)
        // Sediment in transit, per cell, handed on as the walk works its way downstream.
        //
        // In double, and that is not fussiness. A trunk near the coast carries the yield of its
        // whole catchment while the cells feeding it hand over a ten-thousandth of that each; in
        // float those additions land below the accumulator's last bit and vanish, and the mass
        // budget went three percent short at round three before this was widened.
        val load = if (carryingSediment) DoubleArray(w * h) else DoubleArray(0)
        // Sediment at rest, per cell, accumulated over every round and laid on the terrain once,
        // at the end.
        //
        // Keeping it off the terrain while the rounds run is deliberate, and it is the difference
        // between a stage that behaves and one that does not. Incision is self-pinning: a channel
        // that cuts gathers more water and cuts deeper, so its position is stable against a
        // difference in the last bit of the input. Aggradation is the opposite -- a cell that
        // rises can send the next round's steepest-descent step somewhere else -- and feeding the
        // spoil straight back into the routing surface made the whole pipeline a lottery. On the
        // GPU-versus-CPU guard the worst cell swung between 0.006 and 0.034 of the elevation range
        // across parameter values that were otherwise indistinguishable, and downstream a people
        // on seed 42 grew from 29% of the habitable world to 49% for no reason anyone could name.
        //
        // So the water routes over the rock it cut, always, and the spoil is laid on top
        // afterwards. What that gives up is the feedback where a river is steered by its own
        // deposits -- real at the scale of a floodplain, mostly numerical at the scale of one cell.
        // What it keeps is every channel exactly where the rock put it.
        val sediment = if (carryingSediment) FloatArray(w * h) else FloatArray(0)
        // Scratch for the little flood fills that build a delta. One stamp per mouth, so a cell
        // cannot be visited twice; the ids only ever increase, so the array never needs clearing.
        val stamp = if (carryingSediment) IntArray(w * h) else IntArray(0)
        val fanCapacity = (2 * reach + 1) * (2 * reach + 1)
        val fanQueue = IntArray(if (carryingSediment) fanCapacity else 0)
        val fanDistance = IntArray(if (carryingSediment) fanCapacity else 0)
        var mouthId = 0

        // Lays the accumulated spoil on the rock. Called once, on the way out, and always before
        // the last relaxation -- partly so a fresh delta gets the same slope-limiting every other
        // landform gets, and partly because the accelerator seam replaces that call wholesale when
        // a stored terrain is being replayed. Anything added after it would be added to a world
        // that had just been overwritten by the snapshot, and `TerrainSnapshotTest` says so.
        fun settle() {
            if (!carryingSediment) return
            for (i in sediment.indices) working.data[i] += sediment[i]
            sediment.fill(0f)
        }

        repeat(cfg.hydraulicRounds) { round ->
            // The shoreline moves as the land wears down, so it is found again each round rather
            // than fixed once. This is the same percentile the sea level stage will use.
            val sea = SeaLevelStage.apply(working, provisionalSeaLevel)
            if (sea.landCellCount == 0) {
                settle()
                return working
            }

            val filled = FlowRouting.fillDepressions(w, h, sea.isLand, sea.relativeElevation)
            val directions = FlowRouting.flowDirections(w, h, sea.isLand, sea.relativeElevation, filled)
            // Uniform rain: every land cell contributes the same, so accumulation is simply the
            // number of cells upstream.
            val area = FlowRouting.accumulate(
                w, h, sea.isLand, filled, directions, sea.landCellCount
            ) { 1f }
            val order = FlowRouting.drainageOrder(w, h, sea.isLand, directions, sea.landCellCount)

            val land = sea.landCellCount.toFloat()
            val isLand = sea.isLand
            val relative = sea.relativeElevation.data
            val ground = filled.data
            val surfaceOf = working.data

            // Ground as the walk leaves it: the pre-round elevation plus everything this round has
            // already added or taken away. Deposition is judged against this rather than against
            // the stale field, or a cell could be raised past the neighbour that feeds it.
            val settled = if (carryingSediment) relative.copyOf() else relative
            if (carryingSediment) {
                load.fill(0.0)
                // The no-uphill rule is judged against the finished surface, spoil included, or
                // the rounds would each be allowed the same margin over and over.
                for (i in settled.indices) settled[i] += sediment[i]
            }

            // Raw height a delta cell is built up to. `relative` is elevation measured from the
            // shoreline in units of the land's range, so converting back needs that range.
            val landRange = (working.max() - sea.threshold).coerceAtLeast(1e-6f)
            val deltaTop = sea.threshold + cfg.deltaFreeboard * landRange

            var incised = 0.0
            var deposited = 0.0
            var lost = 0.0
            val startingMass =
                if (onRound != null) totalMass(surfaceOf) + totalMass(sediment) else 0.0

            // The lips of the basins the fill just raised, cut down before the water is routed
            // over them.
            //
            // Before, and not during, because the outlet has to be cut as a whole — a lip can only
            // fall if the ground between it and the open valley below falls with it — and the walk
            // that follows runs one cell at a time from the sources down. Running it first also
            // keeps the two honest about each other: the notch lowers the round's routing surface
            // along with the terrain, so the walk sees the ground as the notch left it and takes
            // its own bite out of what is actually there. Whatever the notch removes is handed to
            // the cell's sediment load, so the walk carries it away like any other spoil and the
            // budget closes in the round it was opened.
            val notch = if (cfg.outletIncision || onRound != null) {
                FlowRouting.spillways(w, h, isLand, relative, ground, directions, POND_DEPTH)
            } else {
                null
            }
            var notched = 0.0
            var notchCells = 0
            if (notch != null && cfg.outletIncision) {
                val cut = breach(
                    cfg, w, notch, isLand, relative, ground, directions, area.data, land,
                    landRange, surfaceOf,
                    settled = if (carryingSediment) settled else null,
                    load = if (carryingSediment) load else null
                )
                notched = cut.moved
                notchCells = cut.cells
                incised += cut.moved
            }

            // Sources first, so every cell has already received whatever its tributaries were
            // carrying by the time it is asked what to do with it.
            for (k in order.indices) {
                val i = order[k]
                val target = directions[i]
                var carried = if (carryingSediment) load[i] else 0.0

                if (target < 0) {
                    // Water runs off the polar edge, and whatever it carries goes with it.
                    lost += carried
                    continue
                }

                // Both terms are held against the map rather than the grid, so a finer grid cuts
                // the same valleys rather than deeper ones: area as a share of all land, slope as
                // a rise over a fraction of the map's width.
                val toSea = !isLand[target]
                val drop = ground[i] - if (toSea) relative[target] else ground[target]

                if (!carryingSediment) {
                    if (drop > 0f) surfaceOf[i] -= cut(cfg, i, target, w, drop, area, land, relative)
                    continue
                }

                // Under standing water there is no channel to aggrade: the river here *is* the
                // lake, its gradient is the epsilon the flood-fill left, and anything it was
                // carrying was dropped at the inflow. Skipping deposition inside a basin is what
                // keeps lakes from silting up into meadows.
                //
                // Only deposition, though. An earlier version skipped the incision too, and that
                // was a cliff: a cell a hair either side of this depth either cut or did not, so a
                // difference in the last bit of the input -- which is exactly what the GPU's
                // thermal pass produces -- could change a cell by the full depth of its channel.
                // `GpuErosionTest` found it, at five cells in a million.
                val ponded = ground[i] - relative[i] > POND_DEPTH
                run {
                    val distance = if (isDiagonal(i, target, w)) DIAGONAL else 1f
                    val slope = if (drop > 0f) drop / distance * w else 0f
                    val capacity =
                        (cfg.transportCapacity * sqrt(area.data[i] / land) * slope).toDouble()

                    if (drop > 0f) {
                        // Two limits, and both matter.
                        //
                        // Never cut below what this cell drains into, or the channel digs a hole
                        // for the next round's flood-fill to undo, and the two fight each other
                        // round after round.
                        //
                        // And never cut below the sea, which is the base level every river grades
                        // to. A river reaching the coast stops cutting because there is nothing
                        // left to fall. Without that limit the last cells before the shore incise
                        // hardest -- they have the whole catchment behind them and open water in
                        // front -- and the coastline shreds into drowned valleys and islands.
                        val taken = cut(cfg, i, target, w, drop, area, land, relative)
                        val moved = -raise(surfaceOf, i, -taken.toDouble())
                        settled[i] -= moved.toFloat()
                        carried += moved
                        incised += moved
                    }

                    if (!ponded && carried > capacity) {
                        // More than the flow can hold, so some of the surplus settles: a floodplain
                        // where a trunk flattens out, a fan where a range front drops onto the
                        // plain.
                        //
                        // Cutting and settling both, rather than one or the other. Making them
                        // exclusive was tried first and is what a strictly transport-limited model
                        // would do, but a trunk gathers more than it can carry long before it stops
                        // being steep, so every lower channel switched off its incision and the
                        // valleys filled in: `ValleyIncisionTest` fell from 1.90x to 1.00x, which is
                        // to say the water might as well not have run at all.
                        //
                        // The room above is what keeps this from simply erasing a valley. A channel
                        // cell may never stand as high as the cell feeding it -- that is an uphill
                        // river -- and taking only a fraction of that margin per round means the
                        // floor creeps toward grade over the rounds rather than jumping to it.
                        val room = headroom(w, h, i, drop, directions, settled)
                        val give = minOf(carried - capacity, room.toDouble()) * cfg.depositionRate
                        if (give > 0.0) {
                            // Tallied from what the field actually took, never from what it was
                            // asked to take: the terrain is float, so a small enough increment
                            // rounds away, and a budget counted on intent would not notice.
                            val moved = raise(sediment, i, give)
                            settled[i] += moved.toFloat()
                            carried -= moved
                            deposited += moved
                        }
                    }
                }

                when {
                    toSea -> {
                        // The river's whole remaining load arrives at once. A share of it settles
                        // in the receiving cells and builds land; the rest disperses offshore and
                        // is gone, which is what the sea does with most of the world's sediment.
                        //
                        // Only for a watercourse big enough to be a river, though. Let every rill
                        // build and the coastline merely creeps outward everywhere at once, which
                        // is a wider continent rather than a delta.
                        val river = area.data[i] / land >= cfg.deltaMinCatchment
                        // Which way the trunk was pointing when it arrived, so the lobe can build
                        // out in front of the river rather than equally in every direction.
                        val outX = shortestX(target % w - i % w, w).toFloat()
                        val outY = (target / w - i / w).toFloat()
                        val laid = fan(
                            w, h, target, if (river) carried * cfg.deltaShare else 0.0, reach,
                            stamp, ++mouthId, fanQueue, fanDistance, surfaceOf, sediment, settled,
                            wholeCells = cfg.deltaLobe,
                            accepts = { c, d ->
                                !isLand[c] && (
                                    !cfg.deltaLobe ||
                                        d <= lobeReach(reach, target, c, outX, outY, w)
                                    )
                            },
                            levelOf = { _, d ->
                                if (cfg.deltaLobe) {
                                    lobeLevel(deltaTop, sea.threshold, reach, d)
                                } else {
                                    deltaTop
                                }
                            }
                        )
                        deposited += laid
                        lost += carried - laid
                    }

                    !ponded && ground[target] - relative[target] > POND_DEPTH -> {
                        // A lake inflow. The basin traps a share of the load as a fan built up
                        // toward the water surface, and the rest passes through to the outlet.
                        //
                        // Toward, never to. A fan is stopped twice the pond depth short of the
                        // surface, so however many rounds run and however much sediment arrives,
                        // every cell it touches is still standing water afterwards. A lacustrine
                        // delta shallows a lake; it is not allowed to abolish one, which is what
                        // filling to the brim did — seed 99 lost every lake it had.
                        val laid = fan(
                            w, h, target, carried * cfg.lakeShare, reach,
                            stamp, ++mouthId, fanQueue, fanDistance, surfaceOf, sediment, settled,
                            wholeCells = false,
                            accepts = { c, _ -> isLand[c] && ground[c] - relative[c] > POND_DEPTH },
                            // Deeper the further from the inflow, and uneven cell by cell.
                            //
                            // Laid to one depth below the surface — which is what this was — every
                            // cell of a fan ends at exactly the same height, and a lake whose floor
                            // is a plane has a level set that is a straight line: the water balance
                            // then draws it with a ruler-straight shore. Measured on seed 59758 at
                            // 2048, two lakes of 452 and 287 cells had a single distinct floor
                            // height between them. A real fan slopes away from the river that
                            // built it and is rough, so this one does too.
                            levelOf = { c, d ->
                                val depth = 2f * POND_DEPTH *
                                    (1f + d * LAKE_FAN_SLOPE) * (0.9f + 0.35f * wobble(c))
                                sea.threshold + (ground[c] - depth) * landRange
                            }
                        )
                        deposited += laid
                        load[target] += carried - laid
                    }

                    else -> load[target] += carried
                }
            }

            val closing = round == cfg.hydraulicRounds - 1
            // Where the spoil went, kept before it stops being a layer of its own and becomes
            // terrain: it is how the mouths below tell fresh ground from old.
            //
            // Empty rather than absent when nothing is being carried, so that the pass below runs
            // either way. `DepositionTest` holds a world with deposition switched off and a world
            // with it running and every rate at zero to be bit-identical, which is the assertion
            // that says the deposition machinery is a layer on top of the erosion rather than part
            // of it — and it caught this the first time too.
            val spoil = if (closing) {
                if (carryingSediment) sediment.copyOf() else FloatArray(w * h)
            } else {
                null
            }
            if (closing) settle()

            // One last breach, over the spoil, once the terrain is otherwise finished.
            //
            // The whole of the world's deposition is laid on the rock in the line above, after the
            // last time the water was routed, and a floodplain or a fan laid across a valley mouth
            // dams it. Nothing routes again after this point, so without this pass the lakes a
            // world ends up with are mostly the ones its own sediment made in the last instant of
            // its history: measured on seeds 7 and 42, with the spoil switched off the notch leaves
            // no tectonic lake at all, and with the spoil on the largest lake on the map is one the
            // spoil built. A river does not let its own floodplain dam it, and this is where it
            // says so.
            //
            // Before the last relaxation, and it has to be. The accelerator seam replaces that call
            // wholesale when a stored terrain is being replayed — the snapshot *is* the answer, and
            // anything cut after it would be cut into a field that had just been overwritten by the
            // snapshot and would then be cut again every time the save was opened.
            // `TerrainSnapshotTest` says so, and said so: with this block after the relaxation a
            // reopened save differed from the world it was taken from at the first cell it looked
            // at. What it costs is that the sweeps run over the fresh notch and partly fill it back
            // in, which is the same thing they do to every other channel cut in the same round.
            //
            // What this takes leaves the model: there is no walk left to carry it downstream, so it
            // is accounted as material the outflow took away. The round's own field measurement is
            // read afterwards, so the two halves of the budget still close on the same number.
            //
            // Gated on the notch alone and not on whether anything was carried. The spoil is the
            // reason this pass exists, but making it conditional on the spoil would mean a world
            // with deposition running and every rate at zero had different rock from a world with
            // deposition switched off — and `DepositionTest` holds those two to be bit-identical,
            // which is the assertion that says the deposition machinery is a layer on top of the
            // erosion rather than part of it. It caught this.
            if (closing && cfg.outletIncision) {
                repeat(CLOSING_BREACHES) {
                    val after = SeaLevelStage.apply(working, provisionalSeaLevel)
                    if (after.landCellCount == 0) return@repeat
                    val spoilGround = after.relativeElevation
                    val spoilFilled = FlowRouting.fillDepressions(w, h, after.isLand, spoilGround)
                    val spoilFlow =
                        FlowRouting.flowDirections(w, h, after.isLand, spoilGround, spoilFilled)
                    val spoilArea = FlowRouting.accumulate(
                        w, h, after.isLand, spoilFilled, spoilFlow, after.landCellCount
                    ) { 1f }
                    val cut = breach(
                        cfg, w,
                        FlowRouting.spillways(
                            w, h, after.isLand, spoilGround.data, spoilFilled.data, spoilFlow,
                            POND_DEPTH
                        ),
                        after.isLand, spoilGround.data, spoilFilled.data, spoilFlow, spoilArea.data,
                        after.landCellCount.toFloat(),
                        (working.max() - after.threshold).coerceAtLeast(1e-6f), working.data,
                        settled = null, load = null
                    )
                    incised += cut.moved
                    lost += cut.moved
                    notched += cut.moved
                    notchCells += cut.cells
                }
            }

            // And the last thing of all: give every river that ends on its own delta a way through
            // it.
            //
            // Measured on seed 59758 at 2048, where the author found rivers stopping short of the
            // water: the trunk's drawn chain ended at (640,267), a cell of *sea* — but sea in a
            // body of its own, 105th of 475 on that map, with the delta's new land all round it.
            // Within twelve cells of it, 336 of 614 land cells had no lower neighbour at all. So
            // two things were wrong and neither was the lobe's outline: the water the river reached
            // could not be reached from the ocean, and the ground it would have had to cross to
            // find the ocean was dead flat.
            if (closing && cfg.deltaLobe && spoil != null) {
                val opened = openMouths(w, h, working, provisionalSeaLevel, spoil)
                incised += opened.removed
                lost += opened.removed
            }

            working = relax(working)

            if (onRound != null) {
                onRound(
                    RoundMass(
                        incised = incised,
                        deposited = deposited,
                        lostToSea = lost,
                        // Measured off the finished field, spoil and relaxation included. The
                        // thermal sweeps only move material between neighbours, so they do not
                        // change the total and the comparison stays a comparison with what left.
                        fieldDrop = startingMass - totalMass(working.data) - totalMass(sediment),
                        notched = notched,
                        notchCells = notchCells,
                        basins = notch?.count ?: 0,
                        largestBasinCells = notch?.largestCells ?: 0,
                        largestBasinDepth = notch?.largestDepth ?: 0f,
                        largestBasinSpill = notch?.let {
                            if (it.largest >= 0) it.spill[it.largest] else -1
                        } ?: -1,
                        deepestBasin = notch?.deepest ?: 0f
                    )
                )
            }
        }
        return working
    }

    /**
     * How high a delta lobe stands [d] cells out from its apex.
     *
     * A fan slope, small but never nothing: the freeboard at the apex, falling away to a rim that
     * still clears the water by a sixth of it. Two things come out of that, and the second is the
     * reason for it. It reads as a landform rather than a slab — a lobe laid flat at one level is a
     * blocky raft with a straight edge, which is what the author saw jutting into a bay. And, since
     * the surface descends seaward the whole way across, the trunk keeps a downhill step over its
     * own delta and runs on to the new coast instead of arriving at a flat and stopping: the
     * distributary is the gradient, not a channel cut afterwards.
     */
    private fun lobeLevel(apexLevel: Float, shoreline: Float, reach: Int, d: Int): Float {
        val rim = shoreline + (apexLevel - shoreline) * LOBE_RIM
        return apexLevel + (rim - apexLevel) * d.toFloat() / (reach + 1).toFloat()
    }

    /**
     * How far out the lobe may grow in the direction of one cell.
     *
     * A delta builds in front of its river, not in a circle around it: the load arrives moving, and
     * what it meets on the flanks is the coast it came past. So the reach is the full one straight
     * ahead and a little over a third of it to the sides and behind, plus a few cells of wobble
     * keyed to the cell's own position so that no two lobes and no two sides of one lobe have the
     * same outline. The wobble is arithmetic on the cell index — there is no table and no hash
     * ordering anywhere in it, so the shape is the same shape on any machine.
     */
    private fun lobeReach(reach: Int, apex: Int, cell: Int, outX: Float, outY: Float, w: Int): Int {
        val dx = shortestX(cell % w - apex % w, w).toFloat()
        val dy = (cell / w - apex / w).toFloat()
        val span = sqrt(dx * dx + dy * dy)
        val out = sqrt(outX * outX + outY * outY)
        val ahead =
            if (span <= 0f || out <= 0f) 1f else ((dx * outX + dy * outY) / (span * out))
        val shape = LOBE_SIDES + (1f - LOBE_SIDES) * ahead.coerceAtLeast(0f)
        return (reach * (shape + LOBE_WOBBLE * wobble(cell))).toInt()
    }

    /** A fixed, repeatable number in 0..1 for a cell, from its index and nothing else. */
    private fun wobble(cell: Int): Float {
        var x = cell * -0x61c88647
        x = x xor (x ushr 15)
        x *= 0x2c1b3c6d
        x = x xor (x ushr 12)
        return ((x ushr 8) and 0xFFFF).toFloat() / 65535f
    }

    /** A column difference across a map that wraps in x, taken the short way round. */
    private fun shortestX(dx: Int, w: Int): Int = when {
        dx > w / 2 -> dx - w
        dx < -w / 2 -> dx + w
        else -> dx
    }

    /** Where a lobe's rim stands, as a share of the freeboard its apex stands at. */
    private const val LOBE_RIM = 0.15f

    /** How far a lobe reaches sideways and behind, as a share of how far it reaches ahead. */
    private const val LOBE_SIDES = 0.38f

    /** How much of the reach is given over to the per-cell wobble in the outline. */
    private const val LOBE_WOBBLE = 0.18f

    /**
     * How far a distributary falls per cell, as a share of the freeboard its lobe stands at.
     *
     * Enough that the D8 step across a lobe has one answer rather than the fill's epsilon and a
     * coin toss — two orders of magnitude more than that epsilon — and little enough that a channel
     * ten cells long is a groove across the delta rather than a canyon through it.
     */
    private const val DISTRIBUTARY_FALL = 0.15f

    /**
     * How much of the land a watercourse must drain before this stage treats it as a river.
     *
     * Deliberately the same figure as `RiversConfig.sourceThreshold`, and deliberately a constant
     * rather than a read of that setting, for the same reason [POND_DEPTH] is: the rivers section
     * is chosen long after erosion runs, and reading it here would mean adding `rivers` to
     * erosion's reuse guard so that moving a river setting re-cut every valley. The two agree
     * because the stage works to flat rain, so a share of the world's runoff and a share of its
     * land are the same number.
     */
    private const val DRAWN_RIVER = 0.0006f

    /**
     * How much deeper a lacustrine fan lies per cell of distance from the river that built it, as a
     * share of the two pond-depths it is held below the surface at its apex.
     *
     * A fan is a slope, not a shelf: the coarse material drops at the inflow and the fine carries
     * further out, so the floor falls away from the mouth. Modest, because the whole fan sits in
     * water a few pond-depths deep and the point is a floor with a shape rather than a canyon.
     */
    private const val LAKE_FAN_SLOPE = 0.25f

    /** What one pass of the outlet notch took off, and out of how many cells. */
    private class Breached(val moved: Double, val cells: Int)

    /** What cutting the grooves took off the land, and out of how many cells. */
    private class Opened(val removed: Double, val cuts: Int)

    /**
     * Cuts each drawn river one channel to follow wherever its own path crosses ground the fill had
     * to raise.
     *
     * A delta lobe is built round after round around whichever cell the trunk was reaching at the
     * time, laid a little at a time and then handed to the thermal sweeps, and what comes out is
     * flat enough that the depression fill has to level it. The water then crosses it on the fill's
     * epsilon, which is a coin toss cell by cell: the trunk arrives and breaks into a fan of
     * one-cell threads lying at the grid's own bearings, none of them carrying enough accumulation
     * to be drawn as a river. What the reader sees is a river stopping at the inner edge of a pale
     * slab. Measured on seed 59758 at 2048, 336 of the 614 land cells within twelve of that mouth
     * had no lower neighbour at all.
     *
     * So the river's own path is cut to a surface that falls by a fixed step at every cell, for as
     * long as it is crossing raised ground. The walk is in drainage order, sources first, so each
     * cell is cut against the level its upstream neighbour was left at and the groove descends the
     * whole way. Standing water counts as raised ground where the river is running over its own
     * fresh sediment — a puddle in a week-old fan is not a lake held in rock, and the spoil test is
     * what keeps this off one.
     *
     * What it deliberately does not do is touch the water. An earlier version of this also opened
     * every pocket of sea a river ended in, by cutting an inlet from it to the ocean, and the
     * measurements were good — the mouths ending in a pocket fell below the count in a world with
     * no deposition at all. It is reverted all the same: a small body of water the ocean cannot
     * reach is not always an artefact. `RiftSegmentationTest` asks a flooded rift to be a chain of
     * gulfs with land bridges between them, and those gulfs are exactly such bodies; joining them
     * to the ocean turned the chain back into the channel that chunk existed to break up, and moved
     * enough coastline besides to unsettle the ocean-current and culture guards. Water the sea
     * cannot reach is a question for the sea-level cut, and GEOGRAPHY.md now records it as one.
     */
    private fun openMouths(
        w: Int,
        h: Int,
        working: FloatField,
        provisionalSeaLevel: Float,
        spoil: FloatArray
    ): Opened {
        val size = w * h
        val sea = SeaLevelStage.apply(working, provisionalSeaLevel)
        if (sea.landCellCount == 0) return Opened(0.0, 0)
        val isLand = sea.isLand
        val height = working.data
        val landRange = (working.max() - sea.threshold).coerceAtLeast(1e-6f)
        // Measured against the pond depth rather than against the delta's freeboard, though a
        // freeboard is what it is cutting through. `DepositionTest` holds that no deposition knob
        // may change a world with deposition switched off, and this pass runs either way; reading
        // `deltaFreeboard` here let the fiddled-knobs case move the terrain. It caught that too.
        val step = POND_DEPTH * landRange
        val fall = (step * DISTRIBUTARY_FALL).coerceAtLeast(1e-7f)
        val floor = sea.threshold + step * LOBE_RIM

        val filled = FlowRouting.fillDepressions(w, h, isLand, sea.relativeElevation)
        val flow = FlowRouting.flowDirections(w, h, isLand, sea.relativeElevation, filled)
        val area = FlowRouting.accumulate(
            w, h, isLand, filled, flow, sea.landCellCount
        ) { 1f }
        val land = sea.landCellCount.toFloat()

        var removed = 0.0
        var cuts = 0
        val order = FlowRouting.drainageOrder(w, h, isLand, flow, sea.landCellCount)
        // Mouths first. Reversed, the drainage order reaches a cell only after the cell it drains
        // into, so each one is cut to sit one step above ground that is already final — and a
        // groove built that way descends the whole way to the water by construction.
        //
        // Sources first was tried and is what a channel dug from the top down actually does: it
        // reaches the level it is allowed to stop at, stops, and leaves a trench with a closed end
        // for the next fill to pond. Two of those on seed 718106 at 2048, thirty-three cells
        // between them, and `GlaciationTest` counted them as thin straight water at a grid bearing,
        // which is exactly what they were.
        for (k in order.indices.reversed()) {
            val c = order[k]
            // Every river the map will draw, not only the few big enough to build a delta. The
            // rivers stage draws a channel once it carries `RiversConfig.sourceThreshold` of the
            // world's runoff, and with the flat rain this stage works to that is the same figure as
            // a share of the land. At `deltaMinCatchment` instead — five times as much — the trunk
            // at the author's own mouth on seed 59758 did not qualify and nothing was cut.
            if (area.data[c] / land < DRAWN_RIVER) continue
            val standing = filled.data[c] - sea.relativeElevation.data[c]
            val onFlat = standing > 0f && (standing <= POND_DEPTH || spoil[c] > 0f)
            if (!onFlat) continue
            val t = flow[c]
            if (t < 0) continue
            val below = if (isLand[t]) height[t] else sea.threshold
            val want = minOf(height[c], below + fall).coerceAtLeast(floor)
            if (height[c] > want) {
                removed += -raise(height, c, (want - height[c]).toDouble())
                cuts++
            }
        }
        return Opened(removed, cuts)
    }

    /**
     * Cuts every filled basin's lip down by what its own outflow can take, and cuts the sill below
     * the lip down with it.
     *
     * The second half is the part that is easy to leave out and fatal to leave out. Lowering the
     * rim cell alone changes nothing: the fill finds the same rim the next time it runs, because
     * what dams a basin is not one cell but the whole sill between the lip and the first ground
     * that already lies below the new lake surface. So the channel is *breached* — cut to a surface
     * that begins at the new lip level and falls away from it cell by cell down the flow path,
     * stopping at the first cell that is already lower than that surface. Beyond a steep rim that
     * is one or two cells; on a plateau it is a gorge, and the length of that gorge is exactly why
     * a lake on a plateau lasts and one behind a ridge does not.
     *
     * Breaching rather than filling is the older of the two answers to a depression in the
     * hydrology literature and the one that matches what the ground actually does; this pipeline
     * fills, because the router needs an outlet for every cell in a single pass, and this is where
     * the other half is put back.
     *
     * @param settled the surface deposition is judged against, lowered with the terrain, or null
     *   when nothing is being carried.
     * @param load where the spoil goes, or null when there is no walk left to carry it — in which
     *   case the caller accounts for it as material that left the model.
     */
    private fun breach(
        cfg: ErosionConfig,
        w: Int,
        notch: FlowRouting.Spillways,
        isLand: BooleanArray,
        relative: FloatArray,
        ground: FloatArray,
        directions: IntArray,
        area: FloatArray,
        land: Float,
        landRange: Float,
        surfaceOf: FloatArray,
        settled: FloatArray?,
        load: DoubleArray?
    ): Breached {
        var moved = 0.0
        var cells = 0

        for (b in 0 until notch.count) {
            val spill = notch.spill[b]
            if (spill < 0) continue
            val level = notch.level[b]
            val floor = notch.floor[b]
            if (level - floor <= 0f) continue

            // How far this round's outflow lowers the lip. Stream power, in the same form and with
            // the same coefficient as the ordinary incision, behind a ratio: the discharge is the
            // basin's whole catchment, which is what flow accumulation has already gathered at the
            // rim, and the slope is the one the outlet channel actually stands at, measured over
            // the notch's own length rather than across the single step under the lip. That step is
            // a saddle's, and a saddle is by construction the flattest way out of a basin: a rate
            // taken from it drains nothing in the twelve rounds a world gets, which is the
            // measurement that decided this shape.
            var fall = 0f
            var length = 0
            var c = spill
            while (c >= 0 && isLand[c] && length < cfg.outletReach) {
                fall = level - relative[c]
                if (fall > level - floor) break
                length++
                c = directions[c]
            }
            if (length == 0) continue
            val slope = (fall / length * w).coerceAtLeast(0f)
            val power = cfg.erodibility * cfg.outletIncisionRatio * sqrt(area[spill] / land) * slope

            // Never below the floor of its own basin, because past that there is no lake left to
            // let out; never below the sea, the base level everything grades to.
            val drop = minOf(power, (level - floor) * landRange, (level * landRange))
            if (drop <= 0f) continue

            val newLevel = level - drop / landRange
            c = spill
            var step = 0
            while (c >= 0 && isLand[c] && step < cfg.outletReach) {
                val target = newLevel - step * NOTCH_GRADIENT
                if (relative[c] <= target) break
                val take = (relative[c] - target).toDouble() * landRange
                val ponded = ground[c] - relative[c] > POND_DEPTH
                val cut = -raise(surfaceOf, c, -take)
                if (cut > 0.0) {
                    val asRelative = (cut / landRange).toFloat()
                    relative[c] -= asRelative
                    // Dry ground goes down with the terrain; a cell that was standing under water
                    // keeps its surface, since deepening a pond does not lower what is on top of it.
                    if (!ponded) ground[c] -= asRelative
                    settled?.let { it[c] -= asRelative }
                    load?.let { it[c] += cut }
                    moved += cut
                    cells++
                }
                step++
                c = directions[c]
            }
        }
        return Breached(moved, cells)
    }

    /** Stream-power incision, capped by the drop it sits on and by the sea it grades to. */
    private fun cut(
        cfg: ErosionConfig,
        i: Int,
        target: Int,
        w: Int,
        drop: Float,
        area: FloatField,
        land: Float,
        relative: FloatArray
    ): Float {
        val distance = if (isDiagonal(i, target, w)) DIAGONAL else 1f
        val slope = drop / distance * w
        val share = area.data[i] / land

        val incision = cfg.erodibility * sqrt(share) * slope
        val aboveSea = relative[i].coerceAtLeast(0f)
        return minOf(incision, drop * 0.5f, aboveSea)
    }

    /**
     * How far a cell may be raised before it stands as high as the ground that drains into it.
     *
     * This is the rule that keeps deposition from inventing uphill rivers. The drainage order
     * visits everything upstream of a cell before the cell itself, so their final heights for the
     * round are already known and the margin is exact rather than estimated. A headwater has
     * nothing above it to dam, so it is allowed the drop below it instead.
     */
    private fun headroom(
        w: Int,
        h: Int,
        i: Int,
        drop: Float,
        directions: IntArray,
        settled: FloatArray
    ): Float {
        var room = Float.MAX_VALUE
        var fed = false
        FlowRouting.forEachNeighbour(w, h, i % w, i / w) { n ->
            if (directions[n] == i) {
                fed = true
                val margin = settled[n] - settled[i]
                if (margin < room) room = margin
            }
        }
        return if (fed) room else drop
    }

    /**
     * Lays [budget] of sediment into the water around a mouth, nearest cells first, building each
     * up to [levelOf] and no higher.
     *
     * Breadth-first from the receiving cell out to [reach], so a delta grows from the mouth
     * outward the way a real one does, and a big river's load spreads over more of the shelf than
     * a small one's. Cells are enumerated in a fixed order and marked with a stamp rather than
     * collected in a set, so the result does not depend on any hash ordering.
     *
     * @return how much was actually laid down. Whatever the fill could not place — because the
     *   water was too deep, or the reach ran out — is the caller's to account for.
     */
    private inline fun fan(
        w: Int,
        h: Int,
        start: Int,
        budget: Double,
        reach: Int,
        stamp: IntArray,
        id: Int,
        queue: IntArray,
        distance: IntArray,
        surfaceOf: FloatArray,
        sediment: FloatArray,
        settled: FloatArray,
        wholeCells: Boolean,
        accepts: (Int, Int) -> Boolean,
        levelOf: (Int, Int) -> Float
    ): Double {
        if (budget <= 0.0 || !accepts(start, 0)) return 0.0

        var remaining = budget
        var laid = 0.0
        var head = 0
        var tail = 0
        queue[tail] = start
        distance[tail] = 0
        tail++
        stamp[start] = id

        while (head < tail && remaining > 0.0) {
            val c = queue[head]
            val d = distance[head]
            head++

            // Whole cells only.
            //
            // A cell the budget can only half fill is a cell left under water, and once the lobe
            // has grown past it in a later round it is under water with land all around it: an
            // enclosed pocket of sea, which is where a river was seen to stop dead a few cells
            // short of the coast. Forty-two of a hundred and fifty-two mouths on seed 59758 at 2048
            // ended in one. Leaving the shortfall unspent instead costs nothing — it disperses
            // offshore with the rest of the load, which is where the other six sevenths of it was
            // going anyway — and it makes "every cell of a lobe stands above the water" true by
            // construction rather than by luck.
            val need = levelOf(c, d).toDouble() - surfaceOf[c].toDouble() - sediment[c].toDouble()
            if (need > 0.0) {
                if (wholeCells && need > remaining) break
                val moved = raise(sediment, c, if (need < remaining) need else remaining)
                settled[c] += moved.toFloat()
                remaining -= moved
                laid += moved
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
                    if (stamp[n] == id || !accepts(n, d + 1)) continue
                    stamp[n] = id
                    if (tail < queue.size) {
                        queue[tail] = n
                        distance[tail] = d + 1
                        tail++
                    }
                }
            }
        }
        return laid
    }

    /**
     * Adds [amount] to one cell and reports what the field actually took.
     *
     * The terrain is a float array and the amounts moved in a single round are, cell by cell, very
     * much smaller than the elevations they are added to, so an increment can round away entirely.
     * Reading the change back rather than assuming it is what keeps the mass budget honest about
     * the terrain rather than about its own arithmetic.
     */
    private fun raise(field: FloatArray, i: Int, amount: Double): Double {
        val prior = field[i]
        field[i] = (prior.toDouble() + amount).toFloat()
        return field[i].toDouble() - prior.toDouble()
    }

    /** Summed in double, because a million floats added in float order lose the small changes. */
    private fun totalMass(values: FloatArray): Double {
        var sum = 0.0
        for (v in values) sum += v.toDouble()
        return sum
    }

    private const val DIAGONAL = 1.41421356f

    /** Neighbours differ by one row *and* one column only when the step was diagonal. */
    private fun isDiagonal(from: Int, to: Int, width: Int): Boolean =
        (from / width != to / width) && (from % width != to % width)
}
