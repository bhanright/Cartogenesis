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
 * behind its own lip, and the map grows the diagonal rectangle William pointed at on seed 298405 at
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
        /** William's own world, at the size he looks at it, where the bar was found. */
        const val AUTHORS_SEED = 298405L
        const val AUTHORS_SIDE = 1024

        /** Ground rule 1's seeds plus the audit's fourth, at the size a preview is drawn at. */
        val STANDARD_SEEDS = listOf(7L, 42L, 1234L, 99L)
        const val STANDARD_SIDE = 512

        /** The seed the live control runs on: the most ruled runs of the four at 512. */
        const val CONTROL_SEED = 42L
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
     * 298405 at 1024 and 7, 42, 1234, 99 at 512 — the one being William's own, 53 cells at
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
     */
    @Test
    fun `no standing water is a ruled bar`() {
        val counted = ArrayList<String>()
        var total = 0
        val seeds = listOf(AUTHORS_SEED to AUTHORS_SIDE) + STANDARD_SEEDS.map { it to STANDARD_SIDE }
        seeds.forEach { (seed, side) ->
            val world = world(seed, side)
            val bars = RuledLines.ruledBarsOf(world)
            counted += "$seed@$side=${bars.size}"
            total += bars.size
            bars.forEach { println("F18 BAR on $seed@$side: $it") }
            assertDrainageIsAForest(world, "$seed@$side")
        }
        println("F18 census with the facet rule: ${counted.joinToString(" ")}")
        assertEquals(
            0, total,
            "standing water still runs in ruled lines: ${counted.joinToString(" ")}"
        )
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
