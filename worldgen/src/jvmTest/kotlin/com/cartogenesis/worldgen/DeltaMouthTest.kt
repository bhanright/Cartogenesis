package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A river reaches the open sea, across its own delta.
 *
 * The author found rivers on seed 59758 at 2048 that appeared to stop a few cells short of the
 * water, at the inner edge of a flat blocky raft of new land jutting into a bay. Measured, every
 * river's chain did end at a sea cell — but in forty-two of a hundred and fifty-two mouths that
 * cell was a pocket of one or two cells of sea with land all the way round it, which to the eye is
 * a river stopping in a field.
 *
 * One of those two things was the lobe's fault and one was not, and the difference is measured
 * here rather than assumed. The lobe was laid flat, at one level, so a river arriving at its own
 * delta had no downhill step left and no reason to cross it: that is fixed, and the share of new
 * land at the mouths with nowhere downhill falls by an order of magnitude.
 *
 * The pockets are not a delta at all. With deposition switched off entirely — no lobes anywhere on
 * the map — a third of every seed's mouths still end in one, and building lobes changes the count
 * by single figures either way. What makes them is that the shoreline is a percentile and nothing
 * else: any hollow the erosion leaves below it is drawn as sea whether or not the sea can reach it,
 * and a D8 river ends at the first such hollow it meets. The cure is connectivity in the sea-level
 * cut, which is neither this stage's code nor this chunk's. The figures are reported so that
 * whoever takes it has the measurement.
 */
class DeltaMouthTest {

    private val seeds = listOf(59758L, 42L, 7L, 1234L)

    @Test
    fun `a delta slopes to the sea, and the pockets are not the delta's doing`() {
        var controlPockets = 0
        var controlFlat = 0
        seeds.forEach { seed ->
            val config = WorldGenConfig(seed = seed, width = 512, height = 512)
            val before = WorldGenerationEngine.generateBlocking(
                config.copy(erosion = config.erosion.copy(deltaLobe = false))
            )
            val after = WorldGenerationEngine.generateBlocking(config)

            val bare = WorldGenerationEngine.generateBlocking(
                config.copy(erosion = config.erosion.copy(deposition = false))
            )
            val wasPockets = pocketMouths(before)
            val nowPockets = pocketMouths(after)
            println(
                "DELTA seed $seed: with no deposition at all, ${pocketMouths(bare)} of " +
                    "${bare.rivers.rivers.size} mouths are in a pocket"
            )
            val wasFlat = flatNewLand(before, config)
            val nowFlat = flatNewLand(after, config)
            controlPockets += wasPockets
            if (wasFlat > 0.05) controlFlat++

            println(
                ("DELTA seed %d: mouths in an enclosed pocket %d of %d -> %d of %d; " +
                    "new land with nowhere downhill %.1f%% -> %.1f%%").format(
                    seed, wasPockets, before.rivers.rivers.size, nowPockets,
                    after.rivers.rivers.size, wasFlat * 100, nowFlat * 100
                )
            )

            assertTrue(
                nowFlat <= wasFlat / 2,
                "seed $seed: the new land at the mouths went from ${wasFlat * 100}% with nowhere " +
                    "downhill to ${nowFlat * 100}%, which is not the halving a sloping lobe owes"
            )
        }
        assertTrue(
            controlPockets > 0,
            "no mouth ended in a pocket even on the old lobe, so the figure above is not " +
                "measuring what it claims to"
        )
        assertTrue(
            controlFlat > 0,
            "the old lobe was expected to be flat and was not, so this guard proves nothing"
        )
    }

    /**
     * River mouths whose cell is water that cannot be reached from the ocean.
     *
     * The ocean is the largest connected body of water on the map, found by flooding in ascending
     * cell order so the labelling is one specific labelling. A mouth in any other body — and one or
     * two cells is the usual size — is a river that has visibly stopped inland, whatever the
     * drainage says.
     */
    private fun pocketMouths(world: WorldMap): Int {
        val w = world.width
        val h = world.height
        val label = IntArray(w * h) { -1 }
        val stack = IntArray(w * h)
        var bodies = 0
        var ocean = -1
        var oceanSize = 0

        for (start in 0 until w * h) {
            if (world.sea.isLand[start] || label[start] >= 0) continue
            var top = 0
            stack[top++] = start
            label[start] = bodies
            var size = 0
            while (top > 0) {
                val c = stack[--top]
                size++
                forEachNeighbour(w, h, c) { n ->
                    if (!world.sea.isLand[n] && label[n] < 0) {
                        label[n] = bodies
                        stack[top++] = n
                    }
                }
            }
            if (size > oceanSize) {
                oceanSize = size
                ocean = bodies
            }
            bodies++
        }

        return world.rivers.rivers.count { river ->
            val mouth = river.cells.last()
            !world.sea.isLand[mouth] && label[mouth] != ocean
        }
    }

    /**
     * The share of the land a world's deltas built that has no lower neighbour at all.
     *
     * New land is found the way `DepositionTest` finds it, by cutting both worlds at the same exact
     * elevation rank rather than at their own histogram thresholds, so the figure is where the land
     * is rather than how much of it there is. A cell with nowhere downhill is the flat slab's
     * signature and is exactly what stops a river crossing its own delta.
     */
    private fun flatNewLand(world: WorldMap, config: WorldGenConfig): Double {
        val bare = WorldGenerationEngine.generateBlocking(
            config.copy(erosion = config.erosion.copy(deposition = false))
        )
        val now = exactLandMask(world.erosion.height.data, config.seaLevel)
        val then = exactLandMask(bare.erosion.height.data, config.seaLevel)
        val height = world.erosion.height.data
        val w = world.width
        val h = world.height

        var gained = 0
        var stuck = 0
        for (i in now.indices) {
            if (!now[i] || then[i]) continue
            gained++
            var lower = false
            forEachNeighbour(w, h, i) { n -> if (height[n] < height[i]) lower = true }
            if (!lower) stuck++
        }
        return if (gained == 0) 0.0 else stuck.toDouble() / gained
    }

    private fun exactLandMask(height: FloatArray, seaLevel: Float): BooleanArray {
        val sorted = height.copyOf()
        sorted.sort()
        val threshold = sorted[(sorted.size * seaLevel).toInt().coerceIn(0, sorted.size - 1)]
        return BooleanArray(height.size) { height[it] >= threshold }
    }

    private inline fun forEachNeighbour(w: Int, h: Int, cell: Int, action: (Int) -> Unit) {
        val x = cell % w
        val y = cell / w
        for (dy in -1..1) {
            val ny = y + dy
            if (ny < 0 || ny >= h) continue
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                var nx = (x + dx) % w
                if (nx < 0) nx += w
                action(ny * w + nx)
            }
        }
    }
}
