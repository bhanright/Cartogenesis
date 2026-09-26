package com.cartogenesis.web

import com.cartogenesis.cartography.LibraryKeys
import com.cartogenesis.cartography.LoadOutcome
import com.cartogenesis.cartography.WorldCodec
import com.cartogenesis.cartography.WorldDocument
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/**
 * One folder, two front ends, from the browser's side: a save the desktop's store wrote lists and
 * opens from the folder library, and the file the folder library writes is exactly the save the
 * codec makes, under the name the desktop's store would give it — which the desktop's own
 * `FolderInteropTest` then opens, from a copy this test printed. How both saves are made again when
 * the format moves is in the desktop's `RegenerateInteropFixtures`.
 */
class FolderInteropTest {

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `a save the desktop's store wrote lists and opens from the folder`() = runTest(timeout = 5.minutes) {
        val bytes = Base64.decode(DESKTOP_WRITTEN_SAVE_BASE64)
        assertTrue(WorldCodec.decodeHeader(bytes).writtenBy.startsWith("desktop"), "the fixture was not written by the desktop")
        withTestFolder("from-desktop") { folder ->
            folder.writeRaw("interop-desktop.cgw", bytes)
            val library = FolderWorldLibrary(folder.handle, WebGzipCompressor, "a test")
            val entry = library.list().single()
            assertEquals("interop-desktop.cgw", entry.key)
            assertNull(entry.refusal, "the desktop's save was listed as one that will not open")
            val opened = assertIs<LoadOutcome.Loaded>(library.load(entry.key)).save
            assertEquals("Written by the desktop", opened.document.title)
            assertEquals(4096L, opened.document.config.seed)

            // Saved again from the browser, it stays the desktop's file, under the desktop's name.
            library.save(opened.document.copy(title = "Written back by the browser"), opened.world, entry.key)
            assertEquals(listOf("interop-desktop.cgw"), folder.entries())
            assertEquals("Written back by the browser", assertIs<LoadOutcome.Loaded>(library.load(entry.key)).save.document.title)
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `the folder library writes the codec's save under the desktop's name for it`() = runTest(timeout = 5.minutes) {
        val world = TestWorlds.small()
        val document = WorldDocument(
            id = "interop-browser",
            title = "Written by the browser",
            config = world.config,
            savedAt = 1_700_000_000_000L
        )
        withTestFolder("to-desktop") { folder ->
            val library = FolderWorldLibrary(folder.handle, WebGzipCompressor, "web")
            val key = library.save(document, world)
            assertEquals(LibraryKeys.of(document), key, "not the name the desktop's store gives a new save")
            val written = folder.readRaw(key)
            assertSameBytes(WorldCodec.encode(document, world, WebGzipCompressor, "web"), written)
            // In numbered pieces of [PRINTED_LINE] characters, which `RegenerateInteropFixtures`
            // reads back by number and length: the test reporter runs printed lines together.
            if (regeneratingInteropFixtures()) {
                Base64.encode(written).chunked(PRINTED_LINE).forEachIndexed { index, line ->
                    println("FOLDER-WRITTEN-SAVE ${index.toString().padStart(4, '0')} $line")
                }
            }
        }
    }
}

/** Base64 characters a printed piece holds: `RegenerateInteropFixtures.BROWSER_SAVE_PIECE`. */
private const val PRINTED_LINE = 1_000

/**
 * Whether Karma was started with `REGENERATE_INTEROP_FIXTURES` set; see `karma.config.d/`. Kotlin's
 * test runner moves the client arguments from `args` to `originalArgs` before a test runs, so both
 * are read.
 */
@JsFun(
    """() => {
        const karma = typeof window !== 'undefined' ? window.__karma__ : undefined;
        const config = karma && karma.config ? karma.config : {};
        const args = (config.args || []).concat(config.originalArgs || []);
        return args.indexOf('regenerate-interop-fixtures') >= 0;
    }"""
)
private external fun regeneratingInteropFixtures(): Boolean
