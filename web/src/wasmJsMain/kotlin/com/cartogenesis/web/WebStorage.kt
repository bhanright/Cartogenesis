package com.cartogenesis.web

import com.cartogenesis.cartography.ByteWorldLibrary
import com.cartogenesis.cartography.Compressor
import com.cartogenesis.cartography.WorldCodec
import com.cartogenesis.cartography.WorldDocument
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Saved worlds in IndexedDB, and real gzip on this platform at last.
 *
 * Local storage caps out at a few megabytes per origin, and a save now carries the world, which
 * is tens of megabytes at 512 and more at 1024 - it never had a chance. IndexedDB's quota is a
 * share of the disk, and its API is asynchronous throughout, which used to be the reason web
 * compression was deferred: `CompressionStream` is a promise too, and there was nowhere for one to
 * go in a library that saved and loaded in a single call. Now that [com.cartogenesis.cartography.WorldLibrary]
 * suspends for IndexedDB's sake, it suspends for `CompressionStream`'s for free.
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

@JsFun(
    """(db, store, key, value) => new Promise((resolve, reject) => {
        const tx = db.transaction(store, 'readwrite');
        tx.objectStore(store).put(value, key);
        tx.oncomplete = () => resolve(null);
        tx.onerror = () => reject(tx.error);
        tx.onabort = () => reject(tx.error);
    })"""
)
private external fun idbPut(db: JsHandle, store: String, key: String, value: JsHandle): JsHandle

@JsFun(
    """(db, store, key) => new Promise((resolve, reject) => {
        const tx = db.transaction(store, 'readwrite');
        tx.objectStore(store).delete(key);
        tx.oncomplete = () => resolve(null);
        tx.onerror = () => reject(tx.error);
        tx.onabort = () => reject(tx.error);
    })"""
)
private external fun idbDelete(db: JsHandle, store: String, key: String): JsHandle

@JsFun("(db) => { db.close(); }")
private external fun idbClose(db: JsHandle)

// ---- Byte-array crossings. A second, generic set alongside Browser.kt's `ByteBuffer` ones,
// since an external interface is a type of its own in Kotlin/Wasm even when, as here, it is
// really the same Uint8Array on the JavaScript side. ----

@JsFun("(size) => new Uint8Array(size)")
private external fun newByteArray(size: Int): JsHandle

@JsFun("(array, index, value) => { array[index] = value; }")
private external fun setByteAt(array: JsHandle, index: Int, value: Int)

@JsFun("(array, index) => array[index]")
private external fun byteAt(array: JsHandle, index: Int): Int

@JsFun("(array) => array.length")
private external fun byteArrayLength(array: JsHandle): Int

private fun ByteArray.toJs(): JsHandle {
    val buffer = newByteArray(size)
    for (i in indices) setByteAt(buffer, i, this[i].toInt() and 0xFF)
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
 * writes use this instead and let the caller's `runCatching` turn it into a message.
 */
private suspend fun awaitPromiseOrThrow(promise: JsHandle): JsHandle? =
    suspendCoroutine { continuation ->
        thenStoragePromise(
            promise,
            resolve = { continuation.resume(it) },
            reject = { continuation.resumeWithException(IllegalStateException(it)) }
        )
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

    suspend fun put(store: String, key: String, bytes: ByteArray) {
        awaitPromiseOrThrow(idbPut(handle, store, key, bytes.toJs()))
    }

    suspend fun delete(store: String, key: String) {
        awaitPromiseOrThrow(idbDelete(handle, store, key))
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
 * Saved worlds in IndexedDB, keyed by the same `<id>.cgw` / `<id>.json` names [ByteWorldLibrary]
 * already uses for the desktop's files.
 *
 * Two object stores rather than one, because IndexedDB has no way to read part of a stored value
 * — the whole record comes back or none of it does. `headers` holds just the container's header
 * prefix (magic, version, header length, the header JSON itself — kilobytes, whatever the world's
 * resolution) and `payloads` holds the whole thing. [readPrefix] only ever touches the first, so
 * listing a shelf of 1024 saves never reads an array back from the browser's storage, let alone
 * copies one across the wasm/JavaScript boundary.
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

    override suspend fun read(name: String): ByteArray? = withDb { it.get(STORE_PAYLOADS, name) }

    override suspend fun readPrefix(name: String, limit: Int): ByteArray? =
        withDb { it.get(STORE_HEADERS, name) }

    override suspend fun write(name: String, bytes: ByteArray) = withDb { db ->
        db.put(STORE_PAYLOADS, name, bytes)
        db.put(STORE_HEADERS, name, headerPrefixOf(bytes))
    }

    override suspend fun remove(name: String) = withDb { db ->
        db.delete(STORE_PAYLOADS, name)
        db.delete(STORE_HEADERS, name)
    }

    /**
     * A one-time move from the local-storage library D1 shipped with, base64 and all: read every
     * entry, write it into IndexedDB under the same name, and remove the local-storage copy. Runs
     * at most once per page load, from [list] — the first thing anything does with the library —
     * and costs nothing on every load after the first, once local storage is empty of them.
     */
    private var migrated = false

    override suspend fun list(): List<WorldDocument> {
        migrateFromLocalStorageOnce()
        return super.list()
    }

    private suspend fun migrateFromLocalStorageOnce() {
        if (migrated) return
        migrated = true
        val prefix = "cartogenesis/"
        val keys = (0 until storageLength()).mapNotNull { storageKeyAt(it) }
            .filter { it.startsWith(prefix) }
        for (key in keys) {
            val name = key.removePrefix(prefix)
            val bytes = storageGet(key)?.let(::decodeLegacyStorageValue) ?: continue
            write(name, bytes)
            storageRemove(key)
        }
    }

    companion object {
        private const val STORE_HEADERS = "headers"
        private const val STORE_PAYLOADS = "payloads"
    }
}

/**
 * The header-only prefix of a container: magic, version, header length, and the header JSON, with
 * none of the payload behind it. A version-2 save has no such split, being JSON straight through
 * with nothing else in the file, so its "header" is simply the whole (small) thing.
 */
private fun headerPrefixOf(bytes: ByteArray): ByteArray {
    if (!WorldCodec.isContainer(bytes)) return bytes
    val headerLength = (bytes[8].toInt() and 0xFF) or
        ((bytes[9].toInt() and 0xFF) shl 8) or
        ((bytes[10].toInt() and 0xFF) shl 16) or
        ((bytes[11].toInt() and 0xFF) shl 24)
    val end = (WorldCodec.PREFIX_BYTES + headerLength).coerceAtMost(bytes.size)
    return bytes.copyOfRange(0, end)
}

/** What a `LocalStorageLibrary` entry from before this build used to look like. */
@OptIn(ExperimentalEncodingApi::class)
private fun decodeLegacyStorageValue(text: String): ByteArray? {
    if (text.startsWith("{")) return text.encodeToByteArray()
    return runCatching { Base64.decode(text) }.getOrNull()
}

// ---- Moving a save in or out of the browser as an ordinary file. ----

@JsFun(
    """() => new Promise((resolve) => {
        const input = document.createElement('input');
        input.type = 'file';
        input.accept = '.cgw,.json';
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
            if (!file) { finish(null); return; }
            file.arrayBuffer()
                .then((buffer) => finish(new Uint8Array(buffer)))
                .catch(() => finish(null));
        });
        // Not every browser fires this - there is no fully reliable "the user cancelled" signal
        // on a file input - but where it does, it saves waiting on a promise that will now never
        // resolve on its own until the next pick.
        input.addEventListener('cancel', () => finish(null));
        input.click();
    })"""
)
private external fun pickFileBytes(): JsHandle

/** Opens the browser's file picker and returns the chosen file's bytes, or null if it was
 *  cancelled, empty, or could not be read. */
internal suspend fun pickFile(): ByteArray? {
    val result = awaitPromise(pickFileBytes())
    if (result == null || isNullish(result)) return null
    return result.toKotlinBytes()
}

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

@JsFun(
    """(bytes) => (async () => {
        try {
            const stream = new DecompressionStream('gzip');
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
private external fun gzipDecompress(bytes: JsHandle): JsHandle

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

    override suspend fun decompress(data: ByteArray): ByteArray? {
        if (!compressionStreamsAvailable()) return null
        val result = awaitPromise(gzipDecompress(data.toJs()))
        if (result == null || isNullish(result)) return null
        return result.toKotlinBytes()
    }
}
