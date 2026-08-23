package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.LandmarkStage
import com.cartogenesis.worldgen.pipeline.NationStage
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.RiverStage
import com.cartogenesis.worldgen.pipeline.SeaLevelStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import kotlin.system.measureTimeMillis
import org.junit.Test

/**
 * Where the time actually goes, per stage and per resolution.
 *
 * This exists to answer a specific question: which stages would a GPU help? Broadly, the ones
 * that touch every cell independently (noise, blur, the FFT) parallelise well, and the ones built
 * on a priority queue walking a graph in order (depression filling, flow accumulation, realm
 * expansion) do not. Knowing the split is what decides whether GPU work is worth doing at all.
 */
class StageProfileTest {

    @Test
    fun `report per-stage timings`() {
        // Warm the JIT so the first size measured is not paying for compilation.
        WorldGenerationEngine.generate(WorldGenConfig(seed = 1L, width = 256, height = 256))

        listOf(512, 1024, 2048).forEach { size ->
            val config = WorldGenConfig(seed = 42L, width = 128, height = 128)
                .atResolution(size, size)

            var terrain: com.cartogenesis.worldgen.pipeline.TerrainResult? = null
            var plates: com.cartogenesis.worldgen.pipeline.PlateResult? = null
            var sea: com.cartogenesis.worldgen.pipeline.SeaLevelResult? = null
            var climate: com.cartogenesis.worldgen.pipeline.ClimateResult? = null
            var rivers: com.cartogenesis.worldgen.pipeline.RiverResult? = null
            var nations: com.cartogenesis.worldgen.pipeline.NationResult? = null

            val timings = LinkedHashMap<String, Long>()
            timings["terrain (noise + FFT)"] = measureTimeMillis {
                terrain = TerrainStage.generate(config)
            }
            timings["tectonics"] = measureTimeMillis {
                plates = PlateStage.generate(config, terrain!!)
            }
            timings["sea level"] = measureTimeMillis {
                sea = SeaLevelStage.apply(plates!!.height, config.seaLevel)
            }
            timings["climate"] = measureTimeMillis {
                climate = ClimateStage.generate(config, sea!!)
            }
            timings["rivers"] = measureTimeMillis {
                rivers = RiverStage.generate(config, sea!!, climate!!)
            }
            timings["realms"] = measureTimeMillis {
                nations = NationStage.generate(config, sea!!, climate!!, rivers!!)
            }
            timings["landmarks"] = measureTimeMillis {
                LandmarkStage.generate(config, sea!!, climate!!, rivers!!, plates!!, nations!!)
            }

            val total = timings.values.sum().coerceAtLeast(1)
            println("PROFILE size=$size total=${total}ms")
            timings.forEach { (name, ms) ->
                println("PROFILE   %-24s %6d ms  %4.1f%%".format(name, ms, ms * 100.0 / total))
            }

            val heap = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
            println("PROFILE   heap in use: ${heap / 1024 / 1024} MB")
        }
    }
}
