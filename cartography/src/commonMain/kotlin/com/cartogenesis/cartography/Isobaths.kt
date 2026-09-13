package com.cartogenesis.cartography

import kotlin.math.abs
import kotlin.math.floor

/**
 * Depth contours in the sea, drawn per pixel.
 *
 * Every one of these styles paints the sea as a depth ramp and then leaves it at that, which tells
 * a reader that it gets deeper somewhere over there without telling them how much or how fast. A
 * chart says both with lines, and lines cost nothing here: the depth is a field the raster already
 * reads, so an isobath is the set of pixels where that field crosses a multiple of the interval.
 *
 * Two rules make it a drawing rather than an aliasing pattern, and both are the ones a contour sheet
 * is generalised by:
 *
 *  - **A line is a fixed width in pixels**, not a fixed depth band. The distance to the nearest
 *    contour is measured in metres of depth and then divided by how fast the depth changes here, so
 *    a line across a flat basin is the same weight as one down a slope, rather than a broad smear on
 *    the one and nothing at all on the other.
 *  - **Lines that crowd are dropped.** Where the floor falls away steeply the contours pack closer
 *    than the eye can separate; below [CROWDED_PIXELS] apart they fade out, so the continental slope
 *    reads as a slope rather than as a moiré.
 *
 * The reference implementation: the compute shader carries a copy, as it does of [Engraving].
 */
internal object Isobaths {

    /**
     * Metres of depth between one contour and the next.
     *
     * GEBCO's small-scale sheets are contoured every 500 m below the shelf, and at the generator's
     * default 6,000 m of relief that is twelve lines between the shore and the deepest floor —
     * enough that a basin has a shape and few enough that the sea is still water rather than a
     * target. The shallowest of them sits below the shelf break, so no contour ever doubles the
     * coastline.
     */
    const val INTERVAL_METRES: Float = 500f

    /** Half the thickness of one line, in output pixels. */
    private const val HALF_WIDTH_PIXELS = 0.5f

    /**
     * How wide the soft edge of a line is, in pixels.
     *
     * The same figure the engraving draws its lines with, and for the same two reasons: a hard
     * one-bit edge reads as a dither rather than as a line, and a hard threshold on a divide flips
     * whole pixels where two hardwares round differently, where a ramp moves them by one step.
     */
    private const val ANTIALIAS_PIXELS = EngravingPlan.ANTIALIAS_CELLS

    /**
     * How close two contours may run before they stop reading as two, in output pixels.
     *
     * Four, which is where a pair of one-pixel lines with a soft edge each still has paper between
     * them. Below it the pair is faded out over the same distance again, so a slope loses its
     * contours gradually rather than at a line of its own.
     */
    private const val CROWDED_PIXELS = 4f

    /** Stops the arithmetic dividing by zero on a dead-flat floor, which carries no contour at all. */
    private const val FLATTEST_SLOPE = 1e-6f

    /** The interval as a fraction of the depth the elevation field's -1 stands for. */
    fun interval(maxAltitudeMetres: Float): Float = INTERVAL_METRES / maxAltitudeMetres

    /**
     * How strongly a sea pixel takes the contour ink, 0 for open water and 1 for the middle of a
     * line.
     *
     * [depth] is 0 at the shoreline and 1 at the deepest floor the field can hold; [slopePerPixel]
     * is how much of that depth a step of one pixel covers here; [interval] comes from [interval].
     */
    fun ink(depth: Float, slopePerPixel: Float, interval: Float): Float {
        if (depth <= 0f) return 0f
        val run = if (slopePerPixel < FLATTEST_SLOPE) FLATTEST_SLOPE else slopePerPixel

        // The whole number part names the contour above this pixel and the fraction says how far
        // past it the floor has fallen; whichever of the two lines around it is nearer is the one
        // this pixel might be part of.
        val steps = depth / interval
        val pastLine = steps - floor(steps)
        val stepsFromLine = 0.5f - abs(pastLine - 0.5f)
        val pixelsFromLine = stepsFromLine * interval / run
        val pixelsBetweenLines = interval / run

        val line = 1f - Engraving.smoothstep(
            HALF_WIDTH_PIXELS - ANTIALIAS_PIXELS,
            HALF_WIDTH_PIXELS + ANTIALIAS_PIXELS,
            pixelsFromLine
        )
        val legible = Engraving.smoothstep(
            CROWDED_PIXELS * 0.5f, CROWDED_PIXELS, pixelsBetweenLines
        )
        return line * legible
    }
}
