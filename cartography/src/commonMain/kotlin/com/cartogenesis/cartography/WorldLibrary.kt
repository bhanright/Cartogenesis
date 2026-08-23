package com.cartogenesis.cartography

import kotlinx.serialization.json.Json

/**
 * Turns a saved world into text and back, identically on every platform.
 *
 * The format is derived straight from the `@Serializable` data classes, so a new setting is
 * saved the moment it is added to the config — there is no parallel schema to forget to update.
 *
 * [Json.ignoreUnknownKeys] and [Json.encodeDefaults] together are what make old saves keep
 * opening: a field that no longer exists is skipped rather than throwing, and a field that did
 * not exist when the file was written falls back to its current default.
 */
object WorldCodec {

    const val FORMAT_VERSION = 2

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(document: WorldDocument): String = json.encodeToString(document)

    fun decode(text: String): WorldDocument = json.decodeFromString(text)

    /** Null rather than throwing, so one unreadable file cannot take the whole library down. */
    fun decodeOrNull(text: String): WorldDocument? = runCatching { decode(text) }.getOrNull()
}

/**
 * Where saved worlds live.
 *
 * Only the bytes are platform-specific — Android has an app-private directory, desktop has the
 * user's home. Everything about the *format* is shared, which is what stops the two builds
 * drifting into incompatible save files.
 */
interface WorldLibrary {
    fun list(): List<WorldDocument>
    fun save(document: WorldDocument)
    fun load(id: String): WorldDocument?
    fun delete(id: String)
}

/**
 * A [WorldLibrary] over anything that can read and write named text blobs.
 *
 * Both platforms are just files on disk, so they share this and supply the four primitives.
 */
abstract class TextWorldLibrary : WorldLibrary {

    protected abstract fun names(): List<String>
    protected abstract fun read(name: String): String?
    protected abstract fun write(name: String, text: String)
    protected abstract fun remove(name: String)

    private fun fileName(id: String) = "$id.json"

    override fun list(): List<WorldDocument> =
        names().mapNotNull { name -> read(name)?.let { WorldCodec.decodeOrNull(it) } }
            .sortedByDescending { it.savedAt }

    override fun save(document: WorldDocument) {
        write(fileName(document.id), WorldCodec.encode(document))
    }

    override fun load(id: String): WorldDocument? =
        read(fileName(id))?.let { WorldCodec.decodeOrNull(it) }

    override fun delete(id: String) = remove(fileName(id))
}
