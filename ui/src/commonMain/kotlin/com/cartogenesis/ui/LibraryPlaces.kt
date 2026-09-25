package com.cartogenesis.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cartogenesis.cartography.ByteWorldLibrary
import com.cartogenesis.cartography.WorldLibrary
import kotlin.coroutines.cancellation.CancellationException

/** What a browser answers, without asking the reader, about reading and writing in a chosen folder. */
enum class FolderPermission { GRANTED, PROMPT, DENIED }

/**
 * A folder on the disk the reader chose to keep the library in, as a browser holds one: a name and
 * a handle, never a path, which a page is not told.
 */
interface LibraryFolder {
    /** The folder's own name, the one thing about where it is that a browser tells a page. */
    val name: String

    /** The worlds in it: its `.cgw` files, under their own names, as the desktop keeps them. */
    val library: WorldLibrary

    /** Whether the page may read and write here now, asked without troubling the reader. */
    suspend fun permission(): FolderPermission

    /**
     * Asks the reader for leave to read and write here, and returns their answer.
     *
     * A browser allows this only while it is handling the reader's click, so a caller makes this
     * call first, before anything else that could suspend; see [LibraryPlaces.reconnect].
     */
    suspend fun requestPermission(): FolderPermission

    /**
     * Null when the folder can be listed now, or the reason it cannot: it has been deleted or
     * moved, or the disk it is on is not there. Leave to use a folder does not prove the folder is
     * still where it was, so this is asked as well as [permission].
     */
    suspend fun unreachableBecause(): String?
}

/** The host's side of keeping the library in a folder: its folder picker, and memory of the choice. */
interface FolderChooser {
    /**
     * Opens the host's folder picker and returns the folder chosen, with leave to read and write in
     * it, or null when the reader cancelled. Allowed only while the host is handling the reader's
     * click, like [LibraryFolder.requestPermission].
     */
    suspend fun pick(): LibraryFolder?

    /** The choice as it was last remembered, or [RememberedPlace.NOTHING]. */
    suspend fun remembered(): RememberedPlace

    /** Keeps [place] for the next visit. */
    suspend fun remember(place: RememberedPlace)
}

/**
 * The reader's choice as it is kept between visits: the [folder] last chosen, if any, and whether
 * the library is [inFolder] or in the host's own storage.
 *
 * Two facts rather than one because "use this browser's storage" is a choice of its own: a reader
 * who went back to the browser's storage keeps the folder they left, to be offered again by name,
 * and the next visit must not read the folder's presence as a choice to use it.
 */
data class RememberedPlace(val folder: LibraryFolder?, val inFolder: Boolean) {
    companion object {
        val NOTHING = RememberedPlace(folder = null, inFolder = false)
    }
}

/** Where the library is, as the interface draws it. */
sealed interface LibraryPlace {

    /**
     * The host's own storage: the desktop's folder, or this browser's storage. [folder] is a folder
     * chosen before and left, offered again by name; null when there has never been one.
     */
    data class HostStorage(val folder: LibraryFolder?) : LibraryPlace

    /** The reader's folder, with leave to read and write in it. */
    data class InFolder(val folder: LibraryFolder) : LibraryPlace

    /**
     * The reader's folder is still the choice, and the browser wants to be told so again, which it
     * asks on a new visit to a page: nothing is listed, saved or deleted until the reader
     * reconnects or chooses the browser's storage. The host's storage does not stand in, because a
     * reader who saved into it without noticing would find those worlds missing from their folder.
     */
    data class ReconnectNeeded(val folder: LibraryFolder) : LibraryPlace

    /**
     * The reader's folder is still the choice and cannot be used — leave was refused, or the folder
     * is not where it was — for [reason]. The host's storage stands in and the interface says so;
     * the choice is kept, and offered again by name.
     */
    data class FolderUnavailable(val folder: LibraryFolder, val reason: String) : LibraryPlace
}

/**
 * Which library the interface is using, and the reader's ways of changing it.
 *
 * On a host with no [Platform.folderChooser] this is the platform's own library and nothing else,
 * and every call here but [library] does nothing. Where there is one, the library is either the
 * host's storage or a folder the reader chose, and the choice survives the visit: [start] reads it
 * back and decides, without asking the reader anything, which of the four [LibraryPlace]s the
 * library is in.
 *
 * [place] and [started] are snapshot state, so the pane follows them without being told.
 */
class LibraryPlaces(private val platform: Platform) {

    private val chooser: FolderChooser? = platform.folderChooser

    /** Whether the reader can choose a folder here at all. */
    val offersFolders: Boolean get() = chooser != null

    var place: LibraryPlace by mutableStateOf(LibraryPlace.HostStorage(folder = null))
        private set

    /**
     * Whether [start] has decided where the library is. Until it has, [library] is null, so the
     * first listing is of the place the reader chose and never of the browser's storage in passing.
     */
    var started: Boolean by mutableStateOf(chooser == null)
        private set

    /**
     * The library to list, open, save and delete in, or null while there is none to use: before
     * [start] has finished, and while a folder waits to be reconnected.
     */
    val library: WorldLibrary?
        get() = if (!started) null else when (val at = place) {
            is LibraryPlace.InFolder -> at.folder.library
            is LibraryPlace.ReconnectNeeded -> null
            is LibraryPlace.HostStorage, is LibraryPlace.FolderUnavailable -> platform.library
        }

    /** Where the worlds are, as the end of a sentence: "Files live in …". */
    val location: String
        get() = when (val at = place) {
            is LibraryPlace.InFolder -> "the folder \"${at.folder.name}\" on this computer."
            is LibraryPlace.ReconnectNeeded -> "the folder \"${at.folder.name}\", once it is reconnected."
            is LibraryPlace.HostStorage, is LibraryPlace.FolderUnavailable -> platform.libraryLocation
        }

    /** The folder the reader chose, in whichever state it is in, or null when there is none. */
    val folder: LibraryFolder?
        get() = when (val at = place) {
            is LibraryPlace.InFolder -> at.folder
            is LibraryPlace.ReconnectNeeded -> at.folder
            is LibraryPlace.FolderUnavailable -> at.folder
            is LibraryPlace.HostStorage -> at.folder
        }

    /**
     * How many times the reader has asked for the library to move — started it, chosen a folder,
     * reconnected, gone back to the host's storage. Each such request takes the next number, and
     * every change of [place] that comes after a wait is made only if no request has been made
     * since the one it belongs to: a slow answer about a folder left behind must never undo a
     * choice the reader has made meanwhile.
     */
    private var requests = 0L

    private fun newRequest(): Long = ++requests

    private fun isCurrent(request: Long): Boolean = request == requests

    /**
     * Reads the remembered choice and settles where the library is, asking the reader nothing.
     * Returns a line for the status bar when the reader's folder could not be used as it was left,
     * or null.
     */
    suspend fun start(): String? {
        val chooser = chooser ?: return null
        val request = newRequest()
        val remembered = try {
            chooser.remembered()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            RememberedPlace.NOTHING
        }
        val folder = remembered.folder
        val notice = when {
            !isCurrent(request) -> null
            folder != null && remembered.inFolder -> settle(folder, request)
            else -> {
                place = LibraryPlace.HostStorage(folder)
                null
            }
        }
        started = true
        return notice
    }

    /**
     * Opens the host's folder picker and, if the reader chose a folder, moves the library there
     * and remembers it. Returns the line the status bar says, or null when a later request has
     * taken its place.
     *
     * The picker is the first thing asked for, before anything else that could suspend: a browser
     * opens it only while it is still handling the click that asked.
     */
    suspend fun choose(): String? {
        val chooser = chooser ?: return "This browser cannot keep the library in a folder."
        // Numbered when the click asks, not when the picker answers: the reader may have asked for
        // something else while the picker was open, and that later request is the one that stands.
        val request = newRequest()
        val picked = try {
            chooser.pick()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return "Could not open the folder picker: ${failure.message ?: failure::class.simpleName}"
        } ?: return "No folder chosen; the library is where it was."
        val notice = settle(picked, request)
        if (!isCurrent(request)) return null
        rememberQuietly(RememberedPlace(picked, inFolder = true))
        return notice ?: "The library is now the folder \"${picked.name}\"."
    }

    /**
     * Asks the reader again for leave to use the folder they chose, and uses it if they give it.
     * Returns the line the status bar says, or null when a later request has taken its place.
     *
     * The request is the first thing this does, for the same reason [choose] opens its picker
     * first. A reader who dismisses the question is left where they were; one who refuses gets the
     * browser's storage in its place, saying so, with the folder still their choice.
     */
    suspend fun reconnect(): String? {
        val folder = folder ?: return "There is no folder to reconnect to."
        // Numbered when the click asks, for the reason [choose] gives: a slow answer from the
        // browser must not outrank a choice the reader made while it was being given.
        val request = newRequest()
        val answer = try {
            folder.requestPermission()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // The browser would not ask — most often because the click's moment had passed — which
            // is not the reader refusing, so nothing moves and they can click again.
            return "Could not ask for the folder \"${folder.name}\": ${failure.message ?: failure::class.simpleName}"
        }
        if (!isCurrent(request)) return null
        return when (answer) {
            FolderPermission.GRANTED -> {
                val notice = settle(folder, request)
                if (!isCurrent(request)) return null
                rememberQuietly(RememberedPlace(folder, inFolder = true))
                notice ?: "The library is the folder \"${folder.name}\" again."
            }
            FolderPermission.PROMPT -> "The folder \"${folder.name}\" is still waiting to be reconnected."
            FolderPermission.DENIED -> {
                place = LibraryPlace.FolderUnavailable(folder, REFUSED)
                rememberQuietly(RememberedPlace(folder, inFolder = true))
                unavailableNotice(folder, REFUSED)
            }
        }
    }

    /**
     * Moves the library back to the host's storage, and remembers that as the choice, keeping the
     * folder to offer again. The worlds in the folder stay in it. Returns the status line.
     */
    suspend fun useHostStorage(): String? {
        newRequest()
        val left = folder
        place = LibraryPlace.HostStorage(left)
        rememberQuietly(RememberedPlace(left, inFolder = false))
        return if (left == null) "The library is in this browser's storage."
        else "The library is in this browser's storage; the worlds in \"${left.name}\" stay there."
    }

    /**
     * After an operation on [failed] went wrong: asks again whether the folder can be used, and
     * moves the library out of it when it cannot — leave withdrawn since the visit began, or the
     * folder deleted under the page. Returns a status line when the place changed, or null.
     *
     * Not a request of the reader's, so it takes no number of its own: it acts for whichever request
     * put the library where it is, and stands down if any other is made before its answer comes.
     */
    suspend fun afterFailure(failed: WorldLibrary): String? {
        val at = place as? LibraryPlace.InFolder ?: return null
        if (at.folder.library !== failed) return null
        return settle(at.folder, requests)
    }
    /**
     * How many worlds in the host's storage the folder does not hold yet, for the offer to copy
     * them into it; zero when there is no folder in use or either cannot be listed.
     */
    suspend fun hostWorldCount(): Int {
        val at = place as? LibraryPlace.InFolder ?: return 0
        return try {
            uncopied(at.folder).size
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            0
        }
    }

    /**
     * The keys of the host's worlds that [folder] holds no copy of: none of its files has the same
     * document saved at the same moment. Matched by what is in the file rather than by its name,
     * because a folder another machine writes may hold a different world under the same name.
     */
    private suspend fun uncopied(folder: LibraryFolder): List<String> {
        val held = folder.library.list().mapNotNull { entry -> entry.document?.let { it.id to it.savedAt } }.toSet()
        return platform.library.list()
            .filter { entry -> entry.document?.let { (it.id to it.savedAt) !in held } ?: true }
            .map { it.key }
    }

    /**
     * Copies each world in the host's storage that the folder has no copy of into it, each as a new
     * file beside whatever the folder holds and never over it, and leaves the originals where they
     * were. Returns the status line, and whether it is a failure.
     */
    suspend fun copyHostWorldsIntoFolder(): CopyOutcome {
        val at = place as? LibraryPlace.InFolder
            ?: return CopyOutcome("Choose a folder to copy the worlds into first.", failed = true)
        val source = platform.library as? ByteWorldLibrary
        val target = at.folder.library as? ByteWorldLibrary
        if (source == null || target == null) return CopyOutcome("These worlds cannot be copied here.", failed = true)
        val keys = uncopied(at.folder)
        var copied = 0
        for (key in keys) {
            try {
                target.copyFrom(source, key)
                copied++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                afterFailure(target)
                return CopyOutcome(
                    "Copied $copied of ${keys.size} worlds into \"${at.folder.name}\"; $key could not be " +
                        "copied: ${failure.message ?: failure::class.simpleName}",
                    failed = true
                )
            }
        }
        return CopyOutcome(
            "Copied ${worlds(copied)} into \"${at.folder.name}\". They are still in this browser's storage too.",
            failed = false
        )
    }

    /** What a copy into the folder said, and whether it stopped short. */
    data class CopyOutcome(val line: String, val failed: Boolean)

    /**
     * Where [folder] leaves the library, decided without asking the reader: in it, waiting to be
     * reconnected, or standing in for it with the host's storage. Returns a status line for the
     * last two, and null when the folder is in use — or when [request] is no longer the current
     * one by the time the browser has answered, in which case nothing is changed at all.
     */
    private suspend fun settle(folder: LibraryFolder, request: Long): String? {
        val permission = try {
            folder.permission()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            FolderPermission.DENIED
        }
        if (!isCurrent(request)) return null
        when (permission) {
            FolderPermission.PROMPT -> {
                place = LibraryPlace.ReconnectNeeded(folder)
                return "Reconnect to the folder \"${folder.name}\" to see the worlds in it."
            }
            FolderPermission.DENIED -> {
                place = LibraryPlace.FolderUnavailable(folder, REFUSED)
                return unavailableNotice(folder, REFUSED)
            }
            FolderPermission.GRANTED -> {
                val unreachable = try {
                    folder.unreachableBecause()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    failure.message ?: failure::class.simpleName.orEmpty()
                }
                if (!isCurrent(request)) return null
                if (unreachable != null) {
                    place = LibraryPlace.FolderUnavailable(folder, unreachable)
                    return unavailableNotice(folder, unreachable)
                }
                place = LibraryPlace.InFolder(folder)
                return null
            }
        }
    }

    private fun unavailableNotice(folder: LibraryFolder, reason: String): String =
        "The folder \"${folder.name}\" cannot be used ($reason); worlds are kept in this browser's storage until it can."

    /** A choice not remembered costs the next visit a click; it is no reason to stop this one. */
    private suspend fun rememberQuietly(place: RememberedPlace) {
        val chooser = chooser ?: return
        try {
            chooser.remember(place)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // Nothing to do: this visit goes on with the choice it has.
        }
    }

    private fun worlds(count: Int): String = if (count == 1) "1 world" else "$count worlds"

    private companion object {
        /** Why a folder whose permission reads `denied` is not in use. */
        const val REFUSED = "this browser was refused leave to use it"
    }
}
