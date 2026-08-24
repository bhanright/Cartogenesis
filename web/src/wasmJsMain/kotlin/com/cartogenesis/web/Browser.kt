package com.cartogenesis.web

/**
 * The browser calls this build needs, declared one at a time.
 *
 * Kotlin/Wasm reaches JavaScript through `@JsFun`, and every value crossing that boundary has to
 * be a type both sides agree on — numbers, strings, booleans, or an opaque `JsAny`. So a byte
 * array cannot simply be handed over; [downloadBytes] copies it across a element at a time into a
 * `Uint8Array`, which is the honest cost of the boundary and is paid once per export.
 */

@JsFun("() => Date.now()")
private external fun jsNow(): Double

internal fun epochMillisNow(): Long = jsNow().toLong()

@JsFun("() => localStorage.length")
internal external fun storageLength(): Int

@JsFun("(index) => localStorage.key(index)")
internal external fun storageKeyAt(index: Int): String?

@JsFun("(key) => localStorage.getItem(key)")
internal external fun storageGet(key: String): String?

@JsFun(
    """(key, value) => {
        try { localStorage.setItem(key, value); return true; }
        catch (e) { return false; }
    }"""
)
internal external fun storageSet(key: String, value: String): Boolean

@JsFun("(key) => { localStorage.removeItem(key); }")
internal external fun storageRemove(key: String)

/** An opaque handle to a JavaScript `Uint8Array`, only ever passed straight back to JavaScript. */
internal external interface ByteBuffer : JsAny

@JsFun("(size) => new Uint8Array(size)")
private external fun allocateBytes(size: Int): ByteBuffer

@JsFun("(buffer, index, value) => { buffer[index] = value; }")
private external fun setByte(buffer: ByteBuffer, index: Int, value: Int)

@JsFun(
    """(buffer, name, mime) => {
        const blob = new Blob([buffer], { type: mime });
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
private external fun triggerDownload(buffer: ByteBuffer, name: String, mime: String)

/** Hands the browser a finished file to save. */
internal fun downloadBytes(name: String, bytes: ByteArray, mime: String) {
    val buffer = allocateBytes(bytes.size)
    for (i in bytes.indices) setByte(buffer, i, bytes[i].toInt() and 0xFF)
    triggerDownload(buffer, name, mime)
}

@JsFun("() => { const el = document.getElementById('loading'); if (el) el.remove(); }")
internal external fun hideLoadingMessage()

@JsFun("() => location.search.indexOf('selftest') >= 0")
internal external fun selfTestRequested(): Boolean

@JsFun("(text) => { window.__selftest = text; console.log(text); }")
internal external fun publishSelfTest(text: String)
