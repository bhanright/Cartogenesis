package com.cartogenesis.worldgen

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * F30b at the size the defect was seen: the world whose crop opened the chunk, once over the
 * potential and once over the fill's staircase, and the ruled-run census of each over raised
 * ground.
 *
 * Measured on the day the chunk landed, in one run: **76 ruled runs over raised ground with the
 * staircase against 44 with the potential**, 9.0 against 5.2 per thousand drawn cells, the longest
 * 31 cells against 12 (25 on the tree the branch left, where the run at (431,109), dead north
 * across a filled flat, is the one the crop showed). The census over unraised ground barely moves (181 to 166), because those cells
 * were already routed by the facet rule; what moved is the flats.
 *
 * Asserted the way F18's control is: strictly fewer with the potential than without, on the same
 * world in the same run, so the two rules are told apart by the map rather than by a bar somebody
 * chose. The remainder with the potential is printed and not asserted: some of it is a corridor
 * the terrain made one cell wide, which no routing can bend, and how much wants looking at.
 *
 * The lakes are printed beside the runs because they are what a change to the routing could
 * cost: on this world standing water read 0.995% of land with the staircase and 1.274% with the
 * potential, and on the four standard seeds at 512 the same comparison goes up on two and down
 * on two, pooled 0.712% to 0.704%, which is a re-routed world's scatter and not a direction.
 */
class FlatCourseAuditTest {

    private companion object {
        const val CROP_SEED = 718106L
        const val CROP_SIDE = 2048
    }

    @Test
    fun `the potential draws fewer rulers across the flats than the staircase`() {
        val potential = FlatCourse.census(FlatCourse.world(CROP_SEED, CROP_SIDE, overPotential = true))
        val staircase = FlatCourse.census(FlatCourse.world(CROP_SEED, CROP_SIDE, overPotential = false))
        println("F30B seed $CROP_SEED@$CROP_SIDE potential: $potential")
        println("F30B seed $CROP_SEED@$CROP_SIDE staircase: $staircase")
        assertTrue(
            potential.overRaised < staircase.overRaised,
            "the potential left ${potential.overRaised} ruled runs over raised ground against the " +
                "staircase's ${staircase.overRaised}, so the two cannot be told apart"
        )
        assertTrue(
            potential.perThousandRaised < staircase.perThousandRaised,
            "per thousand drawn cells over raised ground the potential reads %.2f against the staircase's %.2f".format(
                potential.perThousandRaised, staircase.perThousandRaised
            )
        )
    }
}
