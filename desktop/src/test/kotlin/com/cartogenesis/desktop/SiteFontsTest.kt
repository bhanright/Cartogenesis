package com.cartogenesis.desktop

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.skia.FontMgr

/**
 * The landing page's web fonts are the application's faces, and hold every character the page sets.
 *
 * The WOFF2 files under `site/fonts` are cut from the faces under `ui/src/commonMain/composeResources/font` by
 * `site/fonts/build_web_fonts.py`, which records in `faces.json` which file each was cut from and
 * which characters it keeps. That record is what lets the page carry a second copy of the faces
 * without drifting from the first: this fails when an application face changes and the script has
 * not been run again, and when the page or the roadmap it draws uses a character a subset dropped,
 * which a browser would set in a fallback face in the middle of a word.
 *
 * Runs in the ordinary tier, not in `siteTest`, because it needs no assembled site: only the
 * repository's own files.
 *
 * Four faces are cut from downloaded Noto sources under `site/fonts/sources`, which version control
 * ignores because they are large. Where such a source is on disk this checks it as it checks the
 * application's faces. Where it is not (the deploy runner), the source hash and the source's glyphs
 * cannot be read, so the subset is held to its own recorded hash and to `absentFromSource`, the
 * characters the source had no glyph for when the script cut it. An application face is tracked,
 * so a missing one is always a failure.
 */
class SiteFontsTest {

    private val repoRoot: File
        get() {
            var dir = File(".").absoluteFile
            while (dir.parentFile != null) {
                if (File(dir, "settings.gradle.kts").isFile) return dir
                dir = dir.parentFile
            }
            fail("could not find the repository root from ${File(".").absolutePath}")
        }

    private val record: JsonObject by lazy {
        Json.parseToJsonElement(File(repoRoot, "site/fonts/faces.json").readText()).jsonObject
    }

    @Test
    fun `every web font is cut from the application's face as it stands`() {
        val page = File(repoRoot, "site/index.html").readText()
        val asked = Regex("""url\("fonts/([^"]+\.woff2)"\)""").findAll(page).map { it.groupValues[1] }.toSet()
        assertEquals(asked, record.keys, "the page's @font-face rules and faces.json name different files")
        record.forEach { (face, entry) ->
            val source = sourceOf(entry)
            if (source.isFile || !isDownloadedSource(entry)) {
                assertTrue(source.isFile, "Missing source $source; the application's faces are tracked in the repository")
                assertEquals(
                    entry.jsonObject.getValue("sourceSha256").jsonPrimitive.content, sha256(source),
                    "${source.name} has changed since site/fonts/$face was cut from it: run site/fonts/build_web_fonts.py"
                )
            }
            assertEquals(
                entry.jsonObject.getValue("sha256").jsonPrimitive.content, sha256(File(repoRoot, "site/fonts/$face")),
                "site/fonts/$face is not the file faces.json records: run site/fonts/build_web_fonts.py"
            )
        }
    }

    @Test
    fun `every character the page sets is in each face that could set it`() {
        val template = File(repoRoot, "site/index.html").readText()
        val text = template.replace(Regex("""<(script|style)\b[^>]*>[\s\S]*?</\1>"""), "").replace(Regex("<[^>]*>"), "") +
            SiteTranslations.languages.keys.joinToString(" ") { language -> SiteTranslations.shownText(language) }
        val used = text.codePoints().toArray().filter { it >= 0x20 }.toSortedSet()
        val gaps = ArrayList<String>()
        record.forEach { (face, entry) ->
            val kept = codePointsOf(entry, "codePointRanges")
            val source = sourceOf(entry)
            val sourceMissing = !source.isFile && isDownloadedSource(entry)
            if (!sourceMissing) assertTrue(source.isFile, "Missing source $source; the application's faces are tracked in the repository")
            // Only what the source face can draw: a character it has no glyph for falls back to another
            // face whether or not the page's copy is cut from it.
            val languages = entry.jsonObject["languages"]?.jsonArray?.map { it.jsonPrimitive.content }
            val relevant = if (languages == null) used else (languages.joinToString(" ") { SiteTranslations.shownText(it) } +
                "English 简体中文 हिन्दी Español العربية Français বাংলা Português Русский").codePoints().toArray().filter { it >= 0x20 }.toSortedSet()
            val dropped = if (sourceMissing) {
                val absent = codePointsOf(entry, "absentFromSource")
                relevant.filter { it !in kept && it !in absent }
            } else {
                val typeface = FontMgr.default.makeFromFile(source.path, 0) ?: fail("could not read ${source.path} as a face")
                relevant.filter { typeface.getUTF32Glyph(it).toInt() != 0 && it !in kept }.also { typeface.close() }
            }
            if (dropped.isNotEmpty()) {
                gaps += "$face lacks " + dropped.joinToString(" ") { String(Character.toChars(it)) + " (U+%04X)".format(it) }
            }
        }
        println("SITE FONTS ${used.size} characters set on the page, each in every face that has it")
        assertTrue(gaps.isEmpty(), "the page sets characters its web fonts dropped; run site/fonts/build_web_fonts.py " +
            "with them kept: ${gaps.joinToString("; ")}")
    }

    private fun sourceOf(entry: JsonElement) =
        File(repoRoot, entry.jsonObject.getValue("builtFrom").jsonPrimitive.content)

    /** True for a face cut from a download kept out of version control, as against a tracked application face. */
    private fun isDownloadedSource(entry: JsonElement) =
        entry.jsonObject.getValue("builtFrom").jsonPrimitive.content.startsWith("site/fonts/sources/")

    private fun codePointsOf(entry: JsonElement, key: String): Set<Int> =
        (entry.jsonObject[key]?.jsonArray ?: emptyList()).flatMap { run ->
            val (first, last) = run.jsonArray.map { it.jsonPrimitive.int }
            first..last
        }.toSet()

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
