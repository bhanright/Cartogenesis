package com.cartogenesis.cartography

import com.cartogenesis.cartography.geometry.KnownFailures
import com.cartogenesis.cartography.geometry.RecordedViolation
import com.cartogenesis.worldgen.BorrowsSharedWorlds
import com.cartogenesis.worldgen.SharedWorlds
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.model.WorldScale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
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


        /** The gallery's world, at the size these guards measure on. See [TestWorlds]. */
        val WORLD: WorldMap get() = TestWorlds.gallery

        /** The exaggeration and the stencil the gallery's world is drawn with. */
        val SLOPE_SCALE = ReliefShading.slopeScale(TestWorlds.galleryConfig.cellWidthKm)
        val OPENNESS_STEP = ReliefShading.opennessStep(TestWorlds.galleryConfig.cellWidthKm)

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

        /** The share of the land [EngravingPlan.SLOPE_FLOOR] leaves blank: a tenth, the deltas, basin floors and coastal plains. */
        const val TENTH_PERCENTILE = 0.10

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
         * The grids the planes are drawn on, as a count of columns over the default world and a
         * row's height in cell widths: this map's square cells at 512 rows, the half-height cells
         * of a 512 by 512 grid, and cells a quarter and twice as tall as they are wide at that
         * grid's stencil, and twice as tall at 256 columns, whose stencil's shortest step is one
         * cell.
         */
        val PLANE_GRIDS = listOf(1024 to 1.0, 512 to 0.5, 512 to 1.0, 512 to 0.25, 512 to 2.0, 256 to 2.0)

        /** The lamps' altitude: 32 degrees, the cartographic convention the model keeps. */
        const val LAMP_ALTITUDE_DEGREES = 32.0

        /** The brightest factor the model can produce. */
        const val BRIGHTEST = 1.35f

        /**
         * How far a plane's drawn light may sit from its ground's, on square cells and on others: in
         * the light itself, a share of what a flat open plain receives, before the division by
         * ordinary ground, which scales the drawn and the expected alike and is re-derived with
         * the terrain rather than with the geometry these bars are about.
         *
         * On square cells every horizon sample lies on its bearing, so the drawn light and the
         * ground's differ only by rounding and by the model's lamp written to three figures, 0.848
         * and 0.53 for 32 degrees: 0.0001 measured, and a thousandth is the bar. On other cells the
         * sample nearest a diagonal bearing is a whole cell, up to eleven degrees off the diagonal on
         * cells twice as wide as tall and eighteen on cells twice as tall as they are wide, and the
         * steepest of three such samples overstates a plane's horizon a little: 0.0048 measured on
         * the cells twice as wide and on those four times as wide, 0.0082 and 0.0088 on the tall
         * ones, and a hundredth is the bar.
         * What the bars are for is further off: a plane read at half its gradient north-south is
         * several hundredths out, and a horizon read at half the lamps' exaggeration was 0.0257 out
         * in the drawn factor on square cells.
         */
        const val PLANE_TOLERANCE_ON_SQUARE_CELLS = 0.001
        const val PLANE_TOLERANCE = 0.01

        /** The gallery's seed on the 512 by 512 grid, the grid the maps were drawn on before. */
        val HALF_HEIGHT_GALLERY = com.cartogenesis.worldgen.CalibrationPlanet.of(
            com.cartogenesis.worldgen.model.WorldGenConfig(seed = 234475L, width = 512, height = 512)
        )

        /** The exaggeration the maps were drawn at on that grid, set by eye: 24 on its cell. */
        const val HALF_HEIGHT_EXAGGERATION = 24.0

        /**
         * The exaggeration's sweep on square cells: from 34, where the lamp's contrast is 6% under
         * the 512 by 512 grid's and the cone well clear, up to the first step that pins it, in
         * quarters, no further than 48, twice the 512 by 512 grid's 24 per cell width.
         */
        const val EXAGGERATION_SWEEP_FROM = 34.0
        const val EXAGGERATION_SWEEP_TO = 48.0
        const val EXAGGERATION_SWEEP_STEP = 0.25

        /**
         * How far under the 512 by 512 grid's lamp contrast the exaggeration may land: 2.5%, over
         * the 2.0% the steepest unpinned exaggeration measured, and within what an eye tells apart
         * on a map drawn at the other.
         */
        const val MAX_CONTRAST_SHORTFALL = 0.025

        /** How far the declared ordinary ground may sit from the measured median. */
        const val MAX_GROUND_DRIFT = 0.004f
    }

    /**
     * A cone of unit height whose flank is as steep as the steepest tenth of this world's land.
     *
     * The central difference the shading reads spans two cells, so a cone of radius R has a
     * gradient of `2 · scale / R`; inverting that gives the radius a chosen steepness wants.
     */
    private fun cone(steepness: Float, scale: Float = SLOPE_SCALE): Pair<FloatField, Float> {
        val radius = 2f * scale / steepness
        val field = FloatField.of(CONE_FIELD, CONE_FIELD) { x, y ->
            val fromCentreX = x - CONE_FIELD / 2f
            val fromCentreY = y - CONE_FIELD / 2f
            val distance = sqrt(fromCentreX * fromCentreX + fromCentreY * fromCentreY)
            (1f - distance / radius).coerceAtLeast(0f)
        }
        return Pair(field, radius)
    }

    /** The [share]th quantile of the scaled land slope, in the units the shading reads. */
    private fun landSlope(world: WorldMap, share: Double, scale: Float = SLOPE_SCALE): Float {
        val elevation = world.sea.relativeElevation
        val land = world.sea.isLand
        val slopes = ArrayList<Float>()
        for (row in 0 until world.height) {
            for (column in 0 until world.width) {
                if (!land[row * world.width + column]) continue
                val eastward =
                    (elevation.sample(column + 1, row) -
                        elevation.sample(column - 1, row)) * scale
                val southward =
                    (elevation.sample(column, row + 1) -
                        elevation.sample(column, row - 1)) * scale
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
                cellWidthKm = world.config.cellWidthKm,
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
            "RELIEF it is matched at haze %.2f, where ordinary ground sits at %.4f"
                .format(bestHaze, bestGround)
        )
        // The declared haze is a point of the sweep, so the match must land on it: within half a
        // step, the sweep's own resolution, where a step and a half let a constant one step off
        // pass. Armed again on square cells, where the match is on the declared 0.10; it ran as a
        // known failure while the terrain of cells twice as wide as tall matched at 0.12
        // (docs/DESIGN_LEDGER.md, Fix 3, Fix 3b and Q4).
        val matched = String.format(java.util.Locale.ROOT, "%.2f", bestHaze)
        val declared = String.format(java.util.Locale.ROOT, "%.2f", ReliefShading.HAZE)
        assertTrue(
            kotlin.math.abs(bestHaze - ReliefShading.HAZE) <= HAZE_SWEEP_STEP / 2,
            "the lamp's contrast is matched at haze $matched, a step or more from the declared $declared"
        )
        // Ordinary ground is the median light under the sky the map is drawn under — the declared
        // one — and not under whichever haze the sweep matched. Armed again on square cells, with
        // the figure re-derived there and the device's parity guard run on it (it is handed to the
        // card as `uOrdinaryGround`).
        val declaredGround = median(illuminationOverLand(world, ReliefShading.DAYLIGHT))
        println("RELIEF under the declared sky ordinary ground sits at %.4f".format(declaredGround))
        // Recorded at K2: the gallery's world on the 12,000 km planet under K2's physics is other
        // ground than the one the declared figure was read off; it is the drawing's to re-derive,
        // with its other constants, for the Earth-sized default (docs/TODO.md).
        KnownFailures.expect("K2: the relief's ordinary ground was read off the gallery's ground before K2", "0.9216") {
            if (kotlin.math.abs(declaredGround - ReliefShading.ordinaryGround) > MAX_GROUND_DRIFT) {
                val found = "%.4f".format(declaredGround)
                throw RecordedViolation(
                    "ordinary ground measures $found under the declared sky, against the declared ${ReliefShading.ordinaryGround}",
                    found
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
            val cellWidthKm = WorldScale().cellWidthKm(width)
            val scale = ReliefShading.slopeScale(cellWidthKm)
            val step = ReliefShading.opennessStep(cellWidthKm)
            val exaggeration = ReliefShading.verticalExaggeration(cellWidthKm).toDouble()
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
                val gap = kotlin.math.abs(drawn - expected) * ReliefShading.ordinaryGround / bar * PLANE_TOLERANCE
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
            "$worstWhere is lit off the light of the ground it stands for by " +
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
            val step = ReliefShading.opennessStep(WorldScale().cellWidthKm(width))
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
                cellWidthKm = world.config.cellWidthKm,
                cellHeightInCellWidths = world.config.cellHeightInCellWidths
            ),
            world
        )
        val lamp = Spread(
            ReliefShading.of(
                world.sea.relativeElevation, world.sea.isLand, singleLamp = true,
                cellWidthKm = world.config.cellWidthKm,
                cellHeightInCellWidths = world.config.cellHeightInCellWidths
            ),
            world
        )
        println("RELIEF over ${lamp.cells} land cells of seed 234475 at 512 rows")
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

    /**
     * That the exaggeration is the steepest at which no face of the cone is pinned, and that it
     * keeps the contrast the maps were drawn at before the grid was square.
     *
     * The exaggeration was set by eye on the 512 by 512 grid, 24 over a cell 23.4 km wide, and
     * every calibration of the shading since, [ReliefShading.HAZE] and ordinary ground among them,
     * is measured against the single lamp's picture at it. On square cells the lamp's contrast of
     * that picture, and the cone cut to the ninth decile of the land's slope staying off the
     * darkest factor, cannot both be kept to the digit: the finer cells resolve a steeper tail of
     * the same ground. So the rule is the steepest exaggeration, swept in steps of
     * [EXAGGERATION_SWEEP_STEP] from [EXAGGERATION_SWEEP_FROM], at which the cone pins no bearing,
     * each step lit under its own ordinary ground; and it must land within
     * [MAX_CONTRAST_SHORTFALL] of the lamp's contrast on the 512 by 512 grid at 24.
     */
    @Test
    fun `the exaggeration is the steepest that pins no face of the cone, and keeps the maps' contrast`() {
        val reference = SharedWorlds.world(HALF_HEIGHT_GALLERY)
        val target = lampSpread(reference, (HALF_HEIGHT_EXAGGERATION / 2).toFloat()).deviation
        val world = WORLD
        var steepestClear = Double.NaN
        val readings = StringBuilder()
        var exaggeration = EXAGGERATION_SWEEP_FROM
        while (exaggeration <= EXAGGERATION_SWEEP_TO + 1e-9) {
            val pinned = conePinnedAt(world, exaggeration)
            readings.append(" %.2f→%d".format(exaggeration, pinned))
            if (pinned > 0) break
            steepestClear = exaggeration
            exaggeration += EXAGGERATION_SWEEP_STEP
        }
        val declared = ReliefShading.verticalExaggeration(world.config.cellWidthKm).toDouble()
        val contrast = lampSpread(world, (declared / 2).toFloat()).deviation
        println("RELIEF the cone's pinned bearings by exaggeration at 512 rows:$readings")
        println(
            "RELIEF the steepest that pins none is %.2f; the declared is %.4f, %.4f km a cell width; the lamp's contrast there is %.4f against %.4f on the 512 by 512 grid at %.0f, %.1f%% under"
                .format(
                    steepestClear, declared, declared * world.config.cellWidthKm, contrast, target,
                    HALF_HEIGHT_EXAGGERATION, (target - contrast) / target * 100
                )
        )
        // Recorded at K2: the gallery's ground moved with K2's figures, and re-deriving a drawing
        // constant on it is the drawing's chunk (docs/TODO.md).
        KnownFailures.expect("K2: the exaggeration was read off the gallery's ground before K2", "41.00") {
            if (kotlin.math.abs(declared - steepestClear) > EXAGGERATION_SWEEP_STEP / 2) {
                throw RecordedViolation(
                    "the steepest exaggeration that pins no face of the cone is %.2f, not the declared %.4f"
                        .format(steepestClear, declared),
                    "%.2f".format(steepestClear)
                )
            }
        }
        // The contrast is held against another grid's picture, so it is reported rather than
        // asserted: the application makes one grid (docs/DESIGN_LEDGER.md, G1).
        com.cartogenesis.worldgen.CrossGridReport.report(
            "the declared exaggeration keeps the 512 by 512 grid's contrast",
            kotlin.math.abs(contrast - target) / target <= MAX_CONTRAST_SHORTFALL,
            "the lamp's contrast at the declared exaggeration is %.4f against the 512 by 512 grid's %.4f, a band of %.1f%%"
                .format(contrast, target, MAX_CONTRAST_SHORTFALL * 100)
        )
    }

    /**
     * The floor below which a hachure leaves the ground blank is "the tenth percentile of the land
     * slope of seed 234475 measured at this stencil" ([EngravingPlan.SLOPE_FLOOR]). Held here,
     * beside the exaggeration, because the exaggeration sets the scale the slope is read at
     * (`EngravingPlan.gradientScaleAcross` is [ReliefShading.slopeScale] over the stencil), so
     * re-deriving the one moves the other. Held at the digit the floor is stated to: the tenth
     * percentile of the gallery's world, which is that seed at 512 rows, read as a hachure reads it
     * on the ground, rounds to the floor's hundredth: 0.064 at the exaggeration re-derived on
     * square cells (docs/DESIGN_LEDGER.md, Q4).
     */
    @Test
    fun `the slope floor is the tenth percentile of the land it was read off`() {
        val slopes = LandSlopes.ascending(WORLD, EngravingPlan(SheetGeometry.of(WORLD)))
        val tenth = LandSlopes.percentile(slopes, TENTH_PERCENTILE)
        println("RELIEF the tenth percentile of the land slope is %.4f; the floor is %.2f".format(tenth, EngravingPlan.SLOPE_FLOOR))
        // Recorded at K2, for the reason the ordinary ground's clause gives: the floor was read off
        // the gallery's ground before K2's physics moved it (docs/TODO.md).
        KnownFailures.expect("K2: the engraving's slope floor was read off the gallery's ground before K2", "0.04") {
            if (LandSlopes.hundredths(EngravingPlan.SLOPE_FLOOR) != LandSlopes.hundredths(tenth)) {
                throw RecordedViolation(
                    "the tenth percentile of seed 234475's land slope at 512 rows is $tenth; the floor is ${EngravingPlan.SLOPE_FLOOR}",
                    LandSlopes.hundredths(tenth)
                )
            }
        }
    }

    /**
     * How many bearings round the cone cut to the ninth decile of [world]'s land slope are pinned at
     * the darkest factor, drawn at [exaggeration] under the ordinary ground measured at it.
     */
    private fun conePinnedAt(world: WorldMap, exaggeration: Double): Int {
        val scale = (exaggeration / 2).toFloat()
        val elevation = world.sea.relativeElevation
        val land = world.sea.isLand
        val aspect = world.config.cellHeightInCellWidths
        val light = ArrayList<Float>()
        for (row in 0 until world.height) {
            for (column in 0 until world.width) {
                if (!land[row * world.width + column]) continue
                light.add(ReliefShading.illumination(column, row, elevation, scale, OPENNESS_STEP, aspect))
            }
        }
        val ground = light.filter { it > 0f }.sorted().let { it[it.size / 2] }
        val (field, radius) = cone(landSlope(world, CONE_FLANK_PERCENTILE, scale), scale)
        var pinned = 0
        for (step in 0 until BEARINGS) {
            val shade = onFlank(field, radius, step) { x, y ->
                (ReliefShading.illumination(x, y, field, scale, OPENNESS_STEP, SQUARE_CELLS) / ground)
                    .coerceIn(DARKEST, BRIGHTEST)
            }
            if (shade <= DARKEST) pinned++
        }
        return pinned
    }

    /** The single lamp's shading over [world]'s land at a central difference's [scale], as spread. */
    private fun lampSpread(world: WorldMap, scale: Float): Spread = Spread(
        ReliefShading.of(
            world.sea.relativeElevation, world.sea.isLand, singleLamp = true,
            cellWidthKm = world.config.cellWidthKm,
            cellHeightInCellWidths = world.config.cellHeightInCellWidths,
            scale = scale
        ),
        world
    )

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
