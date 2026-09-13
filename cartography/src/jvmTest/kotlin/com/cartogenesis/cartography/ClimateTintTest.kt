package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whether a desert is ever drawn as a lawn.
 *
 * The hypsometric ramp is a claim about height and every style's is written for a wet world, so
 * before this the sand of the Sahara came out of the same green the Rhine valley did, with a wash of
 * desert colour over it that only made it a yellower green. The measurement below is on the rendered
 * pixel, not on the palette, and its question is the simplest one a person could ask of a colour:
 * **is green the strongest of its three channels?** A surface whose strongest reflectance is in the
 * green band is a surface with chlorophyll on it, which is exactly what a desert has none of; sand
 * is red-first at every lightness, and so is every earth.
 *
 * The control is the land colour as it was drawn before F13 — the ramp read at the cell's own height
 * and washed with the biome — reproduced here rather than remembered, so what fails is something
 * this file can still run. The relief cannot rescue either of them: it multiplies all three channels
 * by one factor, which cannot change which of them is largest.
 *
 * [MapStyle.CLEAR] is measured beside the rest and asserted differently, because it promises
 * something else. Its ramp is ordered by lightness and nothing on it is told by hue at all — that is
 * what makes it readable to a dichromat — so lightness is the one channel it has for height and it
 * cannot spend any of it on climate. Its shore is a dark olive whose green channel is three of 255
 * above its red, and that is the promise being kept rather than a desert drawn as a lawn; what is
 * asserted of it here is that the climate leaves it exactly where the ramp put it, and
 * `ClearStyleTest` holds the ramp itself to its CIEDE2000 ladder.
 */
class ClimateTintTest {

    private companion object {

        /** The gallery's world, at the size these guards measure on. See [TestWorlds]. */
        val WORLD: WorldMap get() = TestWorlds.gallery

        /** Below this the world has no desert worth measuring and the guard would pass vacuously. */
        const val MIN_DESERT_CELLS = 500

        /** And below this a biome is too rare on this world for its mean to say anything. */
        const val MIN_BIOME_CELLS = 200

        /** The biomes whose whole definition is a closed woody canopy over the ground. */
        val CLOSED_FORESTS = listOf(
            Biome.TEMPERATE_FOREST,
            Biome.TEMPERATE_RAINFOREST,
            Biome.TROPICAL_SEASONAL_FOREST,
            Biome.TROPICAL_RAINFOREST,
            Biome.MONSOON_FOREST
        )

        /**
         * How dry a closed forest may read, on the 0-to-1 scale [ClimateTint.drynessAt] returns.
         *
         * A fifth, which is where the lift toward the earth band is under a tenth of the ramp and
         * invisible against the biome wash on top of it. It is not zero because the index is a
         * climate's answer and the biome is the classifier's, and the two are allowed to disagree
         * at the margin — a dry-forest cell one millimetre the wet side of the steppe line is
         * genuinely half sand, and drawing it so is the point of this chunk.
         */
        const val MAX_FOREST_DRYNESS = 0.2
    }

    /** Hue in degrees, 0 red, 60 yellow, 120 green — for the report rather than for the assertion. */
    private fun hue(colour: Int): Double {
        val red = ((colour shr 16) and 0xFF) / 255.0
        val green = ((colour shr 8) and 0xFF) / 255.0
        val blue = (colour and 0xFF) / 255.0
        val highest = maxOf(red, green, blue)
        val lowest = minOf(red, green, blue)
        val range = highest - lowest
        if (range <= 0.0) return 0.0
        val turn = when (highest) {
            red -> (green - blue) / range
            green -> 2.0 + (blue - red) / range
            else -> 4.0 + (red - green) / range
        }
        return (turn * 60.0 + 360.0) % 360.0
    }

    private fun readsGreen(colour: Int): Boolean {
        val red = (colour shr 16) and 0xFF
        val green = (colour shr 8) and 0xFF
        val blue = colour and 0xFF
        return green > red && green > blue
    }

    /** Which cells are desert, on dry land, and how high each of them stands. */
    private fun desertCells(world: WorldMap): List<Int> {
        val cells = ArrayList<Int>()
        for (i in 0 until world.width * world.height) {
            if (!world.sea.isLand[i]) continue
            if (world.rivers.lakes.isLake(i)) continue
            if (world.climate.biome[i] == Biome.DESERT) cells.add(i)
        }
        return cells
    }

    @Test
    fun `no desert cell reads green in any style, and every one of them did before`() {
        val world = WORLD
        val desert = desertCells(world)
        assertTrue(
            desert.size >= MIN_DESERT_CELLS,
            "only ${desert.size} desert cells on this world, too few to measure"
        )

        var controlWorst = 0.0
        MapStyle.entries.forEach { style ->
            val drawn = MapRasterizer.rasterize(world, RenderOptions(style = style))
            var green = 0
            var hueTotal = 0.0
            var controlGreen = 0
            var controlHueTotal = 0.0
            desert.forEach { cell ->
                val pixel = drawn[cell]
                if (readsGreen(pixel)) green++
                hueTotal += hue(pixel)

                // The colour this style gave a desert before the climate reached the ramp.
                val before = style.tint(
                    style.land(world.sea.relativeElevation.data[cell]), Biome.DESERT
                )
                if (readsGreen(before)) controlGreen++
                controlHueTotal += hue(before)
            }
            val share = green * 100.0 / desert.size
            val controlShare = controlGreen * 100.0 / desert.size
            if (controlShare > controlWorst) controlWorst = controlShare
            println(
                ("DESERT %-13s %.1f%% of %d desert cells read green, mean hue %.0f degrees; " +
                    "before F13, %.1f%% and %.0f degrees")
                    .format(
                        style.label, share, desert.size, hueTotal / desert.size,
                        controlShare, controlHueTotal / desert.size
                    )
            )
            if (style != MapStyle.CLEAR) {
                assertTrue(
                    green == 0,
                    "$green of ${desert.size} desert cells read green in ${style.label}"
                )
            }
        }
        assertTrue(
            controlWorst > 0.0,
            "no style drew a green desert before F13 either, so this guard proves nothing"
        )
    }

    /**
     * What the climate says about each vegetation, over a whole world.
     *
     * The desert guard above asks whether the driest ground is drawn dry; this asks the other half,
     * which is whether the wettest is drawn wet. A model that sanded everything would pass the
     * first and fail this one — and a forest drawn on sand is the more visible mistake of the two,
     * because there is far more forest on a map than there is desert.
     */
    @Test
    fun `the wet biomes are never drawn as dry ground`() {
        val world = WORLD
        val cells = world.width * world.height
        val total = DoubleArray(Biome.entries.size)
        val count = IntArray(Biome.entries.size)
        for (i in 0 until cells) {
            if (!world.sea.isLand[i]) continue
            val biome = world.climate.biome[i].ordinal
            total[biome] += ClimateTint.drynessAt(world, i).toDouble()
            count[biome]++
        }
        Biome.entries.forEach { biome ->
            if (count[biome.ordinal] == 0) return@forEach
            println(
                "DESERT %-26s mean dryness %.2f over %d cells".format(
                    biome.name, total[biome.ordinal] / count[biome.ordinal], count[biome.ordinal]
                )
            )
        }
        CLOSED_FORESTS.forEach { biome ->
            val cellsHere = count[biome.ordinal]
            if (cellsHere < MIN_BIOME_CELLS) return@forEach
            val mean = total[biome.ordinal] / cellsHere
            assertTrue(
                mean <= MAX_FOREST_DRYNESS,
                "$biome reads $mean dry over $cellsHere cells, past $MAX_FOREST_DRYNESS"
            )
        }
    }

    /**
     * That the aridity index is De Martonne's, at the two boundaries the ramp is hung on.
     *
     * A unit check rather than a rendering one, because the index is the one piece of arithmetic
     * here that has a right answer: 300 mm at 25 °C is 8.6, which is his arid class, and 1,000 mm
     * at 10 °C is 50, which is his humid one.
     */
    @Test
    fun `the aridity index is de Martonne's, and the ramp hangs on his class boundaries`() {
        val sahara = ClimateTint.aridityIndex(meanAnnualC = 25f, annualMm = 300f)
        val temperate = ClimateTint.aridityIndex(meanAnnualC = 10f, annualMm = 1000f)
        println("DESERT aridity index: hot desert %.1f, temperate forest %.1f".format(sahara, temperate))
        assertTrue(sahara < 10f, "a 300 mm year at 25 °C scores $sahara, outside De Martonne's arid class")
        assertTrue(temperate > 20f, "a 1000 mm year at 10 °C scores $temperate, which is not humid")

        // And that the polar case does not divide by zero, which is where his index runs out.
        val polar = ClimateTint.aridityIndex(meanAnnualC = -25f, annualMm = 50f)
        assertTrue(polar.isFinite() && polar > 0f, "a polar desert scores $polar")
        assertTrue(
            ClimateTint.coldness(-25f) == 1f && ClimateTint.coldness(5f) == 0f,
            "the cold term does not run from the tundra line to the ice cap"
        )
    }

    /**
     * That the two styles which hold the modulation at zero draw exactly what they drew before it.
     *
     * The colour-blind ramp is a promise measured in CIEDE2000 and the engraving has no tint at all,
     * so both must come out of [MapStyle.ground] as the plain ramp and the plain wash — the same
     * arithmetic, in the same order, that `MapStyle.tint(land(height))` was.
     */
    @Test
    fun `the styles that hold the climate at zero are untouched by it`() {
        listOf(MapStyle.CLEAR, MapStyle.PEN_AND_INK).forEach { style ->
            for (step in 0..20) {
                val height = step / 20f
                Biome.entries.forEach { biome ->
                    assertTrue(
                        style.ground(height, 1f, 1f, 1f, biome) ==
                            style.tint(style.land(height), biome),
                        "${style.label} moved at height $height under $biome"
                    )
                }
            }
        }
    }
}
