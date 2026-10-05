package com.cartogenesis.cartography

/**
 * The nib a river is drawn with.
 *
 * A drawn river is not its width to scale. The Amazon's mouth is about ten kilometers of channel,
 * which on a world twelve thousand kilometers round (`WorldScale.worldWidthKm`) is 0.08% of the
 * width and, on a sheet a thousand pixels across, less than one pixel: draw a river honestly and
 * the greatest river on the map disappears. What a cartographer does instead is exaggerate it,
 * roughly threefold for a river of that class, and that is the figure this pen is: a stroke
 * [FULL_STROKE_KM] wide on the ground for the biggest river on the map.
 *
 * So the full stroke is a *width on the ground*, not a count of pixels. Held at a fixed five
 * pixels it looked right at 2048 and twice too heavy at 1024 — the same country, the same rivers,
 * half the sheet, the same ink. A width on the ground gives the same picture at every size: two
 * and a half pixels at 1024 across the 12,000 km world, five at 2048, ten at 4096, and a map
 * printed twice as large has rivers twice as wide, exactly as a map printed twice as large has
 * everything else; and a planet three times as large has a river a third as wide on the same
 * sheet, as it has a coast a third as long. See docs/DESIGN_LEDGER.md, F15 and K1.
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
     * The widest stroke, at the mouth of the biggest river on the map, as a width on the ground in
     * kilometers.
     *
     * The Amazon's ten-kilometer mouth, exaggerated about threefold as a printed map exaggerates a
     * river of that class, is 30 km. The five pixels on a 2048 sheet this pen replaced is 0.244%
     * of the 12,000 km world's width, 29.3 km, arrived at by eye and agreeing to the second digit.
     * 28.8 km is the 0.24% of that width the pen was held at as a share of the map until K1.
     */
    const val FULL_STROKE_KM: Double = 28.8

    /**
     * The widest stroke on a sheet [mapWidthPixels] across a world [worldWidthKm] round, never
     * finer than the hairline.
     */
    fun fullPixels(mapWidthPixels: Int, worldWidthKm: Double): Float =
        (mapWidthPixels * (FULL_STROKE_KM / worldWidthKm).toFloat()).coerceAtLeast(HAIRLINE_PIXELS)

    /**
     * The stroke for a point whose [com.cartogenesis.worldgen.pipeline.River.widthRatio] is
     * [widthRatio], on a sheet [mapWidthPixels] across a world [worldWidthKm] round.
     */
    fun widthPixels(widthRatio: Float, mapWidthPixels: Int, worldWidthKm: Double): Float =
        HAIRLINE_PIXELS +
            (fullPixels(mapWidthPixels, worldWidthKm) - HAIRLINE_PIXELS) * widthRatio.coerceIn(0f, 1f)
}
