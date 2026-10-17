package com.cartogenesis.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object SiteTranslations {
    val languages = linkedMapOf("en" to "en", "zh" to "zh-Hans", "hi" to "hi", "es" to "es",
        "ar" to "ar", "fr" to "fr", "bn" to "bn", "pt" to "pt", "ru" to "ru")
    val root: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    fun catalog(language: String): JsonObject = Json.parseToJsonElement(
        File(root, "site/i18n/$language.json").readText()).jsonObject
    fun path(language: String) = if (language == "en") "/" else "/$language/"

    /**
     * The characters a language's page can show from its catalog: every string and roadmap row with
     * its markup removed and its entities decoded, which is what the font subsets are cut to (the
     * catalog's own text, not its JSON spelling, whose escaping backslashes the page never shows).
     */
    fun shownText(language: String): String {
        val catalog = catalog(language)
        val texts = catalog.getValue("strings").jsonObject.values.map { it.jsonPrimitive.content } +
            catalog.getValue("roadmap").jsonObject.flatMap { (release, rows) -> listOf(release) + rows.jsonArray.map { it.jsonPrimitive.content } }
        return texts.joinToString(" ") { it.replace(Regex("<[^>]*>"), "") }
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    }
    fun page(language: String) = File(root, "desktop/build/site${path(language)}index.html")
    fun matches(pattern: String, text: String) = Regex(pattern).findAll(text).map { it.value }.sorted().toList()
}

class SiteTranslationTemplateTest {
    private val english = SiteTranslations.catalog("en")

    @Test
    fun `all nine catalogs have exactly the source keys and no empty entries`() {
        for (language in SiteTranslations.languages.keys) {
            val catalog = SiteTranslations.catalog(language)
            assertEquals(english.keys, catalog.keys, language)
            for (group in listOf("strings", "roadmap", "glossary")) {
                val source = english.getValue(group).jsonObject
                val translated = catalog.getValue(group).jsonObject
                assertEquals(source.keys, translated.keys, "$language $group")
                for ((key, value) in translated) {
                    if (group == "roadmap") {
                        assertEquals(source.getValue(key).jsonArray.size, value.jsonArray.size, "$language $key")
                        assertTrue(value.jsonArray.all { it.jsonPrimitive.content.isNotBlank() }, "$language $key")
                    } else assertTrue(value.jsonPrimitive.content.isNotBlank(), "$language $key")
                }
            }
        }
    }

    @Test
    fun `translations preserve markup code and substitution tokens`() {
        for (language in SiteTranslations.languages.keys) {
            val strings = SiteTranslations.catalog(language).getValue("strings").jsonObject
            for ((key, value) in english.getValue("strings").jsonObject) {
                val source = value.jsonPrimitive.content
                val translated = strings.getValue(key).jsonPrimitive.content
                for (pattern in listOf("<[^>]+>", """<code\b[^>]*>[\s\S]*?</code>""", """\{[a-zA-Z]+\}|__[A-Z_]+__""")) {
                    assertEquals(SiteTranslations.matches(pattern, source), SiteTranslations.matches(pattern, translated), "$language $key $pattern")
                }
                if (key.endsWith(".alt") || key.contains(".aria-label") || key.startsWith("page.meta")) {
                    assertFalse(Regex("""[<>"]""").containsMatchIn(translated), "$language $key is not safe attribute text")
                }
            }
        }
    }

    @Test
    fun `the English roadmap contains every source row in order including repeated releases`() {
        val expected = linkedMapOf<String, MutableList<String>>()
        File(SiteTranslations.root, "ROADMAP.md").readLines().forEach { line ->
            val cells = line.trim().trim('|').split('|').map { it.trim() }
            if (!line.trim().startsWith('|') || cells.size != 2 || cells[0] == "Release" || cells[0].all { it == '-' }) return@forEach
            expected.getOrPut(cells[0].removeSuffix("(current)").trim()) { mutableListOf() }.add(cells[1])
        }
        val actual = english.getValue("roadmap").jsonObject.mapValues { (_, rows) -> rows.jsonArray.map { it.jsonPrimitive.content } }
        assertEquals(expected, actual)
        assertTrue(actual.getValue("3.x").size > 1)
    }

    @Test
    fun `every template marker refers to a source string and every static string is marked`() {
        val template = File(SiteTranslations.root, "site/index.html").readText()
        val strings = english.getValue("strings").jsonObject
        val markers = Regex("""\sdata-i18n(?:-[\w-]+)?="([^"]+)"""").findAll(template).toList()
        assertTrue(markers.size > 200)
        for (marker in markers) assertTrue(marker.groupValues[1] in strings, marker.value)
        val sourceTextKeys = strings.keys.filterNot { it.startsWith("script.") || it.startsWith("structured.") ||
            it.startsWith("roadmap.") || it.startsWith("language.") || it.startsWith("translation.") }.toSet()
        assertEquals(sourceTextKeys, markers.map { it.groupValues[1] }.toSet())
    }
}
