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

        /** The known failure the haze clause records. */
        const val HAZE_ONE_STEP_OFF =
            "Audit III F-I3: the declared haze is a step off the haze at which the relief keeps the lamp's contrast"

        /** The planes of the facing clause: their field, their steepness, and how many facings. */
        const val PLANE_FIELD = 128
        const val PLANE_FALL_PER_CELL_WIDTH = 0.02
        const val PLANE_FACINGS = 16

        /**
         * How far a plane's drawn factor may sit from its ground's: a hundredth of the factor.
         *
         * The lamps are read off the plane's exact difference, so they agree to rounding; the sky's
         * horizon is sampled at whole cells along each bearing, which on cells half as tall as they
         * are wide puts a diagonal sample up to eleven degrees off its bearing at the stencil's
         * shortest reach, and that is worth a few thousandths of the factor. A plane read at half
         * its gradient north-south is several hundredths out.
         */
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
        // pass. It lands a step off today (Audit III, F-I3), and the clause runs as a known
        // failure recorded by where the match lands, until the haze and the shader's copy of the
        // sky are re-derived together.
        val matched = String.format(java.util.Locale.ROOT, "%.2f", bestHaze)
        val declared = String.format(java.util.Locale.ROOT, "%.2f", ReliefShading.HAZE)
        KnownFailures.expect(HAZE_ONE_STEP_OFF, "the contrasts match at haze 0.08, not at the declared 0.10") {
            if (kotlin.math.abs(bestHaze - ReliefShading.HAZE) > HAZE_SWEEP_STEP / 2) {
                throw RecordedViolation(
                    "the lamp's contrast is matched at haze $matched, a step or more from the declared $declared",
                    "the contrasts match at haze $matched, not at the declared $declared"
                )
            }
        }
        // Ordinary ground is the median light under the sky the map is drawn under — the declared
        // one — and not under whichever haze the sweep matched.
        val declaredGround = median(illuminationOverLand(world, ReliefShading.DAYLIGHT))
        println("RELIEF under the declared sky ordinary ground sits at %.4f".format(declaredGround))
        assertTrue(
            kotlin.math.abs(declaredGround - ReliefShading.ordinaryGround) <= MAX_GROUND_DRIFT,
            "ordinary ground measures ${"%.4f".format(declaredGround)} under the declared sky, " +
                "against the declared ${ReliefShading.ordinaryGround}"
        )
    }

    /**
     * The relief is drawn for the ground: a plane on this project's own cells is shaded as the
     * ground it stands for is shaded under the same sky, whichever way it faces.
     *
     * A cell is twice as wide as it is tall, so a plane falling north drops twice as far a row as a
     * plane of the same ground slope falling east drops a column. A shading that read both
     * differences over one cell of the sheet drew the north-facing plane half as steep as the
     * east-facing one (Audit III's F-R5). What each plane's shading should be is worked out here
     * from its exact gradient on the ground, through the model's own lamps and its own sky, with
     * the sky's horizon along each bearing being the plane itself; that is what the drawn factor is
     * held to. Planes of one steepness at sixteen facings; equal slopes facing different ways are
     * lit differently, and the clause asks only that each be lit as its ground is.
     */
    @Test
    fun `a plane is shaded as the ground it stands for, whichever way it faces`() {
        val config = com.cartogenesis.worldgen.model.WorldGenConfig()
        val rowScale = config.cellHeightInCellWidths
        val width = config.width
        val scale = ReliefShading.slopeScale(width)
        val step = ReliefShading.opennessStep(width)
        val sky = ReliefShading.DAYLIGHT
        val onFlatGround = ReliefShading.directLight(0f, 0f, 1f)
        var worst = 0.0
        var worstFacing = 0
        for (facing in 0 until PLANE_FACINGS) {
            val falls = 2.0 * PI * facing / PLANE_FACINGS
            val plane = FloatField.of(PLANE_FIELD, PLANE_FIELD) { column, row ->
                (0.5 - PLANE_FALL_PER_CELL_WIDTH * (column * cos(falls) + row * rowScale * sin(falls))).toFloat()
            }
            val drawn = ReliefShading.at(
                PLANE_FIELD / 2, PLANE_FIELD / 2, plane, scale, step, singleLamp = false, rowScale
            ).toDouble()

            // The model's central difference spans two cell widths of ground either way.
            val eastward = (-2.0 * PLANE_FALL_PER_CELL_WIDTH * cos(falls) * scale).toFloat()
            val southward = (-2.0 * PLANE_FALL_PER_CELL_WIDTH * sin(falls) * scale).toFloat()
            val normalLength = sqrt(eastward * eastward + southward * southward + 1f)
            val direct = ReliefShading.directLight(eastward, southward, normalLength) / onFlatGround
            // An infinite plane's horizon along a bearing is the plane: its rise per cell width of
            // ground along that bearing, where it rises, and nothing where it falls away.
            var blocked = 0.0
            for (bearing in 0 until 8) {
                val toward = PI / 4 * bearing
                val rise = -PLANE_FALL_PER_CELL_WIDTH * scale * cos(toward - falls)
                val tangent = maxOf(0.0, rise)
                blocked += sky.brightness[bearing] * tangent / sqrt(tangent * tangent + 1.0)
            }
            val open = 1.0 - blocked / sky.brightnessTotal
            val illumination = sky.diffuseShare * open + (1.0 - sky.diffuseShare) * direct
            val expected = (illumination / ReliefShading.ordinaryGround).coerceIn(DARKEST.toDouble(), 1.35)
            val gap = kotlin.math.abs(drawn - expected)
            println("RELIEF plane falling at %5.1f degrees on the ground: drawn %.4f, the ground's %.4f".format(360.0 * facing / PLANE_FACINGS, drawn, expected))
            if (gap > worst) { worst = gap; worstFacing = facing }
        }
        assertTrue(
            worst <= PLANE_TOLERANCE,
            "a plane falling at ${360.0 * worstFacing / PLANE_FACINGS} degrees on the ground is drawn " +
                "${"%.4f".format(worst)} off the shading of the ground it stands for"
        )
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
