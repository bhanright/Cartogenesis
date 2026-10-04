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
     * height, uplift and age moved with everything built on them. Re-taken at E1a, whose two heights
     * moved the erosion and the sea and whose re-derived uplift moved the plates' rates, the plates'
     * height held, and at its second round, which moved the erosion and the sea alone; and at E1b
     * and E1c, which cut the lakes' outlets on their surplus and carried the bed through the sea
     * stage, the erosion's and the sea's own fields with them. An ocean change that leaks into the
     * ground moves them.
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
            "terrain.normals" to -5249155343601744929L,
            "terrain.height" to -3894689728446501406L,
            "plates.plates" to -986469721537853709L,
            "plates.plateId" to -7728668575489595548L,
            "plates.boundaryDistance" to -825292753321544589L,
            "plates.nearestBoundaryType" to 4665505714802344748L,
            "plates.nearestBoundaryClass" to 2457436364073694675L,
            "plates.height" to 8675788897625632006L,
            "plates.seafloorAgeMyr" to -8620603663144917145L,
            "plates.seafloorHalfSpreadingRateKmPerMyr" to 5508627893530768010L,
            "plates.continentalShare" to 711069935001870004L,
            "plates.upliftRateMmPerYear" to -5671693104175984468L,
            "plates.crustAge" to 4850336704889317196L,
            "erosion.height" to 852415301455099342L,
            "erosion.bed" to -3370825848173762235L,
            "erosion.channelHeadAreaKm2" to -2801279280926584646L,
            "erosion.sweptOnDevice" to -358906410940142731L,
            "sea.shorelineHeight" to 896537881028781512L,
            "sea.isLand" to -6367173104496679145L,
            "sea.relativeElevation" to -1359760481015986125L,
            "sea.landCellCount" to 5547778436523423956L,
            "sea.relativeBed" to -6073697706743964128L
        )
    }
}
