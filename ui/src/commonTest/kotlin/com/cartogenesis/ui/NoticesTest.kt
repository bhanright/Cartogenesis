package com.cartogenesis.ui

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The generated notices, and the generated build info.
 *
 * Both are written by `ui/build.gradle.kts` from the build's own inputs, which means the failure
 * this guards against is not a wrong value but an *empty* one: a generator that ran, produced a
 * file with nothing in it, and left an About dialog that says the application is made of nothing
 * and licensed under nothing. That would compile.
 *
 * The three type faces are named explicitly because their notices are a licence requirement rather
 * than a courtesy — the OFL asks that the licence travel with anything embedding the fonts, and
 * they are embedded in the desktop jar and the wasm bundle alike. IBM Plex Mono is the third, added
 * by F7 for the Matrix chrome: a face is embedded whether or not fourteen of the fifteen chromes
 * ever draw a glyph of it, so the notice is owed the moment the file is in the module.
 */
class NoticesTest {

    @Test
    fun `the notices name the three bundled faces and their licence`() {
        assertTrue(Notices.entries.isNotEmpty(), "the notices list is empty")
        val text = Notices.entries.joinToString("\n") { "${it.name} — ${it.licence}" }

        assertTrue(text.contains("Spectral"), "Spectral is embedded and must be named:\n$text")
        assertTrue(text.contains("IBM Plex Sans"), "IBM Plex Sans is embedded and must be named")
        assertTrue(text.contains("IBM Plex Mono"), "IBM Plex Mono is embedded and must be named")
        assertTrue(
            Notices.entries.count { it.licence.contains("Open Font License") } >= 3,
            "all three faces must carry the OFL"
        )
        // Named *and* licensed: an entry whose licence line had gone missing would still contain
        // the words above, and would still be a licence violation.
        listOf("Spectral", "IBM Plex Sans", "IBM Plex Mono").forEach { face ->
            val notice = Notices.entries.firstOrNull { it.name.startsWith(face) }
            assertTrue(notice != null, "$face has no notice at all")
            assertTrue(
                notice.licence.contains("Open Font License"),
                "$face is named but carries \"${notice.licence}\" rather than the OFL"
            )
        }
    }

    @Test
    fun `the notices are the dependency graph, not a hand-written handful`() {
        // Generated from what the build actually resolved, so the list is dozens of entries long
        // and includes the toolkit the interface is written in. A hand-typed list would be short
        // and would be wrong within a release.
        assertTrue(
            Notices.entries.size > 10,
            "only ${Notices.entries.size} notices: the dependency graph was not read"
        )
        val names = Notices.entries.joinToString(" ") { it.name }
        assertTrue(names.contains("compose"), "Compose is not named in the notices")
        assertTrue(names.contains("kotlin"), "the Kotlin libraries are not named in the notices")
        assertTrue(names.contains("lwjgl"), "LWJGL, which the desktop links, is not named")
        assertTrue(Notices.entries.all { it.licence.isNotBlank() }, "a notice has no licence line")
    }

    @Test
    fun `the build knows its own version, date and licence position`() {
        assertTrue(BuildInfo.VERSION.isNotBlank())
        assertTrue(
            Updates.parse(BuildInfo.VERSION) != null,
            "the generated version ${BuildInfo.VERSION} is not a version the update check can read"
        )
        // yyyy-mm-dd, which is what `LocalDate.toString` gives and what the About dialog prints.
        assertTrue(
            Regex("""\d{4}-\d{2}-\d{2}""").matches(BuildInfo.BUILD_DATE),
            "the build date is not a date: ${BuildInfo.BUILD_DATE}"
        )
        assertTrue(BuildInfo.LICENCE.isNotBlank(), "the About dialog would show no licence at all")
        assertTrue(BuildInfo.PROJECT_URL.startsWith("https://"))
    }
}
