package com.cartogenesis.desktop

import com.cartogenesis.cartography.MapStyle
import com.cartogenesis.ui.ThemeChoice
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
}
