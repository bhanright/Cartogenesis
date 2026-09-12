package com.cartogenesis.desktop

import com.cartogenesis.cartography.MapRasterizer
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** Throwaway: the author's two worlds at 2048 (62% ocean, 14 plates, 12 realms), rivers painted. */
class SeedProbe2048 {
    @Test
    fun `probe the author's worlds at 2048`() {
        val dir = File("build/maps").apply { mkdirs() }
        for (seed in listOf(718106L, 59758L)) {
            val base = WorldGenConfig(seed = seed, width = 512, height = 512, seaLevel = 0.62f)
            val config = base.copy(
                tectonics = base.tectonics.copy(plateCount = 14),
                nations = base.nations.copy(nationCount = 12)
            ).atResolution(2048, 2048)
            val world = WorldGenerationEngine.generateBlocking(config)
            val w = world.width; val h = world.height
            val pixels = MapRasterizer.rasterize(world, RenderOptions())
            val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            image.setRGB(0, 0, w, h, pixels, 0, w)
            for (r in world.rivers.rivers) for (c in r.cells) image.setRGB(c % w, c / w, 0x1040FF)
            ImageIO.write(image, "png", File(dir, "probe2048-$seed.png"))
            val lakes = world.rivers.lakes
            var lakeCells = 0
            for (i in 0 until w * h) if (lakes.isLake(i)) lakeCells++
            val largest = lakes.lakes.maxOfOrNull { it.cellCount } ?: 0
            println(
                "PROBE2048 seed=$seed rivers=${world.rivers.rivers.size} lakes=${lakes.lakes.size} " +
                    "endorheic=${lakes.lakes.count { it.endorheic }} " +
                    "lakeShare=${"%.3f".format(lakeCells * 100.0 / world.sea.landCellCount)}% " +
                    "largest=${"%.4f".format(largest * 100.0 / (w * h))}% of map realms=${world.nations.nations.size}"
            )
        }
    }
}
