package com.cartogenesis.desktop

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
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
            val source = File(repoRoot, entry.jsonObject.getValue("builtFrom").jsonPrimitive.content)
            assertEquals(
                entry.jsonObject.getValue("sourceSha256").jsonPrimitive.content, sha256(source),
                "${source.name} has changed since site/fonts/$face was cut from it: run site/fonts/build_web_fonts.py"
            )
            assertEquals(
                entry.jsonObject.getValue("sha256").jsonPrimitive.content, sha256(File(repoRoot, "site/fonts/$face")),
                "site/fonts/$face is not the file faces.json records: run site/fonts/build_web_fonts.py"
            )
        }
    }

    @Test
    fun `every character the page sets is in each face that could set it`() {
        val text = File(repoRoot, "site/index.html").readText() + File(repoRoot, "ROADMAP.md").readText()
        val used = text.codePoints().toArray().filter { it >= 0x20 }.toSortedSet()
        val gaps = ArrayList<String>()
        record.forEach { (face, entry) ->
            val kept = entry.jsonObject.getValue("codePointRanges").jsonArray.flatMap { run ->
                val (first, last) = run.jsonArray.map { it.jsonPrimitive.int }
                first..last
            }.toSet()
            val source = File(repoRoot, entry.jsonObject.getValue("builtFrom").jsonPrimitive.content)
            val typeface = FontMgr.default.makeFromFile(source.path, 0)
                ?: fail("could not read ${source.path} as a face")
            // Only what the application's face can draw: a character it has no glyph for falls back
            // to another face whether or not the page's copy is cut from it.
            val dropped = used.filter { typeface.getUTF32Glyph(it).toInt() != 0 && it !in kept }
            if (dropped.isNotEmpty()) {
                gaps += "$face lacks " + dropped.joinToString(" ") { String(Character.toChars(it)) + " (U+%04X)".format(it) }
            }
            typeface.close()
        }
        println("SITE FONTS ${used.size} characters set on the page, each in every face that has it")
        assertTrue(gaps.isEmpty(), "the page sets characters its web fonts dropped; run site/fonts/build_web_fonts.py " +
            "with them kept: ${gaps.joinToString("; ")}")
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
