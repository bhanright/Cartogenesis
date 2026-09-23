package com.cartogenesis.worldgen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The known-failure helper, shown catching a re-armed guard's own violation and nothing else.
 *
 * The same control `GeometryControlTest` runs on the geometry guard's helper in `:cartography`,
 * case for case, so that the twin is held to the same behaviour as the original.
 */
class KnownFailuresTest {

    @Test
    fun `the known-failure helper catches the guard's own violation and nothing else`() {
        val recorded = ArrayList<String>()
        val sink = { finding: String, detail: String -> recorded.add("$finding: $detail"); Unit }
        val stamp = Signature(1, 30, 40, 12.5)
        // A violation with the recorded signature is recorded under its finding, and the clause passes.
        KnownFailures.expect("control finding", stamp, { throw GuardViolation("a ruled bar", Signature(1, 31, 39, 12.6)) }, sink)
        assertEquals(listOf("control finding: a ruled bar"), recorded)
        // Another violation in the same slot fails: moved, grown, or joined by a second place.
        for (other in listOf(Signature(1, 90, 40, 12.5), Signature(1, 30, 40, 14.0), Signature(2, 30, 40, 12.5), null)) {
            val different = assertFailsWith<AssertionError> {
                KnownFailures.expect("control finding", stamp, { throw GuardViolation("another bar", other) }, sink)
            }
            assertTrue(different !is GuardViolation && different.message!!.contains("a different violation"), "$other passed as $stamp")
        }
        assertEquals(Signature.parse(stamp.toString()).toString(), stamp.toString(), "a signature reads back as written")
        // A clause that no longer fails fails the helper, naming the finding.
        val fixed = assertFailsWith<AssertionError> { KnownFailures.expect("control finding", stamp, { }, sink) }
        assertEquals("control finding fixed: arm this clause", fixed.message)
        // Everything else goes through untouched: too little data, another assertion, a setup error.
        assertFailsWith<InsufficientSample> {
            KnownFailures.expect("control finding", stamp, { throw InsufficientSample("three lakes") }, sink)
        }
        val other = assertFailsWith<AssertionError> {
            KnownFailures.expect("control finding", stamp, { assertEquals(1, 2) }, sink)
        }
        assertTrue(other !is GuardViolation && other.message?.contains("fixed") != true)
        assertFailsWith<IllegalStateException> {
            KnownFailures.expect("control finding", stamp, { error("the world did not generate") }, sink)
        }
        assertEquals(1, recorded.size, "only the violation was recorded")
    }

    @Test
    fun `a clause stated through the violation type throws it only past its bar`() {
        GuardViolation.unless(true, { Signature.unplaced(1, 0.5) }) { "not reached" }
        val thrown = assertFailsWith<GuardViolation> {
            GuardViolation.unless(false, { Signature.unplaced(3, 0.25) }) { "three seeds past the bar" }
        }
        assertEquals("three seeds past the bar", thrown.message)
        assertEquals("3@(-1,-1)=0.2500", thrown.signature.toString())
    }
}
