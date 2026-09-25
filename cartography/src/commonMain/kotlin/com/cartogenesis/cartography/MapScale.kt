package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.WorldScale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * A scale bar: a round distance and how long it is drawn.
 *
 * [kilometres] is what the bar measures on the ground, [lengthPixels] how long it runs on the
 * drawing, and [label] what is written at its far end — `500 km`, or metres where the reader has
 * zoomed in past a kilometre to the bar.
 */
class ScaleBar(
    val kilometres: Double,
    val lengthPixels: Float,
    val label: String
)

/**
 * A scale bar placed on the sheet, at the left end of the bar in cell coordinates. It is drawn
 * east-west, along a row, which is the axis its length holds on.
 */
class PlacedScaleBar(
    val bar: ScaleBar,
    val x: Float,
    val y: Float,
    val figureHeightPixels: Float
)

/**
 * How far a distance on the map is on the ground, and how to say so.
 *
 * Everything here comes off one number the world already carries — [WorldScale.worldWidthKm],
 * twelve thousand kilometres east to west and half that from pole to pole, which is what turns a
 * count of cells into a length and is where the realm areas and the heightmap sidecar's cell size
 * come from too. Nothing is measured twice: [WorldScale.cellWidthKm] and [WorldScale.cellHeightKm]
 * are the arithmetic, and this puts them on the paper.
 *
 * The sheet draws a cell as a square pixel, and a cell is not square: on a grid as many cells tall
 * as wide over a world twice as wide as it is tall, a pixel covers a cell's width of ground
 * east-west and half of that north-south. So the sheet has two scales, and a bar laid along a
 * meridian would overstate a distance twice over. The bar is drawn east-west and holds there; the
 * cartouche gives both figures, east-west and north-south. On top of that the projection is
 * equirectangular, so east-west distances shrink by the cosine of the latitude as they go poleward;
 * that is the projection's own distortion and not something a scale bar can fix, which is why the
 * bar and the cartouche both say *at the equator* and neither pretends otherwise. A projection that
 * could say more is a later chunk's work; see REALISM_AUDIT.md, P1.
 */
object MapScale {

    /**
     * How much of the frame a scale bar is allowed to take.
     *
     * A bar is conventionally about a quarter of the map's width: long enough to be laid against a
     * distance on the sheet and read off it, short enough not to be taken for part of the picture.
     * Robinson and others' *Elements of Cartography* puts it at a quarter to a third; Brewer's
     * *Designing Better Maps* sets the ceiling at about a third. A quarter is the low end of both,
     * and the low end is right here because the bar is drawn *on* the map rather than in a margin
     * of its own.
     */
    const val SHARE_OF_FRAME: Float = 0.25f

    /**
     * How wide a pixel is taken to be when a representative fraction is quoted, in millimetres.
     *
     * A representative fraction is a ratio between two *lengths*, so quoting one for a picture that
     * has no physical size means choosing one. The CSS reference pixel — ninety-six to the inch —
     * is the convention every browser and every desktop toolkit already assumes, so it is the
     * honest choice here and the fraction is quoted with it stated.
     */
    private const val MILLIMETRES_PER_PIXEL: Double = 25.4 / 96.0

    private const val MILLIMETRES_PER_KILOMETRE: Double = 1_000_000.0

    /**
     * Ground kilometres one drawn pixel covers east-west, at [pixelsPerCell] pixels to the cell:
     * the scale along a row, which is the one the bar is drawn on.
     */
    fun kilometresPerPixel(
        scale: WorldScale,
        cellsAcross: Int,
        pixelsPerCell: Float
    ): Double = scale.cellWidthKm(cellsAcross) / pixelsPerCell

    /**
     * Ground kilometres one drawn pixel covers north-south, at [pixelsPerCell] pixels to the cell:
     * a row's height, which on this project's grids is half what a pixel covers east-west.
     */
    fun kilometresPerPixelNorthSouth(
        scale: WorldScale,
        cellsDown: Int,
        pixelsPerCell: Float
    ): Double = scale.cellHeightKm(cellsDown) / pixelsPerCell

    /**
     * The longest round distance that fits [SHARE_OF_FRAME] of a frame [frameWidthPixels] wide.
     *
     * Round means the 1-2-5 series — 1, 2, 5, 10, 20, 50 and so on — which is the series every
     * scale bar and every axis tick uses, because those are the numbers a reader can halve and
     * quarter by eye against the bar.
     */
    fun longestBarThatFits(kilometresPerPixel: Double, frameWidthPixels: Float): ScaleBar {
        val longest = frameWidthPixels * SHARE_OF_FRAME * kilometresPerPixel
        val kilometres = roundedDownTo125(longest)
        return ScaleBar(
            kilometres = kilometres,
            lengthPixels = (kilometres / kilometresPerPixel).toFloat(),
            label = distanceLabel(kilometres)
        )
    }

    /** The largest `1`, `2` or `5` times a power of ten that is no bigger than [longest]. */
    internal fun roundedDownTo125(longest: Double): Double {
        if (longest <= 0.0 || !longest.isFinite()) return 0.0
        val decade = 10.0.pow(floor(log10(longest)))
        return when {
            longest >= 5.0 * decade -> 5.0 * decade
            longest >= 2.0 * decade -> 2.0 * decade
            else -> decade
        }
    }

    /** `500 km` above a kilometre, `500 m` below it: a bar never reads `0.5 km`. */
    internal fun distanceLabel(kilometres: Double): String =
        if (kilometres >= 1.0) "${kilometres.roundToLong()} km"
        else "${(kilometres * 1_000.0).roundToLong()} m"

    /**
     * The denominator of this sheet's representative fraction: the `22 000 000` of `1:22 000 000`.
     *
     * One ground length over one drawn length, both in the same unit. The drawn length needs a
     * physical size for a picture that has none, so it is [MILLIMETRES_PER_PIXEL] — the CSS
     * reference pixel, ninety-six to the inch — and the fraction is only ever quoted with that
     * stated. [pixelsPerCell] is the sheet's, so an export of a 2048 world is about 1:22 000 000
     * and the same world fitted into a 900-pixel pane is about 1:50 000 000; the generation
     * resolution on its own fixes neither, because it says nothing about how big the drawing is.
     *
     * Returned as a `Double` and not rounded: [cartoucheLine] is what rounds it for a reader, and
     * [RiverSelection] wants the whole figure to derive a density from.
     */
    fun representativeFractionDenominator(
        scale: WorldScale,
        cellsAcross: Int,
        pixelsPerCell: Float
    ): Double =
        kilometresPerPixel(scale, cellsAcross, pixelsPerCell) *
            MILLIMETRES_PER_KILOMETRE / MILLIMETRES_PER_PIXEL

    /**
     * The line the cartouche carries: how far a pixel of this sheet reaches each way, and the
     * fraction.
     *
     * `5.9 km per pixel east-west, 2.9 north-south · about 1:22 000 000 at the equator, east-west`.
     * Both figures, because a pixel covers twice as much ground east-west as north-south and one
     * figure for both would be wrong along every meridian (Audit III's F-C1). The fraction is the
     * east-west one, the axis the bar is drawn on. It is quoted to two significant figures and no
     * more, because it rests on [MILLIMETRES_PER_PIXEL] — an assumption about the reader's screen —
     * and a fraction written to seven digits would claim a precision that assumption does not have.
     * "About" is there for the same reason.
     */
    fun cartoucheLine(scale: WorldScale, cellsAcross: Int, cellsDown: Int): String {
        val perPixel = kilometresPerPixel(scale, cellsAcross, 1f)
        val perPixelNorthSouth = kilometresPerPixelNorthSouth(scale, cellsDown, 1f)
        val denominator = representativeFractionDenominator(scale, cellsAcross, 1f)
        return "${oneDecimal(perPixel)} km per pixel east-west, ${oneDecimal(perPixelNorthSouth)} " +
            "north-south · about 1:${grouped(twoFigures(denominator))} at the equator, east-west"
    }

    /** `5.9`. Kotlin's common runtime has no format string, and this is the only place one is due. */
    internal fun oneDecimal(value: Double): String {
        val tenths = (value * 10.0).roundToLong()
        return "${tenths / 10}.${tenths % 10}"
    }

    /** [value] with everything below its second significant digit set to zero. */
    internal fun twoFigures(value: Double): Long {
        // Below ten there is no third digit to round away, and the decade below would round to
        // zero and take the whole number with it.
        if (value < 10.0 || !value.isFinite()) return value.roundToLong()
        val decade = 10.0.pow(floor(log10(value)) - 1.0)
        return (value / decade).roundToLong() * decade.roundToLong()
    }

    /** `22 000 000`: thin groups of three, which is how a fraction that large is printed. */
    internal fun grouped(value: Long): String {
        val digits = value.toString()
        val out = StringBuilder()
        digits.forEachIndexed { index, digit ->
            if (index > 0 && (digits.length - index) % 3 == 0) out.append(' ')
            out.append(digit)
        }
        return out.toString()
    }
}
