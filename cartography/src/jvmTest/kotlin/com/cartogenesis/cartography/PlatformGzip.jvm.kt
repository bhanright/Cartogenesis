package com.cartogenesis.cartography

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** The same algorithm as `:desktop`'s `GzipCompressor` — `java.util.zip`, which never suspends and
 *  never declines: the JVM can always do this, so neither function here ever returns null. */
actual suspend fun platformGzipCompress(data: ByteArray): ByteArray? {
    val out = ByteArrayOutputStream(data.size / 2)
    GZIPOutputStream(out).use { it.write(data) }
    return out.toByteArray()
}

actual suspend fun platformGzipDecompress(data: ByteArray): ByteArray? =
    GZIPInputStream(data.inputStream()).use { it.readBytes() }

internal actual fun platformGzipAvailable(): Boolean = true
