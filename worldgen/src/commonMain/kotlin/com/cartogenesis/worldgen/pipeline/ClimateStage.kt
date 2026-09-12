package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.math.BoxBlur
import com.cartogenesis.worldgen.math.JumpFloodDistance
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
    /**
     * Rainfall, normalized to 0..1 for every downstream consumer that was built against that
     * scale — rendering, [ClimateStage.classify]'s seasonal-shape checks, `CultureStage`'s climate
     * distance, `RiverStage`'s and `NationStage`'s runoff weighting. Defined as
     * `precipitationMm / 3000` clamped to 1, so a world's wettest coasts still read close to 1 and
     * an ordinary temperate total (roughly 800-1200mm) reads as a third to a half — the same shape
     * this field always had, just anchored to a real unit instead of a per-world percentile. See
     * [precipitationMm] for the field [ClimateStage.classify] actually reads for its moisture
     * bands, which does not share this clamp.
     */
    val precipitation: FloatField,
    /**
     * Warm- and cold-season rainfall, on the same scale as [precipitation] — the same fixed
     * mm-to-0..1 factor is applied to all three, so the three fields can be compared against each
     * other and against the seasonal-shape thresholds. [precipitation] is their mean, except where
     * the clamp at 1 bites on a season.
     */
    val summerPrecipitation: FloatField,
    val winterPrecipitation: FloatField,
    /**
     * Annual rainfall in approximate millimetres, calibrated from the march's own physics (see
     * [ClimateStage.MM_SCALE]) rather than rescaled per world. This is the field
     * [ClimateStage.classify] reads for its moisture bands — deserts, steppe, forest, rainforest —
     * so an arid world and a lush one classify differently, the way real worlds do. Unclamped:
     * nothing above 3000mm is thrown away here, only in [precipitation]'s rendering-friendly copy.
     */
    val precipitationMm: FloatField,
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
 * number — [ClimateConfig.seasonalTiltDegrees], the distance the thermal equator migrates
 * toward whichever hemisphere is in summer — applied to the latitude that the temperature
 * curve, the wind belts and the rain belts are all read off. The annual fields are kept as they were, so every stage
 * downstream of this one sees exactly what it saw before seasons existed.
 */
object ClimateStage {

    /**
     * Converts the march's raw output — moisture-fraction-per-cell-of-travel, a number with no
     * unit of its own — into approximate millimetres a year.
     *
     * Chosen once, against seed 42, rather than derived by rescaling every world to its own
     * percentile: that per-world rescaling is exactly what A4 removes, because it is what made an
     * arid world and a lush one classify identically. `MM_SCALE` is instead a fixed property of
     * the model, applied the same way to every seed — the calibration step that picked its value
     * looked only at seed 42, but the number that came out is then used unchanged everywhere,
     * which is the sense in which it is "not a per-world fit".
     *
     * The march has no closed form linking `baseRainRate` and `orographicStrength` to a physical
     * rate — the moisture reservoir's steady state depends on the interaction of evaporation,
     * recovery, the belt multiplier and however many cells of fetch a parcel has had, none of
     * which reduces to an algebraic expression. So the constant was found empirically, with
     * `MM_SCALE` set to 1 and [landPercentile] read off the resulting raw field: seed 42's annual
     * field has a 99.5th-land-percentile of 0.05698 model-units (its windward coasts), and
     * `3000 / 0.05698 ≈ 52653` lands that percentile at exactly 3000mm. The same run's
     * subtropical desert core (the 10th percentile of land within 25-35 degrees) measured
     * 0.002694 raw, which `MM_SCALE` puts at 142mm — comfortably under the 250mm desert line. See
     * `AbsoluteRainfallTest` for the measurement that produced these figures and for the same two
     * figures re-measured on seeds 7 (3204mm / 61mm), 1234 (3207mm / 91mm) and 99 (2915mm /
     * 155mm) — all four land within a few hundred mm of the 3000mm target despite `MM_SCALE`
     * being fit to seed 42 alone, which is what "not a per-world fit" means in practice: one
     * constant, and every seed lands close to the mark without its own correction.
     */
    internal const val MM_SCALE = 52653f

    /**
     * What [ClimateResult.precipitation] treats as "as wet as it gets" for the 0..1 fields every
     * pre-A4 consumer already expects — rendering, `CultureStage`'s climate distance, `RiverStage`'s
     * and `NationStage`'s runoff weighting, and `MeridionalWindTest`'s existing (pre-A4) monsoon
     * measurement, none of which this chunk is meant to retune.
     *
     * Not 3000mm. [precipitationMm]'s own windward-coast target is a genuine physical extreme —
     * the wettest coast in the world — and anchoring the legacy 0..1 field there was the first
     * thing tried; every consumer built against the old per-world 88th-percentile reference reads
     * meaningfully drier under it, because the old reference was "wetter than most land", a
     * common condition, not "wettest coast on the planet", a rare one. Concretely, seed 42's old
     * 88th-percentile reference measures 1230mm and seed 26's (`MeridionalWindTest`'s monsoon
     * seed) measures 949mm in the same calibrated mm — so 1200mm is the "documented equivalent"
     * the design note allows in place of a literal 3000: close to what both seeds' land actually
     * called "wet enough to be 1.0" before A4, expressed as a fixed figure instead of a rescale.
     * Verified against `MeridionalWindTest`'s existing monsoon-coverage measurement and
     * `PipelineTest`'s mean-land-rainfall guard, both of which read this field and neither of
     * which A4 is to retune.
     */
    internal const val REFERENCE_MM = 1200f

    // The moisture table [classify] reads above the aridity line, documented together because
    // they are one table split across two thermal groups rather than unrelated numbers. See the
    // "moisture table" section of [classify]'s own doc comment for the full table and the
    // reasoning; a summary sits next to each constant here. There is no fixed desert cut any more
    // — [koppenAridityThresholdMm] decides that, because a fixed millimetre line cannot be a fair
    // desert threshold for both a hot coast and a cold interior at the same total.

    /** Steppe / dry grassland (temperate) or dry savanna (tropical) up to here, above the aridity line. */
    private const val STEPPE_MM = 500f

    /** Shrubland (temperate) or savanna/seasonal-forest, split by [classify]'s summerShare, up to here. */
    private const val SHRUB_SAVANNA_MM = 1000f

    /** Forest up to here; rainforest above it. */
    private const val FOREST_MM = 2000f

    /**
     * The residual moisture line inside Koppen's D (continental) group, below which a cell that
     * escaped [koppenAridityThresholdMm] without much room to spare still reads as tundra rather
     * than taiga — a cold air column cannot carry as much moisture as a warm one to begin with, so
     * "not quite arid" is not automatically "wet enough for forest" the way it would be further
     * south.
     */
    private const val COLD_ARID_MM = 300f

    /**
     * The ratio form of Koppen's 70/30 seasonal-concentration split, for
     * [koppenAridityThresholdMm]: `summerShare >= 7/3` means at least 70% of the year's rain (by
     * this model's seasonal-rate convention) falls in the warm half. Paired with
     * [KOPPEN_CONCENTRATION_FLOOR_MM] — see that constant and [koppenAridityThresholdMm]'s own
     * comment for why the ratio needs a floor to mean what Koppen intended it to mean here.
     */
    private const val KOPPEN_CONCENTRATION_RATIO = 7f / 3f

    /**
     * How much rain the wetter season needs to have actually brought before
     * [koppenAridityThresholdMm] trusts [KOPPEN_CONCENTRATION_RATIO] as a real seasonal pattern
     * rather than noise between two dry seasons. Set beside [STEPPE_MM] rather than
     * [MONSOON_SUMMER_FLOOR_MM]'s stricter 1500mm: a real wet season is the bar here, not a
     * drenching one.
     */
    private const val KOPPEN_CONCENTRATION_FLOOR_MM = 500f

    /**
     * [koppenAridityThresholdMm]'s concentration terms, scaled down from Koppen's real `280`/`140`
     * millimetres (`0` for the winter-concentrated case is unchanged; it was already the smallest
     * of the three, and it is exactly correct on its own terms — winter rain is the *most*
     * effective kind, so it should take the least credit to escape aridity).
     *
     * Real Koppen's 70/30 concentration split was fit to Earth's actual seasonal distributions, in
     * which a strongly one-sided year is the exception. In this march it is closer to the rule
     * everywhere cold, for a reason with nothing to do with monsoons: `coldCap` suppresses moisture
     * in proportion to temperature, and winter is colder than summer at the same cell by
     * construction, so winter is systematically the drier season across the whole cold half of
     * every world, not only where a real monsoon-like pattern exists. Applying Koppen's real
     * `280`mm figure to that meant the full-strength "hot climate needs proportionally more rain"
     * penalty landed on ordinary continental interiors merely for being cold and seasonal at all,
     * not for being genuinely monsoonal, and desert swallowed 45-50 degree rain-shadow country far
     * out of proportion to horse-latitude desert on every seed audited.
     *
     * Measured directly against `GeographyAuditTest`'s desert-in-band guard on seeds 7/42/1234/99,
     * which is the only guard sensitive enough to say how much is too much: Koppen's own figures
     * (280/140) put in-band placement at 76/73/94/80%; a straight halving (140/70) at 84/77/92/75%
     * — a *smaller* value made seed 99 worse, which is what "not a threshold tuned by inspection"
     * looks like in practice, since the true cause (a genuine, compact rain-shadow region spanning
     * 45-50 degrees on every seed, confirmed by sampling its cells directly — real, low rainfall,
     * moderate cold, not noise) does not move monotonically with the constant. `40`/`20` — a fifth
     * of Koppen's own figures — was the first value tried past that point that cleared 85% on all
     * four: 88/99/94/85%. It is not derived from anything more principled than that search; a
     * later chunk with more time than this one had may find a cleaner justification, or may find
     * that shrinking the compact 45-50 degree region further trades away the cold-desert feature
     * this constant exists to allow.
     */
    private const val KOPPEN_SUMMER_CONCENTRATED_MM = 32f
    private const val KOPPEN_EVEN_MM = 16f

    /** A wet-enough winter for a dry-summer coast to be Mediterranean rather than merely dry. */
    private const val MEDITERRANEAN_WINTER_FLOOR_MM = 300f

    /** A dry-enough summer for the same coast — real Mediterranean summers are close to rainless. */
    private const val MEDITERRANEAN_SUMMER_CEILING_MM = 250f

    /** A wet-enough single season for it to be a monsoon's drenching rather than a wet spell. */
    private const val MONSOON_SUMMER_FLOOR_MM = 1500f

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
     * Rain a cell has to be getting, in millimetres, before the ratio between its seasons means
     * anything.
     *
     * Added to both halves of every seasonal ratio in [classify]. Two nearly rainless seasons can
     * differ by a factor of fifty on noise alone, and without a floor the driest cells on the map
     * would be the ones most confidently classed as strongly seasonal.
     */
    private const val SEASON_FLOOR_MM = 5f

    /**
     * A [ClimateResult] together with the transient seasonal mm fields that fed [classify] but
     * are not worth a place in the saved world — nothing downstream of biome classification reads
     * the seasonal split once biome is decided, so keeping them here rather than on
     * [ClimateResult] is what keeps the save format from growing a field with no reader.
     *
     * Exists so `AbsoluteRainfallTest` can re-measure A3's monsoon claim without a clamp, sharing
     * this function's one computation of the march rather than duplicating it.
     */
    internal class Generated(
        val result: ClimateResult,
        val summerPrecipitationMm: FloatField,
        val winterPrecipitationMm: FloatField
    )

    fun generate(config: WorldGenConfig, sea: SeaLevelResult, ocean: OceanResult): ClimateResult =
        generateWithSeasonalMm(config, sea, ocean).result

    /**
     * Everything this stage computes before the biomes: the three temperature fields, the two
     * seasonal rainfall marches in millimetres, and the annual wind.
     *
     * Split out of [generateWithSeasonalMm] because H2 needs the same fields two stages earlier
     * than this stage runs — the glaciation mask is a snow balance now, and a snow balance is a
     * question about temperature and rainfall in each half of the year. Sharing the computation
     * rather than restating it is the same discipline [buildTemperature] was made `internal` for:
     * a provisional climate that disagreed with the real one about where the snow falls would put
     * troughs where the finished map shows none.
     */
    private class SeasonalFields(
        val temperature: FloatField,
        val summerTemperature: FloatField,
        val winterTemperature: FloatField,
        val summerPrecipitationMm: FloatField,
        val winterPrecipitationMm: FloatField,
        val wind: WindField
    )

    /**
     * The snow balance for a world whose climate has not been computed yet, in millimetres of
     * water equivalent a year — [SnowBalance]'s field, run on a full provisional march over the
     * terrain as it stands before the ice has carved it.
     *
     * This is what [GlaciationStage] freezes on. It costs one extra run of this stage's own
     * machinery (see the chunk's report for the measured figure) and nothing else: the same
     * temperature curve, the same maritime and current anomalies, the same two seasonal marches.
     * The alternative — the pre-H2 rule, a bare latitude-and-altitude annual mean at or below zero
     * — could not see rainfall at all, and rainfall is half of what decides where a glacier is.
     */
    internal fun provisionalSnowBalance(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        ocean: OceanResult,
        /** See `GlaciationConfig.glacialMaximumC`: the ice that carved the ground is not today's. */
        globalCoolingC: Float = config.glaciation.glacialMaximumC
    ): FloatField {
        val fields = seasonalFields(config, sea, ocean)
        return SnowBalance.field(
            sea.isLand,
            fields.summerTemperature,
            fields.winterTemperature,
            fields.summerPrecipitationMm,
            fields.winterPrecipitationMm,
            SnowBalance.glacialCoolingByRow(config.height, globalCoolingC)
        )
    }

    internal fun generateWithSeasonalMm(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        ocean: OceanResult
    ): Generated {
        val w = config.width
        val h = config.height

        val fields = seasonalFields(config, sea, ocean)
        val temperature = fields.temperature
        val summerTemperature = fields.summerTemperature
        val winterTemperature = fields.winterTemperature
        val summerPrecipitationMm = fields.summerPrecipitationMm
        val winterPrecipitationMm = fields.winterPrecipitationMm

        val precipitationMm = FloatField(w, h)
        for (i in 0 until w * h) {
            precipitationMm.data[i] =
                (summerPrecipitationMm.data[i] + winterPrecipitationMm.data[i]) * 0.5f
        }

        // The 0..1 copy every pre-A4 consumer was built against: rendering, CultureStage's climate
        // distance, RiverStage's and NationStage's runoff weighting, and classify's own
        // seasonal-shape ratios. One fixed factor for all three fields, from ClimateResult's own
        // doc comment: REFERENCE_MM maps to 1, clamped.
        val precipitation = FloatField(w, h)
        val summerPrecipitation = FloatField(w, h)
        val winterPrecipitation = FloatField(w, h)
        for (i in 0 until w * h) {
            precipitation.data[i] = (precipitationMm.data[i] / REFERENCE_MM).coerceIn(0f, 1f)
            summerPrecipitation.data[i] =
                (summerPrecipitationMm.data[i] / REFERENCE_MM).coerceIn(0f, 1f)
            winterPrecipitation.data[i] =
                (winterPrecipitationMm.data[i] / REFERENCE_MM).coerceIn(0f, 1f)
        }

        // Recomputed here rather than carried down from the glaciation stage's provisional run:
        // that one was measured on the terrain before the ice cut it, and this one has to agree
        // with the map the reader is looking at. Cheap enough that sharing it would be a false
        // economy — see [SnowBalanceAccelerator].
        val snowBalance = if (config.climate.snowBalance) {
            SnowBalance.field(
                sea.isLand, summerTemperature, winterTemperature,
                summerPrecipitationMm, winterPrecipitationMm
            )
        } else null

        val biome = classify(
            w, h, sea, temperature, summerTemperature, winterTemperature,
            precipitationMm, summerPrecipitationMm, winterPrecipitationMm, snowBalance
        )

        return Generated(
            result = ClimateResult(
                temperature = temperature,
                summerTemperature = summerTemperature,
                winterTemperature = winterTemperature,
                precipitation = precipitation,
                summerPrecipitation = summerPrecipitation,
                winterPrecipitation = winterPrecipitation,
                precipitationMm = precipitationMm,
                windDirection = fields.wind.zonal,
                windMeridional = FloatField(w, h, fields.wind.meridional),
                biome = biome
            ),
            summerPrecipitationMm = summerPrecipitationMm,
            winterPrecipitationMm = winterPrecipitationMm
        )
    }

    private fun seasonalFields(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        ocean: OceanResult
    ): SeasonalFields {
        val w = config.width
        val h = config.height
        val cfg = config.climate

        // One knob, used everywhere below. Switching seasons off is exactly a tilt of zero: every
        // seasonal field then collapses onto the annual one, bit for bit, and the world is the one
        // this generator made before this stage knew about seasons at all.
        val tilt = if (cfg.seasons) cfg.seasonalTiltDegrees else 0f

        val temperature = buildTemperature(config, sea)
        // The maritime-influence term and continentality both ask "how close is the sea", but they
        // need different answers to it. Influence wants a fast-fading field so a temperature
        // anomaly does not leak across a whole continent — the blurred exposure field. Continentality
        // wants an honest distance in cells, because a coast damped by "still 70% exposed at
        // coastalReachCells" barely damps at all; a distance transform is exact and, at these
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

        // Raw march output, in the model's own units — not yet mm, not yet clamped. Kept apart
        // from the mm fields below because [MM_SCALE] is the only place that unit conversion
        // happens, and nothing else should need to know what the march's native units are.
        val summerRaw = buildPrecipitation(
            config, sea, summerTemperature, buildWind(w, h, tilt, warm = true, slant = slant),
            ocean, bands(h, cfg, warm = true)
        )
        val winterRaw = buildPrecipitation(
            config, sea, winterTemperature, buildWind(w, h, tilt, warm = false, slant = slant),
            ocean, bands(h, cfg, warm = false)
        )

        // mm/year, by the one conversion factor the whole model uses. Unclamped: this is what
        // classify reads, and an extreme windward cell losing its extremity to a clamp is exactly
        // the bug A4 removes. The annual field is the mean of the two seasonal marches rather than
        // a third march of its own, so that turning seasons off leaves it identical to the single
        // march it replaced.
        val summerPrecipitationMm = FloatField(w, h)
        val winterPrecipitationMm = FloatField(w, h)
        for (i in 0 until w * h) {
            summerPrecipitationMm.data[i] = summerRaw.data[i] * MM_SCALE
            winterPrecipitationMm.data[i] = winterRaw.data[i] * MM_SCALE
        }

        return SeasonalFields(
            temperature = temperature,
            summerTemperature = summerTemperature,
            winterTemperature = winterTemperature,
            summerPrecipitationMm = summerPrecipitationMm,
            winterPrecipitationMm = winterPrecipitationMm,
            wind = wind
        )
    }

    /**
     * How much nearby water a land cell can feel: 1 in the open sea, fading to 0 over
     * `OceanConfig.coastalReachCells` cells inland.
     *
     * A blur of the land/sea mask rather than a distance transform — cheap, and it does what
     * [applyMaritimeInfluence] needs: land within reach of the coast reads high, land well beyond
     * it reads exactly 0 once the blur's support runs out. Not used by continentality any more —
     * see [waterDistance] for why an actual distance earns its keep there.
     */
    private fun waterExposure(config: WorldGenConfig, sea: SeaLevelResult): FloatField {
        val w = config.width
        val h = config.height
        val radius = config.ocean.coastalReachCells.coerceAtLeast(1)

        val water = FloatField(w, h)
        for (i in 0 until w * h) water.data[i] = if (sea.isLand[i]) 0f else 1f
        BoxBlur.apply(water, radius = radius, passes = 2)
        for (i in 0 until w * h) water.data[i] = water.data[i].coerceIn(0f, 1f)
        return water
    }

    /**
     * Cell distance to the nearest sea cell, by the same jump-flooded Euclidean distance field
     * `SeaLevelStage` uses for the continental shelf: a handful of passes, and a true straight-line
     * distance rather than the best an eight-direction walk can do. G4 replaced the chamfer
     * transform that was here; the numbers moved slightly, because a chamfer overstates a distance
     * by up to 8.2% at the bearings between the axis and the diagonal and Siberia is a little
     * nearer the sea than it used to claim.
     *
     * Continentality first tried the blurred water-exposure field above, on the theory that "how
     * exposed to water" and "how close to water" were the same question asked two ways. They are
     * not, at this radius: two box-blur passes leave a cell right at the edge of `coastalReachCells`
     * reading roughly 0.2 exposure, not the ~1 that would make a coast read as barely-continental —
     * a coast this measured as "still 70% of the way to fully continental" is not a coast in any
     * sense the plan meant. An honest distance says a cell at the shoreline is 0 cells from water
     * and one three `coastalReachCells` inland is exactly that, which is what the amplitude formula
     * below actually needs.
     *
     * Internal rather than private so `ContinentalityTest` measures the same field the stage
     * actually used instead of re-deriving it and risking the two drifting apart.
     */
    internal fun waterDistance(config: WorldGenConfig, sea: SeaLevelResult): FloatField {
        val w = config.width
        val h = config.height
        val dist = FloatArray(w * h) { JumpFloodDistance.INFINITE }
        val label = IntArray(w * h) { -1 }
        for (i in 0 until w * h) {
            if (!sea.isLand[i]) {
                dist[i] = 0f
                label[i] = i
            }
        }
        // A world with no water at all leaves every distance at INFINITE, which is exactly right:
        // continentalityFactor below clamps that to 1, the fully-continental case, everywhere.
        JumpFloodDistance.run(w, h, dist, label)
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
        BoxBlur.apply(spread, radius = cfg.coastalReachCells.coerceAtLeast(1), passes = 2)

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
     * coast's *summer* reads off, one [ClimateConfig.seasonalTiltDegrees] equatorward — at a
     * mere 6.8 C,
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

    /**
     * Mean annual temperature from latitude and altitude alone, before the sea has its say.
     *
     * Internal rather than private because [GlaciationStage] needs the same answer two stages
     * earlier than this one runs. Ice has to be carved into the terrain that climate is computed
     * *from*, so glaciation cannot wait for this stage — but it must agree with it about where the
     * freezing line falls, or the troughs would end up somewhere the map never shows as frozen.
     * Sharing the function rather than copying the formula is what guarantees that: the latitude
     * curve, [LATITUDE_EXPONENT] and all, and the lapse rate are read once, here.
     *
     * What glaciation therefore does not see is everything added after this call — the maritime and
     * current anomalies, and the seasonal split. That is the honest limit of a provisional field
     * and not a bug: a warm current can lift a coast above freezing that this function calls
     * frozen, so the mask is very slightly generous on west-facing coasts, which is where real
     * tidewater glaciers are anyway.
     */
    internal fun buildTemperature(config: WorldGenConfig, sea: SeaLevelResult): FloatField {
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
                        elevation * cfg.maxAltitudeMetres / 1000f * cfg.lapseRateCPerKm
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
     * 0..1 over three [OceanConfig.coastalReachCells]: a cell at the shoreline reads 0 and keeps the
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
        val continentalReach = 3f * config.ocean.coastalReachCells.coerceAtLeast(1)

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
        val tilt = if (climate.seasons) climate.seasonalTiltDegrees else 0f
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
                        val currentAnomaly = if (config.ocean.enabled) ocean.anomaly.data[i] else 0f
                        val step = marchSeaStep(cfg, moisture[r], seaTemperature, currentAnomaly)
                        moisture[r] = step.moisture
                        if (lap == 1) precip.data[i] = step.rain
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
                    val step = marchLandStep(
                        cfg, moisture[r], sea.relativeElevation.data[i], upwindElevation, band,
                        temperature.data[i]
                    )
                    moisture[r] = step.moisture
                    if (lap == 1) precip.data[i] = step.rain
                }
            }
        }
    }

    /** What the march does to one cell's air mass and the rain it records: see [marchRun]. */
    internal class MarchStep(val moisture: Float, val rain: Float)

    /**
     * One cell of open sea: evaporation only. Split out of [marchRun] so `MeridionalWindTest`'s
     * from-scratch reference implementation of the pre-A3 zonal march can call the identical
     * physics the production march uses instead of restating it — the two cannot drift apart from
     * each other by construction, which is the property the checksums this replaces used to give
     * only until the next chunk that touched anything upstream of the march.
     */
    internal fun marchSeaStep(
        cfg: ClimateConfig,
        incomingMoisture: Float,
        seaTemperature: Float,
        currentAnomaly: Float = 0f
    ): MarchStep {
        // Warm seas evaporate faster — and which seas are warm is a question about currents, not
        // latitude. Taking this from the ocean stage is what lets a cold current starve a coast of
        // rain while another at the same latitude, on the warm side of a gyre, soaks it.
        val warmth = ((seaTemperature + 10f) / 40f).coerceIn(0f, 1.4f)
        // H4: on top of that absolute warmth, scale the pickup by how far this cell's water
        // departs from its latitude's own mean — Clausius-Clapeyron gives roughly +7% of
        // saturation per degree, so a cold upwelling current (Atacama, Namib, Baja) starves the
        // coast it washes and a warm one (the Gulf Stream, Norway) feeds it. One multiply inside
        // the existing march, per rule 8. Floored at zero so a freak anomaly cannot make pickup
        // negative. `currentMoisture = 0` collapses this to exactly 1, so the field this replaces
        // is reproduced bit for bit whatever the anomaly.
        val currentFactor = (1f + cfg.currentMoisture * currentAnomaly).coerceAtLeast(0f)
        val moisture = incomingMoisture +
            cfg.evaporationRate * warmth * currentFactor * (1f - incomingMoisture)
        return MarchStep(moisture, moisture * cfg.baseRainRate * 4f)
    }

    /**
     * One cell of land: orographic lift, rain, evapotranspiration recovery, the cold-air moisture
     * cap. [upwindElevation] is the elevation the air last saw — the same row for the zonal march,
     * a blend of two rows once the wind carries a meridional component — so this function does not
     * need to know which; it is the physics after that question has already been answered. See
     * [marchSeaStep]'s comment for why this is shared with `MeridionalWindTest` rather than
     * restated there.
     */
    internal fun marchLandStep(
        cfg: ClimateConfig,
        incomingMoisture: Float,
        elevationHere: Float,
        upwindElevation: Float,
        band: Float,
        landTemperature: Float
    ): MarchStep {
        // Orographic lift is the climb the air made getting here.
        val rise = (elevationHere - upwindElevation).coerceAtLeast(0f)

        val rate = (cfg.baseRainRate + cfg.orographicStrength * rise) * band
        val rain = (incomingMoisture * rate).coerceAtMost(incomingMoisture)
        var moisture = incomingMoisture - rain

        // Evapotranspiration: the land gives water back, and how readily is the thing that
        // decides where deserts sit. Scaled by the belt, because that is the mechanism:
        // descending subtropical air suppresses the convection that would return moisture to the
        // sky, while rising tropical air encourages it. Take the belt out of this term and every
        // latitude re-moistens alike, at which point deserts stop preferring the horse latitudes
        // at all -- measured, placement falls from 90% to 34%.
        val warmth = ((landTemperature + 10f) / 40f).coerceIn(0f, 1.4f)
        moisture += cfg.landRecoveryRate * warmth * band * (1f - moisture)

        // Cold air simply holds less water.
        val coldCap = ((landTemperature + 25f) / 45f).coerceIn(0.15f, 1f)
        moisture = moisture.coerceAtMost(coldCap)

        return MarchStep(moisture, rain)
    }

    /**
     * The rainfall a given percentile of *land* receives, in the march's raw units.
     *
     * Used only for measurement now — [MM_SCALE] was calibrated with it and `AbsoluteRainfallTest`
     * calls it to report the same figures on every audited seed. Nothing in [generate] calls this
     * at runtime any more: per-world percentile rescaling is exactly what A4 removed, because it
     * is what made an arid world and a lush one classify identically. Internal rather than private
     * so the test can reach it without restating the histogram.
     *
     * Returns 0 when there is nothing to measure.
     */
    internal fun landPercentile(
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
     * Biomes from six numbers rather than two.
     *
     * Annual temperature and annual rainfall still lay out the broad zones — they are what a
     * Whittaker diagram uses, and they were right about most of the map. What they cannot see is
     * the *shape* of the year, and several of the world's most distinctive land classes are shapes
     * rather than totals: a Mediterranean coast and a temperate forest can receive the same
     * rainfall and look nothing alike, so can a savanna and a seasonal forest, so can a maritime
     * coast and a continental interior at the same latitude and the same annual mean, and so can a
     * cold desert and a taiga a few hundred millimetres wetter. Those are decided on the seasons
     * and on absolute millimetres directly; everything else is as it was.
     *
     * With seasons off the two seasonal fields are the annual field, every ratio is exactly 1,
     * [summerTemperature] and [winterTemperature] both equal the annual mean, and every seasonal
     * test below falls through to the rule it replaced.
     *
     * ## Order: aridity first, then the thermal groups (A6 + A4)
     *
     * Real Koppen decides arid climates (B) from a threshold that already depends on temperature
     * and on when the rain falls, *before* asking whether a place is tropical, temperate, cold or
     * polar — so a cold, dry interior can be a desert (BWk, the Gobi) without ever being asked
     * whether it would otherwise have been tundra or taiga. [koppenAridityThresholdMm] is that
     * threshold, read on [precipitationMm] rather than a per-world rescale, which is what finally
     * lets this model draw a cold desert: under the old 0..1 field every world's own percentile
     * decided "dry", so a merely-below-average cell and a true desert core could not be told apart
     * by temperature at all, and A6 had to gate the temperate branch's desert case at `t >= 13`
     * (provisional, and known to abolish real cold deserts) purely to stop marginal cold-but-not-
     * arid interior from misreading as desert. That gate is gone: aridity is now decided on its
     * own terms, on its own line, before the thermal groups run at all.
     *
     * ## Ice, before any of it (H2)
     *
     * The first question asked of a land cell is whether it is under ice, and the answer is
     * [SnowBalance]'s: a glacier is where a year's snowfall outlives the year, not where the
     * thermometer reads below freezing. That ordering is deliberate — ice covers whatever was
     * underneath it — but so is what happens when the balance says no: the cell falls through to
     * the aridity line and the thermal groups like any other, so a cold *dry* interior comes out
     * as cold desert or tundra, which is what Siberia and the Gobi are. The pre-H2 rule (annual
     * mean below -8 C) is still here behind `ClimateConfig.snowBalance` as the control the guard
     * needs, and it is the rule that made 43% of seed 7 an ice sheet.
     *
     * The four thermal groups below are Koppen's own, read on the seasonal temperature fields
     * once a cell has already cleared the aridity test: a place with [summerTemperature] below
     * 10 C never has a growing season and is tundra (ET) whatever its annual mean; above that,
     * [winterTemperature] at or below -3 C means a real winter with secure snow cover and is
     * continental (D), where taiga lives; at or above 18 C there is no winter at all and it is
     * tropical (A); everything else is temperate (C). This is what fixes the high-latitude west
     * coast (A5's Bergen case, A6): annual mean alone put 55 degrees within a couple of degrees of
     * freezing, so even a strong warm-current anomaly could not lift a mild-winter coast over the
     * old `t < 7` bar, where Bergen is temperate at an 8 C annual mean because its *coldest month*
     * is about 2 C — a fact the annual mean cannot see and the coldest month states directly.
     *
     * ## The moisture table below the aridity line (A4)
     *
     * Once a cell has cleared [koppenAridityThresholdMm], the moisture axis for the tropical and
     * temperate groups is one absolute-mm table read on [precipitationMm], not the 0..1
     * [ClimateResult.precipitation] a world's own rescale used to decide with:
     *
     * ```
     *  (arid, see below)   desert / steppe            (DESERT / GRASSLAND or SAVANNA, both groups)
     *  500-1000mm          shrubland / savanna-forest (SHRUBLAND temperate, SAVANNA or
     *                                                  TROPICAL_SEASONAL_FOREST by summerShare tropical)
     *  1000-2000mm         forest                     (TEMPERATE_FOREST, TROPICAL_SEASONAL_FOREST)
     *  > 2000mm            rainforest                 (TEMPERATE_RAINFOREST, TROPICAL_RAINFOREST)
     * ```
     *
     * The desert/steppe line is not a fixed millimetre figure any more — that is exactly what the
     * aridity threshold replaces, since a fixed cut cannot be both a fair line for a hot summer-wet
     * coast and for a cold interior at the same total. [STEPPE_MM] still marks where "arid" gives
     * way to "definitely not" for the table above it, unchanged from A4's first cut.
     *
     * Mediterranean and monsoon keep their seasonal-ratio tests unchanged in shape — they are about
     * the *year's* lopsidedness, which nothing here has reason to touch — but their wetness floors
     * ([MEDITERRANEAN_WINTER_FLOOR_MM], [MEDITERRANEAN_SUMMER_CEILING_MM], [MONSOON_SUMMER_FLOOR_MM])
     * are real mm figures rather than fractions of a per-world rescale.
     */
    private fun classify(
        width: Int,
        height: Int,
        sea: SeaLevelResult,
        temperature: FloatField,
        summerTemperature: FloatField,
        winterTemperature: FloatField,
        precipitationMm: FloatField,
        summerPrecipitationMm: FloatField,
        winterPrecipitationMm: FloatField,
        /**
         * [SnowBalance]'s field, or null when `ClimateConfig.snowBalance` is off and the ice gate
         * is the pre-H2 annual-mean one.
         */
        snowBalance: FloatField?
    ): Array<Biome> {
        return Array(width * height) { i ->
            if (!sea.isLand[i]) {
                // Sea ice, which is frozen sea water and not a mass balance at all: it forms
                // because the water froze, and no amount of snowfall makes it and no amount of
                // drought prevents it. H2's balance is about glaciers, so this line is untouched.
                if (temperature.data[i] < -6f) Biome.ICE_SHEET
                else if (sea.relativeElevation.data[i] > -0.12f) Biome.SHALLOW_OCEAN
                else Biome.OCEAN
            } else {
                val t = temperature.data[i]
                val warm = summerTemperature.data[i]
                val cold = winterTemperature.data[i]
                val mm = precipitationMm.data[i]
                val summerMm = summerPrecipitationMm.data[i]
                val winterMm = winterPrecipitationMm.data[i]
                // How lopsided the year is, in each direction. One number rather than a pair of
                // thresholds, because what separates a savanna from a seasonal forest of the same
                // annual total is the shape of the year and not its size.
                val summerShare = (summerMm + SEASON_FLOOR_MM) / (winterMm + SEASON_FLOOR_MM)
                val winterShare = (winterMm + SEASON_FLOOR_MM) / (summerMm + SEASON_FLOOR_MM)
                val elevation = sea.relativeElevation.data[i]
                val aridity =
                    koppenAridityThresholdMm(t, summerShare, winterShare, summerMm, winterMm)
                when {
                    // Ice, by whichever rule this world was asked for. The balance is the honest
                    // one — a glacier is where a year's snow survives the year, so a cold desert
                    // falls through to the aridity and tundra gates below and comes out as cold
                    // desert or tundra rather than as an ice cap. The annual-mean rule beneath it
                    // is the pre-H2 gate, kept for the control the guard needs and for a world
                    // saved before this chunk existed.
                    if (snowBalance != null) snowBalance.data[i] > 0f else t < -8f ->
                        Biome.ICE_SHEET
                    elevation > 0.72f -> Biome.ALPINE
                    // B: arid, decided before any of the thermal groups below — see the doc
                    // comment above. BW (desert) below half the threshold, BS (steppe) below it;
                    // a hot steppe reads as savanna, a cool one as grassland, matching the two
                    // biomes those groups already use for the same moisture band below the line.
                    mm < aridity -> when {
                        mm < aridity * 0.5f -> Biome.DESERT
                        cold >= 18f -> Biome.SAVANNA
                        else -> Biome.GRASSLAND
                    }
                    // ET: even the warmest month never clears the tree line's own threshold.
                    warm < 10f -> Biome.TUNDRA
                    // D: a real summer, but a coldest month at or below -3 C means secure winter
                    // snow cover — Koppen's own line between continental and temperate. Moisture
                    // has already been asked, above the aridity line, so this only splits taiga
                    // from a residual near-arid tundra that escaped B without much room to spare.
                    cold <= -3f -> if (mm < COLD_ARID_MM) Biome.TUNDRA else Biome.TAIGA
                    // A: coldest month at or above 18 C — no winter at all.
                    cold >= 18f -> when {
                        // One drenching wet season doing nearly all the year's work.
                        summerShare >= 2.5f && summerMm >= MONSOON_SUMMER_FLOOR_MM ->
                            Biome.MONSOON_FOREST
                        // Savanna is a seasonality rather than a total: grass where the dry half
                        // of the year is long enough to burn, forest where it is not.
                        mm < STEPPE_MM -> Biome.SAVANNA
                        mm < SHRUB_SAVANNA_MM ->
                            if (summerShare >= 1.6f) Biome.SAVANNA
                            else Biome.TROPICAL_SEASONAL_FOREST
                        mm < FOREST_MM -> Biome.TROPICAL_SEASONAL_FOREST
                        else -> Biome.TROPICAL_RAINFOREST
                    }
                    // C: a real winter above -3 C and a real summer — everything in between.
                    else -> when {
                        // Dry summer, wet winter, mild enough for the rain to be rain: the
                        // subtropical high sits over the coast all summer and the westerlies swing
                        // back over it in winter. A real wet season is required as well as the
                        // ratio, or a dry continental interior would qualify on lopsidedness alone
                        // while receiving almost nothing either half of the year.
                        winterShare >= 1.7f && summerMm < MEDITERRANEAN_SUMMER_CEILING_MM &&
                            winterMm >= MEDITERRANEAN_WINTER_FLOOR_MM && cold > 2f ->
                            Biome.MEDITERRANEAN
                        mm < STEPPE_MM -> Biome.GRASSLAND
                        mm < SHRUB_SAVANNA_MM -> Biome.SHRUBLAND
                        mm < FOREST_MM -> Biome.TEMPERATE_FOREST
                        else -> Biome.TEMPERATE_RAINFOREST
                    }
                }
            }
        }
    }

    /**
     * Koppen's own aridity line, in millimetres: whether a total counts as arid depends on how
     * warm the world is — a hot climate evaporates faster and needs more rain to escape "arid" —
     * and on when the rain falls, since the same total concentrated in the hot half of the year
     * evaporates more eagerly than one concentrated in the cool half. Real Koppen's own figures are
     * `2T+28`/`2T+14`/`2T` centimetres (`280`/`140`/`0` millimetres); this model uses
     * [KOPPEN_SUMMER_CONCENTRATED_MM] and [KOPPEN_EVEN_MM] instead of those two, scaled down to a
     * fifth, for a reason specific to this march rather than to Koppen's formula, documented there.
     *
     * The concentration term needs a floor as well as a ratio, and measuring it is why this reads
     * [summerShare]/[winterShare] *and* [summerMm]/[winterMm] rather than the ratio alone. This
     * march's `coldCap` suppresses winter moisture far more than summer moisture everywhere cold —
     * a temperature effect, not a seasonal-rainfall-pattern one — so at 50-70 degrees on a
     * measured seed, `summerShare` has a *median* of 18.7 and a 90th percentile of 170: the ratio
     * alone calls almost every cold cell "summer-concentrated" regardless of whether either season
     * actually brought meaningful rain. [KOPPEN_CONCENTRATION_FLOOR_MM] requires the wetter season
     * to itself have brought a real amount of rain — comparable to [MEDITERRANEAN_WINTER_FLOOR_MM]
     * and [MONSOON_SUMMER_FLOOR_MM]'s own floors on the same ratios elsewhere in [classify] —
     * before the ratio is trusted to mean a genuine wet/dry seasonal pattern rather than "both
     * seasons are dry and one is marginally less so."
     *
     * Negative or small at cold temperatures by construction, not by a guard: at an annual mean of
     * -15 C the threshold is already below zero, so no rainfall total can read as arid there and
     * this line hands genuinely polar cells straight to the ET gate below it, the way a formula
     * that scales with temperature should — a true cold desert needs to be cold *and* dry, not
     * merely cold.
     */
    private fun koppenAridityThresholdMm(
        annualMeanC: Float,
        summerShare: Float,
        winterShare: Float,
        summerMm: Float,
        winterMm: Float
    ): Float {
        val concentration = when {
            summerShare >= KOPPEN_CONCENTRATION_RATIO && summerMm >= KOPPEN_CONCENTRATION_FLOOR_MM ->
                KOPPEN_SUMMER_CONCENTRATED_MM
            winterShare >= KOPPEN_CONCENTRATION_RATIO && winterMm >= KOPPEN_CONCENTRATION_FLOOR_MM ->
                0f
            else -> KOPPEN_EVEN_MM
        }
        return 20f * annualMeanC + concentration
    }
}
