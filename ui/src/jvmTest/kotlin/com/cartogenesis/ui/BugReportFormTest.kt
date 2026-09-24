package com.cartogenesis.ui

import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.worldgen.model.WorldGenConfig
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The bug report's URL against the form it opens.
 *
 * GitHub pre-fills an issue form from query parameters named after the form's own field ids, and a
 * parameter naming a field the form does not have is dropped without a word: the form opens looking
 * normal, with the seed missing. [BugReport] spells the ids once and `bug.yml` spells them again, so
 * the two are read here and held to each other — the form's ids out of the yaml, the report's out
 * of a URL it builds. On the JVM only, because it reads a file of the repository.
 */
class BugReportFormTest {

    private fun repositoryFile(path: String): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle.kts").isFile) return File(dir, path)
            dir = dir.parentFile
        }
        fail("could not find the repository root from ${File(".").absolutePath}")
    }

    @Test
    fun `every field the report fills is a field of the form it opens`() {
        val form = repositoryFile(".github/ISSUE_TEMPLATE/${BugReport.TEMPLATE}")
        assertTrue(form.isFile, "the report opens ${BugReport.TEMPLATE}, which is not among the issue forms")
        val formIds = Regex("""(?m)^\s*id:\s*([A-Za-z0-9_-]+)\s*$""").findAll(form.readText())
            .map { it.groupValues[1] }.toSet()
        assertTrue(formIds.isNotEmpty(), "${form.name} declares no field ids")

        val url = BugReport.of(
            version = "3.2.0", host = "Desktop", world = "Ashenmoor",
            config = WorldGenConfig(seed = 7L, width = 512, height = 512), options = RenderOptions(),
            acceleration = BugReport.accelerationLine(on = false, device = null)
        ).url
        // `template` and `title` are GitHub's own parameters, not fields of the form.
        val filled = url.substringAfter('?').split('&').map { it.substringBefore('=') }
            .filterNot { it == "template" || it == "title" }
        assertTrue(filled.isNotEmpty(), "the report's URL fills no field at all")
        val strangers = filled.filterNot { it in formIds }
        assertTrue(
            strangers.isEmpty(),
            "the report fills $strangers, which ${form.name} does not have: GitHub drops them " +
                "without a word. The form's fields are $formIds"
        )
        println("BUG FORM the report fills ${filled.size} of the form's ${formIds.size} fields: $filled")
    }
}
