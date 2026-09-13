package com.cartogenesis.desktop

import com.cartogenesis.cartography.MapStyle
import com.cartogenesis.cartography.MapView
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.ui.MapImage
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect

/**
 * The author's two worlds at 2048, and four details of each, written out to be looked at.
 *
 * Tints by climate and shading by a sky are claims about *appearance*, and the only instrument that
 * can settle them is a person. What the guards beside this can settle — that a desert never comes
 * out green, that no face of a hill is left unlit, that the graphics card draws the same picture —
 * they do; this writes the pictures those numbers are supposed to describe.
 *
 * Four details a piece, each 800 by 600 at the render's own pixels, chosen by the world rather than
 * by hand: the window holding the most desert, the most forest, the most high ground, and the most
 * sea floor crossed by a depth contour. Choosing them from the fields means the same window comes
 * out before and after a change to the drawing, since the world underneath is untouched.
 *
 * In the audit tier: two 2048 worlds is a minute and a half of generation before a pixel is drawn.
 */
class ClimateReliefGalleryTest {

    @Test
    fun `both worlds at 2048, in three styles, with four details of each`() {
        val dir = File("build/f13-crops").apply { mkdirs() }
        val styles = listOf(MapStyle.ATLAS, MapStyle.VELLUM, MapStyle.PEN_AND_INK)

        WORLDS.forEach { (name, config) ->
            val world = WorldGenerationEngine.generateBlocking(config)
            val windows = DETAILS.associate { detail ->
                detail.name to densestWindow(world, detail)
            }
            println("F13 $name windows: $windows")

            styles.forEach { style ->
                val options = RenderOptions(view = MapView.FANTASY, style = style)
                val bitmap = MapImage.toBitmap(world, options)
                val whole = Image.makeFromBitmap(bitmap)
                val label = "$name-${style.name.lowercase()}"
                write(File(dir, "$label-2048.png"), whole)
                DETAILS.forEach { detail ->
                    val corner = windows.getValue(detail.name)
                    write(
                        File(dir, "$label-${detail.name}.png"),
                        crop(whole, corner.first, corner.second)
                    )
                }
                whole.close()
                bitmap.close()
            }
        }

        val written = dir.listFiles()?.count { it.name.endsWith(".png") } ?: 0
        assertTrue(written >= 24, "only $written pictures were written to ${dir.absolutePath}")
        println("F13 wrote $written pictures to ${dir.absolutePath}")
    }

    /** One PNG, from a whole map or a detail of one. */
    private fun write(file: File, image: Image) {
        file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    /** The detail at [left], [top], at the render's own pixels — no scaling anywhere. */
    private fun crop(whole: Image, left: Int, top: Int): Image {
        val bitmap = Bitmap().apply {
            allocPixels(ImageInfo.makeS32(DETAIL_WIDTH, DETAIL_HEIGHT, ColorAlphaType.PREMUL))
        }
        Canvas(bitmap).drawImageRect(
            whole,
            Rect.makeXYWH(
                left.toFloat(), top.toFloat(), DETAIL_WIDTH.toFloat(), DETAIL_HEIGHT.toFloat()
            ),
            Rect.makeWH(DETAIL_WIDTH.toFloat(), DETAIL_HEIGHT.toFloat())
        )
        val image = Image.makeFromBitmap(bitmap)
        bitmap.close()
        return image
    }

    /**
     * Where a detail-sized window holds the most of what [detail] is looking for.
     *
     * Searched on a coarse lattice and sampled every fourth cell each way: the window only has to
     * be a good one, not the best one, and the full search is a hundred million tests.
     */
    private fun densestWindow(world: WorldMap, detail: Detail): Pair<Int, Int> {
        var best = Pair(0, 0)
        var bestCount = -1
        var top = 0
        while (top + DETAIL_HEIGHT <= world.height) {
            var left = 0
            while (left + DETAIL_WIDTH <= world.width) {
                var count = 0
                var y = top
                while (y < top + DETAIL_HEIGHT) {
                    var x = left
                    while (x < left + DETAIL_WIDTH) {
                        if (detail.wanted(world, y * world.width + x)) count++
                        x += 4
                    }
                    y += 4
                }
                if (count > bestCount) {
                    bestCount = count
                    best = Pair(left, top)
                }
                left += SEARCH_STEP
            }
            top += SEARCH_STEP
        }
        return best
    }

    private class Detail(val name: String, val wanted: (WorldMap, Int) -> Boolean)

    private companion object {

        /** About 800 by 600, which is a detail a person can take in at once. */
        const val DETAIL_WIDTH = 800
        const val DETAIL_HEIGHT = 600

        /** How far the window slides between tries. */
        const val SEARCH_STEP = 100

        /** Where the sea floor is worth contouring: below the shelf and above the abyssal plain. */
        const val SEA_SLOPE_SHALLOW = -0.55f
        const val SEA_SLOPE_DEEP = -0.05f

        /** Where the ground counts as high, in relative elevation. */
        const val HIGH_GROUND = 0.45f

        val FORESTS = setOf(
            Biome.TAIGA,
            Biome.TEMPERATE_FOREST,
            Biome.TEMPERATE_RAINFOREST,
            Biome.TROPICAL_SEASONAL_FOREST,
            Biome.TROPICAL_RAINFOREST,
            Biome.MONSOON_FOREST
        )

        val DETAILS = listOf(
            Detail("desert") { world, cell -> world.climate.biome[cell] == Biome.DESERT },
            Detail("forest") { world, cell -> world.climate.biome[cell] in FORESTS },
            Detail("mountains") { world, cell ->
                world.sea.isLand[cell] && world.sea.relativeElevation.data[cell] > HIGH_GROUND
            },
            // Not the deepest water but the *sloping* water: a window of flat abyss has no contour
            // in it to look at, and neither has a window of shelf.
            Detail("sea") { world, cell ->
                val depth = world.sea.relativeElevation.data[cell]
                !world.sea.isLand[cell] && depth < SEA_SLOPE_DEEP && depth > SEA_SLOPE_SHALLOW
            }
        )

        /**
         * The author's own two worlds, at the size he exports at.
         *
         * 718106 is his, settings and all — 62% ocean, fourteen plates, twelve realms — and 59758
         * is the same seed the cartouche is written against, on the generator's own defaults.
         */
        val WORLDS: List<Pair<String, WorldGenConfig>> = listOf(
            "718106" to WorldGenConfig(seed = 718106L, width = 512, height = 512, seaLevel = 0.62f)
                .let { base ->
                    base.copy(
                        tectonics = base.tectonics.copy(plateCount = 14),
                        nations = base.nations.copy(nationCount = 12)
                    )
                }
                .atResolution(2048, 2048),
            "59758" to WorldGenConfig(seed = 59758L, width = 512, height = 512)
                .atResolution(2048, 2048)
        )
    }
}
