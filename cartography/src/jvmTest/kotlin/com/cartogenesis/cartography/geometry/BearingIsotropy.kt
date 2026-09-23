package com.cartogenesis.cartography.geometry

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * Detector 1: whether a layer's straight lines favour the grid's bearings.
 *
 * The null is not that a natural outline has no preferred bearing at all — ice and biome edges
 * follow the latitude, structural valleys follow the plates, and this generator's land runs twice
 * as far east-west as north-south on the ground (docs/GEOGRAPHY.md). A real preference is broad; a
 * grid artefact is a narrow excess at exactly one of the grid's bearings. So each grid bearing's
 * bin is compared with its two immediate neighbours, not with a uniform distribution: the run
 * length lying within [BIN_HALF_WIDTH_DEGREES] of the grid bearing against the mean of the two
 * bins of the same width on either side of it. That ratio is 1 for any preference broad next to
 * the three bins' 24 degrees, whatever its direction. A layer that follows the latitude is the
 * exception, since a zonal line can prefer the east-west bearing as narrowly as the grid would: such
 * a layer is held to the ratio Earth-like zonal lines reach ([ZonalFigures]) in place of 1.
 *
 * Two things must both hold for a violation: the ratio reaches [EFFECT_RATIO], and it is past the
 * sampling spread — its lower confidence bound, at the census's corrected level, is above 1.
 * Significance alone would, over a whole world's coast, reject real geography.
 *
 * The bearings are read off chords of one fixed length laid along each line ([measureChords]):
 * every line sampled every half a cell's height, each sample standing for that much line, and its
 * bearing the chord to the sample [chordKm] further on. A chord's two ends each lie within half a
 * cell of the line the raster stands for, so its bearing is known to `atan(w / chord)` either way,
 * `w` the cell's width across it ([GridFrame.cellAcrossKm]), and each sample's length is spread
 * evenly over that interval before the bins take their shares. At [chordKm], the widest cell
 * resolves a bearing to the bins' half-width. The chord is fixed rather than Douglas-Peucker's
 * run, because on a raster the runs near a grid bearing come out longer than the runs between,
 * and any reading weighted by run — or cut at a length — favours the grid's bearings by itself:
 * `GeometryControlTest` reads both ways on the controls, and the runs put 1.2 to 1.9 times their
 * neighbours' length on the north-south bin of natural outlines and courses where the chords put
 * 0.9 to 1.1. [measure] keeps the runs' reading for that comparison.
 *
 * The sampling spread comes from a block bootstrap, because neighbouring stretches of one line
 * turn together and are not independent trials. A block is as long as the layer's own correlation
 * length ([correlationLengthChords]): the lag at which whether a chord lies in a grid bin stops
 * predicting whether the chord that far on does.
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
     * centred exactly on the grid's bearing — puts 1.35 times its neighbours' length in the
     * central bin at these bin widths (the Gaussian's mean over the central 8 degrees against its
     * mean over the flanks from 4 to 12; 1.58 at a standard deviation of 8 and 1.24 at 12). A ratio
     * of 1.5 needs a preference narrower than a standard deviation of about 8.7 degrees, which no
     * geography gives a whole layer save one that follows the latitude, and that one is held to the
     * zonal control's ratio times this; what is that narrow otherwise is the grid.
     */
    const val EFFECT_RATIO = 1.5

    /** How many consecutive runs along one line make a block, on the runs' reading. */
    const val BLOCK_RUNS = 8

    const val BOOTSTRAP_RESAMPLES = 1000

    private fun overlap(low: Double, high: Double, from: Double, to: Double): Double =
        (minOf(high, to) - maxOf(low, from)).coerceAtLeast(0.0)

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
        val outcome: Outcome,
        /** How many chords long a block was, on the chord reading; 0 on the runs reading. */
        val blockChords: Int = 0
    ) {
        fun withBlockChords(chords: Int) = Result(
            bearingIndex, gridBearingDegrees, centralKm, flankKm, ratio, lowerRatio, blocks, minimumBlocks, outcome, chords
        )

        override fun toString(): String =
            "%.1f deg: %.2fx (lower %.2fx) over %d blocks of %d chords, central %.0f km, flanks %.0f km%s".format(
                gridBearingDegrees, ratio, lowerRatio, blocks, blockChords, centralKm, flankKm,
                if (outcome == Outcome.INSUFFICIENT) " [insufficient: needs $minimumBlocks]" else ""
            )
    }

    /**
     * The shortest run whose bearing the grid resolves to within a bin's half-width, in km: one
     * lattice spacing across the grid's bearing over the tangent of the half-width. One such run's
     * length is added to the central bin and to each flank as a prior, so that a layer with almost
     * nothing near a bearing reads a ratio of about 1 there rather than 0 or infinity.
     */
    fun shortestResolvedRunKm(frame: GridFrame, bearingIndex: Int): Double =
        frame.latticeSpacingKm[bearingIndex] / tan(Math.toRadians(BIN_HALF_WIDTH_DEGREES))

    /**
     * The fewest blocks at which the test can bind at all.
     *
     * If each block carried one run, the central bin's share would be binomial: `r / (r + 2)` at a
     * ratio `r`, so a third under the isotropic null (`r0 = 1`) and `E / (E + 2)` at the effect
     * size `E`. The smallest count at which a share of exactly the effect size over the null would
     * clear [z] standard errors of the null is `(z * sqrt(p0 * (1 - p0)) / (pE - p0))^2`; below
     * it, even a stamp at the effect size could not be told from sampling spread, so the outcome is
     * insufficient rather than clean. [nullRatio] is the ratio the null itself holds, above 1 for a
     * layer judged against the zonal control ([ZonalFigures]).
     */
    fun minimumBlocks(z: Double, nullRatio: Double = 1.0): Int {
        val atNull = nullRatio / (nullRatio + 2.0)
        val atEffect = EFFECT_RATIO * nullRatio / (EFFECT_RATIO * nullRatio + 2.0)
        val spread = z * sqrt(atNull * (1 - atNull))
        val needed = spread / (atEffect - atNull)
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
    ): List<Result> = measurePieces(
        runs.map { run ->
            Piece(
                run.bearingDegrees, run.lengthKm,
                Math.toDegrees(kotlin.math.atan(frame.cellAcrossKm(run.bearingDegrees) / run.lengthKm)),
                (run.outline.toLong() shl 32) or (run.order / BLOCK_RUNS).toLong()
            )
        },
        frame, familySize, seed, tracedTwice
    )

    /**
     * The same test read off chords of one fixed length rather than off Douglas-Peucker's runs:
     * every outline sampled every half a cell's height, and the chord from each sample to the one
     * [chordKm] further along taken as the line's bearing there, for the half-cell of outline it
     * stands for. The chord's ends each lie within half a cell of the line, so its bearing is known
     * to `atan(w / chord)` whatever the outline does, and no partition of the line into runs enters.
     */
    fun measureChords(
        outlines: List<Outline>,
        frame: GridFrame,
        familySize: Int,
        chordKm: Double = chordKm(frame),
        seed: Long = 1L,
        tracedTwice: Boolean = false
    ): List<Result> {
        val stepKm = 0.5 * minOf(frame.cellWidthKm, frame.cellHeightKm)
        val stride = kotlin.math.ceil(chordKm / stepKm).toInt()
        // Every chord's bearing and length, outline by outline, in order along each.
        class Chord(val bearingDegrees: Double, val lengthKm: Double, val at: Int)
        val chordsOf = ArrayList<Pair<Int, List<Chord>>>()
        outlines.forEachIndexed { index, outline ->
            val (xs, ys) = Arcs.resample(outline, stepKm)
            val count = xs.size
            if (count <= stride) return@forEachIndexed
            val last = if (outline.closed) count else count - stride
            val chords = ArrayList<Chord>(last)
            for (at in 0 until last) {
                val to = (at + stride) % count
                if (!outline.closed && to <= at) continue
                val dx = xs[to] - xs[at]
                val dy = ys[to] - ys[at]
                val length = lengthOf(dx, dy)
                if (length <= 0.0) continue
                chords.add(Chord(frame.bearingDegrees(dx, dy), length, at))
            }
            chordsOf.add(index to chords)
        }
        // Blocks as long as the layer's own correlation length: the lag, in whole chords, at which
        // whether a chord lies in one of the grid's bins stops predicting whether the chord that far
        // on does. A smooth zonal line stays correlated far longer than a rough coast, and blocks
        // shorter than that would make its bootstrap spread too narrow.
        val inABin = chordsOf.map { (_, chords) ->
            DoubleArray(chords.size) { at ->
                if (frame.gridBearings.any { frame.bearingGapDegrees(chords[at].bearingDegrees, it) <= BIN_HALF_WIDTH_DEGREES }) 1.0 else 0.0
            }
        }
        val blockChords = correlationLengthChords(inABin, stride)
        val pieces = ArrayList<Piece>()
        for ((index, chords) in chordsOf) for (chord in chords) {
            pieces.add(
                Piece(
                    chord.bearingDegrees, stepKm,
                    Math.toDegrees(kotlin.math.atan(frame.cellAcrossKm(chord.bearingDegrees) / chord.lengthKm)),
                    (index.toLong() shl 32) or (chord.at / (stride * blockChords)).toLong()
                )
            )
        }
        return measurePieces(pieces, frame, familySize, seed, tracedTwice).map { it.withBlockChords(blockChords) }
    }

    /**
     * The least whole number of chords, at least one and at most [LONGEST_BLOCK_CHORDS], at which
     * the autocorrelation of [series] (each sampled every chord over [stride] samples) falls under
     * [DECORRELATED].
     */
    fun correlationLengthChords(series: List<DoubleArray>, stride: Int): Int {
        val count = series.sumOf { it.size }
        if (count == 0) return 1
        val mean = series.sumOf { it.sum() } / count
        val variance = series.sumOf { values -> values.sumOf { (it - mean) * (it - mean) } } / count
        if (variance <= 0.0) return 1
        for (chords in 1..LONGEST_BLOCK_CHORDS) {
            val lag = chords * stride
            var sum = 0.0
            var pairs = 0
            for (values in series) for (at in 0 until values.size - lag) {
                sum += (values[at] - mean) * (values[at + lag] - mean)
                pairs++
            }
            if (pairs == 0 || kotlin.math.abs(sum / pairs / variance) < DECORRELATED) return chords
        }
        return LONGEST_BLOCK_CHORDS
    }

    /** Where a layer's chords count as no longer correlated: an autocorrelation under a tenth. */
    const val DECORRELATED = 0.1

    /**
     * The longest block, in chords: sixteen, beyond which a layer's lines are a handful of blocks
     * each and the count of blocks, not their length, is what limits the test.
     */
    const val LONGEST_BLOCK_CHORDS = 16

    /**
     * The chord the fixed-length reading takes, in km: the length at which a cell's width across a
     * north-south line, the widest the raster is anywhere, resolves a bearing to the bins' half-width.
     */
    fun chordKm(frame: GridFrame): Double =
        maxOf(frame.cellWidthKm, frame.cellHeightKm) / tan(Math.toRadians(BIN_HALF_WIDTH_DEGREES))

    private class Piece(val bearingDegrees: Double, val lengthKm: Double, val uncertaintyDegrees: Double, val block: Long)

    private fun measurePieces(
        pieces: List<Piece>,
        frame: GridFrame,
        familySize: Int,
        seed: Long,
        tracedTwice: Boolean
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
            val half = BIN_HALF_WIDTH_DEGREES
            for (piece in pieces) {
                // Where the piece's bearing lies against the grid's, signed, and how far either way its
                // two ends let it lie: its length is spread evenly over that interval.
                var offset = (piece.bearingDegrees - gridBearing) % 180.0
                if (offset > 90.0) offset -= 180.0
                if (offset < -90.0) offset += 180.0
                val uncertainty = piece.uncertaintyDegrees
                val low = offset - uncertainty
                val high = offset + uncertainty
                if (high < -3 * half || low > 3 * half) continue
                val width = high - low
                val inCentral = overlap(low, high, -half, half) / width
                val inFlanks = (overlap(low, high, -3 * half, -half) + overlap(low, high, half, 3 * half)) / width
                if (inCentral <= 0.0 && inFlanks <= 0.0) continue
                central[piece.block] = (central[piece.block] ?: 0.0) + piece.lengthKm * inCentral
                flank[piece.block] = (flank[piece.block] ?: 0.0) + piece.lengthKm * inFlanks
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
