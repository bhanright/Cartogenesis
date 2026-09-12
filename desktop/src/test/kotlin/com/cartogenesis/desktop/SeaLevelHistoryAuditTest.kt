package com.cartogenesis.desktop

import com.cartogenesis.cartography.MapStyle
import com.cartogenesis.cartography.MapView
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.ui.MapImage
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import java.io.File
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * H5 at the size the author exports at, rendered and measured.
 *
 * The 512 guards live in `:worldgen`'s `SeaLevelHistoryTest`; this is the pair of worlds the plan
 * named — 718106 and 59758, on the default settings, which are the author's — before and after, at
 * 2048, with the rivers painted. Two claims are asserted here and nowhere else: that no water the
 * ocean cannot reach and could be a lake survives the cut at 2048 either, and that the coast gains
 * estuaries there rather than only on the coarse grid. Everything else is printed for the report.
 *
 * In `:desktop` because the renderer lives here and because a 2048 world wants more heap than
 * `:worldgen`'s test worker is given, which is the same reason `OutletResolutionTest` is here.
 */
class SeaLevelHistoryAuditTest {

    private val seeds = listOf(718106L, 59758L)

    @Test
    fun `the author's own worlds gain estuaries and keep no pocket`() {
        val dir = File("build/sealevel").apply { mkdirs() }
        val gains = ArrayList<String>()
        seeds.forEach { seed ->
            val base = WorldGenConfig(seed = seed, width = 512, height = 512)
                .atResolution(2048, 2048)
            val before = WorldGenerationEngine.generateBlocking(
                base.copy(sea = base.sea.copy(lowstand = 0f, enclosedSeaIsLand = false))
            )
            val after = WorldGenerationEngine.generateBlocking(base)
            val was = Coast(before, "seed $seed at 2048 PRE-H5")
            val now = Coast(after, "seed $seed at 2048 H5    ")
            gains.add(
                "$seed estuaries ${was.estuaries} -> ${now.estuaries}, indentation " +
                    "${"%.2f".format(was.indentation)} -> ${"%.2f".format(now.indentation)}"
            )

            listOf("before" to before, "after" to after).forEach { (label, world) ->
                val bitmap = MapImage.toBitmap(
                    world,
                    RenderOptions(
                        view = MapView.FANTASY,
                        style = MapStyle.ATLAS,
                        showRivers = true,
                        riverScale = 2f
                    )
                )
                val data = Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)!!
                File(dir, "sealevel-$seed-$label.png").writeBytes(data.bytes)
                bitmap.close()
            }

            assertTrue(
                now.pockets == 0 && now.pocketMouths == 0,
                "seed $seed at 2048: ${now.pockets} pockets of water the ocean cannot reach " +
                    "survived the cut, with ${now.pocketMouths} river mouths in them"
            )
            assertTrue(
                now.estuaries > was.estuaries,
                "seed $seed at 2048: ${now.estuaries} river mouths inside an inlet against " +
                    "${was.estuaries} before the chunk"
            )
            // H5b, at the size the author exports at. A basin the enclosure rule converts from
            // unreachable sea to land is filled by the drainage to its sill, and until
            // `SeaConfig.postCutOutlet` nothing could cut that sill: the notch inside the
            // hydraulic rounds ran while the ground was still under the provisional sea, and the
            // water balance has nowhere to drain a floor that is already below sea level. On
            // 718106 at 2048 that left a Caspian-shaped lake filling a coastal rift trough, which
            // is what the render review after H5 and F6 singled out. The bar is the same one
            // `OutletIncisionTest` and `OutletResolutionTest` hold every other lake to: the
            // Caspian's 0.249% share of Earth's land.
            val drowned = largestDrownedShare(after)
            println(
                "SEA HISTORY 2048 seed %d: largest drowned basin %.4f%% of land, %.2fx the Caspian"
                    .format(seed, drowned * 100, drowned / caspianShare)
            )
            assertTrue(
                drowned < caspianShare,
                "seed $seed at 2048 keeps a basin below the sea-level cut holding " +
                    "${"%.4f".format(drowned * 100)}% of its land, " +
                    "${"%.2f".format(drowned / caspianShare)} times the Caspian's share"
            )
        }
        println("SEA HISTORY 2048 wrote ${dir.absolutePath}; $gains")
    }

    /** The Caspian's 371,000 km² against Earth's 148.94 M km² of land, as `OutletIncisionTest`. */
    private val caspianShare = 371_000.0 / 148_940_000.0

    /**
     * The largest lake standing on ground below the sea-level cut, as a share of the world's land.
     *
     * Below the cut is read off `erosion.height` against `sea.shorelineHeight` rather than off the
     * shoreline-relative field, because glaciation rewrites the second one between the cut and
     * here. The same split `OutletIncisionTest.drownedLakes` makes, for the same reason.
     */
    private fun largestDrownedShare(world: WorldMap): Double {
        val drowned = BooleanArray(world.rivers.lakes.lakes.size)
        val ground = world.erosion.height.data
        val cut = world.sea.shorelineHeight
        world.rivers.lakes.lakeId.forEachIndexed { cell, id ->
            if (id >= 0 && ground[cell] < cut) drowned[id] = true
        }
        val largest = world.rivers.lakes.lakes
            .filterIndexed { id, _ -> drowned[id] }
            .maxOfOrNull { it.cellCount } ?: 0
        return largest.toDouble() / world.sea.landCellCount
    }

    /** The same measurement `SeaLevelHistoryTest` makes at 512, copied because modules cannot share tests. */
    private class Coast(world: WorldMap, label: String) {
        val pockets: Int
        val pocketMouths: Int
        val estuaries: Int
        val indentation: Double

        init {
            val w = world.width
            val h = world.height
            val size = w * h
            val land = world.sea.isLand
            val scale = w / 512f
            val cap = (size * WorldGenConfig().sea.enclosedSeaMaxShare).toInt()

            val body = IntArray(size) { -1 }
            val stack = IntArray(size)
            val cellsIn = ArrayList<Int>()
            var bodies = 0
            var ocean = -1
            var oceanCells = 0
            for (start in 0 until size) {
                if (land[start] || body[start] >= 0) continue
                val id = bodies++
                var top = 0
                body[start] = id
                stack[top++] = start
                var found = 0
                while (top > 0) {
                    val c = stack[--top]
                    found++
                    neighbours(w, h, c) { n ->
                        if (!land[n] && body[n] < 0) {
                            body[n] = id
                            stack[top++] = n
                        }
                    }
                }
                cellsIn.add(found)
                if (found > oceanCells) {
                    oceanCells = found
                    ocean = id
                }
            }
            var pocketBodies = 0
            var pocketArea = 0
            var seaBodies = 0
            cellsIn.forEachIndexed { id, n ->
                if (id == ocean) return@forEachIndexed
                if (n <= cap) { pocketBodies++; pocketArea += n } else seaBodies++
            }
            pockets = pocketBodies

            // Narrow water, and how far into it each cell lies, by a walk inward from the open sea.
            val toLand = IntArray(size) { -1 }
            val queue = IntArray(size)
            var head = 0
            var tail = 0
            for (i in 0 until size) if (land[i]) { toLand[i] = 0; queue[tail++] = i }
            while (head < tail) {
                val i = queue[head++]
                if (toLand[i] >= 3) continue
                neighbours(w, h, i) { n -> if (toLand[n] < 0) { toLand[n] = toLand[i] + 1; queue[tail++] = n } }
            }
            val narrow = (2f * scale).toInt().coerceAtLeast(1)
            val inlet = (3f * scale).toInt().coerceAtLeast(1)
            val depth = IntArray(size) { -1 }
            head = 0; tail = 0
            for (i in 0 until size) {
                if (body[i] == ocean && (toLand[i] < 0 || toLand[i] > narrow)) {
                    depth[i] = 0
                    queue[tail++] = i
                }
            }
            while (head < tail) {
                val i = queue[head++]
                neighbours(w, h, i) { n ->
                    if (!land[n] && depth[n] < 0) { depth[n] = depth[i] + 1; queue[tail++] = n }
                }
            }

            var estuaryCount = 0
            var pocketMouthCount = 0
            world.rivers.rivers.forEach { river ->
                val mouth = river.cells.last()
                if (land[mouth]) return@forEach
                if (body[mouth] != ocean) {
                    if (cellsIn[body[mouth]] <= cap) pocketMouthCount++
                    return@forEach
                }
                if (depth[mouth] > inlet) estuaryCount++
            }
            estuaries = estuaryCount
            pocketMouths = pocketMouthCount

            var edges = 0
            for (i in 0 until size) {
                if (!land[i]) continue
                val x = i % w
                val y = i / w
                if (body[y * w + (x + 1) % w] == ocean) edges++
                if (body[y * w + (x + w - 1) % w] == ocean) edges++
                if (y > 0 && body[(y - 1) * w + x] == ocean) edges++
                if (y < h - 1 && body[(y + 1) * w + x] == ocean) edges++
            }
            val area = world.sea.landCellCount.toDouble()
            indentation = if (area <= 0) 0.0 else edges / (4.0 * sqrt(area))

            println(
                ("SEA HISTORY %s: %d estuary mouths, %d in a pocket; indentation %.4f; %d pockets " +
                    "of %d cells and %d inland seas; land %.5f; %d rivers, %d lakes").format(
                    label, estuaries, pocketMouths, indentation, pockets, pocketArea, seaBodies,
                    world.landFraction(), world.rivers.rivers.size, world.rivers.lakes.lakes.size
                )
            )
        }

        private inline fun neighbours(w: Int, h: Int, cell: Int, action: (Int) -> Unit) {
            val x = cell % w
            val y = cell / w
            for (dy in -1..1) {
                val ny = y + dy
                if (ny < 0 || ny >= h) continue
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    action(ny * w + ((x + dx + w) % w))
                }
            }
        }
    }
}
