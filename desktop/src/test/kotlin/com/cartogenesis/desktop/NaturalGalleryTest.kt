package com.cartogenesis.desktop

import com.cartogenesis.cartography.MapRasterizer
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
 * The Natural style on the author's two worlds at 2048, whole and in three details, to be held up
 * beside the photograph its palette was measured off.
 *
 * `NaturalStyleTest` settles what can be settled by arithmetic — that the ink reads, that the ramp
 * climbs, that no stop is a neon or a pastel — and `ClimateTintTest` settles that no desert in it
 * comes out green. What none of them can settle is the only question the style was built to answer,
 * which is whether the picture looks like a photograph of a planet. That is a person's judgement and
 * these are the pictures it is made on.
 *
 * Three details a piece, each 800 by 600 at the render's own pixels and chosen by the world rather
 * than by hand, because those are the three places the palette is making its riskiest claims:
 *
 *  - **a forested coast**, where the deep greens meet the shelf turquoise and the coastline is the
 *    one drawn convention the style keeps;
 *  - **a desert**, which is where a hypsometric ramp usually betrays itself by painting sand green,
 *    and where the arid lift is doing the most work;
 *  - **a snowy range**, where the cold paling, the summit stop and the sky relief all meet, and
 *    where a palette that had no white in it would show a grey mountain.
 *
 * In the audit tier, with F13's gallery and for the same reason: two 2048 worlds is a minute and a
 * half of generation before a pixel is drawn, and nothing per-merge depends on any of it.
 */
class NaturalGalleryTest {

    @Test
    fun `both worlds at 2048 in the Natural style, with three details of each`() {
        val dir = File("build/f23-crops").apply { mkdirs() }
        val options = RenderOptions(view = MapView.FANTASY, style = MapStyle.NATURAL)

        WORLDS.forEach { (name, config) ->
            val world = WorldGenerationEngine.generateBlocking(config)
            println("F23 $name ${fieldSpread(world)}")

            // The same world in Atlas, as the control a reviewer needs: Atlas is the style everyone
            // knows, and the question "is this a photograph or a prettier plate" is only answerable
            // with the plate beside it. The world is already generated, so it costs one raster.
            write(
                File(dir, "$name-atlas-2048.png"),
                Image.makeFromBitmap(MapImage.toBitmap(world, options.copy(style = MapStyle.ATLAS)))
            )

            val bitmap = MapImage.toBitmap(world, options)
            val whole = Image.makeFromBitmap(bitmap)
            write(File(dir, "$name-natural-2048.png"), whole)
            perBiomeColour(world, name)

            DETAILS.forEach { detail ->
                val (left, top) = densestWindow(world, detail)
                println("F23 $name ${detail.name}: the window at $left,$top")
                write(File(dir, "$name-natural-${detail.name}.png"), crop(whole, left, top))
            }
            whole.close()
            bitmap.close()
        }

        val written = dir.listFiles()?.count { it.name.endsWith(".png") } ?: 0
        assertTrue(written >= 8, "only $written pictures were written to ${dir.absolutePath}")
        println("F23 wrote $written pictures to ${dir.absolutePath}")
    }

    private fun write(file: File, image: Image) {
        file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    /**
     * What each vegetation actually comes out as, beside the height and climate that made it.
     *
     * The whole-map statistics are confounded: this generator's mix of biomes is not Earth's, so a
     * median taken over all the land compares two different worlds rather than two palettes. Per
     * biome the comparison is like for like — the reference's own forest, plains and desert are
     * sampled colours, and these are the colours this style gives the same three things. The
     * height and climate beside each are what put it there, so a colour that looks wrong can be
     * traced to the ramp, to the drought's lift or to the cold's paling without another render.
     */
    private fun perBiomeColour(world: WorldMap, name: String) {
        val cells = world.width * world.height
        val drawn = MapRasterizer.rasterize(world, RenderOptions(style = MapStyle.NATURAL))
        val red = LongArray(Biome.entries.size)
        val green = LongArray(Biome.entries.size)
        val blue = LongArray(Biome.entries.size)
        val height = DoubleArray(Biome.entries.size)
        val warmth = DoubleArray(Biome.entries.size)
        val rain = DoubleArray(Biome.entries.size)
        val count = IntArray(Biome.entries.size)
        for (cell in 0 until cells) {
            if (!world.sea.isLand[cell] || world.rivers.lakes.isLake(cell)) continue
            val biome = world.climate.biome[cell].ordinal
            red[biome] += (drawn[cell] shr 16) and 0xFF
            green[biome] += (drawn[cell] shr 8) and 0xFF
            blue[biome] += drawn[cell] and 0xFF
            height[biome] += world.sea.relativeElevation.data[cell].toDouble()
            warmth[biome] += world.climate.temperature.data[cell].toDouble()
            rain[biome] += world.climate.precipitationMm.data[cell].toDouble()
            count[biome]++
        }
        Biome.entries.forEach { biome ->
            val n = count[biome.ordinal]
            if (n < MIN_BIOME_CELLS) return@forEach
            println(
                ("F23 %s %-26s #%02X%02X%02X over %6d cells, mean height %.2f, " +
                    "%.1f C, %.0f mm").format(
                    name, biome.name,
                    red[biome.ordinal] / n, green[biome.ordinal] / n, blue[biome.ordinal] / n,
                    n, height[biome.ordinal] / n, warmth[biome.ordinal] / n, rain[biome.ordinal] / n
                )
            )
        }
    }

    /**
     * Where along each ramp this world actually asks to be painted.
     *
     * A ramp is a promise about a *distribution* as much as about a set of colours: a sea ramp
     * whose turquoise sits in the top tenth paints a turquoise ocean on a world that keeps most of
     * its sea floor in that tenth. The deciles below are what the palette was calibrated against,
     * and they belong in the report beside the pictures.
     */
    private fun fieldSpread(world: WorldMap): String {
        val sea = ArrayList<Float>()
        val land = ArrayList<Float>()
        for (cell in 0 until world.width * world.height) {
            val relative = world.sea.relativeElevation.data[cell]
            if (world.sea.isLand[cell]) land += relative else sea += 1f + relative
        }
        sea.sort()
        land.sort()
        fun deciles(values: List<Float>) = (0..10).joinToString(" ") {
            "%.2f".format(values[(values.size - 1) * it / 10])
        }
        return "sea reads the ocean ramp at ${deciles(sea)}; land reads the land ramp at " +
            deciles(land)
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
     * Searched on a coarse lattice and sampled every fourth cell each way, as F13's gallery does:
     * the window only has to be a good one, not the best one, and choosing it from the world's own
     * fields means the same window comes out again after a change to the drawing, since the world
     * underneath is untouched by a style.
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

        /** Below this a biome is too rare on a world for its mean colour to say anything. */
        const val MIN_BIOME_CELLS = 200

        /** How far the window slides between tries. */
        const val SEARCH_STEP = 100

        /** Where the ground counts as high, in relative elevation. F13's gallery uses the same. */
        const val HIGH_GROUND = 0.45f

        /** How far inland a cell may be and still count as coast, in cells of the 2048 render. */
        const val COAST_REACH = 6

        /** Mean annual degrees below which a summit carries snow rather than rock. */
        const val SNOW_LINE_C = 0f

        val FORESTS = setOf(
            Biome.TAIGA,
            Biome.TEMPERATE_FOREST,
            Biome.TEMPERATE_RAINFOREST,
            Biome.TROPICAL_SEASONAL_FOREST,
            Biome.TROPICAL_RAINFOREST,
            Biome.MONSOON_FOREST
        )

        /** Whether the sea is within [COAST_REACH] cells, on the four bearings. */
        fun nearTheSea(world: WorldMap, cell: Int): Boolean {
            val width = world.width
            val x = cell % width
            val y = cell / width
            for (step in 1..COAST_REACH) {
                val neighbours = intArrayOf(
                    y * width + (x + step).coerceAtMost(width - 1),
                    y * width + (x - step).coerceAtLeast(0),
                    (y + step).coerceAtMost(world.height - 1) * width + x,
                    (y - step).coerceAtLeast(0) * width + x
                )
                if (neighbours.any { !world.sea.isLand[it] }) return true
            }
            return false
        }

        val DETAILS = listOf(
            Detail("forested-coast") { world, cell ->
                world.sea.isLand[cell] &&
                    world.climate.biome[cell] in FORESTS &&
                    nearTheSea(world, cell)
            },
            Detail("desert") { world, cell -> world.climate.biome[cell] == Biome.DESERT },
            // High ground that is also cold, rather than merely high: a tropical summit is bare
            // rock on this palette and the question here is what the snow stop and the cold paling
            // do together.
            Detail("snowy-range") { world, cell ->
                world.sea.isLand[cell] &&
                    world.sea.relativeElevation.data[cell] > HIGH_GROUND &&
                    world.climate.temperature.data[cell] < SNOW_LINE_C
            }
        )

        /**
         * The author's own two worlds, at the size he exports at — the same pair F13's gallery is
         * reviewed on, so the two reviews are of the same country.
         *
         * 718106 is his, settings and all: 62% ocean, fourteen plates, twelve realms. 59758 is the
         * generator's own defaults.
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
