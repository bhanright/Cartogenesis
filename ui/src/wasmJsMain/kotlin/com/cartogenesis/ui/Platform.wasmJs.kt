package com.cartogenesis.ui

import kotlin.random.Random

private external interface JsDate

@JsFun("() => Date.now()")
private external fun jsNow(): Double

@JsFun("(millis) => new Date(millis).toLocaleString()")
private external fun jsFormat(millis: Double): String

@JsFun("() => (crypto.randomUUID ? crypto.randomUUID() : '')")
private external fun jsRandomUuid(): String

actual fun epochMillis(): Long = jsNow().toLong()

actual fun randomId(): String {
    val fromCrypto = jsRandomUuid()
    if (fromCrypto.isNotEmpty()) return fromCrypto
    // Older browsers, and any context the page is not served securely in, have no randomUUID.
    // A save identifier only has to be unique within one library, so this is enough.
    val bytes = ByteArray(16) { Random.nextInt(256).toByte() }
    return bytes.joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
}

actual fun formatTimestamp(millis: Long): String = jsFormat(millis.toDouble())
