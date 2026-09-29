package com.cartogenesis.ui

import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The question a large link asks before it makes anything: which links ask it, what "open at the
 * default size" opens, and that the time it quotes is a measured one or none.
 *
 * The window that asks it is `WorldLinkDesktopTest`'s; this is the reading and the wording, in
 * common code so it runs in the browser that asks it most.
 */
class LargeLinksTest {

    private val base = WorldLinks.PUBLIC_APP_ADDRESS
    private val starting = Knobs.atResolution(WorldGenConfig(seed = 5L), 512)
    private val browserDefault = 512
    private val desktopDefault = 1024
    private val format = "v=${WorldLinks.FORMAT_VERSION}"

    private fun read(address: String, ceiling: Int = WorldCeilings.BROWSER_TAB): LinkOpening =
        WorldLinks.read(address, starting, RenderOptions(), ceiling)

    private val browser = object : FakePlatform() {
        override val hostName: String = "Browser"
    }

    @Test
    fun `a link above the default asks, and one at or below it or naming no size does not`() {
        assertTrue(LargeLinks.asks(read("$base?seed=7#$format&size=1024"), browserDefault))
        assertFalse(LargeLinks.asks(read("$base?seed=7#$format&size=512"), browserDefault))
        assertFalse(LargeLinks.asks(read("$base?seed=7#$format&size=1024"), desktopDefault))
        assertTrue(LargeLinks.asks(read("$base?seed=7#$format&size=2048", WorldCeilings.DESKTOP), desktopDefault))
        // A seed alone opens at the window's own size, which the reader chose.
        assertFalse(LargeLinks.asks(read("$base?seed=7"), browserDefault))
        // A size that is not one of the chips is set aside, so nothing large is made.
        assertFalse(LargeLinks.asks(read("$base?seed=7#$format&size=3000"), browserDefault))
        // A link refused whole makes nothing at all.
        assertFalse(LargeLinks.asks(read("$base?seed=7#v=9&size=2048"), browserDefault))
        // A link above the browser's ceiling asks about what it will actually make there.
        val tooLarge = read("$base?seed=7#$format&size=2048")
        assertEquals(1024, tooLarge.linkedSize)
        assertTrue(LargeLinks.asks(tooLarge, browserDefault))
    }

    @Test
    fun `open at the default size opens the link's world with only the size changed`() {
        val settings = "plates=9&tilt=21.5&realms=5&style=vellum"
        val atLinkSize = read("$base?seed=718106#$format&size=1024&$settings")
        val atDefault = WorldLinks.readAtSize(
            "$base?seed=718106#$format&size=1024&$settings", starting, RenderOptions(),
            WorldCeilings.BROWSER_TAB, size = browserDefault
        )
        // The same link written at the default size, read the ordinary way: the one path a
        // setting reaches a world by, which the answer has to match to the last field.
        val writtenAtDefault = read("$base?seed=718106#$format&size=$browserDefault&$settings")
        assertEquals(writtenAtDefault.config, atDefault.config)
        assertEquals(writtenAtDefault.options, atDefault.options)
        assertEquals(browserDefault, Knobs.sizeOf(atDefault.config))
        assertEquals(1024, Knobs.sizeOf(atLinkSize.config))
        assertEquals(2048, atLinkSize.config.width)
        assertEquals(9, Knobs.plates.read(atDefault.config))
        assertEquals(atLinkSize.options, atDefault.options)
        assertNotEquals(atLinkSize.config, atDefault.config)
        assertNull(atDefault.notice)
    }

    @Test
    fun `the default answer to a link above the ceiling does not say the link was brought down`() {
        val address = "$base?seed=7#$format&size=4096&glaciers=1"
        val atDefault = WorldLinks.readAtSize(
            address, starting, RenderOptions(), WorldCeilings.BROWSER_TAB, size = browserDefault
        )
        val notice = atDefault.notice.orEmpty()
        assertFalse("made at" in notice, notice)
        // What was set aside is still said: that is about the link, not the size.
        assertTrue("\"glaciers\" is not a setting a link carries" in notice, notice)
    }

    @Test
    fun `the question quotes a timed or estimated figure where there is one and says so where there is none`() {
        val phone = LargeLinks.question(1024, browserDefault, GenerationHost.of(browser, compact = true))
        println("LARGE LINK phone: $phone")
        assertTrue("This link makes a 1024 world" in phone, phone)
        assertTrue("expected to take about" in phone && "not been measured there" in phone, phone)
        assertTrue("may be quicker or slower" in phone, phone)
        assertTrue("pause" in phone, phone)

        val desktopBrowser = LargeLinks.question(1024, browserDefault, GenerationHost.of(browser, compact = false))
        println("LARGE LINK desktop browser: $desktopBrowser")
        assertTrue("took about" in desktopBrowser && "when it was measured" in desktopBrowser, desktopBrowser)

        val unmeasured = LargeLinks.question(2048, browserDefault, GenerationHost.DESKTOP_BROWSER)
        println("LARGE LINK unmeasured: $unmeasured")
        assertTrue("has not been measured" in unmeasured, unmeasured)
        assertFalse("about" in unmeasured, unmeasured)

        val desktop = LargeLinks.question(2048, desktopDefault, GenerationHost.of(FakePlatform(), compact = true))
        println("LARGE LINK desktop: $desktop")
        assertTrue("the desktop application" in desktop && "when it was measured" in desktop, desktop)
        assertFalse("pause" in desktop, "the desktop's window does not pause: $desktop")

        val phoneLine = LargeLinks.phoneCostLine()
        println("LARGE LINK phone's small print: $phoneLine")
        assertTrue("1024 is expected to take about" in phoneLine, phoneLine)
    }

    @Test
    fun `every timed figure is for a size a host asks about, once, with its source`() {
        val defaults = mapOf(
            GenerationHost.DESKTOP_APP to desktopDefault,
            GenerationHost.DESKTOP_BROWSER to browserDefault,
            GenerationHost.PHONE_BROWSER to browserDefault
        )
        // The desktop's highest ceiling, a machine whose heap holds 4096 rows: the one it asks at.
        val ceilings = mapOf(
            GenerationHost.DESKTOP_APP to WorldCeilings.LARGEST_DESKTOP,
            GenerationHost.DESKTOP_BROWSER to WorldCeilings.BROWSER_TAB,
            GenerationHost.PHONE_BROWSER to WorldCeilings.BROWSER_TAB
        )
        for (row in LargeLinks.MEASURED) {
            assertTrue(row.sizeRows in Knobs.RESOLUTIONS, "${row.sizeRows} is not a size a link carries")
            assertTrue(row.sizeRows > defaults.getValue(row.host), "${row.host} never asks about ${row.sizeRows}")
            assertTrue(row.sizeRows <= ceilings.getValue(row.host), "${row.host} cannot make ${row.sizeRows}")
            assertTrue(row.about.startsWith("about "), "not worded as an estimate: ${row.about}")
            assertTrue(row.source.isNotBlank(), "no source for ${row.sizeRows} on ${row.host}")
            if (row.estimated) {
                assertTrue("estimated" in row.source, "an estimate whose source does not say how: ${row.source}")
            }
        }
        val keys = LargeLinks.MEASURED.map { it.sizeRows to it.host }
        assertEquals(keys.distinct(), keys, "a size is timed twice for one host")
        // Every size a host asks about and can make has a figure, timed or estimated.
        defaults.forEach { (host, defaultSize) ->
            Knobs.RESOLUTIONS.filter { it > defaultSize && it <= ceilings.getValue(host) }.forEach { size ->
                assertTrue(LargeLinks.measured(size, host) != null, "$host asks about $size and has no figure for it")
            }
        }
    }
}
