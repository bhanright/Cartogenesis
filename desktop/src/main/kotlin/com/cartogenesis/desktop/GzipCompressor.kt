package com.cartogenesis.desktop

import com.cartogenesis.cartography.Compressor
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Gzip, from `java.util.zip`.
 *
 * The id maps squeeze hard — a realm map is long runs of the same integer — while the height
 * fields barely move, because float noise is close to incompressible by design. The ratio for a
 * whole save therefore depends on how much of it is ids, which is why it is measured rather than
 * assumed.
 */
object GzipCompressor : Compressor {

    override val name: String get() = "gzip"

    override fun compress(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size / 2)
        GZIPOutputStream(out, BUFFER).use { it.write(data) }
        return out.toByteArray()
    }

    override fun decompress(data: ByteArray): ByteArray =
        GZIPInputStream(data.inputStream(), BUFFER).use { it.readBytes() }

    /** Large enough that a ninety-megabyte payload is not written a few kilobytes at a time. */
    private const val BUFFER = 1 shl 16
}
