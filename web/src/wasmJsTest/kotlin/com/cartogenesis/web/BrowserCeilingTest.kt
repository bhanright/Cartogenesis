package com.cartogenesis.web

import com.cartogenesis.ui.WorldCeilings
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The browser stops at 1024 rows, whatever shape the window is.
 *
 * A world of 2048 rows is more than a browser tab holds (see [WorldCeilings.BROWSER_TAB]), so the
 * ceiling is the browser's and not the phone's: it takes no window shape at all, and a test that
 * asked it about one would be asking about the old rule. The most rows a save the browser's
 * libraries and uploads open may have is the same number, so a world the tab could not make is not
 * one it is handed either.
 */
class BrowserCeilingTest {

    @Test
    fun `the browser's ceiling is 1024 rows, compact or not, and its libraries open no more rows`() {
        val platform = WebPlatform(accelerator = null, accelerationUnavailableBecause = "a test")
        assertEquals(1024, platform.generationCeiling)
        assertEquals(WorldCeilings.BROWSER_TAB, platform.generationCeiling)
        assertEquals(platform.generationCeiling, BROWSER_OPENING_LIMIT.largestRows)
        assertEquals(512, platform.defaultResolution)
    }
}
