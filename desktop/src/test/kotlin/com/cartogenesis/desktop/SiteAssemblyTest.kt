package com.cartogenesis.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Checks the tree `:web:assembleSite` builds for cartogenesis.com, before it is uploaded.
 *
 * Everything here is a failure that ships silently. A missing font, a source map, an unreplaced
 * loader stamp or a stale second copy of the application wasm all deploy perfectly happily and
 * are only discovered by a visitor — in the stamp's case, by a *returning* visitor on the deploy
 * after next, which is the worst possible place to learn about it.
 *
 * It lives in the desktop module for the same reason `WebDeploymentContractTest` does: the web
 * module compiles to wasm, which cannot read files. It is run by `:desktop:siteTest`, which
 * assembles the site first — never by `:desktop:test`, which has no reason to build 12 MB of
 * WebAssembly and would otherwise be testing whatever an earlier run happened to leave behind.
 */
class SiteAssemblyTest {

    private val repoRoot: File
        get() {
            var dir = File(".").absoluteFile
            while (dir.parentFile != null) {
                if (File(dir, "settings.gradle.kts").isFile) return dir
                dir = dir.parentFile
            }
            fail("could not find the repository root from ${File(".").absolutePath}")
        }

    private val site: File
        get() {
            val dir = File(repoRoot, "web/build/site")
            assertTrue(
                dir.isDirectory,
                "no assembled site at ${dir.absolutePath}. Run :web:assembleSite, or :desktop:siteTest " +
                    "which does it for you."
            )
            return dir
        }

    private fun file(path: String): File {
        val file = File(site, path)
        assertTrue(file.isFile, "the assembled site is missing $path")
        return file
    }

    /** The application's bytes, read as one char per byte so ASCII markers can be searched for. */
    private fun File.asLatin1(): String = readBytes().toString(Charsets.ISO_8859_1)

    /**
     * A WebP file's pixel size, read out of the container.
     *
     * Cheaper and firmer than decoding it. The page states each figure's width and height so the
     * layout does not jump while they load, so a figure that quietly changed shape would reflow
     * the first screenful and nothing else would notice. Both of the forms Skia writes are
     * handled: a plain lossy file (`VP8 `) and the extended one (`VP8X`).
     */
    private fun webpDimensions(file: File): Pair<Int, Int> {
        val bytes = file.readBytes()
        fun byteAt(index: Int) = bytes[index].toInt() and 0xFF
        return when (val chunk = String(bytes, 12, 4, Charsets.ISO_8859_1)) {
            // Lossy: a ten-byte frame tag, then fourteen bits of width and fourteen of height.
            "VP8 " -> Pair(
                (byteAt(26) or (byteAt(27) shl 8)) and 0x3FFF,
                (byteAt(28) or (byteAt(29) shl 8)) and 0x3FFF
            )
            // Lossless: a signature byte, then fourteen bits each, one less than the size.
            "VP8L" -> {
                val packed = byteAt(21).toLong() or (byteAt(22).toLong() shl 8) or
                    (byteAt(23).toLong() shl 16) or (byteAt(24).toLong() shl 24)
                Pair(
                    (packed and 0x3FFF).toInt() + 1,
                    ((packed shr 14) and 0x3FFF).toInt() + 1
                )
            }
            // Extended: the canvas size is three bytes each, one less than the size.
            "VP8X" -> Pair(
                (byteAt(24) or (byteAt(25) shl 8) or (byteAt(26) shl 16)) + 1,
                (byteAt(27) or (byteAt(28) shl 8) or (byteAt(29) shl 16)) + 1
            )
            else -> fail("${file.name} has an unrecognised WebP chunk '$chunk'")
        }
    }

    @Test
    fun `the description page and its hero are published`() {
        val index = file("index.html")
        assertTrue(index.length() > 4_000, "index.html is ${index.length()} bytes — that is not the page")
        assertTrue(
            index.readText().contains("""href="/app/""""),
            "the description page no longer points at /app/, so its launch button goes nowhere"
        )

        // The hero is also the link preview image, so a scraper reads this exact file.
        val hero = file("img/atlas.webp")
        val header = hero.asLatin1()
        assertTrue(
            header.startsWith("RIFF") && header.substring(8, 12) == "WEBP",
            "img/atlas.webp is not a WebP file — the copy has been filtered as though it were text"
        )
        assertEquals(
            1600 to 800, webpDimensions(hero),
            "the hero is no longer 1600x800. The page reserves that shape in the img tag, so a " +
                "different one reflows the whole first screenful as it loads."
        )
    }

    @Test
    fun `every figure the page shows was rendered, at the size the page reserves`() {
        // These names are the contract between SiteImagery.FIGURES and the page's img tags.
        // Renaming one side alone deploys a broken figure that nothing else would notice.
        val expected = mapOf(
            "atlas.webp" to (1600 to 800)
        )
        val page = file("index.html").readText()
        expected.forEach { (name, size) ->
            assertEquals(size, webpDimensions(file("img/$name")), "img/$name is the wrong size")
            assertTrue(
                page.contains("img/$name"),
                "img/$name was rendered and published, but the page never references it"
            )
        }

        val published = File(site, "img").listFiles { f -> f.isFile }.orEmpty().map { it.name }
        assertEquals(
            expected.keys.sorted(), published.sorted(),
            "img/ holds something other than the figures the page shows — a contact sheet left " +
                "by -Pcontact, or a figure the page has stopped asking for"
        )
    }

    @Test
    fun `the five typefaces are published and none is fetched from anywhere else`() {
        val faces = listOf(
            "spectral_regular.ttf",
            "spectral_semibold.ttf",
            "plex_sans_regular.ttf",
            "plex_sans_medium.ttf",
            "plex_mono_regular.ttf"
        )
        val page = file("index.html").readText()
        faces.forEach { face ->
            val font = file("fonts/$face")
            assertTrue(
                font.length() > 50_000,
                "fonts/$face is only ${font.length()} bytes — that is not a TrueType face"
            )
            // 0x00010000 is the TrueType outline version tag. Anything else here means the file
            // was copied through a text filter, which is silent and fatal.
            assertEquals(
                listOf(0, 1, 0, 0), font.readBytes().take(4).map { it.toInt() and 0xFF },
                "fonts/$face does not begin with the TrueType version tag"
            )
            assertTrue(
                page.contains("fonts/$face"),
                "fonts/$face is published but no @font-face rule asks for it"
            )
        }

        // The point of self-hosting: a page that asks a font host for a typeface tells that host
        // who reads it, and falls back to whatever the reader has whenever the host is slow.
        listOf("fonts.googleapis", "fonts.gstatic").forEach { host ->
            assertFalse(page.contains(host), "the page has gone back to fetching type from $host")
        }
    }

    @Test
    fun `the page reaches out to nothing but this project`() {
        // Ground rule 10: this repository names no other site of the author's. Rather than
        // spelling out the name that must not appear — which would be naming it — every absolute
        // URL on the page is checked against the short list of hosts that belong here. Anything
        // else fails, a font host and an analytics script included.
        // www.w3.org is the SVG namespace in the inline data-URI favicon. It is an identifier
        // rather than an address: no browser ever fetches it, and an SVG without it does not
        // render at all.
        val allowed = setOf("cartogenesis.com", "github.com", "api.github.com", "www.w3.org")
        val strangers = Regex("""https?://([A-Za-z0-9.-]+)""")
            .findAll(file("index.html").readText())
            .map { it.groupValues[1] }
            .toSet() - allowed
        assertTrue(
            strangers.isEmpty(),
            "the page reaches out to " + strangers.joinToString() + "; only " +
                allowed.joinToString() + " belong here. See ground rule 10 in REALISM_PLAN.md."
        )
    }

    @Test
    fun `the host configuration files are published`() {
        val headers = file("_headers").readText()
        assertTrue(
            headers.contains("X-Content-Type-Options: nosniff"),
            "_headers no longer sets nosniff"
        )
        assertTrue(
            headers.contains("immutable"),
            "_headers no longer caches the hashed files, so every visit re-downloads 12 MB"
        )
        assertTrue(
            file("_redirects").readText().contains("/releases/latest"),
            "_redirects no longer forwards /releases/latest, so the download card is a 404"
        )
    }

    @Test
    fun `the shell keeps the two names the application reaches for`() {
        val shell = file("app/index.html").readText()

        // The other half of WebDeploymentContractTest: that one pins the names in the Kotlin
        // source, this one pins them in the page that is actually deployed.
        assertTrue(
            shell.contains("composeTarget"),
            "the deployed shell has no #composeTarget for Compose to mount into"
        )
        assertTrue(
            shell.contains("""id="loading""""),
            "the deployed shell has no #loading, so nothing can signal that the app is ready and " +
                "the loading overlay never lifts"
        )
    }

    @Test
    fun `the loader is stamped with the build rather than the placeholder`() {
        val shell = file("app/index.html").readText()
        val stamp = Regex("""src="cartogenesis\.js\?v=([^"]*)"""").find(shell)?.groupValues?.get(1)
            ?: fail("the deployed shell does not load cartogenesis.js with a ?v= stamp at all")

        assertTrue(stamp.isNotBlank(), "the loader's ?v= stamp is empty")
        assertTrue(
            stamp != "__STAMP__",
            "the loader still carries the placeholder stamp, so every deploy asks for the same " +
                "loader URL and a returning visitor runs a cached loader against wasm files it " +
                "has never seen"
        )
        // The stamp only helps if the file it is stamped onto is there.
        file("app/cartogenesis.js")
    }

    @Test
    fun `the application and Skia are both published exactly once`() {
        val wasm = File(site, "app").listFiles { f -> f.isFile && f.extension == "wasm" }
            .orEmpty().sortedBy { it.name }

        assertEquals(
            2, wasm.size,
            "expected two .wasm files, the application and Skia, but found " +
                wasm.joinToString { "${it.name} (${it.length() / 1024} KB)" } +
                ". The names are content hashes, so more than two means an old build was left behind."
        )

        // The application module carries its own package names in the wasm name section; Skia
        // carries none. That is a firmer test of which is which than the file sizes are.
        val application = wasm.filter { it.asLatin1().contains("cartogenesis") }
        assertEquals(
            1, application.size,
            "exactly one of the two .wasm files should be the application; " +
                wasm.joinToString { it.name } + " matched " + application.size
        )
        wasm.forEach {
            assertTrue(it.length() > 1_000_000, "${it.name} is only ${it.length()} bytes")
        }
    }

    @Test
    fun `the bundled interface fonts are published and the source map is not`() {
        val resources = File(site, "app/composeResources")
        assertTrue(
            resources.isDirectory,
            "composeResources is missing, so the interface falls back to whatever the browser has"
        )

        val fonts = resources.walkTopDown().filter { it.isFile && it.extension == "ttf" }.toList()
        assertEquals(
            6, fonts.size,
            "expected the six bundled faces (Plex Sans regular and medium, Plex Mono regular and " +
                "bold, Spectral regular and semibold) but found " + fonts.joinToString { it.name } +
                ". If a face was deliberately added or dropped, move this number."
        )

        val maps = site.walkTopDown().filter { it.isFile && it.name.endsWith(".map") }.toList()
        assertTrue(
            maps.isEmpty(),
            "the source map is 1.7 MB of debug weight and publishes the original Kotlin: " +
                maps.joinToString { it.name }
        )
    }
}
