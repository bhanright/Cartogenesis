package com.cartogenesis.desktop

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.cartogenesis.cartography.ColorVision
import com.cartogenesis.ui.CartogenesisTheme
import com.cartogenesis.ui.ThemeChoice
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
 * variable cannot be added to the page without a pair being measured for it here. The values are
 * held to the application's dark scheme as the window draws it, read from a composition rather
 * than from literals, so the page and the window cannot drift apart from either side.
 *
 * Which ink sits on which ground is written out in [TEXT_PAIRS], because the page's grounds are
 * inherited and no parse of its rules says what a word is read against. What a parse can say is
 * checked: every colour a rule sets text in is one of the palette's names, never a literal, and
 * every one of them is measured in some pair. The loading shell (`site/app/index.html`), which has
 * no variables, is read for its own literal colours and each of them measured on its ground.
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

        /**
         * Exactly the custom properties the page's `:root` is expected to define, each with the
         * role of the application's dark scheme it mirrors.
         */
        val PALETTE: Map<String, (ColorScheme) -> Int> = mapOf(
            "ink" to { s -> s.background.toArgb() },
            "ink-raised" to { s -> s.surface.toArgb() },
            "ink-sunk" to { s -> s.surfaceVariant.toArgb() },
            "hairline" to { s -> s.outline.toArgb() },
            "bone" to { s -> s.onBackground.toArgb() },
            "bone-dim" to { s -> s.onSurfaceVariant.toArgb() },
            "parchment" to { s -> s.onSecondaryContainer.toArgb() },
            "brass" to { s -> s.primary.toArgb() },
            "brass-dim" to { s -> s.secondary.toArgb() },
            "oxblood" to { s -> s.errorContainer.toArgb() },
            "oxblood-lit" to { s -> s.error.toArgb() }
        )

        /** Every text pair on the page: where it is, the ink, and the ground it is read on. */
        val TEXT_PAIRS: List<Triple<String, String, String>> = listOf(
            // On the page's ground.
            Triple("body text", "bone", "ink"),
            Triple("lede, leads, captions, spec values, footer", "bone-dim", "ink"),
            Triple("headline, wordmark, section headings", "parchment", "ink"),
            Triple("eyebrow, spec terms, selected tab, note numbers", "brass", "ink"),
            Triple("annotation cartouche text", "bone", "ink"),
            Triple("unselected tab", "bone-dim", "ink"),
            // On a raised panel: the notices, the practical panels, the hero plate.
            Triple("notice and panel text", "bone-dim", "ink-raised"),
            Triple("notice lead-in", "bone", "ink-raised"),
            Triple("panel heading", "parchment", "ink-raised"),
            Triple("panel call to action", "brass", "ink-raised"),
            Triple("platform tag", "bone-dim", "ink-raised"),
            // A file name inside a sentence takes the mono face and the panel's own ground.
            Triple("file name in a note", "bone", "ink-raised"),
            // On a sunk panel: the three apt commands a Linux reader copies, and what a hovered or
            // focused secondary button and download card become.
            Triple("command block", "bone", "ink-sunk"),
            Triple("secondary button, hovered", "brass", "ink-sunk"),
            Triple("download card heading, hovered", "parchment", "ink-sunk"),
            Triple("download card text, hovered", "bone-dim", "ink-sunk"),
            // On the primary button, at rest and lit.
            Triple("primary button", "parchment", "oxblood"),
            Triple("primary button, hovered", "parchment", "oxblood-lit"),
            // The secondary button's own label sits on the page ground.
            Triple("secondary button", "brass", "ink")
        )
    }

    private fun readFromRoot(path: String): String {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle.kts").isFile) return File(dir, path).readText()
            dir = dir.parentFile
        }
        fail("could not find the repository root from ${File(".").absolutePath}")
    }

    private val page: String by lazy { readFromRoot("site/index.html") }

    /** The loading shell the web app's bundle is fetched behind, which sets its colours literally. */
    private val shell: String by lazy { readFromRoot("site/app/index.html") }

    /** Every `--name:#rrggbb` the page declares, as opaque ARGB. */
    private val namedColours: Map<String, Int> by lazy {
        Regex("""--([a-z-]+):\s*(#[0-9a-fA-F]{6})\s*;""").findAll(page)
            .associate { it.groupValues[1] to (0xFF000000.toInt() or
                it.groupValues[2].substring(1).toInt(16)) }
    }

    private fun colour(name: String): Int =
        namedColours[name] ?: fail("the page no longer defines --$name")

    /** Measures one pair and says, in the failure, exactly which rule on the page it came from. */
    private fun check(where: String, ink: String, ground: String, bar: Double) =
        checkValues(where, "--$ink", colour(ink), "--$ground", colour(ground), bar)

    private fun checkValues(where: String, inkName: String, ink: Int, groundName: String, ground: Int, bar: Double) {
        val ratio = ColorVision.contrast(ink, ground)
        println("SITE CONTRAST %-44s %-12s on %-12s %5.2f:1 (bar %.1f)"
            .format(where, inkName, groundName, ratio, bar))
        assertTrue(
            ratio >= bar,
            "$where sets $inkName on $groundName, which measures " +
                "${"%.2f".format(ratio)}:1 against a bar of $bar"
        )
    }

    /** The application's dark scheme as the window draws it: the theme composed, and read back. */
    @OptIn(ExperimentalTestApi::class)
    private fun windowsDarkScheme(): ColorScheme {
        var scheme: ColorScheme? = null
        runDesktopComposeUiTest(width = 64, height = 64) {
            setContent {
                CartogenesisTheme(dark = true, choice = ThemeChoice.DARK) {
                    scheme = MaterialTheme.colorScheme
                }
            }
            waitForIdle()
        }
        return scheme ?: fail("the dark theme composed no colour scheme")
    }

    @Test
    fun `the page defines exactly the palette this test measures, and it is the window's`() {
        assertEquals(
            PALETTE.keys.sorted(), namedColours.keys.sorted(),
            "the page's palette and this test have drifted apart. Every colour the page defines " +
                "has to have a measured pair here, or it is a colour nobody has checked."
        )
        // The values are the application's dark scheme, read off the theme the window composes:
        // if either side moves alone, the page and the window have stopped being the same room.
        val scheme = windowsDarkScheme()
        val drifted = PALETTE.entries.filter { (name, role) -> colour(name) != role(scheme) }
            .map { (name, role) -> "--$name is #%06X on the page and #%06X in the window".format(colour(name) and 0xFFFFFF, role(scheme) and 0xFFFFFF) }
        assertTrue(drifted.isEmpty(), drifted.joinToString("; "))
    }

    @Test
    fun `every text pair on the page meets AA`() {
        TEXT_PAIRS.forEach { (where, ink, ground) -> check(where, ink, ground, AA) }
    }

    /**
     * What a parse of the page's rules can say about the pairs written out above: every colour a
     * rule sets text in is one of the palette's names — a literal hex would never be measured —
     * and every such name is the ink of at least one measured pair.
     */
    @Test
    fun `every colour the page sets text in is a palette colour that is measured`() {
        val textColours = Regex("""(?<![-a-zA-Z])color\s*:\s*([^;}]+)""").findAll(page)
            .map { it.groupValues[1].trim() }.toList()
        assertTrue(textColours.isNotEmpty(), "the page sets no text colour at all, so this read nothing")
        val literal = textColours.filterNot { it.startsWith("var(--") || it == "inherit" || it == "transparent" }
        assertTrue(literal.isEmpty(), "rules on the page set text in colours no pair measures: $literal")
        val measuredInks = TEXT_PAIRS.map { it.second }.toSet()
        val unmeasured = textColours.filter { it.startsWith("var(--") }
            .map { it.removePrefix("var(--").substringBefore(")") }
            .filter { it !in measuredInks && it != "brass-dim" }.distinct()
        assertTrue(unmeasured.isEmpty(), "the page sets text in --$unmeasured, which no pair here measures")
    }

    /**
     * The loading shell, which a reader sees for as long as the bundle takes to arrive. It has no
     * variables, so its literal colours are read off its rules and each text colour measured on
     * the ground the shell paints behind it.
     */
    @Test
    fun `every text colour the loading shell sets meets AA on its ground`() {
        val ground = Regex("""#boot\s*\{[^}]*background\s*:\s*(#[0-9a-fA-F]{6})""").find(shell)
            ?.groupValues?.get(1) ?: fail("the loading shell no longer paints its own ground")
        val groundValue = 0xFF000000.toInt() or ground.substring(1).toInt(16)
        val inks = Regex("""([#.\w\s]+)\{[^}]*?(?<![-a-zA-Z])color\s*:\s*(#[0-9a-fA-F]{6})""").findAll(shell)
            .map { it.groupValues[1].trim() to it.groupValues[2] }.toList()
        assertTrue(inks.size >= 4, "the loading shell sets ${inks.size} text colours; its wordmark, messages and link are missing")
        inks.forEach { (rule, ink) ->
            checkValues("loading shell, $rule", ink, 0xFF000000.toInt() or ink.substring(1).toInt(16), ground, groundValue, AA)
        }
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
        println("SITE CONTRAST %-44s %-12s on %-12s %5.2f:1 (decorative, no bar)"
            .format("hairlines and dividers", "--hairline", "--ink", divider))
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
