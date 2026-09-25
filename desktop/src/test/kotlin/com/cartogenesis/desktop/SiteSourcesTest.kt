package com.cartogenesis.desktop

import com.cartogenesis.cartography.MapStyle
import com.cartogenesis.ui.ThemeChoice
import com.cartogenesis.ui.WorldCeilings
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What the landing page says that can be checked against the repository without assembling it:
 * the Features list's counts against the application, and the file names and apt source line of
 * the Download and Installation section against `site/downloads.txt`, `docs/INSTALL.md`,
 * `docs/RELEASE_NOTES_TEMPLATE.md` and the apt repository's own configuration.
 *
 * Read from `site/index.html`, which `:web:assembleSite` copies into the site unchanged in these
 * sections, so they are checked on every merge rather than on release day, when a stale sentence
 * could only block the deploy. What needs the assembled tree — the figures, the bundle, the
 * roadmap drawn into the page, the fonts, the loader stamp — stays with `SiteAssemblyTest`.
 */
class SiteSourcesTest {

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

    /** The landing page as it is written, before assembly. */
    private val page: String by lazy { File(repoRoot, "site/index.html").readText() }

    /**
     * That the Features list still counts the map styles the application offers.
     *
     * "Twelve for the map" is the same kind of sentence as the Themes row below it, and goes stale
     * the same silent way. It matters more now that the page shows three of the twelve in a figure
     * of their own: a reader who counts three and is told twelve should be told the truth.
     */
    @Test
    fun `the Features list counts the map styles the application offers`() {
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
     * That no sentence of the page puts a world larger than the browser makes in the browser.
     *
     * The page used to say worlds go "up to 4096 × 4096 in the browser and on the desktop", and a
     * 4096 world kills a browser tab before anything is drawn; the browser now stops at
     * [WorldCeilings.BROWSER_TAB]. A sentence about the browser is any sentence of the page's text
     * that says "browser" or "phone", and a world in one is a square grid, "N × N", or "an N
     * world", so a 4096 × 2048 *picture* of a 2048 world is not a claim about a 4096 world.
     */
    @Test
    fun `the page puts no world larger than the browser makes in the browser`() {
        val text = page
            .replace(Regex("""<style[\s\S]*?</style>|<script[\s\S]*?</script>|<!--[\s\S]*?-->"""), " ")
            .replace(Regex("""<[^>]+>"""), " ")
            .replace("&nbsp;", " ")
            .replace(Regex("""\s+"""), " ")
        val browserSentences = text.split(Regex("""(?<=[.;:])\s""")).filter {
            Regex("""\b(browser|phone)""", RegexOption.IGNORE_CASE).containsMatchIn(it)
        }
        assertTrue(browserSentences.isNotEmpty(), "the page no longer says anything about the browser")
        val world = Regex("""\b(\d{3,5}) × \1\b|\b(\d{3,5}) world\b""")
        val claims = browserSentences.flatMap { sentence ->
            world.findAll(sentence).map { match ->
                val size = (match.groupValues[1].ifEmpty { match.groupValues[2] }).toInt()
                size to sentence
            }.toList()
        }
        val tooLarge = claims.filter { (size, _) -> size > WorldCeilings.BROWSER_TAB }
        assertTrue(
            tooLarge.isEmpty(),
            "the page puts worlds larger than the browser's ${WorldCeilings.BROWSER_TAB} in the browser: " +
                tooLarge.joinToString(" | ") { (size, sentence) -> "$size in \"${sentence.trim()}\"" }
        )
        println("SITE ${claims.size} world sizes in ${browserSentences.size} sentences about the browser, none above ${WorldCeilings.BROWSER_TAB}")
    }

    /**
     * That the Features list still counts the chromes the application actually offers.
     *
     * "Seventeen interface themes" is a sentence that goes stale the instant a chrome is added, and
     * nothing else on the page or in the build would notice: the page would go on deploying,
     * correct in every other respect, quietly one short. So the count is read back out of the page
     * in words and compared with the enum.
     *
     * The sentence used to end "from Nautical to Blacklight" and was also checked against the
     * newest chrome's name. The copy pass of 2026-09-15 took the run of names off — a list of
     * seventeen themes is not what a reader is deciding between here — so there is no name left to
     * check, and the count is the whole of the promise.
     */
    @Test
    fun `the Features list counts the chromes the application offers`() {
        val sentence = Regex("""<dt>Themes</dt><dd>([^<]*)</dd>""").find(page)?.groupValues?.get(1)
            ?: fail("the Features list no longer has a Themes row")
        val counted = NUMBER_WORDS.indexOf(sentence.substringBefore(' '))
        assertEquals(
            ThemeChoice.entries.size,
            counted,
            "the page opens the Themes row with \"${sentence.substringBefore(' ')}\" and the " +
                "application offers ${ThemeChoice.entries.size} chromes"
        )
        println("SITE the Features list counts $counted chromes")
    }

    /**
     * The file names a release carries, with `<version>` where its number goes.
     *
     * One list in `site/downloads.txt`, read by the three documents below rather than copied into
     * each of them. Every one of those three is a reader's first instruction — the page, the
     * installation document and the notes on the release page itself — and a file name is the one
     * thing in them that cannot be nearly right: a download link for a name the release does not
     * carry is a 404 with no explanation on it.
     */
    private val releaseFileNames: Set<String> by lazy {
        val file = File(repoRoot, "site/downloads.txt")
        assertTrue(file.isFile, "site/downloads.txt is missing; it is the list of release files")
        val names = file.readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        assertTrue(names.isNotEmpty(), "site/downloads.txt lists no files")
        names
    }

    /** The `<section id="install">` of the page, or a failure. */
    private fun installSection(page: String): String =
        Regex("""<section id="install">(.*?)</section>""", RegexOption.DOT_MATCHES_ALL)
            .find(page)?.groupValues?.get(1)
            ?: fail("the page has no \"Download and Installation\" section")

    /** HTML as a reader sees it, so a name written `&lt;version&gt;` compares as `<version>`. */
    private fun unescaped(html: String): String =
        html.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    /** Every `Cartogenesis-…` file name in a piece of text, as written. */
    private fun fileNamesIn(text: String): List<String> =
        Regex("""Cartogenesis-[A-Za-z0-9.<>-]*\.(?:zip|msi|deb|tar\.gz)""")
            .findAll(unescaped(text)).map { it.value }.toList()

    /**
     * The apt source line, as a reader would paste it, out of the one command that carries it.
     *
     * Read as the whole line rather than looked for as a substring: what breaks a reader is a
     * source line that differs from the documented one by a character — a codename, a path under
     * `/etc/apt/keyrings`, a stray slash — and a substring search for "cartogenesis.com/apt"
     * would pass on every one of those.
     */
    private fun aptSourceLine(text: String, where: String): String =
        Regex(""""(deb \[signed-by=[^"]*)"""").find(unescaped(text))?.groupValues?.get(1)
            ?: fail("$where no longer carries an apt source line of the form \"deb [signed-by=…] …\"")

    @Test
    fun `the page, the instructions and the notes template name only files a release carries`() {
        val onPage = fileNamesIn(installSection(page))
        assertTrue(onPage.isNotEmpty(), "the Download and Installation section names no files at all")

        val sources = mapOf(
            "the page's Download and Installation section" to onPage,
            "docs/INSTALL.md" to fileNamesIn(File(repoRoot, "docs/INSTALL.md").readText()),
            "docs/RELEASE_NOTES_TEMPLATE.md" to
                fileNamesIn(File(repoRoot, "docs/RELEASE_NOTES_TEMPLATE.md").readText())
        )
        sources.forEach { (where, named) ->
            assertTrue(named.isNotEmpty(), "$where names no release file")
            val strangers = named.toSet() - releaseFileNames
            assertTrue(
                strangers.isEmpty(),
                "$where offers " + strangers.joinToString() + ", which no release carries. " +
                    "site/downloads.txt lists " + releaseFileNames.joinToString()
            )
        }

        // And the other way round: a file added to a release that nothing tells a reader about is
        // a download nobody finds. The page is the one held to it, since it is the page a reader
        // arrives at.
        val unmentioned = releaseFileNames - onPage.toSet()
        assertTrue(
            unmentioned.isEmpty(),
            "a release carries " + unmentioned.joinToString() + " and the page's Download and " +
                "Installation section never names it"
        )
        println("SITE the install section offers " + onPage.joinToString())
    }

    @Test
    fun `the apt source line is the same on the page as in the instructions`() {
        val onPage = aptSourceLine(installSection(page), "the page's Linux card")
        val documented =
            aptSourceLine(File(repoRoot, "docs/INSTALL.md").readText(), "docs/INSTALL.md")
        val inNotes = aptSourceLine(
            File(repoRoot, "docs/RELEASE_NOTES_TEMPLATE.md").readText(),
            "docs/RELEASE_NOTES_TEMPLATE.md"
        )
        assertEquals(documented, onPage, "the page and docs/INSTALL.md give different source lines")
        assertEquals(
            documented, inNotes,
            "docs/RELEASE_NOTES_TEMPLATE.md gives a different source line from docs/INSTALL.md"
        )

        // The two ends of it that the rest of this chunk has to agree with: the key is published
        // where the source line says it is, and the codename is the one reprepro builds.
        assertTrue(
            onPage.contains("https://cartogenesis.com/apt "),
            "the source line no longer points at the repository on this site: \"$onPage\""
        )
        val codename = File(repoRoot, "site/apt/conf/distributions").readLines()
            .firstNotNullOfOrNull { line ->
                line.trim().removePrefix("Codename:").takeIf { it != line.trim() }?.trim()
            } ?: fail("site/apt/conf/distributions declares no Codename")
        assertTrue(
            onPage.trimEnd().endsWith(" $codename main"),
            "the source line asks for a distribution the repository does not build: it ends " +
                "\"${onPage.takeLast(24)}\" and reprepro's codename is \"$codename\""
        )
        println("SITE the apt source line is \"$onPage\"")
    }

    /** Every `<li class="card">` on the page, as written. */
    private val cards: List<String> by lazy {
        Regex("""<li class="card">.*?</li>""", RegexOption.DOT_MATCHES_ALL).findAll(page)
            .map { it.value }.toList()
    }

    /**
     * That every card's picture states its shape and says what it shows.
     *
     * The width and height are what let the browser reserve a card's space before its picture
     * arrives, so the page does not jump as a lazy picture loads under a reader's eye. The `alt` is
     * the picture for a reader who cannot see it, and each card's is its own: a row of cards that
     * all said the same thing would be telling that reader the pictures are the same.
     */
    @Test
    fun `every card picture states its size and has its own description`() {
        assertEquals(
            13, cards.size,
            "the page has ${cards.size} cards where it has six steps, three styles and four views"
        )
        val descriptions = cards.map { card ->
            val images = Regex("""<img\s[^>]*>""").findAll(card).map { it.value }.toList()
            assertEquals(1, images.size, "a card carries ${images.size} pictures: $card")
            val image = images.single()
            fun attribute(name: String): String? =
                Regex("""\s$name="([^"]*)"""").find(image)?.groupValues?.get(1)
            listOf("width", "height").forEach { side ->
                val value = attribute(side)?.toIntOrNull()
                assertTrue(
                    value != null && value > 0,
                    "$image does not state its $side, so its card has no space until it loads"
                )
            }
            val alt = attribute("alt")
            assertTrue(!alt.isNullOrBlank(), "$image says nothing to a reader who cannot see it")
            alt
        }
        val repeated = descriptions.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue(repeated.isEmpty(), "two cards describe their pictures in the same words: $repeated")
        println("SITE ${cards.size} cards, every picture sized and described in its own words")
    }

    /** The page's own style sheet, comments taken out. */
    private val styleSheet: String by lazy {
        val css = Regex("""<style>(.*?)</style>""", RegexOption.DOT_MATCHES_ALL).find(page)
            ?.groupValues?.get(1) ?: fail("the page has no style sheet")
        css.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
    }

    /** Every innermost rule in [css]: its selectors, each trimmed, and its declarations. */
    private fun rules(css: String): List<Pair<List<String>, String>> =
        Regex("""([^{}]+)\{([^{}]*)\}""").findAll(css).map { rule ->
            rule.groupValues[1].split(',').map { it.trim().replace(Regex("""\s+"""), " ") } to
                rule.groupValues[2]
        }.toList()

    /** A declaration that keeps a card or a stretch of its line out of sight. */
    private val hides = Regex("""opacity\s*:\s*0(?![.\d])|scale[XY]?\(\s*0\s*\)""")

    /** Every selector that hides something, anywhere in the style sheet. */
    private val hidingSelectors: List<String> by lazy {
        rules(styleSheet).filter { (_, body) -> hides.containsMatchIn(body) }.flatMap { it.first }
    }

    /**
     * That with scripts off every card is visible.
     *
     * The reveal hides a card until it comes into view, and only a script can bring it back; the
     * living figures hide the frames and the ring that are not on show, which only a script can
     * change. So everything that hides is scoped to the `reveal` or the `live` class, which only
     * the script in the head sets and which the page's own markup never carries: a reader without
     * scripts gets the cards and pictures as they are written, with nothing waiting on a script that
     * will never run.
     */
    @Test
    fun `with scripts off every card is visible`() {
        assertTrue(hidingSelectors.isNotEmpty(), "nothing on the page hides; this checked nothing")
        val unscoped = hidingSelectors.filterNot { it.startsWith(".reveal ") || it.startsWith(".live ") }
        assertTrue(
            unscoped.isEmpty(),
            "these rules hide something whether or not a script ever runs to show it again: $unscoped"
        )
        val htmlTag = Regex("""<html[^>]*>""").find(page)?.value ?: fail("the page has no <html>")
        assertTrue(!htmlTag.contains("reveal") && !htmlTag.contains("live"), "the markup arms the reveal itself: $htmlTag")
        val head = page.substringBefore("<body")
        val armed = Regex("""<script>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).findAll(head)
            .map { it.groupValues[1] }.filter { it.contains("reveal") }.toList()
        assertEquals(
            1, armed.size,
            "the reveal is armed by ${armed.size} scripts in the head, where one has to set it " +
                "before the cards are painted"
        )
        assertTrue(
            armed.single().contains("IntersectionObserver"),
            "the head arms the reveal without asking whether the browser can say when a card is " +
                "in view, so a browser that cannot never shows the cards"
        )
        // And the motion is started by the cards coming into view, never driven by where the page
        // has been scrolled to.
        listOf("addEventListener('scroll'", "addEventListener(\"scroll\"", "onscroll").forEach {
            assertTrue(!page.contains(it), "the page listens to scrolling ($it)")
        }
        println("SITE ${hidingSelectors.distinct().size} hiding rules, all behind the script's class")
    }

    /**
     * That what only a script can work stays out of sight without one.
     *
     * The frame that plays the steps, the style slider and the pins' note carry `hidden` in the
     * markup, and a script takes it off. But a style sheet's own `display` beats the attribute, so
     * a rule giving one of them a display outside the script's classes would show a reader
     * without scripts a frame with nothing in it and controls that do nothing. Checked on every
     * element the markup hides, by each of its classes.
     */
    @Test
    fun `what needs a script stays hidden without one`() {
        val hiddenClasses = Regex("""<[a-z]+\s[^>]*\shidden(?=[\s>])[^>]*>""").findAll(page.substringAfter("<body"))
            .mapNotNull { Regex("""class="([^"]+)"""").find(it.value)?.groupValues?.get(1) }
            .flatMap { it.split(' ') }.toSet()
        assertTrue(hiddenClasses.size >= 3, "the markup hides ${hiddenClasses.size} classes of thing; the frame, the slider and the note are three")
        val shown = rules(styleSheet).filter { (_, body) ->
            Regex("""display\s*:\s*(?!none)""").containsMatchIn(body)
        }.flatMap { it.first }.filter { selector ->
            // The element the rule styles is its selector's last compound, not an ancestor.
            val subject = selector.split(Regex("""[\s>+~]+""")).last()
            !selector.startsWith(".live ") && !selector.startsWith(".reveal ") &&
                hiddenClasses.any { Regex("""\.${Regex.escape(it)}(?![\w-])""").containsMatchIn(subject) }
        }
        assertTrue(shown.isEmpty(), "these give a display to something the markup hides until a script runs: $shown")
        println("SITE ${hiddenClasses.size} classes hidden until a script runs, none shown without one")
    }

    /**
     * That a reader who asks for less motion sees every card at once, still.
     *
     * Twice over: the head does not arm the reveal when the preference is set as the page loads,
     * and the style sheet undoes every hiding rule, with its transition, for a reader whose
     * preference is set after it has — so the block has to name every selector that hides.
     */
    @Test
    fun `a reader who asks for less motion sees every card at once`() {
        val head = page.substringBefore("<body")
        assertTrue(
            Regex("""<script>[^<]*prefers-reduced-motion: reduce[^<]*reveal""").containsMatchIn(head),
            "the head arms the reveal without asking whether the reader wants less motion"
        )
        val block = reducedMotionRules
        val stilled = rules(block).filter { (_, body) ->
            body.contains(Regex("""opacity\s*:\s*1""")) &&
                body.contains(Regex("""transform\s*:\s*none""")) &&
                body.contains(Regex("""transition\s*:\s*none"""))
        }.flatMap { it.first }.toSet()
        val stillMoving = hidingSelectors.filter { it.startsWith(".reveal ") }.toSet() - stilled
        assertTrue(
            stillMoving.isEmpty(),
            "with motion reduced these still hide and then move: $stillMoving"
        )
        println("SITE reduced motion stills ${stilled.size} selectors: $stilled")
    }

    /** Every rule inside the style sheet's `prefers-reduced-motion: reduce` blocks, all of them. */
    private val reducedMotionRules: String by lazy {
        val blocks = Regex(
            """@media[^{]*prefers-reduced-motion:\s*reduce[^{]*\{((?:[^{}]*\{[^{}]*\})*)\s*\}"""
        ).findAll(styleSheet).map { it.groupValues[1] }.toList()
        assertTrue(blocks.isNotEmpty(), "the style sheet has no rule for a reader who prefers reduced motion")
        blocks.joinToString("\n")
    }

    /** The properties a transition may name: the two that move nothing, and colour. */
    private val stillProperties = setOf("color", "background-color", "border-color")

    /**
     * That everything on the page that moves moves by opacity and transform alone, and that a
     * reader who asks for less motion is spared all of it.
     *
     * Opacity and transform are the two properties a browser can change without laying the page
     * out again, so nothing that animates can push anything else about. Colour may fade on a
     * hovered button, which moves nothing. Every rule that moves something, by a transition of
     * either property or by an animation, has its selector stilled in a reduced-motion block:
     * `transition:none` or `animation:none`.
     */
    @Test
    fun `everything that moves moves by opacity and transform, and is stilled for less motion`() {
        val moving = HashMap<String, String>()
        rules(styleSheet.replace(Regex("""@keyframes[^{]*\{(?:[^{}]*\{[^{}]*\})*\s*\}"""), "")).forEach { (selectors, body) ->
            Regex("""transition\s*:\s*([^;}]+)""").find(body)?.groupValues?.get(1)?.let { value ->
                if (value.trim() == "none") return@let
                val properties = value.split(',').map { it.trim().substringBefore(' ') }
                val strangers = properties - stillProperties - setOf("opacity", "transform")
                assertTrue(strangers.isEmpty(), "$selectors transition $strangers, which moves the layout or is not ours to move")
                if (properties.any { it == "opacity" || it == "transform" }) selectors.forEach { moving[it] = "transition" }
            }
            if (Regex("""(^|[;\s])animation\s*:""").containsMatchIn(body) && !body.contains(Regex("""animation\s*:\s*none"""))) {
                selectors.forEach { moving[it] = "animation" }
            }
        }
        val keyframes = Regex("""@keyframes\s+([\w-]+)\s*\{((?:[^{}]*\{[^{}]*\})*)\s*\}""").findAll(styleSheet).toList()
        keyframes.forEach { frames ->
            rules(frames.groupValues[2]).forEach { (_, body) ->
                val properties = body.split(';').map { it.substringBefore(':').trim() }.filter { it.isNotEmpty() }
                assertTrue(
                    properties.all { it == "opacity" || it == "transform" },
                    "@keyframes ${frames.groupValues[1]} animates $properties"
                )
            }
        }
        assertTrue(moving.isNotEmpty(), "nothing on the page moves; this checked nothing")
        val stilled = rules(reducedMotionRules).flatMap { (selectors, body) ->
            selectors.map { it to body }
        }
        val unstilled = moving.filter { (selector, how) ->
            stilled.none { (still, body) -> still == selector && body.contains(Regex("""$how\s*:\s*none""")) }
        }
        assertTrue(unstilled.isEmpty(), "with motion reduced these still move: $unstilled")
        println("SITE ${moving.size} rules move, all by opacity and transform, all stilled for less motion")
    }

    /**
     * That every control on the page says what it is to a reader who cannot see it: a button by its
     * own words or an `aria-label`, an input or a picker by a `label` or an `aria-label`, and
     * anything named by `aria-labelledby` by an element that is on the page.
     *
     * The pins of "Read the land" are put into the page at assembly, so `SiteAssemblyTest` checks
     * those the same way; this reads the page as it is written.
     */
    @Test
    fun `every control has an accessible name`() {
        val labelledFor = Regex("""<label[^>]*for="([^"]+)"""").findAll(page).map { it.groupValues[1] }.toSet()
        val wrapped = Regex("""<label[^>]*>(.*?)</label>""", RegexOption.DOT_MATCHES_ALL).findAll(page)
            .flatMap { label -> Regex("""<(?:input|select)\s[^>]*>""").findAll(label.groupValues[1]).map { it.value } }
            .toSet()
        val controls = Regex("""<(button|input|select)\s[^>]*>(?:(.*?)</\1>)?""", RegexOption.DOT_MATCHES_ALL)
            .findAll(page).toList()
        assertTrue(controls.size >= 5, "the page has ${controls.size} controls; its living figures have more")
        controls.forEach { control ->
            val tag = Regex("""<(button|input|select)\s[^>]*>""").find(control.value)!!.value
            val id = Regex("""\sid="([^"]+)"""").find(tag)?.groupValues?.get(1)
            val labelledBy = Regex("""aria-labelledby="([^"]+)"""").find(tag)?.groupValues?.get(1)
            val named = Regex("""aria-label="[^"]+"""").containsMatchIn(tag) ||
                (labelledBy != null && page.contains("""id="$labelledBy"""")) ||
                (id != null && id in labelledFor) || tag in wrapped ||
                (control.groupValues[1] == "button" && control.groupValues[2].replace(Regex("<[^>]*>"), "").isNotBlank())
            assertTrue(named, "this control has no name a screen reader can say: $tag")
        }
        println("SITE ${controls.size} controls, every one named")
    }
}
