package com.cartogenesis.desktop

import com.cartogenesis.cartography.LoadOutcome
import com.cartogenesis.cartography.WorldCodec
import com.cartogenesis.cartography.WorldFormatException
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.fail
import kotlinx.coroutines.runBlocking

/**
 * A save the browser's folder library wrote opens in the desktop's store: the older browser
 * preview still online saves in the format this build reads, and a reader who moves to the desktop
 * brings those worlds along.
 *
 * The browser's save is a real one, written by the folder library in a headless Chrome and kept
 * byte for byte. The browser build is no longer built (docs/DESIGN_LEDGER.md, G1), so it cannot be
 * made again: when the save format moves, the preview's saves stop opening with it, and this test
 * and its fixture go in the same commit.
 */
class FolderInteropTest {

    private val folder: File = Files.createTempDirectory("cartogenesis-interop").toFile()

    @AfterTest
    fun cleanUp() {
        folder.deleteRecursively()
    }

    @Test
    fun `a save the browser's folder library wrote lists, opens and saves again in the desktop's store`() = runBlocking<Unit> {
        val bytes = javaClass.getResourceAsStream("/interop/folder-written.cgw")?.use { it.readBytes() }
            ?: fail("the browser's save is missing")
        val header = try {
            WorldCodec.decodeHeader(bytes)
        } catch (stale: WorldFormatException) {
            fail("the browser's save no longer opens (${stale.detail}); if the save format moved, this test and its fixture go with the browser preview's saves")
        }
        assertEquals("web", header.writtenBy, "this save was not written by the browser")
        assertEquals("gzip", header.compression)

        // Under the name the folder library gave it, which is the desktop's own naming.
        File(folder, "interop-browser.cgw").writeBytes(bytes)
        val store = DesktopWorldStore(folder, GzipCompressor, "a test")
        val entry = store.list().single()
        assertEquals("interop-browser.cgw", entry.key)
        assertNull(entry.refusal, "the browser's save was listed as one that will not open")

        val opened = assertIs<LoadOutcome.Loaded>(store.load(entry.key)).save
        assertEquals("Written by the browser", opened.document.title)
        assertEquals(64 * 32, opened.world.terrain.height.data.size)

        // And the desktop writes it back where the browser will look for it.
        store.save(opened.document.copy(title = "Written back by the desktop"), opened.world, entry.key)
        assertEquals(listOf("interop-browser.cgw"), folder.list()!!.toList())
        assertEquals(
            "Written back by the desktop",
            assertIs<LoadOutcome.Loaded>(store.load(entry.key)).save.document.title
        )
    }
}
