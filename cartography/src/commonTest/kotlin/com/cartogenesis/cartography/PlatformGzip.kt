package com.cartogenesis.cartography

/**
 * A real gzip, for [GzipInteroperabilityTest]: a checked-in save decoded by an RFC 1952 gzip that
 * is not the one the desktop wrote it with.
 *
 * Test-only: production code reaches gzip through the platform's [Compressor] (`:desktop`'s
 * `GzipCompressor`), which `:cartography` cannot depend on. This is `:cartography`'s own copy,
 * kept to the minimum this one test needs. Declared in common code because the test is; the JVM's
 * is the one implementation since the browser targets were removed (G1).
 *
 * Null means this platform's test environment cannot do it.
 */
expect suspend fun platformGzipCompress(data: ByteArray): ByteArray?

expect suspend fun platformGzipDecompress(data: ByteArray): ByteArray?

/** Whether this platform's test environment can even attempt the round trip. Always true on the JVM. */
internal expect fun platformGzipAvailable(): Boolean

/** Adapts the two functions above to the [Compressor] seam [WorldCodec] expects. */
internal object PlatformGzipCompressor : Compressor {
    override val name: String get() = "gzip"
    override suspend fun compress(data: ByteArray): ByteArray? = platformGzipCompress(data)
    override suspend fun decompress(data: ByteArray, limitBytes: Int): ByteArray? =
        platformGzipDecompress(data)?.let { if (it.size > limitBytes) it.copyOf(limitBytes + 1) else it }
}
