package com.cartogenesis.desktop

import com.cartogenesis.cartography.WorldDocument
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import java.io.File
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

/**
 * Rewrites the two saves `FolderInteropTest` on each side reads: one written by this store for the
 * browser's folder library to open, and one written by that library for this store to open.
 *
 * A throwaway, run by hand when the save format moves, as `RegenerateGzipFixture` is, and doing
 * nothing unless `REGENERATE_INTEROP_FIXTURES` is set. The procedure is two builds, because the
 * browser's save can only be made in a browser:
 *
 * 1. `REGENERATE_INTEROP_FIXTURES=1 ./gradlew :desktop:test --tests '*RegenerateInteropFixtures*'`
 *    writes the desktop's save into `:web`'s test sources, as base64 in [DESKTOP_SAVE_SOURCE].
 * 2. `REGENERATE_INTEROP_FIXTURES=1 ./gradlew :web:wasmJsBrowserTest --tests '*FolderInteropTest*'` makes
 *    the browser's test print the save its folder library wrote into the test results, as base64
 *    in numbered lines beginning [BROWSER_SAVE_MARKER].
 * 3. Step 1 again, which finds that line in `:web`'s results and writes the save it carries to
 *    [BROWSER_SAVE_RESOURCE].
 */
class RegenerateInteropFixtures {

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun `write the saves each side's interoperability test reads`() = runBlocking {
        if (System.getenv("REGENERATE_INTEROP_FIXTURES") == null) return@runBlocking

        val world = WorldGenerationEngine.generateBlocking(WorldGenConfig(seed = DESKTOP_SAVE_SEED, width = 32, height = 32))
        val document = WorldDocument(
            id = DESKTOP_SAVE_ID,
            title = "Written by the desktop",
            config = world.config,
            savedAt = 1_700_000_000_000L
        )
        val folder = Files.createTempDirectory("cartogenesis-interop").toFile()
        try {
            val key = DesktopWorldStore(folder, GzipCompressor).save(document, world)
            val bytes = File(folder, key).readBytes()
            val lines = Base64.encode(bytes).chunked(BASE64_LINE)
                .joinToString("\" +\n    \"", prefix = "    \"", postfix = "\"")
            File(DESKTOP_SAVE_SOURCE).writeText(
                """package com.cartogenesis.web

/**
 * `$key`, a 32 world saved into a folder by the desktop's own store, `DesktopWorldStore`, and
 * checked in as base64 for `FolderInteropTest` to put in a browser folder and open with the
 * folder library. Written by the desktop's `RegenerateInteropFixtures`; it goes stale with the
 * save format, as the gzip fixture does, and is regenerated the same way. Chunked because one
 * string this long is more than a class file's constant holds.
 */
internal val DESKTOP_WRITTEN_SAVE_BASE64: String =
$lines
"""
            )
            println("INTEROP desktop save rewritten: $key, ${bytes.size} bytes")
        } finally {
            folder.deleteRecursively()
        }

        // Numbered pieces, `<marker><index> <base64>`, joined in their order. Found by pattern and
        // by length rather than by line, because the test reporter runs the printed lines together
        // with nothing between them, and the next marker begins with letters base64 also uses.
        val results = File(BROWSER_RESULTS).walkTopDown().filter { it.isFile && it.extension == "xml" }
        val piece = Regex("${BROWSER_SAVE_MARKER}(\\d{4}) ([A-Za-z0-9+/=]{1,$BROWSER_SAVE_PIECE})")
        val pieces = results.flatMap { piece.findAll(it.readText()) }
            .map { it.groupValues[1].toInt() to it.groupValues[2] }
            .sortedBy { it.first }
            .toList()
        if (pieces.isEmpty()) {
            println("INTEROP no browser save in $BROWSER_RESULTS; run step 2 of this class's procedure, then this again")
            return@runBlocking
        }
        check(pieces.map { it.first } == pieces.indices.toList()) { "the browser's save is missing pieces" }
        val browserBytes = Base64.decode(pieces.joinToString("") { it.second })
        File(BROWSER_SAVE_RESOURCE).apply { parentFile.mkdirs() }.writeBytes(browserBytes)
        println("INTEROP browser save rewritten: ${browserBytes.size} bytes")
    }

    companion object {
        const val DESKTOP_SAVE_ID = "interop-desktop"
        const val DESKTOP_SAVE_SEED = 4096L

        /** Where the desktop's save goes, from this module's directory, which is where tests run. */
        const val DESKTOP_SAVE_SOURCE = "../web/src/wasmJsTest/kotlin/com/cartogenesis/web/DesktopWrittenSave.kt"

        /** The line the browser's test prints its save on, and the base64 after it. */
        const val BROWSER_SAVE_MARKER = "FOLDER-WRITTEN-SAVE "

        /** How many base64 characters the browser's test prints a piece: its `PRINTED_LINE`. */
        const val BROWSER_SAVE_PIECE = 1_000

        const val BROWSER_RESULTS = "../web/build/test-results"
        const val BROWSER_SAVE_RESOURCE = "src/test/resources/interop/folder-written.cgw"

        private const val BASE64_LINE = 1_000
    }
}
