package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The ocean reaches the climate and nothing beneath it.
 *
 * The erosion's provisional climates and the glaciation's read the sea through
 * `OceanStage.withoutCurrents`, which switches the currents off before any of their workings are
 * reached, so a world solved with its currents and the same world solved without them stand on the
 * same ground to the last bit. The ocean's circulation was rebuilt entirely inside that boundary;
 * this is what says it stayed there, and what would say so again if a later change let the
 * currents into the terrain.
 */
class OceanLeavesTheGroundTest : BorrowsSharedWorlds() {

    @Test
    fun `the ground is the same with the currents and without them`() {
        val config = WorldGenConfig.forRows(42L, 128)
        val withCurrents = WorldGenerationEngine.generateBlocking(config)
        val without = WorldGenerationEngine.generateBlocking(config.copy(ocean = config.ocean.copy(enabled = false)))
        assertContentEquals(without.sea.relativeElevation.data, withCurrents.sea.relativeElevation.data, "the currents moved the ground")
        assertContentEquals(without.sea.isLand, withCurrents.sea.isLand, "the currents moved the coast")
    }

    /**
     * The ground seed 42 stands on at 512 rows, pinned: every field of the terrain, the plates, the
     * erosion and the sea, digested branch by branch as `ReachableState` digests a world.
     *
     * The case above compares the currents on and off inside one tree, and cannot see a change
     * that moves both alike. The digests were first the tree before chunk 4a drew (3a66025), at 512
     * by 512 (docs/DESIGN_LEDGER.md, 4a), and were re-taken at Q2, which moved the ground on purpose
     * and builds the world on square cells, and at L1, whose rifts are Earth's half-grabens, and
     * again at its review round, whose joins are relay ramps and whose valleys are Earth's width,
     * 55 km across: the plates' partition held and their
     * height, uplift and age moved with everything built on them. K1 re-took the erosion's and the
     * sea's, the terrain and the plates holding: the erosion is fed a provisional climate, whose
     * weather noise K1 made square on the ground, and the sea stage carries the ice's scour, which
     * K1 made square too. K2 re-took every one of them, terrain included: the default planet became
     * Earth's, 40,075 km round, so every lattice on the ground is another count of cycles and the
     * plates are fifteen. C1b re-took the erosion's and the sea's, the terrain and the plates
     * holding: the erosion is fed the provisional climate's rain, which the conserving moisture march
     * redrew, and the sea stage carries the ice its snow feeds. C1b2 re-took the same two: the march's
     * sinks and wind moved the provisional rain, and the ice's profile is Glen's law's. A1-1 re-took
     * the same two: the provisional climate's seasons became the calendar's and its sea surface
     * takes its share of the sun. A1-2 re-took the same two: the provisional climate's pressure wind
     * is smoothed and differentiated on the sphere. A1-4 and A1-5 re-took the same two: the
     * provisional climate's wind became the boundary layer's, and then its rain the coupled
     * atmosphere's vertical motion's. H1 re-took the plates', the erosion's and the sea's, the
     * terrain and the partition holding: Earth's submerged share draws more of the plates as
     * continents, and the platform floats at its own freeboard with its closed sags filled; the
     * merge of H1 with the climate above re-took the same three. A1-6a re-took the erosion's and
     * the sea's: the provisional climate's circulation became Lindzen and Hou's, and the erosion's
     * halfway weather starts where its opening weather settled. A1-6b re-took the same two: the
     * provisional climate's cells are heated about the energy flux equator and its westerlies
     * balance the hemisphere's torque. A1-6c re-took the same two: the provisional climate's water
     * layer shoals in summer, its sea evaporates into air under the water by Kara's difference and its
     * march leaves each cell at its downwind face's speed. W2b re-took the same two: the provisional
     * climate's march solves each crossing as one budget, reads its saturated column on the rising
     * parcel's profile, and rains by one law. W2c re-took the same two: the provisional climate's
     * water stands Kara's difference above its air and the ITCZ's cloud keeps its longwave. W2d re-took
     * the same two: the provisional climate's ground gives back each half-year's share as far as its
     * rain and the root zone's store supply it. W2e re-took the same two: the provisional climate's
     * march carries a boundary layer and the free troposphere on their own winds. W2f re-took the
     * same two by a few tenths of a percent of the rain: the provisional climate's free troposphere
     * takes in the boundary layer's water of the same lap. W2h re-took the same two: the provisional
     * climate's stationary waves are extrapolated to their converged answer and its Hadley cells end
     * at each sector's own baroclinic onset. TH1-1 re-took the plates', the erosion's and the sea's,
     * the terrain holding: the crust and the plates are the tectonic history's, the plate result
     * gained the crust's thickness, excess, orogeny, terrane and rift age, and the sea covers 71% of
     * the planet. An ocean change that leaks into the ground moves them.
     * A chunk meant to move the ground re-takes them here, as the render records are re-taken.
     * At [SharedWorlds.DETAIL_ROWS], whose standard worlds the detail guards generate anyway, so
     * the pin costs a digest and no generation.
     */
    @Test
    fun `seed 42's ground is the one the last chunk that moved it drew`() {
        val world = SharedWorlds.world(WorldGenConfig.forRows(42L, SharedWorlds.DETAIL_ROWS))
        val ground = ReachableState.digestsByBranch(world).filterKeys { branch ->
            GROUND_BRANCHES.any { branch.startsWith(it) }
        }
        assertEquals(GROUND_AT_512_ROWS, ground, "seed 42's ground moved")
    }

    private companion object {
        val GROUND_BRANCHES = listOf("terrain.", "plates.", "erosion.", "sea.")

        val GROUND_AT_512_ROWS: Map<String, Long> = mapOf(
            "terrain.normals" to 6074751788244030761L,
            "terrain.height" to 7240865613227243168L,
            "plates.plates" to 379040954520218981L,
            "plates.plateId" to -1654393875004582752L,
            "plates.boundaryDistance" to -3697201925907623157L,
            "plates.nearestBoundaryType" to 2661699474975711525L,
            "plates.nearestBoundaryClass" to -7053982113971973358L,
            "plates.height" to 5883689036146719620L,
            "plates.seafloorAgeMyr" to -4468759091889903680L,
            "plates.seafloorHalfSpreadingRateKmPerMyr" to -31888459924713235L,
            "plates.continentalShare" to -4507206427882200106L,
            "plates.upliftRateMmPerYear" to 8583129092028575917L,
            "plates.crustAgeMyr" to 408559506598596169L,
            "plates.crustThicknessKm" to -972305557364000144L,
            "plates.orogenicExcessKm" to 2973354533547496891L,
            "plates.orogenyKind" to -2520811977854164394L,
            "plates.terraneId" to -6682424409308876173L,
            "plates.riftAgeMyr" to 7635162345279755684L,
            "erosion.height" to 4954386492872548780L,
            "erosion.sweptOnDevice" to -358906410940142731L,
            "sea.shorelineHeight" to 325187735420036943L,
            "sea.isLand" to 8209212411630080155L,
            "sea.relativeElevation" to 5081943366559536114L,
            "sea.landCellCount" to -8138957920798330060L
        )
    }
}
