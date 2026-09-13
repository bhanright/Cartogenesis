package com.cartogenesis.cartography

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
 *    from somewhere, and the darkest face keeps half of what the brightest has.
 *  - **It is no flatter than what it replaces.** Light from every direction is softer than light
 *    from one, so the question is how much softer. The two contrasts are measured on the same
 *    world and held within a seventh of each other; the sky share in [ReliefShading] is calibrated
 *    against exactly this measurement.
 */
class ReliefShadingTest {

    private companion object {

        /** The gallery's world, at the size these guards measure on. See [TestWorlds]. */
        val WORLD: WorldMap get() = TestWorlds.gallery

        /** The exaggeration and the stencil a 512 map is drawn with. */
        val SLOPE_SCALE = ReliefShading.slopeScale(512)
        val OPENNESS_STEP = ReliefShading.opennessStep(512)

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
         * How dark the darkest face of a cone may be against its brightest, as a ratio.
         *
         * The secondary claim, and a narrow one: the sky holds the darkest flank at 0.53 of the
         * brightest and the single lamp at 0.44, so a bar of a half sits between them. The claims
         * that carry this guard are the two either side of it, which are exact rather than
         * measured — a third of this cone receives no light at all from the lamp, and every part
         * of it receives some from the dome.
         */
        const val MIN_FLANK_RATIO = 0.5

        /**
         * How far the two models' contrasts may differ, as a share of the lamp's.
         *
         * Light from every direction is softer than light from one, and the model does not try to
         * make all of that back: it measures 10.9% flatter over the land of the gallery's world.
         * The bar is a seventh, which leaves a quarter of the headroom and would still catch a
         * model that had lost a third of its relief.
         */
        const val MAX_CONTRAST_DRIFT = 0.15
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
        for (y in 0 until world.height) {
            for (x in 0 until world.width) {
                if (!land[y * world.width + x]) continue
                val eastward =
                    (elevation.sample(x + 1, y) - elevation.sample(x - 1, y)) * SLOPE_SCALE
                val southward =
                    (elevation.sample(x, y + 1) - elevation.sample(x, y - 1)) * SLOPE_SCALE
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
                ReliefShading.at(x, y, field, SLOPE_SCALE, OPENNESS_STEP, singleLamp)
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
     * the north-west light at 32 degrees, exactly as every render before F13 was drawn under. Where
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
        assertTrue(
            sky.ratio >= MIN_FLANK_RATIO,
            "the darkest face of the cone keeps only ${"%.2f".format(sky.ratio)} of the light of " +
                "the brightest under the sky, short of $MIN_FLANK_RATIO"
        )
        assertTrue(
            lamp.ratio < MIN_FLANK_RATIO,
            "the single lamp now keeps ${"%.2f".format(lamp.ratio)} of the light on the dark side " +
                "as well, so this guard no longer separates the two models"
        )
    }

    @Test
    fun `the sky model has the same contrast as the lamp it replaces`() {
        val world = WORLD
        val sky = Spread(
            ReliefShading.of(world.sea.relativeElevation, world.sea.isLand, singleLamp = false),
            world
        )
        val lamp = Spread(
            ReliefShading.of(world.sea.relativeElevation, world.sea.isLand, singleLamp = true),
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
        assertTrue(
            sky.crushed <= lamp.crushed,
            ("the sky model pins %.2f%% of the land at the darkest factor it has, against the " +
                "lamp's %.2f%% — which is the thing it exists to stop doing")
                .format(sky.crushed * 100, lamp.crushed * 100)
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
            for (i in shade.indices) if (land[i]) lit.add(shade[i])
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
