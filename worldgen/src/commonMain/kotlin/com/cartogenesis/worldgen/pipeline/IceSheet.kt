package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.math.JumpFloodDistance
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldScale
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * An ice sheet as a *body*: how thick it is, how high its surface stands, and which way that
 * surface falls.
 *
 * ### The profile
 *
 * A sheet is the snow that falls on it flowing out to its margin. In the steady state the ice
 * flux through a line `x` from the divide is the snow that fell between the divide and that line,
 * `a x`, and the flux the ice carries under its own weight is Glen's flow law in the shallow-ice
 * approximation, `2 A (rho g)^n H^(n+2) |dH/dx|^n / (n + 2)` (Cuffey and Paterson, *The Physics of
 * Glaciers*, 4th edn, 2010, chapter 8). Setting one against the other over a flat bed with no
 * sliding gives Vialov's (1958) profile,
 *
 * ```
 * H(x)^((2n+2)/n) = H0^((2n+2)/n) (1 - (x / L)^((n+1)/n))
 * H0 = 2^(n/(2n+2)) ((n+2) a / (2 A (rho g)^n))^(1/(2n+2)) L^(1/2)
 * ```
 *
 * with `L` the distance from the divide to the margin, `n` Glen's exponent, 3. The dome's height
 * goes as the square root of the sheet's size and only as the eighth root of the snow and the
 * ice's softness, which is why sheets on Earth stand within a factor of two of each other.
 *
 * Each frozen body takes its own: `L` is the farthest any of its ice stands from its margin, `a`
 * is the mean of its snow balance, and `A` is the rate factor of its ice, which depends on how
 * warm the ice is where it deforms ([effectiveRateFactorPerPa3s]). So the profile is a function of
 * the distance from the margin alone within one body, and the surface is the lower envelope of
 * the profiles rising from every point of the margin, as before (see [marginDistanceKm]).
 *
 * It is asked of sheets only. A body of ice under [SMALLEST_SHEET_SQUARE_KM] is an ice cap by
 * glaciology's own definition and gets no profile at all.
 *
 * ### The surface, and why it is the terrain
 *
 * A sheet's surface is its own ground: Greenland's summit is cold because it is three kilometres
 * up. So the surface is written into the elevation field the pipeline reads, the thickness being
 * the difference between it and the bed, and the climate's lapse rate makes the dome colder than
 * its bed by [surfaceCoolingC].
 *
 * ### The flow
 *
 * Ice flows down the slope of *its own surface*, which falls away from the divide in every
 * direction; [flowReceivers] is the steepest descent of it.
 *
 * ### Where it runs
 *
 * The profile and the surface flow are per-cell arithmetic over grid fields, so both go through
 * [IceSheetAccelerator] with this object's own code as the reference and `GpuIceSheetTest` on the
 * desktop measuring the two against each other on [IceSheetParity]'s fixture. The profile's shape
 * is one table ([VIALOV_SHAPE]) both read, so the device does the same arithmetic in the same
 * order.
 */
object IceSheet {

    /**
     * The thickest ice measured on Earth, in metres: Bedmap2's deepest sounding in the Astrolabe
     * Subglacial Basin (Fretwell et al. 2013). The envelope guard's ceiling.
     */
    const val THICKEST_ICE_ON_EARTH_METRES = 4_776f

    /** Glen's flow-law exponent, three (Cuffey and Paterson 2010, section 3.4). */
    const val GLEN_EXPONENT = 3

    /**
     * Cuffey and Paterson's (2010, Eq. 3.35) rate factor: `A* exp(-(Q/R)(1/T_h - 1/T*))` with
     * `A*` 3.5e-25 per second per pascal cubed at `T*` minus ten degrees, and an activation energy
     * of 60 kJ/mol below it and 115 kJ/mol above it, `T_h` the temperature reckoned from the
     * pressure melting point. Their recommended values for ice with no enhancement.
     */
    private const val RATE_FACTOR_AT_REFERENCE_PER_PA3S = 3.5e-25
    private const val REFERENCE_KELVIN = 263.15
    private const val COLD_ACTIVATION_J_PER_MOL = 6.0e4
    private const val WARM_ACTIVATION_J_PER_MOL = 1.15e5
    private const val GAS_CONSTANT_J_PER_MOL_K = 8.314

    /**
     * How fast the melting point falls with pressure, kelvin per pascal: 7.42e-8 for pure ice
     * (Cuffey and Paterson 2010, section 9.2).
     */
    private const val MELTING_POINT_PER_PA = 7.42e-8

    /**
     * The heat rising into a sheet's bed, watts per square meter: Pollack, Hurter and Johnson's
     * (1993) mean over the continents, 65 mW/m².
     */
    const val GEOTHERMAL_FLUX_W_PER_M2 = 0.065

    /**
     * Ice's thermal conductivity, `9.828 exp(-0.0057 T)` W/(m K), and its heat capacity,
     * `152.5 + 7.122 T` J/(kg K), `T` in kelvin (Cuffey and Paterson 2010, section 9.2).
     */
    private fun conductivityWPerMK(kelvin: Double) = 9.828 * exp(-0.0057 * kelvin)
    private fun heatCapacityJPerKgK(kelvin: Double) = 152.5 + 7.122 * kelvin

    /**
     * The snow a sheet with no snow balance of its own is given, millimeters of water a year:
     * Antarctica's grounded ice's mean, 143 kg m⁻² a⁻¹ (Arthern, Winebrenner and Vaughan 2006).
     * Only the control that freezes on temperature alone reads it.
     */
    const val EARTH_SHEET_ACCUMULATION_MM = 143f

    /** Water's density over ice's turns a millimeter of water into ice. */
    private const val WATER_DENSITY_KG_PER_M3 = 1_000.0

    /** Seconds in a year. */
    private const val SECONDS_PER_YEAR = 3.15576e7

    /**
     * The rate factor of a column's ice, per second per pascal cubed, weighted as Glen's law
     * weights it: the flux of a column whose rate factor varies with height is
     * `2 (rho g |dH/dx|)^n H^(n+2) Int A(z) (1 - z/H)^(n+1) dz/H`, so the one rate factor that
     * carries the same flux is `(n + 2)` times that integral, and the deforming ice near the bed
     * counts for nearly all of it.
     *
     * The temperature through the column is Robin's (1955) steady solution for snow falling at
     * [accumulationMPerS] of ice onto a surface at [surfaceC] over the continents' geothermal flux,
     * `T(z) = T_s + (G l sqrt(pi) / 2k) (erf(H/l) - erf(z/l))` with `l = sqrt(2 kappa H / a)`
     * (Cuffey and Paterson 2010, section 9.3), held at the pressure melting point where it would
     * pass it, which under a thick sheet it does: the bed is temperate and the ice above it soft.
     */
    fun effectiveRateFactorPerPa3s(
        thicknessM: Double,
        surfaceC: Double,
        accumulationMPerS: Double,
        iceDensityKgPerM3: Double,
        gravityMPerS2: Double
    ): Double {
        val surfaceKelvin = surfaceC + ColumnWater.KELVIN_AT_ZERO_C
        val conductivity = conductivityWPerMK(surfaceKelvin)
        val diffusivity = conductivity / (iceDensityKgPerM3 * heatCapacityJPerKgK(surfaceKelvin))
        val length = sqrt(2.0 * diffusivity * thicknessM / accumulationMPerS.coerceAtLeast(1.0e-12))
        val warming = GEOTHERMAL_FLUX_W_PER_M2 * length * sqrt(kotlin.math.PI) / (2.0 * conductivity)
        val topErf = erf(thicknessM / length)
        var weighted = 0.0
        for (step in 0 until COLUMN_STEPS) {
            val fraction = (step + 0.5) / COLUMN_STEPS
            val heightM = fraction * thicknessM
            val pressurePa = iceDensityKgPerM3 * gravityMPerS2 * (thicknessM - heightM)
            val meltingC = -MELTING_POINT_PER_PA * pressurePa
            val temperatureC = minOf(surfaceC + warming * (topErf - erf(heightM / length)), meltingC)
            // Homologous: reckoned from the melting point at that depth.
            val homologousKelvin = temperatureC - meltingC + ColumnWater.KELVIN_AT_ZERO_C
            val activation = if (homologousKelvin < REFERENCE_KELVIN) COLD_ACTIVATION_J_PER_MOL else WARM_ACTIVATION_J_PER_MOL
            val rate = RATE_FACTOR_AT_REFERENCE_PER_PA3S *
                exp(-activation / GAS_CONSTANT_J_PER_MOL_K * (1.0 / homologousKelvin - 1.0 / REFERENCE_KELVIN))
            weighted += rate * (1.0 - fraction).pow(GLEN_EXPONENT + 1)
        }
        return (GLEN_EXPONENT + 2) * weighted / COLUMN_STEPS
    }

    /** Steps through a column's height for its rate factor: a fortieth of the ice each. */
    private const val COLUMN_STEPS = 40

    /**
     * The error function, Abramowitz and Stegun's 7.1.26, good to 1.5e-7: the temperature profile
     * needs it to a hundredth of a degree.
     */
    private fun erf(value: Double): Double {
        val sign = if (value < 0.0) -1.0 else 1.0
        val x = kotlin.math.abs(value)
        val t = 1.0 / (1.0 + 0.3275911 * x)
        val polynomial = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))))
        return sign * (1.0 - polynomial * exp(-x * x))
    }

    /**
     * The height of a body's dome over its margin, in metres: Vialov's `H0` for a sheet whose
     * divide stands [divideKm] from its margin, under [accumulationMmWater] of snow a year, with a
     * surface at [surfaceC] before the dome lifts it. The rate factor depends on how thick the ice
     * is and the thickness on the rate factor, and the surface cools by [lapseRateCPerKm] as the
     * dome rises, so the two are iterated together from the dome of ice at the reference
     * temperature; each pass changes the height by the eighth root of the rate factor's change,
     * and [DOME_PASSES] is where the last pass moves it by under a meter.
     */
    fun domeMetres(
        divideKm: Float,
        accumulationMmWater: Float,
        surfaceC: Float,
        lapseRateCPerKm: Float,
        iceDensityKgPerM3: Float,
        gravityMPerS2: Float
    ): Float {
        if (divideKm <= 0f) return 0f
        val accumulation = accumulationMmWater.coerceAtLeast(SnowBalance.SMALLEST_MEANINGFUL_BALANCE_MM) /
            WorldScale.METRES_PER_KM.toDouble() * WATER_DENSITY_KG_PER_M3 / iceDensityKgPerM3 / SECONDS_PER_YEAR
        val weight = iceDensityKgPerM3.toDouble() * gravityMPerS2
        val n = GLEN_EXPONENT.toDouble()
        val lengthM = divideKm * WorldScale.METRES_PER_KM.toDouble()
        var rateFactor = RATE_FACTOR_AT_REFERENCE_PER_PA3S
        var height = 0.0
        for (pass in 0 until DOME_PASSES) {
            val flux = (n + 2.0) * accumulation / (2.0 * rateFactor * weight.pow(n))
            height = 2.0.pow(n / (2.0 * n + 2.0)) * flux.pow(1.0 / (2.0 * n + 2.0)) * sqrt(lengthM)
            val domeSurfaceC = (surfaceC - height / WorldScale.METRES_PER_KM * lapseRateCPerKm).coerceAtLeast(COLDEST_SURFACE_C)
            rateFactor = effectiveRateFactorPerPa3s(height, domeSurfaceC, accumulation, iceDensityKgPerM3.toDouble(), gravityMPerS2.toDouble())
        }
        return height.toFloat()
    }

    /**
     * The coldest a dome's surface is taken to be, degrees: the coldest air measured at Earth's
     * surface, -89.2 C at Vostok in 1983. A dome the lapse rate would carry colder than that is
     * taller than any on Earth, and its ice is stiff whichever way the last degrees go.
     */
    private const val COLDEST_SURFACE_C = -89.2

    /** Passes of [domeMetres]'s iteration. */
    private const val DOME_PASSES = 6

    /**
     * Vialov's profile as a share of the dome's height, at a distance from the margin over the
     * divide's, tabulated from the margin to the divide at [SHAPE_STEPS] points:
     * `(1 - (1 - s)^(4/3))^(3/8)` with `n` 3. Past the divide, where the margin a cell's surface is
     * measured from stands farther off than the body's own half-width, it rises on as the square
     * root, which meets the profile at the divide.
     */
    val VIALOV_SHAPE: FloatArray by lazy {
        val n = GLEN_EXPONENT.toDouble()
        FloatArray(SHAPE_STEPS + 1) { step ->
            val fromMargin = step.toDouble() / SHAPE_STEPS
            (1.0 - (1.0 - fromMargin).pow((n + 1.0) / n)).coerceAtLeast(0.0).pow(n / (2.0 * n + 2.0)).toFloat()
        }
    }

    /** The shape table's intervals: a 4,096th of the half-width, a kilometer or less on any sheet. */
    const val SHAPE_STEPS = 4096

    /**
     * Points the profile is averaged over across one cell's span of distances, and so the cell's
     * mean rather than its middle: eight, which near the margin, where the profile rises as the
     * three-eighths power, holds the mean within a percent.
     */
    const val SPAN_SAMPLES = 8

    /** The profile's share of the dome at [fromMargin] of the divide's distance: the table, read linearly. */
    fun shapeAt(fromMargin: Float): Float {
        if (fromMargin <= 0f) return 0f
        if (fromMargin >= 1f) return sqrt(fromMargin)
        val position = fromMargin * SHAPE_STEPS
        val below = position.toInt().coerceAtMost(SHAPE_STEPS - 1)
        val share = position - below
        return VIALOV_SHAPE[below] + (VIALOV_SHAPE[below + 1] - VIALOV_SHAPE[below]) * share
    }

    /**
     * Which margin each frozen cell's dome rises from, how far off it is, and how far off the
     * *nearest* margin is, all in kilometres.
     *
     * [distanceKm] is the profile's argument: how far the cell stands from the margin whose
     * profile reaches it lowest, which is the one the surface is measured from. [nearestMarginKm]
     * is the geometric one, how far the ice edge is in a straight line, which is what a reader
     * means by a sheet's half-width.
     */
    class Margin(
        val distanceKm: FloatArray,
        val nearestCell: IntArray,
        val nearestMarginKm: FloatArray
    )

    /**
     * The margin itself: ice-free cells with ice against them, the only sources a sheet's surface
     * rises from.
     */
    fun marginCells(config: WorldGenConfig, frozen: BooleanArray): BooleanArray {
        val cellsAcross = config.width
        val cellsDown = config.height
        return BooleanArray(cellsAcross * cellsDown) { cell ->
            if (frozen[cell]) return@BooleanArray false
            val column = cell % cellsAcross
            val row = cell / cellsAcross
            var touchesIce = false
            for (rowStep in -1..1) {
                val neighbourRow = row + rowStep
                if (neighbourRow < 0 || neighbourRow >= cellsDown) continue
                for (columnStep in -1..1) {
                    if (rowStep == 0 && columnStep == 0) continue
                    var neighbourColumn = (column + columnStep) % cellsAcross
                    if (neighbourColumn < 0) neighbourColumn += cellsAcross
                    if (frozen[neighbourRow * cellsAcross + neighbourColumn]) touchesIce = true
                }
            }
            touchesIce
        }
    }

    /**
     * How far each frozen cell stands from the nearest margin in a straight line, kilometres, and
     * zero off the ice: the plain jump flood over the margin's cells, on the ground.
     */
    fun nearestMarginKm(config: WorldGenConfig, frozen: BooleanArray, margin: BooleanArray): FloatArray {
        val cellCount = config.width * config.height
        val distance = FloatArray(cellCount) { if (margin[it]) 0f else JumpFloodDistance.INFINITE }
        val nearest = IntArray(cellCount) { if (margin[it]) it else -1 }
        JumpFloodDistance.run(config.width, config.height, distance, nearest, config.cellHeightInCellWidths)
        val kilometresPerCellWidth = config.cellWidthKm.toFloat()
        for (cell in 0 until cellCount) {
            distance[cell] =
                if (!frozen[cell] || distance[cell] >= JumpFloodDistance.INFINITE) 0f
                else distance[cell] * kilometresPerCellWidth
        }
        return distance
    }

    /**
     * Which margin cell each frozen cell's surface is measured from, and how far away it is in
     * kilometres: the margin whose profile reaches the cell *lowest*, which is not always the
     * nearest one.
     *
     * The surface over a margin of varying height is the lower envelope of the profiles rising
     * from every margin point, `S(x) = min over m of (z_m + H(|x - m|; m))`, each margin point
     * carrying the dome and the divide distance of the body it bounds ([domeMetresOfMargin],
     * [divideKmOfMargin]). Continuous wherever the branches are; where two meet the surface has a
     * crease, which between two margins is an ice divide. The flood is run over that cost. Its
     * sources are the margin itself ([marginCells]), not every ice-free cell: an envelope rises a
     * profile from anything it is offered, and the open ocean far from the ice is not where a
     * sheet's surface starts. A weighted flood is not exact the way the plain one is (see
     * [JumpFloodDistance.run]), which is why `IceSheetTest` measures the finished surface for steps.
     */
    fun marginDistanceKm(
        config: WorldGenConfig,
        frozen: BooleanArray,
        margin: BooleanArray,
        /** The shoreline-relative ground the margins stand on. */
        bedRelative: FloatArray,
        /** What one unit of [bedRelative] is worth in metres. */
        metresPerFieldUnit: Float,
        domeMetresOfMargin: FloatArray,
        divideKmOfMargin: FloatArray,
        cellSpanKm: Float,
        nearestMarginKm: FloatArray
    ): Margin {
        val cellCount = config.width * config.height
        val distance = FloatArray(cellCount) { if (margin[it]) 0f else JumpFloodDistance.INFINITE }
        val nearest = IntArray(cellCount) { if (margin[it]) it else -1 }
        val kilometresPerCellWidth = config.cellWidthKm.toFloat()
        JumpFloodDistance.run(
            config.width, config.height, distance, nearest, config.cellHeightInCellWidths
        ) { source, squaredCellWidths ->
            // The surface this margin would put over the cell, in metres: the ground it stands on,
            // floored at the waterline as [surfaceMetres] floors it, plus its body's profile over
            // the distance. Compared as a height and not as a distance.
            val km = sqrt(squaredCellWidths).toFloat() * kilometresPerCellWidth
            val datum = (bedRelative[source] * metresPerFieldUnit).coerceAtLeast(0f)
            (datum + profileMetres(km, domeMetresOfMargin[source], divideKmOfMargin[source], cellSpanKm)).toDouble()
        }
        for (cell in 0 until cellCount) {
            // Ground with no ice on it has no profile; a world entirely under ice has no margin
            // and is left bare rather than infinitely thick.
            distance[cell] =
                if (!frozen[cell] || distance[cell] >= JumpFloodDistance.INFINITE) 0f
                else distance[cell] * kilometresPerCellWidth
            if (!frozen[cell]) nearest[cell] = cell
        }
        return Margin(distance, nearest, nearestMarginKm)
    }

    /**
     * The smallest body of ice that is an ice *sheet*, in square kilometres: 50,000, glaciology's
     * own line between a sheet and an ice cap (Cuffey and Paterson, 4th edn, §1.2; Benn and Evans,
     * *Glaciers and Glaciation*, 2nd edn, §1.5). A body under it keeps no thickness and nothing
     * is written into the elevation field for it.
     */
    const val SMALLEST_SHEET_SQUARE_KM = 50_000.0

    /**
     * The profile over one cell, in metres above the margin it is measured from: the *mean* of
     * the body's profile across the ground the cell covers, `marginDistanceKm - cellSpanKm` to
     * `marginDistanceKm`, not its value at the cell's middle, so the ice tapers to its margin
     * rather than ending in a step. [cellSpanKm] is the side of the square with a cell's own
     * area, the span that is right on average whatever bearing the margin lies along. The mean
     * is taken at [SPAN_SAMPLES] midpoints, the same points in the same order on every device.
     */
    fun profileMetres(
        marginDistanceKm: Float,
        domeMetres: Float,
        divideKm: Float,
        cellSpanKm: Float
    ): Float {
        if (marginDistanceKm <= 0f || domeMetres <= 0f || divideKm <= 0f) return 0f
        val near = (marginDistanceKm - cellSpanKm).coerceAtLeast(0f)
        val span = marginDistanceKm - near
        var sum = 0f
        for (sample in 0 until SPAN_SAMPLES) {
            val distance = near + span * ((sample + 0.5f) / SPAN_SAMPLES)
            sum += shapeAt(distance / divideKm)
        }
        return domeMetres * (sum / SPAN_SAMPLES)
    }

    /**
     * How high the sheet's *surface* stands at one cell, in metres above the shoreline: the
     * margin's own ground, floored at the waterline (a marine margin is where the ice meets the
     * sea), plus the profile. The profile is a surface and not a drape: the bed it sits on does not
     * show through it.
     */
    fun surfaceMetres(
        marginBedMetres: Float,
        marginDistanceKm: Float,
        domeMetres: Float,
        divideKm: Float,
        cellSpanKm: Float
    ): Float =
        marginBedMetres.coerceAtLeast(0f) + profileMetres(marginDistanceKm, domeMetres, divideKm, cellSpanKm)

    /**
     * How much colder the top of [thicknessMetres] of ice is than its bed, in degrees.
     *
     * The climate's own lapse rate, read off the configuration rather than restated: the dome is
     * cold because it is high, by the same arithmetic that makes a mountain cold, and there is no
     * second rule for ice. `ClimateStage` applies this without knowing it exists, because what it
     * is handed is the surface's altitude.
     */
    fun surfaceCoolingC(thicknessMetres: Float, lapseRateCPerKm: Float): Float =
        thicknessMetres / WorldScale.METRES_PER_KM * lapseRateCPerKm

    /**
     * The thickness over a whole grid, in metres: the dome's surface less the bed under it, and
     * never less than nothing; where the bed stands above the dome the answer is zero, a nunatak.
     * Each cell reads the dome and divide distance of the margin its surface rises from.
     *
     * The processor's reference the accelerator is measured against.
     */
    fun profile(
        margin: Margin,
        bedRelative: FloatArray,
        onTheSheet: BooleanArray,
        domeMetresOfMargin: FloatArray,
        divideKmOfMargin: FloatArray,
        metresPerFieldUnit: Float,
        cellSpanKm: Float
    ): FloatArray = FloatArray(bedRelative.size) { cell ->
        if (!onTheSheet[cell]) 0f else {
            val nearest = margin.nearestCell[cell]
            if (nearest < 0) 0f else {
                val surface = surfaceMetres(
                    bedRelative[nearest] * metresPerFieldUnit, margin.distanceKm[cell],
                    domeMetresOfMargin[nearest], divideKmOfMargin[nearest], cellSpanKm
                )
                (surface - bedRelative[cell] * metresPerFieldUnit).coerceAtLeast(0f)
            }
        }
    }

    /**
     * Which neighbour the ice at each cell flows to: the steepest descent of the ice *surface*,
     * and -1 where there is no sheet or no lower neighbour.
     *
     * The surface is `bed + thickness` in the elevation field's own units, so [metresPerFieldUnit]
     * is what turns the profile's metres into them. Steepest descent is measured per unit of
     * ground distance rather than per cell, for the reason the distance field is: a step down the
     * map is half a step across it on this grid, and a flow that did not know it would drift
     * north-south.
     *
     * The bearing this returns is what makes a sheet's scour radial. It is *not* the bed's D8
     * network: the whole point of the sheet regime is that the bed's network is under a kilometre
     * of ice and steers nothing.
     */
    fun flowReceivers(
        cellsAcross: Int,
        cellsDown: Int,
        bedRelative: FloatArray,
        thicknessMetres: FloatArray,
        onTheSheet: BooleanArray,
        metresPerFieldUnit: Float,
        cellHeightInCellWidths: Float
    ): IntArray {
        val cellCount = cellsAcross * cellsDown
        val surface = FloatArray(cellCount) { cell ->
            bedRelative[cell] + thicknessMetres[cell] / metresPerFieldUnit
        }
        val receiver = IntArray(cellCount) { -1 }
        for (cell in 0 until cellCount) {
            if (!onTheSheet[cell]) continue
            receiver[cell] = steepestDescent(
                cellsAcross, cellsDown, cell, surface, cellHeightInCellWidths
            )
        }
        return receiver
    }

    /**
     * The neighbour of [cell] that [surface] falls to fastest per unit of ground walked, or -1 if
     * none of the eight is lower.
     *
     * Shared by the CPU reference and by every accelerator's shader, which is why it is one
     * function: two copies of a tie-breaking rule drift, and a tie here decides a bearing.
     */
    fun steepestDescent(
        cellsAcross: Int,
        cellsDown: Int,
        cell: Int,
        surface: FloatArray,
        cellHeightInCellWidths: Float
    ): Int {
        val column = cell % cellsAcross
        val row = cell / cellsAcross
        val here = surface[cell]
        var best = -1
        var bestGradient = 0f
        for (rowStep in -1..1) {
            val neighbourRow = row + rowStep
            if (neighbourRow < 0 || neighbourRow >= cellsDown) continue
            for (columnStep in -1..1) {
                if (rowStep == 0 && columnStep == 0) continue
                var neighbourColumn = (column + columnStep) % cellsAcross
                if (neighbourColumn < 0) neighbourColumn += cellsAcross
                val neighbour = neighbourRow * cellsAcross + neighbourColumn
                val fall = here - surface[neighbour]
                if (fall <= 0f) continue
                val acrossCells = columnStep.toFloat()
                val downCells = rowStep * cellHeightInCellWidths
                val walked = sqrt(acrossCells * acrossCells + downCells * downCells)
                val gradient = fall / walked
                // Ties to the lower cell index, as the jump flood's do, so the answer does not
                // depend on the order the eight were looked at.
                if (gradient > bestGradient || (gradient == bestGradient && neighbour < best)) {
                    bestGradient = gradient
                    best = neighbour
                }
            }
        }
        return best
    }
}
