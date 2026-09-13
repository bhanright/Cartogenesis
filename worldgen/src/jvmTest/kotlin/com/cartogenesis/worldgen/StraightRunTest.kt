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
 * Both halves are measured here: the census on the five seeds with the rule in place, and the same
 * census on the same seeds with [WorldGenConfig.facetRouting] off, which is the plain rule this
 * replaced. The second is what makes the first worth reading.
 */
class StraightRunTest {

    private companion object {
        /** William's own world, at the size he looks at it, where the bar was found. */
        const val AUTHORS_SEED = 298405L
        const val AUTHORS_SIDE = 1024

        /** Ground rule 1's seeds plus the audit's fourth, at the size a preview is drawn at. */
        val STANDARD_SEEDS = listOf(7L, 42L, 1234L, 99L)
        const val STANDARD_SIDE = 512
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
     * Measured on the merge base (7b38716, the plain rule) at **1 / 0 / 0 / 0 / 0** on
     * 298405 at 1024 and 7, 42, 1234, 99 at 512 — the one being William's own, 53 cells at
     * (503, 866), every cell within 1.06 of the line it runs 20.1 cells along. F15's note in
     * `TODO.md` records 1/0/0/1/2 for the same census; the two other bodies it counted are a
     * 28-cell body on 1234 that is 16.1 cells long and two on 99 that are 9.3 and 12.4, all of them
     * short of the twenty cells a bar has to run, and F15's figure came from a least-squares axis
     * rather than from the narrowest strip. Counting bodies of twenty *cells* instead of twenty
     * cells of *length* reproduces F15's seeds exactly bar one: 1/0/0/1/1.
     */
    @Test
    fun `no standing water is a ruled bar`() {
        val counted = ArrayList<String>()
        var total = 0
        val seeds = listOf(AUTHORS_SEED to AUTHORS_SIDE) + STANDARD_SEEDS.map { it to STANDARD_SIDE }
        seeds.forEach { (seed, side) ->
            val world = world(seed, side)
            val bars = StraightWaterBars.ruledBarsOf(world)
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
     * The same census against the rule this replaced, so the one above is known to discriminate.
     *
     * Only William's own world, and only because it is the one that carries the defect: generating
     * the other four twice over to count zero twice proves nothing the case above does not, and
     * this class already generates five worlds. The audit tier runs the control on all five.
     */
    @Test
    fun `the plain steepest-neighbour rule fails that census`() {
        val bars = StraightWaterBars.ruledBarsOf(world(AUTHORS_SEED, AUTHORS_SIDE, byFacet = false))
        bars.forEach { println("F18 CONTROL bar on $AUTHORS_SEED@$AUTHORS_SIDE: $it") }
        assertTrue(
            bars.isNotEmpty(),
            "the steepest-neighbour control produced no ruled bar on $AUTHORS_SEED at " +
                "$AUTHORS_SIDE, so the census above cannot tell the two rules apart"
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
                if (row != 0 && row != lastRow) strandedInland++
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
