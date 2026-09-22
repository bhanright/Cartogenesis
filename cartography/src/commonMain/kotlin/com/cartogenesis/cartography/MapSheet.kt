package com.cartogenesis.cartography

import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The surface a map is being drawn for, and how much of the world one of its pixels ends up
 * carrying.
 *
 * A map is not the same map at every size. A sheet drawn cell for pixel can show every bend of
 * every coast and every headwater thread; the same sheet shrunk to fit a window cannot, and drawing
 * it as though it could gives a grey smear where there should be a river system and a staircase
 * where there should be a coast. Generalisation is the cartographer's answer — fewer features,
 * drawn more simply, as the scale comes down — and this is the one number the whole of it is
 * derived from.
 *
 * [pixelsPerCell] is that number: how many pixels of what the reader is finally looking at one cell
 * of the world covers. It is 1 on the sheet itself, where the raster is a cell to a pixel — an
 * export is drawn at the resolution the world was generated at, so an export is always 1. On screen
 * it is the fit times the zoom: a 2048 world fitted into a 900-pixel pane is being shown at 0.44,
 * and the same world at four times zoom is at 1.76.
 */
data class MapSheet(
    val pixelsPerCell: Float,
    /**
     * Whether the sheet carries its own scale bar.
     *
     * A printed map has nothing beside it, so the bar is drawn on the sheet. The live view has the
     * chart legend along its foot, which can say the same thing and restate it as the reader zooms,
     * so the sheet on screen is left clean. See [MapScale].
     */
    val carriesScaleBar: Boolean = false
) {

    /**
     * How many of [total] features survive at this scale, by Töpfer's radical law.
     *
     * Töpfer and Pillewizer, *The principles of selection* (The Cartographic Journal 3(1), 1966,
     * 10-16), measured what cartographers actually kept when they derived one map from another and
     * found the count went as the square root of the change in scale: `n_derived = n_source ·
     * √(M_source / M_derived)`. The source map here is the sheet drawn cell for pixel, where every
     * traced feature is drawn; the derived map is the same sheet seen at [pixelsPerCell] pixels to
     * the cell, so the ratio of the two scales is [pixelsPerCell] itself and the share that
     * survives is its square root.
     *
     * A 2048 world at fit in a 900-pixel pane is at 0.44, which [onScreen] quantises to 0.5, so 71%
     * of the rivers are drawn; at four times zoom it is at 1.76, quantised to 2.0, the law asks for
     * more features than exist, and every river is drawn. Never below one, because a map with
     * rivers on it should not lose all of them.
     *
     * **This is a share and not a density**, which is why the rivers are no longer selected by it:
     * the law relates a derived map to a source map, so what it puts on the page depends on how
     * many courses the generator traced, and the same country drawn from a 512 world and from a
     * 2048 world comes out at two densities. It is kept as [RiverInk.RADICAL_LAW], the control
     * [RiverSelection]'s Earth figure is measured against. The coast is still generalised by the
     * sheet, through [simplifyToleranceCells], which is a tolerance in the plane of the drawing and
     * has no such problem.
     */
    fun featuresKept(total: Int): Int {
        if (total <= 0) return 0
        if (pixelsPerCell >= 1f) return total
        return ceil(total * sqrt(pixelsPerCell)).toInt().coerceIn(1, total)
    }

    /**
     * The Douglas-Peucker band width for this sheet, in cells.
     *
     * Douglas and Peucker (*Algorithms for the reduction of the number of points required to
     * represent a digitized line or its caricature*, Cartographica 10(2), 1973, 112-122) set their
     * tolerance in the plane of the drawing, not on the ground: it is how far the simplified line
     * is allowed to stray from the original *as drawn*. Half a pixel is the largest such stray that
     * cannot move the line off the pixel it was on, so it is the most that can be given away for
     * nothing, and [Shoreline.simplified] holds to it exactly. Divided by [pixelsPerCell] to say it
     * in cells, which is what the traced coast is measured in.
     *
     * At fit for a 2048 world that is about a cell, which is exactly the stair-step the cell grid
     * puts in the coast; zoomed past one pixel to the cell it falls below half a cell and every
     * bend the world has comes back.
     */
    val simplifyToleranceCells: Float get() = HALF_A_PIXEL / pixelsPerCell

    companion object {
        /**
         * A cell to a pixel: the largest a sheet of this world is ever drawn at.
         *
         * What an export and every offline render draw on, the world having been generated at the
         * size it is being drawn at. Nothing is simplified here — the coast keeps every vertex it
         * has — but the rivers are still selected, because a sheet at a cell to a pixel still has
         * a scale (about 1:22 000 000 for a 2048 world, 1:88 000 000 for a 512 one) and
         * [RiverSelection] answers to that rather than to the grid.
         */
        val UNGENERALISED: MapSheet = MapSheet(1f)

        /** The same, carrying the scale bar a printed map has to carry for itself. */
        val PRINTED: MapSheet = MapSheet(1f, carriesScaleBar = true)

        /**
         * The sheet as the reader is looking at it now, at [pixelsPerCell] pixels to the cell.
         *
         * Quantised, because the drawing is redone whenever this changes and a wheel notch is 15%:
         * without it a scroll from fit to four times would redraw the sheet twenty times over. The
         * step is half an octave — a factor of √2, half the doubling a printed map series steps its
         * scales by — so the whole zoom range is a dozen redraws and the quantised scale is never
         * more than 19% from the true one.
         */
        fun onScreen(pixelsPerCell: Float): MapSheet {
            val bounded = pixelsPerCell.coerceIn(SMALLEST_SHOWN, LARGEST_SHOWN)
            val band = (log2(bounded) * BANDS_PER_OCTAVE).roundToInt()
            return MapSheet(2f.pow(band / BANDS_PER_OCTAVE))
        }

        /** See [simplifyToleranceCells]. */
        private const val HALF_A_PIXEL = 0.5f

        /** Half-octave bands: √2 apart. See [onScreen]. */
        private const val BANDS_PER_OCTAVE = 2f

        /**
         * The range the quantised bands cover.
         *
         * The camera stops at a fifth of fit and forty times it, and a 4096 world in a phone-sized
         * pane is at 0.09 when fitted, so this is the widest either end can honestly reach.
         */
        private const val SMALLEST_SHOWN = 1f / 64f
        private const val LARGEST_SHOWN = 64f
    }
}
