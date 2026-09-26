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

@JsFun("(key) => localStorage.getItem(key)")
internal external fun storageGet(key: String): String?

@JsFun(
    """(key, value) => {
        try { localStorage.setItem(key, value); return true; }
        catch (e) { return false; }
    }"""
)
internal external fun storageSet(key: String, value: String): Boolean

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
    for (at in bytes.indices) setByte(buffer, at, bytes[at].toInt() and 0xFF)
    triggerDownload(buffer, name, mime)
}

/**
 * Removes the page's own loading message, once Compose has something to draw.
 *
 * **This is load-bearing for a deployed website, not just cosmetic.** Compose puts its canvas
 * inside a shadow root attached to the viewport div, so from the page there is nothing to watch:
 * `document.querySelector("canvas")` is null, the div reports no children, and a MutationObserver
 * on it never fires, all while a live canvas is generating a world. That cost a debugging session
 * on the deployed site, where the app was working perfectly behind a loading overlay that
 * had no way to know.
 *
 * So the site keeps an empty `<div id="loading">` in the page purely so this can delete it, and
 * treats the deletion as the ready signal. Removing this function, renaming the id, or moving the
 * call later all leave a working application under a permanent overlay - which is a silent failure,
 * the worst kind. `WebDeploymentContractTest` fails if the id changes.
 */
@JsFun("() => { const el = document.getElementById('loading'); if (el) el.remove(); }")
internal external fun hideLoadingMessage()

@JsFun("() => location.search.indexOf('selftest') >= 0")
internal external fun selfTestRequested(): Boolean

/**
 * The page's whole address, query and fragment included: where a link to a world arrives.
 * `String(...)` because a Kotlin string has to be handed a JavaScript string, and `location.href`
 * is one already on every browser this build runs in; the conversion is the guard, not a habit.
 */
@JsFun("() => String(location.href)")
internal external fun pageAddress(): String

/**
 * The page's address with no query and no fragment: the origin and the path, which is what a
 * link copied in this tab begins with.
 */
@JsFun("() => String(location.origin + location.pathname)")
internal external fun pageAddressAlone(): String

@JsFun("(text) => { window.__selftest = text; console.log(text); }")
internal external fun publishSelfTest(text: String)

/**
 * Opens a link in a new tab, with the two attributes that stop the opened page reaching back.
 *
 * `noopener` is not decoration: without it the release page gets a live `window.opener` handle to
 * this one and could navigate it somewhere else.
 */
@JsFun("(url) => { window.open(url, '_blank', 'noopener,noreferrer'); }")
internal external fun openInNewTab(url: String)

/**
 * Whether this page has a way to put text on the clipboard.
 *
 * `navigator.clipboard` exists only in a secure context, which cartogenesis.com is and a page
 * opened from a file is not, so the older `document.execCommand('copy')` is kept as the second
 * answer rather than as a relic: between them they cover every browser this build runs in.
 */
@JsFun(
    """() => {
        try {
            if (navigator.clipboard && navigator.clipboard.writeText) return true;
            return !!document.execCommand;
        } catch (e) { return false; }
    }"""
)
internal external fun clipboardAvailable(): Boolean

/**
 * Puts [text] on the clipboard, by whichever of the two ways this browser has.
 *
 * The modern call returns a promise and this does not wait for it: the reader is told what was
 * copied by a dialog that is already on screen, and a rejection — the tab lost focus, the
 * permission was refused — leaves them with the same text in front of them to copy by hand. The
 * fallback's textarea is put off the left edge rather than hidden, because a `display:none`
 * element cannot be selected and the copy silently does nothing.
 */
@JsFun(
    """(text) => {
        try {
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(text);
                return true;
            }
        } catch (e) {}
        try {
            const area = document.createElement('textarea');
            area.value = text;
            area.setAttribute('readonly', '');
            area.style.position = 'fixed';
            area.style.left = '-10000px';
            document.body.appendChild(area);
            area.select();
            const copied = document.execCommand('copy');
            document.body.removeChild(area);
            return copied;
        } catch (e) { return false; }
    }"""
)
internal external fun copyTextToClipboard(text: String): Boolean

/**
 * Whether this page is being pointed at with a fingertip rather than with a mouse.
 *
 * `(pointer: coarse)` is the media query for "the primary input has limited accuracy", which is a
 * touchscreen and is not a trackpad, a stylus on a tablet PC, or a phone with a mouse plugged into
 * it. Asked once at startup rather than watched: a device that changes its primary pointer
 * mid-session is a laptop being folded into a tablet, and a reload is a fair price for that.
 *
 * Guarded because `matchMedia` is missing in a handful of embedded webviews and throws on a bad
 * query string in older Safari; a browser that cannot answer is treated as a mouse, which is the
 * answer that changes nothing.
 */
@JsFun(
    """() => {
        try { return !!(window.matchMedia && window.matchMedia('(pointer: coarse)').matches); }
        catch (e) { return false; }
    }"""
)
internal external fun pointerIsCoarse(): Boolean

/**
 * One `GET`, resolving to the body as text or to null.
 *
 * The whole of the browser build's network reach, and it is called from exactly one place: the
 * update check, which runs when a reader asks for it. Everything that can go wrong — offline, a
 * CORS refusal, a status that is not a success, a body that never arrives — resolves to null here
 * rather than rejecting, because the caller has one question and this is one of its answers. A
 * ten-second abort keeps a hung request from leaving the dialog saying "Asking GitHub…" for ever.
 */
@JsFun(
    """(url) => {
        const abort = new AbortController();
        const timer = setTimeout(() => abort.abort(), 10000);
        return fetch(url, { signal: abort.signal, headers: { 'Accept': 'application/json' } })
            .then((response) => response.ok ? response.text() : null)
            .catch(() => null)
            .finally(() => clearTimeout(timer));
    }"""
)
private external fun fetchTextPromise(url: String): JsHandle

@JsFun("(value) => String(value)")
private external fun jsToString(value: JsHandle): String

internal suspend fun fetchTextOrNull(url: String): String? {
    val result = awaitPromise(fetchTextPromise(url)) ?: return null
    if (isNullish(result)) return null
    return jsToString(result)
}
