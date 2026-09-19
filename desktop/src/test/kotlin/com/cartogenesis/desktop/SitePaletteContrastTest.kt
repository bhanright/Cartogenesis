package com.cartogenesis.desktop

import com.cartogenesis.cartography.ColorVision
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every pair of colours a reader of cartogenesis.com is asked to read, measured.
 *
 * The landing page took the application's palette in Site 2, and a palette designed for a Compose
 * window is not automatically legible on a web page: the sizes differ, the weights differ, and a
 * grey that reads well as a slider's value at 13sp is a different proposition as a paragraph. The
 * page it replaced had three such greys, and two of them were below AA — `#7d7269` on the ink
 * measured 4.04:1 in the footer and `#6e645c` measured 3.20:1 in the loading shell. Nobody noticed
 * either, because nothing measured them.
 *
 * The colours are **read out of the page's own CSS** rather than copied here. A copy is a second
 * source of truth that drifts silently, and the failure mode is the worst kind: the guard goes on
 * passing while the page it is supposed to be about has changed. [PALETTE] therefore lists only the
 * *names* the page defines, and [namedColours] insists the two lists match exactly, so a new
 * variable cannot be added to the page without a pair being measured for it here.
 *
 * The arithmetic is [ColorVision.contrast], the same function `ChromeContrastTest` holds the
 * window chromes to, so the site and the application cannot come to mean different things
 * by the same number.
 */
class SitePaletteContrastTest {

    private companion object {
        /** WCAG 2.1 AA for body text (success criterion 1.4.3). */
        const val AA = 4.5

        /**
         * WCAG 2.1 AA for large text — 18.66px bold or 24px plain (1.4.3 again).
         *
         * Nothing on this page relies on it. It is stated so that the one heading colour that
         * would qualify is still held to the stricter bar on purpose rather than by accident.
         */
        const val AA_LARGE = 3.0

        /**
         * WCAG 2.1 1.4.11, for a control's own boundary rather than for anything read: the
         * outlined button, the selected tab's rule, the focus ring, an annotation's cartouche.
         */
        const val NON_TEXT = 3.0

        /** Exactly the custom properties the page's `:root` is expected to define. */
        val PALETTE = listOf(
            "ink", "ink-raised", "ink-sunk", "hairline",
            "bone", "bone-dim", "parchment", "brass", "brass-dim", "oxblood", "oxblood-lit"
        )
    }

    private val page: String by lazy {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle.kts").isFile) {
                return@lazy File(dir, "site/index.html").readText()
            }
            dir = dir.parentFile
        }
        fail("could not find the repository root from ${File(".").absolutePath}")
    }

    /** Every `--name:#rrggbb` the page declares, as opaque ARGB. */
    private val namedColours: Map<String, Int> by lazy {
        Regex("""--([a-z-]+):\s*(#[0-9a-fA-F]{6})\s*;""").findAll(page)
            .associate { it.groupValues[1] to (0xFF000000.toInt() or
                it.groupValues[2].substring(1).toInt(16)) }
    }

    private fun colour(name: String): Int =
        namedColours[name] ?: fail("the page no longer defines --$name")

    /** Measures one pair and says, in the failure, exactly which rule on the page it came from. */
    private fun check(where: String, ink: String, ground: String, bar: Double) {
        val ratio = ColorVision.contrast(colour(ink), colour(ground))
        println("SITE CONTRAST %-44s %-10s on %-11s %5.2f:1 (bar %.1f)"
            .format(where, ink, ground, ratio, bar))
        assertTrue(
            ratio >= bar,
            "$where sets --$ink on --$ground, which measures " +
                "${"%.2f".format(ratio)}:1 against a bar of $bar"
        )
    }

    @Test
    fun `the page defines exactly the palette this test measures`() {
        assertEquals(
            PALETTE.sorted(), namedColours.keys.sorted(),
            "the page's palette and this test have drifted apart. Every colour the page defines " +
                "has to have a measured pair here, or it is a colour nobody has checked."
        )
        // The values are the application's dark scheme in ui/Theme.kt, taken literally. If one of
        // these moves, the page and the window have stopped being the same room.
        assertEquals(0xFF121417.toInt(), colour("ink"), "--ink is no longer the application's ink")
        assertEquals(
            0xFF191C20.toInt(), colour("ink-raised"),
            "--ink-raised is no longer the application's raised panel"
        )
        assertEquals(
            0xFF21252A.toInt(), colour("ink-sunk"),
            "--ink-sunk is no longer the application's sunk panel"
        )
        assertEquals(
            0xFF363C44.toInt(), colour("hairline"),
            "--hairline is no longer the application's rule"
        )
        assertEquals(0xFFE8DFD0.toInt(), colour("bone"), "--bone is no longer the app's bone")
        assertEquals(0xFFC9A227.toInt(), colour("brass"), "--brass is no longer the app's brass")
        assertEquals(0xFF5D0000.toInt(), colour("oxblood"), "--oxblood is no longer the app's")
    }

    @Test
    fun `every text pair on the page meets AA`() {
        // On the page's ground.
        check("body text", "bone", "ink", AA)
        check("lede, leads, captions, spec values, footer", "bone-dim", "ink", AA)
        check("headline, wordmark, section headings", "parchment", "ink", AA)
        check("eyebrow, spec terms, selected tab, note numbers", "brass", "ink", AA)
        check("annotation cartouche text", "bone", "ink", AA)
        check("unselected tab", "bone-dim", "ink", AA)

        // On a raised panel: the notices, the practical panels, the hero plate.
        check("notice and panel text", "bone-dim", "ink-raised", AA)
        check("notice lead-in", "bone", "ink-raised", AA)
        check("panel heading", "parchment", "ink-raised", AA)
        check("panel call to action", "brass", "ink-raised", AA)
        check("platform tag", "bone-dim", "ink-raised", AA)

        // A file name inside a sentence takes the mono face and the panel's own ground.
        check("file name in a note", "bone", "ink-raised", AA)

        // On a sunk panel: the three apt commands a Linux reader copies, and what a hovered or
        // focused secondary button and download card become.
        check("command block", "bone", "ink-sunk", AA)
        check("secondary button, hovered", "brass", "ink-sunk", AA)
        check("download card heading, hovered", "parchment", "ink-sunk", AA)
        check("download card text, hovered", "bone-dim", "ink-sunk", AA)

        // On the primary button, at rest and lit.
        check("primary button", "parchment", "oxblood", AA)
        check("primary button, hovered", "parchment", "oxblood-lit", AA)

        // The secondary button's own label sits on the page ground.
        check("secondary button", "brass", "ink", AA)
    }

    @Test
    fun `the boundaries a reader has to see meet the non-text bar`() {
        check("secondary button border", "brass", "ink", NON_TEXT)
        check("selected tab underline", "brass", "ink", NON_TEXT)
        check("focus ring", "brass", "ink", NON_TEXT)
        check("annotation cartouche border", "brass-dim", "ink", NON_TEXT)
        check("notice accent border", "brass-dim", "ink-raised", NON_TEXT)

        // Measured and reported, but deliberately held to nothing. The hairline separates one
        // block of the page from the next and carries no information a reader would otherwise
        // lose: every panel it outlines also has its own ground, every rule it draws sits beside
        // a heading that says what follows. 1.4.11 is about controls and about graphics that
        // convey meaning, and a divider is neither. Naming it here is how the next person knows
        // it was considered rather than missed.
        val divider = ColorVision.contrast(colour("hairline"), colour("ink"))
        println("SITE CONTRAST %-44s %-10s on %-11s %5.2f:1 (decorative, no bar)"
            .format("hairlines and dividers", "hairline", "ink", divider))
        assertTrue(divider > 1.0, "the hairline has become invisible against the ground")
    }

    @Test
    fun `brass-dim is never asked to carry text`() {
        // It measures below AA on every ground the page has, which is why it is a rule colour and
        // nothing else. This is the guard that stops it creeping back into a caption: the old
        // page set its spec terms and its eyebrows in it.
        val onInk = ColorVision.contrast(colour("brass-dim"), colour("ink"))
        assertTrue(
            onInk < AA,
            "--brass-dim now measures ${"%.2f".format(onInk)}:1 on the ink. If the colour was " +
                "changed on purpose, this test's reasoning needs rewriting, not deleting."
        )
        // The lookbehind is what makes this bite: `border-color`, `background-color` and
        // `outline-color` all end in the same five letters, and a rule that merely draws a line
        // in brass-dim is exactly what the colour is for.
        val textRules = Regex("""(?<![-a-zA-Z])color\s*:\s*var\(--brass-dim\)""")
        assertTrue(
            textRules.find(page) == null,
            "a rule on the page paints text in --brass-dim, which measures " +
                "${"%.2f".format(onInk)}:1 on the ink and ${"%.2f".format(
                    ColorVision.contrast(colour("brass-dim"), colour("ink-raised"))
                )}:1 on a panel. Use --brass for small type; --brass-dim is for rules."
        )
    }
}
