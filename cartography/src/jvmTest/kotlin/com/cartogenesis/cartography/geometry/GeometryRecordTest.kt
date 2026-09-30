package com.cartogenesis.cartography.geometry

import com.cartogenesis.worldgen.PinRecord
import com.cartogenesis.worldgen.PinRecordRewriter
import com.cartogenesis.worldgen.PinRecords
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Record mode for the geometry census ([Census.record]) shown on a table of expectations gone
 * stale in every way a census can move one: a known failure moved, one mended, one new, a list of
 * seeds too small to measure grown shorter and a new one. The stale table fails as the census
 * would; record mode writes each change to the line it was written on; and once the rewriter has
 * written them into a copy of this file, the table read back from the copy records nothing more.
 * A violation with no finding named is left to a person, in both.
 */
class GeometryRecordTest {

    /** The stale table. Its lines are what record mode rewrites, in a copy of this file. */
    private fun fixture() = Expectations().apply {
        finding("coast", Detector.FACETS, "record mode's control finding")
        known("7/coast/FACETS", "1@(833,72)=57.45")
        known("7/coast/CREASES", "1@(219,301)=7.088")
        insufficient("coast", Detector.ARCS, 7L, 42L)
    }

    /** What today's census found, by hand: two worlds with one layer. */
    private fun found() = Census.Found(listOf("coast")).apply {
        for (seed in listOf(7L, 42L)) for (detector in Detector.entries) made.add(Census.key(seed, "coast", detector))
        violations["7/coast/FACETS"] = Census.Violation("coast", Detector.FACETS, Signature(1, 840, 80, 61.0))
        violations["42/coast/FACETS"] = Census.Violation("coast", Detector.FACETS, Signature(2, 100, 50, 58.0, listOf(120 to 60)))
        violations["42/coast/RECTANGLE"] = Census.Violation("coast", Detector.RECTANGLE, Signature(1, 10, 10, 1.0))
        unmeasured["coast" to Detector.ARCS] = mutableListOf(7L)
        unmeasured["coast" to Detector.COMBS] = mutableListOf(42L)
    }

    @Test
    fun `a stale census table fails, is recorded line by line, and records nothing once rewritten`() {
        // Under `-Precord` the whole task records, and the stale table cannot be shown failing.
        if (PinRecords.recording) return
        val stale = fixture()
        val moved = found().violations.getValue("7/coast/FACETS").signature
        val failed = assertFailsWith<AssertionError> {
            KnownFailures.expect("control", stale.signatures["7/coast/FACETS"], { throw GeometryViolation("moved", moved) }) { _, _ -> }
        }
        assertTrue(failed.message!!.contains("a different violation"), "the moved violation passed: ${failed.message}")

        val directory = Files.createTempDirectory("pin-records").toFile()
        try {
            val records = File(directory, "records.jsonl")
            val left = recording(records) { Census.record(stale, found(), "GeometryRecordTest") }
            assertEquals(1, left.size, "record mode should leave only the unnamed finding: $left")
            assertTrue(left.single().contains("42/coast/RECTANGLE") && left.single().contains("no finding named"))
            val written = records.readLines().map(PinRecord::fromJson)
            assertEquals(
                listOf(
                    PinRecord.Operation.REPLACE_LINE, PinRecord.Operation.DELETE_LINE, PinRecord.Operation.INSERT_AFTER,
                    PinRecord.Operation.REPLACE_LINE, PinRecord.Operation.INSERT_AFTER
                ).sorted(),
                written.map { it.operation }.sorted()
            )
            val source = File(written.first().file)
            assertEquals("GeometryRecordTest.kt", source.name, "the records name the wrong file")

            val copy = File(directory, source.name)
            source.copyTo(copy)
            val outcome = PinRecordRewriter.apply(written.map { it.copy(file = copy.absolutePath) }, write = true)
            assertTrue(outcome.refusals.isEmpty(), "refused: ${outcome.refusals}")
            val table = tableIn(copy)
            assertEquals(
                listOf(
                    "finding(\"coast\", Detector.FACETS, \"record mode's control finding\")",
                    "known(\"7/coast/FACETS\", \"1@(840,80)=61.00\")",
                    "known(\"42/coast/FACETS\", \"2@(100,50)=58.00 +(120,60)\")",
                    "insufficient(\"coast\", Detector.ARCS, 7L)",
                    "insufficient(\"coast\", Detector.COMBS, 42L)"
                ),
                table.map { it.trim() }
            )
            assertTrue(table.all { it.startsWith("        ") && !it.startsWith("         ") }, "a line lost its indentation: $table")

            val rewritten = Expectations().apply {
                finding("coast", Detector.FACETS, "record mode's control finding")
                for (line in table) {
                    KNOWN.matchEntire(line.trim())?.let { known(it.groupValues[1], it.groupValues[2]) }
                    INSUFFICIENT.matchEntire(line.trim())?.let { entry ->
                        insufficient(entry.groupValues[1], Detector.valueOf(entry.groupValues[2]),
                            *entry.groupValues[3].split(", ").map { it.removeSuffix("L").toLong() }.toLongArray())
                    }
                }
            }
            val again = File(directory, "again.jsonl")
            val stillLeft = recording(again) { Census.record(rewritten, found(), "GeometryRecordTest") }
            assertEquals(left, stillLeft, "the rewritten table left more than the unnamed finding")
            assertTrue(!again.exists() || again.readText().isBlank(), "the rewritten table still records: ${if (again.exists()) again.readText() else ""}")
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun <T> recording(records: File, block: () -> T): T {
        PinRecords.redirectedTo = records
        try {
            return block()
        } finally {
            PinRecords.redirectedTo = null
        }
    }

    /** The lines of [fixture]'s table in [file]: from the line after its declaration to its closing brace. */
    private fun tableIn(file: File): List<String> {
        val lines = file.readLines()
        val start = lines.indexOfFirst { it.contains("private fun fixture()") } + 1
        return lines.drop(start).takeWhile { it.trim() != "}" }
    }

    private companion object {
        val KNOWN = Regex("""known\("([^"]+)", "([^"]+)"\)""")
        val INSUFFICIENT = Regex("""insufficient\("([^"]+)", Detector\.(\w+), (.+)\)""")
    }
}
