package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.FlowRouting
import com.cartogenesis.worldgen.pipeline.RiverResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F18: no lake is a ruled line, because no river runs in one.
 *
 * The plain steepest-of-eight rule cannot express a slope facing between two of its bearings, so
 * over ground that is a plane at the cell scale it takes the same step for cell after cell. Where a
 * real river crosses such an apron the stream power then cuts a ruled trench, the trench ponds
 * behind its own lip, and the map grows the diagonal rectangle the author pointed at on seed 298405 at
 * 1024. [com.cartogenesis.worldgen.pipeline.FlowRouting.flowDirections] now takes the direction
 * from the steepest triangular facet and draws the one receiver across it, so a course crossing an
 * apron follows the same slope without being ruled.
 *
 * Two things are asked. That no standing water on the five seeds is shaped like a ruled line, which
 * is the artefact itself and is rare. And that the old rule, kept under [WorldGenConfig.facetRouting]
 * and run on the same day, still draws more of the map with a ruler than the new one — which is the
 * defect itself, is on every map, and is what makes the census above worth reading.
 */
class StraightRunTest {

    private companion object {
        /** The author's own world, at the size he looks at it, where the bar was found. */
        const val AUTHORS_SEED = 298405L
        const val AUTHORS_SIDE = 1024

        /** Ground rule 1's seeds plus the audit's fourth, at the size a preview is drawn at. */
        val STANDARD_SEEDS = listOf(7L, 42L, 1234L, 99L)
        const val STANDARD_SIDE = 512

        /** The seed the live control runs on: the most ruled runs of the four at 512. */
        const val CONTROL_SEED = 42L

        /**
         * The author's second world, at the one grid the ruled shores can be found on.
         *
         * A 2048 world is the better part of two minutes and it is not optional, for the reason
         * `GlacialBasinShapeTest` keeps one: the bodies the notch opened are three and seven
         * thousand cells, and at 512 that basin is a few hundred cells with no notch cut at all —
         * the outlet pass only runs where the enclosure rule has drowned a basin, and how much of a
         * world it drowns is a question about how finely the coast is resolved.
         */
        const val SHORE_SEED = 364673L
        const val SHORE_SIDE = 2048
    }

    private fun world(seed: Long, side: Int, byFacet: Boolean = true): WorldMap =
        WorldGenerationEngine.generateBlocking(
            WorldGenConfig(seed = seed, width = 512, height = 512)
                .atResolution(side, side)
                .copy(facetRouting = byFacet)
        )

    /**
     * The census, on the five seeds F15 took it on.
     *
     * Measured on the merge base (7b38716, before any of this branch) at **1 / 0 / 0 / 0 / 0** on
     * 298405 at 1024 and 7, 42, 1234, 99 at 512 — the one being the author's own, 53 cells at
     * (503, 866), every cell within 1.06 of the line it runs 20.1 cells along. With the facet rule
     * and nothing else changed it read 0 / 0 / 0 / 0 / 0, which is what this asserts.
     *
     * `TODO.md` records F15's own figure for the same census as 1/0/0/1/2. The three other bodies
     * it counted are a 28-cell one on 1234 that is 16.1 cells long and two on 99 at 9.3 and 12.4,
     * all short of the twenty a bar has to run; F15 measured against a least-squares axis rather
     * than the narrowest strip, and counted cells rather than length. Counting bodies of twenty
     * *cells* here reproduces F15's seeds bar one, at 1/0/0/1/1.
     *
     * A ruled bar needs a ruled course *and* a lip for it to pond behind, and the second is chance:
     * one of these five worlds had one, and whether it still would on a world moved by any other
     * chunk is chance again. So the census records that the artefact is gone, but it is a poor
     * instrument for telling one routing rule from another. What separates them on every seed is
     * the ruled *course*, which is the defect itself and is on every map; see the case below.
     *
     * Counted over the water standing *above* the sea-level cut, which is the water a ruled course
     * can pond. Below the cut is a different population and a different cause: a basin the ocean
     * cannot reach, converted to land at the level it already stood at and filled by the depression
     * flood from the sea beside it, holds water because it is a hollow below the waterline and not
     * because anything ran in a straight line into it. `OutletIncisionTest` already splits its own
     * basin figures the same way and for the same reason. S2b is what made the split necessary: its
     * repair to the flood reaches such basins for the first time, and on seed 7 at 512 that put
     * 28 cells of water into a hollow four rows deep and twenty-five columns long at (344,477) —
     * 590 km by 47 km on the ground, 1.12 cells off one line, standing at 0.272 of the field
     * against a shoreline at 0.632. It is printed by this case and not counted. Whether a hollow
     * that shape should hold an inland sea is a question for the render and is in `TODO.md`; it is
     * not a question about routing, and the world built with the plain rule has no such basin at
     * all.
     */
    @Test
    fun `no standing water is a ruled bar`() {
        val counted = ArrayList<String>()
        var total = 0
        val seeds = listOf(AUTHORS_SEED to AUTHORS_SIDE) + STANDARD_SEEDS.map { it to STANDARD_SIDE }
        seeds.forEach { (seed, side) ->
            val world = world(seed, side)
            val (drowned, ponded) = RuledLines.ruledBarsOf(world)
                .partition { standsBelowTheSeaLevelCut(world, it.lakeId) }
            counted += "$seed@$side=${ponded.size}"
            total += ponded.size
            ponded.forEach { println("F18 BAR on $seed@$side: $it") }
            drowned.forEach { println("F18 BAR on $seed@$side, below the cut and not counted: $it") }
            assertDrainageIsAForest(world, "$seed@$side")
        }
        println("F18 census with the facet rule: ${counted.joinToString(" ")}")
        assertEquals(
            0, total,
            "standing water still runs in ruled lines: ${counted.joinToString(" ")}"
        )
    }

    /**
     * F30: no shore is a ruled line either, which the census above could not see.
     *
     * The census asks whether a body of water is *itself* a bar — every cell within a cell and a bit
     * of one line, twenty cells long — and that is a question about small bodies. the author's seed
     * 364673 at 2048 carried the same defect in a shape it could not read: two inland seas of three
     * and seven thousand cells, entirely ordinary in outline but for one dead-straight edge apiece,
     * 41 cells due north-south and 73 on the diagonal. Those edges are the outlet pass's own notch.
     * [com.cartogenesis.worldgen.pipeline.SeaLevelStage] cuts a drowned basin's sill *below* the
     * waterline, one cell wide, over sixteen passes; where the path it follows is ruled the map
     * grows a canal of open water drawn with a ruler, and the shore of the sea it opens is that
     * canal's side.
     *
     * So this asks the question of the *edge* of every body of standing water, with I2's
     * instrument: [OutlineRuns], which `GlacialBasinShapeTest` asks of the basins the ice cuts and
     * which carries the derivation of the bar. Both kinds of body, because the defect made one of
     * each: a lake off [com.cartogenesis.worldgen.pipeline.LakeResult.lakeId], and a connected run
     * of sea cells, which is what a drowned basin comes out as once it is too big for the enclosure
     * rule to call it a lake.
     *
     * Every body but the world ocean. The ocean's edge is the coastline, its shape is a question
     * about fractal dimension rather than about straight runs, and `LittoralCoastTest` and F17 own
     * it with an instrument built for it. Asked of the ocean this bar says nothing useful: the
     * largest body on a map is most of the map, and a single tidal flat lying along a row reads as
     * a run of hundreds against a bar derived from a body's curvature.
     *
     * And only of a body big enough for the bar to mean anything —
     * [OutlineRuns.SMALLEST_BODY_THE_BAR_BINDS], which carries that derivation. A puddle of twenty
     * cells breaks a bar wider than the puddle is by being four cells long and two across, which is
     * the census above's question and not this one's.
     *
     * Measured at 00b13fe, before S2b: **73 cells against 41.7 allowed, 1.75 times the bar**, on the
     * 7,297-cell inland sea at (2022,1449) of 364673 at 2048, and 41 against 34.2 (1.20 times) on
     * the 3,314-cell one in the author's own window — 72 and 41 of those cells standing above the
     * waterline in the raw terrain before the outlet pass cut them, which is what said they were the
     * notch and not a shore. The other five seeds carried one to six bodies the bar binds apiece and
     * every one was inside it, 0.26 to 0.55 times, which is why the defect needed the author's own
     * world at his own grid to be seen.
     *
     * **Both canals are gone on the merged tree, and S2b took them, not F30.** S2b let the sea seed
     * the depression flood at its own level, which is the same flood this defect was traced to, and
     * the drowned basins the outlet pass was notching are not the basins it now finds: 364673 at 2048
     * offers sixteen measurable bodies instead of ten and neither inland sea is among the ones over
     * the bar. The window the author reported reads clean — no canal, no ruled shore, no dead-straight
     * line at x 1788. What is left over the bar anywhere is **1.07 times it**, a 296-cell lake at
     * (1075,1998) with a 20-cell run against 18.7 allowed, which is a different body with a different
     * story and is marginal where the old two were not.
     *
     * **Reported, not asserted.** Both repairs F30 built for the old cause were measured and
     * reverted before S2b landed, because each bought this bar with an Earth figure and ground rule 5
     * forbids that trade: routing the outlet pass so it could not follow the fill's staircase in a
     * ruled line left 189 cells of water the ocean cannot reach on seed 1234 against
     * `SeaLevelHistoryTest`'s rule, and routing every filled flat that way moved three more
     * (`OutletResolutionTest`'s Caspian bound to 1.83, `RiverWidthTest`'s pen to 2.46 px,
     * `OutletIncisionTest`'s control). The one body still over the bar has not been diagnosed, so
     * asserting the bar would be asserting something nobody has looked at. The figures and both old
     * causes are in `TODO.md`, and the chunk that diagnoses this one turns the report back into the
     * assertion it was written as. It prints how many bodies each seed offered so that a future
     * green cannot be a vacant one.
     */
    @Test
    fun `report how straight every shore is`() {
        val overTheBarEverywhere = ArrayList<String>()
        var worst = 0f
        var worstAt = "nothing"
        val seeds = listOf(SHORE_SEED to SHORE_SIDE, AUTHORS_SEED to AUTHORS_SIDE) +
            STANDARD_SEEDS.map { it to STANDARD_SIDE }
        seeds.forEach { (seed, side) ->
            val world = world(seed, side)
            var worstHere = 0f
            var worstHereAt = "nothing"
            val measurable = standingWaterOf(world)
                .filter { it.cells.size >= OutlineRuns.SMALLEST_BODY_THE_BAR_BINDS }
            measurable.forEach { water ->
                val run = OutlineRuns.longestOutlineRun(
                    water.cells, water.membership, water.id, side, side
                )
                val allowed = OutlineRuns.allowedRunCells(water.cells.size)
                val overTheBar = run.cells / allowed
                val described = "${water.kind} ${water.id} of ${water.cells.size} cells: a run of " +
                    "${run.cells} along bearing ${run.bearing} at (${run.column},${run.row}), " +
                    "against ${"%.1f".format(allowed)} allowed"
                if (overTheBar > worstHere) {
                    worstHere = overTheBar
                    worstHereAt = described
                }
                if (run.cells > allowed) overTheBarEverywhere += "$seed@$side $described"
            }
            println(
                "F30 SHORE $seed@$side: ${measurable.size} bodies big enough to measure, " +
                    "worst ${"%.2f".format(worstHere)} times the bar, $worstHereAt"
            )
            if (worstHere > worst) {
                worst = worstHere
                worstAt = "$seed@$side $worstHereAt"
            }
        }
        println("F30 SHORE worst over all seeds: ${"%.2f".format(worst)} times the bar, $worstAt")
        overTheBarEverywhere.forEach { println("F30 SHORE over the bar: $it") }
        // Asserted: that the instrument still finds something to measure. What it measures is
        // printed rather than asserted, for the reason on the case above — a bar this generator
        // does not yet meet is a finding and not a guard, and turning it into one would mean
        // moving it. What cannot be allowed to rot silently is the census going empty, which is
        // what would happen if the body labelling below ever stopped finding the inland seas.
        assertTrue(
            worst > 0f,
            "no body of standing water on six worlds was big enough to measure, so this case is " +
                "reporting nothing at all"
        )
    }

    /** One body of standing water, and the array its cells are marked in. */
    private class StandingWater(
        val kind: String,
        val id: Int,
        val cells: List<Int>,
        val membership: IntArray
    )

    /**
     * Every body of standing water on [world] but the world ocean: its lakes, and each connected
     * run of sea cells that is not the largest one.
     *
     * The sea bodies are labelled here rather than read off a field because no field holds them:
     * `SeaLevelStage` labels them inside its own pass to decide which are small enough to call
     * lakes, and hands on only the mask. Eight-connected, as every other flood fill on this map is,
     * and the grid wraps east to west.
     */
    private fun standingWaterOf(world: WorldMap): List<StandingWater> {
        val cellsAcross = world.width
        val cellsDown = world.height
        val isLand = world.sea.isLand
        val body = IntArray(isLand.size) { -1 }
        val sizes = ArrayList<Int>()
        val stack = ArrayDeque<Int>()
        for (start in body.indices) {
            if (isLand[start] || body[start] >= 0) continue
            val id = sizes.size
            body[start] = id
            stack.addLast(start)
            var size = 0
            while (stack.isNotEmpty()) {
                val cell = stack.removeLast()
                size++
                FlowRouting.forEachNeighbour(
                    cellsAcross, cellsDown, cell % cellsAcross, cell / cellsAcross
                ) { neighbour ->
                    if (!isLand[neighbour] && body[neighbour] < 0) {
                        body[neighbour] = id
                        stack.addLast(neighbour)
                    }
                }
            }
            sizes.add(size)
        }
        val ocean = sizes.indices.maxByOrNull { sizes[it] } ?: -1

        val water = ArrayList<StandingWater>()
        val seaCells = HashMap<Int, MutableList<Int>>()
        body.forEachIndexed { cell, id ->
            if (id >= 0 && id != ocean) seaCells.getOrPut(id) { ArrayList() }.add(cell)
        }
        seaCells.entries.sortedBy { it.key }.forEach { (id, cells) ->
            water += StandingWater("inland sea", id, cells, body)
        }
        val lakeId = world.rivers.lakes.lakeId
        val lakeCells = HashMap<Int, MutableList<Int>>()
        lakeId.forEachIndexed { cell, id ->
            if (id >= 0) lakeCells.getOrPut(id) { ArrayList() }.add(cell)
        }
        lakeCells.entries.sortedBy { it.key }.forEach { (id, cells) ->
            water += StandingWater("lake", id, cells, lakeId)
        }
        return water
    }

    /**
     * The rule this replaced draws more of the map with a ruler, on the same seed and the same day.
     *
     * The live control, and the reason the census above is worth reading. A run of seven steps on
     * one bearing is 40 to 160 km of watercourse without a bend at these grids; the plain rule
     * leaves more of them than the facet rule on every seed measured — 76/28/38/22/31 against
     * 66/21/32/15/20 on 298405 at 1024 and on 7/42/1234/99 at 512 — and one seed at 512 keeps
     * proving that per merge. The audit tier runs the whole table.
     */
    @Test
    fun `the old rule draws more of the map with a ruler`() {
        val plain = RuledLines.ruledRunsOf(world(CONTROL_SEED, STANDARD_SIDE, byFacet = false))
        val facet = RuledLines.ruledRunsOf(world(CONTROL_SEED, STANDARD_SIDE))
        println(
            "F18 ruled runs of ${RuledLines.RULED_RUN_CELLS}+ on $CONTROL_SEED@$STANDARD_SIDE: " +
                "steepest neighbour $plain, steepest facet $facet"
        )
        assertTrue(
            facet < plain,
            "the facet rule left $facet ruled runs against the plain rule's $plain, so the two " +
                "cannot be told apart and the census above proves nothing"
        )
    }

    /**
     * The two invariants everything downstream of the routing rests on, checked on the same worlds
     * the census is taken from rather than inferred from the stages that would break.
     *
     * A drawn receiver could in principle be a cell no lower than the one draining into it, and
     * that would put a cycle in what the rest of the pipeline treats as a forest — the incision
     * walks it from the outlets upstream, the sediment walks it the other way, and neither
     * terminates on a cycle. It cannot happen, because a facet's interior only exists where the
     * diagonal is below the cardinal and the cardinal below the cell. And a cell may lose its
     * receiver only where it never had one: the fill leaves every land cell a way down, so the
     * water only stops at the polar rows, where there is no further row to run onto.
     *
     * Read off the routing itself rather than off [RiverResult.flowTarget], which is not the same
     * array: an endorheic basin's cells are re-pointed afterwards at the water they actually reach,
     * over the true ground rather than over the fill, and on the filled surface those steps run
     * outward to a spill the lake no longer reaches. That is E2's doing and is deliberate.
     */
    /**
     * Whether the body of standing water [lakeId] names lies below the sea-level cut.
     *
     * Read off the true ground rather than off the fill, the way `OutletIncisionTest` reads it: a
     * basin the enclosure rule converted keeps the level it already stood at, and what puts it in
     * the other population is that level and not how deeply it was later filled.
     */
    private fun standsBelowTheSeaLevelCut(world: WorldMap, lakeId: Int): Boolean {
        val ground = world.sea.relativeElevation.data
        val cut = world.sea.shorelineHeight
        return world.rivers.lakes.lakeId.indices.any {
            world.rivers.lakes.lakeId[it] == lakeId && ground[it] < cut
        }
    }

    private fun assertDrainageIsAForest(world: WorldMap, label: String) {
        val cellsAcross = world.width
        val cellsDown = world.height
        val ground = world.sea.relativeElevation
        val filledField =
            FlowRouting.fillDepressions(cellsAcross, cellsDown, world.sea.isLand, ground)
        val routed = FlowRouting.flowDirections(
            cellsAcross, cellsDown, world.sea.isLand, ground, filledField, world.config.seed
        )
        val filled = filledField.data
        val trueGround = ground.data
        val lastRow = cellsDown - 1
        var uphill = 0
        var strandedInland = 0
        for (cell in filled.indices) {
            if (!world.sea.isLand[cell]) continue
            val receiver = routed[cell]
            if (receiver < 0) {
                val row = cell / cellsAcross
                if (row != 0 && row != lastRow) {
                    strandedInland++
                    // Described rather than counted: a cell with no receiver at all is either a
                    // fill that left no way down or a tie the router could not break, and the two
                    // read differently in the neighbourhood printed here.
                    println(
                        ("STRAIGHTRUN %s stranded at (%d, %d): ground %.9f, filled %.9f," +
                            " neighbours filled %s")
                            .format(
                                label, cell % cellsAcross, row, trueGround[cell], filled[cell],
                                (-1..1).flatMap { rowStep ->
                                    (-1..1).mapNotNull { columnStep ->
                                        if (rowStep == 0 && columnStep == 0) return@mapNotNull null
                                        val neighbourRow = row + rowStep
                                        if (neighbourRow < 0 || neighbourRow >= cellsDown) {
                                            return@mapNotNull null
                                        }
                                        val neighbourColumn =
                                            (cell % cellsAcross + columnStep + cellsAcross) %
                                                cellsAcross
                                        val neighbour = neighbourRow * cellsAcross + neighbourColumn
                                        "%s%.9f".format(
                                            if (world.sea.isLand[neighbour]) "" else "sea ",
                                            filled[neighbour]
                                        )
                                    }
                                }
                            )
                    )
                }
                continue
            }
            val there =
                if (world.sea.isLand[receiver]) filled[receiver] else trueGround[receiver]
            if (there >= filled[cell]) uphill++
        }
        assertEquals(0, uphill, "$label: $uphill cells drain into a cell no lower than themselves")
        assertEquals(
            0, strandedInland,
            "$label: $strandedInland land cells away from the poles have nowhere to drain"
        )
    }
}
