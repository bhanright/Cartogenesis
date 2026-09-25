package com.cartogenesis.web

import com.cartogenesis.ui.WorldCeilings
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The browser stops at 2048, whatever shape the window is.
 *
 * A 4096 generation killed a desktop browser's tab before anything was drawn (see
 * [WorldCeilings.BROWSER_TAB]), so the ceiling is the browser's and not the phone's: it takes no
 * window shape at all, and a test that asked it about one would be asking about the old rule. The
 * widest save the browser's libraries and uploads open is the same number, so a world the tab
 * could not make is not one it is handed either.
 */
class BrowserCeilingTest {

    @Test
    fun `the browser's ceiling is 2048, compact or not, and its libraries open no wider`() {
        val platform = WebPlatform(accelerator = null, accelerationUnavailableBecause = "a test")
        assertEquals(2048, platform.generationCeiling)
        assertEquals(WorldCeilings.BROWSER_TAB, platform.generationCeiling)
        assertEquals(platform.generationCeiling, BROWSER_OPENING_LIMIT.largestSide)
    }
}
