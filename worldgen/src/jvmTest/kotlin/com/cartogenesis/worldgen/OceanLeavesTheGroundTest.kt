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
     * plates are fifteen. An ocean change that leaks into the ground moves them.
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
            "plates.plates" to -8565988885517275170L,
            "plates.plateId" to -7659896205567335042L,
            "plates.boundaryDistance" to 6559268612307642171L,
            "plates.nearestBoundaryType" to -4092876749757560349L,
            "plates.nearestBoundaryClass" to 8537704601329378444L,
            "plates.height" to 6687649860481647278L,
            "plates.seafloorAgeMyr" to -5558611328326441912L,
            "plates.seafloorHalfSpreadingRateKmPerMyr" to 2092863834869833716L,
            "plates.continentalShare" to 5707884443378334786L,
            "plates.upliftRateMmPerYear" to -1700415688480725156L,
            "plates.crustAge" to 8840618518047909851L,
            "erosion.height" to -764119881059144335L,
            "erosion.sweptOnDevice" to -358906410940142731L,
            "sea.shorelineHeight" to 8930012999547282008L,
            "sea.isLand" to 8576149400318180388L,
            "sea.relativeElevation" to -5882165350155285745L,
            "sea.landCellCount" to -819146187983780767L
        )
    }
}
