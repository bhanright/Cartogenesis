package com.cartogenesis.desktop

import com.cartogenesis.cartography.Compressor
import com.cartogenesis.cartography.LoadOutcome
import com.cartogenesis.cartography.SaveProblem
import com.cartogenesis.cartography.WorldCodec
import com.cartogenesis.cartography.WorldDocument
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The desktop library against a real folder: the one it lists, opens, writes and deletes in, and
 * the one a reader may point at a folder a sync client keeps in step across machines.
 *
 * Each case is a file the library meets in the wild — a browser's download dropped in, the copies
 * a sync client makes, a save it has not finished bringing down, a crafted header — and each is
 * held to the same rule: it lists and opens as itself, or is refused with its reason, and nothing
 * outside the folder is ever written or removed.
 */
class DesktopWorldStoreTest {

    private val root: File = Files.createTempDirectory("cartogenesis-library").toFile()
    private val folder = File(root, "worlds")
    private val store = DesktopWorldStore(folder, GzipCompressor, "a test")

    private val world: WorldMap = WorldGenerationEngine.generateBlocking(WorldGenConfig(seed = 31L, width = 32, height = 32))

    private fun document(id: String = "w1", title: String = "One", savedAt: Long = 1L) =
        WorldDocument(id = id, title = title, config = world.config, savedAt = savedAt)

    private fun titleIn(outcome: LoadOutcome): String = assertIs<LoadOutcome.Loaded>(outcome).save.document.title

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `a browser download dropped in the folder lists, opens and deletes by its own name`() = runBlocking<Unit> {
        // The old library listed this by its name, opened it as w1.cgw — which is not there — did
        // nothing, and Delete removed nothing.
        val bytes = WorldCodec.encode(document(), world, GzipCompressor)
        File(folder, "cartogenesis-w1.cgw").writeBytes(bytes)

        val entry = store.list().single()
        assertEquals("cartogenesis-w1.cgw", entry.key)
        assertEquals("One", titleIn(store.load(entry.key)))
        store.delete(entry.key)
        assertFalse(File(folder, "cartogenesis-w1.cgw").exists())
    }

    @Test
    fun `a sync client's copies of one world open as themselves and are saved separately`() = runBlocking<Unit> {
        store.save(document(title = "Here"), world)
        File(folder, "w1 (1).cgw").writeBytes(WorldCodec.encode(document(title = "There", savedAt = 2L), world, GzipCompressor))
        File(folder, "w1-DESKTOP-4F2.cgw").writeBytes(WorldCodec.encode(document(title = "Laptop", savedAt = 3L), world, GzipCompressor))

        val keys = store.list().map { it.key }
        assertEquals(listOf("w1-DESKTOP-4F2.cgw", "w1 (1).cgw", "w1.cgw"), keys)
        assertEquals(listOf("Laptop", "There", "Here"), keys.map { titleIn(store.load(it)) })

        val here = File(folder, "w1.cgw").readBytes()
        store.save(document(title = "There, mended"), world, "w1 (1).cgw")
        assertContentEquals(here, File(folder, "w1.cgw").readBytes(), "saving one copy wrote over another")
        assertEquals("There, mended", titleIn(store.load("w1 (1).cgw")))
    }

    @Test
    fun `a save replaces its file in one step and leaves no half of it behind`() = runBlocking<Unit> {
        // A second name for the first save's bytes. Writing the file in place, as the old store did,
        // changes what this name reads too; renaming a new file over it leaves this one the old save.
        store.save(document(title = "First"), world)
        val target = File(folder, "w1.cgw")
        val witness = File(root, "witness.cgw")
        Files.createLink(witness.toPath(), target.toPath())
        val first = witness.readBytes()

        store.save(document(title = "Second"), world)
        assertContentEquals(first, witness.readBytes(), "the save was written over the old file in place")
        assertEquals("Second", titleIn(store.load("w1.cgw")))
        assertEquals(listOf("w1.cgw"), folder.list()!!.toList(), "a temporary file was left in the library")
    }

    @Test
    fun `a save that fails partway leaves the previous one whole`() = runBlocking<Unit> {
        store.save(document(title = "Kept"), world)
        val kept = File(folder, "w1.cgw").readBytes()

        val failing = DesktopWorldStore(folder, FailsOnFirstChunk, "a test")
        assertFailsWith<IllegalStateException> { failing.save(document(title = "Lost"), world) }
        assertContentEquals(kept, File(folder, "w1.cgw").readBytes())
        assertEquals(listOf("w1.cgw"), folder.list()!!.toList(), "the failed save's temporary file was left behind")
    }

    @Test
    fun `no key or id reaches outside the folder`() = runBlocking<Unit> {
        val outside = File(root, "escape.cgw").apply { writeText("keep me") }

        assertFailsWith<IllegalArgumentException> { store.save(document(id = "../escape"), world) }
        assertFailsWith<IllegalArgumentException> { store.save(document(), world, "../escape.cgw") }
        assertFailsWith<IllegalArgumentException> { store.delete("..\\escape.cgw") }
        assertFailsWith<IllegalArgumentException> { store.load("../escape.cgw") }
        assertEquals("keep me", outside.readText())

        // A crafted header naming a path is listed with its reason and never opened, so its id
        // never becomes the name of anything Save writes.
        val crafted = WorldCodec.encode(document(id = "w9"), world)
        val at = crafted.indexOfSequence("\"id\":\"w9\"".encodeToByteArray())
        // Same length, so the header's own length still adds up: w9 becomes .., a path.
        crafted[at + 6] = '.'.code.toByte()
        crafted[at + 7] = '.'.code.toByte()
        File(folder, "crafted.cgw").writeBytes(crafted)
        val entry = store.list().single { it.key == "crafted.cgw" }
        assertEquals(SaveProblem.DAMAGED, entry.refusal?.problem)
        assertIs<LoadOutcome.Refused>(store.load("crafted.cgw"))
        assertTrue(outside.exists())
    }

    @Test
    fun `a file still arriving is refused as incomplete, and one that cannot be read as unreadable`() = runBlocking<Unit> {
        val bytes = WorldCodec.encode(document(), world, GzipCompressor)
        File(folder, "half.cgw").writeBytes(bytes.copyOf(bytes.size / 2))
        File(folder, "placeholder.cgw").writeBytes(ByteArray(bytes.size))
        // A name the folder holds and the storage will not read, as an online-only placeholder
        // whose client is not running will not: a directory, which no platform reads as a file.
        File(folder, "online-only.cgw").mkdirs()

        val listed = store.list().associateBy { it.key }
        assertNotNull(listed.getValue("half.cgw").document, "its header is whole, so it lists as its world")
        assertEquals(SaveProblem.INCOMPLETE, listed.getValue("placeholder.cgw").refusal?.problem)
        assertEquals(SaveProblem.UNREADABLE, listed.getValue("online-only.cgw").refusal?.problem)

        assertEquals(SaveProblem.INCOMPLETE, assertIs<LoadOutcome.Refused>(store.load("half.cgw")).refusal.problem)
        assertEquals(SaveProblem.INCOMPLETE, assertIs<LoadOutcome.Refused>(store.load("placeholder.cgw")).refusal.problem)
        assertEquals(SaveProblem.UNREADABLE, assertIs<LoadOutcome.Refused>(store.load("online-only.cgw")).refusal.problem)
        // And nothing was written in their place.
        assertContentEquals(bytes.copyOf(bytes.size / 2), File(folder, "half.cgw").readBytes())
    }

    @Test
    fun `the settings file is replaced in one step and never rewritten in place`() = runBlocking<Unit> {
        val file = File(root, "settings.json").apply { writeText("{\"old\":true}") }
        val witness = File(root, "settings-witness.json")
        Files.createLink(witness.toPath(), file.toPath())

        FileSettings(file).write("{\"new\":true}")
        assertEquals("{\"new\":true}", file.readText())
        assertEquals("{\"old\":true}", witness.readText(), "the settings were rewritten in place")
        assertEquals(setOf("settings.json", "settings-witness.json", "worlds"), root.list()!!.toSet())
    }

    @Test
    fun `the default folder comes back when the chosen one is cleared`() = runBlocking<Unit> {
        val platform = DesktopPlatform()
        val home = platform.libraryLocation
        assertTrue(platform.useLibraryFolder(folder.path))
        assertEquals(folder.absolutePath, platform.libraryLocation)
        assertTrue(platform.useLibraryFolder(""))
        assertEquals(home, platform.libraryLocation)
    }
}

/** Throws on the first chunk it is given, as a disk that fills up partway through a save would. */
private object FailsOnFirstChunk : Compressor {
    override val name: String get() = "gzip"
    override suspend fun compress(data: ByteArray): ByteArray = error("the disk filled up")
    override suspend fun decompress(data: ByteArray, limitBytes: Int): ByteArray? = null
}

private fun ByteArray.indexOfSequence(sequence: ByteArray): Int =
    (0..size - sequence.size).first { start -> sequence.indices.all { this[start + it] == sequence[it] } }
