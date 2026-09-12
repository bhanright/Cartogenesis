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
 * What the two accessibility chromes promise, in numbers.
 *
 * The other nine chromes are claims about appearance and are reviewed by looking at a screenshot.
 * These two are not: "high contrast" and "colour-blind" are claims with published thresholds behind
 * them, and a chrome that made either claim and missed it would be worse than no chrome at all —
 * a reader would choose it *because* of the promise.
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
            assertEquals(false, detail.smallCapsHeadings, "${choice.label} capitals")
            assertEquals(false, detail.boxedCartouche, "${choice.label} cartouche")
        }
    }

    private fun Double.rounded(): String {
        val scaled = kotlin.math.round(this * 100.0) / 100.0
        return scaled.toString()
    }
}
