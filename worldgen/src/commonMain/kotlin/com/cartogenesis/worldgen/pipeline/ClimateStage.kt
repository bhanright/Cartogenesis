package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.math.BoxBlur
import com.cartogenesis.worldgen.math.JumpFloodDistance
import com.cartogenesis.worldgen.model.ClimateConfig
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldScale
import com.cartogenesis.worldgen.noise.PerlinNoise
import kotlin.math.abs
import kotlin.math.exp
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
     * Temperature in the local warmest and coldest month, in degrees Celsius.
     *
     * These are not July and January. "Summer" is whichever month the cell's *own* latitude and
     * surface are warmest in, so northern July and southern January both land in
     * [summerTemperature] — and a coast's warmest month runs later than the interior's beside it,
     * because water remembers longer. Months rather than half-years because every threshold
     * [ClimateStage.classify] applies to them is one of Koppen's and Koppen's are monthly means.
     * Storing the local season rather than the calendar month is what lets
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
    /**
     * Where the sea is frozen in each season: one entry per cell, row-major, true where that
     * season's sea surface sits at or below the freezing point of sea water
     * ([EnergyBalance.SEA_FREEZING_C], -1.8 C at the ocean's mean salinity) and false everywhere
     * on land.
     *
     * Two masks rather than one, because the two are different countries. The cold season's is the
     * winter pack, which on Earth reaches the Sea of Okhotsk and the Gulf of Bothnia and is gone
     * by August; the warm season's is the perennial ice, which is what an atlas draws and what
     * [ClimateStage.classify] paints as `ICE_SHEET` over water.
     *
     * The moisture march reads whichever mask belongs to the season it is marching, and takes no
     * moisture at all from a frozen cell — a metre of ice is a lid, which is why the polar ocean is
     * a desert and why a polar desert exists on the coast beside it.
     */
    val summerSeaIce: BooleanArray,
    val winterSeaIce: BooleanArray,
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
     * A fixed property of the model rather than a per-world rescale. Rescaling each world to its
     * own percentile is what made an arid world and a lush one classify identically; this factor
     * is applied the same way to every seed, so an arid world reads as arid.
     *
     * The march has no closed form linking `baseRainRate` and `orographicStrength` to a physical
     * rate — the moisture reservoir's steady state depends on evaporation, recovery, the belt
     * multiplier and however many cells of fetch a parcel has had, none of which reduces to an
     * algebraic expression. So the factor was found empirically, with it set to 1 and
     * [landPercentile] read off the resulting raw field: the calibration seed's annual field has a
     * 99.5th-land-percentile of 0.05698 model-units — its windward coasts — and
     * `3000 / 0.05698 ≈ 52653` puts that percentile at exactly 3000mm, the wettest coast on Earth.
     * The same run's subtropical desert core measured 0.002694 raw, which this factor puts at
     * 142mm, comfortably under the 250mm desert line.
     *
     * `AbsoluteRainfallTest` re-measures both figures on every audited seed, and they land within
     * a few hundred mm of the target on all of them — which is what "not a per-world fit" means in
     * practice. See docs/DESIGN_LEDGER.md, A4.
     */
    internal const val MM_SCALE = 52653f

    /**
     * What [ClimateResult.precipitation] treats as "as wet as it gets", in millimetres a year, for
     * the 0..1 fields its consumers expect — rendering, `CultureStage`'s climate distance,
     * `RiverStage`'s and `NationStage`'s runoff weighting, and `MeridionalWindTest`'s monsoon
     * measurement.
     *
     * Not 3000mm. [ClimateResult.precipitationMm]'s own windward-coast target is a genuine
     * physical extreme — the wettest coast in the world — and anchoring the 0..1 field there makes
     * every consumer read meaningfully drier, because the reference those consumers were built
     * against meant "wetter than most land", a common condition, not "wettest coast on the
     * planet", a rare one. 1200mm is what the two calibration seeds' land actually called "wet
     * enough to be 1.0" under the old per-world rescale, expressed as a fixed figure instead.
     * See docs/DESIGN_LEDGER.md, A4.
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
     * [koppenAridityThresholdMm]'s concentration terms, in millimetres: what a summer-concentrated
     * and an evenly-spread year add to the aridity line. A fifth of Koppen's own `280`/`140`. The
     * winter-concentrated case keeps Koppen's `0` and is not a constant here, because winter rain
     * is the most effective kind and should take no credit at all to escape aridity.
     *
     * Koppen's 70/30 concentration split was fit to Earth, where a strongly one-sided year is the
     * exception. In this march it is close to the rule everywhere cold, for a reason with nothing
     * to do with monsoons: the cold cap in [marchLandStep] suppresses moisture in proportion to
     * temperature, and winter is colder than summer at the same cell by construction, so winter is
     * systematically the drier season across the whole cold half of every world. At Koppen's own
     * figure the full "a hot climate needs proportionally more rain" penalty therefore lands on
     * ordinary continental interiors merely for being cold and seasonal, and desert swallows the
     * 45-50 degree rain-shadow country out of all proportion to horse-latitude desert.
     *
     * A fifth is where `GeographyAuditTest`'s desert-in-band guard clears its bar on every audited
     * seed, and it is a search rather than a derivation: the guard does not move monotonically
     * with the constant, because the cells at issue are a genuine compact rain-shadow region and
     * not noise. A later chunk may find a cleaner justification. See docs/DESIGN_LEDGER.md, A4, for the
     * figures at each value tried.
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
     * Rain a cell has to be getting, in millimetres, before the ratio between its seasons means
     * anything.
     *
     * Added to both halves of every seasonal ratio in [classify]. Two nearly rainless seasons can
     * differ by a factor of fifty on noise alone, and without a floor the driest cells on the map
     * would be the ones most confidently classed as strongly seasonal.
     */
    private const val SEASON_FLOOR_MM = 5f

    /**
     * Passes of [BoxBlur] used wherever this stage spreads a field over its neighbourhood.
     *
     * Two, one short of [BoxBlur.PASSES_FOR_GAUSSIAN]: what is being spread here is a land/sea mask
     * and a sea-surface anomaly, both of which the march then integrates over many cells, so the
     * square kernel's corners never survive into anything a reader sees and a third pass would be
     * two more sweeps of the grid for nothing.
     */
    private const val BLUR_PASSES = 2

    /** Latitude of a pole, in degrees; the top row of the map sits just short of the north one. */
    private const val POLE_DEGREES = 90f

    /** Degrees of latitude the whole map spans, top row to bottom row. */
    private const val POLE_TO_POLE_DEGREES = 180f

    /**
     * Decorrelates this stage's noise from every other stage's, which all draw on the same world
     * seed. The multiplier is a large prime and the offset distinguishes this field from the next
     * one that wants a stream of its own.
     */
    private const val TEMPERATURE_NOISE_MULTIPLIER = 32452843
    private const val TEMPERATURE_NOISE_OFFSET = 11

    /**
     * How much weather the temperature field carries on top of latitude and altitude, in degrees
     * Celsius peak to trough.
     *
     * Small enough that it never moves a cell across a Koppen boundary on its own, large enough
     * that an isotherm is a wandering line rather than a ruled one.
     */
    private const val WEATHER_NOISE_C = 3.5f

    /**
     * Cycles of that noise across the map in each direction, which is also the noise's tiling
     * period — so the pattern meets itself at the map's east-west seam instead of showing a join.
     */
    private const val WEATHER_NOISE_CYCLES = 5

    /** Octaves of it. Four is enough for a ragged isotherm and no more than the eye can see. */
    private const val WEATHER_NOISE_OCTAVES = 4

    /**
     * How far inland the sea's own year reaches, in kilometres: the e-folding length of
     * [marineAirFraction].
     *
     * 350 km, read off Earth's own continentality at 50-56 degrees north, where the annual range
     * against distance to the nearest coast is about as clean a measurement as geography offers.
     * Valentia on the Irish coast swings 8 C over the year; Berlin, 190 km from the Baltic, swings
     * 19; Warsaw at 330 km, 22; Moscow at about 650 km, 28; and a fully continental interior there
     * swings 32-34. Fitting `range = maritime + (continental - maritime)(1 - exp(-distance/L))`
     * to those three inland stations gives L = 310, 377 and 363 km — agreeing to a tenth, which is
     * what says the exponential is the right shape and not merely a curve with a spare parameter.
     *
     * The exponential matters as much as the number. A linear ramp to a fully continental line, the
     * shape this had before the model replaced it, makes the whole first half of a continent
     * uniformly half-maritime; the observed fall-off is steep at the coast and long in the tail,
     * which is why a hundred kilometres inland is a different climate and a thousand more is not.
     */
    private const val MARINE_REACH_KM = 350f

    /**
     * Where the trade-wind belt gives way to the westerlies, in degrees from the thermal equator —
     * the edge of the Hadley cell's surface leg. [OceanStage] reads its wind stress off the same
     * boundary, because it is the same circulation.
     */
    private const val TRADE_BELT_EDGE_DEGREES = 30f

    /** Where the westerlies give way to the polar easterlies: the edge of the Ferrel cell. */
    private const val WESTERLY_BELT_EDGE_DEGREES = 60f

    // The four bumps of [latitudeBandAt]'s rain-rate profile, each placed where the atmosphere
    // actually puts it and each as wide as that feature really is. Read from the *thermal*
    // equator, so the whole profile migrates with the season. A strength above zero encourages
    // rain and below zero suppresses it; the subtropical high's strength is
    // `ClimateConfig.subtropicalDryness`, because how arid a world's horse latitudes are is the
    // one thing here worth a setting.

    /** The rising limb of the Hadley cell, where the trades meet: the wettest belt on Earth. */
    private const val ITCZ_STRENGTH = 1.0f
    private const val ITCZ_DEGREES = 0f
    private const val ITCZ_WIDTH_DEGREES = 12f

    /** Descending, drying air: the horse latitudes, and every subtropical desert on Earth. */
    private const val SUBTROPICAL_HIGH_DEGREES = 30f
    private const val SUBTROPICAL_HIGH_WIDTH_DEGREES = 13f

    /** The mid-latitude storm track, along the polar front. Half the ITCZ's strength. */
    private const val STORM_TRACK_STRENGTH = 0.5f
    private const val STORM_TRACK_DEGREES = 55f
    private const val STORM_TRACK_WIDTH_DEGREES = 15f

    /** The polar cell's descending air. A polar desert is a real thing, but a mild one. */
    private const val POLAR_DRY_STRENGTH = 0.35f
    private const val POLAR_DRY_WIDTH_DEGREES = 18f

    /**
     * The least a circulation belt may scale the rain rate by.
     *
     * Not merely a guard against nonsense: at the default dryness the profile goes negative across
     * roughly 25 to 35 degrees, so that whole span is clamped here and is maximally arid — which
     * is a fair description of a subtropical desert belt, and is why raising the dryness setting
     * widens the belt rather than deepening it.
     */
    private const val MIN_BAND = 0.05f

    /**
     * The most [seasonalBandSharpness] may sharpen a season's belts by.
     *
     * The ratio it computes runs away as the tilt approaches the width of the subtropical high,
     * where the two offset bells barely overlap; four is well past any tilt a world would be given
     * and keeps a nonsensical setting from erasing the belts entirely.
     */
    private const val MAX_BAND_SHARPNESS = 4f

    /**
     * Map width the rain blur's radius is one cell at; it grows in proportion above that.
     *
     * Tied to the grid rather than fixed so that a world looks the same at every resolution
     * instead of smoother at the coarse ones — the blur is softening the march's column-by-column
     * steps into weather, and a step is one cell wide whatever the cell stands for.
     */
    private const val RAIN_BLUR_REFERENCE_WIDTH = 128

    /**
     * Moisture each air mass starts the march with, as a fraction of saturation.
     *
     * Half, and it does not matter: the first of [MARCH_LAPS] exists precisely to wash this value
     * out before anything is recorded.
     */
    private const val INITIAL_MOISTURE = 0.5f

    /**
     * Times each air mass goes round the cylinder.
     *
     * Three. The first carries [INITIAL_MOISTURE] away and leaves every parcel holding what its
     * fetch actually gives it; the second leaves a rainfall on the ground for the third to read
     * the soil's wetness off, which is what [MoistureBudget.groundWetness] needs and what no lap
     * before it could supply; the last is the one recorded. Two laps was enough while the ground
     * gave water back at a rate that did not depend on how wet it was.
     */
    private const val MARCH_LAPS = 3

    /**
     * Temperature at which the evaporative-warmth ramp reaches zero, in degrees Celsius, and how
     * many degrees above that it takes to reach one.
     *
     * Nothing evaporates from ice, and the ramp is full at a warm-temperate 30 C. Above that it
     * keeps climbing to [MAX_WARMTH] rather than flattening, because a tropical sea really does
     * give up more water than a temperate one.
     */
    private const val WARMTH_ZERO_C = -10f
    private const val WARMTH_SPAN_C = 40f

    /** The most that ramp may reach, at roughly 46 C — hotter than any sea surface. */
    private const val MAX_WARMTH = 1.4f

    /**
     * Temperature at which the cold cap on moisture bottoms out, in degrees Celsius, and how many
     * degrees above that it takes to lift entirely.
     *
     * Saturation vapour pressure falls away with temperature, so cold air simply cannot hold much
     * water however wet the ground beneath it is. This is that fact as a straight ramp rather than
     * as Clausius-Clapeyron, which the march has no absolute humidity to feed.
     */
    private const val COLD_CAP_ZERO_C = -25f
    private const val COLD_CAP_SPAN_C = 45f

    /** The least that cap may fall to, so the coldest air still carries a trace of snow. */
    private const val MIN_COLD_CAP = 0.15f

    /**
     * Bins [landPercentile] buckets the land's rainfall into.
     *
     * The bin width is the resolution of the answer, and the answer is a calibration figure rather
     * than anything the pipeline reads, so a couple of thousand bins is ample and the histogram
     * still fits in a few kilobytes.
     */
    private const val PERCENTILE_BINS = 2048

    /**
     * Depth, in `SeaLevelResult.relativeElevation` units, above which water is drawn as shallow.
     *
     * Deeper than `SeaLevelStage`'s shelf so that the whole continental shelf reads as shallow
     * water rather than only its inshore half.
     */
    private const val SHALLOW_OCEAN_DEPTH = -0.12f

    /**
     * The annual mean below which land is an ice sheet when `ClimateConfig.snowBalance` is off.
     *
     * A poor rule — it cannot see rainfall, and an ice sheet is made of snow — but it is the world
     * this generator produced before the balance existed, so it is kept exactly as the control the
     * balance is measured against. See docs/DESIGN_LEDGER.md, H2.
     */
    private const val ANNUAL_MEAN_ICE_C = -8f

    /**
     * Elevation, in `SeaLevelResult.relativeElevation` units, above which land is bare alpine rock
     * whatever its climate says.
     *
     * High ground is its own biome: at 0.72 of the land's relief the lapse rate has already taken
     * some 25 C off the valley below, and what grows there is decided by the rock and the wind
     * rather than by the latitude.
     */
    private const val ALPINE_ELEVATION = 0.72f

    /**
     * Where Koppen splits true desert (BW) from steppe (BS): half the aridity threshold, which is
     * Koppen's own rule and not a figure of this model's.
     */
    private const val DESERT_SHARE_OF_ARIDITY = 0.5f

    /** Koppen's A: a coldest month at or above this has no winter at all, and is tropical. */
    private const val TROPICAL_COLDEST_C = 18f

    /** Koppen's ET: a warmest month below this never has a growing season, and is tundra. */
    private const val TREE_LINE_WARMEST_C = 10f

    /** Koppen's D: a coldest month at or below this has secure winter snow cover. */
    private const val CONTINENTAL_COLDEST_C = -3f

    /** Warm-to-cold-season rainfall ratio at which one season is doing nearly all the work. */
    private const val MONSOON_SUMMER_RATIO = 2.5f

    /** The gentler ratio at which a tropical woodland thins into grass with scattered trees. */
    private const val SAVANNA_SUMMER_RATIO = 1.6f

    /** Cold-to-warm-season ratio a dry-summer coast needs to read as Mediterranean. */
    private const val MEDITERRANEAN_WINTER_RATIO = 1.7f

    /**
     * The mildest winter a Mediterranean coast may have and still be one: above freezing by a
     * margin, so the wet season falls as rain rather than as snow.
     */
    private const val MEDITERRANEAN_COLDEST_C = 2f

    /**
     * Koppen's own temperature term in the aridity threshold, in millimetres per degree Celsius of
     * annual mean.
     *
     * His formula is `2T` centimetres, so twenty millimetres a degree. It is a warmth-to-
     * evaporation proxy, which is why the threshold goes negative in a genuinely polar climate and
     * no rainfall total can read as arid there.
     */
    private const val KOPPEN_ARIDITY_PER_DEGREE_C = 20f

    /**
     * A [ClimateResult] together with the transient seasonal mm fields that fed [classify] but
     * are not worth a place in the saved world — nothing downstream of biome classification reads
     * the seasonal split once biome is decided, so keeping them here rather than on
     * [ClimateResult] is what keeps the save format from growing a field with no reader.
     *
     * Exists so `AbsoluteRainfallTest` can re-measure the monsoon claim without a clamp, sharing
     * this function's one computation of the march rather than duplicating it.
     */
    internal class Generated(
        val result: ClimateResult,
        val summerPrecipitationMm: FloatField,
        val winterPrecipitationMm: FloatField,
        /**
         * Annual rainfall whose water last evaporated from land rather than from the sea, in
         * millimetres: the numerator of the continental recycling ratio. Measured rather than
         * read, which is why it stops here rather than going on to [ClimateResult] and the save.
         */
        val landOriginPrecipitationMm: FloatField
    )

    /**
     * The finished climate of a world: three temperature fields in degrees Celsius, four rainfall
     * fields (three normalised to 0..1 against [REFERENCE_MM], one in millimetres a year), the
     * annual wind as a vector, and a biome per cell.
     *
     * [sea] supplies the land mask and the relative elevation the lapse rate and the orographic
     * lift are read off; [ocean] supplies the sea-surface temperature and the current anomaly that
     * decide how much moisture the march picks up over water and how mild the coast beside it is.
     * Every field is one entry per cell, row-major, at `config.width` by `config.height`.
     */
    fun generate(config: WorldGenConfig, sea: SeaLevelResult, ocean: OceanResult): ClimateResult =
        generateWithSeasonalMm(config, sea, ocean).result

    /**
     * Everything this stage computes before the biomes: the three temperature fields, the two
     * seasonal rainfall marches in millimetres, and the annual wind.
     *
     * Split out of [generateWithSeasonalMm] because [GlaciationStage] needs the same fields two
     * stages earlier than this stage runs — its mask is a snow balance, and a snow balance is a
     * question about temperature and rainfall in each half of the year. Sharing the computation
     * rather than restating it is the same discipline [buildTemperature] was made `internal` for:
     * a provisional climate that disagreed with the real one about where the snow falls would put
     * troughs where the finished map shows none.
     */
    private class SeasonalFields(
        val temperature: FloatField,
        /** The warmest and coldest month, which is what Koppen's thresholds are stated on. */
        val summerTemperature: FloatField,
        val winterTemperature: FloatField,
        /**
         * The warm and cold half-years' means, which is what anything integrating across a season
         * needs — the snow balance's degree-day sum and the moisture march's evaporation. Not
         * saved: `ClimateResult` carries the months, because those are what a reader and a
         * classifier want. See [Season].
         */
        val warmHalfTemperature: FloatField,
        val coldHalfTemperature: FloatField,
        val summerPrecipitationMm: FloatField,
        val winterPrecipitationMm: FloatField,
        val summerSeaIce: BooleanArray,
        val winterSeaIce: BooleanArray,
        val wind: WindField,
        val landOriginPrecipitationMm: FloatField
    )

    /**
     * The snow balance for a world whose climate has not been computed yet, in millimetres of
     * water equivalent a year — [SnowBalance]'s field, run on a full provisional march over the
     * terrain as it stands before the ice has carved it.
     *
     * This is what [GlaciationStage] freezes on. It costs one extra run of this stage's own
     * machinery and nothing else: the same energy balance, the same maritime and current
     * anomalies, the same two seasonal marches. The alternative — a bare latitude-and-altitude
     * annual mean at or below zero — could not see rainfall at all, and rainfall is half of what
     * decides where a glacier is. See docs/DESIGN_LEDGER.md, H2, for the measured cost.
     *
     * [globalCoolingC] is not a shift applied to the finished field. It goes in as a forcing — a
     * dimmer sun — and the model answers with a colder world of its own, ice and all, so the
     * cooling comes out polar-amplified because the poles turn white and not because anybody wrote
     * a latitude ramp. The rainfall of that colder world is the march's answer to it too, so the
     * glacial mask is no longer generous by leaving a cold world as wet as a warm one.
     */
    internal fun provisionalSnowBalance(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        ocean: OceanResult,
        /** See `GlaciationConfig.glacialMaximumC`: the ice that carved the ground is not today's. */
        globalCoolingC: Float = config.glaciation.glacialMaximumC
    ): FloatField {
        val fields = seasonalFields(config, sea, ocean, globalCoolingC)
        return SnowBalance.field(
            sea.isLand,
            fields.warmHalfTemperature,
            fields.coldHalfTemperature,
            fields.summerPrecipitationMm,
            fields.winterPrecipitationMm
        )
    }

    internal fun generateWithSeasonalMm(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        ocean: OceanResult
    ): Generated {
        val cellsAcross = config.width
        val cellsDown = config.height
        val cellCount = cellsAcross * cellsDown

        val fields = seasonalFields(config, sea, ocean)
        val temperature = fields.temperature
        val summerTemperature = fields.summerTemperature
        val winterTemperature = fields.winterTemperature
        val summerPrecipitationMm = fields.summerPrecipitationMm
        val winterPrecipitationMm = fields.winterPrecipitationMm

        val precipitationMm = FloatField(cellsAcross, cellsDown)
        for (cell in 0 until cellCount) {
            precipitationMm.data[cell] =
                (summerPrecipitationMm.data[cell] + winterPrecipitationMm.data[cell]) * 0.5f
        }

        // The 0..1 copy the stage's consumers were built against: rendering, CultureStage's
        // climate distance, RiverStage's and NationStage's runoff weighting, and classify's own
        // seasonal-shape ratios. One fixed factor for all three fields, so they can be compared
        // with each other: REFERENCE_MM maps to 1, clamped.
        val precipitation = FloatField(cellsAcross, cellsDown)
        val summerPrecipitation = FloatField(cellsAcross, cellsDown)
        val winterPrecipitation = FloatField(cellsAcross, cellsDown)
        for (cell in 0 until cellCount) {
            precipitation.data[cell] = (precipitationMm.data[cell] / REFERENCE_MM).coerceIn(0f, 1f)
            summerPrecipitation.data[cell] =
                (summerPrecipitationMm.data[cell] / REFERENCE_MM).coerceIn(0f, 1f)
            winterPrecipitation.data[cell] =
                (winterPrecipitationMm.data[cell] / REFERENCE_MM).coerceIn(0f, 1f)
        }

        // Recomputed here rather than carried down from the glaciation stage's provisional run:
        // that one was measured on the terrain before the ice cut it, and this one has to agree
        // with the map the reader is looking at. Cheap enough that sharing it would be a false
        // economy — see [SnowBalanceAccelerator].
        val snowBalance = if (config.climate.snowBalance) {
            SnowBalance.field(
                sea.isLand, fields.warmHalfTemperature, fields.coldHalfTemperature,
                summerPrecipitationMm, winterPrecipitationMm
            )
        } else null

        val biome = classify(
            cellsAcross, cellsDown, sea, temperature, summerTemperature, winterTemperature,
            precipitationMm, summerPrecipitationMm, winterPrecipitationMm,
            fields.summerSeaIce, snowBalance
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
                windMeridional = FloatField(cellsAcross, cellsDown, fields.wind.meridional),
                summerSeaIce = fields.summerSeaIce,
                winterSeaIce = fields.winterSeaIce,
                biome = biome
            ),
            summerPrecipitationMm = summerPrecipitationMm,
            winterPrecipitationMm = winterPrecipitationMm,
            landOriginPrecipitationMm = fields.landOriginPrecipitationMm
        )
    }

    private fun seasonalFields(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        ocean: OceanResult,
        /** The glacial forcing, in degrees of global mean; zero is today's world. */
        globalCoolingC: Float = 0f
    ): SeasonalFields {
        val cellsAcross = config.width
        val cellsDown = config.height
        val climateConfig = config.climate

        // One knob, used by the belts below. Switching seasons off is exactly a tilt of zero: the
        // rain belts then stop migrating and the planet's axis stands upright, so the energy
        // balance has no seasonal forcing either and both seasonal fields collapse onto the annual
        // one.
        val tiltDegrees =
            if (climateConfig.seasons) climateConfig.seasonalTiltDegrees else 0f

        val zonal = zonalClimate(config, sea, globalCoolingC)
        // Two fields that both ask "how close is the sea", because they need different answers to
        // it. The marine fraction wants an honest distance, because it decides how much of a
        // band's oceanic year a cell takes and that reaches hundreds of kilometres inland. The
        // maritime-influence term below wants a fast-fading field so that one current's anomaly
        // does not leak across a whole continent — the blurred exposure, which runs out at the
        // coastal reach.
        val marineFraction = marineAirFraction(config, sea)
        val temperature = annualTemperature(config, sea, zonal, marineFraction)
        applyMaritimeInfluence(config, sea, ocean, temperature, waterExposure(config, sea))
        // Two readings of the same year. The months are what `classify` gates on, because Koppen's
        // thresholds are monthly means; the half-years are what the snow balance and the moisture
        // march integrate across, because a degree-day sum over 182 days wants those 182 days'
        // mean and not the peak of July. See `Season`.
        val summerTemperature =
            seasonalTemperature(config, temperature, zonal, marineFraction, Season.SUMMER)
        val winterTemperature =
            seasonalTemperature(config, temperature, zonal, marineFraction, Season.WINTER)
        val warmHalfTemperature =
            seasonalTemperature(config, temperature, zonal, marineFraction, Season.WARM_HALF)
        val coldHalfTemperature =
            seasonalTemperature(config, temperature, zonal, marineFraction, Season.COLD_HALF)

        // The water under the marine air, half-year by half-year. The ice test and the march's
        // evaporation are questions about the sea surface, not about the air over it: a sea
        // freezes when the water reaches -1.8, and what evaporates is water.
        val warmHalfSeaSurface =
            seaSurfaceTemperature(config, sea, zonal, warmHalfTemperature, Season.WARM_HALF)
        val coldHalfSeaSurface =
            seaSurfaceTemperature(config, sea, zonal, coldHalfTemperature, Season.COLD_HALF)

        val summerSeaIce = seaIceMask(config, sea, ocean, warmHalfSeaSurface)
        val winterSeaIce = seaIceMask(config, sea, ocean, coldHalfSeaSurface)

        // A pressure field per season, and a third for the annual wind. The annual one is built
        // from the annual temperature rather than averaged from the other two, which is the same
        // field: the anomaly is linear in temperature and the wind is linear in the anomaly, so
        // the wind from the mean pressure and the mean of the two winds are one answer, and this
        // is the cheaper way to get it. Null when the pressure term is off, which is what makes
        // that setting a control rather than a near-miss.
        val pressureWinds = climateConfig.pressureWinds
        val annualPressureHpa =
            if (pressureWinds) PressureWind.pressureAnomalyHpa(config, temperature) else null
        val summerPressureHpa = if (pressureWinds) {
            PressureWind.pressureAnomalyHpa(config, warmHalfTemperature)
        } else null
        val winterPressureHpa = if (pressureWinds) {
            PressureWind.pressureAnomalyHpa(config, coldHalfTemperature)
        } else null

        // The stored wind is the annual one, unshifted: it is what the rest of the pipeline and
        // the wind view mean by "the prevailing wind". Each season marches along its own belts,
        // which live only as long as the march does.
        val slantRowsPerCell = climateConfig.meridionalWind
        val wind = withPressureDeparture(
            config, sea,
            buildWind(cellsAcross, cellsDown, tiltDegrees = 0f, warm = true, slantRowsPerCell),
            annualPressureHpa
        ).march

        // The two terms this stage hands the march that are properties of the season rather than
        // of the parcel: where the regional wind gathers air, and where a cold sea has put a
        // stratus lid on it. Both are null when their setting is off, so the march runs the
        // arithmetic it ran before rather than the same arithmetic with a zero in it.
        val summerWind = withPressureDeparture(
            config, sea,
            buildWind(cellsAcross, cellsDown, tiltDegrees, warm = true, slantRowsPerCell),
            summerPressureHpa
        )
        val winterWind = withPressureDeparture(
            config, sea,
            buildWind(cellsAcross, cellsDown, tiltDegrees, warm = false, slantRowsPerCell),
            winterPressureHpa
        )
        val summerConvergence = if (climateConfig.convergenceRain) {
            MoistureBudget.convergencePerCell(config, summerWind.departureMps)
        } else null
        val winterConvergence = if (climateConfig.convergenceRain) {
            MoistureBudget.convergencePerCell(config, winterWind.departureMps)
        } else null
        val seaSurfaceAnomalyC =
            if (climateConfig.marineInversion && config.ocean.enabled) ocean.anomaly else null
        val summerInversion = MoistureBudget.inversionSuppression(
            config, sea, seaSurfaceAnomalyC, tiltDegrees, warm = true
        )
        val winterInversion = MoistureBudget.inversionSuppression(
            config, sea, seaSurfaceAnomalyC, tiltDegrees, warm = false
        )

        // Where each season's rain came from: the share of it whose water last evaporated from
        // land rather than from the sea. Measured, never read back by the march - see
        // docs/DESIGN_LEDGER.md, W3, and `MoistureRecyclingTest`.
        val summerLandOrigin = FloatField(cellsAcross, cellsDown)
        val winterLandOrigin = FloatField(cellsAcross, cellsDown)

        // Raw march output, in the model's own units — not yet mm, not yet clamped. Kept apart
        // from the mm fields below because [MM_SCALE] is the only place that unit conversion
        // happens, and nothing else should need to know what the march's native units are.
        val summerRaw = buildPrecipitation(
            config, sea, warmHalfTemperature, warmHalfSeaSurface, summerSeaIce, summerWind.march,
            ocean, bands(cellsDown, climateConfig, warm = true),
            summerConvergence, summerInversion, summerLandOrigin
        )
        val winterRaw = buildPrecipitation(
            config, sea, coldHalfTemperature, coldHalfSeaSurface, winterSeaIce, winterWind.march,
            ocean, bands(cellsDown, climateConfig, warm = false),
            winterConvergence, winterInversion, winterLandOrigin
        )

        // mm/year, by the one conversion factor the whole model uses. Unclamped: this is what
        // classify reads, and an extreme windward cell losing its extremity to a clamp is the very
        // bug the absolute-millimetre field exists to remove. The annual field is the mean of the
        // two seasonal marches rather than a third march of its own, so that turning seasons off
        // leaves it identical to the single march it replaced.
        val summerPrecipitationMm = FloatField(cellsAcross, cellsDown)
        val winterPrecipitationMm = FloatField(cellsAcross, cellsDown)
        val landOriginPrecipitationMm = FloatField(cellsAcross, cellsDown)
        for (cell in 0 until cellsAcross * cellsDown) {
            summerPrecipitationMm.data[cell] = summerRaw.data[cell] * MM_SCALE
            winterPrecipitationMm.data[cell] = winterRaw.data[cell] * MM_SCALE
            landOriginPrecipitationMm.data[cell] =
                (summerLandOrigin.data[cell] + winterLandOrigin.data[cell]) * 0.5f * MM_SCALE
        }

        return SeasonalFields(
            temperature = temperature,
            summerTemperature = summerTemperature,
            winterTemperature = winterTemperature,
            warmHalfTemperature = warmHalfTemperature,
            coldHalfTemperature = coldHalfTemperature,
            summerPrecipitationMm = summerPrecipitationMm,
            winterPrecipitationMm = winterPrecipitationMm,
            summerSeaIce = summerSeaIce,
            winterSeaIce = winterSeaIce,
            wind = wind,
            landOriginPrecipitationMm = landOriginPrecipitationMm
        )
    }

    /**
     * The **water** temperature in a season, over every sea cell: the mixed layer under the marine
     * air, which is what freezes and what evaporates.
     *
     * Written as a departure from [seasonAirTemperature] rather than built afresh, so that the
     * weather noise and everything else the air field carries stay identical between the two —
     * the only difference between them is the one the energy balance puts there, the gap between
     * a band's marine air and the fifty metres of water under it. On land the two are the same
     * number, because there is no water there and nothing reads it.
     *
     * See [EnergyBalance]'s marine-air heat capacity for why the sea has two temperatures at all.
     */
    internal fun seaSurfaceTemperature(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        zonal: ZonalClimate,
        seasonAirTemperature: FloatField,
        season: Season
    ): FloatField {
        val cellsAcross = config.width
        val cellsDown = config.height
        val field = FloatField(cellsAcross, cellsDown)
        parallelChunks(0, cellsDown) { startRow, endRow ->
            for (row in startRow until endRow) {
                val latitude = latitudeOf(row, cellsDown)
                val waterAboveAirC = zonal.waterC(latitude, season) - zonal.seaC(latitude, season)
                for (column in 0 until cellsAcross) {
                    val cell = row * cellsAcross + column
                    field.data[cell] = seasonAirTemperature.data[cell] +
                        if (sea.isLand[cell]) 0f else waterAboveAirC
                }
            }
        }
        return field
    }

    /**
     * Where the sea is frozen in the season [seaSurface] belongs to: true on a water cell whose
     * **water** sits at or below [EnergyBalance.SEA_FREEZING_C], false on land.
     *
     * The freezing test is a question about the sea surface, not the air over it: -1.8 C is where
     * water of the ocean's mean salinity turns to ice, and the marine air above a freezing sea is
     * colder than that all winter without the sea being frozen. Add the current anomaly the ocean
     * stage measured — so a warm current keeps a polar sea open where its latitude alone would
     * freeze it, which is the Norwegian Sea, and a cold one closes a sea further from the pole,
     * which is the Labrador. The march reads the same water field, which is what keeps the mask
     * and the march from disagreeing about which cells are ice.
     *
     * Empty when `ClimateConfig.seaIce` is off, which is the control the guard needs.
     * See [ClimateResult.summerSeaIce] for what the two masks are for.
     */
    private fun seaIceMask(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        ocean: OceanResult,
        seaSurface: FloatField
    ): BooleanArray {
        val frozen = BooleanArray(config.width * config.height)
        if (!config.climate.seaIce) return frozen
        for (cell in frozen.indices) {
            if (sea.isLand[cell]) continue
            val anomalyC = if (config.ocean.enabled) ocean.anomaly.data[cell] else 0f
            frozen[cell] = seaSurface.data[cell] + anomalyC <= EnergyBalance.SEA_FREEZING_C
        }
        return frozen
    }

    /**
     * How much nearby water a land cell can feel: 1 in the open sea, fading to 0 over
     * `OceanConfig.coastalReachCells` cells inland.
     *
     * A blur of the land/sea mask rather than a distance transform — cheap, and it does what
     * [applyMaritimeInfluence] needs: land within reach of the coast reads high, land well beyond
     * it reads exactly 0 once the blur's support runs out. The marine blend needs the other
     * question answered — see [waterDistance] and [marineAirFraction].
     */
    private fun waterExposure(config: WorldGenConfig, sea: SeaLevelResult): FloatField {
        val cellsAcross = config.width
        val cellsDown = config.height
        val radiusCells = config.ocean.coastalReachCells.coerceAtLeast(1)

        val exposure = FloatField(cellsAcross, cellsDown)
        for (cell in 0 until cellsAcross * cellsDown) {
            exposure.data[cell] = if (sea.isLand[cell]) 0f else 1f
        }
        BoxBlur.apply(exposure, radius = radiusCells, passes = BLUR_PASSES)
        for (cell in 0 until cellsAcross * cellsDown) {
            exposure.data[cell] = exposure.data[cell].coerceIn(0f, 1f)
        }
        return exposure
    }

    /**
     * Distance in cells from every cell to the nearest sea cell — zero over water — by the same
     * jump-flooded Euclidean distance field `SeaLevelStage` uses for the continental shelf: a
     * handful of passes, and a true straight-line distance rather than the best an eight-direction
     * walk can do.
     *
     * [marineAirFraction] reads this rather than the blurred water exposure above, because "how
     * exposed to water" and "how close to water" are not the same question at this radius: two
     * box-blur passes leave a cell right at the edge of `coastalReachCells` reading roughly 0.2
     * exposure, which would make a coast three quarters of the way to fully continental. An honest
     * distance says a shoreline cell is half a cell from water and one at 350 km is exactly that,
     * which is what a decay length measured in kilometres needs.
     *
     * Half a cell, and the correction matters. The jump flood measures centre to centre, so a cell
     * whose own edge is the shoreline comes back a whole cell from the sea — 78 km on the 512-wide
     * grid and 39 on the 1024 — which made the same coast four fifths maritime at one resolution
     * and nine tenths at the other, and put a fifth of a continental interior's winter onto every
     * shoreline in the world at the coarse one. The water starts halfway between the two centres,
     * so half a cell comes off every distance and a shoreline reads as a shoreline.
     *
     * Internal rather than private so `ContinentalityTest` measures the same field the stage
     * actually used instead of re-deriving it and risking the two drifting apart.
     * See docs/DESIGN_LEDGER.md, A2, G4 and W1.
     */
    internal fun waterDistance(config: WorldGenConfig, sea: SeaLevelResult): FloatField {
        val cellsAcross = config.width
        val cellsDown = config.height
        val distanceToWater = FloatArray(cellsAcross * cellsDown) { JumpFloodDistance.INFINITE }
        val nearestWaterCell = IntArray(cellsAcross * cellsDown) { -1 }
        for (cell in 0 until cellsAcross * cellsDown) {
            if (!sea.isLand[cell]) {
                distanceToWater[cell] = 0f
                nearestWaterCell[cell] = cell
            }
        }
        // A world with no water at all leaves every distance at INFINITE, which is exactly right:
        // the marine fraction falls to zero everywhere and every cell is fully continental.
        JumpFloodDistance.run(cellsAcross, cellsDown, distanceToWater, nearestWaterCell)
        val field = FloatField(cellsAcross, cellsDown)
        for (cell in distanceToWater.indices) {
            val centreToCentre = distanceToWater[cell]
            field.data[cell] =
                if (centreToCentre == JumpFloodDistance.INFINITE) centreToCentre
                else (centreToCentre - HALF_A_CELL).coerceAtLeast(0f)
        }
        return field
    }

    /**
     * The half cell that separates a land cell's centre from the shoreline when the sea starts in
     * the next cell along. See [waterDistance].
     */
    private const val HALF_A_CELL = 0.5f

    /**
     * Lets a coast feel the water beside it.
     *
     * The sea anomaly is spread inland with a blur and added to land temperature, so a shore
     * washed by warm water is milder than its latitude and one beside a cold current is colder.
     * This is the difference between Bergen and Labrador, which sit at the same latitude.
     *
     * The anomaly field is zero on land, so the blur mixes the sea's departure with a great many
     * zeros and the exposure attenuates it a second time: a shoreline ends up with about a quarter
     * of the anomaly of the water it looks out on, and `ColdCapReportTest` reads +1.6 C offshore
     * against a coast that was given nearer +0.4. W1 tried the corrected form — the blurred anomaly
     * divided by the blurred water mask, which is the mean anomaly of the sea cells within reach,
     * decayed inland by [marineAirFraction] instead of by the exposure a second time — and it made
     * the coasts *worse*, because a ten-cell disc of sea around a narrow warm tongue is mostly not
     * that tongue: seed 42's warm west coasts went from 57% temperate forest to 43%. The
     * attenuation is standing in for a neighbourhood that is too wide and too round, and the honest
     * fix is a directed one — the water upwind — which needs W2's surface winds. Left as it is,
     * with the finding written down.
     */
    private fun applyMaritimeInfluence(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        ocean: OceanResult,
        temperature: FloatField,
        exposure: FloatField
    ) {
        val oceanConfig = config.ocean
        if (!oceanConfig.enabled || oceanConfig.coastalInfluence <= 0f) return
        val cellsAcross = config.width
        val cellsDown = config.height

        // Spread the offshore anomaly over the land it touches.
        val spreadAnomaly = FloatField(cellsAcross, cellsDown)
        ocean.anomaly.data.copyInto(spreadAnomaly.data)
        BoxBlur.apply(
            spreadAnomaly,
            radius = oceanConfig.coastalReachCells.coerceAtLeast(1),
            passes = BLUR_PASSES
        )

        parallelChunks(0, cellsAcross * cellsDown) { startCell, endCell ->
            for (cell in startCell until endCell) {
                if (!sea.isLand[cell]) continue
                temperature.data[cell] +=
                    spreadAnomaly.data[cell] * exposure.data[cell] * oceanConfig.coastalInfluence
            }
        }
    }

    /**
     * A normalised bump centred on [centreDegrees], [widthDegrees] degrees wide: 1 at the centre,
     * falling to `1/e` one width away from it.
     */
    private fun bell(latitude: Float, centreDegrees: Float, widthDegrees: Float): Float {
        val widthsFromCentre = (latitude - centreDegrees) / widthDegrees
        return kotlin.math.exp(-(widthsFromCentre * widthsFromCentre).toDouble()).toFloat()
    }

    /**
     * Latitude in degrees at the centre of a row of an equirectangular map: +90 at the top,
     * -90 at the bottom. [rows] is the map's full height in cells.
     */
    fun latitudeOf(row: Int, rows: Int): Float =
        POLE_DEGREES - POLE_TO_POLE_DEGREES * (row + 0.5f) / rows

    /**
     * The world's own zonal climate: [EnergyBalance] solved for this map's land against latitude,
     * this world's obliquity and this world's greenhouse.
     *
     * Internal rather than private because [OceanStage] reads it too — the sea-surface temperature
     * its currents carry has to be the same temperature this stage puts on the map, or the two
     * would disagree about how warm a latitude is, which is the class of bug S1 spent a chunk
     * ending. It is solved rather than cached: forty milliseconds, independent of the grid, and
     * two stages that each solve it cannot go stale against one another.
     *
     * [globalCoolingC] is the glacial forcing, in degrees of global mean; zero is today's world.
     * See `GlaciationConfig.glacialMaximumC` and [EnergyBalance.solarScaleForCooling].
     */
    internal fun zonalClimate(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        globalCoolingC: Float = 0f
    ): ZonalClimate {
        val landFraction =
            EnergyBalance.landFractionByBand(config.width, config.height, sea.isLand)
        val obliquityDegrees = EnergyBalance.obliquityDegrees(
            if (config.climate.seasons) config.climate.seasonalTiltDegrees else 0f
        )
        val greenhouseW = EnergyBalance.outgoingOffsetForShift(
            landFraction, obliquityDegrees, config.climate.globalMeanShiftC
        )
        val solarScale = EnergyBalance.solarScaleForCooling(
            landFraction, obliquityDegrees, globalCoolingC, greenhouseW
        )
        return EnergyBalance.solve(landFraction, obliquityDegrees, solarScale, greenhouseW)
    }

    /**
     * How much of the air over each cell is of marine origin: 1 over water and on the shore,
     * fading inland with [MARINE_REACH_KM].
     *
     * This is what carries a band's two columns onto a two-dimensional map. The model gives each
     * latitude a maritime year and a continental one; a cell takes a blend of them, and this is
     * the weight. A shoreline cell is almost entirely marine air and gets the ocean's small swing;
     * a cell a thousand kilometres inland is almost entirely continental and gets the full one.
     *
     * Distance to the nearest water in any direction, not fetch along the wind. Which quarter the
     * air comes from is the wind's business and the wind is prescribed belts until W2 solves it
     * from pressure; measuring it from the nearest coast is the honest approximation until then,
     * and it is what the Earth figures behind [MARINE_REACH_KM] were read against.
     *
     * Internal rather than private so `ContinentalityTest` measures the same field the stage used
     * instead of re-deriving it and risking the two drifting apart.
     */
    internal fun marineAirFraction(config: WorldGenConfig, sea: SeaLevelResult): FloatField {
        val distanceToWater = waterDistance(config, sea)
        val reachCells = config.cellsFor(MARINE_REACH_KM.toDouble()).coerceAtLeast(1f)
        val field = FloatField(config.width, config.height)
        for (cell in field.data.indices) {
            field.data[cell] = if (sea.isLand[cell]) {
                exp(-(distanceToWater.data[cell] / reachCells).toDouble()).toFloat()
            } else {
                1f
            }
        }
        return field
    }

    /**
     * The band's temperature at a cell, in degrees Celsius: its maritime and continental columns
     * blended by how much of the air there came off the sea.
     */
    private fun blendedC(marineFraction: Float, seaColumnC: Float, landColumnC: Float): Float =
        landColumnC + (seaColumnC - landColumnC) * marineFraction

    /**
     * Mean annual temperature from the solved zonal climate and altitude, before the currents have
     * their say.
     *
     * Internal rather than private because [GlaciationStage] needs the same answer two stages
     * earlier than this one runs. Ice has to be carved into the terrain that climate is computed
     * *from*, so glaciation cannot wait for this stage — but it must agree with it about where the
     * freezing line falls, or the troughs would end up somewhere the map never shows as frozen.
     * Sharing the function rather than copying the formula is what guarantees that.
     *
     * What glaciation therefore does not see is everything added after this call — the current
     * anomaly and the seasonal split. That is the honest limit of a provisional field and not a
     * bug: a warm current can lift a coast above freezing that this function calls frozen, so the
     * mask is very slightly generous on west-facing coasts, which is where real tidewater glaciers
     * are anyway.
     */
    internal fun buildTemperature(config: WorldGenConfig, sea: SeaLevelResult): FloatField =
        annualTemperature(
            config, sea, zonalClimate(config, sea), marineAirFraction(config, sea)
        )

    /** See [buildTemperature]; this is the same field with the shared work already done. */
    private fun annualTemperature(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        zonal: ZonalClimate,
        marineFraction: FloatField
    ): FloatField {
        val cellsAcross = config.width
        val cellsDown = config.height
        val climateConfig = config.climate
        val noise = PerlinNoise(config.seed * TEMPERATURE_NOISE_MULTIPLIER + TEMPERATURE_NOISE_OFFSET)
        val field = FloatField(cellsAcross, cellsDown)

        parallelChunks(0, cellsDown) { startRow, endRow ->
            for (row in startRow until endRow) {
                val latitude = latitudeOf(row, cellsDown)
                val landColumnC = zonal.landC(latitude, Season.ANNUAL)
                val seaColumnC = zonal.seaC(latitude, Season.ANNUAL)

                for (column in 0 until cellsAcross) {
                    val cell = row * cellsAcross + column
                    val elevation = sea.relativeElevation.data[cell]
                    val altitudeDropC = if (sea.isLand[cell]) {
                        config.scale.metresAboveShoreline(elevation) /
                            WorldScale.METRES_PER_KM * climateConfig.lapseRateCPerKm
                    } else 0f
                    val variationC = WEATHER_NOISE_C * noise.fbm(
                        column * WEATHER_NOISE_CYCLES.toFloat() / cellsAcross,
                        row * WEATHER_NOISE_CYCLES.toFloat() / cellsDown,
                        octaves = WEATHER_NOISE_OCTAVES,
                        periodX = WEATHER_NOISE_CYCLES,
                        periodY = WEATHER_NOISE_CYCLES
                    )
                    field.data[cell] =
                        blendedC(marineFraction.data[cell], seaColumnC, landColumnC) -
                            altitudeDropC + variationC
                }
            }
        }
        return field
    }

    /**
     * A half-year's mean temperature, rebuilt from a finished world's annual field.
     *
     * `ClimateResult` stores the warmest and coldest *month*, because those are what Koppen's gates
     * and a reader want, but the snow balance and the moisture march were run on the half-years —
     * see [Season]. A guard that needs to redo either of those computations asks for this rather
     * than reaching for the saved field and quietly measuring a different quantity.
     */
    internal fun halfYearTemperature(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        annual: FloatField,
        season: Season
    ): FloatField = seasonalTemperature(
        config, annual, zonalClimate(config, sea), marineAirFraction(config, sea), season
    )

    /**
     * The warm- or cold-season temperature, as a departure from the annual mean.
     *
     * Everything a season shares with the annual field — the altitude lapse, the current anomaly,
     * the noise — is already in [annual], so what is added is the band's own seasonal departure,
     * blended the same way. That is the honest way round: a mountain is no more seasonal than the
     * valley below it, it is simply colder all year.
     *
     * The size of the departure is nobody's setting. It is the heat capacities the model carries —
     * three metres of soil under an air column, against an air column over fifty metres of sea
     * water — and the world's own coastline deciding, cell by cell, how much of each a place gets.
     * Ireland and Siberia sit at the same latitude and take the same two columns; what separates
     * them is that one is a hundred kilometres from the sea and the other two thousand.
     */
    internal fun seasonalTemperature(
        config: WorldGenConfig,
        annual: FloatField,
        zonal: ZonalClimate,
        marineFraction: FloatField,
        season: Season
    ): FloatField {
        val cellsAcross = config.width
        val cellsDown = config.height
        val field = FloatField(cellsAcross, cellsDown)

        parallelChunks(0, cellsDown) { startRow, endRow ->
            for (row in startRow until endRow) {
                val latitude = latitudeOf(row, cellsDown)
                val landDepartureC =
                    zonal.landC(latitude, season) - zonal.landC(latitude, Season.ANNUAL)
                val seaDepartureC =
                    zonal.seaC(latitude, season) - zonal.seaC(latitude, Season.ANNUAL)
                for (column in 0 until cellsAcross) {
                    val cell = row * cellsAcross + column
                    field.data[cell] = annual.data[cell] +
                        blendedC(marineFraction.data[cell], seaDepartureC, landDepartureC)
                }
            }
        }
        return field
    }

    /**
     * The prevailing wind as a vector: a zonal direction of ±1 and a slant in rows per cell, both
     * per cell, plus the belt's own zonal direction per row.
     *
     * The belt direction is kept beside the cell-by-cell one because the two answer different
     * questions. [zonal] is which way the air over *this cell* is moving, which is what the march
     * follows and what the wind view draws. [beltZonal] is which way the *circulation cell* that
     * row belongs to moves, which is what partitions the map into the runs the march sweeps — and
     * that partition has to be a property of the row, not of the cell, or the wavefront would have
     * no direction to sweep in.
     */
    private class WindField(
        val zonal: IntArray,
        val meridional: FloatArray,
        val beltZonal: IntArray
    )

    /**
     * The most a wind may slant, in rows per cell of zonal travel.
     *
     * A cap on the **length of the march's step**, and that is the only thing it can honestly be.
     * The march advances one cell of *zonal* travel per step and charges that step one cell's
     * worth of rain and one cell's worth of depletion. A slant of `s` makes the step
     * `sqrt(cellWidth^2 + (s cellHeight)^2)` long on the ground, so a slant that carries the air
     * much further than a cell has the march pricing a journey of many cells at the rate of one.
     * Capping `s` at the cell's own aspect ratio holds the step at no more than `sqrt(2)` cells
     * however the grid is shaped — 2.0 rows per cell on a square 512 by 512 grid whose cells are
     * 23.4 km across and 11.7 km down, 1.0 on the 2:1 grid whose cells are square.
     *
     * Where the belt's zonal wind and the pressure field's have cancelled the ratio runs away, and
     * this is what catches it. Two earlier forms are worth recording because each was wrong in a
     * measurable direction. A flat cap of one row per cell bound on 71% of the cells between the
     * equator and 10 degrees north — where the Coriolis force vanishes and the pressure wind runs
     * straight down its own gradient — and cost those bands 8 to 9% of their rain by understating
     * how far the air had come. Lifting the cap away entirely cost them 18% instead, by letting a
     * single step reach hundreds of rows for one cell's worth of rain. See docs/DESIGN_LEDGER.md,
     * W2.
     */
    private fun maxSlantRowsPerCell(config: WorldGenConfig): Float =
        (config.scale.cellWidthKm(config.width) / config.scale.cellHeightKm(config.height))
            .toFloat()

    /**
     * Simplified three-cell circulation: polar easterlies, mid-latitude westerlies, and tropical
     * trade winds blowing east to west — each of them slanted across the latitude lines.
     *
     * The belts ride the thermal equator, so in summer they sit [tiltDegrees] poleward of their
     * annual position and in winter [tiltDegrees] equatorward. That migration is what puts a
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
        cellsAcross: Int,
        cellsDown: Int,
        tiltDegrees: Float,
        warm: Boolean,
        slantRowsPerCell: Float
    ): WindField {
        val zonal = IntArray(cellsAcross * cellsDown)
        val meridional = FloatArray(cellsAcross * cellsDown)
        val beltZonal = IntArray(cellsDown)
        for (row in 0 until cellsDown) {
            val latitude = latitudeOf(row, cellsDown)
            // Which way "poleward" points for this row, as a step in map coordinates: rows grow
            // southward, so the northern hemisphere's pole is at the smaller row number.
            val poleward = if (latitude < 0f) 1f else -1f
            // Signed distance from the thermal equator, positive poleward. Negative means the
            // thermal equator has migrated past this row, into its own hemisphere.
            val fromThermalEquator =
                if (warm) abs(latitude) - tiltDegrees else abs(latitude) + tiltDegrees
            val beltDegrees = abs(fromThermalEquator)
            // Away from the thermal equator, again as a step in map coordinates.
            val outward = if (fromThermalEquator < 0f) -poleward else poleward
            val zonalDirection = when {
                beltDegrees < TRADE_BELT_EDGE_DEGREES -> -1   // trade winds
                beltDegrees < WESTERLY_BELT_EDGE_DEGREES -> 1 // westerlies
                else -> -1                                    // polar easterlies
            }
            val slant = when {
                // The Hadley cell's surface leg, in toward the ITCZ.
                beltDegrees < TRADE_BELT_EDGE_DEGREES -> -outward
                // The Ferrel cell's, out toward the polar front.
                beltDegrees < WESTERLY_BELT_EDGE_DEGREES -> outward
                // The polar cell's, back down toward it.
                else -> -outward
            } * slantRowsPerCell
            beltZonal[row] = zonalDirection
            for (column in 0 until cellsAcross) {
                zonal[row * cellsAcross + column] = zonalDirection
                meridional[row * cellsAcross + column] = slant
            }
        }
        return WindField(zonal, meridional, beltZonal)
    }

    /**
     * The belts of [buildWind] with the pressure field's regional departure added to them.
     *
     * The belts stay the zonal mean and this is what the map's own land and sea do to it, so the
     * sum is a wind and not a second opinion. Both halves are put into metres a second to be
     * added — the belts through [PressureWind.BELT_SPEED_MPS], which is the one number a belt
     * never needed until something had to be added to it — and the total is converted straight
     * back into the pair the march reads, a zonal direction and a slant in rows.
     *
     * Passing a null [pressureHpa] returns [belts] untouched, which is how
     * `ClimateConfig.pressureWinds = false` reproduces the old wind exactly rather than
     * approximately: the arithmetic below is not merely skipped in its effect, it is not run.
     */
    private fun withPressureDeparture(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        belts: WindField,
        pressureHpa: FloatField?
    ): SeasonWind {
        if (pressureHpa == null) return SeasonWind(belts, null)
        val departure = PressureWind.surfaceWind(config, sea, pressureHpa)
        return SeasonWind(
            marchWindOf(config, totalWindMps(config, belts, departure), belts.beltZonal),
            departure
        )
    }

    /**
     * A season'''s wind as the march needs it: the pair the march reads, and the regional departure
     * on its own.
     *
     * The departure is kept apart from the total because the convergence term reads it alone — see
     * [MoistureBudget.convergencePerCell] for why the belts''' own convergence must not be counted a
     * second time. Null when the pressure term is off.
     */
    private class SeasonWind(val march: WindField, val departureMps: PressureWind.Vectors?)

    /**
     * The belts and the pressure departure added together, in metres a second: the wind itself,
     * before it is squeezed back into the direction-and-slant pair the march reads.
     *
     * Separate from [marchWindOf] because a guard that asks which way the wind blows onto a coast
     * wants a vector with a speed in it, and the pair the march reads has thrown the speed away.
     * Both come from this one function, so the guard and the march cannot be measuring different
     * winds.
     */
    private fun totalWindMps(
        config: WorldGenConfig,
        belts: WindField,
        departure: PressureWind.Vectors
    ): PressureWind.Vectors {
        val cellsAcross = config.width
        val cellsDown = config.height
        val cellHeightOverWidth =
            (config.scale.cellHeightKm(cellsDown) / config.scale.cellWidthKm(cellsAcross)).toFloat()
        val eastward = FloatArray(cellsAcross * cellsDown)
        val southward = FloatArray(cellsAcross * cellsDown)
        parallelChunks(0, cellsDown) { startRow, endRow ->
            for (cell in startRow * cellsAcross until endRow * cellsAcross) {
                eastward[cell] = belts.zonal[cell] * PressureWind.BELT_SPEED_MPS +
                    departure.eastwardMps[cell]
                southward[cell] = belts.meridional[cell] * PressureWind.BELT_SPEED_MPS *
                    cellHeightOverWidth + departure.southwardMps[cell]
            }
        }
        return PressureWind.Vectors(eastward, southward)
    }

    /** A wind in metres a second as the march reads it: a zonal direction and a slant in rows. */
    private fun marchWindOf(
        config: WorldGenConfig,
        wind: PressureWind.Vectors,
        beltZonal: IntArray
    ): WindField {
        val cellsAcross = config.width
        val cellsDown = config.height

        // A slant is rows per cell of zonal travel and a wind is metres a second in each
        // direction, and the two are the same ratio only on a grid whose cells are square.
        val cellWidthOverHeight =
            (config.scale.cellWidthKm(cellsAcross) / config.scale.cellHeightKm(cellsDown)).toFloat()

        val zonal = IntArray(cellsAcross * cellsDown)
        val meridional = FloatArray(cellsAcross * cellsDown)
        parallelChunks(0, cellsDown) { startRow, endRow ->
            for (cell in startRow * cellsAcross until endRow * cellsAcross) {
                val eastward = wind.eastwardMps[cell]
                val southward = wind.southwardMps[cell]
                zonal[cell] = if (eastward >= 0f) 1 else -1
                // Where the belt's zonal wind and the pressure's have all but cancelled, the
                // ratio below runs away; the clamp is what keeps it inside the march's
                // one-neighbour blend.
                val eastwardSpeed = abs(eastward)
                val maxSlant = maxSlantRowsPerCell(config)
                meridional[cell] = if (eastwardSpeed == 0f) {
                    if (southward >= 0f) maxSlant else -maxSlant
                } else {
                    (southward / eastwardSpeed * cellWidthOverHeight)
                        .coerceIn(-maxSlant, maxSlant)
                }
            }
        }
        return WindField(zonal, meridional, beltZonal)
    }

    /**
     * The surface wind of one season over a finished world, in metres a second, eastward and
     * southward - the field `PressureWindTest` measures against its coastlines.
     *
     * Rebuilt rather than stored: only the annual wind is saved, and a guard that read the annual
     * wind would be asking a question about the year when the question is about July. Everything
     * it rebuilds from is either in the world already (the annual temperature) or is a pure
     * function of the configuration and the land mask, so the field it returns is the one the
     * season's march actually followed.
     */
    internal fun seasonalSurfaceWindMps(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        annualTemperature: FloatField,
        season: Season
    ): PressureWind.Vectors {
        val climateConfig = config.climate
        val tiltDegrees = if (climateConfig.seasons) climateConfig.seasonalTiltDegrees else 0f
        val warm = season == Season.WARM_HALF || season == Season.SUMMER
        val belts = buildWind(
            config.width, config.height, tiltDegrees, warm, climateConfig.meridionalWind
        )
        val pressureHpa = if (climateConfig.pressureWinds) {
            PressureWind.pressureAnomalyHpa(
                config, halfYearTemperature(config, sea, annualTemperature, season)
            )
        } else {
            // A flat field drives no wind at all, so this is the belts alone in metres a second,
            // which is what the control has to be able to measure.
            FloatField(config.width, config.height)
        }
        return totalWindMps(config, belts, PressureWind.surfaceWind(config, sea, pressureHpa))
    }

    /** The circulation belt each row sits in for a season, precomputed per row. */
    private fun bands(cellsDown: Int, climate: ClimateConfig, warm: Boolean): FloatArray =
        FloatArray(cellsDown) { row -> seasonalBand(latitudeOf(row, cellsDown), climate, warm) }

    /**
     * The belt factor the march applies at a latitude in one season — shifted and sharpened.
     *
     * Exposed so a diagnostic can report the number that was actually applied rather than a copy
     * of the formula that drifts away from it, which is what `DesertCauseTest`'s own copy had
     * already done before seasons made the question harder.
     */
    internal fun seasonalBand(latitude: Float, climate: ClimateConfig, warm: Boolean): Float {
        val tiltDegrees = if (climate.seasons) climate.seasonalTiltDegrees else 0f
        val absoluteLatitude = abs(latitude)
        val fromThermalEquator = if (warm) {
            abs(absoluteLatitude - tiltDegrees)
        } else {
            absoluteLatitude + tiltDegrees
        }
        return latitudeBandAt(
            fromThermalEquator,
            climate.subtropicalDryness,
            seasonalBandSharpness(tiltDegrees, climate.subtropicalDryness)
        )
    }

    /**
     * How much sharper an instantaneous circulation belt is than the annual mean of the belts.
     *
     * [latitudeBandAt]'s constants were measured against *annual* desert placement in a world that
     * had no seasons, which makes them a description of the annual mean rather than of any moment
     * in the year. Migrating the belts and averaging two marches computes that annual mean a
     * second time, and two offset bells average to a profile roughly half as sharp as either —
     * which lifts the horse latitudes out of the clamped, maximally arid span their deserts come
     * from, and brings rain-shadow deserts back at the equator, precisely the regression the belt
     * mechanism was introduced to prevent.
     *
     * So each season's anomaly is scaled by the factor that restores the annual mean at the
     * subtropical high's own centre, which is the belt the deserts depend on. It is derived rather
     * than chosen: at a tilt of zero it is exactly 1 and every band is the number it always was,
     * which is what keeps `seasons = false` identical to the pre-seasons world down to the bit.
     * See docs/DESIGN_LEDGER.md, A1, for what the unsharpened version measured.
     */
    private fun seasonalBandSharpness(tiltDegrees: Float, dryness: Float): Float {
        val annual = bandAnomaly(SUBTROPICAL_HIGH_DEGREES, dryness)
        val seasonal = (bandAnomaly(SUBTROPICAL_HIGH_DEGREES - tiltDegrees, dryness) +
            bandAnomaly(SUBTROPICAL_HIGH_DEGREES + tiltDegrees, dryness)) * 0.5f
        // Both are negative under any sane setting — the subtropics suppress rain. If a setting
        // ever made them otherwise, leave the belts alone rather than invent a correction.
        if (annual >= 0f || seasonal >= 0f) return 1f
        return (annual / seasonal).coerceIn(1f, MAX_BAND_SHARPNESS)
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
        seaSurface: FloatField,
        seaIce: BooleanArray,
        wind: WindField,
        ocean: OceanResult,
        bandOfRow: FloatArray,
        convergencePerCell: FloatField?,
        inversionSuppression: FloatField?,
        landOriginPrecipitation: FloatField
    ): FloatField {
        val cellsAcross = config.width
        val cellsDown = config.height
        val precipitation = FloatField(cellsAcross, cellsDown)

        // Where each run of same-direction rows begins. Built by scanning the *belt* direction, so
        // it is the same list on every platform and in every thread, and so it does not move when
        // the pressure field reverses the wind over one continent: a run is a circulation cell,
        // and a circulation cell is a property of the latitude.
        val runStartRows = ArrayList<Int>()
        for (row in 0 until cellsDown) {
            if (row == 0 || wind.beltZonal[row] != wind.beltZonal[row - 1]) runStartRows.add(row)
        }
        runStartRows.add(cellsDown)

        // The second sweep, and why there is one. A wavefront has to sweep one way, and until the
        // pressure field existed one way was all a run ever needed: every cell of a belt blew the
        // same way. A thermal low reverses the zonal wind over part of a belt — that reversal *is*
        // the monsoon — and the air arriving at a reversed cell comes from the column the sweep
        // has not reached yet. So each run is marched twice, once each way, and every cell records
        // the march whose sweep matches the direction its own wind actually blows. Two marches,
        // each of them the same lock-step wavefront as before, rather than one march with an
        // order-dependent scan: the arrangement the whole pipeline's determinism rests on is
        // untouched.
        //
        // A run with no reversed cell in it never runs the second sweep at all, which is why
        // switching the pressure term off costs nothing and gives back the old field exactly.
        val reversed = FloatField(cellsAcross, cellsDown)
        val reversedLandOrigin = FloatField(cellsAcross, cellsDown)
        val runNeedsSecondSweep = BooleanArray(runStartRows.size - 1)
        for (run in runNeedsSecondSweep.indices) {
            val beltDirection = wind.beltZonal[runStartRows[run]]
            var anyReversed = false
            for (cell in runStartRows[run] * cellsAcross until runStartRows[run + 1] * cellsAcross) {
                if (wind.zonal[cell] != beltDirection) {
                    anyReversed = true
                    break
                }
            }
            runNeedsSecondSweep[run] = anyReversed
        }

        parallelChunks(0, runStartRows.size - 1) { firstRun, lastRun ->
            for (run in firstRun until lastRun) {
                val firstRow = runStartRows[run]
                val lastRow = runStartRows[run + 1]
                val beltDirection = wind.beltZonal[firstRow]
                marchRun(
                    config, sea, temperature, seaSurface, seaIce, wind, ocean, bandOfRow,
                    convergencePerCell, inversionSuppression,
                    precipitation, landOriginPrecipitation, sweepDirection = beltDirection,
                    firstRow = firstRow, lastRow = lastRow
                )
                if (runNeedsSecondSweep[run]) {
                    marchRun(
                        config, sea, temperature, seaSurface, seaIce, wind, ocean, bandOfRow,
                        convergencePerCell, inversionSuppression,
                        reversed, reversedLandOrigin, sweepDirection = -beltDirection,
                        firstRow = firstRow, lastRow = lastRow
                    )
                    for (cell in firstRow * cellsAcross until lastRow * cellsAcross) {
                        if (wind.zonal[cell] != beltDirection) {
                            precipitation.data[cell] = reversed.data[cell]
                            landOriginPrecipitation.data[cell] = reversedLandOrigin.data[cell]
                        }
                    }
                }
            }
        }

        // Softens the march's column-by-column steps into weather. The radius grows with the grid
        // so that a world looks the same at every resolution rather than smoother at the coarse
        // ones: one cell at the reference width, two at twice it.
        val radius = (config.width / RAIN_BLUR_REFERENCE_WIDTH).coerceAtLeast(1)
        BoxBlur.apply(precipitation, radius = radius, passes = BLUR_PASSES)
        // The tracer is spread by the same kernel, so that a ratio taken between the two is a
        // ratio between two fields that have been through the same arithmetic.
        BoxBlur.apply(landOriginPrecipitation, radius = radius, passes = BLUR_PASSES)
        return precipitation
    }

    /**
     * What one cell of travel does with a length: the share of a kilometre figure a parcel spends
     * crossing one cell, capped at all of it.
     *
     * The first-order form of the exponential rather than the exponential itself, because every
     * one of these is a term added to or multiplied by others before anything is taken away, and
     * the linear share is what "per cell of travel" meant when these were bare constants. What
     * changes is that the figure above the line is now a length on the ground, so twice as fine a
     * grid takes twice as many steps to cross the same country and arrives at the same place.
     */
    private fun shareOfLengthPerCell(cellWidthKm: Float, lengthKm: Float): Float =
        if (lengthKm <= 0f) 0f else (cellWidthKm / lengthKm).coerceAtMost(1f)

    /** One circulation belt's worth of rows, marched together. See [buildPrecipitation]. */
    private fun marchRun(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        temperature: FloatField,
        seaSurface: FloatField,
        seaIce: BooleanArray,
        wind: WindField,
        ocean: OceanResult,
        bandOfRow: FloatArray,
        convergencePerCell: FloatField?,
        inversionSuppression: FloatField?,
        precipitation: FloatField,
        landOriginPrecipitation: FloatField,
        sweepDirection: Int,
        firstRow: Int,
        lastRow: Int
    ) {
        val cellsAcross = config.width
        val climateConfig = config.climate
        val rowCount = lastRow - firstRow
        val cellWidthKm = config.cellWidthKm.toFloat()
        val lidElevation = config.scale.reliefShareOfMetres(MoistureBudget.INVERSION_LID_METRES)

        // The budget's four lengths, converted once into what one cell of travel does with them.
        val evaporationPerCell =
            shareOfLengthPerCell(cellWidthKm, climateConfig.oceanEvaporationLengthKm)
        val seaRainPerCell = shareOfLengthPerCell(
            cellWidthKm, climateConfig.depletionLengthKm * MoistureBudget.SEA_DEPLETION_SHARE
        )
        val flatRainPerCell = shareOfLengthPerCell(cellWidthKm, climateConfig.depletionLengthKm)
        val returnPerCell =
            shareOfLengthPerCell(cellWidthKm, climateConfig.evapotranspirationLengthKm)

        // One air mass per row, as before — but now they trade moisture sideways as they go, and
        // each of them carries two numbers rather than one. The second is how much of the water in
        // it last came off land rather than off the sea: the tracer the continental recycling
        // ratio is measured from. It rides along every step the column takes and is never a term
        // in one, so taking the measurement away would leave the rainfall identical.
        val moisture = FloatArray(rowCount) { INITIAL_MOISTURE }
        val landOriginMoisture = FloatArray(rowCount)
        val previousColumn = FloatArray(rowCount)
        val previousColumnLandOrigin = FloatArray(rowCount)

        // Laps around the cylinder: the first two seed a realistic moisture state and leave a
        // rainfall for the ground's wetness to be read off, the last is the one that gets
        // recorded, so the arbitrary starting value washes out. See [MARCH_LAPS].
        for (lap in 0 until MARCH_LAPS) {
            val recording = lap == MARCH_LAPS - 1
            for (stepAlongWind in 0 until cellsAcross) {
                val column =
                    if (sweepDirection > 0) stepAlongWind else cellsAcross - 1 - stepAlongWind
                var upwindColumn = column - sweepDirection
                upwindColumn = ((upwindColumn % cellsAcross) + cellsAcross) % cellsAcross
                moisture.copyInto(previousColumn)
                landOriginMoisture.copyInto(previousColumnLandOrigin)

                for (rowWithinRun in 0 until rowCount) {
                    val row = firstRow + rowWithinRun
                    val cell = row * cellsAcross + column

                    // The circulation belt this row sits in, applied to the rain *rate* rather
                    // than to the finished total. Multiplying the result afterwards cannot make a
                    // rain shadow wet again -- twice nearly nothing is still nearly nothing --
                    // whereas suppressing the rate is what descending subtropical air actually
                    // does, and boosting it is what the ITCZ does. In a season this belt is the
                    // shifted one, so the same row is under the dry descending limb in one half of
                    // the year and under the storm track in the other.
                    val bandFactor = bandOfRow[row]

                    // The upwind point, one cell back along the wind vector. The zonal part is a
                    // whole cell; the meridional part is however many rows the slant carries in
                    // that cell of travel, which need not be less than one and near the equator
                    // usually is not: there the Coriolis force vanishes, the pressure wind runs
                    // straight down its own gradient, and a cell where the zonal winds have
                    // nearly cancelled has air arriving from almost due north or south.
                    //
                    // So the departure row is a real number and the sample is a blend of the two
                    // rows that bracket it, rather than of this row and the one next to it. For a
                    // slant inside one row the two are the same arithmetic in the same order; past
                    // it, the old form silently understated how far the air had come, which is a
                    // moisture loss concentrated exactly where the slant saturated.
                    //
                    // The departure is held inside this run: that edge is a boundary between
                    // circulation cells, and air does not cross it at the surface.
                    val slantRowsPerCell = wind.meridional[cell]
                    if (slantRowsPerCell != 0f) {
                        val departureRow =
                            (rowWithinRun - slantRowsPerCell).coerceIn(0f, (rowCount - 1).toFloat())
                        val rowBefore = departureRow.toInt()
                        val rowAfter = (rowBefore + 1).coerceAtMost(rowCount - 1)
                        val shareOfAfter = departureRow - rowBefore
                        moisture[rowWithinRun] = previousColumn[rowBefore] +
                            (previousColumn[rowAfter] - previousColumn[rowBefore]) * shareOfAfter
                        landOriginMoisture[rowWithinRun] = previousColumnLandOrigin[rowBefore] +
                            (previousColumnLandOrigin[rowAfter] -
                                previousColumnLandOrigin[rowBefore]) * shareOfAfter
                    }

                    val columnBefore = moisture[rowWithinRun]
                    val landBefore = landOriginMoisture[rowWithinRun]

                    if (!sea.isLand[cell]) {
                        val marched = if (seaIce[cell]) {
                            marchSeaIceStep(flatRainPerCell, columnBefore)
                        } else {
                            // Warm seas evaporate faster — and which seas are warm is a question
                            // about currents, not latitude. Taking the anomaly from the ocean
                            // stage is what lets a cold current starve a coast of rain while
                            // another at the same latitude, on the warm side of a gyre, soaks it.
                            val currentAnomalyC =
                                if (config.ocean.enabled) ocean.anomaly.data[cell] else 0f
                            marchSeaStep(
                                climateConfig, evaporationPerCell, seaRainPerCell, columnBefore,
                                seaSurface.data[cell] + currentAnomalyC, currentAnomalyC
                            )
                        }
                        moisture[rowWithinRun] = marched.moisture
                        // Rain over the sea takes its share of both reservoirs with it; what
                        // evaporates in the same step is the sea's own water, so the tracer is
                        // diluted rather than added to. That is how a parcel that crossed a
                        // continent forgets it once it is out over the ocean again.
                        landOriginMoisture[rowWithinRun] =
                            landBefore * (1f - marched.rainShareOfColumn)
                        if (recording) precipitation.data[cell] = marched.rain
                        continue
                    }

                    // Orographic lift is the climb the air made getting here, so it is measured
                    // from the same blended upwind point rather than from due upwind along the row
                    // — otherwise a range a slanting wind climbs obliquely would read as flat.
                    val slant = wind.meridional[cell]
                    val upwindElevation = if (slant != 0f) {
                        val departureRow =
                            (rowWithinRun - slant).coerceIn(0f, (rowCount - 1).toFloat())
                        val rowBefore = departureRow.toInt()
                        val rowAfter = (rowBefore + 1).coerceAtMost(rowCount - 1)
                        val shareOfAfter = departureRow - rowBefore
                        val before = sea.relativeElevation.data[
                            (firstRow + rowBefore) * cellsAcross + upwindColumn
                        ]
                        before + (sea.relativeElevation.data[
                            (firstRow + rowAfter) * cellsAcross + upwindColumn
                        ] - before) * shareOfAfter
                    } else {
                        sea.relativeElevation.data[row * cellsAcross + upwindColumn]
                    }
                    // How wet the ground under this cell is, from the rain the previous lap left
                    // on it: the proxy for W4's vegetation, which does not exist yet. On the first
                    // lap it is bare, which is the same starting guess [INITIAL_MOISTURE] is and
                    // washes out over the laps the same way.
                    val wetness = MoistureBudget.groundWetness(precipitation.data[cell] * MM_SCALE)
                    val marched = marchLandStep(
                        climateConfig, cellWidthKm, returnPerCell, columnBefore,
                        sea.relativeElevation.data[cell], upwindElevation, bandFactor,
                        temperature.data[cell], wetness,
                        convergencePerCell?.data?.get(cell) ?: 0f,
                        inversionSuppression?.data?.get(cell) ?: 0f,
                        lidElevation
                    )
                    moisture[rowWithinRun] = marched.moisture
                    // The rain takes its share of both reservoirs, the ground's return is all of
                    // it land water, and whatever the cold cap clipped afterwards it clipped from
                    // both in proportion.
                    val landAfterRain = landBefore * (1f - marched.rainShareOfColumn)
                    val landRain = landBefore * marched.rainShareOfColumn
                    val uncapped = columnBefore - marched.rain + marched.returned
                    val capShare = if (uncapped > 0f) marched.moisture / uncapped else 0f
                    landOriginMoisture[rowWithinRun] =
                        ((landAfterRain + marched.returned) * capShare)
                            .coerceIn(0f, marched.moisture)
                    // Written on every lap, not only the recorded one: the next lap reads it back
                    // as the ground's wetness.
                    precipitation.data[cell] = marched.rain
                    if (recording) landOriginPrecipitation.data[cell] = landRain
                }
            }
        }
    }

    /**
     * What the march does to one cell's air mass and the rain it records: see [marchRun].
     *
     * [rainShareOfColumn] is that rain as a fraction of the column the step started with, which is
     * what the land-origin tracer in [marchRun] needs to split the rain between water that came
     * off the sea and water that came off the ground. [returned] is what the ground gave back in
     * the same step, which is land water by definition and zero over water.
     */
    internal class MarchStep(
        val moisture: Float,
        val rain: Float,
        val rainShareOfColumn: Float = 0f,
        val returned: Float = 0f
    )

    /**
     * How readily water leaves a surface at [temperatureC], as a ramp from 0 at [WARMTH_ZERO_C] to
     * 1 at 30 C and on up to [MAX_WARMTH].
     *
     * The same ramp over sea and over land, because it is the same fact about warm air both times.
     */
    private fun evaporativeWarmth(temperatureC: Float): Float =
        ((temperatureC - WARMTH_ZERO_C) / WARMTH_SPAN_C).coerceIn(0f, MAX_WARMTH)

    /**
     * One cell of open sea: evaporation only.
     *
     * Takes the air mass's [incomingMoisture] as a fraction of saturation, the water's own
     * [seaTemperatureC] in this season, and [currentAnomalyC] — how far that water departs from
     * the mean of its own latitude, which is what makes a cold upwelling (Atacama, Namib, Baja)
     * starve the coast it washes and a warm current (the Gulf Stream, Norway) feed it. Returns the
     * moisture the air leaves with and the rain it dropped, both in the march's own units. At
     * `ClimateConfig.currentMoisture` of zero the anomaly term is exactly 1 whatever the anomaly.
     *
     * [evaporationPerCell] and [seaRainPerCell] are `ClimateConfig.oceanEvaporationLengthKm` and a
     * quarter of `ClimateConfig.depletionLengthKm` as shares of one cell of travel — see
     * [shareOfLengthPerCell] and [MoistureBudget.SEA_DEPLETION_SHARE].
     *
     * Split out of [marchRun] so `MeridionalWindTest`'s from-scratch reference march can call the
     * identical physics the production march uses instead of restating it, and so the two cannot
     * drift apart.
     */
    internal fun marchSeaStep(
        climateConfig: ClimateConfig,
        evaporationPerCell: Float,
        seaRainPerCell: Float,
        incomingMoisture: Float,
        seaTemperatureC: Float,
        currentAnomalyC: Float = 0f
    ): MarchStep {
        val warmth = evaporativeWarmth(seaTemperatureC)
        // Clausius-Clapeyron gives roughly +7% of saturation per degree, which is what
        // `currentMoisture` defaults to. Floored at zero so a freak anomaly cannot make the pickup
        // negative.
        val currentFactor = (1f + climateConfig.currentMoisture * currentAnomalyC).coerceAtLeast(0f)
        val moisture = incomingMoisture +
            evaporationPerCell * warmth * currentFactor * (1f - incomingMoisture)
        return MarchStep(moisture, moisture * seaRainPerCell, seaRainPerCell)
    }

    /**
     * One cell of sea ice: a lid.
     *
     * Nothing evaporates through a metre of ice, so the air mass picks up nothing at all and what
     * it rains comes out of what it was already carrying — the same arithmetic as a cell of flat
     * land, and for the same reason: a floe is a solid surface. That is the whole of why the polar
     * ocean is a desert, why the coast beside it is one too, and why an ice sheet at the pole
     * cannot feed itself indefinitely.
     *
     * It rains at [flatRainPerCell], the flat-land share and not open sea's four times it, because
     * that multiple is the convection warm water drives and ice drives none.
     */
    internal fun marchSeaIceStep(
        flatRainPerCell: Float,
        incomingMoisture: Float
    ): MarchStep {
        val rain = incomingMoisture * flatRainPerCell
        return MarchStep(incomingMoisture - rain, rain, flatRainPerCell)
    }

    /**
     * One cell of land: orographic lift, the regional wind's convergence, the marine inversion,
     * rain, evapotranspiration recovery, the cold-air moisture cap.
     *
     * [incomingMoisture] is a fraction of saturation and [upwindElevation] is in
     * `SeaLevelResult.relativeElevation` units — the elevation the air last saw, the same row for
     * a zonal march and a blend of two rows once the wind carries a meridional component, so this
     * function does not need to know which. [bandFactor] is the circulation belt's multiplier on
     * the rain rate, [groundWetness] is how freely the surface under the parcel returns water,
     * [convergencePerCell] is the share of the column the regional wind gathered into this cell
     * over one cell of travel, and [inversionStrength] with [lidElevation] are the marine stratus
     * lid and the height a parcel has to climb to escape it. Returns the moisture the air leaves
     * with and the rain it dropped. See [marchSeaStep] for why this is shared with
     * `MeridionalWindTest` rather than restated there.
     */
    internal fun marchLandStep(
        climateConfig: ClimateConfig,
        cellWidthKm: Float,
        returnPerCell: Float,
        incomingMoisture: Float,
        elevationHere: Float,
        upwindElevation: Float,
        bandFactor: Float,
        landTemperatureC: Float,
        groundWetness: Float = 1f,
        convergencePerCell: Float = 0f,
        inversionStrength: Float = 0f,
        lidElevation: Float = 0f
    ): MarchStep {
        // Orographic lift is the climb the air made getting here.
        val rise = (elevationHere - upwindElevation).coerceAtLeast(0f)

        // The depletion length is the flat-land one stretched by how much water the air over this
        // ground can hold; the climb shortens it and the regional wind's convergence adds to what
        // has to come out. The three meet as rates rather than as lengths because only the first
        // of them is a property of the air alone.
        val depletionLengthKm =
            MoistureBudget.depletionLengthKm(climateConfig.depletionLengthKm, landTemperatureC)
        val flatRate = shareOfLengthPerCell(cellWidthKm, depletionLengthKm)

        // The marine inversion: under a stratus lid over cold water the parcel holds its rain in
        // until the ground stands above the lid, which is why the Atacama is a coastal strip and
        // the Andes behind it are not dry.
        val aboveLid =
            if (lidElevation <= 0f) 1f else (elevationHere / lidElevation).coerceIn(0f, 1f)
        val lidFactor = 1f - inversionStrength * (1f - aboveLid)

        val rate = ((flatRate + climateConfig.orographicStrength * rise + convergencePerCell) *
            bandFactor * lidFactor).coerceAtLeast(0f)
        val rain = (incomingMoisture * rate).coerceAtMost(incomingMoisture)
        var moisture = incomingMoisture - rain

        // Evapotranspiration: the land gives water back, and how readily is the thing that
        // decides where deserts sit. Scaled by the belt, because that is the mechanism:
        // descending subtropical air suppresses the convection that would return moisture to the
        // sky, while rising tropical air encourages it. Take the belt out of this term and every
        // latitude re-moistens alike, at which point deserts stop preferring the horse latitudes
        // at all. See GEOGRAPHY.md, "Where the deserts are", for what that measures. Scaled by
        // the ground's own wetness too, because dry ground has nothing left to give: that is the
        // feedback which makes a continental interior either wet or arid rather than uniformly
        // middling, and it is what [MoistureBudget.EVAPOTRANSPIRATION_LENGTH_KM] is measured by.
        val warmth = evaporativeWarmth(landTemperatureC)
        val returned = returnPerCell * warmth * bandFactor * groundWetness * (1f - moisture)
        moisture += returned

        // Cold air simply holds less water.
        val coldCap = ((landTemperatureC - COLD_CAP_ZERO_C) / COLD_CAP_SPAN_C)
            .coerceIn(MIN_COLD_CAP, 1f)
        moisture = moisture.coerceAtMost(coldCap)

        return MarchStep(
            moisture, rain,
            if (incomingMoisture > 0f) rain / incomingMoisture else 0f,
            returned
        )
    }

    /**
     * The rainfall a given percentile of *land* receives, in the march's raw units.
     *
     * Used only for measurement — [MM_SCALE] was calibrated with it and `AbsoluteRainfallTest`
     * calls it to report the same figures on every audited seed. Nothing in [generate] calls it at
     * runtime: per-world percentile rescaling is what made an arid world and a lush one classify
     * identically. Internal rather than private so the test can reach it without restating the
     * histogram.
     *
     * [percentile] is a share of the land, 0..1. Returns 0 when there is nothing to measure.
     * See docs/DESIGN_LEDGER.md, A4.
     */
    internal fun landPercentile(
        precipitation: FloatField,
        isLand: BooleanArray,
        percentile: Float
    ): Float {
        var wettest = 0f
        var landCells = 0
        for (cell in precipitation.data.indices) {
            if (!isLand[cell]) continue
            landCells++
            if (precipitation.data[cell] > wettest) wettest = precipitation.data[cell]
        }
        if (landCells == 0 || wettest <= 0f) return 0f

        val histogram = IntArray(PERCENTILE_BINS)
        val binsPerUnit = (PERCENTILE_BINS - 1) / wettest
        for (cell in precipitation.data.indices) {
            if (isLand[cell]) {
                histogram[
                    (precipitation.data[cell] * binsPerUnit).toInt()
                        .coerceIn(0, PERCENTILE_BINS - 1)
                ]++
            }
        }

        val targetCells = (landCells * percentile).toLong()
        var cumulativeCells = 0L
        var reference = wettest
        for (bin in 0 until PERCENTILE_BINS) {
            cumulativeCells += histogram[bin]
            if (cumulativeCells >= targetCells) {
                reference = bin / binsPerUnit
                break
            }
        }
        if (reference <= 0f) reference = wettest
        return reference
    }

    /**
     * How much the circulation belt encourages or suppresses rain, at a given distance from the
     * thermal equator.
     *
     * Four bumps, each centred where the atmosphere actually puts it: the wet ITCZ at the equator,
     * the dry descending air of the horse latitudes near 30, the wet mid-latitude storm track near
     * 55, and the polar cell's own dry descent at the pole. Returns a multiplier on the rain rate,
     * 1 being an unremarkable latitude.
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
        fromThermalEquatorDegrees: Float,
        subtropicalDryness: Float = 1.15f,
        /** See [seasonalBandSharpness]. One leaves every band exactly as it was. */
        sharpness: Float = 1f
    ): Float {
        val itcz = ITCZ_STRENGTH *
            bell(fromThermalEquatorDegrees, ITCZ_DEGREES, ITCZ_WIDTH_DEGREES)
        val subtropicalHigh = -subtropicalDryness *
            bell(
                fromThermalEquatorDegrees,
                SUBTROPICAL_HIGH_DEGREES,
                SUBTROPICAL_HIGH_WIDTH_DEGREES
            )
        val stormTrack = STORM_TRACK_STRENGTH *
            bell(fromThermalEquatorDegrees, STORM_TRACK_DEGREES, STORM_TRACK_WIDTH_DEGREES)
        val polarDry = -POLAR_DRY_STRENGTH *
            bell(fromThermalEquatorDegrees, POLE_DEGREES, POLAR_DRY_WIDTH_DEGREES)
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
            sharpness * stormTrack + sharpness * polarDry).coerceAtLeast(MIN_BAND)
    }

    /**
     * The belts' departure from an unremarkable rain rate at a given distance from the thermal
     * equator, which is what a season sharpens. The same four bumps as [latitudeBandAt], without
     * its baseline of one and without its floor.
     */
    private fun bandAnomaly(fromThermalEquatorDegrees: Float, subtropicalDryness: Float): Float =
        ITCZ_STRENGTH * bell(fromThermalEquatorDegrees, ITCZ_DEGREES, ITCZ_WIDTH_DEGREES) -
            subtropicalDryness * bell(
                fromThermalEquatorDegrees,
                SUBTROPICAL_HIGH_DEGREES,
                SUBTROPICAL_HIGH_WIDTH_DEGREES
            ) +
            STORM_TRACK_STRENGTH *
            bell(fromThermalEquatorDegrees, STORM_TRACK_DEGREES, STORM_TRACK_WIDTH_DEGREES) -
            POLAR_DRY_STRENGTH *
            bell(fromThermalEquatorDegrees, POLE_DEGREES, POLAR_DRY_WIDTH_DEGREES)

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
     * ## Order: aridity first, then the thermal groups
     *
     * Koppen decides arid climates (B) from a threshold that already depends on temperature and on
     * when the rain falls, *before* asking whether a place is tropical, temperate, cold or polar —
     * so a cold, dry interior can be a desert (BWk, the Gobi) without ever being asked whether it
     * would otherwise have been tundra or taiga. [koppenAridityThresholdMm] is that threshold,
     * read on [precipitationMm] rather than on a per-world rescale, which is what lets this model
     * draw a cold desert at all: rescaled, a merely-below-average cell and a true desert core
     * cannot be told apart.
     *
     * ## Ice, before any of it
     *
     * The first question asked of a land cell is whether it is under ice, and the answer is
     * [SnowBalance]'s: a glacier is where a year's snowfall outlives the year, not where the
     * thermometer reads below freezing. That ordering is deliberate — ice covers whatever was
     * underneath it — but so is what happens when the balance says no: the cell falls through to
     * the aridity line and the thermal groups like any other, so a cold *dry* interior comes out
     * as cold desert or tundra, which is what Siberia and the Gobi are. The annual-mean rule it
     * replaced is still here behind `ClimateConfig.snowBalance`, as the control its guard needs.
     *
     * ## The four thermal groups
     *
     * Koppen's own, read on the seasonal temperature fields once a cell has cleared the aridity
     * test: a place whose [summerTemperature] never reaches [TREE_LINE_WARMEST_C] has no growing
     * season and is tundra (ET) whatever its annual mean; above that, a [winterTemperature] at or
     * below [CONTINENTAL_COLDEST_C] means a real winter with secure snow cover and is continental
     * (D), where taiga lives; at or above [TROPICAL_COLDEST_C] there is no winter at all and it is
     * tropical (A); everything else is temperate (C).
     *
     * Reading the coldest and warmest month rather than the annual mean is what puts a
     * high-latitude west coast in the right group. Bergen is temperate at an 8 C annual mean
     * because its *coldest month* is about 2 C — a fact the annual mean cannot see and the coldest
     * month states directly. See docs/DESIGN_LEDGER.md, A5 and A6.
     *
     * ## The moisture table below the aridity line
     *
     * Once a cell has cleared [koppenAridityThresholdMm], the moisture axis for the tropical and
     * temperate groups is one absolute-mm table read on [precipitationMm]:
     *
     * ```
     *  (arid, see above)   desert / steppe            (DESERT / GRASSLAND or SAVANNA, both groups)
     *  500-1000mm          shrubland / savanna-forest (SHRUBLAND temperate, SAVANNA or
     *                                                  TROPICAL_SEASONAL_FOREST by summerShare tropical)
     *  1000-2000mm         forest                     (TEMPERATE_FOREST, TROPICAL_SEASONAL_FOREST)
     *  > 2000mm            rainforest                 (TEMPERATE_RAINFOREST, TROPICAL_RAINFOREST)
     * ```
     *
     * The desert/steppe line is not a fixed millimetre figure, because a fixed cut cannot be a
     * fair line both for a hot summer-wet coast and for a cold interior at the same total;
     * [STEPPE_MM] marks only where "arid" gives way to "definitely not" for the table above it.
     *
     * Mediterranean and monsoon are decided on the *year's* lopsidedness rather than its size, but
     * each ratio carries a wetness floor in real millimetres ([MEDITERRANEAN_WINTER_FLOOR_MM],
     * [MEDITERRANEAN_SUMMER_CEILING_MM], [MONSOON_SUMMER_FLOOR_MM]) so that two nearly rainless
     * seasons cannot qualify on their ratio alone. See docs/DESIGN_LEDGER.md, A4.
     */
    private fun classify(
        cellsAcross: Int,
        cellsDown: Int,
        sea: SeaLevelResult,
        temperature: FloatField,
        summerTemperature: FloatField,
        winterTemperature: FloatField,
        precipitationMm: FloatField,
        summerPrecipitationMm: FloatField,
        winterPrecipitationMm: FloatField,
        /** The perennial pack: see [ClimateResult.summerSeaIce]. */
        summerSeaIce: BooleanArray,
        /**
         * [SnowBalance]'s field, or null when `ClimateConfig.snowBalance` is off and the ice gate
         * is the annual-mean one it replaced.
         */
        snowBalance: FloatField?
    ): Array<Biome> {
        return Array(cellsAcross * cellsDown) { cell ->
            if (!sea.isLand[cell]) {
                // Sea ice, which is frozen sea water and not a mass balance at all: it forms
                // because the water froze, and no amount of snowfall makes it and no amount of
                // drought prevents it. The snow balance is about glaciers, so it is not asked
                // here. The warm season's mask rather than the cold one's, because what an atlas
                // draws as ice is the pack that is still there in August.
                if (summerSeaIce[cell]) Biome.ICE_SHEET
                else if (sea.relativeElevation.data[cell] > SHALLOW_OCEAN_DEPTH) Biome.SHALLOW_OCEAN
                else Biome.OCEAN
            } else {
                val annualC = temperature.data[cell]
                val warmestC = summerTemperature.data[cell]
                val coldestC = winterTemperature.data[cell]
                val annualMm = precipitationMm.data[cell]
                val summerMm = summerPrecipitationMm.data[cell]
                val winterMm = winterPrecipitationMm.data[cell]
                // How lopsided the year is, in each direction. One number rather than a pair of
                // thresholds, because what separates a savanna from a seasonal forest of the same
                // annual total is the shape of the year and not its size.
                val summerShare = (summerMm + SEASON_FLOOR_MM) / (winterMm + SEASON_FLOOR_MM)
                val winterShare = (winterMm + SEASON_FLOOR_MM) / (summerMm + SEASON_FLOOR_MM)
                val elevation = sea.relativeElevation.data[cell]
                val aridityMm = koppenAridityThresholdMm(
                    annualC, summerShare, winterShare, summerMm, winterMm
                )
                // Ice, by whichever rule this world was asked for. The balance is the honest one —
                // a glacier is where a year's snow survives the year, so a cold desert falls
                // through to the aridity and tundra gates below and comes out as cold desert or
                // tundra rather than as an ice cap. The annual-mean rule beside it is the control
                // its guard needs.
                val underIce = if (snowBalance != null) {
                    snowBalance.data[cell] > 0f
                } else {
                    annualC < ANNUAL_MEAN_ICE_C
                }
                when {
                    underIce -> Biome.ICE_SHEET
                    elevation > ALPINE_ELEVATION -> Biome.ALPINE
                    // B: arid, decided before any of the thermal groups below — see the doc
                    // comment above. BW (desert) below half the threshold, BS (steppe) below it;
                    // a hot steppe reads as savanna, a cool one as grassland, matching the two
                    // biomes those groups already use for the same moisture band below the line.
                    annualMm < aridityMm -> when {
                        annualMm < aridityMm * DESERT_SHARE_OF_ARIDITY -> Biome.DESERT
                        coldestC >= TROPICAL_COLDEST_C -> Biome.SAVANNA
                        else -> Biome.GRASSLAND
                    }
                    // ET: even the warmest month never clears the tree line's own threshold.
                    warmestC < TREE_LINE_WARMEST_C -> Biome.TUNDRA
                    // D: a real summer, but a coldest month cold enough for secure winter snow
                    // cover — Koppen's own line between continental and temperate. Moisture has
                    // already been asked, above the aridity line, so this only splits taiga from a
                    // residual near-arid tundra that escaped B without much room to spare.
                    coldestC <= CONTINENTAL_COLDEST_C ->
                        if (annualMm < COLD_ARID_MM) Biome.TUNDRA else Biome.TAIGA
                    // A: no winter at all.
                    coldestC >= TROPICAL_COLDEST_C -> when {
                        // One drenching wet season doing nearly all the year's work.
                        summerShare >= MONSOON_SUMMER_RATIO &&
                            summerMm >= MONSOON_SUMMER_FLOOR_MM -> Biome.MONSOON_FOREST
                        // Savanna is a seasonality rather than a total: grass where the dry half
                        // of the year is long enough to burn, forest where it is not.
                        annualMm < STEPPE_MM -> Biome.SAVANNA
                        annualMm < SHRUB_SAVANNA_MM ->
                            if (summerShare >= SAVANNA_SUMMER_RATIO) Biome.SAVANNA
                            else Biome.TROPICAL_SEASONAL_FOREST
                        annualMm < FOREST_MM -> Biome.TROPICAL_SEASONAL_FOREST
                        else -> Biome.TROPICAL_RAINFOREST
                    }
                    // C: a real winter and a real summer — everything in between.
                    else -> when {
                        // Dry summer, wet winter, mild enough for the rain to be rain: the
                        // subtropical high sits over the coast all summer and the westerlies swing
                        // back over it in winter. A real wet season is required as well as the
                        // ratio, or a dry continental interior would qualify on lopsidedness alone
                        // while receiving almost nothing either half of the year.
                        winterShare >= MEDITERRANEAN_WINTER_RATIO &&
                            summerMm < MEDITERRANEAN_SUMMER_CEILING_MM &&
                            winterMm >= MEDITERRANEAN_WINTER_FLOOR_MM &&
                            coldestC > MEDITERRANEAN_COLDEST_C -> Biome.MEDITERRANEAN
                        annualMm < STEPPE_MM -> Biome.GRASSLAND
                        annualMm < SHRUB_SAVANNA_MM -> Biome.SHRUBLAND
                        annualMm < FOREST_MM -> Biome.TEMPERATE_FOREST
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
     * The concentration term needs a floor as well as a ratio, which is why this reads
     * [summerShare]/[winterShare] *and* [summerMm]/[winterMm] rather than the ratio alone. This
     * march's cold cap suppresses winter moisture far more than summer moisture everywhere cold —
     * a temperature effect, not a seasonal-rainfall-pattern one — so the ratio on its own calls
     * almost every cold cell "summer-concentrated" regardless of whether either season brought
     * meaningful rain. [KOPPEN_CONCENTRATION_FLOOR_MM] requires the wetter season to have brought
     * a real amount of rain — comparable to [MEDITERRANEAN_WINTER_FLOOR_MM] and
     * [MONSOON_SUMMER_FLOOR_MM]'s own floors on the same ratios elsewhere in [classify] — before
     * the ratio is trusted to mean a genuine wet/dry pattern rather than "both seasons are dry and
     * one is marginally less so." See docs/DESIGN_LEDGER.md, A4, for what the ratio measures without it.
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
        return KOPPEN_ARIDITY_PER_DEGREE_C * annualMeanC + concentration
    }
}
