package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.abs
import kotlin.math.exp

/**
 * The marine inversion: where a cold sea puts a stratus lid on the air above a subtropical west
 * coast and holds its rain in until the ground stands above the lid.
 *
 * A property of the air column over the ground rather than of the parcel crossing it, which is
 * why it is built here, before the march, and handed to it. What the march's own lengths and rates
 * were until C1b, the depletion, evaporation and return lengths and the convergence term beside
 * this one, are now physical rates in [MoistureMarch]; see docs/DESIGN_LEDGER.md, W3 and C1b.
 */
object MoistureBudget {

    /**
     * The height an air mass has to climb before the marine inversion stops holding its rain in,
     * in metres, and how far inland the capped layer reaches, in kilometres.
     *
     * A thousand metres: Klein and Hartmann (1993) map the subtropical stratus regimes to the
     * strength of the lower-tropospheric inversion, whose base over the eastern ocean margins sits
     * between about five hundred and fifteen hundred metres. Below it the marine layer is
     * stratus-capped and rains a drizzle; the first ground that stands above it — the Andes behind
     * the Atacama, the Great Escarpment behind the Namib — rains normally, which is exactly why
     * those deserts are coastal strips and not whole interiors.
     *
     * Five hundred kilometres is how far the capped layer is carried inland before it has mixed
     * out, the width of the Atacama-Sechura and Namib strips taken together with the Baja
     * peninsula's.
     */
    const val INVERSION_LID_METRES = 1_000f
    const val INVERSION_REACH_KM = 500f

    /**
     * **What this term does not do, measured.**
     *
     * It does not make a coastal desert. Pooled over the standard seeds it moves a cold west
     * coast's share of its own hinterland's rain by 0.2%, with a suppression field whose
     * warm-season peak over those coasts is 0.95 and none of whose cells stand above the lid - so
     * the march reads a strong lid and rains anyway. The march is a reservoir and this is a
     * multiplier on the rate it empties at: hold the rate down and the moisture stands higher,
     * because the ground's return adds on the deficit, and `moisture x rate` comes back within a
     * few cells. A coastal desert needs the water taken out of the column, not the rain rate held
     * down, and that is a change to the march's shape rather than to this term. The term is kept
     * because the diagnosis names what would make it bite and `MoistureBudgetTest` goes on
     * measuring it. See docs/DESIGN_LEDGER.md, W3.
     */
    const val INVERSION_MEASURED_EFFECT = 0.002f

    /**
     * How cold the water off a coast has to be, as a departure from the mean of its own latitude
     * in degrees Celsius, for the inversion above it to be at full strength.
     *
     * Three degrees, the California current's — the mildest of the three eastern-boundary
     * currents that make a coastal desert, against the Peru current's four to seven and the
     * Benguela's four. A ramp to it rather than a threshold at it, so that a coast with a
     * one-degree anomaly gets a third of the suppression and nothing has to be decided by a cliff
     * the map's own noise could push a cell across.
     */
    const val INVERSION_FULL_ANOMALY_C = 3f

    /** Where the subtropical stratus decks sit, in degrees from the thermal equator. */
    private const val INVERSION_CENTRE_DEGREES = 25f
    private const val INVERSION_WIDTH_DEGREES = 15f

    /**
     * How far each land cell's rain is held back by a marine inversion, 0..1, where 1 is a cell
     * directly behind a west coast washed by water [INVERSION_FULL_ANOMALY_C] below its latitude's
     * mean, in the middle of the subtropical stratus belt.
     *
     * Built by one sweep west to east along each row, which is the direction the capped marine
     * layer travels: it comes off the cold water onto the coast facing it and is carried inland,
     * decaying over [INVERSION_REACH_KM]. That sweep is why the term makes a *west* coast desert
     * and not an east one, and it does not depend on which way the march happens to be sweeping,
     * because the inversion is a property of the air column over the ground rather than of the
     * parcel crossing it.
     *
     * [seaSurfaceAnomalyC] is the current stage's departure from the latitude mean, and
     * [tiltDegrees] with [warm] place the stratus belt in this season the same way the rain belts
     * are placed. Returns null when `ClimateConfig.marineInversion` is off or the ocean stage is,
     * so the march runs without the term rather than with a zero one.
     */
    internal fun inversionSuppression(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        seaSurfaceAnomalyC: FloatField?,
        tiltDegrees: Float,
        warm: Boolean
    ): FloatField? {
        if (seaSurfaceAnomalyC == null) return null
        val cellsAcross = config.width
        val cellsDown = config.height
        val decayPerCell = exp(
            (-config.scale.cellWidthKm(cellsAcross) / INVERSION_REACH_KM).toFloat()
        )
        val field = FloatField(cellsAcross, cellsDown)
        parallelChunks(0, cellsDown) { startRow, endRow ->
            for (row in startRow until endRow) {
                val latitude = ClimateStage.latitudeOf(row, cellsDown)
                val fromThermalEquator = if (warm) {
                    abs(abs(latitude) - tiltDegrees)
                } else {
                    abs(latitude) + tiltDegrees
                }
                val inBelt = stratusBelt(fromThermalEquator)
                if (inBelt <= 0f) continue

                // Two laps west to east, so a coast at the map's seam is carried across it: the
                // first lap leaves the strength the seam's own column would have handed on, the
                // second records. One extra lap is enough because the decay has fallen to nothing
                // long before a parcel could reach the seam twice.
                var carried = 0f
                for (lap in 0 until 2) {
                    for (column in 0 until cellsAcross) {
                        val cell = row * cellsAcross + column
                        carried = if (!sea.isLand[cell]) {
                            val coldness = (-seaSurfaceAnomalyC.data[cell] /
                                INVERSION_FULL_ANOMALY_C).coerceIn(0f, 1f)
                            coldness * inBelt
                        } else {
                            carried * decayPerCell
                        }
                        if (lap == 1 && sea.isLand[cell]) field.data[cell] = carried
                    }
                }
            }
        }
        return field
    }

    /** How much of the subtropical stratus belt a latitude is in, 0..1. */
    private fun stratusBelt(degreesFromThermalEquator: Float): Float {
        val widthsFromCentre =
            (degreesFromThermalEquator - INVERSION_CENTRE_DEGREES) / INVERSION_WIDTH_DEGREES
        return exp((-widthsFromCentre * widthsFromCentre)).coerceIn(0f, 1f)
    }
}
