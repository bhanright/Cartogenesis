package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.FlatRouting
import com.cartogenesis.worldgen.pipeline.FlowRouting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule

/**
 * F30b: a river crossing ground the depression fill raised does not run in a ruled line.
 *
 * Until this chunk the routing surface across a filled flat was the flood's own expansion order,
 * whose level sets are the grid's chessboard shells, so a cell on the entry's diagonal had exactly
 * one lower neighbour and the course was drawn with a ruler. [FlatRouting] lays a potential across
 * each flat instead. The census here is [RuledLines.ruledRunsOf] split by ground: over cells the
 * fill left alone, where the facet rule already answers for it, and over cells the fill raised.
 *
 * At 512 a flat is a few cells and both rules leave next to nothing on it — 1 to 3 runs over raised
 * ground across the four standard seeds, either way — so the grid that runs per merge cannot tell
 * the two apart, exactly as F30 recorded. What this class asserts per merge is that the potential
 * keeps the two invariants the whole pipeline rests on, and it prints the census and the cost. The
 * discriminating case is the world the defect was seen on, at the size it was seen: see
 * `FlatCourseAuditTest`.
 */
class FlatCourseTest {

    @get:Rule
    val sharedWorlds = SharedWorlds.Check()

    private companion object {
        val STANDARD_SEEDS = listOf(7L, 42L, 1234L, 99L)
        const val STANDARD_SIDE = 512

        /**
         * Water is routed about fifteen times in a generation: twelve hydraulic rounds, the closing
         * breaches, the outlet pass, the ice and the rivers.
         */
        const val ROUTING_PASSES_PER_GENERATION = 15

        /** Rule 8's line: under this share of a generation a device path is declined. */
        const val LARGEST_SHARE_WITHOUT_A_DEVICE_PATH = 0.01
    }

    @Test
    fun `the potential keeps every flat cell a way down and the network a forest`() {
        var potentialRuns = 0
        var staircaseRuns = 0
        STANDARD_SEEDS.forEach { seed ->
            val world = FlatCourse.world(seed, STANDARD_SIDE, overPotential = true)
            val control = FlatCourse.world(seed, STANDARD_SIDE, overPotential = false)
            FlatCourse.assertInvariants(world, "seed $seed@$STANDARD_SIDE")
            val census = FlatCourse.census(world)
            val controlCensus = FlatCourse.census(control)
            potentialRuns += census.overRaised
            staircaseRuns += controlCensus.overRaised
            println("F30B seed $seed@$STANDARD_SIDE potential: $census")
            println("F30B seed $seed@$STANDARD_SIDE staircase: $controlCensus")
        }
        // Printed and not asserted, which is what the class note above has said since F30 and what
        // the numbers keep confirming. A flat at 512 is a few cells across, so both rules leave a
        // handful of ruled runs over raised ground on the four seeds together and the difference
        // between the two handfuls is scatter: pooled they have read 2 against 1 and 4 against 4
        // on successive re-cuts of the same worlds, either side of a tie. The discriminating case
        // is the audit tier's: `FlatCourseAuditTest` runs the world the defect was seen on at
        // 2048, where the staircase leaves 76 runs over raised ground against the potential's 44
        // and the clause is an assertion. An inequality that reverses when the ground moves by a
        // few metres is not measuring the rule it names, so the census is a finding here and the
        // claim is asserted where the grid can carry it.
        //
        // What this case does assert is above: the two invariants, on every one of the four
        // worlds, which is what the whole pipeline downstream of the routing rests on.
        println("F30B pooled at $STANDARD_SIDE: potential $potentialRuns, staircase $staircaseRuns runs over raised ground")
        println(
            "F30B FINDING at $STANDARD_SIDE the two rules are not separable — see FlatCourseAuditTest at 2048"
        )
    }

    /**
     * Rule 8's figure: what the potential costs against the generation it sits inside. Under one
     * percent of a world at every size measured, which is why it has no device path; the share is
     * printed so the decision can be re-read when the cost moves.
     */
    @Test
    fun `the potential is a small share of a generation`() {
        val seed = STANDARD_SEEDS[0]
        // Generated here rather than borrowed from `SharedWorlds`: the generation's own time is the
        // denominator, and a borrowed world takes a few milliseconds to hand over.
        val started = System.nanoTime()
        val world = WorldGenerationEngine.generateBlocking(
            FlatCourse.config(seed, STANDARD_SIDE, overPotential = true)
        )
        val generationMs = (System.nanoTime() - started) / 1_000_000.0
        val sea = world.sea
        val filled = FlowRouting.fillDepressions(world.width, world.height, sea.isLand, sea.relativeElevation)
        // The quickest of three passes rather than one: on a shared machine every source of noise
        // can only make a pass slower, so the floor is the closest any of them comes to the cost
        // being measured. One pass read 3.9 ms against 1.7 quiet while another build ran beside
        // it, and put the share over the line it is meant to sit well under.
        var surfaceMs = Double.MAX_VALUE
        var surface = FlatRouting.surfaceOf(world.width, world.height, sea.isLand, sea.relativeElevation, filled, seed)
        repeat(3) {
            val surfaceStarted = System.nanoTime()
            surface = FlatRouting.surfaceOf(world.width, world.height, sea.isLand, sea.relativeElevation, filled, seed)
            surfaceMs = minOf(surfaceMs, (System.nanoTime() - surfaceStarted) / 1_000_000.0)
        }
        val shareOfGeneration = ROUTING_PASSES_PER_GENERATION * surfaceMs / generationMs
        println(
            "F30B COST seed $seed@$STANDARD_SIDE: potential %.1f ms a pass over %d flats and %d raised cells, "
                .format(surfaceMs, surface.flats, surface.raisedCells) +
                "%.2f%% of a %.1f s generation over $ROUTING_PASSES_PER_GENERATION passes; %d flats kept the staircase"
                    .format(shareOfGeneration * 100, generationMs / 1000, surface.flatsKept)
        )
        assertTrue(
            shareOfGeneration < LARGEST_SHARE_WITHOUT_A_DEVICE_PATH,
            "the potential is %.2f%% of a generation, over the %.0f%% under which a device path is declined"
                .format(shareOfGeneration * 100, LARGEST_SHARE_WITHOUT_A_DEVICE_PATH * 100)
        )
        assertEquals(0, surface.flatsKept, "a flat at $STANDARD_SIDE fell back to the staircase")
    }
}

/** The worlds, the census and the invariants F30b's two classes share. */
internal object FlatCourse {

    fun world(seed: Long, side: Int, overPotential: Boolean): WorldMap =
        SharedWorlds.world(config(seed, side, overPotential))

    fun config(seed: Long, side: Int, overPotential: Boolean): WorldGenConfig =
        WorldGenConfig(seed = seed, width = 512, height = 512)
            .atResolution(side, side)
            .copy(flatPotential = overPotential)

    /** Cells the fill raised, by any amount at all: where the routing surface is not the ground. */
    fun raisedGround(world: WorldMap): BooleanArray {
        val filled = world.rivers.filledElevation.data
        val ground = world.relativeElevation.data
        return BooleanArray(filled.size) { world.sea.isLand[it] && filled[it] > ground[it] }
    }

    class Census(
        val all: Int,
        val overRaised: Int,
        val longestOverRaised: Int,
        val perThousandRaised: Float,
        val perThousandUnraised: Float,
        /** Standing water as a share of the land, so a re-routed world's lakes can be compared. */
        val lakeShareOfLand: Float
    ) {
        override fun toString(): String =
            "$all ruled runs of ${RuledLines.RULED_RUN_CELLS}+, $overRaised over raised ground, " +
                "the longest $longestOverRaised cells; per thousand drawn cells raised %.2f, unraised %.2f; lakes %.3f%% of land"
                    .format(perThousandRaised, perThousandUnraised, lakeShareOfLand * 100)
    }

    fun census(world: WorldMap): Census {
        val raised = raisedGround(world)
        val all = RuledLines.ruledRunListOf(world)
        val overRaised = RuledLines.ruledRunListOf(world) { raised[it] }
        var drawnRaised = 0
        var drawnUnraised = 0
        val counted = BooleanArray(raised.size)
        world.rivers.rivers.forEach { river ->
            river.cells.forEach { cell ->
                if (!counted[cell] && world.sea.isLand[cell] && !world.rivers.lakes.isLake(cell)) {
                    counted[cell] = true
                    if (raised[cell]) drawnRaised++ else drawnUnraised++
                }
            }
        }
        var lakeCells = 0
        var landCells = 0
        for (cell in raised.indices) {
            if (!world.sea.isLand[cell]) continue
            landCells++
            if (world.rivers.lakes.isLake(cell)) lakeCells++
        }
        return Census(
            all.size,
            overRaised.size,
            overRaised.maxOfOrNull { it.length } ?: 0,
            if (drawnRaised == 0) 0f else overRaised.size * 1000f / drawnRaised,
            if (drawnUnraised == 0) 0f else (all.size - overRaised.size) * 1000f / drawnUnraised,
            if (landCells == 0) 0f else lakeCells.toFloat() / landCells
        )
    }

    /**
     * The two invariants everything downstream of the routing rests on, on the routing itself:
     * every raised cell still has a receiver, and following receivers from any raised cell leaves
     * the raised ground in finitely many steps rather than circling on it. Read off the river
     * stage's own fill and the same routing call, which is the one the drawn courses come from.
     */
    fun assertInvariants(world: WorldMap, label: String) {
        val raised = raisedGround(world)
        val sea = world.sea
        val filled = world.rivers.filledElevation
        val flow = FlowRouting.flowDirections(
            world.width, world.height, sea.isLand, sea.relativeElevation, filled,
            world.config.seed, world.config.facetRouting, world.config.flatPotential
        )
        val cellCount = world.width * world.height
        // 0 not yet walked, 1 known to leave the raised ground, 2 on the walk in progress.
        val leaves = IntArray(cellCount)
        var raisedCells = 0
        for (start in 0 until cellCount) {
            if (!raised[start]) continue
            raisedCells++
            assertTrue(
                flow[start] >= 0,
                "$label: raised cell (${start % world.width},${start / world.width}) has no receiver"
            )
            var cell = start
            val path = ArrayList<Int>()
            while (cell >= 0 && raised[cell] && leaves[cell] == 0) {
                leaves[cell] = 2
                path.add(cell)
                cell = flow[cell]
            }
            assertTrue(
                cell < 0 || !raised[cell] || leaves[cell] == 1,
                "$label: the routing circles on raised ground at (${cell % world.width},${cell / world.width})"
            )
            path.forEach { leaves[it] = 1 }
        }
        println("F30B $label: $raisedCells raised cells, every one with a way off the flat")
    }
}
