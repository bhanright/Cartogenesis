package com.cartogenesis.cartography.geometry

import java.io.File

/**
 * The geometry guard's own failure: a layer past a detector's bar.
 *
 * A dedicated type so that [KnownFailures.expect] can catch exactly this and nothing else. A setup
 * error, any other assertion, and [InsufficientSample] all pass through it untouched.
 */
internal class GeometryViolation(message: String) : AssertionError(message)

/**
 * Too little of a layer to measure at the stated power. Not a violation and never caught: a
 * clause that cannot be measured has not passed.
 */
internal class InsufficientSample(message: String) : AssertionError(message)

/**
 * Clauses that fail today and are kept running, each under the name of the finding it records.
 *
 * Worldgen's tests run on JUnit 4 and the desktop's on the JUnit Platform, and neither has an
 * expected-failure marker: `@Ignore` does not run the test and `assertFails` turns it inside out,
 * so a fix would go unnoticed either way. [expect] runs the clause. If it throws a
 * [GeometryViolation] — the detector's own failure, and only that — the violation is recorded in
 * the known-failures report under [finding] and the test passes. If the clause does *not* fail,
 * the helper fails with the finding's name and "fixed: arm this clause", so the fix that turns the
 * layer clean is forced to turn the guard on as well. Everything else the clause throws —
 * [InsufficientSample], another assertion, a setup error — propagates as it would anywhere.
 *
 * The report goes to the file named by the system property [REPORT_PROPERTY], which the test task
 * sets and prints once the whole tier has run; without the property (a run from an IDE) it goes to
 * standard output only.
 */
internal object KnownFailures {

    const val REPORT_PROPERTY = "cartogenesis.knownFailures"

    fun expect(finding: String, clause: () -> Unit) {
        try {
            clause()
        } catch (violation: GeometryViolation) {
            record(finding, violation.message ?: "")
            return
        }
        throw AssertionError("$finding fixed: arm this clause")
    }

    @Synchronized
    private fun record(finding: String, detail: String) {
        val line = "KNOWN FAILURE [$finding] ${detail.lines().first()}"
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
