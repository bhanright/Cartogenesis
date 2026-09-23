package com.cartogenesis.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * This module's [KnownFailures] twin, shown doing what `:cartography`'s does: it catches its own
 * [RecordedViolation] with the recorded signature and nothing else, fails on another signature,
 * and fails with "fixed: arm this clause" when the clause passes.
 */
class KnownFailuresTest {

    @Test
    fun `the known-failure helper catches its own violation with its signature and nothing else`() {
        val recorded = ArrayList<String>()
        val sink = { finding: String, detail: String -> recorded.add("$finding: $detail"); Unit }
        KnownFailures.expect("finding", "lakes as paper", { throw RecordedViolation("a style reached a view", "lakes as paper") }, sink)
        assertEquals(listOf("finding: a style reached a view"), recorded)

        val different = assertFailsWith<AssertionError> {
            KnownFailures.expect("finding", "lakes as paper", { throw RecordedViolation("a style reached a view", "lakes as slate") }, sink)
        }
        assertTrue(different !is RecordedViolation && different.message!!.contains("a different violation"))

        val fixed = assertFailsWith<AssertionError> { KnownFailures.expect("finding", "lakes as paper", { }, sink) }
        assertEquals("finding fixed: arm this clause", fixed.message)

        val other = assertFailsWith<AssertionError> { KnownFailures.expect("finding", "lakes as paper", { assertEquals(1, 2) }, sink) }
        assertTrue(other !is RecordedViolation && other.message?.contains("fixed") != true)
        assertFailsWith<IllegalStateException> { KnownFailures.expect("finding", "lakes as paper", { error("setup") }, sink) }
        assertEquals(1, recorded.size, "only the violation was recorded")
    }
}
