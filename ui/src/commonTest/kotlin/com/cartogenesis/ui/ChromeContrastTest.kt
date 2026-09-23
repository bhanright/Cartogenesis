package com.cartogenesis.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.cartogenesis.cartography.ColorVision
import com.cartogenesis.cartography.ColorVision.Deficiency.DEUTERANOPIA
import com.cartogenesis.cartography.ColorVision.Deficiency.PROTANOPIA
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the chromes that made a promise in numbers actually measure.
 *
 * Most of the seventeen are claims about appearance and are reviewed by looking at a screenshot.
 * Eight are not. "High contrast" and "colour-blind" are claims with published thresholds behind
 * them, and a chrome that made either claim and missed it would be worse than no chrome at all — a
 * reader would choose it *because* of the promise. The four typographic chromes — Matrix, Hessian,
 * Roman and Hitchcock — and the two two-colour rooms, Lemon Blueberry and Blacklight, make a
 * quieter promise of the same kind: a chrome is a room somebody works in for an hour, and one
 * whose secondary text or whose armed button sat under WCAG AA would be a room nobody could work
 * in, however good it looked. That holds of every chrome, so every one is measured at AA as well,
 * and the four that fail today are kept running as known failures.
 *
 * So each is measured with [ColorVision], which is also what `ClearStyleTest` measures the map style
 * with, so the two guards cannot come to mean different things by the same number.
 */
class ChromeContrastTest {

    private companion object {

        /**
         * WCAG 2.1 AAA for body text (success criterion 1.4.6).
         *
         * Not a number this project chose. 4.5 is AA, 7 is AAA, and a chrome called High contrast
         * that stopped at AA would be a chrome called High contrast that was merely adequate.
         */
        const val AAA = 7.0

        /**
         * WCAG 2.1 AA for body text (1.4.6 again, the lower of its two bars).
         *
         * What the four typographic chromes are held to. They are not accessibility
         * chromes and do not claim to be; this is the floor below which a chrome stops being usable
         * for an hour's work, which is the whole of what was asked of them.
         */
        const val AA = 4.5

        /**
         * Hessian's weave, as an alpha.
         *
         * The chrome draws two families of hairlines at ±45° behind everything a panel holds, at 8%.
         * A glyph's stem is a dp or two wide and can sit squarely on one of them, so the ground a
         * word is actually read against is the panel with the weave composited in — which is
         * *darker*, and for dark thread on light cloth that means *less* contrast, not more. This is
         * what the spec means by measuring the crosshatch in, and it is not academic: the burlap the
         * spec names measured 4.34:1 with the weave in and 4.86:1 without.
         */
        const val WEAVE = 0.08f

        /**
         * WCAG 2.1 1.4.11, for a control's own boundary rather than for anything read.
         *
         * The distinction matters exactly once here, and it is why the saturated blue survives: see
         * [ChromeDetail.markAccent].
         */
        const val NON_TEXT = 3.0

        /**
         * How far apart two accents must stay under a simulation, in CIEDE2000.
         *
         * Chosen from the measurement rather than the other way round. About 1 is the smallest
         * difference a trained eye finds in a laboratory, 2 to 3 is a difference a printer will
         * argue about, and 5 upward is what anybody would call two colours. The four accents of the
         * Colorblind chrome measure **13.31** at their closest, over normal vision, deuteranopia
         * and protanopia together; the bar is set at 10, which is a quarter of that headroom given
         * away and still twice the "obviously two colours" figure. It is deliberately not set just
         * under the measurement — a guard that fails when a colour moves by a hair is a guard
         * nobody keeps — and deliberately not at 5, because at 5 the orange and the vermillion the
         * first draft of this chrome used (6.04 under deuteranopia) would have passed.
         *
         * Shown to bite twice: putting that first-draft vermillion (#EE7733) back gave "current and
         * failed are only 6.04 apart under deuteranopia, short of 10.0", and giving High contrast
         * its mark blue as its text colour gave "the accent as a word, on a panel measures 4.72:1,
         * short of AAA's 7.0:1".
         */
        const val ACCENT_MARGIN = 10.0

        /**
         * How far the alarm has to sit from the two colours a two-colour chrome is made of.
         *
         * The same currency as [ACCENT_MARGIN] and a different question, so a different figure: not
         * "can a reader with a colour deficiency tell these two states apart", but "is this a third
         * colour at all, or a shade of one of the two". 20 is well past the point where anybody
         * would call it two colours, and it is what a chrome built from a pair has to clear before
         * its alarm means anything — Lemon Blueberry's pink measures 51.1 from the lemon accent,
         * 59.0 from the panel it is read on and 43.2 from the ink beside it, and Blacklight's
         * orange 39.3, 71.0 and 30.0 from the same three.
         */
        const val ALARM_MARGIN = 20.0

        /**
         * #520C94's hue angle in CIE L*a*b*, in degrees, and #E6FF42's.
         *
         * The two colours Blacklight was given, reduced to the one property the chrome holds every
         * tone to. See the guard that reads them.
         */
        const val VIOLET_HUE = 312.7
        const val LIME_HUE = 110.5

        /**
         * How far a tone may sit off the hue it belongs to, in degrees.
         *
         * Set from the measurement rather than the other way round: the palette was built by
         * naming a lightness and a chroma for each tone and converting back through L*a*b*, so
         * every tone lands within **0.36** of its hue and the rest of that is rounding to eight
         * bits a channel. One degree gives that a little under three times the room it needs and
         * is still far too tight for a tone to have been re-picked by eye.
         */
        const val HUE_DRIFT = 1.0

        /**
         * The chromes under AA today, by the name the every-chrome clause gives them: the finding
         * each records and the pairs short of the bar with what they measure (Audit III, G-I5).
         */
        val CHROMES_UNDER_AA: Map<String, Pair<String, String>> = mapOf(
            "Dark" to ("Audit III G-I5: Dark's error ink, which the seed field letters its label in, is under AA" to
                "an error, on a panel 1.63"),
            "System (dark host)" to ("Audit III G-I5: System's dark chrome letters the seed field's error label under AA" to
                "an error, on a panel 1.63"),
            "Mars" to ("Audit III G-I5: Mars's armed button and its error word are under AA" to
                "the armed button's label 3.9; an error, on a panel 4.44"),
            "Allied" to ("Audit III G-I5: Allied's accent as a word is under AA" to
                "the accent as a word, on a panel 4.14"),
            "Hallowed" to ("Audit III G-I5: Hallowed's accent word, armed button and label on the accent are under AA" to
                "the accent as a word, on a panel 4.17; the armed button's label 3.88; a label on the accent 4.17")
        )

        /**
         * The eleven older chromes, role by role, as they are meant to be.
         *
         * Thirty-six ARGB values each, in the order [roles] writes them. Eight were recorded from
         * `main` at 27fd260, before the four typographic chromes were written, by running exactly
         * that function on that tree. Three — SYSTEM, LIGHT and DARK — were re-recorded when their
         * grounds were cooled: the warm brown-black of the dark ones became a neutral charcoal and
         * Vellum's yellow paper an atlas plate's off-white. A row is re-taken only where a decision
         * moved a colour on purpose, and every other chrome here is byte for byte what it was.
         */
        val RECORDED_ROLES: List<Pair<String, String>> = listOf(
        "SYSTEM" to
            "ff6b3f2a,fffaf8f3,ffe7dfcb,ff2b2117,ffc9a227,ff5b4a2f," +
            "fffaf8f3,ffe7dfcb,ff2b2117,ff6e5b3c,fffaf8f3,ffe7dfcb," +
            "ff2b2117,fff4f1ea,ff2b2117,fffaf8f3,ff2b2117,ffe9e4d8," +
            "ff6e5b3c,fffaf8f3,ff2b2117,fffaf8f3,ff8a3b2e,fffaf8f3," +
            "ffe8cfc0,ff3a1810,ffc2b9a9,ffd9d3c7,ff17120b,fffdfbf7," +
            "ffe8e3d6,fffefdf9,fffbf9f4,fffaf8f3,fff2efe7,ffe9e4d8",
        "LIGHT" to
            "ff6b3f2a,fffaf8f3,ffe7dfcb,ff2b2117,ffc9a227,ff5b4a2f," +
            "fffaf8f3,ffe7dfcb,ff2b2117,ff6e5b3c,fffaf8f3,ffe7dfcb," +
            "ff2b2117,fff4f1ea,ff2b2117,fffaf8f3,ff2b2117,ffe9e4d8," +
            "ff6e5b3c,fffaf8f3,ff2b2117,fffaf8f3,ff8a3b2e,fffaf8f3," +
            "ffe8cfc0,ff3a1810,ffc2b9a9,ffd9d3c7,ff17120b,fffdfbf7," +
            "ffe8e3d6,fffefdf9,fffbf9f4,fffaf8f3,fff2efe7,ffe9e4d8",
        "DARK" to
            "ffc9a227,ff121417,ff292619,ffc9a227,ff6b3f2a,ff8d7326," +
            "ff121417,ff292619,fff2e7cf,ff9c9187,ff121417,ff1f2123," +
            "ffe8dfd0,ff121417,ffe8dfd0,ff191c20,ffe8dfd0,ff21252a," +
            "ff9c9187,ff191c20,fff2e7cf,ff121417,ff7e1414,fff2e7cf," +
            "ff5d0000,fff2e7cf,ff363c44,ff282d33,ff070809,ff272c31," +
            "ff121417,ff0d0f10,ff15171b,ff191c20,ff1f2327,ff262a30",
        "NAUTICAL" to
            "ff8a3b2e,fffaf3e2,ffd6e9f0,ff1b2c3a,ff6e9db5,ff3e6e8c," +
            "fffaf3e2,ffd6e9f0,ff1b2c3a,ff4c6172,fffaf3e2,ffedf6f9," +
            "ff1b2c3a,fff4ead2,ff1b2c3a,fffaf3e2,ff1b2c3a,ffe9dcbe," +
            "ff4c6172,fffaf3e2,ff1b2c3a,fffaf3e2,ff9e2b20,fffaf3e2," +
            "ffeed6cf,ff3a120c,ffb8a886,ffd3c4a4,ff10202c,fffdf8ec," +
            "ffe6d9bc,fffefaf1,fffbf5e6,fffaf3e2,fff0e7d0,ffe9dcbe",
        "MIDNIGHT" to
            "ffd8a05a,ff10151f,ff2c2519,ffd8a05a,ff8a5a20,ff7fc6e8," +
            "ff10151f,ff1c2b38,ff7fc6e8,ff8a94a2,ff10151f,ff1e2635," +
            "ffc8d2de,ff10151f,ffc8d2de,ff161d2a,ffc8d2de,ff1e2635," +
            "ff8a94a2,ff161d2a,ffaeb7c4,ff10151f,ffd0705e,ff10151f," +
            "ff4a1c15,fff0d5cf,ff313c4e,ff262f3d,ff070b12,ff27303f," +
            "ff10151f,ff0c1017,ff131924,ff161d2a,ff1c2431,ff222b3a",
        "MARS" to
            "ffc2683a,ff15100d,ff32211a,ffd8a45a,ff7e3a20,ffd8a45a," +
            "ff15100d,ff32211a,ffe8d8c0,ffa8917a,ff15100d,ff261d18," +
            "ffe8d8c0,ff15100d,ffe8d8c0,ff1d1613,ffe8d8c0,ff261d18," +
            "ffa8917a,ff1d1613,ffe8d8c0,ff15100d,ffd4573a,ff15100d," +
            "ff4a1a0e,fff2d9cc,ff453228,ff31241d,ff0a0705,ff2e231c," +
            "ff15100d,ff0f0b09,ff181210,ff1d1613,ff241b16,ff2b211a",
        "HIGH_CONTRAST" to
            "ff6fa8ff,ff000000,ff000000,ff6fa8ff,ff1a6eff,ffffffff," +
            "ff000000,ff000000,ffffffff,ffffffff,ff000000,ff000000," +
            "ffffffff,ff000000,ffffffff,ff000000,ffffffff,ff000000," +
            "ffffffff,ff000000,ffffffff,ff000000,ffff8a75,ff000000," +
            "ff000000,ffff8a75,ffffffff,ffffffff,ff000000,ff1a1a1a," +
            "ff000000,ff000000,ff000000,ff000000,ff1a1a1a,ff1a1a1a",
        "COLORBLIND" to
            "ffe69f00,ff1a1815,ff3a2e14,ffe69f00,ff8a6100,ff56b4e9," +
            "ff1a1815,ff16303c,ff56b4e9,ff2fbf95,ff1a1815,ff10322a," +
            "ff2fbf95,ff211f1d,fff2eee8,ff2a2724,fff2eee8,ff35312d," +
            "ffc0b9b0,ff2a2724,fff2eee8,ff211f1d,ffcc79a7,ff1a1815," +
            "ff35202b,ffcc79a7,ff565049,ff433d37,ff100e0d,ff3c3833," +
            "ff211f1d,ff171614,ff1f1d1b,ff2a2724,ff322e2a,ff3a3631",
        "ALLIED" to
            "ffb22222,fff2ecd9,ffe6d6ac,ff1e2438,ffe28a80,ff1e2438," +
            "ffd9cba3,ff4b5320,fff2ecd9,ff4b5320,fff2ecd9,ffded2ac," +
            "ff2c3212,ff4b5320,fff2ecd9,ffd9cba3,ff1e2438,ffcdbe93," +
            "ff3a4258,ffd9cba3,ff4b5320,fff2ecd9,ff7a1010,fff2ecd9," +
            "ffe2c6a8,ff3a0a0a,ff3a4258,ff8a8f86,ff171a0c,ffefe6cb," +
            "ffc4b48d,ffefe6cb,ffe6dbbb,ffd9cba3,ffcfc09a,ffc4b48d",
        "HALLOWED" to
            "ff8a6a12,fff1e9d2,ffefe1b6,ff4a3708,ffd4af37,ff26386f," +
            "fff1e9d2,ff1b2a5b,fff1e9d2,ff4a4335,fff1e9d2,ffe4d9bc," +
            "ff23201a,ff1b2a5b,fff1e9d2,fff1e9d2,ff23201a,ffe4d9bc," +
            "ff4a4335,fff1e9d2,ff1b2a5b,fff1e9d2,ff8a1c1c,fff1e9d2," +
            "ffebd3c6,ff3a0c0c,ffb9a87c,ffdccfa9,ff0c1330,fffbf6e9," +
            "ffdfd3b2,fffbf6e9,fff7f1e0,fff1e9d2,ffe9e0c6,ffe4d9bc",
        "BAROQUE" to
            "ff7e1414,ffefe6d8,ffe9d8c6,ff3a0808,ffe0a79e,ff5d0000," +
            "ffefe6d8,ff3b2415,ffefe6d8,ff6e5320,ffefe6d8,ffede0c6," +
            "ff3a2a08,ff3b2415,ffefe6d8,ffefe6d8,ff2a1b10,ffe2d6c4," +
            "ff5a4635,ffefe6d8,ff3b2415,ffefe6d8,ff8c2e1e,ffefe6d8," +
            "ffebd2c6,ff3a1006,ffb9a288,ffd6c6ae,ff1a0f08,fffbf6ee," +
            "ffddd0be,fffbf6ee,fff7f1e8,ffefe6d8,ffe8dccb,ffe2d6c4"
        )
    }

    /**
     * Every pair in the High contrast scheme that the application actually draws text with.
     *
     * Enumerated rather than derived, and each entry names where it is drawn, because Material
     * declares thirty-odd roles and only some of them are ever a word on a ground in this
     * application. Two omissions are deliberate and worth stating:
     *
     *  - `outline` carries the *disabled* label of a button or a chip (see `Controls.kt`), and
     *    WCAG exempts a disabled control from 1.4.6 for the good reason that a disabled control
     *    that shouted would be a lie about what the reader can do.
     *  - `primary` over `primary` is not a thing anyone draws; `onPrimary` is measured against it
     *    all the same, since Material's contract says that is what it is for.
     */
    private fun highContrastPairs(s: ColorScheme): List<Triple<String, Color, Color>> = listOf(
        Triple("body text on the window", s.onBackground, s.background),
        Triple("body text on a panel", s.onSurface, s.surface),
        Triple("secondary text on a sunk panel", s.onSurfaceVariant, s.surfaceVariant),
        Triple("the menu strip", s.onSurface, s.surfaceContainerHigh),
        Triple("an open menu", s.onSurface, s.surfaceContainerHighest),
        Triple("a card", s.onSurface, s.surfaceContainer),
        Triple("the accent as a word, on a panel", s.primary, s.surface),
        Triple("the armed button's label", s.primary, s.primaryContainer),
        Triple("a label on the accent", s.onPrimary, s.primary),
        Triple("a chosen chip", s.onSecondaryContainer, s.secondaryContainer),
        Triple("a filled accent block", s.onPrimaryContainer, s.primaryContainer),
        Triple("a tertiary block", s.onTertiaryContainer, s.tertiaryContainer),
        Triple("an error, on a panel", s.error, s.surface),
        Triple("a label on an error", s.onError, s.error),
        Triple("an error block", s.onErrorContainer, s.errorContainer),
        Triple("an inverted strip", s.inverseOnSurface, s.inverseSurface)
    )

    @Test
    fun `every text pair in the high contrast chrome clears WCAG AAA`() {
        val scheme = ThemeChoice.HIGH_CONTRAST.scheme(systemDark = true)
        var worst = Double.MAX_VALUE
        var worstWhere = ""
        highContrastPairs(scheme).forEach { (where, ink, ground) ->
            val ratio = ColorVision.contrast(ink.toArgb(), ground.toArgb())
            if (ratio < worst) {
                worst = ratio
                worstWhere = where
            }
            assertTrue(
                ratio >= AAA,
                "High contrast: $where measures ${ratio.rounded()}:1, short of AAA's $AAA:1"
            )
        }
        println(
            "CHROME high contrast: ${highContrastPairs(scheme).size} text pairs, " +
                "worst ${worst.rounded()}:1 ($worstWhere), bar $AAA:1"
        )
    }

    /**
     * The saturated blue, where it is a mark rather than a word.
     *
     * This is the other half of the decision the scheme's own note explains: #1A6EFF is the accent
     * this chrome is specified with, it cannot reach 7:1 against anything, and so it is the
     * border, the rail and the bead — never a word. The test asserts both halves, because either one alone could be
     * satisfied by quietly abandoning the other.
     */
    @Test
    fun `the high contrast accent is the specified blue, and only ever a mark`() {
        val detail = ThemeChoice.HIGH_CONTRAST.detail()
        val scheme = ThemeChoice.HIGH_CONTRAST.scheme(systemDark = true)
        val mark = detail.mark(scheme)
        assertEquals(
            Color(0xFF1A6EFF),
            mark,
            "the mark accent is no longer the blue this chrome is specified with"
        )

        val onGround = ColorVision.contrast(mark.toArgb(), scheme.background.toArgb())
        assertTrue(
            onGround >= NON_TEXT,
            "the mark measures ${onGround.rounded()}:1 on the ground, short of 1.4.11's $NON_TEXT:1"
        )
        assertTrue(
            onGround < AAA,
            "the mark now clears AAA, so the two-form accent is no longer needed — collapse it"
        )
        println(
            "CHROME high contrast: mark #1A6EFF at ${onGround.rounded()}:1 (1.4.11 needs " +
                "$NON_TEXT:1), word ${ColorVision.contrast(
                    scheme.primary.toArgb(), scheme.background.toArgb()
                ).rounded()}:1 (1.4.6 needs $AAA:1)"
        )
    }

    /**
     * The Colorblind chrome's four accents, under both red-green deficiencies.
     *
     * Every pair, not only the pairs that happen to appear side by side, because which two states
     * a reader is comparing is the reader's business: the current item beside the focused one, the
     * armed button beside the failed one.
     */
    @Test
    fun `the colorblind chrome's accents survive deuteranopia and protanopia`() {
        val scheme = ThemeChoice.COLORBLIND.scheme(systemDark = true)
        val accents = listOf(
            "current" to scheme.primary,
            "focus" to scheme.secondary,
            "done" to scheme.tertiary,
            "failed" to scheme.error
        )
        val simulations = listOf<Pair<String, ColorVision.Deficiency?>>(
            "normal vision" to null,
            "deuteranopia" to DEUTERANOPIA,
            "protanopia" to PROTANOPIA
        )

        var worst = Double.MAX_VALUE
        var worstWhere = ""
        simulations.forEach { (name, deficiency) ->
            for (i in accents.indices) for (j in i + 1 until accents.size) {
                val a = accents[i].second.toArgb()
                val b = accents[j].second.toArgb()
                val difference =
                    if (deficiency == null) ColorVision.deltaE2000(a, b)
                    else ColorVision.deltaE2000(a, b, deficiency)
                if (difference < worst) {
                    worst = difference
                    worstWhere = "${accents[i].first}/${accents[j].first} under $name"
                }
                assertTrue(
                    difference >= ACCENT_MARGIN,
                    "Colorblind: ${accents[i].first} and ${accents[j].first} are only " +
                        "${difference.rounded()} apart under $name, short of $ACCENT_MARGIN"
                )
            }
        }
        println(
            "CHROME colorblind: 4 accents, 18 comparisons, worst ${worst.rounded()} CIEDE2000 " +
                "($worstWhere), bar $ACCENT_MARGIN"
        )
    }

    /** And that each accent still separates from the two grounds it is drawn on. */
    @Test
    fun `the colorblind chrome's accents survive their own grounds`() {
        val scheme = ThemeChoice.COLORBLIND.scheme(systemDark = true)
        val accents = listOf(scheme.primary, scheme.secondary, scheme.tertiary, scheme.error)
        val grounds = listOf(scheme.background, scheme.surface, scheme.surfaceVariant)
        var worst = Double.MAX_VALUE
        listOf(DEUTERANOPIA, PROTANOPIA).forEach { deficiency ->
            accents.forEach { accent ->
                grounds.forEach { ground ->
                    val difference =
                        ColorVision.deltaE2000(accent.toArgb(), ground.toArgb(), deficiency)
                    worst = minOf(worst, difference)
                    assertTrue(
                        difference >= ACCENT_MARGIN,
                        "Colorblind: an accent is only ${difference.rounded()} from its ground " +
                            "under $deficiency"
                    )
                }
            }
        }
        println("CHROME colorblind: worst accent-to-ground ${worst.rounded()} CIEDE2000")
    }

    /**
     * That the Colorblind chrome never leaves a state to hue alone.
     *
     * The colours above are only half the promise; the other half is that the armed button is
     * underlined, the chosen chip is ruled twice as heavily and the unavailable one is struck
     * through. Those are drawn from [ChromeDetail.shapeCues], and this is the only assertion that
     * can be made about them without reading pixels — the screenshots are the rest.
     */
    @Test
    fun `only the colorblind chrome asks for shape cues`() {
        ThemeChoice.entries.forEach { choice ->
            assertEquals(
                choice == ThemeChoice.COLORBLIND,
                choice.detail().shapeCues,
                "${choice.label} disagrees about shape cues"
            )
        }
    }

    /**
     * That the six unornamented chromes carry no ornament.
     *
     * Every one of them takes the default [ChromeDetail], which is the whole of the reason their
     * screenshots stay pixel-identical as ornaments are added elsewhere: a hairline rule, one
     * accent, no cues, no box.
     */
    @Test
    fun `the unornamented chromes carry no ornament`() {
        val untouched = listOf(
            ThemeChoice.SYSTEM, ThemeChoice.LIGHT, ThemeChoice.DARK,
            ThemeChoice.NAUTICAL, ThemeChoice.MIDNIGHT, ThemeChoice.MARS
        )
        untouched.forEach { choice ->
            val detail = choice.detail()
            assertEquals(SectionRuleStyle.PLAIN, detail.sectionRule, "${choice.label} rules")
            assertEquals(null, detail.markAccent, "${choice.label} mark")
            assertEquals(null, detail.overMapStrip, "${choice.label} strip")
            assertEquals(HeadingCase.SENTENCE, detail.headings, "${choice.label} capitals")
            assertEquals(CartoucheStyle.PLAIN, detail.cartouche, "${choice.label} cartouche")
        }
    }

    // ---------------------------------------------------------------------------------------
    // The four typographic chromes: Matrix, Hessian, Roman, Hitchcock.
    // ---------------------------------------------------------------------------------------

    /**
     * Every pair in a scheme that the application actually draws text with.
     *
     * The High contrast list above, generalised, plus the two roles the typographic chromes need:
     * the armed button's label comes from [ChromeDetail.label] rather than always from `primary`,
     * because three of these chromes make that button a block instead of a stain, and measuring it
     * as `primary` on `primaryContainer` would measure a pair nobody draws.
     *
     * [ground] is what turns a panel colour into the colour a word is really read against. It is the
     * identity for three of the four; for Hessian it composites the weave in.
     */
    private fun textPairs(
        scheme: ColorScheme,
        detail: ChromeDetail,
        ground: (Color) -> Color = { it }
    ): List<Triple<String, Color, Color>> = with(scheme) {
        listOf(
            Triple("body text on the window", onBackground, ground(background)),
            Triple("body text on a panel", onSurface, ground(surface)),
            Triple("secondary text on a sunk panel", onSurfaceVariant, ground(surfaceVariant)),
            Triple("secondary text on a panel", onSurfaceVariant, ground(surface)),
            Triple("the menu strip", onSurface, ground(surfaceContainerHigh)),
            Triple("a menu heading", onSurfaceVariant, ground(surfaceContainerHighest)),
            Triple("an open menu", onSurface, ground(surfaceContainerHighest)),
            Triple("a card", onSurface, ground(surfaceContainer)),
            Triple("the accent as a word, on a panel", primary, ground(surface)),
            Triple("the secondary as a word, on a panel", secondary, ground(surface)),
            Triple("the armed button's label", detail.label(scheme), primaryContainer),
            Triple("a label on the accent", onPrimary, primary),
            Triple("a chosen chip", onSecondaryContainer, secondaryContainer),
            Triple("a filled accent block", onPrimaryContainer, primaryContainer),
            Triple("a tertiary block", onTertiaryContainer, tertiaryContainer),
            Triple("an error, on a panel", error, ground(surface)),
            Triple("a label on an error", onError, error),
            Triple("an error block", onErrorContainer, errorContainer),
            Triple("an inverted strip", inverseOnSurface, inverseSurface)
        )
    }

    /** Asserts every pair at AA and prints the worst, the way the two accessibility guards do. */
    private fun assertAA(
        choice: ThemeChoice,
        ground: (Color) -> Color = { it },
        note: String = "",
        omit: Set<String> = emptySet()
    ) {
        val scheme = choice.scheme(systemDark = true)
        val pairs = textPairs(scheme, choice.detail(), ground).filterNot { it.first in omit }
        var worst = Double.MAX_VALUE
        var worstWhere = ""
        pairs.forEach { (where, ink, background) ->
            val ratio = ColorVision.contrast(ink.toArgb(), background.toArgb())
            if (ratio < worst) {
                worst = ratio
                worstWhere = where
            }
            assertTrue(
                ratio >= AA,
                "${choice.label}$note: $where measures ${ratio.rounded()}:1, short of AA's $AA:1"
            )
        }
        println(
            "CHROME ${choice.label.lowercase()}$note: ${pairs.size} text pairs, " +
                "worst ${worst.rounded()}:1 ($worstWhere), bar $AA:1"
        )
    }

    // ---------------------------------------------------------------------------------------
    // The two standard chromes, which F29 cooled.
    // ---------------------------------------------------------------------------------------

    /**
     * The one role the dark chrome declares and never letters.
     *
     * Material's `ColorScheme` has thirty-six roles and this application paints words with about a
     * dozen; [highContrastPairs] already says so and leaves two out for the same reason. This one
     * is left out of the dark chrome's measurement, and only the dark chrome's: `secondary` is
     * brass-dim after dark, and brass-dim is a **rule** colour by decision, not an ink.
     * `SitePaletteContrastTest` guards the same colour on the website the opposite way round — it
     * fails if a rule on the page ever letters in it — precisely because it measures under AA on
     * every ground the palette has. On a dark panel it is 3.76:1, and nothing in `:ui` reads
     * `colorScheme.secondary`.
     *
     * `error` was left out beside it as a fill nothing letters in. The seed field letters its label
     * in `colorScheme.error` when what is typed is not a seed (Material's outlined field does, and
     * `SeedField` sets `isError`), so it is measured like every other ink.
     */
    private val neverLettered = setOf(
        "the secondary as a word, on a panel"
    )

    /**
     * Light at WCAG AA, which is what F29 had to leave standing.
     *
     * F29 replaced the warm brown-black of the dark grounds with a neutral charcoal and Vellum's
     * yellow paper with an atlas plate's off-white, and moved no ink and no accent. A palette
     * change that improved the look and quietly cost a pair its legibility would be a bad trade, so
     * the pairs are measured — and they came out better rather than worse in daylight, where the
     * weakest of them, muted ink on a sunk panel, went from 4.60:1 to 5.13:1.
     *
     * The light chrome's selection wash and its two hairline weights were re-derived a second time
     * after the first captures were looked at, and both moves raised a pair rather than lowering
     * one: the armed button's label reads 6.67:1 on the wash, against 6.47 on the first derivation
     * and 5.92 before F29. `Theme.kt`'s `SepiaWash` and `Rule` say why they moved. Dark is measured
     * with every other chrome, below.
     */
    @Test
    fun `every text pair in the light chrome clears WCAG AA`() {
        assertAA(ThemeChoice.LIGHT)
    }

    /**
     * Every chrome at WCAG AA, not only those that promised it in so many words.
     *
     * A chrome is a room somebody works in for an hour, and the README says every text pair in
     * every theme is measured; until this clause, seven of the seventeen were not. Each is held to
     * AA over the same pairs [textPairs] lists, Hessian's with its weave under the words, and System
     * in both of the hosts it follows. Four fail today on pairs the audit computed (Audit III, G-I5)
     * — Dark's error label on the seed field, Mars's and Hallowed's armed button, and the accent as
     * a word in Allied and Hallowed — and each is kept running as a known failure recorded by the
     * pairs under the bar and what they measure, so the chrome that is mended arms its clause and a
     * chrome that loses another pair fails.
     */
    @Test
    fun `every text pair in every chrome clears WCAG AA`() {
        val measured = ArrayList<String>()
        for (choice in ThemeChoice.entries) {
            val hosts = if (choice == ThemeChoice.SYSTEM) listOf(true, false) else listOf(true)
            for (systemDark in hosts) {
                val ground: (Color) -> Color = if (choice == ThemeChoice.HESSIAN) ::woven else { colour -> colour }
                val omit = if (choice == ThemeChoice.DARK || (choice == ThemeChoice.SYSTEM && systemDark)) neverLettered else emptySet()
                val short = pairsUnderAA(choice, systemDark, ground, omit)
                val name = choice.label + if (choice == ThemeChoice.SYSTEM) (if (systemDark) " (dark host)" else " (light host)") else ""
                measured.add("$name ${short.size}")
                val signature = short.joinToString("; ") { (where, ratio) -> "$where ${ratio.rounded()}" }
                val known = CHROMES_UNDER_AA[name]
                if (known == null) {
                    assertTrue(short.isEmpty(), "$name: $signature, short of AA's $AA:1")
                } else {
                    KnownFailures.expect(known.first, known.second) {
                        if (short.isNotEmpty()) throw RecordedViolation("$name: $signature, short of AA's $AA:1", signature)
                    }
                }
            }
        }
        println("CHROME every chrome at AA, pairs short of it: ${measured.joinToString()}")
    }

    /** The pairs of [choice]'s scheme under AA, each with what it measures. */
    private fun pairsUnderAA(
        choice: ThemeChoice,
        systemDark: Boolean,
        ground: (Color) -> Color,
        omit: Set<String>
    ): List<Pair<String, Double>> {
        val scheme = choice.scheme(systemDark = systemDark)
        return textPairs(scheme, choice.detail(), ground).filterNot { it.first in omit }
            .map { (where, ink, background) -> where to ColorVision.contrast(ink.toArgb(), background.toArgb()) }
            .filter { it.second < AA }
    }

    @Test
    fun `every text pair in the Matrix chrome clears WCAG AA`() {
        assertAA(ThemeChoice.MATRIX)
    }

    @Test
    fun `every text pair in the Roman chrome clears WCAG AA`() {
        assertAA(ThemeChoice.ROMAN)
    }

    @Test
    fun `every text pair in the Hitchcock chrome clears WCAG AA`() {
        assertAA(ThemeChoice.HITCHCOCK)
    }

    /**
     * Hessian, with the weave under the words.
     *
     * Twice, and the second is the one that counts. The spec asks for the crosshatch to be
     * composited onto the ground at its own opacity before anything is measured, because it is
     * drawn *behind the text* and it darkens what the text is read against — the whole reason the
     * burlap is one shade lighter than the hex the spec names.
     */
    @Test
    fun `every text pair in the Hessian chrome clears WCAG AA with the weave composited in`() {
        assertAA(ThemeChoice.HESSIAN, note = " (bare cloth)")
        assertAA(ThemeChoice.HESSIAN, ground = ::woven, note = " (woven)")

        // And that the chrome really does ask for the weave this measured, rather than the test
        // having quietly measured an effect nobody draws.
        val detail = ThemeChoice.HESSIAN.detail()
        assertEquals(PanelTexture.CROSSHATCH, detail.panelTexture, "Hessian no longer weaves")
        // Within half a byte, because an alpha survives a `Color` as one of 256 values and 8% is
        // not one of them: 20/255 is 0.0784 and 21/255 is 0.0824.
        assertTrue(
            kotlin.math.abs(detail.textureInk.alpha - WEAVE) < 1f / 512f,
            "the weave is drawn at ${detail.textureInk.alpha} but measured at $WEAVE"
        )
    }

    /** The weave's ink laid over a ground at its own alpha, which is what a glyph sits on. */
    private fun woven(ground: Color): Color {
        val ink = ThemeChoice.HESSIAN.detail().textureInk
        val a = ink.alpha
        return Color(
            red = ink.red * a + ground.red * (1f - a),
            green = ink.green * a + ground.green * (1f - a),
            blue = ink.blue * a + ground.blue * (1f - a)
        )
    }

    /**
     * Hitchcock's vermilion, where it is a mark rather than a word.
     *
     * The third instance of the decision High contrast makes, and the spec predicted it:
     * "vermilion on black is about 5.5:1 as text, so check it". Checked, it is **4.38:1** on the
     * flat-black panel — the panel, not the charcoal ground, being the worse of the two and the one
     * a word is actually read on. So #E8491D is the block, the border, the rail and the bead, and
     * the accent that is a word is the same hue lifted. Both halves are asserted, because either
     * alone could be satisfied by quietly abandoning the other.
     */
    @Test
    fun `the Hitchcock accent is the Vertigo vermilion, and only ever a mark`() {
        val detail = ThemeChoice.HITCHCOCK.detail()
        val scheme = ThemeChoice.HITCHCOCK.scheme(systemDark = true)
        val mark = detail.mark(scheme)
        assertEquals(Color(0xFFE8491D), mark, "the mark accent is no longer Vertigo's vermilion")

        val onPanel = ColorVision.contrast(mark.toArgb(), scheme.surface.toArgb())
        assertTrue(
            onPanel >= NON_TEXT,
            "the mark measures ${onPanel.rounded()}:1 on the panel, short of 1.4.11's $NON_TEXT:1"
        )
        assertTrue(
            onPanel < AA,
            "the vermilion now clears AA as a word, so the two-form accent is no longer needed"
        )
        println(
            "CHROME hitchcock: mark #E8491D at ${onPanel.rounded()}:1 on the panel (1.4.11 needs " +
                "$NON_TEXT:1), word ${ColorVision.contrast(
                    scheme.primary.toArgb(), scheme.surface.toArgb()
                ).rounded()}:1 (1.4.6 AA needs $AA:1)"
        )
    }

    /**
     * Roman's bronze, the same way, and Hessian's twine.
     *
     * Both are the Hallowed decision rather than the High contrast one: a metal and a fibre that a
     * reader would recognise instantly and could not read a sentence in. The named hex stays the
     * mark; the word takes the same colour moved.
     */
    @Test
    fun `the Roman bronze and the Hessian twine are marks, and their words are darker`() {
        val roman = ThemeChoice.ROMAN.scheme(systemDark = false)
        val bronze = ThemeChoice.ROMAN.detail().mark(roman)
        assertEquals(
            Color(0xFF9C7A3C),
            bronze,
            "the Roman mark is no longer the bronze the chrome is specified with"
        )
        val bronzeWord = ColorVision.contrast(roman.primary.toArgb(), roman.surface.toArgb())
        val bronzeMark = ColorVision.contrast(bronze.toArgb(), roman.surface.toArgb())
        assertTrue(bronzeMark >= NON_TEXT, "the bronze is ${bronzeMark.rounded()}:1 on marble")
        assertTrue(bronzeMark < AA, "the bronze now reads as a word; collapse the two forms")
        assertTrue(bronzeWord >= AA, "the darkened bronze is ${bronzeWord.rounded()}:1")

        val hessian = ThemeChoice.HESSIAN.scheme(systemDark = false)
        val twine = ColorVision.contrast(
            hessian.secondaryContainer.toArgb(),
            woven(hessian.surface).toArgb()
        )
        println(
            "CHROME roman: bronze #9C7A3C at ${bronzeMark.rounded()}:1 on marble, the same metal " +
                "darkened at ${bronzeWord.rounded()}:1; hessian twine block ${twine.rounded()}:1 " +
                "against woven linen"
        )
    }

    // ---------------------------------------------------------------------------------------
    // F24's one.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `every text pair in the Lemon Blueberry chrome clears WCAG AA`() {
        assertAA(ThemeChoice.LEMON_BLUEBERRY)
    }

    /**
     * The decision F24 was asked to make by measuring, re-measured here rather than remembered.
     *
     * The chunk left which of the two colours is the ground open, on the condition that the
     * arrangement clearing WCAG AA with the higher-contrast Stop button won. Both were built to the
     * same rules and both cleared AA, so the Stop button decided it — and a figure that only ever
     * lived in a report is a figure nobody can check a year from now. So the arrangement that lost
     * is written down as the four colours that decided it and measured again on every run: the
     * lemon-ground chrome's accent, the wash its armed button would have been stained with, and the
     * two the same button ships in.
     *
     * The 7.38 the losing arrangement's worst pair measured is not re-derived here — that would
     * mean carrying a second whole scheme in a test file — but the shipped chrome's own worst is
     * printed by [assertAA] above, and it is 7.76.
     */
    @Test
    fun `Lemon Blueberry puts the blueberry underneath, which is the arrangement that measured`() {
        val scheme = ThemeChoice.LEMON_BLUEBERRY.scheme(systemDark = false)
        val detail = ThemeChoice.LEMON_BLUEBERRY.detail()

        // The blueberry is the room and the lemon is the writing, not the other way round.
        assertTrue(
            ColorVision.luminance(scheme.onSurface.toArgb()) >
                ColorVision.luminance(scheme.surface.toArgb()),
            "the lemon is no longer the ink: the chrome has been turned over"
        )
        assertEquals(
            scheme.background,
            detail.ground(scheme),
            "the window is no longer the darkest of the fruit"
        )

        // The armed button, which is Stop while a world is being built. Neither chrome inverts it,
        // so in both it is the accent read against a wash of the ground.
        val shipped = ColorVision.contrast(
            detail.label(scheme).toArgb(),
            scheme.primaryContainer.toArgb()
        )
        // The arrangement that lost: a lemon ground (#FAF2D2 panels) wants an accent dark enough to
        // be read against a near-white yellow (#3B2F78), and the deepest stain it can put under a
        // button without the button becoming a block is #E4D7A2.
        val rejected = ColorVision.contrast(0xFF3B2F78.toInt(), 0xFFE4D7A2.toInt())

        println(
            "CHROME lemon blueberry: Stop ${shipped.rounded()}:1 with the blueberry underneath, " +
                "against ${rejected.rounded()}:1 with the lemon underneath"
        )
        assertTrue(
            shipped > rejected,
            "the arrangement shipped has the worse Stop button: ${shipped.rounded()}:1 against " +
                "${rejected.rounded()}:1, so F24 chose the wrong way round"
        )
        assertTrue(shipped >= AA, "Stop measures ${shipped.rounded()}:1, short of AA")
    }

    /**
     * That the alarm is a third colour and not a shade of either.
     *
     * A chrome built from two colours has nowhere obvious to put "something has gone wrong", and
     * the tempting answer — a deeper blueberry, or a hotter lemon — is one a reader cannot tell
     * from the accent at a glance. Lemon Blueberry's answer is chemical: blueberry pigment is an
     * anthocyanin, so acid turns it pink, and this asserts that the resulting colour really is far
     * enough from both to carry the meaning on its own. 20 CIEDE2000 is the margin `ClearStyleTest`
     * holds the colour-blind map style's realm fills to, and this is the same question.
     */
    @Test
    fun `the Lemon Blueberry alarm is neither the lemon nor the blueberry`() {
        val scheme = ThemeChoice.LEMON_BLUEBERRY.scheme(systemDark = false)
        val fromAccent = ColorVision.deltaE2000(scheme.error.toArgb(), scheme.primary.toArgb())
        val fromGround = ColorVision.deltaE2000(scheme.error.toArgb(), scheme.surface.toArgb())
        val fromInk = ColorVision.deltaE2000(scheme.error.toArgb(), scheme.onSurface.toArgb())
        println(
            "CHROME lemon blueberry alarm: dE2000 ${fromAccent.rounded()} from the accent, " +
                "${fromGround.rounded()} from the panel, ${fromInk.rounded()} from the ink"
        )
        assertTrue(fromAccent >= ALARM_MARGIN, "the alarm is ${fromAccent.rounded()} from the zest")
        assertTrue(fromGround >= ALARM_MARGIN, "the alarm is ${fromGround.rounded()} from a panel")
        assertTrue(fromInk >= ALARM_MARGIN, "the alarm is ${fromInk.rounded()} from the ink")
    }

    // ---------------------------------------------------------------------------------------
    // F28's one.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `every text pair in the Blacklight chrome clears WCAG AA`() {
        assertAA(ThemeChoice.BLACKLIGHT)
    }

    /**
     * The decision F28 was asked to make by measuring, re-measured here rather than remembered.
     *
     * F24's guard, for F24's reason: which of two named colours is the room was left to the
     * measurement, and a figure that only ever lived in a report is a figure nobody can check a
     * year from now. Both arrangements were built to one set of rules and both cleared AA, so the
     * armed Stop button decided it, and the arrangement that lost is written down here as the two
     * colours that decided it and measured again on every run.
     *
     * The margin is not close. On a lime ground — #E6FF42 has a relative luminance of 0.887,
     * paler than most papers — everything that has to be seen against it is forced down into the
     * dark end together, and the stain under an armed button can only travel so far before the
     * button stops being a stain and becomes a block. With the violet underneath, the accent is
     * the lime itself and the wash beneath it is the darkest thing on the panel.
     */
    @Test
    fun `Blacklight puts the violet underneath, which is the arrangement that measured`() {
        val scheme = ThemeChoice.BLACKLIGHT.scheme(systemDark = false)
        val detail = ThemeChoice.BLACKLIGHT.detail()

        // The violet is the room and the lime is the writing, not the other way round.
        assertTrue(
            ColorVision.luminance(scheme.onSurface.toArgb()) >
                ColorVision.luminance(scheme.surface.toArgb()),
            "the lime is no longer the ink: the chrome has been turned over"
        )
        // And the panel is the violet the author named, not a tone derived from it.
        assertEquals(
            Color(0xFF520C94),
            scheme.surface,
            "the panel is no longer the violet the chunk was given"
        )
        assertEquals(
            Color(0xFFE6FF42),
            scheme.primary,
            "the accent is no longer the lime the chunk was given"
        )
        assertEquals(
            scheme.background,
            detail.ground(scheme),
            "the window is no longer the room past the lamp's reach"
        )

        // The armed button, which is Stop while a world is being built. Neither arrangement
        // inverts it, so in both it is the accent read against a wash of the window ground.
        val shipped = ColorVision.contrast(
            detail.label(scheme).toArgb(),
            scheme.primaryContainer.toArgb()
        )
        // The arrangement that lost: a lime room takes the violet at full strength for its accent
        // (#520C94), and its armed button is the same stain — the panel taken ten L* down, which
        // on a lime panel is #CCDF4B.
        val rejected = ColorVision.contrast(0xFF520C94.toInt(), 0xFFCCDF4B.toInt())

        println(
            "CHROME blacklight: Stop ${shipped.rounded()}:1 with the violet underneath, " +
                "against ${rejected.rounded()}:1 with the lime underneath"
        )
        assertTrue(
            shipped > rejected,
            "the arrangement shipped has the worse Stop button: ${shipped.rounded()}:1 against " +
                "${rejected.rounded()}:1, so F28 chose the wrong way round"
        )
        assertTrue(shipped >= AA, "Stop measures ${shipped.rounded()}:1, short of AA")
    }

    /**
     * That the alarm is a third colour and not a shade of either.
     *
     * The same question F24's alarm answers with chemistry, answered here with optics. A blacklight
     * is an excitation rather than a colour: the ultraviolet goes in and the dye decides what comes
     * out, which is why one highlighter glows lime and the orange one beside it in the box glows
     * orange. So the alarm is arrived at the way the accent was, and this asserts that the result
     * really is far enough from the lime, from the violet it is read on and from the pale lime ink
     * beside it to carry "something has gone wrong" on its own.
     */
    @Test
    fun `the Blacklight alarm is neither the lime nor the violet`() {
        val scheme = ThemeChoice.BLACKLIGHT.scheme(systemDark = false)
        val fromAccent = ColorVision.deltaE2000(scheme.error.toArgb(), scheme.primary.toArgb())
        val fromGround = ColorVision.deltaE2000(scheme.error.toArgb(), scheme.surface.toArgb())
        val fromInk = ColorVision.deltaE2000(scheme.error.toArgb(), scheme.onSurface.toArgb())
        println(
            "CHROME blacklight alarm: dE2000 ${fromAccent.rounded()} from the lime, " +
                "${fromGround.rounded()} from the violet panel, ${fromInk.rounded()} from the ink"
        )
        assertTrue(fromAccent >= ALARM_MARGIN, "the alarm is ${fromAccent.rounded()} from the lime")
        assertTrue(
            fromGround >= ALARM_MARGIN,
            "the alarm is ${fromGround.rounded()} from the violet panel"
        )
        assertTrue(fromInk >= ALARM_MARGIN, "the alarm is ${fromInk.rounded()} from the ink")
    }

    /**
     * That the chrome really is built out of two hues and one stated exception.
     *
     * The claim the palette's own note makes and the one a screenshot cannot check: every violet in
     * the scheme is #520C94's hue and every lime is #E6FF42's, moved only in lightness and chroma.
     * A tone that drifted — a panel warmed toward blue, a rule cooled toward magenta — would
     * still pass every contrast assertion above and would still look like a violet room, and
     * would have quietly made this a chrome of four colours.
     *
     * Every role the application reads is checked rather than a chosen handful, because the ones a
     * chosen handful would leave out are exactly the ones nobody looks at: the container tones a
     * menu and a card are drawn on. The three that carry the alarm are the exception the note
     * declares, and they are named here so that adding a fourth off-hue role is a decision
     * somebody has to make in this file.
     */
    @Test
    fun `every tone in the Blacklight chrome is one of its two hues`() {
        val scheme = ThemeChoice.BLACKLIGHT.scheme(systemDark = false)
        val alarm = setOf("error", "errorContainer", "onErrorContainer")
        var worst = 0.0
        var worstWhere = ""
        var counted = 0
        namedRoles(scheme).forEach { (role, colour) ->
            if (role in alarm) return@forEach
            counted++
            val hue = hueOf(colour)
            val drift = minOf(hueDistance(hue, VIOLET_HUE), hueDistance(hue, LIME_HUE))
            if (drift > worst) {
                worst = drift
                worstWhere = role
            }
            assertTrue(
                drift <= HUE_DRIFT,
                "Blacklight: $role is ${drift.rounded()} degrees off both of the chrome's hues, " +
                    "past $HUE_DRIFT"
            )
        }
        println(
            "CHROME blacklight: $counted roles on $VIOLET_HUE or $LIME_HUE degrees, worst drift " +
                "${worst.rounded()} ($worstWhere), bar $HUE_DRIFT"
        )
    }

    /** The same roles [roles] writes, each with the name the scheme calls it by. */
    private fun namedRoles(s: ColorScheme): List<Pair<String, Color>> = listOf(
        "primary" to s.primary, "onPrimary" to s.onPrimary,
        "primaryContainer" to s.primaryContainer, "onPrimaryContainer" to s.onPrimaryContainer,
        "inversePrimary" to s.inversePrimary,
        "secondary" to s.secondary, "onSecondary" to s.onSecondary,
        "secondaryContainer" to s.secondaryContainer,
        "onSecondaryContainer" to s.onSecondaryContainer,
        "tertiary" to s.tertiary, "onTertiary" to s.onTertiary,
        "tertiaryContainer" to s.tertiaryContainer,
        "onTertiaryContainer" to s.onTertiaryContainer,
        "background" to s.background, "onBackground" to s.onBackground,
        "surface" to s.surface, "onSurface" to s.onSurface,
        "surfaceVariant" to s.surfaceVariant, "onSurfaceVariant" to s.onSurfaceVariant,
        "surfaceTint" to s.surfaceTint,
        "inverseSurface" to s.inverseSurface, "inverseOnSurface" to s.inverseOnSurface,
        "error" to s.error, "onError" to s.onError,
        "errorContainer" to s.errorContainer, "onErrorContainer" to s.onErrorContainer,
        "outline" to s.outline, "outlineVariant" to s.outlineVariant, "scrim" to s.scrim,
        "surfaceBright" to s.surfaceBright, "surfaceDim" to s.surfaceDim,
        "surfaceContainerLowest" to s.surfaceContainerLowest,
        "surfaceContainerLow" to s.surfaceContainerLow,
        "surfaceContainer" to s.surfaceContainer,
        "surfaceContainerHigh" to s.surfaceContainerHigh,
        "surfaceContainerHighest" to s.surfaceContainerHighest
    )

    /**
     * The hue angle of a colour in CIE L*a*b* under D65, in degrees.
     *
     * `ColorVision` exposes the *difference* between two colours and their luminance, but not a
     * colour's own hue, since nothing before this chrome had a use for one. The transform is the
     * standard's and is the same one that function is built on, written out here rather than added
     * to the library for a single guard.
     */
    private fun hueOf(colour: Color): Double {
        val argb = colour.toArgb()
        val red = linearise((argb shr 16) and 0xFF)
        val green = linearise((argb shr 8) and 0xFF)
        val blue = linearise(argb and 0xFF)
        val x = (0.4124564 * red + 0.3575761 * green + 0.1804375 * blue) / 0.95047
        val y = 0.2126729 * red + 0.7151522 * green + 0.0721750 * blue
        val z = (0.0193339 * red + 0.1191920 * green + 0.9503041 * blue) / 1.08883
        val aStar = 500.0 * (labCurve(x) - labCurve(y))
        val bStar = 200.0 * (labCurve(y) - labCurve(z))
        val degrees = atan2(bStar, aStar) * 180.0 / PI
        return if (degrees < 0.0) degrees + 360.0 else degrees
    }

    /** The sRGB transfer function, decoded. */
    private fun linearise(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    /** L*a*b*'s cube root, with the linear segment near black that keeps it finite. */
    private fun labCurve(t: Double): Double {
        val kappa = 6.0 / 29.0
        return if (t > kappa * kappa * kappa) t.pow(1.0 / 3.0)
        else t / (3.0 * kappa * kappa) + 4.0 / 29.0
    }

    /** The shorter way round the wheel between two hue angles. */
    private fun hueDistance(first: Double, second: Double): Double {
        val raw = abs(first - second) % 360.0
        return if (raw > 180.0) 360.0 - raw else raw
    }

    /**
     * That the four typographic chromes are the only ones carrying typographic ornament.
     *
     * The counterpart of the guard above, and the other half of the byte-identity claim: a cut
     * bar, a meander, a running stitch, a prompt, an interpunct, a weave and a button label are
     * each asked for by exactly the chromes that asked for them, and by nobody else.
     */
    @Test
    fun `only the typographic chromes carry typographic ornament`() {
        val typographic = setOf(
            ThemeChoice.MATRIX, ThemeChoice.HESSIAN, ThemeChoice.ROMAN, ThemeChoice.HITCHCOCK
        )
        ThemeChoice.entries.forEach { choice ->
            val detail = choice.detail()
            val mine = choice in typographic
            assertEquals(
                mine,
                detail.headings == HeadingCase.CAPITALS,
                "${choice.label} disagrees about capitals everywhere"
            )
            if (!mine) {
                assertEquals(null, detail.buttonLabel, "${choice.label} paints a button label")
                assertEquals(
                    PanelTexture.NONE,
                    detail.panelTexture,
                    "${choice.label} has grown a texture"
                )
                assertEquals(false, detail.headingInterpunct, "${choice.label} points its headings")
                assertEquals(null, detail.headingPrefix, "${choice.label} prompts its headings")
                assertTrue(
                    detail.sectionRule !in setOf(
                        SectionRuleStyle.STITCHED,
                        SectionRuleStyle.MEANDER,
                        SectionRuleStyle.CUT_BAR
                    ),
                    "${choice.label} has taken one of the typographic rules"
                )
                assertTrue(
                    detail.cartouche !in setOf(
                        CartoucheStyle.STITCHED,
                        CartoucheStyle.DOUBLE_RULE,
                        CartoucheStyle.SPIRAL
                    ),
                    "${choice.label} has taken one of the typographic cartouches"
                )
            }
        }
    }

    /**
     * And that a heading is lettered, never rewritten.
     *
     * The one thing about [ChromeDetail.heading] that could go wrong without showing up in a
     * screenshot: a transform that dropped a word, or that pointed a heading nobody asked to have
     * pointed. Roman's interpunct is the reason this is worth an assertion — the panel's own six
     * headings are all single words, so the transform is only visible in the settings dialog and in
     * the menus, which no capture in this suite opens.
     */
    @Test
    fun `a heading is lettered rather than rewritten`() {
        assertEquals("Generation resolution", ChromeDetail.PLAIN.heading("Generation resolution"))
        assertEquals("TERRAIN", ThemeChoice.ALLIED.detail().heading("Terrain", panel = true))
        // Allied's capitals stop at the panel. That is what leaves it byte-identical.
        assertEquals(
            "Generation resolution",
            ThemeChoice.ALLIED.detail().heading("Generation resolution")
        )
        assertEquals("> TERRAIN", ThemeChoice.MATRIX.detail().heading("Terrain"))
        assertEquals("TERRAIN", ThemeChoice.HESSIAN.detail().heading("Terrain"))
        assertEquals(
            "GENERATION·RESOLUTION",
            ThemeChoice.ROMAN.detail().heading("Generation resolution")
        )
        assertEquals("TERRAIN", ThemeChoice.ROMAN.detail().heading("Terrain"))
        assertEquals("EXPORT", ThemeChoice.HITCHCOCK.detail().heading("Export"))
    }

    /**
     * That no chrome moves a colour except where a decision says it does.
     *
     * The strongest form that claim can be made in without a picture: every Material role of every
     * chrome that existed when [RECORDED_ROLES] was taken, compared against what the enum answers
     * now. Recorded rather than recomputed, because a comparison against something this run also
     * produced would pass however wrong both halves were. Cooling the two standard chromes' grounds
     * is the one change so far that has edited a row here rather than merely passing it, and it
     * edited three; see [RECORDED_ROLES].
     *
     * Thirty-six roles is the whole of a `ColorScheme` this application ever reads, in the order
     * the probe wrote them. `ChromeGalleryTest` makes the other half of the claim, in pixels: the
     * same eleven chromes with a menu open, captured and looked at.
     */
    @Test
    fun `the recorded chromes have not moved a colour`() {
        RECORDED_ROLES.forEach { (name, expected) ->
            val choice = ThemeChoice.entries.first { it.name == name }
            assertEquals(
                expected,
                roles(choice.scheme(systemDark = false)),
                "$name is not the scheme it was recorded as"
            )
        }
        println("CHROME identity: ${RECORDED_ROLES.size} chromes x 36 roles unchanged")
    }

    /** Every role the application reads, in the order the probe wrote them: see [rolesOf]. */
    private fun roles(scheme: ColorScheme): String = rolesOf(scheme)

    private fun Double.rounded(): String {
        val scaled = kotlin.math.round(this * 100.0) / 100.0
        return scaled.toString()
    }
}
