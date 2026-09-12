package com.cartogenesis.cartography

/**
 * The nib a river is drawn with, in output pixels.
 *
 * In pixels, and for the reason [EngravingPlan] is: a pen does not grow with the sheet. An
 * exported map four times the side of the preview is not the same picture enlarged — it is more of
 * the country at the same scale of line, with four times as many tributaries fine enough to draw.
 * Sizing the stroke as a share of the width instead is what made a 2048 export's trunk rivers
 * eleven pixels across, wide enough to hide the valley they run down.
 *
 * Two figures, and the ratio between them is the whole of the idea. Leopold and Maddock's downstream
 * hydraulic geometry has width going as the square root of discharge, so a trunk carrying a thousand
 * times a headwater's water is some thirty times the headwater's width, and no pen can honestly span
 * that on one sheet: below a certain stroke there is no line at all. What can be kept is the shape
 * of the law — the stroke rising with the square root of the discharge, from the finest line a nib
 * leaves to the widest a river should take on a map that also has to show the land it crosses.
 */
object RiverPen {

    /**
     * The finest line, drawn for the smallest channel on the map.
     *
     * Under a pixel, so it comes out as a grey thread rather than a black one: a headwater is a
     * hint that water starts here, and it should not read as firmly as the coast beside it.
     */
    const val HAIRLINE_PIXELS: Float = 0.8f

    /**
     * The widest, drawn at the mouth of the biggest river on the map.
     *
     * Six times the hairline. A trunk has to be unmistakably a trunk at a glance and from across
     * the room, which is what the whole change is for, and five pixels is about where a line stops
     * reading as a line and starts reading as a strip of water.
     */
    const val FULL_PIXELS: Float = 5f

    /** The stroke for a point whose [com.cartogenesis.worldgen.pipeline.River.widthRatio] is this. */
    fun widthPixels(widthRatio: Float): Float =
        HAIRLINE_PIXELS + (FULL_PIXELS - HAIRLINE_PIXELS) * widthRatio.coerceIn(0f, 1f)
}
