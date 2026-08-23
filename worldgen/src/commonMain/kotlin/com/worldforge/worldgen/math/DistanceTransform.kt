package com.worldforge.worldgen.math

import kotlin.math.sqrt

/**
 * Two-pass chamfer distance transform with nearest-source label propagation, O(width * height).
 * The X axis wraps.
 */
object DistanceTransform {

    const val INFINITE = 1e18f

    private val DIAGONAL = sqrt(2.0).toFloat()

    /**
     * @param dist pre-seeded with 0 at source cells and [INFINITE] elsewhere; overwritten with
     *   the distance to the nearest source.
     * @param label pre-seeded with a source id at source cells and -1 elsewhere; overwritten with
     *   the id of the nearest source.
     */
    fun run(width: Int, height: Int, dist: FloatArray, label: IntArray) {
        // Two sweeps: the second lets information cross the east-west seam, which a single
        // forward/backward pair cannot do.
        repeat(2) {
            forward(width, height, dist, label)
            backward(width, height, dist, label)
        }
    }

    private fun forward(width: Int, height: Int, dist: FloatArray, label: IntArray) {
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                relax(width, dist, label, i, x - 1, y, 1f)
                if (y > 0) {
                    relax(width, dist, label, i, x, y - 1, 1f)
                    relax(width, dist, label, i, x - 1, y - 1, DIAGONAL)
                    relax(width, dist, label, i, x + 1, y - 1, DIAGONAL)
                }
            }
        }
    }

    private fun backward(width: Int, height: Int, dist: FloatArray, label: IntArray) {
        for (y in height - 1 downTo 0) {
            for (x in width - 1 downTo 0) {
                val i = y * width + x
                relax(width, dist, label, i, x + 1, y, 1f)
                if (y < height - 1) {
                    relax(width, dist, label, i, x, y + 1, 1f)
                    relax(width, dist, label, i, x + 1, y + 1, DIAGONAL)
                    relax(width, dist, label, i, x - 1, y + 1, DIAGONAL)
                }
            }
        }
    }

    private fun relax(
        width: Int,
        dist: FloatArray,
        label: IntArray,
        target: Int,
        nx: Int,
        ny: Int,
        cost: Float
    ) {
        var wx = nx % width
        if (wx < 0) wx += width
        val n = ny * width + wx
        val candidate = dist[n] + cost
        if (candidate < dist[target]) {
            dist[target] = candidate
            label[target] = label[n]
        }
    }
}
