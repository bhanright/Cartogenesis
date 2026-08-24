package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.test.Test

/**
 * Classifies how every river ends.
 *
 * The existing correctness test accepts "ran off the polar edge" as a valid ending, which is why
 * it passes while rivers still visibly stop in the middle of a continent. This counts each case
 * separately so the visible problem can be told apart from the acceptable one.
 */
class RiverEndingsTest {

    /** Renders the map with every land-ending river mouth ringed, so they can be judged by eye. */
    @Test
    fun `mark land-ending river mouths`() {
        val world = WorldGenerationEngine.generateBlocking(
            WorldGenConfig(seed = 42L, width = 1024, height = 1024)
        )
        val w = world.width
        val h = world.height
        val image = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)

        for (i in 0 until w * h) {
            val land = world.sea.isLand[i]
            val e = world.sea.relativeElevation.data[i]
            val c = if (land) {
                val v = (140 + (e * 90).toInt()).coerceIn(0, 255)
                (v shl 16) or (v shl 8) or (v / 2)
            } else 0x14304A
            image.setRGB(i % w, i / w, c)
        }

        val g = image.createGraphics()
        g.setRenderingHint(
            java.awt.RenderingHints.KEY_ANTIALIASING,
            java.awt.RenderingHints.VALUE_ANTIALIAS_ON
        )
        g.color = java.awt.Color(0x3C7EA8)
        world.rivers.rivers.forEach { river ->
            for (k in 0 until river.cells.size - 1) {
                val a = river.cells[k]
                val b = river.cells[k + 1]
                if (kotlin.math.abs(a % w - b % w) > w / 2) continue
                g.stroke = java.awt.BasicStroke(river.widths[k].coerceAtLeast(1f))
                g.drawLine(a % w, a / w, b % w, b / w)
            }
        }

        // Ring each mouth that stops on land.
        g.stroke = java.awt.BasicStroke(1.5f)
        world.rivers.rivers.forEach { river ->
            val mouth = river.cells.last()
            if (!world.sea.isLand[mouth]) return@forEach
            g.color = java.awt.Color(0xE0, 0x30, 0x30)
            g.drawOval(mouth % w - 5, mouth / w - 5, 10, 10)
        }
        g.dispose()

        val dir = java.io.File("build/maps").apply { mkdirs() }
        javax.imageio.ImageIO.write(image, "png", java.io.File(dir, "river-endings.png"))
        println("ENDINGS image at ${dir.absolutePath}/river-endings.png")
    }

    /** Straight-line distance in cells to the nearest water, by expanding rings. */
    private fun distanceToWater(world: com.cartogenesis.worldgen.model.WorldMap, cell: Int): Int {
        val w = world.width
        val h = world.height
        val cx = cell % w
        val cy = cell / w
        for (r in 1..120) {
            for (dy in -r..r) {
                val y = cy + dy
                if (y < 0 || y >= h) continue
                for (dx in -r..r) {
                    if (kotlin.math.max(kotlin.math.abs(dx), kotlin.math.abs(dy)) != r) continue
                    var x = (cx + dx) % w
                    if (x < 0) x += w
                    if (!world.sea.isLand[y * w + x]) return r
                }
            }
        }
        return 999
    }

    @Test
    fun `report how rivers end`() {
        listOf(7L, 42L, 1234L).forEach { seed ->
            val world = WorldGenerationEngine.generateBlocking(
                WorldGenConfig(seed = seed, width = 512, height = 512)
            )
            val w = world.width
            val h = world.height

            // Every cell any drawn river passes through.
            val onRiver = HashSet<Int>()
            world.rivers.rivers.forEach { onRiver.addAll(it.cells.toList()) }

            var reachedSea = 0
            var joined = 0
            var polarEdge = 0
            var noOutflow = 0
            var stranded = 0

            world.rivers.rivers.forEach { river ->
                val mouth = river.cells.last()
                val y = mouth / w
                val target = world.rivers.flowTarget[mouth]
                when {
                    !world.sea.isLand[mouth] -> reachedSea++
                    target < 0 && (y == 0 || y == h - 1) -> polarEdge++
                    target < 0 -> noOutflow++
                    // Continues into a cell some other drawn river occupies: visually connected.
                    onRiver.contains(target) -> joined++
                    else -> stranded++
                }
            }

            // The one-step check is not enough: a river can join another that itself ends in a
            // gap. Follow the drainage all the way and see whether the *drawn* line is unbroken.
            var brokenChains = 0
            val examples = ArrayList<String>()
            world.rivers.rivers.forEach { river ->
                var cell = river.cells.last()
                var steps = 0
                while (world.sea.isLand[cell] && steps++ < w * h) {
                    val next = world.rivers.flowTarget[cell]
                    if (next < 0) break
                    if (!onRiver.contains(next) && world.sea.isLand[next]) {
                        brokenChains++
                        if (examples.size < 4) {
                            examples.add("(${cell % w},${cell / w})")
                        }
                        break
                    }
                    cell = next
                }
            }
            println("ENDINGS   broken drawn chains=$brokenChains at ${examples.joinToString()}")

            println(
                "ENDINGS seed=$seed rivers=${world.rivers.rivers.size} sea=$reachedSea " +
                    "joined=$joined polarEdge=$polarEdge noOutflow=$noOutflow stranded=$stranded"
            )

            // How far a land-ending river sits from open water. A river that "joins" another one
            // right beside the coast reads fine; one that stops deep inland is what looks wrong.
            val inlandDistances = world.rivers.rivers
                .map { it.cells.last() }
                .filter { world.sea.isLand[it] }
                .map { cell -> distanceToWater(world, cell) }
                .sorted()
            if (inlandDistances.isNotEmpty()) {
                println(
                    "ENDINGS   land-ending=${inlandDistances.size} " +
                        "median=${inlandDistances[inlandDistances.size / 2]} " +
                        "max=${inlandDistances.last()} cells from water"
                )
            }
        }
    }
}
