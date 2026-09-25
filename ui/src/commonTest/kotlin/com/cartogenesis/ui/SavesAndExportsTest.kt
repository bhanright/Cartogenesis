package com.cartogenesis.ui

import com.cartogenesis.cartography.DataExports
import com.cartogenesis.cartography.DataLayer
import com.cartogenesis.cartography.ExportedWorld
import com.cartogenesis.cartography.NoCompression
import com.cartogenesis.cartography.WorldCodec
import com.cartogenesis.cartography.WorldDocument
import com.cartogenesis.cartography.WorldOverrides
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * The rules the application files a world and exports one by, held apart from the composition so
 * each can be asked directly: which document a world is, which settings a save records, which
 * world an export draws, and whose an export's end is.
 */
class SavesAndExportsTest {

    @Test
    fun `a save files the settings of the world on screen, not the panel's`() = runTest {
        // After a stopped change of seed or resolution the panel holds settings the world on
        // screen was not made with. The old document() filed those, and the save reopened as
        // another world or, after a resolution change, not at all.
        val world = WorldGenerationEngine.generate(WorldGenConfig(seed = 11L, width = 32, height = 32))
        val identity = DocumentIdentity("doc", key = null, seed = 11L)
        val document = documentFor(world, identity, "Eleven", WorldOverrides(), emptyList(), savedAt = 1L)
        assertSame(world.config, document.config)
        // And it is a pair the codec will write.
        WorldCodec.encode(document, world)
    }

    @Test
    fun `a world at another seed is another document, and saves beside the last`() {
        // Save, Random world, Save: the second Save wrote over the first world's file, although
        // the pane says saving under the same name updates it in place.
        var fresh = 0
        val next = { "doc-${++fresh}" }
        val shelf = MemoryLibrary()
        val first = DocumentIdentity("doc-0", key = null, seed = null).afterGenerating(1L, next).at("doc-0.cgw", shelf)
        assertEquals(DocumentIdentity("doc-0", "doc-0.cgw", 1L, shelf), first)

        val random = first.afterGenerating(2L, next)
        assertNotEquals(first.id, random.id)
        assertNull(random.key, "a new world has not been saved anywhere yet")

        // A settings edit at the same seed is the same document, saved where it was.
        assertEquals(first, first.afterGenerating(1L, next))
        // A file from outside the library is a new document too, whatever id it carries: that id
        // may be a library save's, and a first Save filed under it wrote over that save.
        val world = WorldDocument(id = "doc-0", title = "Brought in", config = WorldGenConfig(seed = 1L), savedAt = 1L)
        val imported = DocumentIdentity.opened(world, key = null, from = null, freshId = next)
        assertNotEquals("doc-0", imported.id)
        assertNull(imported.key)
        assertEquals(DocumentIdentity("doc-0", "doc-0.cgw", 1L, shelf), DocumentIdentity.opened(world, "doc-0.cgw", shelf, next))
        // Save as is a new document for the same world.
        val copy = first.savedAs(next)
        assertNotEquals(first.id, copy.id)
        assertNull(copy.key)
        assertEquals(first.seed, copy.seed)
    }

    @Test
    fun `a save that finishes after another file was opened leaves that file's key alone`() {
        // Save x.cgw, and while it is written open "x (1).cgw", a sync client's conflict copy that
        // carries the same id. Matched by id, the finished save gave the copy x.cgw's key, and the
        // copy's next Save wrote over x.cgw.
        val shelf = MemoryLibrary()
        val next = { "fresh" }
        val document = OpenDocument(DocumentIdentity("x", "x.cgw", 1L, shelf))
        val ticket = document.saving()

        val copy = WorldDocument(id = "x", title = "The conflict copy", config = WorldGenConfig(seed = 1L), savedAt = 2L)
        document.becomes(DocumentIdentity.opened(copy, "x (1).cgw", shelf, next))
        assertEquals("x (1).cgw", document.keyFor(document.saving(), shelf))

        document.saved(ticket, "x.cgw", shelf)
        assertEquals("x (1).cgw", document.identity.key, "the finished save gave the copy another file's key")
        assertEquals("x", document.identity.id)

        // And a save of the document that is still on screen records where it went.
        val again = document.saving()
        document.saved(again, "x (1).cgw", shelf)
        assertEquals("x (1).cgw", document.identity.key)
        // The same world made again at its own seed is the same document, and its saves still land.
        val beforeGenerating = document.saving()
        document.afterGenerating(1L, next)
        document.saved(beforeGenerating, "x (1).cgw", shelf)
        assertEquals("x (1).cgw", document.identity.key)
    }

    @Test
    fun `a key is written back only into the library it came from`() {
        // The library moved — to another folder, or between this browser's storage and a folder
        // on the disk — and Save wrote the world opened from one place over whatever file of the
        // same name the other place held.
        val browserStorage = MemoryLibrary()
        val folder = MemoryLibrary()
        val opened = DocumentIdentity("doc-0", key = null, seed = 1L).at("doc-0.cgw", browserStorage)
        assertEquals("doc-0.cgw", opened.keyIn(browserStorage))
        assertNull(opened.keyIn(folder), "a key from this browser's storage was written into the folder")
        assertNull(DocumentIdentity("doc-0", "doc-0.cgw", 1L).keyIn(folder), "a key with no library was trusted")
    }

    @Test
    fun `an export at the world's own size is the world on screen, and any other is made again`() = runTest {
        val onScreen = WorldGenerationEngine.generate(WorldGenConfig(seed = 12L, width = 32, height = 32))
        val same = ExportSubjects.at(onScreen, 32, null, null, null)
        assertSame(onScreen, same.world, "an export at the world's own size regenerated it")
        assertEquals(ExportedWorld.OnScreen, same.source)

        val larger = ExportSubjects.at(onScreen, 64, null, null, null)
        assertEquals(64, larger.world.width)
        assertEquals(onScreen.config.atResolution(64, 64), larger.world.config)
        assertEquals(ExportedWorld.MadeAgain(32, 32), larger.source)
        assertTrue("made again" in ExportRunner.notice(ExportOutcome("x.png", 1L, 1L, larger.source)))
        assertTrue("made again" !in ExportRunner.notice(ExportOutcome("x.png", 1L, 1L, same.source)))
    }

    @Test
    fun `a data sidecar says whether its world was the one on screen`() = runTest {
        val world = WorldGenerationEngine.generate(WorldGenConfig(seed = 13L, width = 32, height = 32))
        val onScreen = DataExports.write(world, DataLayer.BIOMES, NoCompression, "a test").sidecar.decodeToString()
        assertTrue("\"worldSource\": \"on screen\"" in onScreen, onScreen)
        val madeAgain = DataExports.write(world, DataLayer.BIOMES, NoCompression, "a test", ExportedWorld.MadeAgain(16, 16))
            .sidecar.decodeToString()
        assertTrue("\"worldSource\": \"regenerated\"" in madeAgain, madeAgain)
        assertTrue("\"regeneratedFromWidthPixels\": 16" in madeAgain, madeAgain)
        assertTrue("not the same world cell for cell" in madeAgain, madeAgain)
    }

    @Test
    fun `the export row says which size is the world on screen`() {
        assertTrue("At 2048, the world's own size" in ExportSubjects.note(2048, Exports.SIZES))
        assertTrue("from the 1024 world on screen" in ExportSubjects.note(1024, Exports.SIZES))
    }

    @Test
    fun `every working resolution the panel offers is one a save holds`() {
        val largest = Knobs.RESOLUTIONS.max()
        assertTrue(
            largest.toLong() * largest <= WorldCodec.LARGEST_GRID_CELLS,
            "the panel offers $largest, which a save refuses as too large"
        )
    }

    @Test
    fun `a second export replaces the first without reporting it failed or being cleared by it`() {
        // The old effect caught the first export's cancellation as a failure, wrote "Export
        // failed" over a file that had been written, and then cleared the shared state, which
        // cancelled the second export as well.
        val scope = TestScope(StandardTestDispatcher())
        val runner = ExportRunner(scope)
        val notices = mutableListOf<String>()
        val firstDone = CompletableDeferred<String>()
        val secondDone = CompletableDeferred<String>()
        var firstCancelled = false

        // The first does not notice its cancellation until its work is done, as a blocking
        // encoder does not: the case where an older export ends after a newer one began.
        val first = ExportRequest(2048, "Rendering 2048x2048")
        runner.start(first, {
            val answer = withContext(NonCancellable) { firstDone.await() }
            firstCancelled = !currentCoroutineContext().isActive
            answer
        }) { notices += it }
        scope.advanceUntilIdle()
        assertSame(first, runner.running)

        val second = ExportRequest(4096, "Rendering 4096x4096")
        runner.start(second, { secondDone.await() }) { notices += it }
        scope.advanceUntilIdle()
        assertSame(second, runner.running)

        firstDone.complete("Saved first")
        scope.advanceUntilIdle()
        assertTrue(firstCancelled, "the first export was not cancelled when the second began")
        assertSame(second, runner.running, "the first export's end cleared the second")
        assertEquals(emptyList(), notices, "a superseded export reported something")

        secondDone.complete("Saved second")
        scope.advanceUntilIdle()
        assertEquals(listOf("Saved second"), notices)
        assertNull(runner.running)
    }

    @Test
    fun `an export that fails says so, and one the reader backed out of says that`() {
        val scope = TestScope(StandardTestDispatcher())
        val runner = ExportRunner(scope)
        val notices = mutableListOf<String>()
        runner.start(ExportRequest(2048, "Rendering"), { error("the disk is full") }) { notices += it }
        scope.advanceUntilIdle()
        assertEquals(1, notices.size)
        assertTrue(notices.single().startsWith("Export failed") && "the disk is full" in notices.single())
        assertNull(runner.running)
        assertEquals("Export cancelled", ExportRunner.notice(null))
    }
}
