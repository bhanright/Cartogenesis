package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.pipeline.FlowRouting
import kotlin.test.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * The priority flood on a grid small enough to reason about, and the one component it used to miss.
 *
 * `SeaLevelStage`'s enclosed-water rule turns sea the ocean cannot reach into land without raising
 * it, so the map carries land that stands *below* the water beside it. Seeding the flood from land
 * that touches lower water — which is what a coast is — never reaches such a component when the
 * water around it stands higher on every side: nothing seeds it, nothing visits it, it keeps
 * whatever sinks it had, and the router then finds its cells nothing to drain into. S2's fourth
 * pass stopped the seeding from mislabelling those cells as outlets, which took the symptom off the
 * real seeds and left the cause where it was.
 *
 * The cure is to let the water itself join the flood at its own level. Two patches here, and what
 * is measured is that one of them is repaired while the other does not move:
 *
 *  - a **submerged patch** lying below the sea floor that rings it, which must come up to its spill
 *    level and drain outward;
 *  - an **ordinary coast**, a plateau standing above the water with a pit in the middle of it,
 *    whose rim must keep the exact height it was given and whose pit must be raised to that rim.
 *
 * Heights are in the relative-elevation field's own units, as `FlowRouting` takes them: the
 * waterline is 0 and the whole field spans about -1 to 1, which is what makes
 * [FlowRouting.FLAT_GRADIENT_STEP] a step a float can still see.
 */
class DepressionFillTest {

    @Test
    fun `land below the water around it is filled to its spill level and drains`() {
        val grid = syntheticCoastAndSubmergedPatch()
        val filled = FlowRouting.fillDepressions(SIDE, SIDE, grid.isLand, grid.elevation)
        val target = FlowRouting.flowDirections(
            SIDE, SIDE, grid.isLand, grid.elevation, filled, seed = 1L
        )

        var landCells = 0
        var sinks = 0
        var withoutTarget = 0
        for (cell in grid.isLand.indices) {
            if (!grid.isLand[cell]) continue
            landCells++
            var anyLower = false
            FlowRouting.forEachNeighbour(SIDE, SIDE, cell % SIDE, cell / SIDE) { neighbour ->
                val neighbourSurface =
                    if (grid.isLand[neighbour]) filled.data[neighbour]
                    else grid.elevation.data[neighbour]
                if (neighbourSurface < filled.data[cell]) anyLower = true
            }
            if (!anyLower) sinks++
            if (target[cell] < 0) withoutTarget++
        }

        val submergedCorner = SUBMERGED_ROWS.first * SIDE + SUBMERGED_COLUMNS.first
        val plateauCorner = PLATEAU_ROWS.first * SIDE + PLATEAU_COLUMNS.first
        val pit = PLATEAU_PIT_ROW * SIDE + PLATEAU_PIT_COLUMN
        println(
            ("FILL %d land cells: %d sinks, %d with no flow target; the submerged patch's corner" +
                " went %.6f to %.6f, the plateau's corner %.6f to %.6f, its pit %.6f to %.6f")
                .format(
                    landCells, sinks, withoutTarget,
                    grid.elevation.data[submergedCorner], filled.data[submergedCorner],
                    grid.elevation.data[plateauCorner], filled.data[plateauCorner],
                    grid.elevation.data[pit], filled.data[pit]
                )
        )

        assertTrue(
            "$sinks of $landCells land cells have no neighbour below them on the filled surface",
            sinks == 0
        )
        assertTrue(
            "$withoutTarget of $landCells land cells have no receiver at all",
            withoutTarget == 0
        )
        assertTrue(
            "the submerged patch's corner is still at" +
                " ${"%.6f".format(filled.data[submergedCorner])}, below the" +
                " ${"%.6f".format(SEA_FLOOR_FIELD_LEVEL)} of the water ringing it: the flood never" +
                " reached it",
            filled.data[submergedCorner] > SEA_FLOOR_FIELD_LEVEL
        )
        assertTrue(
            "the submerged patch came up to ${"%.6f".format(filled.data[submergedCorner])}, over" +
                " the ${"%.6f".format(SEA_FLOOR_FIELD_LEVEL)} it spills into by more than the" +
                " flood's own staircase: it was filled from somewhere other than the water",
            filled.data[submergedCorner] < SEA_FLOOR_FIELD_LEVEL + SPILL_TOLERANCE
        )
        assertEquals(
            "an ordinary coast's rim was raised, so the water is being let in where it should not",
            PLATEAU_FIELD_LEVEL.toDouble(), filled.data[plateauCorner].toDouble(), 0.0
        )
        assertEquals(
            "the pit inside the plateau was not raised to the rim it spills over",
            PLATEAU_FIELD_LEVEL.toDouble(), filled.data[pit].toDouble(), SPILL_TOLERANCE.toDouble()
        )
    }

    /** The land mask and the ground the clause above is measured on. */
    private class Grid(val isLand: BooleanArray, val elevation: FloatField)

    /**
     * A sea floor carrying two pieces of land: a patch lying wholly below it, and a plateau
     * standing above it with a pit in the middle.
     *
     * Neither touches a polar row, so neither gets the free outlet the map's top and bottom edges
     * give, and the two are far enough apart that the flood cannot walk from one to the other.
     */
    private fun syntheticCoastAndSubmergedPatch(): Grid {
        val isLand = BooleanArray(SIDE * SIDE)
        val elevation = FloatField(SIDE, SIDE, FloatArray(SIDE * SIDE) { SEA_FLOOR_FIELD_LEVEL })
        for (row in SUBMERGED_ROWS) {
            for (column in SUBMERGED_COLUMNS) {
                val cell = row * SIDE + column
                isLand[cell] = true
                elevation.data[cell] = SUBMERGED_LAND_FIELD_LEVEL
            }
        }
        for (row in PLATEAU_ROWS) {
            for (column in PLATEAU_COLUMNS) {
                val cell = row * SIDE + column
                isLand[cell] = true
                elevation.data[cell] = PLATEAU_FIELD_LEVEL
            }
        }
        elevation.data[PLATEAU_PIT_ROW * SIDE + PLATEAU_PIT_COLUMN] = PLATEAU_PIT_FIELD_LEVEL
        return Grid(isLand, elevation)
    }

    private companion object {
        const val SIDE = 32

        /** The water the whole grid starts as. */
        const val SEA_FLOOR_FIELD_LEVEL = -0.1f

        /** The enclosed-water rule's leavings: land that never came up when it stopped being sea. */
        const val SUBMERGED_LAND_FIELD_LEVEL = -0.2f
        val SUBMERGED_ROWS = 12..19
        val SUBMERGED_COLUMNS = 12..19

        /** An ordinary coast, with a pit in it that an ordinary priority flood has always filled. */
        const val PLATEAU_FIELD_LEVEL = 0.05f
        const val PLATEAU_PIT_FIELD_LEVEL = 0.01f
        val PLATEAU_ROWS = 3..7
        val PLATEAU_COLUMNS = 3..7
        const val PLATEAU_PIT_ROW = 5
        const val PLATEAU_PIT_COLUMN = 5

        /**
         * How far above its spill level a filled cell may stand, in the field's own units.
         *
         * The flood raises each cell it takes to the last one's level plus
         * [FlowRouting.FLAT_GRADIENT_STEP], a millionth, so a patch eight cells across finishes a
         * few millionths above the level it spills over. A thousandth is far over that staircase
         * and far under the nearest wrong answer, which is the plateau's 0.05 — a seventh of the
         * field's whole span above the water the patch actually spills into.
         */
        const val SPILL_TOLERANCE = 1e-3f
    }
}
