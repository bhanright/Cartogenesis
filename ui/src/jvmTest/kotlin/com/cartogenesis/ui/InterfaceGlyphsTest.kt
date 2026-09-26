package com.cartogenesis.ui

import java.io.File
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every character the interface writes outside ASCII, against the faces it is written in.
 *
 * The browser build draws text with the bundled faces and nothing else: there is no system font
 * behind them, so a character none of them maps is drawn as an empty box. The desktop can hide
 * the same fault behind the operating system's fallback, which is how the dropdown mark `▾` went
 * a fortnight drawn as a box on the web and correctly on the desktop.
 *
 * The characters are read out of the source — every string and character literal in `:ui`'s and
 * `:web`'s main code, comments left out — and the faces out of their own `cmap` tables. A literal's
 * style is chosen at its call site and the Matrix chrome sets every style in the mono face, so the
 * test cannot know which face will draw a given string; every character is held to every bundled
 * face. On the JVM only, because it reads files of the repository.
 */
class InterfaceGlyphsTest {

    private fun repositoryFile(path: String): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle.kts").isFile) return File(dir, path)
            dir = dir.parentFile
        }
        fail("could not find the repository root from ${File(".").absolutePath}")
    }

    @Test
    fun `every character the interface writes is in every face it bundles`() {
        val faces = repositoryFile("ui/src/commonMain/composeResources/font")
            .listFiles { file -> file.extension == "ttf" }.orEmpty().sortedBy { it.name }
        assertTrue(faces.size >= 3, "found ${faces.size} bundled faces; the three families are missing")
        val mapped = faces.associate { it.name to FontCharacterMap.codePoints(it.readBytes()) }

        val sources = listOf("ui/src", "web/src").flatMap { tree ->
            repositoryFile(tree).walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.parentFile.path.contains("Main") }
                .toList()
        }
        assertTrue(sources.size > 20, "found only ${sources.size} interface sources")

        val written = sortedMapOf<Int, MutableSet<String>>()
        for (source in sources) {
            for (codePoint in KotlinLiterals.nonAsciiCodePoints(source.readText())) {
                written.getOrPut(codePoint) { sortedSetOf() }.add(source.name)
            }
        }
        assertTrue(written.isNotEmpty(), "no character outside ASCII found; the literal reader is broken")

        val missing = written.mapNotNull { (codePoint, files) ->
            val without = mapped.filterValues { codePoint !in it }.keys
            if (without.isEmpty()) null
            else "U+%04X '%s' in %s is not in %s".format(
                codePoint, String(Character.toChars(codePoint)), files, without
            )
        }
        assertTrue(
            missing.isEmpty(),
            "characters the browser build would draw as an empty box:\n" + missing.joinToString("\n")
        )
    }

    @Test
    fun `the literal reader finds strings and skips comments`() {
        // The escapes are spelled with BACKSLASH so that this file itself holds none to be decoded.
        val source = """
            // "§" in a line comment
            /* "¶" in a block /* nested "¤" */ still comment */
            val a = "é and ${'$'}{f("ü")} and ${BACKSLASH}u00e5"
            val b = '${BACKSLASH}u00f8'
            val c = ${"\"\"\""}raw ç ${BACKSLASH}u00ff${"\"\"\""}
            val url = "https://example"; val d = "ñ"
        """.trimIndent()
        val found = KotlinLiterals.nonAsciiCodePoints(source).toSet()
        val expected = "éüåøçñ".map { it.code }.toSet()
        assertTrue(found == expected, "read ${found.map { it.toChar() }}, expected ${expected.map { it.toChar() }}")
    }

    private companion object {
        const val BACKSLASH = '\\'
    }

    @Test
    fun `the character map reader agrees with the face on what it holds and lacks`() {
        val sans = FontCharacterMap.codePoints(
            repositoryFile("ui/src/commonMain/composeResources/font/plex_sans_regular.ttf").readBytes()
        )
        assertTrue(('A'.code..'Z'.code).all { it in sans }, "Plex Sans read without its capitals")
        assertTrue(0x2014 in sans, "Plex Sans read without its em dash")
        // A letter from a script the Latin cut does not cover: the reader must say no as well as yes.
        assertTrue(0x05D0 !in sans, "Plex Sans read as holding the Hebrew alef")
    }
}

/** Reads the characters out of a Kotlin file's string and character literals, and nothing else. */
internal object KotlinLiterals {

    /**
     * Every code point above ASCII inside a literal of [source], escapes decoded, in order.
     *
     * A small lexer rather than a pattern, because a `//` inside a string (every URL) and a string
     * inside a template inside a string both defeat a pattern. Raw strings keep their backslashes,
     * as Kotlin does.
     */
    fun nonAsciiCodePoints(source: String): List<Int> {
        val found = mutableListOf<Int>()
        // One entry per open literal or template: the literal's quote, or TEMPLATE with its brace depth.
        val open = ArrayDeque<IntArray>()
        var index = 0
        fun at(offset: Int) = if (index + offset < source.length) source[index + offset] else '\u0000'
        fun take(char: Char) { if (char.code > 127) found += char.code }

        while (index < source.length) {
            val literal = open.lastOrNull()
            val char = source[index]
            when {
                literal == null || literal[0] == TEMPLATE -> when {
                    char == '/' && at(1) == '/' -> index = source.indexOf('\n', index).let { if (it < 0) source.length else it }
                    char == '/' && at(1) == '*' -> index = endOfBlockComment(source, index)
                    char == '"' && at(1) == '"' && at(2) == '"' -> { open.addLast(intArrayOf(RAW, 0)); index += 3 }
                    char == '"' -> { open.addLast(intArrayOf(PLAIN, 0)); index++ }
                    char == '\'' -> index = readCharLiteral(source, index, ::take)
                    char == '{' && literal != null -> { literal[1]++; index++ }
                    char == '}' && literal != null -> {
                        if (literal[1] == 0) open.removeLast() else literal[1]--
                        index++
                    }
                    else -> index++
                }
                char == '$' && at(1) == '{' -> { open.addLast(intArrayOf(TEMPLATE, 0)); index += 2 }
                literal[0] == RAW && char == '"' && at(1) == '"' && at(2) == '"' -> {
                    open.removeLast()
                    index += 3
                    while (at(0) == '"') index++
                }
                literal[0] == PLAIN && char == '"' -> { open.removeLast(); index++ }
                literal[0] == PLAIN && char == '\\' -> index = readEscape(source, index, ::take)
                else -> { take(char); index++ }
            }
        }
        return found
    }

    private fun endOfBlockComment(source: String, start: Int): Int {
        var depth = 0
        var index = start
        while (index < source.length) {
            if (source.startsWith("/*", index)) { depth++; index += 2 }
            else if (source.startsWith("*/", index)) { depth--; index += 2; if (depth == 0) return index }
            else index++
        }
        return index
    }

    private fun readCharLiteral(source: String, start: Int, take: (Char) -> Unit): Int {
        var index = start + 1
        index = if (source[index] == '\\') readEscape(source, index, take) else { take(source[index]); index + 1 }
        return index + 1
    }

    private fun readEscape(source: String, start: Int, take: (Char) -> Unit): Int =
        if (source[start + 1] == 'u') {
            take(source.substring(start + 2, start + 6).toInt(16).toChar())
            start + 6
        } else {
            start + 2
        }

    private const val PLAIN = 0
    private const val RAW = 1
    private const val TEMPLATE = 2
}

/**
 * A TrueType face's character map: the code points its `cmap` table maps to a glyph other than
 * `.notdef`, from its Unicode subtables in format 4 (the basic plane) and format 12 (all planes).
 */
internal object FontCharacterMap {

    fun codePoints(face: ByteArray): Set<Int> {
        val bytes = ByteBuffer.wrap(face)
        val tableCount = bytes.getShort(4).toInt() and 0xFFFF
        val cmap = (0 until tableCount).map { 12 + 16 * it }
            .firstOrNull { String(face, it, 4, Charsets.US_ASCII) == "cmap" }
            ?.let { bytes.getInt(it + 8) }
            ?: fail("the face has no cmap table")
        val subtableCount = bytes.u16(cmap + 2)
        val mapped = HashSet<Int>()
        for (subtable in 0 until subtableCount) {
            val record = cmap + 4 + 8 * subtable
            val platform = bytes.u16(record)
            val encoding = bytes.u16(record + 2)
            val unicode = platform == 0 || (platform == 3 && (encoding == 1 || encoding == 10))
            if (!unicode) continue
            val start = cmap + bytes.getInt(record + 4)
            when (bytes.u16(start)) {
                4 -> readSegments(bytes, start, mapped)
                12 -> readGroups(bytes, start, mapped)
            }
        }
        return mapped
    }

    private fun readSegments(bytes: ByteBuffer, start: Int, into: MutableSet<Int>) {
        val segments = bytes.u16(start + 6) / 2
        val ends = start + 14
        val starts = ends + 2 * segments + 2
        val deltas = starts + 2 * segments
        val rangeOffsets = deltas + 2 * segments
        for (segment in 0 until segments) {
            val first = bytes.u16(starts + 2 * segment)
            val last = bytes.u16(ends + 2 * segment)
            val delta = bytes.getShort(deltas + 2 * segment).toInt()
            val rangeOffsetAt = rangeOffsets + 2 * segment
            val rangeOffset = bytes.u16(rangeOffsetAt)
            for (codePoint in first..last) {
                if (codePoint == 0xFFFF) continue
                val glyph = if (rangeOffset == 0) {
                    (codePoint + delta) and 0xFFFF
                } else {
                    val raw = bytes.u16(rangeOffsetAt + rangeOffset + 2 * (codePoint - first))
                    if (raw == 0) 0 else (raw + delta) and 0xFFFF
                }
                if (glyph != 0) into += codePoint
            }
        }
    }

    private fun readGroups(bytes: ByteBuffer, start: Int, into: MutableSet<Int>) {
        val groups = bytes.getInt(start + 12)
        for (group in 0 until groups) {
            val record = start + 16 + 12 * group
            val first = bytes.getInt(record)
            val last = bytes.getInt(record + 4)
            val firstGlyph = bytes.getInt(record + 8)
            for (codePoint in first..last) if (firstGlyph + (codePoint - first) != 0) into += codePoint
        }
    }

    private fun ByteBuffer.u16(at: Int) = getShort(at).toInt() and 0xFFFF
}
