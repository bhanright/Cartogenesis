package com.cartogenesis.desktop

import com.cartogenesis.cartography.MapStyle
import com.cartogenesis.cartography.MapView
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.ui.MapImage
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * Every style, on one world, written out to be looked at.
 *
 * A style is a claim about appearance and there is no numeric test for whether a map looks like
 * aged vellum. What can be checked without eyes is that each style actually produces a *different*
 * picture — a style that silently falls back to the default is the likely failure here, and it
 * would be invisible in any test that only asked whether rendering succeeded.
 */
class StyleGalleryTest {

    @Test
    fun `every style renders, and none of them look alike`() {
        val dir = File("build/styles").apply { mkdirs() }
        val world = WorldGenerationEngine.generateBlocking(
            WorldGenConfig(seed = 234475L, width = 512, height = 512).atResolution(1024, 1024)
        )

        val fingerprints = mutableMapOf<MapStyle, Int>()
        MapStyle.entries.forEach { style ->
            val bitmap = MapImage.toBitmap(
                world,
                RenderOptions(view = MapView.FANTASY, style = style)
            )
            val data = Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)!!
            File(dir, "style-${style.name.lowercase()}.png").writeBytes(data.bytes)

            // Cheap content hash: enough to tell two styles apart, and it costs nothing.
            val pixels = bitmap.readPixels()!!
            var hash = 17
            for (k in pixels.indices step 997) hash = hash * 31 + pixels[k]
            fingerprints[style] = hash
            bitmap.close()
            println("STYLE ${style.label}: ${style.detail}")
        }

        val distinct = fingerprints.values.toSet().size
        assertTrue(
            distinct == MapStyle.entries.size,
            "two styles rendered identically: $fingerprints"
        )
        println("STYLE wrote ${MapStyle.entries.size} maps to ${dir.absolutePath}")
    }

    /**
     * F6's style, on its own, at the size the spec asks to look at it in.
     *
     * Two of the fifteen views are what the style is *for* — the fantasy view, where the land ramp
     * and the flat sea are the whole of it, and the political view, which is the worst case colour
     * vision has in this application and the only one with a realm set behind it — so those two are
     * written out to be reviewed. The other thirteen are rendered as well and thrown away, because
     * a style that cannot describe a view fails by throwing rather than by looking wrong, and the
     * diagnostic views are supposed to *ignore* it: the assertion below is that they still do.
     */
    @Test
    fun `the colour-blind style renders every view, and leaves the diagnostic ones alone`() {
        val dir = File("build/styles").apply { mkdirs() }
        val world = WorldGenerationEngine.generateBlocking(
            WorldGenConfig(seed = 234475L, width = 512, height = 512)
        )

        MapView.entries.forEach { view ->
            val clear = MapImage.toBitmap(world, RenderOptions(view = view, style = MapStyle.CLEAR))
            if (view == MapView.FANTASY || view == MapView.POLITICAL) {
                val data = Image.makeFromBitmap(clear).encodeToData(EncodedImageFormat.PNG)!!
                File(dir, "clear-${view.name.lowercase()}.png").writeBytes(data.bytes)
            }
            clear.close()

            if (view.showsTerrain) return@forEach
            // A diagnostic view's colours mean a temperature, a plate, a wind: they are read
            // against a legend and a style must not touch them. Compared with the passes that are
            // *not* the colours switched off — the coastline is inked in the style's own ink on
            // every view, and the relief is raked by the style's own strength — so what this asks
            // is exactly the question worth asking: is the palette the same? Atlas and Colour-blind
            // are as far apart as two styles in this list get; if these two agree, nothing differs.
            val bare = RenderOptions(
                view = view, showCoastline = false, showHillshade = false, showLakes = false,
                // Rivers too: they are drawn over every view that is not a flow field, in the
                // style's own ink, and Colour-blind's is white where Atlas's is blue.
                showRivers = false
            )
            val underClear = MapImage.toBitmap(world, bare.copy(style = MapStyle.CLEAR))
            val underAtlas = MapImage.toBitmap(world, bare.copy(style = MapStyle.ATLAS))
            assertTrue(
                underClear.readPixels()!!.contentEquals(underAtlas.readPixels()!!),
                "${view.label} came out in different colours under the Colour-blind style"
            )
            underClear.close()
            underAtlas.close()
        }
        println(
            "STYLE wrote the Colour-blind fantasy and political views at 512 to ${dir.absolutePath}"
        )
    }
}
