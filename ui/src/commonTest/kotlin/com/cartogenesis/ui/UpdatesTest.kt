package com.cartogenesis.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The update check, against fake answers, with no network anywhere near it.
 *
 * The five cases the spec names — older, same, newer, malformed, offline — plus the ones that
 * actually break version comparisons in the wild: a two-digit component (1.10.0 is *newer* than
 * 1.9.0, and a string comparison says the opposite), a tag with no `v`, a pre-release tag, and a
 * repository that has published something that is not a version at all.
 */
class UpdatesTest {

    private fun release(
        tag: String,
        name: String = "Cartogenesis $tag",
        body: String = "- A thing\n- Another thing",
        page: String = "https://github.com/bhanright/Cartogenesis/releases/tag/$tag",
        prerelease: Boolean = false
    ) = """
        {
          "tag_name": "$tag",
          "name": "$name",
          "body": "${body.replace("\n", "\\n")}",
          "html_url": "$page",
          "draft": false,
          "prerelease": $prerelease,
          "assets": [{"name": "Cartogenesis.msi"}]
        }
    """.trimIndent()

    @Test
    fun `a newer release is offered, with its notes and its page`() {
        val status = Updates.evaluate("1.2.0", release("v1.3.0"))
        val available = assertIs<Updates.Status.Available>(status)
        assertEquals("1.3.0", available.version)
        assertEquals("Cartogenesis v1.3.0", available.title)
        assertTrue(available.notes.contains("A thing"), "the notes were dropped")
        assertTrue(available.page.endsWith("v1.3.0"))
    }

    @Test
    fun `the same release is up to date`() {
        assertIs<Updates.Status.UpToDate>(Updates.evaluate("1.2.0", release("v1.2.0")))
    }

    @Test
    fun `an older release is up to date, not a downgrade`() {
        // What a development build sees: this build is ahead of anything published.
        assertIs<Updates.Status.UpToDate>(Updates.evaluate("1.3.0", release("v1.2.0")))
    }

    @Test
    fun `a malformed answer is reported, not thrown`() {
        assertIs<Updates.Status.Unknown>(Updates.evaluate("1.2.0", "not json at all"))
        assertIs<Updates.Status.Unknown>(Updates.evaluate("1.2.0", "{}"))
        assertIs<Updates.Status.Unknown>(Updates.evaluate("1.2.0", release("nightly")))
        assertIs<Updates.Status.Unknown>(Updates.evaluate("1.2.0", release("v1.x.0")))
    }

    @Test
    fun `offline is an answer rather than a failure`() {
        val status = Updates.evaluate("1.2.0", null)
        val unknown = assertIs<Updates.Status.Unknown>(status)
        assertTrue(
            unknown.reason.contains("reach", ignoreCase = true),
            "offline should say so plainly: ${unknown.reason}"
        )
    }

    @Test
    fun `a pre-release is never offered as the latest`() {
        assertIs<Updates.Status.UpToDate>(
            Updates.evaluate("1.2.0", release("v2.0.0", prerelease = true))
        )
    }

    @Test
    fun `versions compare numerically, component by component`() {
        // The case a string comparison gets wrong, and the reason this is not a string comparison.
        assertTrue(Updates.compareTags("1.10.0", "1.9.0")!! > 0)
        assertTrue(Updates.compareTags("v1.2.0", "1.2")!! == 0)
        assertTrue(Updates.compareTags("2.0.0", "1.99.99")!! > 0)
        assertTrue(Updates.compareTags("1.2.3", "1.2.4")!! < 0)
        // A pre-release tag is compared on its numbers, since this build never publishes one.
        assertEquals(0, Updates.compareTags("1.2.0-rc1", "1.2.0"))
        assertEquals(null, Updates.compareTags("1.2.0", "banana"))
        assertEquals(null, Updates.compareTags("1.2.3.4", "1.2.3"))
    }

    @Test
    fun `notes are trimmed to an opening rather than shown whole`() {
        val long = (1..40).joinToString("\n") { "- item $it" }
        val summary = Updates.summarise(long)
        assertEquals(6, summary.lines().size)
        assertTrue(summary.startsWith("• item 1"), "markdown bullets should be set as bullets")
        assertTrue(Updates.summarise(null).isNotBlank())
        assertTrue(Updates.summarise("   ").isNotBlank())
        assertTrue(Updates.summarise("## Heading\n\ntext").startsWith("Heading"))
    }

    @Test
    fun `the endpoint is the one the plan names`() {
        assertEquals(
            "https://api.github.com/repos/bhanright/Cartogenesis/releases/latest",
            Updates.LATEST_RELEASE_URL
        )
    }
}
