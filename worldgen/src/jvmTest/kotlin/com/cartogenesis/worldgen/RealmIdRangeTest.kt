package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WildernessMode
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.NationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every realm id on the map is an index into the realm list.
 *
 * `NationStage.describe` sizes all of its per-realm arrays by the capital list and then counts
 * cells straight into them, so a single cell holding an id past the end of that list is an
 * `ArrayIndexOutOfBoundsException` from `counts[owner]++` — a crash whose stack points at the
 * counting loop rather than at whichever step wrote the id. William hit exactly that shape on his
 * own world at 2048 after Track E landed, so the world he reported it on is the first case here,
 * at the size he reported it at.
 *
 * The check is on the *product*, not on any one step, because the id travels through three of
 * them: `BasinRealms.assign` numbers the realms, `leaveWilderness` releases cells, and
 * `dissolveEnclaves` hands pockets to their neighbours. `NationStage` now asserts the same
 * invariant after the first and the last of those, so a failure here names the step rather than
 * the symptom.
 */
class RealmIdRangeTest {

    private fun authorsConfig(seed: Long): WorldGenConfig {
        val base = WorldGenConfig(seed = seed, width = 512, height = 512, seaLevel = 0.62f)
        return base.copy(
            tectonics = base.tectonics.copy(plateCount = 14),
            nations = base.nations.copy(nationCount = 12)
        )
    }

    private fun assertRealmIdsInRange(config: WorldGenConfig) {
        val world = WorldGenerationEngine.generateBlocking(config)
        val nations = world.nations.nations
        val ids = world.nations.nationId

        // A realm's position in the list is its id. Everything that reads a world by realm — the
        // atlas, the renderer, the overrides a user saves — takes that for granted, and the
        // per-realm arrays inside the nation stage are built on it outright.
        nations.forEachIndexed { index, nation ->
            assertEquals(
                index, nation.id,
                "realm at position $index calls itself ${nation.id}"
            )
        }

        var worst = -1
        var offenders = 0
        for (i in ids.indices) {
            val realm = ids[i]
            if (realm == NationResult.UNCLAIMED || realm in nations.indices) continue
            offenders++
            if (realm > worst) worst = realm
        }
        assertTrue(
            offenders == 0,
            "seed ${config.seed} at ${config.width}x${config.height}: $offenders cells hold a " +
                "realm id outside 0..${nations.size - 1} (largest $worst)"
        )
    }

    /** The world the crash was reported on, at the size it was reported at. */
    @Test
    fun `the author's world at 2048 numbers every cell inside its realm list`() {
        assertRealmIdsInRange(authorsConfig(718106L).atResolution(2048, 2048))
    }

    /**
     * Cheap cases, and deliberately varied: wilderness changes which steps run at all — it is what
     * turns `leaveWilderness` on and `claimStragglers` off — so both modes are worth a pass.
     */
    @Test
    fun `varied worlds number every cell inside their realm list`() {
        for (seed in listOf(718106L, 59758L, 7L, 42L, 1234L)) {
            assertRealmIdsInRange(authorsConfig(seed))
        }
        for (seed in listOf(718106L, 42L)) {
            val config = authorsConfig(seed)
            assertRealmIdsInRange(
                config.copy(nations = config.nations.copy(wilderness = WildernessMode.LEAVE_WILDERNESS))
            )
        }
        assertRealmIdsInRange(authorsConfig(718106L).atResolution(1024, 1024))
    }
}
