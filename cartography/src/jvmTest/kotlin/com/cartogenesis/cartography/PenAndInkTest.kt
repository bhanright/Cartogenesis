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
 *  - **A mark is a fixed count of pixels, not a share of the sheet.** A pen does not grow with the
 *    plate: an engraver handed a larger one draws the same hachure with the same nib and fits more
 *    of them on it. So the pitch below is measured in pixels and asserted identical at 512, 1024,
 *    2048 and 4096, and the stroke *count* over the same ground is asserted to grow as the square
 *    of the grid ratio. The control is the drawing enlarged with the sheet, which is what F9
 *    shipped first and what the review sent back: at 2048 its hachures are dashes thirty pixels
 *    long and its stipple is polka dots.
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
         * measures 20.0 degrees over 6110 windows and the fixed-bearing comb it replaced measures
         * 47.0, which is what a bearing chosen at random scores against an aspect that is uniform.
         * The bar sits at 30, giving away a third of the headroom, so a change that halved how well
         * the strokes followed the ground would still be caught.
         */
        const val MAX_MEAN_ASPECT_ERROR_DEGREES = 30.0

        /**
         * How far the stroke pitch may drift between resolutions, measured in pixels.
         *
         * Nought, near enough, and that is the point: every mark is a fixed count of pixels, so the
         * only thing that can move this is how the cone's own geometry falls across the grid.
         * Measured at 0.7% across 512, 1024, 2048 and 4096 — 8.96, 9.02, 9.02 and 9.03 pixels —
         * and the bar is 5%. The control, the same drawing enlarged with the sheet, which is what
         * F9 shipped first and what the review sent back, goes 8.96, 17.69, 35.18, 70.13: out by
         * a factor of nearly eight over the same range.
         */
        const val MAX_PITCH_DRIFT = 0.05

        /**
         * How far the stroke *count* may fall from the square of the grid ratio.
         *
         * The other half of the same property, and the half a reader sees: a pitch fixed in pixels
         * over a grid four times finer puts sixteen strokes where there was one, so the larger plate
         * carries more of the country at the same weight of line. Asserted rather than assumed,
         * because a mark that quietly kept a share of the width would hold the count instead and
         * this is what would catch it. Measured at 3.97, 15.90 and 63.55 times the 512 count for
         * 1024, 2048 and 4096, against four, sixteen and sixty-four. Bar 8%, which is loose enough
         * for the run-counting to miss the odd stroke where two nearly touch and tight enough that
         * a factor of four or of one fails outright.
         */
        const val MAX_DENSITY_DRIFT = 0.08

        /**
         * Every style's 512 fantasy render, hashed, as it stands today.
         *
         * A change detector rather than a claim about any particular colour: a chunk that means to
         * redraw one style can check here that it redrew only that one, and a chunk that means to
         * redraw them all re-records the lot and says so in its report. F9 wrote it with ten
         * entries and pen and ink deliberately absent, because that chunk changed exactly one
         * style; F13 changed every one of them — the climate reaches the land ramp, the sky
         * replaces the lamp and the sea carries depth contours — so all eleven were recorded again
         * against that chunk's renders. F17 changed every one of them again, from the other end: it
         * fills the drowned valleys the grid cannot hold and grades Earth's third of the shoreline
         * after the sea-level cut, so the land mask under all eleven is a different mask. Recorded
         * twice for that chunk, once for each of its two passes; how far the coastline actually
         * moved is measured rather than hashed, in `LittoralCoastTest` and in `GEOGRAPHY.md`.
         *
         * F14 and F17 were merged after both were written and all eleven were checked again: not
         * one moved. That is worth a line, because it is the answer to the question the two chunks
         * raise together — F14 rewrote the rasterizer around a traced, simplified shoreline, and at
         * this size and this zoom it draws the same pixels the raster did. Its generalisation is an
         * overlay at other zooms, and the fit render these hashes are taken from is untouched by it.
         *
         * F23 added the twelfth entry and changed none of the eleven, which is the whole of what a
         * new style is allowed to do: a style is a row of levers the raster already reads, so a
         * chunk that adds one and moves an existing hash has reached outside its own palette.
         */
        val RECORDED_STYLES: Map<MapStyle, Int> = mapOf(
            MapStyle.ATLAS to -173307292,
            MapStyle.VELLUM to -23465626,
            MapStyle.INK_WASH to 323555479,
            MapStyle.NAUTICAL to -965577701,
            MapStyle.MIDNIGHT to 580634097,
            MapStyle.SCHOOLROOM to 328982709,
            MapStyle.VERDANT to -2116422614,
            MapStyle.SCROLL to -1448446961,
            MapStyle.PEN_AND_INK to 1950662391,
            MapStyle.MARS to -1005799490,
            MapStyle.NATURAL to -1079641503,
            MapStyle.CLEAR to -361190112
        )

        /** The gallery's world, at the size the guards measure on. See [TestWorlds]. */
        val WORLD: WorldMap get() = TestWorlds.gallery

        /** How far the structure tensor looks, in stroke pitches. */
        const val TENSOR_WINDOW_PITCHES = 1.5f

        /** Only ground with real ink on it is asked about: below this the paper is meant to be blank. */
        const val MEASURED_SLOPE_FLOOR = 0.14f

        /** A quarter turn: the angle from the strokes to the steepest change in the picture. */
        val QUARTER_TURN = Math.PI / 2

        /** Every second pixel each way: four times fewer windows, and the mean does not move. */
        const val SAMPLE_STEP = 2

        /** Where two neighbouring strokes stop reading as one stroke and start reading as a break. */
        val SEAM_RADIANS = Math.toRadians(60.0)

        /** The ink gain the fixed-bearing hatch used, so the control is the control that shipped. */
        const val OLD_INK_GAIN = 1.15f
    }

    @Test
    fun `every style renders the pixels it is recorded as rendering`() {
        val drawn = MapStyle.entries.associateWith {
            fingerprint(MapRasterizer.rasterize(WORLD, RenderOptions(style = it)))
        }
        println(
            "PENINK fingerprints at 512: " +
                drawn.entries.joinToString(", ") { "${it.key.name} ${it.value}" }
        )
        RECORDED_STYLES.forEach { (style, expected) ->
            assertEquals(
                expected,
                drawn[style],
                "${style.label} no longer renders the pixels recorded for it"
            )
        }
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
    fun `the pen is the same size at every resolution, and lays more strokes on a bigger plate`() {
        val sizes = listOf(512, 1024, 2048, 4096)
        val pen = sizes.associateWith { strokeScan(it, enlarged = false) }
        val enlarged = sizes.associateWith { strokeScan(it, enlarged = true) }

        println(
            "PENINK stroke pitch in pixels (and strokes over the same share of the map) — the pen " +
                sizes.joinToString(", ") { "$it: %.2f (%d)".format(pen[it]!!.pixelPitch, pen[it]!!.runs) }
        )
        println(
            "PENINK stroke pitch in pixels (and strokes over the same share of the map) — enlarged " +
                "with the sheet " +
                sizes.joinToString(", ") {
                    "$it: %.2f (%d)".format(enlarged[it]!!.pixelPitch, enlarged[it]!!.runs)
                }
        )

        val drift = pen.values.maxOf { it.pixelPitch } / pen.values.minOf { it.pixelPitch } - 1.0
        println("PENINK the pen's pitch drifts %.1f%% across 512..4096".format(drift * 100))
        assertTrue(
            drift <= MAX_PITCH_DRIFT,
            "the stroke pitch drifts %.1f%% in pixels across 512..4096, past %.1f%%"
                .format(drift * 100, MAX_PITCH_DRIFT * 100)
        )

        // The other half of the same property, and the one a reader sees: a fixed pitch in pixels
        // over a grid n times finer means n squared times as many strokes on the same ground.
        sizes.filter { it != 512 }.forEach { side ->
            val ratio = pen[side]!!.runs.toDouble() / pen[512]!!.runs
            val expected = (side.toDouble() / 512) * (side.toDouble() / 512)
            println(
                "PENINK $side lays %.2f times as many strokes as 512 over the same ground, against %.0f"
                    .format(ratio, expected)
            )
            assertTrue(
                abs(ratio / expected - 1.0) <= MAX_DENSITY_DRIFT,
                ("$side lays %.2f times as many strokes as 512 over the same ground, not %.0f — " +
                    "the marks are not a fixed size in pixels").format(ratio, expected)
            )
        }

        val enlargedDrift =
            enlarged.values.maxOf { it.pixelPitch } / enlarged.values.minOf { it.pixelPitch } - 1.0
        assertTrue(
            enlargedDrift > MAX_PITCH_DRIFT,
            "the control passed, so the measurement cannot tell a pen from a magnifying glass"
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
        val zScale = ReliefShading.slopeScale(width)
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

    /** What one scan of the cone found: how far apart the marks are, and how many there were. */
    private class StrokeScan(val pixelPitch: Double, val runs: Long)

    /**
     * How far apart the marks are in pixels, and how many of them fall on the same share of the map.
     *
     * Measured on a cone rather than on a world, because a cone is the same shape at every
     * resolution: the ground is identical at 512 and at 4096, so anything that differs between the
     * two answers belongs to the drawing. The cone also turns the aspect through every bearing, so
     * the measurement is not an artefact of one direction — a horizontal scan crosses the strokes
     * at every angle and what it counts is how often ink starts.
     *
     * [enlarged] is the control: the same drawing blown up with the sheet, which is what sizing a
     * mark as a share of the width does and what F9 shipped first. It is the 512 plan's ink read at
     * map coordinates, so a stroke that is twelve pixels long at 512 is forty-eight at 2048 — which
     * is exactly the woodcut the review sent back.
     */
    private fun strokeScan(width: Int, enlarged: Boolean): StrokeScan {
        val plan = if (enlarged) EngravingPlan(512) else EngravingPlan(width)
        val gain = MapStyle.PEN_AND_INK.inkGain
        // Half way up the ink ramp, so the strokes are neither hairlines nor a solid mass.
        val slope = EngravingPlan.SLOPE_FLOOR + 0.5f / gain
        val centre = width / 2f
        val inner = width * 0.12f
        val outer = width * 0.45f

        var runs = 0L
        var scanned = 0L
        for (y in 0 until width) {
            var wasInk = false
            for (x in 0 until width) {
                val dx = x - centre
                val dy = y - centre
                val radius = sqrt(dx * dx + dy * dy)
                if (radius in inner..outer) {
                    // Under the control the same map position is read at the 512 grid's coordinates,
                    // so the whole picture arrives magnified by the grid ratio.
                    val readX = if (enlarged) x * 512 / width else x
                    val readY = if (enlarged) y * 512 / width else y
                    val ink = Engraving.hachure(
                        readX, readY, dx / radius * slope, dy / radius * slope, plan, gain
                    ) > 0.5f
                    scanned++
                    if (ink && !wasInk) runs++
                    wasInk = ink
                } else {
                    wasInk = false
                }
            }
        }
        return StrokeScan(scanned.toDouble() / runs.coerceAtLeast(1), runs)
    }

}
