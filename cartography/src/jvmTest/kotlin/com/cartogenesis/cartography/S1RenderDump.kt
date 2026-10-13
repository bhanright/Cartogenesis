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
 * and not a guard; what the colour must do is asserted in `SatelliteStyleTest`.
 *
 * Each window is chosen by the world's own surface ([SatelliteGround]), searched on a lattice a
 * quarter of a window apart, so the crop lands where the thing it is named for is strongest.
 */
class S1RenderDump {

    private companion object {
        val SEEDS = listOf(42L, 969495L, 7L)
        const val ROWS = 1024

        /** The side of a crop, in cells: a cell to a pixel, about 1,500 km on the default planet. */
        const val CROP_CELLS = 256

        /** A window counts only if this share of it is land. */
        const val LEAST_LAND_SHARE = 0.5

        /** How many times over the enlarged crops repeat each pixel. */
        const val ENLARGED = 3
    }

    private class Window(val left: Int, val top: Int, val score: Double)

    @Test
    fun `dump three worlds whole and cropped in the satellite style and in Atlas`() {
        val folder = System.getenv("CARTOGENESIS_S1_RENDER") ?: return
        val output = File(folder)
        output.mkdirs()
        for (seed in SEEDS) {
            val world = SharedWorlds.world(WorldGenConfig.forRows(seed, ROWS))
            val surface = SatelliteGround.of(world)
            val satellite = MapRasterizer.rasterize(world, RenderOptions(style = MapStyle.SATELLITE))
            val atlas = MapRasterizer.rasterize(world, RenderOptions(style = MapStyle.ATLAS))
            write(satellite, world, 0, 0, world.width, world.height, File(output, "$seed-whole-satellite.png"))
            write(atlas, world, 0, 0, world.width, world.height, File(output, "$seed-whole-atlas.png"))

            fun byteOf(packed: Int, index: Int): Double = ((packed ushr (index * 8)) and 0xFF) / 255.0
            val windows = mapOf(
                "desert" to best(world) { cell ->
                    byteOf(surface.composition[cell], 2) + byteOf(surface.climate[cell], 3)
                },
                // Both ends in one window: as much dry ground as wooded, whichever is scarcer.
                "desert-to-forest" to bestBalance(world, { cell -> byteOf(surface.climate[cell], 3) > 0.6 }) { cell ->
                    surface.cover[cell] > 0.6f
                },
                "mountains" to best(world) { cell -> byteOf(surface.composition[cell], 1) },
                "dry-river" to best(world) { cell ->
                    byteOf(surface.climate[cell], 1) * byteOf(surface.climate[cell], 3)
                }
            )
            windows.forEach { (name, window) ->
                println("S1 RENDER seed $seed $name window at ${window.left},${window.top} score %.3f".format(window.score))
                write(satellite, world, window.left, window.top, CROP_CELLS, CROP_CELLS, File(output, "$seed-$name-satellite.png"))
                write(atlas, world, window.left, window.top, CROP_CELLS, CROP_CELLS, File(output, "$seed-$name-atlas.png"))
                // And the middle of the satellite crop three times over, pixel for pixel, for a
                // reader to see the texture a cell at a time.
                write(satellite, world, window.left + CROP_CELLS / 4, window.top + CROP_CELLS / 4, CROP_CELLS / 2, CROP_CELLS / 2,
                    File(output, "$seed-$name-satellite-x3.png"), ENLARGED)
            }
        }
    }

    /** The window whose land scores highest on average by [score]. */
    private fun best(world: WorldMap, score: (Int) -> Double): Window =
        search(world) { cells -> cells.sumOf(score) / cells.size }

    /** The window with the most of whichever of [first] and [second] it has less of. */
    private fun bestBalance(world: WorldMap, first: (Int) -> Boolean, second: (Int) -> Boolean): Window =
        search(world) { cells ->
            minOf(cells.count(first), cells.count(second)).toDouble() / cells.size
        }

    private fun search(world: WorldMap, score: (List<Int>) -> Double): Window {
        val stride = CROP_CELLS / 4
        var best = Window(0, 0, Double.NEGATIVE_INFINITY)
        var top = 0
        while (top + CROP_CELLS <= world.height) {
            var left = 0
            while (left + CROP_CELLS <= world.width) {
                val land = ArrayList<Int>()
                for (row in top until top + CROP_CELLS step 2) for (column in left until left + CROP_CELLS step 2) {
                    val cell = row * world.width + column
                    if (world.sea.isLand[cell]) land.add(cell)
                }
                if (land.size >= LEAST_LAND_SHARE * (CROP_CELLS / 2) * (CROP_CELLS / 2)) {
                    val value = score(land)
                    if (value > best.score) best = Window(left, top, value)
                }
                left += stride
            }
            top += stride
        }
        return best
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
