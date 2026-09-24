package com.cartogenesis.desktop

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.cartogenesis.cartography.ByteArraySource
import com.cartogenesis.cartography.LoadOutcome
import com.cartogenesis.cartography.WorldCodec
import com.cartogenesis.cartography.WorldDocument
import com.cartogenesis.cartography.WorldLibrary
import com.cartogenesis.ui.CartogenesisApp
import com.cartogenesis.ui.CartogenesisTheme
import com.cartogenesis.ui.Platform
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.IceSheetAccelerator
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

/**
 * A world brought in from a file, saved without a change, lands beside the library's own copy of
 * it and never over it.
 *
 * The file carries the id it was saved under, which is the id of the library's copy when the file
 * is that copy downloaded and brought back. The opened document kept that id with nowhere saved,
 * and its first Save was filed as `<id>.cgw` — the library's copy. Run in a window, through the
 * library pane's Upload and File ▸ Save, into a real folder.
 */
class ImportSaveTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `importing a copy of a library save and saving it leaves both files whole`() {
        val folder = Files.createTempDirectory("cartogenesis-import").toFile()
        try {
            val store = DesktopWorldStore(folder, GzipCompressor, "a test")
            val world = WorldGenerationEngine.generateBlocking(WorldGenConfig(seed = 4242L, width = 32, height = 32))
            val document = WorldDocument(id = "kept", title = "The library's copy", config = world.config, savedAt = 1L)
            runBlocking { store.save(document, world) }
            val libraryCopy = File(folder, "kept.cgw").readBytes()

            runDesktopComposeUiTest(width = 1440, height = 900) {
                setContent { CartogenesisTheme(dark = false) { CartogenesisApp(ImportingPlatform(store, libraryCopy)) } }
                onNodeWithText("Library").performClick()
                waitForIdle()
                onNodeWithText("Upload a file").performClick()
                waitUntil(timeoutMillis = WAIT_MS) { seedOnTheMap() == 4242L && onAllNodesWithText("Stop").fetchSemanticsNodes().isEmpty() }

                onNodeWithText("File").performClick()
                waitForIdle()
                onNodeWithText("Save").performClick()
                waitUntil(timeoutMillis = WAIT_MS) { (folder.list()?.count { it.endsWith(".cgw") } ?: 0) == 2 }
            }

            assertContentEquals(libraryCopy, File(folder, "kept.cgw").readBytes(), "the import's Save wrote over the library's copy")
            val saved = folder.list()!!.single { it.endsWith(".cgw") && it != "kept.cgw" }
            val reopened = assertIs<LoadOutcome.Loaded>(runBlocking { store.load(saved) }).save
            assertEquals("The library's copy", reopened.document.title)
        } finally {
            folder.deleteRecursively()
        }
    }

    /** The seed on the cartouche, as `SeedFieldTest` reads it. */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.seedOnTheMap(): Long? {
        var found: Long? = null
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { text ->
                Regex("""seed (-?\d+) · \d+ × \d+""").find(text.text)?.let { found = it.groupValues[1].toLong() }
            }
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

/** The desktop, with [library] as its library and an upload that hands back [file]. */
private class ImportingPlatform(
    override val library: WorldLibrary,
    private val file: ByteArray,
    private val desktop: Platform = DesktopPlatform()
) : Platform by desktop {
    override val defaultResolution: Int = 512
    override val libraryLocation: String = "a test's folder"
    override val iceAccelerator: IceSheetAccelerator? = null
    override val supportsFileTransfer: Boolean = true
    override suspend fun uploadWorld(): LoadOutcome = WorldCodec.open(ByteArraySource(file), GzipCompressor)
}
