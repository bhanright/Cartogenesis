package com.cartogenesis.web

import com.cartogenesis.cartography.Compressor
import com.cartogenesis.cartography.LoadOutcome
import com.cartogenesis.cartography.NoCompression
import com.cartogenesis.cartography.SaveProblem
import com.cartogenesis.cartography.WorldCodec
import com.cartogenesis.cartography.WorldDocument
import com.cartogenesis.worldgen.model.WorldMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * The folder library held to the contract every library keeps — the one `WorldLibraryTest` states
 * over bytes in memory and `DesktopWorldStoreTest` over a real folder — in a real browser, against a
 * real `FileSystemDirectoryHandle`.
 *
 * The handle is one from the origin private file system, the only one a page can have without the
 * reader choosing it in a picker, which no test can drive. Its handles are the same interface a
 * picked folder's are, and in this file system the behaviours the library depends on were seen
 * directly: a writable stream writes to a `<name>.crswap` beside its target and replaces the
 * target only on `close`, `abort` leaves an existing file as it was, `getFileHandle(create)` makes
 * an empty target before a byte is written and an aborted stream leaves it, and `move` renames.
 * That a picked folder behaves the same is what Chrome documents, not something seen here. What
 * this file system does not share: permission, always granted here, which `LibraryPlacesTest`
 * covers with a fake instead; the operating system's file system under a picked folder, where
 * Windows refuses to replace a file another program holds open; and any sync client.
 */
class FolderLibraryTest {

    private fun document(id: String = "w1", title: String = "One", savedAt: Long = 1L, world: WorldMap) =
        WorldDocument(id = id, title = title, config = world.config, savedAt = savedAt)

    private fun titleIn(outcome: LoadOutcome): String = assertIs<LoadOutcome.Loaded>(outcome).save.document.title

    @Test
    fun `save, list, load and delete round trip by key, and nothing else is left in the folder`() = runTest(timeout = 5.minutes) {
        val world = TestWorlds.small()
        withTestFolder("roundtrip") { folder ->
            val library = FolderWorldLibrary(folder.handle, WebGzipCompressor, "a test")
            val key = library.save(document(world = world), world)
            assertEquals("w1.cgw", key)
            assertEquals(listOf("w1.cgw"), folder.entries(), "a temporary or swap file was left in the folder")

            assertEquals(listOf("One"), library.list().map { it.document?.title })
            val loaded = assertIs<LoadOutcome.Loaded>(library.load(key)).save
            assertEquals(world.terrain.height.data.toList(), loaded.world.terrain.height.data.toList())

            // Saved over, in place: the browser's swap file, committed on close, and nothing beside it.
            library.save(document(title = "Two", world = world), world, key)
            assertEquals("Two", titleIn(library.load(key)))
            assertEquals(listOf("w1.cgw"), folder.entries())

            library.delete(key)
            assertEquals(emptyList(), folder.entries())
            assertEquals(SaveProblem.UNREADABLE, assertIs<LoadOutcome.Refused>(library.load(key)).refusal.problem)
        }
    }

    @Test
    fun `a file whose name is not its id, and a sync client's copies, open and save as themselves`() = runTest(timeout = 5.minutes) {
        val world = TestWorlds.small()
        withTestFolder("names") { folder ->
            val library = FolderWorldLibrary(folder.handle, WebGzipCompressor, "a test")
            folder.writeRaw("cartogenesis-w1.cgw", WorldCodec.encode(document(world = world), world, WebGzipCompressor))
            library.save(document(title = "Here", world = world), world)
            folder.writeRaw("w1 (1).cgw", WorldCodec.encode(document(title = "There", savedAt = 2L, world = world), world, WebGzipCompressor))

            val keys = library.list().map { it.key }
            assertEquals(listOf("w1 (1).cgw", "cartogenesis-w1.cgw", "w1.cgw").sorted(), keys.sorted())
            assertEquals("There", titleIn(library.load("w1 (1).cgw")))

            val here = folder.readRaw("w1.cgw")
            library.save(document(title = "There, mended", world = world), world, "w1 (1).cgw")
            assertSameBytes(here, folder.readRaw("w1.cgw"), "saving one copy wrote over another")
            assertEquals("There, mended", titleIn(library.load("w1 (1).cgw")))

            library.delete("cartogenesis-w1.cgw")
            assertEquals(listOf("w1 (1).cgw", "w1.cgw"), folder.entries())
        }
    }

    @Test
    fun `a new save never replaces a file already in the folder`() = runTest(timeout = 5.minutes) {
        val world = TestWorlds.small()
        withTestFolder("overwrite") { folder ->
            val library = FolderWorldLibrary(folder.handle, WebGzipCompressor, "a test")
            library.save(document(title = "In the folder", world = world), world)
            val original = folder.readRaw("w1.cgw")

            assertEquals("w1 (2).cgw", library.save(document(title = "Brought in", world = world), world))
            assertEquals("w1 (3).cgw", library.save(document(title = "Brought in again", world = world), world))
            assertSameBytes(original, folder.readRaw("w1.cgw"))
            assertEquals("Brought in", titleIn(library.load("w1 (2).cgw")))
            assertEquals(listOf("w1 (2).cgw", "w1 (3).cgw", "w1.cgw"), folder.entries())
        }
    }

    @Test
    fun `no key or id reaches outside the folder, and a crafted header is refused`() = runTest(timeout = 5.minutes) {
        val world = TestWorlds.small()
        withTestFolder("keys") { folder ->
            val library = FolderWorldLibrary(folder.handle, WebGzipCompressor, "a test")
            assertFailsWith<IllegalArgumentException> { library.save(document(id = "../escape", world = world), world) }
            assertFailsWith<IllegalArgumentException> { library.save(document(world = world), world, "../escape.cgw") }
            assertFailsWith<IllegalArgumentException> { library.delete("..\\escape.cgw") }
            assertFailsWith<IllegalArgumentException> { library.load("C:escape.cgw") }
            assertFailsWith<IllegalArgumentException> { library.delete("notes.json") }
            assertEquals(emptyList(), folder.entries())

            // Same length, so the header's own length still adds up: w9 becomes .., a path.
            val crafted = WorldCodec.encode(document(id = "w9", world = world), world)
            val at = crafted.indexOfSequence("\"id\":\"w9\"".encodeToByteArray())
            crafted[at + 6] = '.'.code.toByte()
            crafted[at + 7] = '.'.code.toByte()
            folder.writeRaw("crafted.cgw", crafted)
            val entry = library.list().single { it.key == "crafted.cgw" }
            assertEquals(SaveProblem.DAMAGED, entry.refusal?.problem)
            assertIs<LoadOutcome.Refused>(library.load("crafted.cgw"))
        }
    }

    @Test
    fun `a damaged file, one still arriving and one that cannot be read are listed with their reasons`() = runTest(timeout = 5.minutes) {
        val world = TestWorlds.small()
        withTestFolder("damaged") { folder ->
            val library = FolderWorldLibrary(folder.handle, WebGzipCompressor, "a test")
            val bytes = WorldCodec.encode(document(world = world), world, WebGzipCompressor)
            folder.writeRaw("half.cgw", bytes.copyOf(bytes.size / 2))
            folder.writeRaw("placeholder.cgw", ByteArray(bytes.size))
            folder.writeRaw("notes.cgw", "not a world".encodeToByteArray())
            // A name the folder holds and the storage will not read as a file, as the desktop's test
            // stands in for an online-only placeholder: a folder of that name.
            folder.makeFolder("online-only.cgw")

            val listed = library.list().associateBy { it.key }
            assertNotNull(listed.getValue("half.cgw").document, "its header is whole, so it lists as its world")
            assertEquals(SaveProblem.INCOMPLETE, listed.getValue("placeholder.cgw").refusal?.problem)
            assertEquals(SaveProblem.NOT_A_SAVE, listed.getValue("notes.cgw").refusal?.problem)
            assertEquals(SaveProblem.UNREADABLE, listed.getValue("online-only.cgw").refusal?.problem)
            assertEquals(SaveProblem.INCOMPLETE, assertIs<LoadOutcome.Refused>(library.load("half.cgw")).refusal.problem)
            assertEquals(SaveProblem.UNREADABLE, assertIs<LoadOutcome.Refused>(library.load("online-only.cgw")).refusal.problem)
            assertSameBytes(bytes.copyOf(bytes.size / 2), folder.readRaw("half.cgw"))
        }
    }

    @Test
    fun `a save of a file that is there which fails partway leaves the file whole and nothing beside it`() = runTest(timeout = 5.minutes) {
        val world = TestWorlds.small()
        withTestFolder("fail-existing") { folder ->
            FolderWorldLibrary(folder.handle, NoCompression, "a test").save(document(title = "Kept", world = world), world)
            val kept = folder.readRaw("w1.cgw")

            val failing = FolderWorldLibrary(folder.handle, FailsAtChunk(1), "a test", SMALL_PARTS)
            assertFailsWith<IllegalStateException> { failing.save(document(title = "Lost", world = world), world, "w1.cgw") }
            assertSameBytes(kept, folder.readRaw("w1.cgw"))
            assertEquals(listOf("w1.cgw"), folder.entries(), "the failed save left its swap file behind")
        }
    }

    @Test
    fun `a new save that fails partway leaves no file at all, under its name or any other`() = runTest(timeout = 5.minutes) {
        // `getFileHandle(create)` makes an empty file before a byte is written, and aborting the
        // stream leaves it there: a new save written straight to its name left an empty w1.cgw,
        // listed as a world that will not open, for every save that failed.
        val world = TestWorlds.small()
        withTestFolder("fail-new") { folder ->
            val failing = FolderWorldLibrary(folder.handle, FailsAtChunk(1), "a test", SMALL_PARTS)
            assertFailsWith<IllegalStateException> { failing.save(document(world = world), world) }
            assertEquals(emptyList(), folder.entries(), "a failed new save left a file behind")
            // A key given whose file is not there is a new file too.
            val failingAgain = FolderWorldLibrary(folder.handle, FailsAtChunk(1), "a test", SMALL_PARTS)
            assertFailsWith<IllegalStateException> { failingAgain.save(document(world = world), world, "w1.cgw") }
            assertEquals(emptyList(), folder.entries())
        }
    }

    @Test
    fun `a new save is never seen half made, and a cancelled one leaves nothing`() = runTest(timeout = 5.minutes) {
        val world = TestWorlds.small()
        withTestFolder("half-made") { folder ->
            val paused = PausesAtChunk(1)
            val library = FolderWorldLibrary(folder.handle, paused, "a test", SMALL_PARTS)
            val saving = launch { library.save(document(world = world), world) }
            paused.reached.await()
            // Partway through: whatever the folder holds, no listing takes any of it for a save.
            assertTrue(folder.entries().isNotEmpty(), "nothing was being written")
            assertEquals(emptyList(), library.list(), "a save still being written was listed")
            assertTrue(folder.entries().none { it.endsWith(".cgw") }, "a .cgw appeared before it was whole: ${folder.entries()}")

            saving.cancel()
            paused.release.complete(Unit)
            saving.join()
            assertEquals(emptyList(), folder.entries(), "a cancelled save left a file behind")
        }
    }

    @Test
    fun `a name taken by another writer during a save is not written over`() = runTest(timeout = 5.minutes) {
        // Another tab, the desktop application or a sync client puts w1.cgw in the folder while
        // this tab is writing its own new w1.cgw. The name was free when the save began.
        val world = TestWorlds.small()
        withTestFolder("taken") { folder ->
            val theirs = WorldCodec.encode(document(title = "Theirs", world = world), world, WebGzipCompressor)
            val interloper = OnChunk(1) { folder.writeRaw("w1.cgw", theirs) }
            val library = FolderWorldLibrary(folder.handle, interloper, "a test")

            assertEquals("w1 (2).cgw", library.save(document(title = "Ours", world = world), world))
            assertSameBytes(theirs, folder.readRaw("w1.cgw"), "the other writer's file was written over")
            assertEquals("Ours", titleIn(library.load("w1 (2).cgw")))
            assertEquals(listOf("w1 (2).cgw", "w1.cgw"), folder.entries())
        }
    }

    @Test
    fun `overlapping saves of one file land in the order they were asked for`() = runTest(timeout = 5.minutes) {
        val world = TestWorlds.small()
        withTestFolder("order") { folder ->
            val paused = PausesAtChunk(1)
            val library = FolderWorldLibrary(folder.handle, paused, "a test", SMALL_PARTS)
            val older = launch { library.save(document(title = "Older", world = world), world, "w1.cgw") }
            paused.reached.await()
            val newer = launch { library.save(document(title = "Newer", world = world), world, "w1.cgw") }
            paused.release.complete(Unit)
            joinAll(older, newer)
            assertEquals("Newer", titleIn(library.load("w1.cgw")))
            assertEquals(listOf("w1.cgw"), folder.entries())
        }
    }

    @Test
    fun `worlds in this browser's storage are copied into the folder beside its own`() = runTest(timeout = 5.minutes) {
        val world = TestWorlds.small()
        withTestFolder("copy") { folder ->
            val browserStorage = IndexedDbLibrary(WebGzipCompressor, "a test")
            val stored = browserStorage.save(document(id = "copytest", title = "From the browser", world = world), world)
            try {
                val library = FolderWorldLibrary(folder.handle, WebGzipCompressor, "a test")
                library.save(document(id = "copytest", title = "The folder's own", world = world), world)
                assertEquals("copytest (2).cgw", library.copyFrom(browserStorage, stored))
                assertEquals("The folder's own", titleIn(library.load("copytest.cgw")))
                assertEquals("From the browser", titleIn(library.load("copytest (2).cgw")))
                assertEquals("From the browser", titleIn(browserStorage.load(stored)), "the copy took the original away")
            } finally {
                browserStorage.delete(stored)
            }
        }
    }
}

/** Stores every chunk raw and throws on chunk [failingChunk], as a disk that fills up partway would. */
private class FailsAtChunk(private val failingChunk: Int) : Compressor {
    private var chunks = 0
    override val name: String get() = "none"
    override suspend fun compress(data: ByteArray): ByteArray? {
        if (++chunks == failingChunk) error("the disk filled up")
        return null
    }
    override suspend fun decompress(data: ByteArray, limitBytes: Int): ByteArray? = null
}

/** Stores every chunk raw, and at chunk [pausingChunk] says so and waits to be released. */
private class PausesAtChunk(private val pausingChunk: Int) : Compressor {
    private var chunks = 0
    val reached = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    override val name: String get() = "none"
    override suspend fun compress(data: ByteArray): ByteArray? {
        if (++chunks == pausingChunk) {
            reached.complete(Unit)
            release.await()
        }
        return null
    }
    override suspend fun decompress(data: ByteArray, limitBytes: Int): ByteArray? = null
}

/** Stores every chunk raw, and runs [action] once, at chunk [chunk]. */
private class OnChunk(private val chunk: Int, private val action: suspend () -> Unit) : Compressor {
    private var chunks = 0
    override val name: String get() = "none"
    override suspend fun compress(data: ByteArray): ByteArray? {
        if (++chunks == chunk) action()
        return null
    }
    override suspend fun decompress(data: ByteArray, limitBytes: Int): ByteArray? = null
}

/**
 * Four kibibytes a part for the tests that stop a save partway: a 32 world's payload is a single
 * codec chunk, so the stand-in compressors above act on the first one, and parts this small mean
 * the header ahead of it has already gone into the stream when they do.
 */
private const val SMALL_PARTS = 1 shl 12

private fun ByteArray.indexOfSequence(sequence: ByteArray): Int =
    (0..size - sequence.size).first { start -> sequence.indices.all { this[start + it] == sequence[it] } }
