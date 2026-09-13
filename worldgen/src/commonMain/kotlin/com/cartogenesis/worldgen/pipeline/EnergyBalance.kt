package com.cartogenesis.worldgen.pipeline

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tan

/**
 * The temperature of every latitude in each half of the year, solved rather than drawn.
 *
 * The band arrays are one entry per latitude band, north to south, at the centre latitudes
 * [EnergyBalance.latitudeOfBand] gives; every temperature is in degrees Celsius at sea level.
 * `land` and `sea` are the two surfaces the model carries in each band: the same insolation and
 * the same imported heat, but heat capacities that differ by a factor of fifteen, which is the
 * whole of why a continent has a winter and an ocean has a cool spell.
 *
 * "Summer" and "winter" are the band's own warmest and coldest contiguous half-year rather than
 * calendar halves, so a southern band's summer is the southern one and a maritime column's summer
 * runs later than the land's beside it — the lag heat capacity produces. Each pair averages
 * exactly to its annual figure, because the two windows are complements.
 */
class ZonalClimate internal constructor(
    val landAnnualC: FloatArray,
    val landSummerC: FloatArray,
    val landWinterC: FloatArray,
    val seaAnnualC: FloatArray,
    val seaSummerC: FloatArray,
    val seaWinterC: FloatArray,
    /** Area-weighted mean of the annual field over the whole planet, in degrees Celsius. */
    val globalMeanC: Float,
    /**
     * How far the last simulated year's global mean moved from the year before it, in degrees
     * Celsius: the residual drift left in the spin-up, reported by the guards rather than acted on.
     */
    val spinUpResidualC: Float
) {

    /** The land temperature at [latitudeDegrees], interpolated between band centres. */
    fun landC(latitudeDegrees: Float, season: Season): Float =
        interpolate(seasonOf(landAnnualC, landSummerC, landWinterC, season), latitudeDegrees)

    /** The sea-surface temperature at [latitudeDegrees], interpolated between band centres. */
    fun seaC(latitudeDegrees: Float, season: Season): Float =
        interpolate(seasonOf(seaAnnualC, seaSummerC, seaWinterC, season), latitudeDegrees)

    private fun seasonOf(
        annual: FloatArray,
        summer: FloatArray,
        winter: FloatArray,
        season: Season
    ): FloatArray = when (season) {
        Season.ANNUAL -> annual
        Season.SUMMER -> summer
        Season.WINTER -> winter
    }

    /**
     * A band array read at an arbitrary latitude, linearly between the two band centres either
     * side of it and flat beyond the outermost centres.
     *
     * Interpolated rather than nearest-band because the map has up to eight times as many rows as
     * the model has bands, and a nearest-band lookup would draw the band edges onto the map as
     * horizontal steps in the temperature.
     */
    private fun interpolate(bandValues: FloatArray, latitudeDegrees: Float): Float {
        val bands = bandValues.size
        val position = (EnergyBalance.POLE_DEGREES - latitudeDegrees) *
            bands / EnergyBalance.POLE_TO_POLE_DEGREES - 0.5f
        val lower = position.toInt().coerceIn(0, bands - 1)
        val upper = (lower + 1).coerceAtMost(bands - 1)
        val within = (position - lower).coerceIn(0f, 1f)
        return bandValues[lower] + (bandValues[upper] - bandValues[lower]) * within
    }
}

/** Which of the three fields a [ZonalClimate] lookup wants. */
enum class Season { ANNUAL, SUMMER, WINTER }

/**
 * A one-dimensional energy-balance model of the atmosphere, solved per season.
 *
 * ### What it replaces
 *
 * A latitude curve with an exponent and two anchors can be made to pass through Earth's equator
 * and Earth's pole, but it cannot do the two things that decide where the interesting climates
 * are. It has no **ice-albedo feedback** — a snow-covered band is colder *because it is white*,
 * which is why polar climates are self-reinforcing, why a cap can be held or lost, and why a
 * cooling is amplified at the poles and barely felt at the equator. And it has no **heat
 * capacity** — the reason a continent has a winter and the ocean beside it does not is that the
 * ocean stores half a year of sunshine in its mixed layer and the land stores a fortnight of it in
 * three metres of soil. Both fall out of the model below; neither can be written as a curve.
 *
 * ### The model
 *
 * Budyko (1969), Sellers (1969) and North (1975): a heat budget per latitude band,
 *
 * ```
 * C dT/dt = Q(latitude, day) (1 - albedo) - (A + B T) + (1/cos p) d/dp [ D cos p dT/dp ]
 * ```
 *
 * with `p` the latitude in radians. The three terms are the sunlight the band absorbs, the
 * infrared it radiates to space ([OUTGOING_OFFSET_W_PER_M2] and
 * [OUTGOING_PER_DEGREE_W_PER_M2_C], North, Cahalan and Coakley's 1981 fit to satellite
 * radiances), and the heat the atmosphere and ocean carry into it from its neighbours
 * ([DIFFUSION_TROPICS_W_PER_M2_C], which falls off toward the poles). The insolation `Q` is
 * the astronomical daily mean at that latitude
 * and that day, so the seasons are the planet's tilt rather than a prescribed migration.
 *
 * ### Two columns per band
 *
 * Each band carries a land column and a sea column with the band's own land fraction as their
 * areas. They see the same sunlight and share the heat their neighbours send, but they store it
 * in [LAND_HEAT_CAPACITY_J_PER_M2_C] and [OCEAN_HEAT_CAPACITY_J_PER_M2_C], which differ by a
 * factor of fifteen. That is the entire land-sea contrast: no continentality setting, no damping
 * factor, only the two capacities and the world's own geography deciding how much of each band is
 * which.
 *
 * The heat a band trades with its neighbours moves both of its columns by the same number of
 * degrees, because the air that carries it has already gone round the latitude circle several
 * times over. So what separates the two columns is only their own radiation budgets, and their
 * annual means come out within a degree of each other while their years do not — which is the
 * observed thing: Bergen and Yakutsk differ far more in January than they do over the year.
 *
 * The simplification is that a band has no *internal* geography. A band that is one percent island
 * still carries a fully continental land column, because nothing tells it that its land is
 * surrounded by its sea. What saves the map from that is the marine blend `ClimateStage` applies
 * when it reads these bands onto cells: an island is entirely within reach of the water and takes
 * the sea column's year.
 *
 * ### The feedback
 *
 * The albedo is not a constant: a band whose year is below the ice line is white
 * ([FROZEN_ALBEDO]) and one above it is not ([ICE_FREE_ALBEDO_BASE]), over a ramp a few degrees
 * wide either side of [ICE_LINE_C], because a band's mean being a degree below the line does not
 * mean every day and every square kilometre of it was. So
 * the model can grow a cap and can lose one, and a forcing applied uniformly comes out
 * polar-amplified without anyone writing the amplification down. That is what
 * [solarScaleForCooling] exists to exploit.
 */
object EnergyBalance {

    /** Latitude of a pole in degrees, and the span from one to the other. */
    internal const val POLE_DEGREES = 90f
    internal const val POLE_TO_POLE_DEGREES = 180f

    /**
     * Latitude bands the planet is divided into, pole to pole.
     *
     * 240 puts a band every three quarters of a degree, which resolves the ice edge to about
     * 80 km — finer than a cell of the coarsest grid the generator draws — while keeping the whole
     * solve at a few hundred thousand arithmetic steps. The model's cost does not grow with the
     * map's resolution, because it does not know the map exists.
     */
    const val BANDS = 240

    /**
     * Time steps per simulated year.
     *
     * 360 rather than 365 so that a half-year is exactly 180 steps and the warm and cold halves
     * of the year are true complements: their means then average to the annual mean exactly,
     * which several things downstream assume. A step is a little over a day, and the diffusion is
     * solved implicitly, so nothing here is near a stability limit.
     */
    private const val STEPS_PER_YEAR = 360

    /** Steps in half a year, which is the window a season's mean is taken over. */
    private const val SEASON_STEPS = STEPS_PER_YEAR / 2

    /**
     * Years the model is marched before the last one is measured.
     *
     * The ocean column's memory is its heat capacity over the radiative damping,
     * `2.05e8 / 2.09` seconds, or three and a quarter years; the land's is eleven weeks. Starting
     * from the ice-free annual-mean equilibrium ([annualMeanEquilibrium]) rather than from a guess
     * removes all of the drift except the cap the model then grows, and twenty years is six of the
     * ocean's own time constants against that remainder — measured, it leaves three thousandths of
     * a degree. [ZonalClimate.spinUpResidualC] reports what is left, and the guards print it.
     */
    private const val SPIN_UP_YEARS = 20

    /** Seconds in a Julian year, which is what a time step is a three-hundred-and-sixtieth of. */
    private const val SECONDS_PER_YEAR = 3.15576e7

    /**
     * The solar constant, in watts per square metre: Kopp and Lean (2011), *A new, lower value of
     * total solar irradiance*, Geophys. Res. Lett. 38.
     */
    private const val SOLAR_CONSTANT_W_PER_M2 = 1361.0

    /**
     * Outgoing longwave radiation as `A + B T` in watts per square metre, with `T` in degrees
     * Celsius: North, Cahalan and Coakley (1981), *Energy balance climate models*,
     * Rev. Geophys. 19, fitting satellite radiances against surface temperature.
     *
     * These two numbers carry the greenhouse effect between them. The slope is North's: 2.09 W/m2
     * for every degree, which is the sensitivity and is the better constrained half. The offset is
     * then fixed by Earth's own energy budget rather than copied — Earth absorbs 240 W/m2 and sits
     * at 14 C, so `A = 240 - 2.09 x 14 = 210.7`.
     *
     * North's own 203.3 is not used, and the reason is worth stating: it was fitted alongside a
     * flat planetary albedo of 0.32, and with the observed albedo distribution below — which
     * absorbs more, because the tropics are darker than the mean and get most of the sunlight — it
     * leaves the planet four degrees too warm. Taking one paper's `A` with another's albedo is the
     * mistake; the budget is the arbiter.
     */
    private const val OUTGOING_OFFSET_W_PER_M2 = 210.7
    private const val OUTGOING_PER_DEGREE_W_PER_M2_C = 2.09

    /**
     * Meridional heat transport as a diffusivity, in watts per square metre per degree Celsius.
     *
     * The published range for this parameter is 0.38 to 0.67 (North 1975 fits 0.382 against the
     * annual mean; North, Cahalan and Coakley 1981 tabulate values up to about 0.67 depending on
     * what the model is asked to reproduce), and every value in it trades one end of the planet
     * against the other: at 0.60 the model reads 26.4 C at the equator and -2.2 at 60 degrees,
     * both right, but 7.7 at 45 where Earth has 12; raise it to 0.65 and 45 gains less than a
     * degree while the pole runs away. The shape is wrong, not the size. Measured as transport
     * rather than as temperature, the constant-D model carries 3.2 PW across 30 degrees where
     * Earth carries 5.3, and about the right amount across 80 — it under-transports in the
     * subtropics and mid-latitudes and nowhere else.
     *
     * **Why `cos^2`.** Earth does not carry its heat poleward by one mechanism. The tropics have
     * the Hadley circulation, a far more efficient conveyor than any eddy and what keeps the
     * tropical troposphere nearly isothermal; the mid-latitudes have baroclinic eddies; the polar
     * cap has weak ones. The total northward transport peaks at about 5.5 PW near 35 degrees and
     * falls to zero at both ends (Trenberth and Caron, *Estimates of meridional atmosphere and
     * ocean heat transports*, J. Climate 14, 2001). A diffusivity falling off as the cosine
     * squared is the simplest shape with that property, and giving an energy-balance model a
     * latitude-dependent diffusion for exactly this reason goes back to Lindzen and Farrell
     * (*Some realistic modifications of simple climate models*, J. Atmos. Sci. 34, 1977).
     *
     * **The two numbers.** Swept against Earth's own land and sea profiles at 0, 20, 40, 60 and
     * 80 degrees, 0.90 and 0.50 is the pair that lands on them; `EnergyBalanceTest` states what it
     * produces at each. The polar figure sits inside the published range for a constant
     * diffusivity; the tropical one is above it, which is what a number standing for the Hadley
     * cell rather than for an eddy should be.
     *
     * It is the smaller of W1's two second-pass corrections and it is honest to say so. The larger
     * was the albedo below, whose shape was wrong; with that fixed and the diffusivity left
     * constant at 0.60 the profile is already close, and what the latitude dependence then buys is
     * the equator (26.3 C against 28.1 for a constant one, where Earth has 26), 40 degrees (14.2
     * against 13.0, where Earth has 14.5) and the cold-season ice edge (59.6 degrees against 56.6,
     * where Earth's is 60). It costs half a degree of warmth at 60 and 80.
     */
    internal const val DIFFUSION_TROPICS_W_PER_M2_C = 0.90
    internal const val DIFFUSION_POLAR_W_PER_M2_C = 0.50

    /**
     * Planetary albedo of an ice-free surface: a clear-sky base that climbs toward the poles, plus
     * the two cloud belts the circulation puts on it. And the albedo of a frozen surface.
     *
     * Planetary rather than surface figures, because [OUTGOING_OFFSET_W_PER_M2] and its slope are
     * measured at the top of the atmosphere: what matters here is what the whole column, cloud
     * included, sends back.
     *
     * **The cloud belts are not decoration; they are why Earth's tropics are isothermal.** Earth's
     * observed planetary albedo (CERES EBAF, annual zonal means) does not fall monotonically toward
     * the equator: it reads 0.27 *at* the equator, dips to 0.235 near 20 degrees, and climbs again
     * to 0.28 at 40 and 0.33 at 50. The dip is the subtropical highs, which are the clearest, driest
     * skies over the darkest water on the planet; the two rises are the ITCZ's towering cloud and
     * the mid-latitude storm track's. The consequence is that the subtropics *absorb as much as the
     * equator does* — 305 W/m2 against 304 — which is why Earth has 26 C at the equator and 25 at
     * 20 degrees, a drop of one.
     *
     * A monotonic form cannot have that. Written as `0.30 + 0.09 P2(sin latitude)`, the Legendre
     * shape these models are usually given, the albedo is *lowest* at the equator, the subtropics
     * absorb 21 W/m2 less than the equator, and the model came out at 26.2 C on the equator against
     * 22.0 at 20 degrees where Earth has 26 and 25 — the whole tropics tilted, and every latitude
     * poleward of it dragged down with them. Flat at 0.32 was worse again, four degrees cold on the
     * equator itself.
     *
     * So the ITCZ's cloud is written down, at the latitude and width `ClimateStage` already puts
     * its own rain belt on, because it is the same circulation.
     *
     * **And the clear sky needs two terms, not one.** A single `sin^2` climb was tried and cannot
     * carry the poleward rise: fitted to the tropics it leaves the planet's global albedo at 0.271
     * against Earth's 0.284 once its ice is taken out (Earth's observed 0.294 less the 3.3 W/m2 of
     * shortwave forcing Flanner et al. 2011 attribute to the cryosphere), and the model came out
     * four degrees warm everywhere. The reason is the solar zenith angle: a clear ocean reflects
     * 0.06 with the sun overhead and more than 0.25 at a slant, a rise that is late and steep and
     * that a quadratic cannot make. Written as a `sin^2` term that *dips* — the subtropics really
     * are darker than the equator — and a `sin^4` term that climbs, it can.
     *
     * The four numbers are one least-squares fit to seven observations: the six ice-free zonal
     * values (0.270 at 0 degrees, 0.258 at 10, 0.235 at 20, 0.245 at 30, 0.280 at 40, 0.330 at 50)
     * and the ice-free global mean of 0.284, weighted by the insolation as a planetary albedo is.
     * No residual is over 0.005. The fit is then checked where it was not constrained and is not
     * adjusted to what it finds: it puts 60 degrees at 0.405 against the observed 0.400 and 70 at
     * 0.479 against 0.480, and falls short at 80 and 90 — 0.535 and 0.556 against 0.580 and 0.620 —
     * by about what the term below adds when the sea there freezes.
     *
     * Frozen is North's 0.62, and it replaces the ice-free figure rather than adding to it.
     */
    private const val ICE_FREE_ALBEDO_BASE = 0.239
    private const val ICE_FREE_ALBEDO_SUBTROPICAL_DIP = -0.067
    private const val ICE_FREE_ALBEDO_AT_A_SLANT = 0.384
    private const val CLOUD_ALBEDO_ITCZ = 0.033
    private const val FROZEN_ALBEDO = 0.62

    /**
     * Where the ITCZ's cloud belt sits and how wide it is, in degrees.
     *
     * `ClimateStage.ITCZ_DEGREES` and `ITCZ_WIDTH_DEGREES` hold the same two numbers, because the
     * belt that rains is the belt that reflects. They are repeated rather than shared because that
     * stage's are the *thermal* equator's, migrating with the season, and these are the annual
     * mean's: the albedo here is read on the annual mean, so a migrating belt would average to a
     * wider, shallower one and the fit above would not be to what it was fitted on.
     *
     * A storm-track belt was fitted alongside and came out at -0.006, which is nothing: the
     * zenith-angle rise already carries the mid-latitudes, so the term is not here.
     */
    private const val CLOUD_ITCZ_DEGREES = 0.0
    private const val CLOUD_ITCZ_WIDTH_DEGREES = 12.0

    /**
     * The spread of temperature within one band and one year, in degrees Celsius: how far either
     * side of [ICE_LINE_C] a column's white share ramps from none to all.
     *
     * 4.5, the standard deviation of temperature about a mean that [SnowBalance.PDD_SIGMA_C]
     * already uses for the same reason (Reeh 1991 takes 4.5 for Greenland; Calov and Greve 5.0). A
     * band is three quarters of a degree of latitude but it circles the planet, so a mean a degree
     * below the line describes a surface that was partly white, not a white one — and a step
     * function there would put the ice edge on an isotherm and let the model chatter between two
     * states instead of settling.
     */
    private const val ICE_LINE_SPREAD_C = 4.5

    /**
     * The temperature at which sea water freezes, in degrees Celsius: -1.8, the freezing point of
     * sea water at the ocean's mean salinity of 35 practical salinity units (UNESCO 1978).
     *
     * The sea-ice mask `ClimateStage` saves is this same figure applied per cell; here it is where
     * the sea column's albedo turns.
     */
    const val SEA_FREEZING_C = -1.8f

    /**
     * The annual mean below which a column goes white, in degrees Celsius: -10.
     *
     * Budyko's (1969) and North's (1975) own ice line, and their reason for it: -10 C is where the
     * observed snow and ice cover becomes *permanent* enough to change what the planet reflects.
     *
     * Read on the **annual** mean rather than on the season, which is the same construction the
     * literature uses and matters more here than it does there. A seasonal albedo whitens half the
     * northern land every winter — the sun is low then, but not so low that the planet can afford
     * it — and costs the model some eight degrees of global mean, carrying it over the edge into a
     * frozen planet. Worse, a seasonal albedo on a threshold is a relaxation oscillator: a land
     * column at 36 south, weakly tethered to the ocean around it, was measured cycling nine degrees
     * a year on an unforced planet with an upright axis, whitening and darkening on its own.
     *
     * The cost of the annual mean is that seasonal sea ice reflects nothing here. The sea-ice mask
     * `ClimateStage` saves *is* seasonal, and it is where a season's sea surface is below
     * [SEA_FREEZING_C]; it feeds the moisture march and the biome, but not this model's albedo.
     */
    private const val ICE_LINE_C = -10.0

    /**
     * Heat stored per square metre of land for each degree it warms, in joules.
     *
     * Two parts. The soil that a year's temperature wave actually reaches is about three metres
     * deep — the seasonal damping depth for ordinary ground, from a thermal diffusivity near
     * 7e-7 m2/s — and holds some 2.4 MJ per cubic metre per degree, so 7e6. The air column above
     * it holds `c_p x p / g = 1004 x 101325 / 9.81`, another 1.04e7. Together 1.7e7, which is the
     * 0.2-0.5 W yr/m2/C the energy-balance literature quotes for land.
     */
    private const val LAND_HEAT_CAPACITY_J_PER_M2_C = 1.7e7

    /**
     * Heat stored per square metre of open sea for each degree it warms, in joules.
     *
     * A 50 m mixed layer of sea water at 4.0 MJ per cubic metre per degree, plus the same air
     * column as the land. Fifty metres is the depth the seasonal cycle actually stirs: de Boyer
     * Montegut et al. (2004) put the global mean mixed layer near 60 m over the year, with a
     * summer minimum of 20-30 m and a deep winter tail that the seasonal wave never fills.
     *
     * Twelve times the land's, and that ratio is the land-sea contrast in one number.
     */
    private const val OCEAN_HEAT_CAPACITY_J_PER_M2_C = 50.0 * 4.0e6 + 1.04e7

    /**
     * How fast a band's land and its sea trade heat with each other, in watts per square metre per
     * degree of difference between them.
     *
     * Not a published figure: a one-dimensional model with two columns per band is not a standard
     * construction, and the literature's seasonal energy-balance models with continents
     * (North, Mengel and Short 1983) are two-dimensional, where the same diffusivity that carries
     * heat poleward also carries it across a coast. So this is set from what Earth measures. At
     * 50-60 degrees a continental interior swings 34-38 C over the year (Novosibirsk 34,
     * Winnipeg 38) and the open ocean 6-8; the exchange is the value that puts the model's own
     * land column inside the first of those, and it is 3.5: the model then reads 35.6 C over the
     * land there and 5.6 over the sea.
     *
     * Small against [OUTGOING_PER_DEGREE_W_PER_M2_C]'s 2.09 and much smaller than the meridional
     * diffusivity, which is right and is why continents have winters: the anomaly that separates a
     * January in Siberia from a January in the North Atlantic lives in the lowest kilometre or two
     * of air, not in the whole column that the westerlies carry round the planet in a fortnight.
     *
     * The check that it is not merely a fit is that nothing at any other latitude was asked of it,
     * and `EnergyBalanceTest` measures the model's land column at 30-40 degrees against Earth's
     * own continental range there.
     */
    private const val ZONAL_EXCHANGE_W_PER_M2_C = 3.5

    /**
     * Heat stored per square metre of *frozen* sea, in joules per degree.
     *
     * Sea ice caps the mixed layer off: the water below it stays near its freezing point and the
     * air above it is talking to a metre or two of ice, not to fifty metres of ocean. So a frozen
     * sea has a land's memory, which is why a polar winter under ice is as cold as a continental
     * one and why the ice edge advances as fast as it does. Two metres of ice at 1.9 MJ per cubic
     * metre per degree, plus the air column.
     */
    private const val FROZEN_SEA_HEAT_CAPACITY_J_PER_M2_C = 2.0 * 1.9e6 + 1.04e7

    /**
     * Earth's axial tilt in degrees (IAU), and the zonal-mean migration of its thermal equator
     * over a year.
     *
     * The pair converts `ClimateConfig.seasonalTiltDegrees` — how far this world's thermal equator
     * wanders, which is the number the wind and rain belts have always been built on — into the
     * obliquity that produces it, so one setting drives both the belts and the sunlight. Earth's
     * zonal-mean thermal equator swings roughly ten degrees over the year, which is the setting's
     * default, and Earth's tilt is 23.44: a world given twice the migration is given twice the
     * tilt, and a world given none has no seasons at all because its axis is upright.
     */
    private const val EARTH_OBLIQUITY_DEGREES = 23.44f
    private const val EARTH_THERMAL_EQUATOR_MIGRATION_DEGREES = 10f

    /**
     * Secant passes [solarScaleForCooling] may take, and how close to the asked-for cooling it
     * settles for, in degrees Celsius.
     *
     * The response of the global mean to the sun is smooth and nearly straight over the range a
     * glacial forcing needs, so the secant lands inside a twentieth of a degree in three or four
     * passes; eight is a ceiling rather than a count.
     */
    private const val MAX_SECANT_PASSES = 8
    private const val SECANT_TOLERANCE_C = 0.05

    /**
     * The least the sun may be dimmed to.
     *
     * Dim the sun by much more than a tenth and the ice-albedo feedback runs away: every solution
     * past that is a frozen planet at about -35 C. The bound keeps a nonsensical setting from
     * asking for one and keeps [solarScaleForCooling] from wandering there while it iterates. A
     * six-degree glacial cooling, for scale, needs three per cent.
     */
    private const val MIN_SOLAR_SCALE = 0.90

    /** The centre latitude of a band, in degrees: +90 at the top, -90 at the bottom. */
    fun latitudeOfBand(band: Int): Float =
        POLE_DEGREES - POLE_TO_POLE_DEGREES * (band + 0.5f) / BANDS

    /**
     * The obliquity in degrees a world has, given how far its thermal equator migrates over the
     * year. See [EARTH_OBLIQUITY_DEGREES].
     */
    fun obliquityDegrees(thermalEquatorMigrationDegrees: Float): Float =
        thermalEquatorMigrationDegrees *
            (EARTH_OBLIQUITY_DEGREES / EARTH_THERMAL_EQUATOR_MIGRATION_DEGREES)

    /**
     * What share of each latitude band is land, from the map's own coastline.
     *
     * One entry per band, 0 for a band of open ocean and 1 for one that circles the planet in
     * land. Rows are pooled into the band their centre latitude falls in, which is exact when the
     * map has more rows than the model has bands — it always does at the resolutions this
     * generator draws — and a band left with no rows takes its neighbour's share so that a very
     * small map cannot leave a hole in the profile.
     */
    fun landFractionByBand(cellsAcross: Int, cellsDown: Int, isLand: BooleanArray): FloatArray {
        val landCellsInBand = IntArray(BANDS)
        val cellsInBand = IntArray(BANDS)
        for (row in 0 until cellsDown) {
            val latitude = ClimateStage.latitudeOf(row, cellsDown)
            val band = (((POLE_DEGREES - latitude) * BANDS / POLE_TO_POLE_DEGREES).toInt())
                .coerceIn(0, BANDS - 1)
            cellsInBand[band] += cellsAcross
            for (column in 0 until cellsAcross) {
                if (isLand[row * cellsAcross + column]) landCellsInBand[band]++
            }
        }
        val fraction = FloatArray(BANDS)
        var lastKnown = 0f
        for (band in 0 until BANDS) {
            if (cellsInBand[band] > 0) {
                lastKnown = landCellsInBand[band].toFloat() / cellsInBand[band]
            }
            fraction[band] = lastKnown
        }
        return fraction
    }

    /**
     * Solves the model and returns every band's year.
     *
     * [landFraction] is one share per band, as [landFractionByBand] produces it.
     * [obliquityDegrees] is the axial tilt the insolation is computed at; zero gives a planet with
     * no seasons at all, where every band's summer and winter are the same number.
     * [solarScale] multiplies the sunlight — 1 is this world's own sun, and less is the colder one
     * a glacial forcing asks for. [outgoingOffsetShiftW] moves `A` in the outgoing-longwave law,
     * negative for a stronger greenhouse and a warmer world.
     */
    fun solve(
        landFraction: FloatArray,
        obliquityDegrees: Float,
        solarScale: Float = 1f,
        outgoingOffsetShiftW: Float = 0f,
        /**
         * Meridional heat transport at the equator and at the pole, in watts per square metre
         * per degree Celsius; between them it follows the cosine squared. The defaults are
         * [DIFFUSION_TROPICS_W_PER_M2_C] and [DIFFUSION_POLAR_W_PER_M2_C]; the guards vary them
         * to show what the transport is doing to the profile — passing the same figure for both
         * is the constant diffusivity the model had before, which is the control the profile
         * guard is measured against — and nothing in the pipeline passes anything else.
         */
        transportTropicsW: Float = DIFFUSION_TROPICS_W_PER_M2_C.toFloat(),
        transportPolarW: Float = DIFFUSION_POLAR_W_PER_M2_C.toFloat(),
        /**
         * Whether the albedo follows the temperature. Off pins every band at its ice-free
         * albedo whatever it is doing, which is the control the ice-albedo guards are measured
         * against: a world with no feedback cools nearly uniformly under a forcing and grows no
         * cap of its own.
         */
        iceAlbedoFeedback: Boolean = true
    ): ZonalClimate {
        val geometry = Geometry(
            transportTropicsW.toDouble(), transportPolarW.toDouble(), iceAlbedoFeedback
        )
        val insolation = insolationByBandAndStep(geometry, obliquityDegrees, solarScale.toDouble())
        val annualInsolation = DoubleArray(BANDS) { band ->
            var total = 0.0
            for (step in 0 until STEPS_PER_YEAR) total += insolation[step * BANDS + band]
            total / STEPS_PER_YEAR
        }
        val outgoingOffset = OUTGOING_OFFSET_W_PER_M2 + outgoingOffsetShiftW

        val landC = DoubleArray(BANDS)
        val seaC = DoubleArray(BANDS)
        annualMeanEquilibrium(geometry, annualInsolation, outgoingOffset, landC, seaC)
        refreshSurfaceState(geometry, landFraction, landC, seaC)

        // Only the last year is turned into seasons; the year before it is kept as a global mean
        // so the spin-up's remaining drift can be reported rather than assumed away.
        val lastYearLand = DoubleArray(BANDS * STEPS_PER_YEAR)
        val lastYearSea = DoubleArray(BANDS * STEPS_PER_YEAR)
        val landAnnualC = DoubleArray(BANDS)
        val seaAnnualC = DoubleArray(BANDS)
        var previousYearMeanC = 0.0
        var lastYearMeanC = 0.0

        for (year in 0 until SPIN_UP_YEARS) {
            val recording = year == SPIN_UP_YEARS - 1
            landAnnualC.fill(0.0)
            seaAnnualC.fill(0.0)
            for (step in 0 until STEPS_PER_YEAR) {
                advance(geometry, landFraction, insolation, step, outgoingOffset, landC, seaC)
                for (band in 0 until BANDS) {
                    landAnnualC[band] += landC[band] / STEPS_PER_YEAR
                    seaAnnualC[band] += seaC[band] / STEPS_PER_YEAR
                    if (recording) {
                        lastYearLand[step * BANDS + band] = landC[band]
                        lastYearSea[step * BANDS + band] = seaC[band]
                    }
                }
            }
            // The albedo and the heat capacities follow the year that has just been lived, so the
            // ice-albedo feedback is an outer iteration over the years of the spin-up. That is
            // what keeps it from oscillating inside one: see [ICE_LINE_C].
            refreshSurfaceState(geometry, landFraction, landAnnualC, seaAnnualC)
            previousYearMeanC = lastYearMeanC
            lastYearMeanC = globalMean(geometry, landFraction, landAnnualC, seaAnnualC)
        }

        val landAnnual = FloatArray(BANDS)
        val landSummer = FloatArray(BANDS)
        val landWinter = FloatArray(BANDS)
        val seaAnnual = FloatArray(BANDS)
        val seaSummer = FloatArray(BANDS)
        val seaWinter = FloatArray(BANDS)
        for (band in 0 until BANDS) {
            splitIntoSeasons(lastYearLand, band, landAnnual, landSummer, landWinter)
            splitIntoSeasons(lastYearSea, band, seaAnnual, seaSummer, seaWinter)
        }

        return ZonalClimate(
            landAnnualC = landAnnual,
            landSummerC = landSummer,
            landWinterC = landWinter,
            seaAnnualC = seaAnnual,
            seaSummerC = seaSummer,
            seaWinterC = seaWinter,
            globalMeanC = lastYearMeanC.toFloat(),
            spinUpResidualC = (lastYearMeanC - previousYearMeanC).toFloat()
        )
    }

    /**
     * How far the sun must be dimmed for this world's global mean annual temperature to fall by
     * [coolingC] degrees, as a multiple of its own solar constant.
     *
     * A forcing rather than a redrawn map, which is the point. The model answers it with the
     * ice-albedo feedback running, so the cooling that comes back is amplified toward the poles
     * and barely felt in the tropics — the shape the proxies describe for the last glacial maximum
     * (MARGO 2009 put the tropical oceans 1.5-3 C below present and the high northern latitudes
     * 10-20 C below it) without that shape being written down anywhere.
     *
     * Returns 1 for a cooling of zero or less. Never dims past [MIN_SOLAR_SCALE]; if the model
     * cannot reach the asked-for cooling short of that, the caller gets the coldest sun the bound
     * allows and the world is merely less cold than it asked to be.
     */
    fun solarScaleForCooling(
        landFraction: FloatArray,
        obliquityDegrees: Float,
        coolingC: Float,
        outgoingOffsetShiftW: Float = 0f
    ): Float {
        if (coolingC <= 0f) return 1f
        val presentMeanC =
            solve(landFraction, obliquityDegrees, 1f, outgoingOffsetShiftW).globalMeanC
        val targetC = presentMeanC - coolingC

        // Without any feedback a dimming of `s` costs `(S/4)(1 - albedo) s` watts and the planet
        // cools by that over B. The feedback makes the real answer smaller, so this overshoots and
        // gives the secant a bracket rather than a starting point on one side.
        var scaleA = 1.0
        var missA = (presentMeanC - targetC).toDouble()
        var scaleB = 1.0 - OUTGOING_PER_DEGREE_W_PER_M2_C * coolingC /
            (SOLAR_CONSTANT_W_PER_M2 / 4.0 * (1.0 - ICE_FREE_ALBEDO_BASE))
        scaleB = scaleB.coerceAtLeast(MIN_SOLAR_SCALE)

        for (pass in 0 until MAX_SECANT_PASSES) {
            val missB = (solve(landFraction, obliquityDegrees, scaleB.toFloat(), outgoingOffsetShiftW)
                .globalMeanC - targetC).toDouble()
            if (abs(missB) <= SECANT_TOLERANCE_C) return scaleB.toFloat()
            val slope = (missB - missA) / (scaleB - scaleA)
            if (slope == 0.0) return scaleB.toFloat()
            val next = (scaleB - missB / slope).coerceIn(MIN_SOLAR_SCALE, 1.0)
            scaleA = scaleB
            missA = missB
            scaleB = next
        }
        return scaleB.toFloat()
    }

    /**
     * How far `A` in the outgoing-longwave law must move, in watts per square metre, for this
     * world's global mean annual temperature to sit [shiftC] degrees from where the model's own
     * constants put it. Negative for a warmer world: a stronger greenhouse lets less heat out.
     *
     * Solved the same way [solarScaleForCooling] is, and for the same reason — the feedback
     * amplifies the shift, so a knob marked in degrees has to be asked what it actually produced
     * rather than told.
     */
    fun outgoingOffsetForShift(
        landFraction: FloatArray,
        obliquityDegrees: Float,
        shiftC: Float
    ): Float {
        if (shiftC == 0f) return 0f
        val targetC = solve(landFraction, obliquityDegrees).globalMeanC + shiftC

        var offsetA = 0.0
        var missA = -shiftC.toDouble()
        // Without feedback, `A` moves by `B` watts for every degree.
        var offsetB = -OUTGOING_PER_DEGREE_W_PER_M2_C * shiftC.toDouble()

        for (pass in 0 until MAX_SECANT_PASSES) {
            val missB = (solve(landFraction, obliquityDegrees, 1f, offsetB.toFloat())
                .globalMeanC - targetC).toDouble()
            if (abs(missB) <= SECANT_TOLERANCE_C) return offsetB.toFloat()
            val slope = (missB - missA) / (offsetB - offsetA)
            if (slope == 0.0) return offsetB.toFloat()
            val next = offsetB - missB / slope
            offsetA = offsetB
            missA = missB
            offsetB = next
        }
        return offsetB.toFloat()
    }

    /**
     * The band geometry the diffusion operator needs: the cosine of each band's centre latitude
     * and of each edge between bands, and the band width in radians.
     *
     * The two outermost edges are the poles themselves, where the cosine is zero, so no heat
     * crosses them and the planet is closed by construction rather than by a special case.
     */
    private class Geometry(
        /** See `solve`'s own parameters of the same names. */
        transportTropicsW: Double,
        transportPolarW: Double,
        val iceAlbedoFeedback: Boolean
    ) {
        /**
         * The diffusivity on each edge between bands, which is where the flux is evaluated.
         *
         * Entry `i` sits on the edge poleward of band `i - 1`, so that band's poleward coefficient
         * and band `i`'s equatorward one read the same number and the operator stays conservative.
         * See [DIFFUSION_TROPICS_W_PER_M2_C] for the shape and where it comes from.
         */
        val transportAtEdge = DoubleArray(BANDS + 1) { edge ->
            val latitudeRadians =
                (POLE_DEGREES - POLE_TO_POLE_DEGREES * edge / BANDS) * PI / 180.0
            val cosine = cos(latitudeRadians)
            transportPolarW + (transportTropicsW - transportPolarW) * cosine * cosine
        }

        val bandWidthRadians = (POLE_TO_POLE_DEGREES / BANDS) * PI / 180.0
        val cosCentre = DoubleArray(BANDS) {
            cos(latitudeOfBand(it) * PI / 180.0).coerceAtLeast(1e-6)
        }

        /** Entry `i` is the edge poleward of band `i - 1` and equatorward of band `i`. */
        val cosEdge = DoubleArray(BANDS + 1) {
            cos((POLE_DEGREES - POLE_TO_POLE_DEGREES * it / BANDS) * PI / 180.0)
                .coerceAtLeast(0.0)
        }
        val sinCentre = DoubleArray(BANDS) { sin(latitudeOfBand(it) * PI / 180.0) }
        val tanCentre = DoubleArray(BANDS) { tan(latitudeOfBand(it) * PI / 180.0) }

        /** Scratch space for the tridiagonal solve and the time step, reused every step. */
        val subDiagonal = DoubleArray(BANDS)
        val diagonal = DoubleArray(BANDS)
        val superDiagonal = DoubleArray(BANDS)
        val eliminatedSuper = DoubleArray(BANDS)
        val rightHandSide = DoubleArray(BANDS)
        val bandMeanBeforeTransport = DoubleArray(BANDS)
        val meanHeatCapacity = DoubleArray(BANDS)
        val seaHeatCapacity = DoubleArray(BANDS)

        /** The white share the albedo and the capacities are read off, refreshed once a year. */
        val white = DoubleArray(BANDS)
    }

    /**
     * The white share of a column whose annual mean is [annualMeanC]: 0 well above
     * [ICE_LINE_C], 1 well below it, ramped over [ICE_LINE_SPREAD_C] either side.
     */
    private fun Geometry.whiteFraction(annualMeanC: Double): Double =
        if (!iceAlbedoFeedback) 0.0
        else ((ICE_LINE_C + ICE_LINE_SPREAD_C - annualMeanC) / (2.0 * ICE_LINE_SPREAD_C))
            .coerceIn(0.0, 1.0)

    /**
     * The planetary albedo of a band that is [white] of the way to frozen, at [latitudeDegrees].
     *
     * The clear-sky curve — flat through the tropics and rising late and steeply — plus the ITCZ's
     * cloud. See [ICE_FREE_ALBEDO_BASE] for the observations these four were fitted to.
     */
    private fun albedoOf(white: Double, latitudeDegrees: Double): Double {
        val sinLatitude = sin(latitudeDegrees * PI / 180.0)
        val towardsPole = sinLatitude * sinLatitude
        val iceFree = ICE_FREE_ALBEDO_BASE +
            ICE_FREE_ALBEDO_SUBTROPICAL_DIP * towardsPole +
            ICE_FREE_ALBEDO_AT_A_SLANT * towardsPole * towardsPole +
            CLOUD_ALBEDO_ITCZ *
            cloudBelt(latitudeDegrees, CLOUD_ITCZ_DEGREES, CLOUD_ITCZ_WIDTH_DEGREES)
        return iceFree + (FROZEN_ALBEDO - iceFree) * white
    }

    /**
     * A cloud belt's weight at a latitude: 1 on its centre, `1/e` one width away, and counted on
     * both sides of the equator because a belt is a pair.
     */
    private fun cloudBelt(
        latitudeDegrees: Double,
        centreDegrees: Double,
        widthDegrees: Double
    ): Double {
        val widths = (abs(latitudeDegrees) - centreDegrees) / widthDegrees
        return exp(-(widths * widths))
    }

    /**
     * The daily-mean insolation for every band on every step of the year, in watts per square
     * metre, indexed `step * BANDS + band`.
     *
     * The standard astronomical expression for the sunlight a latitude receives averaged over one
     * rotation, with the solar declination swinging over the year with the axial tilt. The orbit
     * is taken as circular: eccentricity moves Earth's insolation by about 3.5 per cent between
     * January and July, which is small against the tilt's effect and would need an orbit's worth
     * of settings to describe for a world that has none.
     */
    private fun insolationByBandAndStep(
        geometry: Geometry,
        obliquityDegrees: Float,
        solarScale: Double
    ): DoubleArray {
        val obliquityRadians = obliquityDegrees * PI / 180.0
        val insolation = DoubleArray(STEPS_PER_YEAR * BANDS)
        val solar = SOLAR_CONSTANT_W_PER_M2 * solarScale
        for (step in 0 until STEPS_PER_YEAR) {
            // Step zero is the northern spring equinox, so the northern summer solstice falls a
            // quarter of the way through and each hemisphere's warm half is a true half-year.
            val orbitRadians = 2.0 * PI * step / STEPS_PER_YEAR
            val declinationRadians = asin(sin(obliquityRadians) * sin(orbitRadians))
            val sinDeclination = sin(declinationRadians)
            val cosDeclination = cos(declinationRadians)
            val tanDeclination = tan(declinationRadians)
            for (band in 0 until BANDS) {
                val cosLatitude = geometry.cosCentre[band]
                val sinLatitude = geometry.sinCentre[band]
                val sunsetCosine = -geometry.tanCentre[band] * tanDeclination
                val halfDayRadians = when {
                    sunsetCosine <= -1.0 -> PI
                    sunsetCosine >= 1.0 -> 0.0
                    else -> acos(sunsetCosine)
                }
                insolation[step * BANDS + band] = solar / PI *
                    (halfDayRadians * sinLatitude * sinDeclination +
                        cosLatitude * cosDeclination * sin(halfDayRadians))
            }
        }
        return insolation
    }

    /**
     * Fills [landC] and [seaC] with the ice-free steady state of the annual-mean budget, which is
     * where the seasonal march starts.
     *
     * At steady state the heat capacities drop out and, with the albedo held ice-free, the budget
     * is linear: one tridiagonal solve gives the whole profile and both columns take it.
     *
     * Ice-free, deliberately. This model has two stable states — the one Earth is in and a frozen
     * planet at -35 C — and the albedo feedback is what separates them, so where the spin-up
     * *starts* decides which one it finds. Iterating the albedo here to convergence was tried and
     * overshot into the frozen branch inside four passes, because an undamped fixed point on a
     * positive feedback has no reason not to. Starting warm and letting the march grow the ice
     * cannot do that: the ocean's own heat capacity damps the feedback into something the twenty
     * years of spin-up walk down smoothly.
     */
    private fun annualMeanEquilibrium(
        geometry: Geometry,
        annualInsolation: DoubleArray,
        outgoingOffset: Double,
        landC: DoubleArray,
        seaC: DoubleArray
    ) {
        for (band in 0 until BANDS) {
            geometry.rightHandSide[band] = annualInsolation[band] *
                (1.0 - albedoOf(white = 0.0, latitudeDegrees = latitudeOfBand(band).toDouble())) -
                outgoingOffset
        }
        solveDiffusion(
            geometry,
            diagonalBase = OUTGOING_PER_DEGREE_W_PER_M2_C,
            transportWeight = 1.0,
            perBandCapacity = null,
            result = landC
        )
        landC.copyInto(seaC)
    }

    /**
     * Carries both columns of every band forward one time step.
     *
     * Three terms in order. **Radiation**, implicitly in each column's own temperature, so no step
     * can overshoot the equilibrium it is heading for whatever the step length. **The zonal
     * exchange** between the band's two columns, at [ZONAL_EXCHANGE_W_PER_M2_C], which is what
     * keeps a continent and the sea beside it in the same climate over the year while leaving them
     * free to differ within it. **The meridional transport**, implicitly, spreading the watts it
     * brings over the band's whole area — so each column takes the same watts per square metre and
     * turns them into degrees at its own heat capacity, which is why the band capacity used in the
     * solve is the harmonic mean.
     *
     * Both couplings are needed, and each fixes what the other cannot. Without the exchange the
     * tropics — which export heat all year — drain their land column, because the same watts leaving
     * both columns cost the land fifteen times the degrees; it was measured at forty degrees below
     * the sea beside it, cold enough to whiten and stay there. Without the harmonic split, and with
     * the transport instead moving both columns by the same number of degrees, the steady state
     * requires the ocean to run a radiative imbalance fifteen times the land's, which is not a
     * thing an ocean does.
     */
    private fun advance(
        geometry: Geometry,
        landFraction: FloatArray,
        insolation: DoubleArray,
        step: Int,
        outgoingOffset: Double,
        landC: DoubleArray,
        seaC: DoubleArray
    ) {
        val stepSeconds = SECONDS_PER_YEAR / STEPS_PER_YEAR
        val landHeat = LAND_HEAT_CAPACITY_J_PER_M2_C
        val bandMean = geometry.rightHandSide

        for (band in 0 until BANDS) {
            val sunlight = insolation[step * BANDS + band]

            val bandAlbedo =
                albedoOf(geometry.white[band], latitudeOfBand(band).toDouble())
            val landAbsorbed = sunlight * (1.0 - bandAlbedo)
            var land = (landC[band] + stepSeconds / landHeat * (landAbsorbed - outgoingOffset)) /
                (1.0 + stepSeconds * OUTGOING_PER_DEGREE_W_PER_M2_C / landHeat)

            val seaHeat = geometry.seaHeatCapacity[band]
            val seaAbsorbed = sunlight * (1.0 - bandAlbedo)
            var sea = (seaC[band] + stepSeconds / seaHeat * (seaAbsorbed - outgoingOffset)) /
                (1.0 + stepSeconds * OUTGOING_PER_DEGREE_W_PER_M2_C / seaHeat)

            // The zonal exchange, toward the band's own mean and so conservative by construction:
            // what one column gives, weighted by its area, the other takes.
            val landShare = landFraction[band].toDouble()
            val mixed = landShare * land + (1.0 - landShare) * sea
            land += stepSeconds / landHeat * ZONAL_EXCHANGE_W_PER_M2_C * (mixed - land)
            sea += stepSeconds / seaHeat * ZONAL_EXCHANGE_W_PER_M2_C * (mixed - sea)

            landC[band] = land
            seaC[band] = sea
            bandMean[band] = landShare * land + (1.0 - landShare) * sea
            geometry.bandMeanBeforeTransport[band] = bandMean[band]
        }

        solveDiffusion(
            geometry,
            diagonalBase = 1.0,
            transportWeight = stepSeconds,
            perBandCapacity = geometry.meanHeatCapacity,
            result = bandMean
        )
        for (band in 0 until BANDS) {
            val importedJoules = geometry.meanHeatCapacity[band] *
                (bandMean[band] - geometry.bandMeanBeforeTransport[band])
            landC[band] += importedJoules / landHeat
            seaC[band] += importedJoules / geometry.seaHeatCapacity[band]
        }
    }

    /**
     * Sets each band's white share, its sea heat capacity and its mean heat capacity from the
     * annual means the year just marched produced.
     *
     * Once a year, not once a step, because the albedo follows the annual mean — see [ICE_LINE_C].
     * The capacities go with it: a sea that spends its year under ice has the memory of the ice
     * rather than of the water beneath it, and that is a property of the year too.
     *
     * One white share per **band**, from both columns pooled, rather than one per column. Budyko's
     * and North's ice line is a property of a latitude, and splitting it lets the land column run
     * away on its own: a band's land, having a fortnight's memory against the ocean's three years,
     * settles a dozen degrees colder in the annual mean, whitens, and settles colder still. Earth's
     * high-latitude land is not white in the annual mean — its winters are, and its summers are
     * dark — and pooling the band is what says so.
     */
    private fun refreshSurfaceState(
        geometry: Geometry,
        landFraction: FloatArray,
        landAnnualC: DoubleArray,
        seaAnnualC: DoubleArray
    ) {
        for (band in 0 until BANDS) {
            val landShare = landFraction[band].toDouble()
            val bandAnnualC =
                landShare * landAnnualC[band] + (1.0 - landShare) * seaAnnualC[band]
            val white = geometry.whiteFraction(bandAnnualC)
            geometry.white[band] = white
            val seaHeat = OCEAN_HEAT_CAPACITY_J_PER_M2_C +
                (FROZEN_SEA_HEAT_CAPACITY_J_PER_M2_C - OCEAN_HEAT_CAPACITY_J_PER_M2_C) * white
            geometry.seaHeatCapacity[band] = seaHeat
            geometry.meanHeatCapacity[band] =
                1.0 / (landShare / LAND_HEAT_CAPACITY_J_PER_M2_C + (1.0 - landShare) / seaHeat)
        }
    }

    /**
     * Solves `(diagonalBase - transportWeight / capacity * L) T = rightHandSide` for the band
     * temperatures, where `L` is the meridional diffusion operator, by the Thomas algorithm.
     *
     * Two callers with two meanings. The annual-mean equilibrium passes `diagonalBase = B` and
     * `transportWeight = 1`, which is the steady-state budget. A time step passes
     * `diagonalBase = 1` and `transportWeight = dt`, which is backward Euler on the transport
     * alone — unconditionally stable, so the step length is set by how finely the year needs
     * resolving rather than by how narrow a band is.
     */
    private fun solveDiffusion(
        geometry: Geometry,
        diagonalBase: Double,
        transportWeight: Double,
        /** The band's heat capacity the transport is divided by, or null for the steady state. */
        perBandCapacity: DoubleArray?,
        result: DoubleArray
    ) {
        val width = geometry.bandWidthRadians
        for (band in 0 until BANDS) {
            val capacity = perBandCapacity?.get(band) ?: 1.0
            val scale = transportWeight / capacity / (geometry.cosCentre[band] * width * width)
            geometry.subDiagonal[band] =
                -scale * geometry.transportAtEdge[band] * geometry.cosEdge[band]
            geometry.superDiagonal[band] =
                -scale * geometry.transportAtEdge[band + 1] * geometry.cosEdge[band + 1]
            geometry.diagonal[band] =
                diagonalBase - geometry.subDiagonal[band] - geometry.superDiagonal[band]
        }
        // Thomas: one forward sweep eliminating the sub-diagonal, one back-substitution. The
        // right-hand side and the result may be the same array, which is why every read of it
        // happens before the write to the same index.
        var pivot = geometry.diagonal[0]
        result[0] = geometry.rightHandSide[0] / pivot
        for (band in 1 until BANDS) {
            geometry.eliminatedSuper[band] = geometry.superDiagonal[band - 1] / pivot
            pivot = geometry.diagonal[band] - geometry.subDiagonal[band] * geometry.eliminatedSuper[band]
            result[band] =
                (geometry.rightHandSide[band] - geometry.subDiagonal[band] * result[band - 1]) / pivot
        }
        for (band in BANDS - 2 downTo 0) {
            result[band] -= geometry.eliminatedSuper[band + 1] * result[band + 1]
        }
    }

    /** The area-weighted mean of an annual profile over the whole planet, in degrees Celsius. */
    private fun globalMean(
        geometry: Geometry,
        landFraction: FloatArray,
        landC: DoubleArray,
        seaC: DoubleArray
    ): Double {
        var weighted = 0.0
        var weight = 0.0
        for (band in 0 until BANDS) {
            val land = landFraction[band].toDouble()
            weighted += geometry.cosCentre[band] * (land * landC[band] + (1.0 - land) * seaC[band])
            weight += geometry.cosCentre[band]
        }
        return weighted / weight
    }

    /**
     * Splits one band's year into its warmest and coldest halves and writes all three figures.
     *
     * The warm half is the contiguous half-year with the highest mean, found by sliding the window
     * round the year, and the cold half is what is left — so a southern band finds the southern
     * summer without being told which hemisphere it is in, a sea column finds its own later summer,
     * and the two means average exactly to the annual one.
     */
    private fun splitIntoSeasons(
        year: DoubleArray,
        band: Int,
        annual: FloatArray,
        summer: FloatArray,
        winter: FloatArray
    ) {
        var yearTotal = 0.0
        for (step in 0 until STEPS_PER_YEAR) yearTotal += year[step * BANDS + band]

        var windowTotal = 0.0
        for (step in 0 until SEASON_STEPS) windowTotal += year[step * BANDS + band]
        var warmestTotal = windowTotal
        for (start in 1 until STEPS_PER_YEAR) {
            windowTotal += year[((start + SEASON_STEPS - 1) % STEPS_PER_YEAR) * BANDS + band] -
                year[(start - 1) * BANDS + band]
            if (windowTotal > warmestTotal) warmestTotal = windowTotal
        }

        annual[band] = (yearTotal / STEPS_PER_YEAR).toFloat()
        summer[band] = (warmestTotal / SEASON_STEPS).toFloat()
        winter[band] = ((yearTotal - warmestTotal) / SEASON_STEPS).toFloat()
    }
}
