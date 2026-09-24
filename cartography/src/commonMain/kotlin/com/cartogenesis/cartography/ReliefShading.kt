package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.FloatField
import kotlin.math.sqrt

/**
 * How brightly the ground at each cell is lit, as a factor a colour is multiplied by.
 *
 * One lamp in the north-west is the convention every shaded-relief map has used since the
 * nineteenth century, and it has one failure that no amount of exaggeration fixes: a slope facing
 * away from the lamp receives nothing at all, so a range running the wrong way comes out with one
 * side white and the other black, and everything inside the black is gone. Kennelly and Stewart
 * (2014, *General sky models for illuminating terrains*) put the light back where it comes from —
 * the whole sky — and that is what this computes, in two parts:
 *
 *  - **The direct light**, from eight lamps round the whole compass rather than one. Each is at the
 *    same height above the horizon as the old lamp, each lights a slope according to how that slope
 *    faces it, and each is as bright as its own eighth of the sky is — brightest around the
 *    conventional north-west light and dimmest opposite it, by however much [HAZE] says. So a ridge
 *    reads whichever way it runs, and the north-west still reads as the lit side.
 *  - **The sky**, which is what fills the shadow. A surface walled in by higher ground sees less of
 *    the sky than an open plain does, and [openness] measures how much: the horizon angle along
 *    eight bearings over a short stencil, weighted by the brightness of the sky each bearing hides.
 *    This is the term that means no face of a hill is ever unlit, only dimmer, which is what a
 *    person actually sees on an overcast morning and what `ReliefShadingTest` holds the model to.
 *
 * Mark's four-lamp oblique-weighted relief (1992, USGS Open-File Report 92-422) was written first
 * and measured worse than the lamp it replaced: four lamps inside a 135-degree arc leave a whole
 * quadrant with no direct light at all, so the far side of a cone came out darker than the single
 * lamp had left it. The dome above is what fixed it. See docs/DESIGN_LEDGER.md, F13, for the figures.
 *
 * The single lamp is kept, exactly as it was, because a reader may prefer it — see
 * [RenderOptions.singleLamp].
 *
 * **Drawn for the ground, not for the sheet.** A cell of this map is twice as wide as it is tall,
 * and the sheet draws it as a square pixel, so a slope can be read per pixel (the sheet's) or per
 * kilometre (the ground's), and the two disagree by a factor of two north-south. This reads the
 * ground: the difference down a column is divided by the row's height and the one along a row by
 * the column's width, and the horizon is sampled along the eight compass bearings of the ground at
 * three distances of ground. So a range lights the same whichever way it runs, and a slope facing
 * north is shaded as steep as it stands. Read on the sheet, a north-facing slope was lit as half as
 * steep as the same slope facing east, and the sky's stencil reached twice as far east as north
 * (Audit III's F-R5; docs/DESIGN_LEDGER.md, Fix 2). On square cells the two readings of a slope
 * are one; the horizon's diagonal samples still differ from the older stencil's, which went a whole
 * diagonal cell out per step of reach and so reached the diagonals root two further than the axes.
 *
 * The reference implementation, as [Engraving] is: every line here has a copy in the compute
 * shader (`GpuRaster.SOURCE`) written against it, and the two are one model in two languages.
 */
internal object ReliefShading {

    /**
     * How far the central differences are exaggerated, at a map [width].
     *
     * Gentle relief still has to read at map scale, and these are differences between adjacent
     * cells: at four times the grid a step covers a quarter of the ground, and the relief would
     * otherwise render four times flatter.
     */
    fun slopeScale(width: Int): Float = SLOPE_SCALE_AT_512 * (width / 512f)

    /**
     * How far the openness stencil reaches on its shortest step, in cells, at a map [width].
     *
     * The stencil grows with the grid, unlike the engraving's marks, and for the opposite reason:
     * a hachure is a mark made by a pen and belongs to the sheet, but a valley is a piece of
     * country and belongs to the world. Held at the same three distances of *ground* whatever the
     * resolution, the sky term describes the same valleys at 512 and at 4096; held at three
     * distances of pixel it would describe a valley at 512 and a pothole at 4096. It costs nothing
     * either way — the same twenty-four samples a pixel, further apart.
     */
    fun opennessStep(width: Int): Int =
        (OPENNESS_STEP_AT_512 * width / 512).coerceAtLeast(1)

    /**
     * The lighting factor for every cell: 1 leaves a colour alone, below darkens, above lightens.
     *
     * The sea keeps a factor of 1: relief is only ever raked across the land, and shading the sea
     * floor as well would be most of the work for none of the picture.
     */
    fun of(
        elevation: FloatField,
        isLand: BooleanArray,
        singleLamp: Boolean,
        cellHeightInCellWidths: Double
    ): FloatArray {
        val cellsAcross = elevation.width
        val cellsDown = elevation.height
        val shade = FloatArray(cellsAcross * cellsDown) { 1f }
        val scale = slopeScale(cellsAcross)
        val horizon = ReliefHorizon.of(opennessStep(cellsAcross), cellHeightInCellWidths)
        val rowScale = cellHeightInCellWidths.toFloat()
        for (row in 0 until cellsDown) {
            val rowStart = row * cellsAcross
            for (column in 0 until cellsAcross) {
                if (!isLand[rowStart + column]) continue
                shade[rowStart + column] =
                    at(column, row, elevation, scale, rowScale, horizon, singleLamp, DAYLIGHT)
            }
        }
        return shade
    }

    /**
     * A day's sky: how much of its light is diffuse, and how bright it is along each bearing.
     *
     * One object rather than two loose numbers because the two move together — see [HAZE] — and
     * because `ReliefShadingTest` sweeps them together to derive it. The shipped [DAYLIGHT] is
     * built once; nothing allocates one per pixel.
     */
    class Sky(val diffuseShare: Float, val brightness: FloatArray) {
        val brightnessTotal: Float = brightness.sum()

        companion object {
            fun forHaze(haze: Float): Sky =
                Sky(skyShare(haze), FloatArray(HORIZON_BEARINGS) { skyBrightness(haze, it) })
        }
    }

    /** The sky every render is drawn under. */
    val DAYLIGHT: Sky by lazy { Sky.forHaze(HAZE) }

    /**
     * The lighting factor at one cell.
     *
     * [scale] comes from [slopeScale] and [step] from [opennessStep]; both are properties of the
     * grid rather than of the cell, so they are worked out once and handed in, with how tall a row
     * is against a column's width.
     */
    fun at(
        x: Int,
        y: Int,
        elevation: FloatField,
        scale: Float,
        step: Int,
        singleLamp: Boolean,
        cellHeightInCellWidths: Double,
        sky: Sky = DAYLIGHT
    ): Float = at(
        x, y, elevation, scale, cellHeightInCellWidths.toFloat(), ReliefHorizon.of(step, cellHeightInCellWidths),
        singleLamp, sky
    )

    private fun at(
        x: Int,
        y: Int,
        elevation: FloatField,
        scale: Float,
        rowScale: Float,
        horizon: ReliefHorizon,
        singleLamp: Boolean,
        sky: Sky
    ): Float {
        if (singleLamp) {
            val eastward = (elevation.sample(x + 1, y) - elevation.sample(x - 1, y)) * scale
            val southward = (elevation.sample(x, y + 1) - elevation.sample(x, y - 1)) * scale / rowScale
            val normalLength = sqrt(eastward * eastward + southward * southward + 1f)
            val lambert =
                (-eastward * LAMP_EAST - southward * LAMP_SOUTH + LAMP_HEIGHT) / normalLength
            return (LAMP_AMBIENT + LAMP_SWING * lambert).coerceIn(DARKEST, BRIGHTEST)
        }
        return (illumination(x, y, elevation, scale, rowScale, horizon, sky) / ORDINARY_GROUND)
            .coerceIn(DARKEST, BRIGHTEST)
    }

    /**
     * The light reaching a cell under [sky], as a share of what a flat open plain receives.
     *
     * Before [ORDINARY_GROUND], which is the median of this over a world's land — so this is the
     * quantity `ReliefShadingTest` sweeps the haze over, and [at] is this divided by that median
     * and clamped.
     */
    fun illumination(
        x: Int,
        y: Int,
        elevation: FloatField,
        scale: Float,
        step: Int,
        cellHeightInCellWidths: Double,
        sky: Sky = DAYLIGHT
    ): Float = illumination(
        x, y, elevation, scale, cellHeightInCellWidths.toFloat(), ReliefHorizon.of(step, cellHeightInCellWidths), sky
    )

    private fun illumination(
        x: Int,
        y: Int,
        elevation: FloatField,
        scale: Float,
        rowScale: Float,
        horizon: ReliefHorizon,
        sky: Sky
    ): Float {
        // Rises per cell width of ground: the difference down a column is over two rows, which
        // are [rowScale] of a cell width each.
        val eastward = (elevation.sample(x + 1, y) - elevation.sample(x - 1, y)) * scale
        val southward = (elevation.sample(x, y + 1) - elevation.sample(x, y - 1)) * scale / rowScale
        val normalLength = sqrt(eastward * eastward + southward * southward + 1f)
        val direct = directLight(eastward, southward, normalLength, sky)
        val open = openness(x, y, elevation, scale, horizon, sky)
        val share = sky.diffuseShare
        return share * open + (1f - share) * (direct / LAMP_HEIGHT)
    }

    /**
     * The direct light a slope receives from the dome, in the same units as one lamp's Lambert
     * term: [LAMP_HEIGHT] on flat ground, more on a slope turned toward the bright side of the sky,
     * and never zero, because no slope can turn away from all eight bearings at once.
     *
     * That last property is the one the whole model rests on and the one a four-lamp arc does not
     * have. [eastward] and [southward] are the scaled central differences and [normalLength] the
     * length of the surface normal they imply.
     */
    fun directLight(
        eastward: Float,
        southward: Float,
        normalLength: Float,
        sky: Sky = DAYLIGHT
    ): Float {
        // Each lamp contributes as this slope faces it and as bright as its quarter of the sky is.
        // A lamp below the local horizon contributes nothing, which is what the clamp at zero is;
        // the brightnesses total a constant, so flat ground comes out with exactly the light one
        // lamp of the same altitude would have given it.
        // The table is read out of the object once: this loop runs for every land pixel of the map.
        val brightness = sky.brightness
        var direct = 0f
        for (bearing in 0 until HORIZON_BEARINGS) {
            val bearingEast = BEARING_UNIT_EAST[bearing]
            val bearingSouth = BEARING_UNIT_SOUTH[bearing]
            val lambert = (
                -eastward * bearingEast * LAMP_REACH -
                    southward * bearingSouth * LAMP_REACH + LAMP_HEIGHT
                ) / normalLength
            if (lambert > 0f) direct += brightness[bearing] * lambert
        }
        return direct / sky.brightnessTotal
    }

    /**
     * How much of the sky the ground at a cell can see: 1 on an open plain, toward 0 in a slot.
     *
     * The cheap horizon estimate the sky term needs. Along each of the eight compass bearings of
     * the ground the stencil looks out three distances and keeps the steepest rise it finds; the
     * sine of that angle is the share of that bearing's sky the ground has lost, and the mean over
     * the eight, weighted by how bright each bearing's sky is, is the share lost altogether. Ground
     * that is lower than its surroundings loses nothing, since a horizon below the eye blocks
     * nothing. Where the samples fall is [ReliefHorizon]'s business.
     *
     * This is a horizon over a few cells rather than the full sky-view factor, which would integrate
     * to the true horizon in every direction: at map scale the difference is invisible and the cost
     * is the whole of it.
     */
    fun openness(
        x: Int,
        y: Int,
        elevation: FloatField,
        scale: Float,
        horizon: ReliefHorizon,
        sky: Sky = DAYLIGHT
    ): Float {
        val here = elevation.sample(x, y)
        val brightness = sky.brightness
        var blocked = 0f
        for (bearing in 0 until HORIZON_BEARINGS) {
            var steepest = 0f
            for (further in 0 until HORIZON_STEPS) {
                val sample = bearing * HORIZON_STEPS + further
                val rise = (elevation.sample(x + horizon.columns[sample], y + horizon.rows[sample]) - here) *
                    scale
                val tangent = rise / horizon.strides[sample]
                if (tangent > steepest) steepest = tangent
            }
            // Weighted by how bright that quarter of the sky is, so a ridge standing between the
            // ground and the sun costs it more light than the same ridge behind it.
            blocked += brightness[bearing] * (steepest / sqrt(steepest * steepest + 1f))
        }
        return 1f - blocked / sky.brightnessTotal
    }

    /**
     * The single lamp's exaggeration at a 512 grid.
     *
     * Twelve, which is what every render before the sky model was drawn at and what the
     * engraving's own gradient is still scaled by.
     */
    private const val SLOPE_SCALE_AT_512 = 12f

    /** Shortest reach of the openness stencil at a 512 grid; the other two are twice and four times it. */
    private const val OPENNESS_STEP_AT_512 = 2

    /** How many distances out each bearing looks. Three, at r, 2r and 4r. */
    const val HORIZON_STEPS = 3

    /** The eight compass bearings of the ground the horizon is measured along. */
    const val HORIZON_BEARINGS = 8

    /** The eight bearings as unit vectors on the ground, for the lamps and the horizon alike. */
    internal val BEARING_UNIT_EAST = floatArrayOf(
        1f, ROOT_HALF, 0f, -ROOT_HALF, -1f, -ROOT_HALF, 0f, ROOT_HALF
    )
    internal val BEARING_UNIT_SOUTH = floatArrayOf(
        0f, ROOT_HALF, 1f, ROOT_HALF, 0f, -ROOT_HALF, -1f, -ROOT_HALF
    )

    /**
     * How much of each eighth of the sky faces the light, in the same order: east, south-east,
     * south, south-west, west, north-west, north, north-east.
     *
     * `(1 + cos(bearing - north-west)) / 2`: 1 in the north-west, 0 opposite it. Written out rather
     * than taken from a cosine at run time, because the compute shader's copy has to be the same to
     * the bit. They come to four exactly, whatever the haze, which is what makes the normalisation
     * a constant.
     */
    private val SKY_TOWARD_LIGHT = floatArrayOf(
        0.14644661f, 0f, 0.14644661f, 0.5f, 0.85355339f, 1f, 0.85355339f, 0.5f
    )

    /**
     * The single lamp, in the north-west at 32 degrees above the horizon.
     *
     * The cartographic convention, and the exact numbers every render before the sky model used:
     * x eastward, y southward, so a light in the north-west points west and north. Kept so that
     * [RenderOptions.singleLamp] reproduces the older picture rather than approximating it.
     */
    private const val LAMP_EAST = -0.6f
    private const val LAMP_SOUTH = -0.6f
    private const val LAMP_HEIGHT = 0.53f

    /** What the single lamp's factor is on flat ground, and how far it swings from there. */
    private const val LAMP_AMBIENT = 0.72f
    private const val LAMP_SWING = 0.55f

    /** How far a lamp at 32 degrees lies from the vertical: the cosine of its altitude. */
    private const val LAMP_REACH = 0.848f

    /**
     * How hazy the day is: 0 a clear sky, 1 a fully overcast one.
     *
     * The one number this model is calibrated on, and it moves two things together because a real
     * sky moves them together. A clear sky sends about **0.15** of its light diffusely and is some
     * **ten times** brighter around the sun than opposite it; an overcast sky sends **all** of its
     * light diffusely and is the same brightness whichever way you look. Anything between is a
     * haze, and [skyShare] and [skyBrightness] interpolate the pair.
     *
     * The value is derived rather than chosen: it is the haze at which the shaded relief has the
     * same contrast as the single lamp it replaces, over the land of seed 234475 at 512.
     * `ReliefShadingTest` sweeps the haze, finds that value and asserts this constant is it — so
     * the derivation is a guard rather than a note. Clearer than this and the shadows harden back
     * toward the thing the model exists to stop doing; hazier and the drawing goes flat.
     */
    const val HAZE: Float = 0.10f

    /** The diffuse share of daylight at a given haze: clear-sky 0.15, overcast 1. */
    fun skyShare(haze: Float): Float = CLEAR_SKY_DIFFUSE + (1f - CLEAR_SKY_DIFFUSE) * haze

    /** How bright one eighth of the sky is at a given haze: clear-sky a tenth opposite the sun. */
    fun skyBrightness(haze: Float, bearing: Int): Float {
        val evenness = CLEAR_SKY_OPPOSITE + (1f - CLEAR_SKY_OPPOSITE) * haze
        return evenness + (1f - evenness) * SKY_TOWARD_LIGHT[bearing]
    }

    private const val CLEAR_SKY_DIFFUSE = 0.15f
    private const val CLEAR_SKY_OPPOSITE = 0.1f

    /**
     * How much light ordinary ground receives, as a share of what a flat open plain receives.
     *
     * There is no exaggeration constant in this model — the factor a colour is multiplied by *is*
     * the light the ground gets — but there is a question of what "unshaded" means, and flat open
     * ground is the wrong answer to it: almost no ground is flat, so measuring against a plain
     * would darken every map by the amount ordinary country is rougher than one. This is the median
     * illumination over the land of seed 234475 at 512 under this day's [HAZE], which is what
     * ordinary country comes to; dividing by it leaves the sheet's overall tone where the single
     * lamp had it and lets only the relief move. `ReliefShadingTest` measures it at the haze it
     * derives and asserts this is that figure.
     *
     * It was 0.936 until the 2.0.x line's routing came across, 0.9318 until S2's fourth pass,
     * 0.9582 until I1 and 0.9473 until I3. Nothing in this file changed any of those times; the ground did. The water
     * is routed by the steepest triangular facet now, so twelve rounds of erosion cut different
     * rock; the base relief carries a texture proportional to the ground's own relief, so ordinary
     * country came out smoother and caught more of the light; and I1 wrote the ice sheet's own
     * surface into the elevation field, which is a smooth dome with a kilometre-high flank round
     * it, so the land of seed 234475 is a steeper place than it was and ordinary country is back
     * down to 0.9421; and the merge of I1 with W2 moved it once more, to 0.9473, because W2's
     * pressure wind reaches the provisional climate the glaciation stage carves from, so the two
     * sides between them left a third world under the lamp and neither side's figure described
     * it. I3 moved it back up, to 0.9526, and it is the first of these moves that made the ground
     * *smoother*: the sheet's surface is the lower envelope of the profiles rising from its whole
     * margin instead of the profile rising from the nearest margin cell, so the steps of up to
     * 1,965 m that a nearest-cell datum put between one ice cell and the next are gone, and the
     * comb of one-cell four-hundred-metre walls that a balance of nothing was growing over the
     * polar desert is gone with them. Both were sheer faces written into the elevation field, both
     * were catching a shadow, and ordinary country is half a percent brighter without them.
     * Re-derived rather than argued with, because the figure is defined as that
     * median and for no other reason — and re-derived rather than absorbed into the drift bar,
     * because leaving it stale would draw every map one and a half percent off the tone the lamp
     * set, which is the one thing this constant exists to hold still. Fix 2 moved it to 0.9362,
     * with both the ground and the reading of it: the world is shaped on the ground's ruler, and a
     * slope facing north or south is lit as steep as it stands rather than as half that, so
     * ordinary country catches less light (docs/DESIGN_LEDGER.md, Fix 2).
     */
    private const val ORDINARY_GROUND = 0.9362f

    /** Read by `ReliefShadingTest`, which is where the figure above comes from. */
    val ordinaryGround: Float get() = ORDINARY_GROUND

    /**
     * How dark and how bright the shading is allowed to get.
     *
     * The same pair the single lamp has always been clamped to, so that a style's own
     * [MapStyle.reliefStrength] is applied to the same range it was applied to before.
     */
    private const val DARKEST = 0.45f
    private const val BRIGHTEST = 1.35f
}

/**
 * Where the sky's horizon is sampled round a cell: along each of the eight compass bearings of
 * the ground, at [ReliefShading.HORIZON_STEPS] distances doubling from the stencil's shortest step, the whole
 * cell nearest that point on the ground and the ground's distance to it.
 *
 * The bearings are the ground's, so on cells twice as wide as they are tall the step north is
 * twice as many rows as the step east is columns, and a diagonal lands where the ground's
 * diagonal does rather than on the grid's; the offset is rounded to a whole cell and the
 * stride is that cell's own distance on the ground, so every tangent read is the true rise
 * over the true run to the sample it was read at. Worked out once for a grid and handed to
 * every cell; the compute shader is handed the same arrays, so the two sample the same cells.
 */
class ReliefHorizon(
    /** Column offset of each sample, bearing by bearing, nearest first. */
    val columns: IntArray,
    /** Row offset of each sample, in the same order. */
    val rows: IntArray,
    /** The distance on the ground to each sample, in cell widths. */
    val strides: FloatArray
) {
    companion object {
        /**
         * The samples for a stencil whose shortest step is [step] cell widths, on cells
         * [cellHeightInCellWidths] as tall as they are wide.
         */
        fun of(step: Int, cellHeightInCellWidths: Double): ReliefHorizon {
            val samples = ReliefShading.HORIZON_BEARINGS * ReliefShading.HORIZON_STEPS
            val columns = IntArray(samples)
            val rows = IntArray(samples)
            val strides = FloatArray(samples)
            for (bearing in 0 until ReliefShading.HORIZON_BEARINGS) {
                var reach = step.toDouble()
                for (further in 0 until ReliefShading.HORIZON_STEPS) {
                    val sample = bearing * ReliefShading.HORIZON_STEPS + further
                    val column = kotlin.math.round(ReliefShading.BEARING_UNIT_EAST[bearing] * reach).toInt()
                    val row = kotlin.math.round(ReliefShading.BEARING_UNIT_SOUTH[bearing] * reach / cellHeightInCellWidths).toInt()
                    val down = row * cellHeightInCellWidths
                    columns[sample] = column
                    rows[sample] = row
                    strides[sample] = sqrt(column.toDouble() * column + down * down).toFloat()
                    reach += reach
                }
            }
            return ReliefHorizon(columns, rows, strides)
        }
    }
}

/** The reciprocal of root two, written once: the diagonal bearings' components on the ground. */
private const val ROOT_HALF = 0.70710678f
