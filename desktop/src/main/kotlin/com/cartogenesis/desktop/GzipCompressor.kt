package com.cartogenesis.desktop

import com.cartogenesis.cartography.Compressor
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Gzip, from `java.util.zip`, a chunk at a time.
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
        // Half the input, as a first guess at how far a chunk squeezes; the stream grows it if not.
        val out = ByteArrayOutputStream(data.size / 2)
        GZIPOutputStream(out, BUFFER_BYTES).use { it.write(data) }
        return out.toByteArray()
    }

    /**
     * Reads no more than [limitBytes] and one byte over, so a chunk that expands past what its
     * frame promised is caught with one byte to spare rather than expanded to whatever it holds.
     */
    override suspend fun decompress(data: ByteArray, limitBytes: Int): ByteArray {
        require(limitBytes in 0 until Int.MAX_VALUE) { "a limit of $limitBytes bytes" }
        GZIPInputStream(data.inputStream(), BUFFER_BYTES).use { input ->
            val out = ByteArray(limitBytes + 1)
            var filled = 0
            while (filled < out.size) {
                val count = input.read(out, filled, out.size - filled)
                if (count < 0) break
                filled += count
            }
            return if (filled == out.size) out else out.copyOf(filled)
        }
    }

    /** 64 KB: large enough that a mebibyte chunk is not written a page at a time. */
    private const val BUFFER_BYTES = 1 shl 16
}
