package com.cartogenesis.desktop

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.cartogenesis.ui.CartogenesisApp
import com.cartogenesis.ui.CartogenesisTheme
import com.cartogenesis.ui.Platform
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * The whole application, in both themes, written out to be looked at.
 *
 * `StyleGalleryTest` does this for the map; this does it for everything around the map, and for
 * the same reason. A theme is a claim about appearance and there is no numeric test for whether
 * chrome looks like ink on paper — but a screenshot can be looked at, by whoever is reviewing the
 * change and by whoever revisits it a year later, and a theme that quietly reverted to Material's
 * lilac would be obvious in one glance and invisible in every assertion anyone would think to
 * write.
 *
 * What *is* asserted is the little that can be: that both shots were produced at the size asked
 * for, that they differ from each other (a theme that ignored its `dark` argument would produce
 * two identical files), and that neither is a blank page.
 */
class ChromeGalleryTest {

    @Test
    fun `the whole interface renders in both themes`() {
        val dir = File("build/screens").apply { mkdirs() }

        val light = shoot(dark = false)
        val dark = shoot(dark = true)

        File(dir, "chrome-light.png").writeBytes(light.png)
        File(dir, "chrome-dark.png").writeBytes(dark.png)
        println("CHROME wrote two ${WIDTH}x$HEIGHT shots to ${dir.absolutePath}")
        println(
            "CHROME light fingerprint ${light.fingerprint} over ${light.distinctColours} colours, " +
                "dark ${dark.fingerprint} over ${dark.distinctColours}"
        )

        assertTrue(
            light.fingerprint != dark.fingerprint,
            "the light and dark themes rendered identically, so the theme is not being applied"
        )
        // A window drawn in one flat colour is what a failure to lay anything out looks like, and
        // it would still have written a plausible-looking file.
        assertTrue(light.distinctColours > 200, "the light shot is nearly blank")
        assertTrue(dark.distinctColours > 200, "the dark shot is nearly blank")
    }

    private class Shot(val png: ByteArray, val fingerprint: Int, val distinctColours: Int)

    /**
     * Composes the application at 1440x900, presses Generate, waits for the world, and captures.
     *
     * Pressing the button rather than handing the app a world is deliberate: since F0 the app opens
     * on a blank canvas, so this is also the only way to photograph it with a map in it, and it
     * exercises the same path a reader takes.
     */
    @OptIn(ExperimentalTestApi::class)
    private fun shoot(dark: Boolean): Shot {
        var shot: Shot? = null
        runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
            val platform = ChromePlatform()
            setContent {
                CartogenesisTheme(dark = dark) { CartogenesisApp(platform) }
            }
            onNodeWithText("Generate").performClick()
            // Only the finished status line reads "… N realms · M rivers". Waiting on the word
            // "rivers" alone would match the progress banner's "Carving rivers" and photograph a
            // half-drawn world, which is exactly what the first run of this test did.
            waitUntil(timeoutMillis = GENERATION_TIMEOUT_MS) {
                onAllNodesWithText("realms ·", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            waitForIdle()

            val bitmap = onRoot().captureToImage().asSkiaBitmap()
            val png = Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)!!.bytes
            val pixels = bitmap.readPixels()!!
            var hash = 17
            for (k in pixels.indices step 997) hash = hash * 31 + pixels[k]
            val colours = HashSet<Int>()
            // Every hundredth pixel, packed: enough to tell a drawn window from a filled one.
            for (k in 0 until pixels.size - 4 step 400) {
                colours += (pixels[k].toInt() and 0xFF shl 16) or
                    (pixels[k + 1].toInt() and 0xFF shl 8) or
                    (pixels[k + 2].toInt() and 0xFF)
            }
            shot = Shot(png, hash, colours.size)
        }
        return shot ?: error("the composition never produced a frame")
    }

    private companion object {
        const val WIDTH = 1440
        const val HEIGHT = 900

        /** Generous: this is a full 512 world on the CPU, on whatever machine is running the tests. */
        const val GENERATION_TIMEOUT_MS = 300_000L
    }
}

/**
 * The desktop, told to start small.
 *
 * Everything real — the library on disk, the graphics device, the export dialog — is the desktop's
 * own, so the shot shows the interface a reader actually gets, including whatever the machine says
 * about its graphics card. Only the working resolution is overridden, because 1024 is a slow world
 * to wait for in a test and 512 photographs identically.
 */
private class ChromePlatform(private val desktop: Platform = DesktopPlatform()) :
    Platform by desktop {
    override val defaultResolution: Int = 512
}
