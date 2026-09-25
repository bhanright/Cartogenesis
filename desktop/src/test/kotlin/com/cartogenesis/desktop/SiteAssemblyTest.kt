package com.cartogenesis.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.math.abs

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
         * The fewest colours, at five bits a channel, a map picture can hold: a picture of one flat
         * colour decodes to one after lossy compression, and the pictures as built to between
         * about three hundred (the currents view, whose land is one dark ground) and four and a half
         * thousand.
         */
        const val LEAST_COLOURS_IN_A_MAP = 32

        /**
         * How far, on average in the green channel out of 255, the strip's first stretch may be
         * from the hero halved before the strip taking over would read as a jump. The two are the
         * same window encoded at two scales, so what is left is the encoder's loss at each; a
         * strip cut one stretch along the world measures many times this.
         */
        const val HERO_TAKEOVER_MOST_MEAN_DIFFERENCE = 12.0

        /**
         * The bytes the page fetched as it loaded before Site 5a: the page as assembled (46,168),
         * the two preloaded faces (261,088 and 200,500) and the hero (178,484, the same file
         * then and now). The page's figure is main's page at d7c7e31 with the roadmap drawn in as
         * the assembly draws it today. That page's step pictures were lazy cards lower down,
         * which a headless Chrome saw arrive after the load event, so they are not in it.
         */
        const val LOAD_BYTES_BEFORE_SITE_5A = 686_240L

        /**
         * What Site 5a allows the load to grow by: 128 KiB, where it measured 112,205 bytes. The
         * markup, style and script of its five figures and the five pins the assembly writes in
         * are 24,801 of those, and the six steps' pictures, which the frame brings into the load,
         * the other 87,404. The headroom is for the step pictures, whose size moves whenever the
         * site's pictures are made again. Every other picture Site 5a adds is lazy, is fetched
         * after the load, or is fetched when the reader picks it.
         */
        const val LOAD_BYTES_GROWTH_SITE_5A = 131_072L

        /**
         * The most the hero's strip may weigh: 112 KiB, where it measured 106,218 bytes, 2048 by
         * 400 at [SiteImagery.WEBP_QUALITY]. Fetched once the page has loaded and never by a reader
         * who asked for less motion.
         */
        const val HERO_STRIP_MOST_BYTES = 114_688L
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

    /** How many colours, at five bits a channel, a decoded picture holds. */
    private fun colourCount(pixels: IntArray): Int =
        pixels.mapTo(HashSet()) { argb -> (argb shr 3) and 0x1F1F1F }.size

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

    /** The `<img>` a picture is published by, as the page writes it. */
    private fun imageTag(page: String, file: String): String =
        Regex("""<img\s+src="img/${Regex.escape(file)}"[^>]*>""").find(page)?.value
            ?: fail("the page has no <img> for img/$file")

    private fun attribute(tag: String, name: String): String =
        Regex("""$name="([^"]*)"""").find(tag)?.groupValues?.get(1)
            ?: fail("""$tag has no $name attribute""")

    /** The `<li class="card">` a picture is the picture of, or a failure. */
    private fun cardOf(page: String, file: String): String =
        Regex("""<li class="card">.*?</li>""", RegexOption.DOT_MATCHES_ALL).findAll(page)
            .map { it.value }.firstOrNull { it.contains("\"img/$file\"") }
            ?: fail("img/$file is not the picture of any card on the page")

    @Test
    fun `every picture the page shows was rendered, at the size the page reserves`() {
        // These names and sizes are the contract between SiteImagery.FIGURES and the page's img
        // tags, written out rather than read from either side: renaming or reshaping one side
        // alone deploys a broken picture or a card that jumps as it loads, and nothing else would
        // notice.
        val expected = mapOf(
            "natural.webp" to (1600 to 800),
            // The strip the hero drifts along: the band round the whole world, halved.
            "hero-strip.webp" to (2048 to 400),
            // How a world is made: one picture a step, one window halved, played in one frame.
            "step-plates.webp" to (520 to 280),
            "step-erosion.webp" to (520 to 280),
            "step-seas.webp" to (520 to 280),
            "step-climate.webp" to (520 to 280),
            "step-rivers.webp" to (520 to 280),
            "step-realms.webp" to (520 to 280),
            // Read the land: the map the pins stand on.
            "land.webp" to (1120 to 560),
            // The twelve styles, each the same 600 by 400 window: three are cards, all twelve are
            // the comparison slider's.
            "style-atlas.webp" to (600 to 400),
            "style-vellum.webp" to (600 to 400),
            "style-ink_wash.webp" to (600 to 400),
            "style-nautical.webp" to (600 to 400),
            "style-midnight.webp" to (600 to 400),
            "style-schoolroom.webp" to (600 to 400),
            "style-verdant.webp" to (600 to 400),
            "style-scroll.webp" to (600 to 400),
            "style-pen_and_ink.webp" to (600 to 400),
            "style-mars.webp" to (600 to 400),
            "style-natural.webp" to (600 to 400),
            "style-clear.webp" to (600 to 400),
            // The four data views, each the same 480 by 600 window.
            "layer-temperature.webp" to (480 to 600),
            "layer-currents.webp" to (480 to 600),
            "layer-wind.webp" to (480 to 600),
            "layer-rainfall.webp" to (480 to 600)
        )
        assertEquals(
            expected.mapValues { it.value }.toSortedMap(),
            SiteImagery.FIGURES.associate { it.file to (it.width to it.height) }.toSortedMap(),
            "SiteImagery renders a different set of pictures, or different sizes, from the ones " +
                "this guard and the page were written for"
        )

        val page = file("index.html").readText()
        // Two ways a picture is asked for without an img of its own: the hero's strip, fetched by
        // the script once the page has loaded, and a style the slider fetches when it is picked.
        val strip = attribute(Regex("""<div[^>]*id="hero-plate"[^>]*>""").find(page)?.value
            ?: fail("the hero has no plate to drift in"), "data-strip")
        assertEquals("img/hero-strip.webp", strip, "the hero drifts along a strip that was not rendered")
        val offered = pickerValues(page).map { "style-$it.webp" }.toSet()
        expected.forEach { (name, size) ->
            assertEquals(size, webpDimensions(file("img/$name")), "img/$name is the wrong size")
            if (name == "hero-strip.webp") return@forEach
            if (!Regex("""<img\s+src="img/${Regex.escape(name)}"""").containsMatchIn(page)) {
                assertTrue(name in offered, "img/$name is rendered and nothing on the page asks for it")
                return@forEach
            }
            val tag = imageTag(page, name)
            // Stated in the tag as well as rendered: the page reserves each picture's shape so the
            // page does not reflow as they load, and a reserved shape that is not the picture's
            // shape reflows it twice over.
            assertEquals(
                size,
                attribute(tag, "width").toInt() to attribute(tag, "height").toInt(),
                "the page reserves a different shape for img/$name than the picture that was rendered"
            )
        }

        val published = File(site, "img").listFiles { f -> f.isFile }.orEmpty().map { it.name }
        assertEquals(
            expected.keys.sorted(), published.sorted(),
            "img/ holds something other than the pictures the page shows — a contact sheet left " +
                "by -Pcontact, or a picture the page has stopped asking for"
        )
        println(
            "SITE ${expected.size} pictures, ${published.sumOf { File(site, "img/$it").length() }} " +
                "bytes: " + published.sorted().joinToString { "$it ${File(site, "img/$it").length()}" }
        )
    }

    /**
     * That each row of comparison cards is the same ground read a different way in each card, and
     * that each card is headed by the name of the reading its picture shows.
     *
     * The pictures are separate files now, so nothing in a file says it is the same window as its
     * neighbour's: that is held here, by the figure table sharing one window across the row. And
     * the name a reader is given for a picture is the card's heading rather than lettering in the
     * picture, so the heading is held to what the picture is — the style's or the view's own label,
     * the word the application's toolbar uses for it.
     */
    @Test
    fun `each row of comparison cards reads one window a different way in each card`() {
        val page = file("index.html").readText()
        mapOf(
            "the style cards" to SiteImagery.STYLE_CARDS,
            "the data views" to SiteImagery.LAYER_CARDS
        ).forEach { (row, figures) ->
            assertTrue(figures.size > 1, "$row have fewer than two cards, so compare nothing")
            assertEquals(
                1, figures.map { it.window }.distinct().size,
                "$row are cut from different windows, so they are not the same ground: " +
                    figures.joinToString { "${it.file} ${it.window}" }
            )
            assertEquals(
                figures.size, figures.map { it.view to it.style }.distinct().size,
                "two of $row are the same reading of the same window"
            )
            assertTrue(
                figures.all { it.reduction == 1 },
                "$row are cut at the sheet's own pixels, and one of them is reduced"
            )
            figures.forEach { figure ->
                val heading = Regex("""<h3>([^<]*)</h3>""").find(cardOf(page, figure.file))
                    ?.groupValues?.get(1) ?: fail("the card for img/${figure.file} has no heading")
                assertEquals(
                    figure.readingName, heading,
                    "img/${figure.file} is the ${figure.readingName} reading, and its card is headed \"$heading\""
                )
            }
            println("SITE $row: " + figures.joinToString { it.readingName } + " at ${figures.first().window}")
        }
    }

    /** The `value` of every option the slider's two pickers offer, left picker first. */
    private fun pickerValues(page: String): List<String> {
        val figure = compareFigure(page)
        return Regex("""<option value="([^"]*)"""").findAll(figure).map { it.groupValues[1] }.toList()
    }

    /** The slider's `<figure>`, as the page writes it. */
    private fun compareFigure(page: String): String =
        Regex("""<figure class="compare"[^>]*>.*?</figure>""", RegexOption.DOT_MATCHES_ALL).find(page)?.value
            ?: fail("the page has no comparison slider")

    /**
     * That the frame which plays "How a world is made" shows one ground at one scale: the six
     * steps' pictures, which are the frame's, cut from one window with one reduction, and in the
     * cards' order.
     *
     * The frame crossfades from each picture to the next, so a picture from another window, or the
     * same window at another scale, is the land jumping under the reader between two steps.
     */
    @Test
    fun `the six steps are one window at one scale`() {
        val steps = SiteImagery.STEP_CARDS
        assertEquals(6, steps.size, "How a world is made has ${steps.size} steps to play, not six")
        assertEquals(1, steps.map { it.window }.distinct().size, "the steps are cut from different windows: " +
            steps.joinToString { "${it.file} ${it.window}" })
        assertEquals(1, steps.map { it.reduction }.distinct().size, "the steps are drawn at different scales: " +
            steps.joinToString { "${it.file} 1:${it.reduction}" })
        assertEquals(1, steps.map { it.width to it.height }.distinct().size, "the steps are different sizes")
        val page = file("index.html").readText()
        val order = Regex("""<ol class="cards steps">.*?</ol>""", RegexOption.DOT_MATCHES_ALL).find(page)?.value
            ?.let { cards -> Regex("""src="img/([^"]+)"""").findAll(cards).map { it.groupValues[1] }.toList() }
            ?: fail("the page has no row of step cards")
        assertEquals(steps.map { it.file }, order, "the step cards are not the steps, in order")
        println("SITE the six steps: one window ${steps.first().window} at 1:${steps.first().reduction}")
    }

    /**
     * That the slider compares every style the application offers over one ground, and asks for
     * only the two it shows.
     *
     * The page's weight at load may grow by two pictures at most for the slider, and grows by none:
     * its default pair are the Atlas and Natural cards' own files. The other ten are named only as
     * a picker's option and fetched when picked.
     */
    @Test
    fun `the slider offers all twelve styles on one ground and loads two`() {
        val styles = SiteImagery.STYLE_PICTURES
        assertEquals(
            com.cartogenesis.cartography.MapStyle.entries.toList(), styles.map { it.style },
            "the slider's pictures are not every style the application offers, in its order"
        )
        assertEquals(1, styles.map { it.window }.distinct().size, "the twelve styles are cut from different windows")
        assertTrue(styles.all { it.reduction == 1 }, "a style picture is reduced, so the pens are not the renderer's")
        assertTrue(SiteImagery.STYLE_CARDS.all { it in styles }, "a style card is not one of the slider's pictures")

        val page = file("index.html").readText()
        val figure = compareFigure(page)
        val pickers = Regex("""<select[^>]*>(.*?)</select>""", RegexOption.DOT_MATCHES_ALL).findAll(figure)
            .map { select ->
                Regex("""<option value="([^"]*)"[^>]*>([^<]*)</option>""").findAll(select.groupValues[1])
                    .map { it.groupValues[1] to it.groupValues[2] }.toList()
            }.toList()
        assertEquals(2, pickers.size, "the slider has ${pickers.size} pickers, where it has a left and a right")
        val offered = styles.map { it.file.removePrefix("style-").removeSuffix(".webp") to it.style.label }
        pickers.forEach { assertEquals(offered, it, "a picker does not offer the twelve styles by their own names") }

        val loaded = Regex("""<img\s[^>]*src="([^"]+)"""").findAll(figure).map { it.groupValues[1] }.toList()
        assertEquals(2, loaded.size, "the slider asks for ${loaded.size} pictures as the page loads, not two: $loaded")
        val cards = SiteImagery.STYLE_CARDS.map { "img/${it.file}" }
        println(
            "SITE the slider offers ${styles.size} styles and loads ${loaded.size}: $loaded" +
                ", of which ${loaded.count { it in cards }} are card pictures already on the page"
        )
    }

    /**
     * That the strip the hero drifts along joins itself end to end, and that its first stretch is
     * the hero's own picture, so the strip can take over from it without the land moving.
     *
     * The world wraps east-west and the strip is its whole circumference, so as the track slides
     * the strip's last column is followed by its first: those two have to be neighbours on the
     * ground. Held to how much one column differs from the next anywhere inside the strip, where
     * every pair is neighbours by construction: the seam may differ as much as the roughest of
     * those and no more. On the strip as built the seam measures 24 against a median of 17 and a
     * roughest of 49; a strip stopped 512 pixels short of the circumference measured 164.
     */
    @Test
    fun `the hero's strip joins itself and begins where the hero is`() {
        val figure = SiteImagery.HERO_STRIP
        val pixels = decodedPixels(file("img/${figure.file}"))
        val width = figure.width
        val height = figure.height
        fun columnDifference(left: Int, right: Int): Double {
            var sum = 0L
            for (row in 0 until height) {
                val a = pixels[row * width + left]
                val b = pixels[row * width + right]
                sum += abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) +
                    abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) + abs((a and 0xFF) - (b and 0xFF))
            }
            return sum.toDouble() / height
        }
        val roughest = (0 until width - 1).maxOf { columnDifference(it, it + 1) }
        val seam = columnDifference(width - 1, 0)
        assertTrue(
            seam <= roughest,
            "the strip's last column and its first differ by %.1f a pixel, and no two neighbouring columns inside it by more than %.1f: the strip does not join itself"
                .format(seam, roughest)
        )

        // The first stretch, against the hero halved: the same window at the strip's scale.
        val hero = decodedPixels(file("img/${SiteImagery.HERO.file}"))
        val heroWidth = SiteImagery.HERO.width
        val across = heroWidth / figure.reduction
        var difference = 0L
        for (row in 0 until height) for (column in 0 until across) {
            val a = pixels[row * width + column]
            val b = hero[(row * figure.reduction) * heroWidth + column * figure.reduction]
            difference += abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF))
        }
        val meanGreen = difference.toDouble() / (height * across)
        assertTrue(
            meanGreen < HERO_TAKEOVER_MOST_MEAN_DIFFERENCE,
            "the strip's first $across columns differ from the hero by %.1f in green on average: the strip does not begin where the hero is, and would jump as it takes over"
                .format(meanGreen)
        )
        println("SITE the hero's strip: seam %.1f against %.1f at the roughest inside, first stretch %.1f from the hero".format(seam, roughest, meanGreen))
    }

    /** One pin as the assembled page writes it. */
    private class Pin(val kind: String, val left: Double, val top: Double, val cellX: Int, val cellY: Int, val labelledBy: String)

    /** Every pin on the "Read the land" map, in the page's order. */
    private fun pins(page: String): List<Pin> =
        Regex("""<button[^>]*class="pin"[^>]*>""").findAll(page).map { match ->
            val tag = match.value
            val style = attribute(tag, "style")
            val left = Regex("""left:([\d.]+)%""").find(style)?.groupValues?.get(1)?.toDouble() ?: fail("$tag has no left")
            val top = Regex("""top:([\d.]+)%""").find(style)?.groupValues?.get(1)?.toDouble() ?: fail("$tag has no top")
            val (cellX, cellY) = attribute(tag, "data-cell").split(",").map { it.toInt() }
            Pin(attribute(tag, "data-kind"), left, top, cellX, cellY, attribute(tag, "aria-labelledby"))
        }.toList()

    /** The world the site's pictures were cut from, as `SiteImagery` saved it beside them. */
    private val siteWorld: com.cartogenesis.worldgen.model.WorldMap by lazy {
        val save = File(repoRoot, "web/build/site-imagery/${SiteImagery.WORLD_FILE}")
        assertTrue(save.isFile, "no saved world at ${save.path}: :desktop:renderSiteImagery writes it")
        save.inputStream().buffered(1 shl 16).use { input ->
            val source = object : com.cartogenesis.cartography.SaveSource {
                override suspend fun read(into: ByteArray, offset: Int, length: Int): Int = input.read(into, offset, length)
            }
            kotlinx.coroutines.runBlocking { com.cartogenesis.cartography.WorldCodec.read(source, GzipCompressor).world }
        }
    }

    /**
     * That every pin on "Read the land" stands inside the map, on the cell it names, and that the
     * cell is the kind of place its note says it is.
     *
     * The pins are placed by `SiteLandmarks` from the world's fields, and this reads the same
     * world back from the save written beside the pictures and asks each pin's question again, in
     * code of its own: a delta pin on the mouth of a river large enough to build one, a coastal
     * range on high ground over an ocean plate's margin, and so on. A finder that drifted onto the
     * wrong cell, or a page whose pins were typed by hand, fails here rather than being trusted.
     */
    @Test
    fun `every pin stands on the kind of place its note names`() {
        val page = file("index.html").readText()
        val pins = pins(page)
        val notes = Regex("""<li id="land-([a-z-]+)">""").findAll(page).map { it.groupValues[1] }.toList()
        assertTrue(pins.size in 5..6, "the map has ${pins.size} pins, where it has five or six")
        assertEquals(notes, pins.map { it.kind }, "the pins are not the notes under the map, in their order")
        pins.forEachIndexed { index, pin ->
            assertTrue(page.contains("""id="${pin.labelledBy}""""), "pin ${index + 1} is named by ${pin.labelledBy}, which is not on the page")
            val number = Regex("""data-kind="${pin.kind}"[^>]*>(\d+)</button>""").find(page)?.groupValues?.get(1)
            assertEquals("${index + 1}", number, "the ${pin.kind} pin is numbered $number and is note ${index + 1}")
        }

        val world = siteWorld
        val window = SiteImagery.LAND_WINDOW
        val sheet = com.cartogenesis.cartography.SheetGeometry.of(world)
        pins.forEach { pin ->
            // Inside the picture, with the whole of its square on it.
            assertTrue(pin.left in 2.0..98.0 && pin.top in 4.0..96.0, "the ${pin.kind} pin stands at ${pin.left}%, ${pin.top}%, at the picture's edge or off it")
            // On the cell it names: the cell's centre, as a share of the picture.
            val centreX = ((pin.cellX + 0.5) * sheet.pixelsPerCellAcross - window.x).mod(sheet.widthPixels.toDouble())
            val centreY = (pin.cellY + 0.5) * sheet.pixelsPerCellDown - window.y
            assertEquals(centreX * 100 / window.width, pin.left, 0.001, "the ${pin.kind} pin is not on its cell across")
            assertEquals(centreY * 100 / window.height, pin.top, 0.001, "the ${pin.kind} pin is not on its cell down")
            val cell = pin.cellY * world.width + pin.cellX
            val failure = kindFailure(world, pin.kind, cell)
            assertTrue(failure == null, "the ${pin.kind} pin stands on cell ${pin.cellX},${pin.cellY}, which $failure")
            println("SITE pin ${pin.kind} on cell ${pin.cellX},${pin.cellY} at ${pin.left}%, ${pin.top}%")
        }
    }

    /** Height above the shoreline in metres, or depth below it as a negative number, for any cell. */
    private fun metres(world: com.cartogenesis.worldgen.model.WorldMap, cell: Int): Float {
        val relative = world.sea.relativeElevation.data[cell]
        return if (world.sea.isLand[cell]) world.config.scale.metresAboveShoreline(relative)
        else world.config.scale.metresBelowShoreline(relative)
    }

    /**
     * Why [cell] is not a place of [kind], or null when it is. Each clause is the kind's definition
     * in the world's own terms, written here rather than taken from the finder.
     */
    private fun kindFailure(world: com.cartogenesis.worldgen.model.WorldMap, kind: String, cell: Int): String? {
        val land = world.sea.isLand
        val width = world.width
        val scale = world.config.scale
        val cellWidthKm = scale.cellWidthKm(width)
        val cellHeightKm = scale.cellHeightKm(world.height)
        return when (kind) {
            "coastal-range" -> {
                val tectonics = world.config.tectonics
                val reach = tectonics.andeanWidthCells + tectonics.arcOffsetCells + tectonics.arcWidthCells
                when {
                    !land[cell] -> "is sea"
                    world.plates.nearestBoundaryClass[cell] != com.cartogenesis.worldgen.pipeline.BoundaryClass.ANDEAN_MARGIN.ordinal ->
                        "lies nearest a ${com.cartogenesis.worldgen.pipeline.BoundaryClass.entries.getOrNull(world.plates.nearestBoundaryClass[cell])} boundary, not an ocean plate under a continent"
                    world.plates.boundaryDistance.data[cell] > reach -> "stands ${world.plates.boundaryDistance.data[cell]} cells from the margin, beyond its range and arc"
                    metres(world, cell) < 1_500f -> "stands ${metres(world, cell).toInt()} m high, not a range"
                    else -> null
                }
            }
            "rain-shadow" -> {
                if (!land[cell] || world.rivers.lakes.isLake(cell)) return "is water"
                if (metres(world, cell) > 1_000f) return "stands ${metres(world, cell).toInt()} m high, inside the range rather than behind it"
                // Upwind, a kilometre at a time for 250 km: a crest a kilometre above the cell, and
                // land beyond the crest with three times the cell's rain.
                val eastKm = world.climate.windDirection[cell] * cellWidthKm
                val southKm = world.climate.windMeridional.data[cell] * cellHeightKm
                val speed = kotlin.math.hypot(eastKm, southKm)
                if (speed == 0.0) return "has no wind"
                var crest = metres(world, cell)
                var wettestBeyond = 0f
                for (km in 1..250) {
                    val row = cell / width - Math.round(southKm / speed * km / cellHeightKm).toInt()
                    if (row !in 0 until world.height) break
                    val column = Math.floorMod(cell % width - Math.round(eastKm / speed * km / cellWidthKm).toInt(), width)
                    val upwind = row * width + column
                    if (metres(world, upwind) > crest) { crest = metres(world, upwind); wettestBeyond = 0f }
                    else if (land[upwind]) wettestBeyond = maxOf(wettestBeyond, world.climate.precipitationMm.data[upwind])
                }
                val rain = world.climate.precipitationMm.data[cell]
                when {
                    crest < metres(world, cell) + 1_000f -> "has no crest a kilometre above it within 250 km upwind"
                    wettestBeyond < 3 * rain -> "gets ${rain.toInt()} mm, and the land upwind of the crest at most ${wettestBeyond.toInt()}: no rain shadow"
                    else -> null
                }
            }
            "drowned-valley" -> {
                if (land[cell]) return "is land"
                val across = (60.0 / cellWidthKm).toInt()
                val down = (60.0 / cellHeightKm).toInt()
                var sea = 0
                var all = 0
                for (dy in -down..down) for (dx in -across..across) {
                    if ((dx * cellWidthKm) * (dx * cellWidthKm) + (dy * cellHeightKm) * (dy * cellHeightKm) > 3_600.0) continue
                    val row = cell / width + dy
                    if (row !in 0 until world.height) continue
                    all++
                    if (!land[row * width + Math.floorMod(cell % width + dx, width)]) sea++
                }
                if (sea * 3 >= all) "has $sea of the $all cells within 60 km under the sea, an open coast rather than a narrow arm" else null
            }
            "delta" -> {
                if (!land[cell]) return "is sea"
                val next = world.rivers.flowTarget[cell]
                if (next < 0 || land[next]) return "is not where a river meets the sea"
                // How much land drains through it: walk down the routing from every land cell and
                // count the walks that pass here. Cheap enough over one world, and it needs no
                // order over the cells to be right.
                val drains = IntArray(world.width * world.height)
                for (source in 0 until world.width * world.height) {
                    if (!land[source]) continue
                    var at = source
                    var steps = 0
                    while (at >= 0 && land[at] && steps++ < world.width * 4) {
                        if (at == cell) { drains[cell]++; break }
                        at = world.rivers.flowTarget[at]
                    }
                }
                val least = world.config.erosion.deltaMinCatchment * world.sea.landCellCount
                if (drains[cell] < least) "drains ${drains[cell]} cells, under the $least a river must before it builds a delta" else null
            }
            "shelf" -> when {
                land[cell] -> "is land"
                metres(world, cell) < -world.config.sea.shelfDepthMetres -> "lies ${-metres(world, cell).toInt()} m deep, off the shelf"
                else -> null
            }
            else -> "is of a kind this guard does not know"
        }
    }

    /**
     * What the page fetches as it loads, and a ceiling on it: the page itself, the two faces it
     * preloads, every picture it asks for without `loading="lazy"`, and the six steps' pictures.
     * The steps are lazy, but the frame that plays them stands in the second screenful at every
     * width, inside the distance a browser fetches lazy pictures ahead of the reader, so a headless
     * Chrome measured all six arriving before the load event at 375, 768, 1280 and 1920 wide. The
     * hero's strip is fetched after the page has loaded and is weighed apart, and every other lazy
     * picture comes as the reader nears it.
     *
     * The ceiling is the weight before Site 5a and the growth Site 5a states for it (see
     * [LOAD_BYTES_BEFORE_SITE_5A] and [LOAD_BYTES_GROWTH_SITE_5A]), so a later change that makes
     * the first load heavier has to come here and say by how much.
     */
    @Test
    fun `the page's load stays within its stated weight`() {
        val page = file("index.html")
        val text = page.readText()
        val preloaded = Regex("""<link rel="preload"[^>]*href="([^"]+)"""").findAll(text).map { it.groupValues[1] }.toList()
        val eager = Regex("""<img\s[^>]*>""").findAll(text).map { it.value }
            .filterNot { it.contains("""loading="lazy"""") }
            .map { attribute(it, "src") }.toList()
        val steps = SiteImagery.STEP_CARDS.map { "img/${it.file}" }
        val atLoad = page.length() + (preloaded + eager + steps).distinct().sumOf { file(it).length() }
        val strip = file("img/${SiteImagery.HERO_STRIP.file}").length()
        println(
            "SITE at load: $atLoad bytes (page ${page.length()}, preloaded ${preloaded.joinToString()}, eager ${eager.joinToString()}, the frame's ${steps.joinToString()}); " +
                "the hero's strip after load, $strip bytes"
        )
        assertTrue(
            atLoad <= LOAD_BYTES_BEFORE_SITE_5A + LOAD_BYTES_GROWTH_SITE_5A,
            "the page fetches $atLoad bytes as it loads, more than the $LOAD_BYTES_BEFORE_SITE_5A it " +
                "fetched before Site 5a and the $LOAD_BYTES_GROWTH_SITE_5A Site 5a allowed it"
        )
        assertTrue(
            strip <= HERO_STRIP_MOST_BYTES,
            "the hero's strip is $strip bytes, more than the $HERO_STRIP_MOST_BYTES stated for it"
        )
    }

    @Test
    fun `no picture on the page is blank`() {
        SiteImagery.FIGURES.forEach { figure ->
            val colours = colourCount(decodedPixels(file("img/${figure.file}")))
            assertTrue(
                colours >= LEAST_COLOURS_IN_A_MAP,
                "img/${figure.file} holds $colours colours, fewer than $LEAST_COLOURS_IN_A_MAP: it " +
                    "has come out blank, or as one flat tint with no map in it"
            )
            println("SITE img/${figure.file} holds $colours colours at five bits a channel")
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

        val ceiling = desktop.generationCeiling
        val browserCeiling = when (
            Regex("""override val generationCeiling: Int = WorldCeilings\.(\w+)""").find(web)?.groupValues?.get(1)
        ) {
            "BROWSER_TAB" -> com.cartogenesis.ui.WorldCeilings.BROWSER_TAB
            "DESKTOP" -> com.cartogenesis.ui.WorldCeilings.DESKTOP
            else -> fail("WebPlatform.kt no longer states the browser's ceiling as one of WorldCeilings'")
        }

        val exports = row("Export")
        assertTrue(
            exports.contains("$ceiling × $ceiling"),
            "the Export row does not quote the $ceiling × $ceiling this build can finish: \"$exports\""
        )
        assertTrue(
            Regex("""browser[^.]*$browserCeiling × $browserCeiling""").containsMatchIn(exports),
            "the Export row does not quote the browser's ceiling of $browserCeiling: \"$exports\""
        )
        // And what a world at the ceiling comes out as for a picture: its true-shape sheet, which
        // is not the grid's own size.
        val sheet = com.cartogenesis.cartography.SheetGeometry.of(
            com.cartogenesis.worldgen.model.WorldScale(), ceiling, ceiling
        )
        assertTrue(
            exports.contains("${sheet.widthPixels} × ${sheet.heightPixels}"),
            "the Export row does not say a $ceiling world's map image is " +
                "${sheet.widthPixels} × ${sheet.heightPixels}: \"$exports\""
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
            "SITE the Features list quotes $ceiling as the ceiling, $browserCeiling in the browser, " +
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
