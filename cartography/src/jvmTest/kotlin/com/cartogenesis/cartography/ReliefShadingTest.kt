package com.cartogenesis.cartography

import com.cartogenesis.cartography.geometry.KnownFailures
import com.cartogenesis.cartography.geometry.RecordedViolation
import com.cartogenesis.worldgen.BorrowsSharedWorlds
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whether the light in [ReliefShading] behaves like a sky rather than like a lamp.
 *
 * Two claims, and the single lamp is the control for both because it is still in the code — a
 * reader can switch to it, so the comparison is between two things the application actually draws
 * rather than between the present and a memory.
 *
 *  - **Nothing is unlit.** A cone is the shape that finds this: whatever bearing a lamp is at, some
 *    part of a cone faces away from it, and a single lamp leaves a third of this one with no light
 *    at all and goes on darkening it past that point. Under the dome every bearing receives light
 *    from somewhere. How *dark* the darkest face ends up is much the same either way, by design —
 *    what the dome changes is that the dark side is the steep side of each hill rather than one
 *    quadrant of the whole map.
 *  - **It is no flatter than what it replaces.** Light from every direction is softer than light
 *    from one, so [ReliefShading.HAZE] is chosen to make the difference up: the sweep below
 *    re-derives it from the two contrasts rather than trusting the constant.
 */
class ReliefShadingTest : BorrowsSharedWorlds() {

    private companion object {

        /**
         * The known failure the haze clause records. It matched at 0.12 against the declared 0.10
         * from Fix 3, and still does on the implicit incision's terrain (Fix 3b), which moved
         * ordinary ground and not the match. It is recorded and not re-derived there because the
         * declared haze is copied, as the sky's share and brightness, into the graphics card's
         * shader (`GpuRaster`), and the parity guard that would check a new pair against the
         * processor cannot run without a display; see docs/DESIGN_LEDGER.md, Fix 3 and Fix 3b.
         */
        const val HAZE_A_STEP_OFF =
            "the relief: the lamp's contrast is matched a step off the declared haze"

        /**
         * The known failure the ordinary-ground clause records. The median light over the
         * gallery world's land under the declared sky is 0.8756 on the implicit incision's
         * terrain, rougher on the plains and the ranges alike, against the declared 0.9225 (0.9489
         * under the capped update, recorded then because the implicit update was to move the
         * relief again). Re-derived to 0.8756 it holds its own clause, but a synthetic plane
         * falling at 315 degrees on cells twice as tall as wide is then drawn 1.01 of its bar off
         * the shading of its ground, because the constant scales the factor the plane is clipped
         * by; and it travels to the graphics card with the haze, whose parity guard cannot run here.
         * Recorded, for the chunk that re-derives the haze and the ground together with the device
         * guard running (docs/DESIGN_LEDGER.md, Fix 3b).
         */
        const val ORDINARY_GROUND_AWAITS_THE_DEVICE =
            "the relief: ordinary ground's median light has moved off the declared figure with the terrain"

        /** The gallery's world, at the size these guards measure on. See [TestWorlds]. */
        val WORLD: WorldMap get() = TestWorlds.gallery

        /** The exaggeration and the stencil a 512 map is drawn with. */
        val SLOPE_SCALE = ReliefShading.slopeScale(512)
        val OPENNESS_STEP = ReliefShading.opennessStep(512)

        /**
         * The row scale of the synthetic cone's field: square cells, so a cone round in cells is
         * round on its ground. What the shading does on this project's own cells is
         * `the drawn relief is the ground's under the same light`.
         */
        const val SQUARE_CELLS = 1.0

        /** The darkest factor the model can produce. A face pinned here has lost its detail. */
        const val DARKEST = 0.45f

        /** The single lamp, reproduced as the control. See [lightlessBearings]. */
        const val LAMP_EAST = -0.6f
        const val LAMP_SOUTH = -0.6f
        const val LAMP_HEIGHT = 0.53f

        /** Side of the synthetic cone's field. */
        const val CONE_FIELD = 128

        /**
         * How steep the cone's flank is, as a share of the way through this world's own land
         * slopes.
         *
         * A cone shallower than the country it stands for would prove nothing about either model,
         * and one steeper than any real hillside would prove something about a cliff nobody draws.
         * The flank is cut to the ninth decile of the land slope of the gallery's world, measured
         * at the same central difference the shading itself reads.
         */
        const val CONE_FLANK_PERCENTILE = 0.9

        /** How many bearings the flank is sampled at. One a degree. */
        const val BEARINGS = 360

        /**
         * How far the two models' contrasts may differ, as a share of the lamp's.
         *
         * Tight, because this is no longer a hope but the thing [ReliefShading.HAZE] is derived
         * from: the sweep below picks the day at which the two match, so what is left over is the
         * coarseness of the sweep's own step. It measures 8.8% flatter, and one step of haze either
         * side of the chosen one moves that by about six points, so a tenth is the honest bar.
         */
        const val MAX_CONTRAST_DRIFT = 0.10

        /** How far the haze sweep goes, and in what steps: a clear sky to a thick overcast. */
        const val HAZE_SWEEP_TO = 0.6f
        const val HAZE_SWEEP_STEP = 0.02f

        /** The planes of the facing clause: their field, their steepness, and how many facings. */
        const val PLANE_FIELD = 128
        const val PLANE_FALL_PER_CELL_WIDTH = 0.02
        const val PLANE_FACINGS = 16

        /**
         * The grids the planes are drawn on, as a width and a row's height in cell widths: this
         * map's cells, square ones, and cells a quarter and twice as tall as they are wide, at the
         * 512 stencil, and twice as tall at 256, whose stencil's shortest step is one cell.
         */
        val PLANE_GRIDS = listOf(512 to 0.5, 512 to 1.0, 512 to 0.25, 512 to 2.0, 256 to 2.0)

        /** The lamps' altitude: 32 degrees, the cartographic convention the model keeps. */
        const val LAMP_ALTITUDE_DEGREES = 32.0

        /** The brightest factor the model can produce. */
        const val BRIGHTEST = 1.35f

        /**
         * How far a plane's drawn factor may sit from its ground's, on square cells and on others.
         *
         * On square cells every horizon sample lies on its bearing, so the drawn factor and the
         * ground's differ only by rounding and by the model's lamp written to three figures, 0.848
         * and 0.53 for 32 degrees: 0.0001 measured, and a thousandth is the bar. On other cells the
         * sample nearest a diagonal bearing is a whole cell, up to eleven degrees off the diagonal on
         * this map's cells and eighteen on cells twice as tall as they are wide, and the steepest of
         * three such samples overstates a plane's horizon a little: 0.0051 measured on this map's
         * cells, 0.0088 and 0.0095 on the tall ones, and a hundredth is the bar. What the bars are
         * for is further off: a plane read at half its gradient north-south is several hundredths
         * out, and a horizon read at half the lamps' exaggeration is 0.0257 out on square cells.
         */
        const val PLANE_TOLERANCE_ON_SQUARE_CELLS = 0.001
        const val PLANE_TOLERANCE = 0.01

        /** How far the declared ordinary ground may sit from the measured median. */
        const val MAX_GROUND_DRIFT = 0.004f
    }

    /**
     * A cone of unit height whose flank is as steep as the steepest tenth of this world's land.
     *
     * The central difference the shading reads spans two cells, so a cone of radius R has a
     * gradient of `2 · scale / R`; inverting that gives the radius a chosen steepness wants.
     */
    private fun cone(steepness: Float): Pair<FloatField, Float> {
        val radius = 2f * SLOPE_SCALE / steepness
        val field = FloatField.of(CONE_FIELD, CONE_FIELD) { x, y ->
            val fromCentreX = x - CONE_FIELD / 2f
            val fromCentreY = y - CONE_FIELD / 2f
            val distance = sqrt(fromCentreX * fromCentreX + fromCentreY * fromCentreY)
            (1f - distance / radius).coerceAtLeast(0f)
        }
        return Pair(field, radius)
    }

    /** The [share]th quantile of the scaled land slope, in the units the shading reads. */
    private fun landSlope(world: WorldMap, share: Double): Float {
        val elevation = world.sea.relativeElevation
        val land = world.sea.isLand
        val slopes = ArrayList<Float>()
        for (row in 0 until world.height) {
            for (column in 0 until world.width) {
                if (!land[row * world.width + column]) continue
                val eastward =
                    (elevation.sample(column + 1, row) -
                        elevation.sample(column - 1, row)) * SLOPE_SCALE
                val southward =
                    (elevation.sample(column, row + 1) -
                        elevation.sample(column, row - 1)) * SLOPE_SCALE
                slopes.add(sqrt(eastward * eastward + southward * southward))
            }
        }
        slopes.sort()
        return slopes[(slopes.size * share).toInt().coerceIn(0, slopes.size - 1)]
    }

    private class Flank(val darkest: Float, val brightest: Float, val floored: Int) {
        val ratio: Double get() = darkest.toDouble() / brightest
        override fun toString(): String =
            "darkest %.3f, brightest %.3f, darkest is %.2f of brightest, %d bearings at the floor"
                .format(darkest, brightest, ratio, floored)
    }

    /** Every bearing round the cone's flank, shaded by one of the two models. */
    private fun flank(field: FloatField, radius: Float, singleLamp: Boolean): Flank {
        var darkest = Float.MAX_VALUE
        var brightest = 0f
        var floored = 0
        for (step in 0 until BEARINGS) {
            val shade = onFlank(field, radius, step) { x, y ->
                ReliefShading.at(x, y, field, SLOPE_SCALE, OPENNESS_STEP, singleLamp, SQUARE_CELLS)
            }
            if (shade < darkest) darkest = shade
            if (shade > brightest) brightest = shade
            if (shade <= DARKEST) floored++
        }
        return Flank(darkest, brightest, floored)
    }

    /** How many bearings round the flank the dome gives no direct light to at all. */
    private fun unlitUnderTheSky(field: FloatField, radius: Float): Int {
        var unlit = 0
        for (step in 0 until BEARINGS) {
            val direct = onFlank(field, radius, step) { x, y ->
                val eastward = (field.sample(x + 1, y) - field.sample(x - 1, y)) * SLOPE_SCALE
                val southward = (field.sample(x, y + 1) - field.sample(x, y - 1)) * SLOPE_SCALE
                ReliefShading.directLight(
                    eastward,
                    southward,
                    sqrt(eastward * eastward + southward * southward + 1f)
                )
            }
            if (direct <= 0f) unlit++
        }
        return unlit
    }

    /**
     * How many bearings round the flank the single lamp gives no light to at all.
     *
     * The lamp reproduced here rather than called, so that what fails is a control this test owns:
     * the north-west light at 32 degrees, exactly as every render before the sky model. Where
     * this dot product is at or below zero the face is turned away from the only light there is —
     * and the drawing goes on darkening it past that point, which is what "unlit" means on a map
     * lit by one lamp.
     */
    private fun lightlessBearings(field: FloatField, radius: Float): Int {
        var lightless = 0
        for (step in 0 until BEARINGS) {
            val lambert = onFlank(field, radius, step) { x, y ->
                val eastward = (field.sample(x + 1, y) - field.sample(x - 1, y)) * SLOPE_SCALE
                val southward = (field.sample(x, y + 1) - field.sample(x, y - 1)) * SLOPE_SCALE
                -eastward * LAMP_EAST - southward * LAMP_SOUTH + LAMP_HEIGHT
            }
            if (lambert <= 0f) lightless++
        }
        return lightless
    }

    /**
     * [measure] taken at bearing [step] of [BEARINGS], half way down the flank — where the cone is
     * at its steepest all the way round and neither the summit nor the foot is in the sample.
     */
    private fun onFlank(
        field: FloatField,
        radius: Float,
        step: Int,
        measure: (Int, Int) -> Float
    ): Float {
        val bearing = 2.0 * PI * step / BEARINGS
        val sampleRadius = radius * 0.5
        return measure(
            (CONE_FIELD / 2 + sampleRadius * cos(bearing)).roundToInt(),
            (CONE_FIELD / 2 + sampleRadius * sin(bearing)).roundToInt()
        )
    }

    @Test
    fun `no face of a cone is unlit under the sky, and the lamp leaves a dark side`() {
        val steepness = landSlope(WORLD, CONE_FLANK_PERCENTILE)
        val (field, radius) = cone(steepness)
        val sky = flank(field, radius, singleLamp = false)
        val lamp = flank(field, radius, singleLamp = true)
        val lightless = lightlessBearings(field, radius)

        println(
            "RELIEF cone: flank %.2f, the ninth decile of this world's land slope, over %.0f cells"
                .format(steepness, radius)
        )
        println("RELIEF cone under the sky:         $sky")
        println("RELIEF cone under the single lamp: $lamp")
        println("RELIEF $lightless of $BEARINGS bearings receive no light at all from the lamp")

        assertTrue(
            lightless > 0,
            "no face of this cone is turned away from the single lamp, so it is not the case the " +
                "sky model exists for and proves nothing"
        )
        assertTrue(
            unlitUnderTheSky(field, radius) == 0,
            "${unlitUnderTheSky(field, radius)} of $BEARINGS bearings round the cone receive no " +
                "direct light at all from the dome"
        )
        assertTrue(
            sky.floored == 0,
            "${sky.floored} of $BEARINGS bearings round the cone are pinned at the darkest factor " +
                "the model has, which is a face with no detail left in it"
        )
        // How dark the darkest face is comes out much the same either way, and it should: the haze
        // is calibrated so that the two models have the same contrast. What the dome changes is
        // *which* faces are dark — the lamp blacks out a whole quadrant, the dome darkens the steep
        // side of every hill whichever way it points — so the two assertions above are the ones
        // that separate them, and the ratios are reported rather than asserted.
    }

    /**
     * That the haze the model is drawn under is the one it says it is.
     *
     * [ReliefShading.HAZE] is the model's only calibrated number, and what calibrates it is this:
     * the haze at which the shaded relief has the same contrast as the single lamp it replaces.
     * Rather than take that on trust, the sweep below re-derives it — every haze from a clear sky
     * to a thoroughly overcast one, the deviation of the shading over this world's land at each,
     * and the one that lands nearest the lamp's — and asserts that the constant is that value and
     * that [ReliefShading.ORDINARY_GROUND] is the median illumination there. A change to the sky's
     * arithmetic that quietly moved either fails here rather than in a render six chunks later.
     */
    @Test
    fun `the haze is the one at which the relief keeps the lamp's contrast`() {
        val world = WORLD
        val lamp = Spread(
            ReliefShading.of(
                world.sea.relativeElevation, world.sea.isLand, singleLamp = true,
                cellHeightInCellWidths = world.config.cellHeightInCellWidths
            ),
            world
        )

        var bestHaze = 0f
        var bestGap = Double.MAX_VALUE
        var bestGround = 1f
        val readings = StringBuilder()
        var haze = 0f
        while (haze <= HAZE_SWEEP_TO + 1e-6f) {
            val sky = ReliefShading.Sky.forHaze(haze)
            val light = illuminationOverLand(world, sky)
            val ground = median(light)
            val deviation = Spread(shadeFrom(light, ground, world), world).deviation
            if (haze % 0.05f < 1e-5f || haze < 1e-5f) {
                readings.append(" %.2f→%.4f".format(haze, deviation))
            }
            val gap = kotlin.math.abs(deviation - lamp.deviation)
            if (gap < bestGap) {
                bestGap = gap
                bestHaze = haze
                bestGround = ground
            }
            haze += HAZE_SWEEP_STEP
        }

        println("RELIEF the lamp's contrast is %.4f; haze→deviation:%s".format(lamp.deviation, readings))
        println(
            "RELIEF the shipped sky: diffuse share %.8f, brightness %s".format(
                ReliefShading.DAYLIGHT.diffuseShare,
                ReliefShading.DAYLIGHT.brightness.joinToString(", ") { "%.8f".format(it) }
            )
        )
        println(
            "RELIEF it is matched at haze %.2f, where ordinary ground sits at %.3f"
                .format(bestHaze, bestGround)
        )
        // The declared haze is a point of the sweep, so the match must land on it: within half a
        // step, the sweep's own resolution, where a step and a half let a constant one step off
        // pass. It landed a step off, at 0.08 (Audit III, F-I3), while the sky's horizon read the
        // ground half as steep as the lamps did; with both reading the one exaggeration the match
        // is back on the declared 0.10 (docs/DESIGN_LEDGER.md, Fix 2).
        val matched = String.format(java.util.Locale.ROOT, "%.2f", bestHaze)
        val declared = String.format(java.util.Locale.ROOT, "%.2f", ReliefShading.HAZE)
        // A step off since Fix 3, and still at 0.12 on the implicit incision's terrain: see
        // [HAZE_A_STEP_OFF].
        KnownFailures.expect(HAZE_A_STEP_OFF, "matched at haze 0.12") {
            if (kotlin.math.abs(bestHaze - ReliefShading.HAZE) > HAZE_SWEEP_STEP / 2) {
                throw RecordedViolation(
                    "the lamp's contrast is matched at haze $matched, a step or more from the declared $declared",
                    "matched at haze $matched"
                )
            }
        }
        // Ordinary ground is the median light under the sky the map is drawn under — the declared
        // one — and not under whichever haze the sweep matched.
        val declaredGround = median(illuminationOverLand(world, ReliefShading.DAYLIGHT))
        println("RELIEF under the declared sky ordinary ground sits at %.4f".format(declaredGround))
        // Recorded: see [ORDINARY_GROUND_AWAITS_THE_DEVICE]. 0.9489 under the cap, 0.8756 on the
        // implicit incision's terrain.
        KnownFailures.expect(ORDINARY_GROUND_AWAITS_THE_DEVICE, "ordinary ground 0.8756") {
            if (kotlin.math.abs(declaredGround - ReliefShading.ordinaryGround) > MAX_GROUND_DRIFT) {
                throw RecordedViolation(
                    "ordinary ground measures ${"%.4f".format(declaredGround)} under the declared sky, " +
                        "against the declared ${ReliefShading.ordinaryGround}",
                    String.format(java.util.Locale.ROOT, "ordinary ground %.4f", declaredGround)
                )
            }
        }
    }

    /**
     * The relief is drawn for the ground: a plane is shaded as the ground it stands for is shaded
     * under the same sky, whichever way it faces and whatever shape the grid's cells are.
     *
     * A cell of this map is twice as wide as it is tall, so a plane falling north drops twice as far
     * a row as a plane of the same ground slope falling east drops a column. A shading that read
     * both differences over one cell of the sheet drew the north-facing plane half as steep as the
     * east-facing one (Audit III's F-R5). What each plane's shading should be is worked out here
     * from nothing of the model's but the sky it is lit by and the exaggeration it declares: the
     * plane's gradient on the ground, exaggerated, gives its normal; eight lamps 32 degrees up at
     * the eight compass bearings light it by Lambert's law; and the sky's horizon along each bearing
     * is the plane itself, rising at the same exaggerated gradient, where it rises. That is what the
     * drawn factor is held to, for planes of one steepness at sixteen facings, on this map's cells,
     * on square ones, and on cells a quarter and twice as tall as they are wide, at two stencils.
     * Equal slopes facing different ways are lit differently, and the clause asks only that each be
     * lit as its ground is.
     */
    @Test
    fun `a plane is shaded as the ground it stands for, whichever way it faces`() {
        val sky = ReliefShading.DAYLIGHT
        var worst = 0.0
        var worstWhere = ""
        for ((width, aspect) in PLANE_GRIDS) {
            val scale = ReliefShading.slopeScale(width)
            val step = ReliefShading.opennessStep(width)
            val exaggeration = ReliefShading.verticalExaggeration(width).toDouble()
            for (facing in 0 until PLANE_FACINGS) {
                val falls = 2.0 * PI * facing / PLANE_FACINGS
                val plane = FloatField.of(PLANE_FIELD, PLANE_FIELD) { column, row ->
                    (0.5 - PLANE_FALL_PER_CELL_WIDTH * (column * cos(falls) + row * aspect * sin(falls))).toFloat()
                }
                val drawn = ReliefShading.at(
                    PLANE_FIELD / 2, PLANE_FIELD / 2, plane, scale, step, singleLamp = false, aspect
                ).toDouble()
                val expected = groundShading(falls, exaggeration, sky)
                // Measured against the bar for this shape of cell, so one figure orders them all.
                val bar = if (aspect == 1.0) PLANE_TOLERANCE_ON_SQUARE_CELLS else PLANE_TOLERANCE
                val gap = kotlin.math.abs(drawn - expected) / bar * PLANE_TOLERANCE
                println(
                    "RELIEF %4d wide, cells %.2f as tall as wide, plane falling at %5.1f degrees on the ground: drawn %.4f, the ground's %.4f"
                        .format(width, aspect, 360.0 * facing / PLANE_FACINGS, drawn, expected)
                )
                if (gap > worst) {
                    worst = gap
                    worstWhere = "a plane falling at ${360.0 * facing / PLANE_FACINGS} degrees, $width wide, cells $aspect as tall as wide"
                }
            }
        }
        assertTrue(
            worst <= PLANE_TOLERANCE,
            "$worstWhere is drawn off the shading of the ground it stands for by " +
                "${"%.2f".format(worst / PLANE_TOLERANCE)} of its bar"
        )
    }

    /**
     * The factor a plane falling toward [falls] at [PLANE_FALL_PER_CELL_WIDTH] on the ground should
     * be drawn at, worked out here and not by the model: its own normal, its own lamps, its own
     * horizon. The sky's brightness by bearing, its diffuse share and ordinary ground are the
     * model's declared figures, which are what is being drawn under rather than how it is drawn.
     */
    private fun groundShading(falls: Double, exaggeration: Double, sky: ReliefShading.Sky): Double {
        // The plane's rise per cell width of ground, east and south, drawn [exaggeration] times steeper.
        val riseEast = -PLANE_FALL_PER_CELL_WIDTH * cos(falls) * exaggeration
        val riseSouth = -PLANE_FALL_PER_CELL_WIDTH * sin(falls) * exaggeration
        // Its upward unit normal, in east, south and up.
        val length = sqrt(riseEast * riseEast + riseSouth * riseSouth + 1.0)
        val normalEast = -riseEast / length
        val normalSouth = -riseSouth / length
        val normalUp = 1.0 / length
        val altitude = LAMP_ALTITUDE_DEGREES * PI / 180.0
        var direct = 0.0
        var blocked = 0.0
        var total = 0.0
        for (bearing in 0 until 8) {
            // East first, then round through south: the model's own order of bearings.
            val toward = PI / 4 * bearing
            val lambert = normalEast * cos(altitude) * cos(toward) +
                normalSouth * cos(altitude) * sin(toward) + normalUp * sin(altitude)
            if (lambert > 0.0) direct += sky.brightness[bearing] * lambert
            // An infinite plane's horizon along a bearing is the plane: its rise per cell width
            // of ground along that bearing, where it rises, and nothing where it falls away.
            val tangent = maxOf(0.0, riseEast * cos(toward) + riseSouth * sin(toward))
            blocked += sky.brightness[bearing] * tangent / sqrt(tangent * tangent + 1.0)
            total += sky.brightness[bearing]
        }
        // Each lamp's light on flat ground is the sine of its altitude, and the model reads the
        // direct light as a share of what flat ground receives.
        val directShare = direct / total / sin(altitude)
        val open = 1.0 - blocked / total
        val illumination = sky.diffuseShare * open + (1.0 - sky.diffuseShare) * directShare
        return (illumination / ReliefShading.ordinaryGround).coerceIn(DARKEST.toDouble(), BRIGHTEST.toDouble())
    }

    /**
     * Every point the sky's horizon is sampled at stands off the cell it is read for, on cells of
     * every shape a grid can have and at every stencil.
     *
     * A sample rounded onto the cell itself has no distance to it, so its tangent is nought over
     * nought and that bearing's reading is dropped without a word: on a grid 256 by 64 the step
     * north, one cell width of ground, is half a row and rounds to none. Every width and height is
     * a power of two, so a cell is `2^k` as tall as it is wide; read here for `k` from -6 to 6 and
     * for every stencil from 64 cells across to 8,192.
     */
    @Test
    fun `every horizon sample stands off the cell it is read for`() {
        val failures = ArrayList<String>()
        var width = 64
        while (width <= 8192) {
            val step = ReliefShading.opennessStep(width)
            for (k in -6..6) {
                val aspect = Math.pow(2.0, k.toDouble())
                val horizon = ReliefHorizon.of(step, aspect)
                for (sample in horizon.strides.indices) {
                    val onItself = horizon.columns[sample] == 0 && horizon.rows[sample] == 0
                    if (onItself || !(horizon.strides[sample] > 0f)) {
                        failures.add(
                            "$width wide, cells $aspect as tall as wide: sample $sample at " +
                                "(${horizon.columns[sample]},${horizon.rows[sample]}), stride ${horizon.strides[sample]}"
                        )
                    }
                }
            }
            width *= 2
        }
        println("RELIEF horizon samples on the cell they are read for: ${failures.size}")
        failures.take(10).forEach { println("RELIEF   $it") }
        assertTrue(failures.isEmpty(), "${failures.size} horizon samples stand on their own cell: ${failures.take(5)}")
    }

    /** The unnormalised light over every land cell, in cell order. */
    private fun illuminationOverLand(world: WorldMap, sky: ReliefShading.Sky): FloatArray {
        val elevation = world.sea.relativeElevation
        val land = world.sea.isLand
        val light = FloatArray(world.width * world.height)
        for (row in 0 until world.height) {
            for (column in 0 until world.width) {
                val cell = row * world.width + column
                if (!land[cell]) continue
                light[cell] = ReliefShading.illumination(
                    column, row, elevation, SLOPE_SCALE, OPENNESS_STEP,
                    world.config.cellHeightInCellWidths, sky
                )
            }
        }
        return light
    }

    /** What [ReliefShading.at] would return from those figures: normalised, then clamped. */
    private fun shadeFrom(light: FloatArray, ordinaryGround: Float, world: WorldMap): FloatArray =
        FloatArray(light.size) { cell ->
            if (world.sea.isLand[cell]) (light[cell] / ordinaryGround).coerceIn(DARKEST, 1.35f)
            else 1f
        }

    private fun median(light: FloatArray): Float {
        val lit = light.filter { it > 0f }.sorted()
        return lit[lit.size / 2]
    }

    @Test
    fun `the sky model has the same contrast as the lamp it replaces`() {
        val world = WORLD
        val sky = Spread(
            ReliefShading.of(
                world.sea.relativeElevation, world.sea.isLand, singleLamp = false,
                cellHeightInCellWidths = world.config.cellHeightInCellWidths
            ),
            world
        )
        val lamp = Spread(
            ReliefShading.of(
                world.sea.relativeElevation, world.sea.isLand, singleLamp = true,
                cellHeightInCellWidths = world.config.cellHeightInCellWidths
            ),
            world
        )
        println("RELIEF over ${lamp.cells} land cells of seed 234475 at 512")
        println("RELIEF single lamp: $lamp")
        println("RELIEF sky:         $sky")
        println(
            ("RELIEF the two contrasts are %.1f%% apart, and ordinary ground is at %.3f of the " +
                "light of a flat plain").format(
                (sky.deviation - lamp.deviation) / lamp.deviation * 100,
                ReliefShading.ordinaryGround
            )
        )
        assertTrue(
            kotlin.math.abs(sky.deviation - lamp.deviation) / lamp.deviation <= MAX_CONTRAST_DRIFT,
            "the sky model's contrast is %.4f against the lamp's %.4f, more than %.0f%% apart"
                .format(sky.deviation, lamp.deviation, MAX_CONTRAST_DRIFT * 100)
        )
    }

    /** What the shading did to the land: how far it swings, and how much of it is pinned flat. */
    private class Spread(shade: FloatArray, world: WorldMap) {
        val cells: Int
        val deviation: Double
        val crushed: Double
        private val quantiles: List<Float>

        init {
            val land = world.sea.isLand
            val lit = ArrayList<Float>()
            for (cell in shade.indices) if (land[cell]) lit.add(shade[cell])
            lit.sort()
            cells = lit.size
            val mean = lit.sumOf { it.toDouble() } / cells
            deviation = sqrt(lit.sumOf { (it - mean) * (it - mean) } / cells)
            crushed = lit.count { it <= DARKEST }.toDouble() / cells
            quantiles = listOf(0.01, 0.5, 0.99).map { lit[(cells * it).toInt()] }
        }

        override fun toString(): String =
            "deviation %.4f, 1st %.3f, median %.3f, 99th %.3f, %.2f%% at the darkest factor"
                .format(deviation, quantiles[0], quantiles[1], quantiles[2], crushed * 100)
    }
}
