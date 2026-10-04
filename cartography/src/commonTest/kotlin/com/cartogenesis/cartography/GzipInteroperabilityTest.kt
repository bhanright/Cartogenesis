package com.cartogenesis.cartography

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * A save carries the world, and compression is a platform seam, so without this nothing proves
 * that a save is RFC 1952 gzip rather than whatever the writer's own reader happens to accept.
 *
 * [GZIP_FIXTURE_BASE64] is a small world (64x32, square cells) written once by the JVM's own gzip
 * algorithm and checked in as bytes, and decoded here through the test's own gzip. It ran on the
 * browser's `CompressionStream` too until the browser build was removed (G1); the frozen browser
 * app's saves are format 14, which this fixture is.
 */
class GzipInteroperabilityTest {

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `a gzip container the JVM wrote decodes on this platform too`() = runTest {
        if (!platformGzipAvailable()) {
            // A test environment with no gzip at all can prove nothing either way.
            return@runTest
        }

        val bytes = Base64.decode(GZIP_FIXTURE_BASE64)
        val header = WorldCodec.decodeHeader(bytes)
        assertEquals("gzip", header.compression)
        assertEquals("Gzip fixture", header.document.title)

        val save = WorldCodec.decode(bytes, PlatformGzipCompressor)
        val world = save.world

        assertEquals(64, save.document.config.width)
        assertEquals(32, save.document.config.height)
        assertEquals(4096L, save.document.config.seed)
        assertTrue(world.terrain.height.data.isNotEmpty())
        assertEquals(64 * 32, world.terrain.height.data.size)
        // Round-tripping it again through this platform's own compressor proves the read path is
        // not merely tolerant of the fixture by accident - re-encoding and decoding it again has
        // to come back exactly the same world.
        val rewritten = WorldCodec.encode(save.document, world, PlatformGzipCompressor, "roundtrip")
        val reread = WorldCodec.decode(rewritten, PlatformGzipCompressor).world
        for (i in world.terrain.height.data.indices) {
            assertEquals(
                world.terrain.height.data[i].toRawBits(),
                reread.terrain.height.data[i].toRawBits(),
                "height differed at cell $i after a local round trip"
            )
        }
    }
}
