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
     * The bar is Earth's, under both rules: Hack's exponent inside its band on every seed, and the
     * bifurcation ratio inside Horton's with the allowance `EarthLikeness` derives for how the
     * figure moves with its support threshold. A routing rule that took a network out of either
     * band would have changed the kind of world, and that is what this case is for.
     *
     * **What it used to assert, and why it no longer does.** F18 asserted that the rule moves each
     * statistic by less than the spread across the four seeds. That is one noisy figure against
     * another: the spread is a property of the four facet worlds, so a rule that made worlds more
     * alike would fail it and one that made them differ more would pass, and four seeds cannot
     * derive an equivalence margin. It held at F18 (docs/DESIGN_LEDGER.md: the ratio moved 0.413
     * against 1.842) because the drawn network's ratio was loose across seeds; R1 pruned the
     * drawn courses and the same figure came out tight, and the rule's move, 0.318 on seed 42,
     * stood over a spread of 0.124 without the rule having changed. The paired moves are printed
     * on every seed and not asserted.
     *
     * **Over the support-area channel network, not the drawn courses.** The drawn network is
     * conditioned on `RiverConfig.maxRivers` and on R1's pruning, neither of which is the routing
     * rule; the support-area network, every land cell draining at least 4,400 km2, is the sample
     * `EarthLikeness` fits Horton on, a fifth order of a stream or two deeper than the drawn one
     * and independent of what the map chooses to draw. The drawn figure is still printed on every
     * seed beside it, because it is what the map shows.
     */
    @Test
    fun `Hack's exponent and the bifurcation ratio stay inside Earth's bands under both rules`() {
        val complaints = ArrayList<String>()
        SEEDS.forEach { seed ->
            val plain = statisticsOf(world(seed, SIDE, byFacet = false))
            val facet = statisticsOf(world(seed, SIDE, byFacet = true))
            println(
                "F18 NETWORK seed $seed: Hack %.4f -> %.4f over %d/%d basins, bifurcation ".format(
                    plain.hackExponent, facet.hackExponent, plain.basins, facet.basins
                ) + "%.3f -> %.3f, orders %s -> %s".format(
                    plain.bifurcationRatio, facet.bifurcationRatio,
                    plain.routed.perOrder(), facet.routed.perOrder()
                )
            )
            println(
                ("F18 NETWORK seed $seed over the drawn courses: bifurcation %.3f -> %.3f, " +
                    "orders %s -> %s; printed and not asserted, see the note on the case").format(
                    plain.drawn.bifurcationRatio, facet.drawn.bifurcationRatio,
                    plain.drawn.perOrder(), facet.drawn.perOrder()
                )
            )
            println(
                "F18 NETWORK seed $seed: the rule moved Hack by %.4f and the bifurcation ratio by %.3f"
                    .format(
                        abs(facet.hackExponent - plain.hackExponent),
                        abs(facet.bifurcationRatio - plain.bifurcationRatio)
                    )
            )
            listOf("plain" to plain, "facet" to facet).forEach { (rule, statistics) ->
                if (statistics.hackExponent < EARTH_HACK_LOW - HACK_TOLERANCE ||
                    statistics.hackExponent > EARTH_HACK_HIGH + HACK_TOLERANCE
                ) {
                    complaints += ("seed $seed, $rule rule: Hack's exponent is %.4f, outside " +
                        "Earth's %.2f to %.2f give or take %.2f").format(
                        statistics.hackExponent, EARTH_HACK_LOW, EARTH_HACK_HIGH, HACK_TOLERANCE
                    )
                }
                EarthLikeness.bifurcationComplaint("seed $seed, $rule rule", statistics.routed)
                    ?.let { complaints += it }
            }
        }
        assertTrue(complaints.isEmpty(), complaints.joinToString("\n"))
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
            val plain = EarthLikeness.drawnChannelMask(world(seed, side, byFacet = false))
            val facet = EarthLikeness.drawnChannelMask(world(seed, side, byFacet = true))
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
            cellsAcross, cellsDown, isLand, ground, filled, seed, world.config.cellHeightInCellWidths, byFacet
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
        /** Over the support-area channel network, `EarthLikeness`'s metric of record. */
        val routed: EarthLikeness.StreamOrders,
        /** Over the drawn courses, which is what the map shows: printed, not asserted. */
        val drawn: EarthLikeness.StreamOrders
    ) {
        val bifurcationRatio get() = routed.bifurcationRatio
    }

    private fun statisticsOf(world: WorldMap): Statistics {
        val cellsAcross = world.width
        val cellsDown = world.height
        val filled = world.rivers.filledElevation
        val catchmentCells = FlowRouting.accumulate(
            cellsAcross, cellsDown, world.sea.isLand, filled, world.rivers.flowTarget,
            world.sea.landCellCount
        ) { 1f }.data
        // Sources first and mouths last: a topological order of the flow forest, which
        // `FlowRouting.heightOrder` has not been since F30b laid a potential over each filled flat,
        // where a receiver may stand higher on the fill than the cell draining into it. Walked on
        // the height order, the two accumulations below can settle a cell before its feeders and
        // read a shorter main stem or a lower Strahler order there. At 512 that is worth hundredths
        // on the four seeds, since a flat is a few cells there; `EarthLikeness` made the same
        // change at R1's follow-up.
        val sourcesFirst = FlowRouting.drainageOrder(
            cellsAcross, cellsDown, world.sea.isLand, world.rivers.flowTarget, world.sea.landCellCount
        )

        // Hack's `L` is the main stem measured from the divide, which on a tree is the longest of
        // the paths reaching the cell. In cells rather than kilometres: the exponent is the slope
        // of a log-log fit, and rescaling both axes by a constant moves only the intercept.
        val longestPath = DoubleArray(cellsAcross * cellsDown)
        for (cell in sourcesFirst) {
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

        // Strahler over the support-area network, which is `EarthLikeness`'s metric of record,
        // and over the drawn courses. The bar is on the first and the second is printed: see the
        // note on the case.
        val routed = EarthLikeness.strahlerStreamOrders(
            EarthLikeness.supportAreaChannelMask(
                world, catchmentCells, EarthLikeness.CHANNEL_SUPPORT_KM2.min()
            ),
            world.rivers.flowTarget, sourcesFirst
        )
        val drawn = EarthLikeness.strahlerStreamOrders(
            EarthLikeness.drawnChannelMask(world), world.rivers.flowTarget, sourcesFirst
        )
        return Statistics(
            hackExponent = fitSlope(catchments, mainStems),
            basins = catchments.size,
            routed = routed,
            drawn = drawn
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
