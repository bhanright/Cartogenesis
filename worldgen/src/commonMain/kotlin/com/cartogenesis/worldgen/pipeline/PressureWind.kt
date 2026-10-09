package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldScale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.tan

/**
 * Surface pressure, and the wind that blows down its gradient.
 *
 * The circulation belts in [ClimateStage] are the zonal mean of the surface wind: what is left of
 * the wind after every longitude at a latitude has been averaged together. Averaging away the
 * longitude is exactly what throws out the monsoon, because the monsoon is the departure. A
 * heated continent in summer is a *thermal low* and the sea beside it is relatively high, so the
 * surface air blows in off the water onto the land; in winter the continent is the high and the
 * air blows out over the sea and arrives on the far coast dry. That is the Asian monsoon and the
 * Siberian outflow, and neither is a function of latitude alone.
 *
 * This object computes that departure in three steps, all of them per cell:
 *
 * 1. **A pressure anomaly from a temperature anomaly.** The departure of a cell's seasonal surface
 *    temperature from the mean of its own row, converted to hectopascals by the hydrostatic
 *    relation for a heated column ([HPA_PER_KELVIN]), then smoothed to the scale the atmosphere
 *    actually organises pressure on ([rossbyRadiusKm]).
 * 2. **A wind from the pressure gradient**, balancing the gradient against the Coriolis force and
 *    a linear surface drag ([surfaceWind]). Far from the equator that is the geostrophic wind
 *    turned across the isobars by the Ekman angle; at the equator, where the Coriolis parameter
 *    vanishes, the same expression becomes a straight down-gradient flow with no division by zero
 *    anywhere in between.
 * 3. **Addition to the belts**, which [ClimateStage.buildWind] does. The belts stay the zonal mean
 *    and this is the regional departure, so a world generated with [WorldGenConfig.climate]'s
 *    `pressureWinds` switched off has exactly the wind it had before this existed — which is what
 *    gives the guards on it a control.
 */
internal object PressureWind {

    /**
     * How far the surface pressure falls, in hectopascals, for each degree Celsius a column of air
     * is warmer than its neighbours.
     *
     * The hydrostatic relation, in the form Holton & Hakim state it (*An Introduction to Dynamic
     * Meteorology*, 5th edition, the hypsometric equation and the thermal low): the thickness of
     * the layer between two pressure surfaces is `Z = (R T / g) ln(p_surface / p_top)`, so warming
     * the layer by one degree expands it by `(R / g) ln(p_surface / p_top)` metres. Hold the
     * pressure at the top of the layer fixed — that is what the *level of non-divergence* means,
     * the height near 500 hPa where the outflow aloft balances the inflow below — and that
     * expansion has to come out of the surface pressure. Combining the two and cancelling the gas
     * constant and gravity leaves
     *
     * `dp_surface/dT = -p_surface * ln(p_surface / p_top) / T`
     *
     * which at [SEA_LEVEL_PRESSURE_HPA], [NON_DIVERGENT_LEVEL_HPA] and [REFERENCE_COLUMN_K] is
     * 2.48 hPa per degree. Nothing here was chosen to make a picture look right; the three numbers
     * it is built from are the standard atmosphere's surface pressure, the standard level of
     * non-divergence, and the standard atmosphere's mean surface temperature.
     *
     * Earth's own figure, for the record rather than as a bar: the Siberian high in January
     * averages about 1035 hPa and the South Asian low in July about 995 hPa, a 40 hPa range, over
     * a land-sea seasonal temperature contrast of about 20 degrees at those latitudes — 2.0 hPa
     * per degree. This derivation runs 1.24 times that, because the whole of the surface anomaly
     * is not felt through the whole depth of the column. The figure is left where the physics puts
     * it rather than scaled down to Earth's ratio, and the guards on this chunk read the *sign* of
     * the coast-normal wind rather than its speed for that reason.
     */
    val HPA_PER_KELVIN: Float = (SEA_LEVEL_PRESSURE_HPA *
        ln(SEA_LEVEL_PRESSURE_HPA / NON_DIVERGENT_LEVEL_HPA) / REFERENCE_COLUMN_K).toFloat()

    /**
     * The angle the surface wind crosses the isobars by, over the sea, in degrees.
     *
     * Friction at the surface takes a bite out of the wind, the Coriolis force that was balancing
     * the pressure gradient weakens with it, and the balance tips toward low pressure. Holton &
     * Hakim give the observed cross-isobar angle as 10-20 degrees over the ocean and 25-45 over
     * land; Ekman's own 1905 boundary-layer solution gives 45 degrees for the flow right at the
     * surface, which the depth-averaged wind never reaches. The two figures here are the middle of
     * the range this chunk was specified against — 20-30 over sea, larger over land — and of
     * Holton & Hakim's land range.
     */
    const val CROSS_ISOBAR_SEA_DEGREES = 25f

    /** See [CROSS_ISOBAR_SEA_DEGREES]: rougher ground, a deeper boundary layer, a bigger turn. */
    const val CROSS_ISOBAR_LAND_DEGREES = 40f

    /**
     * The speed of the belts, in metres a second, so the pressure departure can be added to them.
     *
     * [ClimateStage]'s belts carry a direction and a slant but never had a speed, because a march
     * that steps one cell at a time does not need one. Adding a wind in metres a second to them
     * does. Earth's zonal-mean surface wind is about 7 metres a second at the centre of the trades
     * and about 8 at the centre of the westerlies; one number for both belts at 7.5 is within the
     * spread of either, and the belts' *shape* — where they reverse, how they slant — is unchanged
     * by it, since it multiplies the whole belt field uniformly.
     */
    const val BELT_SPEED_MPS = 7.5f

    /** The standard atmosphere at sea level, in hectopascals. */
    private const val SEA_LEVEL_PRESSURE_HPA = 1013.25

    /**
     * The level of non-divergence, in hectopascals: the height at which the outflow aloft from a
     * thermal low balances the inflow beneath it, so that a warmed column's expansion is paid for
     * out of the surface pressure rather than shared with the pressure above.
     *
     * Its ratio to the surface, `ln(1013.25 / 500)`, is a factor of [HPA_PER_KELVIN].
     */
    private const val NON_DIVERGENT_LEVEL_HPA = 500.0

    /** The standard atmosphere's mean surface temperature, in kelvin: 15 degrees Celsius. */
    private const val REFERENCE_COLUMN_K = 288.15

    /** Density of air at sea level, in kilograms per cubic metre, at the standard atmosphere. */
    internal const val AIR_DENSITY_KG_PER_M3 = 1.225f

    /**
     * The Brunt-Vaisala frequency of the mid-latitude troposphere, in radians a second, and the
     * depth of the troposphere in metres: the two figures the Rossby radius is built from. Both
     * are textbook standards — a stratification of `1.0e-2` and a tropopause at 10 km.
     */
    private const val BUOYANCY_FREQUENCY_PER_S = 1.0e-2
    private const val TROPOPAUSE_DEPTH_M = 10_000.0

    /** Where the Rossby radius is evaluated: the middle of the mid-latitudes. */
    private const val ROSSBY_REFERENCE_LATITUDE_DEGREES = 45.0

    /**
     * Implicit steps the smoothing is split into. Four, as `SphereBlur` takes across rows: one
     * backward step spreads a spike into a two-sided exponential, and four of a quarter the strength
     * are within a few percent of the Gaussian of the same variance.
     */
    private const val SMOOTHING_STEPS = 4

    /** One hectopascal in pascals, for the one place the gradient leaves the map's own unit. */
    private const val PASCALS_PER_HPA = 100f

    private const val DEGREES_TO_RADIANS = PI / 180.0

    /**
     * The scale the atmosphere organises surface pressure on, in kilometres: the first baroclinic
     * Rossby radius of deformation, `N H / f`, at [ROSSBY_REFERENCE_LATITUDE_DEGREES].
     *
     * A pressure field is not a copy of the temperature field. A continent's coastline has
     * kilometre-scale wiggles and a bay a hundred kilometres across is warmer than the cape beside
     * it, but the atmosphere does not carry a separate low over every bay: below the deformation
     * radius a pressure anomaly cannot hold itself up against the flow that drains it, and
     * disperses. Smoothing the anomaly at this radius before taking a gradient from it is what
     * makes the result a synoptic weather map rather than a differentiated coastline.
     *
     * With `N = 1.0e-2` per second, `H = 10 km` and `f` at 45 degrees this is 970 km, which is the
     * thousand kilometres the chunk was specified at, derived rather than assumed. It is a length
     * on the ground, so the same world smooths over the same distance at every grid — see
     * `ScaleFreeTest`.
     *
     * It is the standard deviation of the Gaussian [smooth] spreads the anomaly with, so the
     * smoothed field's width is this radius and not some multiple of it.
     */
    fun rossbyRadiusKm(): Double {
        val coriolisAt45 = 2.0 * WorldScale.ROTATION_RATE_PER_S *
            sin(ROSSBY_REFERENCE_LATITUDE_DEGREES * DEGREES_TO_RADIANS)
        return BUOYANCY_FREQUENCY_PER_S * TROPOPAUSE_DEPTH_M / coriolisAt45 / 1_000.0
    }

    /** The Coriolis parameter at a latitude, in radians a second: `f = 2 omega sin(phi)`. */
    fun coriolisParameter(latitudeDegrees: Float): Float =
        2f * WorldScale.ROTATION_RATE_PER_S * sin(latitudeDegrees * DEGREES_TO_RADIANS).toFloat()

    /**
     * The linear surface drag, in radians a second, that turns the wind across the isobars by
     * [crossIsobarDegrees].
     *
     * The turn comes out of the balance in [surfaceWind] as `atan(drag / f)`, so a drag fixed at
     * `f * tan(angle)` for one reference latitude gives that angle there and rather more of a turn
     * toward the poles, where `f` is larger — which is the wrong way round from the observations,
     * but only mildly so, and the alternative of scaling the drag with latitude would make the
     * drag vanish at the equator along with `f` and take the tropical limit with it. Evaluated at
     * [ROSSBY_REFERENCE_LATITUDE_DEGREES], the two angles give drag timescales `1/k` of 5.8 hours
     * over sea and 3.2 over land, both inside the few hours a boundary layer takes to spin down.
     */
    fun surfaceDrag(crossIsobarDegrees: Float): Float {
        val coriolisAt45 = coriolisParameter(ROSSBY_REFERENCE_LATITUDE_DEGREES.toFloat())
        return coriolisAt45 * tan(crossIsobarDegrees * DEGREES_TO_RADIANS).toFloat()
    }

    /**
     * The surface pressure anomaly of a season, in hectopascals, one value per cell.
     *
     * Zero is not a pressure but the mean of the cell's own row: only the *departure* from the
     * zonal mean can drive a wind that the belts do not already carry, and the belts are the zonal
     * mean by construction. Warm against its row means low, cold against its row means high, which
     * puts the thermal low over the summer continent and the thermal high over the winter one. It
     * puts no ridge over a sea a cold current has chilled: the temperature it is given carries no
     * current anomaly over water on either path, since the climate stage's maritime influence adds
     * the anomaly to land cells only and the ocean's own stress reads the temperature before there
     * is an anomaly at all. Earth's subtropical highs are not thermal lows' mirror images either;
     * they wait on an atmosphere that is solved (docs/TODO.md, "Build the atmosphere").
     */
    fun pressureAnomalyHpa(config: WorldGenConfig, seasonTemperatureC: FloatField): FloatField {
        val cellsAcross = config.width
        val cellsDown = config.height
        val field = FloatField(cellsAcross, cellsDown)

        for (row in 0 until cellsDown) {
            val rowStart = row * cellsAcross
            var sum = 0.0
            for (column in 0 until cellsAcross) sum += seasonTemperatureC.data[rowStart + column]
            val zonalMeanC = (sum / cellsAcross).toFloat()
            for (column in 0 until cellsAcross) {
                field.data[rowStart + column] =
                    -(seasonTemperatureC.data[rowStart + column] - zonalMeanC) * HPA_PER_KELVIN
            }
        }

        smooth(config, field)
        return field
    }

    /**
     * Smooths a pressure field over [rossbyRadiusKm] on the ground, in place: carried down to the
     * atmosphere's grid ([SphericalGrid.forAtmosphere], [AtmosphereRemap.areaMean]), spread there by
     * implicit diffusion on the sphere ([SphericalOperators.diffuse]) whose kernel approaches a
     * Gaussian of that standard deviation, and carried back up by its double Fourier series.
     *
     * On the sphere and not in cells, because the gradient [surfaceWind] takes is the sphere's. A
     * blur whose radius is a count of cells spreads 970 km east-west at the equator and a few
     * kilometers near a pole, and the true east-west gradient of what it leaves there is that
     * departure over a few kilometers; with the gradient in the sphere's metric the polar rows' winds
     * ran to hundreds and thousands of meters a second, and the ocean's heat, forced by them, did
     * not solve (docs/DESIGN_LEDGER.md, A1-2). Near a pole the diffusion draws every zonal wave but
     * the mean toward the row beyond it, and the series carrying it up takes one value at the pole.
     *
     * On the atmosphere's grid because nothing finer survives the smoothing: its rows are a seventh
     * of the deformation radius apart, so the field the Gaussian leaves is resolved there many times
     * over, and the diffusion solves 45,000 cells on Earth's planet rather than the map's two
     * million.
     */
    fun smooth(config: WorldGenConfig, pressureHpa: FloatField) {
        val coarse = SphericalGrid.forAtmosphere(config.scale)
        val remap = AtmosphereRemap(config.width, config.height, coarse)
        val spread = SphericalOperators(coarse).diffuse(
            remap.areaMean(pressureHpa.data), rossbyRadiusKm() * WorldScale.METRES_PER_KM, SMOOTHING_STEPS
        )
        remap.toGround(spread).copyInto(pressureHpa.data)
    }

    /** A surface wind as two components in metres a second, eastward and southward, per cell. */
    class Vectors(val eastwardMps: FloatArray, val southwardMps: FloatArray)

    /**
     * The surface wind the pressure field drives, in metres a second, one vector per cell.
     *
     * The balance solved at every cell is the momentum equation with a linear drag — the standard
     * damped surface-layer form, and the one Gill and Matsuno use for exactly this job of getting
     * a surface wind out of a tropical pressure field without the geostrophic relation blowing up:
     *
     * ```
     * k u - f v = -(1/rho) dp/dx
     * k v + f u = -(1/rho) dp/dy
     * ```
     *
     * with `f` the Coriolis parameter from latitude and `k` the drag from [surfaceDrag]. Its
     * solution is one pair of expressions that covers the whole map:
     *
     * ```
     * u = (k Gx + f Gy) / (k^2 + f^2)      v = (k Gy - f Gx) / (k^2 + f^2)
     * ```
     *
     * where `G` is the acceleration the pressure gradient supplies. Where `f` is much larger than
     * `k` — the middle and high latitudes — this is the geostrophic wind, along the isobars, with
     * low pressure on the left in the northern hemisphere, turned toward the low by `atan(k/f)`.
     * At the equator `f` is zero, the denominator is `k^2` rather than nothing, and what is left
     * is a flow straight down the gradient from high to low. **That is the tropical limit, and it
     * is why there is no division by `f` and no special case at the equator anywhere below.** It
     * is also the physically right answer: tropical surface flow really does run down the pressure
     * gradient into the convergence zone rather than along the isobars.
     *
     * Over land the drag is larger ([CROSS_ISOBAR_LAND_DEGREES]), so the wind there crosses the
     * isobars at a steeper angle and blows further into the continent's thermal low than it would
     * over water — which is the monsoon reaching inland rather than running along the coast.
     */
    fun surfaceWind(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        pressureHpa: FloatField
    ): Vectors {
        val cellsAcross = config.width
        val cellsDown = config.height
        val eastward = FloatArray(cellsAcross * cellsDown)
        val southward = FloatArray(cellsAcross * cellsDown)

        // The gradient in the sphere's own metric: east-west across a row's own ground, which is
        // cos(latitude) of the equator's, and north-south across the pole onto the meridian
        // opposite rather than one-sided at the outermost row (`SphericalOperators`). The northward
        // component lives on the faces between rows; its mean over a cell's two faces is the
        // central difference across the cell.
        val grid = SphericalGrid.forGround(cellsAcross, cellsDown, config.scale)
        val operators = SphericalOperators(grid)
        val pressurePa = DoubleArray(cellsAcross * cellsDown) { pressureHpa.data[it] * PASCALS_PER_HPA.toDouble() }
        val gradient = operators.gradient(pressurePa)
        val gradientNorthward = operators.northAtCenters(gradient)

        val seaDrag = surfaceDrag(CROSS_ISOBAR_SEA_DEGREES)
        val landDrag = surfaceDrag(CROSS_ISOBAR_LAND_DEGREES)

        for (row in 0 until cellsDown) {
            val coriolis = coriolisParameter(ClimateStage.latitudeOf(row, cellsDown))
            for (column in 0 until cellsAcross) {
                val cell = row * cellsAcross + column
                val accelerationEast = (-gradient.eastAtCenters[cell] / AIR_DENSITY_KG_PER_M3).toFloat()
                val accelerationNorth = (-gradientNorthward[cell] / AIR_DENSITY_KG_PER_M3).toFloat()

                val drag = if (sea.isLand[cell]) landDrag else seaDrag
                val inverseBalance = 1f / (drag * drag + coriolis * coriolis)
                eastward[cell] =
                    (drag * accelerationEast + coriolis * accelerationNorth) * inverseBalance
                // Southward is the negative of northward, because rows grow southward.
                southward[cell] =
                    -(drag * accelerationNorth - coriolis * accelerationEast) * inverseBalance
            }
        }
        return Vectors(eastward, southward)
    }

    /**
     * The cross-isobar turn a given drag produces at a latitude, in degrees — the figure
     * [surfaceDrag] was built to deliver, read back out so a guard can measure the angle the code
     * actually applied instead of restating the formula and drifting away from it.
     */
    fun crossIsobarDegreesAt(latitudeDegrees: Float, crossIsobarDegrees: Float): Float {
        val coriolis = abs(coriolisParameter(latitudeDegrees))
        if (coriolis == 0f) return 90f
        return (atan(surfaceDrag(crossIsobarDegrees) / coriolis) / DEGREES_TO_RADIANS).toFloat()
    }
}
