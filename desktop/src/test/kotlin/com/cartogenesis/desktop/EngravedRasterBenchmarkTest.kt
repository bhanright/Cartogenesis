package com.cartogenesis.desktop

import com.cartogenesis.cartography.MapRasterizer
import com.cartogenesis.cartography.MapStyle
import com.cartogenesis.cartography.RasterRecipe
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

/**
 * What a style costs to draw, at export sizes, on each path.
 *
 * The engraving is more arithmetic than the hatch it replaced — a distance field over the whole
 * grid, and nine lattice cells asked per land pixel instead of one modulo — so the question is how
 * much more. Atlas is measured beside it as the same machine's baseline, since the raster's absolute
 * speed depends on the device and the comparison does not, and twice: once under the sky model,
 * which asks the terrain twenty-four more questions a land pixel for its horizon, and once under
 * the single lamp, which is what the raster cost before that.
 *
 * Off unless asked for, like the rest of the benchmarks here:
 *
 * ```
 * ./gradlew :desktop:test --tests "*EngravedRasterBenchmarkTest*" -Pbenchmark=true
 * ```
 */
class EngravedRasterBenchmarkTest {

    @Test
    fun `the raster at 2048`() {
        skipUnlessBenchmarking()
        measure(2048)
    }

    @Test
    fun `the raster at 4096`() {
        skipUnlessBenchmarking()
        measure(4096)
    }

    private fun measure(side: Int) {
        val world: WorldMap
        val generation = measureTimeMillis {
            world = WorldGenerationEngine.generateBlocking(
                WorldGenConfig(seed = 42L, width = side, height = side)
            )
        }
        println("ENGRAVED $side: world generated in ${generation / 1000.0}s")

        val found = GpuRaster.createOrNull()
        val gpu = found.accelerator
        if (gpu == null) println("ENGRAVED GPU unavailable here: ${found.unavailableBecause}")
        else println("ENGRAVED GPU device: ${gpu.name}")

        val cases = listOf(
            "Pen and ink" to RenderOptions(style = MapStyle.PEN_AND_INK),
            "Atlas under the sky" to RenderOptions(style = MapStyle.ATLAS),
            "Atlas under one lamp" to RenderOptions(style = MapStyle.ATLAS, singleLamp = true)
        )
        cases.forEach { (label, options) ->
            // Warm the JIT once, then measure. The first raster of a run pays for class loading and
            // for the collector's first look at a buffer this size.
            MapRasterizer.rasterize(world, options)
            val cpuMillis = measureTimeMillis { MapRasterizer.rasterize(world, options) }

            var recipeMillis = -1L
            var gpuMillis = -1L
            if (gpu != null) {
                var recipe = RasterRecipe.of(world, options)
                recipeMillis = measureTimeMillis { recipe = RasterRecipe.of(world, options) }
                val ready = recipe!!
                runBlocking { gpu.rasterize(ready) }
                gpuMillis = measureTimeMillis { runBlocking { gpu.rasterize(ready) } }
            }

            println(
                "ENGRAVED $side $label: CPU ${cpuMillis}ms, " +
                    "recipe ${recipeMillis}ms + GPU ${gpuMillis}ms"
            )
        }
    }
}
