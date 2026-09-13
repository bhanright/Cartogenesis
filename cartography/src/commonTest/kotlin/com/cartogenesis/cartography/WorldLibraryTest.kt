package com.cartogenesis.cartography

import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/**
 * [ByteWorldLibrary] over an in-memory map of names to bytes, so the listing contract can be
 * proven without a filesystem or a browser.
 *
 * [fullReadsAllowed] is the guard's teeth: once a fixture is saved, flipping it to `false` and
 * then listing the library has to keep working, because [read] throws the moment anything calls
 * it. A version of this test that only checked the listing came back right, with no way for it to
 * fail if `list` quietly read every array, would prove nothing (ground rule 2) — this is the
 * shape that was shown to fail: with [readPrefix] deleted so it falls back to the default (which
 * is exactly `read`), the assertion below throws instead of passing.
 */
private class FakeByteWorldLibrary(
    compressor: Compressor = NoCompression
) : ByteWorldLibrary(compressor, "test") {
    private val blobs = LinkedHashMap<String, ByteArray>()
    var fullReadsAllowed = true
    var fullReadCount = 0
        private set

    override suspend fun names(): List<String> = blobs.keys.toList()

    override suspend fun read(name: String): ByteArray? {
        fullReadCount++
        check(fullReadsAllowed) { "listing read the full payload of '$name' instead of a prefix" }
        return blobs[name]
    }

    override suspend fun write(name: String, bytes: ByteArray) {
        blobs[name] = bytes
    }

    override suspend fun remove(name: String) {
        blobs.remove(name)
    }

    override suspend fun readPrefix(name: String, limitBytes: Int): ByteArray? {
        val bytes = blobs[name] ?: return null
        return bytes.copyOfRange(0, minOf(limitBytes, bytes.size))
    }
}

class WorldLibraryTest {

    @Test
    fun `listing a 1024 save reads only its header`() = runTest(timeout = 15.minutes) {
        val config = WorldGenConfig(seed = 5150L, width = 1024, height = 1024)
        val world = WorldGenerationEngine.generate(config)
        val doc = WorldDocument(
            id = "large-world",
            title = "A 1024 world",
            config = config,
            savedAt = 1_700_000_000_000L
        )

        val library = FakeByteWorldLibrary()
        library.save(doc, world)
        assertEquals(0, library.fullReadCount, "saving must not have read anything back")

        // The guard: a listing must survive with full reads switched off, which only holds if it
        // never asks for one.
        library.fullReadsAllowed = false
        val listed = library.list()

        assertEquals(0, library.fullReadCount, "listing a save should never read its full payload")
        val entry = assertNotNull(listed.singleOrNull { it.document.id == "large-world" })
        assertEquals("A 1024 world", entry.document.title)
        assertEquals(1_700_000_000_000L, entry.document.savedAt)
        assertEquals("complete", entry.status, "a save this build just wrote should need nothing")
    }

    @Test
    fun `save, list, load and delete round trip through the shared byte format`() = runTest {
        val config = WorldGenConfig(seed = 11L, width = 128, height = 128)
        val world = WorldGenerationEngine.generate(config)
        val doc = WorldDocument(id = "a", title = "Alpha", config = config, savedAt = 1L)

        val library = FakeByteWorldLibrary()
        library.save(doc, world)

        val listed = library.list()
        assertEquals(listOf("Alpha"), listed.map { it.document.title })

        val loaded = assertNotNull(library.load("a"))
        assertEquals("Alpha", loaded.document.title)
        assertNotNull(loaded.world)

        library.delete("a")
        assertEquals(emptyList(), library.list())
        assertEquals(null, library.load("a"))
    }
}
