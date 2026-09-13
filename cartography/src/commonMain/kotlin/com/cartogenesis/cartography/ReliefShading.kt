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
 *    faces it, and each is as bright as its quarter of the sky is — brightest around the
 *    conventional north-west light and a quarter of that opposite it (see [SKY_BRIGHTNESS]). So a
 *    ridge reads whichever way it runs, and the north-west still reads as the lit side.
 *  - **The sky**, which is what fills the shadow. A surface walled in by higher ground sees less of
 *    the sky than an open plain does, and [openness] measures how much: the horizon angle along
 *    eight bearings over a short stencil, weighted by the brightness of the sky each bearing hides.
 *    This is the term that means no face of a hill is ever unlit, only dimmer, which is what a
 *    person actually sees on an overcast morning and what `ReliefShadingTest` holds the model to.
 *
 * Mark's four-lamp oblique-weighted relief (1992, USGS Open-File Report 92-422) was written first
 * and measured worse than the lamp it replaced: four lamps inside a 135-degree arc leave a whole
 * quadrant with no direct light at all, so the far side of a cone came out darker than the single
 * lamp had left it. The dome above is what fixed it; the figures are in the plan's F13 row.
 *
 * The single lamp is kept, exactly as it was, because a reader may prefer it — see
 * [RenderOptions.singleLamp]. Under it this file reproduces the pre-F13 shading bit for bit.
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
    fun of(elevation: FloatField, isLand: BooleanArray, singleLamp: Boolean): FloatArray {
        val width = elevation.width
        val height = elevation.height
        val shade = FloatArray(width * height) { 1f }
        val scale = slopeScale(width)
        val step = opennessStep(width)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (!isLand[row + x]) continue
                shade[row + x] = at(x, y, elevation, scale, step, singleLamp)
            }
        }
        return shade
    }

    /**
     * The lighting factor at one cell.
     *
     * [scale] comes from [slopeScale] and [step] from [opennessStep]; both are properties of the
     * grid rather than of the cell, so they are worked out once and handed in.
     */
    fun at(
        x: Int,
        y: Int,
        elevation: FloatField,
        scale: Float,
        step: Int,
        singleLamp: Boolean
    ): Float {
        val eastward = (elevation.sample(x + 1, y) - elevation.sample(x - 1, y)) * scale
        val southward = (elevation.sample(x, y + 1) - elevation.sample(x, y - 1)) * scale
        val normalLength = sqrt(eastward * eastward + southward * southward + 1f)

        if (singleLamp) {
            val lambert =
                (-eastward * LAMP_EAST - southward * LAMP_SOUTH + LAMP_HEIGHT) / normalLength
            return (LAMP_AMBIENT + LAMP_SWING * lambert).coerceIn(DARKEST, BRIGHTEST)
        }

        val direct = directLight(eastward, southward, normalLength)
        val sky = openness(x, y, elevation, scale, step)
        val illumination = SKY_SHARE * sky + (1f - SKY_SHARE) * (direct / LAMP_HEIGHT)
        return (illumination / ORDINARY_GROUND).coerceIn(DARKEST, BRIGHTEST)
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
    fun directLight(eastward: Float, southward: Float, normalLength: Float): Float {
        // Each lamp contributes as this slope faces it and as bright as its quarter of the sky is.
        // A lamp below the local horizon contributes nothing, which is what the clamp at zero is;
        // the brightnesses total a constant, so flat ground comes out with exactly the light one
        // lamp of the same altitude would have given it.
        var direct = 0f
        for (bearing in 0 until HORIZON_BEARINGS) {
            val bearingEast = BEARING_UNIT_EAST[bearing]
            val bearingSouth = BEARING_UNIT_SOUTH[bearing]
            val lambert = (
                -eastward * bearingEast * LAMP_REACH -
                    southward * bearingSouth * LAMP_REACH + LAMP_HEIGHT
                ) / normalLength
            if (lambert > 0f) direct += SKY_BRIGHTNESS[bearing] * lambert
        }
        return direct / SKY_BRIGHTNESS_TOTAL
    }

    /**
     * How much of the sky the ground at a cell can see: 1 on an open plain, toward 0 in a slot.
     *
     * The cheap horizon estimate the sky term needs. Along each of the eight grid bearings the
     * stencil looks out three distances and keeps the steepest rise it finds; the sine of that
     * angle is the share of that bearing's sky the ground has lost, and the mean over the eight,
     * weighted by how bright each bearing's sky is, is the share lost altogether. Ground that is
     * lower than its surroundings loses nothing, since a horizon below the eye blocks nothing.
     *
     * This is a horizon over a few cells rather than the full sky-view factor, which would integrate
     * to the true horizon in every direction: at map scale the difference is invisible and the cost
     * is the whole of it.
     */
    fun openness(x: Int, y: Int, elevation: FloatField, scale: Float, step: Int): Float {
        val here = elevation.sample(x, y)
        var blocked = 0f
        for (bearing in 0 until HORIZON_BEARINGS) {
            val eastward = BEARING_EAST[bearing]
            val southward = BEARING_SOUTH[bearing]
            var steepest = 0f
            var reach = step
            var stride = BEARING_LENGTH[bearing] * step
            for (further in 0 until HORIZON_STEPS) {
                val rise = (elevation.sample(x + eastward * reach, y + southward * reach) - here) *
                    scale
                val tangent = rise / stride
                if (tangent > steepest) steepest = tangent
                reach += reach
                stride += stride
            }
            // Weighted by how bright that quarter of the sky is, so a ridge standing between the
            // ground and the sun costs it more light than the same ridge behind it.
            blocked += SKY_BRIGHTNESS[bearing] * (steepest / sqrt(steepest * steepest + 1f))
        }
        return 1f - blocked / SKY_BRIGHTNESS_TOTAL
    }

    /**
     * The single lamp's exaggeration at a 512 grid.
     *
     * Twelve, which is what every render before F13 was drawn at and what the engraving's own
     * gradient is still scaled by.
     */
    private const val SLOPE_SCALE_AT_512 = 12f

    /** Shortest reach of the openness stencil at a 512 grid; the other two are twice and four times it. */
    private const val OPENNESS_STEP_AT_512 = 2

    /** How many distances out each bearing looks. Three, at r, 2r and 4r. */
    private const val HORIZON_STEPS = 3

    /** The eight grid bearings the horizon is measured along. */
    private const val HORIZON_BEARINGS = 8

    private val BEARING_EAST = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
    private val BEARING_SOUTH = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)

    /** Cells travelled per cell of reach: a diagonal step covers root two. */
    private val BEARING_LENGTH = floatArrayOf(
        1f, ROOT_TWO, 1f, ROOT_TWO, 1f, ROOT_TWO, 1f, ROOT_TWO
    )

    /** The same eight bearings as unit vectors, which is what a lamp's direction wants. */
    private val BEARING_UNIT_EAST = floatArrayOf(
        1f, ROOT_HALF, 0f, -ROOT_HALF, -1f, -ROOT_HALF, 0f, ROOT_HALF
    )
    private val BEARING_UNIT_SOUTH = floatArrayOf(
        0f, ROOT_HALF, 1f, ROOT_HALF, 0f, -ROOT_HALF, -1f, -ROOT_HALF
    )

    /**
     * How bright each eighth of the sky is, in the same order: east, south-east, south, south-west,
     * west, north-west, north, north-east.
     *
     * `SKY_BIAS + (1 - SKY_BIAS) · (1 + cos(bearing - north-west)) / 2` — brightest around the
     * conventional north-west light and dimmest opposite it, with [SKY_BIAS] a quarter, so the sky
     * behind the reader is a quarter as bright as the sky the light is in. That is between the CIE
     * overcast sky, which has no variation with bearing at all, and a clear one, where the sky
     * around the sun is ten times the rest. Written out rather than computed, so the compute
     * shader's copy is the same to the bit.
     *
     * They come to five exactly, which is eight times the mean of the cosine term: a fact of the
     * arithmetic rather than a choice, and what makes the normalisation a constant.
     */
    private val SKY_BRIGHTNESS = floatArrayOf(
        0.359835f, 0.25f, 0.359835f, 0.625f, 0.890165f, 1f, 0.890165f, 0.625f
    )
    private const val SKY_BRIGHTNESS_TOTAL = 5f

    /**
     * The single lamp, in the north-west at 32 degrees above the horizon.
     *
     * The cartographic convention, and the exact numbers every render before F13 used: x eastward,
     * y southward, so a light in the north-west points west and north. Kept to the last bit so that
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
     * How much of the light comes from the sky rather than from the sun.
     *
     * The diffuse share of daylight: about 0.15 to 0.20 of the light falling on a horizontal surface
     * under a clear sky, 0.3 to 0.4 under a hazy or lightly clouded one, and all of it under
     * overcast. A quarter is a bright day with a little haze in it, and it is the value inside that
     * range at which the shaded relief keeps the contrast of the lamp it replaces — the whole of the
     * calibration, since raising it flattens the drawing and lowering it hardens the shadows back
     * toward the thing this model exists to stop doing. `ReliefShadingTest` measures both.
     */
    private const val SKY_SHARE = 0.25f

    /**
     * How much light ordinary ground receives, as a share of what a flat open plain receives.
     *
     * There is no exaggeration constant in this model — the factor a colour is multiplied by *is*
     * the light the ground gets — but there is a question of what "unshaded" means, and flat open
     * ground is the wrong answer to it: almost no ground is flat, so measuring against a plain
     * would darken every map by the amount ordinary country is rougher than one. This is the median
     * illumination over the land of seed 234475 at 512, which is what ordinary country comes to;
     * dividing by it leaves the sheet's overall tone where the single lamp had it and lets only the
     * relief move. `ReliefShadingTest` measures both models and reports the two side by side.
     */
    private const val ORDINARY_GROUND = 0.933f

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

/** Root two and its reciprocal, written once: the diagonals of the stencil and of the lamps. */
private const val ROOT_TWO = 1.4142135f
private const val ROOT_HALF = 0.70710678f
