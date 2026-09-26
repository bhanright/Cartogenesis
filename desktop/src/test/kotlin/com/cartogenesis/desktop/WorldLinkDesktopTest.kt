package com.cartogenesis.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.WorldLibrary
import com.cartogenesis.ui.CartogenesisApp
import com.cartogenesis.ui.CartogenesisTheme
import com.cartogenesis.ui.Platform
import com.cartogenesis.ui.WorldLinks
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ErosionAccelerator
import com.cartogenesis.worldgen.pipeline.IceSheetAccelerator
import com.cartogenesis.worldgen.pipeline.OceanAccelerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop's half of a link, and the whole path through a running window.
 *
 * The desktop is no page, so the link it copies is the published browser application's, which
 * opens for anybody the link is sent to; and it was opened at no address, so it starts as it always
 * has. The window case stands in for a browser opened at a link — the shared application reads the
 * address through [Platform.openedAt] whichever host answers it — and follows the link to a world on
 * screen, the line about what was set aside, and File's copy of it back out.
 */
class WorldLinkDesktopTest {

    /**
     * The desktop platform with a clipboard that records, and optionally an address. The tests run
     * headless, where AWT's clipboard throws, so the copy is caught here rather than on the
     * machine's own clipboard; the library is empty rather than the reader's own folder, and no
     * graphics device is reached for.
     */
    private class Recording(
        override val openedAt: String? = null,
        /** Small enough that the window's generation is a moment; a link without a size keeps it. */
        override val defaultResolution: Int = WINDOW_CELLS,
        private val desktop: Platform = DesktopPlatform()
    ) : Platform by desktop {
        val copied = mutableListOf<String>()
        override val canCopyToClipboard: Boolean = true
        override fun copyToClipboard(text: String) {
            copied += text
        }

        override val library: WorldLibrary = EmptyWorldLibrary
        override val libraryLocation: String = "a test"
        override val accelerator: ErosionAccelerator? = null
        override val oceanAccelerator: OceanAccelerator? = null
        override val iceAccelerator: IceSheetAccelerator? = null
    }

    @Test
    fun `a link copied on the desktop begins with the published address`() {
        val platform = Recording()
        val line = WorldLinks.copy(platform, WorldGenConfig(seed = 718106L).atResolution(1024, 1024), RenderOptions())
        val link = platform.copied.single()
        println("WORLD LINK copied on the desktop: $link")
        assertTrue(link.startsWith("https://cartogenesis.com/app/?seed=718106#"), link)
        assertEquals(WorldLinks.PUBLIC_APP_ADDRESS, DesktopPlatform().worldLinkBase)
        assertTrue(link in line, line)
    }

    @Test
    fun `the desktop was opened at no address`() {
        assertNull(DesktopPlatform().openedAt)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a window opened at a link makes that world, says what it set aside, and copies it back`() {
        val platform = Recording(openedAt = "${WorldLinks.PUBLIC_APP_ADDRESS}?seed=718106#v=1&plates=9&glaciers=1")
        runDesktopComposeUiTest(width = 1440, height = 900) {
            setContent { CartogenesisTheme(dark = false) { CartogenesisApp(platform) } }
            // Nobody presses Generate: the link is the ask.
            waitUntil(timeoutMillis = WAIT_MS) {
                seedOnTheMap() == 718106L && onAllNodesWithText("Stop").fetchSemanticsNodes().isEmpty()
            }
            waitForIdle()
            // The line is about the world the link made, so the finished generation leaves it up.
            assertTrue(
                onAllNodesWithText("\"glaciers\" is not a setting a link carries", substring = true)
                    .fetchSemanticsNodes().isNotEmpty(),
                "the window does not say what it set aside from the link"
            )

            onNodeWithText("File").performClick()
            waitForIdle()
            onNodeWithText("Copy link to this world").performClick()
            waitForIdle()
        }
        val link = platform.copied.single()
        println("WORLD LINK copied from a window opened at a link: $link")
        assertTrue(link.startsWith("https://cartogenesis.com/app/?seed=718106#v=1&size=$WINDOW_CELLS"), link)
        assertTrue("&plates=9" in link, "the copied link lost the plate count the window was opened with: $link")
        assertTrue("glaciers" !in link, link)
    }

    // ---- a link larger than the window starts at asks first ---------------------------------

    /**
     * A link to a 512 world in a window that starts at 128: above the default, so the window asks
     * and makes nothing until answered. The smaller answer holds the focus, and Enter takes it —
     * the keyboard's way through — and the world it makes is at 128 with the link's plate count.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a link above the default size asks first, and Enter opens it at the default with the link's settings`() {
        val platform = Recording(openedAt = "${WorldLinks.PUBLIC_APP_ADDRESS}?seed=718106#v=1&size=512&plates=9")
        runDesktopComposeUiTest(width = 1440, height = 900) {
            setContent { CartogenesisTheme(dark = false) { CartogenesisApp(platform) } }
            awaitQuestionOrWorld()
            assertTrue(shown(QUESTION_TITLE), "the window made the link's world without asking")
            val question = textOf(QUESTION_LEAD)
            println("LARGE LINK asked on the desktop: $question")
            assertTrue("This link makes a 512 world, larger than the $WINDOW_CELLS" in question, question)
            // Asked and not answered: nothing is made, however long the question stands.
            Thread.sleep(UNANSWERED_MS)
            waitForIdle()
            assertNull(worldOnTheMap(), "a world was made before the question was answered")
            assertTrue(!shown("Stop"), "a generation started before the question was answered")

            onNodeWithText("Open at $WINDOW_CELLS").assertIsFocused()
            onNodeWithText("Open at $WINDOW_CELLS").performKeyInput { pressKey(Key.Enter) }
            waitUntil(timeoutMillis = WAIT_MS) { worldOnTheMap() != null && !shown("Stop") }
            waitForIdle()
            assertEquals(718106L to WINDOW_CELLS, worldOnTheMap())
            assertTrue(!shown(QUESTION_TITLE), "the question is still up after it was answered")

            onNodeWithText("File").performClick()
            waitForIdle()
            onNodeWithText("Copy link to this world").performClick()
            waitForIdle()
        }
        val link = platform.copied.single()
        assertTrue("&size=$WINDOW_CELLS" in link, link)
        assertTrue("&plates=9" in link, "the default size lost the link's plate count: $link")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `make it makes the world at the link's size`() {
        val platform = Recording(openedAt = "${WorldLinks.PUBLIC_APP_ADDRESS}?seed=718106#v=1&size=512&plates=9")
        runDesktopComposeUiTest(width = 1440, height = 900) {
            setContent { CartogenesisTheme(dark = false) { CartogenesisApp(platform) } }
            awaitQuestionOrWorld()
            assertTrue(shown(QUESTION_TITLE), "the window made the link's world without asking")
            onNodeWithText("Make it at 512").performClick()
            waitUntil(timeoutMillis = WAIT_MS) { worldOnTheMap() != null && !shown("Stop") }
            waitForIdle()
            assertEquals(718106L to 512, worldOnTheMap())

            onNodeWithText("File").performClick()
            waitForIdle()
            onNodeWithText("Copy link to this world").performClick()
            waitForIdle()
        }
        val link = platform.copied.single()
        assertTrue("&size=512" in link && "&plates=9" in link, link)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a link at the default size makes its world on arrival without asking`() {
        val platform = Recording(
            openedAt = "${WorldLinks.PUBLIC_APP_ADDRESS}?seed=718106#v=1&size=512&plates=9",
            defaultResolution = 512
        )
        runDesktopComposeUiTest(width = 1440, height = 900) {
            setContent { CartogenesisTheme(dark = false) { CartogenesisApp(platform) } }
            awaitQuestionOrWorld()
            assertTrue(!shown(QUESTION_TITLE), "a link at the default size asked first")
            assertEquals(718106L to 512, worldOnTheMap())
        }
    }

    /**
     * Until the window has either asked or finished a world, whichever it does, so a window that
     * should have asked and did not fails on the assertion that says so rather than on a timeout.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.awaitQuestionOrWorld() {
        waitUntil(timeoutMillis = WAIT_MS) {
            shown(QUESTION_TITLE) || (worldOnTheMap() != null && !shown("Stop"))
        }
        waitForIdle()
    }

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.shown(text: String): Boolean =
        onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    /** The whole of the first text on screen that contains [part]. */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.textOf(part: String): String =
        onAllNodesWithText(part, substring = true).fetchSemanticsNodes().first()
            .config[SemanticsProperties.Text].joinToString("") { it.text }

    /** The seed on the cartouche, as `ImportSaveTest` reads it. */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.seedOnTheMap(): Long? = worldOnTheMap()?.first

    /** The seed and the width on the cartouche, or null while no world is on the map. */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.worldOnTheMap(): Pair<Long, Int>? {
        var found: Pair<Long, Int>? = null
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { text ->
                Regex("""seed (-?\d+) · (\d+) × \d+""").find(text.text)?.let {
                    found = it.groupValues[1].toLong() to it.groupValues[2].toInt()
                }
            }
            node.children.forEach(::walk)
        }
        // Every root, because a dialog is a root of its own beside the window's.
        onAllNodes(isRoot()).fetchSemanticsNodes().forEach(::walk)
        return found
    }

    private companion object {
        const val QUESTION_TITLE = "A large world"
        const val QUESTION_LEAD = "This link makes a"

        /**
         * How long an unanswered question is left standing before the test looks for a world: a
         * 128 world is made in well under this on the test machine, so a window that generated
         * behind the question would have a world on its map by now.
         */
        const val UNANSWERED_MS = 3_000L

        /** No link names so small a world; the window starts at it only because this one names none. */
        const val WINDOW_CELLS = 128

        /** A 128 world is made in moments; this is room for a loaded machine. */
        const val WAIT_MS = 120_000L
    }
}
