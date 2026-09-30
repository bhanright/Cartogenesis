package com.cartogenesis.worldgen

import java.io.File
import kotlin.math.abs

/**
 * Writes the records a `-Precord` run left under every module's `build/pin-records` into the
 * source files they name, and prints what it changed for a person to review: file and line, the
 * old value, the new one, and the test that measured it.
 *
 * Every record is placed against the file as it was read, before any is written, so that one
 * record's inserted or deleted line cannot move another's; two records that would touch the same
 * characters are refused and printed, as is a record whose pin cannot be found where it says or can
 * be found in more than one place at the same distance. A refused record changes nothing. Notices
 * are printed and never written. Once applied, the records are deleted, so a second run cannot
 * insert the same line twice.
 */
internal object PinRecordRewriter {

    /** What was done: one line per change for the review, and one per record left alone. */
    class Outcome(val changes: List<String>, val refusals: List<String>, val notices: List<String>)

    /** The records under every module of [repositoryRoot], oldest file first. */
    fun recordFiles(repositoryRoot: File): List<File> =
        (repositoryRoot.listFiles() ?: emptyArray()).map { File(it, "build/pin-records") }
            .filter { it.isDirectory }
            .flatMap { directory -> directory.walkTopDown().filter { it.isFile && it.extension == "jsonl" }.toList() }
            .sortedBy { it.lastModified() }

    fun read(files: List<File>): List<PinRecord> =
        files.flatMap { file -> file.readLines().filter { it.isNotBlank() }.map(PinRecord::fromJson) }.distinct()

    /** Applies [records] to the files they name; with [write] false, only says what it would do. */
    fun apply(records: List<PinRecord>, write: Boolean): Outcome {
        val changes = ArrayList<String>()
        val refusals = ArrayList<String>()
        val notices = records.filter { it.operation == PinRecord.Operation.NOTICE }
            .map { "${where(it)}: ${it.new} (${it.test})" }
        for ((path, fileRecords) in records.filter { it.operation != PinRecord.Operation.NOTICE }.groupBy { it.file }) {
            val file = File(path)
            if (!file.isFile) {
                fileRecords.forEach { refusals.add("${where(it)}: the file is not there (${it.test})") }
                continue
            }
            val source = SourceText(file.readText())
            val edits = ArrayList<Edit>()
            for (record in fileRecords) {
                val edit = try {
                    source.editFor(record)
                } catch (refused: Refused) {
                    refusals.add("${where(record)}: ${refused.message} (${record.test})")
                    continue
                }
                if (edits.any { it.overlaps(edit) }) {
                    refusals.add("${where(record)}: touches what another record already changes (${record.test})")
                    continue
                }
                edits.add(edit)
                val new = if (record.operation == PinRecord.Operation.DELETE_LINE) "deleted" else record.new
                val (oldShown, newShown) = changedParts(record.old.ifEmpty { "-" }, new)
                changes.add("${file.name}:${source.lineOf(edit.start)}  [$oldShown] -> [$newShown]  (${record.kind}, ${record.test})")
            }
            if (write && edits.isNotEmpty()) file.writeText(source.applied(edits))
        }
        return Outcome(changes, refusals, notices)
    }

    /**
     * [old] and [new] as the review shows them: whole where they are short, and where they are
     * long, only the part that differs with [CONTEXT_CHARACTERS] either side, so that one figure
     * moved in a long signature is what the eye lands on.
     */
    fun changedParts(old: String, new: String): Pair<String, String> {
        if (old.length <= SHOWN_WHOLE_CHARACTERS && new.length <= SHOWN_WHOLE_CHARACTERS) return old to new
        val sharedStart = old.zip(new).takeWhile { (a, b) -> a == b }.size
        val sharedEnd = old.reversed().zip(new.reversed()).takeWhile { (a, b) -> a == b }.size
            .coerceAtMost(minOf(old.length, new.length) - sharedStart)
        fun shown(value: String): String {
            val from = (sharedStart - CONTEXT_CHARACTERS).coerceAtLeast(0)
            val to = (value.length - sharedEnd + CONTEXT_CHARACTERS).coerceAtMost(value.length)
            return (if (from > 0) "..." else "") + value.substring(from, to) + (if (to < value.length) "..." else "")
        }
        return shown(old) to shown(new)
    }

    /** A value up to this long is shown whole in the review. */
    private const val SHOWN_WHOLE_CHARACTERS = 100

    /** How much unchanged text is shown either side of a change in a long value. */
    private const val CONTEXT_CHARACTERS = 24

    private fun where(record: PinRecord) = "${record.file.substringAfterLast('/').substringAfterLast('\\')}:${record.line}"

    /** A pin that cannot be placed; the message says why. */
    class Refused(message: String) : Exception(message)

    /**
     * One change to a file's text: [replacement] for the characters from [start] to [end]. An
     * insertion has [start] equal to [end], and [order] keeps two insertions at one place in the
     * order their records came.
     */
    class Edit(val start: Int, val end: Int, val replacement: String, val order: Int) {
        /** Whether the two change a character in common; insertions at one place do not. */
        fun overlaps(other: Edit): Boolean = start < other.end && other.start < end
    }

    /** A source file's text, and where its lines and string literals are. */
    class SourceText(val text: String) {
        private val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        private val lineStarts: List<Int> = listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }
        private var edits = 0

        /** The one-based line the character at [offset] is on. */
        fun lineOf(offset: Int): Int = lineStarts.binarySearch(offset).let { if (it >= 0) it + 1 else -it - 1 }

        private fun lineText(index: Int): String {
            val end = if (index + 1 < lineStarts.size) lineStarts[index + 1] else text.length
            return text.substring(lineStarts[index], end).trimEnd('\n', '\r')
        }

        private fun indentation(index: Int) = lineText(index).takeWhile { it == ' ' || it == '\t' }

        fun editFor(record: PinRecord): Edit = when (record.operation) {
            PinRecord.Operation.STRING_LITERAL -> stringEdit(record)
            PinRecord.Operation.CODE_TOKEN -> tokenEdit(record)
            PinRecord.Operation.REPLACE_LINE -> lineMatching(record.old, record.line).let { index ->
                val start = lineStarts[index] + indentation(index).length
                Edit(start, lineStarts[index] + lineText(index).length, record.new, edits++)
            }
            PinRecord.Operation.DELETE_LINE -> lineMatching(record.old, record.line).let { index ->
                val end = if (index + 1 < lineStarts.size) lineStarts[index + 1] else text.length
                Edit(lineStarts[index], end, "", edits++)
            }
            PinRecord.Operation.INSERT_AFTER -> lineMatching(record.anchor, record.line).let { index ->
                val at = if (index + 1 < lineStarts.size) lineStarts[index + 1] else text.length
                Edit(at, at, indentation(index) + record.new + newline, edits++)
            }
            PinRecord.Operation.INSERT_BEFORE -> lineMatching(record.anchor, record.line).let { index ->
                Edit(lineStarts[index], lineStarts[index], indentation(index) + record.new + newline, edits++)
            }
            PinRecord.Operation.NOTICE -> throw Refused("a notice is not written")
        }

        /** The index of the line reading [trimmed], the nearest to [line] if several do. */
        private fun lineMatching(trimmed: String, line: Int): Int {
            val matches = lineStarts.indices.filter { lineText(it).trim() == trimmed }
            return nearest(matches, line) { it + 1 } ?: throw Refused("no line reads [$trimmed]")
        }

        private fun <T> nearest(candidates: List<T>, line: Int, lineOf: (T) -> Int): T? {
            if (candidates.isEmpty()) return null
            if (line <= 0) return candidates.singleOrNull() ?: throw Refused("found in ${candidates.size} places and no line to choose by")
            val best = candidates.minOf { abs(lineOf(it) - line) }
            return candidates.filter { abs(lineOf(it) - line) == best }.singleOrNull()
                ?: throw Refused("found in two places equally near line $line")
        }

        private fun tokenEdit(record: PinRecord): Edit {
            val pattern = Regex("(?<![\\w.])" + Regex.escape(record.old) + "(?![\\w.])")
            val matches = pattern.findAll(text).filter { lineText(lineOf(it.range.first) - 1).contains(record.anchor) }.toList()
            val match = nearest(matches, record.line) { lineOf(it.range.first) }
                ?: throw Refused("no [${record.old}] on a line with [${record.anchor}]")
            return Edit(match.range.first, match.range.last + 1, record.new, edits++)
        }

        /**
         * The string literal whose value is [PinRecord.old], or the literals joined by `+` that
         * spell it, rewritten to hold [PinRecord.new]: as one literal where it was one, and wrapped
         * at the same indentation where it was several.
         */
        private fun stringEdit(record: PinRecord): Edit {
            val chains = ArrayList<List<StringLiteral>>()
            for (first in literals.indices) {
                var spelled = ""
                for (last in first until literals.size) {
                    val literal = literals[last]
                    if (literal.value == null) break
                    if (last > first && !JOINED.matches(text.substring(literals[last - 1].end, literal.start))) break
                    spelled += literal.value
                    if (spelled == record.old) { chains.add(literals.subList(first, last + 1)); break }
                    if (!record.old.startsWith(spelled)) break
                }
            }
            val chain = nearest(chains, record.line) { lineOf(it.first().start) }
                ?: throw Refused("no string literal reads [${record.old}]")
            val replacement = if (chain.size == 1) quoted(record.new) else {
                val continuation = newline + indentation(lineOf(chain[1].start) - 1)
                rejoined(chain.map { it.value!! }, record.new).joinToString(" +$continuation") { quoted(it) }
            }
            return Edit(chain.first().start, chain.last().end, replacement, edits++)
        }

        /**
         * [new] cut into pieces the way [oldPieces] cut the old value: every leading and trailing
         * piece the change does not reach is kept as it was, so the diff shows only the lines the
         * change moved, and what lies between is wrapped at the widest old piece's width.
         */
        private fun rejoined(oldPieces: List<String>, new: String): List<String> {
            val old = oldPieces.joinToString("")
            val sharedStart = old.zip(new).takeWhile { (a, b) -> a == b }.size
            val sharedEnd = old.reversed().zip(new.reversed()).takeWhile { (a, b) -> a == b }.size
                .coerceAtMost(minOf(old.length, new.length) - sharedStart)
            val leading = ArrayList<String>()
            var leadingLength = 0
            for (piece in oldPieces) {
                if (leadingLength + piece.length > sharedStart) break
                leading.add(piece); leadingLength += piece.length
            }
            val trailing = ArrayList<String>()
            var trailingLength = 0
            for (piece in oldPieces.drop(leading.size).reversed()) {
                if (trailingLength + piece.length > sharedEnd) break
                trailing.add(0, piece); trailingLength += piece.length
            }
            val middle = new.substring(leadingLength, new.length - trailingLength)
            val middlePieces = if (middle.isEmpty()) emptyList() else wrapped(middle, oldPieces.maxOf { it.length })
            return (leading + middlePieces + trailing).ifEmpty { listOf("") }
        }

        private val literals: List<StringLiteral> by lazy { stringLiterals(text) }

        /**
         * The text with [edits] made, from the last to the first so that no offset moves. At one
         * place a deletion goes before an insertion, or it would take the inserted line with it,
         * and insertions go in reverse so that they read in their records' order.
         */
        fun applied(edits: List<Edit>): String {
            val builder = StringBuilder(text)
            val lastFirst = compareByDescending<Edit> { it.start }.thenByDescending { it.end }.thenByDescending { it.order }
            for (edit in edits.sortedWith(lastFirst)) {
                builder.replace(edit.start, edit.end, edit.replacement)
            }
            return builder.toString()
        }
    }

    /** A `"..."` in the source, from its opening quote to past its closing one; [value] is null for a template. */
    class StringLiteral(val start: Int, val end: Int, val value: String?)

    /**
     * Every single-quoted-string literal in [text], skipping comments, character literals and raw
     * strings, each with the value it spells, or null where it holds a `$` template and so has no
     * value in the source.
     */
    fun stringLiterals(text: String): List<StringLiteral> {
        val found = ArrayList<StringLiteral>()
        var at = 0
        while (at < text.length) {
            when {
                text.startsWith("//", at) -> at = text.indexOf('\n', at).let { if (it < 0) text.length else it }
                text.startsWith("/*", at) -> at = text.indexOf("*/", at + 2).let { if (it < 0) text.length else it + 2 }
                text.startsWith("\"\"\"", at) -> at = text.indexOf("\"\"\"", at + 3).let { if (it < 0) text.length else it + 3 }
                text[at] == '\'' -> at = if (text.startsWith("\\", at + 1)) text.indexOf('\'', at + 3) + 1 else at + 3
                text[at] == '"' -> {
                    val start = at
                    val value = StringBuilder()
                    var template = false
                    at++
                    while (at < text.length && text[at] != '"' && text[at] != '\n') {
                        if (text[at] == '\\' && at + 1 < text.length) {
                            val escaped = text[at + 1]
                            if (escaped == 'u' && at + 5 < text.length) {
                                value.append(text.substring(at + 2, at + 6).toInt(16).toChar()); at += 6; continue
                            }
                            value.append(ESCAPES[escaped] ?: escaped); at += 2; continue
                        }
                        if (text[at] == '$' && at + 1 < text.length && (text[at + 1] == '{' || text[at + 1].isJavaIdentifierStart())) {
                            template = true
                            if (text[at + 1] == '{') {
                                var depth = 0
                                while (at < text.length) {
                                    if (text[at] == '{') depth++
                                    if (text[at] == '}' && --depth == 0) break
                                    at++
                                }
                            }
                        }
                        value.append(text[at]); at++
                    }
                    at++
                    found.add(StringLiteral(start, at, if (template) null else value.toString()))
                }
                else -> at++
            }
        }
        return found
    }

    /** [value] as a Kotlin string literal. */
    fun quoted(value: String): String = buildString {
        append('"')
        for (character in value) when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '$' -> append("\\$")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
        append('"')
    }

    /**
     * [value] cut into pieces of at most [width] characters where it can be, each cut after a
     * separator ("; ", ", " or a space) so that a list breaks between its items.
     */
    fun wrapped(value: String, width: Int): List<String> {
        val pieceWidth = width.coerceAtLeast(MINIMUM_PIECE_CHARACTERS)
        val pieces = ArrayList<String>()
        var rest = value
        while (rest.length > pieceWidth) {
            val window = rest.substring(0, pieceWidth)
            val cut = listOf("; ", ", ", " ").map { window.lastIndexOf(it).let { at -> if (at <= 0) -1 else at + it.length } }
                .firstOrNull { it > 0 } ?: pieceWidth
            pieces.add(rest.substring(0, cut))
            rest = rest.substring(cut)
        }
        pieces.add(rest)
        return pieces
    }

    /** What may stand between two literals that one expression joins: a plus, and white space. */
    private val JOINED = Regex("""\s*\+\s*""")

    private val ESCAPES = mapOf('n' to '\n', 'r' to '\r', 't' to '\t', 'b' to '\b', '\\' to '\\', '"' to '"', '\'' to '\'', '$' to '$')

    /**
     * The narrowest a wrapped piece is cut: a signature split over literals of a few characters
     * each would be harder to read than one long line.
     */
    private const val MINIMUM_PIECE_CHARACTERS = 40
}

/**
 * `:worldgen:applyPinRecords`: the rewriter over the repository at the first argument, writing
 * unless `--review` is given, when it only prints what it would change.
 */
fun main(arguments: Array<String>) {
    val root = File(arguments.firstOrNull() ?: ".").absoluteFile
    val write = "--review" !in arguments
    val files = PinRecordRewriter.recordFiles(root)
    val records = PinRecordRewriter.read(files)
    println("${records.size} records in ${files.size} files under ${root.path}")
    val outcome = PinRecordRewriter.apply(records, write)
    println(if (write) "Changed (${outcome.changes.size}):" else "Would change (${outcome.changes.size}):")
    outcome.changes.forEach { println("  $it") }
    if (outcome.refusals.isNotEmpty()) {
        println("Not changed, by hand (${outcome.refusals.size}):")
        outcome.refusals.forEach { println("  $it") }
    }
    if (outcome.notices.isNotEmpty()) {
        println("Notices, for a person to decide (${outcome.notices.size}):")
        outcome.notices.forEach { println("  $it") }
    }
    if (write) files.forEach { it.delete() }
}
