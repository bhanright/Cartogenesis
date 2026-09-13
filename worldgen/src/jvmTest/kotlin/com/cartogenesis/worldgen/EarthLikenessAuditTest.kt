package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.test.Test
import org.junit.Assert.assertTrue

/**
 * The Earth-likeness yardstick at export resolution: the four standard seeds and the author's own
 * two, all at 2048 on the settings he generates at.
 *
 * The audit tier because six worlds at 2048 is what it costs, and it is worth the cost for two
 * reasons the 512 suite cannot cover. The first is that half the metrics are about size
 * distributions and small features — lakes, islands, first-order streams — and a grid with sixteen
 * times the cells resolves a decade more of each, so a Pareto exponent measured on fifty-eight
 * pooled lakes at 512 is measured on several hundred here. The second is the cell area: the lake
 * share of land is compared against Downing's figure *at the map's own floor*, and the floor at
 * 2048 is 17 km2 against 275 at 512, which is a different question about the same world.
 *
 * Nothing is asserted here that [EarthLikenessTest] does not assert at 512. The bars are Earth's
 * and are the same ones; what this class adds is the finer grid's answer and the author's own two
 * seeds, printed for the ledger.
 */
class EarthLikenessAuditTest {

    @Test
    fun `every metric of the suite at 2048, on the author's own settings`() {
        val pool = EarthLikeness.Pool()
        val perSeed = seeds.map { seed ->
            val world = WorldGenerationEngine.generateBlocking(authorConfig(seed))
            EarthLikeness.measure(world, "$seed@2048", pool).also { EarthLikeness.print(it) }
        }
        val pooled = pool.pooled("pooled@2048")
        EarthLikeness.print(pooled)
        pool.desertBands.report(seeds, "EARTH BAND 2048")
        EarthLikeness.findings(pooled).forEachIndexed { rank, finding ->
            println("EARTH FINDING 2048 ${rank + 1}. $finding")
        }

        val complaints = ArrayList<String>()
        perSeed.forEach { complaints += EarthLikeness.complaints(it, oneWorld = true) }
        complaints += EarthLikeness.complaints(pooled, oneWorld = false)
        assertTrue(
            "the Earth-likeness suite at 2048 has regressed on metrics the generator was meeting" +
                " at 512: " + complaints.joinToString("; "),
            complaints.isEmpty()
        )
    }

    private companion object {
        /**
         * The four standard seeds and the two the author generates: 718106, the world every
         * coastline and rift complaint since E4 has been raised on, and 59758, the one with the
         * long flooded rift.
         */
        val seeds = listOf(7L, 42L, 1234L, 99L, 718106L, 59758L)

        /**
         * The desktop app's own settings — ocean at 62%, fourteen plates, twelve realms — which
         * have been `WorldGenConfig`'s defaults since they were made so, and 2048 because that is
         * the size the author exports at. Written out the way `GlaciationAuditTest.authorConfig`
         * writes it: built at 512 and re-targeted, so every length measured in cells is scaled.
         */
        fun authorConfig(seed: Long): WorldGenConfig =
            WorldGenConfig(seed = seed, width = 512, height = 512).atResolution(2048, 2048)
    }
}
