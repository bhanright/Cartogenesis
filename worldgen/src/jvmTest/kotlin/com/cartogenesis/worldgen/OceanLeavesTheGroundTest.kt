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
     * The ground seed 42 stands on at 512, pinned: every field of the terrain, the plates, the
     * erosion and the sea, digested branch by branch as `ReachableState` digests a world.
     *
     * The case above compares the currents on and off inside one tree, and cannot see a change
     * that moves both alike. These are the digests the tree before chunk 4a drew (3a66025), measured
     * identical on the branch for seeds 7, 42, 1234 and 99 at 512 and 969495 at 2048
     * (docs/DESIGN_LEDGER.md, 4a); an ocean change that leaks into the ground moves them. A chunk
     * meant to move the ground re-takes them here, as the render records are re-taken.
     */
    @Test
    fun `seed 42's ground is the one the tree before the ocean chunk drew`() {
        val world = SharedWorlds.world(WorldGenConfig.forRows(42L, 512))
        val ground = ReachableState.digestsByBranch(world).filterKeys { branch ->
            GROUND_BRANCHES.any { branch.startsWith(it) }
        }
        assertEquals(GROUND_AT_512, ground, "seed 42's ground moved")
    }

    private companion object {
        val GROUND_BRANCHES = listOf("terrain.", "plates.", "erosion.", "sea.")

        val GROUND_AT_512: Map<String, Long> = mapOf(
            "terrain.normals" to -3957635811720869786L,
            "terrain.height" to -7794187521108087658L,
            "plates.plates" to -3453775354215011380L,
            "plates.plateId" to -960170144707551208L,
            "plates.boundaryDistance" to 2999928083810557773L,
            "plates.nearestBoundaryType" to -1609309034894245319L,
            "plates.nearestBoundaryClass" to 2920887103839809940L,
            "plates.height" to -3922716849206942368L,
            "plates.seafloorAgeMyr" to 2114563380794089770L,
            "plates.seafloorHalfSpreadingRateKmPerMyr" to -3927593839454705991L,
            "plates.continentalShare" to -4440896453843652197L,
            "plates.upliftRateMmPerYear" to -2287229503424040108L,
            "plates.crustAge" to 777488221023972833L,
            "erosion.height" to -9066333811287384399L,
            "erosion.sweptOnDevice" to -358906410940142731L,
            "sea.shorelineHeight" to 8640021679815207680L,
            "sea.isLand" to 539254778493484283L,
            "sea.relativeElevation" to -6837861070948377844L,
            "sea.landCellCount" to -926338647955881927L
        )
    }
}
