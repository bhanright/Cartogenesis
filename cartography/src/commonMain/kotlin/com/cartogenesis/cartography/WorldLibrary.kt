package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.WorldMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where saved worlds live.
 *
 * Only the bytes are platform-specific — the desktop has a folder, the browser has IndexedDB or,
 * where it offers the File System Access API, a folder the reader chose.
 * Everything about the *format* is shared, which is what stops the two builds drifting into
 * incompatible save files.
 *
 * Every save is found by its **key**: the name the library lists it under, which on the desktop is
 * the file's own name. Listing, opening, saving over and deleting all use that one key, so a file
 * whose name is not its world's id — a download from the browser, a copy the reader made, the
 * `world (1).cgw` a sync client makes when two machines edit one world — lists, opens and saves as
 * itself, and two files carrying the same world never write over each other.
 *
 * Every method suspends. The desktop never actually suspends on one — plain blocking file I/O —
 * but both of the browser's libraries, IndexedDB and a chosen folder, are asynchronous throughout,
 * so the interface has to be.
 */
interface WorldLibrary {
    /** Headers only. A listing must never expand a payload — a 1024 save is tens of megabytes. */
    suspend fun list(): List<LibraryEntry>

    /**
     * Writes [world] under [document] to [key], and returns the key it wrote.
     *
     * A null [key] is a new file, which never replaces one already in the library: it is named
     * for the document, or for the document and a number when that name is taken. The write
     * replaces the file whole or leaves it as it was, so a reader, or a sync client, never sees
     * half of one; and writes are made one at a time in the order they were asked for, so when two
     * saves of one file overlap the one asked for last is the one that stays.
     */
    suspend fun save(document: WorldDocument, world: WorldMap, key: String? = null): String

    /** The world saved under [key], or the reason it will not open. */
    suspend fun load(key: String): LoadOutcome

    suspend fun delete(key: String)
}

/**
 * One row of the library listing: the [key] it is found by, and either the [document] its header
 * holds or the [refusal] that says why the header could not be read. A file that will not open is
 * listed with its reason rather than left out, so a reader can see it is there and delete it.
 */
data class LibraryEntry(val key: String, val document: WorldDocument?, val refusal: SaveRefusal? = null)

/** What a library key may be. */
object LibraryKeys {

    /** A full-world save. */
    const val EXTENSION = ".cgw"

    /**
     * Whether [key] is one name inside the library and nothing else: a `.cgw` file name with no
     * separator, no drive or stream colon, no control character and no leading dot, so no key can
     * reach outside the folder or name a hidden file. Spaces and brackets are allowed, because a
     * sync client's conflict copies are named with them.
     */
    fun isValid(key: String): Boolean =
        key.length in (EXTENSION.length + 1)..LONGEST_KEY &&
            key.endsWith(EXTENSION) &&
            !key.startsWith(".") &&
            key.none { it == '/' || it == '\\' || it == ':' || it.code < SPACE || it.code == DELETE }

    /** The key a document takes when it is first saved. */
    fun of(document: WorldDocument): String {
        require(WorldDocument.isValidId(document.id)) { "'${document.id}' is not a save id" }
        return document.id + EXTENSION
    }

    /** The longest file name the file systems a library can sit on all accept. */
    private const val LONGEST_KEY = 255

    /** The first printable character: everything below it is a control code. */
    private const val SPACE = 0x20

    /** The one control code above [SPACE]. */
    private const val DELETE = 0x7F
}

/**
 * A [WorldLibrary] over anything that can list, read, replace and remove named blobs.
 *
 * Both platforms are just named blobs, so they share this and supply the primitives. The header
 * sits at the front of a save, so a listing reads only [readPrefix]; a save is read through
 * [reading] a chunk at a time and written through [replacing], which makes the new blob visible
 * only once it is whole.
 */
abstract class ByteWorldLibrary(
    private val compressor: Compressor = NoCompression,
    private val writtenBy: String = "unknown"
) : WorldLibrary {

    /** Every key in the library, whatever state its file is in. */
    protected abstract suspend fun names(): List<String>

    /**
     * The widest save this library's host can hold, or null for any the format allows. A save wider
     * than it is listed as refused and refused again if opened, from its header alone, so a host
     * that could not hold it never allocates for it.
     */
    protected open val openingLimit: OpeningLimit? get() = null

    /**
     * Runs [block] over the blob [name], a piece at a time, and returns what it returned; null if
     * there is no such blob. A failure of the storage itself throws [WorldFormatException] with
     * [SaveProblem.UNREADABLE].
     */
    protected abstract suspend fun <T> reading(name: String, block: suspend (SaveSource) -> T): T?

    /**
     * Up to the first [limitBytes] bytes of the blob [name], or null if there is none. Throws
     * [WorldFormatException] with [SaveProblem.UNREADABLE] when the storage cannot read it.
     */
    protected abstract suspend fun readPrefix(name: String, limitBytes: Int): ByteArray?

    /**
     * Runs [contents] into a sink and makes what it wrote the blob [name], whole, only once it has
     * returned: if it throws, or is cancelled, [name] is as it was before.
     */
    protected abstract suspend fun replacing(name: String, contents: suspend (SaveSink) -> Unit)

    protected abstract suspend fun remove(name: String)

    /**
     * Held for the whole of every write and delete, so they happen one at a time. `Mutex` hands
     * itself on first come, first served, and a save asks for it before it suspends on anything
     * else, so the order writes land in is the order they were asked for — never the order they
     * would have finished in, which let a slow older save rename itself over a newer one.
     */
    private val writing = Mutex()

    override suspend fun list(): List<LibraryEntry> {
        val entries = names().filter { LibraryKeys.isValid(it) }.map { entry(it) }
        val readable = entries.filter { it.document != null }.sortedByDescending { it.document!!.savedAt }
        val refused = entries.filter { it.document == null }.sortedBy { it.key }
        return readable + refused
    }

    override suspend fun save(document: WorldDocument, world: WorldMap, key: String?): String {
        key?.let(::requireKey)
        val newName = if (key == null) LibraryKeys.of(document) else null
        val contents: suspend (SaveSink) -> Unit = { sink -> WorldCodec.write(document, world, sink, compressor, writtenBy) }
        return writing.withLock {
            if (key != null) {
                replacing(key, contents)
                key
            } else {
                creating(newName!!, contents)
            }
        }
    }

    /**
     * Writes a new blob under [wanted], or under the next free name after it, and returns the name
     * it took: a new file is never written over an old one.
     *
     * The name is chosen before the write here, which is all a library whose only writer is this
     * instance needs. A library that shares its folder with other writers — another tab, the
     * desktop application, a sync client — overrides this to choose again once the bytes are
     * written, immediately before they are put in place, so the window in which another writer can
     * take the name is the one call that moves the file rather than the whole of the write.
     */
    protected open suspend fun creating(wanted: String, contents: suspend (SaveSink) -> Unit): String {
        val name = freeName(wanted)
        replacing(name, contents)
        return name
    }

    /**
     * Copies [key] from [source] into this library as a new file, under the same name or the next
     * free one after it, and returns the name it took. The bytes are passed on a piece at a time as
     * they are read, neither decoded nor held whole, so a copy costs what a save costs and a file
     * that would not open is copied as it is, to be refused here as it was there.
     */
    suspend fun copyFrom(source: ByteWorldLibrary, key: String): String {
        requireKey(key)
        return writing.withLock {
            source.reading(key) { input ->
                creating(key) { sink ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val count = input.read(buffer, 0, buffer.size)
                        if (count < 0) break
                        if (count > 0) sink.write(buffer, 0, count)
                    }
                }
            } ?: throw WorldFormatException(SaveProblem.UNREADABLE, "it is no longer in the library it was copied from")
        }
    }

    /**
     * [wanted], or when the library already has a file of that name, the first of `<id> (2).cgw`,
     * `<id> (3).cgw` and so on that it does not: a new file is never written over an old one,
     * whatever the ids inside them say.
     */
    protected suspend fun freeName(wanted: String): String {
        val taken = names().toSet()
        if (wanted !in taken) return wanted
        val stem = wanted.removeSuffix(LibraryKeys.EXTENSION)
        return generateSequence(2) { it + 1 }.map { "$stem ($it)${LibraryKeys.EXTENSION}" }.first { it !in taken }
    }

    override suspend fun load(key: String): LoadOutcome {
        requireKey(key)
        return try {
            reading(key) { source -> WorldCodec.open(source, compressor, openingLimit) }
                ?: LoadOutcome.Refused(SaveRefusal(SaveProblem.UNREADABLE, "it is no longer in the library"))
        } catch (refused: WorldFormatException) {
            LoadOutcome.Refused(SaveRefusal(refused.problem, refused.detail))
        }
    }

    override suspend fun delete(key: String) {
        requireKey(key)
        writing.withLock { remove(key) }
    }

    private fun requireKey(key: String) = require(LibraryKeys.isValid(key)) { "'$key' is not a library key" }

    /**
     * One row of the listing, from the header alone.
     *
     * The header's length is in the prefix, so this asks for a probe first and only asks again
     * when the header turned out to be longer than the probe — two reads at worst, neither of them
     * the arrays.
     */
    private suspend fun entry(name: String): LibraryEntry = try {
        val probe = readPrefix(name, HEADER_PROBE_BYTES)
            ?: throw WorldFormatException(SaveProblem.UNREADABLE, "it is no longer in the library")
        val declared = if (WorldCodec.isContainer(probe)) getInt(probe, WorldCodec.HEADER_LENGTH_OFFSET) else 0
        val needed = WorldCodec.PREFIX_BYTES.toLong() + declared
        val bytes = if (declared <= 0 || probe.size >= needed || needed > WorldCodec.PREFIX_BYTES + WorldCodec.LARGEST_HEADER_BYTES) {
            probe
        } else {
            readPrefix(name, needed.toInt()) ?: probe
        }
        LibraryEntry(name, WorldCodec.decodeHeader(bytes, openingLimit).document)
    } catch (refused: WorldFormatException) {
        LibraryEntry(name, null, SaveRefusal(refused.problem, refused.detail))
    }

    companion object {
        /**
         * Enough for a header on any world worth saving — it is the settings, the reader's edits and
         * a directory of forty-two entries, not the lists — and small enough that reading it costs
         * nothing.
         */
        const val HEADER_PROBE_BYTES = 1 shl 20

        /** What a copy between two libraries holds between reading and writing: the desktop's own buffer. */
        private const val COPY_BUFFER_BYTES = 1 shl 16
    }
}
