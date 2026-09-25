package com.cartogenesis.desktop

import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.ui.Platform
import com.cartogenesis.ui.WorldLinks
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop's half of a link: it is no page, so the link it copies is the published browser
 * application's, which opens for anybody the link is sent to; and it was opened at no address, so
 * it starts as it always has.
 */
class WorldLinkDesktopTest {

    /**
     * The desktop platform with a clipboard that records. The tests run headless, where AWT's
     * clipboard throws, so the copy is caught here rather than on the machine's own clipboard.
     */
    private class Recording(private val desktop: Platform = DesktopPlatform()) : Platform by desktop {
        val copied = mutableListOf<String>()
        override val canCopyToClipboard: Boolean = true
        override fun copyToClipboard(text: String) {
            copied += text
        }
    }

    @Test
    fun `a link copied on the desktop begins with the published address`() {
        val platform = Recording()
        val line = WorldLinks.copy(platform, WorldGenConfig(seed = 718106L).atResolution(1024, 1024), RenderOptions())
        val link = platform.copied.single()
        println("WORLD LINK copied on the desktop: $link")
        assertTrue(link.startsWith("https://cartogenesis.com/app/?seed=718106#"), link)
        assertEquals(WorldLinks.PUBLIC_APP_ADDRESS, DesktopPlatform().worldLinkBase)
        assertTrue(link in line, line)
    }

    @Test
    fun `the desktop was opened at no address`() {
        assertNull(DesktopPlatform().openedAt)
    }
}
