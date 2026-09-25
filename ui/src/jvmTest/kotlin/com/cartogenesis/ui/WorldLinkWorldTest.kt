package com.cartogenesis.ui

import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.worldgen.ReachableState
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A link opened makes the world it was copied from: the same world, field for field.
 *
 * `WorldLinksTest` holds the settings; this holds the world, because the settings being equal is a
 * claim about the generator as much as about the link — that nothing a world is made from escapes
 * the config. Both worlds are fingerprinted by [ReachableState], the walk `WorldFingerprintTest`
 * uses, over every array, list and name in the world.
 *
 * At 128 cells, which no link names: a link carries one of the panel's sizes, the smallest of which
 * is 512, and this module's tests generate nothing larger than 128. So the link is written from the
 * world at 512 and both configs are then taken to 128 the way the panel's own resolution chips take
 * a world between sizes, [Knobs.atResolution]. The size itself is held by the settings comparison.
 * On the JVM only, because the walk is reflection.
 */
class WorldLinkWorldTest {

    @Test
    fun `a link made from a world with changed settings opens the identical world`() {
        var original = WorldGenConfig(seed = 718106L, width = 512, height = 512)
        original = Knobs.oceanCoverage.set(original, 0.48f)
        original = Knobs.plates.set(original, 9)
        original = Knobs.mountainHeight.set(original, 0.71f)
        original = Knobs.seasonalTiltDegrees.set(original, 21.5f)
        original = Knobs.rainShadow.set(original, 3.3f)
        original = Knobs.realms.set(original, 5)
        original = Knobs.wilderness.set(original, true)
        original = Knobs.landmarkCount.set(original, 12f)
        val link = WorldLinks.linkTo(WorldLinks.PUBLIC_APP_ADDRESS, original, RenderOptions())
        println("WORLD LINK fingerprinted world: $link")

        // What a browser window at another seed and size makes of the link.
        val elsewhere = WorldGenConfig(seed = 1L, width = 1024, height = 1024).atResolution(1024, 1024)
        val opened = WorldLinks.read(link, elsewhere, RenderOptions(), WorldCeilings.BROWSER_TAB).config
        assertEquals(original, opened, "the link opens other settings")

        val made = digests(original)
        val remade = digests(opened)
        assertTrue(made.size > 20, "the walk found only ${made.size} branches, so it compared almost nothing")
        val differing = made.keys.filter { made[it] != remade[it] }
        assertTrue(differing.isEmpty(), "the linked world differs from the one it was copied from in ${differing.joinToString()}")

        // The control: the same walk tells the world from its neighbour one plate away, so an
        // empty list above is two identical worlds rather than a comparison that sees nothing.
        val neighbour = digests(Knobs.plates.set(original, 10))
        assertTrue(made.keys.any { made[it] != neighbour[it] }, "the fingerprint does not see a change of plate count")
    }

    private fun digests(config: WorldGenConfig): Map<String, Long> =
        ReachableState.digestsByBranch(
            WorldGenerationEngine.generateBlocking(Knobs.atResolution(config, FINGERPRINT_CELLS))
        )

    private companion object {
        /** The largest world this module's tests generate; see the note on `jvmTest` in its build script. */
        const val FINGERPRINT_CELLS = 128
    }
}
