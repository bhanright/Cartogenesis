package com.cartogenesis.cartography

import kotlin.math.abs
import kotlin.math.max

/** One line of the graticule, in cell coordinates: a whole meridian or a whole parallel. */
class GraticuleLine(
    val fromX: Float,
    val fromY: Float,
    val toX: Float,
    val toY: Float
)

/**
 * A figure in the sheet's margin, already placed: `40°N`, `0°`, `170°W`.
 *
 * [leftX] and [baselineY] are where [Numerals] should start setting it, in cell coordinates, and
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
 * down its height, with the prime meridian at the middle column and the equator at the middle row.
 * That makes the spacing exact rather than fitted — a meridian every ten degrees falls every
 * `width / 36` cells and a parallel every `height / 18`, which at 2048 is 56.888… and 113.777…
 * cells and is not asked to be a whole number of anything. Rounding it to whole cells is the one
 * mistake worth naming here: it would put the equator off the middle row and make the two
 * hemispheres different sizes.
 *
 * The graticule is a hairline — [RiverPen.HAIRLINE_PIXELS], the finest mark the nib leaves, for the
 * reason that constant gives — and its figures are sized from its own spacing, so a sheet twice as
 * large carries the same picture rather than the same picture with bigger type.
 */
class Graticule(
    val lines: List<GraticuleLine>,
    val labels: List<GraticuleLabel>,
    /** Cells between two neighbouring meridians. Exactly `cellsAcross · degrees / 360`. */
    val meridianSpacingCells: Float,
    /** Cells between two neighbouring parallels. Exactly `cellsDown · degrees / 180`. */
    val parallelSpacingCells: Float
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
         * figure has to fit between is the two lines either side of it: a little over a quarter of
         * that leaves the label about a third of the gap and the rest air. Floored at six pixels,
         * below which the strokes fall inside one another and the figure is a smudge, so a 512
         * sheet gets a figure that is legible rather than one that is in proportion.
         */
        fun labelHeightPixels(meridianSpacingCells: Float): Float =
            max(SMALLEST_LEGIBLE_FIGURE_PIXELS, meridianSpacingCells * FIGURE_SHARE_OF_SPACING)

        private const val FIGURE_SHARE_OF_SPACING = 0.28f
        private const val SMALLEST_LEGIBLE_FIGURE_PIXELS = 6f

        /** How far a figure is inset from the edge it labels, as a share of its own height. */
        private const val MARGIN_SHARE_OF_FIGURE = 0.55f

        /**
         * The graticule for a sheet [cellsAcross] by [cellsDown], at [Graticule.DEGREES].
         *
         * Both edges of the sheet carry the figures for the lines that cross them — longitude along
         * the top and the bottom, latitude down the left and the right — which is what lets a
         * reader take a position off a corner of the map without carrying a finger across it.
         */
        fun of(cellsAcross: Int, cellsDown: Int): Graticule {
            val meridianSpacing = cellsAcross.toFloat() * DEGREES / DEGREES_OF_LONGITUDE
            val parallelSpacing = cellsDown.toFloat() * DEGREES / DEGREES_OF_LATITUDE
            val figure = labelHeightPixels(meridianSpacing)
            val margin = figure * MARGIN_SHARE_OF_FIGURE

            val lines = ArrayList<GraticuleLine>()
            val labels = ArrayList<GraticuleLabel>()

            // Longitude, west to east. The two antimeridians are the sheet's own edges and are
            // drawn as lines but left unlabelled: they would sit half off the paper.
            for (step in 0..DEGREES_OF_LONGITUDE / DEGREES) {
                val degrees = -DEGREES_OF_LONGITUDE / 2 + step * DEGREES
                val x = step * meridianSpacing
                lines.add(GraticuleLine(x, 0f, x, cellsDown.toFloat()))
                if (abs(degrees) == DEGREES_OF_LONGITUDE / 2) continue
                val text = eastWest(degrees)
                val left = x - Numerals.widthOf(text, figure) / 2f
                labels.add(GraticuleLabel(text, left, figure + margin, figure))
                labels.add(GraticuleLabel(text, left, cellsDown - margin, figure))
            }

            // Latitude, north to south. The poles are the top and bottom edges, and are the same
            // case as the antimeridians.
            for (step in 0..DEGREES_OF_LATITUDE / DEGREES) {
                val degrees = DEGREES_OF_LATITUDE / 2 - step * DEGREES
                val y = step * parallelSpacing
                lines.add(GraticuleLine(0f, y, cellsAcross.toFloat(), y))
                if (abs(degrees) == DEGREES_OF_LATITUDE / 2) continue
                val text = northSouth(degrees)
                // Half the cap height above the line, so the figure is centred on what it names.
                val baseline = y + figure / 2f
                labels.add(GraticuleLabel(text, margin, baseline, figure))
                labels.add(
                    GraticuleLabel(
                        text, cellsAcross - margin - Numerals.widthOf(text, figure), baseline, figure
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
