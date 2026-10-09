package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Spreads a field of rates over the ground around each cell without making or losing any of it:
 * the area-weighted sum over the sphere is the same after as before.
 *
 * A box blur on the map's grid does neither of those things. It treats every cell alike, and a
 * cell at 60 degrees is half the ground of one at the equator, so a blur across rows moves rain
 * from small cells into large ones and the planet's total changes; and its radius is a count of
 * cells, so the same blur spreads twice as far east-west on the ground at 60 degrees as at the
 * equator. This one is a Gaussian of one width on the ground everywhere, built from two passes
 * that each conserve exactly:
 *
 * - **Along a row**, three box passes whose radius in cells is the row's own: every cell of a row
 *   is the same ground, and a box that wraps round the row moves nothing out of it.
 * - **Across rows**, diffusion in flux form: what leaves one row through the boundary it shares
 *   with the next arrives in the next, weighted by the length of that boundary, `cos` of its
 *   latitude, so the area-weighted sum is untouched and the pole, which has no boundary, is
 *   closed.
 *
 * Three box passes and four implicit diffusion steps are both close to a Gaussian, so the kernel
 * is near round on the ground and has no preferred bearing (conventions rule 13).
 */
internal object SphereBlur {

    /** Box passes along a row: three is where a box's sum is within a few percent of a Gaussian. */
    private const val ROW_PASSES = 3

    /**
     * Implicit diffusion steps across rows. One backward step spreads a spike into a two-sided
     * exponential; the sum of four is within a few percent of a Gaussian of the same variance, as
     * the three box passes along the row are, so the kernel is near round on the ground.
     */
    private const val ROW_DIFFUSION_STEPS = 4

    /**
     * Spreads [field] in place with a Gaussian of standard deviation [sigmaKm] on the ground.
     * [field] is a rate per unit area, one entry per cell, row-major, on [config]'s grid; a
     * [sigmaKm] of zero or less leaves it untouched.
     */
    fun apply(config: WorldGenConfig, field: FloatField, sigmaKm: Double) {
        if (sigmaKm <= 0.0) return
        val cellsAcross = config.width
        val cellsDown = config.height
        val cellWidthKm = config.cellWidthKm
        val cellHeightKm = config.cellHeightKm
        val cosRow = DoubleArray(cellsDown) { cos(ClimateStage.latitudeOf(it, cellsDown) * PI / 180.0) }
        val data = field.data

        // Along each row: three box passes have a variance of r(r+1) cells², so the radius is the
        // one whose variance is nearest the target in this row's cells.
        parallelChunks(0, cellsDown) { startRow, endRow ->
            val row = DoubleArray(cellsAcross)
            val scratch = DoubleArray(cellsAcross)
            for (rowIndex in startRow until endRow) {
                val groundWidthKm = cellWidthKm * cosRow[rowIndex]
                val varianceCells = (sigmaKm / groundWidthKm).let { it * it }
                val radius = (sqrt(varianceCells + 0.25) - 0.5).roundToInt()
                if (radius <= 0) continue
                val rowStart = rowIndex * cellsAcross
                for (column in 0 until cellsAcross) row[column] = data[rowStart + column].toDouble()
                if (2 * radius + 1 >= cellsAcross) {
                    // Wider than the row itself: every pass averages the whole circle.
                    val mean = row.sum() / cellsAcross
                    for (column in 0 until cellsAcross) data[rowStart + column] = mean.toFloat()
                    continue
                }
                repeat(ROW_PASSES) { boxAlongRow(row, scratch, radius) }
                for (column in 0 until cellsAcross) data[rowStart + column] = row[column].toFloat()
            }
        }

        // Across rows: backward-Euler steps of the flux-form diffusion, whose variances add to the
        // target. Each is one tridiagonal solve per column, stable at any step and conservative
        // because every boundary's flux enters the two rows beside it with opposite signs.
        val varianceRows = (sigmaKm / cellHeightKm).let { it * it }
        val diffusion = varianceRows / (2.0 * ROW_DIFFUSION_STEPS)
        // The boundary between row r and row r + 1, at that row's southern edge.
        val cosBoundary = DoubleArray(cellsDown - 1) { boundary ->
            cos((90.0 - 180.0 * (boundary + 1) / cellsDown) * PI / 180.0)
        }
        parallelChunks(0, cellsAcross) { startColumn, endColumn ->
            val column = DoubleArray(cellsDown)
            val upper = DoubleArray(cellsDown)
            for (columnIndex in startColumn until endColumn) {
                for (row in 0 until cellsDown) column[row] = data[row * cellsAcross + columnIndex].toDouble()
                repeat(ROW_DIFFUSION_STEPS) {
                    var previousUpper = 0.0
                    var previousSolved = 0.0
                    for (row in 0 until cellsDown) {
                        val north = if (row > 0) diffusion * cosBoundary[row - 1] / cosRow[row] else 0.0
                        val south = if (row < cellsDown - 1) diffusion * cosBoundary[row] / cosRow[row] else 0.0
                        val denominator = 1.0 + north + south + north * previousUpper
                        upper[row] = -south / denominator
                        previousSolved = (column[row] + north * previousSolved) / denominator
                        column[row] = previousSolved
                        previousUpper = upper[row]
                    }
                    for (row in cellsDown - 2 downTo 0) column[row] -= upper[row] * column[row + 1]
                }
                for (row in 0 until cellsDown) data[row * cellsAcross + columnIndex] = column[row].toFloat()
            }
        }
    }

    /** One wrapping box pass of [radius] cells over [row], in place, [scratch] as working space. */
    private fun boxAlongRow(row: DoubleArray, scratch: DoubleArray, radius: Int) {
        val cells = row.size
        val width = 2 * radius + 1
        var sum = 0.0
        for (offset in -radius..radius) sum += row[(offset % cells + cells) % cells]
        for (column in 0 until cells) {
            scratch[column] = sum / width
            sum -= row[((column - radius) % cells + cells) % cells]
            sum += row[(column + radius + 1) % cells]
        }
        scratch.copyInto(row)
    }
}
