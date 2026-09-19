package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldScale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Depth contours in the sea, drawn per pixel.
 *
 * Every one of these styles paints the sea as a depth ramp and then leaves it at that, which tells
 * a reader that it gets deeper somewhere over there without telling them how much or how fast. A
 * chart says both with lines, and lines cost nothing here: the depth is a field the raster already
 * reads, so an isobath is the set of pixels where that field crosses a multiple of the interval.
 *
 * Three rules make it a drawing rather than an aliasing pattern, and each is one a contour sheet is
 * generalised by:
 *
 *  - **A line is a fixed width in pixels**, not a fixed depth band. The distance to the nearest
 *    contour is measured in metres of depth and then divided by how fast the depth changes here, so
 *    a line across a flat basin is the same weight as one down a slope, rather than a broad smear on
 *    the one and nothing at all on the other.
 *  - **Lines that crowd are dropped.** Where the floor falls away steeply the contours pack closer
 *    than the eye can separate; below [CROWDED_PIXELS] apart they fade out, so the continental slope
 *    reads as a slope rather than as a moiré.
 *  - **Nothing is drawn on a plain.** A contour is a line only where the floor crosses the level
 *    once. On an abyssal plain the floor wanders either side of a level over a whole basin, so the
 *    same arithmetic draws a ragged nest of closed loops through the middle of open water, saying
 *    nothing and looking like a stain, which is what a render review found in an open basin at
 *    2048. Below [ABYSSAL_PLAIN_GRADIENT] the contour fades out. See docs/DESIGN_LEDGER.md, F13.
 *
 * How fast the floor falls is measured over [slopeStencil] rather than between two neighbouring
 * cells, and that is the other half of the same fix: a generated sea floor is rough at cell scale,
 * so the difference between two neighbours is mostly that roughness. A line whose width is set by
 * it is wider than the floor's own fall deserves, and a plain measured that way reads as a slope.
 * Over a short distance of *ground* the roughness averages out and what is left is the fall.
 *
 * The reference implementation: the compute shader carries a copy, as it does of [Engraving].
 *
 * Public, unlike the engraving beside it, because the rule is a fact about the sea floor rather
 * than about this drawing's pen: `ClimateReliefGalleryTest` in `:desktop` measures the same
 * contours on a 2048 world, which is the size the defect that produced [ABYSSAL_PLAIN_GRADIENT] was
 * found at.
 */
object Isobaths {

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

    /**
     * The gradient at which a sea floor stops being a slope and becomes a plain: one in a thousand.
     *
     * The definition of an abyssal plain, and not a figure of this drawing's own: Heezen, Tharp and
     * Ewing set the physiographic province at a floor descending less than a metre in a kilometre,
     * and that is the flattest natural surface on Earth. It is also, for the same reason, the
     * gradient below which a contour stops describing anything — the floor's own roughness is then
     * larger than its fall between one contour and the next, so the level set is a region rather
     * than a line.
     */
    const val ABYSSAL_PLAIN_GRADIENT: Float = 0.001f

    /** Over how much of an octave below that the contour fades out rather than stopping dead. */
    private const val PLAIN_FADE_FROM = 0.5f

    /**
     * How far the central difference that measures the floor's fall reaches, in cells, at a map
     * [width].
     *
     * Two cells at 512 and eight at 2048 — the same short distance of ground either way, and the
     * same rule the sky's horizon stencil follows, for the same reason: this is a question about
     * the sea floor rather than about the sheet. Between two neighbours the answer would be the
     * floor's roughness at cell scale, which is what drew contours on an abyssal plain.
     */
    fun slopeStencil(width: Int): Int = (SLOPE_STENCIL_AT_512 * width / 512).coerceAtLeast(1)

    private const val SLOPE_STENCIL_AT_512 = 2

    private const val METRES_PER_KILOMETRE = 1000f

    /**
     * The interval as a fraction of the depth the elevation field's -1 stands for.
     *
     * Off the sea's own half of the ruler, [WorldScale.deepestOceanMetres], because a contour on
     * the sea floor is a depth below the shoreline and not a height above it.
     */
    fun interval(scale: WorldScale): Float = scale.depthShareOfMetres(INTERVAL_METRES)

    /**
     * [ABYSSAL_PLAIN_GRADIENT] in the units the raster measures a slope in: field units per pixel.
     *
     * A pixel covers a piece of the world, and how big a piece is what turns a gradient on the sea
     * floor into a difference between two neighbours. Both halves come from the config — how wide
     * the world is taken to be, and how many metres its full elevation range stands for — so the
     * rule is about the sea floor and holds at every resolution: at 2048 the same plain is measured
     * over four times as many cells, each a quarter of the ground, and comes to the same gradient.
     */
    fun flattestSlope(config: WorldGenConfig, width: Int, height: Int): Float {
        val kilometresPerCell = sqrt(config.scale.squareKilometresPerCell(width, height)).toFloat()
        return config.scale.depthShareOfMetres(
            ABYSSAL_PLAIN_GRADIENT * kilometresPerCell * METRES_PER_KILOMETRE
        )
    }

    /**
     * How strongly a sea pixel takes the contour ink, 0 for open water and 1 for the middle of a
     * line.
     *
     * [depth] is 0 at the shoreline and 1 at the deepest floor the field can hold; [slopePerPixel]
     * is how much of that depth a step of one pixel covers here; [interval] comes from [interval]
     * and [flattestSlope] from [flattestSlope], or 0 to draw on the plains as well, which is the
     * control `IsobathTest` measures against.
     */
    fun ink(depth: Float, slopePerPixel: Float, interval: Float, flattestSlope: Float): Float {
        if (depth <= 0f) return 0f
        val onASlope = if (flattestSlope <= 0f) 1f else Engraving.smoothstep(
            flattestSlope * PLAIN_FADE_FROM, flattestSlope, slopePerPixel
        )
        if (onASlope <= 0f) return 0f
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
        return line * legible * onASlope
    }
}
