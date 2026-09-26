package com.cartogenesis.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cartogenesis.cartography.WorldDocument
import com.cartogenesis.cartography.WorldLibrary
import com.cartogenesis.cartography.WorldOverrides
import com.cartogenesis.worldgen.model.MapLabel
import com.cartogenesis.worldgen.model.WorldMap

/**
 * Which document the world on screen belongs to: its [id], the library [key] it was last opened
 * from or saved to (null until it has been either), the [library] that key names a file in, and
 * the [seed] of the world it holds.
 *
 * The rule is the one the library pane states — "saving under the same name updates it in place" —
 * and the one the cartouche already follows for the name: a settings edit at the same seed is the
 * same document, and a world at another seed is a new one. So Save after Random world writes a new
 * file beside the last, rather than over it, and Save after opening a file writes back to that
 * file, whatever it is called.
 *
 * A key names a file in one library and nothing in any other. The library can move — the desktop's
 * to another folder, the browser's between its own storage and a folder on the disk — and a key
 * carried across would be written over whatever file of that name the other place holds, which is
 * as likely as not somebody else's world. So the key is kept with the library it came from, and a
 * Save into any other library files the world as new: see [keyIn].
 */
internal data class DocumentIdentity(
    val id: String,
    val key: String?,
    val seed: Long?,
    val library: WorldLibrary? = null
) {

    /**
     * After a generation made [madeSeed]'s world: this document if the seed is its own or it had
     * none yet, and otherwise a new document with [freshId] and nowhere saved.
     */
    fun afterGenerating(madeSeed: Long, freshId: () -> String): DocumentIdentity =
        if (seed == null || seed == madeSeed) copy(seed = madeSeed) else DocumentIdentity(freshId(), null, madeSeed)

    /** Save as: the same world under a new document, so the one already in the library stays. */
    fun savedAs(freshId: () -> String): DocumentIdentity = DocumentIdentity(freshId(), null, seed)

    /** Saved to, or opened from, [writtenKey] in [into]: the next Save there goes there too. */
    fun at(writtenKey: String, into: WorldLibrary): DocumentIdentity = copy(key = writtenKey, library = into)

    /**
     * Where a Save into [destination] writes this document: its key when the key names a file in
     * [destination], and otherwise null, which is a new file and never replaces another.
     */
    fun keyIn(destination: WorldLibrary): String? = key?.takeIf { library === destination }

    companion object {
        /**
         * A save opened from [from]'s [key], which Save writes back to; or, when either is null, a
         * file from outside the library, which is a new document with [freshId] and nowhere saved.
         *
         * An imported file keeps no id of its own because the id it carries may be the id of a save
         * already in the library — a copy downloaded and brought back, or a copy from another
         * machine — and its first Save must never write over that save. The library files a new
         * document under a name no other file has.
         */
        fun opened(document: WorldDocument, key: String?, from: WorldLibrary?, freshId: () -> String): DocumentIdentity =
            if (key != null && from != null) DocumentIdentity(document.id, key, document.config.seed, from)
            else DocumentIdentity(freshId(), null, document.config.seed)
    }
}

/**
 * The document on screen, and which opening of it this is.
 *
 * A save runs while the reader goes on working, and when it finishes it records where it wrote
 * only if the document on screen is still the one it saved. The id cannot tell: a sync client's
 * conflict copy carries the id of the file it was copied from, so opening that copy while a Save of
 * the file was under way gave the copy the file's key when the Save finished, and the copy's next
 * Save wrote over the file. So every opening — a new world, a file opened, Save as — is a session
 * of its own, and a save belongs to the session it was asked in.
 */
internal class OpenDocument(initial: DocumentIdentity) {

    var identity: DocumentIdentity by mutableStateOf(initial)
        private set

    private var session = 0L

    /** Another document than the last: opened, made new, or saved as. */
    fun becomes(next: DocumentIdentity) {
        identity = next
        session++
    }

    /** After a generation made [madeSeed]'s world: the same session while it is the same document. */
    fun afterGenerating(madeSeed: Long, freshId: () -> String) {
        val next = identity.afterGenerating(madeSeed, freshId)
        if (next.id == identity.id) identity = next else becomes(next)
    }

    /** What a save asked for now carries with it to the end. */
    fun saving(): SaveTicket = SaveTicket(session, identity)

    /**
     * Where a save of [ticket] writes in [destination], read when the write starts: the document's
     * key there as it is now, while the document is the one the save was asked for — an earlier
     * Save of it may have finished while this one waited — and the ticket's own otherwise.
     */
    fun keyFor(ticket: SaveTicket, destination: WorldLibrary): String? =
        (if (ticket.session == session) identity else ticket.identity).keyIn(destination)

    /** A save of [ticket] wrote [key] in [into]: recorded only if the document is still that one. */
    fun saved(ticket: SaveTicket, key: String, into: WorldLibrary) {
        if (ticket.session == session) identity = identity.at(key, into)
    }
}

/** A save's claim on the document it was asked for: which opening, and its identity then. */
internal class SaveTicket(val session: Long, val identity: DocumentIdentity)

/**
 * The document Save, Save as and Download file [world] under.
 *
 * The settings are [world]'s own, never the panel's: after a stopped generation, or while one is
 * running, the panel holds settings no world on screen was made with, and a save that filed them
 * with this world would reopen as a world nobody made — or, after a change of resolution, not open
 * at all. The codec refuses such a pair, and this is what keeps the application from offering one.
 */
internal fun documentFor(
    world: WorldMap,
    identity: DocumentIdentity,
    title: String,
    overrides: WorldOverrides,
    labels: List<MapLabel>,
    savedAt: Long
): WorldDocument = WorldDocument(
    id = identity.id,
    title = title,
    config = world.config,
    overrides = overrides,
    labels = labels,
    savedAt = savedAt
)
