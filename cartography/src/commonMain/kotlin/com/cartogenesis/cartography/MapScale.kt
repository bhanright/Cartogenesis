package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.NationsConfig
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

/** A scale bar placed on the sheet, at the left end of the bar in cell coordinates. */
class PlacedScaleBar(
    val bar: ScaleBar,
    val x: Float,
    val y: Float,
    val figureHeightPixels: Float
)

/**
 * How far a distance on the map is on the ground, and how to say so.
 *
 * Everything here comes off one number the world already carries — [NationsConfig.worldWidthKm],
 * twelve thousand kilometres east to west, which is what turns a count of cells into a length and
 * is where the realm areas and the heightmap sidecar's cell size come from too. Nothing is measured
 * twice: [NationsConfig.kilometresPerCellWidth] is the arithmetic, and this puts it on the paper.
 *
 * The projection is equirectangular, so a kilometre is only a kilometre along the equator and along
 * every meridian; east-west distances shrink by the cosine of the latitude as they go poleward.
 * That is the projection's own distortion and not something a scale bar can fix, which is why the
 * bar and the cartouche both say *at the equator* and neither pretends otherwise. The audit's P1
 * chunk is where a projection that could say more belongs.
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

    /** Ground kilometres one drawn pixel covers, at [pixelsPerCell] pixels to the cell. */
    fun kilometresPerPixel(
        nations: NationsConfig,
        cellsAcross: Int,
        pixelsPerCell: Float
    ): Double = nations.kilometresPerCellWidth(cellsAcross) / pixelsPerCell

    /**
     * The longest round distance that fits [SHARE_OF_FRAME] of a frame [frameWidthPixels] wide.
     *
     * Round means the 1-2-5 series — 1, 2, 5, 10, 20, 50 and so on — which is the series every
     * scale bar and every axis tick uses, because those are the numbers a reader can halve and
     * quarter by eye against the bar.
     */
    fun bar(kilometresPerPixel: Double, frameWidthPixels: Float): ScaleBar {
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
     * The line the cartouche carries: how far a pixel of this sheet reaches, and the fraction.
     *
     * `5.9 km per pixel · about 1:22 000 000 at the equator`. The fraction is quoted to two
     * significant figures and no more, because it rests on [MILLIMETRES_PER_PIXEL] — an assumption
     * about the reader's screen — and a fraction written to seven digits would claim a precision
     * that assumption does not have. "About" is there for the same reason.
     */
    fun cartoucheLine(nations: NationsConfig, cellsAcross: Int): String {
        val perPixel = kilometresPerPixel(nations, cellsAcross, 1f)
        val denominator = perPixel * MILLIMETRES_PER_KILOMETRE / MILLIMETRES_PER_PIXEL
        return "${oneDecimal(perPixel)} km per pixel · about 1:${grouped(twoFigures(denominator))} " +
            "at the equator"
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
