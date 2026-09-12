package com.cartogenesis.worldgen.pipeline

import kotlin.math.sqrt

/**
 * Which mechanism put sediment on which cell, and which mouth it came from.
 *
 * Diagnostics only, exactly as `RoundMass` is: nothing in the pipeline reads it back, it is null
 * unless a caller asks for it, and allocating it changes no world. It exists because the question
 * "which of the four things that lay sediment made *that* shape" could not be answered from the
 * finished map — a raft of new land looks the same whether a lake fan or a delta built it — and
 * E5 was dispatched to answer exactly that question.
 */
internal class DepositionLog(cells: Int) {

    /** [NONE], [SEA_LOBE], [LAKE_FAN] or [FLOODPLAIN], whichever last raised the cell. */
    val mechanism = ByteArray(cells)

    /** For a fan cell, the cell the fan grew from; -1 for everything else. */
    val apex = IntArray(cells) { -1 }

    /** How much was laid on the cell, summed over every round. */
    val laid = DoubleArray(cells)

    /** The first and the last hydraulic round that laid anything on the cell; -1 for neither. */
    val firstRound = ByteArray(cells) { -1 }
    val lastRound = ByteArray(cells) { -1 }

    /** Set by the stage as each round opens, so [record] needs no extra argument. */
    var round: Int = 0

    fun record(cell: Int, mark: Byte, from: Int, amount: Double) {
        mechanism[cell] = mark
        if (apex[cell] < 0) apex[cell] = from
        if (firstRound[cell] < 0) firstRound[cell] = round.toByte()
        lastRound[cell] = round.toByte()
        laid[cell] += amount
    }

    companion object {
        const val NONE: Byte = 0
        const val SEA_LOBE: Byte = 1
        const val LAKE_FAN: Byte = 2
        const val FLOODPLAIN: Byte = 3
    }
}

/**
 * The outline of a fan, and the walk that fills it.
 *
 * ## What was wrong
 *
 * A delta was grown breadth-first from its mouth and the step count of that walk was handed to the
 * acceptance rule as though it were a distance. Over eight neighbours a breadth-first step count is
 * the Chebyshev metric, whose iso-lines are **squares**, so any fan whose acceptance did not shape
 * itself some other way grew as a square: the lacustrine fan, whose rule was "any ponded cell",
 * covered the full 2R+1 square around its inflow and left a flat raft with straight edges and
 * right-angle corners wherever the water later went away. The sea lobe did shape itself, by a
 * cosine of the angle to the trunk — but it compared that Euclidean shape against the Chebyshev
 * count, so it came out as a cosine lobe stretched by 1/max(|cos φ|, |sin φ|): a half-disc with
 * corners pulled out along the diagonals. Its only irregularity was a per-cell hash, which at 2048
 * is a one-cell fringe nobody can see and which averages away over twelve rounds.
 *
 * ## What this is instead
 *
 * The rim of a fan is a curve in polar coordinates about the apex:
 *
 * ```
 * reach(θ) = R · (sides + (1 − sides) · max(cos θ, 0)) · (1 + a · s(θ))
 * ```
 *
 * θ is measured from the direction the river was flowing when it arrived, so the fan builds in
 * front of its river; `s(θ)` is a wobble of four harmonics — orders two, three, five and seven, taken on
 * the *absolute* bearing so the bays stay where they are when the trunk's own D8 step swings by
 * forty-five degrees between rounds — with phases hashed from the mouth's identity. Four
 * harmonics rather than noise is the whole point: what a coastline has is bays and headlands a
 * good fraction of the lobe across, not fuzz on the last cell.
 *
 * The distance the rim is compared against is Euclidean, and it is bent by the depth of the water
 * the fan is building into. Advancing into a cell costs its distance plus a penalty proportional to
 * how deep that cell lies, accumulated along the path, so the fan spends its budget on shallow
 * ground first and progrades far across a shelf and barely at all into deep water. That is the
 * difference between the Nile, which has built two hundred kilometres out over a shelf, and the
 * delta at the head of a fjord, which is a step.
 *
 * Every number in the rim is +, −, ×, ÷ and `sqrt`, and the hashed phases are unit vectors built by
 * normalising two hashed integers rather than by taking a cosine of a hashed angle. There is no
 * transcendental function anywhere in the outline, so the same mouth has bit-identically the same
 * rim on every platform, and there is no sequential random stream: the phases are a splitmix hash
 * of (seed, stage, apex), so editing an upstream stage cannot reshuffle every delta on the map.
 *
 * ## Rule 8
 *
 * This is a graph walk from one cell — a best-first traversal with a binary heap, at most a few
 * thousand cells per mouth and a few hundred mouths per world — so it **stays on the CPU** and no
 * GPU path is specified for it. There is nothing here for a compute shader to do in parallel: the
 * cost of a cell depends on the path taken to reach it, and the budget is spent in visit order.
 */
internal object DeltaFan {

    /** How far a lobe reaches sideways and behind, as a share of how far it reaches ahead. */
    const val SIDES = 0.38f

    /**
     * How much of the reach the harmonic wobble may add or take away.
     *
     * At 0.30 the rim in one direction can be 1.86 times the rim in another, which is about what a
     * real lobe's outline does between its promontories and the bays between them. Below about
     * 0.18 the outline reads as a disc again; `DeltaOutlineTest` holds the measured ratio.
     */
    const val WOBBLE = 0.30f

    // Four harmonics, at orders two, three, five and seven.
    //
    // Four rather than two, for the detail rather than for the guard. The weights fall with the
    // order, so a fourth term adds a notch on a bay rather than a fifth bay, and a lobe at 2048 has
    // something to look at at two scales instead of one. It was added in the hope that it would
    // also raise the *least* asymmetric outline the hashed phases can produce — a set of phases
    // that happens to come out nearly mirror-symmetric is a lobe drawn with a compass by accident —
    // and measured over twenty-four mouths it does not: the unluckiest of them went from 10.0% to
    // 10.2%. `DeltaOutlineTest` sets its bar between the measured populations instead.
    private const val W2 = 1.0f
    private const val W3 = 0.7f
    private const val W5 = 0.5f
    private const val W7 = 0.45f
    private const val INV_W = 1f / (W2 + W3 + W5 + W7)

    /** How far to either side of the trunk the distributaries fan out, as a tangent. */
    private const val SPREAD = 1.1f

    /** Half-width of a distributary groove, as a share of the reach, and never under half a cell. */
    private const val GROOVE_WIDTH = 0.05f

    /**
     * The fewest cells of reach a lobe needs before its distributaries are cut.
     *
     * How many distributaries a delta has and which way they run are properties of the delta —
     * hashed from the mouth, the same at every resolution — but a groove has to be at least a cell
     * wide to exist at all, and four grooves across a four-cell fan is one groove. Below this the
     * lobe keeps its apex-to-rim slope and the trunk's own path, which is what carries the river
     * across it; above it, the same delta reads as a bird's foot.
     */
    private const val GROOVE_MIN_REACH = 4

    /** Mixed into every hash here, so the delta outlines are their own stream. */
    private const val STAGE = 0x5D_E1_7A_11

    /**
     * A splitmix64-style hash of a seed, this stage and an index, folded to 32 bits.
     *
     * Not a sequential stream: the phases of one mouth's wobble depend on that mouth's own position
     * and on nothing that happened before it, so inserting a stage upstream, or generating the
     * mouths in a different order, leaves every other delta on the map exactly where it was.
     */
    fun hash(seed: Long, index: Int): Int {
        var z = seed * -0x61c8864680b583ebL + index.toLong() * -0x7ee3623a03d3c83fL + STAGE
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z = z xor (z ushr 31)
        return (z ushr 17).toInt()
    }

    /** A hashed float in 0..1 from a bit window, so one hash yields several independent draws. */
    private fun draw(hash: Int, slot: Int): Float {
        var x = hash + slot * -0x61c88647
        x = x xor (x ushr 15)
        x *= 0x2c1b3c6d
        x = x xor (x ushr 12)
        x *= 0x297a2d39
        x = x xor (x ushr 15)
        return ((x ushr 8) and 0xFFFF).toFloat() / 65535f
    }

    /**
     * The rim of one fan: how far it may reach in each direction, and where its distributaries run.
     *
     * Built once per mouth per round and read a few thousand times, so everything that can be
     * worked out from the mouth alone is worked out here.
     *
     * @param outX horizontal component of the step the river took as it arrived. Need not be a unit
     *   vector; a zero vector gives a fan that reaches equally in every direction.
     * @param reach the lobe's full reach straight ahead, in cells. Scaled with the grid by
     *   `WorldGenConfig.atResolution`, so it is a length on the ground.
     */
    class Rim(
        val apex: Int,
        private val width: Int,
        val reach: Float,
        outX: Float,
        outY: Float,
        hash: Int,
        grooved: Boolean,
        /**
         * How much of the reach the harmonic wobble may add or take away. Only ever moved from
         * [WOBBLE] by `DeltaOutlineTest`, whose control for "this is not a shape drawn with a
         * compass" is this same outline with the harmonics switched off — which is precisely what
         * the cosine lobe it replaced was.
         */
        private val wobble: Float = WOBBLE
    ) {
        private val ax = apex % width
        private val ay = apex / width
        private val ox: Float
        private val oy: Float
        private val p2x: Float
        private val p2y: Float
        private val p3x: Float
        private val p3y: Float
        private val p5x: Float
        private val p5y: Float
        private val p7x: Float
        private val p7y: Float

        /** How many distributaries this lobe has: two to five, hashed, zero if it is too small. */
        val distributaries: Int
        private val gx = FloatArray(5)
        private val gy = FloatArray(5)
        private val halfWidth = if (reach * GROOVE_WIDTH > 0.55f) reach * GROOVE_WIDTH else 0.55f

        init {
            val out = sqrt(outX * outX + outY * outY)
            if (out <= 0f) {
                ox = 0f
                oy = 0f
            } else {
                ox = outX / out
                oy = outY / out
            }
            val p2 = phase(hash, 1)
            val p3 = phase(hash, 2)
            val p5 = phase(hash, 3)
            val p7 = phase(hash, 4)
            p2x = p2.first; p2y = p2.second
            p3x = p3.first; p3y = p3.second
            p5x = p5.first; p5y = p5.second
            p7x = p7.first; p7y = p7.second

            distributaries = if (!grooved || reach < GROOVE_MIN_REACH || out <= 0f) {
                0
            } else {
                2 + ((hash ushr 3) and 3)
            }
            if (distributaries > 0) {
                val n = distributaries
                val lx = -oy
                val ly = ox
                val span = if (n > 1) 2f * SPREAD / (n - 1).toFloat() else 0f
                for (k in 0 until n) {
                    val jitter = (draw(hash, 10 + k) - 0.5f) * 0.8f * span
                    val t = if (n > 1) -SPREAD + span * k.toFloat() + jitter else jitter
                    val vx = ox + lx * t
                    val vy = oy + ly * t
                    val len = sqrt(vx * vx + vy * vy).coerceAtLeast(1e-6f)
                    gx[k] = vx / len
                    gy[k] = vy / len
                }
            }
        }

        /**
         * How far the rim stands in the direction of a cell that lies [dx], [dy] from the apex at
         * distance [r].
         */
        fun radius(dx: Float, dy: Float, r: Float): Float {
            if (r <= 0f) return reach
            val c = dx / r
            val s = dy / r
            // cos of the angle to the trunk, and the nose that makes a fan a fan rather than a
            // disc: full reach straight ahead, [SIDES] of it abeam and behind. The exponent on the
            // cosine is one, deliberately. A fractional power broadens the fan, and a broader fan
            // looks better — but it also puts harmonics of its own into the outline, and the
            // outline's harmonic content is exactly what `DeltaOutlineTest` measures to tell a
            // coastline from a shape drawn with a compass. With the exponent at one the nose is
            // pure zeroth and first order, so everything the guard finds above the first order is
            // the wobble and nothing else, and the control — the same rim with the wobble at zero
            // — is the cosine lobe this replaced, measuring zero.
            val ahead = c * ox + s * oy
            val nose = SIDES + (1f - SIDES) * (if (ahead > 0f) ahead else 0f)
            // The wobble, on the absolute bearing. cos(kφ) and sin(kφ) by the Chebyshev recurrence
            // on (c, s), so the harmonics cost four multiplies each and no trigonometry.
            val c2 = c * c - s * s
            val s2 = 2f * c * s
            val c3 = c2 * c - s2 * s
            val s3 = s2 * c + c2 * s
            val c5 = c3 * c2 - s3 * s2
            val s5 = s3 * c2 + c3 * s2
            val c7 = c5 * c2 - s5 * s2
            val s7 = s5 * c2 + c5 * s2
            val wob = (
                W2 * (c2 * p2x - s2 * p2y) +
                    W3 * (c3 * p3x - s3 * p3y) +
                    W5 * (c5 * p5x - s5 * p5y) +
                    W7 * (c7 * p7x - s7 * p7y)
                ) * INV_W
            return reach * nose * (1f + wobble * wob)
        }

        /**
         * How many of [distributaries] have survived [pruneGrooves]; they are kept at the front.
         *
         * All of them until that is called, so a caller that never prunes gets every groove rather
         * than none — a rim with its grooves silently switched off would be a very quiet bug.
         */
        private var live = distributaries

        /** Whether a cell lies in one of the lobe's distributary grooves. */
        fun grooved(dx: Float, dy: Float): Boolean {
            for (k in 0 until live) {
                if (dx * gx[k] + dy * gy[k] < 0f) continue
                val perp = dx * gy[k] - dy * gx[k]
                if (perp > -halfWidth && perp < halfWidth) return true
            }
            return false
        }

        /**
         * Drops every distributary whose ray to the rim crosses ground the fan may not build on.
         *
         * A groove is a channel, and a channel that runs into the back of the coast is not one: its
         * outermost cell is lower than the lobe on both sides of it, lower than the ground ahead,
         * and lower than the groove behind it, which is to say it is a pit. Measured on the four
         * guard seeds at 512, cutting the grooves without this check took the share of new land
         * with nowhere downhill from 1.5/0.5/0.8/2.5% to 2.2/1.8/1.7/3.3% — the grooves were making
         * more flat ground than the slope they were cut into was removing.
         *
         * So each ray is walked, one cell at a time, out to its own rim; if it meets anything that
         * is not open water, or runs off the pole, that distributary does not exist. What is left
         * is the set of channels that actually reach the sea, which is what a bird's-foot delta is.
         */
        fun pruneGrooves(height: Int, open: (Int) -> Boolean) {
            var kept = 0
            for (k in 0 until distributaries) {
                val ex = gx[k]
                val ey = gy[k]
                val edge = radius(ex, ey, 1f)
                var blocked = false
                var step = 1
                while (step <= edge.toInt()) {
                    val y = ay + (ey * step).toInt()
                    if (y < 0 || y >= height) { blocked = true; break }
                    var x = (ax + (ex * step).toInt()) % width
                    if (x < 0) x += width
                    if (!open(y * width + x)) { blocked = true; break }
                    step++
                }
                if (blocked) continue
                if (kept != k) {
                    gx[kept] = gx[k]
                    gy[kept] = gy[k]
                }
                kept++
            }
            live = kept
        }

        /** Column difference from the apex, the short way round a map that wraps in x. */
        fun dx(cell: Int): Float = wrapDx(cell % width - ax, width).toFloat()

        /** Row difference from the apex. The y axis does not wrap. */
        fun dy(cell: Int): Float = (cell / width - ay).toFloat()

        private fun phase(hash: Int, slot: Int): Pair<Float, Float> {
            val u = draw(hash, slot * 2) * 2f - 1f
            val v = draw(hash, slot * 2 + 1) * 2f - 1f
            val len = sqrt(u * u + v * v)
            return if (len < 1e-4f) 1f to 0f else (u / len) to (v / len)
        }
    }

    /**
     * The heap and the visit stamps one fan needs, allocated once for the whole run.
     *
     * The stamp array is grid-sized and the ids only ever increase, so it never needs clearing; the
     * heap is bounded by the cells inside a disc of radius `reach`, which is why it can be sized
     * from the reach rather than from the grid.
     */
    class Scratch(cells: Int, reach: Int) {
        val stamp = IntArray(cells)
        private val cap = ((2 * reach + 1) * (2 * reach + 1)).coerceAtLeast(9)
        private val cell = IntArray(cap)
        private val key = FloatArray(cap)
        private val pen = FloatArray(cap)
        private var size = 0

        /** The cells the walk admitted, in the order it reached them. */
        val admitted = IntArray(cap)
        var admittedCount = 0
            private set

        fun admit(c: Int) {
            if (admittedCount < cap) admitted[admittedCount++] = c
        }

        fun forget() {
            admittedCount = 0
        }

        /** The cell and accumulated depth penalty the last [pop] returned. */
        var outCell = -1
            private set
        var outPen = 0f
            private set

        fun reset() {
            size = 0
        }

        fun push(c: Int, k: Float, p: Float) {
            if (size >= cap) return
            var i = size++
            cell[i] = c
            key[i] = k
            pen[i] = p
            while (i > 0) {
                val parent = (i - 1) / 2
                if (!less(i, parent)) break
                swap(i, parent)
                i = parent
            }
        }

        fun pop(): Boolean {
            if (size == 0) return false
            outCell = cell[0]
            outPen = pen[0]
            size--
            if (size > 0) {
                cell[0] = cell[size]
                key[0] = key[size]
                pen[0] = pen[size]
                var i = 0
                while (true) {
                    val l = 2 * i + 1
                    if (l >= size) break
                    val r = l + 1
                    var m = l
                    if (r < size && less(r, l)) m = r
                    if (!less(m, i)) break
                    swap(i, m)
                    i = m
                }
            }
            return true
        }

        /**
         * Strictly ordered, ties broken by cell index, so the visit order is a property of the map
         * and not of the order the heap happened to fill.
         */
        private fun less(i: Int, j: Int): Boolean =
            key[i] < key[j] || (key[i] == key[j] && cell[i] < cell[j])

        private fun swap(i: Int, j: Int) {
            val c = cell[i]; cell[i] = cell[j]; cell[j] = c
            val k = key[i]; key[i] = key[j]; key[j] = k
            val p = pen[i]; pen[i] = pen[j]; pen[j] = p
        }
    }
}

/**
 * Lays [budget] of sediment into the water around a mouth, cheapest cell first, building each up to
 * [levelOf] and no higher.
 *
 * Cheapest, not nearest: the priority is the cell's Euclidean distance from the apex *plus* the
 * depth penalty accumulated along the path that reached it. On flat ground that is exactly the
 * distance, so the fan fills outward in rings; over a shelf beside a trench it fills the shelf
 * first and stops early down the slope. A cell is admitted only if that same figure is inside
 * [DeltaFan.Rim.radius] for its own direction, which is what gives the lobe its outline.
 *
 * The Euclidean part of the priority is the true `sqrt(dx² + dy²)` to the apex and never a sum of
 * steps, because a sum of eight-neighbour steps is the octagonal metric and overstates a straight
 * line by up to 8.2% at the bearings between an axis and a diagonal — which is the same facetting
 * `JumpFloodDistance` exists to remove from every other distance field in this project. Only the
 * depth penalty is a path integral, because it has to be: what stops a lobe crossing a trench is
 * the trench being in the way, not the far side being deep.
 *
 * @param wholeCells stop at the first cell the remaining budget cannot fill completely. A cell left
 *   half-filled is a cell left under water with the lobe grown past it in a later round, which is
 *   an enclosed pocket of sea; leaving the shortfall unspent costs nothing, since it disperses
 *   offshore with the rest of the load.
 * @param accepts whether a cell is eligible at all — open water for a delta, ponded ground for a
 *   lacustrine fan. Independent of distance; the rim does the shaping.
 * @param advance the cost multiplier for stepping into a cell, one for ground at the level the fan
 *   is building to and more for every unit of depth below it.
 * @param levelOf the height to build a cell up to, given the cell and how far out it lies as a
 *   fraction of the lobe's full reach (0 at the apex, 1 at the reach). A fraction of the *reach*
 *   and not of the rim in the cell's own direction, so the surface falls monotonically away from
 *   the apex whatever the outline is doing; see the note at its call below.
 * @return how much was actually laid down. Whatever the fan could not place is the caller's to
 *   account for.
 */
internal inline fun growFan(
    w: Int,
    h: Int,
    budget: Double,
    rim: DeltaFan.Rim,
    scratch: DeltaFan.Scratch,
    id: Int,
    surfaceOf: FloatArray,
    sediment: FloatArray,
    settled: FloatArray,
    /**
     * What one height unit is worth in the shoreline-relative units [settled] is kept in — the
     * reciprocal of the land's range. The fan's own writes to [settled] are inert inside one round,
     * since every cell it touches is water and no land cell drains into one, but an array whose
     * units depend on who wrote to it is a trap, and the muddle that was there cost the alluvial
     * dams a factor of four.
     */
    toRelative: Float,
    wholeCells: Boolean,
    log: DepositionLog?,
    mark: Byte,
    accepts: (Int) -> Boolean,
    advance: (Int) -> Float,
    levelOf: (Int, Float) -> Float
): Double {
    val apex = rim.apex
    if (budget <= 0.0 || !accepts(apex)) return 0.0

    // Pass one: which cells the lobe reaches, by a best-first walk over the depth-bent cost. The
    // walk runs to exhaustion and lays nothing.
    scratch.reset()
    scratch.forget()
    scratch.stamp[apex] = id
    scratch.push(apex, 0f, 0f)
    while (scratch.pop()) {
        val c = scratch.outCell
        val pen = scratch.outPen
        scratch.admit(c)
        val cx = c % w
        val cy = c / w
        for (stepY in -1..1) {
            val ny = cy + stepY
            if (ny < 0 || ny >= h) continue
            for (stepX in -1..1) {
                if (stepX == 0 && stepY == 0) continue
                var nx = (cx + stepX) % w
                if (nx < 0) nx += w
                val n = ny * w + nx
                if (scratch.stamp[n] == id || !accepts(n)) continue
                val ndx = rim.dx(n)
                val ndy = rim.dy(n)
                val nr = sqrt(ndx * ndx + ndy * ndy)
                val step = if (stepX != 0 && stepY != 0) 1.41421356f else 1f
                // Only the extra cost of the depth accumulates; the distance term is measured
                // afresh from the apex, so the walk cannot inherit an octagonal path length.
                val npen = pen + step * (advance(n) - 1f)
                if (nr + npen > rim.radius(ndx, ndy, nr)) continue
                scratch.stamp[n] = id
                scratch.push(n, nr + npen, npen)
            }
        }
    }

    // Pass two: spend the budget on those cells nearest the apex first.
    //
    // Nearest, and not cheapest, and the difference is a bug that was measured. The walk above
    // visits shallow cells before deep ones, which is what bends the outline; spending the budget
    // in that same order means a deep cell close to the mouth is reached late, and when the budget
    // runs out at [wholeCells] it is left under water with the lobe grown all round it — an
    // enclosed pocket, which is the thing E1 existed to remove. Filling outward from the apex
    // instead makes "the lobe is a filled region" true by construction, and it is what a delta
    // does: it fills its accommodation space from the apex out, and where that space is deep it
    // simply does not get as far. On seed 1234 at 512, filling in cost order left 2.5% of the new
    // land with nowhere downhill against 1.1% before the chunk; filling in radial order, 0.9%.
    val count = scratch.admittedCount
    scratch.reset()
    for (k in 0 until count) {
        val c = scratch.admitted[k]
        val dx = rim.dx(c)
        val dy = rim.dy(c)
        scratch.push(c, sqrt(dx * dx + dy * dy), 0f)
    }

    var remaining = budget
    var laid = 0.0
    while (remaining > 0.0 && scratch.pop()) {
        val c = scratch.outCell
        val dx = rim.dx(c)
        val dy = rim.dy(c)
        val r = sqrt(dx * dx + dy * dy)
        // How far out the cell lies as a share of the lobe's *full* reach, never of the rim in its
        // own direction.
        //
        // This is the one place the outline and the surface are deliberately not the same shape,
        // and it was measured the other way round first. A surface graded to the rim in each
        // direction is not monotone in distance: a cell five cells out where the rim is six stands
        // lower than a cell six cells out where the rim is twelve, so every bay in the outline puts
        // a dip in the plain behind it, and a dip with the lobe all round it is a cell with nowhere
        // downhill. On the four guard seeds at 512 that measured 1.9/1.1/1.1/2.6% of the new land
        // against 0.8/0.8/1.2/1.1% before the chunk — a delta with a rougher outline but a worse
        // surface. Graded to the full reach instead, the surface falls with distance from the apex
        // and nothing else, so every cell has a lower neighbour straight out from the mouth by
        // construction. What it costs is that the flanks of a lobe end above the rim level and drop
        // to the water in one step, which is a delta front, and is what a delta actually has.
        val t = (r / rim.reach).coerceAtMost(1f)

        val need = levelOf(c, t).toDouble() - surfaceOf[c].toDouble() - sediment[c].toDouble()
        if (need <= 0.0) continue
        if (wholeCells && need > remaining) break
        val moved = fanRaise(sediment, c, if (need < remaining) need else remaining)
        settled[c] += (moved * toRelative).toFloat()
        remaining -= moved
        laid += moved
        log?.record(c, mark, apex, moved)
    }
    return laid
}

/**
 * Adds [amount] to one cell and reports what the field actually took.
 *
 * The same reasoning as `HydraulicErosion`'s own copy, and deliberately a second copy rather than a
 * shared one: this file and that one are edited by different chunks at the same time, and a
 * four-line arithmetic helper is cheaper to duplicate than a merge conflict in the incision loop.
 * The terrain is a float array and a single round's increment can round away entirely, so the mass
 * budget is counted from what the field took and never from what it was asked to take.
 */
internal fun fanRaise(field: FloatArray, i: Int, amount: Double): Double {
    val prior = field[i]
    field[i] = (prior.toDouble() + amount).toFloat()
    return field[i].toDouble() - prior.toDouble()
}

/** A column difference across a map that wraps in x, taken the short way round. */
internal fun wrapDx(dx: Int, w: Int): Int = when {
    dx > w / 2 -> dx - w
    dx < -w / 2 -> dx + w
    else -> dx
}
