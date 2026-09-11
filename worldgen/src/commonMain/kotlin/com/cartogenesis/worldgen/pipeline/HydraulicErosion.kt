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
    val fieldDrop: Double
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
                        val laid = fan(
                            w, h, target, if (river) carried * cfg.deltaShare else 0.0, reach,
                            stamp, ++mouthId, fanQueue, fanDistance, surfaceOf, sediment, settled,
                            accepts = { c -> !isLand[c] },
                            levelOf = { deltaTop }
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
                            accepts = { c -> isLand[c] && ground[c] - relative[c] > POND_DEPTH },
                            levelOf = { c ->
                                sea.threshold + (ground[c] - 2f * POND_DEPTH) * landRange
                            }
                        )
                        deposited += laid
                        load[target] += carried - laid
                    }

                    else -> load[target] += carried
                }
            }

            if (onRound != null) {
                onRound(
                    RoundMass(
                        incised = incised,
                        deposited = deposited,
                        lostToSea = lost,
                        fieldDrop = startingMass - totalMass(surfaceOf) - totalMass(sediment)
                    )
                )
            }

            if (round == cfg.hydraulicRounds - 1) settle()
            working = relax(working)
        }
        return working
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
        accepts: (Int) -> Boolean,
        levelOf: (Int) -> Float
    ): Double {
        if (budget <= 0.0 || !accepts(start)) return 0.0

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

            val need = levelOf(c).toDouble() - surfaceOf[c].toDouble() - sediment[c].toDouble()
            if (need > 0.0) {
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
                    if (stamp[n] == id || !accepts(n)) continue
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
