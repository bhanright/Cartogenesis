package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ErosionStage
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Skipping the settled parts of the map has to change nothing at all.
 *
 * The optimisation rests on an argument rather than a tolerance: a cell moves only if it or a
 * neighbour holds material above the critical slope, and material travels one cell per sweep, so
 * dilating the tiles that still hold excess covers everything that can change. If that argument is
 * wrong the output differs, so the test is exact equality — not "close enough".
 */
class ErosionSkipTest {

    @Test
    fun `skipping settled ground gives bit-identical terrain`() {
        val config = WorldGenConfig(seed = 234475L, width = 512, height = 512)
        val uplift = PlateStage.generate(config, TerrainStage.generate(config)).height

        val skipped = ErosionStage.apply(config, uplift, skipSettled = true).height.data
        val full = ErosionStage.apply(config, uplift, skipSettled = false).height.data

        assertEquals(full.size, skipped.size)
        var differing = 0
        var worst = 0f
        for (i in full.indices) {
            if (full[i] != skipped[i]) {
                differing++
                val delta = kotlin.math.abs(full[i] - skipped[i])
                if (delta > worst) worst = delta
            }
        }
        assertTrue(
            differing == 0,
            "skipping changed $differing of ${full.size} cells, worst by $worst"
        )
    }

    @Test
    fun `report what skipping settled ground saves`() {
        val config = WorldGenConfig(seed = 42L, width = 1024, height = 1024)
        val uplift = PlateStage.generate(config, TerrainStage.generate(config)).height

        // Warm the JIT, or the first measurement pays for compiling the inner loop.
        ErosionStage.apply(config, uplift, skipSettled = true)

        val skipped = measureTimeMillis { ErosionStage.apply(config, uplift, skipSettled = true) }
        val full = measureTimeMillis { ErosionStage.apply(config, uplift, skipSettled = false) }
        // Reported rather than asserted. The saving is real but modest and depends on how much of
        // the map has gone quiet, which depends on the seed and the resolution — a threshold here
        // would be measuring the machine and the seed more than the code.
        println("EROSION %d ms skipping settled ground vs %d ms scanning all of it: %.2fx"
            .format(skipped, full, full.toDouble() / skipped))
    }
}
