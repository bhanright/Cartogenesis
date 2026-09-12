package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import kotlin.system.measureTimeMillis
import kotlin.test.Test

/**
 * What the tectonic history costs, at the resolutions the app exports at.
 *
 * Split out of [TectonicHistoryTest] and into T1's audit tier because it generates the tectonic
 * stage sixteen times at 1024 and 2048 and reports rather than asserts — the shape of thing that
 * tier exists for. Everything [TectonicHistoryTest] actually guards runs at 512 and stays in the
 * per-merge tier.
 *
 * Rule 8 of the plan asks for the measurement before the GPU decision, and this is it. Belt
 * stamping and the ageing blur are per-cell passes and would go behind the accelerator seam if the
 * numbers warranted it; what the numbers say is that they are not where the time goes. An epoch
 * costs about 0.9 s at 2048, of which the assignment, the pair classification and the jump-flood
 * distance field — Voronoi labelling, hash lookups and a label-propagating sweep, all of which
 * rule 8 puts on the CPU by design — measure 0.6 s on their own. The per-cell work a GPU could
 * take is the remainder, so the best a perfect accelerator could do is take a 2.8 s stage to about
 * 1.9 s, on a 2048 generation whose erosion is measured in tens of seconds. G4 declined a GPU path
 * at +0.77 s on the same grounds; this is the same decision with the same kind of evidence.
 */
class TectonicHistoryAuditTest {

    @Test
    fun `report the cost of a history`() {
        // Warm the JIT, so the first size measured is not paying for compilation.
        val warm = WorldGenConfig(seed = 1L, width = 512, height = 512)
        PlateStage.generate(warm, TerrainStage.generate(warm))

        listOf(1024, 2048).forEach { size ->
            val base = WorldGenConfig(seed = 42L, width = 512, height = 512)
                .atResolution(size, size)
            val terrain = TerrainStage.generate(base)
            // Best of three, because a single run of a stage this size is at the mercy of a
            // garbage collection and the numbers are being used to decide something.
            fun best(times: Int = 3, body: () -> Unit): Long =
                (1..times).minOf { measureTimeMillis(body) }

            listOf(1, 2, 3, 4).forEach { epochs ->
                val config = base.copy(tectonics = base.tectonics.copy(historyEpochs = epochs))
                val ms = best { PlateStage.generate(config, terrain) }
                println("HISTORY cost $size epochs=$epochs tectonics ${ms} ms")
            }
            // What an epoch is actually made of. Everything before the stamp — the Voronoi
            // assignment and its domain warp, the pair classification, the jump-flood distance
            // field — is graph, hash and label work that stays on the CPU by rule 8; only what is
            // left over is the per-cell pass a GPU could take. Measured rather than assumed, which
            // is what the rule asks for before the decision.
            val setup = best { PlateStage.epochBoundaries(base, 1) }
            println("HISTORY cost $size one epoch's assignment, classification and distance $setup ms")
        }
    }
}
