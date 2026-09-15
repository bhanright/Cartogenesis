package com.cartogenesis.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.cartogenesis.ui.CartogenesisApp
import com.cartogenesis.ui.CartogenesisTheme
import com.cartogenesis.ui.Platform
import com.cartogenesis.ui.ThemeChoice
import java.io.File
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * That the atlas on a phone can be got out of again.
 *
 * William opened it on his phone against 2.0.0 and could not close it: "it opens up an atlas menu
 * that becomes hidden by the top transparent menu screen and isn't navigable so it's impossible to
 * close". Both halves of that are one mistake. The compact arrangement draws the map's translucent
 * toolbar over whatever the pane is showing — which is right over a chart and is a lid over a page
 * of text — and the only button that goes back to the map lives in the header, which in this
 * arrangement is inside the pull-up sheet. So the atlas opens under a strip that belongs to the
 * map, and the way out is behind a sheet the reader has no reason to think holds it.
 *
 * The guard is therefore two claims, and both are asked of a real composition at a phone's size
 * rather than of a declaration: there is a Map button, it is enabled and it is on the screen; and
 * the map's toolbar is not drawn over the atlas. Against 2.0.0 the first fails because no such
 * button exists anywhere and the second fails because the strip is there.
 *
 * The third claim is the one William actually cared about: pressing the button gets you back.
 */
class PhoneAtlasTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the atlas on a phone carries a way back to the map`() {
        runDesktopComposeUiTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
            setContent {
                val platform = PhonePlatform()
                CartogenesisTheme(dark = false, coarsePointer = platform.coarsePointer) {
                    CartogenesisApp(platform)
                }
            }
            waitForIdle()

            // The reader's own route: the sheet is where Generate and Atlas both live now.
            onNodeWithText("Settings").performClick()
            waitForIdle()
            onNodeWithText("Generate").performClick()
            waitUntil(timeoutMillis = GENERATION_TIMEOUT_MS) {
                onAllNodesWithText("512 × 512", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            waitForIdle()
            // Scrolled to first: the header is a scrolling column and a click on a node whose
            // centre is off the bottom of the sheet lands on nothing.
            theAtlasButton().performScrollTo().performClick()
            waitForIdle()

            // The atlas is up, and the sheet has gone back down behind it — which is exactly the
            // state William was stuck in.
            assertTrue(
                onAllNodesWithText("Atlas settings").fetchSemanticsNodes().isNotEmpty(),
                "the Atlas button did not open the atlas"
            )

            val back = onNodeWithText("Map")
            back.assertIsEnabled()
            back.assertIsDisplayed()
            val bounds = back.fetchSemanticsNode().boundsInRoot
            println(
                "PHONE Map button at ${bounds.left}, ${bounds.top} to ${bounds.right}, " +
                    "${bounds.bottom} in a ${PHONE_WIDTH}x$PHONE_HEIGHT viewport"
            )
            assertTrue(
                bounds.left >= 0f && bounds.top >= 0f &&
                    bounds.right <= PHONE_WIDTH.toFloat() && bounds.bottom <= PHONE_HEIGHT.toFloat(),
                "the Map button is outside the viewport: $bounds"
            )

            val strips = onAllNodesWithContentDescription(MAP_TOOLBAR).fetchSemanticsNodes()
            println("PHONE map toolbars drawn over the atlas: ${strips.size}")
            assertEquals(
                0,
                strips.size,
                "the map's toolbar is still drawn over the atlas, which is the lid William hit"
            )

            // And the drill-down, which is what the atlas does instead of a 320 dp list beside a
            // 70 dp page when the window is this narrow: the list is the screen until a realm is
            // picked, then the realm is, with the list one press away.
            val realms = onAllNodes(REALM_ROW)
            assertTrue(
                realms.fetchSemanticsNodes().isNotEmpty(),
                "a 512 world produced no realms to drill into"
            )
            realms[0].performClick()
            waitForIdle()
            assertEquals(
                0,
                onAllNodes(REALM_ROW).fetchSemanticsNodes().size,
                "the list of realms is still taking the screen behind the realm's own page"
            )
            onNodeWithText("← Realms").performClick()
            waitForIdle()
            assertTrue(
                onAllNodes(REALM_ROW).fetchSemanticsNodes().isNotEmpty(),
                "there was no way back from a realm to the list"
            )

            back.performClick()
            waitForIdle()
            assertTrue(
                onAllNodesWithContentDescription(MAP_TOOLBAR).fetchSemanticsNodes().isNotEmpty(),
                "pressing Map did not bring the map back"
            )
        }
    }

    /**
     * The library is the atlas's twin here — the other whole screen the map's chrome must let go
     * of — so it gets the same two questions and none of the commentary.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the library on a phone carries a way back to the map`() {
        runDesktopComposeUiTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
            setContent {
                val platform = PhonePlatform()
                CartogenesisTheme(dark = false, coarsePointer = platform.coarsePointer) {
                    CartogenesisApp(platform)
                }
            }
            waitForIdle()
            onNodeWithText("Settings").performClick()
            waitForIdle()
            onNodeWithText("Library").performScrollTo().performClick()
            waitForIdle()

            val back = onNodeWithText("Map")
            back.assertIsEnabled()
            back.assertIsDisplayed()
            assertEquals(
                0,
                onAllNodesWithContentDescription(MAP_TOOLBAR).fetchSemanticsNodes().size,
                "the map's toolbar is still drawn over the library"
            )
        }
    }

    /**
     * The wide arrangement's own version of the same question, which was expected to be already
     * answered: the header there is always on screen, so "Show map" is never hidden, and the strips
     * are already withheld from anything that is not the map. Written down because "we checked"
     * is worth less than a test that fails if somebody stops it being true.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a wide window draws no map toolbar over the atlas either`() {
        runDesktopComposeUiTest(width = WIDE_WIDTH, height = WIDE_HEIGHT) {
            setContent {
                CartogenesisTheme(dark = false) { CartogenesisApp(DesktopAt512()) }
            }
            waitForIdle()
            theAtlasButton().performScrollTo().performClick()
            waitForIdle()
            assertEquals(
                0,
                onAllNodesWithContentDescription(MAP_TOOLBAR).fetchSemanticsNodes().size,
                "the wide arrangement draws the map's toolbar over the atlas"
            )
            onNodeWithText("Show map").assertIsEnabled()
        }
    }

    /**
     * That every chrome draws the panes' own words in an ink you can see.
     *
     * The library's two headings, "This world" and "Saved worlds", came out very nearly invisible
     * in the dark chromes: a heading that asks for no colour takes `LocalContentColor`, Material's
     * default for that is black, and the panes are the one part of this application drawn straight
     * onto a painted background rather than inside a `Surface` — so nothing had ever told them what
     * ink the ground they lie on wants. Everything around them was fine, which is why it survived
     * two rounds of review: the name field, the buttons and the saved-world cards are all Surfaces
     * or set their own colour, and in the light chromes black on paper is very nearly right.
     *
     * Measured rather than asserted by eye, and measured off the pixels rather than off the scheme:
     * the question is what was *drawn*, not what the scheme would have supplied if anything had
     * asked it. The heading's own bounding box holds ink and ground and nothing else, so the
     * contrast between its lightest and darkest pixel is the contrast the reader gets. 4.5:1 is
     * WCAG AA for text at this size, and is the bar `ChromeContrastTest` holds the schemes to.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `every chrome draws the library's headings legibly`() {
        val illegible = mutableListOf<String>()
        val measured = mutableMapOf<String, String>()
        ThemeChoice.entries.forEach { choice ->
            listOf("light" to false, "dark" to true).forEach { (tone, dark) ->
                val ratio = libraryHeadingContrast(choice, dark)
                measured["${choice.name}-$tone"] = ratio.round()
                if (ratio < LEGIBLE) illegible += "${choice.name} in $tone at ${ratio.round()}:1"
            }
        }
        println("PHONE library heading contrast $measured")
        assertTrue(
            illegible.isEmpty(),
            "the library's heading is below $LEGIBLE:1 against its own ground: $illegible"
        )
    }

    /**
     * The same question of the realm's own page, which is the other text the panes draw bare.
     *
     * `NationDetail` is a scrolling column, not a `Surface`, so its heading and its paragraphs of
     * geography took the same default black. The chromes here are the dark ones a reader would
     * actually meet it in; one world apiece, because there is no realm to open without one.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the dark chromes draw a realm's page legibly`() {
        val illegible = mutableListOf<String>()
        val measured = mutableMapOf<String, String>()
        DARK_CHROMES.forEach { choice ->
            val ratio = realmPageContrast(choice)
            measured[choice.name] = ratio.round()
            if (ratio < LEGIBLE) illegible += "${choice.name} at ${ratio.round()}:1"
        }
        println("PHONE realm page contrast $measured")
        assertTrue(
            illegible.isEmpty(),
            "a realm's page is below $LEGIBLE:1 against its own ground: $illegible"
        )
    }

    /** The library open on a phone in one chrome, and what "This world" is drawn in. */
    @OptIn(ExperimentalTestApi::class)
    private fun libraryHeadingContrast(choice: ThemeChoice, dark: Boolean): Double {
        var ratio = 0.0
        runDesktopComposeUiTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
            val platform = PhonePlatform()
            setContent {
                CartogenesisTheme(
                    dark = dark,
                    choice = choice,
                    coarsePointer = platform.coarsePointer
                ) { CartogenesisApp(platform) }
            }
            waitForIdle()
            onNodeWithText("Settings").performClick()
            waitForIdle()
            onNodeWithText("Library").performScrollTo().performClick()
            waitForIdle()
            ratio = contrastAcross(onNodeWithText("This world"))
        }
        return ratio
    }

    /** A realm opened on a phone in one chrome, and what its "Geography" heading is drawn in. */
    @OptIn(ExperimentalTestApi::class)
    private fun realmPageContrast(choice: ThemeChoice): Double {
        var ratio = 0.0
        runDesktopComposeUiTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
            val platform = PhonePlatform()
            setContent {
                CartogenesisTheme(
                    dark = true,
                    choice = choice,
                    coarsePointer = platform.coarsePointer
                ) { CartogenesisApp(platform) }
            }
            waitForIdle()
            onNodeWithText("Settings").performClick()
            waitForIdle()
            onNodeWithText("Generate").performClick()
            waitUntil(timeoutMillis = GENERATION_TIMEOUT_MS) {
                onAllNodesWithText("512 × 512", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            waitForIdle()
            theAtlasButton().performScrollTo().performClick()
            waitForIdle()
            onAllNodes(REALM_ROW)[0].performClick()
            waitForIdle()
            // Below the fold on a 390 dp page, and a box that is off the bottom of the window is a
            // box the capture does not contain.
            onNodeWithText("Geography").performScrollTo()
            waitForIdle()
            ratio = contrastAcross(onNodeWithText("Geography"))
        }
        return ratio
    }

    /**
     * The graticule on a phone: it can be turned on from the sheet, and it reaches the map.
     *
     * The toggle is in the Cartography section, which rolls up like every other, so the route is
     * the reader's own: pull the sheet up, generate, unroll Cartography, flip Graticule, put the
     * sheet away and look. Two claims a capture can make and a declaration cannot — that the switch
     * is reachable at 390 dp, and that flipping it changes the picture rather than only the state —
     * plus the third, that the legend prints the scale the sheet is at.
     *
     * The shot is written out because the graticule is the one thing here whose worth is a matter
     * of looking: whether ten degrees is fine enough to place a coast by and coarse enough not to
     * bury one, at the size a phone shows a whole world.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a phone can turn the graticule on and see it`() {
        val dir = File("build/screens").apply { mkdirs() }
        runDesktopComposeUiTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
            val platform = PhonePlatform()
            setContent {
                CartogenesisTheme(dark = false, coarsePointer = platform.coarsePointer) {
                    CartogenesisApp(platform)
                }
            }
            waitForIdle()
            onNodeWithText("Settings").performClick()
            waitForIdle()
            onNodeWithText("Generate").performClick()
            waitUntil(timeoutMillis = GENERATION_TIMEOUT_MS) {
                onAllNodesWithText("512 × 512", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            waitForIdle()
            onNodeWithText("Settings").performClick()
            waitForIdle()
            val plain = captureRoot()
            File(dir, "f14-phone-plain.png").writeBytes(plain.png)

            onNodeWithText("Settings").performClick()
            waitForIdle()
            onNodeWithText("Cartography").performScrollTo().performClick()
            waitForIdle()
            val toggle = onNodeWithContentDescription("Graticule")
            toggle.performScrollTo()
            toggle.assertIsEnabled()
            toggle.performClick()
            waitForIdle()
            onNodeWithText("Settings").performClick()
            waitForIdle()
            // The redraw runs off the composition on a background dispatcher, so the frame the
            // switch was flipped on is not yet the frame that carries the graticule.
            waitUntil(timeoutMillis = GENERATION_TIMEOUT_MS) {
                captureRoot().fingerprint != plain.fingerprint
            }
            val figured = captureRoot()
            File(dir, "f14-phone-graticule.png").writeBytes(figured.png)

            println(
                "PHONE graticule at ${PHONE_WIDTH}x$PHONE_HEIGHT: plain ${plain.fingerprint}, " +
                    "with the graticule ${figured.fingerprint}, written to ${dir.absolutePath}"
            )
            assertTrue(
                figured.fingerprint != plain.fingerprint,
                "turning the graticule on changed nothing on the phone's screen"
            )
            // And the legend says what scale the sheet is at, which is the other half of it.
            assertTrue(
                onAllNodesWithText("km per pixel", substring = true).fetchSemanticsNodes()
                    .isNotEmpty(),
                "the cartouche does not print the map's scale"
            )
        }
    }

    /** The whole window as a PNG and a fingerprint of it, for the two shots above. */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.captureRoot(): PhoneShot {
        val image = onRoot().captureToImage()
        val pixels = image.toPixelMap()
        var fingerprint = 17
        for (y in 0 until pixels.height step 3) {
            for (x in 0 until pixels.width step 3) {
                fingerprint = fingerprint * 31 + pixels[x, y].toArgb()
            }
        }
        val bytes = Image.makeFromBitmap(image.asSkiaBitmap())
            .encodeToData(EncodedImageFormat.PNG)!!.bytes
        return PhoneShot(bytes, fingerprint)
    }

    private class PhoneShot(val png: ByteArray, val fingerprint: Int)

    /**
     * The contrast between the lightest and the darkest pixel of one piece of text.
     *
     * A text node's bounds are tight around its glyphs, so the box holds the ink, the ground it is
     * printed on and the antialiasing between them. The extremes of that are therefore the pair the
     * reader is actually reading, whatever either of them turns out to be — which is the point:
     * this measures what was drawn and not what some scheme says it should have been.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.contrastAcross(node: SemanticsNodeInteraction): Double {
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val pixels = onRoot().captureToImage().toPixelMap()
        var lightest = -1.0
        var darkest = 2.0
        for (y in bounds.top.toInt().coerceAtLeast(0) until
            bounds.bottom.toInt().coerceAtMost(pixels.height)) {
            for (x in bounds.left.toInt().coerceAtLeast(0) until
                bounds.right.toInt().coerceAtMost(pixels.width)) {
                val luminance = relativeLuminance(pixels[x, y])
                if (luminance > lightest) lightest = luminance
                if (luminance < darkest) darkest = luminance
            }
        }
        check(lightest >= 0.0) { "the text's box is outside the window: $bounds" }
        return (lightest + 0.05) / (darkest + 0.05)
    }

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.theAtlasButton() = onNode(ATLAS_BUTTON)

    private companion object {
        /**
         * The header's World atlas button, and not the map style that is also called Atlas.
         *
         * The copy pass of 2026-09-15 gave the button its own words for exactly this reason — the
         * default style's name is Atlas and both toolbars print it, so the two used to be the same
         * word doing opposite things. The role is still asserted beside the text: Material gives
         * every button `Role.Button`, while a cell on the style strip is a bare clickable with no
         * role, and a guard that leaned on the wording alone would go quiet the next time the
         * wording moved.
         */
        val ATLAS_BUTTON = hasText("World atlas") and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

        /**
         * One realm in the atlas's list.
         *
         * Realm names are generated, so there is no string to look for; what every row does have is
         * a click and a second line reading "<government> · <population>", and nothing else on the
         * atlas has both. The cartouche uses the same separator and is not on this screen, and is
         * not clickable in any case.
         */
        val REALM_ROW = hasText(" · ", substring = true) and hasClickAction()

        /** An iPhone 14's viewport in CSS pixels: the size the compact arrangement is drawn for. */
        const val PHONE_WIDTH = 390
        const val PHONE_HEIGHT = 844
        const val WIDE_WIDTH = 1440
        const val WIDE_HEIGHT = 900

        /** A full 512 world on the CPU, on whatever machine is running the tests. */
        const val GENERATION_TIMEOUT_MS = 300_000L

        /** WCAG AA for text at the size a heading in these panes is set. */
        const val LEGIBLE = 4.5

        /** The dark chromes a reader would meet a dark pane in, which is where this went wrong. */
        val DARK_CHROMES = listOf(
            ThemeChoice.DARK,
            ThemeChoice.MIDNIGHT,
            ThemeChoice.MATRIX,
            ThemeChoice.HITCHCOCK,
            ThemeChoice.LEMON_BLUEBERRY,
            ThemeChoice.BLACKLIGHT
        )

        /**
         * What the two map strips call themselves. Spelled out rather than imported, because the
         * constant is internal to `:ui` and this is the interface as the outside sees it — the same
         * argument `ChromeGalleryTest` makes about writing the panel's copy out by hand.
         */
        const val MAP_TOOLBAR = "Map toolbar"
    }
}

/** sRGB relative luminance, WCAG 2.1's definition, which is what a contrast ratio is built from. */
private fun relativeLuminance(colour: Color): Double {
    fun channel(value: Float): Double {
        val v = value.toDouble()
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(colour.red) +
        0.7152 * channel(colour.green) +
        0.0722 * channel(colour.blue)
}

/** Two decimals, for a ratio printed in a report rather than compared with anything. */
private fun Double.round(): String = ((this * 100).roundToInt() / 100.0).toString()

/**
 * The desktop reporting a fingertip and a phone's working resolution.
 *
 * `ChromeGalleryTest` has one of these too and cannot share it: a private top-level class belongs
 * to its file, and two files in one package cannot both compile a class of the same name.
 */
private class PhonePlatform(private val desktop: Platform = DesktopPlatform()) :
    Platform by desktop {
    override val defaultResolution: Int = 512
    override val coarsePointer: Boolean = true
}

/** The real desktop, started small, for the one test here that is about a wide window. */
private class DesktopAt512(private val desktop: Platform = DesktopPlatform()) :
    Platform by desktop {
    override val defaultResolution: Int = 512
}
