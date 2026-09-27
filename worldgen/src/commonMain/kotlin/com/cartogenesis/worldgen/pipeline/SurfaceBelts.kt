package com.cartogenesis.worldgen.pipeline

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * The zonal-mean surface wind: the three circulation belts as one vector in meters a second,
 * eastward and northward, at a latitude in one half of the year.
 *
 * One copy of the belts for everything that reads them. [ClimateStage]'s march takes its belt edges
 * from here; the ocean's stress takes the whole vector ([windMps]). The march still reads its own
 * direction-and-slant pair built from the same edges and the same slope, until it is moved onto this
 * vector (docs/TODO.md, "The march and the sea blow two winds").
 *
 * **Zonal.** A cosine in each belt: the trades westward, strongest at the thermal equator and
 * falling to nothing at [TRADE_BELT_EDGE_DEGREES]; the westerlies eastward, strongest at
 * [WESTERLY_BELT_CENTRE_DEGREES]; the polar easterlies westward at [POLAR_EASTERLY_STRENGTH] of the
 * others. The peak is [PressureWind.BELT_SPEED_MPS].
 *
 * **Meridional.** Each belt's surface leg carries a slope across the latitude lines, the same slope
 * the march's slant stands for: in toward the thermal equator in the trades, out toward the polar
 * front in the westerlies, back down in the polar cell. It is a direction, a share of the zonal
 * speed on the ground (`ClimateConfig.meridionalWindShare`), so the meridional wind falls away with
 * the zonal one at every belt edge rather than stepping there.
 *
 * **Seasons.** The belts ride the thermal equator, [tilt] degrees poleward of their annual place in
 * a hemisphere's warm half and as far equatorward in its cold half, as the march's belts do. A
 * half-year here is each hemisphere's own, as it is everywhere in the climate: the warm half is the
 * northern summer in the north and the southern summer in the south.
 */
internal object SurfaceBelts {

    /** Where the trades give way to the westerlies, in degrees from the thermal equator: the Hadley cell's edge. */
    const val TRADE_BELT_EDGE_DEGREES = 30f

    /** Where the westerlies give way to the polar easterlies: the edge of the Ferrel cell. */
    const val WESTERLY_BELT_EDGE_DEGREES = 60f

    /** Middle of the westerly belt, where its eastward wind is strongest. */
    private const val WESTERLY_BELT_CENTRE_DEGREES = 45f

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
     * The belts' zonal wind at [beltDegrees] from the thermal equator as a share of
     * [PressureWind.BELT_SPEED_MPS], positive eastward: -1 at the thermal equator, +1 at the
     * westerlies' center, -0.6 at the polar easterlies'.
     */
    fun zonalShare(beltDegrees: Float): Float {
        val fromEquator = abs(beltDegrees)
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

    /**
     * Signed degrees from the thermal equator at [latitude] in one half-year, positive poleward of
     * it: negative where the thermal equator has migrated past the latitude into its hemisphere.
     */
    fun fromThermalEquatorDegrees(latitude: Float, tiltDegrees: Float, warm: Boolean): Float =
        if (warm) abs(latitude) - tiltDegrees else abs(latitude) + tiltDegrees

    /** A wind as two components in meters a second, eastward and northward. */
    class Wind(val eastwardMps: Float, val northwardMps: Float)

    /**
     * The belts' surface wind at [latitude] in the [warm] or cold half of the year, meters a second.
     *
     * [tiltDegrees] is the thermal equator's migration (zero for a world without seasons), and
     * [meridionalShare] the belts' slope across the latitude lines, meridional speed over zonal
     * speed on the ground.
     */
    fun windMps(latitude: Float, tiltDegrees: Float, warm: Boolean, meridionalShare: Float): Wind {
        val fromThermalEquator = fromThermalEquatorDegrees(latitude, tiltDegrees, warm)
        val beltDegrees = abs(fromThermalEquator)
        val eastward = PressureWind.BELT_SPEED_MPS * zonalShare(beltDegrees)
        // Away from the thermal equator, as a direction north: a row the thermal equator has
        // crossed finds "away" pointing toward its own pole's opposite.
        val poleward = if (latitude < 0f) -1f else 1f
        val outwardNorth = if (fromThermalEquator < 0f) -poleward else poleward
        val legDirection = when {
            beltDegrees < TRADE_BELT_EDGE_DEGREES -> -outwardNorth      // the Hadley leg, in toward the ITCZ
            beltDegrees < WESTERLY_BELT_EDGE_DEGREES -> outwardNorth    // the Ferrel leg, out toward the polar front
            else -> -outwardNorth                                       // the polar leg, back down
        }
        return Wind(eastward, legDirection * meridionalShare * abs(eastward))
    }
}
