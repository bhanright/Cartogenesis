package com.cartogenesis.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SiteLocalizationAssemblyTest {
    private val site = File(SiteTranslations.root, "desktop/build/site")
    private fun pages() = SiteTranslations.languages.keys.associateWith { SiteTranslations.page(it).readText() }

    @Test
    fun `all language pages have consistent alternates canonical addresses direction and navigation`() {
        val expected = SiteTranslations.languages.map { (code, tag) -> tag to "https://cartogenesis.com${SiteTranslations.path(code)}" }.toMap() +
            ("x-default" to "https://cartogenesis.com/")
        val sitemap = File(site, "sitemap.xml").readText()
        for ((code, page) in pages()) {
            val url = "https://cartogenesis.com${SiteTranslations.path(code)}"
            assertTrue(page.contains("""<html lang="${SiteTranslations.languages.getValue(code)}" dir="${if (code == "ar") "rtl" else "ltr"}">"""), code)
            assertTrue(page.contains("""<link rel="canonical" href="$url">"""), code)
            assertTrue(page.contains("""<meta property="og:url" content="$url">"""), code)
            assertTrue(page.contains("""property="og:locale""""), code)
            val alternates = Regex("""<link rel="alternate" hreflang="([^"]+)" href="([^"]+)">""").findAll(page).toList()
            assertEquals(10, alternates.size, code)
            assertEquals(expected, alternates.associate { it.groupValues[1] to it.groupValues[2] }, code)
            val nav = Regex("""<nav class="languages"[\s\S]*?</nav>""").find(page)!!.value
            assertEquals(9, Regex("<a ").findAll(nav).count(), code)
            assertEquals(1, Regex("aria-current=").findAll(nav).count(), code)
            for ((other, tag) in SiteTranslations.languages) {
                assertTrue(nav.contains("""href="${SiteTranslations.path(other)}" lang="$tag""""), "$code $other")
            }
            assertTrue(sitemap.contains("<loc>$url</loc>"), code)
            assertFalse(page.contains("data-i18n"), code)
            assertFalse(page.contains("__BUNDLE_MB__"), code)
            assertFalse(page.contains("SITE_MESSAGES"), code)
        }
        for ((tag, url) in expected) assertEquals(9, Regex(Regex.escape("""hreflang="$tag" href="$url"""")).findAll(sitemap).count(), tag)
        assertFalse(File(site, "i18n").exists())
        assertFalse(File(site, "fonts/sources").exists())
    }

    @Test
    fun `every page has localized structured data roadmap rows and translation attribution`() {
        for ((language, page) in pages()) {
            val catalog = SiteTranslations.catalog(language)
            val strings = catalog.getValue("strings").jsonObject
            val structured = Regex("""<script type="application/ld\+json">([\s\S]*?)</script>""").findAll(page).toList()
            assertEquals(1, structured.size, language)
            val data = Json.parseToJsonElement(structured.single().groupValues[1]).jsonObject
            assertEquals("SoftwareApplication", data.getValue("@type").jsonPrimitive.content)
            assertEquals("Cartogenesis", data.getValue("name").jsonPrimitive.content)
            assertEquals(SiteTranslations.languages.getValue(language), data.getValue("inLanguage").jsonPrimitive.content)
            assertEquals("https://cartogenesis.com${SiteTranslations.path(language)}", data.getValue("url").jsonPrimitive.content)
            val description = strings.getValue("structured.description").jsonPrimitive.content
            assertEquals(description, data.getValue("description").jsonPrimitive.content)
            assertTrue(page.substringAfter("<body>").contains(description), language)
            assertEquals("https://github.com/bhanright/Cartogenesis/blob/main/LICENSE", data.getValue("license").jsonObject.getValue("url").jsonPrimitive.content)
            val roadmap = page.substringAfter("id=\"next\"").substringBefore("</section>")
            for ((release, rows) in catalog.getValue("roadmap").jsonObject) {
                assertEquals(rows.jsonArray.size, Regex(Regex.escape(">$release<")).findAll(roadmap).count(), "$language $release")
                for (row in rows.jsonArray) {
                    val escaped = row.jsonPrimitive.content.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    assertTrue(roadmap.contains(escaped), "$language $release")
                }
            }
            assertEquals(language != "en", page.contains("class=\"translation-note\""), language)
            if (language != "en") assertTrue(page.contains(strings.getValue("translation.note").jsonPrimitive.content), language)
        }
    }

    @Test
    fun `all pages keep allowed hosts downloads and executable commands`() {
        val allowed = setOf("cartogenesis.com", "github.com", "api.github.com", "schema.org")
        val english = pages().getValue("en")
        val commands = SiteTranslations.matches("""<code\b[^>]*>[\s\S]*?</code>""", english)
        val downloads = SiteTranslations.matches("""data-asset="[^"]+"""", english)
        assertTrue(downloads.isNotEmpty())
        for ((language, page) in pages()) {
            val hosts = Regex("""https?://([A-Za-z0-9.-]+)""").findAll(page).map { it.groupValues[1] }.toSet()
            assertTrue((hosts - allowed).isEmpty(), "$language ${hosts - allowed}")
            assertEquals(commands, SiteTranslations.matches("""<code\b[^>]*>[\s\S]*?</code>""", page), language)
            assertEquals(downloads, SiteTranslations.matches("""data-asset="[^"]+"""", page), language)
            assertTrue(page.contains("href=\"/app/\""), language)
            assertTrue(page.contains("href=\"/releases/latest\""), language)
            if (language != "en") assertFalse(Regex("""(?:src|href|srcset)="(?:img|fonts)/""").containsMatchIn(page), language)
        }
    }

    @Test
    fun `every page uses the same measured color palette`() {
        val styles = pages().mapValues { (_, page) -> Regex("<style>([\\s\\S]*?)</style>").find(page)!!.groupValues[1].replace("/fonts/", "fonts/") }
        for ((language, style) in styles) assertEquals(styles.getValue("en"), style, language)
        SitePaletteContrastTest().`every text pair on the page meets AA`()
    }

    @Test
    fun `all declared fonts and licenses are published`() {
        for ((language, page) in pages()) {
            val fonts = Regex("""url\("/?fonts/([^"]+\.woff2)"\)""").findAll(page).map { it.groupValues[1] }.toSet()
            assertEquals(9, fonts.size, language)
            for (face in fonts) {
                val file = File(site, "fonts/$face")
                assertTrue(file.isFile, "$language: missing $face; run site/fonts/build_web_fonts.py")
                assertEquals("wOF2", file.readBytes().take(4).map { it.toInt().toChar() }.joinToString(""), face)
            }
        }
        for (script in listOf("sc", "devanagari", "bengali", "arabic")) {
            assertTrue(File(site, "fonts/OFL-notosans$script.txt").isFile, "missing Noto $script license")
        }
    }

    @Test
    fun `localized pages fit narrow and wide screens with and without scripts`() {
        val faults = mutableListOf<String>()
        HeadlessChrome.start(site).use { chrome ->
            for (language in SiteTranslations.languages.keys) for (width in listOf(360, 768, 1440)) for (scripts in listOf(true, false)) {
                chrome.open("${SiteTranslations.path(language).removePrefix("/")}#measure", width, 900, scripts = scripts, reducedMotion = true)
                chrome.evaluate("document.fonts.ready.then(() => true)")
                val probe = """(() => {
                    const faults = [];
                    if (document.documentElement.scrollWidth > innerWidth + 1) faults.push('page overflow');
                    for (const element of document.querySelectorAll('h1,h2,h3,p,.btn,.control-label,.dl-file,.dl-name,.dl-sum,.languages')) {
                        const box = element.getBoundingClientRect();
                        if (!box.width || !box.height || getComputedStyle(element).visibility === 'hidden') continue;
                        if (box.left < -1 || box.right > innerWidth + 1) faults.push(element.className || element.tagName);
                        if (element.scrollWidth > element.clientWidth + 2 && getComputedStyle(element).display !== 'inline') faults.push('text overflow: ' + (element.className || element.tagName));
                    }
                    return faults;
                })()"""
                chrome.evaluate(probe).jsonArray.forEach { faults += "$language $width scripts=$scripts ${it.jsonPrimitive.content}" }
                if (scripts) for (system in listOf("windows", "linux", "browser")) {
                    chrome.evaluate("document.querySelector('.dl-card.$system .dl-head').click()")
                    chrome.evaluate(probe).jsonArray.forEach { faults += "$language $width $system ${it.jsonPrimitive.content}" }
                }
            }
        }
        assertTrue(faults.isEmpty(), faults.joinToString("\n"))
    }
}
