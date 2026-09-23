package com.cartogenesis.cartography.geometry

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * Detector 1: whether a layer's straight runs favour the grid's bearings.
 *
 * The null is not that a natural outline has no preferred bearing at all — ice and biome edges
 * follow the latitude, structural valleys follow the plates, and this generator's land runs twice
 * as far east-west as north-south on the ground (docs/GEOGRAPHY.md). A real preference is broad; a
 * grid artefact is a narrow excess at exactly one of the grid's bearings. So each grid bearing's
 * bin is compared with its two immediate neighbours, not with a uniform distribution: the run
 * length lying within [BIN_HALF_WIDTH_DEGREES] of the grid bearing against the mean of the two
 * bins of the same width on either side of it. That ratio is 1 for any preference broad next to
 * the three bins' 24 degrees, whatever its direction.
 *
 * Two things must both hold for a violation: the ratio reaches [EFFECT_RATIO], and it is past the
 * sampling spread — its lower confidence bound, at the census's corrected level, is above 1.
 * Significance alone would, over a whole world's coast, reject real geography.
 *
 * Only runs long enough for the grid to have resolved their bearing count toward a bin: see
 * [shortestResolvedRunKm]. The sampling spread comes from a block bootstrap, because neighbouring
 * runs along one outline turn together and are not independent trials: runs are grouped
 * [BLOCK_RUNS] at a time along each line, and the blocks resampled.
 */
internal object BearingIsotropy {

    /**
     * Half the width of each bearing bin, in degrees on the ground.
     *
     * A grid bearing's bin and its two neighbours span six half-widths, and the closest two grid
     * bearings on this map (east-west and the diagonal) lie 26.565 degrees apart, so the three
     * bins round one bearing stay clear of the three round the next only below 4.43 degrees.
     * Four leaves the gap.
     */
    const val BIN_HALF_WIDTH_DEGREES = 4.0

    /**
     * How much more run length the grid's bin must hold than its neighbours' mean to matter.
     *
     * A real preference is broad. The narrowest one a whole layer of a world could plausibly carry
     * — every edge within about ten degrees of one bearing, a Gaussian of standard deviation 10
     * centred exactly on the grid's bearing — puts 1.34 times its neighbours' length in the
     * central bin at these bin widths (the Gaussian's mean over the central 8 degrees against its
     * mean over the flanks from 4 to 12). A ratio past 1.5 therefore needs a preference narrower
     * than any geography gives a layer; what is that narrow is the grid.
     */
    const val EFFECT_RATIO = 1.5

    /**
     * How many consecutive runs along one line make a block for the bootstrap.
     *
     * Chosen from the controls, not from any world: along the natural outlines of
     * `GeometryControlTest`, whether one run lies in a grid bin and whether the run eight
     * further along does are uncorrelated (the lag at which the indicator's autocorrelation falls
     * below 0.1 is under four runs; eight doubles it), so blocks this long are independent enough
     * for the bootstrap's spread to be honest.
     */
    const val BLOCK_RUNS = 8

    const val BOOTSTRAP_RESAMPLES = 1000

    /** The share of the three bins the central one holds when the ratio is 1. */
    private const val NULL_CENTRAL_SHARE = 1.0 / 3.0

    class Result(
        val bearingIndex: Int,
        val gridBearingDegrees: Double,
        val centralKm: Double,
        val flankKm: Double,
        /** Central length over the flanks' mean, with one shortest resolved run added to each as a prior. */
        val ratio: Double,
        /** The ratio's lower confidence bound at the census's corrected level. */
        val lowerRatio: Double,
        val blocks: Int,
        val minimumBlocks: Int,
        val outcome: Outcome
    ) {
        override fun toString(): String =
            "%.1f deg: %.2fx (lower %.2fx) over %d blocks, central %.0f km, flanks %.0f km%s".format(
                gridBearingDegrees, ratio, lowerRatio, blocks, centralKm, flankKm,
                if (outcome == Outcome.INSUFFICIENT) " [insufficient: needs $minimumBlocks]" else ""
            )
    }

    /**
     * The shortest run whose bearing the grid resolves to within a bin's half-width, in km.
     *
     * A run of length `L` whose two ends lie within one lattice spacing `p` of a line at the grid's
     * bearing is indistinguishable, on this grid, from lying on it, so its bearing is known only to
     * `atan(p / L)`. Counting a shorter run would let the raster itself move bearings from a
     * neighbour's bin into the grid's — which is exactly the excess being looked for — so a run
     * counts toward a bearing's bins only when `atan(p / L)` is within the half-width.
     */
    fun shortestResolvedRunKm(frame: GridFrame, bearingIndex: Int): Double =
        frame.latticeSpacingKm[bearingIndex] / tan(Math.toRadians(BIN_HALF_WIDTH_DEGREES))

    /**
     * The fewest blocks at which the test can bind at all.
     *
     * If each block carried one run, the central bin's share would be binomial with a third under
     * the null and `E / (E + 2)` at the effect size `E`. The smallest count at which a share of
     * exactly the effect size would clear [z] standard errors of the null is
     * `(z * sqrt(p0 * (1 - p0)) / (pE - p0))^2`; below it, even a stamp at the effect size could
     * not be told from sampling spread, so the outcome is insufficient rather than clean.
     */
    fun minimumBlocks(z: Double): Int {
        val atEffect = EFFECT_RATIO / (EFFECT_RATIO + 2.0)
        val spread = z * sqrt(NULL_CENTRAL_SHARE * (1 - NULL_CENTRAL_SHARE))
        val needed = spread / (atEffect - NULL_CENTRAL_SHARE)
        return ceil(needed * needed).toInt()
    }

    /**
     * The four grid bearings' results for one layer's [runs], at the level a census of
     * [familySize] tests needs. [seed] fixes the bootstrap so a rerun reads the same figures.
     */
    fun measure(
        runs: List<Run>,
        frame: GridFrame,
        familySize: Int,
        seed: Long = 1L,
        tracedTwice: Boolean = false
    ): List<Result> {
        val z = Statistics.zFor(familySize)
        val minimum = minimumBlocks(z)
        // A partition traces every border once from each side, so each piece of border is two
        // blocks that always agree: the count of independent blocks is half, and the spread of
        // the bootstrap, which took them as independent, is too narrow by the square root of two.
        val duplication = if (tracedTwice) 2 else 1
        return frame.gridBearings.indices.map { index ->
            val gridBearing = frame.gridBearings[index]
            val shortest = shortestResolvedRunKm(frame, index)
            // Central and flank length per block.
            val central = HashMap<Long, Double>()
            val flank = HashMap<Long, Double>()
            for (run in runs) {
                if (run.lengthKm < shortest) continue
                val gap = frame.bearingGapDegrees(run.bearingDegrees, gridBearing)
                if (gap > 3 * BIN_HALF_WIDTH_DEGREES) continue
                val block = (run.outline.toLong() shl 32) or (run.order / BLOCK_RUNS).toLong()
                if (gap <= BIN_HALF_WIDTH_DEGREES) {
                    central[block] = (central[block] ?: 0.0) + run.lengthKm
                } else {
                    flank[block] = (flank[block] ?: 0.0) + run.lengthKm
                }
            }
            val blocks = (central.keys + flank.keys).toList()
            val centralOf = DoubleArray(blocks.size) { central[blocks[it]] ?: 0.0 }
            val flankOf = DoubleArray(blocks.size) { flank[blocks[it]] ?: 0.0 }
            val centralKm = centralOf.sum()
            val flankKm = flankOf.sum()
            fun ratioOf(c: Double, f: Double): Double = (c + shortest) / ((f + 2 * shortest) / 2.0)
            val ratio = ratioOf(centralKm, flankKm)

            val lower: Double
            if (blocks.size < 2) {
                lower = 0.0
            } else {
                val random = Random(seed * 31 + index)
                val logs = DoubleArray(BOOTSTRAP_RESAMPLES) {
                    var c = 0.0
                    var f = 0.0
                    repeat(blocks.size) {
                        val pick = random.nextInt(blocks.size)
                        c += centralOf[pick]
                        f += flankOf[pick]
                    }
                    ln(ratioOf(c, f))
                }
                val spread = Statistics.standardDeviation(logs) * kotlin.math.sqrt(duplication.toDouble())
                lower = exp(ln(ratio) - z * spread)
            }
            val outcome = when {
                blocks.size / duplication < minimum -> Outcome.INSUFFICIENT
                ratio >= EFFECT_RATIO && lower > 1.0 -> Outcome.VIOLATION
                else -> Outcome.CLEAN
            }
            Result(index, gridBearing, centralKm / duplication, flankKm / duplication, ratio, lower, blocks.size / duplication, minimum, outcome)
        }
    }
}
