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

        /**
         * The page's colours of its own, which the window has no use for, each with the one place it
         * is used. Named here so a colour cannot be added to the page without being listed, and each
         * measured below on what it is read against.
         */
        val PAGE_ONLY: Map<String, String> = mapOf(
            "letter-unlit" to "the title card's name, before its letters are struck",
            "letter-struck" to "each letter's flash as it is struck, on its way to brass"
        )

        /** Every text pair on the page: where it is, the ink, and the ground it is read on. */
        val TEXT_PAIRS: List<Triple<String, String, String>> = listOf(
            // On the page's ground, the opening's panel and the tray included (the tray's ground is
            // the ink at 92%, over a page whose every ground is darker than the words set on it).
            Triple("body text, the opening's lede", "bone", "ink"),
            Triple("section leads, captions, spec values, card text, footer", "bone-dim", "ink"),
            Triple("headline, wordmark, section and card headings", "parchment", "ink"),
            Triple("eyebrow, spec terms, note numbers, the link under the downloads", "brass", "ink"),
            // On a raised panel: the practical panels.
            Triple("panel text", "bone-dim", "ink-raised"),
            Triple("panel heading", "parchment", "ink-raised"),
            Triple("panel call to action", "brass", "ink-raised"),
            Triple("platform tag", "bone-dim", "ink-raised"),
            // On a sunk panel: what a hovered or focused download panel in the Notes becomes.
            Triple("download panel heading, hovered", "parchment", "ink-sunk"),
            Triple("download panel text, hovered", "bone-dim", "ink-sunk"),
            // On the primary button, at rest and lit: the page's ink on brass, and on parchment.
            Triple("primary button", "ink", "brass"),
            Triple("primary button, hovered", "ink", "parchment"),
            // The secondary button's label and the scroll cue's arrow sit on the page's ground.
            Triple("secondary button, the cue and the pause control", "parchment", "ink"),
            // A card's number, set in the page's ink on the brass square that marks its place on
            // the line: the one place the page sets type on brass rather than brass on the ground.
            Triple("card number", "ink", "brass"),
            // The living figures.
            Triple("the slider's style pickers", "bone", "ink-raised"),
            Triple("step and picker labels", "bone-dim", "ink"),
            Triple("play button", "brass", "ink"),
            Triple("play button, hovered", "brass", "ink-sunk"),
            // The second set: a data frame overlay switched on, the reel's seed and its link.
            Triple("an overlay switch, pressed", "ink", "brass"),
            Triple("a reel world's seed", "bone-dim", "ink"),
            Triple("a reel world's link", "brass", "ink")
        )

        /**
         * How a download card's pairs are read: which of the card's own colours is the ink and
         * which the ground. Each card names its ground and ink, and mixes its dim ink, its chip and
         * its command well from them, so every pair is measured on that card's own ground.
         */
        val CARD_PAIRS: List<Triple<String, String, String>> = listOf(
            Triple("name, the + control, other pills, links", "card-ink", "card-ground"),
            Triple("summary line, instructions and notes", "card-ink-dim", "card-ground"),
            Triple("the For your system tag, the Copy button", "card-ink", "card-chip"),
            Triple("the apt commands, on a card that carries commands", "card-ink", "card-well"),
            Triple("the first pill, filled", "card-ground", "card-ink")
        )

        /**
         * WCAG 2.1's large text: the title card's name is at least 22 pixels in the semibold, which
         * is past the 18.66 pixels of bold that counts as large. The line under it is regular type
         * from 17 pixels, which does not, and is held to [AA].
         */
        const val TITLE_NAME_BAR = AA_LARGE

        /** The brightest a pixel of the band can be, and the band holds it: the ice is pure white. */
        val BRIGHTEST_BAND_PIXEL = 0xFFFFFFFF.toInt()
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

    /**
     * Every `--name:#rrggbb` the page declares, as opaque ARGB: the palette in `:root` and the
     * page's own colours beside it.
     */
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
            (PALETTE.keys + PAGE_ONLY.keys).sorted(), namedColours.keys.sorted(),
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
     * rule sets text in is one of the page's names — a literal hex would never be measured — and
     * every such name is the ink of at least one measured pair: a palette colour in [TEXT_PAIRS], a
     * download card's own colour in [CARD_PAIRS], or a title-card letter.
     */
    @Test
    fun `every colour the page sets text in is a palette colour that is measured`() {
        val textColours = Regex("""(?<![-a-zA-Z])color\s*:\s*([^;}]+)""").findAll(page)
            .map { it.groupValues[1].trim() }.toList()
        assertTrue(textColours.isNotEmpty(), "the page sets no text colour at all, so this read nothing")
        val literal = textColours.filterNot { it.startsWith("var(--") || it == "inherit" || it == "transparent" }
        assertTrue(literal.isEmpty(), "rules on the page set text in colours no pair measures: $literal")
        val measuredInks = TEXT_PAIRS.map { it.second }.toSet() + CARD_PAIRS.map { it.second } + PAGE_ONLY.keys
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
        // A secondary button's resting border is a hairline, which says nothing its own label does
        // not; hovered or focused it turns brass, and that is the edge a reader looks for.
        check("secondary button border, hovered or focused", "brass", "ink", NON_TEXT)
        check("focus ring", "brass", "ink", NON_TEXT)
        // The line a row of cards sits on and the squares on it are what say the cards are a
        // sequence, which is information, so they are held to the bar a meaningful graphic is.
        check("card line and number square", "brass", "ink", NON_TEXT)
        // The step on show is marked by a ring round its square, which is the only thing saying
        // which card the frame is showing; the suggested download by a rule beside its row.
        check("lit step ring", "parchment", "ink", NON_TEXT)
        // The lens's rim, which says where the lens is over the map, and the frame a reel world
        // takes when hovered or focused, on the page's ground.
        check("lens rim and a reel world, hovered", "brass", "ink", NON_TEXT)

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

    /** The page's style sheet, comments taken out. */
    private val styleSheet: String by lazy {
        (Regex("""<style>(.*?)</style>""", RegexOption.DOT_MATCHES_ALL).find(page)?.groupValues?.get(1)
            ?: fail("the page has no style sheet")).replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
    }

    /** The declarations of the rule whose whole selector is [selector], or a failure. */
    private fun declarationsOf(selector: String): Map<String, String> {
        val body = Regex("""(?:^|[}\s])${Regex.escape(selector)}\s*\{([^}]*)\}""").find(styleSheet)?.groupValues?.get(1)
            ?: fail("the style sheet has no rule for $selector")
        return body.split(';').map { it.trim() }.filter { it.contains(':') }
            .associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
    }

    /**
     * A colour as the style sheet writes it, resolved: a palette name, one of [custom]'s own
     * properties, or `color-mix(in srgb, A N%, B)` of two of those, which a browser mixes channel by
     * channel in sRGB, N parts of A to the rest of B.
     */
    private fun resolve(value: String, custom: Map<String, String>): Int {
        val mix = Regex("""color-mix\(\s*in srgb\s*,\s*(var\(--[\w-]+\))\s+(\d+(?:\.\d+)?)%\s*,\s*(var\(--[\w-]+\))\s*\)""")
            .matchEntire(value.trim())
        if (mix != null) {
            val share = mix.groupValues[2].toDouble() / 100
            val first = resolve(mix.groupValues[1], custom)
            val second = resolve(mix.groupValues[3], custom)
            fun channel(shift: Int) = Math.round(
                ((first shr shift) and 0xFF) * share + ((second shr shift) and 0xFF) * (1 - share)
            ).toInt()
            return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
        val name = Regex("""var\(--([\w-]+)\)""").matchEntire(value.trim())?.groupValues?.get(1)
            ?: fail("$value is not a colour this test can resolve")
        return custom[name]?.let { resolve(it, custom) } ?: colour(name)
    }

    /**
     * That every download card's words meet AA on that card's own ground.
     *
     * The cards are the one place the page sets type on grounds other than its own: Windows on
     * brass, Linux on the lit oxblood, the browser on the sunk ink. Each card's ground and inks are
     * read out of the card's own rule, over the defaults every card starts from, and each of
     * [CARD_PAIRS] measured with them, so a card whose ground or dim ink moves is measured as it
     * now is rather than as it was written here.
     */
    @Test
    fun `every download card's words meet AA on its own ground`() {
        val defaults = declarationsOf(".dl-card").filterKeys { it.startsWith("--card-") }.mapKeys { it.key.removePrefix("--") }
        assertTrue(defaults.keys.containsAll(listOf("card-ground", "card-ink", "card-ink-dim", "card-chip", "card-well")),
            "the cards no longer name their ground, inks, chip and well: ${defaults.keys}")
        val cards = listOf("windows", "linux", "browser")
        cards.forEach { card ->
            val own = declarationsOf(".dl-card.$card").filterKeys { it.startsWith("--card-") }.mapKeys { it.key.removePrefix("--") }
            val custom = defaults + own
            // The command well is measured on the cards that have one.
            val markup = Regex("""<div class="dl-card $card"[\s\S]*?(?=<div class="dl-card |<a class="dl-all")""").find(page)?.value
                ?: fail("the page has no $card card")
            val pairs = CARD_PAIRS.filter { (_, _, ground) -> ground != "card-well" || markup.contains("""class="dl-cmd""") }
            pairs.forEach { (where, ink, ground) ->
                val inkValue = resolve("var(--$ink)", custom)
                val groundValue = resolve("var(--$ground)", custom)
                checkValues("$card card, $where", "--$ink", inkValue, "--$ground", groundValue, AA)
            }
            // The focus ring is drawn in the card's ink.
            checkValues("$card card, focus ring", "--card-ink", resolve("var(--card-ink)", custom),
                "--card-ground", resolve("var(--card-ground)", custom), NON_TEXT)
        }
    }

    /**
     * That the title card's name, in each of its three colours, and the line under it read over
     * the brightest ground the band can put behind them.
     *
     * The card stands over the moving map, so what is behind a letter could be anything the band
     * holds, and it holds pure white ice. The words sit on a scrim of their own, flat across the
     * middle of an ellipse that holds every letter (a headless Chrome measured each letter's
     * corners inside it at 375, 768, 1280 and 1920 wide; see docs/DESIGN_LEDGER.md, Site 6), so the
     * worst any letter is read against is the scrim's ink at its flat strength over white. The name
     * is measured unlit, at its flash and lit, and the line in its bone.
     */
    @Test
    fun `the title card's words read over the brightest ice the band holds`() {
        val scrim = declarationsOf(".title-words::before")["background"] ?: fail("the title card's words have no scrim")
        val flat = Regex("""radial-gradient\(\s*closest-side\s*,\s*color-mix\(\s*in srgb\s*,\s*var\(--ink\)\s+(\d+)%\s*,\s*transparent\s*\)\s+(\d+)%""")
            .find(scrim) ?: fail("the scrim is no longer the ink, flat to a stop and then feathered: $scrim")
        val strength = flat.groupValues[1].toDouble() / 100
        val ink = colour("ink")
        fun over(shift: Int) = Math.round(
            ((ink shr shift) and 0xFF) * strength + ((BRIGHTEST_BAND_PIXEL shr shift) and 0xFF) * (1 - strength)
        ).toInt()
        val ground = (0xFF shl 24) or (over(16) shl 16) or (over(8) shl 8) or over(0)
        val groundName = "ink ${flat.groupValues[1]}% over white"
        listOf("letter-unlit" to "the name, unlit", "letter-struck" to "the name, struck", "brass" to "the name, lit")
            .forEach { (name, where) -> checkValues("title card, $where", "--$name", colour(name), groundName, ground, TITLE_NAME_BAR) }
        checkValues("title card, the line under the name", "--bone", colour("bone"), groundName, ground, AA)
    }

    /**
     * The ink panel of the opening, over [BRIGHTEST_BAND_PIXEL] at [opacityPercent] of the ink,
     * mixed as a browser composites a translucent colour over what is behind it.
     */
    private fun panelOverTheBrightestBand(opacityPercent: Int): Int {
        val ink = colour("ink")
        val share = opacityPercent / 100.0
        fun channel(shift: Int) = Math.round(
            ((ink shr shift) and 0xFF) * share + ((BRIGHTEST_BAND_PIXEL shr shift) and 0xFF) * (1 - share)
        ).toInt()
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** The WCAG level a ratio reaches, as the bar it clears: 7 (AAA), 4.5 (AA), 3 or nothing. */
    private fun levelOf(ratio: Double, levels: List<Double>): Double = levels.firstOrNull { ratio >= it } ?: 0.0

    /**
     * That the opening's panel is only as translucent as its words allow, measured over the
     * brightest ground the band can put behind it.
     *
     * The panel rises over the drifting map, so any pixel of the band can be behind any word on it,
     * and the band holds pure white ice. The panel's opacity is not chosen by eye: it is the least
     * whole percent of the ink at which no word on the panel, read over white through the panel,
     * falls below the WCAG level it reaches on the solid ink (7:1 for body text that is AAA there,
     * 4.5 for large text that is AAA there, 3 for the cue's outline), and the page is held to
     * exactly that percent. Less would cost a word a level; more would make the panel less
     * translucent than its words need.
     */
    @Test
    fun `the opening's panel is as translucent as its words allow over the brightest band`() {
        val background = declarationsOf(".opening-panel")["background"] ?: fail("the opening's panel has no background")
        val percent = Regex("""color-mix\(\s*in srgb\s*,\s*var\(--ink\)\s+(\d+)%\s*,\s*transparent\s*\)""").find(background)
            ?.groupValues?.get(1)?.toInt() ?: fail("the panel's ground is no longer the ink mixed with transparency: $background")
        val bodyLevels = listOf(7.0, AA, AA_LARGE)
        val largeLevels = listOf(AA, AA_LARGE)
        val boundaryLevels = listOf(NON_TEXT)
        // Every word and edge on the panel, with the levels its kind of text is judged on.
        val panelPairs = listOf(
            Triple("eyebrow", "brass", bodyLevels),
            Triple("heading", "parchment", largeLevels),
            Triple("lede", "bone", bodyLevels),
            Triple("secondary button's label", "parchment", bodyLevels),
            Triple("scroll cue's arrow", "parchment", boundaryLevels),
            Triple("focus ring", "brass", boundaryLevels)
        )
        fun keepsEveryLevel(opacity: Int) = panelPairs.all { (_, ink, levels) ->
            val solid = levelOf(ColorVision.contrast(colour(ink), colour("ink")), levels)
            levelOf(ColorVision.contrast(colour(ink), panelOverTheBrightestBand(opacity)), levels) >= solid
        }
        val least = (0..100).first { keepsEveryLevel(it) }
        panelPairs.forEach { (where, ink, levels) ->
            val solid = levelOf(ColorVision.contrast(colour(ink), colour("ink")), levels)
            checkValues("opening panel, $where", "--$ink", colour(ink), "ink $percent% over white",
                panelOverTheBrightestBand(percent), solid)
        }
        assertEquals(
            least, percent,
            "the opening's panel is the ink at $percent%, and the least percent at which every word on it keeps its level over white is $least"
        )
        println("SITE the opening's panel is the ink at $percent%, the least that keeps every word's level over the band's brightest pixel")
    }
}
