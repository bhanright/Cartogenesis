package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.FlatRouting
import com.cartogenesis.worldgen.pipeline.FlowRouting
import com.cartogenesis.worldgen.pipeline.SeaLevelStage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
class FlatCourseTest : BorrowsSharedWorlds() {

    private companion object {
        val STANDARD_SEEDS = listOf(7L, 42L, 1234L, 99L)
        const val STANDARD_SIDE = 512

        /**
         * The most routing passes one default generation makes, counted at the calls to
         * `FlowRouting.flowDirections`: one per hydraulic round; the closing breach and the mouths
         * the last round opens, two more; the post-cut outlet pass over the drowned basins, at
         * least one and at most `SeaLevelStage.MAX_POST_CUT_OUTLET_PASSES`; and one each for the
         * ice, the drowned valleys and the rivers. Eighteen to thirty-three at the defaults, and the
         * cost below is charged at the most, which is the figure a device-path decision has to
         * survive. Fifteen until Audit III (its B-I13), which undercounted every term but the
         * rounds.
         */
        fun routingPassesPerGeneration(config: WorldGenConfig): Int =
            config.erosion.hydraulicRounds + CLOSING_ROUTING_PASSES +
                SeaLevelStage.MAX_POST_CUT_OUTLET_PASSES + LATER_STAGE_ROUTING_PASSES

        /** The closing breach and the mouths the last round opens. */
        const val CLOSING_ROUTING_PASSES = 2

        /** The ice, the drowned valleys and the rivers, one each. */
        const val LATER_STAGE_ROUTING_PASSES = 3

        /** Rule 8's line: under this share of a generation a device path is declined. */
        const val LARGEST_SHARE_WITHOUT_A_DEVICE_PATH = 0.01

        /** The trench case's grid, its one column and where the trench begins. */
        const val TRENCH_GRID = 128
        const val TRENCH_COLUMN = 64
        const val TRENCH_TOP_ROW = 20

        /**
         * The trench's entry, in the relative field: between 1 and 2, where a float's last place is
         * 1.19e-7 and one flat-gradient step is 8.39 of them, so the flood's addition rounds down.
         */
        const val TRENCH_ENTRY = 1.5f

        /** How far below its entry the trench's floor lies, and the walls stand above it. */
        const val TRENCH_DEPTH = 0.001f
        const val WALL_HEIGHT = 0.01f
        const val WALL_RISE = 0.001f
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
     * A converged potential is laid whichever way the flood's rounding went at the flat's entry.
     *
     * The flood raises the flat's first cell to its entry's height plus one flat-gradient step in
     * float, and the band the potential is laid into was anchored at that level less the nominal
     * step, in double. Where the float addition rounded down, the anchor stood below the entry, the
     * member beside the entry was laid below it too whenever its share of the band was smaller than
     * the rounding, and the whole flat went back to the staircase. A long flat is where that share
     * is small: here a trench 107 cells long down one column, its entry at 1.5 in the relative
     * field, where a float's step is an eighth of a flat-gradient step and the addition rounds down
     * by four hundredths of one. The trench is one column, so the column-run preconditioner is the
     * whole matrix and the solve is exact in one step: what refuses it can only be the laying.
     * Shown failing on the tree before Fix 3, which kept the staircase here.
     */
    @Test
    fun `a converged flat is laid however the flood rounded its entry`() {
        val config = WorldGenConfig(seed = 7L, width = TRENCH_GRID, height = TRENCH_GRID)
        val cellsAcross = config.width
        val cellsDown = config.height
        val isLand = BooleanArray(cellsAcross * cellsDown) { true }
        val ground = FloatField(cellsAcross, cellsDown)
        for (row in 0 until cellsDown) {
            for (column in 0 until cellsAcross) {
                val fromTheEdge = minOf(row, cellsDown - 1 - row)
                ground.data[row * cellsAcross + column] = when {
                    column == TRENCH_COLUMN && row == cellsDown - 1 -> TRENCH_ENTRY
                    column == TRENCH_COLUMN && row >= TRENCH_TOP_ROW -> TRENCH_ENTRY - TRENCH_DEPTH
                    // Rising away from the poles and from the trench, so no cell but the trench's
                    // is a flat and nothing but the trench is raised.
                    else -> TRENCH_ENTRY + WALL_HEIGHT + WALL_RISE * fromTheEdge + WALL_RISE / 10 * abs(column - TRENCH_COLUMN)
                }
            }
        }
        val entry = (cellsDown - 1) * cellsAcross + TRENCH_COLUMN
        val stepped = TRENCH_ENTRY + FlowRouting.FLAT_GRADIENT_STEP
        assertTrue(
            stepped.toDouble() - FlowRouting.FLAT_GRADIENT_STEP.toDouble() < TRENCH_ENTRY.toDouble(),
            "the flood's step at this entry does not round down, so this case does not pose the question"
        )
        val filled = FlowRouting.fillDepressions(cellsAcross, cellsDown, isLand, ground)
        val surface = FlatRouting.surfaceOf(
            cellsAcross, cellsDown, isLand, ground, filled, config.seed, config.cellHeightInCellWidths
        )
        println(
            "F30B trench: %d flats over %d raised cells, %d kept the staircase; the flat's first cell stands %.3g above its entry"
                .format(surface.flats, surface.raisedCells, surface.flatsKept, surface.heights[entry - cellsAcross] - surface.heights[entry])
        )
        assertEquals(1, surface.flats, "the trench is not one flat")
        assertEquals(cellsDown - 1 - TRENCH_TOP_ROW, surface.raisedCells, "the fill raised more than the trench")
        assertEquals(0, surface.flatsKept, "the trench's converged potential was refused and the staircase kept")
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
        val rowScale = world.config.cellHeightInCellWidths
        var surface = FlatRouting.surfaceOf(world.width, world.height, sea.isLand, sea.relativeElevation, filled, seed, rowScale)
        repeat(3) {
            val surfaceStarted = System.nanoTime()
            surface = FlatRouting.surfaceOf(world.width, world.height, sea.isLand, sea.relativeElevation, filled, seed, rowScale)
            surfaceMs = minOf(surfaceMs, (System.nanoTime() - surfaceStarted) / 1_000_000.0)
        }
        val passes = routingPassesPerGeneration(world.config)
        val shareOfGeneration = passes * surfaceMs / generationMs
        println(
            "F30B COST seed $seed@$STANDARD_SIDE: potential %.1f ms a pass over %d flats and %d raised cells, "
                .format(surfaceMs, surface.flats, surface.raisedCells) +
                "%.2f%% of a %.1f s generation over $passes passes; %d flats kept the staircase"
                    .format(shareOfGeneration * 100, generationMs / 1000, surface.flatsKept)
        )
        // Armed again at Fix 3, whose worlds hold far fewer raised cells (seed 7: 2,696 in 478
        // flats, a pass 3.4 ms, 1.00% of a generation, where Fix 2's world read 4.7%). It sits on
        // the line, so a loaded machine can put it over (docs/DESIGN_LEDGER.md, Fix 3).
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
            world.config.seed, world.config.cellHeightInCellWidths, world.config.facetRouting,
            world.config.flatPotential
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
