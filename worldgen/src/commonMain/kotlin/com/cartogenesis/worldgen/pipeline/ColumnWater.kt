package com.cartogenesis.worldgen.pipeline

import kotlin.math.exp

/**
 * How much water a column of air can hold, and how wet the air near the ground is: the
 * thermodynamics the moisture march and the evaporation formulas share, in physical units.
 *
 * Every figure here is either a physical constant or a formula read from its source; nothing is
 * fitted to a world. See docs/DESIGN_LEDGER.md, C1b.
 */
internal object ColumnWater {

    /**
     * The gas constant of water vapor, in joules per kilogram per kelvin: the universal gas
     * constant, 8.314 J/(mol K), over water's molar mass, 0.018015 kg/mol.
     */
    const val WATER_VAPOR_GAS_CONSTANT_J_PER_KG_K = 461.5

    /**
     * The latent heat of vaporization at the surface, in joules per kilogram: Large and Yeager's
     * (2004) 2.5e6, the figure their bulk formulas carry. FAO-56 uses 2.45e6 for its own
     * equation, the value near 20 C, and [SurfaceEvaporation] keeps that one where FAO-56's
     * constants are used as a set.
     */
    const val LATENT_HEAT_J_PER_KG = 2.5e6

    /** Zero Celsius in kelvin. */
    const val KELVIN_AT_ZERO_C = 273.15

    /** The standard atmosphere at sea level, in kilopascals. */
    const val SEA_LEVEL_PRESSURE_KPA = 101.325

    /**
     * Saturation vapor pressure over water at [temperatureC], in kilopascals: FAO-56's Eq. 11
     * (Allen and others 1998), `0.6108 exp(17.27 T / (T + 237.3))`, the Tetens form.
     */
    fun saturationVaporPressureKpa(temperatureC: Double): Double =
        TETENS_KPA * exp(TETENS_SLOPE * temperatureC / (temperatureC + TETENS_OFFSET_C))

    /**
     * The slope of [saturationVaporPressureKpa] at [temperatureC], in kilopascals per degree: the
     * derivative of Eq. 11 itself, `17.27 x 237.3 e / (T + 237.3)^2`, which is FAO-56's Eq. 13 with
     * its 4098 written out.
     */
    fun saturationSlopeKpaPerC(temperatureC: Double): Double {
        val shifted = temperatureC + TETENS_OFFSET_C
        return TETENS_SLOPE * TETENS_OFFSET_C * saturationVaporPressureKpa(temperatureC) /
            (shifted * shifted)
    }

    /** Eq. 11's three coefficients: kilopascals, a dimensionless slope and degrees Celsius. */
    private const val TETENS_KPA = 0.6108
    private const val TETENS_SLOPE = 17.27
    private const val TETENS_OFFSET_C = 237.3

    /**
     * The specific humidity, kilograms of vapor per kilogram of moist air, of air at
     * [pressureKpa] holding vapor at [vaporPressureKpa]: `0.622 e / (p - 0.378 e)`, with 0.622 the
     * ratio of water's molar mass to dry air's.
     */
    fun specificHumidity(vaporPressureKpa: Double, pressureKpa: Double): Double =
        MOLAR_MASS_RATIO * vaporPressureKpa / (pressureKpa - (1.0 - MOLAR_MASS_RATIO) * vaporPressureKpa)

    /** Water's molar mass over dry air's. */
    private const val MOLAR_MASS_RATIO = 0.622

    /**
     * The water a saturated column of air holds over ground at [surfaceTemperatureC], in
     * kilograms per square meter, which is millimeters of water.
     *
     * Clausius-Clapeyron up the saturated adiabat: the column's temperature falls with height at
     * the moist adiabat's own lapse rate ([moistAdiabaticLapseKPerM]) from the surface's, its
     * pressure hydrostatically from the standard sea level's, and the saturated vapor density
     * `e_s / (R_v T)` is summed up it, in [COLUMN_STEP_M] steps to [COLUMN_TOP_M]. The tropical
     * troposphere stands close to that adiabat (Xu and Emanuel 1989), and Bretherton, Peters and
     * Back's (2004) column humidity, which the march's rain reads, divides by the saturated column
     * of the real temperature profile. It is 75 mm at 25 C, 36 mm at 15 C, 13 mm at 0 C and 1.3
     * at -25 C. Earth's atmosphere holds 12.6e3 km³ over its 510 million km² (Trenberth and
     * others 2011, as van der Ent and Tuinenburg 2017 use it), a mean of 24.7 mm: the check, read
     * off the world in `RainAgainstEarthTest`, and not an input.
     *
     * Tabulated once by the surface's temperature at [COLUMN_TABLE_STEP_C] and read by linear
     * interpolation, since every cell of every lap asks it.
     */
    fun saturatedColumnMm(surfaceTemperatureC: Double): Double {
        val position = ((surfaceTemperatureC - COLUMN_TABLE_COLDEST_C) / COLUMN_TABLE_STEP_C)
            .coerceIn(0.0, (saturatedColumnTable.size - 1).toDouble())
        val below = position.toInt().coerceAtMost(saturatedColumnTable.size - 2)
        val share = position - below
        return saturatedColumnTable[below] * (1.0 - share) + saturatedColumnTable[below + 1] * share
    }

    /** The saturated column summed up the moist adiabat from one surface temperature, mm. */
    internal fun integratedSaturatedColumnMm(surfaceTemperatureC: Double): Double {
        var kelvin = surfaceTemperatureC + KELVIN_AT_ZERO_C
        var pressureKpa = SEA_LEVEL_PRESSURE_KPA
        var column = 0.0
        var height = 0.0
        while (height < COLUMN_TOP_M) {
            val celsius = kelvin - KELVIN_AT_ZERO_C
            val density = saturationVaporPressureKpa(celsius) * PASCALS_PER_KPA / (WATER_VAPOR_GAS_CONSTANT_J_PER_KG_K * kelvin)
            // The midpoint rule over the step: the density there, from the lapse and the
            // hydrostatic fall half a step up.
            val lapse = moistAdiabaticLapseKPerM(celsius, pressureKpa)
            val midKelvin = kelvin - lapse * COLUMN_STEP_M * 0.5
            val midPressure = pressureKpa * kotlin.math.exp(-STANDARD_GRAVITY_MPS2 * COLUMN_STEP_M * 0.5 / (DRY_AIR_GAS_CONSTANT_J_PER_KG_K * kelvin))
            val midCelsius = midKelvin - KELVIN_AT_ZERO_C
            val midDensity = saturationVaporPressureKpa(midCelsius) * PASCALS_PER_KPA / (WATER_VAPOR_GAS_CONSTANT_J_PER_KG_K * midKelvin)
            column += midDensity * COLUMN_STEP_M
            val midLapse = moistAdiabaticLapseKPerM(midCelsius, midPressure)
            kelvin -= midLapse * COLUMN_STEP_M
            pressureKpa *= kotlin.math.exp(-STANDARD_GRAVITY_MPS2 * COLUMN_STEP_M / (DRY_AIR_GAS_CONSTANT_J_PER_KG_K * midKelvin))
            height += COLUMN_STEP_M
            if (density < NEGLIGIBLE_VAPOR_KG_PER_M3) break
        }
        return column
    }

    /**
     * The table's span and step, degrees Celsius: from colder than any air the energy balance
     * makes to warmer than any it makes, at a twentieth of a degree, where the interpolation is
     * a part in a hundred thousand off the integral.
     */
    private const val COLUMN_TABLE_COLDEST_C = -80.0
    private const val COLUMN_TABLE_WARMEST_C = 50.0
    private const val COLUMN_TABLE_STEP_C = 0.05

    /**
     * The column's step and top, meters: fifty meters, a hundredth of the vapor's own scale height,
     * up to sixteen kilometers, over the tropical tropopause, past which a saturated column holds
     * under a hundred thousandth of its water.
     */
    private const val COLUMN_STEP_M = 50.0
    private const val COLUMN_TOP_M = 16_000.0

    /** Below this saturated vapor density the rest of the column is nothing, kg m⁻³. */
    private const val NEGLIGIBLE_VAPOR_KG_PER_M3 = 1.0e-9

    private val saturatedColumnTable: DoubleArray by lazy {
        val steps = ((COLUMN_TABLE_WARMEST_C - COLUMN_TABLE_COLDEST_C) / COLUMN_TABLE_STEP_C).toInt() + 1
        DoubleArray(steps) { integratedSaturatedColumnMm(COLUMN_TABLE_COLDEST_C + it * COLUMN_TABLE_STEP_C) }
    }

    /** Pascals in a kilopascal. */
    private const val PASCALS_PER_KPA = 1_000.0

    /**
     * The water air lifted over the ground condenses per meter it rises, in kilograms per cubic
     * meter: Smith and Barstad's (2004) thermodynamic sensitivity `C_w = rho_s Gamma_m / gamma`,
     * the saturated vapor density at the surface's [surfaceTemperatureC] times the moist
     * adiabat's lapse rate over the environment's, [lapseRateCPerKm]. A column of saturated air
     * carried up a slope at `w` meters a second condenses `C_w w` kilograms per square meter a
     * second, which is their linear model's source term.
     */
    fun upliftCondensationKgPerM3(surfaceTemperatureC: Double, lapseRateCPerKm: Double): Double {
        val kelvin = surfaceTemperatureC + KELVIN_AT_ZERO_C
        val vaporPressurePa = saturationVaporPressureKpa(surfaceTemperatureC) * PASCALS_PER_KPA
        val vaporDensity = vaporPressurePa / (WATER_VAPOR_GAS_CONSTANT_J_PER_KG_K * kelvin)
        val environmentKPerM = lapseRateCPerKm / METERS_PER_KM
        if (environmentKPerM <= 0.0) return 0.0
        return vaporDensity * moistAdiabaticLapseKPerM(surfaceTemperatureC, SEA_LEVEL_PRESSURE_KPA) / environmentKPerM
    }

    /** One kilometer in meters, for the lapse rate the configuration states per kilometer. */
    private const val METERS_PER_KM = 1_000.0


    /**
     * The saturated adiabatic lapse rate at [temperatureC] and [pressureKpa], kelvin per meter:
     * `g (1 + L r / (R_d T)) / (c_p + L² r epsilon / (R_d T²))`, with `r` the saturation mixing
     * ratio and `epsilon` water's molar mass over dry air's (the American Meteorological
     * Society's glossary; Wallace and Hobbs 2006, section 3.5). 3.8 K/km at 25 C and sea level, 6.5
     * at 0 C, nearing the dry 9.8 in the cold.
     */
    fun moistAdiabaticLapseKPerM(temperatureC: Double, pressureKpa: Double): Double {
        val kelvin = temperatureC + KELVIN_AT_ZERO_C
        val vaporPressure = saturationVaporPressureKpa(temperatureC)
        val mixingRatio = MOLAR_MASS_RATIO * vaporPressure / (pressureKpa - vaporPressure)
        val numerator = 1.0 + LATENT_HEAT_J_PER_KG * mixingRatio / (DRY_AIR_GAS_CONSTANT_J_PER_KG_K * kelvin)
        val denominator = DRY_AIR_HEAT_CAPACITY_J_PER_KG_K +
            LATENT_HEAT_J_PER_KG * LATENT_HEAT_J_PER_KG * mixingRatio * MOLAR_MASS_RATIO /
            (DRY_AIR_GAS_CONSTANT_J_PER_KG_K * kelvin * kelvin)
        return STANDARD_GRAVITY_MPS2 * numerator / denominator
    }

    /** Dry air's gas constant and heat capacity at constant pressure, and standard gravity. */
    private const val DRY_AIR_GAS_CONSTANT_J_PER_KG_K = 287.04
    private const val DRY_AIR_HEAT_CAPACITY_J_PER_KG_K = 1004.0
    private const val STANDARD_GRAVITY_MPS2 = 9.80665
}
