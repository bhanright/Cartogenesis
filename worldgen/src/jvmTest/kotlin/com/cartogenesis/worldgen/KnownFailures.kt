package com.cartogenesis.worldgen

import java.io.File

/**
 * A re-armed clause's own failure, with the [signature] that says which failure it is: the list of
 * what the clause found wrong, written out in full and compared whole.
 *
 * The twin of `:cartography`'s `RecordedViolation`, which these tests cannot see; the two behave
 * the same way to the letter, as `:desktop`'s and `:ui`'s twins do. A clause throws this only where
 * the assertion it re-arms has failed, and [KnownFailures.expect] catches it and nothing else.
 */
internal class RecordedViolation(message: String, val signature: String) : AssertionError(message)

/**
 * Clauses that fail today and are kept running, each under the name of the audit finding it
 * records and the signature of the failure it is.
 *
 * The twin of `:cartography`'s `KnownFailures` for a clause outside the geometry guard, which these
 * tests cannot see, and the same in every behaviour and every message; a change to one is made to
 * all of them. This module's tests run on JUnit 4, which has no expected-failure marker: `@Ignore`
 * does not run the test and `assertFails` turns it inside out, so a fix would go unnoticed either
 * way. [expect] runs the clause; a [RecordedViolation] with the recorded signature is written to
 * the known-failures report under the finding and the test passes; one with another signature
 * fails, since a different failure has taken the recorded one's place; a clause that does not fail
 * fails the helper with the finding's name and "fixed: arm this clause", so the fix is forced to
 * turn the guard on; and anything else the clause throws propagates as it would anywhere.
 *
 * The report goes to the file named by the system property [REPORT_PROPERTY], which the test task
 * sets and prints once the task has run; without it (a run from an IDE) the line is printed only.
 */
internal object KnownFailures {

    const val REPORT_PROPERTY = "cartogenesis.knownFailures"

    fun expect(finding: String, signature: String, clause: () -> Unit) =
        expect(finding, signature, clause) { name, detail -> record("KNOWN FAILURE [$name]", detail) }

    /** The same, reporting to [report]: the helper's own test uses it to keep out of the report. */
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

    /** How many lines of a failure's detail are echoed under its heading. */
    private const val DETAIL_LINES = 6
}
