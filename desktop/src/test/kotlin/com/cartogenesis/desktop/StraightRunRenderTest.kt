package com.cartogenesis.desktop

import com.cartogenesis.cartography.MapRasterizer
import com.cartogenesis.cartography.MapSheet
import com.cartogenesis.cartography.MapStyle
import com.cartogenesis.cartography.MapView
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.SheetGeometry
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
        /** The author's own world at the size the bar was found at, and the author's own at export. */
        const val AUTHORS_SEED = 298405L
        const val AUTHORS_SIDE = 1024
        const val EXPORT_SIDE = 2048

        /**
         * A 1:1 window of the sheet, in its pixels, big enough to hold the bar and the country
         * either side of it.
         */
        const val CROP = 384

        /**
         * Where F15 found the bar on 298405 at 1024: 53 cells centred on this column and row of
         * the grid. Cells, not pixels: the window is placed on the sheet through [SheetGeometry],
         * so it frames the same ground whatever shape the sheet is drawn at.
         */
        const val BAR_COLUMN = 503
        const val BAR_ROW = 866

        /** Seed 99 at the size its stuck basin was measured on. */
        const val STUCK_SIDE = 512

        /**
         * How far apart, in sheet pixels, a window's water is sampled each way: a sixteenth of the
         * pixels is enough to rank windows, which only have to find the basin, not measure it.
         */
        const val WINDOW_SAMPLE_PIXELS = 4
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
                authors, options, MapRasterizer.rasterize(authors, options), MapSheet.UNGENERALISED
            )
            written += write(dir, "298405-1024-$which-whole.png", sheet)
            val authorsGeometry = SheetGeometry.of(authors)
            val barX = authorsGeometry.sheetX(BAR_COLUMN + 0.5f).toInt()
            val barY = authorsGeometry.sheetY(BAR_ROW + 0.5f).toInt()
            println("F18 BAR cell $BAR_COLUMN,$BAR_ROW is sheet pixel $barX,$barY")
            written += write(
                dir, "298405-1024-$which-bar.png",
                crop(sheet, barX - CROP / 2, barY - CROP / 2)
            )
            sheet.close()

            val site = siteWorld(EXPORT_SIDE, byFacet)
            val exportSheet = MapImage.toBitmap(
                site, options, MapRasterizer.rasterize(site, options), MapSheet.UNGENERALISED
            )
            written += write(dir, "718106-2048-$which-whole.png", exportSheet)
            val window = riverWindow ?: busiestWindow(site, options).also {
                riverWindow = it
            }
            written += write(
                dir, "718106-2048-$which-rivers.png",
                crop(exportSheet, window.first, window.second)
            )
            exportSheet.close()
        }

        written.forEach { println("F18 CROP $it") }
        assertTrue(written.size == 8, "expected eight pictures, wrote ${written.size}")
    }

    /**
     * F22: the drowned basins whose sills lay level to the water, before and after they could cut.
     *
     * The notch measured its channel's fall to the last cell of land, one step short of the water
     * the outflow empties into, so a sill that ran level to the shore read as having no gradient
     * and never cut. What that looks like is a basin of standing water below the shoreline that no
     * river drains — seed 99 keeps one of 668 cells, 2.64 times the Caspian's share of its land —
     * and what it looks like afterwards is country.
     *
     * The two seeds that carry such a sill, each at the size its basin is worth looking at, with
     * the window chosen on the *before* world so the pair frames the same ground.
     */
    @Test
    fun `the sills that could not cut, before and after`() {
        val dir = File("build/f18-crops").apply { mkdirs() }
        val written = ArrayList<String>()
        val options = RenderOptions(view = MapView.FANTASY, style = MapStyle.ATLAS)

        listOf(
            Triple(SiteImagery.SEED, EXPORT_SIDE, "lake-country"),
            Triple(99L, STUCK_SIDE, "outlet")
        ).forEach { (seed, side, what) ->
            var window: Pair<Int, Int>? = null
            listOf(false to "before", true to "after").forEach { (cutTheSill, which) ->
                val world = worldWithSill(seed, side, cutTheSill)
                val sheet = MapImage.toBitmap(
                    world, options, MapRasterizer.rasterize(world, options), MapSheet.UNGENERALISED
                )
                val at = window ?: wettestWindow(world).also { window = it }
                written += write(dir, "$seed-$side-$what-$which-whole.png", sheet)
                written += write(
                    dir, "$seed-$side-$what-$which.png", crop(sheet, at.first, at.second)
                )
                sheet.close()
            }
        }

        written.forEach { println("F22 CROP $it") }
        assertTrue(written.size == 8, "expected eight pictures, wrote ${written.size}")
    }

    private fun worldWithSill(seed: Long, side: Int, cutTheSill: Boolean): WorldMap {
        val base = if (seed == SiteImagery.SEED) {
            WorldGenConfig(
                seed = seed, width = 512, height = 512, seaLevel = SiteImagery.SEA_LEVEL
            ).let {
                it.copy(
                    tectonics = it.tectonics.copy(plateCount = SiteImagery.PLATES),
                    nations = it.nations.copy(nationCount = SiteImagery.REALMS)
                )
            }
        } else {
            WorldGenConfig(seed = seed, width = 512, height = 512)
        }
        val scaled = base.atResolution(side, side)
        return WorldGenerationEngine.generateBlocking(
            scaled.copy(erosion = scaled.erosion.copy(outletFallToTheWater = cutTheSill))
        )
    }

    /**
     * The top-left corner, in sheet pixels, of the [CROP]-square window of the sheet holding the
     * most standing water: where the stuck basins are. Searched over the sheet itself and sampled
     * through [SheetGeometry.cellAt], so it is the window [crop] cuts whatever the sheet's shape.
     */
    private fun wettestWindow(world: WorldMap): Pair<Int, Int> {
        val geometry = SheetGeometry.of(world)
        val lake = world.rivers.lakes.lakeId
        return bestWindow(geometry) { left, top ->
            var wet = 0
            for (y in top until top + CROP step WINDOW_SAMPLE_PIXELS) {
                for (x in left until left + CROP step WINDOW_SAMPLE_PIXELS) {
                    if (lake[geometry.cellAt(x.toFloat(), y.toFloat())] >= 0) wet++
                }
            }
            wet
        }
    }

    /**
     * The top-left corner, in sheet pixels, of the [CROP]-square window of the sheet with the most
     * river in it. A river's runs are in cell coordinates, so each is carried onto the sheet
     * through [SheetGeometry] before it is counted.
     */
    private fun busiestWindow(map: WorldMap, options: RenderOptions): Pair<Int, Int> {
        val geometry = SheetGeometry.of(map)
        val runStarts = MapRasterizer.overlay(map, options, MapSheet.UNGENERALISED).rivers.map {
            geometry.sheetX(it.fromX) to geometry.sheetY(it.fromY)
        }
        return bestWindow(geometry) { left, top ->
            runStarts.count { (x, y) -> x >= left && x < left + CROP && y >= top && y < top + CROP }
        }
    }

    /**
     * The top-left corner of the [CROP]-square window of [geometry]'s sheet that [score] rates
     * highest, sliding a quarter of a window at a time; the middle of the sheet when none scores.
     */
    private fun bestWindow(geometry: SheetGeometry, score: (Int, Int) -> Int): Pair<Int, Int> {
        var most = 0
        var at = (geometry.widthPixels - CROP) / 2 to (geometry.heightPixels - CROP) / 2
        var top = 0
        while (top <= geometry.heightPixels - CROP) {
            var left = 0
            while (left <= geometry.widthPixels - CROP) {
                val found = score(left, top)
                if (found > most) {
                    most = found
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

    /** A [CROP]-square window out of [bitmap] at 1:1, its corner in sheet pixels, clamped to it. */
    private fun crop(bitmap: Bitmap, left: Int, top: Int): Bitmap {
        val fromLeft = left.coerceIn(0, bitmap.width - CROP)
        val fromTop = top.coerceIn(0, bitmap.height - CROP)
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
