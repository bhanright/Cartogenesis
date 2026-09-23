package com.cartogenesis.cartography.geometry

import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * What one violation is, so that a known failure can tell its own violation from another in the
 * same place: how many places are past the bar, where the worst of them is, in cells (or which
 * grid bearing, for a test of the whole layer, with the row -1), and its figure in the detector's
 * own units.
 */
internal class Signature(val count: Int, val column: Int, val row: Int, val figure: Double) {

    /**
     * The same violation: the same count, the worst place within [PLACE_CELLS] either way and its
     * figure within [FIGURE_SHARE] of itself. Every run of a census reads the same worlds the same
     * way, so the tolerance is for rounding in the record and not for drift; a fix that moves the
     * violation, or a new violation beside it, is a different one and fails.
     */
    fun matches(found: Signature?): Boolean =
        found != null && found.count == count &&
            abs(found.column - column) <= PLACE_CELLS && abs(found.row - row) <= PLACE_CELLS &&
            abs(found.figure - figure) <= FIGURE_SHARE * maxOf(abs(figure), 1e-9)

    override fun toString(): String = String.format(Locale.ROOT, "%d@(%d,%d)=%.4g", count, column, row, figure)

    companion object {
        const val PLACE_CELLS = 2
        const val FIGURE_SHARE = 0.02
        private val FORM = Regex("""(\d+)@\((-?\d+),(-?\d+)\)=(.+)""")

        fun parse(text: String): Signature {
            val match = FORM.matchEntire(text.trim()) ?: error("not a signature: $text")
            val (count, column, row, figure) = match.destructured
            return Signature(count.toInt(), column.toInt(), row.toInt(), figure.toDouble())
        }
    }
}

/**
 * The geometry guard's own failure: a layer past a detector's bar, with its [signature].
 *
 * A dedicated type so that [KnownFailures.expect] can catch exactly this and nothing else. A setup
 * error, any other assertion, and [InsufficientSample] all pass through it untouched.
 */
internal class GeometryViolation(message: String, val signature: Signature? = null) : AssertionError(message)

/**
 * Too little of a layer to measure at the stated power. Not a violation and never caught: a
 * clause that cannot be measured has not passed.
 */
internal class InsufficientSample(message: String) : AssertionError(message)

/**
 * Clauses that fail today and are kept running, each under the name of the finding it records and
 * the signature of the violation it is.
 *
 * Worldgen's tests run on JUnit 4 and the desktop's on the JUnit Platform, and neither has an
 * expected-failure marker: `@Ignore` does not run the test and `assertFails` turns it inside out,
 * so a fix would go unnoticed either way. [expect] runs the clause. If it throws a
 * [GeometryViolation] — the detector's own failure, and only that — whose signature is the one
 * recorded, the violation is written to the known-failures report under [finding] and the test
 * passes. A violation with another signature fails: a different violation has taken the recorded
 * one's slot, and neither the fix of the one nor the arrival of the other may pass unseen. If the
 * clause does *not* fail, the helper fails with the finding's name and "fixed: arm this clause", so
 * the fix that turns the layer clean is forced to turn the guard on as well. Everything else the
 * clause throws — [InsufficientSample], another assertion, a setup error — propagates as it would
 * anywhere.
 *
 * The report goes to the file named by the system property [REPORT_PROPERTY], which the test task
 * sets and prints once the whole tier has run; without the property (a run from an IDE) it goes to
 * standard output only. Clauses too small to measure are written there too ([insufficient]), so
 * what the tier could not see is at its foot beside what it knows to be wrong.
 */
internal object KnownFailures {

    const val REPORT_PROPERTY = "cartogenesis.knownFailures"

    fun expect(finding: String, signature: Signature?, clause: () -> Unit) =
        expect(finding, signature, clause) { name, detail -> record("KNOWN FAILURE [$name]", detail) }

    /**
     * The same, with somewhere else to [report] to: the helper's own control test uses it so that
     * showing the helper at work does not put a finding in the tier's report.
     */
    fun expect(finding: String, signature: Signature?, clause: () -> Unit, report: (String, String) -> Unit) {
        try {
            clause()
        } catch (violation: GeometryViolation) {
            if (signature != null && !signature.matches(violation.signature)) {
                throw AssertionError(
                    "$finding: a different violation in its place, $signature recorded and ${violation.signature} found: " +
                        (violation.message ?: "")
                )
            }
            report(finding, violation.message ?: "")
            return
        }
        throw AssertionError("$finding fixed: arm this clause")
    }

    /** A clause the census could not measure and expected not to, written to the report. */
    fun insufficient(key: String, detail: String) = record("INSUFFICIENT [$key]", detail)

    @Synchronized
    private fun record(heading: String, detail: String) {
        val line = "$heading ${detail.lines().first()}"
        println(line)
        detail.lines().drop(1).take(DETAIL_LINES).forEach { println("    $it") }
        val path = System.getProperty(REPORT_PROPERTY) ?: return
        val file = File(path)
        file.parentFile?.mkdirs()
        file.appendText(line + "\n")
    }

    /** How many lines of a violation's detail are echoed under its heading. */
    private const val DETAIL_LINES = 6
}
