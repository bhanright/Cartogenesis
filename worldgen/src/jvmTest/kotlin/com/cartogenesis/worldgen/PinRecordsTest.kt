package com.cartogenesis.worldgen

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Record mode ([PinRecords] and [PinRecordRewriter]) shown on a deliberately stale pin: the pin
 * fails as it always has, record mode writes the measured value as its literal's replacement,
 * and once the rewriter has written that into (a copy of) this file the pin holds.
 */
class PinRecordsTest {

    private val measured = "seed 7 at 0.211"

    private fun violation(): Unit = throw RecordedViolation("a seed under the pin", measured)

    /** The stale pin: the call whose literal record mode is to rewrite. */
    private fun stalePin(sink: (String, String) -> Unit) =
        KnownFailures.held(FINDING, "seed 7 at 0.198", ::violation, recordable = true, sink)

    private val reported = ArrayList<String>()
    private val sink = { finding: String, _: String -> reported.add(finding); Unit }

    @Test
    fun `a stale pin fails, is recorded under record mode, and holds once the rewriter writes it`() {
        // Under `-Precord` the whole task records, and a stale pin cannot be shown failing.
        if (PinRecords.recording) return
        val stale = assertFailsWith<AssertionError> { stalePin(sink) }
        assertTrue(stale.message!!.contains("a different violation"), "the stale pin did not fail as a stale pin: ${stale.message}")

        val directory = Files.createTempDirectory("pin-records").toFile()
        try {
            val records = File(directory, "records.jsonl")
            PinRecords.redirectedTo = records
            try {
                stalePin(sink)
            } finally {
                PinRecords.redirectedTo = null
            }
            assertEquals(listOf(FINDING), reported, "under record mode the stale pin should pass and report its finding")
            val record = records.readLines().map(PinRecord::fromJson).single()
            val source = File(record.file)
            assertEquals("PinRecordsTest.kt", source.name, "the record names the wrong file: ${record.file}")
            val pinLine = source.readLines().indexOfFirst { it.contains("\"seed 7 at 0.198\", ::violation") } + 1
            assertEquals(pinLine, record.line, "the record names the wrong line")
            assertEquals(PinRecord.Operation.STRING_LITERAL, record.operation)
            assertEquals("seed 7 at 0.198", record.old)
            assertEquals(measured, record.new)
            assertTrue(record.test.startsWith("PinRecordsTest."), "the record names the wrong test: ${record.test}")

            val copy = File(directory, "PinRecordsTest.kt")
            source.copyTo(copy)
            val outcome = PinRecordRewriter.apply(listOf(record.copy(file = copy.absolutePath)), write = true)
            assertEquals(1, outcome.changes.size, "the rewriter changed ${outcome.changes}, refused ${outcome.refusals}")
            val rewrittenLine = copy.readLines()[pinLine - 1]
            assertEquals(source.readLines()[pinLine - 1].replace("0.198", "0.211"), rewrittenLine, "the rewriter changed more than the literal")
            val rewritten = PinRecordRewriter.stringLiterals(rewrittenLine).first().value!!
            KnownFailures.held(FINDING, rewritten, ::violation, recordable = false, sink)
            assertEquals(listOf(FINDING, FINDING), reported, "the rewritten pin does not hold")
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * A pin written as literals joined over several lines, and a render record's number, the two
     * shapes a pin takes besides one literal: each rewritten in place and nothing else moved.
     */
    @Test
    fun `the rewriter rewrites a joined literal and a number in place`() {
        val before = """
            |        KnownFailures.expect(
            |            DIAGNOSTIC_VIEWS_STYLED,
            |            "ELEVATION: the coast, the relief; " +
            |                "BIOMES: the coast, the rivers"
            |        ) {
            |        MapStyle.SCROLL to -595599251,
            |        MapStyle.MARS to 1249990086,
            |""".trimMargin()
        val after = """
            |        KnownFailures.expect(
            |            DIAGNOSTIC_VIEWS_STYLED,
            |            "ELEVATION: the coast, the relief; " +
            |                "BIOMES: the coast, the lakes, the rivers"
            |        ) {
            |        MapStyle.SCROLL to 20260930,
            |        MapStyle.MARS to 1249990086,
            |""".trimMargin()
        val directory = Files.createTempDirectory("pin-records").toFile()
        try {
            val file = File(directory, "Pins.kt").apply { writeText(before) }
            val records = listOf(
                PinRecord(
                    "known failure", PinRecord.Operation.STRING_LITERAL, "test", file.absolutePath, 1, "",
                    "ELEVATION: the coast, the relief; BIOMES: the coast, the rivers",
                    "ELEVATION: the coast, the relief; BIOMES: the coast, the lakes, the rivers"
                ),
                PinRecord("render hash", PinRecord.Operation.CODE_TOKEN, "test", file.absolutePath, 0, "MapStyle.SCROLL to ", "-595599251", "20260930")
            )
            val outcome = PinRecordRewriter.apply(records, write = true)
            assertEquals(2, outcome.changes.size, "refused: ${outcome.refusals}")
            assertEquals(after, file.readText())
            val again = PinRecordRewriter.apply(records, write = false)
            assertEquals(0 to 2, again.changes.size to again.refusals.size, "a second pass still found a pin to change")
        } finally {
            directory.deleteRecursively()
        }
    }

    private companion object {
        const val FINDING = "record mode's control finding"
    }
}
