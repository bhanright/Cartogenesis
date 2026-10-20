package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.CrustMap
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.TectonicHistory
import com.cartogenesis.worldgen.pipeline.TerrainStage
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.util.Locale
import kotlin.test.Test
import org.junit.Assert.assertTrue

/**
 * What the tectonic history costs (docs/DESIGN_LEDGER.md, TH1-1), reported rather than asserted:
 * the history, the map pass and the whole plate stage at 1,024 rows, against main's plate stage's
 * 2.65 s and the design's estimate of about 5 s, and a whole world's time and peak heap at the
 * same grid.
 *
 * Each is timed [RUNS] times and the fastest kept, since the first run pays for the classes' loading
 * and the compiler's warming, which a user's second world does not. The audit tier, since it
 * generates a 1,024-row world for a printed report.
 */
class TectonicHistoryAuditTest {

    @Test
    fun `report what the history costs at 1024 rows`() {
        val config = WorldGenConfig.forRows(SEED, SharedWorlds.DETAIL_ROWS).atResolution(2 * ROWS, ROWS)
        val terrain = TerrainStage.generate(config)
        var historyMs = Long.MAX_VALUE
        var mapMs = Long.MAX_VALUE
        var stageMs = Long.MAX_VALUE
        repeat(RUNS) {
            var started = System.nanoTime()
            val history = TectonicHistory.run(config)
            historyMs = minOf(historyMs, (System.nanoTime() - started) / NANOS_PER_MS)
            started = System.nanoTime()
            CrustMap.read(config, history)
            mapMs = minOf(mapMs, (System.nanoTime() - started) / NANOS_PER_MS)
            started = System.nanoTime()
            PlateStage.generate(config, terrain)
            stageMs = minOf(stageMs, (System.nanoTime() - started) / NANOS_PER_MS)
        }
        println(
            "HISTORY COST seed %d at %d rows: history %d ms, map pass %d ms, plate stage %d ms (main's 2,650; the design's estimate about 5,000)"
                .format(Locale.ROOT, SEED, ROWS, historyMs, mapMs, stageMs)
        )

        val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }
        System.gc()
        pools.forEach { it.resetPeakUsage() }
        val started = System.nanoTime()
        val world = WorldGenerationEngine.generateBlocking(config)
        val worldMs = (System.nanoTime() - started) / NANOS_PER_MS
        val peakBytes = pools.sumOf { it.peakUsage.used }
        println(
            "HISTORY COST seed %d at %d rows: a whole world %d ms, heap at its peak %.2f GB (summed over the heap's pools' own peaks)"
                .format(Locale.ROOT, SEED, ROWS, worldMs, peakBytes / BYTES_PER_GB)
        )
        assertTrue(world.plates.plates.isNotEmpty())
    }

    private companion object {
        const val SEED = 42L
        const val ROWS = 1024
        const val RUNS = 3
        const val NANOS_PER_MS = 1_000_000L
        const val BYTES_PER_GB = 1_073_741_824.0
    }
}
