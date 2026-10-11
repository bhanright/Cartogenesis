package com.cartogenesis.worldgen.pipeline

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * Earth's annual zonal-mean surface wind: the three circulation belts' zonal wind in meters a
 * second at a latitude, and the latitudes they turn at.
 *
 * The year's mean profile, whose mean square the boundary layer's weather is measured against
 * ([BoundaryLayer.TRANSIENT_WIND_MPS]), and whose belts' widths and polar easterlies the climate's
 * own seasonal circulation keeps poleward of its Hadley cells ([HadleyCirculation]); each half-year's
 * surface wind, and the ocean's stress, are that circulation's.
 *
 * **Zonal.** A cosine in each belt: the trades westward, strongest at the equator and falling to
 * nothing at [TRADE_BELT_EDGE_DEGREES]; the westerlies eastward, strongest at
 * [WESTERLY_BELT_CENTRE_DEGREES]; the polar easterlies westward at [POLAR_EASTERLY_STRENGTH] of the
 * others. The peak is [PressureWind.BELT_SPEED_MPS].
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
    internal const val POLAR_EASTERLY_STRENGTH = 0.6f

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
}
