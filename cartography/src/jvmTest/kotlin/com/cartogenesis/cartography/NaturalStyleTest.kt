package com.cartogenesis.cartography

import com.cartogenesis.worldgen.pipeline.Biome
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Whether [MapStyle.NATURAL] keeps the two promises a photographic palette makes.
 *
 * The first is legibility. A map drawn as a photograph has no paper to letter on: its ground runs
 * from a rainforest at a relative luminance of 0.09 to an ice sheet at 0.75, and the sheet's
 * lettering, its coastline and its realm borders are all ruled straight across that in one ink.
 * The arithmetic below settles what that ink can and cannot be, and it settles it by searching
 * rather than by asserting a colour somebody liked.
 *
 * The second is the style's actual claim — "saturated but earthy" — which sounds like taste and is
 * not. Saturation and value are measurable, the reference photograph has an envelope of its own,
 * and a stop outside it is either a neon that no ground on Earth reaches or a pastel that reads as
 * a chart rather than a photograph. The bars are the reference's own figures.
 *
 * Every colour this file measures is reached through [MapStyle.ground] and [MapStyle.ocean], so it
 * is measuring what the rasterizer will draw rather than a table sitting beside it.
 */
class NaturalStyleTest {

    private companion object {

        val STYLE = MapStyle.NATURAL

        /** WCAG 2.1 AA for body text, success criterion 1.4.3. */
        const val AA = 4.5

        /**
         * How vivid the most vivid ground in the reference photograph is.
         *
         * Saturation times value in HSV, which is the pair of numbers "saturated but earthy" is
         * actually about: a neon is high in both, an earth is high in one. The reference's own
         * ceiling is its Mississippi lowland at (300,288)-(316,304) — #3A6904, saturation 0.962 at
         * a value of only 0.412, so 0.396. Nothing in this style may be more vivid than the
         * photograph it was measured off, and 0.40 is that figure rounded up to the nearest
         * hundredth rather than a bar chosen to clear.
         */
        const val MOST_VIVID = 0.40

        /**
         * And how washed out the least saturated *coloured* ground in it is.
         *
         * The Pacific north-west at (232,250)-(246,264) — #668042, saturation 0.484 — which is the
         * palest thing in the reference that is still plainly a colour. Below this a stop is a
         * pastel, and the whole point of the style is that a photograph of a planet has no pastels
         * in it. Measured on every stop but the summit, because snow has no hue at all: the
         * reference's own reads 0.019.
         */
        const val LEAST_SATURATED = 0.48

        /** Steps the ground sweep takes along each of its axes. */
        const val HEIGHT_STEPS = 20
        const val CLIMATE_STEPS = 10

        /** The two biomes that are never painted as ground: the sea has a ramp of its own. */
        val WATER = setOf(Biome.OCEAN, Biome.SHALLOW_OCEAN)

        /**
         * How coarsely the search below walks the colour cube looking for a better ink.
         *
         * Every third value on each channel, so 592,704 candidates. The luminance of a colour is
         * monotone in each channel, so the best ink is at a corner of whatever region the search
         * lands in and a step of three cannot hide it behind a step of one.
         */
        const val INK_SEARCH_STEP = 3
    }

    /**
     * Every colour this style can paint on land, over the whole of [MapStyle.ground]'s inputs.
     *
     * Height, the drought and the cold are swept; the canopy is not, because it is a property of
     * the biome rather than a free variable, and neither is the dryness beyond the band the biome
     * itself allows ([ClimateTint.bareEarthLeast] to [ClimateTint.bareEarthMost]). Sweeping outside
     * those would measure ground the rasterizer can never ask for and would make the guard answer
     * a question nobody asked.
     */
    private fun everyGround(style: MapStyle): List<Int> {
        val painted = ArrayList<Int>()
        Biome.entries.filter { it !in WATER }.forEach { biome ->
            val least = ClimateTint.bareEarthLeast(biome)
            val most = ClimateTint.bareEarthMost(biome)
            val canopy = ClimateTint.canopyClosure(biome)
            for (h in 0..HEIGHT_STEPS) {
                for (d in 0..CLIMATE_STEPS) {
                    val dryness = least + (most - least) * d / CLIMATE_STEPS
                    for (c in 0..CLIMATE_STEPS) {
                        painted += style.ground(
                            h.toFloat() / HEIGHT_STEPS,
                            dryness,
                            c.toFloat() / CLIMATE_STEPS,
                            canopy,
                            biome
                        )
                    }
                }
            }
        }
        return painted
    }

    /** HSV saturation and value, which is what "saturated but earthy" is a claim about. */
    private fun saturationAndValue(colour: Int): Pair<Double, Double> {
        val red = ((colour shr 16) and 0xFF) / 255.0
        val green = ((colour shr 8) and 0xFF) / 255.0
        val blue = (colour and 0xFF) / 255.0
        val value = maxOf(red, green, blue)
        val lowest = minOf(red, green, blue)
        return (if (value <= 0.0) 0.0 else (value - lowest) / value) to value
    }

    private fun Double.rounded(places: Int = 2): String {
        var scale = 1.0
        repeat(places) { scale *= 10 }
        return (kotlin.math.round(this * scale) / scale).toString()
    }

    /**
     * The ink over the two grounds where WCAG's body-text bar is actually reachable.
     *
     * The paper is the plate the sheet draws its scale bar on (`MapRasterizer` hands it out as
     * `marginPaper`, and the ink beside it as `marginInk`, which is this style's coastline), and
     * the palest ground is an ice sheet, which is where a graticule figure is most at risk of
     * disappearing. Both clear AA and by a wide margin, which is what a near-black ink is for.
     */
    @Test
    fun `the label ink reads at AA over the paper it is plated on and over the palest ground`() {
        val ink = STYLE.coastline
        val palest = everyGround(STYLE).maxBy { ColorVision.luminance(it) }

        val overPaper = ColorVision.contrast(ink, STYLE.paper)
        val overPalest = ColorVision.contrast(ink, palest)
        println(
            "NATURAL ink #%06X over paper #%06X %.2f:1, over the palest ground #%06X %.2f:1 (bar %.1f)"
                .format(ink and 0xFFFFFF, STYLE.paper and 0xFFFFFF, overPaper, palest and 0xFFFFFF, overPalest, AA)
        )
        assertTrue(overPaper >= AA, "the ink reads ${overPaper.rounded()}:1 over the paper, under AA")
        assertTrue(
            overPalest >= AA,
            "the ink reads ${overPalest.rounded()}:1 over the palest ground, under AA"
        )
    }

    /**
     * Why the guard above stops where it does, and that the ink is the best answer available.
     *
     * A single ink cannot clear AA over both ends of a photographic ground, and that is arithmetic
     * rather than an opinion: the darkest ground this style paints is a temperate rainforest at a
     * relative luminance of about 0.087, so even pure black reaches only 2.75:1 over it, and any
     * ink light enough to do better there is lighter than the ice sheet is dark. The search below
     * establishes the ceiling instead of taking anyone's word for it, and asserts that it is under
     * AA — so if a later chunk ever lifts this style's darkest ground far enough that AA becomes
     * reachable at both ends, this test fails and says to raise the bar rather than quietly
     * leaving the ink where it was.
     *
     * What can be asserted of the dark end is that the ink is the darkest thing the style owns:
     * nothing it draws — no ramp stop, no water, no ground, no border — is closer to black than
     * the ink ruled across all of them.
     */
    @Test
    fun `no ink clears AA over both ends of this ground, and this one is the darkest the style owns`() {
        val grounds = everyGround(STYLE)
        val darkest = grounds.minBy { ColorVision.luminance(it) }
        val palest = grounds.maxBy { ColorVision.luminance(it) }

        var bestInk = 0
        var bestWorstCase = 0.0
        var channelRed = 0
        while (channelRed < 256) {
            var channelGreen = 0
            while (channelGreen < 256) {
                var channelBlue = 0
                while (channelBlue < 256) {
                    val candidate = (0xFF shl 24) or (channelRed shl 16) or
                        (channelGreen shl 8) or channelBlue
                    val worstCase = minOf(
                        ColorVision.contrast(candidate, darkest),
                        ColorVision.contrast(candidate, palest)
                    )
                    if (worstCase > bestWorstCase) {
                        bestWorstCase = worstCase
                        bestInk = candidate
                    }
                    channelBlue += INK_SEARCH_STEP
                }
                channelGreen += INK_SEARCH_STEP
            }
            channelRed += INK_SEARCH_STEP
        }

        val ink = STYLE.coastline
        println(
            ("NATURAL ground runs #%06X (luminance %.4f) to #%06X (%.4f); the best ink over both " +
                "ends is #%06X at %.2f:1, and this style's #%06X reaches %.2f:1 over the darkest")
                .format(
                    darkest and 0xFFFFFF, ColorVision.luminance(darkest),
                    palest and 0xFFFFFF, ColorVision.luminance(palest),
                    bestInk and 0xFFFFFF, bestWorstCase,
                    ink and 0xFFFFFF, ColorVision.contrast(ink, darkest)
                )
        )
        assertTrue(
            bestWorstCase < AA,
            "an ink now reaches ${bestWorstCase.rounded()}:1 over both ends of this style's " +
                "ground, so AA is reachable at both and this guard should assert it"
        )

        // And that the ink is the style's own black: darker than every ground and every other
        // colour it draws with, which is the only sense in which one ink over a photograph can be
        // the right one.
        val inkLuminance = ColorVision.luminance(ink)
        val everythingElse = grounds +
            STYLE.landRamp.toList() + STYLE.oceanRamp.toList() +
            listOf(STYLE.paper, STYLE.river, STYLE.lake, STYLE.lakeDeep, STYLE.wilderness)
        val nearestToBlack = everythingElse.minBy { ColorVision.luminance(it) }
        assertTrue(
            inkLuminance < ColorVision.luminance(nearestToBlack),
            "#%06X is darker than the ink #%06X ruled across it".format(
                nearestToBlack and 0xFFFFFF, ink and 0xFFFFFF
            )
        )
        assertTrue(
            STYLE.border == ink,
            "the borders are inked in #%06X and the lettering in #%06X, which is two inks"
                .format(STYLE.border and 0xFFFFFF, ink and 0xFFFFFF)
        )
    }

    /**
     * That the land ramp is still a hypsometric series under everything else.
     *
     * The cover carries most of the colour here — the biome wash is the highest of any style that
     * is not the illustrated one — so the ramp underneath has one job left, which is to make
     * height read where the cover is uniform. It can only do that if every stop is lighter than
     * the one below it: a ramp that dips is a ramp on which two different heights are the same
     * grey, and on a green-on-green map that is the whole of the shape gone.
     */
    @Test
    fun `the land ramp climbs in lightness at every step`() {
        val stops = STYLE.landRamp
        val ladder = stops.joinToString(", ") {
            "#%06X %.3f".format(it and 0xFFFFFF, ColorVision.luminance(it))
        }
        println("NATURAL land ramp: $ladder")
        for (stop in 1 until stops.size) {
            val below = ColorVision.luminance(stops[stop - 1])
            val here = ColorVision.luminance(stops[stop])
            assertTrue(
                here > below,
                "land stop $stop (#%06X, %.4f) is no lighter than the stop below it (#%06X, %.4f)"
                    .format(stops[stop] and 0xFFFFFF, here, stops[stop - 1] and 0xFFFFFF, below)
            )
        }
        // The sea has the same promise the other way round: it is written abyss first, so it
        // climbs too, and a sea that dipped would put two depths at one blue.
        val sea = STYLE.oceanRamp
        for (stop in 1 until sea.size) {
            assertTrue(
                ColorVision.luminance(sea[stop]) > ColorVision.luminance(sea[stop - 1]),
                "ocean stop $stop is no lighter than the stop below it"
            )
        }
    }

    /**
     * "Saturated but earthy", measured against the photograph the phrase was said about.
     *
     * Two bars, one at each end, and both of them figures the reference itself produced. Nothing
     * may be more vivid than its most vivid ground, which rules out a neon; nothing that carries a
     * hue at all may be less saturated than its palest coloured ground, which rules out a pastel.
     * The summit is exempt from the second because snow is not a colour — the reference's own
     * reads 0.019 saturated.
     */
    @Test
    fun `every stop of the land ramp is saturated but earthy`() {
        val stops = STYLE.landRamp
        stops.forEachIndexed { index, stop ->
            val (saturation, value) = saturationAndValue(stop)
            val summit = index == stops.lastIndex
            println(
                "NATURAL land stop %d #%06X saturation %.3f, value %.3f, vividness %.3f%s".format(
                    index, stop and 0xFFFFFF, saturation, value, saturation * value,
                    if (summit) " (the snow line, exempt from the saturation floor)" else ""
                )
            )
            assertTrue(
                saturation * value <= MOST_VIVID,
                "land stop $index is ${(saturation * value).rounded(3)} vivid, past the " +
                    "reference's own $MOST_VIVID: that is a neon, not an earth"
            )
            if (!summit) {
                assertTrue(
                    saturation >= LEAST_SATURATED,
                    "land stop $index is only ${saturation.rounded(3)} saturated, under the " +
                        "reference's palest coloured ground at $LEAST_SATURATED: that is a pastel"
                )
            }
        }
    }
}
