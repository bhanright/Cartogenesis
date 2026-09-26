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
     * world", so a 4096 × 2048 *picture* of a 2048 world is not a claim about a 4096 world, and
     * "worlds up to N" is one. The Browser download card is about the browser from end to end
     * without saying so in every clause ("Nothing to install · worlds up to 2048"), so every number
     * in it is held to the ceiling too, except in a clause that names the desktop app.
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
        val world = Regex("""\b(\d{3,5}) × \1\b|\b(\d{3,5}) world\b|\bworlds? (?:\w+ ){0,2}?up to (\d{3,5})\b""")
        val claims = browserSentences.flatMap { sentence ->
            world.findAll(sentence).map { match ->
                val size = match.groupValues.drop(1).first { it.isNotEmpty() }.toInt()
                size to sentence
            }.toList()
        } + browserCardClauses().flatMap { clause ->
            Regex("""\b\d{3,5}\b""").findAll(clause).map { it.value.toInt() to clause }.toList()
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
     * The Browser download card's words as a reader reads them, split into clauses at full stops,
     * semicolons and the middle dots its summary line uses, less every clause that names the
     * desktop app, whose numbers are the desktop's.
     */
    private fun browserCardClauses(): List<String> {
        val card = Regex("""<div class="dl-card browser"[\s\S]*?<a class="dl-all"""").find(page)?.value
            ?: fail("the page has no Browser download card")
        val text = card.replace(Regex("""<[^>]+>"""), " ").replace(Regex("""\s+"""), " ")
        val clauses = text.split(Regex("""[.;·]""")).map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue(clauses.any { it.contains("2048") }, "the Browser card no longer says how large a world it makes")
        return clauses.filterNot { it.contains("desktop", ignoreCase = true) }
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
     * The classes only the head's script sets: `live` (scripts run), `reveal` (the cards may rise),
     * `unfold` (the opening is pinned and moves with the scroll) and `splash` (the title card is up).
     * A rule scoped to one of them does nothing for a reader without scripts.
     */
    private val scriptClasses = listOf("live", "reveal", "unfold", "splash")

    private fun scopedToTheScript(selector: String): Boolean =
        scriptClasses.any { selector.startsWith(".$it ") }

    /**
     * That with scripts off every card is visible.
     *
     * The reveal hides a card until it comes into view, and only a script can bring it back; the
     * living figures hide the frames and the ring that are not on show, and the title card hides
     * the map until it has arrived, which only a script can change. So everything that hides is
     * scoped to one of [scriptClasses], which only the script in the head sets and which the page's
     * own markup never carries: a reader without scripts gets the cards and pictures as they are
     * written, with nothing waiting on a script that will never run.
     */
    @Test
    fun `with scripts off every card is visible`() {
        assertTrue(hidingSelectors.isNotEmpty(), "nothing on the page hides; this checked nothing")
        val unscoped = hidingSelectors.filterNot { scopedToTheScript(it) }
        assertTrue(
            unscoped.isEmpty(),
            "these rules hide something whether or not a script ever runs to show it again: $unscoped"
        )
        val htmlTag = Regex("""<html[^>]*>""").find(page)?.value ?: fail("the page has no <html>")
        assertTrue(scriptClasses.none { htmlTag.contains(it) }, "the markup arms the script's classes itself: $htmlTag")
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
            !scopedToTheScript(selector) &&
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

    /**
     * The properties a transition may name besides opacity and transform: colour, which moves
     * nothing, and visibility, which the tray changes at the end of its slide so that, hidden, it is
     * out of the keyboard's reach too.
     */
    private val stillProperties = setOf("color", "background-color", "border-color", "visibility")

    /**
     * The one animation allowed more than opacity and transform: the title card's letters, struck
     * one by one, change their colour and their glow and nothing else. Neither moves anything.
     */
    private val struckLetters = "key-struck"
    private val struckLetterProperties = setOf("color", "text-shadow")

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
                val allowed = if (frames.groupValues[1] == struckLetters) struckLetterProperties
                else setOf("opacity", "transform")
                assertTrue(
                    properties.all { it in allowed },
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

    /** The page's own script at its foot, the last `<script>` in the page. */
    private val pageScript: String by lazy {
        Regex("""<script>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).findAll(page).last().groupValues[1]
    }

    /** The part of [pageScript] that works the opening, between its heading and the tray's. */
    private val openingScript: String by lazy {
        val from = pageScript.indexOf("---------- the opening")
        val to = pageScript.indexOf("---------- the tray")
        assertTrue(from in 0 until to, "the page's script has no opening block before its tray block")
        pageScript.substring(from, to)
    }

    /**
     * That the page reads the scroll and never steers it.
     *
     * The opening is linked to the scroll, so the page has to listen to it; what the reader must
     * never meet is a page that takes the scroll over. So: nothing on the page prevents a default
     * (a wheel, a touch or a key held back is a scroll taken over), no listener is registered as
     * able to, the style sheet snaps nothing, and the one place the page scrolls for the reader is
     * the opening's cue, when it is pressed. The scroll is listened to in one place, passively, and
     * that listener is taken off again (by the opening's watcher, when the opening leaves the
     * screen), so no scroll anywhere else on the page does any work; and every frame the page asks
     * for is asked for by the opening, whose frames run only while it is on screen.
     */
    /** The part of [pageScript] that works the zoom lens, from its heading to the relief's. */
    private val lensScript: String by lazy { scriptBetween("The lens. The whole world", "---------- the ground in relief") }

    /** The part of [pageScript] that draws the relief, from its heading to the download cards'. */
    private val reliefScript: String by lazy { scriptBetween("---------- the ground in relief", "---------- the download cards") }

    /** The part of [pageScript] that works the data frame, from its heading to the lens's. */
    private val dataFrameScript: String by lazy { scriptBetween("The data frame. Each data card", "The lens. The whole world") }

    private fun scriptBetween(start: String, end: String): String {
        val from = pageScript.indexOf(start)
        val to = pageScript.indexOf(end)
        assertTrue(from in 0 until to, "the page's script has no block from \"$start\" to \"$end\"")
        return pageScript.substring(from, to)
    }

    /**
     * The one default the page may prevent: an arrow key pressed while the zoom lens has the
     * focus, which moves the lens rather than the page. A key pressed on a focused control that
     * the control consumes is the control's, as a range input's arrows are its own; every other key,
     * and every wheel and touch, is left to the page.
     */
    private fun lensArrowsAreTheOnlyDefaultPrevented() {
        assertEquals(1, Regex("""preventDefault""").findAll(page).count(), "the page prevents a default in more than the lens's arrow keys")
        val keys = Regex("""lensFrame\.addEventListener\('keydown', function \(event\) \{(.*?)\n    \}\);""", RegexOption.DOT_MATCHES_ALL)
            .find(lensScript)?.groupValues?.get(1) ?: fail("the lens has no key handler, and the page prevents a default somewhere")
        val prevented = keys.indexOf("event.preventDefault()")
        val onlyArrows = keys.indexOf("if (!move) return;")
        assertTrue(onlyArrows in 0 until prevented && keys.contains("LENS_KEYS[event.key]"),
            "the lens prevents a key's default without first making sure it is one of its arrow keys")
        assertTrue(Regex("""var LENS_KEYS = \{ArrowLeft: \[-1, 0\], ArrowRight: \[1, 0\], ArrowUp: \[0, -1\], ArrowDown: \[0, 1\]\};""").containsMatchIn(lensScript),
            "the lens's keys are not exactly the four arrows")
    }

    @Test
    fun `the page reads the scroll and never steers it`() {
        lensArrowsAreTheOnlyDefaultPrevented()
        assertTrue(!Regex("""passive\s*:\s*false""").containsMatchIn(page), "the page registers a listener that may hold the scroll back")
        assertTrue(!Regex("""scroll-snap""").containsMatchIn(styleSheet), "the style sheet snaps the scroll")
        assertTrue(!Regex("""(^|[\s,}])(html|body)\s*\{[^}]*overflow\s*:\s*hidden""").containsMatchIn(styleSheet), "the style sheet locks the page's scroll")

        val listened = Regex("""addEventListener\(\s*['"]scroll['"]\s*,\s*(\w+)\s*,\s*\{\s*passive\s*:\s*true\s*\}\s*\)""")
            .findAll(page).map { it.groupValues[1] }.toList()
        val everyScrollListener = Regex("""addEventListener\(\s*['"]scroll['"]""").findAll(page).count() +
            Regex("""onscroll""").findAll(page).count()
        assertEquals(1, everyScrollListener, "the page listens to the scroll in $everyScrollListener places, where the opening is the one")
        assertEquals(1, listened.size, "the page's scroll listener is not passive")
        assertTrue(openingScript.contains("addEventListener('scroll', ${listened.single()}"), "the scroll is listened to outside the opening")
        assertTrue(
            Regex("""removeEventListener\(\s*['"]scroll['"]\s*,\s*${listened.single()}\s*\)""").containsMatchIn(openingScript),
            "the opening's scroll listener is never taken off, so it works on the scroll the whole page down"
        )

        val scrolledFor = Regex("""\.(scrollTo|scrollBy|scrollIntoView)\(""").findAll(pageScript).count()
        assertEquals(1, scrolledFor, "the page scrolls for the reader in $scrolledFor places, where the opening's cue is the one")
        val cueHandler = Regex("""cue\.addEventListener\('click', function \(\) \{(.*?)\n  \}\);""", RegexOption.DOT_MATCHES_ALL)
            .find(openingScript)?.groupValues?.get(1) ?: fail("the opening's cue has no click handler")
        assertTrue(Regex("""\.scrollTo\(""").containsMatchIn(cueHandler), "the page scrolls for the reader somewhere other than when the cue is pressed")

        val frames = Regex("""requestAnimationFrame\(""").findAll(pageScript).count()
        val openingFrames = Regex("""requestAnimationFrame\(""").findAll(openingScript).count()
        val reliefFrames = Regex("""requestAnimationFrame\(""").findAll(reliefScript).count()
        assertTrue(frames > 0, "the opening asks for no frames; this checked nothing")
        assertEquals(frames, openingFrames + reliefFrames,
            "something other than the opening and the relief asks for frames, so per-frame work runs off both")
        // The relief's frames are asked for again only while it is on screen and still settling.
        assertTrue(reliefScript.contains("if (reliefInView && (Math.abs(aim.x - tilt.x) > TILT_SETTLED"),
            "the relief asks for its next frame whether or not it is on screen and still moving")
        println("SITE the scroll is read in one passive listener that comes off, scrolled for only by the cue, and $frames frame requests, the opening's and the relief's")
    }

    /**
     * That the title card is only ever up behind its class, once a visit, and never for a reader
     * who asked for less motion.
     *
     * The head decides before anything is painted: the card is armed only inside the branch that
     * has already asked for motion, only when the visit has not seen it (read and written in the
     * browser's session storage, inside a `try`, because a browser that refuses storage must not
     * replay it on every page) and not when the reader arrived at a place on the page. Everything
     * the style sheet does to show the card, hide the map behind it or strike its letters is
     * scoped to `splash`, and it is hidden without the class.
     */
    @Test
    fun `the title card plays once a visit, never for less motion, and only behind its class`() {
        val head = page.substringBefore("<body")
        val decides = Regex("""<script>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).findAll(head)
            .map { it.groupValues[1] }.singleOrNull { it.contains("splash") }
            ?: fail("no script in the head arms the title card")
        val motion = decides.indexOf("prefers-reduced-motion: reduce")
        val armed = decides.indexOf("' splash'")
        assertTrue(motion in 0 until armed, "the head arms the title card without first asking whether the reader wants less motion")
        val tried = Regex("""try\s*\{(.*?)\}\s*catch""", RegexOption.DOT_MATCHES_ALL).find(decides)?.groupValues?.get(1)
            ?: fail("the head reads the visit's memory outside a try")
        listOf("sessionStorage.getItem", "sessionStorage.setItem", "location.hash").forEach {
            assertTrue(tried.contains(it), "the head's once-a-visit test has no $it")
        }

        val showing = rules(styleSheet).filter { (_, body) -> Regex("""display\s*:\s*(?!none)""").containsMatchIn(body) }
            .flatMap { (selectors, _) -> selectors }
            .filter { Regex("""\.title-card$""").containsMatchIn(it.split(Regex("""[\s>+~]+""")).last()) }
        val striking = rules(styleSheet).filter { (_, body) -> body.contains("key-struck") }.flatMap { (selectors, _) -> selectors }
        val hidingTheMap = rules(styleSheet).filter { (_, body) -> Regex("""opacity\s*:\s*0(?![.\d])""").containsMatchIn(body) }
            .flatMap { (selectors, _) -> selectors }.filter { it.contains(".opening-map") }
        val cardSelectors = showing + striking + hidingTheMap
        assertTrue(showing.isNotEmpty() && striking.isNotEmpty() && hidingTheMap.isNotEmpty(),
            "the style sheet no longer shows the card, strikes its letters or hides the map behind it; this checked nothing")
        val unscoped = cardSelectors.filterNot { it.startsWith(".splash ") }
        assertTrue(unscoped.isEmpty(), "these show the title card, strike its letters or hide the map outside the card's class: $unscoped")
        assertTrue(Regex("""(^|})\s*\.title-card\s*\{\s*display\s*:\s*none""").containsMatchIn(styleSheet), "the title card is not hidden without its class")

        val card = Regex("""<div class="title-card"[^>]*>""").find(page)?.value ?: fail("the page has no title card")
        assertTrue(card.contains("""aria-hidden="true""""), "the title card is read aloud as well as the heading")
        assertEquals(1, Regex("""<h1[\s>]""").findAll(page).count(), "the page has more or fewer than one h1")
        println("SITE the title card is armed after the motion test, once a visit, and ${cardSelectors.size} rules show it only behind its class")
    }

    /**
     * That with no script the opening is settled and every download card is open, and says so.
     *
     * Settled means in the page's flow: nothing that pins the stage, sizes it to the screen or
     * moves the panel or the map applies without the classes the head's script sets. Open means
     * every card's body is shown, which only a rule scoped to `live` may undo, and every card's
     * head says `aria-expanded="true"` in the markup, naming a body that is on the page.
     */
    @Test
    fun `with no script the opening is settled and every card is open`() {
        val pinning = rules(styleSheet).filter { (selectors, body) ->
            selectors.any { it.contains(".opening") } &&
                Regex("""position\s*:\s*sticky|(?<![-\w])height\s*:[^;}]*vh|transform\s*:|display\s*:\s*contents""").containsMatchIn(body)
        }.flatMap { (selectors, _) -> selectors }.filter { it.contains(".opening") }
        assertTrue(pinning.isNotEmpty(), "nothing pins the opening; this checked nothing")
        val unscoped = pinning.filterNot { scopedToTheScript(it) }
        assertTrue(unscoped.isEmpty(), "these pin or move the opening whether or not a script runs: $unscoped")

        val closing = rules(styleSheet).filter { (_, body) -> Regex("""display\s*:\s*none""").containsMatchIn(body) }
            .flatMap { (selectors, _) -> selectors }.filter { it.contains(".dl-body") }
        assertTrue(closing.isNotEmpty(), "nothing closes a card; this checked nothing")
        assertTrue(closing.all { it.startsWith(".live ") }, "a card is closed whether or not a script runs: $closing")

        val heads = Regex("""<button[^>]*class="dl-head"[^>]*>""").findAll(page).map { it.value }.toList()
        assertEquals(3, heads.size, "the page has ${heads.size} download cards, where it has Windows, Linux and the browser")
        heads.forEach { head ->
            assertTrue(head.contains("""aria-expanded="true""""), "a card's head says it is closed, and without a script it is open: $head")
            val body = Regex("""aria-controls="([^"]+)"""").find(head)?.groupValues?.get(1) ?: fail("$head names no body")
            val bodyTag = Regex("""<div class="dl-body" id="${Regex.escape(body)}"[^>]*>""").find(page)?.value
                ?: fail("$head names $body, which is not a card's body on the page")
            assertTrue(!bodyTag.contains("hidden"), "$bodyTag is hidden in the markup")
        }
        println("SITE with no script: ${pinning.size} opening rules all behind the script's classes, ${heads.size} cards open")
    }

    /**
     * That every download pill works without the release lookup, and that the lookup can upgrade
     * every file a release carries.
     *
     * Each pill names its file in `data-asset`, as `site/downloads.txt` writes it, and links to
     * `/releases/latest`, which the host forwards to the release page, so a pill whose file the
     * lookup cannot find, or a page whose lookup fails, still takes the reader to the file. The
     * pills are the list, both ways: every file a release carries has one, and none names a file it
     * does not. And nothing in the section says a version or a size: those come from the release.
     */
    @Test
    fun `every download pill falls back to the release page and names a file the release carries`() {
        val section = installSection(page)
        val pills = Regex("""<a class="dl-file[^"]*"[^>]*>""").findAll(section).map { it.value }.toList()
        val named = pills.mapNotNull { pill ->
            Regex("""data-asset="([^"]+)"""").find(pill)?.groupValues?.get(1)?.let { unescaped(it) to pill }
        }
        assertTrue(named.isNotEmpty(), "no pill names a release file")
        named.forEach { (_, pill) ->
            assertEquals("/releases/latest", Regex("""href="([^"]+)"""").find(pill)?.groupValues?.get(1),
                "a pill links somewhere other than the release page before the lookup has run: $pill")
        }
        assertEquals(releaseFileNames.sorted(), named.map { it.first }.sorted(),
            "the pills and site/downloads.txt name different files")
        val stated = Regex("""\b\d+(\.\d+)?\s?MB\b|\bv?\d+\.\d+\.\d+\b""").findAll(
            section.replace(Regex("""<!--[\s\S]*?-->"""), "").replace(Regex("""<[^>]+>"""), " ")
        ).map { it.value }.toList()
        assertTrue(stated.isEmpty(), "the section states a version or a size by hand: $stated")
        assertTrue(pageScript.contains("getAttribute('data-asset')"), "the release lookup no longer reads the pills' file names")
        assertEquals(1, Regex("""api\.github\.com""").findAll(page).count(), "the page asks GitHub's API for the release in more than one place")
        println("SITE ${named.size} pills fall back to /releases/latest: " + named.joinToString { it.first })
    }

    /**
     * That what the keyboard can reach is what the eye can see, and that the drift can be stopped.
     *
     * The tray slides out of sight when it is not wanted; translated away, its links would still
     * take the keyboard's focus somewhere nobody can see, so while it is hidden it is invisible to
     * the keyboard (`visibility`) and inert. The drift is continuous motion, so the opening has a
     * control that pauses it, a real button that says whether it is pressed.
     */
    @Test
    fun `the hidden tray is out of the keyboard's reach and the drift can be paused`() {
        val hidden = rules(styleSheet).filter { (selectors, _) -> ".live .tray" in selectors }
        assertTrue(hidden.any { (_, body) -> Regex("""visibility\s*:\s*hidden""").containsMatchIn(body) },
            "the tray, hidden, is only moved away, so the keyboard can still reach it")
        assertTrue(rules(styleSheet).any { (selectors, body) -> ".live .tray.on" in selectors && body.contains("visibility:visible") },
            "the tray is never made visible again")
        assertTrue(pageScript.contains("setAttribute('inert'") && pageScript.contains("removeAttribute('inert')"),
            "the tray is not made inert while it is hidden")
        val pause = Regex("""<button[^>]*id="band-pause"[^>]*>""").find(page)?.value ?: fail("the opening has no control to pause the drift")
        assertTrue(pause.contains("aria-pressed"), "the pause control does not say whether it is pressed")
        println("SITE the hidden tray is invisible to the keyboard and inert; the drift has a pause control")
    }

    /** The data frame's `<figure>`, as the page writes it. */
    private val dataFrame: String by lazy {
        Regex("""<figure class="datamap"[^>]*>.*?</figure>""", RegexOption.DOT_MATCHES_ALL).find(page)?.value
            ?: fail("the page has no data frame")
    }

    /**
     * That every layer of the data frame is switched by a real button that says whether it is
     * pressed, one per data card and one per overlay, and that without a script the cards are as
     * they were.
     *
     * The four data cards become the frame's switches once a script runs: each card's heading's
     * words move into a button that carries `aria-pressed`, which a keyboard reaches and presses
     * like any button. So the markup holds plain headings (a reader without scripts meets no button
     * that does nothing), the script makes the buttons and keeps `aria-pressed` in step, and every
     * data card's picture names a layer the frame has. The two overlays are buttons in the markup,
     * inside the frame, which stays hidden without a script.
     */
    @Test
    fun `the data frame's layers are switched by real buttons that say whether they are pressed`() {
        val layers = Regex("""<img data-layer="([^"]+)"""").findAll(dataFrame).map { it.groupValues[1] }.toList()
        assertTrue(layers.size >= 4, "the data frame has ${layers.size} layers")
        val cardLayers = Regex("""<ol class="cards layers">.*?</ol>""", RegexOption.DOT_MATCHES_ALL).find(page)?.value
            ?.let { cards -> Regex("""src="img/layer-([a-z]+)\.webp"""").findAll(cards).map { it.groupValues[1] }.toList() }
            ?: fail("the page has no data cards")
        assertEquals(4, cardLayers.size, "the page has ${cardLayers.size} data cards")
        assertTrue(layers.containsAll(cardLayers), "a data card names a layer the frame does not have: ${cardLayers - layers.toSet()}")
        val overlays = Regex("""<button type="button" class="control" data-toggles="([^"]+)" aria-pressed="false">""").findAll(dataFrame)
            .map { it.groupValues[1] }.toList()
        assertEquals(layers - cardLayers.toSet(), overlays, "the layers no card switches are not each switched by an overlay button that says it is not pressed")
        assertTrue(Regex("""<figure class="datamap"[^>]*\shidden""").containsMatchIn(page), "the data frame shows without a script")
        assertTrue(!Regex("""<h3>\s*<button""").containsMatchIn(page), "a card's heading is a button in the markup, which does nothing without a script")
        listOf("document.createElement('button')", "control.type = 'button'", "control.setAttribute('aria-pressed', 'false')",
            "control.setAttribute('aria-pressed', on ? 'true' : 'false')").forEach {
            assertTrue(dataFrameScript.contains(it), "the data frame's script no longer does $it")
        }
        println("SITE the data frame has ${layers.size} layers, ${cardLayers.size} switched by the cards and ${overlays.size} by overlay buttons")
    }

    /**
     * That the lens can be worked by pointer, touch and keyboard, and fetches its full-size picture
     * only when it is first used.
     *
     * The full picture is named only in the lens's `data-src` and in a plain link, which is the
     * accessible way to the same picture; it is asked for only from the function that shows the
     * lens. The frame takes the focus once a script runs and the arrow keys move the lens.
     */
    @Test
    fun `the lens is worked by pointer, touch and keys, and fetches its full picture on first use`() {
        val full = Regex("""<img id="lens-picture"[^>]*\sdata-src="(img/[^"]+)"""").find(page)?.groupValues?.get(1)
            ?: fail("the lens names no full-size picture to fetch when used")
        assertTrue(!Regex("""(?<!data-)src="${Regex.escape(full)}"""").containsMatchIn(page), "the page asks for the lens's full picture as it loads")
        assertTrue(Regex("""<a href="${Regex.escape(full)}">[^<]+</a>""").containsMatchIn(page), "there is no plain link to the full picture for a reader who cannot use the lens")
        val asked = Regex("""askForFull\(\)""").findAll(lensScript).count()
        assertEquals(1, asked, "the full picture is asked for from $asked places, where showing the lens is the one")
        assertTrue(lensScript.contains("if (on) { glass.hidden = false; askForFull(); }"), "the full picture is asked for somewhere other than when the lens is shown")
        listOf("'pointermove'", "'pointerdown'", "'pointerup'", "'pointercancel'", "'focus'", "'keydown'", "lensFrame.tabIndex = 0").forEach {
            assertTrue(lensScript.contains(it), "the lens no longer answers $it")
        }
        println("SITE the lens answers pointer, touch and keys and fetches $full on first use")
    }

    /**
     * That the relief is the still picture wherever it cannot be the relief.
     *
     * The still picture is in the markup with its own description, and the canvas over it is
     * hidden in the markup and described by nothing, so a reader without scripts, a browser without
     * WebGL and a reader who asked for less motion all get the picture. The script starts only when
     * motion may be shown; whatever fails on the way (no WebGL, a shader that will not compile, the
     * heights not arriving or not the size the patch needs) leaves the canvas hidden, and so does a
     * lost context; the canvas is shown only once a frame has been drawn into it. And the triangles
     * are kept out of sight by the light: it is worked out per pixel from a normal blended across
     * each triangle and normalised again, never from a triangle's own flat face.
     */
    @Test
    fun `the relief falls back to its still picture and never shows its triangles`() {
        val still = Regex("""<img class="relief-still"[^>]*>""").find(page)?.value ?: fail("the relief has no still picture")
        assertTrue(Regex("""\salt="[^"]+"""").containsMatchIn(still), "the relief's still picture says nothing to a reader who cannot see it")
        val canvas = Regex("""<canvas class="relief-canvas"[^>]*>""").find(page)?.value ?: fail("the relief has no canvas")
        assertTrue(Regex("""\shidden(?=[\s>])""").containsMatchIn(canvas) && canvas.contains("""aria-hidden="true""""),
            "the relief's canvas shows, or is read out, before a script has drawn into it")
        assertTrue(page.indexOf(still) < page.indexOf(canvas), "the still picture is not under the canvas")
        assertTrue(reliefScript.contains("if (reliefCanvas && mayMove &&"), "the relief starts whether or not the reader asked for less motion")
        assertTrue(reliefScript.contains(".catch(giveUp)"), "a failure on the way to the relief does not leave the still picture")
        assertTrue(reliefScript.contains("addEventListener('webglcontextlost', giveUp)"), "a lost context does not leave the still picture")
        assertTrue(reliefScript.contains("if (bitmap.width !== pointsAcross || bitmap.height !== pointsDown) throw"),
            "heights of the wrong size would be drawn")
        val drawn = reliefScript.indexOf("gl.drawElements(")
        val shown = reliefScript.indexOf("reliefCanvas.classList.add('shown')")
        assertTrue(drawn in 0 until shown, "the canvas is shown before anything has been drawn into it")
        assertTrue(Regex("""\.live \.relief-canvas\{opacity:0""").containsMatchIn(page), "the canvas is not kept at no opacity until it is shown")
        assertTrue(reliefScript.contains("dot(normalize(vNormal), light)") && reliefScript.contains("varying vec3 vNormal"),
            "the light is not worked out per pixel from a normal blended across each triangle")
        assertTrue(!Regex("""dFdx|dFdy|\bflat\s+(varying|in|out)\b""").containsMatchIn(reliefScript), "the relief shades a triangle by its own flat face")
        // The caption states the exaggeration the script draws with.
        val exaggeration = Regex("""var RELIEF_EXAGGERATION = (\d+);""").find(reliefScript)?.groupValues?.get(1)
            ?: fail("the relief states no exaggeration")
        val caption = Regex("""<figure class="relief".*?</figure>""", RegexOption.DOT_MATCHES_ALL).find(page)?.value ?: fail("the relief has no figure")
        val said = Regex("""drawn (\w+) times their true scale""").find(caption)?.groupValues?.get(1)
            ?: fail("the relief's caption does not say how much its heights are exaggerated")
        assertEquals(exaggeration, NUMBER_WORDS_BY_TENS[said] ?: said,
            "the caption says the heights are drawn $said times their scale and the script draws them $exaggeration times")
        println("SITE the relief falls back to its still, is shown only once drawn, is lit per pixel, and says its heights are $said times their scale")
    }

    /** The multiples of ten the relief's caption may spell out, by the number they mean. */
    private val NUMBER_WORDS_BY_TENS = mapOf(
        "ten" to "10", "twenty" to "20", "thirty" to "30", "forty" to "40", "fifty" to "50",
        "sixty" to "60", "seventy" to "70", "eighty" to "80", "ninety" to "90", "a hundred" to "100"
    )

    /**
     * That every world in "Every seed is a world" is a link a keyboard reaches, named by what it
     * does, to the application.
     */
    @Test
    fun `every world in the reel is a link the keyboard reaches`() {
        val reel = Regex("""<ul class="reel">(.*?)</ul>""", RegexOption.DOT_MATCHES_ALL).find(page)?.groupValues?.get(1)
            ?: fail("the page has no reel")
        val links = Regex("""<a\s[^>]*>([^<]*)</a>""").findAll(reel).toList()
        assertTrue(links.size >= 4, "the reel has ${links.size} worlds")
        links.forEach { link ->
            val tag = Regex("""<a\s[^>]*>""").find(link.value)!!.value
            assertTrue(Regex("""href="/app/\?seed=\d+#""").containsMatchIn(tag), "a reel link does not open the application at a seed: $tag")
            assertTrue(!tag.contains("tabindex"), "a reel link is taken out of the keyboard's order: $tag")
            assertTrue(Regex("""Open seed \d+ in the app""").matches(link.groupValues[1].trim()), "a reel link does not say what it opens: ${link.value}")
        }
        println("SITE the reel's ${links.size} worlds are each a link the keyboard reaches")
    }
}
