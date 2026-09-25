package com.cartogenesis.desktop

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.cartogenesis.cartography.WorldDocument
import com.cartogenesis.cartography.WorldLibrary
import com.cartogenesis.ui.CartogenesisApp
import com.cartogenesis.ui.CartogenesisTheme
import com.cartogenesis.ui.Platform
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.IceSheetAccelerator
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

/**
 * That a save pressed in the library on a phone says what became of it, in the library.
 *
 * The author chose a folder in Chrome on his phone and pressed Save, and nothing seemed to happen:
 * no world appeared in the list and nothing said why. The application reported a save only on the
 * status line, which is in the panel's header, and in the compact arrangement the header is inside
 * the settings sheet, which is down whenever the library is up. So a save that failed, and one
 * still being written, both looked like nothing at all.
 *
 * Asked of a real composition at a phone's size, with the library played by a desktop folder whose
 * save the test makes fail, or hold, as a browser's might: the failure's reason — the browser's own
 * exception name with it — and the save under way must each be on the screen, in the pane.
 */
class PhoneLibraryOutcomeTest {

    private val root: File = Files.createTempDirectory("cartogenesis-phone-library").toFile()
    private val store = DesktopWorldStore(File(root, "Maps"), GzipCompressor, "a test")
    private val world = WorldGenerationEngine.generateBlocking(WorldGenConfig(seed = 4243L, width = 32, height = 32))

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a save that fails on a phone says why in the library`() {
        runBlocking { store.save(WorldDocument(id = "w1", title = "Kept", config = world.config, savedAt = 1L), world) }
        val refusing = object : WorldLibrary by store {
            override suspend fun save(document: WorldDocument, world: WorldMap, key: String?): String =
                throw IllegalStateException("this browser no longer has leave to use the folder ($REFUSAL)")
        }
        runDesktopComposeUiTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
            setContent { PhoneApp(refusing) }
            openTheWorldAndComeBack()

            theSaveButton().performClick()
            // Long enough for the failure to have been said somewhere, the status line included.
            waitUntil(timeoutMillis = WAIT_MS) { onAllNodesWithText(REFUSAL, substring = true).fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()
            val reason = assertNotNull(shown("Could not save \"Kept\""), "the failed save said nothing on the screen")
            assertTrue(
                reason.config[SemanticsProperties.Text].any { REFUSAL in it.text },
                "the browser's own name for the failure was left out of the line on the screen"
            )
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a save still being written on a phone shows so in the library, then says it is saved`() {
        runBlocking { store.save(WorldDocument(id = "w1", title = "Kept", config = world.config, savedAt = 1L), world) }
        val released = CompletableDeferred<Unit>()
        val slow = object : WorldLibrary by store {
            override suspend fun save(document: WorldDocument, world: WorldMap, key: String?): String {
                released.await()
                return store.save(document, world, key)
            }
        }
        runDesktopComposeUiTest(width = PHONE_WIDTH, height = PHONE_HEIGHT) {
            setContent { PhoneApp(slow) }
            openTheWorldAndComeBack()

            theSaveButton().performClick()
            waitForIdle()
            assertTrue(shown("Saving \"Kept\"") != null, "a save still being written showed nothing on the screen")

            released.complete(Unit)
            waitUntil(timeoutMillis = WAIT_MS) { onAllNodesWithText("Saved \"Kept\"").fetchSemanticsNodes().isNotEmpty() }
            waitForIdle()
            assertTrue(shown("Saved \"Kept\"") != null, "the finished save said nothing on the screen")
            assertTrue(shown("Saving \"Kept\"") == null, "the finished save still showed as under way")
        }
    }

    /**
     * The first node whose text holds [fragment] and that is drawn on the screen, inside the phone's
     * window; null when there is none. A line composed out of sight — the status line in a sheet
     * that is down — does not count, which is the whole of the defect.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.shown(fragment: String): SemanticsNode? {
        val matches = onAllNodesWithText(fragment, substring = true)
        return matches.fetchSemanticsNodes().indices.firstNotNullOfOrNull { index ->
            val match = matches[index]
            val node = match.fetchSemanticsNode()
            val bounds = node.boundsInRoot
            val inside = bounds.left >= 0f && bounds.top >= 0f && bounds.right <= PHONE_WIDTH && bounds.bottom <= PHONE_HEIGHT
            node.takeIf { inside && match.isDisplayed() }
        }
    }

    @androidx.compose.runtime.Composable
    private fun PhoneApp(library: WorldLibrary) {
        val platform = PhoneLibraryPlatform(library)
        CartogenesisTheme(dark = false, coarsePointer = platform.coarsePointer) { CartogenesisApp(platform) }
    }

    /**
     * The reader's own route on a phone: the library from the sheet, the saved world opened from
     * its row, which puts the map up, and the library again, where Save is.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.openTheWorldAndComeBack() {
        toTheLibrary()
        waitUntil(timeoutMillis = WAIT_MS) { onAllNodesWithText("Kept").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Kept").performClick()
        // Opened, and nothing generating: the cartouche over the map names the world's seed.
        waitUntil(timeoutMillis = WAIT_MS) {
            onAllNodesWithText("seed 4243 ·", substring = true).fetchSemanticsNodes().isNotEmpty() &&
                onAllNodesWithText("Stop").fetchSemanticsNodes().isEmpty()
        }
        waitForIdle()
        toTheLibrary()
        waitUntil(timeoutMillis = WAIT_MS) {
            onAllNodes(saveButton).fetchSemanticsNodes().any { SemanticsProperties.Disabled !in it.config }
        }
    }

    /** The sheet pulled up, and the header's Library button in it: not the bar over the library, which has the same word. */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.toTheLibrary() {
        onNodeWithText("Settings").performClick()
        waitForIdle()
        onNode(button("Library")).performScrollTo().performClick()
        waitForIdle()
    }

    private fun button(words: String) = hasText(words) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private val saveButton = button("Save")

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.theSaveButton() = onNode(saveButton)

    private companion object {
        const val PHONE_WIDTH = 390
        const val PHONE_HEIGHT = 844

        /** A 32 world opens and saves in moments; this is room for a loaded machine. */
        const val WAIT_MS = 120_000L

        /** What Chrome says when a page's leave to write a folder is not there, as `String(error)` gives it. */
        const val REFUSAL = "NotAllowedError: The request is not allowed by the user agent or the platform in the current context."
    }
}

/** A phone-sized host whose library is [library]. */
private class PhoneLibraryPlatform(override val library: WorldLibrary, private val desktop: Platform = DesktopPlatform()) :
    Platform by desktop {
    override val defaultResolution: Int = 512
    override val coarsePointer: Boolean = true
    override val libraryLocation: String = "a folder the test made."
    override val iceAccelerator: IceSheetAccelerator? = null
}
