package com.cartogenesis.ui

import com.cartogenesis.cartography.WorldOverrides
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.naming.NameForge
import com.cartogenesis.worldgen.pipeline.Culture
import com.cartogenesis.worldgen.pipeline.Nation
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * What the map says about itself, in the corner of the sheet.
 *
 * A printed chart carries a cartouche: the name of the country, then the small print — the scale,
 * the projection, the surveyor, the year. The application had a status line instead, one run-on
 * sentence of `Seed 59758 · 512x512 in 1840 ms · 12 realms · 431 rivers`, filed in the settings
 * panel where it read as a debug print rather than as part of the map. F3 makes it a cartouche and
 * moves it onto the sheet, at the left of the legend along the map's bottom edge.
 *
 * Three parts, in descending weight:
 *
 *  - the **name of the world**, set in the display face. Worlds had no names at all before this;
 *    a seed is how you *return* to one, not what you call it.
 *  - the **facts**: seed, working resolution, and the largest realm with its share of the land.
 *  - the **footnote**: how long the world took to make, in the muted colour, because it is a fact
 *    about this machine rather than about the world.
 *
 * Everything here is a pure function of a finished world, so it is all testable without a
 * composition — see `CartoucheTest`. Nothing in this file generates anything or touches Compose.
 */
internal data class Cartouche(
    val worldName: String,
    val facts: String,
    /** Empty until a world has actually been generated in this session (an opened save has not). */
    val footnote: String
)

internal object Cartouches {

    /**
     * The world's own name, in the language of the people who hold most of it.
     *
     * Every culture already carries a `nameSeed`, which is the seed of its phonetics — the thing
     * that makes one people's names full of hard stops and its neighbour's full of sibilants. The
     * name of the world is a bare word in the largest people's language, so a world of the Verrin
     * peoples is called something a Verrin speaker could pronounce.
     *
     * Deterministic from the seed, as the spec asks: the language is chosen by cell count (ties to
     * the lower id, so the answer cannot depend on list order), and the word is drawn from a
     * generator seeded with the world seed alone. The same seed therefore always names the same
     * world, and re-rendering, re-opening or re-styling it never renames it.
     *
     * A world with no peoples at all — every hearth boxed in by ice, or `Realms` and cultures
     * turned right down — still gets a name, from a language derived from the seed itself. That is
     * a different case from the blank canvas, which has no cartouche at all.
     */
    fun worldName(seed: Long, languageSeed: Long?): String {
        val language = languageSeed ?: (seed * 31 + 1_013)
        // Three syllables: a one-syllable world name reads as a typo and the generator's own
        // ceiling is three.
        return NameForge.styleFor(language).word(Random(seed), 3)
    }

    /** `seed 59758 · 2048 × 2048 · largest realm Kelmaria (23%)`. */
    fun facts(seed: Long, width: Int, height: Int, largestRealm: String?, share: Int): String {
        val head = "seed $seed · $width × $height"
        return if (largestRealm == null) head else "$head · largest realm $largestRealm ($share%)"
    }

    /** `generated in 1.8 s`. Sub-second worlds are quoted in milliseconds, as they were. */
    fun footnote(millis: Long): String = when {
        millis <= 0L -> ""
        millis < 1_000L -> "generated in $millis ms"
        else -> "generated in ${(millis / 100L) / 10.0} s"
    }

    /** Whole percent of the world's *land* — the sea is nobody's, so it is not in the divisor. */
    fun share(cellCount: Int, landCellCount: Int): Int =
        if (landCellCount <= 0) 0 else (cellCount * 100f / landCellCount).roundToInt()

    /**
     * The cartouche for a finished world.
     *
     * [millis] is how long the last generation took, or zero for a world that was opened from the
     * library rather than made here — there is no honest time to quote for those.
     */
    fun of(world: WorldMap, overrides: WorldOverrides, millis: Long): Cartouche {
        val people = world.cultures.cultures.maxWithOrNull(
            compareBy<Culture> { it.cellCount }.thenByDescending { it.id }
        )
        val realm = world.nations.nations.maxWithOrNull(
            compareBy<Nation> { it.cellCount }.thenByDescending { it.id }
        )
        return Cartouche(
            worldName = worldName(world.config.seed, people?.nameSeed),
            facts = facts(
                seed = world.config.seed,
                width = world.config.width,
                height = world.config.height,
                largestRealm = realm?.let { overrides.forNation(it.id).name ?: it.name },
                share = realm?.let { share(it.cellCount, world.sea.landCellCount) } ?: 0
            ),
            footnote = footnote(millis)
        )
    }
}
