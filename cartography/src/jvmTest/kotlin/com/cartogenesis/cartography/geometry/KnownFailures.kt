package com.cartogenesis.cartography.geometry

import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * What one violation is, so that a known failure can tell its own violation from another in the
 * same place: how many places are past the bar, where the worst of them is, in cells (or which
 * grid bearing, for a test of the whole layer, with the row [LAYER_WIDE_ROW]), its figure in the
 * detector's own units, and where every other place past the bar is ([others]).
 */
internal class Signature(
    val count: Int,
    val column: Int,
    val row: Int,
    val figure: Double,
    /** The other places past the bar, as (column, row), sorted by row and then column. */
    val others: List<Pair<Int, Int>> = emptyList()
) {

    /**
     * The same violation: the same count, the worst place within [PLACE_CELLS] either way and its
     * figure within [FIGURE_SHARE] of itself, and every other place matched one to one with a
     * place found within [PLACE_CELLS] of it. A grid bearing is a name and not a place, so a test
     * of the whole layer matches its bearings exactly. Every run of a census reads the same worlds
     * the same way, so the tolerance is for rounding in the record and not for drift; a fix that
     * moves the violation, a new violation beside it, or one place mended while another breaks
     * elsewhere, is a different violation and fails.
     */
    fun matches(found: Signature?): Boolean =
        found != null && found.count == count &&
            samePlace(column to row, found.column to found.row) &&
            abs(found.figure - figure) <= FIGURE_SHARE * maxOf(abs(figure), 1e-9) &&
            sameOthers(found.others)

    private fun samePlace(recorded: Pair<Int, Int>, found: Pair<Int, Int>): Boolean =
        if (recorded.second == LAYER_WIDE_ROW || found.second == LAYER_WIDE_ROW) recorded == found
        else abs(found.first - recorded.first) <= PLACE_CELLS && abs(found.second - recorded.second) <= PLACE_CELLS

    /** Each recorded place paired with the nearest unpaired place found, and no place left over. */
    private fun sameOthers(found: List<Pair<Int, Int>>): Boolean {
        if (found.size != others.size) return false
        val unpaired = found.toMutableList()
        for (place in others) {
            val partner = unpaired.filter { samePlace(place, it) }
                .minByOrNull { abs(it.first - place.first) + abs(it.second - place.second) } ?: return false
            unpaired.remove(partner)
        }
        return true
    }

    override fun toString(): String =
        String.format(Locale.ROOT, "%d@(%d,%d)=%.4g", count, column, row, figure) +
            if (others.isEmpty()) "" else " +" + others.joinToString("") { "(${it.first},${it.second})" }

    companion object {
        const val PLACE_CELLS = 2
        const val FIGURE_SHARE = 0.02

        /** The row a test of the whole layer records, with its grid bearing's index as the column. */
        const val LAYER_WIDE_ROW = -1
        private val FORM = Regex("""(\d+)@\((-?\d+),(-?\d+)\)=(\S+)(?: \+((?:\(-?\d+,-?\d+\))+))?""")
        private val PLACE = Regex("""\((-?\d+),(-?\d+)\)""")

        fun parse(text: String): Signature {
            val match = FORM.matchEntire(text.trim()) ?: error("not a signature: $text")
            val (count, column, row, figure, others) = match.destructured
            val places = PLACE.findAll(others).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toList()
            return Signature(count.toInt(), column.toInt(), row.toInt(), figure.toDouble(), places)
        }

        /** The signature of [places] past a bar: the worst of them by its figure, and the rest. */
        fun of(places: List<Pair<Pair<Int, Int>, Double>>): Signature {
            val worst = places.maxBy { it.second }
            val rest = places.filter { it !== worst }.map { it.first }.sortedWith(compareBy({ it.second }, { it.first }))
            return Signature(places.size, worst.first.first, worst.first.second, worst.second, rest)
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
 * A re-armed clause's own failure outside the geometry guard, with the [signature] that says which
 * failure it is: the list of what the clause found wrong, written out in full — the shores left
 * uninked by facing, the pairs of colours under their bar — and compared whole.
 *
 * The geometry guard's [GeometryViolation] carries a [Signature] of places on the grid; a clause
 * about colours, a codec or a scale bar has no place on the grid, so its signature is the text of
 * its finding. A clause throws this only where the assertion it re-arms has failed, and
 * [KnownFailures.expect] catches it the way it catches a [GeometryViolation], and nothing else.
 * `:worldgen`'s, `:desktop`'s and `:ui`'s tests, which cannot see this module's, keep twins of it
 * and of the helper that behave the same way to the letter; a change here is made in all four.
 */
internal class RecordedViolation(message: String, val signature: String) : AssertionError(message)

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
 * A clause re-armed outside the geometry guard is held the same way with a [RecordedViolation] and
 * a text signature ([expect] with a `String`): only that type is caught, only its own signature
 * passes, and a clause that passes fails with "fixed: arm this clause".
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

    /** A clause outside the geometry guard, whose failure is a [RecordedViolation] with [signature]. */
    fun expect(finding: String, signature: String, clause: () -> Unit) =
        expect(finding, signature, clause) { name, detail -> record("KNOWN FAILURE [$name]", detail) }

    /** The same, reporting to [report]; see the geometry guard's overload for why. */
    fun expect(finding: String, signature: String, clause: () -> Unit, report: (String, String) -> Unit) {
        try {
            clause()
        } catch (violation: RecordedViolation) {
            if (violation.signature != signature) {
                throw AssertionError(
                    "$finding: a different violation in its place, [$signature] recorded and [${violation.signature}] found: " +
                        (violation.message ?: "")
                )
            }
            report(finding, violation.message ?: "")
            return
        }
        throw AssertionError("$finding fixed: arm this clause")
    }

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
