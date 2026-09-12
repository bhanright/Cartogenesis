package com.cartogenesis.worldgen.math

import kotlin.math.sqrt

/**
 * Two-pass chamfer distance transform with nearest-source label propagation, O(width * height).
 * The X axis wraps.
 *
 * What it measures is an octagonal metric, not Euclid: a walk over the grid that may only step
 * along the eight directions a cell has, costing 1 along an axis and sqrt(2) along a diagonal.
 * That agrees with the straight-line distance on those eight bearings and overstates it by up to
 * 8.2% in between, so every contour of the field is an octagon. Every consumer that reads the
 * *distance* has moved to [JumpFloodDistance], which has no metric error at all; what is left here
 * is [com.cartogenesis.worldgen.pipeline.PlateStage]'s plate assignment, where only the
 * nearest-seed *label* is read and the metric decides nothing anybody can see, and the control in
 * `JumpFloodDistanceTest` that shows the faceting guard has teeth. See REALISM_PLAN.md, G4.
 */
object DistanceTransform {

    /** Distance of a cell no source has reached. Same sentinel [JumpFloodDistance] uses. */
    const val INFINITE = 1e18f

    /** Cost of a diagonal step, against 1 for a step along an axis. */
    private val DIAGONAL = sqrt(2.0).toFloat()

    /**
     * Forward-and-backward sweep pairs.
     *
     * Two rather than one: a single pair propagates left-to-right then right-to-left, and neither
     * direction can carry a source across the east-west seam and back again in the same pass. The
     * second pair starts from a field that already has the seam's near side filled, so information
     * crosses. A third changes nothing, the field being settled by then.
     */
    private const val SEAM_CROSSING_SWEEPS = 2

    /**
     * @param distance pre-seeded with 0 at source cells and [INFINITE] elsewhere; overwritten with
     *   the octagonal distance to the nearest source, in cells.
     * @param label pre-seeded with a source id at source cells and -1 elsewhere; overwritten with
     *   the id of the nearest source.
     */
    fun run(width: Int, height: Int, distance: FloatArray, label: IntArray) {
        repeat(SEAM_CROSSING_SWEEPS) {
            forward(width, height, distance, label)
            backward(width, height, distance, label)
        }
    }

    /** North-west to south-east, so a cell hears from the four neighbours already settled. */
    private fun forward(width: Int, height: Int, distance: FloatArray, label: IntArray) {
        for (row in 0 until height) {
            for (column in 0 until width) {
                val cell = row * width + column
                relax(width, distance, label, cell, column - 1, row, 1f)
                if (row > 0) {
                    relax(width, distance, label, cell, column, row - 1, 1f)
                    relax(width, distance, label, cell, column - 1, row - 1, DIAGONAL)
                    relax(width, distance, label, cell, column + 1, row - 1, DIAGONAL)
                }
            }
        }
    }

    /** South-east to north-west, covering the four neighbours [forward] could not reach. */
    private fun backward(width: Int, height: Int, distance: FloatArray, label: IntArray) {
        for (row in height - 1 downTo 0) {
            for (column in width - 1 downTo 0) {
                val cell = row * width + column
                relax(width, distance, label, cell, column + 1, row, 1f)
                if (row < height - 1) {
                    relax(width, distance, label, cell, column, row + 1, 1f)
                    relax(width, distance, label, cell, column + 1, row + 1, DIAGONAL)
                    relax(width, distance, label, cell, column - 1, row + 1, DIAGONAL)
                }
            }
        }
    }

    /**
     * Takes [target] the neighbour's source instead of its own, if reaching it through that
     * neighbour at [stepCost] is shorter than the distance [target] holds now.
     */
    private fun relax(
        width: Int,
        distance: FloatArray,
        label: IntArray,
        target: Int,
        neighbourColumn: Int,
        neighbourRow: Int,
        stepCost: Float
    ) {
        var wrappedColumn = neighbourColumn % width
        if (wrappedColumn < 0) wrappedColumn += width
        val neighbour = neighbourRow * width + wrappedColumn
        val throughNeighbour = distance[neighbour] + stepCost
        if (throughNeighbour < distance[target]) {
            distance[target] = throughNeighbour
            label[target] = label[neighbour]
        }
    }
}
