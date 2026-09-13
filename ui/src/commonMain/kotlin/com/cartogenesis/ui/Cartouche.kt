package com.cartogenesis.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cartogenesis.cartography.MapScale
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.naming.NameForge
import com.cartogenesis.worldgen.pipeline.Culture
import kotlin.random.Random

/**
 * What the map says about itself, in the corner of the sheet.
 *
 * A printed chart carries a cartouche: the name of the country, then the small print — the scale,
 * the projection, the surveyor, the year. The application had a status line instead, one run-on
 * sentence of `Seed 59758 · 512x512 in 1840 ms · 12 realms · 431 rivers`, filed in the settings
 * panel where it read as a debug print rather than as part of the map. Here it is a cartouche, on
 * the sheet, at the left of the legend along the map's bottom edge.
 *
 * Three parts, in descending weight:
 *
 *  - the **name of the world**, set in the display face. Worlds had no names at all before this;
 *    a seed is how you *return* to one, not what you call it. The name is generated but not
 *    fixed — [WorldNaming] holds it, the header's Name field edits it, and it is what the save is
 *    filed under.
 *  - the **facts**: the seed and the working resolution, and nothing else. The largest realm and
 *    its share were here for a draft and read as a statistic rather than as a caption.
 *  - the **scale**: how far one pixel of the sheet reaches on the ground, and the representative
 *    fraction that follows from it, under the title where a printed chart puts it — see
 *    [com.cartogenesis.cartography.MapScale] for why the fraction is quoted the way it is and why
 *    it says *at the equator*.
 *  - the **footnote**: how long the world took to make, in the muted colour, because it is a fact
 *    about this machine rather than about the world.
 *
 * Everything here is a pure function of a finished world, so it is all testable without a
 * composition — see `CartoucheTest`. Nothing in this file generates anything or touches Compose.
 */
internal data class Cartouche(
    val worldName: String,
    val facts: String,
    /** `5.9 km per pixel · about 1:22 000 000 at the equator`, for the size [facts] quotes. */
    val scale: String,
    /**
     * How wide one cell of this world is on the ground.
     *
     * The legend's scale bar is the same arithmetic as [scale] taken at the zoom the reader is at
     * rather than at the sheet's own size, and this is what it needs to do it with.
     */
    val kilometresPerCellWidth: Double,
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
        // A world with no peoples has no language to be named in, so one is derived from the
        // world's own seed. Any invertible mixing would do; this is the seed put through an odd
        // multiplier and an odd offset so that neighbouring seeds do not land on the same
        // phonetics, and it is fixed because the name has to be the same every time.
        val language = languageSeed ?: (seed * LANGUAGE_MULTIPLIER + LANGUAGE_OFFSET)
        return NameForge.styleFor(language).word(Random(seed), WORLD_NAME_SYLLABLES)
    }

    /** See [worldName]: the mixing that turns a world seed into a language seed. */
    private const val LANGUAGE_MULTIPLIER = 31L
    private const val LANGUAGE_OFFSET = 1_013L

    /** A one-syllable world name reads as a typo, and [NameForge]'s own ceiling is three. */
    private const val WORLD_NAME_SYLLABLES = 3

    /** `seed 59758 · 2048 × 2048`: which world, and how finely it was computed. */
    fun facts(seed: Long, width: Int, height: Int): String = "seed $seed · $width × $height"

    /** `generated in 1.8 s`, or milliseconds for a world that took less than a second. */
    fun footnote(millis: Long): String = when {
        millis <= 0L -> ""
        millis < 1_000L -> "generated in $millis ms"
        // Truncated to a tenth rather than rounded: "1.8 s" for 1899 ms overstates nothing.
        else -> "generated in ${(millis / 100L) / 10.0} s"
    }

    /**
     * The name this world would be given if nobody renamed it: the language of the people who
     * hold most of its land, and the world's own seed.
     */
    fun suggest(world: WorldMap): String {
        val people = world.cultures.cultures.maxWithOrNull(
            compareBy<Culture> { it.cellCount }.thenByDescending { it.id }
        )
        return worldName(world.config.seed, people?.nameSeed)
    }

    /**
     * The cartouche for a finished world, under whatever [name] the header's field holds.
     *
     * [millis] is how long the last generation took, or zero for a world that was opened from the
     * library rather than made here — there is no honest time to quote for those.
     */
    fun of(world: WorldMap, name: String, millis: Long): Cartouche = Cartouche(
        worldName = name,
        facts = facts(world.config.seed, world.config.width, world.config.height),
        scale = MapScale.cartoucheLine(world.config.scale, world.width),
        kilometresPerCellWidth = world.config.scale.cellWidthKm(world.width),
        footnote = footnote(millis)
    )
}

/**
 * The world's name, as the header's field holds it and as the save is filed under it.
 *
 * Three rules, and the awkward one is the third:
 *
 *  1. a world that has just been generated is named, so nothing is ever called "Untitled world"
 *     by default;
 *  2. whatever the reader types wins, and goes into the save's `title`, so it survives the file
 *     and shows in the library listing;
 *  3. **a new world takes a new name, and an adjusted one keeps its own.** Moving the seed makes a
 *     different world and a name someone typed for the old one would be a lie; turning the ocean
 *     up is the same world adjusted, and silently renaming it there would throw away what they
 *     typed. The seed is what tells those two apart, so this remembers which seed the name in hand
 *     belongs to.
 *
 * Compose state in a plain class, like [GenerationGate] and [MapCamera] beside it, so the rule can
 * be tested without a composition — which is where the third one is worth having a test of.
 */
internal class WorldNaming {

    /** Exactly what the field holds, including blank while someone is retyping it. */
    var name by mutableStateOf("")
        private set

    /** The seed [name] was generated for, so an adjusted world can be told from a new one. */
    private var namedSeed: Long? = null

    /** What a save is filed under. A cleared field is still a file that has to be called something. */
    val title: String get() = name.ifBlank { UNTITLED }

    /** The reader typed. From this moment the name is theirs until the seed moves. */
    fun rename(typed: String) {
        name = typed
    }

    /** A generation finished: name the world, unless this is a world that already has a name. */
    fun generated(seed: Long, suggestion: String) {
        if (namedSeed == seed && name.isNotBlank()) return
        name = suggestion
        namedSeed = seed
    }

    /** A save was opened. Its title is its name, and the generation that follows must not touch it. */
    fun opened(seed: Long, title: String) {
        name = title
        namedSeed = seed
    }

    private companion object {
        const val UNTITLED = "Untitled world"
    }
}
