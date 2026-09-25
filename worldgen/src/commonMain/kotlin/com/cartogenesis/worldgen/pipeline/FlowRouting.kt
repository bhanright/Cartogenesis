package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.math.GroundSteps
import com.cartogenesis.worldgen.math.LongMinHeap
import com.cartogenesis.worldgen.model.FloatField
import kotlin.math.sqrt

/**
 * Where water goes: filling the hollows, picking the downhill neighbour, and adding up what
 * arrives.
 *
 * Shared, because two stages need the same answer at different times. Erosion needs it to know
 * which cells carry enough water to cut a valley, before there is a sea or a climate; rivers need
 * it afterwards, weighted by real rainfall, to decide which channels to draw. Having one copy
 * means the valleys the erosion carves are the valleys the rivers later find.
 *
 * Everything here takes a plain land mask rather than a [SeaLevelResult], since erosion runs before
 * the sea level is chosen and has to supply a provisional one.
 */
internal object FlowRouting {

    /**
     * Raised by this much per step when flooding a flat, so filled ground still has a gradient.
     *
     * In the elevation field's own units. Small enough to be invisible on any map, large enough
     * that a float can still tell two neighbouring cells of a filled lake apart, which is what
     * gives the routing below a direction to take across one.
     *
     * Visible outside this object because the outlet walk in [HydraulicErosion] has to tell a real
     * gradient from this staircase: a fall of one step a cell is the fill's own, not the ground's.
     */
    const val FLAT_GRADIENT_STEP = 1e-6f

    /**
     * Raises every hollow to the level of its lowest outlet, so no cell is left without a downhill
     * path. Priority-flood (Barnes et al.): start from the outlets and work inward, always taking
     * the lowest cell still on the frontier.
     *
     * The sea is the outlet, and it joins the flood at its own level rather than being treated as
     * a rim of already-drained land. That distinction is the whole of the rule below: touching
     * water is not the same as being able to drain into it. The enclosed-water rule turns
     * unreachable sea into land without raising it, so a converted cell keeps a level *below* the
     * shoreline; where a whole component of such cells is ringed by water standing higher, it has
     * no cell that could be called an outlet at all, and seeding only from the land would leave it
     * unvisited with its sinks intact and its rivers draining to nothing. Letting the water in
     * raises that component to its spill level, which is what a priority flood over the whole grid
     * does anyway. Ordinary coasts are untouched: land at or above the shoreline is reached from
     * the lower water beside it and keeps its own elevation, exactly as seeding it directly did.
     */
    fun fillDepressions(
        width: Int,
        height: Int,
        isLand: BooleanArray,
        elevation: FloatField
    ): FloatField {
        val filled = elevation.copy()
        val visited = BooleanArray(width * height)
        val heap = LongMinHeap(width * 4)

        fun seedOutlet(cell: Int) {
            if (visited[cell]) return
            visited[cell] = true
            heap.push(encode(filled.data[cell], cell))
        }

        // Water is never raised, so it is marked before anything is pushed and the flood can only
        // ever move from it onto land.
        for (cell in visited.indices) {
            if (!isLand[cell]) visited[cell] = true
        }

        // Outlets: the sea wherever it meets land, plus land running off the top and bottom edges.
        // Only water that touches land is pushed. Open water deeper in has nothing but visited
        // neighbours whatever order it is popped in, so leaving it out is an economy and not a
        // change: the flood reaches exactly the same cells at exactly the same levels.
        for (row in 0 until height) {
            for (column in 0 until width) {
                val cell = row * width + column
                if (!isLand[cell]) {
                    var touchesLand = false
                    forEachNeighbour(width, height, column, row) { neighbour ->
                        if (isLand[neighbour]) touchesLand = true
                    }
                    if (touchesLand) heap.push(encode(filled.data[cell], cell))
                    continue
                }
                if (row == 0 || row == height - 1) seedOutlet(cell)
            }
        }

        while (!heap.isEmpty()) {
            val lowest = heap.pop()
            val cell = decodeIndex(lowest)
            val cellElevation = filled.data[cell]
            val cellColumn = cell % width
            val cellRow = cell / width

            forEachNeighbour(width, height, cellColumn, cellRow) { neighbour ->
                if (!visited[neighbour]) {
                    visited[neighbour] = true
                    if (filled.data[neighbour] <= cellElevation) {
                        filled.data[neighbour] = cellElevation + FLAT_GRADIENT_STEP
                    }
                    heap.push(encode(filled.data[neighbour], neighbour))
                }
            }
        }
        return filled
    }

    /**
     * Which neighbour each land cell drains into, or -1 where the water leaves the map.
     *
     * Not simply the steepest of the eight, because eight bearings cannot express a slope that
     * faces between two of them. Over ground that is smooth at the cell scale — the apron below a
     * range, laid by deposition and worn by the thermal relaxation until it is a plane — every cell
     * in turn faces the same way, the same neighbour wins by the same margin, and the water runs
     * dead straight for as far as the plane goes. That is a property of the grid and not of the
     * ground: a river crossing an apron wanders, because the real apron has relief at scales a
     * six-kilometre cell cannot hold. Left alone, the stream power along such a run cuts a ruled
     * trench, the trench ponds behind its own lip, and the map grows a lake shaped like a ruler.
     * See `GEOGRAPHY.md`, "A river crossing smooth ground does not run in a ruled line".
     *
     * So the direction is taken from the surface rather than from the neighbour list, by Tarboton's
     * method (1997, *Water Resources Research* 33(2), 309-319): the cell and each cardinal
     * neighbour with one of the two diagonals flanking it make a triangular facet, eight in all,
     * and the steepest descent over the steepest of those facets is the direction the water really
     * takes. That direction points somewhere between the facet's two neighbours, at some share of
     * the way from the cardinal to the diagonal.
     *
     * The facets are the ground's, not a square's. This map's cells are [cellHeightInCellWidths] as
     * tall as they are wide, so a facet's leg out to a cardinal neighbour and its leg on to the
     * diagonal are a cell width and a row's height, one way round or the other, and each slope is
     * taken over its own leg; the facet's far edge then subtends its own angle, and the share is
     * where along that edge the descent crosses it. Taken over a square's legs, a plane falling at
     * 45 degrees on the ground routed at 14 (docs/DESIGN_LEDGER.md, Fix 2).
     *
     * The whole flow then goes to *one* of the two, drawn at that share — Fairfield and Leymarie's
     * Rho8 (1991, *Water Resources Research* 27(5), 709-717), which was written for this defect.
     * Every stage below this one needs a single receiver: the drainage is a forest, a river cannot
     * fork, and the incision walks the tree from the outlets upstream. So the split is spent on
     * *which* cell rather than on how much, and a reach whose true bearing lies four fifths of the
     * way toward the diagonal takes the diagonal four steps in five — the same slope, without the
     * ruled line. Where the facet's descent points out of the facet, which is what an incised
     * channel always does, the answer collapses to the steepest neighbour exactly.
     *
     * The draw is a per-cell hash of the world's seed, and it shares that hash with
     * [LakeWaterBalance.jitter] and nothing else. The jitter's own field is smoothed over eight
     * cells, deliberately, because its job is to give ground with *no* gradient one to follow and a
     * value that changed from cell to cell would leave the path staggering on the spot. Reading the
     * draw off that same smooth field was tried here and does nothing at all: a reach twenty cells
     * long sits inside one period, draws one value, and rounds every one of its bearings the same
     * way, which is the ruled line again — measured on seed 42 at 512, 39 ruled runs against the
     * plain rule's 39. The two questions want opposite fields. This one wants relief between one
     * cell and the next, which is sub-grid and so uncorrelated at this scale.
     *
     * Two invariants hold, and everything downstream rests on them. The receiver is always strictly
     * lower on [filled] than the cell itself — where the drawn share is strictly between the ends,
     * the diagonal is below the cardinal and the cardinal below the cell — so the network is still
     * a forest with no cycles, [heightOrder] still places a cell before its receiver, and
     * [drainageOrder] still terminates. And a cell has a receiver exactly where the plain
     * steepest-descent rule gave it one, so the fill's promise that every land cell can reach the
     * sea is untouched: no new sink, no river that stops inland.
     *
     * [seed] is the world's, so a world's courses are its own and are the same on every platform;
     * the draw is integer mixing and a comparison, with no transcendental in it.
     *
     * @param byFacet false for the plain steepest-of-eight rule this replaced, which is the control
     *   the straight-bar census is measured against. See [com.cartogenesis.worldgen.model.WorldGenConfig.facetRouting].
     * @param overPotential false to route the fill's flats over its own staircase rather than over
     *   the potential [FlatRouting] lays, the control the ruled-run census over raised ground is
     *   measured against. See [com.cartogenesis.worldgen.model.WorldGenConfig.flatPotential].
     */
    fun flowDirections(
        width: Int,
        height: Int,
        isLand: BooleanArray,
        elevation: FloatField,
        filled: FloatField,
        seed: Long,
        cellHeightInCellWidths: Double,
        byFacet: Boolean = true,
        overPotential: Boolean = true
    ): IntArray {
        val receiver = IntArray(width * height) { -1 }
        // The filled field, except across the flats the fill raised, where it is the potential
        // [FlatRouting] lays: see there for why a staircase cannot be routed across without a ruler.
        val routingSurface =
            if (overPotential) FlatRouting.surfaceOf(width, height, isLand, elevation, filled, seed, cellHeightInCellWidths).heights
            else DoubleArray(width * height) { (if (isLand[it]) filled.data[it] else elevation.data[it]).toDouble() }
        val trueGround = elevation.data
        val steps = GroundSteps(cellHeightInCellWidths)
        for (row in 0 until height) {
            for (column in 0 until width) {
                val cell = row * width + column
                if (!isLand[cell]) continue
                val here = routingSurface[cell]
                if (!byFacet) {
                    receiver[cell] = steepestNeighbourOf(
                        width, height, isLand, trueGround, routingSurface, column, row, steps
                    )
                    continue
                }

                var steepestFacetSlope = 0.0
                var facetCardinal = -1
                var facetDiagonal = -1
                var diagonalShare = 0.0

                for (side in CARDINAL_COLUMN_STEP.indices) {
                    val cardinalColumnStep = CARDINAL_COLUMN_STEP[side]
                    val cardinalRowStep = CARDINAL_ROW_STEP[side]
                    val cardinal =
                        neighbourAt(width, height, column + cardinalColumnStep, row + cardinalRowStep)
                    if (cardinal < 0) continue
                    val cardinalDrop = here - routingSurface[cardinal]
                    // The facet's two legs on the ground: out to the cardinal, and on from it to
                    // the diagonal, square to the first.
                    val toTheCardinal = steps.of(cardinalColumnStep, cardinalRowStep).toDouble()
                    val onToTheDiagonal =
                        (if (cardinalColumnStep == 0) steps.eastWest else steps.northSouth).toDouble()

                    for (turn in -1..1 step 2) {
                        val diagonalColumnStep =
                            if (cardinalColumnStep == 0) turn else cardinalColumnStep
                        val diagonalRowStep = if (cardinalRowStep == 0) turn else cardinalRowStep
                        val diagonal = neighbourAt(
                            width, height, column + diagonalColumnStep, row + diagonalRowStep
                        )
                        if (diagonal < 0) continue
                        val diagonalFall = here - routingSurface[diagonal]
                        val diagonalSlope = diagonalFall / steps.diagonal
                        // Tarboton's two components: the fall to the cardinal over the first leg,
                        // and the further fall from the cardinal on to the diagonal over the second.
                        val outwardFall = diagonalFall - cardinalDrop
                        val cardinalSlope = cardinalDrop / toTheCardinal
                        val outwardSlope = outwardFall / onToTheDiagonal

                        // Where along the far edge, from the cardinal to the diagonal, the descent
                        // crosses it: the tangent of its angle off the first leg, over the tangent
                        // of the angle the far edge subtends. On a square's legs this is the ratio
                        // of the two falls.
                        val shareAlongTheEdge = if (cardinalDrop > 0.0) {
                            outwardFall * toTheCardinal * toTheCardinal /
                                (cardinalDrop * onToTheDiagonal * onToTheDiagonal)
                        } else {
                            1.0
                        }

                        // Where the descent points out of the facet it is clamped to the edge it
                        // left by: to the cardinal when the diagonal is no lower than the cardinal,
                        // and to the diagonal when the cardinal is not downhill at all or the
                        // descent crosses the far edge past the diagonal.
                        val clampedToTheCardinal = cardinalDrop > 0.0 && outwardFall <= 0.0
                        val clampedToTheDiagonal = !clampedToTheCardinal && shareAlongTheEdge >= 1.0
                        val facetSlope = when {
                            clampedToTheCardinal -> cardinalSlope
                            clampedToTheDiagonal -> diagonalSlope
                            else -> sqrt(cardinalSlope * cardinalSlope + outwardSlope * outwardSlope)
                        }
                        val shareTowardTheDiagonal = when {
                            clampedToTheCardinal -> 0.0
                            clampedToTheDiagonal -> 1.0
                            else -> shareAlongTheEdge
                        }
                        if (facetSlope > steepestFacetSlope) {
                            steepestFacetSlope = facetSlope
                            facetCardinal = cardinal
                            facetDiagonal = diagonal
                            diagonalShare = shareTowardTheDiagonal
                        }
                    }
                }

                receiver[cell] = when {
                    steepestFacetSlope <= 0.0 -> -1
                    diagonalShare <= 0.0 -> facetCardinal
                    diagonalShare >= 1.0 -> facetDiagonal
                    subGridDraw(column, row, seed) < diagonalShare -> facetDiagonal
                    else -> facetCardinal
                }
            }
        }
        return receiver
    }

    /**
     * The steepest of the eight neighbours, which is what the water followed everywhere before the
     * facet rule below it, and still follows on ground the fill had to raise.
     *
     * Also the whole rule when [flowDirections] is asked for it, because a guard that has only ever
     * been green proves nothing: the straight-bar census is run against this as well as against the
     * facet's answer, and asserts that this one fails it. See `docs/DESIGN_LEDGER.md`, F18.
     */
    private fun steepestNeighbourOf(
        width: Int,
        height: Int,
        isLand: BooleanArray,
        trueGround: FloatArray,
        routingSurface: DoubleArray,
        column: Int,
        row: Int,
        steps: GroundSteps
    ): Int {
        var steepest = -1
        var steepestDrop = 0.0
        val here = routingSurface[row * width + column]
        forEachNeighbourWithDistance(width, height, column, row, steps) { neighbour, distance ->
            // Ocean cells carry their true elevation on the surface, so coastal cells drain to sea.
            val there = routingSurface[neighbour]
            val drop = (here - there) / distance
            if (drop > steepestDrop) {
                steepestDrop = drop
                steepest = neighbour
            }
        }
        return steepest
    }

    /**
     * A number in 0..1 standing for the relief a grid this coarse cannot hold, which is what
     * decides a step the slope itself leaves open.
     *
     * The same smooth field [LakeWaterBalance.jitter] is built on, salted so the two decisions are
     * independent, and smooth for the reason that one is: a value that changes from cell to cell
     * makes each cell round its bearing on its own and the course staggers, while a field that
     * turns over a few cells rounds a whole reach one way and the next reach the other, which is a
     * course that meanders. It also keeps neighbouring flow lines agreeing with each other, so a
     * hillside's drainage stays the coherent thing the terrain says it is instead of being
     * scrambled cell by cell — which matters more than the wander itself, because everything below
     * this reads that network: the basins, their spills, and the sills the outlet pass has to cut.
     */
    private fun subGridDraw(column: Int, row: Int, seed: Long): Double =
        ((seededNoise(column, row, seed xor SUB_GRID_DRAW_SALT) + 1f) * 0.5f).toDouble()

    private const val SUB_GRID_DRAW_SALT = 0x5f3a91c7_2b64d8e3L

    /**
     * Value noise in -1..1 on a lattice of [SMOOTH_FIELD_PERIOD_CELLS] cells, smoothstepped between the
     * corners so the field has no creases on the lattice lines for a path to follow.
     *
     * Shared: [LakeWaterBalance.jitter] scales it to nudge exactly-flat ground, and [subGridDraw]
     * reads it as a quantile. Integer mixing at the corners, so every platform agrees.
     */
    fun smoothSeededField(width: Int, column: Int, row: Int, seed: Long): Float {
        val latticeColumns = (width / SMOOTH_FIELD_PERIOD_CELLS).coerceAtLeast(1)
        val cornerColumn = column / SMOOTH_FIELD_PERIOD_CELLS
        val cornerRow = row / SMOOTH_FIELD_PERIOD_CELLS
        val alongColumn =
            (column - cornerColumn * SMOOTH_FIELD_PERIOD_CELLS).toFloat() / SMOOTH_FIELD_PERIOD_CELLS
        val alongRow =
            (row - cornerRow * SMOOTH_FIELD_PERIOD_CELLS).toFloat() / SMOOTH_FIELD_PERIOD_CELLS
        val easedAlongColumn = alongColumn * alongColumn * (3f - 2f * alongColumn)
        val easedAlongRow = alongRow * alongRow * (3f - 2f * alongRow)
        val west = cornerColumn % latticeColumns
        val east = (cornerColumn + 1) % latticeColumns
        val northEdge = lerp(
            seededNoise(west, cornerRow, seed), seededNoise(east, cornerRow, seed), easedAlongColumn
        )
        val southEdge = lerp(
            seededNoise(west, cornerRow + 1, seed),
            seededNoise(east, cornerRow + 1, seed),
            easedAlongColumn
        )
        return lerp(northEdge, southEdge, easedAlongRow)
    }

    private fun lerp(from: Float, to: Float, at: Float): Float = from + (to - from) * at

    /** Cells across one period of [smoothSeededField]: short enough to bend a course inside one
     * reach. */
    private const val SMOOTH_FIELD_PERIOD_CELLS = 8

    /**
     * One lattice point's value in -1..1.
     *
     * Integer mixing only — the multiply-shift-xor rounds are SplitMix64's, chosen because they
     * are the same on every platform where a floating-point hash would not be. The last line takes
     * the top 24 bits and maps them onto -1..1, hence [HALF_OF_24_BITS].
     *
     * Shared with [LakeWaterBalance.jitter], which samples it on a coarse lattice and smooths
     * between the samples; [subGridDraw] takes it per cell.
     */
    fun seededNoise(latticeColumn: Int, latticeRow: Int, seed: Long): Float {
        var mixed = seed xor
            (latticeColumn.toLong() * -0x61c8864680b583ebL) xor
            (latticeRow.toLong() * 0x27220a95_1d5a2b1fL)
        mixed = mixed xor (mixed ushr 30)
        mixed *= -0x40a7b892e31b1a47L
        mixed = mixed xor (mixed ushr 27)
        mixed *= -0x6b2fb644ecceee15L
        mixed = mixed xor (mixed ushr 31)
        return (mixed ushr (Long.SIZE_BITS - HASH_BITS)).toInt() / HALF_OF_24_BITS - 1f
    }

    /** Bits of the mixed hash kept, and half that range, which is what centres it on zero. */
    private const val HASH_BITS = 24
    private const val HALF_OF_24_BITS = 8388608f

    /** The cell index at these coordinates, wrapping east to west, or -1 off the poles. */
    private fun neighbourAt(width: Int, height: Int, column: Int, row: Int): Int {
        if (row < 0 || row >= height) return -1
        var wrappedColumn = column % width
        if (wrappedColumn < 0) wrappedColumn += width
        return row * width + wrappedColumn
    }

    /** East, north, west, south: the four a facet is built around, each flanked by two diagonals. */
    private val CARDINAL_COLUMN_STEP = intArrayOf(1, 0, -1, 0)
    private val CARDINAL_ROW_STEP = intArrayOf(0, -1, 0, 1)

    /**
     * Every land cell, lowest first, ordered by the depression-filled surface.
     *
     * Walking this backwards visits each cell before anything it drains into, which is what lets a
     * single pass carry a running total — water, or the sediment that water is carrying —
     * downstream without recursion and without a stack that a long river could overflow.
     */
    fun heightOrder(
        width: Int,
        height: Int,
        isLand: BooleanArray,
        filled: FloatField,
        landCellCount: Int
    ): IntArray {
        // Elevation packed above the cell index so a plain primitive sort orders cells by height.
        val ordered = LongArray(landCellCount)
        var written = 0
        for (cell in 0 until width * height) {
            if (!isLand[cell]) continue
            ordered[written++] = encode(filled.data[cell], cell)
        }
        ordered.sort()
        return IntArray(landCellCount) { decodeIndex(ordered[it]) }
    }

    /**
     * Adds each cell's own contribution to everything downstream of it.
     *
     * Walked in order of height rather than by recursion, so a cell's total is final before it
     * passes anything on, and no stack can overflow on a long river.
     */
    fun accumulate(
        width: Int,
        height: Int,
        isLand: BooleanArray,
        filled: FloatField,
        flowTarget: IntArray,
        landCellCount: Int,
        weightOf: (Int) -> Float
    ): FloatField {
        val accumulation = FloatField(width, height)
        for (cell in 0 until width * height) {
            if (isLand[cell]) accumulation.data[cell] = weightOf(cell)
        }

        // Sources first and mouths last, read off the network itself rather than off the filled
        // field: across a flat the routing follows [FlatRouting]'s potential and not the fill's
        // staircase, so a receiver is no longer always lower on the fill than the cell draining
        // into it, and a walk ordered by height would pass water to a cell it had already emptied.
        val order = drainageOrder(width, height, isLand, flowTarget, landCellCount)
        for (cell in order) {
            val receiver = flowTarget[cell]
            if (receiver >= 0 && isLand[receiver]) {
                accumulation.data[receiver] += accumulation.data[cell]
            }
        }
        return accumulation
    }

    /**
     * The basins the fill had to raise, one entry per basin.
     *
     * Every array here is indexed by basin rather than by cell, so nothing that reads it has to
     * carry a grid-sized side table to work out which hollow a cell belongs to.
     */
    class Spillways(
        val count: Int,
        /** The rim cell each basin spills over, or -1 where it has none the water can cut. */
        val spill: IntArray,
        /** The level of that rim: the height the fill raised the whole basin to. */
        val level: FloatArray,
        /** The lowest true ground in the basin: the level at which it holds no water at all. */
        val floor: FloatArray,
        val cells: IntArray,
        /** The largest basin by area, and how deep the fill stands over its lowest ground. */
        val largest: Int,
        val largestCells: Int,
        val largestDepth: Float,
        /** The deepest fill anywhere on the map: how far the water stands over the lowest ground. */
        val deepest: Float
    )

    /**
     * Finds every depression the fill had to raise and the rim cell each one spills over.
     *
     * A basin filled to its rim is a lake with an overflow, and the overflow has a knickpoint at
     * the lip. What decides the lake's size is how fast that lip wears down, not how big the hollow
     * behind it is — Bonneville emptied through Red Rock Pass because the pass gave way, and what
     * was left was Great Salt Lake. So the interesting cell is the rim, and the interesting
     * quantity is how far it may fall: to the floor of its own basin and no further, because below
     * that there is no lake left to drain.
     *
     * Ponded cells are grouped by connectivity, seeded in ascending index order and walked with an
     * explicit stack, so the labelling is one specific labelling rather than any valid one. The
     * spill of a basin is the lowest cell outside it that a cell inside it drains to, ties broken
     * on the cell index — no set, no map, and nothing that depends on a hash.
     *
     * @param elevation the true ground, before the fill raised anything.
     * @param filled the surface the routing actually runs on.
     * @param pondDepth how far the fill must stand above the ground before a cell counts as water
     *   rather than as a flat the epsilon-fill nudged.
     */
    fun spillways(
        width: Int,
        height: Int,
        isLand: BooleanArray,
        elevation: FloatArray,
        filled: FloatArray,
        flowTarget: IntArray,
        pondDepth: Float
    ): Spillways {
        val cellCount = width * height
        val ponded = BooleanArray(cellCount) { isLand[it] && filled[it] - elevation[it] > pondDepth }
        val seen = BooleanArray(cellCount)
        var stack = IntArray(1024)

        var spills = IntArray(64)
        var levels = FloatArray(64)
        var floors = FloatArray(64)
        var counts = IntArray(64)
        var basins = 0

        var largest = -1
        var largestCells = 0
        var largestDepth = 0f
        var deepest = 0f

        for (start in 0 until cellCount) {
            if (!ponded[start] || seen[start]) continue

            var stackTop = 0
            stack[stackTop++] = start
            seen[start] = true

            var cells = 0
            var basinFloor = Float.MAX_VALUE
            var spill = -1
            var spillLevel = Float.MAX_VALUE

            while (stackTop > 0) {
                val cell = stack[--stackTop]
                cells++
                if (elevation[cell] < basinFloor) basinFloor = elevation[cell]

                // Where this cell's water leaves the basin. The lowest such exit is the rim the
                // fill levelled the whole basin up to; the others are higher ground the epsilon
                // gradient happens to touch.
                val receiver = flowTarget[cell]
                if (receiver >= 0 && isLand[receiver] && !ponded[receiver]) {
                    val exitLevel = filled[receiver]
                    if (exitLevel < spillLevel || (exitLevel == spillLevel && receiver < spill)) {
                        spillLevel = exitLevel
                        spill = receiver
                    }
                }

                forEachNeighbour(width, height, cell % width, cell / width) { neighbour ->
                    if (ponded[neighbour] && !seen[neighbour]) {
                        seen[neighbour] = true
                        if (stackTop == stack.size) stack = stack.copyOf(stack.size * 2)
                        stack[stackTop++] = neighbour
                    }
                }
            }

            if (basins == spills.size) {
                spills = spills.copyOf(basins * 2)
                levels = levels.copyOf(basins * 2)
                floors = floors.copyOf(basins * 2)
                counts = counts.copyOf(basins * 2)
            }
            spills[basins] = spill
            levels[basins] = if (spill >= 0) spillLevel else basinFloor
            floors[basins] = basinFloor
            counts[basins] = cells
            val fillDepth = if (spill >= 0) spillLevel - basinFloor else 0f
            if (cells > largestCells) {
                largest = basins
                largestCells = cells
                largestDepth = fillDepth
            }
            if (fillDepth > deepest) deepest = fillDepth
            basins++
        }

        return Spillways(
            basins, spills, levels, floors, counts, largest, largestCells, largestDepth, deepest
        )
    }

    /**
     * Land cells in an order where nothing appears before everything that drains into it — sources
     * first, mouths last.
     *
     * [heightOrder] is nearly this and is not good enough for sediment. Its key biases the
     * elevation by four before taking the raw bits, and at that magnitude a float's last place is
     * about 5e-7, so two cells whose real heights differ by less than that sort as equal and the
     * tie falls to the cell index. Water does not care — flow accumulation off by one cell in ten
     * thousand is invisible — but a load handed to a cell the walk has already passed is *lost*,
     * and the mass budget caught it: two to ten cells a round, three percent of the sediment.
     *
     * A topological sort of the flow network has no such tolerance. D8 gives each cell one
     * downstream neighbour and every step is strictly downhill, so the network is a forest and
     * Kahn's algorithm orders it exactly. Cells are seeded and drained in ascending index order, so
     * the result is one specific order rather than any valid one.
     */
    fun drainageOrder(
        width: Int,
        height: Int,
        isLand: BooleanArray,
        flowTarget: IntArray,
        landCellCount: Int
    ): IntArray {
        val cellCount = width * height
        val feeding = IntArray(cellCount)
        for (cell in 0 until cellCount) {
            if (!isLand[cell]) continue
            val receiver = flowTarget[cell]
            if (receiver >= 0 && isLand[receiver]) feeding[receiver]++
        }

        val order = IntArray(landCellCount)
        var tail = 0
        for (cell in 0 until cellCount) {
            if (isLand[cell] && feeding[cell] == 0) order[tail++] = cell
        }
        var head = 0
        while (head < tail) {
            val receiver = flowTarget[order[head++]]
            if (receiver >= 0 && isLand[receiver] && --feeding[receiver] == 0) order[tail++] = receiver
        }

        // A cycle would leave cells unplaced, and the strictly-downhill rule says there can be
        // none. Belt and braces: anything left over still gets its turn, at the end.
        if (tail < landCellCount) {
            for (cell in 0 until cellCount) {
                if (isLand[cell] && feeding[cell] > 0) order[tail++] = cell
            }
        }
        return order
    }

    /**
     * Packs elevation into the high bits and the cell index into the low, so sorting or heaping the
     * longs orders cells by height.
     *
     * The bias exists because elevation is measured from the shoreline and so goes negative at sea,
     * and the sign bit of a negative float does not sort as a smaller integer. Adding a constant
     * first keeps every value positive and the ordering honest.
     */
    fun encode(elevation: Float, index: Int): Long {
        val bits = (elevation + ELEVATION_BIAS).toRawBits()
        return (bits.toLong() shl 32) or index.toLong()
    }

    fun decodeIndex(encoded: Long): Int = (encoded and 0xFFFFFFFFL).toInt()

    /**
     * Added to an elevation before its bits are packed by [encode].
     *
     * Elevation is measured from the shoreline and so goes negative at sea, and the sign bit of a
     * negative float does not sort as a smaller integer. Four is comfortably above the deepest
     * water the shoreline-relative field can hold, which is -1.
     */
    private const val ELEVATION_BIAS = 4f

    inline fun forEachNeighbour(
        width: Int,
        height: Int,
        column: Int,
        row: Int,
        action: (index: Int) -> Unit
    ) {
        for (rowStep in -1..1) {
            val neighbourRow = row + rowStep
            if (neighbourRow < 0 || neighbourRow >= height) continue
            for (columnStep in -1..1) {
                if (columnStep == 0 && rowStep == 0) continue
                var neighbourColumn = (column + columnStep) % width
                if (neighbourColumn < 0) neighbourColumn += width
                action(neighbourRow * width + neighbourColumn)
            }
        }
    }

    /**
     * [forEachNeighbour], handing each neighbour's distance on the ground in cell widths with it:
     * [steps]' length for a step along a row, down a column or on a diagonal.
     */
    inline fun forEachNeighbourWithDistance(
        width: Int,
        height: Int,
        column: Int,
        row: Int,
        steps: GroundSteps,
        action: (index: Int, distance: Float) -> Unit
    ) {
        for (rowStep in -1..1) {
            val neighbourRow = row + rowStep
            if (neighbourRow < 0 || neighbourRow >= height) continue
            for (columnStep in -1..1) {
                if (columnStep == 0 && rowStep == 0) continue
                var neighbourColumn = (column + columnStep) % width
                if (neighbourColumn < 0) neighbourColumn += width
                action(neighbourRow * width + neighbourColumn, steps.of(columnStep, rowStep))
            }
        }
    }
}
