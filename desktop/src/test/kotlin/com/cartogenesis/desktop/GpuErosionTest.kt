package com.cartogenesis.desktop

import com.cartogenesis.worldgen.model.Acceleration
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.pipeline.ErosionStage
import com.cartogenesis.worldgen.pipeline.erodeBlocking
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import kotlin.math.abs
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What the graphics card buys, and what it costs.
 *
 * Neither number can be asserted tightly. The speed belongs to whatever device is present, and the
 * agreement with the CPU is deliberately not exact — that is the premise of the whole feature. So
 * this measures both and holds them only to the claims the UI makes: that it is substantially
 * faster, and that the world is the same world.
 *
 * On a machine with no usable device the accelerator reports itself unavailable and this reports
 * that instead of failing, since a headless CI runner is not a broken build.
 */
class GpuErosionTest {

    @Test
    fun `gpu erosion matches the cpu closely and runs far faster`() {
        val result = GpuErosion.createOrNull()
        val gpu = result.accelerator
        if (gpu == null) {
            println("GPU unavailable here: ${result.unavailableBecause}")
            return
        }
        println("GPU device: ${gpu.name}")

        val config = WorldGenConfig(seed = 234475L, width = 512, height = 512)
            .atResolution(1024, 1024)
        val uplift = PlateStage.generate(config, TerrainStage.generate(config)).height

        val onCpu: FloatArray
        val cpuMs = measureTimeMillis {
            onCpu = erodeBlocking(config, uplift).height.data
        }

        val gpuConfig = config.copy(
            erosion = config.erosion.copy(acceleration = Acceleration.GPU)
        )
        // Once to warm the driver, then the measurement.
        erodeBlocking(gpuConfig, uplift, gpu)
        val onGpu: FloatArray
        val gpuMs = measureTimeMillis {
            onGpu = erodeBlocking(gpuConfig, uplift, gpu).height.data
        }

        println("GPU ${gpuMs}ms vs CPU ${cpuMs}ms: ${"%.1f".format(cpuMs.toDouble() / gpuMs)}x")

        // Same world, different arithmetic. Elevation runs 0..1, so compare in those terms.
        var worst = 0f
        var total = 0.0
        for (i in onCpu.indices) {
            val delta = abs(onCpu[i] - onGpu[i])
            if (delta > worst) worst = delta
            total += delta.toDouble()
        }
        val mean = total / onCpu.size
        println("GPU vs CPU terrain: mean difference %.6f, worst %.6f (elevation is 0..1)".format(mean, worst))

        assertTrue(gpuMs < cpuMs, "the GPU was not faster: ${gpuMs}ms vs ${cpuMs}ms")
        assertTrue(
            worst < 0.02f,
            "GPU terrain diverged from the CPU by $worst, which is more than a rounding difference"
        )
    }

    @Test
    fun `how far a world drifts when the gpu generates it`() {
        val result = GpuErosion.createOrNull()
        val gpu = result.accelerator
        if (gpu == null) {
            println("GPU unavailable here: ${result.unavailableBecause}")
            return
        }

        // The terrain difference is tiny, but the stages after it are not smooth functions of it.
        // Sea level is a percentile, river routing picks a single steepest neighbour per cell, and
        // depression filling walks a queue in elevation order — each can turn a difference far
        // below anything visible into a different decision. This measures whether it does, which
        // is what decides whether a GPU world can be saved as a seed or has to carry its terrain.
        val config = WorldGenConfig(seed = 234475L, width = 512, height = 512)
            .atResolution(1024, 1024)
        val onCpu = WorldGenerationEngine.generateBlocking(config)
        val onGpu = WorldGenerationEngine.generateBlocking(
            config.copy(erosion = config.erosion.copy(acceleration = Acceleration.GPU)),
            accelerator = gpu
        )

        var differingCells = 0
        for (i in onCpu.sea.isLand.indices) {
            if (onCpu.sea.isLand[i] != onGpu.sea.isLand[i]) differingCells++
        }
        var differingOwners = 0
        for (i in onCpu.nations.nationId.indices) {
            if (onCpu.nations.nationId[i] != onGpu.nations.nationId[i]) differingOwners++
        }

        println(
            "GPU world drift: coastline differs in %d of %d cells (%.4f%%), rivers %d vs %d, realm ownership differs in %.2f%% of cells"
                .format(
                    differingCells, onCpu.sea.isLand.size,
                    differingCells * 100.0 / onCpu.sea.isLand.size,
                    onCpu.rivers.rivers.size, onGpu.rivers.rivers.size,
                    differingOwners * 100.0 / onCpu.nations.nationId.size
                )
        )
    }
}
