package com.cartogenesis.desktop

import com.cartogenesis.cartography.MapRasterizer
import com.cartogenesis.cartography.MapSheet
import com.cartogenesis.cartography.MapStyle
import com.cartogenesis.cartography.MapView
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.Shoreline
import com.cartogenesis.ui.MapImage
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import java.io.File
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * F14 at the size the author looks at his worlds: both of them, at 2048, generalised two ways.
 *
 * The arithmetic is guarded in `:cartography`'s `GeneralisationTest`, which runs on every merge.
 * What cannot be guarded by a number is whether the picture is *better* — whether the coast reads
 * as a line, whether the graticule sits on the map without burying it, whether dropping a third of
 * the rivers at whole-world scale loses anything a reader wanted. So this writes the crops and the
 * report says what they showed.
 *
 * In the audit tier for the reason `ExportAuditTest` and `SeaLevelHistoryAuditTest` are: two 2048
 * worlds is minutes of generation, and none of it is per-merge work.
 *
 * It also measures the one cost F14 adds to every drawing — tracing the shoreline off the land mask
 * — against the raster it sits on top of, because rule 8 asks that a per-cell pass state its figure.
 */
class GeneralisationRenderTest {

    private companion object {
        const val SIZE = 2048

        /** A 1:1 window big enough to show a coast and a river system, and no bigger. */
        const val CROP = 640

        /**
         * The zooms the crops are drawn for, in screen pixels to the cell.
         *
         * A 2048 sheet fitted into a 900-pixel pane is at 0.44; four times zoom is 1.76.
         * [MapSheet.onScreen] quantises them to 0.5 and 2.0.
         */
        const val AT_FIT = 900f / SIZE
        const val AT_FOUR_TIMES = 4f * AT_FIT
    }

    /** The author's two worlds, on the settings he generates with. */
    private val worlds = listOf(
        "718106" to WorldGenConfig(
            seed = SiteImagery.SEED,
            width = SIZE,
            height = SIZE,
            seaLevel = SiteImagery.SEA_LEVEL
        ).let { base ->
            base.copy(
                tectonics = base.tectonics.copy(plateCount = SiteImagery.PLATES),
                nations = base.nations.copy(nationCount = SiteImagery.REALMS)
            )
        },
        "59758" to WorldGenConfig(seed = 59758L, width = SIZE, height = SIZE)
    )

    @Test
    fun `both worlds at 2048, at fit and at four times, with the graticule on and off`() {
        val dir = File("build/f14-crops").apply { mkdirs() }
        val written = ArrayList<String>()

        worlds.forEach { (name, config) ->
            var world: WorldMap? = null
            val generateMs = measureTimeMillis {
                world = WorldGenerationEngine.generateBlocking(config)
            }
            val map = world!!
            println("F14 seed $name at $SIZE generated in $generateMs ms")

            val plain = RenderOptions(view = MapView.FANTASY, style = MapStyle.ATLAS)
            val figured = plain.copy(showGraticule = true)

            // The raster is drawn once and the overlay four times over it, which is exactly what
            // the application does as the reader zooms; it also makes the four sheets differ only
            // in the ink, so the crops can be compared honestly.
            val ground = MapRasterizer.rasterize(map, plain)
            val groundFigured = MapRasterizer.rasterize(map, figured)

            listOf(
                "fit" to MapSheet.onScreen(AT_FIT),
                "4x" to MapSheet.onScreen(AT_FOUR_TIMES)
            ).forEach { (zoom, sheet) ->
                listOf("plain" to (plain to ground), "graticule" to (figured to groundFigured))
                    .forEach { (grid, drawing) ->
                        val (options, pixels) = drawing
                        val bitmap = MapImage.toBitmap(map, options, pixels, sheet)
                        written += write(dir, "$name-$zoom-$grid.png", bitmap)
                        written += write(
                            dir,
                            "$name-$zoom-$grid-crop.png",
                            crop(bitmap, SIZE / 2 - CROP / 2, SIZE / 2 - CROP / 2)
                        )
                        bitmap.close()
                    }
            }

            // The corner an export puts its scale bar in, at 1:1, with the graticule's figures
            // around it: the one place the stroked numerals can be looked at closely.
            val printed = MapImage.toBitmap(map, figured, groundFigured, MapSheet.PRINTED)
            written += write(
                dir,
                "$name-printed-corner.png",
                crop(printed, 0, SIZE - CROP)
            )
            written += write(dir, "$name-printed-topright.png", crop(printed, SIZE - CROP, 0))
            printed.close()

            val atFit = MapRasterizer.overlay(map, plain, MapSheet.onScreen(AT_FIT))
            val zoomedIn = MapRasterizer.overlay(map, plain, MapSheet.onScreen(AT_FOUR_TIMES))
            println(
                "F14 seed $name at $SIZE: ${atFit.riversDrawn} rivers and " +
                    "${atFit.coastline.sumOf { it.size / 2 }} coast vertices at fit, " +
                    "${zoomedIn.riversDrawn} rivers and " +
                    "${zoomedIn.coastline.sumOf { it.size / 2 }} at 4x, " +
                    "of ${map.rivers.rivers.size} rivers traced"
            )
            assertTrue(atFit.riversDrawn < zoomedIn.riversDrawn)
        }

        written.forEach { println("F14 CROP $it") }
    }

    /**
     * What the shoreline trace costs against the raster it is drawn over, at 2048.
     *
     * Rule 8: the trace is a pass over every cell, so it states its figure. The raster it is
     * compared against is the same world's own — the processor path, which is what a machine with
     * no graphics device draws with and therefore the honest thing to be a fraction of.
     */
    @Test
    fun `what the shoreline trace costs beside the raster`() {
        val config = worlds[0].second
        val map = WorldGenerationEngine.generateBlocking(config)
        val options = RenderOptions()

        // Once to let the JIT see it, then measured.
        MapRasterizer.rasterize(map, options)
        Shoreline.trace(map.sea.isLand, map.width, map.height)

        var rasterMs = 0L
        repeat(3) { rasterMs += measureTimeMillis { MapRasterizer.rasterize(map, options) } }
        rasterMs /= 3

        var traceMs = 0L
        var vertices = 0
        repeat(3) {
            traceMs += measureTimeMillis {
                vertices = Shoreline.trace(map.sea.isLand, map.width, map.height)
                    .sumOf { it.size / 2 }
            }
        }
        traceMs /= 3

        var wholeOverlayMs = 0L
        repeat(3) {
            wholeOverlayMs += measureTimeMillis {
                MapRasterizer.overlay(map, options, MapSheet.onScreen(AT_FIT))
            }
        }
        wholeOverlayMs /= 3

        println(
            "F14 at $SIZE: raster $rasterMs ms, shoreline trace $traceMs ms for $vertices " +
                "vertices, whole overlay (trace, simplify, rivers) $wholeOverlayMs ms"
        )
        assertTrue(
            traceMs < rasterMs,
            "the shoreline trace costs $traceMs ms against the raster's $rasterMs ms, so the " +
                "coast is now the expensive half of drawing a map"
        )
    }

    private fun write(dir: File, name: String, bitmap: Bitmap): String {
        val data = Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)!!
        val file = File(dir, name)
        file.writeBytes(data.bytes)
        return file.absolutePath
    }

    /** A [CROP]-square window out of [bitmap] at 1:1, its top-left corner at [left], [top]. */
    private fun crop(bitmap: Bitmap, left: Int, top: Int): Bitmap {
        val source = bitmap.readPixels() ?: error("could not read the rendered map back")
        val window = Bitmap()
        window.allocPixels(ImageInfo.makeS32(CROP, CROP, ColorAlphaType.PREMUL))
        val bytes = ByteArray(CROP * CROP * 4)
        for (row in 0 until CROP) {
            val from = ((top + row) * bitmap.width + left) * 4
            source.copyInto(bytes, row * CROP * 4, from, from + CROP * 4)
        }
        window.installPixels(bytes)
        return window
    }
}
