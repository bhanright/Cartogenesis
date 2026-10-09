package com.cartogenesis.worldgen.pipeline

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * What evaporates from the sea, and what land and open water could evaporate if they were wet:
 * the three formulas the moisture march and the lakes share, each read from its source.
 *
 * - **The sea:** the bulk formula, `E = rho C_E U (q_s(SST) - q_a)`, with Large and Yeager's
 *   (2004) neutral transfer coefficient at the belts' speed.
 * - **Land:** the FAO-56 Penman-Monteith reference evapotranspiration (Allen and others 1998,
 *   Eq. 6), the standard short-grass surface with its fixed canopy resistance.
 * - **Open water:** Penman's combination equation with his 1956 wind function, which McMahon and
 *   others (2013) recommend as the standard for lakes, at the albedo of water.
 *
 * All three are potential rates. The march spends the sea's directly, because the sea never runs
 * out; it spends the land's through Budyko's curve, which turns a potential into what the ground's
 * own rain lets it give back; and the lakes lose the open water's. See docs/DESIGN_LEDGER.md, C1b.
 */
internal object SurfaceEvaporation {

    // ---------------------------------------------------------------------------------- the sea

    /**
     * The neutral 10 m transfer coefficient for evaporation at a wind of [windMps], from Large and
     * Yeager's (2004) fits: `1000 C_D = 2.7/U + 0.142 + U/13.09` and `1000 C_E = 34.6
     * sqrt(C_D)`. At the belts' 7.5 m/s it is 1.13e-3.
     */
    fun evaporationTransferCoefficient(windMps: Double): Double {
        val dragCoefficient =
            (DRAG_LIGHT_WIND_MPS / windMps + DRAG_CONSTANT + windMps / DRAG_STRONG_WIND_MPS) / 1_000.0
        return EVAPORATION_PER_ROOT_DRAG * sqrt(dragCoefficient) / 1_000.0
    }

    /** Large and Yeager's (2004) drag fit's three terms and their evaporation coefficient. */
    private const val DRAG_LIGHT_WIND_MPS = 2.7
    private const val DRAG_CONSTANT = 0.142
    private const val DRAG_STRONG_WIND_MPS = 13.09
    private const val EVAPORATION_PER_ROOT_DRAG = 34.6

    /**
     * How much lower the vapor pressure over sea water is than over fresh water: Large and
     * Yeager's (2004) 0.98, the salt's effect on the saturation of the surface.
     */
    const val SEA_WATER_SATURATION_SHARE = 0.98

    /**
     * The specific humidity at the sea's surface at [seaSurfaceC]: saturation over sea water,
     * [SEA_WATER_SATURATION_SHARE] of fresh water's, at the standard sea-level pressure.
     */
    fun seaSurfaceHumidity(seaSurfaceC: Double): Double =
        SEA_WATER_SATURATION_SHARE * ColumnWater.specificHumidity(
            ColumnWater.saturationVaporPressureKpa(seaSurfaceC), ColumnWater.SEA_LEVEL_PRESSURE_KPA
        )

    /**
     * Evaporation from open sea, in kilograms per square meter per second, which is millimeters a
     * second: `rho C_E U (q_s - q_a)`, held at zero where the air is already wetter than the
     * surface.
     *
     * [surfaceHumidity] is [seaSurfaceHumidity] at the water's temperature, current and all, so
     * Clausius-Clapeyron carries a warm current's extra evaporation and a cold one's starvation
     * without a term of its own. [airHumidity] is the specific humidity of the air the parcel
     * brings, which the march reads off its own column. [transferMassFlux] is `rho C_E U`.
     */
    fun bulkEvaporationKgPerM2S(surfaceHumidity: Double, airHumidity: Double, transferMassFlux: Double): Double =
        (transferMassFlux * (surfaceHumidity - airHumidity)).coerceAtLeast(0.0)

    // --------------------------------------------------------------------------------- the sun

    /**
     * The half-year's mean extraterrestrial radiation on a horizontal surface at
     * [latitudeDegrees], in megajoules per square meter per day: FAO-56's Eq. 21 to 25 averaged
     * over one of the calendar's half-years, April to September when [julyHalf] and October to
     * March otherwise ([EnergyBalance.APRIL_FIRST_STEP]), the windows the march's two seasons are.
     *
     * The orbit is circular and the solar constant is the energy balance's, so the sun that sets
     * the potential evaporation is the sun that set the temperature: FAO-56's inverse distance
     * factor is Earth's own eccentricity, which the energy balance does not carry. The
     * declination follows the world's obliquity, so a world without seasons has one sun all year.
     */
    fun halfYearExtraterrestrialMjPerM2Day(
        latitudeDegrees: Double,
        obliquityDegrees: Double,
        julyHalf: Boolean
    ): Double {
        val latitudeRadians = latitudeDegrees * PI / 180.0
        val obliquityRadians = obliquityDegrees * PI / 180.0
        var sum = 0.0
        var days = 0
        for (step in 0 until DAYS_PER_YEAR) {
            // Step zero is the northern spring equinox, as in the energy balance.
            val fractionOfYear = (step + 0.5) / DAYS_PER_YEAR
            if (EnergyBalance.inJulyHalf(fractionOfYear) != julyHalf) continue
            val orbitRadians = 2.0 * PI * fractionOfYear
            val declination = asin(sin(obliquityRadians) * sin(orbitRadians))
            val sunsetCosine = -tan(latitudeRadians) * tan(declination)
            val sunsetHourAngle = when {
                sunsetCosine <= -1.0 -> PI
                sunsetCosine >= 1.0 -> 0.0
                else -> acos(sunsetCosine)
            }
            sum += SOLAR_MJ_PER_M2_DAY / PI * (
                sunsetHourAngle * sin(latitudeRadians) * sin(declination) +
                    cos(latitudeRadians) * cos(declination) * sin(sunsetHourAngle)
                )
            days++
        }
        return if (days == 0) 0.0 else sum / days
    }

    /** Days the half-year averages are taken over. */
    private const val DAYS_PER_YEAR = 365

    /** The energy balance's solar constant as a daily dose, megajoules per square meter per day. */
    private val SOLAR_MJ_PER_M2_DAY = EnergyBalance.SOLAR_CONSTANT_W_PER_M2 * SECONDS_PER_DAY / 1.0e6

    // ------------------------------------------------------------------ the surface's radiation

    /**
     * The share of the sun at the top of the atmosphere that reaches the ground over land, on
     * Earth's annual mean: 184.7 of 330.2 W/m² (Trenberth, Fasullo and Kiehl 2009, Table 2a and
     * 2b, land: 145.1 absorbed plus 39.6 reflected at the surface). FAO-56 asks for a cloudiness
     * the generator does not have (its Eq. 35 wants hours of sunshine), and this is the cloudiness
     * of Earth's land as a whole; the deserts' clearer skies and the rainforests' cloudier ones
     * are left out, recorded in docs/TODO.md.
     */
    const val SURFACE_SHARE_OF_EXTRATERRESTRIAL = 184.7 / 330.2

    /**
     * The clear-sky share, FAO-56's Eq. 37: `0.75 + 2e-5 z`, z the station's height in meters.
     */
    private fun clearSkyShare(elevationM: Double): Double = CLEAR_SKY_SHARE + CLEAR_SKY_PER_M * elevationM

    private const val CLEAR_SKY_SHARE = 0.75
    private const val CLEAR_SKY_PER_M = 2.0e-5

    /**
     * FAO-56's reference surface's albedo, Eq. 38's 0.23, and open water's, 0.08 (McMahon and
     * others 2013, supplement S4).
     */
    const val REFERENCE_ALBEDO = 0.23
    const val OPEN_WATER_ALBEDO = 0.08

    /** FAO-56's Eq. 39 constants: Stefan-Boltzmann per day and the two corrections' terms. */
    private const val STEFAN_BOLTZMANN_MJ_PER_M2_DAY_K4 = 4.903e-9
    private const val LONGWAVE_DRY_AIR = 0.34
    private const val LONGWAVE_PER_ROOT_KPA = 0.14
    private const val CLOUD_CLEAR_FACTOR = 1.35
    private const val CLOUD_OFFSET = 0.35

    // -------------------------------------------------------------------------- the two formulas

    /**
     * The air pressure at [elevationM], in kilopascals: FAO-56's Eq. 7,
     * `101.3 ((293 - 0.0065 z) / 293)^5.26`.
     */
    fun pressureAtKpa(elevationM: Double): Double =
        FAO_SEA_LEVEL_KPA * ((FAO_REFERENCE_K - FAO_LAPSE_K_PER_M * elevationM) / FAO_REFERENCE_K)
            .coerceAtLeast(0.0).pow(FAO_PRESSURE_EXPONENT)

    private const val FAO_SEA_LEVEL_KPA = 101.3
    private const val FAO_REFERENCE_K = 293.0
    private const val FAO_LAPSE_K_PER_M = 0.0065
    private const val FAO_PRESSURE_EXPONENT = 5.26

    /** FAO-56's Eq. 8: the psychrometric constant, `0.665e-3 P`, in kilopascals per degree. */
    private fun psychrometricKpaPerC(pressureKpa: Double): Double = PSYCHROMETRIC_PER_KPA * pressureKpa

    private const val PSYCHROMETRIC_PER_KPA = 0.665e-3

    /**
     * The wind at 2 m, in meters a second: FAO-56's 2 m/s, "the average over 2000 weather
     * stations around the globe" it recommends where no wind is measured. The march's belts are a
     * zonal mean over the sea at 10 m and say nothing of the wind over a field.
     */
    const val LAND_WIND_AT_2_M_MPS = 2.0

    /**
     * FAO-56's reference evapotranspiration, in millimeters a day: Eq. 6,
     *
     *     ET0 = (0.408 D (Rn - G) + g 900/(T + 273) u2 (e_s - e_a)) / (D + g (1 + 0.34 u2))
     *
     * the Penman-Monteith equation for "a hypothetical reference crop with an assumed crop height
     * of 0.12 m, a fixed surface resistance of 70 s m-1 and an albedo of 0.23", whose aerodynamic
     * resistance is `208/u2`. The 900 and the 0.34 are that crop's resistances and the gas law
     * folded into the equation (FAO-56, Box 6). The soil heat flux G is zero: over a half-year it
     * is the season's warming of the ground, which the year's two halves cancel and Budyko's
     * annual curve never sees.
     *
     * [actualVaporKpa] is the air's vapor pressure; the march hands in its column's relative
     * humidity times the saturation at [temperatureC]. Held at zero below: the formula can go
     * negative under a cold sun, where the ground takes heat from the air and gives none.
     */
    fun referenceEvapotranspirationMmPerDay(
        temperatureC: Double,
        actualVaporKpa: Double,
        extraterrestrialMjPerM2Day: Double,
        elevationM: Double
    ): Double = referenceTerms(temperatureC, extraterrestrialMjPerM2Day, elevationM)
        .mmPerDay(actualVaporKpa / ColumnWater.saturationVaporPressureKpa(temperatureC))

    /**
     * [referenceEvapotranspirationMmPerDay] written as what it is in the air's relative humidity
     * `h`: `constant + perRootHumidity sqrt(h) + perHumidity h`, the humidity entering through
     * the vapor deficit and the longwave's `sqrt(e_a)`. Everything else is the ground's and the
     * sun's, so the march builds these once and spends only the humidity each lap.
     */
    fun referenceTerms(temperatureC: Double, extraterrestrialMjPerM2Day: Double, elevationM: Double): HumidityTerms {
        val slope = ColumnWater.saturationSlopeKpaPerC(temperatureC)
        val psychrometric = psychrometricKpaPerC(pressureAtKpa(elevationM))
        val wind = LAND_WIND_AT_2_M_MPS
        val denominator = slope + psychrometric * (1.0 + REFERENCE_RESISTANCE_RATIO_PER_MPS * wind)
        val aerodynamic = psychrometric * REFERENCE_AERODYNAMIC_TERM / (temperatureC + FAO_KELVIN_OFFSET) * wind
        return humidityTerms(
            temperatureC, extraterrestrialMjPerM2Day, elevationM, REFERENCE_ALBEDO,
            radiative = MM_PER_MJ * slope / denominator, aerodynamic = aerodynamic / denominator
        )
    }

    /**
     * The two combination equations' shared shape: `radiative (Rns - Rnl) + aerodynamic e_s (1 - h)`,
     * with `Rnl = sigma T^4 (0.34 - 0.14 sqrt(h e_s)) cloud` (FAO-56 Eq. 39), split by the power of
     * the humidity each term carries.
     */
    private fun humidityTerms(
        temperatureC: Double,
        extraterrestrialMjPerM2Day: Double,
        elevationM: Double,
        albedo: Double,
        radiative: Double,
        aerodynamic: Double
    ): HumidityTerms {
        val saturation = ColumnWater.saturationVaporPressureKpa(temperatureC)
        val solar = SURFACE_SHARE_OF_EXTRATERRESTRIAL * extraterrestrialMjPerM2Day
        val clearSky = clearSkyShare(elevationM) * extraterrestrialMjPerM2Day
        val relativeShortwave = if (clearSky <= 0.0) 1.0 else (solar / clearSky).coerceIn(0.0, 1.0)
        val kelvin = temperatureC + ColumnWater.KELVIN_AT_ZERO_C
        val longwaveScale = STEFAN_BOLTZMANN_MJ_PER_M2_DAY_K4 * kelvin.pow(4) *
            (CLOUD_CLEAR_FACTOR * relativeShortwave - CLOUD_OFFSET)
        return HumidityTerms(
            constant = radiative * ((1.0 - albedo) * solar - longwaveScale * LONGWAVE_DRY_AIR) +
                aerodynamic * saturation,
            perRootHumidity = radiative * longwaveScale * LONGWAVE_PER_ROOT_KPA * sqrt(saturation),
            perHumidity = -aerodynamic * saturation
        )
    }

    /** A potential evaporation as a function of the air's relative humidity; see [referenceTerms]. */
    class HumidityTerms(val constant: Double, val perRootHumidity: Double, val perHumidity: Double) {
        /** Millimeters a day at relative humidity [relativeHumidity], held to 0..1; never below zero. */
        fun mmPerDay(relativeHumidity: Double): Double {
            val humidity = relativeHumidity.coerceIn(0.0, 1.0)
            return (constant + perRootHumidity * sqrt(humidity) + perHumidity * humidity).coerceAtLeast(0.0)
        }
    }

    /**
     * Eq. 6 itself, on the terms FAO-56's worked examples state: the mean temperature, the
     * saturation and actual vapor pressures, the available energy `Rn - G` in megajoules per
     * square meter per day, the air pressure and the wind at 2 m. Millimeters a day, held at zero
     * below. Its Example 17 (Bangkok in April) is `EvaporationFormulasTest`'s check on it.
     */
    fun penmanMonteithMmPerDay(
        temperatureC: Double,
        saturationKpa: Double,
        actualVaporKpa: Double,
        availableEnergyMjPerM2Day: Double,
        pressureKpa: Double,
        windAt2mMps: Double
    ): Double {
        val slope = ColumnWater.saturationSlopeKpaPerC(temperatureC)
        val psychrometric = psychrometricKpaPerC(pressureKpa)
        val deficit = (saturationKpa - actualVaporKpa).coerceAtLeast(0.0)
        val rate = (MM_PER_MJ * slope * availableEnergyMjPerM2Day +
            psychrometric * REFERENCE_AERODYNAMIC_TERM / (temperatureC + FAO_KELVIN_OFFSET) * windAt2mMps * deficit) /
            (slope + psychrometric * (1.0 + REFERENCE_RESISTANCE_RATIO_PER_MPS * windAt2mMps))
        return rate.coerceAtLeast(0.0)
    }

    /** Eq. 6's own kelvin offset, 273, which its 900 was derived with. */
    private const val FAO_KELVIN_OFFSET = 273.0

    /**
     * FAO-56's conversion of energy to evaporated water, Eq. 20: 0.408 mm per MJ/m², the inverse
     * of its latent heat of 2.45 MJ/kg.
     */
    private const val MM_PER_MJ = 0.408

    /** Eq. 6's 900 and 0.34: the reference crop's resistances. See [referenceEvapotranspirationMmPerDay]. */
    private const val REFERENCE_AERODYNAMIC_TERM = 900.0
    private const val REFERENCE_RESISTANCE_RATIO_PER_MPS = 0.34

    /**
     * Evaporation from open fresh water, in millimeters a day: Penman's (1948) combination
     * equation as McMahon and others (2013, Eq. 12 and 13) state it,
     *
     *     E = (D Rn_w 0.408 + g f(u) (e_s - e_a)) / (D + g),   f(u) = 1.313 + 1.381 u2
     *
     * with Penman's 1956 wind function, which they recommend as the standard for open water, in
     * millimeters a day per kilopascal, and the net radiation at water's albedo of 0.08. No
     * storage term and no advection, which is their statement of the equation's limits for a
     * large shallow lake.
     */
    fun openWaterEvaporationMmPerDay(
        temperatureC: Double,
        actualVaporKpa: Double,
        extraterrestrialMjPerM2Day: Double,
        elevationM: Double
    ): Double = openWaterTerms(temperatureC, extraterrestrialMjPerM2Day, elevationM)
        .mmPerDay(actualVaporKpa / ColumnWater.saturationVaporPressureKpa(temperatureC))

    /** [openWaterEvaporationMmPerDay] in the air's relative humidity; see [referenceTerms]. */
    fun openWaterTerms(temperatureC: Double, extraterrestrialMjPerM2Day: Double, elevationM: Double): HumidityTerms {
        val slope = ColumnWater.saturationSlopeKpaPerC(temperatureC)
        val psychrometric = psychrometricKpaPerC(pressureAtKpa(elevationM))
        val windFunction = PENMAN_WIND_CONSTANT + PENMAN_WIND_PER_MPS * LAND_WIND_AT_2_M_MPS
        val denominator = slope + psychrometric
        return humidityTerms(
            temperatureC, extraterrestrialMjPerM2Day, elevationM, OPEN_WATER_ALBEDO,
            radiative = MM_PER_MJ * slope / denominator, aerodynamic = psychrometric * windFunction / denominator
        )
    }

    /** Penman's (1956) wind function, as McMahon and others (2013) give it. */
    private const val PENMAN_WIND_CONSTANT = 1.313
    private const val PENMAN_WIND_PER_MPS = 1.381

    /** Seconds in a day. */
    const val SECONDS_PER_DAY = 86_400.0

    /** Days in a year, for turning a daily rate into a yearly one. */
    const val DAYS_PER_YEAR_DOUBLE = 365.25
}
