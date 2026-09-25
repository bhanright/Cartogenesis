package com.cartogenesis.web

import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.ui.Platform
import com.cartogenesis.ui.WorldLinks
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@JsFun("() => String(location.origin)")
private external fun pageOrigin(): String

@JsFun("() => String(location.href)")
private external fun pageHref(): String

/** Moves the page's address without loading anything, as a link arriving would have set it. */
@JsFun("(address) => { history.replaceState(null, '', address); }")
private external fun replaceAddress(address: String)

/**
 * The browser's half of a link: the page reads the address it was opened at, and a link copied in
 * a tab begins with that tab's own page rather than the published one.
 *
 * What the application makes of the address is `WorldLinksTest`'s, which runs in this browser too;
 * what is held here is only what a browser supplies, and it is read from the real `location`.
 */
class WorldLinkAddressTest {

    /** The page's platform with a clipboard that records, since a test page cannot read its own. */
    private class Recording(private val page: WebPlatform) : Platform by page {
        val copied = mutableListOf<String>()
        override val canCopyToClipboard: Boolean = true
        override fun copyToClipboard(text: String) {
            copied += text
        }
    }

    private fun page(): WebPlatform = WebPlatform(accelerator = null, accelerationUnavailableBecause = "a test")

    @Test
    fun `a link copied in the browser begins with the page's own origin`() {
        val platform = Recording(page())
        WorldLinks.copy(platform, WorldGenConfig(seed = 718106L), RenderOptions())
        val link = platform.copied.single()
        println("WORLD LINK copied in the browser: $link")
        assertTrue(link.startsWith(pageOrigin() + "/"), "$link does not begin with ${pageOrigin()}")
        assertFalse(link.startsWith(WorldLinks.PUBLIC_APP_ADDRESS), "the browser copied the published address")
        assertTrue(link.startsWith(page().worldLinkBase + "?seed=718106#"), link)
    }

    @Test
    fun `the page reads the whole address it was opened at, and its link base carries none of it`() {
        val before = pageHref()
        try {
            replaceAddress("?seed=718106#v=1&plates=18")
            val platform = page()
            assertEquals(pageHref(), platform.openedAt)
            assertTrue(platform.openedAt.endsWith("?seed=718106#v=1&plates=18"), platform.openedAt)
            assertFalse('?' in platform.worldLinkBase || '#' in platform.worldLinkBase, platform.worldLinkBase)
        } finally {
            replaceAddress(before)
        }
    }
}
