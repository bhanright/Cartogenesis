package com.cartogenesis.web

import com.cartogenesis.cartography.ByteWorldLibrary
import com.cartogenesis.cartography.OpeningLimit
import com.cartogenesis.cartography.Compressor
import com.cartogenesis.cartography.SaveProblem
import com.cartogenesis.cartography.SaveSink
import com.cartogenesis.cartography.SaveSource
import com.cartogenesis.cartography.WorldCodec
import com.cartogenesis.cartography.WorldFormatException
import com.cartogenesis.ui.randomId
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Saved worlds in IndexedDB, and real gzip on this platform.
 *
 * Local storage caps out at a few megabytes per origin, and a save carries the world, which is tens
 * of megabytes at 512 and more at 1024 - it never had a chance. IndexedDB's quota is a share of the
 * disk, and its API is asynchronous throughout, as `CompressionStream` is; [com.cartogenesis.cartography.WorldLibrary]
 * suspends for both.
 *
 * A save in the library never sits whole in the tab. The codec hands the library its bytes a chunk
 * at a time, and each mebibyte is put in IndexedDB as a record of its own the moment it is full;
 * reading one back fetches the records one at a time in the same way. What the tab holds for a
 * save beyond the world itself is a part or two. A download is the exception: a browser hands a
 * page no way to write a file a piece at a time that works in every browser, so a downloaded save
 * is gathered whole, outside the WebAssembly heap, and handed over as one `Blob`.
 */

// ---- IndexedDB, wrapped as promises the native callback API does not otherwise offer. ----

@JsFun(
    """() => new Promise((resolve, reject) => {
        const req = indexedDB.open('cartogenesis-worlds', 2);
        req.onupgradeneeded = (event) => {
            const db = event.target.result;
            if (!db.objectStoreNames.contains('headers')) db.createObjectStore('headers');
            if (!db.objectStoreNames.contains('payloads')) db.createObjectStore('payloads');
            if (!db.objectStoreNames.contains('parts')) db.createObjectStore('parts');
        };
        req.onsuccess = () => resolve(req.result);
        req.onerror = () => reject(req.error);
    })"""
)
private external fun idbOpen(): JsHandle

@JsFun(
    """(db, store) => new Promise((resolve, reject) => {
        const tx = db.transaction(store, 'readonly');
        const req = tx.objectStore(store).getAllKeys();
        req.onsuccess = () => resolve(req.result);
        req.onerror = () => reject(req.error);
    })"""
)
private external fun idbGetAllKeys(db: JsHandle, store: String): JsHandle

@JsFun(
    """(db, store, key) => new Promise((resolve, reject) => {
        const tx = db.transaction(store, 'readonly');
        const req = tx.objectStore(store).get(key);
        req.onsuccess = () => resolve(req.result === undefined ? null : req.result);
        req.onerror = () => reject(req.error);
    })"""
)
private external fun idbGet(db: JsHandle, store: String, key: String): JsHandle

/**
 * What the library holds for a save: a manifest naming its parts (`{ token, count }`), or, from a
 * build before saves were stored in parts, the whole save as a `Blob` or a byte array — wrapped as
 * a `Blob` so it is read the same way, and refused by its format version. Null when there is none.
 */
@JsFun(
    """(db, key) => new Promise((resolve, reject) => {
        const tx = db.transaction('payloads', 'readonly');
        const req = tx.objectStore('payloads').get(key);
        req.onsuccess = () => {
            const value = req.result;
            if (value === undefined || value === null) resolve(null);
            else if (value instanceof Blob || typeof value.token === 'string') resolve(value);
            else resolve(new Blob([value]));
        };
        req.onerror = () => reject(req.error);
    })"""
)
private external fun idbGetPayload(db: JsHandle, key: String): JsHandle

@JsFun("(value) => !(value instanceof Blob) && typeof value.token === 'string'")
private external fun isManifest(value: JsHandle): Boolean

@JsFun("(value) => value.token")
private external fun manifestToken(value: JsHandle): String

@JsFun("(value) => value.count")
private external fun manifestCount(value: JsHandle): Int

/** One part of a save, in its own transaction, so a part is on disk before the next is made. */
@JsFun(
    """(db, key, bytes) => new Promise((resolve, reject) => {
        const tx = db.transaction('parts', 'readwrite');
        tx.objectStore('parts').put(bytes, key);
        tx.oncomplete = () => resolve(null);
        tx.onerror = () => reject(tx.error);
        tx.onabort = () => reject(tx.error);
    })"""
)
private external fun idbPutPart(db: JsHandle, key: String, bytes: JsHandle): JsHandle

@JsFun(
    """(db, key) => new Promise((resolve, reject) => {
        const tx = db.transaction('parts', 'readonly');
        const req = tx.objectStore('parts').get(key);
        req.onsuccess = () => resolve(req.result === undefined ? null : req.result);
        req.onerror = () => reject(req.error);
    })"""
)
private external fun idbGetPart(db: JsHandle, key: String): JsHandle

/**
 * The save's manifest and its header prefix, written in one transaction once every part is in:
 * the moment the new save replaces the old one, which is whole on either side of it. Resolves with
 * the token of the parts the old manifest named, for them to be cleared, or null.
 */
@JsFun(
    """(db, key, header, token, count) => new Promise((resolve, reject) => {
        const tx = db.transaction(['headers', 'payloads'], 'readwrite');
        const payloads = tx.objectStore('payloads');
        let old = null;
        const req = payloads.get(key);
        req.onsuccess = () => {
            const value = req.result;
            if (value && !(value instanceof Blob) && typeof value.token === 'string') old = value.token;
            payloads.put({ token: token, count: count }, key);
            tx.objectStore('headers').put(header, key);
        };
        tx.oncomplete = () => resolve(old);
        tx.onerror = () => reject(tx.error);
        tx.onabort = () => reject(tx.error);
    })"""
)
private external fun idbCommitSave(db: JsHandle, key: String, header: JsHandle, token: String, count: Int): JsHandle

/** Removes a save's header and manifest together, resolving with the token of its parts, or null. */
@JsFun(
    """(db, key) => new Promise((resolve, reject) => {
        const tx = db.transaction(['headers', 'payloads'], 'readwrite');
        const payloads = tx.objectStore('payloads');
        let old = null;
        const req = payloads.get(key);
        req.onsuccess = () => {
            const value = req.result;
            if (value && !(value instanceof Blob) && typeof value.token === 'string') old = value.token;
            payloads.delete(key);
            tx.objectStore('headers').delete(key);
        };
        tx.oncomplete = () => resolve(old);
        tx.onerror = () => reject(tx.error);
        tx.onabort = () => reject(tx.error);
    })"""
)
private external fun idbDeleteSave(db: JsHandle, key: String): JsHandle

/** Every part whose key begins with [prefix]: one save's parts, by the token they were written under. */
@JsFun(
    """(db, prefix) => new Promise((resolve, reject) => {
        const tx = db.transaction('parts', 'readwrite');
        tx.objectStore('parts').delete(IDBKeyRange.bound(prefix, prefix + '\uffff'));
        tx.oncomplete = () => resolve(null);
        tx.onerror = () => reject(tx.error);
        tx.onabort = () => reject(tx.error);
    })"""
)
private external fun idbDeleteParts(db: JsHandle, prefix: String): JsHandle

@JsFun("(value) => typeof value === 'string' ? value : null")
private external fun asString(value: JsHandle?): String?

@JsFun("(db) => { db.close(); }")
private external fun idbClose(db: JsHandle)

// ---- Byte-array and blob crossings. A second, generic set alongside Browser.kt's `ByteBuffer`
// ones, since an external interface is a type of its own in Kotlin/Wasm even when, as here, it is
// really the same Uint8Array on the JavaScript side. ----

@JsFun("(size) => new Uint8Array(size)")
private external fun newByteArray(size: Int): JsHandle

@JsFun("(array, index, value) => { array[index] = value; }")
private external fun setByteAt(array: JsHandle, index: Int, value: Int)

@JsFun("(array, index) => array[index]")
private external fun byteAt(array: JsHandle, index: Int): Int

@JsFun("(array) => array.length")
private external fun byteArrayLength(array: JsHandle): Int

@JsFun("() => []")
internal external fun newParts(): JsHandle

@JsFun("(parts, part) => { parts.push(part); }")
private external fun appendPart(parts: JsHandle, part: JsHandle)

@JsFun("(blob) => blob.size")
internal external fun blobSize(blob: JsHandle): Double

@JsFun("(blob, start, end) => blob.slice(start, end).arrayBuffer().then((buffer) => new Uint8Array(buffer))")
internal external fun blobSlice(blob: JsHandle, start: Double, end: Double): JsHandle

internal fun ByteArray.toJs(offset: Int = 0, length: Int = size): JsHandle {
    val buffer = newByteArray(length)
    for (index in 0 until length) setByteAt(buffer, index, this[offset + index].toInt() and 0xFF)
    return buffer
}

internal fun JsHandle.toKotlinBytes(): ByteArray {
    val length = byteArrayLength(this)
    return ByteArray(length) { byteAt(this, it).toByte() }
}

@JsFun(
    """(promise, resolve, reject) => {
        promise.then((value) => resolve(value), (error) => reject(String(error)));
    }"""
)
private external fun thenStoragePromise(
    promise: JsHandle,
    resolve: (JsHandle?) -> Unit,
    reject: (String) -> Unit
)

/**
 * Like [awaitPromise], but a rejection throws instead of quietly becoming null.
 *
 * [awaitPromise] fits the GPU path, where a failure and "nothing to report" are the same answer.
 * A write that silently did not happen is a worse failure than one that throws, so IndexedDB's
 * calls use this instead and let the caller turn it into a message.
 *
 * Cancellable: a coroutine cancelled while the browser works stops waiting at once and throws, and
 * whatever the promise settles to afterwards is dropped. The browser's own work is not stopped by
 * that, so a caller with something to undo — a stream to abort, parts to clear — does it after the
 * throw, and a call that must be seen to its end, a commit, is awaited under `NonCancellable`.
 */
internal suspend fun awaitPromiseOrThrow(promise: JsHandle): JsHandle? =
    suspendCancellableCoroutine { continuation ->
        thenStoragePromise(
            promise,
            resolve = { continuation.resume(it) },
            reject = { continuation.resumeWithException(IllegalStateException(it)) }
        )
    }


/**
 * The front of a save as it goes past — magic, version, header length, checksum and header — which
 * is what a listing reads, and all it reads. Kept from the first writes until the header is whole.
 */
private class HeaderPrefix {
    private var prefix = ByteArray(0)
    private var wanted = WorldCodec.PREFIX_BYTES

    fun take(bytes: ByteArray, offset: Int, length: Int) {
        var from = offset
        val end = offset + length
        while (prefix.size < wanted && from < end) {
            val taken = minOf(end - from, wanted - prefix.size)
            prefix += bytes.copyOfRange(from, from + taken)
            from += taken
            if (prefix.size == WorldCodec.PREFIX_BYTES && wanted == WorldCodec.PREFIX_BYTES) {
                wanted = WorldCodec.PREFIX_BYTES + readInt(prefix, WorldCodec.HEADER_LENGTH_OFFSET)
            }
        }
    }

    fun bytes(): ByteArray = prefix
}

/**
 * A sink for a download: each piece is copied out to a JavaScript array of byte arrays, from which
 * one `Blob` is made once the save is whole. The whole save is held in the tab until it is handed
 * over, outside the WebAssembly heap; see the note at the top of this file for why a download is
 * the one place that happens.
 */
internal class DownloadSink : SaveSink {
    val parts: JsHandle = newParts()

    override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
        appendPart(parts, bytes.toJs(offset, length))
    }
}

private fun readInt(bytes: ByteArray, at: Int): Int =
    (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
        ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)

/**
 * A save read back out of a `Blob` — stored by an older build, or a file the reader picked — a
 * slice at a time. Slices of [SLICE_BYTES] are fetched and served from, so a frame's sixteen-byte
 * header does not cost a promise of its own.
 */
internal class BlobSource(private val blob: JsHandle) : SaveSource {
    private val size = blobSize(blob)
    private var fetchedTo = 0.0
    private var buffer = ByteArray(0)
    private var position = 0

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (position == buffer.size) {
            if (fetchedTo >= size) return -1
            val end = minOf(size, fetchedTo + SLICE_BYTES)
            buffer = awaitPromiseOrThrow(blobSlice(blob, fetchedTo, end))?.toKotlinBytes()
                ?: throw WorldFormatException(SaveProblem.UNREADABLE, "the browser returned nothing for part of the file")
            fetchedTo = end
            position = 0
            if (buffer.isEmpty()) return -1
        }
        val count = minOf(length, buffer.size - position)
        buffer.copyInto(into, offset, position, position + count)
        position += count
        return count
    }

    private companion object {
        /** A mebibyte a slice: the codec's own chunk, so a chunk is one or two promises. */
        const val SLICE_BYTES = (1 shl 20).toDouble()
    }
}

/** The key a save's [index]th part is stored under, among the parts written under [token]. */
private fun partKey(name: String, token: String, index: Int): String = partPrefix(name, token) + index

/** What every key of the parts written for [name] under [token] begins with, and nothing else's. */
private fun partPrefix(name: String, token: String): String = "$name$PART_SEPARATOR$token$PART_SEPARATOR"

/** A character no library key and no token holds, so one save's parts never share a prefix with another's. */
private const val PART_SEPARATOR = '\u0000'

/**
 * How much of a save goes in one stored part: a mebibyte, the codec's own chunk, so a part is a
 * chunk or so and the tab holds one while it is put.
 */
private const val PART_BYTES = 1 shl 20

/** Watches a save's parts go in and come out, for the self-test that measures the tab. */
internal fun interface PartObserver {
    suspend fun onPart(writing: Boolean, index: Int)
}

/** One IndexedDB connection, open just long enough for the call that needed it. */
private class IndexedDbConnection private constructor(private val handle: JsHandle) {

    suspend fun keys(store: String): List<String> {
        val array = awaitPromiseOrThrow(idbGetAllKeys(handle, store)) ?: return emptyList()
        val length = byteArrayLength(array) // Array.length, same property name as Uint8Array's.
        return List(length) { jsArrayGetString(array, it) }
    }

    suspend fun get(store: String, key: String): ByteArray? {
        val value = awaitPromiseOrThrow(idbGet(handle, store, key))
        // A promise resolved with JavaScript's own `null` does not necessarily come back as
        // Kotlin's - see `isNullish` and its use in WebGpuErosion, which hit this first.
        if (value == null || isNullish(value)) return null
        return value.toKotlinBytes()
    }

    suspend fun payload(key: String): JsHandle? {
        val value = awaitPromiseOrThrow(idbGetPayload(handle, key))
        return if (value == null || isNullish(value)) null else value
    }

    suspend fun putPart(key: String, bytes: ByteArray, length: Int) {
        awaitPromiseOrThrow(idbPutPart(handle, key, bytes.toJs(0, length)))
    }

    suspend fun part(key: String): ByteArray? {
        val value = awaitPromiseOrThrow(idbGetPart(handle, key))
        if (value == null || isNullish(value)) return null
        return value.toKotlinBytes()
    }

    /** Makes the save whole and returns the token of the parts it replaced, if any. */
    suspend fun commit(key: String, header: ByteArray, token: String, count: Int): String? =
        asString(awaitPromiseOrThrow(idbCommitSave(handle, key, header.toJs(), token, count)))

    /** Removes a save and returns the token of its parts, if any. */
    suspend fun deleteSave(key: String): String? = asString(awaitPromiseOrThrow(idbDeleteSave(handle, key)))

    suspend fun deleteParts(name: String, token: String) {
        awaitPromiseOrThrow(idbDeleteParts(handle, partPrefix(name, token)))
    }

    fun close() = idbClose(handle)

    companion object {
        suspend fun open(): IndexedDbConnection =
            IndexedDbConnection(
                awaitPromiseOrThrow(idbOpen())
                    ?: error("this browser declined to open its world database")
            )
    }
}

@JsFun("(array, index) => array[index]")
private external fun jsArrayGetString(array: JsHandle, index: Int): String

/**
 * A sink that puts each [PART_BYTES] of a save in IndexedDB as its own record the moment it is
 * full, under [token], and keeps the header prefix for the listing's record.
 */
private class StoredPartsSink(
    private val db: IndexedDbConnection,
    private val name: String,
    private val token: String,
    private val observer: PartObserver?
) : SaveSink {
    private val buffer = ByteArray(PART_BYTES)
    private var filled = 0
    val header = HeaderPrefix()

    /** How many parts have been put. */
    var count = 0
        private set

    override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
        header.take(bytes, offset, length)
        var from = offset
        val end = offset + length
        while (from < end) {
            val taken = minOf(end - from, buffer.size - filled)
            bytes.copyInto(buffer, filled, from, from + taken)
            filled += taken
            from += taken
            if (filled == buffer.size) flush()
        }
    }

    suspend fun flush() {
        if (filled == 0) return
        db.putPart(partKey(name, token, count), buffer, filled)
        filled = 0
        observer?.onPart(writing = true, index = count)
        count++
    }
}

/** A save read back out of its stored parts, one record at a time. */
private class StoredPartsSource(
    private val db: IndexedDbConnection,
    private val name: String,
    private val token: String,
    private val count: Int,
    private val observer: PartObserver?
) : SaveSource {
    private var next = 0
    private var buffer = ByteArray(0)
    private var position = 0

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (position == buffer.size) {
            if (next == count) return -1
            buffer = db.part(partKey(name, token, next))
                ?: throw WorldFormatException(SaveProblem.INCOMPLETE, "part $next of $count is missing from the browser's storage")
            observer?.onPart(writing = false, index = next)
            next++
            position = 0
        }
        val taken = minOf(length, buffer.size - position)
        buffer.copyInto(into, offset, position, position + taken)
        position += taken
        return taken
    }
}

/**
 * Saved worlds in IndexedDB, under the library's keys — `<id>.cgw` for a save made here.
 *
 * Three object stores, because a listing wants only the headers and a save must never be held
 * whole. `headers` holds each container's header prefix (kilobytes, whatever the world's
 * resolution); `parts` holds the save itself in records of [PART_BYTES], keyed by the save's name,
 * a token fresh for every write and the part's place; and `payloads` holds, for each save, a
 * manifest naming its token and how many parts it has. A write puts every part first and then the
 * manifest and the header in one transaction, which is the moment the new save replaces the old;
 * only then are the old parts cleared, so a write that fails or is abandoned leaves the save that
 * was there, and at worst some parts nobody names.
 *
 * A connection is opened and closed for each call rather than held open between them. That costs
 * a little time on a library with many entries, but it is also what makes the self-test's round
 * trip a real one: every read here is through a connection that did not write the bytes it reads.
 */
internal class IndexedDbLibrary(
    compressor: Compressor,
    writtenBy: String
) : ByteWorldLibrary(compressor, writtenBy) {

    override val openingLimit: OpeningLimit = BROWSER_OPENING_LIMIT

    /** Told of every part as it goes in or comes out; the self-test measures the tab with it. */
    var partObserver: PartObserver? = null

    private suspend fun <T> withDb(block: suspend (IndexedDbConnection) -> T): T {
        val db = try {
            IndexedDbConnection.open()
        } catch (cancelled: CancellationException) {
            // A cancellation is an IllegalStateException too, and is not the storage refusing.
            throw cancelled
        } catch (refused: IllegalStateException) {
            throw WorldFormatException(SaveProblem.UNREADABLE, refused.message.orEmpty())
        }
        try {
            return block(db)
        } finally {
            db.close()
        }
    }

    override suspend fun names(): List<String> = withDb { it.keys(STORE_HEADERS) }

    override suspend fun <T> reading(name: String, block: suspend (SaveSource) -> T): T? = withDb { db ->
        val payload = try {
            db.payload(name)
        } catch (cancelled: CancellationException) {
            // A cancellation is an IllegalStateException too, and is not the storage refusing.
            throw cancelled
        } catch (refused: IllegalStateException) {
            throw WorldFormatException(SaveProblem.UNREADABLE, refused.message.orEmpty())
        } ?: return@withDb null
        val source = if (isManifest(payload)) {
            StoredPartsSource(db, name, manifestToken(payload), manifestCount(payload), partObserver)
        } else {
            BlobSource(payload)
        }
        block(source)
    }

    override suspend fun readPrefix(name: String, limitBytes: Int): ByteArray? =
        try {
            withDb { it.get(STORE_HEADERS, name) }
        } catch (cancelled: CancellationException) {
            // A cancellation is an IllegalStateException too, and is not the storage refusing.
            throw cancelled
        } catch (refused: IllegalStateException) {
            throw WorldFormatException(SaveProblem.UNREADABLE, refused.message.orEmpty())
        }

    override suspend fun replacing(name: String, contents: suspend (SaveSink) -> Unit) = withDb { db ->
        val token = randomId()
        var committed = false
        try {
            val sink = StoredPartsSink(db, name, token, partObserver)
            contents(sink)
            sink.flush()
            // The commit is seen to its end once begun: abandoned halfway through its wait, the
            // transaction would still land, and the cleanup below would then clear the parts of
            // the save it had just made the library's.
            val replaced = withContext(NonCancellable) { db.commit(name, sink.header.bytes(), token, sink.count) }
            committed = true
            if (replaced != null) withContext(NonCancellable) { db.deleteParts(name, replaced) }
        } finally {
            // A write that did not finish leaves its parts behind unnamed; they are cleared here
            // rather than left to fill the browser's quota, cancelled or not.
            if (!committed) withContext(NonCancellable) { runCatching { db.deleteParts(name, token) } }
        }
    }

    // Seen to its end once begun, as a commit is: a delete abandoned between its two steps would
    // leave the parts of a save nobody names.
    override suspend fun remove(name: String) = withContext(NonCancellable) {
        withDb { db ->
            val token = db.deleteSave(name)
            if (token != null) db.deleteParts(name, token)
        }
    }

    companion object {
        private const val STORE_HEADERS = "headers"
    }
}


// ---- Moving a save in or out of the browser as an ordinary file. ----

@JsFun(
    """() => new Promise((resolve) => {
        const input = document.createElement('input');
        input.type = 'file';
        input.accept = '.cgw';
        input.style.display = 'none';
        document.body.appendChild(input);
        let settled = false;
        const finish = (value) => {
            if (settled) return;
            settled = true;
            document.body.removeChild(input);
            resolve(value);
        };
        input.addEventListener('change', () => {
            const file = input.files && input.files[0];
            finish(file ? file : null);
        });
        // Not every browser fires this - there is no fully reliable "the user cancelled" signal
        // on a file input - but where it does, it saves waiting on a promise that will now never
        // resolve on its own until the next pick.
        input.addEventListener('cancel', () => finish(null));
        input.click();
    })"""
)
private external fun pickFileBlob(): JsHandle

/**
 * Opens the browser's file picker and returns the chosen file, to be read a slice at a time, or
 * null if the picker was cancelled.
 */
internal suspend fun pickFile(): SaveSource? {
    val result = awaitPromise(pickFileBlob())
    if (result == null || isNullish(result)) return null
    return BlobSource(result)
}

@JsFun(
    """(name, parts, mime) => {
        const blob = new Blob(parts, { type: mime });
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = name;
        document.body.appendChild(anchor);
        anchor.click();
        document.body.removeChild(anchor);
        // Revoking immediately can cancel the download in some browsers, so give it a moment.
        setTimeout(() => URL.revokeObjectURL(url), 10000);
    }"""
)
internal external fun downloadParts(name: String, parts: JsHandle, mime: String)

// ---- Real gzip, via the browser's own streams. ----

@JsFun("() => typeof CompressionStream !== 'undefined' && typeof DecompressionStream !== 'undefined'")
internal external fun compressionStreamsAvailable(): Boolean

@JsFun(
    """(bytes) => (async () => {
        try {
            const stream = new CompressionStream('gzip');
            const writer = stream.writable.getWriter();
            writer.write(bytes);
            writer.close();
            const buffer = await new Response(stream.readable).arrayBuffer();
            return new Uint8Array(buffer);
        } catch (e) {
            return null;
        }
    })()"""
)
private external fun gzipCompress(bytes: JsHandle): JsHandle

/**
 * Expands [bytes], reading no further than one byte past [limit], so a chunk that would expand to
 * gigabytes is stopped at a mebibyte and one. A stream that is not gzip rejects, which the caller
 * reads as damage rather than as a platform that cannot expand.
 *
 * Resolves with the answer and how many expanded bytes were pulled from the stream to get it, which
 * the self-test reads to show that the stream was left unread past the limit.
 */
@JsFun(
    """(bytes, limit) => (async () => {
        const stream = new DecompressionStream('gzip');
        const writer = stream.writable.getWriter();
        writer.write(bytes).catch(() => {});
        writer.close().catch(() => {});
        const reader = stream.readable.getReader();
        const parts = [];
        let total = 0;
        while (total <= limit) {
            const { done, value } = await reader.read();
            if (done) break;
            parts.push(value);
            total += value.length;
        }
        if (total > limit) reader.cancel().catch(() => {});
        const kept = Math.min(total, limit + 1);
        const out = new Uint8Array(kept);
        let at = 0;
        for (const part of parts) {
            if (at >= kept) break;
            const count = Math.min(part.length, kept - at);
            out.set(part.subarray(0, count), at);
            at += count;
        }
        return [out, total];
    })()"""
)
private external fun gzipDecompress(bytes: JsHandle, limit: Int): JsHandle

/**
 * Gzip via `CompressionStream`/`DecompressionStream`, so a save this browser writes and one the
 * desktop writes are the same bytes either way rather than merely the same format.
 *
 * [compressionStreamsAvailable] is checked on every call rather than once, since it costs nothing
 * and means a browser that only half-implements the API — a real `CompressionStream` behind a
 * `DecompressionStream` that throws, say — degrades one direction at a time instead of failing a
 * save it could otherwise have written.
 */
internal object WebGzipCompressor : Compressor {
    override val name: String get() = "gzip"

    override suspend fun compress(data: ByteArray): ByteArray? {
        if (!compressionStreamsAvailable()) return null
        val result = awaitPromise(gzipCompress(data.toJs()))
        if (result == null || isNullish(result)) return null
        return result.toKotlinBytes()
    }

    override suspend fun decompress(data: ByteArray, limitBytes: Int): ByteArray? {
        if (!compressionStreamsAvailable()) return null
        val result = awaitPromiseOrThrow(gzipDecompress(data.toJs(), limitBytes))
        if (result == null || isNullish(result)) return null
        lastPulledBytes = pairSecond(result)
        return pairFirst(result).toKotlinBytes()
    }

    /** Expanded bytes the last [decompress] pulled from its stream, for the self-test. */
    var lastPulledBytes: Double = 0.0
        private set
}

@JsFun("(pair) => pair[0]")
private external fun pairFirst(pair: JsHandle): JsHandle

@JsFun("(pair) => pair[1]")
private external fun pairSecond(pair: JsHandle): Double

/** Gzip of [size] zero bytes: a stream that expands to far more than its own length, for the self-test. */
@JsFun(
    """(size) => (async () => {
        const stream = new CompressionStream('gzip');
        const writer = stream.writable.getWriter();
        writer.write(new Uint8Array(size));
        writer.close();
        return new Uint8Array(await new Response(stream.readable).arrayBuffer());
    })()"""
)
private external fun gzipOfZerosPromise(size: Int): JsHandle

internal suspend fun gzipOfZeros(size: Int): ByteArray? {
    val result = awaitPromise(gzipOfZerosPromise(size))
    if (result == null || isNullish(result)) return null
    return result.toKotlinBytes()
}
