package com.cartogenesis.app.world

import android.content.Context
import com.cartogenesis.cartography.LandmarkOverride
import com.cartogenesis.cartography.NationOverride
import com.cartogenesis.cartography.WorldDocument
import com.cartogenesis.cartography.WorldOverrides
import com.cartogenesis.worldgen.model.LabelKind
import com.cartogenesis.worldgen.model.MapLabel
import com.cartogenesis.worldgen.model.WildernessMode
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.LandmarkKind
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads and writes saved worlds as JSON under the app's own storage.
 *
 * A save holds the seed and settings rather than the generated world, because generation is
 * deterministic — so a whole world is a few kilobytes and reopening it rebuilds the same map.
 * Alongside that sit the user's edits, which are what could not be regenerated.
 *
 * Reading is deliberately forgiving: every field falls back to the current default when it is
 * missing, so saves made before a setting existed still open.
 */
class WorldStore(context: Context) {

    private val directory = File(context.filesDir, "worlds").apply { mkdirs() }

    fun list(): List<WorldDocument> =
        directory.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { runCatching { read(it) }.getOrNull() }
            ?.sortedByDescending { it.savedAt }
            ?: emptyList()

    fun save(document: WorldDocument) {
        File(directory, "${document.id}.json").writeText(encode(document).toString())
    }

    fun delete(id: String) {
        File(directory, "$id.json").delete()
    }

    fun load(id: String): WorldDocument? =
        runCatching { read(File(directory, "$id.json")) }.getOrNull()

    private fun read(file: File): WorldDocument = decode(JSONObject(file.readText()))

    // -- encoding ---------------------------------------------------------------------------

    private fun encode(document: WorldDocument): JSONObject = JSONObject().apply {
        put("version", FORMAT_VERSION)
        put("id", document.id)
        put("title", document.title)
        put("savedAt", document.savedAt)
        put("config", encodeConfig(document.config))
        put("overrides", encodeOverrides(document.overrides))
        put("labels", JSONArray().apply {
            document.labels.forEach { label ->
                put(JSONObject().apply {
                    put("id", label.id)
                    put("text", label.text)
                    put("x", label.x.toDouble())
                    put("y", label.y.toDouble())
                    put("kind", label.kind.name)
                })
            }
        })
    }

    private fun encodeConfig(config: WorldGenConfig): JSONObject = JSONObject().apply {
        put("seed", config.seed)
        put("width", config.width)
        put("height", config.height)
        put("seaLevel", config.seaLevel.toDouble())

        put("terrain", JSONObject().apply {
            put("octaves", config.terrain.octaves)
            put("baseFrequency", config.terrain.baseFrequency)
            put("lacunarity", config.terrain.lacunarity.toDouble())
            put("gain", config.terrain.gain.toDouble())
            put("gradientStrength", config.terrain.gradientStrength.toDouble())
            put("smoothing", config.terrain.smoothing.toDouble())
        })

        put("tectonics", JSONObject().apply {
            put("plateCount", config.tectonics.plateCount)
            put("oceanicFraction", config.tectonics.oceanicFraction.toDouble())
            put("mountainHeight", config.tectonics.mountainHeight.toDouble())
            put("trenchDepth", config.tectonics.trenchDepth.toDouble())
            put("boundaryFalloff", config.tectonics.boundaryFalloff.toDouble())
            put("plateElevationBias", config.tectonics.plateElevationBias.toDouble())
            put("tectonicWeight", config.tectonics.tectonicWeight.toDouble())
            put("detailAmplitude", config.tectonics.detailAmplitude.toDouble())
            put("detailFrequency", config.tectonics.detailFrequency)
        })

        put("climate", JSONObject().apply {
            put("equatorTemperatureC", config.climate.equatorTemperatureC.toDouble())
            put("poleTemperatureC", config.climate.poleTemperatureC.toDouble())
            put("maxAltitudeMetres", config.climate.maxAltitudeMetres.toDouble())
            put("lapseRateC", config.climate.lapseRateC.toDouble())
            put("orographicStrength", config.climate.orographicStrength.toDouble())
            put("baseRainRate", config.climate.baseRainRate.toDouble())
            put("evaporationRate", config.climate.evaporationRate.toDouble())
        })

        put("rivers", JSONObject().apply {
            put("sourceThreshold", config.rivers.sourceThreshold.toDouble())
            put("maxRivers", config.rivers.maxRivers)
            put("minLength", config.rivers.minLength)
        })

        put("nations", JSONObject().apply {
            put("nationCount", config.nations.nationCount)
            put("wilderness", config.nations.wilderness.name)
            put("reach", config.nations.reach.toDouble())
            put("seedSpacing", config.nations.seedSpacing.toDouble())
            put("minSeedHabitability", config.nations.minSeedHabitability.toDouble())
            put("terrainResistance", config.nations.terrainResistance.toDouble())
            put("slopeResistance", config.nations.slopeResistance.toDouble())
            put("riverBorderCost", config.nations.riverBorderCost.toDouble())
            put("seaCrossingCost", config.nations.seaCrossingCost.toDouble())
            put("navigableDepth", config.nations.navigableDepth.toDouble())
            put("peoplePerArableKm2", config.nations.peoplePerArableKm2)
            put("worldWidthKm", config.nations.worldWidthKm)
        })

        put("landmarks", JSONObject().apply {
            put("count", config.landmarks.count)
            put("wildernessOnly", config.landmarks.wildernessOnly)
            put("remotenessBias", config.landmarks.remotenessBias.toDouble())
        })
    }

    private fun encodeOverrides(overrides: WorldOverrides): JSONObject = JSONObject().apply {
        put("nations", JSONObject().apply {
            overrides.nations.forEach { (id, override) ->
                put(id.toString(), JSONObject().apply {
                    override.name?.let { put("name", it) }
                    override.capitalName?.let { put("capitalName", it) }
                    override.government?.let { put("government", it) }
                    override.population?.let { put("population", it) }
                    override.lore?.let { put("lore", it) }
                    override.exports?.let { put("exports", JSONArray(it)) }
                    override.imports?.let { put("imports", JSONArray(it)) }
                })
            }
        })
        put("landmarks", JSONObject().apply {
            overrides.landmarks.forEach { (id, override) ->
                put(id.toString(), JSONObject().apply {
                    override.name?.let { put("name", it) }
                    override.detail?.let { put("detail", it) }
                    override.kind?.let { put("kind", it.name) }
                    override.notes?.let { put("notes", it) }
                })
            }
        })
        // Stored sparsely: only cells the user actually moved, not the whole grid.
        put("territory", JSONObject().apply {
            overrides.territory.forEach { (cell, owner) -> put(cell.toString(), owner) }
        })
    }

    // -- decoding ---------------------------------------------------------------------------

    private fun decode(json: JSONObject): WorldDocument = WorldDocument(
        id = json.optString("id", java.util.UUID.randomUUID().toString()),
        title = json.optString("title", "Untitled world"),
        config = decodeConfig(json.optJSONObject("config") ?: JSONObject()),
        overrides = decodeOverrides(json.optJSONObject("overrides") ?: JSONObject()),
        labels = decodeLabels(json.optJSONArray("labels")),
        savedAt = json.optLong("savedAt", System.currentTimeMillis())
    )

    private fun decodeConfig(json: JSONObject): WorldGenConfig {
        val defaults = WorldGenConfig()
        val terrain = json.optJSONObject("terrain") ?: JSONObject()
        val tectonics = json.optJSONObject("tectonics") ?: JSONObject()
        val climate = json.optJSONObject("climate") ?: JSONObject()
        val rivers = json.optJSONObject("rivers") ?: JSONObject()
        val nations = json.optJSONObject("nations") ?: JSONObject()
        val landmarks = json.optJSONObject("landmarks") ?: JSONObject()

        return defaults.copy(
            seed = json.optLong("seed", defaults.seed),
            width = json.optInt("width", defaults.width),
            height = json.optInt("height", defaults.height),
            seaLevel = json.float("seaLevel", defaults.seaLevel),
            terrain = defaults.terrain.copy(
                octaves = terrain.optInt("octaves", defaults.terrain.octaves),
                baseFrequency = terrain.optInt("baseFrequency", defaults.terrain.baseFrequency),
                lacunarity = terrain.float("lacunarity", defaults.terrain.lacunarity),
                gain = terrain.float("gain", defaults.terrain.gain),
                gradientStrength = terrain.float("gradientStrength", defaults.terrain.gradientStrength),
                smoothing = terrain.float("smoothing", defaults.terrain.smoothing)
            ),
            tectonics = defaults.tectonics.copy(
                plateCount = tectonics.optInt("plateCount", defaults.tectonics.plateCount),
                oceanicFraction = tectonics.float("oceanicFraction", defaults.tectonics.oceanicFraction),
                mountainHeight = tectonics.float("mountainHeight", defaults.tectonics.mountainHeight),
                trenchDepth = tectonics.float("trenchDepth", defaults.tectonics.trenchDepth),
                boundaryFalloff = tectonics.float("boundaryFalloff", defaults.tectonics.boundaryFalloff),
                plateElevationBias = tectonics.float("plateElevationBias", defaults.tectonics.plateElevationBias),
                tectonicWeight = tectonics.float("tectonicWeight", defaults.tectonics.tectonicWeight),
                detailAmplitude = tectonics.float("detailAmplitude", defaults.tectonics.detailAmplitude),
                detailFrequency = tectonics.optInt("detailFrequency", defaults.tectonics.detailFrequency)
            ),
            climate = defaults.climate.copy(
                equatorTemperatureC = climate.float("equatorTemperatureC", defaults.climate.equatorTemperatureC),
                poleTemperatureC = climate.float("poleTemperatureC", defaults.climate.poleTemperatureC),
                maxAltitudeMetres = climate.float("maxAltitudeMetres", defaults.climate.maxAltitudeMetres),
                lapseRateC = climate.float("lapseRateC", defaults.climate.lapseRateC),
                orographicStrength = climate.float("orographicStrength", defaults.climate.orographicStrength),
                baseRainRate = climate.float("baseRainRate", defaults.climate.baseRainRate),
                evaporationRate = climate.float("evaporationRate", defaults.climate.evaporationRate)
            ),
            rivers = defaults.rivers.copy(
                sourceThreshold = rivers.float("sourceThreshold", defaults.rivers.sourceThreshold),
                maxRivers = rivers.optInt("maxRivers", defaults.rivers.maxRivers),
                minLength = rivers.optInt("minLength", defaults.rivers.minLength)
            ),
            nations = defaults.nations.copy(
                nationCount = nations.optInt("nationCount", defaults.nations.nationCount),
                wilderness = runCatching {
                    WildernessMode.valueOf(nations.optString("wilderness", defaults.nations.wilderness.name))
                }.getOrDefault(defaults.nations.wilderness),
                reach = nations.float("reach", defaults.nations.reach),
                seedSpacing = nations.float("seedSpacing", defaults.nations.seedSpacing),
                minSeedHabitability = nations.float("minSeedHabitability", defaults.nations.minSeedHabitability),
                terrainResistance = nations.float("terrainResistance", defaults.nations.terrainResistance),
                slopeResistance = nations.float("slopeResistance", defaults.nations.slopeResistance),
                riverBorderCost = nations.float("riverBorderCost", defaults.nations.riverBorderCost),
                seaCrossingCost = nations.float("seaCrossingCost", defaults.nations.seaCrossingCost),
                navigableDepth = nations.float("navigableDepth", defaults.nations.navigableDepth),
                peoplePerArableKm2 = nations.optDouble("peoplePerArableKm2", defaults.nations.peoplePerArableKm2),
                worldWidthKm = nations.optDouble("worldWidthKm", defaults.nations.worldWidthKm)
            ),
            landmarks = defaults.landmarks.copy(
                count = landmarks.optInt("count", defaults.landmarks.count),
                wildernessOnly = landmarks.optBoolean("wildernessOnly", defaults.landmarks.wildernessOnly),
                remotenessBias = landmarks.float("remotenessBias", defaults.landmarks.remotenessBias)
            )
        )
    }

    private fun decodeOverrides(json: JSONObject): WorldOverrides {
        val nations = HashMap<Int, NationOverride>()
        json.optJSONObject("nations")?.let { section ->
            section.keys().forEach { key ->
                val entry = section.optJSONObject(key) ?: return@forEach
                nations[key.toInt()] = NationOverride(
                    name = entry.stringOrNull("name"),
                    capitalName = entry.stringOrNull("capitalName"),
                    government = entry.stringOrNull("government"),
                    population = if (entry.has("population")) entry.optLong("population") else null,
                    exports = entry.stringListOrNull("exports"),
                    imports = entry.stringListOrNull("imports"),
                    lore = entry.stringOrNull("lore")
                )
            }
        }

        val landmarks = HashMap<Int, LandmarkOverride>()
        json.optJSONObject("landmarks")?.let { section ->
            section.keys().forEach { key ->
                val entry = section.optJSONObject(key) ?: return@forEach
                landmarks[key.toInt()] = LandmarkOverride(
                    name = entry.stringOrNull("name"),
                    detail = entry.stringOrNull("detail"),
                    kind = entry.stringOrNull("kind")
                        ?.let { runCatching { LandmarkKind.valueOf(it) }.getOrNull() },
                    notes = entry.stringOrNull("notes")
                )
            }
        }

        val territory = HashMap<Int, Int>()
        json.optJSONObject("territory")?.let { section ->
            section.keys().forEach { key -> territory[key.toInt()] = section.optInt(key) }
        }

        return WorldOverrides(nations, landmarks, territory)
    }

    private fun decodeLabels(array: JSONArray?): List<MapLabel> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val entry = array.optJSONObject(index) ?: return@mapNotNull null
            MapLabel(
                id = entry.optLong("id", index.toLong()),
                text = entry.optString("text"),
                x = entry.float("x", 0.5f),
                y = entry.float("y", 0.5f),
                kind = runCatching {
                    LabelKind.valueOf(entry.optString("kind", LabelKind.REGION.name))
                }.getOrDefault(LabelKind.REGION)
            )
        }
    }

    private companion object {
        const val FORMAT_VERSION = 1

        fun JSONObject.float(name: String, fallback: Float): Float =
            optDouble(name, fallback.toDouble()).toFloat()

        fun JSONObject.stringOrNull(name: String): String? =
            if (has(name) && !isNull(name)) optString(name) else null

        fun JSONObject.stringListOrNull(name: String): List<String>? {
            val array = optJSONArray(name) ?: return null
            return (0 until array.length()).map { array.optString(it) }
        }
    }
}
