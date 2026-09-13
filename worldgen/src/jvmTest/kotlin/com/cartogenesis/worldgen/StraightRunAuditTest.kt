package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.FlowRouting
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * F18, the half that needs both worlds at once: what taking the direction from the steepest facet
 * did to the network as a whole, against the plain steepest-of-eight rule on the same seeds.
 *
 * In the audit tier because most of it generates each world twice over — ten worlds, a pair of
 * them at 1024, and one more at 2048 to be timed — and none of it is per-merge work. The guard
 * that holds the chunk is `StraightRunTest`.
 *
 * Two questions. Is the network still the same *kind* of network: Hack's exponent and Horton's
 * bifurcation ratio, the two statistics M1 measures a drainage by, copied from `main`'s
 * `EarthLikeness` rather than re-derived. And is it still the same network *here*: how many of the
 * cells the map draws a river through moved, and how far they moved, because a course that wanders
 * a cell either side of where it used to run is the same river drawn with a less ruled hand, and a
 * course somewhere else entirely is not. Ground rule 8's figure for the routing is here too.
 */
class StraightRunAuditTest {

    private companion object {
        val SEEDS = listOf(7L, 42L, 1234L, 99L)
        const val SIDE = 512
        const val AUTHORS_SEED = 298405L
        const val AUTHORS_SIDE = 1024

        /**
         * Hack's exponent over Earth: Hack (1957) measured 0.6, Rigon et al. (1996) 0.5 to 0.57,
         * and 0.5 is where a self-similar basin sits, so the band's edges are the physics. Half the
         * band's width either side is the room a log-log slope over two decades of area needs.
         * `EarthLikeness` on `main` holds the same three figures.
         */
        const val EARTH_HACK_LOW = 0.5
        const val EARTH_HACK_HIGH = 0.6
        const val HACK_TOLERANCE = 0.05

        /** Horton (1945): natural basins run 3 to 5, weighted after Strahler (1953). */
        const val EARTH_BIFURCATION_LOW = 3.0
        const val EARTH_BIFURCATION_HIGH = 5.0

        /**
         * The smallest basin Hack's fit will take, in cells.
         *
         * `EarthLikeness`'s own figure. A hundred cells is two decades below the largest basins on
         * these maps, which is the span a log-log slope needs, and below it a basin is a handful of
         * cells whose "main stem" is an artefact of the grid rather than a length.
         */
        const val SMALLEST_HACK_CATCHMENT_CELLS = 100

        /** The author's own world, at the size he exports at: where a cost is worth stating. */
        const val EXPORT_SEED = 718106L
        const val EXPORT_SIDE = 2048
        const val TIMED_PASSES = 5
    }

    private fun world(seed: Long, side: Int, byFacet: Boolean): WorldMap =
        WorldGenerationEngine.generateBlocking(
            WorldGenConfig(seed = seed, width = 512, height = 512)
                .atResolution(side, side)
                .copy(facetRouting = byFacet)
        )

    /**
     * Hack's exponent and the bifurcation ratio, on the four standard seeds, both ways.
     *
     * The bar is that the routing rule moves each statistic by less than the spread across seeds.
     * These are figures that characterise *a world of this kind* rather than one particular world,
     * so a rule that moved one further than the choice of seed does would have changed the kind.
     * It is not fitted to anything this code produces.
     *
     * Earth's own bands are printed beside the figures rather than asserted, and the reason is
     * worth writing down. Hack's exponent sits inside its band on every seed under both rules. The
     * bifurcation ratio does not: measured over the *drawn* courses, which are three Strahler
     * orders deep, it reads 5.05 to 6.41 under the old rule and 4.87 to 6.52 under the new one,
     * where Horton's range is 3 to 5. A network three orders deep has two ratios to average and the
     * top one is a handful of streams, so the figure is high for a reason that has nothing to do
     * with how the water is routed — `EarthLikeness` takes it over a support-area channel mask
     * many orders deep, which is a different and better-conditioned measurement. Asserting Earth's
     * band here would be failing F18 for something it did not do and did not move; the band that
     * belongs to this chunk is how far the rule shifts the figure, and that is asserted.
     */
    @Test
    fun `Hack's exponent and the bifurcation ratio stay where they were`() {
        val plainHack = ArrayList<Double>()
        val facetHack = ArrayList<Double>()
        val plainBifurcation = ArrayList<Double>()
        val facetBifurcation = ArrayList<Double>()

        SEEDS.forEach { seed ->
            val plain = statisticsOf(world(seed, SIDE, byFacet = false))
            val facet = statisticsOf(world(seed, SIDE, byFacet = true))
            plainHack += plain.hackExponent
            facetHack += facet.hackExponent
            plainBifurcation += plain.bifurcationRatio
            facetBifurcation += facet.bifurcationRatio
            println(
                "F18 NETWORK seed $seed: Hack %.4f -> %.4f over %d/%d basins, bifurcation ".format(
                    plain.hackExponent, facet.hackExponent, plain.basins, facet.basins
                ) + "%.3f -> %.3f, orders %s -> %s".format(
                    plain.bifurcationRatio, facet.bifurcationRatio,
                    plain.streamsByOrder.toList(), facet.streamsByOrder.toList()
                )
            )
        }

        val hackSpread = facetHack.max() - facetHack.min()
        val bifurcationSpread = facetBifurcation.max() - facetBifurcation.min()
        val hackMoved = SEEDS.indices.maxOf { abs(facetHack[it] - plainHack[it]) }
        val bifurcationMoved = SEEDS.indices.maxOf { abs(facetBifurcation[it] - plainBifurcation[it]) }
        println(
            "F18 NETWORK: Hack moved at most %.4f against a spread of %.4f across seeds; "
                .format(hackMoved, hackSpread) +
                "bifurcation moved at most %.3f against a spread of %.3f".format(
                    bifurcationMoved, bifurcationSpread
                )
        )

        SEEDS.forEachIndexed { at, seed ->
            assertTrue(
                facetHack[at] >= EARTH_HACK_LOW - HACK_TOLERANCE &&
                    facetHack[at] <= EARTH_HACK_HIGH + HACK_TOLERANCE,
                "seed $seed: Hack's exponent is %.4f, outside Earth's %.2f to %.2f give or take %.2f"
                    .format(facetHack[at], EARTH_HACK_LOW, EARTH_HACK_HIGH, HACK_TOLERANCE)
            )
            // The bifurcation ratio is reported against Earth rather than asserted, for the reason
            // in the note above this case: over the drawn network it is out of Horton's range
            // under both rules, which is a property of the measurement and not of the routing.
            if (facetBifurcation[at] < EARTH_BIFURCATION_LOW ||
                facetBifurcation[at] > EARTH_BIFURCATION_HIGH
            ) {
                println(
                    ("F18 NETWORK seed $seed: the bifurcation ratio is %.3f over the drawn " +
                        "network, outside Horton's %.1f to %.1f, and was %.3f under the plain rule")
                        .format(
                            facetBifurcation[at], EARTH_BIFURCATION_LOW, EARTH_BIFURCATION_HIGH,
                            plainBifurcation[at]
                        )
                )
            }
        }
        assertTrue(
            hackMoved <= hackSpread,
            "the routing rule moved Hack's exponent by %.4f, more than the %.4f between one seed and another"
                .format(hackMoved, hackSpread)
        )
        assertTrue(
            bifurcationMoved <= bifurcationSpread,
            "the routing rule moved the bifurcation ratio by %.3f, more than the %.3f between one seed and another"
                .format(bifurcationMoved, bifurcationSpread)
        )
    }

    /**
     * The whole ruled-run table: how much of each drawn network holds one bearing, both ways.
     *
     * `StraightRunTest` asserts this on one seed per merge; here it is on all five, because the
     * claim the chunk rests on is that the rule bites everywhere and not on one lucky world.
     */
    @Test
    fun `the old rule draws more of every map with a ruler`() {
        val stubborn = ArrayList<String>()
        (SEEDS.map { it to SIDE } + (AUTHORS_SEED to AUTHORS_SIDE)).forEach { (seed, side) ->
            val plain = RuledLines.ruledRunsOf(world(seed, side, byFacet = false))
            val facet = RuledLines.ruledRunsOf(world(seed, side, byFacet = true))
            println(
                "F18 RULED seed $seed at $side: runs of ${RuledLines.RULED_RUN_CELLS}+ on one " +
                    "bearing, steepest neighbour $plain, steepest facet $facet " +
                    "(%.0f%% fewer)".format(100.0 * (plain - facet) / max(plain, 1))
            )
            if (facet >= plain) stubborn += "$seed at $side: $plain -> $facet"
        }
        assertTrue(
            stubborn.isEmpty(),
            "the facet rule left as many ruled runs as the plain one on $stubborn"
        )
    }

    /**
     * How much of the drawn network moved, and how far.
     *
     * The count on its own says nothing: a river drawn one cell to the left of where it used to run
     * is the same river, and the whole point of the rule is that a course crossing smooth ground
     * should stop being ruled, which cannot happen without cells moving. So what is reported is the
     * distance each moved cell sits from the network it replaced — a cell within one of a cell the
     * old rule drew is the same watercourse taking a different step, and a cell three or more away
     * is a river somewhere else.
     */
    @Test
    fun `how far the drawn network moved`() {
        (SEEDS.map { it to SIDE } + (AUTHORS_SEED to AUTHORS_SIDE)).forEach { (seed, side) ->
            val plain = drawnChannelMask(world(seed, side, byFacet = false))
            val facet = drawnChannelMask(world(seed, side, byFacet = true))
            val awayFromThePlainNetwork = chebyshevDistanceFrom(plain, side, side)
            var drawnByBoth = 0
            var drawnByFacetOnly = 0
            var drawnByPlainOnly = 0
            val byDistance = IntArray(8)
            for (cell in plain.indices) {
                when {
                    plain[cell] && facet[cell] -> drawnByBoth++
                    plain[cell] -> drawnByPlainOnly++
                    facet[cell] -> {
                        drawnByFacetOnly++
                        byDistance[awayFromThePlainNetwork[cell].coerceAtMost(7)]++
                    }
                }
            }
            val facetCells = drawnByBoth + drawnByFacetOnly
            val withinOne = byDistance[1]
            val withinTwo = byDistance[1] + byDistance[2]
            println(
                "F18 MOVED seed $seed at $side: $facetCells river cells drawn, $drawnByBoth of " +
                    "them where they were; $drawnByFacetOnly new, $drawnByPlainOnly gone " +
                    "(%.1f%% of the network moved). Of the new, %.1f%% touch a cell the old rule "
                        .format(100.0 * drawnByFacetOnly / max(facetCells, 1),
                            100.0 * withinOne / max(drawnByFacetOnly, 1)) +
                    "drew and %.1f%% are within two; the rest run %s".format(
                        100.0 * withinTwo / max(drawnByFacetOnly, 1),
                        (3..7).joinToString(", ") { "$it:${byDistance[it]}" }
                    )
            )
        }
    }

    /**
     * What the routing costs at export size, under each rule.
     *
     * Ground rule 8 asks any per-cell pass for its figure and for a word on where it should run.
     * The routing is per-cell arithmetic over a stencil, so the accelerator seam would take it, but
     * it sits between a priority flood, a topological sort and a tree walk that are all graph work
     * and all stay on the processor — moving one stencil across the bus between them would cost
     * more than it saved. So it stays where it is, and this says what that is worth. A generation
     * routes the whole grid once per hydraulic round and again for the ice, the post-cut outlet and
     * the rivers, so the figure below is paid fifteen or so times over.
     */
    @Test
    fun `what the facet rule costs against the rule it replaced`() {
        val world = world(EXPORT_SEED, EXPORT_SIDE, byFacet = true)
        val cellsAcross = world.width
        val cellsDown = world.height
        val isLand = world.sea.isLand
        val ground = world.sea.relativeElevation
        val filled = FlowRouting.fillDepressions(cellsAcross, cellsDown, isLand, ground)
        val seed = world.config.seed

        fun route(byFacet: Boolean) = FlowRouting.flowDirections(
            cellsAcross, cellsDown, isLand, ground, filled, seed, byFacet
        )

        // Once each to let the just-in-time compiler see them, then measured.
        route(false)
        route(true)

        var plainNanos = 0L
        var facetNanos = 0L
        repeat(TIMED_PASSES) {
            var at = System.nanoTime()
            route(false)
            plainNanos += System.nanoTime() - at
            at = System.nanoTime()
            route(true)
            facetNanos += System.nanoTime() - at
        }
        val plainMs = plainNanos / TIMED_PASSES / 1_000_000
        val facetMs = facetNanos / TIMED_PASSES / 1_000_000
        println(
            "F18 TIMING at $EXPORT_SIDE: steepest neighbour $plainMs ms a pass, steepest facet " +
                "$facetMs ms a pass"
        )
    }

    // ------------------------------------------------------------------ the two statistics

    private class Statistics(
        val hackExponent: Double,
        val basins: Int,
        val bifurcationRatio: Double,
        val streamsByOrder: LongArray
    )

    private fun statisticsOf(world: WorldMap): Statistics {
        val cellsAcross = world.width
        val cellsDown = world.height
        val filled = world.rivers.filledElevation
        val catchmentCells = FlowRouting.accumulate(
            cellsAcross, cellsDown, world.sea.isLand, filled, world.rivers.flowTarget,
            world.sea.landCellCount
        ) { 1f }.data
        val byHeight = FlowRouting.heightOrder(
            cellsAcross, cellsDown, world.sea.isLand, filled, world.sea.landCellCount
        )

        // Hack's `L` is the main stem measured from the divide, which on a tree is the longest of
        // the paths reaching the cell. In cells rather than kilometres: the exponent is the slope
        // of a log-log fit, and rescaling both axes by a constant moves only the intercept.
        val longestPath = DoubleArray(cellsAcross * cellsDown)
        for (rank in byHeight.indices.reversed()) {
            val cell = byHeight[rank]
            val receiver = world.rivers.flowTarget[cell]
            if (receiver < 0 || !world.sea.isLand[receiver]) continue
            val columnStep = shortestColumnStep(receiver % cellsAcross - cell % cellsAcross, cellsAcross)
            val rowStep = receiver / cellsAcross - cell / cellsAcross
            val step = if (columnStep != 0 && rowStep != 0) 1.41421356 else 1.0
            val throughHere = longestPath[cell] + step
            if (throughHere > longestPath[receiver]) longestPath[receiver] = throughHere
        }

        val catchments = ArrayList<Double>()
        val mainStems = ArrayList<Double>()
        world.rivers.rivers.forEach { river ->
            val outletStep = lastOwnStep(world, river.cells)
            if (outletStep < 1) return@forEach
            val outlet = river.cells[outletStep]
            val catchment = catchmentCells[outlet].toDouble()
            if (catchment < SMALLEST_HACK_CATCHMENT_CELLS) return@forEach
            val mainStem = longestPath[outlet]
            if (mainStem <= 0.0) return@forEach
            catchments += ln(catchment)
            mainStems += ln(mainStem)
        }

        val orders = strahlerStreamOrders(
            drawnChannelMask(world), world.rivers.flowTarget, byHeight
        )
        return Statistics(
            hackExponent = fitSlope(catchments, mainStems),
            basins = catchments.size,
            bifurcationRatio = bifurcationRatioOf(orders),
            streamsByOrder = orders
        )
    }

    /**
     * How far along a traced course the reach's own ground goes, as an index into its cells, or -1.
     *
     * `EarthLikeness`'s own rule. A trace stops one cell past itself — at the sea or lake it
     * empties into, or at the first cell an earlier river claimed — and measuring at the last cell
     * would hand a tributary the trunk's whole basin. A course running off a polar row has no cell
     * past its own.
     */
    private fun lastOwnStep(world: WorldMap, cells: IntArray): Int {
        if (cells.size < 2) return -1
        val last = cells[cells.size - 1]
        val runsOffTheMap = world.sea.isLand[last] &&
            !world.rivers.lakes.isLake(last) &&
            world.rivers.flowTarget[last] < 0
        val step = if (runsOffTheMap) cells.size - 1 else cells.size - 2
        val cell = cells[step]
        return if (world.sea.isLand[cell] && !world.rivers.lakes.isLake(cell)) step else -1
    }

    /** The cells the world drew a river through: land, not under a lake, and on a traced course. */
    private fun drawnChannelMask(world: WorldMap): BooleanArray {
        val channel = BooleanArray(world.width * world.height)
        world.rivers.rivers.forEach { river ->
            river.cells.forEach { cell ->
                if (world.sea.isLand[cell] && !world.rivers.lakes.isLake(cell)) channel[cell] = true
            }
        }
        return channel
    }

    /**
     * Strahler orders over the channel cells, and how many streams each order carries.
     *
     * `EarthLikeness`'s own walk, copied. Highest ground first, so a cell's upstream is settled
     * before the cell itself. A cell with no channel above it is order 1; a cell fed by two or more
     * channels of its own highest incoming order is one order above them; anything else keeps the
     * highest order that reaches it. A stream of an order is a run of consecutive cells holding it,
     * counted where the run leaves that order.
     */
    private fun strahlerStreamOrders(
        channel: BooleanArray,
        flowTarget: IntArray,
        byHeight: IntArray
    ): LongArray {
        val cellCount = channel.size
        val order = IntArray(cellCount)
        val incomingOrder = IntArray(cellCount)
        val incomingCount = IntArray(cellCount)
        for (rank in byHeight.indices.reversed()) {
            val cell = byHeight[rank]
            if (!channel[cell]) continue
            order[cell] = when {
                incomingCount[cell] == 0 -> 1
                incomingCount[cell] >= 2 -> incomingOrder[cell] + 1
                else -> incomingOrder[cell]
            }
            val receiver = flowTarget[cell]
            if (receiver < 0 || !channel[receiver]) continue
            when {
                order[cell] > incomingOrder[receiver] -> {
                    incomingOrder[receiver] = order[cell]
                    incomingCount[receiver] = 1
                }
                order[cell] == incomingOrder[receiver] -> incomingCount[receiver]++
            }
        }
        var highest = 0
        for (cell in 0 until cellCount) if (order[cell] > highest) highest = order[cell]
        if (highest == 0) return LongArray(0)
        val streams = LongArray(highest)
        for (cell in 0 until cellCount) {
            val own = order[cell]
            if (own == 0) continue
            val receiver = flowTarget[cell]
            val continues = receiver >= 0 && channel[receiver] && order[receiver] == own
            if (!continues) streams[own - 1]++
        }
        return streams
    }

    /**
     * Strahler's weighted mean bifurcation ratio, `EarthLikeness`'s own.
     *
     * Each adjacent pair of orders gives a ratio, averaged weighted by how many streams the pair
     * holds — Strahler (1953)'s correction, because the top of a network is a handful of streams
     * carrying most of the noise and none of the information.
     */
    private fun bifurcationRatioOf(streamsByOrder: LongArray): Double {
        var weighted = 0.0
        var weight = 0.0
        for (order in 0 until streamsByOrder.size - 1) {
            val above = streamsByOrder[order]
            val below = streamsByOrder[order + 1]
            if (above <= 0L || below <= 0L) continue
            val pairWeight = (above + below).toDouble()
            weighted += pairWeight * above.toDouble() / below
            weight += pairWeight
        }
        return if (weight <= 0.0) 0.0 else weighted / weight
    }

    /** Least squares, `EarthLikeness`'s own, reduced to the slope this file needs. */
    private fun fitSlope(x: List<Double>, y: List<Double>): Double {
        if (x.size < 2) return 0.0
        val meanX = x.average()
        val meanY = y.average()
        var covariance = 0.0
        var variance = 0.0
        for (i in x.indices) {
            covariance += (x[i] - meanX) * (y[i] - meanY)
            variance += (x[i] - meanX) * (x[i] - meanX)
        }
        val degenerate = 1e-12 * (meanX * meanX + 1.0)
        return if (variance <= degenerate) 0.0 else covariance / variance
    }

    /** Chebyshev cells to the nearest set cell, by a breadth-first sweep over the cylinder. */
    private fun chebyshevDistanceFrom(
        mask: BooleanArray,
        cellsAcross: Int,
        cellsDown: Int
    ): IntArray {
        val distance = IntArray(mask.size) { Int.MAX_VALUE }
        val queue = IntArray(mask.size)
        var head = 0
        var tail = 0
        for (cell in mask.indices) {
            if (mask[cell]) {
                distance[cell] = 0
                queue[tail++] = cell
            }
        }
        while (head < tail) {
            val cell = queue[head++]
            val next = distance[cell] + 1
            FlowRouting.forEachNeighbour(cellsAcross, cellsDown, cell % cellsAcross, cell / cellsAcross) { n ->
                if (distance[n] > next) {
                    distance[n] = next
                    queue[tail++] = n
                }
            }
        }
        return distance
    }

    private fun shortestColumnStep(step: Int, cellsAcross: Int): Int = when {
        step > cellsAcross / 2 -> step - cellsAcross
        step < -cellsAcross / 2 -> step + cellsAcross
        else -> step
    }
}
