package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.math.BoxBlur
import com.cartogenesis.worldgen.math.JumpFloodDistance
import com.cartogenesis.worldgen.model.ClimateConfig
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldScale
import com.cartogenesis.worldgen.noise.GroundLattice
import com.cartogenesis.worldgen.noise.PerlinNoise
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
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
     * Storing the local extreme rather than the calendar month is what lets
     * [ClimateStage.classify] apply one rule to the whole map instead of branching on the sign of
     * the latitude — a Mediterranean coast is a Mediterranean coast either side of the equator.
     * Everything that is one moment of the planet reads [julyTemperature] and [januaryTemperature]
     * or the half-years instead.
     */
    val summerTemperature: FloatField,
    val winterTemperature: FloatField,
    /**
     * Temperature in the calendar's July and January, in degrees Celsius, one entry per cell,
     * row-major: one moment of the planet each, the northern summer with the southern winter and
     * the other way about, which is what a map compared with an atlas's isotherms shows. See
     * [EnergyBalance.APRIL_FIRST_STEP] for where the months sit in the model's year.
     */
    val julyTemperature: FloatField,
    val januaryTemperature: FloatField,
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
     * Rainfall in the calendar's two half-years, April to September and October to March, on the
     * same scale as [precipitation] — the same fixed mm-to-0..1 factor is applied to all three, so
     * the three fields can be compared against each other. [precipitation] is their mean, except
     * where the clamp at 1 bites on a half. These are the march's own two seasons, each one moment
     * of the planet; a place's own summer is whichever of the two is warmer there (see
     * [ClimateStage.classify]).
     */
    val julyHalfPrecipitation: FloatField,
    val januaryHalfPrecipitation: FloatField,
    /**
     * Annual rainfall in millimeters, the march's own: it carries water in kilograms per square
     * meter and records what rains in kilograms per square meter a year, so there is no
     * conversion and no per-world rescale (see [MoistureMarch]). This is the field
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
     * Where the sea is frozen in each of the calendar's half-years, April to September and
     * October to March: one entry per cell, row-major, true where that half's sea surface sits at
     * or below the freezing point of sea water ([EnergyBalance.SEA_FREEZING_C], -1.8 C at the
     * ocean's mean salinity) and false everywhere on land.
     *
     * Each mask is one moment of the planet, so each holds one hemisphere's winter pack and the
     * other's summer remnant; frozen in either is the winter pack, which on Earth reaches the Sea
     * of Okhotsk and the Gulf of Bothnia. The perennial ice, which is what an atlas draws and what
     * [ClimateStage.classify] paints as `ICE_SHEET` over water, is the sea frozen through its own
     * warmest half-year, and the biome carries it.
     *
     * The moisture march reads whichever mask belongs to the half it is marching, and takes no
     * moisture at all from a frozen cell — a metre of ice is a lid, which is why the polar ocean is
     * a desert and why a polar desert exists on the coast beside it.
     */
    val julyHalfSeaIce: BooleanArray,
    val januaryHalfSeaIce: BooleanArray,
    val biome: Array<Biome>,
    /**
     * How much living cover the ground carries, 0 on bare rock or ice to 1 under a closed forest,
     * one entry per cell, row-major, and 0 at every sea cell.
     *
     * The continuous field [biome] is a partition of: see [VegetationDensity] for the two
     * relations it is built from. Beside the classification rather than inside it — nothing in
     * [ClimateStage.classify] reads this, and the sixteen names are what they were before it
     * existed — because a name and a density answer different questions and the chunk that added
     * this one was not asked to redraw the other.
     *
     * Read today by the map's tint, which takes its canopy from here instead of from a figure per
     * biome. The consumers named and not yet built are S3 and H3, where a vegetated slope resists
     * erosion roughly twice as well as a bare one (Istanbulluoglu and Bras 2005); see TODO.md.
     */
    val vegetationDensity: FloatField,
    /**
     * Where the ground is frozen the year round: one [VegetationDensity.Permafrost] ordinal a
     * cell, row-major, and [VegetationDensity.Permafrost.NONE] at sea.
     *
     * A byte rather than a boolean because the two zones are different countries — continuous
     * permafrost cannot root a tree and discontinuous permafrost carries the Siberian larch — and
     * only the continuous zone caps [vegetationDensity].
     */
    val permafrost: ByteArray,
    /**
     * The year's potential evapotranspiration over land, in millimeters, one entry per cell,
     * row-major: FAO-56's Penman-Monteith reference rate, under each half-year's sun, air and the
     * march's own humidity ([SurfaceEvaporation.referenceEvapotranspirationMmPerDay]). Filled at
     * sea cells too, as the rate land there would have, because the shoreline moves under the
     * erosion that reads it.
     *
     * Saved because two stages must read the same one: the march's ground return is Budyko's
     * share of the year's rain against this, and the rivers' and lakes' runoff
     * ([LakeWaterBalance.shedding], [Runoff]) is the rest of the same rain against the same
     * potential, so rain is the return plus the runoff at every cell.
     */
    val potentialEvapotranspirationMm: FloatField,
    /**
     * The year's evaporation from open fresh water, in millimeters, one entry per cell, row-major:
     * Penman's equation at water's albedo ([SurfaceEvaporation.openWaterEvaporationMmPerDay]).
     * What a lake surface loses, which is not what a field of grass could: open water has no
     * canopy holding its vapor in.
     */
    val openWaterEvaporationMm: FloatField
)

/**
 * Step 4: temperature from latitude and altitude, then rainfall by marching moist air along
 * prevailing wind bands so that windward slopes get soaked and leeward slopes fall into rain
 * shadow.
 *
 * Run twice over, for the calendar's two half-years, April to September and October to March:
 * each is one moment of the planet, one hemisphere's summer and the other's winter. The whole of
 * the seasonal machinery is one number — [ClimateConfig.seasonalTiltDegrees], the distance the
 * thermal equator migrates into the summer hemisphere — applied to the obliquity the energy
 * balance's sun is computed at and to the latitude the wind belts and the rain belts are read
 * off. The annual fields are kept as they were, so every stage downstream of this one sees
 * exactly what it saw before seasons existed.
 */
object ClimateStage {

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
     * to do with monsoons: cold air holds less water (the saturated column of [ColumnWater] falls
     * by Clausius-Clapeyron, as the cold cap that stood for it until C1b did), and winter is colder
     * than summer at the same cell by construction, so winter is systematically the drier season
     * across the whole cold half of every world. At Koppen's own
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
     * That noise's longest wavelength on the ground, in kilometers: 2,400 km, the 5 cycles round
     * the 12,000 km world it was set as, read as whole cycles round the planet so the pattern meets
     * itself at the map's east-west seam instead of showing a join.
     *
     * The same wavelength north-south, on a lattice square on the ground. The noise used to cover
     * as many cycles from pole to pole as round the equator, which on a map twice as wide as it is
     * tall drew every weather cell twice as long east-west as north-south, so isotherms and the
     * tints that follow them ran along the rows (docs/DESIGN_LEDGER.md, K1).
     */
    internal const val WEATHER_NOISE_WAVELENGTH_KM = 2_400.0

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
     * The belts' edges, in degrees from the thermal equator: one copy, [SurfaceBelts]', which the
     * ocean's stress reads too, because it is the same circulation.
     */
    private const val TRADE_BELT_EDGE_DEGREES = SurfaceBelts.TRADE_BELT_EDGE_DEGREES
    private const val WESTERLY_BELT_EDGE_DEGREES = SurfaceBelts.WESTERLY_BELT_EDGE_DEGREES

    // The three bumps of [latitudeBandAt]'s rain-rate profile, each placed where the atmosphere
    // actually puts it and each as wide as that feature really is. The ITCZ is not one of them:
    // the march's transport gathers the trades' water into it and the convergence rains it, so a
    // bump here would count the same rising air twice (docs/DESIGN_LEDGER.md, C1b). Read from the *thermal*
    // equator, so the whole profile migrates with the season. A strength above zero encourages
    // rain and below zero suppresses it; the subtropical high's strength is
    // `ClimateConfig.subtropicalDryness`, because how arid a world's horse latitudes are is the
    // one thing here worth a setting.

    /** Descending, drying air: the horse latitudes, and every subtropical desert on Earth. */
    private const val SUBTROPICAL_HIGH_DEGREES = 30f
    private const val SUBTROPICAL_HIGH_WIDTH_DEGREES = 13f

    /** The mid-latitude storm track, along the polar front. */
    private const val STORM_TRACK_STRENGTH = 0.5f
    private const val STORM_TRACK_DEGREES = 55f
    private const val STORM_TRACK_WIDTH_DEGREES = 15f

    /** The polar cell's descending air. A polar desert is a real thing, but a mild one. */
    private const val POLAR_DRY_STRENGTH = 0.35f
    private const val POLAR_DRY_WIDTH_DEGREES = 18f

    /**
     * The floor a circulation belt's factor on the rain's rates approaches where the
     * subtropical high's descent outweighs the baseline: a twentieth.
     *
     * At the default dryness the profile goes negative across roughly 25 to 35 degrees, and the
     * floor is what is left there. It is reached smoothly rather than by a clamp ([smoothFloor]):
     * a clamp put a kink in the profile at the latitude where it bit, which drew the desert's
     * edge straight along a row (docs/DESIGN_LEDGER.md, C1b). The figure itself has no Earth
     * source; it stands until the atmosphere solves the descent it stands in for (docs/TODO.md).
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
     * The rain blur's radius on the ground, in kilometers: 93.75 km, one cell of a 128-wide grid
     * of the 12,000 km world, which is where it was set as a divisor of the map's width.
     *
     * A length on the ground so that a world looks the same at every resolution and on a planet
     * of any size: the blur is softening the march's column-by-column steps into weather, and the
     * weather it makes has a scale of its own.
     */
    internal const val RAIN_BLUR_RADIUS_KM = 93.75

    /**
     * The same blur as the standard deviation of the Gaussian [SphereBlur] spreads with: two box
     * passes of half-width R have a variance of `2 R^2 / 3`, so 76.5 km.
     */
    internal val RAIN_BLUR_SIGMA_KM = RAIN_BLUR_RADIUS_KM * sqrt(2.0 / 3.0)

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
        /**
         * Each cell's own summer and winter rain in millimetres, which [classify] reads: the
         * warmer and the cooler of the two calendar half-years at that cell (Peel, Finlayson and
         * McMahon 2007; see [localSummerIsJulyHalf]).
         */
        val summerPrecipitationMm: FloatField,
        val winterPrecipitationMm: FloatField,
        /** The march's own two seasons, the calendar's half-years, in millimetres. */
        val julyHalfPrecipitationMm: FloatField,
        val januaryHalfPrecipitationMm: FloatField,
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
        /** The calendar's July and January, which is what the maps of a season draw. */
        val julyTemperature: FloatField,
        val januaryTemperature: FloatField,
        /**
         * The calendar's half-years' means, which is what anything integrating across a season
         * needs — the snow balance's degree-day sum and the moisture march's evaporation. Not
         * saved: `ClimateResult` carries the months, because those are what a reader and a
         * classifier want. See [Season].
         */
        val julyHalfTemperature: FloatField,
        val januaryHalfTemperature: FloatField,
        val julyHalfPrecipitationMm: FloatField,
        val januaryHalfPrecipitationMm: FloatField,
        val julyHalfSeaIce: BooleanArray,
        val januaryHalfSeaIce: BooleanArray,
        /** Frozen through its own warmest half-year: the pack that survives the summer. */
        val perennialSeaIce: BooleanArray,
        val wind: WindField,
        val landOriginPrecipitationMm: FloatField,
        val potentialEvapotranspirationMm: FloatField,
        val openWaterEvaporationMm: FloatField
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
            fields.julyHalfTemperature,
            fields.januaryHalfTemperature,
            fields.julyHalfPrecipitationMm,
            fields.januaryHalfPrecipitationMm
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
        val julyHalfPrecipitationMm = fields.julyHalfPrecipitationMm
        val januaryHalfPrecipitationMm = fields.januaryHalfPrecipitationMm

        val precipitationMm = FloatField(cellsAcross, cellsDown)
        for (cell in 0 until cellCount) {
            precipitationMm.data[cell] =
                (julyHalfPrecipitationMm.data[cell] + januaryHalfPrecipitationMm.data[cell]) * 0.5f
        }

        // Each cell's own summer and winter, for the shapes classify asks about: whichever of the
        // two calendar halves is warmer there.
        val summerPrecipitationMm = FloatField(cellsAcross, cellsDown)
        val winterPrecipitationMm = FloatField(cellsAcross, cellsDown)
        for (cell in 0 until cellCount) {
            val julyIsSummer = localSummerIsJulyHalf(
                fields.julyHalfTemperature.data[cell], fields.januaryHalfTemperature.data[cell]
            )
            summerPrecipitationMm.data[cell] =
                if (julyIsSummer) julyHalfPrecipitationMm.data[cell] else januaryHalfPrecipitationMm.data[cell]
            winterPrecipitationMm.data[cell] =
                if (julyIsSummer) januaryHalfPrecipitationMm.data[cell] else julyHalfPrecipitationMm.data[cell]
        }

        // The 0..1 copy the stage's consumers were built against: rendering, CultureStage's
        // climate distance, RiverStage's and NationStage's runoff weighting. One fixed factor for
        // all three fields, so they can be compared with each other: REFERENCE_MM maps to 1,
        // clamped.
        val precipitation = FloatField(cellsAcross, cellsDown)
        val julyHalfPrecipitation = FloatField(cellsAcross, cellsDown)
        val januaryHalfPrecipitation = FloatField(cellsAcross, cellsDown)
        for (cell in 0 until cellCount) {
            precipitation.data[cell] = (precipitationMm.data[cell] / REFERENCE_MM).coerceIn(0f, 1f)
            julyHalfPrecipitation.data[cell] =
                (julyHalfPrecipitationMm.data[cell] / REFERENCE_MM).coerceIn(0f, 1f)
            januaryHalfPrecipitation.data[cell] =
                (januaryHalfPrecipitationMm.data[cell] / REFERENCE_MM).coerceIn(0f, 1f)
        }

        // Recomputed here rather than carried down from the glaciation stage's provisional run:
        // that one was measured on the terrain before the ice cut it, and this one has to agree
        // with the map the reader is looking at. Cheap enough that sharing it would be a false
        // economy — see [SnowBalanceAccelerator].
        val snowBalance = if (config.climate.snowBalance) {
            SnowBalance.field(
                sea.isLand, fields.julyHalfTemperature, fields.januaryHalfTemperature,
                julyHalfPrecipitationMm, januaryHalfPrecipitationMm
            )
        } else null

        val biome = classify(
            cellsAcross, cellsDown, sea, temperature, summerTemperature, winterTemperature,
            precipitationMm, summerPrecipitationMm, winterPrecipitationMm,
            fields.perennialSeaIce, snowBalance
        )

        // The cover on the ground and the frozen ground under it, beside the classification and
        // read by none of it. Built here rather than in a stage of its own because every field it
        // needs is this stage's and a stage boundary would buy a save section, a reuse guard and a
        // progress line for one pass over the grid. See [VegetationDensity], and
        // `VegetationConfig.enabled` for the control the guards are shown failing against.
        val vegetation = if (config.vegetation.enabled) {
            VegetationDensity.field(
                sea.isLand, temperature, summerTemperature, winterTemperature, precipitationMm,
                permafrostEnabled = config.vegetation.permafrost
            )
        } else {
            VegetationDensity.Field(FloatField(cellsAcross, cellsDown), ByteArray(cellCount))
        }

        return Generated(
            result = ClimateResult(
                temperature = temperature,
                summerTemperature = summerTemperature,
                winterTemperature = winterTemperature,
                julyTemperature = fields.julyTemperature,
                januaryTemperature = fields.januaryTemperature,
                precipitation = precipitation,
                julyHalfPrecipitation = julyHalfPrecipitation,
                januaryHalfPrecipitation = januaryHalfPrecipitation,
                precipitationMm = precipitationMm,
                windDirection = fields.wind.zonal,
                windMeridional = FloatField(cellsAcross, cellsDown, fields.wind.meridional),
                julyHalfSeaIce = fields.julyHalfSeaIce,
                januaryHalfSeaIce = fields.januaryHalfSeaIce,
                biome = biome,
                vegetationDensity = vegetation.density,
                permafrost = vegetation.permafrost,
                potentialEvapotranspirationMm = fields.potentialEvapotranspirationMm,
                openWaterEvaporationMm = fields.openWaterEvaporationMm
            ),
            summerPrecipitationMm = summerPrecipitationMm,
            winterPrecipitationMm = winterPrecipitationMm,
            julyHalfPrecipitationMm = julyHalfPrecipitationMm,
            januaryHalfPrecipitationMm = januaryHalfPrecipitationMm,
            landOriginPrecipitationMm = fields.landOriginPrecipitationMm
        )
    }

    /**
     * Whether a place's own summer is the calendar's half about July, given its mean temperature
     * in that half and in the half about January, both in degrees Celsius.
     *
     * Peel, Finlayson and McMahon's rule for the Koppen map (2007, Table 1): "Summer (winter) is
     * defined as the warmer (cooler) six month period of ONDJFM and AMJJAS". Read on the place's
     * own temperatures rather than on the sign of its latitude, so the switch from one half to the
     * other follows the thermal equator over the land rather than running along the equator's row.
     */
    internal fun localSummerIsJulyHalf(julyHalfC: Float, januaryHalfC: Float): Boolean =
        julyHalfC >= januaryHalfC


    private fun seasonalFields(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        ocean: OceanResult,
        /** The glacial forcing, in degrees of global mean; zero is today's world. */
        globalCoolingC: Float = 0f,
        /** Filled with the march's water budget when handed in; see [moistureLedger]. */
        ledger: MoistureLedger? = null
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
        carrySeaAnomalyIntoMarineAir(config, sea, ocean, temperature)
        // Three readings of the same year. The local extreme months are what `classify` gates on,
        // because Koppen's thresholds are monthly means; the calendar's July and January are what
        // a map of a season draws; and the calendar's half-years are what the snow balance and the
        // moisture march integrate across, because a degree-day sum over 182 days wants those 182
        // days' mean and not the peak of July. See `Season`.
        val summerTemperature =
            seasonalTemperature(config, temperature, zonal, marineFraction, Season.SUMMER)
        val winterTemperature =
            seasonalTemperature(config, temperature, zonal, marineFraction, Season.WINTER)
        val julyTemperature =
            seasonalTemperature(config, temperature, zonal, marineFraction, Season.JULY)
        val januaryTemperature =
            seasonalTemperature(config, temperature, zonal, marineFraction, Season.JANUARY)
        val julyHalfTemperature =
            seasonalTemperature(config, temperature, zonal, marineFraction, Season.JULY_HALF)
        val januaryHalfTemperature =
            seasonalTemperature(config, temperature, zonal, marineFraction, Season.JANUARY_HALF)

        // The water under the marine air, half-year by half-year. The ice test and the march's
        // evaporation are questions about the sea surface, not about the air over it: a sea
        // freezes when the water reaches -1.8, and what evaporates is water.
        val julyHalfSeaSurface =
            seaSurfaceTemperature(config, sea, zonal, julyHalfTemperature, Season.JULY_HALF)
        val januaryHalfSeaSurface =
            seaSurfaceTemperature(config, sea, zonal, januaryHalfTemperature, Season.JANUARY_HALF)

        val julyHalfSeaIce = seaIceMask(config, sea, julyHalfSeaSurface)
        val januaryHalfSeaIce = seaIceMask(config, sea, januaryHalfSeaSurface)
        // The pack an atlas draws is the one that survives the summer: frozen through the water's
        // own warmest half-year, wherever in the year the lag puts it, as Koppen's months are each
        // place's own. Not a calendar half, whose mean the lag leaves colder than the water's own
        // summer, so that it drew 95% of the winter pack as perennial; and not the warmest month,
        // because the model carries no latent heat to hold a frozen sea at its melting point, and
        // under the pole's summer sun the frozen column's warmest month passes it and opened the
        // pole while the sea twenty degrees from it stayed frozen. See docs/DESIGN_LEDGER.md, A1-1.
        val perennialSeaIce = seaIceMask(
            config, sea, seaSurfaceTemperature(
                config, sea, zonal,
                seasonalTemperature(config, temperature, zonal, marineFraction, Season.WARMEST_HALF),
                Season.WARMEST_HALF
            )
        )

        // The boundary layer's pressure and wind for each half-year, solved by the dry atmosphere
        // over this world's land, sea and terrain. Null when the pressure term is off, which is
        // what makes that setting a control rather than a near-miss: the belts are then the whole
        // wind.
        val atmosphere = if (climateConfig.pressureWinds) {
            atmosphere(config, sea, zonal, marineFraction, globalCoolingC)
        } else null

        // Each half's wind in meters a second: the march carries its water along the zonal
        // direction and across the rows at the meridional speed. The belts ride one thermal
        // equator, in the northern hemisphere in the half about July and in the southern in the
        // half about January.
        val slantRowsPerCell = slantRowsPerCell(config)
        val julyHalfWind = seasonWind(
            config, buildWind(cellsAcross, cellsDown, thermalEquatorDegrees(tiltDegrees, julyHalf = true), slantRowsPerCell),
            atmosphere?.julyHalf
        )
        val januaryHalfWind = seasonWind(
            config, buildWind(cellsAcross, cellsDown, thermalEquatorDegrees(tiltDegrees, julyHalf = false), slantRowsPerCell),
            atmosphere?.januaryHalf
        )

        // The stored wind is the annual one: what the wind view means by "the prevailing wind".
        // With the atmosphere it is the mean of the two halves' winds; without it, the belts about
        // the geographic equator, every cell of a row the same.
        val wind = if (atmosphere == null) {
            buildWind(cellsAcross, cellsDown, thermalEquatorDegrees = 0f, slantRowsPerCell)
        } else {
            val annual = PressureWind.Vectors(
                FloatArray(cellsAcross * cellsDown) {
                    (julyHalfWind.totalMps.eastwardMps[it] + januaryHalfWind.totalMps.eastwardMps[it]) * 0.5f
                },
                FloatArray(cellsAcross * cellsDown) {
                    (julyHalfWind.totalMps.southwardMps[it] + januaryHalfWind.totalMps.southwardMps[it]) * 0.5f
                }
            )
            marchWindOf(
                config, annual,
                buildWind(1, cellsDown, thermalEquatorDegrees = 0f, slantRowsPerCell).zonalShareOfRow
            )
        }

        // The marine inversion, a property of the season rather than of the parcel: where a cold
        // sea has put a stratus lid on the air. Null when its setting is off, so the march runs
        // without the term rather than with a zero in it.
        val seaSurfaceAnomalyC =
            if (climateConfig.marineInversion && config.ocean.enabled) ocean.anomaly else null
        val julyHalfInversion = MoistureBudget.inversionSuppression(
            config, sea, seaSurfaceAnomalyC, thermalEquatorDegrees(tiltDegrees, julyHalf = true)
        )
        val januaryHalfInversion = MoistureBudget.inversionSuppression(
            config, sea, seaSurfaceAnomalyC, thermalEquatorDegrees(tiltDegrees, julyHalf = false)
        )

        val obliquityDegrees = EnergyBalance.obliquityDegrees(tiltDegrees).toDouble()
        fun marchSeason(
            julyHalf: Boolean,
            airC: FloatField,
            waterC: FloatField,
            seaIce: BooleanArray,
            wind: SeasonWind,
            inversion: FloatField?
        ): MoistureMarch.Season {
            val totalWind = wind.totalMps
            // The water carries the current anomaly already, through the air it is built from.
            val seaSurfaceC = FloatArray(cellsAcross * cellsDown) { cell ->
                if (sea.isLand[cell]) airC.data[cell] else waterC.data[cell]
            }
            // The wind's speed through every gust and calm, which the sea's evaporation and the
            // ground's potential read; the belts' control keeps the march's own scalar winds.
            val solved = atmosphere != null
            val scalarAt10m = if (solved) FloatArray(cellsAcross * cellsDown) { cell ->
                BoundaryLayer.scalarWindAt10mMps(totalWind.eastwardMps[cell], totalWind.southwardMps[cell], sea.isLand[cell])
            } else null
            val at2m = if (solved) FloatArray(cellsAcross * cellsDown) { cell ->
                BoundaryLayer.windAt2mMps(totalWind.eastwardMps[cell], totalWind.southwardMps[cell])
            } else null
            return MoistureMarch.Season(
                airTemperatureC = airC.data,
                seaSurfaceC = seaSurfaceC,
                seaIce = seaIce,
                eastwardMps = totalWind.eastwardMps,
                southwardMps = totalWind.southwardMps,
                scalarWindAt10mMps = scalarAt10m,
                windAt2mMps = at2m,
                beltRainFactorOfRow = bands(cellsDown, climateConfig, julyHalf),
                inversionSuppression = inversion?.data,
                extraterrestrialOfRow = DoubleArray(cellsDown) { row ->
                    SurfaceEvaporation.halfYearExtraterrestrialMjPerM2Day(
                        latitudeOf(row, cellsDown).toDouble(), obliquityDegrees, julyHalf
                    )
                }
            )
        }
        val elevationM = FloatArray(cellsAcross * cellsDown) { cell ->
            if (sea.isLand[cell]) {
                config.scale.metresAboveShoreline(sea.relativeElevation.data[cell]).coerceAtLeast(0f)
            } else 0f
        }
        val marched = MoistureMarch.run(
            MoistureMarch.Inputs(
                config = config,
                isLand = sea.isLand,
                relativeElevation = sea.relativeElevation.data,
                elevationM = elevationM,
                julyHalf = marchSeason(
                    true, julyHalfTemperature, julyHalfSeaSurface, julyHalfSeaIce, julyHalfWind,
                    julyHalfInversion
                ),
                januaryHalf = marchSeason(
                    false, januaryHalfTemperature, januaryHalfSeaSurface, januaryHalfSeaIce,
                    januaryHalfWind, januaryHalfInversion
                ),
                lidElevation = config.scale.reliefShareOfMetres(MoistureBudget.INVERSION_LID_METRES),
                blurSigmaKm = RAIN_BLUR_SIGMA_KM
            ),
            ledger
        )

        return SeasonalFields(
            temperature = temperature,
            summerTemperature = summerTemperature,
            winterTemperature = winterTemperature,
            julyTemperature = julyTemperature,
            januaryTemperature = januaryTemperature,
            julyHalfTemperature = julyHalfTemperature,
            januaryHalfTemperature = januaryHalfTemperature,
            julyHalfPrecipitationMm = marched.julyHalfRainMm,
            januaryHalfPrecipitationMm = marched.januaryHalfRainMm,
            julyHalfSeaIce = julyHalfSeaIce,
            januaryHalfSeaIce = januaryHalfSeaIce,
            perennialSeaIce = perennialSeaIce,
            wind = wind,
            landOriginPrecipitationMm = marched.landOriginRainMm,
            potentialEvapotranspirationMm = marched.potentialEvapotranspirationMm,
            openWaterEvaporationMm = marched.openWaterEvaporationMm
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
     * colder than that all winter without the sea being frozen. The water carries the current
     * anomaly the ocean stage measured, through the air it is built from
     * ([carrySeaAnomalyIntoMarineAir]) — so a warm current keeps a polar sea open where its
     * latitude alone would freeze it, which is the Norwegian Sea, and a cold one closes a sea
     * further from the pole, which is the Labrador. The march reads the same water field, which is
     * what keeps the mask and the march from disagreeing about which cells are ice.
     *
     * Empty when `ClimateConfig.seaIce` is off, which is the control the guard needs.
     * See [ClimateResult.julyHalfSeaIce] for what the two masks are for.
     */
    private fun seaIceMask(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        seaSurface: FloatField
    ): BooleanArray {
        val frozen = BooleanArray(config.width * config.height)
        if (!config.climate.seaIce) return frozen
        for (cell in frozen.indices) {
            if (sea.isLand[cell]) continue
            frozen[cell] = seaSurface.data[cell] <= EnergyBalance.SEA_FREEZING_C
        }
        return frozen
    }

    /**
     * How much nearby water a land cell can feel: 1 in the open sea, fading to 0 over
     * `OceanConfig.coastalReachKm` inland.
     *
     * A blur of the land/sea mask rather than a distance transform — cheap, and it does what
     * [applyMaritimeInfluence] needs: land within reach of the coast reads high, land well beyond
     * it reads exactly 0 once the blur's support runs out. The marine blend needs the other
     * question answered — see [waterDistance] and [marineAirFraction].
     */
    internal fun waterExposure(config: WorldGenConfig, sea: SeaLevelResult): FloatField {
        val cellsAcross = config.width
        val cellsDown = config.height
        val radiusCells = config.wholeCellsFor(config.ocean.coastalReachKm)

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
     * Distance on the ground, in cell widths, from every land cell to the water of the nearest sea
     * cell — zero over water — off the same jump-flooded Euclidean distance field `SeaLevelStage`
     * uses for the continental shelf, told how tall a row is so that a coast facing north reaches
     * as far inland as one facing east. The land mask at this stage counts lakes as land, since
     * lakes are decided later by the rivers, so the water is the sea's.
     *
     * [marineAirFraction] reads this rather than the blurred water exposure above, because "how
     * exposed to water" and "how close to water" are not the same question at this radius: two
     * box-blur passes leave a cell right at the edge of `coastalReachKm` reading roughly 0.2
     * exposure, which would make a coast three quarters of the way to fully continental. An honest
     * distance says a shoreline cell is half a cell from water and one at 350 km is exactly that,
     * which is what a decay length measured in kilometres needs.
     *
     * To the water's edge, not to its centre, and the correction matters. The jump flood measures
     * centre to centre, so a cell whose own edge is the shoreline would come back a whole cell from
     * the sea — 78 km on the 512-wide grid and 39 on the 1024 — which made the same coast four
     * fifths maritime at one resolution and nine tenths at the other. The water starts at the near
     * side of the nearest sea cell, which is half a column away across a row and half a row away
     * down a column, and at its corner on a diagonal, so the distance is taken to the nearest point
     * of that cell rather than less a constant half.
     *
     * Internal rather than private so `ContinentalityTest` measures the same field the stage
     * actually used instead of re-deriving it and risking the two drifting apart.
     * See docs/DESIGN_LEDGER.md, A2, G4 and W1.
     */
    internal fun waterDistance(config: WorldGenConfig, sea: SeaLevelResult): FloatField {
        val cellsAcross = config.width
        val cellsDown = config.height
        val rowScale = config.cellHeightInCellWidths
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
        JumpFloodDistance.run(cellsAcross, cellsDown, distanceToWater, nearestWaterCell, rowScale)
        val field = FloatField(cellsAcross, cellsDown)
        for (cell in distanceToWater.indices) {
            val water = nearestWaterCell[cell]
            field.data[cell] = when {
                distanceToWater[cell] == JumpFloodDistance.INFINITE -> JumpFloodDistance.INFINITE
                water == cell -> 0f
                else -> toTheWatersEdgeCellWidths(cellsAcross, cell, water, rowScale).toFloat()
            }
        }
        return field
    }

    /**
     * From the centre of [cell] to the nearest point of sea cell [water], in cell widths on the
     * ground: the gap between the two cells' centres less the half of the water cell that faces
     * this one, along each axis.
     */
    private fun toTheWatersEdgeCellWidths(cellsAcross: Int, cell: Int, water: Int, rowScale: Double): Double {
        var columns = abs(cell % cellsAcross - water % cellsAcross)
        if (columns > cellsAcross - columns) columns = cellsAcross - columns
        val rows = abs(cell / cellsAcross - water / cellsAcross)
        val acrossCellWidths = (columns - HALF_A_CELL).coerceAtLeast(0.0)
        val downCellWidths = (rows - HALF_A_CELL).coerceAtLeast(0.0) * rowScale
        return sqrt(acrossCellWidths * acrossCellWidths + downCellWidths * downCellWidths)
    }

    /** Half a cell: from a cell's centre to its edge, along either axis, in that axis's cells. */
    private const val HALF_A_CELL = 0.5

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
    internal fun applyMaritimeInfluence(
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
            radius = config.wholeCellsFor(oceanConfig.coastalReachKm),
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
     * Gives the air over every sea cell the water's own departure from its latitude, the current
     * anomaly [OceanResult.anomaly], so the air over a cold current is cold and the air over a warm
     * one warm.
     *
     * Over Earth's open ocean the air follows the sea surface one for one: annual means at every
     * one-degree bin regress as `Tsst = 0.93 + 1.00 Tair` in ERA-40 and `0.63 + 1.00 Tair` in COADS,
     * the sea "generally" under a degree warmer "nearly everywhere" (Kara, Hurlburt and Loh 2007,
     * J. Geophys. Res. 112, C05020). Before A1-1 the air over the sea took its band's marine column
     * alone, so it stood at its latitude's temperature over the California Current and the Gulf
     * Stream alike, and a month's temperature across a west coast held only the land's column
     * against the sea's. Every season and the water under the air are built from this field, so
     * they carry the anomaly through it and nothing adds it a second time.
     */
    private fun carrySeaAnomalyIntoMarineAir(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        ocean: OceanResult,
        temperature: FloatField
    ) {
        if (!config.ocean.enabled) return
        parallelChunks(0, config.width * config.height) { startCell, endCell ->
            for (cell in startCell until endCell) {
                if (sea.isLand[cell]) continue
                temperature.data[cell] += ocean.anomaly.data[cell]
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
        val weatherLattice = GroundLattice(config, WEATHER_NOISE_WAVELENGTH_KM)
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
                        weatherLattice.x(column),
                        weatherLattice.y(row),
                        octaves = WEATHER_NOISE_OCTAVES,
                        periodX = weatherLattice.period,
                        periodY = weatherLattice.period
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
     * A calendar half-year's mean temperature, rebuilt from a finished world's annual field;
     * [season] is [Season.JULY_HALF] or [Season.JANUARY_HALF].
     *
     * `ClimateResult` stores the months, because those are what Koppen's gates and a reader want,
     * but the snow balance and the moisture march were run on the half-years — see [Season]. A
     * guard that needs to redo either of those computations asks for this rather than reaching for
     * the saved field and quietly measuring a different quantity.
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
     * A season's temperature, any reading of [Season], as a departure from the annual mean.
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
     * The prevailing wind as the wind view reads it: a zonal direction of ±1 and a slant in rows
     * per cell, both per cell, and the belts' own zonal wind per row as a share of
     * [PressureWind.BELT_SPEED_MPS], positive eastward.
     *
     * The belts' zonal wind is continuous across every belt edge and passes through zero there
     * ([SurfaceBelts.zonalShare]), so where the pressure field's departure is added to it the line
     * the total wind reverses along is the belts' edge moved by the land and sea under it, not a
     * latitude row. [zonal] is the sign of that total at each cell.
     */
    private class WindField(
        val zonal: IntArray,
        val meridional: FloatArray,
        val zonalShareOfRow: FloatArray
    )

    /**
     * The belts' slope, `ClimateConfig.meridionalWindShare`, as the march spends it: rows per cell
     * of zonal travel, `share × cellWidth / cellHeight`. Computed in double and rounded once, so on
     * an N by N grid, whose cells are exactly twice as wide as they are tall, 0.15 is the float 0.3
     * the setting held when it was counted in rows.
     */
    private fun slantRowsPerCell(config: WorldGenConfig): Float =
        (config.climate.meridionalWindShare.toDouble() *
            (config.scale.cellWidthKm(config.width) / config.scale.cellHeightKm(config.height))).toFloat()

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
     * The shape of a belt's meridional wind across it, as a multiple of the belt's mean: a half
     * sine between the two latitudes where it is zero, `pi/2` at the middle, so the mean over each
     * stretch is the mean `ClimateConfig.meridionalWindShare` states.
     *
     * Zero at every edge of a belt, the thermal equator, the subtropical high and the polar front,
     * all measured from the thermal equator, because an edge is where two cells' surface legs meet
     * or part, and a wind that kept its strength up to the edge and reversed there would pile the
     * water carried on it into the one row beside a converging edge and empty the row beside a
     * diverging one. Not zero at the geographic equator: each half-year is one moment of the
     * planet, so the winter hemisphere's trades blow across the equator to a thermal equator
     * standing in the summer hemisphere, which is the cross-equatorial leg of Earth's winter Hadley
     * cell.
     *
     * [latitude] is the row's latitude in degrees and [thermalEquatorDegrees] the latitude the
     * season's thermal equator stands at.
     */
    private fun meridionalProfile(latitude: Float, thermalEquatorDegrees: Float): Float {
        val fromThermalEquator = latitude - thermalEquatorDegrees
        val beltDegrees = abs(fromThermalEquator)
        // The pole on this row's side of the thermal equator, in degrees from it.
        val poleDegrees = if (fromThermalEquator >= 0f) {
            POLE_DEGREES - thermalEquatorDegrees
        } else {
            POLE_DEGREES + thermalEquatorDegrees
        }
        val zeros = floatArrayOf(
            0f,
            TRADE_BELT_EDGE_DEGREES.coerceAtMost(poleDegrees),
            WESTERLY_BELT_EDGE_DEGREES.coerceAtMost(poleDegrees),
            poleDegrees
        )
        for (segment in 0 until zeros.size - 1) {
            val near = zeros[segment]
            val far = zeros[segment + 1]
            if (beltDegrees < near || beltDegrees >= far || far <= near) continue
            return (PI / 2.0 * sin(PI * (beltDegrees - near) / (far - near))).toFloat()
        }
        return 0f
    }

    /**
     * The latitude a half-year's thermal equator stands at, in degrees: [tiltDegrees] into the
     * northern hemisphere in the half about July ([julyHalf]), as far into the southern in the
     * half about January.
     */
    internal fun thermalEquatorDegrees(tiltDegrees: Float, julyHalf: Boolean): Float =
        if (julyHalf) tiltDegrees else -tiltDegrees

    /**
     * Simplified three-cell circulation: polar easterlies, mid-latitude westerlies, and tropical
     * trade winds blowing east to west — each of them slanted across the latitude lines.
     *
     * The belts ride the thermal equator at [thermalEquatorDegrees], so in a hemisphere's summer
     * they sit that far poleward of their annual position and in its winter as far equatorward.
     * That migration is what puts a west coast at 35 degrees under the westerlies in winter and
     * under the trades in summer, which is the Mediterranean climate in one sentence.
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
        thermalEquatorDegrees: Float,
        slantRowsPerCell: Float
    ): WindField {
        val zonal = IntArray(cellsAcross * cellsDown)
        val meridional = FloatArray(cellsAcross * cellsDown)
        val zonalShareOfRow = FloatArray(cellsDown)
        for (row in 0 until cellsDown) {
            val latitude = latitudeOf(row, cellsDown)
            // Signed distance from the thermal equator, positive to its north.
            val fromThermalEquator = latitude - thermalEquatorDegrees
            val beltDegrees = abs(fromThermalEquator)
            // Away from the thermal equator, as a step in map coordinates: rows grow southward,
            // so north of it is the smaller row number.
            val outward = if (fromThermalEquator >= 0f) -1f else 1f
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
            } * slantRowsPerCell * meridionalProfile(latitude, thermalEquatorDegrees)
            // The belts' zonal wind itself, measured from the thermal equator as the direction is,
            // and continuous through zero at each edge where the direction steps.
            zonalShareOfRow[row] = SurfaceBelts.zonalShare(beltDegrees)
            for (column in 0 until cellsAcross) {
                zonal[row * cellsAcross + column] = zonalDirection
                meridional[row * cellsAcross + column] = slant
            }
        }
        return WindField(zonal, meridional, zonalShareOfRow)
    }

    /**
     * A half-year's wind from its belts and, when the pressure term is on, the boundary layer's
     * solved wind for that half ([BoundaryLayer]), whose zonal mean over the open sea is those
     * belts: the wind in metres a second and the direction-and-slant pair the march reads.
     *
     * Passing no [atmosphere] gives the belts alone, which is how `ClimateConfig.pressureWinds =
     * false` reproduces the belts' wind exactly: the solved wind is not computed at all.
     */
    private fun seasonWind(config: WorldGenConfig, belts: WindField, atmosphere: BoundaryLayer.Half?): SeasonWind {
        if (atmosphere == null) return SeasonWind(belts, beltWindMps(config, belts))
        val total = PressureWind.Vectors(atmosphere.eastwardMps, atmosphere.southwardMps)
        return SeasonWind(marchWindOf(config, total, belts.zonalShareOfRow), total)
    }

    /**
     * A season's wind: the direction-and-slant pair the stored wind is drawn from, and the wind
     * itself in meters a second, which the march carries its water on.
     */
    private class SeasonWind(val march: WindField, val totalMps: PressureWind.Vectors)

    /**
     * The belts' wind in metres a second, per cell: their zonal share of
     * [PressureWind.BELT_SPEED_MPS] and their slant turned back into a meridional speed.
     */
    private fun beltWindMps(config: WorldGenConfig, belts: WindField): PressureWind.Vectors {
        val cellsAcross = belts.meridional.size / belts.zonalShareOfRow.size
        val cellsDown = belts.zonalShareOfRow.size
        val cellHeightOverWidth =
            (config.scale.cellHeightKm(config.height) / config.scale.cellWidthKm(config.width)).toFloat()
        val eastward = FloatArray(cellsAcross * cellsDown)
        val southward = FloatArray(cellsAcross * cellsDown)
        parallelChunks(0, cellsDown) { startRow, endRow ->
            for (cell in startRow * cellsAcross until endRow * cellsAcross) {
                eastward[cell] = belts.zonalShareOfRow[cell / cellsAcross] * PressureWind.BELT_SPEED_MPS
                southward[cell] = belts.meridional[cell] * PressureWind.BELT_SPEED_MPS * cellHeightOverWidth
            }
        }
        return PressureWind.Vectors(eastward, southward)
    }

    /**
     * A half-year's belts on each row of the map, metres a second, eastward and southward: the
     * zonal-mean surface wind the boundary layer's pressure is built from ([BoundaryLayer]).
     */
    internal fun beltWindOfRows(config: WorldGenConfig, julyHalf: Boolean): PressureWind.Vectors {
        val climateConfig = config.climate
        val tiltDegrees = if (climateConfig.seasons) climateConfig.seasonalTiltDegrees else 0f
        val belts = buildWind(1, config.height, thermalEquatorDegrees(tiltDegrees, julyHalf), slantRowsPerCell(config))
        return beltWindMps(config, belts)
    }

    /**
     * The boundary layer's last answer and what it was asked: the ocean's stress and the climate
     * read the same world's atmosphere one after the other, and it is the same one.
     */
    private class SolvedAtmosphere(
        val config: WorldGenConfig,
        val sea: SeaLevelResult,
        val globalCoolingC: Float,
        val atmosphere: BoundaryLayer.Atmosphere
    )

    @kotlin.concurrent.Volatile
    private var lastAtmosphere: SolvedAtmosphere? = null

    /**
     * The boundary layer of [sea] under the energy balance [zonal] (solved at [globalCoolingC]) and
     * [marineFraction], both halves ([BoundaryLayer.solve]). The last one solved is kept: it is a
     * pure function of the configuration, the sea result and the cooling, and the ocean's stress
     * and the climate ask for the same one in turn.
     */
    internal fun atmosphere(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        zonal: ZonalClimate,
        marineFraction: FloatField,
        globalCoolingC: Float = 0f
    ): BoundaryLayer.Atmosphere {
        lastAtmosphere?.let { last ->
            if (last.sea === sea && last.globalCoolingC == globalCoolingC && last.config == config) return last.atmosphere
        }
        val solved = BoundaryLayer.solve(
            config, sea, zonal, marineFraction,
            beltWindOfRows(config, julyHalf = true), beltWindOfRows(config, julyHalf = false)
        )
        lastAtmosphere = SolvedAtmosphere(config, sea, globalCoolingC, solved)
        return solved
    }

    /** [atmosphere] for a world's own sea under today's energy balance. */
    internal fun atmosphere(config: WorldGenConfig, sea: SeaLevelResult): BoundaryLayer.Atmosphere =
        atmosphere(config, sea, zonalClimate(config, sea), marineAirFraction(config, sea))

    /** A wind in metres a second as the march reads it: a zonal direction and a slant in rows. */
    private fun marchWindOf(
        config: WorldGenConfig,
        wind: PressureWind.Vectors,
        zonalShareOfRow: FloatArray
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
        return WindField(zonal, meridional, zonalShareOfRow)
    }

    /**
     * The surface wind of one calendar half-year over a finished world, in metres a second,
     * eastward and southward - the field `PressureWindTest` measures against its coastlines;
     * [season] is [Season.JULY_HALF] or [Season.JANUARY_HALF].
     *
     * Rebuilt rather than stored: only the annual wind is saved, and a guard that read the annual
     * wind would be asking a question about the year when the question is about July. It is a pure
     * function of the configuration and the sea result ([atmosphere]), so the field it returns is
     * the one the season's march actually followed.
     */
    internal fun seasonalSurfaceWindMps(config: WorldGenConfig, sea: SeaLevelResult, season: Season): PressureWind.Vectors {
        val julyHalf = season == Season.JULY_HALF || season == Season.JULY
        if (!config.climate.pressureWinds) {
            val tiltDegrees = if (config.climate.seasons) config.climate.seasonalTiltDegrees else 0f
            val belts = buildWind(config.width, config.height, thermalEquatorDegrees(tiltDegrees, julyHalf), slantRowsPerCell(config))
            return beltWindMps(config, belts)
        }
        val half = atmosphere(config, sea).half(julyHalf)
        return PressureWind.Vectors(half.eastwardMps, half.southwardMps)
    }

    /** The circulation belt each row sits in for a half-year, precomputed per row. */
    private fun bands(cellsDown: Int, climate: ClimateConfig, julyHalf: Boolean): FloatArray =
        FloatArray(cellsDown) { row -> seasonalBand(latitudeOf(row, cellsDown), climate, julyHalf) }

    /**
     * The belt factor the march applies at a latitude in one calendar half-year, the half about
     * July when [julyHalf] — shifted with the thermal equator and sharpened.
     *
     * Exposed so a diagnostic can report the number that was actually applied rather than a copy
     * of the formula that drifts away from it, which is what `DesertCauseTest`'s own copy had
     * already done before seasons made the question harder.
     */
    internal fun seasonalBand(latitude: Float, climate: ClimateConfig, julyHalf: Boolean): Float {
        val tiltDegrees = if (climate.seasons) climate.seasonalTiltDegrees else 0f
        val fromThermalEquator = abs(latitude - thermalEquatorDegrees(tiltDegrees, julyHalf))
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
     * The march's water budget for a world, term by term, lap by lap: the same two seasonal
     * marches [generate] runs, with a [MoistureLedger] handed in to be filled. Nothing the march
     * computes is changed by the ledger, so this is a measurement of the world [generate] makes.
     */
    internal fun moistureLedger(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        ocean: OceanResult
    ): MoistureLedger = MoistureLedger().also { seasonalFields(config, sea, ocean, ledger = it) }

    /**
     * How much the circulation belt speeds or slows the rain, at a given distance from the thermal
     * equator: a multiplier on the rate of every sink the march charges (the column's rain, the
     * cloud's conversion to rain and the convergence closure), 1 being an unremarkable latitude.
     *
     * Three bumps, each centred where the atmosphere actually puts it: the dry descending air of
     * the horse latitudes near 30, the wet mid-latitude storm track near 55, and the polar cell's
     * own dry descent at the pole. The march has no vertical motion of its own, so the descent is
     * stated here, as a modulation of the rain's rates; the ascent at the ITCZ is not, because the
     * transport gathers the trades' water there and the column's own humidity rains it.
     *
     * Taken from the *thermal* equator rather than the geographic one, which is what lets the
     * whole system migrate with the season: the same row sits under the dry descending limb in
     * one half of the year and under the storm track in the other, and that is the Mediterranean
     * climate and the monsoon both.
     */
    internal fun latitudeBandAt(
        fromThermalEquatorDegrees: Float,
        subtropicalDryness: Float = 1.15f,
        /** See [seasonalBandSharpness]. One leaves every band as it was. */
        sharpness: Float = 1f
    ): Float = smoothFloor(1f + sharpness * bandAnomaly(fromThermalEquatorDegrees, subtropicalDryness))

    /**
     * [value] where it stands well above [MIN_BAND], and a floor it approaches without a kink
     * where it falls toward or below it: the larger root of `f^2 - value f - MIN_BAND^2 = 0`,
     * `(value + sqrt(value^2 + 4 MIN_BAND^2)) / 2`, which is a hyperbola whose asymptotes are the
     * value and zero. At a value of one it reads 1.0025, at zero `MIN_BAND`, at minus one 0.0025.
     */
    private fun smoothFloor(value: Float): Float =
        ((value + sqrt(value * value + 4f * MIN_BAND * MIN_BAND)) * 0.5f)

    /**
     * The belts' departure from an unremarkable rain lifetime at a given distance from the
     * thermal equator, which is what a season sharpens. The same three bumps as [latitudeBandAt],
     * without its baseline of one and without its floor.
     */
    private fun bandAnomaly(fromThermalEquatorDegrees: Float, subtropicalDryness: Float): Float =
        -subtropicalDryness * bell(
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
        /** The perennial pack, frozen through its own warmest half-year: see [ClimateResult.julyHalfSeaIce]. */
        perennialSeaIce: BooleanArray,
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
                // here. The perennial pack rather than the winter one, because what an atlas draws
                // as ice is the pack that is still there at the end of its own summer.
                if (perennialSeaIce[cell]) Biome.ICE_SHEET
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
                    SnowBalance.isGlaciated(snowBalance.data[cell])
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
     * [summerShare]/[winterShare] *and* [summerMm]/[winterMm] rather than the ratio alone. Cold
     * air's smaller saturated column holds winter moisture down far more than summer's everywhere
     * cold —
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
