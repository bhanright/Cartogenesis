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

    /** One kilometer in meters, for the lapse rate the configuration states per kilometer. */
    private const val METERS_PER_KM = 1_000.0

    /**
     * The boundary layer's depth in pressure, kilopascals: the dry atmosphere's lowest layer, the
     * mass the surface wind's drag balance acts on ([BoundaryLayer.LAYER_THICKNESS_PA]), so the
     * water the boundary layer carries moves on the wind that layer blows. 1.1 km deep over the sea,
     * between the trades' well-mixed subcloud layer, 0.69 to 0.71 km deep over EUREC4A's sondes, and
     * the trade inversion's base near 2 km (Albright and others 2022, read as chapter 4 of Albright's
     * thesis, its Table 4.2; Galewsky and Los 2026, section 2.1).
     */
    const val BOUNDARY_LAYER_DEPTH_KPA = BoundaryLayer.LAYER_THICKNESS_PA / PASCALS_PER_KPA

    /** Saturation specific humidity at [temperatureC] and [pressureKpa], kilograms per kilogram. */
    fun saturationSpecificHumidity(temperatureC: Double, pressureKpa: Double): Double =
        specificHumidity(saturationVaporPressureKpa(temperatureC), pressureKpa)

    /**
     * The part of the saturated column ([saturatedColumnMm]) of a sea-level surface at
     * [seaLevelTemperatureC] that lies under the boundary layer's top, [BOUNDARY_LAYER_DEPTH_KPA]
     * above it, millimeters, read from a table at [COLUMN_TABLE_STEP_C] by linear interpolation. The
     * rest, [referenceFreeSaturatedMm], is the free troposphere's saturated column: the column of
     * the free troposphere's own air, wherever the ground under it stands ([MoistureMarch]).
     */
    fun referenceBoundaryLayerSaturatedMm(seaLevelTemperatureC: Double): Double {
        val across = ((seaLevelTemperatureC - COLUMN_TABLE_COLDEST_C) / COLUMN_TABLE_STEP_C).coerceIn(0.0, (COLUMN_TABLE_TEMPERATURES - 1).toDouble())
        val left = across.toInt().coerceAtMost(COLUMN_TABLE_TEMPERATURES - 2)
        val share = across - left
        return boundaryLayerTable[left] * (1.0 - share) + boundaryLayerTable[left + 1] * share
    }

    /** The free troposphere's saturated column over a sea-level surface at [seaLevelTemperatureC], millimeters. */
    fun referenceFreeSaturatedMm(seaLevelTemperatureC: Double): Double =
        saturatedColumnMm(seaLevelTemperatureC) - referenceBoundaryLayerSaturatedMm(seaLevelTemperatureC)

    /**
     * The saturated column's part under the boundary layer's top on the parcel profile
     * [integratedSaturatedColumnMm] climbs from a sea-level surface at [surfaceTemperatureC],
     * millimeters: the same steps, stopped where the pressure falls [BOUNDARY_LAYER_DEPTH_KPA] under
     * the surface's.
     */
    internal fun integratedBoundaryLayerPart(surfaceTemperatureC: Double): Double {
        val surfacePressureKpa = SEA_LEVEL_PRESSURE_KPA
        val topPressureKpa = surfacePressureKpa - BOUNDARY_LAYER_DEPTH_KPA
        val surfaceKelvin = surfaceTemperatureC + KELVIN_AT_ZERO_C
        val parcelHumidity = specificHumidity(PARCEL_RELATIVE_HUMIDITY * saturationVaporPressureKpa(surfaceTemperatureC), surfacePressureKpa)
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
        // Where the dry part of the climb reaches the layer's top, if it does: the dry adiabat's
        // pressure falls as a power of its temperature, so the height is read off it directly.
        val dryTopHeight = surfaceKelvin / DRY_LAPSE_K_PER_M *
            (1.0 - (topPressureKpa / surfacePressureKpa).pow(DRY_AIR_GAS_CONSTANT_J_PER_KG_K / DRY_AIR_HEAT_CAPACITY_J_PER_KG_K))
        var column = 0.0
        val dryEnd = minOf(condensationLevel, dryTopHeight)
        val drySteps = kotlin.math.ceil(dryEnd / COLUMN_STEP_M).toInt()
        for (step in 0 until drySteps) {
            val thickness = dryEnd / drySteps
            val midKelvin = dryKelvin((step + 0.5) * thickness)
            column += saturationVaporPressureKpa(midKelvin - KELVIN_AT_ZERO_C) * PASCALS_PER_KPA /
                (WATER_VAPOR_GAS_CONSTANT_J_PER_KG_K * midKelvin) * thickness
        }
        if (dryTopHeight <= condensationLevel) return column
        var kelvin = dryKelvin(condensationLevel)
        var pressureKpa = dryPressureKpa(condensationLevel)
        while (true) {
            val celsius = kelvin - KELVIN_AT_ZERO_C
            val lapse = moistAdiabaticLapseKPerM(celsius, pressureKpa)
            val midKelvin = kelvin - lapse * COLUMN_STEP_M * 0.5
            val midPressure = pressureKpa * exp(-STANDARD_GRAVITY_MPS2 * COLUMN_STEP_M * 0.5 / (DRY_AIR_GAS_CONSTANT_J_PER_KG_K * kelvin))
            val midCelsius = midKelvin - KELVIN_AT_ZERO_C
            val midDensity = saturationVaporPressureKpa(midCelsius) * PASCALS_PER_KPA / (WATER_VAPOR_GAS_CONSTANT_J_PER_KG_K * midKelvin)
            val nextKelvin = kelvin - moistAdiabaticLapseKPerM(midCelsius, midPressure) * COLUMN_STEP_M
            val nextPressure = pressureKpa * exp(-STANDARD_GRAVITY_MPS2 * COLUMN_STEP_M / (DRY_AIR_GAS_CONSTANT_J_PER_KG_K * midKelvin))
            if (nextPressure <= topPressureKpa) {
                // The last step's share that lies under the top, by the logarithm of the pressure.
                val share = kotlin.math.ln(pressureKpa / topPressureKpa) / kotlin.math.ln(pressureKpa / nextPressure)
                return column + midDensity * COLUMN_STEP_M * share
            }
            column += midDensity * COLUMN_STEP_M
            kelvin = nextKelvin
            pressureKpa = nextPressure
        }
    }

    /** [integratedBoundaryLayerPart] at every temperature of the column table. */
    private val boundaryLayerTable: DoubleArray = DoubleArray(COLUMN_TABLE_TEMPERATURES) { index ->
        integratedBoundaryLayerPart(COLUMN_TABLE_COLDEST_C + index * COLUMN_TABLE_STEP_C)
    }

    /**
     * The boundary layer's depth in meters over ground at [groundPressureKpa] whose air stands at
     * [surfaceTemperatureC]: [BOUNDARY_LAYER_DEPTH_KPA] by the hypsometric equation, through air
     * cooling at the dry adiabat's rate, the well-mixed layer's own. 1.1 km at 25 C over the sea.
     */
    fun boundaryLayerDepthM(surfaceTemperatureC: Double, groundPressureKpa: Double): Double {
        val surfaceKelvin = surfaceTemperatureC + KELVIN_AT_ZERO_C
        val topPressureKpa = (groundPressureKpa - BOUNDARY_LAYER_DEPTH_KPA).coerceAtLeast(MIN_LAYER_TOP_KPA)
        return surfaceKelvin / DRY_LAPSE_K_PER_M *
            (1.0 - (topPressureKpa / groundPressureKpa).pow(DRY_AIR_GAS_CONSTANT_J_PER_KG_K / DRY_AIR_HEAT_CAPACITY_J_PER_KG_K))
    }

    /** The least pressure a layer's top is read at, kilopascals: under any ground the plates raise. */
    private const val MIN_LAYER_TOP_KPA = 10.0

    /**
     * What the boundary layer's air condenses per meter it is lifted, in kilograms per square meter
     * per meter, with the meter of lift's derivative in the layer's water: the thermodynamics of
     * Smith and Barstad's (2004) source term read on the layer's own air rather than on a saturated
     * column.
     *
     * The layer is well mixed: its air at every height carries the specific humidity [humidity]
     * (kg/kg) of its surface air at [surfaceTemperatureC] and [groundPressureKpa], and climbs dry
     * to its lifting condensation level `z_c`, where its own water saturates it. Lifted at `w`, the
     * air above `z_c` and under the layer's top, [layerDepthM] over the ground, rises along the
     * moist adiabat and condenses `rho (-dq_s/dz)` per meter, so for each meter of lift the layer
     * condenses what its saturated part loses climbing from `z_c` to the top, `rho (q - q_s(top))`;
     * none where `z_c` stands at or over the top. Air under its condensation level only cools.
     *
     * Into [into]: that yield in kilograms per square meter per meter of lift, and its slope in the
     * layer's water, per millimeter of column water, the condensation level moving with the humidity.
     * The condensation level is found by Newton's method on the dry
     * adiabat, where the vapor's pressure falls with the air's in proportion and the saturation
     * pressure falls with the temperature (Tetens' form, [saturationVaporPressureKpa]).
     */
    fun boundaryLayerLiftYield(
        surfaceTemperatureC: Double,
        groundPressureKpa: Double,
        layerDepthM: Double,
        humidity: Double,
        into: DoubleArray
    ) {
        into[0] = 0.0
        into[1] = 0.0
        if (humidity <= 0.0) return
        val surfaceKelvin = surfaceTemperatureC + KELVIN_AT_ZERO_C
        val vaporPressure = humidity * groundPressureKpa / (MOLAR_MASS_RATIO + (1.0 - MOLAR_MASS_RATIO) * humidity)
        val logVapor = kotlin.math.ln(vaporPressure / TETENS_KPA)
        // The dew point, from Tetens' form inverted; the condensation level's temperature lies under it.
        var levelC = TETENS_OFFSET_C * logVapor / (TETENS_SLOPE - logVapor)
        // Rising dry the air cools by more than its dew point does, so its condensation level is
        // over the height where the air has cooled to the dew point; a layer that ends under that
        // height never condenses, and most of the land's layers do, so they go no further.
        if ((surfaceKelvin - (levelC + KELVIN_AT_ZERO_C)) / DRY_LAPSE_K_PER_M >= layerDepthM) return
        var slope = 1.0
        repeat(CONDENSATION_LEVEL_NEWTON_STEPS) {
            val shifted = levelC + TETENS_OFFSET_C
            val levelKelvin = levelC + KELVIN_AT_ZERO_C
            val excess = TETENS_SLOPE * levelC / shifted - logVapor - DRY_POWER * kotlin.math.ln(levelKelvin / surfaceKelvin)
            slope = TETENS_SLOPE * TETENS_OFFSET_C / (shifted * shifted) - DRY_POWER / levelKelvin
            levelC -= excess / slope
        }
        val levelKelvin = levelC + KELVIN_AT_ZERO_C
        val levelHeight = ((surfaceKelvin - levelKelvin) / DRY_LAPSE_K_PER_M).coerceAtLeast(0.0)
        val above = layerDepthM - levelHeight
        if (above <= 0.0) return
        val levelPressure = groundPressureKpa * (levelKelvin / surfaceKelvin).pow(DRY_POWER)
        val levelHumidity = saturationSpecificHumidity(levelC, levelPressure)
        // The saturated part's climb to the layer's top, one midpoint step up the moist adiabat: what
        // it condenses is its water at the condensation level less what it holds at the top, in the
        // air's mean density over the climb.
        val levelLapse = moistAdiabaticLapseKPerM(levelC, levelPressure)
        val midKelvin = levelKelvin - 0.5 * levelLapse * above
        val midPressure = levelPressure * exp(-STANDARD_GRAVITY_MPS2 * 0.5 * above / (DRY_AIR_GAS_CONSTANT_J_PER_KG_K * levelKelvin))
        val topKelvin = levelKelvin - moistAdiabaticLapseKPerM(midKelvin - KELVIN_AT_ZERO_C, midPressure) * above
        val topPressure = levelPressure * exp(-STANDARD_GRAVITY_MPS2 * above / (DRY_AIR_GAS_CONSTANT_J_PER_KG_K * midKelvin))
        val meanDensity = midPressure * PASCALS_PER_KPA / (DRY_AIR_GAS_CONSTANT_J_PER_KG_K * midKelvin)
        into[0] = (meanDensity * (levelHumidity - saturationSpecificHumidity(topKelvin - KELVIN_AT_ZERO_C, topPressure))).coerceAtLeast(0.0)
        // Its slope in the layer's water: a wetter layer saturates lower, and the air between the
        // two levels condenses at the saturated yield per meter at the condensation level; the level's
        // temperature rises with the vapor's pressure, which rises with the humidity.
        val levelDensity = levelPressure * PASCALS_PER_KPA / (DRY_AIR_GAS_CONSTANT_J_PER_KG_K * levelKelvin)
        val perMeter = levelDensity * levelHumidity * (
            LATENT_HEAT_J_PER_KG * levelLapse /
                (WATER_VAPOR_GAS_CONSTANT_J_PER_KG_K * levelKelvin * levelKelvin) -
                STANDARD_GRAVITY_MPS2 / (DRY_AIR_GAS_CONSTANT_J_PER_KG_K * levelKelvin)
            )
        val logVaporPerHumidity = 1.0 / humidity - (1.0 - MOLAR_MASS_RATIO) / (MOLAR_MASS_RATIO + (1.0 - MOLAR_MASS_RATIO) * humidity)
        val levelWarmingPerHumidity = logVaporPerHumidity / slope
        into[1] = perMeter * levelWarmingPerHumidity / DRY_LAPSE_K_PER_M / BOUNDARY_LAYER_MASS_KG_PER_M2
    }

    /**
     * Newton's steps for the condensation level's temperature from the dew point: the excess is
     * nearly linear over the few kelvin between them, and four steps leave it under a microkelvin.
     */
    private const val CONDENSATION_LEVEL_NEWTON_STEPS = 4

    /**
     * What the boundary layer's air condenses per meter it is lifted when it stands at its surface
     * air's saturation, in kilograms per square meter per meter ([boundaryLayerLiftYield] at
     * [saturationSpecificHumidity], its first term): the most the layer yields, and the rate the
     * lee's descent can evaporate cloud at. It depends on the ground's temperature and pressure
     * alone, the layer's depth being theirs ([boundaryLayerDepthM]), so it is read from a table
     * of the direct calculation, at [LIFT_TABLE_STEP_C] in the temperature and [LIFT_TABLE_PRESSURES]
     * steps in the logarithm of the pressure, by bicubic (Catmull-Rom) interpolation: within
     * [LIFT_TABLE_BOUND] of it, relative, over the air a world makes, and within 2e-3 of it in
     * the span's corners no world reaches, 31 C at 22 kPa (`BoundaryLayerWaterTest`). Ground outside
     * the table's span, and in its outermost step, is taken from the direct calculation.
     */
    fun saturatedLiftYield(surfaceTemperatureC: Double, groundPressureKpa: Double): Double {
        val across = (surfaceTemperatureC - LIFT_TABLE_COLDEST_C) / LIFT_TABLE_STEP_C
        val down = (kotlin.math.ln(groundPressureKpa) - LIFT_TABLE_LOWEST_LOG_PRESSURE) / LIFT_TABLE_LOG_PRESSURE_STEP
        // The cubic reads a step before the cell and a step after it, so the outermost cells go to the
        // direct calculation.
        if (!(across >= 1.0 && across < LIFT_TABLE_TEMPERATURES - 2 && down >= 1.0 && down < LIFT_TABLE_PRESSURES - 2)) {
            return directSaturatedLiftYield(surfaceTemperatureC, groundPressureKpa)
        }
        val row = across.toInt()
        val column = down.toInt()
        val acrossShare = across - row
        val downShare = down - column
        val table = saturatedLiftYieldTable
        var first = 0.0
        var second = 0.0
        var third = 0.0
        var fourth = 0.0
        for (offset in 0 until 4) {
            val start = (row - 1 + offset) * LIFT_TABLE_PRESSURES + column - 1
            val along = catmullRom(table[start], table[start + 1], table[start + 2], table[start + 3], downShare)
            when (offset) {
                0 -> first = along
                1 -> second = along
                2 -> third = along
                else -> fourth = along
            }
        }
        return catmullRom(first, second, third, fourth, acrossShare).coerceAtLeast(0.0)
    }

    /** The Catmull-Rom cubic through four equally spaced values, read between the middle two at [share]. */
    private fun catmullRom(before: Double, first: Double, second: Double, after: Double, share: Double): Double =
        first + 0.5 * share * (second - before + share * (2.0 * before - 5.0 * first + 4.0 * second - after +
            share * (3.0 * (first - second) + after - before)))

    private fun directSaturatedLiftYield(surfaceTemperatureC: Double, groundPressureKpa: Double): Double {
        val into = DoubleArray(2)
        boundaryLayerLiftYield(
            surfaceTemperatureC, groundPressureKpa, boundaryLayerDepthM(surfaceTemperatureC, groundPressureKpa),
            saturationSpecificHumidity(surfaceTemperatureC, groundPressureKpa), into
        )
        return into[0]
    }

    /**
     * The table's span and steps: from colder than any air the energy balance makes to warmer than
     * any it makes, an eighth of a degree apart, and in pressure from over the highest ground the
     * plates raise to over the standard sea level's, 128 steps of the logarithm. Fine enough that
     * the interpolation is within [LIFT_TABLE_BOUND] of the direct calculation, which grows 7% a
     * degree and bends little over an eighth of one.
     */
    private const val LIFT_TABLE_COLDEST_C = -80.0
    private const val LIFT_TABLE_WARMEST_C = 60.0
    private const val LIFT_TABLE_STEP_C = 0.125
    private val LIFT_TABLE_TEMPERATURES = ((LIFT_TABLE_WARMEST_C - LIFT_TABLE_COLDEST_C) / LIFT_TABLE_STEP_C).toInt() + 1
    private const val LIFT_TABLE_LOWEST_PRESSURE_KPA = 20.0
    private const val LIFT_TABLE_HIGHEST_PRESSURE_KPA = 105.0
    private const val LIFT_TABLE_PRESSURES = 128
    private val LIFT_TABLE_LOWEST_LOG_PRESSURE = kotlin.math.ln(LIFT_TABLE_LOWEST_PRESSURE_KPA)
    private val LIFT_TABLE_LOG_PRESSURE_STEP =
        (kotlin.math.ln(LIFT_TABLE_HIGHEST_PRESSURE_KPA) - LIFT_TABLE_LOWEST_LOG_PRESSURE) / (LIFT_TABLE_PRESSURES - 1)

    /** How far the table may stand from the direct calculation, relative: a millionth. */
    const val LIFT_TABLE_BOUND = 1.0e-6

    /** The table, a row of pressures for each temperature from the coldest up, built when the object is first read. */
    private val saturatedLiftYieldTable: DoubleArray = DoubleArray(LIFT_TABLE_TEMPERATURES * LIFT_TABLE_PRESSURES) { index ->
        directSaturatedLiftYield(
            LIFT_TABLE_COLDEST_C + (index / LIFT_TABLE_PRESSURES) * LIFT_TABLE_STEP_C,
            kotlin.math.exp(LIFT_TABLE_LOWEST_LOG_PRESSURE + (index % LIFT_TABLE_PRESSURES) * LIFT_TABLE_LOG_PRESSURE_STEP)
        )
    }


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

    /** The boundary layer's air, kilograms per square meter: its depth in pressure over gravity, 1,292. */
    const val BOUNDARY_LAYER_MASS_KG_PER_M2 = BoundaryLayer.LAYER_THICKNESS_PA / STANDARD_GRAVITY_MPS2

    /** `c_p / R_d`, the dry adiabat's power between pressure and temperature. */
    private const val DRY_POWER = DRY_AIR_HEAT_CAPACITY_J_PER_KG_K / DRY_AIR_GAS_CONSTANT_J_PER_KG_K

    /**
     * The constants [boundaryLayerLiftYield] is written with, by the names a device's kernels read
     * them by ([MoistureDeviceRun.constants]).
     */
    internal fun deviceConstants(): Map<String, Double> = mapOf(
        "KELVIN_AT_ZERO_C" to KELVIN_AT_ZERO_C,
        "TETENS_KPA" to TETENS_KPA,
        "TETENS_SLOPE" to TETENS_SLOPE,
        "TETENS_OFFSET_C" to TETENS_OFFSET_C,
        "MOLAR_MASS_RATIO" to MOLAR_MASS_RATIO,
        "LATENT_HEAT_J_PER_KG" to LATENT_HEAT_J_PER_KG,
        "WATER_VAPOR_GAS_CONSTANT_J_PER_KG_K" to WATER_VAPOR_GAS_CONSTANT_J_PER_KG_K,
        "DRY_AIR_GAS_CONSTANT_J_PER_KG_K" to DRY_AIR_GAS_CONSTANT_J_PER_KG_K,
        "DRY_AIR_HEAT_CAPACITY_J_PER_KG_K" to DRY_AIR_HEAT_CAPACITY_J_PER_KG_K,
        "STANDARD_GRAVITY_MPS2" to STANDARD_GRAVITY_MPS2,
        "DRY_LAPSE_K_PER_M" to DRY_LAPSE_K_PER_M,
        "DRY_POWER" to DRY_POWER,
        "PASCALS_PER_KPA" to PASCALS_PER_KPA,
        "CONDENSATION_LEVEL_NEWTON_STEPS" to CONDENSATION_LEVEL_NEWTON_STEPS.toDouble()
    )
}
