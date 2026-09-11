package com.cartogenesis.cartography

import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Gzip via `CompressionStream`/`DecompressionStream`, under whatever Node.js runs
 * `wasmJsNodeTest`. A self-contained copy of the same idea as `:web`'s `WebGzipCompressor` — this
 * module cannot depend on `:web` — kept to the one direction this test needs and the other for
 * symmetry.
 *
 * If this Node build has neither stream global, both functions return null and
 * `GzipInteroperabilityTest` says so and skips rather than failing: the property under test is
 * that a *real* browser and the JVM agree, and a test environment too old to have the API cannot
 * speak to that either way.
 */

private external interface JsHandle : JsAny

@JsFun("() => typeof CompressionStream !== 'undefined' && typeof DecompressionStream !== 'undefined'")
private external fun gzipStreamsAvailable(): Boolean

@JsFun("(size) => new Uint8Array(size)")
private external fun newByteArray(size: Int): JsHandle

@JsFun("(array, index, value) => { array[index] = value; }")
private external fun setByteAt(array: JsHandle, index: Int, value: Int)

@JsFun("(array, index) => array[index]")
private external fun byteAt(array: JsHandle, index: Int): Int

@JsFun("(array) => array.length")
private external fun byteArrayLength(array: JsHandle): Int

@JsFun("(value) => value === null || value === undefined")
private external fun isNullish(value: JsHandle?): Boolean

@JsFun(
    """(promise, resolve, reject) => {
        promise.then((value) => resolve(value), (error) => reject(String(error)));
    }"""
)
private external fun thenPromise(promise: JsHandle, resolve: (JsHandle?) -> Unit, reject: (String) -> Unit)

private suspend fun awaitPromise(promise: JsHandle): JsHandle? =
    suspendCoroutine { continuation ->
        thenPromise(promise, resolve = { continuation.resume(it) }, reject = { continuation.resume(null) })
    }

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
private external fun gzipCompressJs(bytes: JsHandle): JsHandle

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
private external fun gzipDecompressJs(bytes: JsHandle): JsHandle

private fun ByteArray.toJs(): JsHandle {
    val buffer = newByteArray(size)
    for (i in indices) setByteAt(buffer, i, this[i].toInt() and 0xFF)
    return buffer
}

private fun JsHandle.toKotlinBytes(): ByteArray {
    val length = byteArrayLength(this)
    return ByteArray(length) { byteAt(this, it).toByte() }
}

actual suspend fun platformGzipCompress(data: ByteArray): ByteArray? {
    if (!gzipStreamsAvailable()) return null
    val result = awaitPromise(gzipCompressJs(data.toJs()))
    if (result == null || isNullish(result)) return null
    return result.toKotlinBytes()
}

actual suspend fun platformGzipDecompress(data: ByteArray): ByteArray? {
    if (!gzipStreamsAvailable()) return null
    val result = awaitPromise(gzipDecompressJs(data.toJs()))
    if (result == null || isNullish(result)) return null
    return result.toKotlinBytes()
}

/** Whether this platform's test can even attempt the round trip. */
internal actual fun platformGzipAvailable(): Boolean = gzipStreamsAvailable()
