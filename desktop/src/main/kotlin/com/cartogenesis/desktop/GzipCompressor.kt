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

    // Never actually suspends - java.util.zip is plain blocking work - but the seam is suspend
    // throughout so the browser's CompressionStream fits it too. See Compressor's doc comment.
    override suspend fun compress(data: ByteArray): ByteArray {
        // Half the input, as a first guess at how far a save squeezes; the stream grows it if not.
        val out = ByteArrayOutputStream(data.size / 2)
        GZIPOutputStream(out, BUFFER_BYTES).use { it.write(data) }
        return out.toByteArray()
    }

    override suspend fun decompress(data: ByteArray): ByteArray =
        GZIPInputStream(data.inputStream(), BUFFER_BYTES).use { it.readBytes() }

    /** 64 KB: large enough that a ninety-megabyte payload is not written a page at a time. */
    private const val BUFFER_BYTES = 1 shl 16
}
