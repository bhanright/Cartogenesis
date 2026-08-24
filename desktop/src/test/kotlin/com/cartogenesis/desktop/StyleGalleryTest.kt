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
        // Symbols on, since they are half of what the drawn styles are for.
        val withSymbols = true
        MapStyle.entries.forEach { style ->
            val bitmap = MapImage.toBitmap(
                world,
                RenderOptions(view = MapView.FANTASY, style = style, showSymbols = withSymbols)
            )
            val data = Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)!!
            val suffix = if (withSymbols) "-symbols" else ""
            File(dir, "style-${style.name.lowercase()}$suffix.png").writeBytes(data.bytes)

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
}
