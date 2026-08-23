package com.worldforge.worldgen.pipeline

import com.worldforge.worldgen.model.WorldGenConfig
import com.worldforge.worldgen.naming.NameForge
import com.worldforge.worldgen.naming.NameKind
import kotlin.math.abs
import kotlin.random.Random

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

    fun generate(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        climate: ClimateResult,
        rivers: RiverResult,
        plates: PlateResult,
        nations: NationResult
    ): LandmarkResult {
        val cfg = config.landmarks
        if (cfg.count <= 0 || sea.landCellCount == 0) return LandmarkResult(emptyList())

        val w = config.width
        val h = config.height
        val random = Random(config.seed * 6_700_417L + 91)

        val anyWilderness = (0 until w * h).any {
            sea.isLand[it] && nations.nationId[it] == NationResult.UNCLAIMED
        }

        // Score every land cell once, then take the best sites with spacing between them.
        val scored = ArrayList<Pair<Int, Float>>()
        for (i in 0 until w * h) {
            if (!sea.isLand[i]) continue
            val unclaimed = nations.nationId[i] == NationResult.UNCLAIMED

            // Remoteness is the main draw. When the whole world is claimed there is no wilderness
            // to prefer, so fall back to whatever is least liveable.
            var score = if (unclaimed && anyWilderness) 1f else 0f
            score += (1f - nations.habitability.data[i]) * cfg.remotenessBias
            score *= 0.6f + 0.8f * random.nextFloat()
            if (!unclaimed && anyWilderness && cfg.wildernessOnly) continue
            scored.add(i to score)
        }
        if (scored.isEmpty()) return LandmarkResult(emptyList())

        scored.sortByDescending { it.second }

        val spacing = kotlin.math.sqrt(sea.landCellCount.toDouble() / cfg.count).toFloat() * 0.8f
        val chosen = ArrayList<Int>(cfg.count)
        for ((cell, _) in scored) {
            if (chosen.size >= cfg.count) break
            val cx = cell % w
            val cy = cell / w
            val clear = chosen.none { other ->
                var dx = abs(cx - other % w).toFloat()
                if (dx > w / 2f) dx = w - dx
                val dy = (cy - other / w).toFloat()
                dx * dx + dy * dy < spacing * spacing
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
        val volcanic = plates.boundaryDistance.data[cell] < 6f

        // Weights per kind, nudged by what the ground is actually like.
        val weights = mutableMapOf(
            LandmarkKind.MONSTER_LAIR to 1.0f,
            LandmarkKind.DUNGEON to 0.8f,
            LandmarkKind.RUIN to 0.9f,
            LandmarkKind.HAZARD to 0.7f,
            LandmarkKind.RESOURCE to 0.9f,
            LandmarkKind.WONDER to 0.6f,
            LandmarkKind.SANCTUARY to 0.5f
        )
        if (elevation > 0.45f) {
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
        val high = sea.relativeElevation.data[cell] > 0.45f
        val biome = climate.biome[cell]
        val cold = climate.temperature.data[cell] < 0f

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
        val cultureSeed = config.seed * 7717L + 4211L
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
