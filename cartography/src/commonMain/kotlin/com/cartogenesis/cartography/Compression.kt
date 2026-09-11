package com.cartogenesis.cartography

/**
 * Squeezing a container's payload, which common code cannot do for itself.
 *
 * The JVM has `java.util.zip`; a browser has `CompressionStream`; common Kotlin has neither, and
 * a save is tens of megabytes of arrays, so this is worth a seam of its own rather than shipping
 * the raw bytes everywhere.
 *
 * A platform that cannot compress returns null and the container records `none` in its header, so
 * the file still opens anywhere — the flag travels with the bytes rather than being assumed from
 * whoever is reading them.
 */
interface Compressor {

    /** What goes in the header. `gzip` or `none`; anything else is a format nobody can read yet. */
    val name: String

    /** Null when this platform cannot compress, in which case the payload is stored raw. */
    fun compress(data: ByteArray): ByteArray?

    /**
     * Null when this platform cannot expand [name]-compressed bytes, which is a readable file this
     * reader cannot open rather than a corrupt one — worth saying differently to the user.
     */
    fun decompress(data: ByteArray): ByteArray?
}

/** The fallback: store the payload as it is, and say so. */
object NoCompression : Compressor {
    override val name: String get() = "none"
    override fun compress(data: ByteArray): ByteArray? = null
    override fun decompress(data: ByteArray): ByteArray = data
}
