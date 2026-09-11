package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.math.BoxBlur
import com.cartogenesis.worldgen.math.DistanceTransform
import com.cartogenesis.worldgen.model.ClimateConfig
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.noise.PerlinNoise
import kotlin.math.abs
import kotlin.math.pow
import kotlinx.serialization.Serializable

@Serializable
enum class Biome {
    OCEAN, SHALLOW_OCEAN, ICE_SHEET, TUNDRA, TAIGA, TEMPERATE_FOREST, TEMPERATE_RAINFOREST,
    GRASSLAND, SHRUBLAND, DESERT, SAVANNA, TROPICAL_SEASONAL_FOREST, TROPICAL_RAINFOREST, ALPINE,

    // Appended rather than slotted in beside their relatives, because a biome's ordinal is what a
    // save stores for it — one byte per cell — and reordering this list would silently repaint
    // every world already written to disk.

    /**
     * Dry-summer temperate country: the rain falls in the cold half of the year and the warm half
     * is parched. Olive, vine and cork country, and the reason a west coast at 35 degrees is not
     * the same place as an east coast at 35 degrees.
     */
    MEDITERRANEAN,

    /**
     * Tropical forest fed almost entirely by one drenching wet season, with a dry winter that a
     * rainforest would not survive.
     */
    MONSOON_FOREST
}

data class ClimateResult(
    /** Mean annual temperature in degrees Celsius. */
    val temperature: FloatField,
    /**
     * Temperature in the local warm and cold seasons, in degrees Celsius.
     *
     * These are not July and January. "Summer" is whichever half of the year the cell's *own*
     * hemisphere is warm in, so northern July and southern January both land in
     * [summerTemperature]. Storing the local season rather than the calendar month is what lets
     * [ClimateStage.classify] apply one rule to the whole map instead of branching on the sign of
     * the latitude — a Mediterranean coast is a Mediterranean coast either side of the equator.
     */
    val summerTemperature: FloatField,
    val winterTemperature: FloatField,
    /** Rainfall, normalized to 0..1 across the world. */
    val precipitation: FloatField,
    /**
     * Warm- and cold-season rainfall, on the same scale as [precipitation] — the same normalising
     * factor is applied to all three, so the three fields can be compared against each other and
     * against the biome thresholds. [precipitation] is their mean, except where the clamp at 1
     * bites on a season.
     */
    val summerPrecipitation: FloatField,
    val winterPrecipitation: FloatField,
    /** Prevailing wind direction along X: +1 blows east, -1 blows west. */
    val windDirection: IntArray,
    /**
     * Prevailing wind along Y, in rows per cell of zonal travel: positive blows toward the bottom
     * of the map, negative toward the top — the same sense as [windDirection]'s, and the same
     * sense the map's own coordinates use, so the pair is a vector an arrow can be drawn from.
     *
     * Held as a field rather than folded into [windDirection] because the zonal part is a
     * direction and this is a slope; and stored per cell rather than per row because every other
     * field of this stage is per cell and a save's sections are per-cell arrays.
     *
     * This is the annual wind, as [windDirection] is: each season marches along belts of its own
     * that live only as long as the march does.
     */
    val windMeridional: FloatField,
    val biome: Array<Biome>
)

/**
 * Step 4: temperature from latitude and altitude, then rainfall by marching moist air along
 * prevailing wind bands so that windward slopes get soaked and leeward slopes fall into rain
 * shadow.
 *
 * Run twice over, for the warm season and the cold one. The whole of the seasonal machinery is one
 * number — [ClimateConfig.seasonalTilt], the distance the thermal equator migrates toward whichever
 * hemisphere is in summer — applied to the latitude that the temperature curve, the wind belts and
 * the rain belts are all read off. The annual fields are kept as they were, so every stage
 * downstream of this one sees exactly what it saw before seasons existed.
 */
object ClimateStage {

    /** Land wetter than this fraction of land is treated as fully saturated when normalizing. */
    private const val WET_PERCENTILE = 0.88f

    /**
     * How much of the land's seasonal swing the open sea takes.
     *
     * Water has an enormous heat capacity and mixes to depth, so the ocean surface barely notices
     * a season that moves the land beside it by twenty degrees. Without this the sea would swing
     * exactly as far as the continent, which would be wrong on its own terms and would also hand
     * every coastal march a wildly seasonal evaporation rate it has no business having.
     *
     * A constant rather than a setting: how far the damping reaches *inland* is the interesting
     * question and it belongs to continentality, which has the water-exposure field to answer it
     * with. This only has to separate water from land.
     */
    private const val OCEAN_SEASONAL_AMPLITUDE = 0.22f

    /**
     * Rain a cell has to be getting before the ratio between its seasons means anything.
     *
     * Added to both halves of every seasonal ratio below. Two nearly rainless seasons can differ
     * by a factor of fifty on noise alone, and without a floor the driest cells on the map would
     * be the ones most confidently classed as strongly seasonal.
     */
    private const val SEASON_FLOOR = 0.02f

    fun generate(config: WorldGenConfig, sea: SeaLevelResult, ocean: OceanResult): ClimateResult {
        val w = config.width
        val h = config.height
        val cfg = config.climate

        // One knob, used everywhere below. Switching seasons off is exactly a tilt of zero: every
        // seasonal field then collapses onto the annual one, bit for bit, and the world is the one
        // this generator made before this stage knew about seasons at all.
        val tilt = if (cfg.seasons) cfg.seasonalTilt else 0f

        val temperature = buildTemperature(config, sea)
        // The maritime-influence term and continentality both ask "how close is the sea", but they
        // need different answers to it. Influence wants a fast-fading field so a temperature
        // anomaly does not leak across a whole continent — the blurred exposure field. Continentality
        // wants an honest distance in cells, because a coast damped by "still 70% exposed at
        // coastalReach" barely damps at all; a chamfer distance transform is exact and, at these
        // resolutions, cheaper than the blur besides.
        val exposure = waterExposure(config, sea)
        applyMaritimeInfluence(config, sea, ocean, temperature, exposure)
        val waterDist = waterDistance(config, sea)
        val summerTemperature =
            seasonalTemperature(config, sea, temperature, waterDist, tilt, warm = true)
        val winterTemperature =
            seasonalTemperature(config, sea, temperature, waterDist, tilt, warm = false)

        // The stored wind is the annual one, unshifted: it is what the rest of the pipeline and
        // the wind view mean by "the prevailing wind". Each season marches along its own belts,
        // which live only as long as the march does.
        val slant = cfg.meridionalWind
        val wind = buildWind(w, h, tilt = 0f, warm = true, slant = slant)

        val summerPrecipitation = buildPrecipitation(
            config, sea, summerTemperature, buildWind(w, h, tilt, warm = true, slant = slant),
            ocean, bands(h, cfg, warm = true)
        )
        val winterPrecipitation = buildPrecipitation(
            config, sea, winterTemperature, buildWind(w, h, tilt, warm = false, slant = slant),
            ocean, bands(h, cfg, warm = false)
        )

        // The annual field is the mean of the two marches rather than a third march of its own, so
        // that turning seasons off leaves it identical to the single march it replaced.
        val precipitation = FloatField(w, h)
        for (i in 0 until w * h) {
            precipitation.data[i] =
                (summerPrecipitation.data[i] + winterPrecipitation.data[i]) * 0.5f
        }

        // One scale for all three, taken from the annual field, because a season measured against
        // its own percentile would lose the very thing the seasons are here to express: that the
        // wet half of the year is wetter than the dry half.
        val reference = landPercentile(precipitation, sea.isLand, WET_PERCENTILE)
        scaleAndClamp(precipitation, reference)
        scaleAndClamp(summerPrecipitation, reference)
        scaleAndClamp(winterPrecipitation, reference)

        val biome = classify(
            w, h, sea, temperature, summerTemperature, winterTemperature,
            precipitation, summerPrecipitation, winterPrecipitation
        )

        return ClimateResult(
            temperature = temperature,
            summerTemperature = summerTemperature,
            winterTemperature = winterTemperature,
            precipitation = precipitation,
            summerPrecipitation = summerPrecipitation,
            winterPrecipitation = winterPrecipitation,
            windDirection = wind.zonal,
            windMeridional = FloatField(w, h, wind.meridional),
            biome = biome
        )
    }

    /**
     * How much nearby water a land cell can feel: 1 in the open sea, fading to 0 over
     * `OceanConfig.coastalReach` cells inland.
     *
     * A blur of the land/sea mask rather than a distance transform — cheap, and it does what
     * [applyMaritimeInfluence] needs: land within reach of the coast reads high, land well beyond
     * it reads exactly 0 once the blur's support runs out. Not used by continentality any more —
     * see [waterDistance] for why an actual distance earns its keep there.
     */
    private fun waterExposure(config: WorldGenConfig, sea: SeaLevelResult): FloatField {
        val w = config.width
        val h = config.height
        val radius = config.ocean.coastalReach.coerceAtLeast(1)

        val water = FloatField(w, h)
        for (i in 0 until w * h) water.data[i] = if (sea.isLand[i]) 0f else 1f
        BoxBlur.apply(water, radius = radius, passes = 2)
        for (i in 0 until w * h) water.data[i] = water.data[i].coerceIn(0f, 1f)
        return water
    }

    /**
     * Cell distance to the nearest sea cell, by the same chamfer distance transform
     * `SeaLevelStage` already uses for the continental shelf: two sweeps, `O(width * height)`
     * regardless of how far the nearest coast is, and correct rather than approximate.
     *
     * Continentality first tried the blurred water-exposure field above, on the theory that "how
     * exposed to water" and "how close to water" were the same question asked two ways. They are
     * not, at this radius: two box-blur passes leave a cell right at the edge of `coastalReach`
     * reading roughly 0.2 exposure, not the ~1 that would make a coast read as barely-continental —
     * a coast this measured as "still 70% of the way to fully continental" is not a coast in any
     * sense the plan meant. An honest distance says a cell at the shoreline is 0 cells from water
     * and one three `coastalReach` inland is exactly that, which is what the amplitude formula
     * below actually needs.
     *
     * Internal rather than private so `ContinentalityTest` measures the same field the stage
     * actually used instead of re-deriving it and risking the two drifting apart.
     */
    internal fun waterDistance(config: WorldGenConfig, sea: SeaLevelResult): FloatField {
        val w = config.width
        val h = config.height
        val dist = FloatArray(w * h) { DistanceTransform.INFINITE }
        val label = IntArray(w * h) { -1 }
        for (i in 0 until w * h) {
            if (!sea.isLand[i]) {
                dist[i] = 0f
                label[i] = i
            }
        }
        // A world with no water at all leaves every distance at INFINITE, which is exactly right:
        // continentalityFactor below clamps that to 1, the fully-continental case, everywhere.
        DistanceTransform.run(w, h, dist, label)
        val field = FloatField(w, h)
        dist.copyInto(field.data)
        return field
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
        temperature: FloatField,
        exposure: FloatField
    ) {
        val cfg = config.ocean
        if (!cfg.enabled || cfg.coastalInfluence <= 0f) return
        val w = config.width
        val h = config.height

        // Spread the offshore anomaly over the land it touches.
        val spread = FloatField(w, h)
        ocean.anomaly.data.copyInto(spread.data)
        BoxBlur.apply(spread, radius = cfg.coastalReach.coerceAtLeast(1), passes = 2)

        parallelChunks(0, w * h) { start, end ->
            for (i in start until end) {
                if (!sea.isLand[i]) continue
                temperature.data[i] += spread.data[i] * exposure.data[i] * cfg.coastalInfluence
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

    /**
     * How far a row sits from the thermal equator in a given season, in degrees.
     *
     * The thermal equator migrates [tilt] degrees toward whichever hemisphere is in summer, so in
     * the warm season a cell is that much closer to it and in the cold season that much further.
     * Measured from `|lat|`, which is what makes both hemispheres get their own summer rather than
     * sharing July: a row at 35 south is as close to the thermal equator in *its* summer as a row
     * at 35 north is in *its* own.
     *
     * The warm case takes an absolute value again, because a tropical row can find the thermal
     * equator has crossed over it — which is the whole mechanism behind the monsoon.
     */
    private fun seasonalLatitude(y: Int, height: Int, tilt: Float, warm: Boolean): Float {
        val lat = abs(latitudeOf(y, height))
        return if (warm) abs(lat - tilt) else lat + tilt
    }

    /**
     * The exponent of the latitude curve.
     *
     * Raised from 1.25 by A6. The Koppen gate alone did not fix the high-latitude west coast (the
     * Bergen case): [ClimateStage.classify] now reads the coldest and warmest month instead of the
     * annual mean, but at 1.25 the curve put 45 degrees — the effective latitude a 55-degree
     * coast's *summer* reads off, one [ClimateConfig.seasonalTilt] equatorward — at a mere 6.8 C,
     * so even a strong warm-current anomaly could not lift a maritime coast's warmest month over
     * the 10 C tree line. Measured before this change: every one of seeds 7/42/1234's 50-60 degree
     * west-facing, warm-current coast classified taiga or tundra, 0.0-0.1% forest. At 1.8, that
     * same point reaches 14.8 C and the guard passes on all three seeds (56.7-66.7% forest).
     *
     * The equator and pole anchors are untouched; only the exponent moved, and it cuts both ways.
     * Raising it lifts the whole curve between those fixed ends, so the 60-70 degree band a
     * continental *interior*'s winter reads off warms past Koppen's -3 C boundary too, and a dry
     * interior up there stops being gated as continental — measured on seed 42, that alone dropped
     * desert-in-band from 98-100% to 48%, because a marginal, barely-continental interior that used
     * to be taiga was now warm enough for [classify]'s existing desert check to fire on it. That is
     * fixed in `classify` itself (see the `t >= 13f` guard on the C-branch's desert case) rather
     * than by pulling the curve back down, since the coast fix needs the lift right where the
     * interior leak happens — the two effective-latitude ranges overlap almost exactly, so no
     * choice of exponent or pole alone separates them; see that guard's comment for the reasoning
     * and the desert figures with it in place (98-100%, matching before).
     */
    private const val LATITUDE_EXPONENT = 1.8f

    /** The latitude term of the temperature curve, on its own, so a season can re-read it. */
    private fun latitudeTemperature(cfg: ClimateConfig, absoluteLatitude: Float): Float {
        val latFactor = (absoluteLatitude / 90f).pow(LATITUDE_EXPONENT)
        return cfg.equatorTemperatureC - (cfg.equatorTemperatureC - cfg.poleTemperatureC) * latFactor
    }

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
                val base = latitudeTemperature(cfg, abs(lat))

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
     * The warm- or cold-season temperature, as a departure from the annual mean.
     *
     * Everything a season shares with the annual field — the altitude lapse, the maritime term,
     * the noise — is already in [annual], so only the latitude term is recomputed, at the
     * season's own distance from the thermal equator. What is added is therefore the *difference*
     * the migrating equator makes, which is the honest way round: a mountain is no more seasonal
     * than the valley below it, it is simply colder all year.
     *
     * Over water the departure is damped to [OCEAN_SEASONAL_AMPLITUDE] of itself. A maritime
     * climate has a small annual range at a latitude where a continental one swings thirty
     * degrees, and that is the sea's heat capacity, not anything about the latitude.
     *
     * Over land the departure is scaled by `1 + continentality * continentalityFactor`
     * ([ClimateConfig.continentality]), where `continentalityFactor` is [waterDistance] clamped to
     * 0..1 over three [OceanConfig.coastalReach]: a cell at the shoreline reads 0 and keeps the
     * amplitude at 1, swinging exactly as far as it did before this setting existed; a cell three
     * reaches inland or further reads 1 and swings up to `1 + continentality` as far. This is
     * Siberia versus Ireland at the same latitude.
     */
    private fun seasonalTemperature(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        annual: FloatField,
        waterDistance: FloatField,
        tilt: Float,
        warm: Boolean
    ): FloatField {
        val w = config.width
        val h = config.height
        val cfg = config.climate
        val field = FloatField(w, h)
        val continentalReach = 3f * config.ocean.coastalReach.coerceAtLeast(1)

        parallelChunks(0, h) { start, end ->
            for (y in start until end) {
                val departure = latitudeTemperature(cfg, seasonalLatitude(y, h, tilt, warm)) -
                    latitudeTemperature(cfg, abs(latitudeOf(y, h)))
                for (x in 0 until w) {
                    val i = y * w + x
                    val amplitude = if (sea.isLand[i]) {
                        val continentalityFactor =
                            (waterDistance.data[i] / continentalReach).coerceIn(0f, 1f)
                        1f + cfg.continentality * continentalityFactor
                    } else {
                        OCEAN_SEASONAL_AMPLITUDE
                    }
                    field.data[i] = annual.data[i] + departure * amplitude
                }
            }
        }
        return field
    }

    /** The prevailing wind as a vector: a zonal direction of ±1, and a slant in rows per cell. */
    private class WindField(val zonal: IntArray, val meridional: FloatArray)

    /**
     * Simplified three-cell circulation: polar easterlies, mid-latitude westerlies, and tropical
     * trade winds blowing east to west — each of them slanted across the latitude lines.
     *
     * The belts ride the thermal equator, so in summer they sit [tilt] degrees poleward of their
     * annual position and in winter [tilt] degrees equatorward. That migration is what puts a
     * west coast at 35 degrees under the westerlies in winter and under the trades in summer,
     * which is the Mediterranean climate in one sentence.
     *
     * The slant is the other half of a circulation cell, and the half that makes a monsoon. Each
     * cell has air rising at one edge and sinking at the other, and the surface leg runs between
     * them: the trades spiral in toward the thermal equator, the westerlies carry poleward toward
     * the polar front, the polar easterlies run back down. Which way that is has to be measured
     * from the *thermal* equator and not the geographic one, because in summer the thermal equator
     * migrates over the tropics, and a row it has crossed finds its trades reversed — blowing away
     * from the equator, up onto whatever land lies poleward of it. That reversal is the monsoon,
     * and it is not available to a belt model that reads its direction off `|latitude|`.
     */
    private fun buildWind(
        width: Int,
        height: Int,
        tilt: Float,
        warm: Boolean,
        slant: Float
    ): WindField {
        val zonal = IntArray(width * height)
        val meridional = FloatArray(width * height)
        for (y in 0 until height) {
            val signed = latitudeOf(y, height)
            // Which way "poleward" points for this row, as a step in map coordinates: y grows
            // southward, so the northern hemisphere's pole is at smaller y.
            val poleward = if (signed < 0f) 1f else -1f
            // Signed distance from the thermal equator, positive poleward. Negative means the
            // thermal equator has migrated past this row, into its own hemisphere.
            val offset = if (warm) abs(signed) - tilt else abs(signed) + tilt
            val belt = abs(offset)
            // Away from the thermal equator, again as a step in map coordinates.
            val outward = if (offset < 0f) -poleward else poleward
            val direction = when {
                belt < 30f -> -1   // trade winds
                belt < 60f -> 1    // westerlies
                else -> -1         // polar easterlies
            }
            val drift = when {
                belt < 30f -> -outward   // the Hadley cell's surface leg, in toward the ITCZ
                belt < 60f -> outward    // the Ferrel cell's, out toward the polar front
                else -> -outward         // the polar cell's, back down toward it
            } * slant
            for (x in 0 until width) {
                zonal[y * width + x] = direction
                meridional[y * width + x] = drift
            }
        }
        return WindField(zonal, meridional)
    }

    /** The circulation belt each row sits in for a season, precomputed per row. */
    private fun bands(height: Int, climate: ClimateConfig, warm: Boolean): FloatArray =
        FloatArray(height) { y -> seasonalBand(latitudeOf(y, height), climate, warm) }

    /**
     * The belt factor the march applies at a latitude in one season — shifted and sharpened.
     *
     * Exposed so a diagnostic can report the number that was actually applied rather than a copy
     * of the formula that drifts away from it, which is what `DesertCauseTest`'s own copy had
     * already done before seasons made the question harder.
     */
    internal fun seasonalBand(latitude: Float, climate: ClimateConfig, warm: Boolean): Float {
        val tilt = if (climate.seasons) climate.seasonalTilt else 0f
        val lat = abs(latitude)
        val effective = if (warm) abs(lat - tilt) else lat + tilt
        return latitudeBandAt(
            effective,
            climate.subtropicalDryness,
            seasonalBandSharpness(tilt, climate.subtropicalDryness)
        )
    }

    /**
     * How much sharper an instantaneous circulation belt is than the annual mean of the belts.
     *
     * [latitudeBandAt]'s constants were measured against *annual* desert placement in a world that
     * had no seasons, which makes them a description of the annual mean rather than of any moment
     * in the year. Migrating the belts and averaging two marches computes that annual mean a
     * second time, and two offset bells average to a profile roughly half as sharp as either: at
     * the subtropical high's centre the anomaly falls from -1.12 to -0.51, which lifts the horse
     * latitudes out of the clamped, maximally arid span their deserts come from. Measured, that
     * cost seed 42 three quarters of its desert and dropped desert placement from 100% to 74% in
     * 15-45 degrees, with rain-shadow deserts reappearing at the equator — precisely the
     * regression the belt mechanism was introduced to prevent.
     *
     * So each season's anomaly is scaled by the factor that restores the annual mean at the
     * subtropical high's own centre, which is the belt the deserts depend on. It is derived rather
     * than chosen: at a tilt of zero it is exactly 1 and every band is the number it always was,
     * which is what keeps `seasons = false` identical to the pre-seasons world down to the bit.
     */
    private fun seasonalBandSharpness(tilt: Float, dryness: Float): Float {
        val centre = 30f
        val annual = bandAnomaly(centre, dryness)
        val seasonal =
            (bandAnomaly(centre - tilt, dryness) + bandAnomaly(centre + tilt, dryness)) * 0.5f
        // Both are negative under any sane setting — the subtropics suppress rain. If a setting
        // ever made them otherwise, leave the belts alone rather than invent a correction.
        if (annual >= 0f || seasonal >= 0f) return 1f
        return (annual / seasonal).coerceIn(1f, 4f)
    }

    /**
     * Rainfall, by marching air masses along the wind and recording what they drop.
     *
     * # How the diagonal march is arranged
     *
     * The march used to be one air mass per row, scanning along X with moisture state that never
     * left the row. A slanted wind breaks that: the air arriving at a cell came from the row
     * beside it as well as from the column behind it, so rows are no longer independent.
     *
     * The scheme here is semi-Lagrangian, which is what the shape of the dependency asks for.
     * Every cell in column `x` takes its moisture from the point one cell upwind — `(x - dx,
     * y - dy)` — which, since `dx` is a whole cell and `dy` a fraction of a row, is a bilinear
     * blend of two cells that both sit in column `x - dx`. So the whole of column `x` depends on
     * the whole of column `x - dx` and on nothing else, and the march is a wavefront sweeping
     * column by column with every row of a column independent of every other.
     *
     * That wavefront is walked *in lock step across rows* rather than row by row: the outer loop
     * is the step along X and the inner loop runs over the rows, reading the previous column's
     * moisture out of a snapshot taken before the column began. Reading a snapshot rather than
     * live neighbours is what makes the result independent of the order the rows are visited in,
     * and therefore of thread scheduling — the property the whole pipeline is built on.
     *
     * The work is split for parallelism by *run* — a maximal block of adjacent rows that march the
     * same way round the cylinder — rather than by row, because two rows marching opposite ways
     * are at opposite ends of the map at the same step and have no business exchanging air. A run
     * is a circulation belt, its edges are the boundaries between Hadley, Ferrel and polar cells,
     * and air genuinely does not cross those at the surface: at 30 degrees the two cells' surface
     * legs diverge. There are only ever five or so runs, so this parallelises less finely than one
     * chunk of rows per core did — but the whole stage is a couple of passes over the grid against
     * erosion's eighty, and correctness here is worth more than the cores.
     *
     * At `meridionalWind = 0` every row's blend weight is zero, the branch below is not taken, and
     * what remains is the old scan, arithmetic for arithmetic, in the same order per row.
     */
    private fun buildPrecipitation(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        temperature: FloatField,
        wind: WindField,
        ocean: OceanResult,
        bandOfRow: FloatArray
    ): FloatField {
        val w = config.width
        val h = config.height
        val cfg = config.climate
        val precip = FloatField(w, h)

        // Where each run of same-direction rows begins. Built by scanning, so it is the same list
        // on every platform and in every thread.
        val runStarts = ArrayList<Int>()
        for (y in 0 until h) {
            if (y == 0 || wind.zonal[y * w] != wind.zonal[(y - 1) * w]) runStarts.add(y)
        }
        runStarts.add(h)

        parallelChunks(0, runStarts.size - 1) { first, last ->
            for (run in first until last) {
                marchRun(
                    config, sea, temperature, wind, ocean, bandOfRow, precip,
                    firstRow = runStarts[run], lastRow = runStarts[run + 1]
                )
            }
        }

        BoxBlur.apply(precip, radius = (config.width / 128).coerceAtLeast(1), passes = 2)
        return precip
    }

    /** One circulation belt's worth of rows, marched together. See [buildPrecipitation]. */
    private fun marchRun(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        temperature: FloatField,
        wind: WindField,
        ocean: OceanResult,
        bandOfRow: FloatArray,
        precip: FloatField,
        firstRow: Int,
        lastRow: Int
    ) {
        val w = config.width
        val cfg = config.climate
        val rows = lastRow - firstRow
        val direction = wind.zonal[firstRow * w]

        // One air mass per row, as before — but now they trade moisture sideways as they go.
        val moisture = FloatArray(rows) { 0.5f }
        val previousColumn = FloatArray(rows)

        // Two laps around the cylinder: the first seeds a realistic moisture state, the second is
        // the one that gets recorded, so the arbitrary starting value washes out.
        for (lap in 0 until 2) {
            for (step in 0 until w) {
                val x = if (direction > 0) step else w - 1 - step
                var upwindX = x - direction
                upwindX = ((upwindX % w) + w) % w
                moisture.copyInto(previousColumn)

                for (r in 0 until rows) {
                    val y = firstRow + r
                    val i = y * w + x

                    // The circulation belt this row sits in, applied to the rain *rate* rather
                    // than to the finished total. Multiplying the result afterwards cannot make a
                    // rain shadow wet again -- twice nearly nothing is still nearly nothing --
                    // whereas suppressing the rate is what descending subtropical air actually
                    // does, and boosting it is what the ITCZ does. In a season this belt is the
                    // shifted one, so the same row is under the dry descending limb in one half of
                    // the year and under the storm track in the other.
                    val band = bandOfRow[y]

                    // The upwind point, one cell back along the wind vector. The zonal part is a
                    // whole cell; the meridional part is a fraction of a row, so the sample is a
                    // blend of this row and the one the air drifted in from. A neighbour outside
                    // this run is not sampled: that edge is a boundary between circulation cells,
                    // and air does not cross it at the surface.
                    val drift = wind.meridional[y * w]
                    val neighbour = if (drift > 0f) r - 1 else r + 1
                    val blend = if (drift != 0f && neighbour in 0 until rows) abs(drift) else 0f
                    if (blend != 0f) {
                        moisture[r] = previousColumn[r] +
                            (previousColumn[neighbour] - previousColumn[r]) * blend
                    }

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
                        moisture[r] += cfg.evaporationRate * warmth * (1f - moisture[r])
                        if (lap == 1) precip.data[i] = moisture[r] * cfg.baseRainRate * 4f
                        continue
                    }

                    // Orographic lift is the climb the air made getting here, so it is measured
                    // from the same blended upwind point rather than from due upwind along the row
                    // — otherwise a range a slanting wind climbs obliquely would read as flat.
                    val here = sea.relativeElevation.data[y * w + upwindX]
                    val upwindElevation = if (blend != 0f) {
                        here + (sea.relativeElevation.data[(firstRow + neighbour) * w + upwindX] -
                            here) * blend
                    } else {
                        here
                    }
                    val rise = (sea.relativeElevation.data[i] - upwindElevation).coerceAtLeast(0f)

                    val rate = (cfg.baseRainRate + cfg.orographicStrength * rise) * band
                    val rain = (moisture[r] * rate).coerceAtMost(moisture[r])
                    moisture[r] -= rain

                    // Evapotranspiration: the land gives water back, and how readily is the
                    // thing that decides where deserts sit. Scaled by the belt, because that
                    // is the mechanism: descending subtropical air suppresses the convection
                    // that would return moisture to the sky, while rising tropical air
                    // encourages it. Take the belt out of this term and every latitude
                    // re-moistens alike, at which point deserts stop preferring the horse
                    // latitudes at all -- measured, placement falls from 90% to 34%.
                    val warmth = ((temperature.data[i] + 10f) / 40f).coerceIn(0f, 1.4f)
                    moisture[r] += cfg.landRecoveryRate * warmth * band * (1f - moisture[r])

                    // Cold air simply holds less water.
                    val coldCap = ((temperature.data[i] + 25f) / 45f).coerceIn(0.15f, 1f)
                    moisture[r] = moisture[r].coerceAtMost(coldCap)

                    if (lap == 1) precip.data[i] = rain
                }
            }
        }
    }

    /**
     * The rainfall a high percentile of *land* receives, which is what 1.0 comes to mean.
     *
     * Normalizing by the absolute maximum instead would let the handful of extreme windward
     * mountain cells — which receive an order of magnitude more rain than anywhere flat — set the
     * scale, squashing every ordinary land cell below the desert threshold.
     *
     * Returns 0 when there is nothing to measure, which [scaleAndClamp] reads as "leave it alone".
     */
    private fun landPercentile(
        precip: FloatField,
        isLand: BooleanArray,
        percentile: Float
    ): Float {
        val bins = 2048
        var maximum = 0f
        var landCells = 0
        for (i in precip.data.indices) {
            if (!isLand[i]) continue
            landCells++
            if (precip.data[i] > maximum) maximum = precip.data[i]
        }
        if (landCells == 0 || maximum <= 0f) return 0f

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
        return reference
    }

    /** Scales a rainfall field so [reference] maps to 1, then clamps. */
    private fun scaleAndClamp(precip: FloatField, reference: Float) {
        if (reference <= 0f) return
        val inverse = 1f / reference
        for (i in precip.data.indices) {
            precip.data[i] = (precip.data[i] * inverse).coerceIn(0f, 1f)
        }
    }

    /**
     * How much the circulation belt encourages or suppresses rain, at a given distance from the
     * thermal equator.
     *
     * Three bands, each a bump centred where the atmosphere actually puts it: the wet ITCZ at the
     * equator, the dry descending air of the horse latitudes near 30, and the wet mid-latitude
     * storm track near 55.
     *
     * Taken from the *thermal* equator rather than the geographic one, which is what lets the
     * whole system migrate with the season: the same row sits under the dry descending limb in
     * one half of the year and under the storm track in the other, and that is the Mediterranean
     * climate and the monsoon both.
     *
     * Applied to the rain rate during the march rather than to the finished totals afterwards. As
     * a post-hoc multiplier it could not put rain back into air already wrung out crossing a
     * mountain -- twice nearly nothing is still nearly nothing -- so a rain shadow stayed a rain
     * shadow even on the wettest row of the map, which is how deserts were reaching the equator.
     */
    internal fun latitudeBandAt(
        lat: Float,
        subtropicalDryness: Float = 1.15f,
        /** See [seasonalBandSharpness]. One leaves every band exactly as it was. */
        sharpness: Float = 1f
    ): Float {
        val itcz = 1.0f * bell(lat, 0f, 12f)
        val subtropicalHigh = -subtropicalDryness * bell(lat, 30f, 13f)
        val stormTrack = 0.5f * bell(lat, 55f, 15f)
        val polarDry = -0.35f * bell(lat, 90f, 18f)
        // The floor does real work rather than merely guarding against nonsense: at the default
        // dryness the sum goes negative for roughly 25 to 35 degrees, so that span is clamped flat
        // and maximally arid. That is a fair description of a subtropical desert belt, but it does
        // mean raising the setting further widens the belt rather than deepening it.
        //
        // Each term is scaled separately rather than the sum being scaled afterwards, so that a
        // sharpness of one multiplies each by exactly 1 and adds them in exactly the order this
        // expression always added them. Float addition does not associate, and a reassociated sum
        // would be a last-bit difference — which is the whole of what `seasons = false` promises
        // not to be.
        return (1f + sharpness * itcz + sharpness * subtropicalHigh +
            sharpness * stormTrack + sharpness * polarDry).coerceAtLeast(0.05f)
    }

    /** The belts' departure from an unremarkable rain rate, which is what a season sharpens. */
    private fun bandAnomaly(lat: Float, subtropicalDryness: Float): Float =
        1.0f * bell(lat, 0f, 12f) -
            subtropicalDryness * bell(lat, 30f, 13f) +
            0.5f * bell(lat, 55f, 15f) -
            0.35f * bell(lat, 90f, 18f)

    /**
     * Biomes from four numbers rather than two.
     *
     * Annual temperature and annual rainfall still lay out the broad zones — they are what a
     * Whittaker diagram uses, and they were right about most of the map. What they cannot see is
     * the *shape* of the year, and four of the world's most distinctive land classes are shapes
     * rather than totals: a Mediterranean coast and a temperate forest can receive the same
     * rainfall and look nothing alike, so can a savanna and a seasonal forest, and so can a
     * maritime coast and a continental interior at the same latitude and the same annual mean.
     * Those four are decided on the seasons directly; everything else is as it was.
     *
     * The temperate/continental/polar thermal gate is Koppen's own: the coldest month decides
     * whether a place has a real winter, not the average of a year that blends one. A place with
     * [summerTemperature] below 10 C never has a growing season and is tundra (ET) whatever its
     * annual mean. Above that, [winterTemperature] at or below -3 C means a real winter with secure
     * snow cover and is continental (D), where taiga lives; above -3 C is temperate (C). The
     * tropical line is Koppen's A: a coldest month at or above 18 C, meaning there is no winter at
     * all — taken verbatim rather than invented, since nothing here argues for a different number.
     *
     * This is what fixes the high-latitude west coast (A5's Bergen case, A6 in the realism plan):
     * annual mean alone put 55 degrees within a couple of degrees of freezing, so even a strong
     * warm-current anomaly could not lift a mild-winter coast over the old `t < 7` bar. Bergen is
     * temperate at an 8 C annual mean because its *coldest month* is about 2 C — a fact the annual
     * mean cannot see and the coldest month states directly.
     *
     * With seasons off the two seasonal fields are the annual field, every ratio is exactly 1,
     * [summerTemperature] and [winterTemperature] both equal the annual mean, and every seasonal
     * test below falls through to the rule it replaced.
     */
    private fun classify(
        width: Int,
        height: Int,
        sea: SeaLevelResult,
        temperature: FloatField,
        summerTemperature: FloatField,
        winterTemperature: FloatField,
        precipitation: FloatField,
        summerPrecipitation: FloatField,
        winterPrecipitation: FloatField
    ): Array<Biome> {
        return Array(width * height) { i ->
            if (!sea.isLand[i]) {
                if (temperature.data[i] < -6f) Biome.ICE_SHEET
                else if (sea.relativeElevation.data[i] > -0.12f) Biome.SHALLOW_OCEAN
                else Biome.OCEAN
            } else {
                val t = temperature.data[i]
                val warm = summerTemperature.data[i]
                val cold = winterTemperature.data[i]
                val p = precipitation.data[i]
                val summerRain = summerPrecipitation.data[i]
                val winterRain = winterPrecipitation.data[i]
                // How lopsided the year is, in each direction. One number rather than a pair of
                // thresholds, because what separates a savanna from a seasonal forest of the same
                // annual total is the shape of the year and not its size.
                val summerShare = (summerRain + SEASON_FLOOR) / (winterRain + SEASON_FLOOR)
                val winterShare = (winterRain + SEASON_FLOOR) / (summerRain + SEASON_FLOOR)
                val elevation = sea.relativeElevation.data[i]
                when {
                    t < -8f -> Biome.ICE_SHEET
                    elevation > 0.72f -> Biome.ALPINE
                    // ET: even the warmest month never clears the tree line's own threshold.
                    warm < 10f -> Biome.TUNDRA
                    // D: a real summer, but a coldest month at or below -3 C means secure winter
                    // snow cover — Koppen's own line between continental and temperate. Moisture
                    // still decides taiga from the dry cold exactly as the old annual `t < 7`
                    // branch did; there is no separate steppe biome to give the dry case its own
                    // name.
                    cold <= -3f -> if (p < 0.18f) Biome.TUNDRA else Biome.TAIGA
                    // A: coldest month at or above 18 C — no winter at all.
                    cold >= 18f -> when {
                        p < 0.14f -> Biome.DESERT
                        // One drenching wet season doing nearly all the year's work.
                        summerShare >= 2.5f && summerRain >= 0.50f -> Biome.MONSOON_FOREST
                        // Savanna is a seasonality rather than a total: grass where the dry half
                        // of the year is long enough to burn, forest where it is not. The dry
                        // cases stay savanna as they were, and a wetter cell now joins them if
                        // its rain all arrives at once.
                        p < 0.30f -> Biome.SAVANNA
                        p < 0.58f ->
                            if (summerShare >= 1.6f) Biome.SAVANNA
                            else Biome.TROPICAL_SEASONAL_FOREST
                        else -> Biome.TROPICAL_RAINFOREST
                    }
                    // C: a real winter above -3 C and a real summer — everything in between.
                    else -> when {
                        // PROVISIONAL, and known to be wrong in one direction: this abolishes cold
                        // deserts. The Gobi's annual mean is about 2 C and Patagonia's is under 10,
                        // and both would be gated out below alongside the false positives this was
                        // added to stop. A real fix is a Koppen B (arid) test on rainfall in mm
                        // against a temperature-dependent aridity threshold — BW/BS — which A4's
                        // absolute-rainfall chunk is expected to add and subsume this into.
                        //
                        // What it stops: moving the D/C boundary poleward to fix the high-latitude
                        // coast (A6) also exposes marginal, barely-C interior at 46-58 degrees whose
                        // dry patches were taiga before and would otherwise read as desert now
                        // purely for having crossed a thermal line by a couple of degrees — measured
                        // on seed 42, that alone dropped desert-in-band from 98% to 48%. Until A4,
                        // an annual mean under 13 C is treated as that marginal case rather than a
                        // true hot subtropical desert, and falls to grassland below instead, same as
                        // a cold steppe would.
                        p < 0.14f && t >= 13f -> Biome.DESERT
                        // Dry summer, wet winter, mild enough for the rain to be rain: the
                        // subtropical high sits over the coast all summer and the westerlies swing
                        // back over it in winter. A real wet season is required as well as the
                        // ratio, or a dry continental interior would qualify on lopsidedness alone
                        // while receiving almost nothing either half of the year.
                        winterShare >= 1.7f && summerRain < 0.30f && winterRain >= 0.30f &&
                            cold > 2f -> Biome.MEDITERRANEAN
                        p < 0.28f -> Biome.GRASSLAND
                        p < 0.42f -> Biome.SHRUBLAND
                        p < 0.68f -> Biome.TEMPERATE_FOREST
                        else -> Biome.TEMPERATE_RAINFOREST
                    }
                }
            }
        }
    }
}
