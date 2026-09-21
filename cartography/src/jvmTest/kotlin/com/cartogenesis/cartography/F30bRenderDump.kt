package com.cartogenesis.cartography

import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * F30b's render: the window on 718106 at 2048 where a river was drawn with a ruler across a filled
 * flat, in the Atlas and Natural styles, so the course can be looked at before and after the
 * routing change. Runs only when asked (`CARTOGENESIS_F30B_RENDER=1`), because a 2048 world is
 * minutes and the picture is for a person, not a guard.
 */
class F30bRenderDump {

    private companion object {
        const val SEED = 718106L
        const val SIDE = 2048

        /** The longest ruled run the census found over raised ground, and a window around it. */
        const val RUN_COLUMN = 431
        const val RUN_ROW = 109
        const val WINDOW = 384
    }

    private val outputDir = File("../desktop/build/f30b-crops")

    @Test
    fun `dump the ruled-run window`() {
        if (System.getenv("CARTOGENESIS_F30B_RENDER") != "1") return
        outputDir.mkdirs()
        listOf(false to "before", true to "after").forEach { (overPotential, label) ->
            val world = WorldGenerationEngine.generateBlocking(
                WorldGenConfig(seed = SEED, width = 512, height = 512)
                    .atResolution(SIDE, SIDE)
                    .copy(flatPotential = overPotential)
            )
            dump(world, label)
        }
    }

    private fun dump(world: WorldMap, label: String) {
        listOf(
            "atlas" to RenderOptions(view = MapView.FANTASY, style = MapStyle.ATLAS),
            "natural" to RenderOptions(view = MapView.FANTASY, style = MapStyle.NATURAL)
        ).forEach { (name, options) ->
            val image = rasterOf(world, options)
            val left = (RUN_COLUMN - WINDOW / 2).coerceIn(0, SIDE - WINDOW)
            val top = (RUN_ROW - WINDOW / 4).coerceIn(0, SIDE - WINDOW)
            val crop = image.getSubimage(left, top, WINDOW, WINDOW)
            ImageIO.write(crop, "png", File(outputDir, "$SEED-$SIDE-$name-$label.png"))
            // The whole map at half size as well, so a body of water that appears in the window can
            // be followed to whatever it joins outside it.
            val half = BufferedImage(SIDE / 2, SIDE / 2, BufferedImage.TYPE_INT_ARGB)
            val graphics = half.createGraphics()
            graphics.drawImage(image, 0, 0, SIDE / 2, SIDE / 2, null)
            graphics.dispose()
            ImageIO.write(half, "png", File(outputDir, "$SEED-$SIDE-$name-$label-whole.png"))
            println("F30B RENDER $name $label window $left,$top written")
        }
        // The courses themselves, which the styles above draw a pixel wide and the eye cannot follow
        // at this size: every drawn channel cell black over the land, lakes blue, at three times
        // scale on the same window, so the ruled run and what replaced it can be seen.
        val windowLeft = (RUN_COLUMN - WINDOW / 2).coerceIn(0, SIDE - WINDOW)
        val windowTop = (RUN_ROW - WINDOW / 4).coerceIn(0, SIDE - WINDOW)
        val zoom = 3
        val courses = BufferedImage(WINDOW * zoom, WINDOW * zoom, BufferedImage.TYPE_INT_ARGB)
        val pen = courses.createGraphics()
        pen.color = java.awt.Color(0xE8, 0xE2, 0xD0)
        pen.fillRect(0, 0, WINDOW * zoom, WINDOW * zoom)
        val shore = world.sea
        for (row in 0 until WINDOW) for (column in 0 until WINDOW) {
            val cell = (windowTop + row) * SIDE + windowLeft + column
            val colour = when {
                !shore.isLand[cell] -> java.awt.Color(0x2E, 0x4A, 0x6E)
                world.rivers.lakes.isLake(cell) -> java.awt.Color(0x5A, 0x8C, 0xC8)
                else -> null
            }
            if (colour != null) {
                pen.color = colour
                pen.fillRect(column * zoom, row * zoom, zoom, zoom)
            }
        }
        pen.color = java.awt.Color.BLACK
        world.rivers.rivers.forEach { river ->
            river.cells.forEach { cell ->
                val column = cell % SIDE - windowLeft
                val row = cell / SIDE - windowTop
                if (column in 0 until WINDOW && row in 0 until WINDOW && shore.isLand[cell] && !world.rivers.lakes.isLake(cell)) {
                    pen.fillRect(column * zoom, row * zoom, zoom, zoom)
                }
            }
        }
        pen.dispose()
        ImageIO.write(courses, "png", File(outputDir, "$SEED-$SIDE-courses-$label.png"))

        // The water itself: how many cells stand below the shoreline cut and are drawn as sea, and
        // how many of those the ocean cannot reach, read off the sea-level result.
        val sea = world.sea
        var seaCells = 0
        for (cell in sea.isLand.indices) if (!sea.isLand[cell]) seaCells++
        var lakeCells = 0
        for (cell in sea.isLand.indices) if (sea.isLand[cell] && world.rivers.lakes.isLake(cell)) lakeCells++
        println("F30B WATER $label: $seaCells sea cells, $lakeCells lake cells, ${world.rivers.lakes.lakes.size} lakes")
    }

    private fun rasterOf(world: WorldMap, options: RenderOptions): BufferedImage {
        val pixels = MapRasterizer.rasterize(world, options)
        val image = BufferedImage(world.width, world.height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, world.width, world.height, pixels, 0, world.width)
        return image
    }
}
