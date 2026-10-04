package com.cartogenesis.desktop

import com.cartogenesis.cartography.MapStyle
import com.cartogenesis.cartography.MapView
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * That the application speaks American English wherever a reader meets it: every string in the
 * main sources of `:ui`, `:desktop` and `:cartography` (labels, style names, prose,
 * dialogs, messages, the export sidecars' words) and the browser's page shell.
 *
 * Read from the source rather than from the running interface, because the words are spread over
 * composables, enums, sidecar writers and error messages that no one screen shows at once. Comments
 * are not read, and neither is code held in a string: a shader's source (docs/CONVENTIONS.md,
 * rule 10) or a script the page runs, whose words are identifiers. A key written into a file is a
 * wire name and keeps its spelling (rule 11); each such key is listed in [WIRE_NAMES] with the
 * file that writes it.
 */
class AppSpellingTest {

    /**
     * Words in strings that are names in a file format rather than text, by the file that writes
     * them. A save or a sidecar already written reads them back by these names.
     */
    private val WIRE_NAMES: Map<String, Set<String>> = mapOf(
        // The legend's colour field in a data export's sidecar, read by other tools by name.
        "cartography/src/commonMain/kotlin/com/cartogenesis/cartography/DataExport.kt" to setOf("colour")
    )

    private val modules = listOf("ui", "desktop", "cartography")

    @Test
    fun `the application's own words are spelled in American English`() {
        val found = mutableListOf<String>()
        var stringsRead = 0
        for (module in modules) {
            val sources = File(repoRoot, "$module/src").walkTopDown()
                .filter { it.isFile && (it.extension == "kt" || it.extension == "html") }
                .filter { file -> file.invariantSeparatorsPath.split('/').none { it.endsWith("Test") || it == "test" } }
            for (file in sources) {
                val relative = file.relativeTo(repoRoot).invariantSeparatorsPath
                val allowed = WIRE_NAMES[relative].orEmpty()
                val texts = if (file.extension == "kt") KotlinStrings.of(file.readText()) else listOf(htmlText(file.readText()))
                for (text in texts) {
                    if (looksLikeCode(text)) continue
                    stringsRead++
                    AmericanEnglish.BRITISH_SPELLINGS.findAll(text)
                        .map { it.value }
                        .filter { it.lowercase() !in allowed }
                        .forEach { found += "$relative: \"$it\" in \"${text.trim().take(80)}\"" }
                }
            }
        }
        assertTrue(stringsRead > 1000, "only $stringsRead strings were read; the reader has stopped finding them")
        assertTrue(found.isEmpty(), "British spellings in the application's text:\n" + found.joinToString("\n"))
        println("SPELLING $stringsRead strings of the application read, none British")
    }

    /** The labels a reader picks from, read off the running enums: the style names and the views. */
    @Test
    fun `the style and view names are spelled in American English`() {
        val names = MapStyle.entries.flatMap { listOf(it.label, it.detail) } + MapView.entries.map { it.label }
        val british = names.filter { AmericanEnglish.BRITISH_SPELLINGS.containsMatchIn(it) }
        assertTrue(british.isEmpty(), "British spellings in the style and view names: $british")
    }

    /**
     * A string that holds code rather than words: a shader's source, a script, or a fragment of
     * either. Its words are identifiers, which no reader meets.
     */
    private fun looksLikeCode(text: String): Boolean =
        CODE_MARKERS.any { it in text }

    private val CODE_MARKERS = listOf(
        "#version", "layout(", "@group(", "@binding(", "@compute", "var<", "vec2", "vec3", "vec4", "u32", "f32",
        "=>", "uniform ", ";\n"
    )

    /** The page shell's text, with its scripts and styles taken out. */
    private fun htmlText(html: String): String =
        html.replace(Regex("""<script\b.*?</script>|<style\b.*?</style>|<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), " ")

    private val repoRoot: File
        get() {
            var dir = File(".").absoluteFile
            while (dir.parentFile != null) {
                if (File(dir, "settings.gradle.kts").isFile) return dir
                dir = dir.parentFile
            }
            fail("could not find the repository root from ${File(".").absolutePath}")
        }
}

/**
 * The string literals of a Kotlin source, read with just enough of the language to tell a string
 * from a comment: line and nested block comments are skipped, character literals are stepped over,
 * and inside a string a template expression (`${...}`, `$name`) is left out, since it is code.
 * Each literal comes back as its text with the expressions removed.
 */
internal object KotlinStrings {

    fun of(source: String): List<String> {
        val strings = mutableListOf<String>()
        var at = 0
        while (at < source.length) {
            when {
                source.startsWith("//", at) -> at = source.indexOf('\n', at).let { if (it < 0) source.length else it }
                source.startsWith("/*", at) -> at = afterBlockComment(source, at)
                source.startsWith("\"\"\"", at) -> at = readString(source, at + 3, raw = true, into = strings)
                source[at] == '"' -> at = readString(source, at + 1, raw = false, into = strings)
                source[at] == '\'' -> at = afterCharLiteral(source, at)
                else -> at++
            }
        }
        return strings
    }

    private fun afterBlockComment(source: String, start: Int): Int {
        var depth = 0
        var at = start
        while (at < source.length) {
            when {
                source.startsWith("/*", at) -> { depth++; at += 2 }
                source.startsWith("*/", at) -> { depth--; at += 2; if (depth == 0) return at }
                else -> at++
            }
        }
        return at
    }

    private fun afterCharLiteral(source: String, start: Int): Int {
        var at = start + 1
        while (at < source.length && source[at] != '\'') at += if (source[at] == '\\') 2 else 1
        return at + 1
    }

    /** Reads a string whose text starts at [start]; returns where the code resumes after it. */
    private fun readString(source: String, start: Int, raw: Boolean, into: MutableList<String>): Int {
        val text = StringBuilder()
        var at = start
        while (at < source.length) {
            if (raw && source.startsWith("\"\"\"", at)) {
                // A raw string may end in more quotes than three; the last three close it.
                var end = at + 3
                while (end < source.length && source[end] == '"') { text.append('"'); end++ }
                into += text.toString()
                return end
            }
            val char = source[at]
            when {
                !raw && char == '"' -> { into += text.toString(); return at + 1 }
                !raw && char == '\\' -> { text.append(source.getOrElse(at + 1) { ' ' }); at += 2 }
                char == '$' && source.getOrNull(at + 1) == '{' -> { text.append(' '); at = afterTemplate(source, at + 2, into) }
                char == '$' && source.getOrNull(at + 1)?.let { it.isLetter() || it == '_' } == true -> {
                    text.append(' ')
                    at++
                    while (at < source.length && (source[at].isLetterOrDigit() || source[at] == '_')) at++
                }
                else -> { text.append(char); at++ }
            }
        }
        into += text.toString()
        return at
    }

    /**
     * Skips a template expression's code; a string within it is read into [into] as a string of its
     * own, since `${'$'}{if (one) "a word" else "another"}` says either. Returns where the string resumes.
     */
    private fun afterTemplate(source: String, start: Int, into: MutableList<String>): Int {
        var depth = 1
        var at = start
        while (at < source.length && depth > 0) {
            when {
                source.startsWith("\"\"\"", at) -> at = readString(source, at + 3, raw = true, into = into)
                source[at] == '"' -> at = readString(source, at + 1, raw = false, into = into)
                source[at] == '\'' -> at = afterCharLiteral(source, at)
                source[at] == '{' -> { depth++; at++ }
                source[at] == '}' -> { depth--; at++ }
                else -> at++
            }
        }
        return at
    }
}
