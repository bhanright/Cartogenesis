package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.naming.NameForge
import com.cartogenesis.worldgen.naming.NameKind
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.serialization.Serializable

@Serializable
enum class LandmarkKind(val label: String) {
    MONSTER_LAIR("Monster lair"),
    DUNGEON("Dungeon"),
    RUIN("Ruin"),
    HAZARD("Hazard"),
    RESOURCE("Resource"),
    WONDER("Natural wonder"),
    SANCTUARY("Sanctuary")
}

/**
 * Somewhere worth putting on a map for reasons other than politics. Generated, and every field is
 * meant to be overridable by the user.
 */
@Serializable
data class Landmark(
    val id: Int,
    val cell: Int,
    val kind: LandmarkKind,
    val name: String,
    /** The specific thing — which beast, which ore, which hazard. */
    val detail: String,
    /** True when it sits on land no realm claims, which is where most of these belong. */
    val inWilderness: Boolean
)

data class LandmarkResult(val landmarks: List<Landmark>)

/**
 * Step 7: stock the wild places.
 *
 * Sites are chosen for terrain that suits them — wyrms in the peaks, drowned things in the marsh,
 * ore where mountains meet — and biased hard toward country no realm has claimed, so the blank
 * spaces on the map become the interesting ones rather than merely empty.
 */
object LandmarkStage {

    /**
     * Decorrelates the site draw from the realm and culture draws, so that changing the realm
     * count does not move every lair on the map.
     */
    private const val SITE_SEED_MULTIPLIER = 6_700_417L
    private const val SITE_SEED_OFFSET = 91L

    /**
     * How much a site's score is jittered: three fifths of it at least, and up to two fifths
     * above it. Wide, because remoteness alone would put every site in the same waste.
     */
    private const val MIN_SITE_JITTER = 0.6f
    private const val SITE_JITTER_RANGE = 0.8f

    /**
     * Fraction of the natural spacing that sites are actually held apart by.
     *
     * At exactly the natural spacing the last few sites have nowhere left to go on a world whose
     * land is in awkward shapes, and the map comes up short of the count it was asked for.
     */
    private const val SPACING_SLACK = 0.8f

    /**
     * Elevation, in `SeaLevelResult.relativeElevation` units, above which a site counts as being
     * in the mountains — which is what puts the wyrms and the ore up there.
     */
    private const val HIGH_GROUND_ELEVATION = 0.45f

    /** Cells from a plate boundary within which a site counts as volcanic country. */
    private const val VOLCANIC_REACH_CELLS = 6f

    /** Mean annual temperature, in degrees Celsius, below which a site counts as cold country. */
    private const val COLD_COUNTRY_C = 0f

    /**
     * Seeds the naming style landmarks share. One style for the whole world rather than one per
     * realm, because these places tend to predate whoever lives nearby.
     */
    private const val NAME_SEED_MULTIPLIER = 7717L
    private const val NAME_SEED_OFFSET = 4211L

    fun generate(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        climate: ClimateResult,
        rivers: RiverResult,
        plates: PlateResult,
        nations: NationResult
    ): LandmarkResult {
        val landmarksConfig = config.landmarks
        if (landmarksConfig.count <= 0 || sea.landCellCount == 0) {
            return LandmarkResult(emptyList())
        }

        val cellsAcross = config.width
        val cellsDown = config.height
        val random = Random(config.seed * SITE_SEED_MULTIPLIER + SITE_SEED_OFFSET)

        val anyWilderness = (0 until cellsAcross * cellsDown).any {
            sea.isLand[it] && nations.nationId[it] == NationResult.UNCLAIMED
        }

        // Score every land cell once, then take the best sites with spacing between them.
        val scored = ArrayList<Pair<Int, Float>>()
        for (cell in 0 until cellsAcross * cellsDown) {
            if (!sea.isLand[cell]) continue
            val unclaimed = nations.nationId[cell] == NationResult.UNCLAIMED

            // Remoteness is the main draw. When the whole world is claimed there is no wilderness
            // to prefer, so fall back to whatever is least liveable.
            var score = if (unclaimed && anyWilderness) 1f else 0f
            score += (1f - nations.habitability.data[cell]) * landmarksConfig.remotenessBias
            score *= MIN_SITE_JITTER + SITE_JITTER_RANGE * random.nextFloat()
            if (!unclaimed && anyWilderness && landmarksConfig.wildernessOnly) continue
            scored.add(cell to score)
        }
        if (scored.isEmpty()) return LandmarkResult(emptyList())

        scored.sortByDescending { it.second }

        // Natural spacing for this many sites over this much land, pulled in a little so a world
        // that cannot quite fit them at arm's length still gets its full count.
        val spacingCells = kotlin.math.sqrt(
            sea.landCellCount.toDouble() / landmarksConfig.count
        ).toFloat() * SPACING_SLACK
        val chosen = ArrayList<Int>(landmarksConfig.count)
        for ((cell, _) in scored) {
            if (chosen.size >= landmarksConfig.count) break
            val column = cell % cellsAcross
            val row = cell / cellsAcross
            val clear = chosen.none { other ->
                // The map wraps east to west, so the shorter way round is the real distance.
                var across = abs(column - other % cellsAcross).toFloat()
                if (across > cellsAcross / 2f) across = cellsAcross - across
                val down = (row - other / cellsAcross).toFloat()
                across * across + down * down < spacingCells * spacingCells
            }
            if (clear) chosen.add(cell)
        }

        val landmarks = chosen.mapIndexed { index, cell ->
            val kind = pickKind(random, cell, sea, climate, plates)
            val detail = detailFor(random, kind, cell, sea, climate)
            Landmark(
                id = index,
                cell = cell,
                kind = kind,
                name = nameFor(config, kind, detail, index.toLong()),
                detail = detail,
                inWilderness = nations.nationId[cell] == NationResult.UNCLAIMED
            )
        }
        return LandmarkResult(landmarks)
    }

    private fun pickKind(
        random: Random,
        cell: Int,
        sea: SeaLevelResult,
        climate: ClimateResult,
        plates: PlateResult
    ): LandmarkKind {
        val elevation = sea.relativeElevation.data[cell]
        val biome = climate.biome[cell]
        val volcanic = plates.boundaryDistance.data[cell] < VOLCANIC_REACH_CELLS

        // Weights per kind, nudged by what the ground is actually like. A `mutableMapOf` because
        // it iterates in insertion order on every platform, and the roll below walks it — a
        // HashMap's bucket order would draw a different kind on the JVM and in a browser.
        val weights = mutableMapOf(
            LandmarkKind.MONSTER_LAIR to 1.0f,
            LandmarkKind.DUNGEON to 0.8f,
            LandmarkKind.RUIN to 0.9f,
            LandmarkKind.HAZARD to 0.7f,
            LandmarkKind.RESOURCE to 0.9f,
            LandmarkKind.WONDER to 0.6f,
            LandmarkKind.SANCTUARY to 0.5f
        )
        if (elevation > HIGH_GROUND_ELEVATION) {
            weights[LandmarkKind.MONSTER_LAIR] = 2.0f
            weights[LandmarkKind.DUNGEON] = 1.6f
            weights[LandmarkKind.RESOURCE] = 1.8f
        }
        if (volcanic) weights[LandmarkKind.HAZARD] = 2.4f
        if (biome == Biome.DESERT || biome == Biome.ICE_SHEET) {
            weights[LandmarkKind.RUIN] = 2.0f
            weights[LandmarkKind.HAZARD] = 1.6f
        }
        if (biome == Biome.TROPICAL_RAINFOREST || biome == Biome.TEMPERATE_RAINFOREST) {
            weights[LandmarkKind.RUIN] = 1.8f
            weights[LandmarkKind.SANCTUARY] = 1.2f
        }

        val total = weights.values.sum()
        var roll = random.nextFloat() * total
        for ((kind, weight) in weights) {
            roll -= weight
            if (roll <= 0f) return kind
        }
        return LandmarkKind.RUIN
    }

    private fun detailFor(
        random: Random,
        kind: LandmarkKind,
        cell: Int,
        sea: SeaLevelResult,
        climate: ClimateResult
    ): String {
        val high = sea.relativeElevation.data[cell] > HIGH_GROUND_ELEVATION
        val biome = climate.biome[cell]
        val cold = climate.temperature.data[cell] < COLD_COUNTRY_C

        return when (kind) {
            LandmarkKind.MONSTER_LAIR -> when {
                high -> listOf("Dragon", "Griffon", "Roc", "Stone giant", "Wyvern")
                cold -> listOf("Frost wyrm", "Ice troll", "Winter wolves", "Yeti")
                biome == Biome.DESERT -> listOf("Sand wyrm", "Sphinx", "Basilisk", "Djinn")
                biome == Biome.TROPICAL_RAINFOREST -> listOf("Hydra", "Giant serpent", "Manticore")
                else -> listOf("Troll clan", "Chimera", "Direwolf pack", "Ogre warband")
            }.random(random)

            LandmarkKind.DUNGEON -> listOf(
                "Delving", "Barrow complex", "Sunken vault", "Catacombs", "Mine workings",
                "Undercity", "Warren"
            ).random(random)

            LandmarkKind.RUIN -> listOf(
                "Fallen city", "Broken tower", "Abandoned fortress", "Toppled colossus",
                "Buried temple", "Dead observatory"
            ).random(random)

            LandmarkKind.HAZARD -> when {
                cold -> listOf("Crevasse field", "Killing cold", "Shifting floes")
                biome == Biome.DESERT -> listOf("Shifting sands", "Poison springs", "Glass waste")
                high -> listOf("Rockfall country", "Sky-fire storms", "Sheer passes")
                else -> listOf("Sinkholes", "Sucking mire", "Blighted ground", "Wild magic")
            }.random(random)

            LandmarkKind.RESOURCE -> when {
                high -> listOf("Iron", "Silver", "Mithral", "Gemstones", "Adamant")
                biome == Biome.TROPICAL_RAINFOREST -> listOf("Rare timber", "Spices", "Medicines")
                cold -> listOf("Furs", "Whale oil", "Amber")
                else -> listOf("Salt", "Copper", "Fine clay", "Horses", "Gold placer")
            }.random(random)

            LandmarkKind.WONDER -> when {
                high -> listOf("Thunder falls", "Cloud bridge", "Singing peak")
                cold -> listOf("Aurora field", "Glass glacier", "Frozen wave")
                else -> listOf("Great geyser", "Petrified forest", "Tidal arch", "Star crater")
            }.random(random)

            LandmarkKind.SANCTUARY -> listOf(
                "Hermitage", "Oracle", "Grove shrine", "Standing stones", "Monastery", "Wardstone"
            ).random(random)
        }
    }

    /** Landmark names ignore realm cultures — these places tend to predate whoever lives nearby. */
    private fun nameFor(config: WorldGenConfig, kind: LandmarkKind, detail: String, salt: Long): String {
        val cultureSeed = config.seed * NAME_SEED_MULTIPLIER + NAME_SEED_OFFSET
        // Anything phrased as "<something> of <place>" takes a bare stem. Using a full generated
        // name there appends a descriptor of its own and yields "Sunken vault of Expanse of Zuk".
        val stem = NameForge.stem(cultureSeed, salt)
        return when (kind) {
            LandmarkKind.MONSTER_LAIR, LandmarkKind.DUNGEON, LandmarkKind.RUIN ->
                "$detail of $stem"
            LandmarkKind.RESOURCE -> "$stem $detail Workings"
            LandmarkKind.HAZARD -> "The $detail of $stem"
            LandmarkKind.WONDER, LandmarkKind.SANCTUARY ->
                NameForge.name(cultureSeed, NameKind.REGION, salt)
        }
    }
}
