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
    private val starting = WorldGenConfig(seed = 5L, width = 512, height = 512)
    private val browserDefault = 512
    private val desktopDefault = 1024

    private fun read(address: String, ceiling: Int = WorldCeilings.BROWSER_TAB): LinkOpening =
        WorldLinks.read(address, starting, RenderOptions(), ceiling)

    private val browser = object : FakePlatform() {
        override val hostName: String = "Browser"
    }

    @Test
    fun `a link above the default asks, and one at or below it or naming no size does not`() {
        assertTrue(LargeLinks.asks(read("$base?seed=7#v=1&size=2048"), browserDefault))
        assertTrue(LargeLinks.asks(read("$base?seed=7#v=1&size=1024"), browserDefault))
        assertFalse(LargeLinks.asks(read("$base?seed=7#v=1&size=512"), browserDefault))
        assertFalse(LargeLinks.asks(read("$base?seed=7#v=1&size=1024"), desktopDefault))
        // A seed alone opens at the window's own size, which the reader chose.
        assertFalse(LargeLinks.asks(read("$base?seed=7"), browserDefault))
        // A size that is not one of the chips is set aside, so nothing large is made.
        assertFalse(LargeLinks.asks(read("$base?seed=7#v=1&size=3000"), browserDefault))
        // A link refused whole makes nothing at all.
        assertFalse(LargeLinks.asks(read("$base?seed=7#v=9&size=2048"), browserDefault))
        // A link above the browser's ceiling asks about what it will actually make there.
        val tooLarge = read("$base?seed=7#v=1&size=4096")
        assertEquals(2048, tooLarge.linkedSize)
        assertTrue(LargeLinks.asks(tooLarge, browserDefault))
    }

    @Test
    fun `open at the default size opens the link's world with only the size changed`() {
        val settings = "plates=9&tilt=21.5&realms=5&style=vellum"
        val atLinkSize = read("$base?seed=718106#v=1&size=2048&$settings")
        val atDefault = WorldLinks.readAtSize(
            "$base?seed=718106#v=1&size=2048&$settings", starting, RenderOptions(),
            WorldCeilings.BROWSER_TAB, size = browserDefault
        )
        // The same link written at the default size, read the ordinary way: the one path a
        // setting reaches a world by, which the answer has to match to the last field.
        val writtenAtDefault = read("$base?seed=718106#v=1&size=$browserDefault&$settings")
        assertEquals(writtenAtDefault.config, atDefault.config)
        assertEquals(writtenAtDefault.options, atDefault.options)
        assertEquals(browserDefault, atDefault.config.width)
        assertEquals(2048, atLinkSize.config.width)
        assertEquals(9, Knobs.plates.read(atDefault.config))
        assertEquals(atLinkSize.options, atDefault.options)
        assertNotEquals(atLinkSize.config, atDefault.config)
        assertNull(atDefault.notice)
    }

    @Test
    fun `the default answer to a link above the ceiling does not say the link was brought down`() {
        val address = "$base?seed=7#v=1&size=4096&glaciers=1"
        val atDefault = WorldLinks.readAtSize(
            address, starting, RenderOptions(), WorldCeilings.BROWSER_TAB, size = browserDefault
        )
        val notice = atDefault.notice.orEmpty()
        assertFalse("made at" in notice, notice)
        // What was set aside is still said: that is about the link, not the size.
        assertTrue("\"glaciers\" is not a setting a link carries" in notice, notice)
    }

    @Test
    fun `the question quotes a measured time where there is one and says so where there is none`() {
        val phone = LargeLinks.question(2048, browserDefault, GenerationHost.of(browser, compact = true))
        println("LARGE LINK phone: $phone")
        assertTrue("This link makes a 2048 world" in phone, phone)
        assertTrue("about a minute and a half when it was measured" in phone, phone)
        assertTrue("may be quicker or slower" in phone, phone)
        assertTrue("pause" in phone, phone)

        val desktopBrowser = LargeLinks.question(2048, browserDefault, GenerationHost.of(browser, compact = false))
        assertTrue("about four minutes" in desktopBrowser, desktopBrowser)

        val unmeasured = LargeLinks.question(1024, browserDefault, GenerationHost.DESKTOP_BROWSER)
        println("LARGE LINK unmeasured: $unmeasured")
        assertTrue("has not been measured" in unmeasured, unmeasured)
        assertFalse("about" in unmeasured, unmeasured)

        val desktop = LargeLinks.question(2048, desktopDefault, GenerationHost.of(FakePlatform(), compact = true))
        assertTrue("the desktop application" in desktop, desktop)
        assertFalse("pause" in desktop, "the desktop's window does not pause: $desktop")
    }

    @Test
    fun `every measured time is for a size a host asks about, once, with its source`() {
        val defaults = mapOf(
            GenerationHost.DESKTOP_APP to desktopDefault,
            GenerationHost.DESKTOP_BROWSER to browserDefault,
            GenerationHost.PHONE_BROWSER to browserDefault
        )
        val ceilings = mapOf(
            GenerationHost.DESKTOP_APP to WorldCeilings.DESKTOP,
            GenerationHost.DESKTOP_BROWSER to WorldCeilings.BROWSER_TAB,
            GenerationHost.PHONE_BROWSER to WorldCeilings.BROWSER_TAB
        )
        for (row in LargeLinks.MEASURED) {
            assertTrue(row.sizeCells in Knobs.RESOLUTIONS, "${row.sizeCells} is not a size a link carries")
            assertTrue(row.sizeCells > defaults.getValue(row.host), "${row.host} never asks about ${row.sizeCells}")
            assertTrue(row.sizeCells <= ceilings.getValue(row.host), "${row.host} cannot make ${row.sizeCells}")
            assertTrue(row.about.startsWith("about "), "not worded as an estimate: ${row.about}")
            assertTrue(row.source.isNotBlank(), "no source for ${row.sizeCells} on ${row.host}")
        }
        val keys = LargeLinks.MEASURED.map { it.sizeCells to it.host }
        assertEquals(keys.distinct(), keys, "a size is measured twice for one host")
    }
}
