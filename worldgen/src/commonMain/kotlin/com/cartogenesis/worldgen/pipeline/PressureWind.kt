package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldScale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The surface wind that blows down the sea-level pressure's gradient, and the figures the balance
 * is built from.
 *
 * The pressure is the boundary layer's ([BoundaryLayer]): a zonal mean from the circulation belts
 * and the stationary waves the dry atmosphere solves for the land and sea and the terrain under it.
 * [surfaceWind] balances its gradient against the Coriolis force and a linear surface drag on the
 * map's own grid, cell by cell, with land's drag and the sea's, so the wind changes at the real
 * coast and not at a coarse cell's edge.
 *
 * A world generated with [WorldGenConfig.climate]'s `pressureWinds` switched off has the belts
 * alone, every cell of a row blowing the same way, which is what gives the guards on the solved
 * wind a control.
 */
internal object PressureWind {

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

    private const val DEGREES_TO_RADIANS = PI / 180.0

    /**
     * The scale the atmosphere organises surface pressure on, in kilometres: the first baroclinic
     * Rossby radius of deformation, `N H / f`, at [ROSSBY_REFERENCE_LATITUDE_DEGREES].
     *
     * A pressure field is not a copy of the temperature field. A continent's coastline has
     * kilometre-scale wiggles and a bay a hundred kilometres across is warmer than the cape beside
     * it, but the atmosphere does not carry a separate low over every bay: below the deformation
     * radius a pressure anomaly cannot hold itself up against the flow that drains it, and
     * disperses.
     *
     * With `N = 1.0e-2` per second, `H = 10 km` and `f` at 45 degrees this is 970 km, which is the
     * thousand kilometres the chunk was specified at, derived rather than assumed. It is a length
     * on the ground, the same at every grid.
     *
     * The atmosphere's grid is sized by it ([SphericalGrid.rowsForAtmosphere]), and the forcing the
     * stationary waves read is spread to a quarter of it ([WaveForcing.GROUND_FORCING_WIDTH_METERS]).
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
     * The drag on the boundary layer's wind over land or the sea at the belts' speed, per second:
     * the surface's stress on the layer's mass, one law for the surface wind's balance here and the
     * dry model's lowest level ([BoundaryLayer.surfaceDragPerSecond]).
     */
    fun surfaceDrag(isLand: Boolean): Double = BoundaryLayer.surfaceDragPerSecond(isLand)

    /** The sea's drag under the belts' own wind on a row, [eastwardMps] by [southwardMps], per second. */
    fun beltDrag(eastwardMps: Float, southwardMps: Float): Double =
        BoundaryLayer.surfaceDragPerSecond(false, sqrt(eastwardMps.toDouble() * eastwardMps + southwardMps.toDouble() * southwardMps))

    /** A surface wind as two components in metres a second, eastward and southward, per cell. */
    class Vectors(val eastwardMps: FloatArray, val southwardMps: FloatArray)

    /**
     * The surface wind a sea-level pressure drives, in metres a second, one vector per cell of the
     * map: the pressure's departure from its zonal mean, [eddyPressurePa] (pascals, row-major on
     * the map), on top of the belts' zonal-mean wind, [beltEastwardMps] and [beltSouthwardMps]
     * (metres a second, one each per row of the map), which set the zonal mean's own pressure.
     *
     * The balance solved at every cell is the momentum equation with a drag — the standard damped
     * surface-layer form, and the one Gill and Matsuno use for exactly this job of getting a surface
     * wind out of a tropical pressure field without the geostrophic relation blowing up:
     *
     * ```
     * k u - f v = -(1/rho) dp/dx + F
     * k v + f u = -(1/rho) dp/dy
     * ```
     *
     * with `f` the Coriolis parameter from latitude and `k` the drag: the well-mixed layer's
     * momentum budget under the surface's bulk stress (de Roode and Siebesma 2020, their equations
     * 3, 4, 8 and 10, the mixed layer of Stevens and others 2002), `k = c s` with `c` the stress's
     * drag per unit of speed ([BoundaryLayer.dragPerSpeed]) and `s` the speed the stress feels,
     * `sqrt(|V|^2 + U_t^2)` with the weather's gusts `U_t`. The speed is the wind's own, so the
     * balance is solved for it first: with `|G|` the acceleration's size, `|V| = |G| / sqrt(f^2 +
     * k^2)` is a quadratic in `|V|^2`, `c^2 |V|^4 + (f^2 + c^2 U_t^2) |V|^2 - |G|^2 = 0`, whose
     * positive root is exact. Then the solution is one pair of expressions that covers the whole map:
     *
     * ```
     * u = (k Gx + f Gy) / (k^2 + f^2)      v = (k Gy - f Gx) / (k^2 + f^2)
     * ```
     *
     * where `G` is the acceleration the pressure gradient and `F` supply. Where `f` is much larger
     * than `k` — the middle and high latitudes — this is the geostrophic wind, along the isobars,
     * with low pressure on the left in the northern hemisphere, turned toward the low by
     * `atan(k/f)`. At the equator `f` is zero, the denominator is `k^2` rather than nothing, and
     * what is left is a flow straight down the gradient from high to low. **That is the tropical
     * limit, and it is why there is no division by `f` and no special case at the equator.**
     *
     * **The zonal mean.** Its pressure gradient is the belts' own under the sea's drag at the
     * belts' speed ([beltDrag]), `-(1/rho) dp/dy = k v + f u` ([BoundaryLayer.zonalPressurePa]
     * integrates it), and `F`, the
     * eastward push `k u - f v` the belts need, is what holds their zonal wind against the drag
     * where no zonal pressure gradient can: on Earth that is the eddies' convergence of westerly
     * momentum in the westerlies and its divergence from the trades, which no mean pressure
     * carries. With both, the open sea's wind under no eddy pressure is the belts exactly, and a
     * coast's land is slowed and turned more by its own larger drag.
     *
     * Over land the drag is larger ([BoundaryLayer.LAND_DRAG_COEFFICIENT]), so the wind there crosses
     * the isobars at a steeper angle and blows further into the continent's thermal low than it would
     * over water — which is the monsoon reaching inland rather than running along the coast.
     */
    fun surfaceWind(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        eddyPressurePa: FloatArray,
        beltEastwardMps: FloatArray,
        beltSouthwardMps: FloatArray
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
        val gradient = operators.gradient(DoubleArray(cellsAcross * cellsDown) { eddyPressurePa[it].toDouble() })
        val gradientNorthward = operators.northAtCenters(gradient)

        val seaPerSpeed = BoundaryLayer.dragPerSpeed(isLand = false)
        val landPerSpeed = BoundaryLayer.dragPerSpeed(isLand = true)
        val gustsSquared = BoundaryLayer.TRANSIENT_WIND_MPS * BoundaryLayer.TRANSIENT_WIND_MPS
        val density = AIR_DENSITY_KG_PER_M3.toDouble()

        parallelChunks(0, cellsDown) { startRow, endRow ->
            for (row in startRow until endRow) {
                val coriolis = coriolisParameter(ClimateStage.latitudeOf(row, cellsDown)).toDouble()
                val beltEast = beltEastwardMps[row].toDouble()
                val beltNorth = -beltSouthwardMps[row].toDouble()
                val beltDrag = beltDrag(beltEastwardMps[row], beltSouthwardMps[row])
                val beltPush = beltDrag * beltEast - coriolis * beltNorth
                val zonalPressurePush = beltDrag * beltNorth + coriolis * beltEast
                for (column in 0 until cellsAcross) {
                    val cell = row * cellsAcross + column
                    val accelerationEast = -gradient.eastAtCenters[cell] / density + beltPush
                    val accelerationNorth = -gradientNorthward[cell] / density + zonalPressurePush
                    val perSpeed = if (sea.isLand[cell]) landPerSpeed else seaPerSpeed
                    val drag = perSpeed * sqrt(balancedSpeedSquared(
                        accelerationEast * accelerationEast + accelerationNorth * accelerationNorth,
                        coriolis * coriolis, perSpeed, gustsSquared
                    ) + gustsSquared)
                    val inverseBalance = 1.0 / (drag * drag + coriolis * coriolis)
                    eastward[cell] = ((drag * accelerationEast + coriolis * accelerationNorth) * inverseBalance).toFloat()
                    // Southward is the negative of northward, because rows grow southward.
                    southward[cell] = (-(drag * accelerationNorth - coriolis * accelerationEast) * inverseBalance).toFloat()
                }
            }
        }
        return Vectors(eastward, southward)
    }

    /**
     * The square of the speed `s` at which `s^2 (f^2 + c^2 (s^2 + U_t^2)) = |G|^2`, the drag balance
     * of an acceleration of size squared [accelerationSquared] under a Coriolis parameter squared
     * [coriolisSquared], a drag per unit of speed [perSpeed] and gusts squared [gustsSquared]:
     * the positive root of the quadratic in `s^2`, written so it keeps its digits when the drag is
     * weak.
     */
    private fun balancedSpeedSquared(accelerationSquared: Double, coriolisSquared: Double, perSpeed: Double, gustsSquared: Double): Double {
        val linear = coriolisSquared + perSpeed * perSpeed * gustsSquared
        val quadratic = perSpeed * perSpeed
        if (accelerationSquared == 0.0) return 0.0
        return 2.0 * accelerationSquared / (linear + sqrt(linear * linear + 4.0 * quadratic * accelerationSquared))
    }

    /**
     * The angle the balance in [surfaceWind] turns the wind across the isobars by at a latitude,
     * over land when [isLand], in degrees: `atan(k / |f|)`, read back from the drag the code
     * applies so a guard measures what is used and not a copy of it.
     */
    fun crossIsobarDegreesAt(latitudeDegrees: Float, isLand: Boolean): Double {
        val coriolis = abs(coriolisParameter(latitudeDegrees).toDouble())
        return atan2(surfaceDrag(isLand), coriolis) / DEGREES_TO_RADIANS
    }
}
