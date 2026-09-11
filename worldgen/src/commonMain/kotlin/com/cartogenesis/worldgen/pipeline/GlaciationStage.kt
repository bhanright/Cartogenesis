package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.GlaciationConfig
import com.cartogenesis.worldgen.model.WorldGenConfig
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
    val glacierCells: Int,
    val cirques: Int,
    val moraines: Int,
    val riegels: Int,
    /** Rock taken off the land: troughs, cirques and all. */
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
 * The way out is that the two things ice depends on — latitude and altitude — are both known the
 * moment sea level is. So this stage computes a *provisional* mean annual temperature from
 * [ClimateStage.buildTemperature], the very function the climate stage will later use, and freezes
 * what that says is frozen. What it cannot see is the maritime and current anomalies climate adds
 * afterwards, so the mask is a little generous on a coast washed by a warm current. Tidewater
 * glaciers live on exactly such coasts, so the error runs the forgiving way.
 *
 * ### What it does not touch
 *
 * [SeaLevelResult.isLand] and [SeaLevelResult.threshold], neither of them, ever. The coastline is
 * a percentile cut through the whole field and moving one cell of it moves every other — the reason
 * [SeaLevelStage]'s shelf remap is careful to touch only water is the same reason this is careful
 * to touch only land. A fjord in the strict sense is a trough that the sea has *drowned*, and
 * drowning it would mean re-cutting that percentile; what is modelled instead is the trough graded
 * down to the waterline and, outside the mouth, the over-deepened basin on the sea floor with the
 * shelf standing beyond it as a sill. That runs after the shelf remap — this stage is handed the
 * remapped result — so nothing re-flattens it.
 */
object GlaciationStage {

    fun apply(config: WorldGenConfig, sea: SeaLevelResult): SeaLevelResult =
        apply(config, sea, onBudget = null)

    /**
     * @param onBudget handed this stage's mass tally on the way out. An observer, like erosion's:
     *   passing it changes nothing about the world.
     */
    internal fun apply(
        config: WorldGenConfig,
        sea: SeaLevelResult,
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

        val temperature = ClimateStage.buildTemperature(config, sea)
        var frozenCount = 0
        val frozen = BooleanArray(size)
        for (i in 0 until size) {
            if (isLand[i] && temperature.data[i] <= cfg.freezingC) {
                frozen[i] = true
                frozenCount++
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

        val land = sea.landCellCount.toFloat()
        val glacier = BooleanArray(size)
        val strength = FloatArray(size)
        // Cells travelled since the ice left frozen ground. A snout sits below its own snowline —
        // that is what an ablation zone is — so the trough is allowed this far past the mask and
        // not one cell further.
        val runOut = IntArray(size) { Int.MAX_VALUE }
        var glacierCells = 0

        for (k in order.indices) {
            val i = order[k]
            val share = ice[i] / land
            if (share < cfg.minCatchment) continue
            if (frozen[i]) runOut[i] = 0
            if (runOut[i] > cfg.runOut) continue

            glacier[i] = true
            glacierCells++
            // Ice thickness, as a proxy: a glacier draining twenty times the ground is not twenty
            // times as deep, so the root rather than the share itself.
            //
            // Floored, and the floor is load-bearing. Everything this stage cuts is scaled by this
            // number, the over-deepening included, and an over-deepening scaled to a fifth is a
            // basin shallower than [LakesConfig.minDepth] — which is to say a basin that the river
            // stage will not see as a lake, on a glacier that was carved anyway. The smallest
            // thing allowed to be a glacier here already drains a quarter of a percent of a
            // continent's frozen ground, and ice that size is hundreds of metres thick.
            strength[i] = sqrt(share / cfg.fullCatchment).coerceIn(MIN_THICKNESS, 1f)

            val t = directions[i]
            if (t >= 0 && isLand[t]) {
                val next = if (frozen[t]) 0 else runOut[i] + 1
                if (next < runOut[t]) runOut[t] = next
            }
        }
        if (glacierCells == 0) return sea

        // How far down the staircase each cell is.
        //
        // Two things advance it, and they simply add: how far the ice has run (in cells, over
        // [GlaciationConfig.basinSpacing]) and how far it has fallen (in elevation, over
        // [GlaciationConfig.basinDrop]). A reach ends when the sum passes the next whole number, so
        // whichever runs out first ends it — a long flat reach on a plain, a short one on a
        // mountainside. Measured from the head of the longest feeder rather than the nearest, so a
        // tributary joining halfway down does not restart the count.
        val spacing = cfg.basinSpacing.coerceAtLeast(2f)
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

        // The lowest ground the flow meets before this reach ends, which is the level a flattened
        // floor is cut down to. Walked mouths-first, so a cell reads an answer its own downstream
        // neighbour has already finished.
        val reachFloor = FloatArray(size)
        for (i in 0 until size) if (glacier[i]) reachFloor[i] = relative[i]
        for (k in order.indices.reversed()) {
            val i = order[k]
            if (!glacier[i]) continue
            val t = directions[i]
            if (t >= 0 && glacier[t] && reach[t] == reach[i] && reachFloor[t] < reachFloor[i]) {
                reachFloor[i] = reachFloor[t]
            }
        }

        val floor = FloatArray(size)
        for (i in 0 until size) {
            if (!glacier[i]) continue
            val st = strength[i]
            val phase = progress[i] - reach[i]
            val target = if (phase < cfg.basinShare) {
                // The basin: floor flattened to the lowest ground in the reach and then cut below
                // it. The limit needs no setting of its own, which is the point of measuring a
                // reach in descent: a reach falls at most [GlaciationConfig.basinDrop], so
                // flattening one costs at most that plus the over-deepening, wherever it is and
                // however steep the ground. The only case that reaches the limit is a cliff inside
                // a single cell, and there it stops the ice gouging a canyon out of it.
                val basinCut = (cfg.deepening + cfg.overDeepening) * st
                maxOf(reachFloor[i] - basinCut, relative[i] - basinCut - cfg.basinDrop)
            } else {
                // The step: the bed follows the ground down, cut only by the ordinary amount, so it
                // stands proud of the basin above it by the over-deepening.
                relative[i] - cfg.deepening * st
            }
            floor[i] = target.coerceIn(0f, relative[i])
        }

        // Carving proper. Every stamp is computed from the *original* surface and combined with a
        // minimum, so overlapping glaciers compose in any order and the result does not depend on
        // which cell was visited first.
        val carved = relative.copyOf()

        for (i in 0 until size) {
            if (!glacier[i]) continue
            // Wall to wall: the ice lowers the whole cross-section toward its bed on a parabola,
            // untouched at the rim and flat at the floor. That parabola is the U.
            //
            // *Across* the flow and one cell thick along it, which is not a detail. Stamped as a
            // disc instead — the obvious thing, and what this did first — a basin's flat floor
            // reaches a valley-width in every direction, including forward over the step that is
            // supposed to hold its water in, and quietly planes it off. The staircase was there in
            // the long profile and the map had no lakes: seven where there should have been two
            // thousand closed basins. A cross-section is a cross-section.
            swath(
                w, h, i, flowOf(i, directions, glacier, w, h),
                valleyHalfWidth(cfg, strength[i]), cfg.floorShare, floor[i],
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
                w, h, i, cfg.cirqueRadius.coerceAtLeast(1f), cfg.floorShare,
                (relative[i] - depth).coerceAtLeast(0f), isLand, relative, carved
            )
        }

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
            } else if (reach[t] != reach[i]) {
                // A recessional moraine, at the lower end of every reach: the ridge a retreating
                // snout leaves each time it pauses, and what a valley full of them looks like is
                // a chain of lakes. The over-deepening alone does not reliably make one — measured
                // on seed 42, it left a hundred closed basins of which sixty were one or two cells,
                // because how far a basin can spread before the ground rises out of it is a
                // question about the slope and not about the ice. A dam of a known height ponds a
                // known depth whatever the slope, which is why real glaciated valleys owe more of
                // their water to till than to scour.
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
        if (cfg.fjords && cfg.fjordReach > 0) {
            val stamp = IntArray(size)
            val queue = IntArray((2 * cfg.fjordReach + 1) * (2 * cfg.fjordReach + 1))
            val queueDistance = IntArray(queue.size)
            var mouthId = 0
            for (i in 0 until size) {
                if (!glacier[i]) continue
                val t = directions[i]
                if (t < 0 || isLand[t]) continue
                submarine += fjord(
                    w, h, t, cfg.fjordReach, cfg.fjordDepth * strength[i],
                    isLand, carved, stamp, ++mouthId, queue, queueDistance
                )
            }
        }

        onBudget?.invoke(
            GlacialMass(
                frozenCells = frozenCount,
                glacierCells = glacierCells,
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
        (cfg.valleyWidth * sqrt(strength)).coerceAtLeast(1f)

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
