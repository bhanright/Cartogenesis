package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.concurrent.parallelism
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.erodeBlocking
import com.cartogenesis.worldgen.pipeline.ErosionStage
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import kotlin.math.sqrt
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * How many sweeps erosion actually needs, and whether the threads are doing anything.
 *
 * Thermal erosion approaches an equilibrium — every slope at or below the critical angle — and
 * approaches it asymptotically, so the sweep count is a question with a real answer rather than a
 * taste setting. This reports how far from equilibrium the terrain still is at various counts, so
 * the default can be chosen where the curve flattens instead of guessed.
 */
class ErosionConvergenceTest {

    @Test
    fun `report convergence against sweep count`() {
        val config = WorldGenConfig(seed = 234475L, width = 1024, height = 1024)
            .let { it.copy(erosion = it.erosion.copy(passes = 0)) }
        val uplift = PlateStage.generate(config, TerrainStage.generate(config)).height

        println("EROSION parallelism reported as ${parallelism()}")

        listOf(18, 40, 80, 160, 320).forEach { passes ->
            val cfg = config.copy(erosion = config.erosion.copy(passes = passes, enabled = true))
            lateinit var result: com.cartogenesis.worldgen.pipeline.ErosionResult
            val ms = measureTimeMillis { result = erodeBlocking(cfg, uplift) }
            val (over, worst) = disequilibrium(cfg, result.height.data)
            println(
                "EROSION passes=%3d  %5d ms  %.2f%% of cells still above the critical slope, worst %.1fx over"
                    .format(passes, ms, over * 100, worst)
            )
        }
    }

    @Test
    fun `the sweep loop is split into one chunk per worker`() {
        // Erosion is the most expensive stage, so if its sweeps were not being divided up that
        // would be the largest thing left on the table.
        //
        // This checks the division rather than the threads. Counting distinct thread names looked
        // like the more direct test and was written first, but it fails intermittently: waiting on
        // a ForkJoinPool task lets the waiting thread help run the queue, so short chunks can all
        // end up on one thread quite legitimately. How the work is partitioned is the part that is
        // actually under this code's control, and it is deterministic.
        if (parallelism() <= 1) {
            // A CI runner is often allocated two cores, which leaves the common pool with a
            // parallelism of one. Dividing the work there would be pure overhead, so the
            // sequential path is the correct behaviour and there is nothing to assert.
            println("EROSION single worker available; nothing to divide")
            return
        }

        val ranges = java.util.Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())
        val cells = 4096
        com.cartogenesis.worldgen.concurrent.parallelChunks(0, cells) { start, end ->
            ranges.add(start to end)
        }

        println("EROSION sweep split into ${ranges.size} chunks across ${parallelism()} workers")
        assertTrue(ranges.size > 1, "parallelChunks did not divide the work at all")

        // The chunks must tile the range exactly: no cell done twice, none missed.
        val ordered = ranges.sortedBy { it.first }
        assertTrue(ordered.first().first == 0, "chunks did not start at the beginning")
        assertTrue(ordered.last().second == cells, "chunks did not reach the end")
        ordered.zipWithNext().forEach { (a, b) ->
            assertTrue(a.second == b.first, "chunks ${a} and ${b} do not meet")
        }
    }

    /** Fraction of cells whose steepest drop still exceeds the critical slope, and by how much. */
    private fun disequilibrium(config: WorldGenConfig, data: FloatArray): Pair<Double, Double> {
        val w = config.width
        val h = config.height
        val limit = config.erosion.talus / w
        val diagonal = limit * sqrt(2f)
        var over = 0
        var worst = 0f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val here = data[y * w + x]
                for (n in 0 until 8) {
                    val ny = y + intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)[n]
                    if (ny < 0 || ny >= h) continue
                    val nx = (x + intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)[n] + w) % w
                    val drop = here - data[ny * w + nx]
                    val cap = if (n < 4) limit else diagonal
                    if (drop > cap) {
                        over++
                        if (drop / cap > worst) worst = drop / cap
                        break
                    }
                }
            }
        }
        return over.toDouble() / (w * h) to worst.toDouble()
    }
}
