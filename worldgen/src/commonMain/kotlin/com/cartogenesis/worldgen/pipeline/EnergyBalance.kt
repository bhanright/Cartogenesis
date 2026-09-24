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
 * Three temperatures per band. `land` and `sea` are the two *air* columns the model carries — the
 * same insolation and the same imported heat, but different memories and different neighbours,
 * which is the whole of why a continent has a winter and a coast has a cool spell. `water` is the
 * sea surface under the marine air: the ocean's mixed layer, twenty times the memory, which is
 * what freezes and what the moisture march evaporates from. Read the air for what a place feels
 * and the water for what the sea is.
 *
 * Each column carries its year as five numbers — the annual mean, the warmest and coldest month,
 * and the warm and cold half-year means — and they are the column's own, not the calendar's: a
 * southern band's summer is the southern one, and a maritime column's summer runs later than the
 * land's beside it, which is the lag heat capacity produces. See [ZonalColumn] for why both the
 * months and the halves are kept.
 */
class ZonalClimate internal constructor(
    /** The air over the band's land. */
    val land: ZonalColumn,
    /** The air over the band's sea. */
    val sea: ZonalColumn,
    /** The mixed layer under that air. */
    val water: ZonalColumn,
    /** Area-weighted mean of the annual field over the whole planet, in degrees Celsius. */
    val globalMeanC: Float,
    /**
     * How far the last simulated year's global mean moved from the year before it, in degrees
     * Celsius: the residual drift left in the spin-up, reported by the guards rather than acted on.
     */
    val spinUpResidualC: Float
) {

    /** The land air temperature at [latitudeDegrees], interpolated between band centres. */
    fun landC(latitudeDegrees: Float, season: Season): Float =
        interpolate(land.at(season), latitudeDegrees)

    /**
     * The **marine air** temperature at [latitudeDegrees], interpolated between band centres:
     * what a ship's deck or a shoreline reads, and what the map's marine blend hands to a coast.
     */
    fun seaC(latitudeDegrees: Float, season: Season): Float =
        interpolate(sea.at(season), latitudeDegrees)

    /**
     * The **sea-surface** temperature at [latitudeDegrees], interpolated between band centres:
     * the mixed layer itself, which is what freezes and what evaporates.
     */
    fun waterC(latitudeDegrees: Float, season: Season): Float =
        interpolate(water.at(season), latitudeDegrees)

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

/**
 * One surface's year, band by band: the annual mean, the warmest and coldest month, and the means
 * of the warm and cold half-years.
 *
 * Both the months and the halves, because two different kinds of question get asked of this model
 * and each wants its own answer. Koppen's thresholds are monthly means — the 10 C tree line, the
 * -3 C continental winter, the 18 C tropical one — so `ClimateStage.classify` reads
 * [warmestMonthC] and [coldestMonthC]. Anything that *integrates over* a season wants the season's
 * own mean instead: `SnowBalance` runs a positive-degree-day sum across 182 days and the moisture
 * march evaporates for half a year, and handing either of those a warmest month has it melting and
 * evaporating at the peak of summer for the whole of summer. W1's third pass made that mistake for
 * one build and it cost the world a third of its permanent ice.
 */
class ZonalColumn internal constructor(
    val annualC: FloatArray,
    val warmestMonthC: FloatArray,
    val coldestMonthC: FloatArray,
    val warmHalfC: FloatArray,
    val coldHalfC: FloatArray
) {
    internal fun at(season: Season): FloatArray = when (season) {
        Season.ANNUAL -> annualC
        Season.SUMMER -> warmestMonthC
        Season.WINTER -> coldestMonthC
        Season.WARM_HALF -> warmHalfC
        Season.COLD_HALF -> coldHalfC
    }
}

/**
 * Which reading of a [ZonalColumn]'s year a lookup wants.
 *
 * [SUMMER] and [WINTER] are the warmest and coldest *month*, which is what Koppen's thresholds are
 * stated on; [WARM_HALF] and [COLD_HALF] are the means of the warm and cold half-years, which is
 * what anything integrating over a season needs. See [ZonalColumn].
 */
enum class Season { ANNUAL, SUMMER, WINTER, WARM_HALF, COLD_HALF }

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
 * ### Two air columns and a slab of water
 *
 * Each band carries a land column and a marine column with the band's own land fraction as their
 * areas, and beneath the marine column a mixed layer of sea water. Both columns are *air*: they
 * see the same sunlight, radiate the same infrared law, trade heat with each other round the
 * latitude circle, and share what their neighbours send them. They differ only in memory —
 * [LAND_HEAT_CAPACITY_J_PER_M2_C] against [MARINE_AIR_HEAT_CAPACITY_J_PER_M2_C], the land's soil
 * and the sea's damp counted on top of the same atmospheric column — and in what sits under them.
 * Under the marine column is [MIXED_LAYER_HEAT_CAPACITY_J_PER_M2_C], twenty times either, coupled
 * to the air by the bulk surface flux [SURFACE_EXCHANGE_W_PER_M2_C] and coupled to nothing else.
 *
 * That is the entire land-sea contrast: no continentality setting, no damping factor, only the
 * capacities, the flux, and the world's own geography deciding how much of each band is which.
 * The water buys the marine air its mildness, and the zonal exchange with the continent beside it
 * takes some of that mildness back — which is why the sea's year is not the coast's year, and why
 * the model has to carry both. The three annual means come out within a degree of each other
 * while their years do not, which is the observed thing: Bergen and Yakutsk differ far more in
 * January than they do over the year, and the water off Bergen differs from Bergen too.
 *
 * The heat a band trades with its neighbours moves both *air* columns by the same number of
 * degrees, because the air that carries it has already gone round the latitude circle several
 * times over; the water feels it only through the surface flux above.
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
     * 360 rather than 365 so that a month is exactly 30 steps and a half-year exactly 180. A step
     * is a little over a day, and the diffusion is solved implicitly, so nothing here is near a
     * stability limit.
     */
    private const val STEPS_PER_YEAR = 360

    /**
     * Steps in a month, which is the window "summer" and "winter" are the extremes of.
     *
     * A month and not a half-year, because every threshold downstream is one of Koppen's and
     * Koppen's are monthly means: the 10 C tree line, the -3 C continental winter, the 18 C
     * tropical one. `ClimateStage.classify` says so in as many words, and W1's first two passes
     * handed it warm- and cold-*half-year* means instead — which for a sinusoidal year are 0.64 of
     * the month extremes, so every gate was being asked of a number a third short of the one it
     * was written for. Nothing else in the pipeline needs the halves: the seasonal rain march
     * wants the temperature of the season it is marching, and the warmest month is the honest
     * stand-in for that, which is what the model before W1 supplied.
     */
    private const val MONTH_STEPS = STEPS_PER_YEAR / 12

    /**
     * Steps in half a year, which is the window the warm and cold seasons' *means* are taken over.
     *
     * The other half of the pair above. Everything that integrates across a season rather than
     * testing a threshold reads these: see [ZonalColumn].
     */
    private const val HALF_YEAR_STEPS = STEPS_PER_YEAR / 2

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
     * 80 degrees: 0.90 and 0.30, with the storm track below carrying the mid-latitudes.
     * `EnergyBalanceTest` states what the three together produce at each. The polar figure sits at
     * the bottom of the published range for a constant diffusivity, which is what a pole with no
     * storms of its own should have; the tropical one is above it, which is what a number standing
     * for the Hadley cell rather than for an eddy should be.
     *
     * The latitude dependence was the smaller of W1's two second-pass corrections and it is honest
     * to say so. The larger was the albedo below, whose shape was wrong; with that fixed and the
     * diffusivity left constant at 0.60 the profile is already close, and what the shape then buys
     * is the equator (26.3 C against 28.1 for a constant one, where Earth has 26), 40 degrees (14.2
     * against 13.0, where Earth has 14.5) and the cold-season ice edge.
     */
    internal const val DIFFUSION_TROPICS_W_PER_M2_C = 0.90
    internal const val DIFFUSION_POLAR_W_PER_M2_C = 0.30

    /**
     * The storm track: how much more heat the mid-latitudes carry than the cosine-squared shape
     * above accounts for, as a share of [DIFFUSION_TROPICS_W_PER_M2_C], and where the extra sits.
     *
     * Earth's atmosphere carries heat poleward by two different machines and they live in
     * different places. The Hadley cell is a mean overturning, strongest at the equator and gone
     * by 30 degrees, and a cosine-squared diffusivity stands in for it well. Baroclinic eddies —
     * the depressions of the mid-latitude storm track — are the other, and they peak near 45-50
     * degrees and fall away to nothing at the pole; Trenberth and Stepaniak (2003) separate the
     * two components and the eddy one carries most of the transport poleward of 35. A single
     * monotonic shape cannot be both, and the cost of pretending it is one shows up as a band of
     * mid-latitude ocean several degrees colder than the reanalysis: 11.0 C at 45 and 3.8 at 55
     * against about 12.5 and 7.5, with the pole several degrees *warmer* than Earth's because the
     * same shape keeps carrying heat past 70 where Earth's eddies have stopped.
     *
     * A Gaussian on the eddy band, therefore, and a lower floor under it. Written as a share of
     * the tropical figure rather than as watts of its own so that a guard which scales the
     * transport down — the no-transport control does, by ten — scales all of it.
     *
     * Fifty degrees and fifteen wide, which is where the North Atlantic and North Pacific storm
     * tracks are and about how broad they are, and 0.30 of the tropical figure. What the three buy,
     * against the same model with the eddy term left out:
     *
     *              45 deg   50 deg   55 deg   60 deg   pole    ice edge
     *   without     11.0      7.6      3.8      0.2   -13.2       58.1
     *   with        11.2      8.3      5.0      1.8   -15.6       60.4
     *   Earth       12.5     10.0      7.5      2.0   -20.0       60.0
     *
     * and a poleward transport of 4.6 / 4.9 / 3.2 PW at 30 / 45 / 60 against Trenberth and Caron's
     * 5.3 / 5.0 / 3.3. It is the difference between a west coast at 55 degrees that is taiga and
     * one that is forest: `ColdCapReportTest` reads 32/48/37% of warm-current west-facing coast as
     * temperate forest without the term and 40/52/51% with it, against A6's recorded 65/53/59.
     */
    private const val DIFFUSION_STORM_TRACK_SHARE = 0.30
    private const val STORM_TRACK_DEGREES = 50.0
    private const val STORM_TRACK_WIDTH_DEGREES = 15.0

    /**
     * The whole diffusivity shape at one latitude, in watts per square metre per degree: the
     * Hadley cosine-squared plus the storm track's Gaussian.
     *
     * Internal so that `EnergyBalanceTest`'s petawatt report reads the same curve the solve uses
     * rather than re-deriving it from the constants and drifting away from it.
     */
    internal fun diffusivityAt(
        latitudeDegrees: Double,
        tropics: Double = DIFFUSION_TROPICS_W_PER_M2_C,
        polar: Double = DIFFUSION_POLAR_W_PER_M2_C
    ): Double {
        val cosine = cos(latitudeDegrees * PI / 180.0)
        return polar + (tropics - polar) * cosine * cosine +
            tropics * DIFFUSION_STORM_TRACK_SHARE *
            cloudBelt(latitudeDegrees, STORM_TRACK_DEGREES, STORM_TRACK_WIDTH_DEGREES)
    }

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
     * Heat stored per square metre of the ocean's **mixed layer** for each degree it warms, in
     * joules: a 50 m slab of sea water at 4.0 MJ per cubic metre per degree.
     *
     * Fifty metres is the depth the seasonal cycle actually stirs: de Boyer Montegut et al. (2004)
     * put the global mean mixed layer near 60 m over the year, with a summer minimum of 20-30 m and
     * a deep winter tail that the seasonal wave never fills.
     *
     * The air that sits on it is [MARINE_AIR_HEAT_CAPACITY_J_PER_M2_C] and is a separate reservoir,
     * which is the whole point: twenty times less memory, so it swings while the water does not.
     */
    internal const val MIXED_LAYER_HEAT_CAPACITY_J_PER_M2_C = 50.0 * 4.0e6

    /**
     * Heat stored per square metre of **marine air** for each degree it warms, in joules: the
     * atmospheric column, `c_p x p / g = 1004 x 101325 / 9.81`, the same one that is lumped into
     * the land's figure.
     *
     * ### Why the sea band has two temperatures and not one
     *
     * W1's first two passes gave each band one sea temperature carrying the water's heat capacity,
     * and the map's marine blend handed it to every coastal cell. That is a category error with a
     * measurable cost. A fifty-metre slab of water barely moves through the year — at 55-60 degrees
     * it swung 4.4 C, which is right for the *water* — but the air over it swings half again as far,
     * because it has a twentieth of the memory and is chilled every winter by the continental air
     * beside it. A shoreline cell is 94% marine air, so it inherited the water's year, and a coast
     * whose warmest month is 6 C is tundra by Koppen's tree line whatever else is true of it: A6's
     * own guard fell from 65/53/59% of warm west-facing coasts as forest to 16/14/6%, taiga went
     * from 1.5% of the map to nothing, and `OceanCurrentTest`'s warm-against-cold coastal
     * habitability went from +13.2% to -2.4%.
     *
     * So the sunlight, the outgoing radiation, the zonal exchange with the land and the meridional
     * transport all belong to the **air**, which is what the atmosphere does with them and what a
     * coast feels; and the mixed layer hangs off it as a buffer, exchanging by the bulk surface flux
     * below. The annual mean is untouched by the split — at steady state the water sits exactly at
     * the air's temperature and the air's budget is the one the single column always had — and the
     * seasonal cycle is not. Measured at 50-60 degrees, warmest month against coldest: the air
     * swings 13.0 C and the water 6.9, against Earth's 8-11 for zonal-mean marine air and 5-8 for
     * the sea surface under it. The water is right and the air is at the top of its range, which is
     * the honest place for it to be: with a bulk coefficient of 25 against a fifty-metre slab's
     * inertia the air can only hand the water about half its amplitude, and Earth's air-sea
     * difference over the open ocean is nearer a degree all year. See TODO.md.
     */
    private const val MARINE_AIR_HEAT_CAPACITY_J_PER_M2_C = 1.04e7

    /**
     * How fast the sea surface and the air above it trade heat, in watts per square metre per
     * degree of difference between them.
     *
     * The bulk aerodynamic formulae, sensible plus latent, at a typical marine wind of 8 m/s:
     *
     *   sensible   `rho c_p C_H U = 1.2 x 1004 x 1.2e-3 x 8`                    = 11.6 W/m2/K
     *   latent     `rho L_v C_E U x RH x dq_sat/dT`, at 15 C and 80% humidity,
     *              `1.2 x 2.5e6 x 1.2e-3 x 8 x 0.8 x 7e-4`                      = 13.4 W/m2/K
     *
     * Twenty-five together, the top of the 15-25 the standard formulae give across the range of
     * wind speeds and surface temperatures a planet has. Warmer water couples harder, because the
     * latent term follows Clausius-Clapeyron — the same 7% a degree `ClimateConfig.currentMoisture`
     * uses — and that dependence is not modelled here; 25 is the mid-latitude figure, which is
     * where the coasts this matters for are.
     *
     * It sets how fast the air forgets the water: `C_air / 25` is six days, so marine air tracks
     * the sea surface closely and departs from it only as far as the land beside it and the heat
     * arriving from other latitudes push it.
     */
    private const val SURFACE_EXCHANGE_W_PER_M2_C = 25.0

    /**
     * How fast the two air columns of one band trade heat with each other, in watts per square
     * metre per degree of difference from the band's mean.
     *
     * This is the westerlies going round the latitude circle. An air mass crossing a continent at
     * mid-latitudes takes one to two weeks, and over that time it takes on the ground beneath it
     * and gives up what it brought; the same air then spends a comparable time over the ocean.
     * Written as a one-box exchange, that is a rate of `C / tau`: with the land column's
     * [LAND_HEAT_CAPACITY_J_PER_M2_C] and a fortnight, 14 W/m2/K, and with a month, 6.5. Eight is
     * in the middle of that bracket — three and a half weeks for the land column, a fortnight for
     * the lighter marine one — and it is what puts the model's seasonal ranges on Earth's:
     *
     *   50-60 deg   land 38.1 C     Earth's continental interiors  34-38
     *               marine air 13.6                                10-13
     *               water 7.0                                       5-8
     *   30-40 deg   land 27.6 C     Earth's continental interiors  24-26
     *
     * W1's first two passes had this at 3.5, which was fitted against the same latitudes and came
     * out half as large — because at the time [splitIntoSeasons] was reporting warm- and cold-half-
     * *year* means and they were being compared against Earth's month-to-month figures. A number
     * fitted against the wrong quantity is worse than an unfitted one, and this is the correction:
     * the timescale is now the argument and the table above is the check.
     */
    private const val ZONAL_EXCHANGE_W_PER_M2_C = 8.0

    /**
     * Heat stored per square metre of *frozen* sea, in joules per degree.
     *
     * Sea ice caps the mixed layer off: the water below it stays near its freezing point and the
     * air above it is talking to a metre or two of ice, not to fifty metres of ocean. So a frozen
     * sea has a land's memory, which is why a polar winter under ice is as cold as a continental
     * one and why the ice edge advances as fast as it does. Two metres of ice at 1.9 MJ per cubic
     * metre per degree; the air column is a reservoir of its own now and is not added here.
     */
    private const val FROZEN_SEA_HEAT_CAPACITY_J_PER_M2_C = 2.0 * 1.9e6

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
        iceAlbedoFeedback: Boolean = true,
        /**
         * The land column's heat capacity, in joules per square metre per degree. The default is
         * [LAND_HEAT_CAPACITY_J_PER_M2_C] and nothing in the pipeline passes anything else; the
         * seasonal-contrast guard passes the mixed layer's own, which is the planet with one heat
         * capacity for both surfaces that its control is about.
         */
        landHeatCapacityJPerM2C: Double = LAND_HEAT_CAPACITY_J_PER_M2_C
    ): ZonalClimate {
        val geometry = Geometry(
            transportTropicsW.toDouble(), transportPolarW.toDouble(), iceAlbedoFeedback,
            landHeatCapacityJPerM2C
        )
        val insolation = insolationByBandAndStep(geometry, obliquityDegrees, solarScale.toDouble())
        val annualInsolation = DoubleArray(BANDS) { band ->
            var total = 0.0
            for (step in 0 until STEPS_PER_YEAR) total += insolation[step * BANDS + band]
            total / STEPS_PER_YEAR
        }
        val outgoingOffset = OUTGOING_OFFSET_W_PER_M2 + outgoingOffsetShiftW

        val landC = DoubleArray(BANDS)
        val seaAirC = DoubleArray(BANDS)
        val waterC = DoubleArray(BANDS)
        annualMeanEquilibrium(geometry, annualInsolation, outgoingOffset, landC, seaAirC, waterC)
        refreshSurfaceState(geometry, landFraction, landC, seaAirC)

        // Only the last year is turned into seasons; the year before it is kept as a global mean
        // so the spin-up's remaining drift can be reported rather than assumed away.
        val lastYearLand = DoubleArray(BANDS * STEPS_PER_YEAR)
        val lastYearSeaAir = DoubleArray(BANDS * STEPS_PER_YEAR)
        val lastYearWater = DoubleArray(BANDS * STEPS_PER_YEAR)
        val landAnnualC = DoubleArray(BANDS)
        val seaAirAnnualC = DoubleArray(BANDS)
        var previousYearMeanC = 0.0
        var lastYearMeanC = 0.0

        for (year in 0 until SPIN_UP_YEARS) {
            val recording = year == SPIN_UP_YEARS - 1
            landAnnualC.fill(0.0)
            seaAirAnnualC.fill(0.0)
            for (step in 0 until STEPS_PER_YEAR) {
                advance(
                    geometry, landFraction, insolation, step, outgoingOffset,
                    landC, seaAirC, waterC
                )
                for (band in 0 until BANDS) {
                    landAnnualC[band] += landC[band] / STEPS_PER_YEAR
                    seaAirAnnualC[band] += seaAirC[band] / STEPS_PER_YEAR
                    if (recording) {
                        lastYearLand[step * BANDS + band] = landC[band]
                        lastYearSeaAir[step * BANDS + band] = seaAirC[band]
                        lastYearWater[step * BANDS + band] = waterC[band]
                    }
                }
            }
            // The albedo and the heat capacities follow the year that has just been lived, so the
            // ice-albedo feedback is an outer iteration over the years of the spin-up. That is
            // what keeps it from oscillating inside one: see [ICE_LINE_C].
            refreshSurfaceState(geometry, landFraction, landAnnualC, seaAirAnnualC)
            previousYearMeanC = lastYearMeanC
            lastYearMeanC = globalMean(geometry, landFraction, landAnnualC, seaAirAnnualC)
        }

        return ZonalClimate(
            land = splitIntoSeasons(lastYearLand),
            sea = splitIntoSeasons(lastYearSeaAir),
            water = splitIntoSeasons(lastYearWater),
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
        val iceAlbedoFeedback: Boolean,
        val landHeatCapacity: Double
    ) {
        /**
         * The diffusivity on each edge between bands, which is where the flux is evaluated.
         *
         * Entry `i` sits on the edge poleward of band `i - 1`, so that band's poleward coefficient
         * and band `i`'s equatorward one read the same number and the operator stays conservative.
         * See [DIFFUSION_TROPICS_W_PER_M2_C] for the shape and where it comes from.
         */
        val transportAtEdge = DoubleArray(BANDS + 1) { edge ->
            diffusivityAt(
                (POLE_DEGREES - POLE_TO_POLE_DEGREES * edge / BANDS).toDouble(),
                transportTropicsW,
                transportPolarW
            )
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
        val mixedLayerHeatCapacity = DoubleArray(BANDS)

        /** The white share the albedo and the capacities are read off, refreshed once a year. */
        val white = DoubleArray(BANDS)

        /**
         * The band's planetary albedo, refreshed with [white] once a year.
         *
         * Held rather than recomputed because it depends only on the white share and the latitude,
         * and neither moves inside a year — where computing it in the step loop asked for a sine
         * and an exponential 1.7 million times per solve, and a world's generation solves this
         * model eight times. That is nothing on a desktop and it is seconds in a browser, which is
         * how it was found: `GenerationProgressTest` on Wasm gives a 128-cell world two seconds and
         * the model was taking longer than that. Bit for bit the same numbers, computed once.
         */
        val bandAlbedo = DoubleArray(BANDS)
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
        seaAirC: DoubleArray,
        waterC: DoubleArray
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
        landC.copyInto(seaAirC)
        landC.copyInto(waterC)
    }

    /**
     * Carries all three reservoirs of every band forward one time step.
     *
     * Four terms in order. **Radiation**, implicitly in each air column's own temperature, so no
     * step can overshoot the equilibrium it is heading for whatever the step length; the water
     * carries none of it, because the sunlight the sea absorbs and the infrared it sends to space
     * both pass through the air, which is where the model's radiative law lives. **The zonal
     * exchange** between the band's two air columns, at [ZONAL_EXCHANGE_W_PER_M2_C], which keeps a
     * continent and the coast beside it in the same climate over the year while leaving them free
     * to differ within it. **The meridional transport**, implicitly, spreading the watts it brings
     * over the band's whole air — so each column takes the same watts per square metre and turns
     * them into degrees at its own heat capacity, which is why the band capacity used in the solve
     * is the harmonic mean. And last **the surface flux**, marine air against the water beneath
     * it, at [SURFACE_EXCHANGE_W_PER_M2_C].
     *
     * The split changes no annual mean. At steady state the surface flux vanishes, so the water
     * sits exactly at the air's temperature and the air's budget is the one the single sea column
     * always had. What it changes is the year: the air's amplitude rises because it is answering
     * for its own small capacity with the water only lagging behind it, and the water's falls
     * because it is driven through a flux rather than by the sun directly.
     *
     * All three couplings are needed, and each fixes what the others cannot. Without the zonal
     * exchange the tropics — which export heat all year — drain their land column, because the same
     * watts leaving both columns cost the land more degrees; it was measured at forty degrees below
     * the sea beside it, cold enough to whiten and stay there. Without the harmonic split, and with
     * the transport instead moving both columns by the same number of degrees, the steady state
     * requires the ocean to run a radiative imbalance many times the land's, which is not a thing
     * an ocean does. And without the surface flux — with the sea a single slab of water handed
     * whole to the coasts — every shoreline in the world inherits the ocean's year and freezes into
     * tundra; see [MARINE_AIR_HEAT_CAPACITY_J_PER_M2_C] for what that cost when it was tried.
     */
    private fun advance(
        geometry: Geometry,
        landFraction: FloatArray,
        insolation: DoubleArray,
        step: Int,
        outgoingOffset: Double,
        landC: DoubleArray,
        seaAirC: DoubleArray,
        waterC: DoubleArray
    ) {
        val stepSeconds = SECONDS_PER_YEAR / STEPS_PER_YEAR
        val landHeat = geometry.landHeatCapacity
        val marineAirHeat = MARINE_AIR_HEAT_CAPACITY_J_PER_M2_C
        val bandMean = geometry.rightHandSide

        for (band in 0 until BANDS) {
            val sunlight = insolation[step * BANDS + band]
            val absorbed = sunlight * (1.0 - geometry.bandAlbedo[band])
            var land = (landC[band] + stepSeconds / landHeat * (absorbed - outgoingOffset)) /
                (1.0 + stepSeconds * OUTGOING_PER_DEGREE_W_PER_M2_C / landHeat)
            var seaAir =
                (seaAirC[band] + stepSeconds / marineAirHeat * (absorbed - outgoingOffset)) /
                    (1.0 + stepSeconds * OUTGOING_PER_DEGREE_W_PER_M2_C / marineAirHeat)

            // The zonal exchange, between the two *air* columns, toward the band's own mean and so
            // conservative by construction: what one gives, weighted by its area, the other takes.
            val landShare = landFraction[band].toDouble()
            val mixed = landShare * land + (1.0 - landShare) * seaAir
            land += stepSeconds / landHeat * ZONAL_EXCHANGE_W_PER_M2_C * (mixed - land)
            seaAir += stepSeconds / marineAirHeat * ZONAL_EXCHANGE_W_PER_M2_C * (mixed - seaAir)

            landC[band] = land
            seaAirC[band] = seaAir
            bandMean[band] = landShare * land + (1.0 - landShare) * seaAir
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
            val seaAir = seaAirC[band] + importedJoules / marineAirHeat

            // The bulk surface flux last, so that what is recorded for the air and what is
            // recorded for the water are two ends of the same finished exchange. Over a periodic
            // year the flux integrates to nothing, so ordering it here is what makes the two
            // columns' annual means identical rather than a few tenths apart — which matters,
            // because one of them is compared against a marine-air climatology and the other
            // against a sea-surface one. Implicit in the pair, because six days of air memory
            // against a step of one day is close enough to stiff to matter.
            val water = waterC[band]
            val waterHeat = geometry.mixedLayerHeatCapacity[band]
            val exchange = stepSeconds * SURFACE_EXCHANGE_W_PER_M2_C
            val determinant =
                waterHeat * marineAirHeat + exchange * waterHeat + exchange * marineAirHeat
            val waterNext = (
                waterHeat * water * (marineAirHeat + exchange) + exchange * marineAirHeat * seaAir
                ) / determinant
            seaAirC[band] =
                (marineAirHeat * seaAir + exchange * waterNext) / (marineAirHeat + exchange)
            waterC[band] = waterNext
        }
    }

    /**
     * Sets each band's white share, its mixed-layer heat capacity and the mean heat capacity of
     * its air, from the annual means the year just marched produced.
     *
     * Once a year, not once a step, because the albedo follows the annual mean — see [ICE_LINE_C].
     * The mixed layer's capacity goes with it: a sea that spends its year under ice presents the
     * air a metre or two of ice rather than fifty metres of water, and that is a property of the
     * year too.
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
            geometry.bandAlbedo[band] = albedoOf(white, latitudeOfBand(band).toDouble())
            geometry.mixedLayerHeatCapacity[band] = MIXED_LAYER_HEAT_CAPACITY_J_PER_M2_C +
                (FROZEN_SEA_HEAT_CAPACITY_J_PER_M2_C - MIXED_LAYER_HEAT_CAPACITY_J_PER_M2_C) *
                white
            // The transport moves air, so the capacity it is spread over is the air's: the
            // harmonic mean of the two columns, which is what makes the watts rather than the
            // degrees the shared quantity. The water is not in it — it takes its share of the
            // imported heat through the surface flux, a step later and much attenuated, which is
            // exactly how an ocean receives what the atmosphere brings.
            geometry.meanHeatCapacity[band] = 1.0 / (
                landShare / geometry.landHeatCapacity +
                    (1.0 - landShare) / MARINE_AIR_HEAT_CAPACITY_J_PER_M2_C
                )
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
     * Turns one surface's recorded year into a [ZonalColumn]: the annual mean, the warmest and
     * coldest month, and the warm and cold half-years.
     *
     * A sliding window round the year for each length, so a southern band finds the southern
     * summer without being told which hemisphere it is in and a maritime column finds its own
     * later one: the sea's warmest month runs a month or two behind the land's beside it, and that
     * lag is the heat capacity's doing rather than anything written down here. The cold half-year
     * is the warm one's complement, so those two average exactly to the annual mean; the two months
     * do not, and nothing treats them as though they did.
     */
    private fun splitIntoSeasons(year: DoubleArray): ZonalColumn {
        val annual = FloatArray(BANDS)
        val warmestMonth = FloatArray(BANDS)
        val coldestMonth = FloatArray(BANDS)
        val warmHalf = FloatArray(BANDS)
        val coldHalf = FloatArray(BANDS)
        for (band in 0 until BANDS) {
            var yearTotal = 0.0
            for (step in 0 until STEPS_PER_YEAR) yearTotal += year[step * BANDS + band]
            annual[band] = (yearTotal / STEPS_PER_YEAR).toFloat()

            var monthTotal = 0.0
            for (step in 0 until MONTH_STEPS) monthTotal += year[step * BANDS + band]
            var warmestMonthTotal = monthTotal
            var coldestMonthTotal = monthTotal

            var halfTotal = 0.0
            for (step in 0 until HALF_YEAR_STEPS) halfTotal += year[step * BANDS + band]
            var warmestHalfTotal = halfTotal

            for (start in 1 until STEPS_PER_YEAR) {
                monthTotal += year[((start + MONTH_STEPS - 1) % STEPS_PER_YEAR) * BANDS + band] -
                    year[(start - 1) * BANDS + band]
                if (monthTotal > warmestMonthTotal) warmestMonthTotal = monthTotal
                if (monthTotal < coldestMonthTotal) coldestMonthTotal = monthTotal

                halfTotal += year[((start + HALF_YEAR_STEPS - 1) % STEPS_PER_YEAR) * BANDS + band] -
                    year[(start - 1) * BANDS + band]
                if (halfTotal > warmestHalfTotal) warmestHalfTotal = halfTotal
            }

            warmestMonth[band] = (warmestMonthTotal / MONTH_STEPS).toFloat()
            coldestMonth[band] = (coldestMonthTotal / MONTH_STEPS).toFloat()
            warmHalf[band] = (warmestHalfTotal / HALF_YEAR_STEPS).toFloat()
            coldHalf[band] = ((yearTotal - warmestHalfTotal) / HALF_YEAR_STEPS).toFloat()
        }
        return ZonalColumn(annual, warmestMonth, coldestMonth, warmHalf, coldHalf)
    }
}
