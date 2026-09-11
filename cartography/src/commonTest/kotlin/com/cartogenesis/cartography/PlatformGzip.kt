package com.cartogenesis.cartography

/**
 * A real gzip, for [GzipInteroperabilityTest] — proof that the desktop's `java.util.zip` and a
 * browser's `CompressionStream` produce and accept the same bytes, not merely the same format.
 *
 * Test-only: production code reaches gzip through the platform's [Compressor] (`:desktop`'s
 * `GzipCompressor`, `:web`'s `WebGzipCompressor`), which `:cartography` cannot depend on in either
 * direction. This is `:cartography`'s own `jvmTest`/`wasmJsTest` copy of the same two algorithms,
 * kept to the minimum this one test needs.
 *
 * Null means this platform's test environment cannot do it — see the wasmJs actual for when that
 * is expected to happen.
 */
expect suspend fun platformGzipCompress(data: ByteArray): ByteArray?

expect suspend fun platformGzipDecompress(data: ByteArray): ByteArray?

/** Whether this platform's test environment can even attempt the round trip. Always true on the
 *  JVM; on wasmJs, whether Node has `CompressionStream`/`DecompressionStream` at all. */
internal expect fun platformGzipAvailable(): Boolean

/** Adapts the two functions above to the [Compressor] seam [WorldCodec] expects. */
internal object PlatformGzipCompressor : Compressor {
    override val name: String get() = "gzip"
    override suspend fun compress(data: ByteArray): ByteArray? = platformGzipCompress(data)
    override suspend fun decompress(data: ByteArray): ByteArray? = platformGzipDecompress(data)
}
