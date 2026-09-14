package com.cartogenesis.desktop

import com.cartogenesis.cartography.ColorVision
import com.cartogenesis.cartography.MapStyle
import com.cartogenesis.ui.ThemeChoice
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

    private companion object {
        /**
         * The counting words the page writes its tallies in, indexed by the number they mean.
         *
         * The page says "Seventeen for the window" rather than "17 for the window" because it is
         * prose, so a guard that wants to compare that with `ThemeChoice.entries.size` has to
         * spell the numbers somewhere. Here, once, rather than in the assertion.
         */
        val NUMBER_WORDS = listOf(
            "Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
            "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen",
            "Eighteen", "Nineteen", "Twenty"
        )

        /**
         * WCAG 2.1 AA for body text (1.4.3), the bar `SitePaletteContrastTest` holds the rest of
         * the page to. The naming bands are held to it rather than to the large-text bar, because
         * the line under a band's name is small text by any measure.
         */
        const val AA = 4.5
    }

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

        // The hero is also the link preview image, so a scraper reads this exact file — and reads
        // it at the address the og:image names, which is why that is checked and not only the tag
        // the page draws with.
        val hero = file("img/natural.webp")
        val header = hero.asLatin1()
        assertTrue(
            header.startsWith("RIFF") && header.substring(8, 12) == "WEBP",
            "img/natural.webp is not a WebP file — the copy has been filtered as though it were text"
        )
        assertTrue(
            index.readText().contains("""og:image" content="https://cartogenesis.com/img/natural.webp"""),
            "the link preview still points at a figure the page no longer shows"
        )
        assertEquals(
            1600 to 800, webpDimensions(hero),
            "the hero is no longer 1600x800. The page reserves that shape in the img tag, so a " +
                "different one reflows the whole first screenful as it loads."
        )
    }

    /**
     * One figure's `<img>` tag, as the page writes it.
     *
     * Attribute order is the page's own and is not going to change by accident; matching the whole
     * tag rather than hunting for the file name is what lets the width, the height and the `alt`
     * be read back out of it.
     */
    private fun imageTag(page: String, file: String): String =
        Regex("""<img\s+src="img/${Regex.escape(file)}"[^>]*>""").find(page)?.value
            ?: fail("the page has no <img> for img/$file")

    private fun attribute(tag: String, name: String): String =
        Regex("""$name="([^"]*)"""").find(tag)?.groupValues?.get(1)
            ?: fail("""$tag has no $name attribute""")

    /**
     * The panel names a strip's `alt` text promises, in order.
     *
     * The sentence ends "…, labelled A, B and C." — a shape rather than a word count, so that the
     * list a reader who cannot see the figure is given and the list `SiteImagery` actually letters
     * the bands with can be compared name for name. A panel dropped from either side moves one of
     * the two lists and not the other.
     */
    private fun namesPromised(alt: String): List<String> {
        val listed = alt.substringAfter("labelled ", "").substringBefore('.')
        assertTrue(
            listed.isNotEmpty(),
            "a comparison strip's alt text has to end \"…, labelled A, B and C.\" so that what it " +
                "promises can be compared with what is drawn; this one reads \"$alt\""
        )
        return listed.split(Regex(""",\s*|\s+and\s+""")).map { it.trim() }.filter { it.isNotEmpty() }
    }

    @Test
    fun `every figure the page shows was rendered, at the size the page reserves`() {
        // These names are the contract between SiteImagery.FIGURES and the page's img tags.
        // Renaming one side alone deploys a broken figure that nothing else would notice.
        val expected = mapOf(
            "natural.webp" to (1600 to 800),
            "styles.webp" to (1924 to 711),
            "layers.webp" to (1926 to 667)
        )
        val page = file("index.html").readText()
        expected.forEach { (name, size) ->
            assertEquals(size, webpDimensions(file("img/$name")), "img/$name is the wrong size")
            val tag = imageTag(page, name)
            // Stated in the tag as well as rendered: the page reserves each figure's shape so the
            // first screenful does not reflow as they load, and a reserved shape that is not the
            // figure's shape reflows it twice over.
            assertEquals(
                size,
                attribute(tag, "width").toInt() to attribute(tag, "height").toInt(),
                "the page reserves a different shape for img/$name than the figure that was rendered"
            )
        }

        val published = File(site, "img").listFiles { f -> f.isFile }.orEmpty().map { it.name }
        assertEquals(
            expected.keys.sorted(), published.sorted(),
            "img/ holds something other than the figures the page shows — a contact sheet left " +
                "by -Pcontact, or a figure the page has stopped asking for"
        )
    }

    /**
     * That a comparison strip draws exactly the panels the page says it draws.
     *
     * A strip is one image, so a panel that stopped being rendered would not 404 and would not
     * break the layout: the figure would simply arrive one panel short, with the page's prose and
     * its `alt` text still promising three. Two things are compared with the one picture — the
     * names the `alt` lists, and the arithmetic the width has to satisfy — so neither the list nor
     * the strip can move without the other.
     */
    @Test
    fun `each comparison strip draws the panels the page's alt text lists`() {
        val page = file("index.html").readText()
        val strips = SiteImagery.FIGURES.filter { it.panels.size > 1 }
        assertTrue(
            strips.isNotEmpty(),
            "SiteImagery has no comparison strip left; the page's style and layer sections are " +
                "about figures that are no longer rendered"
        )

        strips.forEach { figure ->
            val alt = attribute(imageTag(page, figure.file), "alt")
            assertEquals(
                figure.panels.map { it.name }, namesPromised(alt),
                "img/${figure.file} letters its bands ${figure.panels.joinToString { it.name }}, " +
                    "and the page's alt text promises a reader ${namesPromised(alt)}"
            )

            val drawn = webpDimensions(file("img/${figure.file}"))
            assertEquals(
                figure.width to figure.height, drawn,
                "img/${figure.file} is not the size ${figure.panels.size} panels of " +
                    "${figure.window.width}x${figure.window.height} come to"
            )
            // The width says how many panels are in the file, independently of what the figure
            // table claims: n windows and the n-1 hairlines between them.
            assertEquals(
                figure.panels.size,
                (drawn.first + SiteImagery.DIVIDER) / (figure.window.width + SiteImagery.DIVIDER),
                "img/${figure.file} is ${drawn.first} wide, which is not " +
                    "${figure.panels.size} panels' worth"
            )

            // The window belongs to the figure and not to the panel, so the panels of a strip
            // cannot be showing different ground. What they must not share is the reading: two
            // panels drawing the same view in the same style would be a comparison of nothing.
            assertEquals(
                figure.panels.size,
                figure.panels.map { it.view to it.style }.distinct().size,
                "two panels of img/${figure.file} are the same reading of the same window"
            )
            println(
                "SITE ${figure.file} ${drawn.first}x${drawn.second}, " +
                    "${figure.panels.size} panels of ${figure.window.width}x${figure.window.height} " +
                    "at ${figure.window.x},${figure.window.y}: ${figure.panels.joinToString { it.name }}"
            )
        }
    }

    /**
     * That a naming band's lettering is legible on its own tint, and that the tint is the page's.
     *
     * The bands are drawn into the figure, so nothing on the page measures them: they are pixels by
     * the time a browser sees them, and `SitePaletteContrastTest` only ever sees CSS. A band is
     * text a reader is asked to read, though, and it is the only text on this site that the page's
     * own guard cannot reach — so it is measured here, by the same arithmetic and against the same
     * bar, and the tint is checked against the custom property it claims to be so that the strips
     * cannot quietly stop matching the page around them.
     */
    @Test
    fun `every naming band is the page's own tint, and legible on it`() {
        val page = file("index.html").readText()
        val palette = Regex("""--([a-z-]+):\s*#([0-9a-fA-F]{6})\s*;""").findAll(page)
            .associate { it.groupValues[1] to (0xFF000000.toInt() or it.groupValues[2].toInt(16)) }

        SiteImagery.BandTint.entries.forEach { tint ->
            fun fromPage(name: String) = palette[name]
                ?: fail("a band is tinted --$name, and the page no longer defines that")
            assertEquals(
                fromPage(tint.groundName), tint.ground,
                "${tint.name}'s ground is not the --${tint.groundName} the page sets"
            )
            assertEquals(
                fromPage(tint.inkName), tint.ink,
                "${tint.name}'s lettering is not the --${tint.inkName} the page sets"
            )
            val ratio = ColorVision.contrast(tint.ink, tint.ground)
            assertTrue(
                ratio >= AA,
                "a band's --${tint.inkName} on --${tint.groundName} measures " +
                    "${"%.2f".format(ratio)}:1, under the $AA:1 the rest of the page keeps"
            )
            println(
                "SITE band ${tint.name}: --${tint.inkName} on --${tint.groundName} " +
                    "${"%.2f".format(ratio)}:1"
            )
        }
    }

    /**
     * That the Features list still counts the map styles the application offers.
     *
     * "Twelve for the map" is the same kind of sentence as the Themes row below it, and goes stale
     * the same silent way. It matters more now that the page shows three of the twelve in a figure
     * of their own: a reader who counts three and is told twelve should be told the truth.
     */
    @Test
    fun `the Features list counts the map styles the application offers`() {
        val page = file("index.html").readText()
        val sentence = Regex("""<dt>Styles</dt><dd>([^<]*)</dd>""").find(page)?.groupValues?.get(1)
            ?: fail("the Features list no longer has a Styles row")
        val counted = NUMBER_WORDS.indexOf(sentence.substringBefore(' '))
        assertEquals(
            MapStyle.entries.size,
            counted,
            "the page opens the Styles row with \"${sentence.substringBefore(' ')}\" and the " +
                "application offers ${MapStyle.entries.size} map styles"
        )
        println("SITE the Features list counts $counted map styles")
    }

    /**
     * That the Features list still counts the chromes the application actually offers.
     *
     * "Seventeen for the window … from Nautical to Blacklight" is a sentence that goes stale the
     * instant a chrome is added, and nothing else on the page or in the build would notice: the
     * page would go on deploying, correct in every other respect, quietly one short. So the count
     * is read back out of the page in words and compared with the enum, and so is the name the run
     * of styled chromes ends at, which is the newest one and the one a reader is most likely to
     * have come looking for.
     */
    @Test
    fun `the Features list counts the chromes the application offers`() {
        val page = file("index.html").readText()
        val sentence = Regex("""<dt>Themes</dt><dd>([^<]*)</dd>""").find(page)?.groupValues?.get(1)
            ?: fail("the Features list no longer has a Themes row")
        val counted = NUMBER_WORDS.indexOf(sentence.substringBefore(' '))
        assertEquals(
            ThemeChoice.entries.size,
            counted,
            "the page opens the Themes row with \"${sentence.substringBefore(' ')}\" and the " +
                "application offers ${ThemeChoice.entries.size} chromes"
        )
        val newest = ThemeChoice.entries.last().label
        assertTrue(
            sentence.contains(newest),
            "the page's run of styled chromes stops short of $newest: \"$sentence\""
        )
        println("SITE the Features list counts $counted chromes, ending at $newest")
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
