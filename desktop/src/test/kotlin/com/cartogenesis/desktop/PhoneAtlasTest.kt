package com.cartogenesis.desktop

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.cartogenesis.ui.CartogenesisApp
import com.cartogenesis.ui.CartogenesisTheme
import com.cartogenesis.ui.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F8: that the atlas on a phone can be got out of again.
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
                "F8 Map button at ${bounds.left}, ${bounds.top} to ${bounds.right}, " +
                    "${bounds.bottom} in a ${PHONE_WIDTH}x$PHONE_HEIGHT viewport"
            )
            assertTrue(
                bounds.left >= 0f && bounds.top >= 0f &&
                    bounds.right <= PHONE_WIDTH.toFloat() && bounds.bottom <= PHONE_HEIGHT.toFloat(),
                "the Map button is outside the viewport: $bounds"
            )

            val strips = onAllNodesWithContentDescription(MAP_TOOLBAR).fetchSemanticsNodes()
            println("F8 map toolbars drawn over the atlas: ${strips.size}")
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
     * The wide arrangement's own version of the same question, which F8 expected to find already
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

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.theAtlasButton() = onNode(ATLAS_BUTTON)

    private companion object {
        /**
         * The header's Atlas button, and not the map style that is also called Atlas.
         *
         * The default style's name is Atlas and both toolbars print it — the wide one as a cell in
         * the segmented row, the compact one on the style menu's button — so in either arrangement
         * there are two nodes reading "Atlas" that do opposite things. What tells them apart is
         * that one is a button and the other is not: Material gives every button `Role.Button`,
         * while a cell on the strip is a bare clickable with no role, because a segmented key is a
         * key rather than a row of buttons.
         */
        val ATLAS_BUTTON = hasText("Atlas") and
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

        /** An iPhone 14's viewport in CSS pixels, which is the size F5 was drawn against. */
        const val PHONE_WIDTH = 390
        const val PHONE_HEIGHT = 844
        const val WIDE_WIDTH = 1440
        const val WIDE_HEIGHT = 900

        /** A full 512 world on the CPU, on whatever machine is running the tests. */
        const val GENERATION_TIMEOUT_MS = 300_000L

        /**
         * What the two map strips call themselves. Spelled out rather than imported, because the
         * constant is internal to `:ui` and this is the interface as the outside sees it — the same
         * argument `ChromeGalleryTest` makes about writing the panel's copy out by hand.
         */
        const val MAP_TOOLBAR = "Map toolbar"
    }
}

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
