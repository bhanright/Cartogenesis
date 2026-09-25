package com.cartogenesis.ui

import com.cartogenesis.cartography.LibraryEntry
import com.cartogenesis.cartography.LoadOutcome
import com.cartogenesis.cartography.SaveProblem
import com.cartogenesis.cartography.SaveRefusal
import com.cartogenesis.cartography.WorldDocument
import com.cartogenesis.cartography.WorldLibrary
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Where the library is, decided from what the host remembers and what it answers about a folder,
 * with the folder and the host played by fakes: a real folder's permission cannot be set by a test,
 * and the origin private file system the browser tests use always answers `granted`.
 *
 * The case that matters most is the one a reader meets every morning: a folder chosen yesterday,
 * which today's visit has not yet been given leave to use. It must wait to be reconnected, not
 * quietly become this browser's storage, or the worlds saved that day go somewhere the reader is
 * not looking.
 */
class LibraryPlacesTest {

    @Test
    fun `a remembered folder whose permission reads prompt waits to be reconnected`() = runTest {
        val folder = FakeFolder("Maps", permission = FolderPermission.PROMPT)
        val platform = FolderPlatform(FakeChooser(RememberedPlace(folder, inFolder = true)))
        val places = LibraryPlaces(platform)
        assertNull(places.library, "a library was offered before the remembered choice was read")

        val notice = places.start()
        assertEquals(LibraryPlace.ReconnectNeeded(folder), places.place)
        assertNull(places.library, "the browser's storage stood in for a folder waiting to be reconnected")
        assertTrue(notice.orEmpty().contains("Reconnect to the folder \"Maps\""), notice)
        assertEquals(0, folder.requests, "the reader was asked without clicking anything")
    }

    @Test
    fun `reconnecting asks the reader first and uses the folder once they agree`() = runTest {
        val folder = FakeFolder("Maps", permission = FolderPermission.PROMPT, answer = FolderPermission.GRANTED)
        val chooser = FakeChooser(RememberedPlace(folder, inFolder = true))
        val places = LibraryPlaces(FolderPlatform(chooser))
        places.start()
        folder.calls.clear()

        places.reconnect()
        assertEquals(1, folder.requests)
        assertEquals("requestPermission", folder.calls.first(), "something was asked before the permission")
        assertEquals(LibraryPlace.InFolder(folder), places.place)
        assertSame(folder.library, places.library)
        assertEquals(RememberedPlace(folder, inFolder = true), chooser.stored)
    }

    @Test
    fun `a refused folder is said so, and this browser's storage stands in with the choice kept`() = runTest {
        val folder = FakeFolder("Maps", permission = FolderPermission.DENIED)
        val chooser = FakeChooser(RememberedPlace(folder, inFolder = true))
        val platform = FolderPlatform(chooser)
        val places = LibraryPlaces(platform)

        val notice = places.start()
        val place = assertIs<LibraryPlace.FolderUnavailable>(places.place)
        assertSame(folder, place.folder)
        assertSame(platform.library, places.library)
        assertTrue(notice.orEmpty().contains("cannot be used"), notice)
        assertEquals(RememberedPlace(folder, inFolder = true), chooser.stored, "the choice was forgotten")
    }

    @Test
    fun `a folder with leave to use it that is no longer there is not used`() = runTest {
        val folder = FakeFolder("Maps", permission = FolderPermission.GRANTED, unreachable = "it is no longer where it was")
        val places = LibraryPlaces(FolderPlatform(FakeChooser(RememberedPlace(folder, inFolder = true))))
        places.start()
        assertEquals(LibraryPlace.FolderUnavailable(folder, "it is no longer where it was"), places.place)
    }

    @Test
    fun `choosing this browser's storage is remembered as a choice of its own`() = runTest {
        // A handle left in memory must not undo the choice at the next visit.
        val folder = FakeFolder("Maps", permission = FolderPermission.GRANTED)
        val chooser = FakeChooser(RememberedPlace(folder, inFolder = true))
        val platform = FolderPlatform(chooser)
        val places = LibraryPlaces(platform)
        places.start()
        assertEquals(LibraryPlace.InFolder(folder), places.place)

        places.useHostStorage()
        assertEquals(LibraryPlace.HostStorage(folder), places.place)
        assertSame(platform.library, places.library)
        assertEquals(RememberedPlace(folder, inFolder = false), chooser.stored)

        val nextVisit = LibraryPlaces(FolderPlatform(FakeChooser(chooser.stored)))
        nextVisit.start()
        assertEquals(LibraryPlace.HostStorage(folder), nextVisit.place)
    }

    @Test
    fun `choosing opens the picker before anything else and remembers the folder`() = runTest {
        val picked = FakeFolder("Atlas", permission = FolderPermission.GRANTED)
        val chooser = FakeChooser(RememberedPlace.NOTHING, picks = picked)
        val places = LibraryPlaces(FolderPlatform(chooser))
        places.start()
        chooser.calls.clear()

        places.choose()
        assertEquals("pick", chooser.calls.first())
        assertEquals(LibraryPlace.InFolder(picked), places.place)
        assertEquals(RememberedPlace(picked, inFolder = true), chooser.stored)

        // A cancelled picker leaves everything where it was.
        val cancelling = FakeChooser(RememberedPlace(picked, inFolder = true), picks = null)
        val unchanged = LibraryPlaces(FolderPlatform(cancelling))
        unchanged.start()
        unchanged.choose()
        assertEquals(LibraryPlace.InFolder(picked), unchanged.place)
    }

    @Test
    fun `leave withdrawn during the visit moves the library out of the folder`() = runTest {
        val folder = FakeFolder("Maps", permission = FolderPermission.GRANTED)
        val places = LibraryPlaces(FolderPlatform(FakeChooser(RememberedPlace(folder, inFolder = true))))
        places.start()
        assertSame(folder.library, places.library)

        folder.permission = FolderPermission.PROMPT
        assertNull(places.afterFailure(MemoryLibrary()), "another library's failure moved this one")
        assertEquals(LibraryPlace.InFolder(folder), places.place)
        val notice = places.afterFailure(folder.library)
        assertEquals(LibraryPlace.ReconnectNeeded(folder), places.place)
        assertTrue(notice.orEmpty().contains("Reconnect"), notice)
    }

    @Test
    fun `the offer to copy counts only the worlds the folder has no copy of`() = runTest {
        // Offered by count of everything in this browser's storage, it went on offering the same
        // world after it had been copied, and each click made another copy.
        val folder = FakeFolder("Maps", permission = FolderPermission.GRANTED)
        val platform = FolderPlatform(FakeChooser(RememberedPlace(folder, inFolder = true)))
        val places = LibraryPlaces(platform)
        places.start()
        val config = WorldGenConfig(seed = 1L)
        val shared = WorldDocument(id = "shared", title = "Copied already", config = config, savedAt = 5L)
        (platform.library as MemoryLibrary).documents["shared.cgw"] = shared
        (platform.library as MemoryLibrary).documents["only-here.cgw"] = WorldDocument(id = "only-here", title = "Not yet", config = config, savedAt = 6L)
        (folder.library as MemoryLibrary).documents["shared (2).cgw"] = shared
        // Another world under a name the browser's storage also uses is not a copy of it.
        (folder.library as MemoryLibrary).documents["only-here.cgw"] = WorldDocument(id = "only-here", title = "Another", config = config, savedAt = 99L)
        assertEquals(1, places.hostWorldCount())

        places.useHostStorage()
        assertEquals(0, places.hostWorldCount(), "a copy was offered with no folder in use")
    }

    @Test
    fun `without a folder chooser the library is the platform's and nothing else changes`() = runTest {
        val platform = FakePlatform()
        val places = LibraryPlaces(platform)
        assertFalse(places.offersFolders)
        assertTrue(places.started, "a host with no choice to read waited for one")
        assertSame(platform.library, places.library)
        assertNull(places.start())
        assertEquals(LibraryPlace.HostStorage(folder = null), places.place)
        assertEquals(platform.libraryLocation, places.location)
    }
}

/** A host whose library can also live in a folder, through [chooser]. */
internal class FolderPlatform(chooser: FolderChooser) : FakePlatform() {
    override val library: WorldLibrary = MemoryLibrary()
    override val folderChooser: FolderChooser = chooser
    override val libraryLocation: String = "this browser's storage, in a test"
}

/** A folder whose permission, and the answer it gives when asked, the test sets. */
internal class FakeFolder(
    override val name: String,
    var permission: FolderPermission,
    private val answer: FolderPermission = permission,
    private val unreachable: String? = null
) : LibraryFolder {
    override val library: WorldLibrary = MemoryLibrary()
    val calls = mutableListOf<String>()
    val requests: Int get() = calls.count { it == "requestPermission" }

    override suspend fun permission(): FolderPermission {
        calls += "permission"
        return permission
    }

    override suspend fun requestPermission(): FolderPermission {
        calls += "requestPermission"
        permission = answer
        return answer
    }

    override suspend fun unreachableBecause(): String? {
        calls += "unreachableBecause"
        return unreachable
    }
}

/** A chooser that remembers in memory and whose picker hands back [picks]. */
internal class FakeChooser(
    var stored: RememberedPlace,
    private val picks: LibraryFolder? = null
) : FolderChooser {
    val calls = mutableListOf<String>()

    override suspend fun pick(): LibraryFolder? {
        calls += "pick"
        return picks
    }

    override suspend fun remembered(): RememberedPlace {
        calls += "remembered"
        return stored
    }

    override suspend fun remember(place: RememberedPlace) {
        calls += "remember"
        stored = place
    }
}

/** A library of documents in memory, to tell one library from another by what it holds. */
internal class MemoryLibrary : WorldLibrary {
    val documents = LinkedHashMap<String, WorldDocument>()

    override suspend fun list(): List<LibraryEntry> = documents.map { (key, document) -> LibraryEntry(key, document) }

    override suspend fun save(document: WorldDocument, world: WorldMap, key: String?): String {
        val name = key ?: "${document.id}.cgw"
        documents[name] = document
        return name
    }

    override suspend fun load(key: String): LoadOutcome =
        LoadOutcome.Refused(SaveRefusal(SaveProblem.UNREADABLE, "a memory library keeps no worlds"))

    override suspend fun delete(key: String) {
        documents.remove(key)
    }
}
