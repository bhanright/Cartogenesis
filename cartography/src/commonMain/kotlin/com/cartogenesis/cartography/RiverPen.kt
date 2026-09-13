package com.cartogenesis.cartography

/**
 * The nib a river is drawn with.
 *
 * A drawn river is not its width to scale. The Amazon's mouth is about ten kilometres of channel on
 * a world twelve thousand kilometres round (`NationsConfig.worldWidthKm`), which is 0.08% of the
 * width and, on a sheet a thousand pixels across, less than one pixel: draw a river honestly and
 * the greatest river on the map disappears. What a cartographer does instead is exaggerate it,
 * roughly threefold for a river of that class, and that is the figure this pen is: a quarter of a
 * percent of the width of the sheet for the biggest river on it.
 *
 * So the full stroke is a *share of the map*, not a count of pixels. Held at a fixed five pixels it
 * looked right at 2048 and twice too heavy at 1024 — the same country, the same rivers, half the
 * sheet, the same ink. A share gives the same picture at every size: two and a half pixels at 1024,
 * five at 2048, ten at 4096, and a map printed twice as large has rivers twice as wide, exactly as
 * a map printed twice as large has everything else. See REALISM_PLAN.md, F15.
 *
 * The hairline is the exception and stays in pixels, because it is not a width at all — it is the
 * finest mark a nib can leave, and on a bigger sheet the smallest channel is still the smallest
 * channel. The engraving's hachures are the same kind of thing and stay in pixels for the same
 * reason; see [Engraving].
 *
 * Between the two ends the stroke follows the shape of Leopold and Maddock's law — width going as
 * the square root of discharge, which `RiverWidth` in `:worldgen` turns into a ratio in 0..1. No
 * pen can span the law itself: a trunk carrying a thousand times a headwater's water is thirty
 * times its width, and below a certain stroke there is no line at all.
 */
object RiverPen {

    /**
     * The finest line, drawn for the smallest channel on the map, in output pixels.
     *
     * Under a pixel, so it comes out as a grey thread rather than a black one: a headwater is a
     * hint that water starts here, and it should not read as firmly as the coast beside it.
     */
    const val HAIRLINE_PIXELS: Float = 0.8f

    /**
     * The widest stroke, at the mouth of the biggest river on the map, as a share of the map width.
     *
     * The Amazon's ten-kilometre mouth is 0.083% of a twelve-thousand-kilometre world; a printed
     * map exaggerates a river of that class about threefold, which is 0.25%. The five pixels on a
     * 2048 sheet this replaced is 0.244% of the same width, arrived at by eye and agreeing to the
     * second digit. Both round to the figure used here.
     */
    const val FULL_SHARE_OF_MAP_WIDTH: Float = 0.0024f

    /** The widest stroke on a sheet [mapWidthPixels] across, never finer than the hairline. */
    fun fullPixels(mapWidthPixels: Int): Float =
        (mapWidthPixels * FULL_SHARE_OF_MAP_WIDTH).coerceAtLeast(HAIRLINE_PIXELS)

    /**
     * The stroke for a point whose [com.cartogenesis.worldgen.pipeline.River.widthRatio] is
     * [widthRatio], on a sheet [mapWidthPixels] across.
     */
    fun widthPixels(widthRatio: Float, mapWidthPixels: Int): Float =
        HAIRLINE_PIXELS + (fullPixels(mapWidthPixels) - HAIRLINE_PIXELS) * widthRatio.coerceIn(0f, 1f)
}
