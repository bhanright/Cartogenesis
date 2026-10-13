package com.cartogenesis.cartography

import com.cartogenesis.worldgen.SharedWorlds
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * The satellite style's pictures: three worlds on the application's grid, whole in the new
 * style, and four windows of each cut out a cell to a pixel in the new style and in Atlas, the
 * default it replaces: the largest desert, a desert running into forest, a mountain range and a
 * river through dry country.
 *
 * Runs only when asked: `CARTOGENESIS_S1_RENDER` names the folder the pictures go to. A harness
 * and not a guard; what the color must do is asserted in `SatelliteStyleTest`.
 *
 * The windows are fixed ([WINDOWS]), so one round's crops can be laid beside another's.
 */
class S1RenderDump {

    private companion object {
        val SEEDS = listOf(42L, 969495L, 7L)
        const val ROWS = 1024

        /** The side of a crop, in cells: a cell to a pixel, about 5,000 km on the Earth-sized default. */
        const val CROP_CELLS = 256

        /** A window counts only if this share of it is land. */
        const val LEAST_LAND_SHARE = 0.5

        /** How many times over the enlarged crops repeat each pixel. */
        const val ENLARGED = 3

        /**
         * The windows, fixed so that every round crops the same ground: the ones the first round
         * chose by its own surface (the largest desert, a desert running into forest, a mountain
         * range, a river through dry country), each the best on a lattice a quarter of a window
         * apart.
         */
        private val WINDOWS: Map<Long, Map<String, Window>> = mapOf(
            42L to mapOf(
                "desert" to Window(64, 320), "desert-to-forest" to Window(64, 256),
                "mountains" to Window(192, 0), "dry-river" to Window(384, 128)
            ),
            969495L to mapOf(
                "desert" to Window(1472, 640), "desert-to-forest" to Window(1536, 512),
                "mountains" to Window(1280, 576), "dry-river" to Window(1472, 576)
            ),
            7L to mapOf(
                "desert" to Window(192, 256), "desert-to-forest" to Window(1664, 256),
                "mountains" to Window(448, 384), "dry-river" to Window(0, 128)
            )
        )
    }

    private class Window(val left: Int, val top: Int)

    @Test
    fun `dump three worlds whole and cropped in the satellite style and in Atlas`() {
        val folder = System.getenv("CARTOGENESIS_S1_RENDER") ?: return
        val output = File(folder)
        output.mkdirs()
        for (seed in SEEDS) {
            val world = SharedWorlds.world(WorldGenConfig.forRows(seed, ROWS))
            val satellite = MapRasterizer.rasterize(world, RenderOptions(style = MapStyle.SATELLITE))
            val atlas = MapRasterizer.rasterize(world, RenderOptions(style = MapStyle.ATLAS))
            write(satellite, world, 0, 0, world.width, world.height, File(output, "$seed-whole-satellite.png"))
            write(atlas, world, 0, 0, world.width, world.height, File(output, "$seed-whole-atlas.png"))

            val windows = WINDOWS.getValue(seed)
            windows.forEach { (name, window) ->
                write(satellite, world, window.left, window.top, CROP_CELLS, CROP_CELLS, File(output, "$seed-$name-satellite.png"))
                write(atlas, world, window.left, window.top, CROP_CELLS, CROP_CELLS, File(output, "$seed-$name-atlas.png"))
                // And the middle of the satellite crop three times over, pixel for pixel, for a
                // reader to see the texture a cell at a time.
                write(satellite, world, window.left + CROP_CELLS / 4, window.top + CROP_CELLS / 4, CROP_CELLS / 2, CROP_CELLS / 2,
                    File(output, "$seed-$name-satellite-x3.png"), ENLARGED)
            }
        }
    }

    private fun write(
        pixels: IntArray, world: WorldMap, left: Int, top: Int, across: Int, down: Int, file: File,
        enlarged: Int = 1
    ) {
        val image = BufferedImage(across * enlarged, down * enlarged, BufferedImage.TYPE_INT_RGB)
        for (row in 0 until down * enlarged) for (column in 0 until across * enlarged) {
            image.setRGB(column, row, pixels[(top + row / enlarged) * world.width + left + column / enlarged])
        }
        ImageIO.write(image, "png", file)
    }
}
