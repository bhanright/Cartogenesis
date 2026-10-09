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

    /** One kilometer in meters, for the lapse rate the configuration states per kilometer. */
    private const val METERS_PER_KM = 1_000.0

    /**
     * The least lapse rate the saturated column is evaluated at, in kelvin per meter: a tenth of a
     * degree per kilometer. Not physics but a guard: an isothermal column has no vapor scale
     * height, and the formula below would hand back an infinite column for a lapse of zero.
     */
    private const val MIN_LAPSE_K_PER_M = 1.0e-4

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
     * Clausius-Clapeyron up a column cooling at [lapseRateCPerKm]. The vapor density at saturation
     * at the surface is `e_s / (R_v T)`, and up a column cooling at `Gamma` its logarithm falls as
     * `d ln rho/dz = -Gamma (L / (R_v T) - 1) / T`, so the saturated vapor thins with height over
     * a scale height `H = T / (Gamma (L / (R_v T) - 1))` and the column holds `rho_s H`. At the
     * standard atmosphere's 6.5 K/km that is 2.5 km at 15 C, and the column 32 mm at 15 C, 10 mm at
     * 0 C, 58 mm at 25 C and 1.2 mm at -25 C. Earth's atmosphere holds 12.6e3 km³ over its 510
     * million km² (Trenberth and others 2011, as van der Ent and Tuinenburg 2017 use it), a mean
     * of 24.7 mm, which at a mean surface temperature near 14 C is three quarters of this column:
     * the check, read off the world in `MoistureClosureTest`, and not an input.
     *
     * The scale height is held at the surface's temperature rather than integrated through a
     * column whose temperature changes with height; over the 2.5 km that hold most of the water
     * that is a few percent, and the formula stays one exponential.
     */
    fun saturatedColumnMm(surfaceTemperatureC: Double, lapseRateCPerKm: Double): Double {
        val kelvin = surfaceTemperatureC + KELVIN_AT_ZERO_C
        val lapseKPerM = (lapseRateCPerKm / METERS_PER_KM).coerceAtLeast(MIN_LAPSE_K_PER_M)
        val vaporDensityKgPerM3 = saturationVaporPressureKpa(surfaceTemperatureC) * PASCALS_PER_KPA /
            (WATER_VAPOR_GAS_CONSTANT_J_PER_KG_K * kelvin)
        val scaleHeightM = kelvin /
            (lapseKPerM * (LATENT_HEAT_J_PER_KG / (WATER_VAPOR_GAS_CONSTANT_J_PER_KG_K * kelvin) - 1.0))
        return vaporDensityKgPerM3 * scaleHeightM
    }

    /** Pascals in a kilopascal. */
    private const val PASCALS_PER_KPA = 1_000.0
}
