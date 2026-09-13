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

        /**
         * Which styles are asked where their steppe sits.
         *
         * The ones that let most of the climate through, since a style that lets a fifth of it
         * through is making a different claim and its three vegetations are meant to be close
         * together.
         */
        const val STRONG_CLIMATE_TINT = 0.5f

        /**
         * How far along the road from desert to forest a steppe must stand, in CIEDE2000.
         *
         * A quarter, and the same figure the other way for the forest end, so a steppe has to be a
         * colour of its own rather than either neighbour wearing a slightly different hat. The
         * number comes from the same place [ClimateTint]'s bands do: on the fractional-cover axis
         * those classes are defined on, a steppe shows something like a third of the bare ground a
         * desert does and several times what a forest does, so a quarter of the way is a floor
         * rather than a target. Measured against a style's own span rather than in absolute
         * degrees, because a style that mutes everything toward its paper — Scroll's forest is nine
         * degrees of hue from its desert — is making a different claim, and the guard should ask
         * whether the steppe sits inside *that* claim.
         */
        const val MIN_STEPPE_SHARE = 0.25

        /** The single bare-earth figure the first pass of F13 gave a grassland. See [steppeBefore]. */
        const val BEFORE_GRASSLAND_BARE = 0.15f

        /** How much of a desert's bare ground a steppe may read as. See the assertion's note. */
        const val MAX_STEPPE_SHARE_OF_DESERT = 0.5
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
     * That a steppe is drawn between a forest and a desert, and not as either.
     *
     * The other end of the same claim the desert guard makes, and the one the first pass of F13 got
     * wrong: the drought lift saturated well before the arid line, so grassland came out at 0.74 of
     * the way to bare ground and the interior of a continent read as Sahara. On a physical atlas
     * the Great Plains and the Kazakh steppe are straw or olive — plainly not forest, plainly not
     * sand — and hue is where that difference lives: measured on the rendered pixels of Atlas,
     * desert sits at 40 degrees, forest at 78, and a steppe belongs between them with room either
     * side. Before the fix it measured 46, six degrees off the desert and thirty-two off the
     * forest.
     */
    @Test
    fun `a steppe is drawn between the forest and the desert`() {
        val world = WORLD
        MapStyle.entries.filter { it.climateTint >= STRONG_CLIMATE_TINT }.forEach { style ->
            val drawn = MapRasterizer.rasterize(world, RenderOptions(style = style))
            val desert = meanColour(drawn, world, listOf(Biome.DESERT))
            val steppe = meanColour(drawn, world, listOf(Biome.GRASSLAND))
            val forest = meanColour(drawn, world, CLOSED_FORESTS)
            val before = steppeBefore(world, style)

            val span = ColorVision.deltaE2000(desert, forest)
            val share = ColorVision.deltaE2000(desert, steppe) / span
            val shareBefore = ColorVision.deltaE2000(desert, before) / span
            println(
                ("DESERT %-13s hue: desert %.0f, steppe %.0f, forest %.0f degrees; the steppe " +
                    "stands %.0f%% of the %.1f CIEDE2000 from desert to forest, against %.0f%% " +
                    "before the biome's band bounded the lift")
                    .format(
                        style.label, hue(desert), hue(steppe), hue(forest),
                        share * 100, span, shareBefore * 100
                    )
            )
            assertTrue(
                share >= MIN_STEPPE_SHARE && share <= 1.0 - MIN_STEPPE_SHARE,
                "${style.label}: a steppe stands ${"%.0f".format(share * 100)}% of the way from " +
                    "the desert to the forest, outside " +
                    "${(MIN_STEPPE_SHARE * 100).toInt()}-${((1 - MIN_STEPPE_SHARE) * 100).toInt()}%"
            )
        }
    }

    /**
     * What this style gave a steppe before the biome's band bounded the drought's lift.
     *
     * The first pass of F13 took the drier of two numbers — the climate's drought and one
     * bare-earth figure per biome — so a grassland with an arid index went all the way to bare
     * ground. Reproduced here rather than remembered, so the control is something this file can
     * still run.
     */
    private fun steppeBefore(world: WorldMap, style: MapStyle): Int {
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        for (cell in 0 until world.width * world.height) {
            if (!world.sea.isLand[cell] || world.rivers.lakes.isLake(cell)) continue
            if (world.climate.biome[cell] != Biome.GRASSLAND) continue
            val drought = ClimateTint.droughtAt(world, cell)
            val colour = style.ground(
                world.sea.relativeElevation.data[cell],
                maxOf(BEFORE_GRASSLAND_BARE, drought),
                ClimateTint.coldnessAt(world, cell),
                ClimateTint.canopyClosure(Biome.GRASSLAND),
                Biome.GRASSLAND
            )
            red += (colour shr 16) and 0xFF
            green += (colour shr 8) and 0xFF
            blue += colour and 0xFF
            count++
        }
        return packed(red / count, green / count, blue / count)
    }

    /** The mean rendered colour of the land cells of [biomes]. */
    private fun meanColour(drawn: IntArray, world: WorldMap, biomes: List<Biome>): Int {
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        for (cell in drawn.indices) {
            if (!world.sea.isLand[cell] || world.rivers.lakes.isLake(cell)) continue
            if (world.climate.biome[cell] !in biomes) continue
            red += (drawn[cell] shr 16) and 0xFF
            green += (drawn[cell] shr 8) and 0xFF
            blue += drawn[cell] and 0xFF
            count++
        }
        return packed(red / count, green / count, blue / count)
    }

    private fun packed(red: Long, green: Long, blue: Long): Int =
        (0xFF shl 24) or (red.toInt() shl 16) or (green.toInt() shl 8) or blue.toInt()

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

        // And the steppe's own bound, which is where the first pass of F13 went wrong: it took the
        // drier of the climate's drought and one figure per biome, so a grassland with an arid
        // index went all the way to bare ground and measured 0.74 against a desert's 1.00. The
        // classes these biomes are named for put a grassland's bare ground at half a desert's at
        // the very driest, so half is the bound rather than a target.
        val steppe = total[Biome.GRASSLAND.ordinal] / count[Biome.GRASSLAND.ordinal]
        val desert = total[Biome.DESERT.ordinal] / count[Biome.DESERT.ordinal]
        println("DESERT a steppe reads %.2f dry against a desert's %.2f".format(steppe, desert))
        assertTrue(
            steppe <= desert * MAX_STEPPE_SHARE_OF_DESERT,
            "a steppe reads ${"%.2f".format(steppe)} dry against a desert's " +
                "${"%.2f".format(desert)}, past ${MAX_STEPPE_SHARE_OF_DESERT} of it"
        )
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
