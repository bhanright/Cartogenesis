package com.cartogenesis.cartography

import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ChannelInitiation
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * R1's renders: five worlds in the Atlas view, whole and cropped on a range's flank, with the
 * channel-head criterion's cover term on and off.
 *
 * A harness and not a guard. What R1 claims about the picture is that channel spacing now answers
 * to the country — close together on a wet flank, far apart on a dry plain — where before it was
 * one density everywhere, and no share of land settles that. Runs only when asked
 * (`CARTOGENESIS_R1_RENDER=1`), because two 2048 worlds are minutes and the pictures are for a
 * person.
 *
 * **The control is the cover term**, `RiverConfig.coverRaisesChannelHead`, which is the same
 * control the drainage-density guard is shown failing against. It is not the rule R1 replaced —
 * that rule is gone, and a picture of it has to come from a build at the branch point — but it is
 * the half of this criterion that carries the climate, and turning it off is what makes every
 * country dissect alike again.
 */
class R1RenderDump {

    private companion object {
        /** The three standard seeds at the per-merge grid, and the author's two at the working one. */
        val SEEDS = listOf(7L to 512, 42L to 512, 1234L to 512, 718106L to 2048, 59758L to 2048)

        /** The side of the crop, in cells, and how many coarse tiles the crop is chosen from. */
        const val CROP_SIDE = 384
        const val TILES_ACROSS = 8
    }

    private val outputDir = File("../desktop/build/r1-crops")

    @Test
    fun `dump five worlds with the cover term on and off`() {
        if (System.getenv("CARTOGENESIS_R1_RENDER") != "1") return
        outputDir.mkdirs()
        SEEDS.forEach { (seed, side) ->
            val started = System.currentTimeMillis()
            val config = WorldGenConfig(seed = seed, width = side, height = side)
            val after = WorldGenerationEngine.generateBlocking(config)
            // The same world with the cover term off. Handed the finished world as `previous`, so
            // every stage above the rivers is reused by identity and the control costs one river
            // stage and what depends on it rather than one world.
            val before = runBlocking {
                WorldGenerationEngine.generate(
                    config.copy(rivers = config.rivers.copy(coverRaisesChannelHead = false)),
                    previous = after
                )
            }
            println(
                "R1 RENDER seed $seed at $side in ${(System.currentTimeMillis() - started) / 1000}s"
            )
            report(seed, side, "cover", after)
            report(seed, side, "bare", before)
            val window = dissectedFlankWindow(after)
            val options = RenderOptions(view = MapView.FANTASY, style = MapStyle.ATLAS)
            write(rasterOf(before, options), window, "$seed-$side-atlas", "bare")
            write(rasterOf(after, options), window, "$seed-$side-atlas", "cover")
        }
    }

    /** What the criterion left on this world, so the pictures have numbers beside them. */
    private fun report(seed: Long, side: Int, label: String, world: WorldMap) {
        val channel = ChannelInitiation.channelMaskOf(world)
        var channelCells = 0
        for (cell in channel.indices) if (channel[cell]) channelCells++
        val drawnCells = world.rivers.rivers.sumOf { it.length }
        println(
            ("R1 NETWORK seed %d at %d %s: %d courses, %d drawn cells, %d channel cells, " +
                "%.3f of the land")
                .format(
                    seed, side, label, world.rivers.rivers.size, drawnCells, channelCells,
                    channelCells.toFloat() / world.sea.landCellCount
                )
        )
    }

    private class Window(val x: Int, val y: Int, val side: Int)

    /**
     * A window on the ground where channel spacing is most worth looking at: the coarse tile
     * holding the most channel cells that stand on a slope.
     *
     * Chosen by the arithmetic rather than by eye, so it lands on the same country on every
     * platform and on the ground the chunk's claim is about — a range's flank, where a wet side and
     * a dry side meet within one crop.
     */
    private fun dissectedFlankWindow(world: WorldMap): Window {
        val channel = ChannelInitiation.channelMaskOf(world)
        val gradient = ChannelInitiation.gradientToReceiver(
            world.config, world.sea.isLand, world.rivers.filledElevation, world.rivers.flowTarget
        )
        val cellsAcross = world.width
        val cellsDown = world.height
        val tileSide = cellsAcross / TILES_ACROSS
        val steepness = DoubleArray(TILES_ACROSS * TILES_ACROSS)
        for (cell in 0 until cellsAcross * cellsDown) {
            if (!channel[cell]) continue
            val tile = (cell / cellsAcross / tileSide) * TILES_ACROSS +
                (cell % cellsAcross) / tileSide
            if (tile in steepness.indices) steepness[tile] += gradient[cell].toDouble()
        }
        var best = 0
        for (tile in steepness.indices) if (steepness[tile] > steepness[best]) best = tile
        val side = CROP_SIDE.coerceAtMost(minOf(cellsAcross, cellsDown))
        val x = ((best % TILES_ACROSS) * tileSide + tileSide / 2 - side / 2)
            .coerceIn(0, cellsAcross - side)
        val y = ((best / TILES_ACROSS) * tileSide + tileSide / 2 - side / 2)
            .coerceIn(0, cellsDown - side)
        println("R1 CROP: window $x,$y on the steepest channelled tile")
        return Window(x, y, side)
    }

    private fun rasterOf(world: WorldMap, options: RenderOptions): BufferedImage {
        val pixels = MapRasterizer.rasterize(world, options)
        val image = BufferedImage(world.width, world.height, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, world.width, world.height, pixels, 0, world.width)
        return image
    }

    private fun write(image: BufferedImage, window: Window, name: String, label: String) {
        ImageIO.write(halved(image), "png", File(outputDir, "$name-whole-$label.png"))
        ImageIO.write(
            image.getSubimage(window.x, window.y, window.side, window.side), "png",
            File(outputDir, "$name-crop-$label.png")
        )
        println("R1 RENDER wrote $name-*-$label.png")
    }

    /** Half size, so a whole world is a picture a reader can take in at once. */
    private fun halved(image: BufferedImage): BufferedImage {
        val half = BufferedImage(image.width / 2, image.height / 2, BufferedImage.TYPE_INT_RGB)
        val graphics = half.createGraphics()
        graphics.drawImage(image, 0, 0, half.width, half.height, null)
        graphics.dispose()
        return half
    }
}
