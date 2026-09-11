package com.cartogenesis.cartography

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * A save carries the world now, and compression is a platform seam — the JVM's `java.util.zip`
 * and, since this chunk, a browser's `CompressionStream` — so nothing before this proved the two
 * actually agree on what "gzip" means rather than each merely reading its own writing back.
 *
 * [GZIP_FIXTURE_BASE64] is a small world (32x32) written once by the JVM's own gzip algorithm and
 * checked in as bytes, precisely so this does not depend on a JVM being present to write one at
 * test time. This class runs in both `jvmTest` and `wasmJsNodeTest` and decodes the same fixture
 * in both, so a platform whose "gzip" silently drifted from RFC 1952 — a wrong header, a missing
 * trailer, a different dictionary — would fail here without needing a real browser at all.
 */
class GzipInteroperabilityTest {

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `a gzip container the JVM wrote decodes on this platform too`() = runTest {
        if (!platformGzipAvailable()) {
            // Documented in the D2 report: the plan allows for a test Node too old to have
            // CompressionStream/DecompressionStream at all, in which case there is nothing this
            // platform's test run can prove either way.
            return@runTest
        }

        val bytes = Base64.decode(GZIP_FIXTURE_BASE64)
        val header = WorldCodec.decodeHeader(bytes)
        assertEquals("gzip", header.compression)
        assertEquals("Gzip fixture", header.document.title)

        val save = WorldCodec.decode(bytes, PlatformGzipCompressor)
        val world = assertNotNull(save.world, "the JVM-gzipped fixture did not decode on this platform")

        assertEquals(32, save.document.config.width)
        assertEquals(32, save.document.config.height)
        assertEquals(4096L, save.document.config.seed)
        assertTrue(world.terrain.height.data.isNotEmpty())
        assertEquals(32 * 32, world.terrain.height.data.size)
        // Round-tripping it again through this platform's own compressor proves the read path is
        // not merely tolerant of the fixture by accident - re-encoding and decoding it again has
        // to come back exactly the same world.
        val rewritten = WorldCodec.encode(save.document, world, PlatformGzipCompressor, "roundtrip")
        val reread = assertNotNull(WorldCodec.decode(rewritten, PlatformGzipCompressor).world)
        for (i in world.terrain.height.data.indices) {
            assertEquals(
                world.terrain.height.data[i].toRawBits(),
                reread.terrain.height.data[i].toRawBits(),
                "height differed at cell $i after a local round trip"
            )
        }
    }
}
