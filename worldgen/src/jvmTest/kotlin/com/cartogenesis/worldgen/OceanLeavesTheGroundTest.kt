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
     * the planet. TH1-1b re-took the same three: the plates move under the slab's resistance, the
     * supercontinent is docked from blocks, a stretched margin carries McKenzie's heat and the sea's
     * share is the planet's area. TH1-2 re-took the same three: the upper plates deform at their
     * trenches, the belts are the history's crust with ridges along strike and the rock's
     * erodibility, the young belts' window is replayed by the erosion, and a rift needs continent on
     * both sides. TH1-2c re-took the same three: a hot back-arc stands behind every live trench and
     * cools at its own time constant, and each world's lattice is turned by its seed. An ocean change that leaks into the ground moves them.
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
            "plates.plates" to 3917137307598863656L,
            "plates.plateId" to 401508425367660866L,
            "plates.boundaryDistance" to 6689825411907013802L,
            "plates.nearestBoundaryType" to 2365921136121937757L,
            "plates.nearestBoundaryClass" to 4469012307783500296L,
            "plates.height" to 5618236171103727664L,
            "plates.seafloorAgeMyr" to 38077735158606035L,
            "plates.seafloorHalfSpreadingRateKmPerMyr" to 3664504083695585842L,
            "plates.continentalShare" to -6927718574923087874L,
            "plates.upliftRateMmPerYear" to -6945949472492381772L,
            "plates.crustAgeMyr" to -8657102125320745076L,
            "plates.crustThicknessKm" to -5494104400115855287L,
            "plates.orogenicExcessKm" to -8829537918862340492L,
            "plates.orogenyKind" to -4637805461740389800L,
            "plates.terraneId" to 3102677728008120338L,
            "plates.riftAgeMyr" to -122927204389976897L,
            "plates.rockErodibility" to 3182590276419559818L,
            "plates.windowTectonicKm" to -1017985078175827327L,
            "plates.windowWearKm" to 1851443143122907588L,
            "plates.windowOnsetMyrAgo" to 4035947595858816465L,
            "plates.exposureWindowMyr" to 5003754825279433589L,
            "erosion.height" to -534356067670680851L,
            "erosion.sweptOnDevice" to -358906410940142731L,
            "sea.shorelineHeight" to -1173977029005291191L,
            "sea.isLand" to -1712408077943130106L,
            "sea.relativeElevation" to -5168511183198189431L,
            "sea.landCellCount" to -623822849077492323L
        )
    }
}
