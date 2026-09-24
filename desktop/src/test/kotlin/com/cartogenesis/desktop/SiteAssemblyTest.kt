package com.cartogenesis.desktop

import com.cartogenesis.cartography.ColorVision
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
         * WCAG 2.1 AA for body text (1.4.3), the bar `SitePaletteContrastTest` holds the rest of
         * the page to. The naming bands are held to it rather than to the large-text bar, because
         * the line under a band's name is small text by any measure.
         */
        const val AA = 4.5

        /**
         * How far a decoded pixel of a hairline may sit from the divider's brightness, in levels
         * of Rec. 601 luma.
         *
         * Brightness and not colour, because the figures are lossy WebP, which keeps brightness at
         * every pixel and colour at every second one: a two-pixel line of the divider's brass
         * between two dark bands decodes as #7C7153, 45 levels bluer than it was drawn, with its
         * brightness within one level. Measured on the assembled strips, both ways round: within 8
         * levels every divider line keeps 0.92 of its length or more, and no other line more than
         * 0.29. A calibration on the figures as built, not a figure of the codec's.
         */
        const val DIVIDER_LUMA_TOLERANCE = 8.0

        /**
         * The share of a line's pixels within [DIVIDER_LUMA_TOLERANCE] of the divider's brightness
         * for the line to be part of a hairline: most of it, which sits between the 0.92 the
         * dividers keep and the 0.29 no line of a map or a band reaches.
         */
        const val DIVIDER_SHARE = 0.5

        /**
         * The fewest colours, at five bits a channel, the map in a panel can hold: a panel of one
         * flat colour decodes to one after lossy compression, and the strips' panels as built to
         * between six hundred and three and a half thousand.
         */
        const val LEAST_COLOURS_IN_A_MAP = 32
    }

    /** A WebP decoded through Skia as ARGB, row after row. */
    private fun decodedPixels(file: File): IntArray {
        val image = org.jetbrains.skia.Image.makeFromEncoded(file.readBytes())
        val bitmap = org.jetbrains.skia.Bitmap()
        bitmap.allocPixels(
            org.jetbrains.skia.ImageInfo.makeS32(image.width, image.height, org.jetbrains.skia.ColorAlphaType.UNPREMUL)
        )
        check(image.readPixels(bitmap)) { "could not read pixels back from ${file.name}" }
        val bytes = bitmap.readPixels() ?: error("no pixels in ${file.name}")
        bitmap.close()
        image.close()
        // Skia's S32 is BGRA in memory on this platform.
        return IntArray(bytes.size / 4) { i ->
            val at = i * 4
            (bytes[at + 2].toInt() and 0xFF shl 16) or (bytes[at + 1].toInt() and 0xFF shl 8) or
                (bytes[at].toInt() and 0xFF)
        }
    }

    /** Rec. 601 luma, the brightness lossy WebP stores at every pixel. */
    private fun luma(argb: Int): Double =
        0.299 * ((argb shr 16) and 0xFF) + 0.587 * ((argb shr 8) and 0xFF) + 0.114 * (argb and 0xFF)

    /**
     * The hairlines across a strip: runs of whole lines — columns for panels laid across, rows for
     * panels laid down — most of whose pixels are as bright as [SiteImagery.DIVIDER_COLOUR], each
     * run one hairline.
     */
    private fun dividerRules(pixels: IntArray, width: Int, height: Int, layout: SiteImagery.Layout): List<IntRange> {
        val across = layout == SiteImagery.Layout.ACROSS
        val lines = if (across) width else height
        val length = if (across) height else width
        val dividerLuma = luma(SiteImagery.DIVIDER_COLOUR)
        fun near(argb: Int): Boolean = kotlin.math.abs(luma(argb) - dividerLuma) <= DIVIDER_LUMA_TOLERANCE
        val isRule = BooleanArray(lines) { line ->
            val matching = (0 until length).count { along ->
                near(if (across) pixels[along * width + line] else pixels[line * width + along])
            }
            matching >= DIVIDER_SHARE * length
        }
        val rules = ArrayList<IntRange>()
        var start = -1
        for (line in 0..lines) {
            val rule = line < lines && isRule[line]
            if (rule && start < 0) start = line
            if (!rule && start >= 0) { rules.add(start until line); start = -1 }
        }
        return rules
    }

    /** How many colours, at five bits a channel, the map part of each panel holds. */
    private fun mapColourCounts(pixels: IntArray, width: Int, figure: SiteImagery.Figure, layout: SiteImagery.Layout): List<Int> =
        figure.panels.indices.map { index ->
            val left = if (layout == SiteImagery.Layout.ACROSS) index * (figure.window.width + SiteImagery.DIVIDER) else 0
            val top = if (layout == SiteImagery.Layout.ACROSS) 0 else index * (figure.panelHeight + SiteImagery.DIVIDER)
            val colours = HashSet<Int>()
            for (row in top until top + figure.window.height) for (column in left until left + figure.window.width) {
                val argb = pixels[row * width + column]
                colours.add((argb shr 3) and 0x1F1F1F)
            }
            colours.size
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
     * The tag a figure is published by, as the page writes it.
     *
     * Either the `<img>` a wide screen loads or the `<source>` a phone is handed instead: both
     * carry the file's name and both state its shape, and which of the two a given figure appears
     * in is the page's business rather than this guard's. Matching the whole tag rather than
     * hunting for the file name is what lets the width, the height and the `alt` be read out of it.
     */
    private fun imageTag(page: String, file: String): String {
        val quoted = Regex.escape(file)
        return Regex("""<img\s+src="img/$quoted"[^>]*>""").find(page)?.value
            ?: Regex("""<source[^>]*srcset="img/$quoted"[^>]*>""").find(page)?.value
            ?: fail("the page has no <img> or <source> for img/$file")
    }

    private fun attribute(tag: String, name: String): String =
        Regex("""$name="([^"]*)"""").find(tag)?.groupValues?.get(1)
            ?: fail("""$tag has no $name attribute""")

    /**
     * The panel names a strip's `alt` text promises, in order.
     *
     * The text ends "Labelled A, B and C." — a shape rather than a word count, so that the list a
     * reader who cannot see the figure is given and the list `SiteImagery` actually letters the
     * bands with can be compared name for name. A panel dropped from either side moves one of the
     * two lists and not the other.
     *
     * The capital is optional because the clause has been both: it was the tail of one sentence
     * ("…drawn three times, labelled Atlas, Schoolroom and Natural.") until the alts were widened
     * to say what each panel *shows*, which is the half of WCAG 1.1.1 a complex image needs and a
     * list of names does not give. It is a sentence of its own now, and either spelling is the same
     * promise.
     */
    private fun namesPromised(alt: String): List<String> {
        val listed = Regex("""[Ll]abelled ([^.]*)""").find(alt)?.groupValues?.get(1).orEmpty()
        assertTrue(
            listed.isNotEmpty(),
            "a comparison strip's alt text has to end \"Labelled A, B and C.\" so that what it " +
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
            "layers.webp" to (1926 to 667),
            // The same panels stacked, which is what a phone is handed: one window wide, and as
            // many finished panels tall as there are readings, with a hairline between each pair.
            "styles-stacked.webp" to (640 to 2137),
            "layers-stacked.webp" to (480 to 2674)
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
     * That every variant of a comparison strip draws exactly the panels the page says it draws.
     *
     * A strip is one image, so a panel that stopped being rendered would not 404 and would not
     * break the layout: the figure would simply arrive one panel short, with the page's prose and
     * its `alt` text still promising three. And now that a phone is handed a second file, a panel
     * could go missing from one variant alone and be invisible to anyone reviewing on the other.
     *
     * So both files are counted, each along its own axis — the wide one is *n* windows plus the
     * hairlines between them, the stacked one is *n* finished panels plus the same hairlines — and
     * both are held to the one list of names in the `alt`, which the two variants share because
     * they are the same figure turned a different way round.
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

            // The window belongs to the figure and not to the panel, so the panels of a strip
            // cannot be showing different ground. What they must not share is the reading: two
            // panels drawing the same view in the same style would be a comparison of nothing.
            assertEquals(
                figure.panels.size,
                figure.panels.map { it.view to it.style }.distinct().size,
                "two panels of img/${figure.file} are the same reading of the same window"
            )

            assertEquals(
                listOf(SiteImagery.Layout.ACROSS, SiteImagery.Layout.DOWN), figure.layouts,
                "a comparison strip is published both ways round, or a phone is left scrolling " +
                    "${figure.file} sideways"
            )

            figure.layouts.forEach { layout ->
                val name = figure.fileFor(layout)
                val drawn = webpDimensions(file("img/$name"))
                assertEquals(
                    figure.width(layout) to figure.height(layout), drawn,
                    "img/$name is not the size ${figure.panels.size} panels of " +
                        "${figure.window.width}x${figure.window.height} come to laid " +
                        layout.name.lowercase()
                )
                // How many panels are in the file, counted off the decoded picture rather than
                // from its size, which the assertion above has already tied to the figure table:
                // the hairlines between panels, found where a whole line of pixels is the
                // divider's colour, and the map inside each panel, which must not be blank.
                val picture = decodedPixels(file("img/$name"))
                val rules = dividerRules(picture, drawn.first, drawn.second, layout)
                assertEquals(
                    figure.panels.size - 1, rules.size,
                    "img/$name has ${rules.size} hairlines across it where ${figure.panels.size} " +
                        "panels have ${figure.panels.size - 1}"
                )
                val blank = mapColourCounts(picture, drawn.first, figure, layout)
                    .withIndex().filter { it.value < LEAST_COLOURS_IN_A_MAP }.map { it.index }
                assertTrue(
                    blank.isEmpty(),
                    "img/$name has blank panels at ${blank.map { figure.panels[it].name }}: " +
                        "fewer than $LEAST_COLOURS_IN_A_MAP colours in the map"
                )
                println(
                    "SITE $name ${drawn.first}x${drawn.second}, ${figure.panels.size} panels of " +
                        "${figure.window.width}x${figure.window.height} laid " +
                        "${layout.name.lowercase()} at ${figure.window.x},${figure.window.y}: " +
                        figure.panels.joinToString { it.name }
                )
            }
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
     * That the sizes the Features list quotes are the sizes the two builds actually allow.
     *
     * Every one of these is a number a reader plans around — how large an export they can ask for,
     * what a phone will do, what a fresh world starts at — and every one of them is a constant in
     * the code that a later chunk can move. The desktop's are read off the platform itself; the
     * browser's cannot be, because that class compiles to wasm and this test is a JVM one, so its
     * source is read instead, exactly as `WebDeploymentContractTest` reads it.
     */
    @Test
    fun `the Features list quotes the sizes the code allows`() {
        val page = file("index.html").readText()
        fun row(term: String): String =
            Regex("""<dt>$term</dt><dd>(.*?)</dd>""").find(page)?.groupValues?.get(1)
                ?: fail("the Features list no longer has a $term row")

        val desktop = DesktopPlatform()
        val web = File(repoRoot, "web/src/wasmJsMain/kotlin/com/cartogenesis/web/WebPlatform.kt")
            .readText()
        fun webNumber(property: String): Int =
            Regex("""$property[^\n]*?(\d+)""").find(web)?.groupValues?.get(1)?.toInt()
                ?: fail("WebPlatform.kt no longer states $property")

        val ceiling = desktop.exportCeiling(compact = false)
        val phoneCeiling = Regex("""exportCeiling\(compact: Boolean\): Int = if \(compact\) (\d+)""")
            .find(web)?.groupValues?.get(1)?.toInt()
            ?: fail("WebPlatform.kt no longer caps a phone's export")

        val exports = row("Export")
        assertTrue(
            exports.contains("$ceiling × $ceiling"),
            "the Export row does not quote the $ceiling × $ceiling this build can finish: \"$exports\""
        )
        assertTrue(
            exports.contains("$phoneCeiling × $phoneCeiling"),
            "the Export row does not quote the phone's cap of $phoneCeiling: \"$exports\""
        )

        val resolutions = row("Resolution")
        assertTrue(
            resolutions.contains("the browser starts at ${webNumber("override val defaultResolution")}"),
            "the Resolution row does not say what the browser starts at: \"$resolutions\""
        )
        assertTrue(
            resolutions.contains("the desktop app at ${desktop.defaultResolution}"),
            "the Resolution row does not say what the desktop starts at: \"$resolutions\""
        )
        println(
            "SITE the Features list quotes $ceiling as the ceiling, $phoneCeiling on a phone, " +
                "and ${webNumber("override val defaultResolution")}/${desktop.defaultResolution} " +
                "as the starting grids"
        )
    }

    /**
     * That both pages say how big the download is, and that they say what it measures.
     *
     * The figure used to be typed into each page by hand and had been left behind by two releases
     * of a growing bundle. It is measured by the assembly now — the loader and the two wasm
     * modules, gzipped, which is what a reader on any host this is served from actually waits for —
     * and the only thing left that can go wrong is the two pages drifting apart, or the
     * measurement drifting from the files. So both are read back and both are recomputed here.
     */
    @Test
    fun `both pages quote the measured size of the download`() {
        fun quoted(path: String): String =
            Regex("""about (\d+\.\d)&nbsp;MB""").find(file(path).readText())?.groupValues?.get(1)
                ?: fail("$path does not say how big the download is")

        val onPage = quoted("index.html")
        assertEquals(
            onPage, quoted("app/index.html"),
            "the landing page and the loading shell quote different download sizes"
        )

        val engine = File(site, "app").listFiles()
            .orEmpty()
            .filter { it.isFile && (it.extension == "wasm" || it.name == "cartogenesis.js") }
        assertTrue(engine.size >= 2, "the published app has no loader and wasm to measure")
        val compressed = engine.sumOf { source ->
            val sink = java.io.ByteArrayOutputStream()
            java.util.zip.GZIPOutputStream(sink).use { it.write(source.readBytes()) }
            sink.size().toLong()
        }
        val tenths = (compressed * 10 + 512 * 1024) / (1024 * 1024)
        val measured = "${tenths / 10}.${tenths % 10}"
        assertEquals(
            measured, onPage,
            "the pages say $onPage MB and the published loader and wasm gzip to $measured MB"
        )
        println(
            "SITE the download is $measured MB compressed (" +
                engine.joinToString { "${it.name} ${it.length() / 1024} KB" } + ")"
        )
    }

    /**
     * One row of `ROADMAP.md`: the release, what it brings, and whether it is the current one.
     *
     * Parsed here rather than shared with the build script, and deliberately: a guard that asked
     * the build for its own answer would pass whatever the build did. These are two readers of one
     * file, which is what makes the comparison below mean anything.
     */
    private class RoadmapRow(val release: String, val brings: String, val current: Boolean)

    private val roadmap: List<RoadmapRow> by lazy {
        val file = File(repoRoot, "ROADMAP.md")
        assertTrue(file.isFile, "ROADMAP.md is missing; the page's roadmap is drawn from it")
        val rows = file.readLines()
            .map { it.trim() }
            .filter { it.startsWith("|") && it.endsWith("|") }
            .map { line -> line.trim('|').split('|').map { it.trim() } }
            .filter { it.size == 2 }
            .filterNot { it[0].equals("Release", ignoreCase = true) }
            .filterNot { cells -> cells.all { it.isNotEmpty() && it.all { char -> char == '-' } } }
            .map { (release, brings) ->
                RoadmapRow(
                    release = release.removeSuffix("(current)").trim(),
                    brings = brings,
                    current = release.contains("(current)")
                )
            }
        assertTrue(rows.isNotEmpty(), "ROADMAP.md has no table rows")
        rows
    }

    /** The `<dl class="spec">` inside the page's `id="next"` section, or a failure. */
    private fun roadmapSection(page: String): String =
        Regex("""<section id="next">(.*?)</section>""", RegexOption.DOT_MATCHES_ALL)
            .find(page)?.groupValues?.get(1)
            ?: fail("the page has no \"What comes next\" section for the roadmap to be drawn in")

    /**
     * That the roadmap on the page is the roadmap in the file, row for row.
     *
     * Both directions, because each of them fails in its own silent way. A release in the file and
     * not on the page is a plan nobody is told about; a release on the page and not in the file is
     * a promise with nothing behind it, which is what a table written by hand into the page turns
     * into the first time the plan moves. The current release is checked separately because it is
     * the one thing on the table a reader reads as a statement of fact about the build they have.
     */
    @Test
    fun `the roadmap on the page is the roadmap in the file`() {
        val section = roadmapSection(file("index.html").readText())
        val drawn = Regex("""<dt>(.*?)</dt><dd>(.*?)</dd>""").findAll(section)
            .map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertTrue(drawn.isNotEmpty(), "the roadmap section has no table in it")

        assertEquals(
            roadmap.map { it.release },
            drawn.map { it.first.substringBefore("<span").trim() },
            "the releases on the page are not the releases ROADMAP.md lists, in its order"
        )
        assertEquals(
            roadmap.map { it.brings },
            drawn.map { it.second },
            "a line on the page is not the line ROADMAP.md gives for that release"
        )

        val current = roadmap.single { it.current }
        val marked = drawn.filter { it.first.contains("""<span class="tag">""") }
        assertEquals(
            1, marked.size,
            "the page marks ${marked.size} releases as the current one: ${marked.map { it.first }}"
        )
        assertTrue(
            marked.single().first.startsWith(current.release),
            "the page marks ${marked.single().first} as current and ROADMAP.md marks " +
                "${current.release}"
        )
        // No date on the table, by the plan: a date is a promise this roadmap does not make.
        assertFalse(
            Regex("""\b(20\d\d|January|February|March|April|May|June|July|August|September|October|November|December)\b""")
                .containsMatchIn(section),
            "the roadmap has grown a date"
        )
        println(
            "SITE roadmap: " + roadmap.joinToString {
                it.release + if (it.current) " (current)" else ""
            }
        )
    }

    /**
     * That the Atlas note names the release ROADMAP.md gives the full atlas.
     *
     * The note said 4.0 until the atlas moved to 5.0, and nothing on the page or in the build would
     * have noticed: it is a number in a sentence. So it is read back out of the sentence and
     * compared with the release whose roadmap line is about the atlas.
     */
    @Test
    fun `the Atlas note names the release the roadmap gives the full atlas`() {
        val page = file("index.html").readText()
        val atlasRelease = roadmap.last { it.brings.contains("atlas", ignoreCase = true) }.release
        val note = Regex("""<h3>The Atlas is a work in progress</h3>\s*<p>([^<]*)</p>""")
            .find(page)?.groupValues?.get(1)
            ?: fail("the Notes no longer carry the Atlas card")
        assertTrue(
            note.contains(atlasRelease),
            "the Atlas note does not name $atlasRelease, which is the release ROADMAP.md gives " +
                "the full atlas: \"$note\""
        )
        println("SITE the Atlas note targets $atlasRelease, as ROADMAP.md does")
    }

    /**
     * That both ways of reporting something are on the page, with both addresses, in the band at
     * the foot that exists to carry them.
     *
     * The issue templates are the route that carries a seed; the two addresses are the route for a
     * reader with no GitHub account, and they are the half that cannot be checked by clicking
     * anything — a mail address with a typo in it fails silently for ever.
     *
     * Read out of `id="report"` rather than out of the whole document, because these two lines
     * were a card in the Notes grid until the page put them where a reader finishes reading. A
     * guard that searched the whole page would have passed either way — including the way where
     * the band is gone and the sentences survive in some other corner.
     */
    @Test
    fun `the band at the foot offers both ways of reporting a bug and asking for a feature`() {
        val page = file("index.html").readText()
        val band = Regex("""<section id="report"[^>]*>(.*?)</section>""", RegexOption.DOT_MATCHES_ALL)
            .find(page)?.groupValues?.get(1)
            ?: fail("the page has no band at the foot asking for bug reports and suggestions")
        listOf(
            "Found something wrong? Report it with the seed and the generation resolution.",
            "Have a feature in mind? Say so.",
            "mailto:bugreport@cartogenesis.com",
            "mailto:dev@cartogenesis.com",
            "template=bug.yml",
            "template=feature.yml"
        ).forEach {
            assertTrue(band.contains(it), "the band at the foot no longer carries \"$it\"")
        }

        // Where it is, which is the whole of why it left the Notes grid: the page's closing word,
        // under the roadmap and above the licence, rather than a fifth fact about the download.
        val at = page.indexOf("""<section id="report"""")
        assertTrue(at > page.indexOf("""<section id="next""""), "the band has moved above the roadmap")
        assertTrue(at < page.indexOf("<footer"), "the band is no longer the last thing on the page")

        // And that the forms the two links ask for are in the repository, since GitHub silently
        // opens a blank issue for a template that is not there.
        listOf("bug.yml", "feature.yml", "config.yml").forEach {
            assertTrue(
                File(repoRoot, ".github/ISSUE_TEMPLATE/$it").isFile,
                ".github/ISSUE_TEMPLATE/$it is missing, and the page links to it"
            )
        }
        println("SITE the band at the foot carries both addresses and both issue forms")
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
                allowed.joinToString() + " belong here: this repository names only its own home."
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

    /**
     * That the download button names the platforms there are downloads for.
     *
     * It read "Download for Windows (recommended)" for as long as Windows was the only one, and a
     * label like that survives a new platform perfectly happily: the button still works, and it
     * still tells a Linux reader the program is not for them.
     */
    @Test
    fun `the hero's download button names both desktop platforms`() {
        val page = file("index.html").readText()
        val label = Regex("""id="launch-desktop"[^>]*>([^<]*)<""").find(page)?.groupValues?.get(1)
            ?: fail("the hero no longer has a download button")
        listOf("Windows", "Linux").forEach {
            assertTrue(
                label.contains(it),
                "the hero's download button reads \"$label\" and there is a $it download"
            )
        }

        // Where the section sits, which is the decision rather than the fact that it exists: after
        // what the program is and what it will not do, and before the roadmap, which answers a
        // different question.
        val at = page.indexOf("""<section id="install">""")
        assertTrue(at > 0, "the page has no Download and Installation section")
        assertTrue(
            at > page.indexOf("""<section id="practical">"""),
            "the Download and Installation section has moved above the Notes"
        )
        assertTrue(
            at < page.indexOf("""<section id="next">"""),
            "the Download and Installation section has moved below the roadmap"
        )
        println("SITE the hero's download button reads \"$label\"")
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
