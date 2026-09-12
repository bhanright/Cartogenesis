package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.MapLabel
import kotlinx.serialization.Serializable
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.Landmark
import com.cartogenesis.worldgen.pipeline.LandmarkKind
import com.cartogenesis.worldgen.pipeline.Nation

/**
 * A user's edits to one realm. Every field is null until they change it, so anything they have not
 * touched keeps following the generator — including after a regeneration.
 */
@Serializable
data class NationOverride(
    val name: String? = null,
    val capitalName: String? = null,
    val government: String? = null,
    val population: Long? = null,
    val exports: List<String>? = null,
    val imports: List<String>? = null,
    val lore: String? = null
) {
    val isEmpty: Boolean
        get() = name == null && capitalName == null && government == null &&
            population == null && exports == null && imports == null && lore == null
}

@Serializable
data class LandmarkOverride(
    val name: String? = null,
    val detail: String? = null,
    val kind: LandmarkKind? = null,
    val notes: String? = null
) {
    val isEmpty: Boolean
        get() = name == null && detail == null && kind == null && notes == null
}

/**
 * Everything the user has changed about a generated world.
 *
 * Kept apart from the generated data on purpose: these are the decisions, and the world they sit
 * over is whatever the settings currently produce. They survive a regeneration because of it — an
 * edit is a layer, not a value baked into the world it was made against.
 */
@Serializable
data class WorldOverrides(
    val nations: Map<Int, NationOverride> = emptyMap(),
    val landmarks: Map<Int, LandmarkOverride> = emptyMap(),
    /** Cells the user reassigned by hand: cell index to realm id (or UNCLAIMED). */
    val territory: Map<Int, Int> = emptyMap()
) {
    val isEmpty: Boolean
        get() = nations.isEmpty() && landmarks.isEmpty() && territory.isEmpty()

    fun forNation(id: Int): NationOverride = nations[id] ?: NationOverride()

    fun forLandmark(id: Int): LandmarkOverride = landmarks[id] ?: LandmarkOverride()

    fun withNation(id: Int, override: NationOverride): WorldOverrides = copy(
        nations = if (override.isEmpty) nations - id else nations + (id to override)
    )

    fun withLandmark(id: Int, override: LandmarkOverride): WorldOverrides = copy(
        landmarks = if (override.isEmpty) landmarks - id else landmarks + (id to override)
    )
}

/** A realm as the user sees it: generated values with their edits applied on top. */
data class ResolvedNation(
    val id: Int,
    val name: String,
    val capitalName: String,
    val government: String,
    val population: Long,
    val exports: List<String>,
    val imports: List<String>,
    val lore: String,
    val source: Nation,
    val edited: Boolean
) {
    val capitalCell: Int get() = source.capitalCell
    val cellCount: Int get() = source.cellCount
    val neighbours: Set<Int> get() = source.neighbours
    val isLandlocked: Boolean get() = source.isLandlocked
}

fun Nation.resolve(override: NationOverride): ResolvedNation = ResolvedNation(
    id = id,
    name = override.name ?: name,
    capitalName = override.capitalName ?: capitalName,
    government = override.government ?: government,
    population = override.population ?: population,
    exports = override.exports ?: exports,
    imports = override.imports ?: imports,
    lore = override.lore ?: lore,
    source = this,
    edited = !override.isEmpty
)

data class ResolvedLandmark(
    val id: Int,
    val cell: Int,
    val kind: LandmarkKind,
    val name: String,
    val detail: String,
    val notes: String,
    val inWilderness: Boolean,
    val edited: Boolean
)

fun Landmark.resolve(override: LandmarkOverride): ResolvedLandmark = ResolvedLandmark(
    id = id,
    cell = cell,
    kind = override.kind ?: kind,
    name = override.name ?: name,
    detail = override.detail ?: detail,
    notes = override.notes.orEmpty(),
    inWilderness = inWilderness,
    edited = !override.isEmpty
)

/**
 * The text half of a save: the settings, the edits, the labels and the title.
 *
 * The world itself travels beside this as binary sections — see [WorldCodec] — so this is what a
 * library listing reads and what the app needs to know before it has a map to draw. It is also
 * the whole of a version-2 save, which is why one still opens: no world, so it is regenerated.
 *
 * [savedAt] is passed in rather than defaulted, because a wall clock is not something common
 * Kotlin has — each platform supplies its own.
 */
@Serializable
data class WorldDocument(
    val id: String,
    val title: String,
    val config: WorldGenConfig,
    val overrides: WorldOverrides = WorldOverrides(),
    val labels: List<MapLabel> = emptyList(),
    /**
     * Always null now: the eroded terrain of a world made on the graphics card, which the seed
     * alone did not pin down, carried in the save so it could be replayed through [StoredTerrain].
     *
     * A save has carried every stage since the container format, so nothing writes this; and the
     * only files that ever had it are older than the format this build reads, so nothing can hand
     * it back either. It is left in place because removing it is a change to the wire and to the
     * app's own load path rather than a rename, and belongs to whoever takes that on.
     */
    val terrain: TerrainSnapshot? = null,
    val savedAt: Long
)
