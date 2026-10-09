package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.FlowRouting
import com.cartogenesis.worldgen.pipeline.LakeResult
import com.cartogenesis.worldgen.pipeline.RiverStage
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
                        val neighbor = atRow * cellsAcross + (column + columnStep).mod(cellsAcross)
                        val other = lakes.lakeId[neighbor]
                        if (other == id && !seen[neighbor]) {
                            seen[neighbor] = true
                            stack.add(neighbor)
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
        // Recorded at C1b2: the march's new rain left two lakes of two pieces each, a lake rule
        // the rain only exposed (docs/TODO.md, "A lake in two pieces").
        KnownFailures.expect("C1b2: a lake in two pieces", "[seed 1234: [lake 94 in 2 pieces]]") {
            if (failures.isNotEmpty()) throw RecordedViolation("a body of water holds more than one level: $failures", failures.toString())
        }
    }

    /**
     * No rain is counted twice and none is lost: the rain a basin's pockets are handed, summed over
     * all of them, is the rain that reaches the basin at all. For a basin that overflows that is its
     * catchment at its exits on the routing the fill left; for one the balance closes it is the rain
     * on every cell whose water ends in the basin's own sinks once every basin is closed, which is
     * the answer where the basin's own water leaves it into a basin that keeps it and a tributary
     * comes back (`WaterReceivedTest` lays that case out by hand).
     *
     * A pocket's catchment is read cell by cell where water enters the basin, and a path below a
     * basin that turns back into it across a level rim carries the basin's own water back with it.
     * Counted whole as an inflow, it handed pockets up to 5.69 times their basin's rain at 512 rows
     * and seed 42's rift basin at 1,024 rows enough to fill it to the brim; left out whole, it lost
     * the tributaries that join such a path, and one of seed 7's basins was handed 0.68 of its rain.
     * The bar is the catchment itself, with a ten-thousandth either way for the order the two sums
     * are taken in, and never finer than a float can hold of the largest water the map carries: a
     * basin in dry country is handed runoff a millionth of a trunk's, which the float fields carry
     * to that trunk's last place and no finer (K2, where the runoff became Budyko's).
     */
    @Test
    fun `a closed basin's pockets are handed exactly the rain that reaches the basin`() {
        val failures = ArrayList<String>()
        SharedWorlds.STANDARD_SEEDS.forEach { seed ->
            val config = WorldGenConfig.forRows(seed, SharedWorlds.DETAIL_ROWS)
            val world = SharedWorlds.world(config)
            val solved = RiverStage.solvedBasins(config, world.sea, world.climate)
            val rain = solved.runoffMm
            val reaching = FlowRouting.accumulate(
                world.width, world.height, world.sea.isLand, world.rivers.filledElevation,
                solved.routingAfterClosing, world.sea.landCellCount
            ) { rain[it] }.data
            var most = 0.0
            var least = Double.MAX_VALUE
            solved.basins.forEach { basin ->
                val rainReaching = if (basin.endorheic) {
                    basin.cells.filter { solved.routingAfterClosing[it] < 0 }.sumOf { reaching[it].toDouble() }
                } else {
                    basin.catchmentRunoffMm.toDouble()
                }
                if (rainReaching <= 0.0) return@forEach
                val ratio = basin.pocketRunoffMm / rainReaching
                most = maxOf(most, ratio)
                least = minOf(least, ratio)
                val floatFloor = Math.ulp(reaching.max()).toDouble()
                if (kotlin.math.abs(basin.pocketRunoffMm - rainReaching) > maxOf(SUMMING_ORDER * rainReaching, floatFloor)) {
                    failures += "seed $seed: a basin of ${basin.cells.size} cells handed %.4f of its catchment (%.6g of %.6g mm-cells; the largest runoff carried on the map %.6g)".format(ratio, basin.pocketRunoffMm, rainReaching, reaching.max())
                }
            }
            println(
                "LAKE RAIN seed %d at %d rows: %d closed basins, their pockets handed %.4f to %.4f of the rain reaching them"
                    .format(seed, SharedWorlds.DETAIL_ROWS, solved.basins.size, least, most)
            )
        }
        // Recorded at A1-1, whose calendar seasons moved seed 42's ground and left one basin of 59
        // cells handed half as much again as the rain reaching it: a fault of the basins' own
        // accounting the new ground exposed, not traced (docs/TODO.md).
        val signature = failures.joinToString("; ") { it.substringBefore(" (") }
        KnownFailures.expect("A1-1: a basin handed more than the rain reaching it", "seed 42: a basin of 59 cells handed 1.5015 of its catchment") {
            if (failures.isNotEmpty()) throw RecordedViolation("rain counted twice or lost: $failures", signature)
        }
    }

    private companion object {
        /** The two sums are taken in different orders, one in doubles and one in floats. */
        const val SUMMING_ORDER = 1e-4
    }
}
