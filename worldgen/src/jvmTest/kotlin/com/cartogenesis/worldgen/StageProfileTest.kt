package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import org.junit.Test

/**
 * Where the time actually goes, per stage and per resolution.
 *
 * This exists to answer a specific question: which stages would a GPU help? Broadly, the ones
 * that touch every cell independently (noise, blur, the FFT) parallelise well, and the ones built
 * on a priority queue walking a graph in order (depression filling, flow accumulation, realm
 * expansion) do not. Knowing the split is what decides whether GPU work is worth doing at all.
 *
 * Timed through the engine itself, by the progress callback it calls before each stage: a stage's
 * time runs from its own call to the next one's, and the last stage's to the world coming back. So
 * what is timed is the pipeline the app runs — the tectonic uplift under the hydraulic rounds, the
 * provisional climate and the ice inside the sea-level step, the peoples — and the total is a whole
 * generation. It used to call the stages one by one beside the engine, without the uplift, without
 * the ice and its provisional climate and without the peoples, so its total omitted whole stages
 * and the shares `docs/PERFORMANCE.md` quotes from it were divided by too little.
 */
class StageProfileTest {

    @Test
    fun `report per-stage timings`() {
        // Warm the JIT so the first size measured is not paying for compilation.
        WorldGenerationEngine.generateBlocking(WorldGenConfig(seed = 1L, width = 256, height = 256))

        listOf(512, 1024, 2048).forEach { size ->
            val config = WorldGenConfig(seed = 42L, width = 128, height = 128)
                .atResolution(size, size)

            val started = LinkedHashMap<GenerationStage, Long>()
            val begun = System.nanoTime()
            WorldGenerationEngine.generateBlocking(config) { stage, _, _ ->
                started[stage] = System.nanoTime()
            }
            val ended = System.nanoTime()

            val stages = started.keys.toList()
            val timings = LinkedHashMap<String, Long>()
            stages.forEachIndexed { index, stage ->
                val until = if (index + 1 < stages.size) started.getValue(stages[index + 1]) else ended
                timings[stage.shortLabel] = (until - started.getValue(stage)) / 1_000_000
            }
            val total = ((ended - begun) / 1_000_000).coerceAtLeast(1)
            println("PROFILE size=$size total=${total}ms, the whole generation")
            timings.forEach { (name, ms) ->
                println("PROFILE   %-24s %6d ms  %4.1f%%".format(name, ms, ms * 100.0 / total))
            }

            val heap = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
            println("PROFILE   heap in use: ${heap / 1024 / 1024} MB")
        }
    }
}
