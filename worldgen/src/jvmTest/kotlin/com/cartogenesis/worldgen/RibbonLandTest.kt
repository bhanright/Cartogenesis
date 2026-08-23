package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * How much of the land is ribbon — long thin strips a couple of cells wide.
 *
 * Convergent boundaries raise a linear belt, and where that belt crosses a submerged region only
 * its crest clears sea level, leaving a strip of land with a strait on either side. Island arcs do
 * look like that, but real ones are segmented, curved and volcanic, not continuous ruler-edged
 * walls of uniform width.
 *
 * Measured as the share of land sitting within two cells of water, which is what "thin" means
 * here, alongside the longest single strip so a few big continents cannot hide a bad one.
 */
class RibbonLandTest {

    @Test
    fun `erosion widens the strips a belt leaves in shallow sea`() {
        val base = WorldGenConfig(seed = 234475L, width = 512, height = 512)
            .atResolution(1024, 1024)
        listOf(
            "no erosion" to base.copy(erosion = base.erosion.copy(enabled = false)),
            "eroded" to base
        ).map { (name, config) ->
            val world = WorldGenerationEngine.generate(config)
            val w = world.width
            val h = world.height
            val land = world.sea.isLand

            // Chebyshev distance from each land cell to the nearest water, by BFS from the coast.
            val depth = IntArray(w * h) { -1 }
            val queue = ArrayDeque<Int>()
            for (i in 0 until w * h) {
                if (!land[i]) continue
                var coastal = false
                val x = i % w
                val y = i / w
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny !in 0 until h) continue
                    for (dx in -1..1) {
                        if (!land[ny * w + ((x + dx + w) % w)]) coastal = true
                    }
                }
                if (coastal) { depth[i] = 1; queue.add(i) }
            }
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                val x = i % w
                val y = i / w
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny !in 0 until h) continue
                    for (dx in -1..1) {
                        val n = ny * w + ((x + dx + w) % w)
                        if (land[n] && depth[n] == -1) {
                            depth[n] = depth[i] + 1
                            queue.add(n)
                        }
                    }
                }
            }

            // Group the land into bodies, and judge each by its own half-width. A strip stays
            // shallow however long it runs; a continent does not.
            val component = IntArray(w * h) { -1 }
            var components = 0
            val area = ArrayList<Int>()
            val halfWidth = ArrayList<Int>()
            for (start in 0 until w * h) {
                if (!land[start] || component[start] != -1) continue
                val id = components++
                var cells = 0
                var deepest = 0
                val stack = ArrayDeque<Int>()
                stack.add(start)
                component[start] = id
                while (stack.isNotEmpty()) {
                    val i = stack.removeLast()
                    cells++
                    if (depth[i] > deepest) deepest = depth[i]
                    val x = i % w
                    val y = i / w
                    for (dy in -1..1) {
                        val ny = y + dy
                        if (ny !in 0 until h) continue
                        for (dx in -1..1) {
                            val n = ny * w + ((x + dx + w) % w)
                            if (land[n] && component[n] == -1) {
                                component[n] = id
                                stack.add(n)
                            }
                        }
                    }
                }
                area.add(cells)
                halfWidth.add(deepest)
            }

            // Scale-free: a strip is thin relative to the map, and long enough to be a feature.
            val thin = (w / 170f).coerceAtLeast(2f)
            val longEnough = w / 4

            val landCells = area.sum()
            var ribbonArea = 0
            var ribbonCount = 0
            var longest = 0
            for (id in 0 until components) {
                if (halfWidth[id] <= thin && area[id] >= longEnough) {
                    ribbonCount++
                    ribbonArea += area[id]
                    if (area[id] > longest) longest = area[id]
                }
            }
            val share = ribbonArea * 100.0 / landCells
            println(
                "RIBBON %-22s %d bodies, %d are strips (half-width <= %.0f) holding %.1f%% of land, largest strip %d cells"
                    .format(name, components, ribbonCount, thin, share, longest)
            )
            share
        }.let { (withoutErosion, withErosion) ->
            // Erosion cannot remove a strip, and is not meant to: a belt crossing shallow sea will
            // always leave land above water. What it does is take material off the crest and pile
            // it against the flanks, which widens the footprint until the strip stops being one.
            assertTrue(
                withErosion < withoutErosion,
                "erosion left as much ribbon land as before: $withErosion% vs $withoutErosion%"
            )
        }
    }
}
