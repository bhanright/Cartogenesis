package com.cartogenesis.desktop

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.cartogenesis.cartography.LoadOutcome
import com.cartogenesis.cartography.WorldDocument
import com.cartogenesis.cartography.WorldLibrary
import com.cartogenesis.ui.CartogenesisApp
import com.cartogenesis.ui.CartogenesisTheme
import com.cartogenesis.ui.FolderChooser
import com.cartogenesis.ui.FolderPermission
import com.cartogenesis.ui.LibraryFolder
import com.cartogenesis.ui.Platform
import com.cartogenesis.ui.RememberedPlace
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.IceSheetAccelerator
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The library pane where the host lets the reader keep the library in a folder, in a running
 * window, with the browser's folder played by a desktop folder whose permission the test sets:
 * what the browser's storage and the folder each hold is real, and only the browser's answers are
 * made up.
 */
class LibraryFolderPaneTest {

    private val root: File = Files.createTempDirectory("cartogenesis-places").toFile()
    private val browserStorage = DesktopWorldStore(File(root, "browser"), GzipCompressor, "a test")
    private val folderStore = DesktopWorldStore(File(root, "Maps"), GzipCompressor, "a test")
    private val world = WorldGenerationEngine.generateBlocking(WorldGenConfig(seed = 4243L, width = 32, height = 32))

    private fun document(title: String) = WorldDocument(id = "w1", title = title, config = world.config, savedAt = 1L)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a remembered folder that needs reconnecting is shown so, and this browser's storage does not stand in`() {
        runBlocking {
            browserStorage.save(document("Kept in the browser"), world)
            folderStore.save(document("Kept in the folder"), world)
        }
        val folder = TestFolder("Maps", folderStore, FolderPermission.PROMPT, answer = FolderPermission.GRANTED)
        val platform = FolderHostPlatform(browserStorage, TestChooser(RememberedPlace(folder, inFolder = true)))
        runDesktopComposeUiTest(width = 1440, height = 900) {
            setContent { CartogenesisTheme(dark = false) { CartogenesisApp(platform) } }
            onNodeWithText("Library").performClick()
            // Whatever the pane settles on, it names a place; then it is asked which.
            waitUntil(timeoutMillis = WAIT_MS) { anyText { it.startsWith("Worlds are kept") || it.startsWith("The library is") } }
            waitForIdle()
            assertTrue(!anyText { it.contains("Kept in the browser") }, "this browser's storage stood in for the folder")
            assertTrue(anyText { it == "Reconnect to \"Maps\"" }, "the folder waiting to be reconnected was not offered")
            onNodeWithText("The library is the folder \"Maps\".").assertExists()
            onNodeWithText("Reconnect to the folder to see its worlds").assertExists()
            onNode(hasText("Save") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).assertIsNotEnabled()
            assertEquals(0, folder.requests, "the reader was asked before they clicked")

            onNodeWithText("Reconnect to \"Maps\"").performClick()
            waitUntil(timeoutMillis = WAIT_MS) { anyText { it.contains("Kept in the folder") } }
            assertEquals(1, folder.requests)
            onNodeWithText("Worlds are kept in the folder \"Maps\" on this computer.").assertExists()
            assertTrue(!anyText { it.contains("Kept in the browser") })
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a world opened from the folder and saved after moving to this browser's storage is a new file there`() {
        runBlocking {
            browserStorage.save(document("A stranger's world under the same name"), world)
            folderStore.save(document("Mine, in the folder"), world)
        }
        val stranger = File(root, "browser/w1.cgw").readBytes()
        val folder = TestFolder("Maps", folderStore, FolderPermission.GRANTED)
        val platform = FolderHostPlatform(browserStorage, TestChooser(RememberedPlace(folder, inFolder = true)))
        runDesktopComposeUiTest(width = 1440, height = 900) {
            setContent { CartogenesisTheme(dark = false) { CartogenesisApp(platform) } }
            onNodeWithText("Library").performClick()
            waitUntil(timeoutMillis = WAIT_MS) { anyText { it.contains("Mine, in the folder") } }
            onNodeWithText("Mine, in the folder").performClick()
            // Opened: the cartouche names the world's seed, and nothing is generating.
            waitUntil(timeoutMillis = WAIT_MS) {
                anyText { it.contains("seed 4243 ·") } && onAllNodesWithText("Stop").fetchSemanticsNodes().isEmpty()
            }

            onNodeWithText("Library").performClick()
            waitUntil(timeoutMillis = WAIT_MS) { onAllNodesWithText("Use this browser's storage").fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Use this browser's storage").performClick()
            waitUntil(timeoutMillis = WAIT_MS) { anyText { it.contains("A stranger's world") } }

            // The pane's own Save, which is the call File ▸ Save makes.
            onNode(hasText("Save") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).performClick()
            // Written somewhere: beside the stranger's file, or over it.
            waitUntil(timeoutMillis = WAIT_MS) {
                (File(root, "browser").list()?.count { it.endsWith(".cgw") } ?: 0) == 2 ||
                    !stranger.contentEquals(File(root, "browser/w1.cgw").readBytes())
            }
        }
        assertContentEquals(stranger, File(root, "browser/w1.cgw").readBytes(), "Save wrote the folder's world over a file in this browser's storage")
        val saved = assertIs<LoadOutcome.Loaded>(runBlocking { browserStorage.load("w1 (2).cgw") }).save
        assertEquals("Mine, in the folder", saved.document.title)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a host with no folder chooser offers no folder, and the pane is as it was`() {
        val platform = FolderHostPlatform(browserStorage, chooser = null)
        runDesktopComposeUiTest(width = 1440, height = 900) {
            setContent { CartogenesisTheme(dark = false) { CartogenesisApp(platform) } }
            onNodeWithText("Library").performClick()
            waitUntil(timeoutMillis = WAIT_MS) { onAllNodesWithText("Nothing saved yet").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(!anyText { it == "Where the worlds are" || it.startsWith("Choose a folder") || it.startsWith("Use this browser") })
            assertTrue(anyText { it.contains("Files live in ${FolderHostPlatform.LOCATION}") })
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.anyText(predicate: (String) -> Boolean): Boolean {
        var found = false
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { if (predicate(it.text)) found = true }
            node.children.forEach(::walk)
        }
        walk(onRoot().fetchSemanticsNode())
        return found
    }

    private companion object {
        /** A 32 world opens and saves in moments; this is room for a loaded machine. */
        const val WAIT_MS = 120_000L
    }
}

/** The desktop standing in for a browser: [library] as its own storage, and [chooser] for folders. */
private class FolderHostPlatform(
    override val library: WorldLibrary,
    private val chooser: FolderChooser?,
    private val desktop: Platform = DesktopPlatform()
) : Platform by desktop {
    override val defaultResolution: Int = 512
    override val libraryLocation: String = LOCATION
    override val iceAccelerator: IceSheetAccelerator? = null
    override val folderChooser: FolderChooser? get() = chooser

    companion object {
        const val LOCATION = "this browser's storage, played by a folder."
    }
}

/** A folder over [library] whose permission, and the answer when asked, the test sets. */
private class TestFolder(
    override val name: String,
    override val library: WorldLibrary,
    private var permission: FolderPermission,
    private val answer: FolderPermission = permission
) : LibraryFolder {
    var requests = 0
        private set

    override suspend fun permission(): FolderPermission = permission

    override suspend fun requestPermission(): FolderPermission {
        requests++
        permission = answer
        return answer
    }

    override suspend fun unreachableBecause(): String? = null
}

/** A chooser that remembers in memory and has no picker to open. */
private class TestChooser(private var stored: RememberedPlace) : FolderChooser {
    override suspend fun pick(): LibraryFolder? = null
    override suspend fun remembered(): RememberedPlace = stored
    override suspend fun remember(place: RememberedPlace) {
        stored = place
    }
}
