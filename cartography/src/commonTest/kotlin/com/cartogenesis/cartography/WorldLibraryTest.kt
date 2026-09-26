package com.cartogenesis.cartography

import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * [ByteWorldLibrary] over an in-memory map of names to bytes, so the library's contract can be
 * proven without a filesystem or a browser.
 *
 * [fullReadsAllowed] is the listing guard's teeth: once a fixture is saved, flipping it to `false`
 * and then listing the library has to keep working, because [reading] throws the moment anything
 * calls it. [replacing] keeps the blob as it was when the write throws, which is the contract the
 * desktop's temporary file and rename and the browser's single transaction both keep.
 */
private class FakeByteWorldLibrary(compressor: Compressor = NoCompression) : ByteWorldLibrary(compressor, "test") {
    val blobs = LinkedHashMap<String, ByteArray>()
    var fullReadsAllowed = true
    var fullReadCount = 0
        private set

    override suspend fun names(): List<String> = blobs.keys.toList()

    override suspend fun <T> reading(name: String, block: suspend (SaveSource) -> T): T? {
        fullReadCount++
        check(fullReadsAllowed) { "listing read the full payload of '$name' instead of a prefix" }
        val bytes = blobs[name] ?: return null
        return block(ByteArraySource(bytes))
    }

    override suspend fun readPrefix(name: String, limitBytes: Int): ByteArray? {
        val bytes = blobs[name] ?: return null
        return bytes.copyOfRange(0, minOf(limitBytes, bytes.size))
    }

    override suspend fun replacing(name: String, contents: suspend (SaveSink) -> Unit) {
        val sink = ByteArraySink()
        contents(sink)
        blobs[name] = sink.toByteArray()
    }

    override suspend fun remove(name: String) {
        blobs.remove(name)
    }
}

class WorldLibraryTest {

    private val world = SyntheticWorlds.of()

    private fun document(id: String = "a", title: String = "Alpha", savedAt: Long = 1L) =
        WorldDocument(id = id, title = title, config = world.config, savedAt = savedAt)

    @Test
    fun `listing a 1024 save reads only its header`() = runTest(timeout = 15.minutes) {
        val config = WorldGenConfig(seed = 5150L, width = 1024, height = 1024)
        val large = WorldGenerationEngine.generate(config)
        val doc = WorldDocument(id = "large-world", title = "A 1024 world", config = config, savedAt = 1_700_000_000_000L)

        val library = FakeByteWorldLibrary()
        library.save(doc, large)
        assertEquals(0, library.fullReadCount, "saving must not have read anything back")

        // The guard: a listing must survive with full reads switched off, which only holds if it
        // never asks for one.
        library.fullReadsAllowed = false
        val listed = library.list()

        assertEquals(0, library.fullReadCount, "listing a save should never read its full payload")
        val entry = assertNotNull(listed.singleOrNull { it.key == "large-world.cgw" })
        assertEquals("A 1024 world", entry.document?.title)
        assertEquals(1_700_000_000_000L, entry.document?.savedAt)
        assertNull(entry.refusal, "a save this build just wrote should open")
    }

    @Test
    fun `a save reports its bytes as they reach the library, and they add up to the file`() = runTest {
        // What the library pane shows beside a save under way, so a slow save on a phone is seen
        // moving rather than taken for one that did nothing.
        val library = FakeByteWorldLibrary()
        val counts = mutableListOf<Long>()
        val key = withContext(SaveProgress { counts += it }) { library.save(document(), world) }
        assertTrue(counts.isNotEmpty(), "a save reported no progress")
        assertTrue(counts.zipWithNext().all { (before, after) -> after > before }, "the count went backwards or stood still: $counts")
        assertEquals(library.blobs.getValue(key).size.toLong(), counts.last(), "the count does not add up to the file")

        // And a save with nothing listening writes the same file.
        val quiet = FakeByteWorldLibrary()
        quiet.save(document(), world)
        assertContentEquals(library.blobs.getValue(key), quiet.blobs.getValue(key))
    }

    @Test
    fun `save, list, load and delete round trip by key`() = runTest {
        val library = FakeByteWorldLibrary()
        val key = library.save(document(), world)
        assertEquals("a.cgw", key)

        assertEquals(listOf("Alpha"), library.list().map { it.document?.title })
        val loaded = assertIs<LoadOutcome.Loaded>(library.load(key))
        assertEquals("Alpha", loaded.save.document.title)

        library.delete(key)
        assertEquals(emptyList(), library.list())
        val gone = assertIs<LoadOutcome.Refused>(library.load(key))
        assertEquals(SaveProblem.UNREADABLE, gone.refusal.problem)
    }

    @Test
    fun `a save under a name that is not its id lists, opens, saves and deletes by that name`() = runTest {
        // A browser download dropped into the desktop's folder is named cartogenesis-<id>.cgw. The
        // old library listed it by that name, opened it by <id>.cgw — which is not there — and did
        // nothing; Delete removed nothing.
        val library = FakeByteWorldLibrary()
        val bytes = WorldCodec.encode(document(id = "b1"), world)
        library.blobs["cartogenesis-b1.cgw"] = bytes

        val entry = library.list().single()
        assertEquals("cartogenesis-b1.cgw", entry.key)
        assertIs<LoadOutcome.Loaded>(library.load(entry.key))

        // Saving what was opened from it writes back to it, not to b1.cgw beside it.
        assertEquals(entry.key, library.save(document(id = "b1", title = "Renamed"), world, entry.key))
        assertEquals(setOf("cartogenesis-b1.cgw"), library.blobs.keys)
        library.delete(entry.key)
        assertTrue(library.blobs.isEmpty())
    }

    @Test
    fun `two copies of one world are two entries, and saving one leaves the other`() = runTest {
        // What a sync client leaves when two machines edit one world: the same id in two files.
        val library = FakeByteWorldLibrary()
        library.save(document(title = "Here"), world)
        library.blobs["a (conflicted copy).cgw"] = WorldCodec.encode(document(title = "There", savedAt = 2L), world)
        library.blobs["a (1).cgw"] = WorldCodec.encode(document(title = "Elsewhere", savedAt = 3L), world)

        val listed = library.list()
        assertEquals(listOf("a (1).cgw", "a (conflicted copy).cgw", "a.cgw"), listed.map { it.key })
        listed.forEach { assertIs<LoadOutcome.Loaded>(library.load(it.key), it.key) }

        val untouched = library.blobs.getValue("a.cgw")
        library.save(document(title = "Mended"), world, "a (1).cgw")
        assertContentEquals(untouched, library.blobs.getValue("a.cgw"))
        assertEquals("There", assertIs<LoadOutcome.Loaded>(library.load("a (conflicted copy).cgw")).save.document.title)
        assertEquals("Mended", assertIs<LoadOutcome.Loaded>(library.load("a (1).cgw")).save.document.title)
    }

    @Test
    fun `no key or id reaches outside the library`() = runTest {
        val library = FakeByteWorldLibrary()
        library.save(document(), world)
        val before = library.blobs.keys.toSet()

        assertFailsWith<IllegalArgumentException> { library.save(document(id = "../escape"), world) }
        assertFailsWith<IllegalArgumentException> { library.save(document(), world, "../escape.cgw") }
        assertFailsWith<IllegalArgumentException> { library.load("..\\escape.cgw") }
        assertFailsWith<IllegalArgumentException> { library.delete("C:escape.cgw") }
        assertFailsWith<IllegalArgumentException> { library.delete("a.json") }
        assertEquals(before, library.blobs.keys)

        // A file whose header carries such an id is listed, with its reason, and never opened.
        library.blobs["crafted.cgw"] = TakenApart.of(WorldCodec.encode(document(id = "c"), world)).let {
            it.reassemble(it.header.copy(document = it.header.document.copy(id = "../../x")))
        }
        val crafted = library.list().single { it.key == "crafted.cgw" }
        assertEquals(SaveProblem.DAMAGED, crafted.refusal?.problem)
        assertIs<LoadOutcome.Refused>(library.load("crafted.cgw"))
    }

    @Test
    fun `a file that will not open is listed with its reason`() = runTest {
        val library = FakeByteWorldLibrary()
        val bytes = WorldCodec.encode(document(), world)
        library.blobs["cut.cgw"] = bytes.copyOf(30)
        library.blobs["zeros.cgw"] = ByteArray(bytes.size)
        library.blobs["half.cgw"] = bytes.copyOf(bytes.size / 2)
        library.blobs["notes.cgw"] = "not a world".encodeToByteArray()

        val listed = library.list().associateBy { it.key }
        assertEquals(SaveProblem.INCOMPLETE, listed.getValue("cut.cgw").refusal?.problem)
        assertEquals(SaveProblem.INCOMPLETE, listed.getValue("zeros.cgw").refusal?.problem)
        assertEquals(SaveProblem.NOT_A_SAVE, listed.getValue("notes.cgw").refusal?.problem)
        // Its header is whole, so it lists as a world, and opening it finds where it stops.
        assertNotNull(listed.getValue("half.cgw").document)
        val half = assertIs<LoadOutcome.Refused>(library.load("half.cgw"))
        assertEquals(SaveProblem.INCOMPLETE, half.refusal.problem)
    }

    @Test
    fun `a new save never replaces a file already in the library`() = runTest {
        // A world opened from outside the library keeps the id it was saved with. Filed as new
        // under <id>.cgw it wrote over the library's own copy of that world, edited or not.
        val library = FakeByteWorldLibrary()
        library.save(document(title = "In the library"), world)
        val original = library.blobs.getValue("a.cgw").copyOf()

        assertEquals("a (2).cgw", library.save(document(title = "Imported"), world))
        assertEquals("a (3).cgw", library.save(document(title = "Imported again"), world))
        assertContentEquals(original, library.blobs.getValue("a.cgw"))
        assertEquals("Imported", assertIs<LoadOutcome.Loaded>(library.load("a (2).cgw")).save.document.title)
    }

    @Test
    fun `a copy from another library is a new file beside what is there, byte for byte`() = runTest {
        // What the browser offers when the reader moves the library into a folder: the worlds in
        // the browser's storage copied in, and none of the folder's own written over.
        val from = FakeByteWorldLibrary()
        val into = FakeByteWorldLibrary()
        from.save(document(title = "In the browser"), world)
        into.save(document(title = "Already in the folder"), world)
        val folderOwn = into.blobs.getValue("a.cgw").copyOf()

        assertEquals("a (2).cgw", into.copyFrom(from, "a.cgw"))
        assertContentEquals(folderOwn, into.blobs.getValue("a.cgw"), "the copy wrote over the folder's own file")
        assertContentEquals(from.blobs.getValue("a.cgw"), into.blobs.getValue("a (2).cgw"))
        assertEquals(setOf("a.cgw"), from.blobs.keys, "the copy took the original away")
        assertEquals("In the browser", assertIs<LoadOutcome.Loaded>(into.load("a (2).cgw")).save.document.title)
        assertFailsWith<WorldFormatException> { into.copyFrom(from, "missing.cgw") }
    }

    @Test
    fun `overlapping saves of one file land in the order they were asked for`() = runTest {
        // The first save is slow and the second fast. Unordered, the second finished first and
        // the first then renamed itself over it, so the older world was the one left on disk.
        val release = CompletableDeferred<Unit>()
        var holdNext = true
        val slowFirst = object : Compressor {
            override val name: String get() = "none"
            override suspend fun compress(data: ByteArray): ByteArray? {
                if (holdNext) {
                    holdNext = false
                    release.await()
                }
                return null
            }
            override suspend fun decompress(data: ByteArray, limitBytes: Int): ByteArray? = null
        }
        val library = FakeByteWorldLibrary(slowFirst)
        val older = launch { library.save(document(title = "Older"), world, "a.cgw") }
        runCurrent()
        val newer = launch { library.save(document(title = "Newer"), world, "a.cgw") }
        runCurrent()
        release.complete(Unit)
        joinAll(older, newer)
        assertEquals("Newer", assertIs<LoadOutcome.Loaded>(library.load("a.cgw")).save.document.title)
    }

    @Test
    fun `a save that fails partway leaves the previous one whole`() = runTest {
        val library = FakeByteWorldLibrary(FailingCompressor)
        library.save(document(title = "First"), SyntheticWorlds.of())
        val first = library.blobs.getValue("a.cgw").copyOf()

        FailingCompressor.failNext = true
        assertFailsWith<IllegalStateException> { library.save(document(title = "Second"), world, "a.cgw") }
        assertContentEquals(first, library.blobs.getValue("a.cgw"))
    }
}

/** Stores every chunk raw, or throws on the next one when asked to: a disk filling up halfway. */
private object FailingCompressor : Compressor {
    var failNext = false
    override val name: String get() = "none"
    override suspend fun compress(data: ByteArray): ByteArray? {
        if (failNext) {
            failNext = false
            error("the disk filled up")
        }
        return null
    }
    override suspend fun decompress(data: ByteArray, limitBytes: Int): ByteArray? = null
}
