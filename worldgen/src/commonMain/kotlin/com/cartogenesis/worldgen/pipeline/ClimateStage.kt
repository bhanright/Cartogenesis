package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.math.BoxBlur
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.noise.PerlinNoise
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow

enum class Biome {
    OCEAN, SHALLOW_OCEAN, ICE_SHEET, TUNDRA, TAIGA, TEMPERATE_FOREST, TEMPERATE_RAINFOREST,
    GRASSLAND, SHRUBLAND, DESERT, SAVANNA, TROPICAL_SEASONAL_FOREST, TROPICAL_RAINFOREST, ALPINE
}

data class ClimateResult(
    /** Mean temperature in degrees Celsius. */
    val temperature: FloatField,
    /** Rainfall, normalized to 0..1 across the world. */
    val precipitation: FloatField,
    /** Prevailing wind direction along X: +1 blows east, -1 blows west. */
    val windDirection: IntArray,
    val biome: Array<Biome>
)

/**
 * Step 4: temperature from latitude and altitude, then rainfall by marching moist air along
 * prevailing wind bands so that windward slopes get soaked and leeward slopes fall into rain
 * shadow.
 */
object ClimateStage {

    /** Land wetter than this fraction of land is treated as fully saturated when normalizing. */
    private const val WET_PERCENTILE = 0.88f


    fun generate(config: WorldGenConfig, sea: SeaLevelResult, ocean: OceanResult): ClimateResult {
        val w = config.width
        val h = config.height

        val temperature = buildTemperature(config, sea)
        applyMaritimeInfluence(config, sea, ocean, temperature)
        val wind = buildWind(w, h)
        val precipitation = buildPrecipitation(config, sea, temperature, wind, ocean)
        val biome = classify(w, h, sea, temperature, precipitation)

        return ClimateResult(temperature, precipitation, wind, biome)
    }

    /**
     * Lets a coast feel the water beside it.
     *
     * The sea anomaly is spread inland with a blur and added to land temperature, so a shore
     * washed by warm water is milder than its latitude and one beside a cold current is colder.
     * This is the difference between Bergen and Labrador, which sit at the same latitude.
     */
    private fun applyMaritimeInfluence(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        ocean: OceanResult,
        temperature: FloatField
    ) {
        val cfg = config.ocean
        if (!cfg.enabled || cfg.coastalInfluence <= 0f) return
        val w = config.width
        val h = config.height

        // Spread the offshore anomaly over the land it touches.
        val spread = FloatField(w, h)
        ocean.anomaly.data.copyInto(spread.data)
        BoxBlur.apply(spread, radius = cfg.coastalReach.coerceAtLeast(1), passes = 2)

        // The blur washes the anomaly out over open ocean too, so scale by how much water is
        // actually nearby; an inland cell should feel almost nothing.
        val water = FloatField(w, h)
        for (i in 0 until w * h) water.data[i] = if (sea.isLand[i]) 0f else 1f
        BoxBlur.apply(water, radius = cfg.coastalReach.coerceAtLeast(1), passes = 2)

        parallelChunks(0, w * h) { start, end ->
            for (i in start until end) {
                if (!sea.isLand[i]) continue
                val exposure = water.data[i].coerceIn(0f, 1f)
                temperature.data[i] += spread.data[i] * exposure * cfg.coastalInfluence
            }
        }
    }

    /** A normalised bump centred on [centre] degrees, [width] degrees wide. */
    private fun bell(latitude: Float, centre: Float, width: Float): Float {
        val d = (latitude - centre) / width
        return kotlin.math.exp(-(d * d).toDouble()).toFloat()
    }

    /** Latitude in degrees for a row: +90 at the top of the map, -90 at the bottom. */
    fun latitudeOf(y: Int, height: Int): Float =
        90f - 180f * (y + 0.5f) / height

    private fun buildTemperature(config: WorldGenConfig, sea: SeaLevelResult): FloatField {
        val w = config.width
        val h = config.height
        val cfg = config.climate
        val noise = PerlinNoise(config.seed * 32452843 + 11)
        val field = FloatField(w, h)

        // Temperature is a pure function of latitude and altitude, per cell.
        parallelChunks(0, h) { start, end ->
            for (y in start until end) {
                val lat = latitudeOf(y, h)
                val latFactor = (abs(lat) / 90f).pow(1.25f)
                val base = cfg.equatorTemperatureC -
                    (cfg.equatorTemperatureC - cfg.poleTemperatureC) * latFactor

                for (x in 0 until w) {
                    val i = y * w + x
                    val elevation = sea.relativeElevation.data[i]
                    val altitudeDrop = if (sea.isLand[i]) {
                        elevation * cfg.maxAltitudeMetres / 1000f * cfg.lapseRateC
                    } else 0f
                    val variation = 3.5f * noise.fbm(x * 5f / w, y * 5f / h, 4, 5, 5)
                    field.data[i] = base - altitudeDrop + variation
                }
            }
        }
        return field
    }

    /**
     * Simplified three-cell circulation: polar easterlies, mid-latitude westerlies, and tropical
     * trade winds blowing east to west.
     */
    private fun buildWind(width: Int, height: Int): IntArray {
        val wind = IntArray(width * height)
        for (y in 0 until height) {
            val lat = abs(latitudeOf(y, height))
            val direction = when {
                lat < 30f -> -1   // trade winds
                lat < 60f -> 1    // westerlies
                else -> -1        // polar easterlies
            }
            for (x in 0 until width) wind[y * width + x] = direction
        }
        return wind
    }

    private fun buildPrecipitation(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        temperature: FloatField,
        wind: IntArray,
        ocean: OceanResult
    ): FloatField {
        val w = config.width
        val h = config.height
        val cfg = config.climate
        val precip = FloatField(w, h)

        // Each row marches its own air mass along its own wind direction, carrying moisture state that never leaves the row.
        parallelChunks(0, h) { start, end ->
            for (y in start until end) {
                val direction = wind[y * w]
                var moisture = 0.5f

                // Two laps around the cylinder: the first seeds a realistic moisture state, the
                // second is the one that gets recorded, so the arbitrary starting value washes out.
                for (lap in 0 until 2) {
                    for (step in 0 until w) {
                        val x = if (direction > 0) step else w - 1 - step
                        val i = y * w + x

                        if (!sea.isLand[i]) {
                            // Warm seas evaporate faster — and which seas are warm is a question
                            // about currents, not latitude. Taking this from the ocean stage is
                            // what lets a cold current starve a coast of rain while another at the
                            // same latitude, on the warm side of a gyre, soaks it.
                            val seaTemperature = if (config.ocean.enabled) {
                                ocean.temperature.data[i]
                            } else {
                                temperature.data[i]
                            }
                            val warmth = ((seaTemperature + 10f) / 40f).coerceIn(0f, 1.4f)
                            moisture += cfg.evaporationRate * warmth * (1f - moisture)
                            if (lap == 1) precip.data[i] = moisture * cfg.baseRainRate * 4f
                            continue
                        }

                        var upwindX = x - direction
                        upwindX = ((upwindX % w) + w) % w
                        val rise = (sea.relativeElevation.data[i] -
                            sea.relativeElevation.data[y * w + upwindX]).coerceAtLeast(0f)

                        val rate = cfg.baseRainRate + cfg.orographicStrength * rise
                        val rain = (moisture * rate).coerceAtMost(moisture)
                        moisture -= rain

                        // Cold air simply holds less water.
                        val coldCap = ((temperature.data[i] + 25f) / 45f).coerceIn(0.15f, 1f)
                        moisture = moisture.coerceAtMost(coldCap)

                        if (lap == 1) precip.data[i] = rain
                    }
                }
            }
        }

        applyLatitudeBands(precip)
        BoxBlur.apply(precip, radius = (config.width / 128).coerceAtLeast(1), passes = 2)
        normalizeByLandPercentile(precip, sea.isLand, WET_PERCENTILE)
        return precip
    }

    /**
     * Scales rainfall so that a high percentile of *land* values maps to 1, then clamps.
     *
     * Normalizing by the absolute maximum instead would let the handful of extreme windward
     * mountain cells — which receive an order of magnitude more rain than anywhere flat — set the
     * scale, squashing every ordinary land cell below the desert threshold.
     */
    private fun normalizeByLandPercentile(
        precip: FloatField,
        isLand: BooleanArray,
        percentile: Float
    ) {
        val bins = 2048
        var maximum = 0f
        var landCells = 0
        for (i in precip.data.indices) {
            if (!isLand[i]) continue
            landCells++
            if (precip.data[i] > maximum) maximum = precip.data[i]
        }
        if (landCells == 0 || maximum <= 0f) return

        val histogram = IntArray(bins)
        val scale = (bins - 1) / maximum
        for (i in precip.data.indices) {
            if (isLand[i]) histogram[(precip.data[i] * scale).toInt().coerceIn(0, bins - 1)]++
        }

        val target = (landCells * percentile).toLong()
        var cumulative = 0L
        var reference = maximum
        for (bin in 0 until bins) {
            cumulative += histogram[bin]
            if (cumulative >= target) {
                reference = bin / scale
                break
            }
        }
        if (reference <= 0f) reference = maximum

        val inverse = 1f / reference
        for (i in precip.data.indices) {
            precip.data[i] = (precip.data[i] * inverse).coerceIn(0f, 1f)
        }
    }

    /** Wet equatorial convergence zone, dry horse latitudes around 30 degrees. */
    private fun applyLatitudeBands(precip: FloatField) {
        // Latitude bands are a per-row multiplier.
        parallelChunks(0, precip.height) { start, end ->
            for (y in start until end) {
                val lat = abs(latitudeOf(y, precip.height))

                // Three bands, each a bump centred where the atmosphere actually puts it: the
                // wet ITCZ at the equator, the dry descending air of the horse latitudes near 30,
                // and the wet mid-latitude storm track near 55.
                //
                // The previous version multiplied a single cosine by a dip at 30, which made 60
                // the driest latitude on the map — drier than the subtropics. That mostly hid
                // behind the temperature test classifying those rows as tundra, but it was wrong,
                // and it is what let deserts drift toward the equator.
                val itcz = 1.0f * bell(lat, 0f, 12f)
                val subtropicalHigh = -0.55f * bell(lat, 30f, 13f)
                val stormTrack = 0.5f * bell(lat, 55f, 15f)
                val polarDry = -0.35f * bell(lat, 90f, 18f)

                val factor = (1f + itcz + subtropicalHigh + stormTrack + polarDry)
                    .coerceAtLeast(0.05f)
                for (x in 0 until precip.width) {
                    precip[x, y] = precip[x, y] * factor
                }
            }
        }
    }

    private fun classify(
        width: Int,
        height: Int,
        sea: SeaLevelResult,
        temperature: FloatField,
        precipitation: FloatField
    ): Array<Biome> {
        return Array(width * height) { i ->
            if (!sea.isLand[i]) {
                if (temperature.data[i] < -6f) Biome.ICE_SHEET
                else if (sea.relativeElevation.data[i] > -0.12f) Biome.SHALLOW_OCEAN
                else Biome.OCEAN
            } else {
                val t = temperature.data[i]
                val p = precipitation.data[i]
                val elevation = sea.relativeElevation.data[i]
                when {
                    t < -8f -> Biome.ICE_SHEET
                    elevation > 0.72f -> Biome.ALPINE
                    t < 0f -> Biome.TUNDRA
                    t < 7f -> if (p < 0.18f) Biome.TUNDRA else Biome.TAIGA
                    t < 20f -> when {
                        p < 0.14f -> Biome.DESERT
                        p < 0.28f -> Biome.GRASSLAND
                        p < 0.42f -> Biome.SHRUBLAND
                        p < 0.68f -> Biome.TEMPERATE_FOREST
                        else -> Biome.TEMPERATE_RAINFOREST
                    }
                    else -> when {
                        p < 0.14f -> Biome.DESERT
                        p < 0.3f -> Biome.SAVANNA
                        p < 0.58f -> Biome.TROPICAL_SEASONAL_FOREST
                        else -> Biome.TROPICAL_RAINFOREST
                    }
                }
            }
        }
    }
}
