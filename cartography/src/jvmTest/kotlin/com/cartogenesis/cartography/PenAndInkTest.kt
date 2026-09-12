package com.cartogenesis.cartography

import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Whether [MapStyle.PEN_AND_INK] draws what an engraver would have drawn.
 *
 * A style is a claim about appearance, and most of what this one claims is settled by looking at
 * the gallery. Two of its claims are not, because the picture it replaced looked plausible at a
 * glance and was wrong in a way only a measurement finds:
 *
 *  - **The strokes follow the ground.** The hatch this replaced laid every stroke at one fixed
 *    bearing whatever the slope faced, so a range read as a scribble. The measurement below takes
 *    the *rendered pixels*, recovers the direction the ink actually runs in with a structure
 *    tensor, and compares it with the aspect. The old rule is reproduced here as the control and
 *    fails by the width of the whole answer.
 *  - **A stroke is a share of the map, not a count of pixels.** The old comb had a five-cell
 *    period, so a 2048 render carried four times as many strokes as a 512 one over the same ground
 *    and the two were not the same drawing. The measurement below is in map fractions and the
 *    control fails it by a factor of four.
 */
class PenAndInkTest {

    private companion object {

        /**
         * How far the ink may run from the aspect, in degrees, averaged over the land it is drawn
         * on.
         *
         * Not zero, and could not be: the structure tensor is measured over a window a stroke and
         * a half across, and on real ground the aspect turns within that window — every ridge crest
         * and every valley floor is a place where two directions meet inside one window and the
         * answer is the average of them. What the bar has to separate is ink that follows the
         * ground from ink that ignores it, and those two are nowhere near each other: the engraving
         * measures 19.4 degrees over 6110 windows and the fixed-bearing comb it replaced measures
         * 47.0, which is what a bearing chosen at random scores against an aspect that is uniform.
         * The bar sits at 30, giving away a third of the headroom, so a change that halved how well
         * the strokes followed the ground would still be caught.
         */
        const val MAX_MEAN_ASPECT_ERROR_DEGREES = 30.0

        /**
         * How far the stroke spacing may drift between resolutions, as a share of the smaller.
         *
         * The lattice is a whole number of cells and every other length is a float multiple of it,
         * so the drawing scales exactly and the only thing that can move the answer is the soft
         * edge, which is a fixed six tenths of a cell because antialiasing belongs to the pixel
         * grid rather than to the map. Measured at 1.1% across 512, 1024, 2048 and 4096; the bar is
         * 5%. The comb it replaced had a five-cell period at every size — a twentieth of the map at
         * 512 and an eightieth at 2048 — and wanders by a factor of two under the same ruler.
         */
        const val MAX_SPACING_DRIFT = 0.05

        /**
         * Every style's 512 fantasy render, hashed, as it stood at v2.0.0 — before F9 touched
         * anything.
         *
         * F9 redraws one style, and the cheapest way to be sure it redrew only that one is to hold
         * the other ten to the pixel. Pen and ink's own entry is deliberately absent: it is the one
         * that is supposed to have changed.
         */
        val UNCHANGED_STYLES: Map<MapStyle, Int> = mapOf(
            MapStyle.ATLAS to 1505162113,
            MapStyle.VELLUM to 1731718276,
            MapStyle.INK_WASH to 940010414,
            MapStyle.NAUTICAL to -615327928,
            MapStyle.MIDNIGHT to -337638301,
            MapStyle.SCHOOLROOM to 1860155522,
            MapStyle.VERDANT to -206922609,
            MapStyle.SCROLL to -1085895034,
            MapStyle.MARS to 1710409417,
            MapStyle.CLEAR to -958663001
        )

        /** The gallery's world, at the size the guards measure on. */
        val WORLD: WorldMap by lazy {
            WorldGenerationEngine.generateBlocking(
                WorldGenConfig(seed = 234475L, width = 512, height = 512)
            )
        }

        /** How far the structure tensor looks, in stroke pitches. */
        const val TENSOR_WINDOW_PITCHES = 1.5f

        /** Only ground with real ink on it is asked about: below this the paper is meant to be blank. */
        const val MEASURED_SLOPE_FLOOR = 0.14f

        /** A quarter turn: the angle from the strokes to the steepest change in the picture. */
        val QUARTER_TURN = Math.PI / 2

        /** Every fourth pixel each way: sixteen times fewer windows, and the mean does not move. */
        const val SAMPLE_STEP = 2

        /** Where two neighbouring strokes stop reading as one stroke and start reading as a break. */
        val SEAM_RADIANS = Math.toRadians(60.0)

        /** The ink gain the fixed-bearing hatch used, so the control is the control that shipped. */
        const val OLD_INK_GAIN = 1.15f
    }

    @Test
    fun `every other style is left alone to the pixel`() {
        UNCHANGED_STYLES.forEach { (style, expected) ->
            assertEquals(
                expected,
                fingerprint(MapRasterizer.rasterize(WORLD, RenderOptions(style = style))),
                "${style.label} no longer renders the pixels it rendered at 2.0.0"
            )
        }
        println(
            "PENINK the other ${UNCHANGED_STYLES.size} styles are unchanged at 512; " +
                "pen and ink now fingerprints " +
                fingerprint(
                    MapRasterizer.rasterize(WORLD, RenderOptions(style = MapStyle.PEN_AND_INK))
                )
        )
    }

    @Test
    fun `the ink runs down the slope, and the fixed-bearing hatch did not`() {
        val world = WORLD
        val width = world.width
        val plan = EngravingPlan(width)

        val engraved = MapRasterizer.rasterize(
            world,
            RenderOptions(style = MapStyle.PEN_AND_INK, showCoastline = false, showLakes = false)
        )
        val comb = fixedBearingHatch(world)

        val engravedError = meanAspectError(world, engraved, plan)
        val combError = meanAspectError(world, comb, plan)

        println(
            "PENINK aspect error over %d windows: engraved %.1f degrees, the comb it replaced %.1f"
                .format(engravedError.windows, engravedError.meanDegrees, combError.meanDegrees)
        )
        println("PENINK %s".format(slopeReport(world, plan)))
        println("PENINK %s".format(seamReport(world, plan)))

        assertTrue(
            engravedError.windows > 3000,
            "only ${engravedError.windows} windows qualified; the measurement says nothing"
        )
        assertTrue(
            engravedError.meanDegrees <= MAX_MEAN_ASPECT_ERROR_DEGREES,
            "the ink runs %.1f degrees from the aspect on average, past %.1f"
                .format(engravedError.meanDegrees, MAX_MEAN_ASPECT_ERROR_DEGREES)
        )
        assertTrue(
            combError.meanDegrees > MAX_MEAN_ASPECT_ERROR_DEGREES,
            "the control passed, so the measurement cannot tell the two apart"
        )
    }

    @Test
    fun `a stroke is the same share of the map at every size`() {
        val sizes = listOf(512, 1024, 2048, 4096)
        val engraved = sizes.associateWith { strokeSpacingFraction(it, engraved = true) }
        val comb = sizes.associateWith { strokeSpacingFraction(it, engraved = false) }

        println(
            "PENINK stroke spacing, share of the map (and in cells) — engraved " +
                sizes.joinToString(", ") {
                    "$it: %.5f (%.1f)".format(engraved[it], engraved[it]!! * it)
                }
        )
        println(
            "PENINK stroke spacing, share of the map (and in cells) — the comb it replaced " +
                sizes.joinToString(", ") { "$it: %.5f (%.1f)".format(comb[it], comb[it]!! * it) }
        )

        val smallest = engraved.values.min()
        val largest = engraved.values.max()
        val drift = largest / smallest - 1.0
        println("PENINK engraved spacing drifts %.1f%% across 512..4096".format(drift * 100))
        assertTrue(
            drift <= MAX_SPACING_DRIFT,
            "the engraved stroke spacing drifts %.1f%% across 512..4096, past %.1f%%"
                .format(drift * 100, MAX_SPACING_DRIFT * 100)
        )

        val combDrift = comb.values.max() / comb.values.min() - 1.0
        assertTrue(
            combDrift > MAX_SPACING_DRIFT,
            "the control passed, so the measurement cannot tell a map fraction from a pixel count"
        )
    }

    // ---- measurements ----

    private fun fingerprint(pixels: IntArray): Int {
        var hash = 17
        for (p in pixels) hash = hash * 31 + p
        return hash
    }

    private class AspectError(val meanDegrees: Double, val windows: Int)

    /**
     * How far the direction the ink actually runs in sits from the aspect, over the whole land.
     *
     * The direction of the ink is recovered from the picture rather than asked of the code: the
     * structure tensor of the image gradient over a window a stroke pitch and a half across has its
     * principal axis along the *steepest change* in the picture, which for a field of parallel
     * strokes is across them. So the strokes run a quarter turn from that axis, and the question is
     * how far that is from the aspect. Orientation is modulo half a turn — a stroke has no head or
     * tail — so the error folds into 0 to 90 degrees.
     */
    private fun meanAspectError(
        world: WorldMap,
        pixels: IntArray,
        plan: EngravingPlan
    ): AspectError {
        val width = world.width
        val height = world.height
        val land = world.sea.isLand
        val elevation = world.sea.relativeElevation
        val reach = plan.gradientStencilCells
        val window = (plan.hachureLatticeCells * TENSOR_WINDOW_PITCHES).toInt().coerceAtLeast(2)

        var total = 0.0
        var windows = 0
        var y = window + reach
        while (y < height - window - reach) {
            var x = window + reach
            while (x < width - window - reach) {
                val gradientX =
                    (elevation.sample(x + reach, y) - elevation.sample(x - reach, y)) *
                        plan.gradientScale
                val gradientY =
                    (elevation.sample(x, y + reach) - elevation.sample(x, y - reach)) *
                        plan.gradientScale
                val slope = sqrt(gradientX * gradientX + gradientY * gradientY)
                if (slope >= MEASURED_SLOPE_FLOOR && allLand(land, width, x, y, window)) {
                    val tensor = structureTensor(pixels, width, x, y, window)
                    if (tensor != null) {
                        // The picture's steepest change runs across the strokes; the strokes run a
                        // quarter turn from it, and the aspect is what they should be along.
                        val inkDirection = tensor + QUARTER_TURN
                        total += foldedDifference(
                            inkDirection, atan2(gradientY.toDouble(), gradientX.toDouble())
                        )
                        windows++
                    }
                }
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }
        return AspectError(
            if (windows == 0) 0.0 else Math.toDegrees(total / windows),
            windows
        )
    }

    private fun allLand(land: BooleanArray, width: Int, x: Int, y: Int, window: Int): Boolean {
        for (dy in -window..window) {
            val row = (y + dy) * width
            for (dx in -window..window) if (!land[row + x + dx]) return false
        }
        return true
    }

    /** The principal axis of the image gradient's structure tensor, or null on flat ink. */
    private fun structureTensor(
        pixels: IntArray,
        width: Int,
        x: Int,
        y: Int,
        window: Int
    ): Double? {
        var xx = 0.0
        var yy = 0.0
        var xy = 0.0
        for (dy in -window..window) {
            for (dx in -window..window) {
                val i = (y + dy) * width + x + dx
                val ix = (luminance(pixels[i + 1]) - luminance(pixels[i - 1])).toDouble()
                val iy = (luminance(pixels[i + width]) - luminance(pixels[i - width])).toDouble()
                xx += ix * ix
                yy += iy * iy
                xy += ix * iy
            }
        }
        if (xx + yy < 1.0) return null
        return 0.5 * atan2(2.0 * xy, xx - yy)
    }

    private fun luminance(argb: Int): Int =
        ((argb shr 16 and 0xFF) + (argb shr 8 and 0xFF) + (argb and 0xFF)) / 3

    /** The angle between two orientations, in radians, folded into 0..pi/2. */
    private fun foldedDifference(a: Double, b: Double): Double {
        // Doubling the angles makes two orientations a half turn apart the same direction, which is
        // what an undirected stroke is; halving the answer brings it back into 0..pi/2.
        val doubled = 2 * (a - b)
        return abs(atan2(sin(doubled), cos(doubled))) / 2
    }

    /**
     * The hatch this style used to draw, reproduced so the guards have something to fail against.
     *
     * A diagonal comb at one bearing with a five-cell period, thresholded against the hillshade —
     * `MapStyle.inked` and the line-art branch of `MapRasterizer` as they stood at 2.0.0.
     */
    private fun fixedBearingHatch(world: WorldMap): IntArray {
        val width = world.width
        val height = world.height
        val elevation = world.sea.relativeElevation
        val style = MapStyle.PEN_AND_INK
        val zScale = MapRasterizer.hillshadeScale(width)
        val pixels = IntArray(width * height) { style.paper }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                if (!world.sea.isLand[i]) continue
                val dzdx = (elevation.sample(x + 1, y) - elevation.sample(x - 1, y)) * zScale
                val dzdy = (elevation.sample(x, y + 1) - elevation.sample(x, y - 1)) * zScale
                val length = sqrt(dzdx * dzdx + dzdy * dzdy + 1f)
                val lambert = (-dzdx * -0.6f - dzdy * -0.6f + 0.53f) / length
                val shade = (0.72f + 0.55f * lambert).coerceIn(0.45f, 1.35f)
                val steepness = (1f - shade).coerceAtLeast(0f)
                val hatch = (((x + y) % 5) + 1) / 6f
                if (steepness * OLD_INK_GAIN > hatch) pixels[i] = style.coastline
            }
        }
        return pixels
    }

    /** What share of the land the aspect turns hard enough on to break a stroke. */
    private fun seamReport(world: WorldMap, plan: EngravingPlan): String {
        val width = world.width
        val height = world.height
        val elevation = world.sea.relativeElevation
        val reach = plan.gradientStencilCells
        var measured = 0
        var seams = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                if (!world.sea.isLand[i]) continue
                val here = aspectOrNull(elevation, x, y, reach, plan) ?: continue
                measured++
                val east = aspectOrNull(elevation, x + 1, y, reach, plan)
                val south = aspectOrNull(elevation, x, y + 1, reach, plan)
                val turned = (east != null && foldedDifference(here, east) > SEAM_RADIANS) ||
                    (south != null && foldedDifference(here, south) > SEAM_RADIANS)
                if (turned) seams++
            }
        }
        return "the aspect turns more than %d degrees between neighbours on %.2f%% of the %d inked cells"
            .format(
                Math.toDegrees(SEAM_RADIANS).toInt(),
                seams * 100.0 / measured.coerceAtLeast(1),
                measured
            )
    }

    private fun aspectOrNull(
        elevation: com.cartogenesis.worldgen.model.FloatField,
        x: Int,
        y: Int,
        reach: Int,
        plan: EngravingPlan
    ): Double? {
        val gradientX =
            (elevation.sample(x + reach, y) - elevation.sample(x - reach, y)) * plan.gradientScale
        val gradientY =
            (elevation.sample(x, y + reach) - elevation.sample(x, y - reach)) * plan.gradientScale
        val slope = sqrt(gradientX * gradientX + gradientY * gradientY)
        if (slope < MEASURED_SLOPE_FLOOR) return null
        return atan2(gradientY.toDouble(), gradientX.toDouble())
    }

    /** Where the land's slopes actually sit, which is what the ink gain and the floor are set from. */
    private fun slopeReport(world: WorldMap, plan: EngravingPlan): String {
        val width = world.width
        val elevation = world.sea.relativeElevation
        val reach = plan.gradientStencilCells
        val slopes = ArrayList<Float>()
        for (i in world.sea.isLand.indices) {
            if (!world.sea.isLand[i]) continue
            val x = i % width
            val y = i / width
            val gradientX =
                (elevation.sample(x + reach, y) - elevation.sample(x - reach, y)) * plan.gradientScale
            val gradientY =
                (elevation.sample(x, y + reach) - elevation.sample(x, y - reach)) * plan.gradientScale
            slopes.add(sqrt(gradientX * gradientX + gradientY * gradientY))
        }
        slopes.sort()
        fun at(fraction: Double) = slopes[(slopes.size * fraction).toInt().coerceAtMost(slopes.size - 1)]
        val gain = MapStyle.PEN_AND_INK.inkGain
        return ("land slope over %d cells: 10th %.3f, median %.3f, 75th %.3f, 90th %.3f, 99th %.3f " +
            "(blank below %.2f, fully black at %.2f, widest stroke at %.2f)").format(
            slopes.size, at(0.10), at(0.50), at(0.75), at(0.90), at(0.99),
            EngravingPlan.SLOPE_FLOOR,
            EngravingPlan.SLOPE_FLOOR + EngravingPlan.FULL_INK_AT / gain,
            EngravingPlan.SLOPE_FLOOR + 1f / gain
        )
    }

    /**
     * The mean distance between one mark and the next, as a share of the map's width.
     *
     * Measured on a cone rather than on a world, because a cone is the same shape at every
     * resolution: whatever the drawing does to it at 2048 ought to be exactly the 512 picture
     * magnified, so any difference is the drawing's and not the ground's. The cone also turns the
     * aspect through every bearing, so the measurement is not an artefact of one direction — a
     * horizontal scan crosses the strokes at every angle and what it counts is how often ink
     * starts.
     */
    private fun strokeSpacingFraction(width: Int, engraved: Boolean): Double {
        val plan = EngravingPlan(width)
        val gain = MapStyle.PEN_AND_INK.inkGain
        // Half way up the ink ramp, so the strokes are neither hairlines nor a solid mass.
        val slope = EngravingPlan.SLOPE_FLOOR + 0.5f / gain
        val centre = width / 2f
        val inner = width * 0.12f
        val outer = width * 0.45f

        // Sampled at the same *map* positions whatever the resolution — every cell at 512, every
        // fourth at 2048 — so the measurement's own pixel quantisation is identical at each size
        // and any drift left in the answer belongs to the drawing rather than to the ruler.
        val step = (width / 512).coerceAtLeast(1)

        var runs = 0L
        var scanned = 0L
        var y = 0
        while (y < width) {
            var wasInk = false
            var x = 0
            while (x < width) {
                val dx = x - centre
                val dy = y - centre
                val radius = sqrt(dx * dx + dy * dy)
                if (radius in inner..outer) {
                    val ink = if (engraved) {
                        Engraving.hachure(
                            x, y, dx / radius * slope, dy / radius * slope, plan, gain
                        ) > 0.5f
                    } else {
                        // The comb this replaced: a five-cell diagonal period, in cells, whatever
                        // the map's size.
                        0.5f * OLD_INK_GAIN > (((x + y) % 5) + 1) / 6f
                    }
                    scanned += step
                    if (ink && !wasInk) runs++
                    wasInk = ink
                } else {
                    wasInk = false
                }
                x += step
            }
            y += step
        }
        return scanned.toDouble() / runs.coerceAtLeast(1) / width
    }

}
