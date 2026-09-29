package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.LakeResult
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * One level per connected body of water: every lake is one piece of water, and no two lakes with
 * different levels touch.
 *
 * A closed basin used to hold its water at one level over the lowest cells of the whole basin,
 * wherever they lay, so separate hollows below the basin's internal saddles were drawn as one lake
 * at one level: seed 42's lake 1 at 512 rows was three pockets of 983, 582 and 475 cells
 * (docs/DESIGN_LEDGER.md, L1). Each hollow is its own lake now, and two lakes are one surface only
 * where both reach the saddle between them, so a lake of several pieces, or two lakes of different
 * levels side by side, is the old rule showing through.
 *
 * Measured over the standard seeds at [SharedWorlds.DETAIL_ROWS], lakes being a figure of the
 * grid's detail, with eight-connected pieces, the connectivity the basins are found with.
 */
class LakeBodyTest : BorrowsSharedWorlds() {

    @Test
    fun `every lake is one body of water at one level`() {
        val failures = ArrayList<String>()
        SharedWorlds.STANDARD_SEEDS.forEach { seed ->
            val world = SharedWorlds.world(WorldGenConfig.forRows(seed, SharedWorlds.DETAIL_ROWS))
            val lakes = world.rivers.lakes
            val cellsAcross = world.width
            val cellsDown = world.height
            val pieces = IntArray(lakes.lakes.size)
            val seen = BooleanArray(lakes.lakeId.size)
            val touching = HashSet<Pair<Int, Int>>()
            val stack = ArrayList<Int>()
            for (start in lakes.lakeId.indices) {
                val id = lakes.lakeId[start]
                if (id == LakeResult.NO_LAKE || seen[start]) continue
                pieces[id]++
                seen[start] = true
                stack.add(start)
                while (stack.isNotEmpty()) {
                    val cell = stack.removeAt(stack.size - 1)
                    val column = cell % cellsAcross
                    val row = cell / cellsAcross
                    for (rowStep in -1..1) for (columnStep in -1..1) {
                        if (rowStep == 0 && columnStep == 0) continue
                        val atRow = row + rowStep
                        if (atRow < 0 || atRow >= cellsDown) continue
                        val neighbour = atRow * cellsAcross + (column + columnStep).mod(cellsAcross)
                        val other = lakes.lakeId[neighbour]
                        if (other == id && !seen[neighbour]) {
                            seen[neighbour] = true
                            stack.add(neighbour)
                        } else if (other != LakeResult.NO_LAKE && other != id &&
                            lakes.lakes[other].surfaceElevation != lakes.lakes[id].surfaceElevation
                        ) {
                            touching.add(minOf(id, other) to maxOf(id, other))
                        }
                    }
                }
            }
            val split = pieces.indices.filter { pieces[it] > 1 }
            println(
                "LAKE BODIES seed %d at %d rows: %d lakes, %d in more than one piece %s, %d pairs at two levels touching"
                    .format(seed, SharedWorlds.DETAIL_ROWS, lakes.lakes.size, split.size,
                        split.take(6).map { "lake $it: ${pieces[it]} pieces of ${lakes.lakes[it].cellCount} cells" },
                        touching.size)
            )
            if (split.isNotEmpty()) failures += "seed $seed: ${split.map { "lake $it in ${pieces[it]} pieces" }}"
            if (touching.isNotEmpty()) failures += "seed $seed: lakes at two levels touching ${touching.take(6)}"
        }
        assertTrue(failures.isEmpty(), "a body of water holds more than one level: $failures")
    }

}
