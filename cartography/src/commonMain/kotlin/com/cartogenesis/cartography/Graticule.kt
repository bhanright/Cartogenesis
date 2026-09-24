package com.cartogenesis.cartography

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** One line of the graticule, in sheet pixels: a whole meridian or a whole parallel. */
class GraticuleLine(
    val fromX: Float,
    val fromY: Float,
    val toX: Float,
    val toY: Float
)

/**
 * A figure in the sheet's margin, already placed: `40°N`, `0°`, `170°W`.
 *
 * [leftX] and [baselineY] are where [Numerals] should start setting it, in sheet pixels, and
 * [heightPixels] is its cap height. Placed here rather than in the front end because where a
 * figure sits depends on how wide it is, and how wide it is is this module's arithmetic.
 */
class GraticuleLabel(
    val text: String,
    val leftX: Float,
    val baselineY: Float,
    val heightPixels: Float
)

/**
 * Lines of latitude and longitude over an equirectangular world, and the figures that name them.
 *
 * The map is the whole globe: 360 degrees of longitude across the sheet's width and 180 of latitude
 * down its height, with the prime meridian at the middle and the equator half way down. That makes
 * the spacing exact rather than fitted — a meridian every ten degrees falls every `width / 36`
 * pixels and a parallel every `height / 18`, which on a 2048 world's 4096 by 2048 true-shape sheet
 * is 113.777… pixels either way, and is not asked to be a whole number of anything. Rounding it to
 * whole pixels is the one mistake worth naming here: it would put the equator off the middle and
 * make the two hemispheres different sizes.
 *
 * Laid out on the sheet ([SheetGeometry]) rather than on the grid, because it belongs to the paper:
 * its lines, its figures and where each figure sits are all in the sheet's own pixels.
 *
 * The graticule is a hairline — [RiverPen.HAIRLINE_PIXELS], the finest mark the nib leaves, for the
 * reason that constant gives — and its figures are sized from its own spacing, so a sheet twice as
 * large carries the same picture rather than the same picture with bigger type.
 */
class Graticule(
    val lines: List<GraticuleLine>,
    val labels: List<GraticuleLabel>,
    /** Pixels between two neighbouring meridians. Exactly `widthPixels · degrees / 360`. */
    val meridianSpacingPixels: Float,
    /** Pixels between two neighbouring parallels. Exactly `heightPixels · degrees / 180`. */
    val parallelSpacingPixels: Float
) {

    companion object {

        /**
         * The spacing every atlas of a whole world uses.
         *
         * Ten degrees is about 1,100 km on the ground at the equator on a 12,000 km world, which is
         * a country's width: fine enough to read a position off, coarse enough that the lines do
         * not become a texture. Finer graticules belong to sheets of one region, which this
         * renderer does not draw.
         */
        const val DEGREES: Int = 10

        private const val DEGREES_OF_LONGITUDE = 360
        private const val DEGREES_OF_LATITUDE = 180

        /**
         * How tall a margin figure is set, in output pixels.
         *
         * Sized from the graticule's own spacing rather than from the sheet's width, because what a
         * figure has to fit between is the two lines either side of it. The widest figure there is
         * is `170°W`, and [Numerals] sets it about three and a third cap heights wide, so asking it
         * to take no more than [SHARE_OF_GAP_A_FIGURE_MAY_TAKE] of the gap fixes the cap height at
         * a fifth of the spacing — the figures then have a third of the gap as air between them.
         *
         * Floored at six pixels, below which the strokes fall inside one another and the figure is
         * a smudge. A 512 sheet is therefore given a figure that is legible rather than one that is
         * in proportion, and pays for it by having [figuresEveryNthLine] label fewer of its lines.
         */
        fun labelHeightPixels(meridianSpacingPixels: Float): Float =
            max(SMALLEST_LEGIBLE_FIGURE_PIXELS, meridianSpacingPixels * figureShareOfSpacing)

        /**
         * How many lines apart the figures are set: every line where they fit side by side, every
         * second or third where they would run into one another.
         *
         * A small sheet cannot have both — its spacing is a few dozen pixels and its figures are held at
         * six pixels by the floor above — and of the two, legible figures on every other line beat
         * a solid smear of digits on every one. An atlas does exactly this: the graticule is ruled
         * at ten degrees and figured at twenty or thirty, whichever the margin has room for.
         */
        internal fun figuresEveryNthLine(spacingPixels: Float, figurePixels: Float): Int {
            val room = spacingPixels * SHARE_OF_GAP_A_FIGURE_MAY_TAKE
            if (room <= 0f) return 1
            // The nudge is against the exact-fit case: where the figure was sized from the
            // spacing, the ratio is one to the last bit and must not be rounded up to two.
            return ceil(
                Numerals.widthOf(WIDEST_FIGURE, figurePixels) / room - EXACT_FIT_TOLERANCE
            ).toInt().coerceAtLeast(1)
        }

        /** The longest thing that ever appears in the margin: five glyphs of longitude. */
        private const val WIDEST_FIGURE = "170°W"

        /** Two thirds, so a third of the gap between two lines is left as air. */
        private const val SHARE_OF_GAP_A_FIGURE_MAY_TAKE = 0.66f

        private const val SMALLEST_LEGIBLE_FIGURE_PIXELS = 6f

        /** See [figuresEveryNthLine]: a thousandth of a figure's width, which no real case is. */
        private const val EXACT_FIT_TOLERANCE = 1e-3f

        private val figureShareOfSpacing: Float =
            SHARE_OF_GAP_A_FIGURE_MAY_TAKE / Numerals.widthOf(WIDEST_FIGURE, 1f)

        /** How far a figure is inset from the edge it labels, as a share of its own height. */
        private const val MARGIN_SHARE_OF_FIGURE = 0.55f

        /**
         * The graticule for a sheet [widthPixels] by [heightPixels] — the true-shape sheet's own
         * dimensions, [SheetGeometry.widthPixels] and [SheetGeometry.heightPixels] — at
         * [Graticule.DEGREES].
         *
         * Both edges of the sheet carry the figures for the lines that cross them — longitude along
         * the top and the bottom, latitude down the left and the right — which is what lets a
         * reader take a position off a corner of the map without carrying a finger across it.
         */
        fun of(widthPixels: Int, heightPixels: Int): Graticule {
            val meridianSpacing = widthPixels.toFloat() * DEGREES / DEGREES_OF_LONGITUDE
            val parallelSpacing = heightPixels.toFloat() * DEGREES / DEGREES_OF_LATITUDE
            val figurePixels = labelHeightPixels(meridianSpacing)
            val marginPixels = figurePixels * MARGIN_SHARE_OF_FIGURE
            val figuredEvery = figuresEveryNthLine(meridianSpacing, figurePixels)

            val lines = ArrayList<GraticuleLine>()
            val labels = ArrayList<GraticuleLabel>()

            // Longitude, west to east. The two antimeridians are the sheet's own edges and are
            // drawn as lines but left unlabelled: they would sit half off the paper.
            for (step in 0..DEGREES_OF_LONGITUDE / DEGREES) {
                val degrees = -DEGREES_OF_LONGITUDE / 2 + step * DEGREES
                val atX = step * meridianSpacing
                lines.add(GraticuleLine(atX, 0f, atX, heightPixels.toFloat()))
                if (abs(degrees) == DEGREES_OF_LONGITUDE / 2) continue
                if (step % figuredEvery != 0) continue
                val text = eastWest(degrees)
                val left = atX - Numerals.widthOf(text, figurePixels) / 2f
                labels.add(
                    GraticuleLabel(text, left, figurePixels + marginPixels, figurePixels)
                )
                labels.add(
                    GraticuleLabel(text, left, heightPixels - marginPixels, figurePixels)
                )
            }

            // Latitude, north to south. The poles are the top and bottom edges, and are the same
            // case as the antimeridians.
            for (step in 0..DEGREES_OF_LATITUDE / DEGREES) {
                val degrees = DEGREES_OF_LATITUDE / 2 - step * DEGREES
                val atY = step * parallelSpacing
                lines.add(GraticuleLine(0f, atY, widthPixels.toFloat(), atY))
                if (abs(degrees) == DEGREES_OF_LATITUDE / 2) continue
                // Figured at the same interval as the meridians, because a graticule figured every
                // twenty degrees one way and every ten the other reads as two grids.
                if (step % figuredEvery != 0) continue
                val text = northSouth(degrees)
                // Half the cap height above the line, so the figure is centred on what it names.
                val baseline = atY + figurePixels / 2f
                labels.add(GraticuleLabel(text, marginPixels, baseline, figurePixels))
                labels.add(
                    GraticuleLabel(
                        text,
                        widthPixels - marginPixels - Numerals.widthOf(text, figurePixels),
                        baseline,
                        figurePixels
                    )
                )
            }

            return Graticule(lines, labels, meridianSpacing, parallelSpacing)
        }

        /** `0°`, `30°E`, `170°W`. */
        internal fun eastWest(degrees: Int): String = when {
            degrees == 0 -> "0°"
            degrees > 0 -> "$degrees°E"
            else -> "${-degrees}°W"
        }

        /** `0°`, `40°N`, `80°S`. */
        internal fun northSouth(degrees: Int): String = when {
            degrees == 0 -> "0°"
            degrees > 0 -> "$degrees°N"
            else -> "${-degrees}°S"
        }
    }
}
