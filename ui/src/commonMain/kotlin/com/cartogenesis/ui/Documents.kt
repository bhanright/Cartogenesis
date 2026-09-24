package com.cartogenesis.ui

import com.cartogenesis.cartography.WorldDocument
import com.cartogenesis.cartography.WorldOverrides
import com.cartogenesis.worldgen.model.MapLabel
import com.cartogenesis.worldgen.model.WorldMap

/**
 * Which document the world on screen belongs to: its [id], the library [key] it was last opened
 * from or saved to (null until it has been either), and the [seed] of the world it holds.
 *
 * The rule is the one the library pane states — "saving under the same name updates it in place" —
 * and the one the cartouche already follows for the name: a settings edit at the same seed is the
 * same document, and a world at another seed is a new one. So Save after Random world writes a new
 * file beside the last, rather than over it, and Save after opening a file writes back to that
 * file, whatever it is called.
 */
internal data class DocumentIdentity(val id: String, val key: String?, val seed: Long?) {

    /**
     * After a generation made [madeSeed]'s world: this document if the seed is its own or it had
     * none yet, and otherwise a new document with [freshId] and nowhere saved.
     */
    fun afterGenerating(madeSeed: Long, freshId: () -> String): DocumentIdentity =
        if (seed == null || seed == madeSeed) copy(seed = madeSeed) else DocumentIdentity(freshId(), null, madeSeed)

    /** Save as: the same world under a new document, so the one already in the library stays. */
    fun savedAs(freshId: () -> String): DocumentIdentity = DocumentIdentity(freshId(), null, seed)

    /** Saved to, or opened from, [writtenKey]: the next Save goes there too. */
    fun at(writtenKey: String): DocumentIdentity = copy(key = writtenKey)

    companion object {
        /**
         * A save opened from the library's [key], which Save writes back to; or, when [key] is
         * null, a file from outside the library, which is a new document with [freshId] and
         * nowhere saved.
         *
         * An imported file keeps no id of its own because the id it carries may be the id of a save
         * already in the library — a copy downloaded and brought back, or a copy from another
         * machine — and its first Save must never write over that save. The library files a new
         * document under a name no other file has.
         */
        fun opened(document: WorldDocument, key: String?, freshId: () -> String): DocumentIdentity =
            if (key != null) DocumentIdentity(document.id, key, document.config.seed)
            else DocumentIdentity(freshId(), null, document.config.seed)
    }
}

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
