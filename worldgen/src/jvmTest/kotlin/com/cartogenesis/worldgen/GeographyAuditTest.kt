package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.ClimateStage
import kotlin.math.abs
import kotlin.test.Test

/**
 * Audits generated worlds against the rules real geography follows — the ones fantasy maps are
 * usually caught breaking.
 *
 * This measures rather than asserts. Several of these properties hold structurally (D8 routing
 * cannot produce a river that splits), but "cannot happen by construction" is a claim worth
 * checking against actual output, and the numbers show which rules the pipeline honours by
 * accident rather than by design.
 */
class GeographyAuditTest {

    private val seeds = listOf(7L, 42L, 1234L, 99L)

    @Test
    fun `audit worlds against real-world geography`() {
        seeds.forEach { seed ->
            val world = WorldGenerationEngine.generate(
                WorldGenConfig(seed = seed, width = 512, height = 512)
            )
            val w = world.width
            val h = world.height
            println("AUDIT ---- seed $seed ----")

            // 1. Rivers must merge, never split. A split would be one cell with two different
            //    downstream cells somewhere in the drawn network.
            val downstream = HashMap<Int, MutableSet<Int>>()
            world.rivers.rivers.forEach { river ->
                for (k in 0 until river.cells.size - 1) {
                    downstream.getOrPut(river.cells[k]) { HashSet() }.add(river.cells[k + 1])
                }
            }
            val splits = downstream.count { it.value.size > 1 }
            println("AUDIT rivers that split: $splits")

            // 2. Rivers must not run uphill. Routing uses depression-filled elevation, so this
            //    checks the *raw* surface — where a river crosses a filled basin it is, strictly,
            //    flowing across ground that does not slope downhill.
            var uphillSegments = 0
            var totalSegments = 0
            var worstRise = 0f
            world.rivers.rivers.forEach { river ->
                for (k in 0 until river.cells.size - 1) {
                    // A segment inside a lake is not drawn, so it cannot look like uphill flow.
                    if (world.rivers.lakes.isLake(river.cells[k]) &&
                        world.rivers.lakes.isLake(river.cells[k + 1])
                    ) continue
                    val a = world.sea.relativeElevation.data[river.cells[k]]
                    val b = world.sea.relativeElevation.data[river.cells[k + 1]]
                    totalSegments++
                    if (b > a) {
                        uphillSegments++
                        worstRise = maxOf(worstRise, b - a)
                    }
                }
            }
            println(
                "AUDIT uphill segments on raw terrain: $uphillSegments / $totalSegments " +
                    "(worst rise ${"%.4f".format(worstRise)})"
            )

            // 3. Rivers must not run coast to coast. A river beginning beside the sea and ending
            //    in it would be a channel, not a river.
            val startsAtSea = world.rivers.rivers.count { river ->
                val source = river.cells.first()
                neighbours(source, w, h).any { !world.sea.isLand[it] }
            }
            println("AUDIT rivers rising on the shoreline: $startsAtSea")

            // 4. Deserts belong near the horse latitudes, around 30 degrees.
            var desertLatSum = 0.0
            var desertCells = 0
            var landLatSum = 0.0
            var landCells = 0
            var desertsInBand = 0
            for (i in 0 until w * h) {
                if (!world.sea.isLand[i]) continue
                val lat = abs(ClimateStage.latitudeOf(i / w, h))
                landCells++
                landLatSum += lat
                if (world.climate.biome[i] == Biome.DESERT) {
                    desertCells++
                    desertLatSum += lat
                    if (lat in 15f..45f) desertsInBand++
                }
            }
            if (desertCells > 0) {
                println(
                    "AUDIT desert mean latitude ${"%.1f".format(desertLatSum / desertCells)} deg " +
                        "vs land mean ${"%.1f".format(landLatSum / landCells)} deg; " +
                        "${desertsInBand * 100 / desertCells}% of desert sits in 15-45 deg"
                )
            }

            // 5. Capitals should sit on fresh water, a harbour, or both.
            val nations = world.nations.nations
            if (nations.isNotEmpty()) {
                val onCoast = nations.count { nation ->
                    neighbours(nation.capitalCell, w, h).any { !world.sea.isLand[it] }
                }
                val onRiver = nations.count { nation ->
                    world.rivers.rivers.any { it.cells.contains(nation.capitalCell) }
                }
                println(
                    "AUDIT capitals: ${onCoast}/${nations.size} coastal, " +
                        "${onRiver}/${nations.size} on a drawn river"
                )
            }

            // 6. Lakes. Depression filling removes them entirely, so a basin with no outlet to the
            //    sea becomes flat land rather than water — worth stating plainly.
            val enclosedWater = countEnclosedWater(world, w, h)
            println("AUDIT enclosed seas: $enclosedWater")

            val lakes = world.rivers.lakes.lakes
            val lakeCells = world.rivers.lakes.lakeId.count { it != -1 }
            println(
                "AUDIT lakes: ${lakes.size} covering $lakeCells cells " +
                    "(largest ${lakes.maxOfOrNull { it.cellCount } ?: 0})"
            )
            lakes.maxByOrNull { it.cellCount }?.let { biggest ->
                val cells = world.rivers.lakes.lakeId.indices.filter {
                    world.rivers.lakes.lakeId[it] == biggest.id
                }
                val cx = cells.map { it % w }.average().toInt()
                val cy = cells.map { it / w }.average().toInt()
                println("AUDIT largest lake centred at ($cx,$cy), ${biggest.cellCount} cells")
            }
        }
    }

    private fun neighbours(cell: Int, w: Int, h: Int): List<Int> {
        val x = cell % w
        val y = cell / w
        val out = ArrayList<Int>(8)
        for (dy in -1..1) {
            val ny = y + dy
            if (ny < 0 || ny >= h) continue
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                var nx = (x + dx) % w
                if (nx < 0) nx += w
                out.add(ny * w + nx)
            }
        }
        return out
    }

    /** Water bodies with no connection to the map edge — i.e. seas that are not the ocean. */
    private fun countEnclosedWater(
        world: com.cartogenesis.worldgen.model.WorldMap,
        w: Int,
        h: Int
    ): Int {
        val seen = BooleanArray(w * h)
        // Flood the ocean inward from the poles, which always touch open water on these maps.
        val stack = ArrayDeque<Int>()
        for (x in 0 until w) {
            listOf(x, (h - 1) * w + x).forEach { i ->
                if (!world.sea.isLand[i] && !seen[i]) { seen[i] = true; stack.addLast(i) }
            }
        }
        while (stack.isNotEmpty()) {
            val cell = stack.removeLast()
            neighbours(cell, w, h).forEach { n ->
                if (!world.sea.isLand[n] && !seen[n]) { seen[n] = true; stack.addLast(n) }
            }
        }
        // Anything still unvisited and wet is an inland sea.
        var bodies = 0
        val counted = BooleanArray(w * h)
        for (i in 0 until w * h) {
            if (world.sea.isLand[i] || seen[i] || counted[i]) continue
            bodies++
            val local = ArrayDeque<Int>()
            local.addLast(i); counted[i] = true
            while (local.isNotEmpty()) {
                val cell = local.removeLast()
                neighbours(cell, w, h).forEach { n ->
                    if (!world.sea.isLand[n] && !seen[n] && !counted[n]) {
                        counted[n] = true; local.addLast(n)
                    }
                }
            }
        }
        return bodies
    }
}
