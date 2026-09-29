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
 * At 64 rows, which no link names: a link carries one of the panel's sizes, the smallest of which
 * is 512, and this module's tests generate nothing more than 128 cells across. So the link is
 * written from the world at 512 and both configs are then taken to 64 rows the way the panel's own
 * resolution chips take a world between sizes, [Knobs.atResolution]. The size itself is held by
 * the settings comparison. On the JVM only, because the walk is reflection.
 */
class WorldLinkWorldTest {

    @Test
    fun `a link made from a world with changed settings opens the identical world`() {
        val original = changed(Knobs.atResolution(WorldGenConfig(seed = 718106L), 512))
        val link = WorldLinks.linkTo(WorldLinks.PUBLIC_APP_ADDRESS, original, RenderOptions())
        println("WORLD LINK fingerprinted world: $link")

        // What a window at another seed and size makes of the link: nothing but the seed, the size
        // and where the work runs may pass from the window into the world the link makes.
        val elsewhere = Knobs.atResolution(WorldGenConfig(seed = 1L), 1024)
        val opened = WorldLinks.read(link, elsewhere, RenderOptions(), WorldCeilings.BROWSER_TAB).config

        // The worlds first, so a failure names the fields of the world that moved.
        val made = digests(Knobs.atResolution(original, FINGERPRINT_ROWS))
        val remade = digests(Knobs.atResolution(opened, FINGERPRINT_ROWS))
        assertTrue(made.size > 20, "the walk found only ${made.size} branches, so it compared almost nothing")
        val differing = made.keys.filter { made[it] != remade[it] }
        assertTrue(differing.isEmpty(), "the linked world differs from the one it was copied from in ${differing.joinToString()}")
        assertEquals(original, opened, "the link opens other settings")

        // The control: the same walk tells the world from its neighbour one plate away, so an
        // empty list above is two identical worlds rather than a comparison that sees nothing.
        val neighbour = digests(Knobs.atResolution(Knobs.plates.set(original, 10), FINGERPRINT_ROWS))
        assertTrue(made.keys.any { made[it] != neighbour[it] }, "the fingerprint does not see a change of plate count")
    }

    /**
     * A link of the first format was copied from a world as many cells tall as wide, and opens,
     * with its size read as rows, a world of square cells: the fingerprint tells the two apart,
     * which is what the line the link opens with says. No converter could make the older world.
     */
    @Test
    fun `a link of the first format opens a world that differs from the one it was copied from`() {
        val copiedFrom = changed(WorldGenConfig(seed = 718106L).atResolution(512, 512))
        val olderLink = WorldLinks.linkTo(WorldLinks.PUBLIC_APP_ADDRESS, copiedFrom, RenderOptions())
            .replace("#v=${WorldLinks.FORMAT_VERSION}&", "#v=1&")
        println("WORLD LINK first format: $olderLink")
        val opening = WorldLinks.read(olderLink, WorldGenConfig(seed = 1L), RenderOptions(), WorldCeilings.DESKTOP)
        assertTrue(WorldLinks.OLDER_SIZE_NOTICE in opening.notice.orEmpty(), "the older link opened without a word")

        val then = digests(copiedFrom.atResolution(FINGERPRINT_ROWS, FINGERPRINT_ROWS))
        val now = digests(Knobs.atResolution(opening.config, FINGERPRINT_ROWS))
        assertTrue(then.keys.any { then[it] != now[it] }, "the older link's world and the world it opens are the same")
        // Every setting but the grid came through.
        assertEquals(copiedFrom.atResolution(opening.config.width, opening.config.height), opening.config)
    }

    /** Several knobs away from their defaults, so the link has something to carry. */
    private fun changed(start: WorldGenConfig): WorldGenConfig {
        var config = start
        config = Knobs.oceanCoverage.set(config, 0.48f)
        config = Knobs.plates.set(config, 9)
        config = Knobs.mountainHeight.set(config, 0.71f)
        config = Knobs.seasonalTiltDegrees.set(config, 21.5f)
        config = Knobs.rainShadow.set(config, 3.3f)
        config = Knobs.realms.set(config, 5)
        config = Knobs.wilderness.set(config, true)
        config = Knobs.landmarkCount.set(config, 12f)
        return config
    }

    private fun digests(config: WorldGenConfig): Map<String, Long> =
        ReachableState.digestsByBranch(WorldGenerationEngine.generateBlocking(config))

    private companion object {
        /**
         * The rows the worlds are compared at: 128 by 64 cells, inside the largest grid this
         * module's tests generate; see the note on `jvmTest` in its build script.
         */
        const val FINGERPRINT_ROWS = 64
    }
}
