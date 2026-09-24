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
import com.cartogenesis.cartography.LibraryEntry
import com.cartogenesis.cartography.LibraryKeys
import com.cartogenesis.cartography.LoadOutcome
import com.cartogenesis.cartography.SaveProblem
import com.cartogenesis.cartography.SaveRefusal
import com.cartogenesis.cartography.WorldDocument
import com.cartogenesis.cartography.WorldLibrary
import com.cartogenesis.ui.CartogenesisApp
import com.cartogenesis.ui.CartogenesisTheme
import com.cartogenesis.ui.Platform
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.IceSheetAccelerator
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * What File ▸ Save hands the library, in a running window: the world on screen, filed under that
 * world's own settings and under a document that is that world's.
 *
 * Two of Audit III's findings are about exactly this and neither shows without a window. G-D2:
 * Save read the panel's settings, so during a generation or after a stopped one it filed the world
 * on screen under settings it was not made with. G-D4: Save after Random world wrote the new world
 * over the previous one's file. Each was shown failing with its defect put back: filing the panel's
 * settings recorded a 1024 document with a 512 world, and keeping the document across a change of
 * seed recorded the same id twice.
 */
class SaveIdentityTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `Save files the world on screen under its own settings, and a new world under a new document`() {
        val library = RecordingLibrary()
        runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
            setContent { CartogenesisTheme(dark = false) { CartogenesisApp(RecordingPlatform(library)) } }

            onNodeWithText("Generate").performClick()
            waitUntil(timeoutMillis = GENERATION_TIMEOUT_MS) { seedOnTheMap() != null }
            val first = seedOnTheMap()

            save(library, 1)
            val saved = library.saves[0]
            assertEquals(saved.world.config, saved.document.config)
            assertNull(saved.key, "a world never saved was written to a key it did not own")

            // A resolution change starts a generation at once; Save while it runs files the world
            // still on screen, under that world's settings rather than the panel's new ones.
            onNodeWithText("1024").performClick()
            save(library, 2)
            val during = library.saves[1]
            assertEquals(during.world.config, during.document.config, "Save filed a world under settings it was not made with")
            assertEquals(saved.document.id, during.document.id, "the same world at the same seed is the same document")
            assertEquals(LibraryKeys.of(saved.document), during.key, "the second Save did not write where the first did")
            waitUntil(timeoutMillis = GENERATION_TIMEOUT_MS) { onAllStop().isEmpty() }

            onNodeWithText("Random world").performClick()
            waitUntil(timeoutMillis = GENERATION_TIMEOUT_MS) { seedOnTheMap().let { it != null && it != first } && onAllStop().isEmpty() }
            save(library, 3)
            val random = library.saves[2]
            assertNotEquals(saved.document.id, random.document.id, "Save after Random world wrote over the last world's document")
            assertNull(random.key, "Save after Random world wrote to the last world's file")
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.save(library: RecordingLibrary, count: Int) {
        onNodeWithText("File").performClick()
        waitForIdle()
        onNodeWithText("Save").performClick()
        waitUntil(timeoutMillis = SAVE_TIMEOUT_MS) { library.saves.size == count }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.onAllStop() =
        onAllNodesWithText("Stop").fetchSemanticsNodes()

    /** The seed on the cartouche, as `SeedFieldTest` reads it: the one record of the world on screen. */
    @OptIn(ExperimentalTestApi::class)
    private fun DesktopComposeUiTest.seedOnTheMap(): Long? {
        var found: Long? = null
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { text ->
                CARTOUCHE_FACTS.find(text.text)?.let { found = it.groupValues[1].toLong() }
            }
            node.children.forEach(::walk)
        }
        walk(onRoot().fetchSemanticsNode())
        return found
    }

    private companion object {
        const val WIDTH = 1440
        const val HEIGHT = 900
        val CARTOUCHE_FACTS = Regex("""seed (-?\d+) · \d+ × \d+""")

        /** Two 512 worlds and a 1024 one on whatever machine is running the tests. */
        const val GENERATION_TIMEOUT_MS = 300_000L
        const val SAVE_TIMEOUT_MS = 30_000L
    }
}

/** One call to [WorldLibrary.save], as it was made. */
private class RecordedSave(val document: WorldDocument, val world: WorldMap, val key: String?)

/** A library that keeps nothing but what it was asked to save, and where. */
private class RecordingLibrary : WorldLibrary {
    val saves: MutableList<RecordedSave> = Collections.synchronizedList(ArrayList())

    override suspend fun list(): List<LibraryEntry> = emptyList()

    override suspend fun save(document: WorldDocument, world: WorldMap, key: String?): String {
        saves += RecordedSave(document, world, key)
        return key ?: LibraryKeys.of(document)
    }

    override suspend fun load(key: String): LoadOutcome =
        LoadOutcome.Refused(SaveRefusal(SaveProblem.UNREADABLE, "this library keeps nothing"))

    override suspend fun delete(key: String) = Unit
}

/** The desktop at 512, with [library] in place of the reader's, and no ice sheet from the card. */
private class RecordingPlatform(
    override val library: WorldLibrary,
    private val desktop: Platform = DesktopPlatform()
) : Platform by desktop {
    override val defaultResolution: Int = 512
    override val libraryLocation: String = "a test's recording library"
    override val iceAccelerator: IceSheetAccelerator? = null
}
