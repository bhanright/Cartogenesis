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
         * How far, on average in the green channel out of 255, the band's first stretch may be
         * from the link preview at the band's scale before the settled plate would show a
         * different window from the author's. The two are the same window encoded apart, so what
         * is left is the encoder's loss at each; a band cut one stretch along the world measures
         * many times this.
         */
        const val PREVIEW_TAKEOVER_MOST_MEAN_DIFFERENCE = 12.0

        /**
         * The most the full band may weigh: 320 KiB, where it measured 288,294 bytes, 4096 by 800 at
         * [SiteImagery.WORLD_BAND_QUALITY]. It is the page's largest request and every wide or
         * dense screen fetches it as the page opens.
         */
        const val WORLD_BAND_MOST_BYTES = 327_680L

        /** The most the half band may weigh: 100 KiB, where it measured 90,848 bytes, 2048 by 400. */
        const val WORLD_BAND_HALF_MOST_BYTES = 102_400L

        /**
         * The least and most of its frame a data layer may cover. The rivers covered 2.0% of the
         * frame and the winds' arrows 7.2%, the currents and the rainfall half each, the sea and
         * the land; a layer cut as the whole reading covers all of it and an empty one none.
         */
        const val LEAST_LAYER_COVER = 0.005
        const val MOST_LAYER_COVER = 0.9

        /**
         * The least the relief's highest point may stand above the sea: a thousand metres, a hill
         * a reader sees rise when the patch tilts. The patch was picked for its range.
         */
        const val LEAST_RELIEF_METRES = 1_000.0

        /**
         * What the page fetches only when a reader asks for it, by file, each with the most it may
         * weigh: the lens's full-size world (507,604 bytes at 4096 by 2048 when it was made, fetched
         * the first time the lens is used), each with 64 KiB for the picture moving when it is made
         * again.
         */
        val FETCHED_WHEN_USED: Map<String, Long> = mapOf(
            "img/world-full.webp" to 507_604L + 65_536L
        )

        /**
         * The ceiling on what the page fetches as it loads, by the kind of screen (see
         * [fetchedAtLoad]): the 1,409,962 and 1,607,408 bytes the list summed to for Site 5b, each
         * plus 64 KiB for the pictures and the page moving when they are made again.
         *
         * Site 6's page summed to 1,373,328 and 1,570,774, so Site 5b costs every screen 36,634
         * bytes more, all of it the page itself (136,906 bytes where it was 100,272): the markup,
         * style and script of the data frame, the lens, the relief and the reel. None of their
         * pictures is fetched as the page loads; a headless Chrome at 375 and 1280 wide, at one and
         * two device pixels, on a first visit and a returning one, fetched the same pictures as for
         * Site 6, the new ones all being lazy and further down the page than the distance a lazy
         * picture is fetched ahead (docs/DESIGN_LEDGER.md, Site 5b). Site 5a's page fetched
         * 1,432,079 by the same count.
         */
        val LOAD_BYTES: Map<String, Long> = mapOf(
            "narrow at one device pixel" to 1_409_962L + 65_536L,
            "wide or dense" to 1_607_408L + 65_536L
        )
    }

    /** A picture decoded through Skia as unpremultiplied ARGB, row after row. */
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
            (bytes[at + 3].toInt() and 0xFF shl 24) or (bytes[at + 2].toInt() and 0xFF shl 16) or
                (bytes[at + 1].toInt() and 0xFF shl 8) or (bytes[at].toInt() and 0xFF)
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
    fun `the description page and its link preview are published`() {
        val index = file("index.html")
        assertTrue(index.length() > 4_000, "index.html is ${index.length()} bytes — that is not the page")
        assertTrue(
            index.readText().contains("""href="/app/""""),
            "the description page no longer points at /app/, so its launch button goes nowhere"
        )

        // The link preview image: a scraper reads this exact file at the address the og:image
        // names. The page no longer draws it; its opening's band begins with the same window.
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
            "the link preview is no longer 1600x800, the 2:1 a link's preview is drawn at"
        )
    }

    /** The `<img>` a picture is published by, as the page writes it. */
    private fun imageTag(page: String, file: String): String =
        Regex("""<img\s[^>]*?(?<![-\w])src="img/${Regex.escape(file)}"[^>]*>""").find(page)?.value
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
            // The link preview, which the page's og:image names.
            "natural.webp" to (1600 to 800),
            // The band the opening drifts, round the whole world, full size and halved.
            "world-band.webp" to (4096 to 800),
            "world-band-half.webp" to (2048 to 400),
            // How a world is made: one picture a step, one window halved, played in one frame.
            "step-plates.webp" to (520 to 280),
            "step-erosion.webp" to (520 to 280),
            "step-seas.webp" to (520 to 280),
            "step-climate.webp" to (520 to 280),
            "step-rivers.webp" to (520 to 280),
            "step-realms.webp" to (520 to 280),
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
            "layer-rainfall.webp" to (480 to 600),
            // The data frame: the relief and the layers laid over it, each the same 800 by 600.
            "data-relief.webp" to (800 to 600),
            "data-temperature.webp" to (800 to 600),
            "data-rainfall.webp" to (800 to 600),
            "data-currents.webp" to (800 to 600),
            "data-wind.webp" to (800 to 600),
            "data-rivers.webp" to (800 to 600),
            // The whole world under the lens, at a quarter and at full size, and the relief's patch.
            "world-whole.webp" to (1024 to 512),
            "world-full.webp" to (4096 to 2048),
            "relief-natural.webp" to (640 to 400),
            // Every seed is a world: five worlds whole, each half its 1024 by 512 sheet.
            "seed-7.webp" to (512 to 256),
            "seed-42.webp" to (512 to 256),
            "seed-1066.webp" to (512 to 256),
            "seed-2024.webp" to (512 to 256),
            "seed-31337.webp" to (512 to 256)
        )
        assertEquals(
            expected.mapValues { it.value }.toSortedMap(),
            SiteImagery.FIGURES.associate { it.file to (it.width to it.height) }.toSortedMap(),
            "SiteImagery renders a different set of pictures, or different sizes, from the ones " +
                "this guard and the page were written for"
        )

        val page = file("index.html").readText()
        // Four ways a picture is asked for without an img of its own shape: the link preview,
        // named by the og:image; the full band, a <picture>'s source over the half band's img; a
        // style the slider fetches when it is picked; and a picture an img names in `data-src`,
        // fetched when it is first wanted (a data layer switched on, the lens first used), whose
        // tag states its shape like any other.
        val offered = pickerValues(page).map { "style-$it.webp" }.toSet()
        val waiting = Regex("""<img\s[^>]*data-src="img/([^"]+)"[^>]*>""").findAll(page).associate { it.groupValues[1] to it.value }
        val bandSources = Regex("""<source[^>]*srcset="img/${Regex.escape(SiteImagery.WORLD_BAND.file)}"""").findAll(page).count()
        assertTrue(bandSources >= 1, "the opening never asks for the full band")
        expected.forEach { (name, size) ->
            assertEquals(size, webpDimensions(file("img/$name")), "img/$name is the wrong size")
            if (name == SiteImagery.HERO.file || name == SiteImagery.WORLD_BAND.file) return@forEach
            if (name == SiteImagery.WORLD_BAND_HALF.file) {
                // Laid at the band's shape, the full band's, which the half band shares.
                val tag = imageTag(page, name)
                assertEquals(
                    SiteImagery.WORLD_BAND.width * size.second, SiteImagery.WORLD_BAND.height * size.first,
                    "the two bands are not the same shape"
                )
                assertEquals(
                    SiteImagery.WORLD_BAND.width to SiteImagery.WORLD_BAND.height,
                    attribute(tag, "width").toInt() to attribute(tag, "height").toInt(),
                    "the page lays the band out at a shape that is not the band's"
                )
                return@forEach
            }
            waiting[name]?.let { tag ->
                assertEquals(
                    size, attribute(tag, "width").toInt() to attribute(tag, "height").toInt(),
                    "the page reserves a different shape for img/$name, fetched when wanted, than the picture that was rendered"
                )
                return@forEach
            }
            if (!Regex("""<img\s[^>]*?(?<![-\w])src="img/${Regex.escape(name)}"""").containsMatchIn(page)) {
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
            (expected.keys + SiteImagery.RELIEF_HEIGHTS_FILE).sorted(), published.sorted(),
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
     * That the band the opening drifts joins itself end to end at both its sizes, that its first
     * stretch is the link preview's window, and that neither size has grown past its stated weight.
     *
     * The world wraps east-west and the band is its whole circumference, laid twice on one track,
     * so as the track slides the band's last column is followed by its first: those two have to be
     * neighbours on the ground. Held to how much one column differs from the next anywhere inside
     * the band, where every pair is neighbours by construction: the seam may differ as much as the
     * roughest of those and no more. The half band's seam measured 24 against a roughest of 49 as
     * the strip of Site 5a; a strip stopped 512 pixels short of the circumference measured 164.
     */
    @Test
    fun `the band joins itself at both sizes and begins where the preview is`() {
        val hero = decodedPixels(file("img/${SiteImagery.HERO.file}"))
        val heroWidth = SiteImagery.HERO.width
        listOf(
            SiteImagery.WORLD_BAND to WORLD_BAND_MOST_BYTES,
            SiteImagery.WORLD_BAND_HALF to WORLD_BAND_HALF_MOST_BYTES
        ).forEach { (figure, mostBytes) ->
            val published = file("img/${figure.file}")
            val pixels = decodedPixels(published)
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
                "${figure.file}'s last column and its first differ by %.1f a pixel, and no two neighbouring columns inside it by more than %.1f: the band does not join itself"
                    .format(seam, roughest)
            )

            // The first stretch, against the link preview at the band's scale.
            val across = heroWidth / figure.reduction
            var difference = 0L
            for (row in 0 until height) for (column in 0 until across) {
                val a = pixels[row * width + column]
                val b = hero[(row * figure.reduction) * heroWidth + column * figure.reduction]
                difference += abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF))
            }
            val meanGreen = difference.toDouble() / (height * across)
            assertTrue(
                meanGreen < PREVIEW_TAKEOVER_MOST_MEAN_DIFFERENCE,
                "${figure.file}'s first $across columns differ from the link preview by %.1f in green on average: the band does not begin at the author's window"
                    .format(meanGreen)
            )
            assertTrue(
                published.length() <= mostBytes,
                "${figure.file} is ${published.length()} bytes, more than the $mostBytes stated for it"
            )
            // The brightest pixel, for the title card's scrim: the contrast guard measures the card's
            // words over pure white, which is the brightest a pixel can be and, here, is.
            val brightest = pixels.maxOf { (it shr 16 and 0xFF) + (it shr 8 and 0xFF) + (it and 0xFF) }
            println(
                "SITE ${figure.file}: seam %.1f against %.1f at the roughest inside, first stretch %.1f from the preview, %d bytes, brightest pixel %d of 765"
                    .format(seam, roughest, meanGreen, published.length(), brightest)
            )
        }
    }

    /**
     * What a headless Chrome fetched as the page loaded, by name, for a screen 375 pixels wide at
     * one device pixel to the CSS pixel and for every other screen it was measured on (375 at two,
     * 1280 at one and at two), on a first visit with the title card and on a returning one alike
     * (see docs/DESIGN_LEDGER.md, Site 6). Every face the style sheet names is fetched, not only
     * the two the head preloads, because the whole page is laid out at once and every face is set
     * somewhere in it; one of the two bands; and the six steps' pictures, which are lazy but stand
     * inside the distance a browser fetches lazy pictures ahead of the reader.
     */
    private val fetchedAtLoad: Map<String, List<String>> by lazy {
        val always = listOf(
            "fonts/spectral_regular.ttf", "fonts/spectral_semibold.ttf", "fonts/plex_sans_regular.ttf",
            "fonts/plex_sans_medium.ttf", "fonts/plex_mono_regular.ttf"
        ) + SiteImagery.STEP_CARDS.map { "img/${it.file}" }
        mapOf(
            "narrow at one device pixel" to always + "img/${SiteImagery.WORLD_BAND_HALF.file}",
            "wide or dense" to always + "img/${SiteImagery.WORLD_BAND.file}"
        )
    }

    /**
     * What the page fetches as it loads, and a ceiling on it, restated for Site 5b.
     *
     * Site 5a's guard counted the page, the two preloaded faces, the eager pictures and the six
     * steps, and so missed the three faces the style sheet asks for, which a headless Chrome
     * fetched at load every time, and anything a style sheet or a `<picture>`'s source asks for. So
     * the list here is what was measured, by name ([fetchedAtLoad]), and this checks it both ways:
     * everything the page asks for without waiting (a preload, a face, an eager picture or a
     * `<picture>`'s source) is on the list, and the list, summed off the published files, stays
     * under a stated ceiling for each kind of screen.
     */
    @Test
    fun `the page's load stays within its stated weight`() {
        val page = file("index.html")
        val text = page.readText()
        val asked = (Regex("""<link rel="preload"[^>]*href="([^"]+)"""").findAll(text).map { it.groupValues[1] } +
            Regex("""url\("(fonts/[^"]+)"\)""").findAll(text).map { it.groupValues[1] } +
            Regex("""<img\s[^>]*>""").findAll(text).map { it.value }.filterNot { it.contains("""loading="lazy"""") }
                .filter { Regex("""(?<![-\w])src=""").containsMatchIn(it) }.map { attribute(it, "src") } +
            Regex("""<source[^>]*srcset="([^"]+)"""").findAll(text).map { it.groupValues[1] }).toSet()
        val listed = fetchedAtLoad.values.flatten().toSet()
        assertTrue((asked - listed).isEmpty(), "the page asks for ${asked - listed} as it loads, which the stated load does not count")
        fetchedAtLoad.forEach { (screen, files) ->
            val atLoad = page.length() + files.sumOf { file(it).length() }
            val ceiling = LOAD_BYTES.getValue(screen)
            println("SITE at load, $screen: $atLoad bytes (page ${page.length()}, " + files.joinToString { "$it ${file(it).length()}" } + ")")
            assertTrue(
                atLoad <= ceiling,
                "the page fetches $atLoad bytes as it loads on a $screen screen, more than the $ceiling stated for it"
            )
        }
    }

    /**
     * That the data frame lays each layer over one ground: every picture cut from one window that
     * holds the data cards' window, laid on the page in SiteImagery's order with the relief at the
     * bottom and the only one asked for as the page is read, and each layer cut as its reading
     * says: the relief and temperature whole, the rest mostly clear.
     *
     * And that the layers are the application's own views taken apart, not new pictures: the winds'
     * arrows over the relief are the winds view the Winds card shows, and the temperature layer is
     * the Temperature card's picture where the two overlap, each measured over the card's window.
     */
    @Test
    fun `the data frame lays the application's own layers over one ground`() {
        val frame = SiteImagery.DATA_FRAME
        val window = frame.first().window
        assertEquals(1, frame.map { it.window }.distinct().size, "the data frame's pictures are cut from different windows")
        val cards = SiteImagery.LAYERS_WINDOW
        assertTrue(
            cards.x >= window.x && cards.y >= window.y && cards.x + cards.width <= window.x + window.width &&
                cards.y + cards.height <= window.y + window.height,
            "the data frame's window $window does not hold the cards' window $cards, so it is not their ground"
        )
        assertEquals(SiteImagery.Cut.GROUND, frame.first().cut, "the data frame's bottom picture is not the relief's ground")

        val page = file("index.html").readText()
        val figure = Regex("""<figure class="datamap".*?</figure>""", RegexOption.DOT_MATCHES_ALL).find(page)?.value
            ?: fail("the page has no data frame")
        val laid = Regex("""<img\s[^>]*?(?:data-src|(?<![-\w])src)="img/([^"]+)"""").findAll(figure).map { it.groupValues[1] }.toList()
        assertEquals(frame.map { it.file }, laid, "the page lays the data frame's pictures in another order, or other pictures")
        val askedAsRead = Regex("""<img\s[^>]*?(?<![-\w])src="img/([^"]+)"""").findAll(figure).map { it.groupValues[1] }.toList()
        assertEquals(listOf(frame.first().file), askedAsRead, "the data frame asks for more than its relief before a layer is switched on")

        fun clearShare(figure: SiteImagery.Figure): Double {
            val pixels = decodedPixels(file("img/${figure.file}"))
            return pixels.count { it ushr 24 == 0 }.toDouble() / pixels.size
        }
        frame.forEach { layer ->
            val clear = clearShare(layer)
            when (layer.cut) {
                SiteImagery.Cut.WHOLE, SiteImagery.Cut.GROUND ->
                    assertEquals(0.0, clear, "img/${layer.file} is the whole reading and has clear pixels in it")
                SiteImagery.Cut.MARKS, SiteImagery.Cut.WATER ->
                    assertTrue(clear >= 0.8, "img/${layer.file} is marks alone and is clear over only %.3f of the frame".format(clear))
                else -> assertTrue(clear in 0.2..0.8, "img/${layer.file} keeps land or sea and is clear over %.3f of the frame".format(clear))
            }
            println("SITE the data frame's ${layer.file} (${layer.cut}) is clear over %.3f".format(clear))
        }

        // Over the cards' window: the relief with the winds' arrows laid on it is the Winds card,
        // and the temperature layer is the Temperature card, to within the encoder's loss (the card
        // draws the rivers too, which the frame lays as a layer of its own).
        val relief = decodedPixels(file("img/data-relief.webp"))
        val wind = decodedPixels(file("img/data-wind.webp"))
        val over = IntArray(relief.size) { at ->
            val alpha = (wind[at] ushr 24) / 255.0
            fun channel(shift: Int) = Math.round(((wind[at] shr shift) and 0xFF) * alpha + ((relief[at] shr shift) and 0xFF) * (1 - alpha)).toInt()
            (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
        listOf(
            "the relief with the winds laid over it" to over to "layer-wind.webp",
            "the temperature layer" to decodedPixels(file("img/data-temperature.webp")) to "layer-temperature.webp"
        ).forEach { (named, card) ->
            val (what, pixels) = named
            val cardPixels = decodedPixels(file("img/$card"))
            var difference = 0L
            val left = cards.x - window.x
            val top = cards.y - window.y
            for (row in 0 until cards.height) for (column in 0 until cards.width) {
                val a = pixels[(top + row) * window.width + left + column]
                val b = cardPixels[row * cards.width + column]
                difference += abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF))
            }
            val mean = difference.toDouble() / (cards.width * cards.height)
            assertTrue(mean < PREVIEW_TAKEOVER_MOST_MEAN_DIFFERENCE,
                "$what differs from the $card card by %.1f in green on average, so it is not that view taken apart".format(mean))
            println("SITE $what is the $card card to %.2f in green on average".format(mean))
        }
    }

    /**
     * That the lens's full-size picture is fetched only when the lens is used, and weighs no more
     * than stated: the page names it only in the lens's `data-src` and in the plain link a reader
     * who cannot use the lens follows, and never as a picture's `src`, a preload or a source.
     */
    @Test
    fun `what is fetched only when used is never asked for as the page loads, and stays within its weight`() {
        val page = file("index.html").readText()
        FETCHED_WHEN_USED.forEach { (path, mostBytes) ->
            val named = Regex("""(?<![-\w])(src|srcset|href)="${Regex.escape(path)}"""").findAll(page).map { it.value }.toList()
            assertTrue(named.none { it.startsWith("src") || it.startsWith("srcset") },
                "the page asks for $path as it loads: $named")
            assertTrue(Regex("""data-src="${Regex.escape(path)}"""").containsMatchIn(page), "nothing on the page fetches $path when it is wanted")
            assertTrue(Regex("""<a href="${Regex.escape(path)}">""").containsMatchIn(page), "there is no plain link to $path")
            assertTrue(fetchedAtLoad.values.none { path in it }, "$path is counted in what the page fetches as it loads")
            val bytes = file(path).length()
            assertTrue(bytes <= mostBytes, "$path is $bytes bytes, more than the $mostBytes stated for it")
            println("SITE fetched only when used: $path, $bytes bytes against $mostBytes")
        }
    }

    /**
     * That the relief's heights are the patch's, and that the page reads them as they were written.
     *
     * The heights are a PNG of one point every few sheet pixels of the patch, both edges included,
     * so their size follows from the texture's and the spacing; the page's canvas states the
     * spacing, the steps per metre and the sheet's kilometres per pixel, which must be the ones
     * they were written with, or the page would draw a relief of the wrong height or shape. The mesh
     * has to fit a 16-bit index. And the heights are ground: none below the sea, which is drawn flat
     * at nought, and some well above it.
     */
    @Test
    fun `the relief's heights are the patch's and the page reads them at their own scale`() {
        val texture = SiteImagery.RELIEF_TEXTURE
        val heights = file("img/${SiteImagery.RELIEF_HEIGHTS_FILE}")
        val image = org.jetbrains.skia.Image.makeFromEncoded(heights.readBytes())
        val across = SiteImagery.RELIEF_POINTS_ACROSS
        val down = SiteImagery.RELIEF_POINTS_DOWN
        assertEquals(across to down, image.width to image.height, "the heights are not one point every ${SiteImagery.RELIEF_POINT_SPACING_PIXELS} pixels of the patch")
        assertEquals(texture.width, (across - 1) * SiteImagery.RELIEF_POINT_SPACING_PIXELS, "the heights do not span the patch's picture across")
        assertEquals(texture.height, (down - 1) * SiteImagery.RELIEF_POINT_SPACING_PIXELS, "the heights do not span the patch's picture down")
        assertTrue(across * down <= 65_536, "the relief's mesh has ${across * down} points, more than a 16-bit index reaches")
        image.close()
        val pixels = decodedPixels(heights)
        val metres = pixels.map { (((it shr 16) and 0xFF) * 256 + ((it shr 8) and 0xFF)).toDouble() / SiteImagery.RELIEF_STEPS_PER_METRE }
        assertTrue(pixels.all { it ushr 24 == 0xFF && it and 0xFF == 0 }, "the heights carry something other than sixteen bits in red and green")
        assertEquals(0.0, metres.min(), "the patch's lowest point is not the sea's surface")
        assertTrue(metres.max() > LEAST_RELIEF_METRES, "the patch's highest point is ${metres.max()} m: no relief to draw")

        val page = file("index.html").readText()
        val canvas = Regex("""<canvas class="relief-canvas"[^>]*>""").find(page)?.value ?: fail("the page has no relief canvas")
        assertEquals("img/${SiteImagery.RELIEF_HEIGHTS_FILE}", attribute(canvas, "data-heights"), "the relief reads another file's heights")
        assertEquals(SiteImagery.RELIEF_POINT_SPACING_PIXELS, attribute(canvas, "data-point-spacing").toInt(), "the page spaces the relief's points differently from the heights")
        assertEquals(SiteImagery.RELIEF_STEPS_PER_METRE, attribute(canvas, "data-steps-per-metre").toInt(), "the page reads the heights at another number of steps a metre")
        val sheet = com.cartogenesis.cartography.SheetGeometry.of(SiteImagery.config())
        assertEquals(sheet.kilometresPerPixel, attribute(canvas, "data-kilometres-per-pixel").toDouble(), "the page puts the relief on another scale than the sheet's")
        val still = imageTag(page, texture.file)
        assertEquals(texture.width to texture.height, attribute(canvas, "width").toInt() to attribute(canvas, "height").toInt(),
            "the relief's canvas is not the shape of its still picture")
        assertTrue(still.isNotEmpty())
        println("SITE the relief: ${across}x$down points, highest %.0f m, ${heights.length()} bytes of heights, ${file("img/${texture.file}").length()} of picture, ${sheet.kilometresPerPixel} km a pixel"
            .format(metres.max()))
    }

    /**
     * That every world in "Every seed is a world" links to the world its picture shows: the link
     * the application itself writes for that world, at the size and settings the picture was made
     * with, drawn in the style it was drawn in; captioned with its seed; in the reel's order.
     */
    @Test
    fun `every world in the reel links to the world its picture shows`() {
        val page = file("index.html").readText()
        val reel = Regex("""<ul class="reel">(.*?)</ul>""", RegexOption.DOT_MATCHES_ALL).find(page)?.groupValues?.get(1)
            ?: fail("the page has no reel")
        val items = Regex("""<li>(.*?)</li>""", RegexOption.DOT_MATCHES_ALL).findAll(reel).map { it.groupValues[1] }.toList()
        assertEquals(SiteImagery.REEL.map { it.file }, items.map { item ->
            Regex("""src="img/([^"]+)"""").find(item)?.groupValues?.get(1) ?: fail("a world in the reel has no picture: $item")
        }, "the reel's pictures are not SiteImagery's reel, in order")
        SiteImagery.REEL.zip(items).forEach { (figure, item) ->
            val link = Regex("""<a href="([^"]+)">""").find(item)?.groupValues?.get(1)?.replace("&amp;", "&")
                ?: fail("seed ${figure.seed} has no link")
            val expected = com.cartogenesis.ui.WorldLinks.linkTo(
                "/app/", SiteImagery.reelConfig(figure.seed), figure.options
            )
            assertEquals(expected, link, "seed ${figure.seed}'s link is not the application's own link to the world pictured")
            assertEquals(SiteImagery.REEL_GRID_CELLS, SiteImagery.reelConfig(figure.seed).width, "seed ${figure.seed} is not made at the reel's size")
            assertTrue(item.contains("Seed ${figure.seed}<"), "seed ${figure.seed}'s picture is not captioned with its seed")
            println("SITE reel: ${figure.file} links to $link")
        }
    }

    @Test
    fun `no picture on the page is blank`() {
        SiteImagery.FIGURES.forEach { figure ->
            val pixels = decodedPixels(file("img/${figure.file}"))
            if (figure.cut != SiteImagery.Cut.WHOLE && figure.cut != SiteImagery.Cut.GROUND) {
                // A layer is mostly clear by design, so it is held to having something drawn in it
                // and to leaving something clear, rather than to a count of colours.
                val covered = pixels.count { it ushr 24 > 0 }.toDouble() / pixels.size
                assertTrue(
                    covered in LEAST_LAYER_COVER..MOST_LAYER_COVER,
                    "img/${figure.file} covers %.4f of its frame: a layer that is blank, or one that is not a layer".format(covered)
                )
                println("SITE img/${figure.file} covers %.4f of its frame".format(covered))
                return@forEach
            }
            val colours = colourCount(pixels)
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
            Regex("""browser[^.]*\b$browserCeiling × $browserCeiling\b""").containsMatchIn(exports),
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
     * That the opening's download button names the platforms there are downloads for.
     *
     * It read "Download for Windows (recommended)" for as long as Windows was the only one, and a
     * label like that survives a new platform perfectly happily: the button still works, and it
     * still tells a Linux reader the program is not for them.
     */
    @Test
    fun `the opening's download button names both desktop platforms`() {
        val page = file("index.html").readText()
        val label = Regex("""id="launch-desktop"[^>]*>([^<]*)<""").find(page)?.groupValues?.get(1)
            ?: fail("the opening no longer has a download button")
        listOf("Windows", "Linux").forEach {
            assertTrue(
                label.contains(it),
                "the opening's download button reads \"$label\" and there is a $it download"
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
        println("SITE the opening's download button reads \"$label\"")
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

    /**
     * The files a release carries, from `site/downloads.txt`, with `<version>` where its number goes.
     */
    private val releaseFileNames: List<String> by lazy {
        File(repoRoot, "site/downloads.txt").readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Runs the page's own release lookup, as published, in Node against a release made of every
     * file `site/downloads.txt` lists, and against the ways that release can fail to arrive.
     *
     * The lookup is the part of the page's script between its two markers. It takes what it
     * depends on — the network, the visit's storage, the clock and its time limit — as arguments,
     * so here it is handed stand-ins for each and asked: does every file a release carries match
     * its own pill, and only its own; does a release that lacks a file leave only that pill as it
     * was; do a malformed answer, a refusal (the API's rate limit is a 403), a failed request and a
     * request that never answers all come back as no release, so every pill stays a link to the
     * release page with no size; is a refusal kept for the visit rather than asked again on every
     * page; and is a download that is not on this project's own release page ignored. Node is
     * already on the deploy runner, which is Linux with the Actions image's tools, and on the
     * machines this is built on.
     */
    @Test
    fun `the release lookup finds every file the release carries and falls back on every failure`() {
        val page = file("index.html").readText()
        val lookup = Regex("""/\* release lookup begins \*/(.*?)/\* release lookup ends \*/""", RegexOption.DOT_MATCHES_ALL)
            .find(page)?.groupValues?.get(1) ?: fail("the page's release lookup is no longer marked")
        val work = kotlin.io.path.createTempDirectory("release-lookup").toFile()
        try {
            File(work, "lookup.js").writeText(lookup)
            File(work, "names.json").writeText(releaseFileNames.joinToString(",", "[", "]") { "\"$it\"" })
            File(work, "run.js").writeText(RELEASE_LOOKUP_HARNESS)
            val process = ProcessBuilder("node", "run.js").directory(work).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), "Node did not finish the release lookup's cases")
            assertEquals(0, process.exitValue(), "Node could not run the release lookup: $output")
            val results = output.lines().filter { it.startsWith("CASE ") }
            println(results.joinToString("\n") { "SITE release lookup $it" })
            val failed = results.filter { it.endsWith(" FAIL") || it.contains(" FAIL ") }
            assertTrue(results.size >= 9, "the release lookup's harness reported ${results.size} cases: $output")
            assertTrue(failed.isEmpty(), "the release lookup got these wrong:\n" + failed.joinToString("\n"))
        } finally {
            work.deleteRecursively()
        }
    }

    /**
     * The cases, in Node. Each prints `CASE <name> PASS` or `CASE <name> FAIL <why>`. The release
     * is made from `names.json`, the list the page's pills are held to, so a file added to a
     * release is a file this asks the lookup to find.
     */
    private val RELEASE_LOOKUP_HARNESS = """
const fs = require('fs');
const block = fs.readFileSync('lookup.js', 'utf8');
const names = JSON.parse(fs.readFileSync('names.json', 'utf8'));
const api = new Function(block + '; return {releaseFromAnswer, releaseFileFor, lookUpLatestRelease, sizeInWords};')();
const version = '3.2.0';
const home = 'https://github.com/bhanright/Cartogenesis/releases/download/v' + version + '/';
const asset = (pattern, at) => ({name: pattern.replace('<version>', version), size: 1048576 * (10 + at), browser_download_url: home + pattern.replace('<version>', version)});
const whole = {tag_name: 'v' + version, assets: names.map(asset)};
function keeping() { const kept = new Map(); return {getItem: (k) => kept.has(k) ? kept.get(k) : null, setItem: (k, v) => kept.set(k, String(v))}; }
function answering(status, body, counter) {
  return () => { counter.calls++; return Promise.resolve({ok: status >= 200 && status < 300, status,
    json: () => typeof body === 'string' ? Promise.reject(new SyntaxError('not JSON')) : Promise.resolve(JSON.parse(JSON.stringify(body)))}); };
}
let clock = 0; const now = () => clock;
const say = (name, ok, why) => console.log('CASE ' + name + (ok ? ' PASS' : ' FAIL ' + (why || '')));
(async () => {
  let counter = {calls: 0};
  let release = await api.lookUpLatestRelease(answering(200, whole, counter), keeping(), now, 1000);
  const found = names.map((n) => release && api.releaseFileFor(release, n));
  say('every-file-matches-its-own-pill', found.every((f, at) => f && f.name === names[at].replace('<version>', version) && f.size === whole.assets[at].size),
    JSON.stringify(found));
  say('no-two-pills-share-a-file', new Set(found.map((f) => f && f.name)).size === names.length);
  say('size-in-words', api.sizeInWords(107692851) === '102.7 MB', api.sizeInWords(107692851));

  const lacking = {tag_name: 'v' + version, assets: whole.assets.slice(1)};
  release = await api.lookUpLatestRelease(answering(200, lacking, {calls: 0}), keeping(), now, 1000);
  const partly = names.map((n) => api.releaseFileFor(release, n));
  say('a-missing-file-leaves-only-its-pill', partly[0] === null && partly.slice(1).every(Boolean), JSON.stringify(partly.map(Boolean)));

  release = await api.lookUpLatestRelease(answering(200, 'not json', {calls: 0}), keeping(), now, 1000);
  say('malformed-answer-is-no-release', release === null, JSON.stringify(release));
  release = await api.lookUpLatestRelease(answering(200, {message: 'Not Found'}, {calls: 0}), keeping(), now, 1000);
  say('answer-of-the-wrong-shape-is-no-release', release === null, JSON.stringify(release));

  counter = {calls: 0};
  const store = keeping();
  release = await api.lookUpLatestRelease(answering(403, {message: 'API rate limit exceeded'}, counter), store, now, 1000);
  const again = await api.lookUpLatestRelease(answering(200, whole, counter), store, now, 1000);
  say('a-refusal-is-no-release-and-is-kept-for-the-visit', release === null && again === null && counter.calls === 1, 'calls ' + counter.calls);

  release = await api.lookUpLatestRelease(() => Promise.reject(new TypeError('offline')), keeping(), now, 1000);
  say('a-failed-request-is-no-release', release === null);

  const started = Date.now();
  release = await api.lookUpLatestRelease(() => new Promise(() => {}), keeping(), now, 50);
  say('a-request-that-never-answers-is-given-up', release === null && Date.now() - started < 1000, (Date.now() - started) + ' ms');

  counter = {calls: 0};
  const kept = keeping();
  await api.lookUpLatestRelease(answering(200, whole, counter), kept, now, 1000);
  const second = await api.lookUpLatestRelease(answering(200, whole, counter), kept, now, 1000);
  clock += 16 * 60 * 1000;
  await api.lookUpLatestRelease(answering(200, whole, counter), kept, now, 1000);
  say('an-answer-is-kept-for-a-quarter-hour-and-no-longer', second && counter.calls === 2, 'calls ' + counter.calls);

  const foreign = {tag_name: 'v' + version, assets: [Object.assign(asset(names[0], 0), {browser_download_url: 'https://mirror.invalid/' + names[0]})]};
  release = await api.lookUpLatestRelease(answering(200, foreign, {calls: 0}), keeping(), now, 1000);
  say('a-download-off-this-project-is-ignored', release && api.releaseFileFor(release, names[0]) === null);
})().catch((e) => { console.log('CASE harness FAIL ' + e); });
""".trimIndent()
}
