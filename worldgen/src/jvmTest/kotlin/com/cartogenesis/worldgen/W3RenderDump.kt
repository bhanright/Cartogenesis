package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.ClimateStage
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test

/**
 * W3's renders: the author's two worlds at 2048 in the Rainfall and Biomes views, whole and
 * cropped on the largest continent's deep interior.
 *
 * A harness, not a guard, and the thing that decides the chunk: the complaint W3 answers is about
 * a picture — "a fairly low degree of variation in precipitation within the continents, with the
 * only real variation being along coastlines" — and a coefficient of variation cannot settle it.
 *
 * Written so that it compiles against the code either side of W3, so the same crop can be taken
 * from the branch point and from the branch and the two put side by side. The crop window is
 * chosen from the world rather than typed in, so it lands on the same ground both times: the
 * largest landmass, and the cell of it that is furthest from any sea.
 *
 * Set `W3_RENDER_LABEL` to `before` or `after` to say which run this is. Excluded from the
 * per-merge tier by name, like the rest of the render harness.
 */
class W3RenderDump {

    private val label: String = System.getenv("W3_RENDER_LABEL") ?: "after"
    private val outputDir = File("../desktop/build/w3-crops/$label")

    @Test
    fun `dump the author's worlds at 2048`() {
        outputDir.mkdirs()
        listOf(718106L, 59758L).forEach { seed ->
            val started = System.currentTimeMillis()
            val world = WorldGenerationEngine.generateBlocking(
                WorldGenConfig(seed = seed, width = 2048, height = 2048)
            )
            println(
                "W3 RENDER $label seed $seed at 2048 in ${(System.currentTimeMillis() - started) / 1000}s"
            )
            reportInterior(seed, world)

            val views = listOf(
                "rainfall" to rainfallImage(world),
                "biome" to biomeImage(world)
            )
            views.forEach { (view, image) ->
                write(halved(image), "$seed-$view-whole-$label.png")
            }
            val window = largestContinentInterior(world)
            views.forEach { (view, image) ->
                write(
                    image.getSubimage(window.x, window.y, window.width, window.height),
                    "$seed-$view-interior-$label.png"
                )
            }
        }
    }

    /**
     * What the interior of this world actually reads, in millimetres, so the picture has numbers
     * beside it: the mean, the spread and the driest and wettest tenth of land more than five
     * hundred kilometres from the sea.
     */
    private fun reportInterior(seed: Long, world: WorldMap) {
        val distance = ClimateStage.waterDistance(world.config, world.sea)
        val reachCells = world.config.cellsFor(500.0)
        val totals = ArrayList<Double>()
        for (cell in 0 until world.width * world.height) {
            if (!world.sea.isLand[cell]) continue
            if (distance.data[cell] < reachCells) continue
            totals.add(world.climate.precipitationMm.data[cell].toDouble())
        }
        if (totals.size < 100) {
            println("W3 INTERIOR $label seed $seed: no interior to measure")
            return
        }
        totals.sort()
        val mean = totals.average()
        val spread = kotlin.math.sqrt(totals.sumOf { (it - mean) * (it - mean) } / totals.size)
        println(
            ("W3 INTERIOR %s seed %d: %d cells, mean %.0f mm, coefficient of variation %.3f, " +
                "driest tenth %.0f mm, wettest tenth %.0f mm")
                .format(
                    label, seed, totals.size, mean, spread / mean,
                    totals[totals.size / 10], totals[totals.size * 9 / 10]
                )
        )
    }

    private class Window(val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * A square window on the deep interior of the world's largest landmass.
     *
     * The landmass is found by flooding from every land cell in row-major order and keeping the
     * biggest, which is the same answer on every platform; the window is centred on its cell
     * furthest from any sea, which is where the complaint says the map reads as nothing at all.
     */
    private fun largestContinentInterior(world: WorldMap): Window {
        val cellsAcross = world.width
        val cellsDown = world.height
        val side = 768
        val seen = BooleanArray(cellsAcross * cellsDown)
        val distance = ClimateStage.waterDistance(world.config, world.sea)
        var bestCentre = 0
        var bestSize = -1
        val stack = ArrayDeque<Int>()
        for (start in 0 until cellsAcross * cellsDown) {
            if (seen[start] || !world.sea.isLand[start]) continue
            stack.addLast(start)
            seen[start] = true
            var size = 0
            var deepest = start
            var deepestDistance = -1f
            while (stack.isNotEmpty()) {
                val cell = stack.removeLast()
                size++
                if (distance.data[cell] > deepestDistance) {
                    deepestDistance = distance.data[cell]
                    deepest = cell
                }
                val row = cell / cellsAcross
                val column = cell % cellsAcross
                val neighbours = intArrayOf(
                    row * cellsAcross + (column + 1) % cellsAcross,
                    row * cellsAcross + (column + cellsAcross - 1) % cellsAcross,
                    if (row > 0) (row - 1) * cellsAcross + column else -1,
                    if (row < cellsDown - 1) (row + 1) * cellsAcross + column else -1
                )
                neighbours.forEach { next ->
                    if (next >= 0 && !seen[next] && world.sea.isLand[next]) {
                        seen[next] = true
                        stack.addLast(next)
                    }
                }
            }
            if (size > bestSize) {
                bestSize = size
                bestCentre = deepest
            }
        }
        val x = ((bestCentre % cellsAcross) - side / 2).coerceIn(0, cellsAcross - side)
        val y = ((bestCentre / cellsAcross) - side / 2).coerceIn(0, cellsDown - side)
        println(
            "W3 CROP $label: largest landmass $bestSize cells, interior at cell $bestCentre, " +
                "window $x,$y"
        )
        return Window(x, y, side, side)
    }

    /**
     * Rainfall over land, dry sand through green to blue over 0 to 3000 mm, with the sea flat and
     * dark so the eye reads the land alone.
     *
     * A ramp of its own rather than one of the atlas's styles, because the question this render
     * has to answer is whether the interior carries structure at all, and a style that tints by
     * biome would answer a different one.
     */
    private fun rainfallImage(world: WorldMap): BufferedImage {
        val image = BufferedImage(world.width, world.height, BufferedImage.TYPE_INT_RGB)
        for (cell in 0 until world.width * world.height) {
            val colour = if (!world.sea.isLand[cell]) {
                0x101820
            } else {
                val wet = (world.climate.precipitationMm.data[cell] / 3000f).coerceIn(0f, 1f)
                val red = (220 * (1f - wet) + 20 * wet).toInt()
                val green = (200 * (1f - wet) + 110 * wet).toInt()
                val blue = (130 * (1f - wet) + 200 * wet).toInt()
                (red shl 16) or (green shl 8) or blue
            }
            image.setRGB(cell % world.width, cell / world.width, colour)
        }
        return image
    }

    private fun biomeImage(world: WorldMap): BufferedImage {
        val image = BufferedImage(world.width, world.height, BufferedImage.TYPE_INT_RGB)
        for (cell in world.climate.biome.indices) {
            image.setRGB(cell % world.width, cell / world.width, colourOf(world.climate.biome[cell]))
        }
        return image
    }

    private fun colourOf(biome: Biome): Int = when (biome) {
        Biome.OCEAN -> 0x1B3B6F
        Biome.SHALLOW_OCEAN -> 0x2E6DA4
        Biome.ICE_SHEET -> 0xEFF4F7
        Biome.TUNDRA -> 0x9DB3A8
        Biome.TAIGA -> 0x3F6B4A
        Biome.TEMPERATE_FOREST -> 0x4C8C3F
        Biome.TEMPERATE_RAINFOREST -> 0x2F6B33
        Biome.GRASSLAND -> 0xA8B863
        Biome.SHRUBLAND -> 0xB8A35C
        Biome.DESERT -> 0xD9C48A
        Biome.SAVANNA -> 0xC6B24E
        Biome.TROPICAL_SEASONAL_FOREST -> 0x4F9B3A
        Biome.TROPICAL_RAINFOREST -> 0x1F6B2B
        Biome.ALPINE -> 0x8C8C8C
        Biome.MEDITERRANEAN -> 0xC08A4A
        Biome.MONSOON_FOREST -> 0x3E8B57
    }

    /** Half size, so a whole 2048 world is a picture a reader can take in at once. */
    private fun halved(image: BufferedImage): BufferedImage {
        val half = BufferedImage(image.width / 2, image.height / 2, BufferedImage.TYPE_INT_RGB)
        val graphics = half.createGraphics()
        graphics.drawImage(image, 0, 0, half.width, half.height, null)
        graphics.dispose()
        return half
    }

    private fun write(image: BufferedImage, name: String) {
        val file = File(outputDir, name)
        ImageIO.write(image, "png", file)
        println("W3 RENDER wrote ${file.absolutePath}")
    }
}
