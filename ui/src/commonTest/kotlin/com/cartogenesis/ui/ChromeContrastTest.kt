package com.cartogenesis.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.cartogenesis.cartography.ColorVision
import com.cartogenesis.cartography.ColorVision.Deficiency.DEUTERANOPIA
import com.cartogenesis.cartography.ColorVision.Deficiency.PROTANOPIA
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the chromes that made a promise in numbers actually measure.
 *
 * Most of the fifteen are claims about appearance and are reviewed by looking at a screenshot. Six
 * are not. "High contrast" and "colour-blind" are claims with published thresholds behind them, and
 * a chrome that made either claim and missed it would be worse than no chrome at all — a reader
 * would choose it *because* of the promise. F7's four make a quieter promise and the same kind: a
 * chrome is a room somebody works in for an hour, and one whose secondary text or whose armed
 * button sat under WCAG AA would be a room nobody could work in, however good it looked.
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
         * What F7's four are held to, and the spec's own figure for them. They are not accessibility
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
         * The eleven chromes as they were on `main` at 27fd260, role by role.
         *
         * Thirty-six ARGB values each, in the order [roles] writes them. See the test that
         * reads them; they were produced by running exactly that function on that tree.
         */
        val BEFORE_F7: List<Pair<String, String>> = listOf(
        "SYSTEM" to
            "ff6b3f2a,fff6eedb,ffe2d2a9,ff2b2117,ffc9a227,ff5b4a2f," +
            "fff6eedb,ffe2d2a9,ff2b2117,ff6e5b3c,fff6eedb,ffe2d2a9," +
            "ff2b2117,ffefe4c8,ff2b2117,fff6eedb,ff2b2117,ffe4d8b9," +
            "ff6e5b3c,fff6eedb,ff2b2117,fff6eedb,ff8a3b2e,fff6eedb," +
            "ffe8cfc0,ff3a1810,ffbfad86,ffd7c8a5,ff17120b,fff9f3e3," +
            "ffe3d7b8,fffbf6e9,fff7f0de,fff6eedb,ffede2c7,ffe4d8b9",
        "LIGHT" to
            "ff6b3f2a,fff6eedb,ffe2d2a9,ff2b2117,ffc9a227,ff5b4a2f," +
            "fff6eedb,ffe2d2a9,ff2b2117,ff6e5b3c,fff6eedb,ffe2d2a9," +
            "ff2b2117,ffefe4c8,ff2b2117,fff6eedb,ff2b2117,ffe4d8b9," +
            "ff6e5b3c,fff6eedb,ff2b2117,fff6eedb,ff8a3b2e,fff6eedb," +
            "ffe8cfc0,ff3a1810,ffbfad86,ffd7c8a5,ff17120b,fff9f3e3," +
            "ffe3d7b8,fffbf6e9,fff7f0de,fff6eedb,ffede2c7,ffe4d8b9",
        "DARK" to
            "ffc9a227,ff15110f,ff2a2114,ffc9a227,ff6b3f2a,ff8d7326," +
            "ff15110f,ff2a2114,fff2e7cf,ff9c9187,ff15110f,ff241e1a," +
            "ffe8dfd0,ff15110f,ffe8dfd0,ff1c1714,ffe8dfd0,ff241d18," +
            "ff9c9187,ff1c1714,fff2e7cf,ff15110f,ff7e1414,fff2e7cf," +
            "ff5d0000,fff2e7cf,ff3a2f28,ff2b231d,ff0a0807,ff2a231e," +
            "ff15110f,ff100d0b,ff181310,ff1c1714,ff221b16,ff29211b",
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
     * F6 names, it cannot reach 7:1 against anything, and so it is the border, the rail and the
     * bead — never a word. The test asserts both halves, because either one alone could be
     * satisfied by quietly abandoning the other.
     */
    @Test
    fun `the high contrast accent is the specified blue, and only ever a mark`() {
        val detail = ThemeChoice.HIGH_CONTRAST.detail()
        val scheme = ThemeChoice.HIGH_CONTRAST.scheme(systemDark = true)
        val mark = detail.mark(scheme)
        assertEquals(Color(0xFF1A6EFF), mark, "the mark accent is no longer the blue F6 names")

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
     * That F6 changed nothing about the eight chromes that came before it.
     *
     * Every one of them takes the default [ChromeDetail], which is the whole of the reason their
     * screenshots are pixel-identical: a hairline rule, one accent, no cues, no box.
     */
    @Test
    fun `the chromes before F6 carry no ornament`() {
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
    // F7's four.
    // ---------------------------------------------------------------------------------------

    /**
     * Every pair in a scheme that the application actually draws text with.
     *
     * The High contrast list above, generalised, plus the two roles F7 added to the machinery: the
     * armed button's label now comes from [ChromeDetail.label] rather than always from `primary`,
     * because three of these chromes make that button a block instead of a stain, and measuring it
     * as `primary` on `primaryContainer` would measure a pair nobody draws.
     *
     * [ground] is what turns a panel colour into the colour a word is really read against. It is the
     * identity for three of the four; for Hessian it composites the weave in.
     */
    private fun textPairs(
        s: ColorScheme,
        detail: ChromeDetail,
        ground: (Color) -> Color = { it }
    ): List<Triple<String, Color, Color>> = listOf(
        Triple("body text on the window", s.onBackground, ground(s.background)),
        Triple("body text on a panel", s.onSurface, ground(s.surface)),
        Triple("secondary text on a sunk panel", s.onSurfaceVariant, ground(s.surfaceVariant)),
        Triple("secondary text on a panel", s.onSurfaceVariant, ground(s.surface)),
        Triple("the menu strip", s.onSurface, ground(s.surfaceContainerHigh)),
        Triple("a menu heading", s.onSurfaceVariant, ground(s.surfaceContainerHighest)),
        Triple("an open menu", s.onSurface, ground(s.surfaceContainerHighest)),
        Triple("a card", s.onSurface, ground(s.surfaceContainer)),
        Triple("the accent as a word, on a panel", s.primary, ground(s.surface)),
        Triple("the secondary as a word, on a panel", s.secondary, ground(s.surface)),
        Triple("the armed button's label", detail.label(s), s.primaryContainer),
        Triple("a label on the accent", s.onPrimary, s.primary),
        Triple("a chosen chip", s.onSecondaryContainer, s.secondaryContainer),
        Triple("a filled accent block", s.onPrimaryContainer, s.primaryContainer),
        Triple("a tertiary block", s.onTertiaryContainer, s.tertiaryContainer),
        Triple("an error, on a panel", s.error, ground(s.surface)),
        Triple("a label on an error", s.onError, s.error),
        Triple("an error block", s.onErrorContainer, s.errorContainer),
        Triple("an inverted strip", s.inverseOnSurface, s.inverseSurface)
    )

    /** Asserts every pair at AA and prints the worst, the way the two F6 guards do. */
    private fun assertAA(
        choice: ThemeChoice,
        ground: (Color) -> Color = { it },
        note: String = ""
    ) {
        val scheme = choice.scheme(systemDark = true)
        val pairs = textPairs(scheme, choice.detail(), ground)
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
     * The third instance of the decision F6 made for High contrast, and the spec predicted it:
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
        assertEquals(Color(0xFF9C7A3C), bronze, "the Roman mark is no longer the bronze F7 names")
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

    /**
     * That F7's four are the only chromes carrying F7's ornament.
     *
     * The counterpart of the guard above for F6, and the other half of the byte-identity claim: a
     * cut bar, a meander, a running stitch, a prompt, an interpunct, a weave and a button label are
     * each asked for by exactly the chromes that asked for them, and by nobody else.
     */
    @Test
    fun `only F7's four carry F7's ornament`() {
        val f7 = setOf(
            ThemeChoice.MATRIX, ThemeChoice.HESSIAN, ThemeChoice.ROMAN, ThemeChoice.HITCHCOCK
        )
        ThemeChoice.entries.forEach { choice ->
            val detail = choice.detail()
            val mine = choice in f7
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
                    "${choice.label} has taken one of F7's rules"
                )
                assertTrue(
                    detail.cartouche !in setOf(
                        CartoucheStyle.STITCHED,
                        CartoucheStyle.DOUBLE_RULE,
                        CartoucheStyle.SPIRAL
                    ),
                    "${choice.label} has taken one of F7's cartouches"
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
        assertEquals("Working resolution", ChromeDetail.PLAIN.heading("Working resolution"))
        assertEquals("TERRAIN", ThemeChoice.ALLIED.detail().heading("Terrain", panel = true))
        // Allied's capitals stop at the panel. That is what leaves it byte-identical.
        assertEquals("Working resolution", ThemeChoice.ALLIED.detail().heading("Working resolution"))
        assertEquals("> TERRAIN", ThemeChoice.MATRIX.detail().heading("Terrain"))
        assertEquals("TERRAIN", ThemeChoice.HESSIAN.detail().heading("Terrain"))
        assertEquals(
            "WORKING·RESOLUTION",
            ThemeChoice.ROMAN.detail().heading("Working resolution")
        )
        assertEquals("TERRAIN", ThemeChoice.ROMAN.detail().heading("Terrain"))
        assertEquals("EXPORT", ThemeChoice.HITCHCOCK.detail().heading("Export"))
    }

    /**
     * That F7 did not move one colour of the eleven chromes that came before it.
     *
     * The claim F7 has to make, and the strongest form it can be made in without a picture: every
     * Material role of every earlier chrome, recorded from `main` at 27fd260 before a line of F7
     * was written, and compared against what the enum answers now. Recorded rather than recomputed,
     * because a comparison against something this run also produced would pass however wrong both
     * halves were.
     *
     * Thirty-six roles is the whole of a `ColorScheme` this application ever reads, in the order
     * the probe wrote them. `ChromeGalleryTest` makes the other half of the claim, in pixels: the
     * same eleven chromes with a menu open, captured and compared with the fingerprints the same
     * tree produced.
     */
    @Test
    fun `the eleven chromes before F7 have not moved a colour`() {
        BEFORE_F7.forEach { (name, expected) ->
            val choice = ThemeChoice.entries.first { it.name == name }
            assertEquals(
                expected,
                roles(choice.scheme(systemDark = false)),
                "$name is not the scheme it was before F7"
            )
        }
        println("CHROME F7 identity: ${BEFORE_F7.size} chromes x 36 roles unchanged")
    }

    /** Every role the application reads, in the order the pre-F7 probe wrote them. */
    private fun roles(s: ColorScheme): String = listOf(
        s.primary, s.onPrimary, s.primaryContainer, s.onPrimaryContainer, s.inversePrimary,
        s.secondary, s.onSecondary, s.secondaryContainer, s.onSecondaryContainer,
        s.tertiary, s.onTertiary, s.tertiaryContainer, s.onTertiaryContainer,
        s.background, s.onBackground, s.surface, s.onSurface,
        s.surfaceVariant, s.onSurfaceVariant, s.surfaceTint,
        s.inverseSurface, s.inverseOnSurface,
        s.error, s.onError, s.errorContainer, s.onErrorContainer,
        s.outline, s.outlineVariant, s.scrim,
        s.surfaceBright, s.surfaceDim,
        s.surfaceContainerLowest, s.surfaceContainerLow, s.surfaceContainer,
        s.surfaceContainerHigh, s.surfaceContainerHighest
    ).joinToString(",") { it.toArgb().toUInt().toString(16).padStart(8, '0') }

    private fun Double.rounded(): String {
        val scaled = kotlin.math.round(this * 100.0) / 100.0
        return scaled.toString()
    }
}
