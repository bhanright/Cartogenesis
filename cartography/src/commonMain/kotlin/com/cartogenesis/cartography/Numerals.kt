package com.cartogenesis.cartography

/**
 * The few characters a chart's margin needs, drawn as strokes rather than set as type.
 *
 * A graticule figure and a scale bar's distance are the only words this renderer puts on the sheet,
 * and they have to appear on an exported PNG as surely as on the screen — so they cannot go through
 * the interface's text layer. Nor can they go through a font: the drawing is shared between a
 * desktop window and a browser tab, and a typeface that resolves on one and not the other would
 * make the same map two different maps.
 *
 * So they are geometry, like everything else the overlay describes: fifteen glyphs written out as
 * polylines and stroked with the same pen as the graticule. It is a plotter's alphabet, which is
 * also what an engraved chart's margin figures look like, so the constraint and the style agree.
 *
 * Each glyph is drawn in a box one unit tall, with `y = 0` at the cap line and `y = 1` on the
 * baseline, and [Glyph.advance] wide. Only the characters [strokes] can draw are here; anything
 * else takes a space's width and leaves the paper alone, because a margin figure with a hole in it
 * is easier to notice than one that has silently moved.
 */
object Numerals {

    /**
     * How wide a space is and how much air is left between two glyphs, as fractions of the cap
     * height.
     *
     * A space a little under half the height and tracking of a seventh is what a plotter's
     * alphabet is drawn at: wide enough that `170` does not read as one shape, tight enough that
     * `170°W` still reads as one word.
     */
    private const val SPACE_SHARE_OF_HEIGHT = 0.42f
    private const val TRACKING_SHARE_OF_HEIGHT = 0.14f

    /** How wide [text] runs when set [heightPixels] tall, in the same pixels. */
    fun widthOf(text: String, heightPixels: Float): Float {
        if (text.isEmpty()) return 0f
        // Every glyph carries a trailing gap, so one is given back at the end: the width is to
        // the last stroke, not to the air past it.
        var widthInHeights = 0f
        text.forEach {
            widthInHeights +=
                (GLYPHS[it]?.advance ?: SPACE_SHARE_OF_HEIGHT) + TRACKING_SHARE_OF_HEIGHT
        }
        return (widthInHeights - TRACKING_SHARE_OF_HEIGHT) * heightPixels
    }

    /**
     * [text] as polylines, set [heightPixels] tall with its left end at [leftX] and its baseline at
     * [baselineY], in whatever coordinates those two are given in.
     */
    fun strokes(text: String, leftX: Float, baselineY: Float, heightPixels: Float): List<FloatArray> {
        val drawn = ArrayList<FloatArray>()
        var penX = leftX
        val capLineY = baselineY - heightPixels
        text.forEach { character ->
            val glyph = GLYPHS[character]
            if (glyph != null) {
                glyph.strokes.forEach { stroke ->
                    val placed = FloatArray(stroke.size)
                    var at = 0
                    while (at < stroke.size) {
                        placed[at] = penX + stroke[at] * heightPixels
                        placed[at + 1] = capLineY + stroke[at + 1] * heightPixels
                        at += 2
                    }
                    drawn.add(placed)
                }
            }
            penX += ((glyph?.advance ?: SPACE_SHARE_OF_HEIGHT) + TRACKING_SHARE_OF_HEIGHT) *
                heightPixels
        }
        return drawn
    }

    private class Glyph(val advance: Float, val strokes: Array<FloatArray>)

    private fun glyph(advance: Float, vararg strokes: FloatArray) = Glyph(advance, arrayOf(*strokes))

    private val GLYPHS: Map<Char, Glyph> = mapOf(
        '0' to glyph(0.62f, floatArrayOf(
            0.08f, 0.22f, 0.35f, 0f, 0.54f, 0f, 0.62f, 0.22f,
            0.62f, 0.78f, 0.54f, 1f, 0.35f, 1f, 0.08f, 0.78f, 0.08f, 0.22f
        )),
        '1' to glyph(0.42f, floatArrayOf(0.02f, 0.24f, 0.28f, 0f, 0.28f, 1f)),
        '2' to glyph(0.62f, floatArrayOf(
            0.05f, 0.2f, 0.32f, 0f, 0.58f, 0.16f, 0.55f, 0.42f, 0.04f, 1f, 0.62f, 1f
        )),
        '3' to glyph(0.62f, floatArrayOf(
            0.05f, 0.14f, 0.33f, 0f, 0.6f, 0.17f, 0.3f, 0.44f,
            0.62f, 0.7f, 0.35f, 1f, 0.05f, 0.86f
        )),
        '4' to glyph(0.62f, floatArrayOf(0.46f, 1f, 0.46f, 0f, 0.02f, 0.7f, 0.62f, 0.7f)),
        '5' to glyph(0.62f, floatArrayOf(
            0.58f, 0f, 0.1f, 0f, 0.06f, 0.44f, 0.36f, 0.36f,
            0.6f, 0.6f, 0.36f, 1f, 0.04f, 0.9f
        )),
        '6' to glyph(0.62f, floatArrayOf(
            0.56f, 0.06f, 0.24f, 0.06f, 0.05f, 0.42f, 0.05f, 0.82f, 0.3f, 1f,
            0.55f, 0.94f, 0.6f, 0.66f, 0.36f, 0.5f, 0.08f, 0.6f
        )),
        '7' to glyph(0.58f, floatArrayOf(0.02f, 0f, 0.58f, 0f, 0.24f, 1f)),
        '8' to glyph(0.62f, floatArrayOf(
            0.33f, 0.46f, 0.58f, 0.28f, 0.5f, 0.04f, 0.2f, 0.04f,
            0.1f, 0.28f, 0.33f, 0.46f, 0.62f, 0.68f, 0.5f, 1f,
            0.18f, 1f, 0.05f, 0.68f, 0.33f, 0.46f
        )),
        '9' to glyph(0.62f, floatArrayOf(
            0.1f, 0.94f, 0.42f, 0.94f, 0.6f, 0.6f, 0.6f, 0.18f, 0.36f, 0f,
            0.12f, 0.06f, 0.06f, 0.34f, 0.3f, 0.5f, 0.58f, 0.4f
        )),
        '°' to glyph(0.42f, floatArrayOf(
            0.14f, 0.1f, 0.3f, 0.02f, 0.42f, 0.12f, 0.38f, 0.28f,
            0.2f, 0.32f, 0.1f, 0.22f, 0.14f, 0.1f
        )),
        'N' to glyph(0.66f, floatArrayOf(0.04f, 1f, 0.04f, 0f, 0.62f, 1f, 0.62f, 0f)),
        'S' to glyph(0.6f, floatArrayOf(
            0.58f, 0.12f, 0.3f, 0f, 0.06f, 0.14f, 0.08f, 0.38f,
            0.52f, 0.6f, 0.56f, 0.86f, 0.28f, 1f, 0.02f, 0.88f
        )),
        'E' to glyph(0.56f, floatArrayOf(0.56f, 0f, 0.06f, 0f, 0.06f, 1f, 0.56f, 1f),
            floatArrayOf(0.06f, 0.5f, 0.42f, 0.5f)),
        'W' to glyph(0.86f, floatArrayOf(
            0.02f, 0f, 0.2f, 1f, 0.43f, 0.36f, 0.66f, 1f, 0.84f, 0f
        )),
        'k' to glyph(0.56f, floatArrayOf(0.06f, 0f, 0.06f, 1f),
            floatArrayOf(0.52f, 0.38f, 0.12f, 0.74f, 0.54f, 1f)),
        'm' to glyph(0.86f, floatArrayOf(0.04f, 0.38f, 0.04f, 1f),
            floatArrayOf(0.04f, 0.46f, 0.24f, 0.36f, 0.42f, 0.48f, 0.42f, 1f),
            floatArrayOf(0.42f, 0.48f, 0.62f, 0.36f, 0.82f, 0.48f, 0.82f, 1f))
    )
}
