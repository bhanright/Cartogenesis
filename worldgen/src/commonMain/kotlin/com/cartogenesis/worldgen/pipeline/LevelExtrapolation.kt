package com.cartogenesis.worldgen.pipeline

import kotlin.math.ln
import kotlin.math.pow

/**
 * The stationary waves' response on as many levels as it takes to converge, extrapolated from the
 * model's answers on two counts of equal-mass levels, one twice the other: Richardson's
 * extrapolation in the vertical.
 *
 * **What it corrects, and only that.** The dry model's answer moves as its levels are doubled, by
 * less each time: on the benchmarks the surface pressure stands 19 to 66% from twenty-four levels'
 * by area at four, 6 to 21% at eight and 1 to 4.5% at sixteen (docs/DESIGN_LEDGER.md, A1-3 and
 * W2h). That is the vertical discretization's truncation error, not a process the model leaves
 * out, so it is removed the way a truncation error is: with the answer `F_h` on levels of spacing
 * `h` and `F_2h` on twice the spacing, the converged answer is `F_h + (F_h - F_2h) / (2^p - 1)` to
 * the next order, `p` the order at which the error falls with the spacing ([CONVERGENCE_ORDER],
 * measured on the model's own benchmarks). Every field is read at the finer levels' places, the
 * coarser answer carried there in the vertical first ([coarseAt]); the surface pressure is each
 * model's own.
 *
 * Not a boost: where the coarser answer equals the finer one there is nothing to add, and where it
 * differs the correction is that difference's own shape, so it moves each place and each forcing by
 * what the levels missed there. Fewer levels make the benchmarks' surface pressure stronger, not
 * weaker (its root mean square 1.03 to 1.19 times twenty-four levels' at eight, 1.11 to 1.59 at
 * four), so the correction weakens it.
 *
 * **To be deleted** when sixteen levels are affordable (about a second a solve where eight take a
 * fifth, docs/DESIGN_LEDGER.md, A1-3 and W2h): it stands in for levels the model cannot yet pay
 * for.
 */
object LevelExtrapolation {

    /**
     * The order at which the response's error falls as the levels are doubled: the benchmarks'
     * surface pressure, the field this corrects, read as `log2(|F_8 - F_4| / |F_16 - F_8|)` by area
     * on the atmosphere's grid (no exact answer needed) and averaged over Gill's equatorial heating,
     * Hoskins and Karoly's heating at 45 N and their mountain on the jets, and Rodwell and Hoskins'
     * monsoon: 1.70, 1.27, 1.36 and 1.72, mean 1.51 (`LevelExtrapolationReport`). The winds' and
     * the vertical motion's orders spread from below zero to 2.3, and a world's surface pressure
     * reads 0.0 to 0.6: four levels are not yet where the error falls as a power of the spacing
     * for those (docs/DESIGN_LEDGER.md, W2h).
     */
    const val CONVERGENCE_ORDER = 1.51

    /** How much finer the finer answer's levels are than the coarser's: twice as many. */
    const val LEVEL_RATIO = 2

    /** The share of the finer answer's departure from the coarser one that is added to it: `1 / (2^p - 1)`. */
    val CORRECTION_SHARE: Double = 1.0 / (LEVEL_RATIO.toDouble().pow(CONVERGENCE_ORDER) - 1.0)

    /**
     * The extrapolated response: [fine] on its levels plus [CORRECTION_SHARE] (or [share]) of its
     * departure from [coarse], field by field, at [fine]'s places. Both on one grid, [fine] on
     * twice [coarse]'s equal-mass levels down to the same ground, and both under the same forcing.
     * The fields other than the surface pressure are made when first read, as [WaveResponse]'s are.
     */
    fun extrapolate(fine: WaveResponse, coarse: WaveResponse, share: Double = CORRECTION_SHARE): WaveResponse {
        val fineLevels = fine.levels
        val coarseLevels = coarse.levels
        require(fine.grid === coarse.grid || (fine.grid.rows == coarse.grid.rows && fine.grid.columns == coarse.grid.columns)) {
            "two responses on different grids"
        }
        require(fineLevels.levelCount == LEVEL_RATIO * coarseLevels.levelCount && fineLevels.surfacePressurePa == coarseLevels.surfacePressurePa) {
            "${fineLevels.levelCount} levels against ${coarseLevels.levelCount}"
        }
        val cells = fine.grid.cellCount
        fun combine(fineField: DoubleArray, coarseField: DoubleArray) =
            DoubleArray(fineField.size) { fineField[it] + share * (fineField[it] - coarseField[it]) }

        val surfacePressure = combine(fine.surfacePressurePa, coarse.surfacePressurePa)
        return WaveResponse(fine.grid, fineLevels, surfacePressure) {
            val coarsePressures = coarseLevels.levelPressurePa
            val finePressures = fineLevels.levelPressurePa
            val eastward = Array(fineLevels.levelCount) { level ->
                combine(fine.eastward[level], coarseAt(coarse.eastward, coarsePressures, finePressures[level], cells))
            }
            val northwardAtFaces = Array(fineLevels.levelCount) { level ->
                combine(fine.northwardAtFaces[level], coarseAt(coarse.northwardAtFaces, coarsePressures, finePressures[level], fine.northwardAtFaces[level].size))
            }
            // The geopotential is linear in the logarithm of pressure between levels, as the
            // hydrostatic law the model's temperature is read by makes it.
            val coarseLogs = DoubleArray(coarsePressures.size) { ln(coarsePressures[it]) }
            val geopotential = Array(fineLevels.levelCount) { level ->
                combine(fine.geopotential[level], coarseAt(coarse.geopotential, coarseLogs, ln(finePressures[level]), cells))
            }
            // The vertical motion: zero at the lid and the terrain's at the ground, the same in both.
            val coarseOmegaPressures = doubleArrayOf(0.0) + coarseLevels.interiorPressurePa + doubleArrayOf(coarseLevels.surfacePressurePa)
            val coarseOmega = arrayOf(DoubleArray(cells)) + coarse.verticalMotion + arrayOf(coarse.surfaceVerticalMotion)
            val verticalMotion = Array(fineLevels.interiorCount) { interior ->
                combine(fine.verticalMotion[interior], coarseAt(coarseOmega, coarseOmegaPressures, fineLevels.interiorPressurePa[interior], cells))
            }
            val temperature = Array(fineLevels.interiorCount) { interior ->
                val factor = 1.0 / (DryAir.GAS_CONSTANT_J_PER_KG_K * fineLevels.logPressureRatio[interior])
                DoubleArray(cells) { (geopotential[interior][it] - geopotential[interior + 1][it]) * factor }
            }
            WaveResponse.Fields(eastward, northwardAtFaces, geopotential, verticalMotion, temperature, fine.surfaceVerticalMotion)
        }
    }

    /**
     * [fields] (one array of [size] per point of [positions], rising) read at [target]: linear
     * between the two points either side of it, and beyond the first or the last point along the
     * line through the two nearest, which keeps the reading second-order where the finer levels
     * reach past the coarser ones (the top and the bottom half-layers).
     */
    internal fun coarseAt(fields: Array<DoubleArray>, positions: DoubleArray, target: Double, size: Int): DoubleArray {
        val last = positions.size - 1
        var upper = 0
        while (upper < last - 1 && positions[upper + 1] <= target) upper++
        val share = (target - positions[upper]) / (positions[upper + 1] - positions[upper])
        val above = fields[upper]
        val below = fields[upper + 1]
        return DoubleArray(size) { above[it] + share * (below[it] - above[it]) }
    }
}
