package com.cartogenesis.worldgen.pipeline

import kotlin.math.exp
import kotlin.math.pow

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
     * The water a column of air would hold if it were saturated, over ground at
     * [surfaceTemperatureC] and [surfacePressureKpa], in kilograms per square meter, which is
     * millimeters of water: the denominator of the column's relative humidity, as Bretherton,
     * Peters and Back (2004, section 2) sum it, the saturation humidity integrated up the column's
     * temperature profile.
     *
     * The profile is the one the boundary layer's own air makes rising: surface air at
     * [PARCEL_RELATIVE_HUMIDITY] lifted dry to the height where it saturates, then up the moist
     * adiabat ([moistAdiabaticLapseKPerM]), its pressure hydrostatic from the ground's. The
     * saturation vapor density `e_s / (R_v T)` is summed up all of it, below the condensation level
     * too, where the parcel itself is not saturated: the column's saturated water is a property of
     * its temperatures, not of the parcel's own water. Over the tropical sea the profile stands
     * near the temperatures Bretherton and others integrated (theirs from analyzed soundings,
     * whose temperature above the boundary layer varies by one or two kelvin over the tropical
     * oceans): 63 mm at 25 C and 81 at 28 C against their 72 in the regions of active convection,
     * where the moist adiabat climbed from saturated air at the surface's own temperature stands
     * warmer aloft and holds 82 and 106 (docs/DESIGN_LEDGER.md, W2b).
     *
     * Over high ground the column starts at the ground's lower pressure, where a saturated parcel's
     * lapse rate is smaller for the same temperature, and the condensation level's height is the
     * parcel's own.
     *
     * Earth's atmosphere holds 12.6e3 km³ over its 510 million km² (Trenberth and others 2011, as
     * van der Ent and Tuinenburg 2017 use it), a mean of 24.7 mm: the check, read off the world in
     * `RainAgainstEarthTest`, and not an input.
     *
     * Tabulated once by the surface's temperature at [COLUMN_TABLE_STEP_C] and its pressure at
     * [COLUMN_TABLE_PRESSURE_STEP_KPA] and read by bilinear interpolation, since every cell of every
     * lap asks it.
     */
    fun saturatedColumnMm(surfaceTemperatureC: Double, surfacePressureKpa: Double = SEA_LEVEL_PRESSURE_KPA): Double {
        val temperatures = COLUMN_TABLE_TEMPERATURES
        val pressures = COLUMN_TABLE_PRESSURES
        val across = ((surfaceTemperatureC - COLUMN_TABLE_COLDEST_C) / COLUMN_TABLE_STEP_C).coerceIn(0.0, (temperatures - 1).toDouble())
        val down = ((COLUMN_TABLE_HIGHEST_PRESSURE_KPA - surfacePressureKpa) / COLUMN_TABLE_PRESSURE_STEP_KPA).coerceIn(0.0, (pressures - 1).toDouble())
        val left = across.toInt().coerceAtMost(temperatures - 2)
        val top = down.toInt().coerceAtMost(pressures - 2)
        val acrossShare = across - left
        val downShare = down - top
        val table = saturatedColumnTable
        val upper = table[top * temperatures + left] * (1.0 - acrossShare) + table[top * temperatures + left + 1] * acrossShare
        val lower = table[(top + 1) * temperatures + left] * (1.0 - acrossShare) + table[(top + 1) * temperatures + left + 1] * acrossShare
        return upper * (1.0 - downShare) + lower * downShare
    }

    /**
     * The relative humidity of the surface parcel whose rise sets the column's temperature profile:
     * Dai's (2006) marine surface air, [MoistureMarch.MARINE_RELATIVE_HUMIDITY], the boundary-layer
     * air that feeds the convection whose profile the tropical troposphere keeps. Read the same
     * everywhere: the column's own humidity would make its denominator move with its water.
     */
    const val PARCEL_RELATIVE_HUMIDITY = MoistureMarch.MARINE_RELATIVE_HUMIDITY

    /**
     * The pressure at ground [elevationM] above the sea under air whose surface stands at
     * [surfaceTemperatureC], kilopascals: the hypsometric equation from the standard sea level,
     * through a layer whose mean temperature is the surface's plus half the environment's
     * [lapseRateCPerKm] over the height.
     */
    fun surfacePressureKpa(elevationM: Double, surfaceTemperatureC: Double, lapseRateCPerKm: Double): Double {
        if (elevationM <= 0.0) return SEA_LEVEL_PRESSURE_KPA
        val meanKelvin = surfaceTemperatureC + KELVIN_AT_ZERO_C + 0.5 * lapseRateCPerKm * elevationM / METERS_PER_KM
        return SEA_LEVEL_PRESSURE_KPA * exp(-STANDARD_GRAVITY_MPS2 * elevationM / (DRY_AIR_GAS_CONSTANT_J_PER_KG_K * meanKelvin))
    }

    /**
     * Where the moist adiabat from a surface at [surfaceTemperatureC] stands: [heightsMeters] the
     * height at which it reaches each of the pressures asked, and [topMeters] the height above which
     * its saturated column holds the share asked of its water.
     */
    class Adiabat(val heightsMeters: DoubleArray, val topMeters: Double)

    /**
     * The [Adiabat] of a surface at [surfaceTemperatureC] for [pressuresKpa] (each under the
     * standard sea level's) and [shareAbove]: the column climbed as [integratedSaturatedColumnMm]
     * climbs it, in the same steps to the same top, each pressure's height read by the logarithm of
     * the pressure between the steps either side, a pressure the climb never reaches standing at
     * its top.
     */
    internal fun moistAdiabatHeights(surfaceTemperatureC: Double, pressuresKpa: DoubleArray, shareAbove: Double): Adiabat {
        val steps = (COLUMN_TOP_M / COLUMN_STEP_M).toInt()
        val heights = DoubleArray(steps + 1)
        val pressures = DoubleArray(steps + 1)
        val water = DoubleArray(steps)
        var kelvin = surfaceTemperatureC + KELVIN_AT_ZERO_C
        var pressureKpa = SEA_LEVEL_PRESSURE_KPA
        pressures[0] = pressureKpa
        for (step in 0 until steps) {
            val lapse = moistAdiabaticLapseKPerM(kelvin - KELVIN_AT_ZERO_C, pressureKpa)
            val midKelvin = kelvin - lapse * COLUMN_STEP_M * 0.5
            val midPressure = pressureKpa * exp(-STANDARD_GRAVITY_MPS2 * COLUMN_STEP_M * 0.5 / (DRY_AIR_GAS_CONSTANT_J_PER_KG_K * kelvin))
            val midCelsius = midKelvin - KELVIN_AT_ZERO_C
            water[step] = saturationVaporPressureKpa(midCelsius) * PASCALS_PER_KPA / (WATER_VAPOR_GAS_CONSTANT_J_PER_KG_K * midKelvin) * COLUMN_STEP_M
            kelvin -= moistAdiabaticLapseKPerM(midCelsius, midPressure) * COLUMN_STEP_M
            pressureKpa *= exp(-STANDARD_GRAVITY_MPS2 * COLUMN_STEP_M / (DRY_AIR_GAS_CONSTANT_J_PER_KG_K * midKelvin))
            heights[step + 1] = (step + 1) * COLUMN_STEP_M
            pressures[step + 1] = pressureKpa
        }
        val total = water.sum()
        var below = 0.0
        var top = COLUMN_TOP_M
        for (step in 0 until steps) {
            if (below + water[step] >= (1.0 - shareAbove) * total) {
                top = heights[step] + COLUMN_STEP_M * ((1.0 - shareAbove) * total - below) / water[step]
                break
            }
            below += water[step]
        }
        val result = DoubleArray(pressuresKpa.size) { index ->
            val target = pressuresKpa[index]
            var height = COLUMN_TOP_M
            for (step in 0 until steps) {
                if (pressures[step + 1] <= target) {
                    val share = kotlin.math.ln(pressures[step] / target) / kotlin.math.ln(pressures[step] / pressures[step + 1])
                    height = heights[step] + share.coerceIn(0.0, 1.0) * COLUMN_STEP_M
                    break
                }
            }
            if (target >= SEA_LEVEL_PRESSURE_KPA) 0.0 else height
        }
        return Adiabat(result, top)
    }

    /**
     * The saturated column of the profile [saturatedColumnMm] reads, summed from a surface at
     * [surfaceTemperatureC] and [surfacePressureKpa], mm: the parcel's temperature, its surface air
     * at [parcelRelativeHumidity], carried up dry until its own water saturates it and moist after,
     * by the midpoint rule over each step, and the saturation vapor density summed at every step's
     * middle. At a humidity of one it is the moist adiabat from saturated surface air.
     */
    internal fun integratedSaturatedColumnMm(
        surfaceTemperatureC: Double,
        surfacePressureKpa: Double,
        parcelRelativeHumidity: Double = PARCEL_RELATIVE_HUMIDITY
    ): Double {
        val surfaceKelvin = surfaceTemperatureC + KELVIN_AT_ZERO_C
        val parcelHumidity = specificHumidity(parcelRelativeHumidity * saturationVaporPressureKpa(surfaceTemperatureC), surfacePressureKpa)
        // Dry, the parcel's temperature falls at g / c_p and its pressure as `(T / T_0)^(c_p / R_d)`;
        // it saturates where its saturation humidity falls to its own, found by bisection so the
        // column moves smoothly with the surface's temperature.
        fun dryKelvin(height: Double) = surfaceKelvin - DRY_LAPSE_K_PER_M * height
        fun dryPressureKpa(height: Double) =
            surfacePressureKpa * (dryKelvin(height) / surfaceKelvin).pow(DRY_AIR_HEAT_CAPACITY_J_PER_KG_K / DRY_AIR_GAS_CONSTANT_J_PER_KG_K)
        fun saturationDeficit(height: Double) =
            specificHumidity(saturationVaporPressureKpa(dryKelvin(height) - KELVIN_AT_ZERO_C), dryPressureKpa(height)) - parcelHumidity
        var condensationLevel = 0.0
        if (saturationDeficit(0.0) > 0.0) {
            var low = 0.0
            var high = MAX_CONDENSATION_LEVEL_M
            repeat(CONDENSATION_LEVEL_BISECTIONS) {
                val middle = 0.5 * (low + high)
                if (saturationDeficit(middle) > 0.0) low = middle else high = middle
            }
            condensationLevel = 0.5 * (low + high)
        }
        var column = 0.0
        val drySteps = kotlin.math.ceil(condensationLevel / COLUMN_STEP_M).toInt()
        for (step in 0 until drySteps) {
            val thickness = condensationLevel / drySteps
            val midKelvin = dryKelvin((step + 0.5) * thickness)
            column += saturationVaporPressureKpa(midKelvin - KELVIN_AT_ZERO_C) * PASCALS_PER_KPA /
                (WATER_VAPOR_GAS_CONSTANT_J_PER_KG_K * midKelvin) * thickness
        }
        var kelvin = dryKelvin(condensationLevel)
        var pressureKpa = dryPressureKpa(condensationLevel)
        var height = condensationLevel
        while (height < COLUMN_TOP_M) {
            val celsius = kelvin - KELVIN_AT_ZERO_C
            val lapse = moistAdiabaticLapseKPerM(celsius, pressureKpa)
            val midKelvin = kelvin - lapse * COLUMN_STEP_M * 0.5
            val midPressure = pressureKpa * exp(-STANDARD_GRAVITY_MPS2 * COLUMN_STEP_M * 0.5 / (DRY_AIR_GAS_CONSTANT_J_PER_KG_K * kelvin))
            val midCelsius = midKelvin - KELVIN_AT_ZERO_C
            val midDensity = saturationVaporPressureKpa(midCelsius) * PASCALS_PER_KPA / (WATER_VAPOR_GAS_CONSTANT_J_PER_KG_K * midKelvin)
            column += midDensity * COLUMN_STEP_M
            kelvin -= moistAdiabaticLapseKPerM(midCelsius, midPressure) * COLUMN_STEP_M
            pressureKpa *= exp(-STANDARD_GRAVITY_MPS2 * COLUMN_STEP_M / (DRY_AIR_GAS_CONSTANT_J_PER_KG_K * midKelvin))
            height += COLUMN_STEP_M
            if (midDensity < NEGLIGIBLE_VAPOR_KG_PER_M3) break
        }
        return column
    }

    /**
     * The table's span and step in the surface's temperature, degrees Celsius: from colder than any
     * air the energy balance makes to warmer than any it makes, at a quarter of a degree, where the
     * interpolation is under a part in a thousand off the integral (the column grows 6 to 10% a
     * degree and bends little over a quarter of one).
     */
    private const val COLUMN_TABLE_COLDEST_C = -80.0
    private const val COLUMN_TABLE_WARMEST_C = 50.0
    private const val COLUMN_TABLE_STEP_C = 0.25
    private val COLUMN_TABLE_TEMPERATURES = ((COLUMN_TABLE_WARMEST_C - COLUMN_TABLE_COLDEST_C) / COLUMN_TABLE_STEP_C).toInt() + 1

    /**
     * Its span and step in the surface's pressure, kilopascals: from over the standard sea level's
     * to the pressure on ground higher than any the plates raise, nine kilometers, at two and a half,
     * over which the column changes by under 2%.
     */
    private const val COLUMN_TABLE_HIGHEST_PRESSURE_KPA = 105.0
    private const val COLUMN_TABLE_LOWEST_PRESSURE_KPA = 27.5
    private const val COLUMN_TABLE_PRESSURE_STEP_KPA = 2.5
    private val COLUMN_TABLE_PRESSURES =
        ((COLUMN_TABLE_HIGHEST_PRESSURE_KPA - COLUMN_TABLE_LOWEST_PRESSURE_KPA) / COLUMN_TABLE_PRESSURE_STEP_KPA).toInt() + 1

    /**
     * The column's step and top, meters: fifty meters, a hundredth of the vapor's own scale height,
     * up to sixteen kilometers, over the tropical tropopause, past which a saturated column holds
     * under a hundred thousandth of its water.
     */
    private const val COLUMN_STEP_M = 50.0
    private const val COLUMN_TOP_M = 16_000.0

    /**
     * Where the parcel's condensation level is sought, meters, and in how many halvings: up to six
     * kilometers, over the driest air's (surface air at a tenth of saturation saturates near four),
     * to a few millimeters.
     */
    private const val MAX_CONDENSATION_LEVEL_M = 6_000.0
    private const val CONDENSATION_LEVEL_BISECTIONS = 40

    /** Below this saturated vapor density the rest of the column is nothing, kg m⁻³. */
    private const val NEGLIGIBLE_VAPOR_KG_PER_M3 = 1.0e-9

    /**
     * The table, a row of temperatures for each pressure from the highest down. Built when the
     * object is first read and held in a plain field, since every cell of every lap reads it from
     * several threads at once.
     */
    private val saturatedColumnTable: DoubleArray =
        DoubleArray(COLUMN_TABLE_PRESSURES * COLUMN_TABLE_TEMPERATURES) { index ->
            integratedSaturatedColumnMm(
                COLUMN_TABLE_COLDEST_C + (index % COLUMN_TABLE_TEMPERATURES) * COLUMN_TABLE_STEP_C,
                COLUMN_TABLE_HIGHEST_PRESSURE_KPA - (index / COLUMN_TABLE_TEMPERATURES) * COLUMN_TABLE_PRESSURE_STEP_KPA
            )
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

    /** The dry adiabat's lapse rate, `g / c_p`, kelvin per meter. */
    private const val DRY_LAPSE_K_PER_M = STANDARD_GRAVITY_MPS2 / DRY_AIR_HEAT_CAPACITY_J_PER_KG_K
}
