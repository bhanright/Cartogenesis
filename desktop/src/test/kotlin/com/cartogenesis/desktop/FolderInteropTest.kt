package com.cartogenesis.desktop

import com.cartogenesis.cartography.LoadOutcome
import com.cartogenesis.cartography.WorldCodec
import com.cartogenesis.cartography.WorldFormatException
import java.io.File
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking

/**
 * One folder, two front ends: a save the browser's folder library wrote opens in the desktop's
 * store, and the save of the desktop's store that the browser's `FolderInteropTest` opens is one this
 * build's store writes and reads.
 *
 * The browser's save is a real one, written by the folder library in a headless Chrome and kept
 * byte for byte; the browser's side of this is in `:web`'s `FolderInteropTest`, and how both saves
 * are made again when the format moves is in [RegenerateInteropFixtures].
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
            ?: fail("the browser's save is missing; see RegenerateInteropFixtures")
        val header = try {
            WorldCodec.decodeHeader(bytes)
        } catch (stale: WorldFormatException) {
            fail("the browser's save no longer opens (${stale.detail}); regenerate it: see RegenerateInteropFixtures")
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
        assertEquals(32 * 32, opened.world.terrain.height.data.size)

        // And the desktop writes it back where the browser will look for it.
        store.save(opened.document.copy(title = "Written back by the desktop"), opened.world, entry.key)
        assertEquals(listOf("interop-browser.cgw"), folder.list()!!.toList())
        assertEquals(
            "Written back by the desktop",
            assertIs<LoadOutcome.Loaded>(store.load(entry.key)).save.document.title
        )
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `the desktop's save the browser opens is one this build's store wrote and reads`() = runBlocking<Unit> {
        val source = File(RegenerateInteropFixtures.DESKTOP_SAVE_SOURCE)
        assertTrue(source.isFile, "${source.path} is missing; see RegenerateInteropFixtures")
        val base64 = Regex("\"([A-Za-z0-9+/=]+)\"").findAll(source.readText()).joinToString("") { it.groupValues[1] }
        val bytes = Base64.decode(base64)
        val header = try {
            WorldCodec.decodeHeader(bytes)
        } catch (stale: WorldFormatException) {
            fail("the desktop's save the browser opens no longer opens (${stale.detail}); regenerate it: see RegenerateInteropFixtures")
        }
        assertTrue(header.writtenBy.startsWith("desktop"), "the save the browser opens was written by ${header.writtenBy}")

        File(folder, "${RegenerateInteropFixtures.DESKTOP_SAVE_ID}.cgw").writeBytes(bytes)
        val opened = assertIs<LoadOutcome.Loaded>(DesktopWorldStore(folder, GzipCompressor, "a test").load("${RegenerateInteropFixtures.DESKTOP_SAVE_ID}.cgw"))
        assertEquals(RegenerateInteropFixtures.DESKTOP_SAVE_SEED, opened.save.document.config.seed)
    }
}
