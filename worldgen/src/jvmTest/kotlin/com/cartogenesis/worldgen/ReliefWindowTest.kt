package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.ReliefWindowShape
import com.cartogenesis.worldgen.pipeline.GlaciationStage
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The glaciation's relief window has no bearing of its own (docs/CONVENTIONS.md, rule 13).
 *
 * A sliding extremum draws its window's outline round every summit: the cells whose window holds
 * one lone summit on a flat plain are the window itself, turned inside out, and that outline is
 * what `channelled`, the sheet mask and every scour basin clipped to it inherit
 * (`GlaciationStage.localRelief`). So the window is read the plainest way there is: one raised
 * cell on a level grid, the region where the relief is above nothing, and that region's radius on
 * the ground along every whole degree of bearing from the summit.
 *
 * The bar is what rasterizing a circle costs and nothing more. A ray leaves a digitized disc
 * somewhere inside the last cell it crosses, so its exit stands within half a cell's diagonal of
 * the true circle either way, and the furthest and nearest exits differ by at most one cell
 * diagonal, `sqrt(1 + h^2)` cell widths for cells `h` cell widths tall, plus the ray's own step.
 * Over the radius that is the spread a round window may show; the octagon's corners stand 8.2%
 * further out than its flats and the square's 41%, and on cells half as tall as wide both, being
 * windows in cells, are twice as wide as tall on the ground.
 *
 * No world is generated: an everyday class that costs a few seconds.
 */
class ReliefWindowTest {

    @Test
    fun `the relief window is round on the ground, and the octagon and the square are not`() {
        val lines = ArrayList<String>()
        val failures = ArrayList<String>()
        for (cellHeightInCellWidths in doubleArrayOf(1.0, 0.5)) {
            for (shape in ReliefWindowShape.values()) {
                val outline = outlineAroundOneSummit(shape, cellHeightInCellWidths)
                val bar = roundnessBar(cellHeightInCellWidths)
                val round = outline.spreadShareOfRadius <= bar
                lines += String.format(
                    Locale.ROOT,
                    "RELIEF WINDOW %s on cells %.1f as tall as wide: radius %.2f to %.2f cell widths" +
                        " on the ground (spread %.2f%% of its mean, bar %.2f%%), the nearest at %d" +
                        " degrees and the furthest at %d",
                    shape, cellHeightInCellWidths, outline.nearest, outline.furthest,
                    outline.spreadShareOfRadius * 100, bar * 100, outline.nearestBearing,
                    outline.furthestBearing
                )
                println(lines.last())
                when (shape) {
                    ReliefWindowShape.DISC -> if (!round) failures += lines.last()
                    // The controls: each has to fail, or the measurement cannot see a bearing.
                    ReliefWindowShape.SQUARE, ReliefWindowShape.OCTAGON ->
                        if (round) failures += "the control passed: " + lines.last()
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private class Outline(
        val nearest: Double,
        val furthest: Double,
        val mean: Double,
        val nearestBearing: Int,
        val furthestBearing: Int
    ) {
        val spreadShareOfRadius: Double get() = (furthest - nearest) / mean
    }

    private fun outlineAroundOneSummit(
        shape: ReliefWindowShape,
        cellHeightInCellWidths: Double,
        radiusCellWidths: Float = RADIUS_CELL_WIDTHS
    ): Outline {
        val rowsAcrossTheWindow = (radiusCellWidths / cellHeightInCellWidths).toInt()
        val cellsAcross = 4 * radiusCellWidths.toInt() + 16
        val cellsDown = 2 * rowsAcrossTheWindow + 16
        val summitColumn = cellsAcross / 2
        val summitRow = cellsDown / 2
        val ground = FloatArray(cellsAcross * cellsDown)
        ground[summitRow * cellsAcross + summitColumn] = 1f
        val relief = GlaciationStage.localRelief(
            cellsAcross, cellsDown, ground, radiusCellWidths, cellHeightInCellWidths, shape
        )
        fun inWindow(eastCellWidths: Double, southCellWidths: Double): Boolean {
            val column = kotlin.math.floor(summitColumn + 0.5 + eastCellWidths).toInt()
            val row = kotlin.math.floor(summitRow + 0.5 + southCellWidths / cellHeightInCellWidths).toInt()
            if (column !in 0 until cellsAcross || row !in 0 until cellsDown) return false
            return relief[row * cellsAcross + column] > 0f
        }
        var nearest = Double.MAX_VALUE
        var furthest = 0.0
        var sum = 0.0
        var nearestBearing = 0
        var furthestBearing = 0
        for (bearing in 0 until 360) {
            val radians = bearing * PI / 180.0
            var reach = 0.0
            while (inWindow(reach * sin(radians), -reach * cos(radians))) reach += RAY_STEP_CELL_WIDTHS
            if (reach < nearest) {
                nearest = reach
                nearestBearing = bearing
            }
            if (reach > furthest) {
                furthest = reach
                furthestBearing = bearing
            }
            sum += reach
        }
        return Outline(nearest, furthest, sum / 360, nearestBearing, furthestBearing)
    }

    /** One cell's diagonal and one ray step, over the radius: see the class's KDoc. */
    private fun roundnessBar(cellHeightInCellWidths: Double): Double =
        (kotlin.math.sqrt(1.0 + cellHeightInCellWidths * cellHeightInCellWidths) + RAY_STEP_CELL_WIDTHS) /
            RADIUS_CELL_WIDTHS

    private companion object {
        /** A window wide enough that a cell's rasterization is a small part of it. */
        const val RADIUS_CELL_WIDTHS = 60f

        /** How far a ray steps between reads, in cell widths. */
        const val RAY_STEP_CELL_WIDTHS = 0.05
    }
}
