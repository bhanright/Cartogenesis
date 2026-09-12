package com.cartogenesis.worldgen.math

import com.cartogenesis.worldgen.concurrent.parallelChunks
import kotlin.math.sqrt

/**
 * Euclidean distance to the nearest source cell, with nearest-source label propagation, by jump
 * flooding. The X axis wraps; the Y axis does not, exactly as [DistanceTransform] behaves.
 *
 * This is the drop-in replacement for the chamfer transform wherever the *distance itself* is
 * read — continentality's distance from water, the shelf's distance from land, the boundary
 * profiles' distance from a plate edge. A chamfer transform can only step along the eight
 * directions a square grid offers, so what it measures is an octagonal metric: it agrees with
 * Euclid along the axes and along the diagonals and overstates it by up to 8.2% at the bearings
 * in between (22.5 degrees, where a straight line of length 1 costs `(1 - t) + sqrt(2) * t`
 * to walk). Every contour of such a field is therefore an octagon rather than a circle, and every
 * feature whose width is read off that field — a plateau's rim, the shelf break — inherits the
 * facets. They are what `TODO.md` calls the octagonal streaks.
 *
 * Jump flooding propagates *the source's coordinates* rather than an accumulated path length, so
 * the distance it reports is `sqrt(dx^2 + dy^2)` to a real cell: no metric error at all, only the
 * risk that a cell never hears about the source that is actually nearest. That risk is what the
 * halving step schedule addresses — a cell looks at its eight neighbours `step` cells away, then
 * `step / 2`, and so on — and the schedule here brackets the standard one with a step-1 pass at
 * each end (the "1+JFA+1" variant), which repairs the thin diagonal wedges plain JFA is known to
 * leave. `JumpFloodDistanceTest` checks the result against a brute-force nearest-source search on
 * randomly seeded grids and finds it exact, not merely close.
 *
 * Deterministic and platform-independent: the whole search is integer arithmetic on squared
 * distances, ties go to the lower source index, and each pass reads the previous buffer and writes
 * its own cell, so splitting the rows across cores cannot change the answer.
 */
object JumpFloodDistance {

    /** Same sentinel [DistanceTransform] uses, so a call site swaps one for the other unchanged. */
    const val INFINITE = 1e18f

    /**
     * @param dist pre-seeded with 0 at source cells and [INFINITE] elsewhere; overwritten with
     *   the distance to the nearest source. Cells with no source anywhere keep [INFINITE].
     * @param label pre-seeded with a source id at source cells and -1 elsewhere; overwritten with
     *   the id of the nearest source. The id is whatever the caller seeded — a cell index, a plate
     *   id — and is carried, not recomputed.
     */
    fun run(width: Int, height: Int, dist: FloatArray, label: IntArray) {
        val n = width * height
        if (n == 0) return

        // The labels as the caller seeded them. The flood itself moves cell indices, because it
        // needs the source's coordinates to measure to; the label is looked up at the end.
        val seedLabel = label.copyOf()

        var src = IntArray(n) { if (dist[it] == 0f) it else -1 }
        var any = false
        for (i in 0 until n) {
            if (src[i] >= 0) { any = true; break }
        }
        if (!any) return

        var dst = IntArray(n)
        for (step in schedule(width, height)) {
            pass(width, height, step, src, dst)
            val swap = src
            src = dst
            dst = swap
        }

        for (i in 0 until n) {
            val s = src[i]
            if (s < 0) continue
            dist[i] = sqrt(squaredDistance(width, i % width, i / width, s).toDouble()).toFloat()
            label[i] = seedLabel[s]
        }
    }

    /**
     * A step-1 pass, then the halving powers of two, then a second step-1 pass.
     *
     * The powers start at the first one at or above half the larger side, which is what makes the
     * flood reach across the whole grid; the two step-1 passes at the ends are cheap insurance
     * against the small errors the bare schedule leaves.
     */
    private fun schedule(width: Int, height: Int): IntArray {
        var start = 1
        while (start < maxOf(width, height) / 2) start = start shl 1
        var count = 2
        var s = start
        while (s >= 1) { count++; s = s shr 1 }
        val out = IntArray(count)
        var index = 0
        out[index++] = 1
        s = start
        while (s >= 1) { out[index++] = s; s = s shr 1 }
        out[index] = 1
        return out
    }

    private fun pass(width: Int, height: Int, step: Int, src: IntArray, dst: IntArray) {
        // Reads `src`, writes only its own cell of `dst`, so the rows split cleanly and the
        // result does not depend on how they were split.
        parallelChunks(0, height) { startY, endY ->
            for (y in startY until endY) {
                for (x in 0 until width) {
                    val i = y * width + x
                    var best = src[i]
                    var bestD = if (best < 0) Int.MAX_VALUE else squaredDistance(width, x, y, best)
                    for (oy in -1..1) {
                        val ny = y + oy * step
                        if (ny < 0 || ny >= height) continue
                        val row = ny * width
                        for (ox in -1..1) {
                            if (ox == 0 && oy == 0) continue
                            var nx = (x + ox * step) % width
                            if (nx < 0) nx += width
                            val candidate = src[row + nx]
                            if (candidate < 0) continue
                            val d = squaredDistance(width, x, y, candidate)
                            // Ties to the lower cell index: two sources exactly as far away is
                            // common on a grid, and which one wins decides the label.
                            if (d < bestD || (d == bestD && candidate < best)) {
                                bestD = d
                                best = candidate
                            }
                        }
                    }
                    dst[i] = best
                }
            }
        }
    }

    /** Squared distance from (x, y) to cell [source], taking the short way round in x. */
    private fun squaredDistance(width: Int, x: Int, y: Int, source: Int): Int {
        var dx = x - source % width
        if (dx < 0) dx = -dx
        if (dx > width - dx) dx = width - dx
        val dy = y - source / width
        return dx * dx + dy * dy
    }
}
