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

/** Throwaway: seed 59758 at 2048 with the author's settings; classify where every drawn river ends. */
class SeedProbeRiver {
    @Test
    fun `probe river endings on seed 59758`() {
        val dir = File("build/maps").apply { mkdirs() }
        val base = WorldGenConfig(seed = 59758L, width = 512, height = 512, seaLevel = 0.62f)
        val config = base.copy(
            tectonics = base.tectonics.copy(plateCount = 14),
            nations = base.nations.copy(nationCount = 12)
        ).atResolution(2048, 2048)
        val world = WorldGenerationEngine.generateBlocking(config)
        val w = world.width
        val h = world.height
        val pixels = MapRasterizer.rasterize(world, RenderOptions())
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, w, h, pixels, 0, w)

        val isLand = world.sea.isLand
        val lakes = world.rivers.lakes
        val target = world.rivers.flowTarget
        val riverOf = IntArray(w * h) { -1 }
        for ((idx, r) in world.rivers.rivers.withIndex()) for (c in r.cells) riverOf[c] = idx
        for (i in 0 until w * h) if (riverOf[i] >= 0) image.setRGB(i % w, i / w, 0x1040FF)
        ImageIO.write(image, "png", File(dir, "probe59758-whole.png"))

        val counts = HashMap<String, Int>()
        val dead = ArrayList<Pair<Int, Int>>()
        val edgeReport = StringBuilder()
        for ((idx, r) in world.rivers.rivers.withIndex()) {
            val last = r.cells.last()
            val next = target[last]
            val kind = when {
                next < 0 -> "edge"
                !isLand[next] -> "sea"
                lakes.isLake(next) -> "lake"
                riverOf[next] >= 0 && riverOf[next] != idx -> "tributary"
                else -> "dead"
            }
            counts[kind] = (counts[kind] ?: 0) + 1
            if (kind == "dead") dead += idx to last
            if (kind == "edge") {
                val x = last % w; val y = last / w
                var seaN = 0; var minLand = Float.MAX_VALUE; var minSea = Float.MAX_VALUE
                val here = world.rivers.filledElevation.data[last]
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val n = ny * w + nx
                    if (isLand[n]) minLand = minOf(minLand, world.rivers.filledElevation.data[n])
                    else { seaN++; minSea = minOf(minSea, world.sea.relativeElevation.data[n]) }
                }
                if (y > 0 && y < h - 1) edgeReport.append(
                    "EDGE river=$idx len=${r.cells.size} end=($x,$y) filled=${"%.5f".format(here)} " +
                        "seaNeighbours=$seaN minSea=${if (seaN > 0) "%.5f".format(minSea) else "-"} " +
                        "minLandFilled=${"%.5f".format(minLand)} lakeHere=${lakes.isLake(last)} biome=${world.climate.biome[last]}\n"
                )
            }
        }
        print(edgeReport)
        println("PROBE59758 rivers=${world.rivers.rivers.size} endings=$counts lakes=${lakes.lakes.size}")
        for ((idx, last) in dead) {
            // Walk the flow from the dead end and report what it reaches within 64 steps.
            var c = last; var steps = 0; var reached = "land"
            while (steps < 64) {
                val n = target[c]
                if (n < 0) { reached = "edge"; break }
                if (!isLand[n]) { reached = "sea"; break }
                if (lakes.isLake(n)) { reached = "lake"; break }
                if (riverOf[n] >= 0 && riverOf[n] != idx) { reached = "river"; break }
                c = n; steps++
            }
            val r = world.rivers.rivers[idx]
            println(
                "DEAD river=$idx len=${r.cells.size} end=(${last % w},${last / w}) " +
                    "accEnd=${"%.1f".format(world.rivers.flowAccumulation.data[last])} " +
                    "accNext=${"%.1f".format(world.rivers.flowAccumulation.data[target[last]])} " +
                    "flowReaches=$reached in $steps steps elev=${"%.4f".format(world.sea.relativeElevation.data[last])} " +
                    "filled=${"%.4f".format(world.rivers.filledElevation.data[last])} biome=${world.climate.biome[last]}"
            )
        }
        var n = 0
        for ((_, last) in dead.sortedByDescending { world.rivers.rivers[it.first].cells.size }) {
            if (n >= 6) break
            val cx = (last % w - 100).coerceIn(0, w - 200); val cy = (last / w - 100).coerceIn(0, h - 200)
            val crop = BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until 600) for (x in 0 until 600) crop.setRGB(x, y, image.getRGB(cx + x / 3, cy + y / 3))
            ImageIO.write(crop, "png", File(dir, "probe59758-dead-${n++}-x${last % w}-y${last / w}.png"))
        }
    }
}
