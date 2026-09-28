package com.cartogenesis.worldgen.pipeline

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos

/**
 * The zonal-mean surface wind of the year: the three circulation belts as one vector in meters a
 * second, eastward and northward, at a latitude.
 *
 * One copy of the belts for everything that reads them. [ClimateStage]'s march takes its belt edges
 * from here, and its slant is the same slope ([meridionalShare]); the ocean's stress takes the whole
 * vector ([windMps]).
 *
 * **Zonal.** A cosine in each belt: the trades westward, strongest at the equator and falling to
 * nothing at [TRADE_BELT_EDGE_DEGREES]; the westerlies eastward, strongest at
 * [WESTERLY_BELT_CENTRE_DEGREES]; the polar easterlies westward at [POLAR_EASTERLY_STRENGTH] of the
 * others. The peak is [PressureWind.BELT_SPEED_MPS]. The profile is the annual mean's shape, and it
 * is not migrated with the seasons here: migrating an annual shape by the tilt and averaging the two
 * halves would smooth it twice (docs/DESIGN_LEDGER.md, 4b-1).
 *
 * **Meridional.** Each belt's surface leg carries a slope across the latitude lines, the slope the
 * march's slant stands for: in toward the equator in the trades, out toward the polar front in the
 * westerlies, back down in the polar cell. It is a direction, a share of the zonal speed on the
 * ground (`ClimateConfig.meridionalWindShare`), so the meridional wind falls away with the zonal one
 * at the 30- and 60-degree edges and at the poles, where the zonal wind is zero, rather than
 * stepping there. At the equator the zonal wind is strongest, so there the leg's direction itself
 * must pass through zero: the trades blow toward the ITCZ wherever it is in the year, and the
 * annual mean of that direction under the ITCZ's migration is continuous ([hadleyLegNorth]).
 */
internal object SurfaceBelts {

    /** Where the trades give way to the westerlies, in degrees from the thermal equator: the Hadley cell's edge. */
    const val TRADE_BELT_EDGE_DEGREES = 30f

    /** Where the westerlies give way to the polar easterlies: the edge of the Ferrel cell. */
    const val WESTERLY_BELT_EDGE_DEGREES = 60f

    /**
     * Middle of the westerly belt, where its eastward wind is strongest and where the belts' own
     * curl changes sign: the boundary between the subtropical and the subpolar gyres.
     */
    const val WESTERLY_BELT_CENTRE_DEGREES = 45f

    /** Middle of the polar-easterly belt, where its westward wind is strongest. */
    private const val POLAR_BELT_CENTRE_DEGREES = 75f

    /** Degrees of cosine phase per degree of latitude inside the trade belt: 90 over 30. */
    private const val TRADE_PHASE_PER_DEGREE = 3.0

    /** The same for the two belts poleward of the trades: 90 over the 15 from center to edge. */
    private const val MID_AND_POLAR_PHASE_PER_DEGREE = 6.0

    /**
     * How much weaker the polar easterlies blow than the trades and the westerlies: the polar cell
     * is the shallowest and weakest of the three.
     */
    private const val POLAR_EASTERLY_STRENGTH = 0.6f

    /**
     * The belts' zonal wind at [latitude] as a share of [PressureWind.BELT_SPEED_MPS], positive
     * eastward: -1 at the equator, +1 at the westerlies' center, -0.6 at the polar easterlies'.
     */
    fun zonalShare(latitude: Float): Float {
        val fromEquator = abs(latitude)
        return when {
            fromEquator < TRADE_BELT_EDGE_DEGREES ->
                -cos(fromEquator * TRADE_PHASE_PER_DEGREE * PI / 180.0).toFloat()
            fromEquator < WESTERLY_BELT_EDGE_DEGREES ->
                cos((fromEquator - WESTERLY_BELT_CENTRE_DEGREES) * MID_AND_POLAR_PHASE_PER_DEGREE * PI / 180.0).toFloat()
            else ->
                -cos((fromEquator - POLAR_BELT_CENTRE_DEGREES) * MID_AND_POLAR_PHASE_PER_DEGREE * PI / 180.0).toFloat() *
                    POLAR_EASTERLY_STRENGTH
        }
    }

    /** A wind as two components in meters a second, eastward and northward. */
    class Wind(val eastwardMps: Float, val northwardMps: Float)

    /**
     * The belts' surface wind at [latitude], meters a second, with [meridionalShare] the belts'
     * slope across the latitude lines, meridional speed over zonal speed on the ground, and
     * [migrationDegrees] how far the thermal equator migrates over the year
     * (`ClimateConfig.seasonalTiltDegrees`, zero with seasons off).
     */
    fun windMps(latitude: Float, meridionalShare: Float, migrationDegrees: Float): Wind {
        val fromEquator = abs(latitude)
        val eastward = PressureWind.BELT_SPEED_MPS * zonalShare(latitude)
        val poleward = if (latitude < 0f) -1f else 1f
        val legNorth = when {
            fromEquator < TRADE_BELT_EDGE_DEGREES -> hadleyLegNorth(latitude, migrationDegrees)
            fromEquator < WESTERLY_BELT_EDGE_DEGREES -> poleward   // the Ferrel leg, out toward the polar front
            else -> -poleward                                      // the polar leg, back down
        }
        return Wind(eastward, legNorth * meridionalShare * abs(eastward))
    }

    /**
     * The year's mean direction of the Hadley cell's surface leg at [latitude], northward positive,
     * -1 to 1: the trades blow in toward the ITCZ, and the ITCZ migrates.
     *
     * With the thermal equator at `T sin(2π t)` over the year, `T` [migrationDegrees], the leg at a
     * latitude `φ` inside `±T` blows north while the ITCZ is north of it and south while it is
     * south; the share of the year the ITCZ spends north of `φ` is `1/2 - asin(φ/T)/π`, so the
     * mean direction is `-(2/π) asin(φ/T)`. It passes through zero on the equator, as Earth's
     * annual surface meridional wind does under its ITCZ, and meets the leg's full equatorward
     * direction at `±T`, continuous there too. The migration is the energy balance's own, whose
     * declination is a sinusoid of the year ([EnergyBalance]); the march's two half-years sample the
     * same year at two positions.
     *
     * A world with no migration has no such mean: its ITCZ sits on the equator all year and the leg
     * reverses there, between the two rows either side of it (docs/TODO.md).
     */
    internal fun hadleyLegNorth(latitude: Float, migrationDegrees: Float): Float {
        val equatorward = if (latitude < 0f) 1f else -1f
        if (migrationDegrees <= 0f || abs(latitude) >= migrationDegrees) return equatorward
        return (-2.0 / PI * asin((latitude / migrationDegrees).toDouble())).toFloat()
    }
}
