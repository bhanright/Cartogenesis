package com.cartogenesis.cartography

/**
 * Squeezing a save's chunks, which common code cannot do for itself.
 *
 * The JVM has `java.util.zip`; a browser has `CompressionStream`; common Kotlin has neither, and
 * a save is tens of megabytes of arrays, so this is worth a seam of its own rather than shipping
 * the raw bytes everywhere.
 *
 * The codec hands this one chunk at a time — see [WorldCodec.CHUNK_BYTES] — so neither method is
 * ever given, or asked to produce, more than a chunk's worth of bytes. A platform that cannot
 * compress returns null and the chunk is stored as it is, with a flag in its frame saying so, so
 * the file still opens anywhere.
 *
 * Both methods suspend. `java.util.zip` never needs to, but a browser's `CompressionStream` and
 * `DecompressionStream` are asynchronous throughout, and [WorldLibrary] suspends for the same
 * reason IndexedDB does.
 */
interface Compressor {

    /** What goes in the header. `gzip` or `none`; anything else is a format nobody can read yet. */
    val name: String

    /** Null when this platform cannot compress, in which case the chunk is stored raw. */
    suspend fun compress(data: ByteArray): ByteArray?

    /**
     * [data] expanded, or null when this platform cannot expand [name]-compressed bytes — a
     * readable file this reader cannot open, rather than a corrupt one.
     *
     * Stops once it has produced more than [limitBytes], so the answer is never longer than
     * `limitBytes + 1`: a chunk that expands past what its frame promised is found out without the
     * platform ever holding what it would have expanded to. The caller compares the length.
     */
    suspend fun decompress(data: ByteArray, limitBytes: Int): ByteArray?
}

/** The fallback: store the payload as it is, and say so. */
object NoCompression : Compressor {
    override val name: String get() = "none"
    override suspend fun compress(data: ByteArray): ByteArray? = null
    override suspend fun decompress(data: ByteArray, limitBytes: Int): ByteArray? = null
}
