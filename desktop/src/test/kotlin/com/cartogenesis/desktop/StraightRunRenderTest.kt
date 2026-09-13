package com.cartogenesis.desktop

import com.cartogenesis.cartography.MapRasterizer
import com.cartogenesis.cartography.MapSheet
import com.cartogenesis.cartography.MapStyle
import com.cartogenesis.cartography.MapView
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.ui.MapImage
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * F18 at the two sizes the author looks at his worlds, drawn under both routing rules.
 *
 * The census in `:worldgen`'s `StraightRunTest` says the ruled bar is gone. What a census cannot
 * say is whether the rivers elsewhere are still the same rivers, which is the thing that would sink
 * this chunk: a rule that touches every cell of every drainage on every world has to leave the map
 * looking like the same map. So this writes the pair — the whole sheet and the window the bar sat
 * in, at 1:1 — and the report says what they showed.
 *
 * In the audit tier beside `GeneralisationRenderTest`, and for the same reason: four worlds, two of
 * them at 2048, is minutes of generation for pictures only a person can judge.
 */
class StraightRunRenderTest {

    private companion object {
        /** William's own world at the size the bar was found at, and the author's own at export. */
        const val AUTHORS_SEED = 298405L
        const val AUTHORS_SIDE = 1024
        const val EXPORT_SIDE = 2048

        /** A 1:1 window big enough to hold the bar and the country either side of it. */
        const val CROP = 384

        /** Where F15 found the bar on 298405 at 1024: 53 cells centred here. */
        const val BAR_X = 503
        const val BAR_Y = 866
    }

    private fun authorsWorld(side: Int, byFacet: Boolean): WorldMap =
        WorldGenerationEngine.generateBlocking(
            WorldGenConfig(seed = AUTHORS_SEED, width = 512, height = 512)
                .atResolution(side, side)
                .copy(facetRouting = byFacet)
        )

    private fun siteWorld(side: Int, byFacet: Boolean): WorldMap {
        val base = WorldGenConfig(
            seed = SiteImagery.SEED, width = 512, height = 512, seaLevel = SiteImagery.SEA_LEVEL
        )
        return WorldGenerationEngine.generateBlocking(
            base.copy(
                tectonics = base.tectonics.copy(plateCount = SiteImagery.PLATES),
                nations = base.nations.copy(nationCount = SiteImagery.REALMS)
            ).atResolution(side, side).copy(facetRouting = byFacet)
        )
    }

    @Test
    fun `both worlds, both rules, whole and at the bar`() {
        val dir = File("build/f18-crops").apply { mkdirs() }
        val written = ArrayList<String>()
        val options = RenderOptions(view = MapView.FANTASY, style = MapStyle.ATLAS)
        // Chosen on the world drawn first and reused, so the pair frames the same ground.
        var riverWindow: Pair<Int, Int>? = null

        listOf(false to "before", true to "after").forEach { (byFacet, which) ->
            val authors = authorsWorld(AUTHORS_SIDE, byFacet)
            val sheet = MapImage.toBitmap(
                authors, options, MapRasterizer.rasterize(authors, options), MapSheet.SHEET
            )
            written += write(dir, "298405-1024-$which-whole.png", sheet)
            written += write(
                dir, "298405-1024-$which-bar.png",
                crop(sheet, BAR_X - CROP / 2, BAR_Y - CROP / 2, AUTHORS_SIDE)
            )
            sheet.close()

            val site = siteWorld(EXPORT_SIDE, byFacet)
            val exportSheet = MapImage.toBitmap(
                site, options, MapRasterizer.rasterize(site, options), MapSheet.SHEET
            )
            written += write(dir, "718106-2048-$which-whole.png", exportSheet)
            val window = riverWindow ?: busiestWindow(site, options, EXPORT_SIDE).also {
                riverWindow = it
            }
            written += write(
                dir, "718106-2048-$which-rivers.png",
                crop(exportSheet, window.first, window.second, EXPORT_SIDE)
            )
            exportSheet.close()
        }

        written.forEach { println("F18 CROP $it") }
        assertTrue(written.size == 8, "expected eight pictures, wrote ${written.size}")
    }

    /** The top-left corner of the [CROP]-square window with the most river in it. */
    private fun busiestWindow(map: WorldMap, options: RenderOptions, side: Int): Pair<Int, Int> {
        val rivers = MapRasterizer.overlay(map, options, MapSheet.SHEET).rivers
        var most = 0
        var at = (side - CROP) / 2 to (side - CROP) / 2
        var top = 0
        while (top <= side - CROP) {
            var left = 0
            while (left <= side - CROP) {
                val inside = rivers.count { segment ->
                    segment.x0 >= left && segment.x0 < left + CROP &&
                        segment.y0 >= top && segment.y0 < top + CROP
                }
                if (inside > most) {
                    most = inside
                    at = left to top
                }
                left += CROP / 4
            }
            top += CROP / 4
        }
        return at
    }

    private fun write(dir: File, name: String, bitmap: Bitmap): String {
        val data = Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)!!
        val file = File(dir, name)
        file.writeBytes(data.bytes)
        return file.absolutePath
    }

    /** A [CROP]-square window out of [bitmap] at 1:1, clamped to the sheet. */
    private fun crop(bitmap: Bitmap, left: Int, top: Int, side: Int): Bitmap {
        val fromLeft = left.coerceIn(0, side - CROP)
        val fromTop = top.coerceIn(0, side - CROP)
        val source = bitmap.readPixels() ?: error("could not read the rendered map back")
        val window = Bitmap()
        window.allocPixels(ImageInfo.makeS32(CROP, CROP, ColorAlphaType.PREMUL))
        val bytes = ByteArray(CROP * CROP * 4)
        for (row in 0 until CROP) {
            val from = ((fromTop + row) * bitmap.width + fromLeft) * 4
            source.copyInto(bytes, row * CROP * 4, from, from + CROP * 4)
        }
        window.installPixels(bytes)
        return window
    }
}
