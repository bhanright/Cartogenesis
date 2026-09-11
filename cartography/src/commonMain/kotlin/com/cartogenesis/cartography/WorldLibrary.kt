package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.WorldMap

/**
 * Where saved worlds live.
 *
 * Only the bytes are platform-specific — the desktop has the user's home directory, the browser
 * has whatever storage it can offer. Everything about the *format* is shared, which is what stops
 * the two builds drifting into incompatible save files.
 *
 * [save] takes the world as well as the document, because a save now carries the world rather than
 * the recipe for it; passing null still writes a valid save, which simply regenerates on open.
 */
interface WorldLibrary {
    /** Headers only. A listing must never expand a payload — a 1024 save is tens of megabytes. */
    fun list(): List<WorldDocument>

    fun save(document: WorldDocument, world: WorldMap?)

    fun load(id: String): WorldSave?

    fun delete(id: String)
}

/**
 * A [WorldLibrary] over anything that can read and write named byte blobs.
 *
 * Both platforms are just named blobs, so they share this and supply the primitives. A platform
 * that can read part of a blob should override [readPrefix]: the header sits at the front, so a
 * listing need never touch the arrays behind it.
 *
 * Version-2 saves — `<id>.json`, seed and settings only — are listed and opened alongside the new
 * ones. Saving replaces one with a full container and removes the old file, so nothing has to be
 * migrated by hand and nothing is lost if it never is.
 */
abstract class ByteWorldLibrary(
    private val compressor: Compressor = NoCompression,
    private val writtenBy: String = "unknown"
) : WorldLibrary {

    protected abstract fun names(): List<String>

    protected abstract fun read(name: String): ByteArray?

    protected abstract fun write(name: String, bytes: ByteArray)

    protected abstract fun remove(name: String)

    /**
     * The first [limit] bytes, or the whole blob if this platform cannot read part of one.
     *
     * Returning everything is correct but slow to list a library of large worlds, which is the
     * one thing a listing must not be.
     */
    protected open fun readPrefix(name: String, limit: Int): ByteArray? = read(name)

    private fun fileName(id: String) = "$id$EXTENSION"

    private fun legacyFileName(id: String) = "$id$LEGACY_EXTENSION"

    override fun list(): List<WorldDocument> =
        names().mapNotNull { header(it)?.document }.sortedByDescending { it.savedAt }

    override fun save(document: WorldDocument, world: WorldMap?) {
        write(fileName(document.id), WorldCodec.encode(document, world, compressor, writtenBy))
        // A world first saved by an older build leaves a version-2 file behind, which would then
        // show up in the library a second time under the same name.
        remove(legacyFileName(document.id))
    }

    override fun load(id: String): WorldSave? {
        read(fileName(id))?.let { return WorldCodec.decodeOrNull(it, compressor) }
        val legacy = read(legacyFileName(id)) ?: return null
        return WorldCodec.decodeTextOrNull(legacy.decodeToString())?.let { WorldSave(it, null) }
    }

    override fun delete(id: String) {
        remove(fileName(id))
        remove(legacyFileName(id))
    }

    /**
     * Reads a blob's header without its payload.
     *
     * The header's length is in the prefix, so this asks for a probe first and only asks again
     * when the header turned out to be longer than the probe — two reads at worst, neither of
     * them the arrays.
     */
    private fun header(name: String): SaveHeader? {
        val probe = readPrefix(name, HEADER_PROBE_BYTES) ?: return null
        // A version-2 save is JSON all the way down, so there is no prefix to stop at — and one
        // carrying a GPU terrain runs to several megabytes, well past the probe.
        if (!WorldCodec.isContainer(probe)) {
            return WorldCodec.decodeHeaderOrNull(read(name) ?: return null)
        }

        val declared = ByteReader(probe, position = 8).getInt()
        val needed = WorldCodec.PREFIX_BYTES + declared
        val bytes = if (probe.size >= needed) probe else readPrefix(name, needed) ?: return null
        return WorldCodec.decodeHeaderOrNull(bytes)
    }

    companion object {
        /** A full-world save. */
        const val EXTENSION = ".cgw"

        /** A version-2 save: JSON text, seed and settings only. */
        const val LEGACY_EXTENSION = ".json"

        /**
         * Enough for a header on any world worth saving — the lists in it are realms, rivers and
         * landmarks, not cells — and small enough that reading it costs nothing.
         */
        const val HEADER_PROBE_BYTES = 1 shl 20
    }
}
