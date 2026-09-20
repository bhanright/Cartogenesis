package com.cartogenesis.cartography

import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

/**
 * Rewrites [GZIP_FIXTURE_BASE64] from this build.
 *
 * A throwaway, run by hand when the save format moves — which is what [GzipFixture]'s own doc
 * comment says to do — and named so the per-merge tier skips it. It writes the source file it is
 * regenerating, so the diff is the fixture and nothing else.
 */
class RegenerateGzipFixture {

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `write the fixture this build would read`() = runTest {
        if (System.getenv("REGENERATE_GZIP_FIXTURE") == null) return@runTest

        val document = WorldDocument(
            id = "gzip-fixture",
            title = "Gzip fixture",
            config = com.cartogenesis.worldgen.model.WorldGenConfig(
                seed = 4096L, width = 32, height = 32
            ),
            savedAt = 1_700_000_000_000L
        )
        val world = com.cartogenesis.worldgen.WorldGenerationEngine.generate(document.config)
        val bytes = WorldCodec.encode(document, world, PlatformGzipCompressor, "gzip")
        val base64 = Base64.encode(bytes)

        val chunk = 1_000
        val lines = base64.chunked(chunk).joinToString("\" +\n    \"", prefix = "    \"", postfix = "\"")
        val source = """package com.cartogenesis.cartography

/**
 * A 32x32 world, gzipped by the JVM's own gzip algorithm (see [GzipInteroperabilityTest]),
 * checked in as base64 chunks because a single Kotlin string literal this size trips the
 * JVM class file's 64KB-per-constant limit.
 *
 * It is a whole save of the current format, so it goes stale whenever the format does:
 * a section added or renamed, or a bump of [WorldCodec.FORMAT_VERSION], and the reader
 * refuses it. Regenerate it with `RegenerateGzipFixture` when that happens.
 */
internal val GZIP_FIXTURE_BASE64: String =
$lines
"""
        File("src/commonTest/kotlin/com/cartogenesis/cartography/GzipFixture.kt")
            .writeText(source)
        println("GZIP FIXTURE rewritten, ${bytes.size} bytes, format ${WorldCodec.FORMAT_VERSION}")
    }
}
