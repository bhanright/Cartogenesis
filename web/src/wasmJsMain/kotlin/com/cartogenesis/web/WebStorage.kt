package com.cartogenesis.web

import com.cartogenesis.cartography.ByteWorldLibrary
import com.cartogenesis.cartography.Compressor
import com.cartogenesis.cartography.SaveProblem
import com.cartogenesis.cartography.SaveSink
import com.cartogenesis.cartography.SaveSource
import com.cartogenesis.cartography.WorldCodec
import com.cartogenesis.cartography.WorldFormatException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Saved worlds in IndexedDB, and real gzip on this platform.
 *
 * Local storage caps out at a few megabytes per origin, and a save carries the world, which is tens
 * of megabytes at 512 and more at 1024 - it never had a chance. IndexedDB's quota is a share of the
 * disk, and its API is asynchronous throughout, as `CompressionStream` is; [com.cartogenesis.cartography.WorldLibrary]
 * suspends for both.
 *
 * A save never sits whole in the page's own memory. The codec hands the library one compressed
 * chunk at a time, each is copied out to a JavaScript array as it comes, and the stored value is a
 * `Blob` made from those pieces; reading one back takes it a slice at a time. How much of that a
 * browser keeps in memory rather than on disk is the browser's business, and it is outside the
 * WebAssembly heap either way.
 */

// ---- IndexedDB, wrapped as promises the native callback API does not otherwise offer. ----

@JsFun(
    """() => new Promise((resolve, reject) => {
        const req = indexedDB.open('cartogenesis-worlds', 1);
        req.onupgradeneeded = (event) => {
            const db = event.target.result;
            if (!db.objectStoreNames.contains('headers')) db.createObjectStore('headers');
            if (!db.objectStoreNames.contains('payloads')) db.createObjectStore('payloads');
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
 * A stored save as a `Blob`, or null. A value stored before saves were blobs is a plain byte
 * array, and is wrapped as one so it is read the same way — and refused by its format version.
 */
@JsFun(
    """(db, key) => new Promise((resolve, reject) => {
        const tx = db.transaction('payloads', 'readonly');
        const req = tx.objectStore('payloads').get(key);
        req.onsuccess = () => {
            const value = req.result;
            if (value === undefined || value === null) resolve(null);
            else resolve(value instanceof Blob ? value : new Blob([value]));
        };
        req.onerror = () => reject(req.error);
    })"""
)
private external fun idbGetBlob(db: JsHandle, key: String): JsHandle

/**
 * The whole save and its header prefix, written in one transaction: either both land or neither
 * does, so a listing never shows a header whose world is missing, or the other way about.
 */
@JsFun(
    """(db, key, header, parts) => new Promise((resolve, reject) => {
        const tx = db.transaction(['headers', 'payloads'], 'readwrite');
        tx.objectStore('payloads').put(new Blob(parts), key);
        tx.objectStore('headers').put(header, key);
        tx.oncomplete = () => resolve(null);
        tx.onerror = () => reject(tx.error);
        tx.onabort = () => reject(tx.error);
    })"""
)
private external fun idbPutSave(db: JsHandle, key: String, header: JsHandle, parts: JsHandle): JsHandle

@JsFun(
    """(db, key) => new Promise((resolve, reject) => {
        const tx = db.transaction(['headers', 'payloads'], 'readwrite');
        tx.objectStore('payloads').delete(key);
        tx.objectStore('headers').delete(key);
        tx.oncomplete = () => resolve(null);
        tx.onerror = () => reject(tx.error);
        tx.onabort = () => reject(tx.error);
    })"""
)
private external fun idbDeleteSave(db: JsHandle, key: String): JsHandle

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
private external fun blobSize(blob: JsHandle): Double

@JsFun("(blob, start, end) => blob.slice(start, end).arrayBuffer().then((buffer) => new Uint8Array(buffer))")
private external fun blobSlice(blob: JsHandle, start: Double, end: Double): JsHandle

private fun ByteArray.toJs(offset: Int = 0, length: Int = size): JsHandle {
    val buffer = newByteArray(length)
    for (index in 0 until length) setByteAt(buffer, index, this[offset + index].toInt() and 0xFF)
    return buffer
}

private fun JsHandle.toKotlinBytes(): ByteArray {
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
 */
private suspend fun awaitPromiseOrThrow(promise: JsHandle): JsHandle? =
    suspendCoroutine { continuation ->
        thenStoragePromise(
            promise,
            resolve = { continuation.resume(it) },
            reject = { continuation.resumeWithException(IllegalStateException(it)) }
        )
    }

/**
 * A sink that copies each piece it is given out to a JavaScript array of byte arrays, from which a
 * `Blob` is made once the save is whole. The first bytes are also kept here, until they hold the
 * container's header, for the listing's own record.
 */
internal class PartsSink : SaveSink {
    val parts: JsHandle = newParts()
    private var prefix = ByteArray(0)
    private var prefixWanted = WorldCodec.PREFIX_BYTES

    override suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (prefix.size < prefixWanted) {
            val taken = minOf(length, prefixWanted - prefix.size)
            prefix += bytes.copyOfRange(offset, offset + taken)
            if (prefix.size == WorldCodec.PREFIX_BYTES && prefixWanted == WorldCodec.PREFIX_BYTES) {
                prefixWanted = WorldCodec.PREFIX_BYTES + readInt(prefix, WorldCodec.HEADER_LENGTH_OFFSET)
                val rest = minOf(length - taken, prefixWanted - prefix.size)
                if (rest > 0) prefix += bytes.copyOfRange(offset + taken, offset + taken + rest)
            }
        }
        appendPart(parts, bytes.toJs(offset, length))
    }

    /** Magic, version, header length and the header: what a listing reads, and all it reads. */
    fun headerPrefix(): ByteArray = prefix
}

private fun readInt(bytes: ByteArray, at: Int): Int =
    (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
        ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)

/**
 * A save read back out of a `Blob` — stored, or a file the reader picked — a slice at a time.
 * Slices of [SLICE_BYTES] are fetched and served from, so a frame's sixteen-byte header does not
 * cost a promise of its own.
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

    suspend fun blob(key: String): JsHandle? {
        val value = awaitPromiseOrThrow(idbGetBlob(handle, key))
        return if (value == null || isNullish(value)) null else value
    }

    suspend fun putSave(key: String, header: ByteArray, parts: JsHandle) {
        awaitPromiseOrThrow(idbPutSave(handle, key, header.toJs(), parts))
    }

    suspend fun deleteSave(key: String) {
        awaitPromiseOrThrow(idbDeleteSave(handle, key))
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
 * Saved worlds in IndexedDB, under the library's keys — `<id>.cgw` for a save made here.
 *
 * Two object stores rather than one, because a listing wants only the headers. `headers` holds
 * each container's header prefix (magic, version, header length and the header JSON — kilobytes,
 * whatever the world's resolution) and `payloads` the whole save as a `Blob`. [readPrefix] only ever
 * touches the first, so listing a shelf of 1024 saves never reads an array back.
 *
 * A connection is opened and closed for each call rather than held open. That costs a little time
 * on a library with many entries, but it is also what makes the self-test's round trip a real one:
 * every read here is through a connection that did not write the bytes it is reading.
 */
internal class IndexedDbLibrary(
    compressor: Compressor,
    writtenBy: String
) : ByteWorldLibrary(compressor, writtenBy) {

    private suspend fun <T> withDb(block: suspend (IndexedDbConnection) -> T): T {
        val db = IndexedDbConnection.open()
        try {
            return block(db)
        } finally {
            db.close()
        }
    }

    override suspend fun names(): List<String> = withDb { it.keys(STORE_HEADERS) }

    override suspend fun <T> reading(name: String, block: suspend (SaveSource) -> T): T? {
        val blob = try {
            withDb { it.blob(name) }
        } catch (refused: IllegalStateException) {
            throw WorldFormatException(SaveProblem.UNREADABLE, refused.message.orEmpty())
        } ?: return null
        return block(BlobSource(blob))
    }

    override suspend fun readPrefix(name: String, limitBytes: Int): ByteArray? =
        try {
            withDb { it.get(STORE_HEADERS, name) }
        } catch (refused: IllegalStateException) {
            throw WorldFormatException(SaveProblem.UNREADABLE, refused.message.orEmpty())
        }

    override suspend fun replacing(name: String, contents: suspend (SaveSink) -> Unit) {
        val sink = PartsSink()
        contents(sink)
        withDb { it.putSave(name, sink.headerPrefix(), sink.parts) }
    }

    override suspend fun remove(name: String) = withDb { it.deleteSave(name) }

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
        return out;
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
        return result.toKotlinBytes()
    }
}
