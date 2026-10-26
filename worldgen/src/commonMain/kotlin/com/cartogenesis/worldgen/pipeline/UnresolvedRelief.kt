package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The relief a cell's own ground holds above the channel its water leaves it by: the reaches of
 * its rivers finer than the grid, from their channel heads down to the cell's own scale
 * (docs/DESIGN_LEDGER.md, TH1-2b).
 *
 * The rounds grade each cell toward the cell its water runs to, as if the cell were a reach of
 * channel. A river's profile is not resolved that way: at steady state its slope falls with the
 * area it drains, `S = ks A^-theta` with `theta` near a half (Flint's law, which Lague, *The
 * stream power river incision model*, ESPL 39, 2014, finds holds above a channel head of 0.1 to
 * 5 km²), and the steep reaches near the heads, where the area is small, hold most of a range's
 * fluvial relief (Whipple and Tucker, JGR 104, 1999). A grid whose cells are hundreds of square
 * kilometers starts every river at a cell's area, so the relief of every reach above that is lost
 * to it: graded cell to cell, a belt sinks toward the share of its relief the grid resolves, a
 * third at 512 rows and a half at 1,024, and the same world's ranges stand higher on a finer grid
 * (rule 14). That is the relief this restores.
 *
 * **The unresolved reach.** Along a river, length grows as the 0.6 power of the area above it
 * (Hack, *Studies of longitudinal stream profiles in Virginia and Maryland*, USGS Professional
 * Paper 294-B, 1957, equation 3: `L = 1.4 A^0.6`, miles and square miles), so integrating Flint's
 * slope from the channel head's area to a cell's gives the reach's relief, `ks c^(theta/h)
 * (L(A)^q - L(A_head)^q) / q`, `q = 1 - theta/h`, `h` Hack's exponent. Its steepness `ks` is the
 * steady state's at the rate the history wears the crust down, `E` the orogen's excess over its
 * decay time: the reaches the grid cannot hold are as steep as the history's own wear needs them,
 * so a kilometer the history thickened and wore is worn once, and the grid does not wear away
 * again the relief it is too coarse to draw. Nothing else changes: the grid's rivers cut what it
 * resolves at the rounds' rate, and the cell's ground stands this much above the channel they cut.
 *
 * **The steepness against the rate** is Earth's measured one, not the rounds' linear law. Lague's
 * compilation of steady channels (2014, his figure 1) runs from a normalized steepness of
 * about 20 m^0.9 at 0.02 mm a year to about 500 at 14 mm a year, the square root of the rate, and
 * every data set in it but one calls for `n` near 2 and `m` near 1 (so `theta` is still a half): a
 * threshold for incision and floods that come and go make a river's long-term cut grow faster than
 * its slope. Read at the linear law instead, the steepness grew with the rate a hundredfold where
 * Earth's ranges' 39 km boxes hold relief that differs about threefold (docs/DESIGN_LEDGER.md,
 * TH1-2c). The rock's erodibility scales `K` in `E = K A S^2`, which at the steady slope
 * `S = ks A^-0.5` is `K ks^2`, as it scales the rounds' law, so the steepness goes as one over its
 * square root ([steadySteepnessMeters]).
 *
 * **The cell's mean.** A cell's height is its ground on the mean, which stands over its outlet by
 * a share of its relief, the hypsometric integral: [EARTH_CELL_HYPSOMETRIC_INTEGRAL], measured on
 * Earth's active ranges at the grid's own scale.
 */
internal object UnresolvedRelief {

    /**
     * Each cell's unresolved relief, meters, row-major: [orogenicExcessKm] what orogeny left on the
     * crust, km, which the history wears down at `excess / orogenDecayTime`, and [rockErodibility]
     * the factor on bedrock K, or null for one rock. The cell's mean ground over its channel, so the
     * relief times [EARTH_CELL_HYPSOMETRIC_INTEGRAL]. Zero where the crust carries no excess, and
     * everywhere on a grid whose cells are finer than a channel head.
     */
    fun meters(config: WorldGenConfig, orogenicExcessKm: FloatArray, rockErodibility: FloatArray?): FloatArray {
        val cells = orogenicExcessKm.size
        val cellAreaSquareMeters = config.squareKilometresPerCell * SQUARE_METERS_PER_KM2
        val reliefPerSteepness = reliefPerSteepness(cellAreaSquareMeters)
        val middleAreaSquareMeters = sqrt(CHANNEL_HEAD_SQUARE_METERS * cellAreaSquareMeters)
        val wearYears = config.tectonics.orogenDecayTimeYears
        if (reliefPerSteepness <= 0.0 || wearYears <= 0.0) return FloatArray(cells)
        return FloatArray(cells) { cell ->
            // The history's own wear, in meters of rock a year: the rate its rivers must keep.
            val wearMetersPerYear = orogenicExcessKm[cell].coerceAtLeast(0f) * METERS_PER_KM / wearYears
            if (wearMetersPerYear <= 0.0) return@FloatArray 0f
            val rockFactor = rockErodibility?.get(cell)?.toDouble() ?: 1.0
            val steepnessMeters = steadySteepnessMeters(wearMetersPerYear, rockFactor, middleAreaSquareMeters)
            (EARTH_CELL_HYPSOMETRIC_INTEGRAL * reliefPerSteepness * steepnessMeters).toFloat()
        }
    }

    /**
     * The relief of a steady river's reach from a channel head to where it drains [areaSquareMeters],
     * per meter of its steepness `ks`: Flint's slope integrated along Hack's length. Zero where the
     * area is no larger than a channel head's.
     */
    fun reliefPerSteepness(areaSquareMeters: Double): Double {
        if (areaSquareMeters <= CHANNEL_HEAD_SQUARE_METERS) return 0.0
        val exponent = 1.0 - CONCAVITY / HACK_EXPONENT
        fun lengthMeters(area: Double) = HACK_COEFFICIENT_SI * area.pow(HACK_EXPONENT)
        return HACK_COEFFICIENT_SI.pow(CONCAVITY / HACK_EXPONENT) *
            (lengthMeters(areaSquareMeters).pow(exponent) - lengthMeters(CHANNEL_HEAD_SQUARE_METERS).pow(exponent)) / exponent
    }

    /**
     * A steady channel's steepness, meters, where its rock wears at [wearMetersPerYear] and is
     * [rockFactor] times as erodible as the bedrock base: Lague's compilation at its exponent of a
     * half, [EARTH_STEEPNESS_AT_ONE_MILLIMETER_M09] times the root of the rate in mm a year, read at
     * Flint's concavity of a half instead of the compilation's 0.45 by the reach's own middle area
     * [middleAreaSquareMeters] (the two profiles agree there), over the root of the rock's factor.
     */
    private fun steadySteepnessMeters(wearMetersPerYear: Double, rockFactor: Double, middleAreaSquareMeters: Double): Double {
        val atOneMillimeter = EARTH_STEEPNESS_AT_ONE_MILLIMETER_M09 * middleAreaSquareMeters.pow(CONCAVITY - COMPILATION_CONCAVITY)
        return atOneMillimeter * sqrt(wearMetersPerYear / METERS_PER_MILLIMETER / rockFactor)
    }

    /**
     * Earth's steady channels' normalized steepness at 1 mm a year, m^0.9 at the compilation's
     * reference concavity [COMPILATION_CONCAVITY]: the square-root law through Lague's two ends, 20
     * at 0.02 mm a year and 500 at 14, `sqrt(20 / sqrt(0.02) * 500 / sqrt(14))`, 137.5.
     */
    private val EARTH_STEEPNESS_AT_ONE_MILLIMETER_M09: Double = sqrt(20.0 / sqrt(0.02) * (500.0 / sqrt(14.0)))

    /** The reference concavity the compilation's normalized steepness is read at (Lague 2014). */
    private const val COMPILATION_CONCAVITY = 0.45

    /** Flint's concavity: `theta = m / n`, a half for the rounds' law (`m` a half, `n` one) and for Lague's (`m` one, `n` two). */
    private const val CONCAVITY = 0.5

    /** Hack's exponent, length on area: 0.6. */
    private const val HACK_EXPONENT = 0.6

    /**
     * Hack's coefficient in meters and square meters: `1.4` miles per square mile to the 0.6,
     * `1.4 * 1,609.344 / (2,589,988)^0.6`, about 0.320.
     */
    private val HACK_COEFFICIENT_SI: Double = 1.4 * METERS_PER_MILE / SQUARE_METERS_PER_SQUARE_MILE.pow(HACK_EXPONENT)

    /**
     * Where fluvial channels begin, m²: Lague's 0.1 to 5 km², below which debris flows and
     * landslides do the work; their geometric middle, 0.71 km².
     */
    private val CHANNEL_HEAD_SQUARE_METERS: Double = sqrt(0.1 * 5.0) * SQUARE_METERS_PER_KM2

    /**
     * How high a cell's ground stands on the mean above its lowest, as a share of its relief: the
     * median, over 39 km boxes of Earth's active ranges whose relief passes a kilometer, of
     * `(mean - min) / (max - min)` (GMRT: the northern, central and southern Andes 0.42, 0.44 and
     * 0.42, the Himalaya 0.46, the Rockies 0.45, the Alps 0.36; 0.44 to 0.49 at 20 km).
     */
    const val EARTH_CELL_HYPSOMETRIC_INTEGRAL = 0.43

    private const val METERS_PER_MILE = 1_609.344
    private const val SQUARE_METERS_PER_SQUARE_MILE = 2_589_988.110336
    private const val SQUARE_METERS_PER_KM2 = 1e6
    private const val METERS_PER_KM = 1_000.0
    private const val METERS_PER_MILLIMETER = 1e-3
}
