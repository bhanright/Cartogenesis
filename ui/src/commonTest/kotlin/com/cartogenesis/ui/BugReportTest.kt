package com.cartogenesis.ui

import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a bug report carries, for a world whose numbers are known.
 *
 * The report exists so that a reader does not have to copy the seed correctly, so the assertions
 * are about exactly that: that the world's own numbers are in the text, that a setting the reader
 * moved is named with the value they moved it to, and that the URL GitHub is sent to still parses
 * back into those numbers after the encoding. The length limit is checked on a report built to be
 * as long as one can get — every knob moved — because that is the case that would silently lose
 * half a URL to a proxy.
 */
class BugReportTest {

    /** A world with three knobs moved off their defaults, and nothing else touched. */
    private fun world(): WorldGenConfig = WorldGenConfig(seed = 718106L)
        .atResolution(1024, 1024)
        .let { Knobs.plates.set(it, 18) }
        .let { Knobs.seasonalTiltDegrees.set(it, 25f) }
        .let { Knobs.ice.set(it, false) }

    private fun report(
        config: WorldGenConfig = world(),
        options: RenderOptions = RenderOptions()
    ): BugReport.Report = BugReport.of(
        version = "3.0.1",
        host = "Desktop",
        world = "Ashenmoor",
        config = config,
        options = options,
        acceleration = BugReport.accelerationLine(on = true, device = "a fake graphics card")
    )

    /** `name=value&name=value`, decoded, which is what GitHub's form reads. */
    private fun parameters(url: String): Map<String, String> =
        url.substringAfter('?').split('&').associate { pair ->
            decode(pair.substringBefore('=')) to decode(pair.substringAfter('='))
        }

    private fun decode(value: String): String {
        val bytes = mutableListOf<Byte>()
        var at = 0
        while (at < value.length) {
            val char = value[at]
            if (char == '%' && at + 2 < value.length) {
                bytes += value.substring(at + 1, at + 3).toInt(16).toByte()
                at += 3
            } else {
                bytes += char.code.toByte()
                at += 1
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    @Test
    fun `the report names the world, the seed, the grid and the build`() {
        val text = report().text
        listOf(
            "Cartogenesis 3.0.1",
            "Platform: Desktop",
            "World: Ashenmoor",
            "Seed: 718106",
            "Working resolution: 1024 × 1024",
            "Ocean coverage: 62%",
            "Graphics acceleration: On, a fake graphics card"
        ).forEach { line ->
            assertTrue(line in text, "the report does not say \"$line\":\n$text")
        }
        println("BUG REPORT for a known world:\n$text")
    }

    @Test
    fun `every knob that was moved is named with its value, and no other`() {
        val changed = BugReport.changedFromDefaults(world(), RenderOptions())
        assertEquals(
            listOf("Plates 18", "Seasonal tilt 25°", "Ice sheets and glaciers off"),
            changed,
            "the report's list of changed settings is not the three that were changed"
        )
        assertTrue(
            BugReport.changedFromDefaults(WorldGenConfig(), RenderOptions()).isEmpty(),
            "an untouched world reports settings as changed"
        )
        assertTrue(
            BugReport.NOTHING_CHANGED in report(config = WorldGenConfig()).text,
            "a report of an untouched world leaves its settings line blank"
        )
    }

    @Test
    fun `a mark on the drawing counts as a moved setting too`() {
        val changed = BugReport.changedFromDefaults(
            WorldGenConfig(),
            Knobs.graticule.set(RenderOptions(), true)
        )
        assertEquals(listOf("Graticule on"), changed)
    }

    @Test
    fun `the URL opens the bug form with the world's own numbers in it`() {
        val report = report()
        val fields = parameters(report.url)

        assertTrue(
            report.url.startsWith("${BugReport.NEW_ISSUE_URL}?"),
            "the report opens ${report.url}, which is not a new issue on this repository"
        )
        assertEquals(BugReport.TEMPLATE, fields["template"], "the URL asks for the wrong form")
        assertEquals("718106", fields["seed"])
        assertEquals("1024 × 1024", fields["resolution"])
        assertEquals("Desktop", fields["platform"])
        assertEquals("3.0.1", fields["version"])
        assertEquals("62%", fields["ocean"])
        assertEquals("Plates 18; Seasonal tilt 25°; Ice sheets and glaciers off", fields["knobs"])
        assertTrue(
            fields.getValue("title").contains("718106"),
            "the issue's own title does not name the seed: ${fields["title"]}"
        )
        println("BUG REPORT URL (${report.url.length} characters): ${report.url}")
    }

    /**
     * The longest report there is: every knob moved, and a world named to the length the name
     * field allows.
     *
     * It is well under the limit, which is the answer this test wants — and if a later chunk adds
     * enough settings to take it over, the trimming below is what holds, not the assertion.
     */
    @Test
    fun `the URL stays under the limit, and says where it was trimmed`() {
        val everything = Knobs.all.fold(WorldGenConfig(seed = Long.MAX_VALUE)) { config, knob ->
            when (knob) {
                is Dial -> knob.set(config, knob.range.endInclusive)
                is Stepper -> knob.set(config, knob.range.last)
                is Latch -> knob.set(config, !knob.read(WorldGenConfig()))
                is Mark -> config
            }
        }
        val marks = Knobs.all.filterIsInstance<Mark>().fold(RenderOptions()) { options, mark ->
            mark.set(options, !mark.read(RenderOptions()))
        }
        // The longest name the header will take, so the report is as long as one can be made.
        val report = BugReport.of(
            version = "3.0.1",
            host = "Desktop",
            world = "W".repeat(MAX_WORLD_NAME_LENGTH),
            config = everything,
            options = marks,
            acceleration = BugReport.accelerationLine(true, "a fake graphics card")
        )

        assertTrue(
            report.changedSettings.size > 10,
            "the worst case moved only ${report.changedSettings.size} settings"
        )
        assertTrue(
            report.url.length <= BugReport.MAX_URL_CHARACTERS,
            "the worst report's URL is ${report.url.length} characters, over the " +
                "${BugReport.MAX_URL_CHARACTERS} a proxy will carry"
        )
        println(
            "BUG REPORT worst case: ${report.changedSettings.size} settings moved, " +
                "${report.url.length} of ${BugReport.MAX_URL_CHARACTERS} characters"
        )

        // And that the trimming does happen when a report is longer than the limit allows: the
        // list gives way from the end, the seed does not, and what is left says so.
        val overlong = BugReport.Report(
            version = report.version,
            host = report.host,
            world = report.world,
            seed = report.seed,
            cellsAcross = report.cellsAcross,
            cellsDown = report.cellsDown,
            oceanShareOfWorld = report.oceanShareOfWorld,
            acceleration = report.acceleration,
            changedSettings = List(400) { "A setting nobody has, number $it" }
        )
        val fields = parameters(overlong.url)
        assertTrue(
            overlong.url.length <= BugReport.MAX_URL_CHARACTERS,
            "an overlong settings list was not trimmed: ${overlong.url.length} characters"
        )
        assertEquals(overlong.seed.toString(), fields["seed"], "the seed was trimmed away")
        assertTrue(
            fields.getValue("knobs").contains("more, on the clipboard"),
            "the trimmed list does not say that the whole of it is on the clipboard: " +
                fields["knobs"]
        )
    }

    @Test
    fun `the acceleration line says what the panel says`() {
        assertEquals("On, a device", BugReport.accelerationLine(on = true, device = "a device"))
        assertEquals(
            "Off. Available here: a device",
            BugReport.accelerationLine(on = false, device = "a device")
        )
        assertEquals(
            "Off. No graphics device here.",
            BugReport.accelerationLine(on = false, device = null)
        )
    }
}
