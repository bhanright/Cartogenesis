package com.cartogenesis.web

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ErosionStage
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import kotlin.math.abs
import kotlin.time.measureTime

/**
 * Checks the WebGPU path against the CPU, in the browser, and reports what it finds.
 *
 * This exists because there is nowhere else to run it. The desktop GPU path has a normal test, but
 * a browser's device can only be reached from a page, so `?selftest` in the URL runs the same
 * comparison here: erode one terrain both ways, and report how long each took and how far apart
 * the answers are.
 *
 * The answers are expected to differ slightly — that is the premise of the whole feature — so this
 * reports the size of the difference rather than asserting there is none.
 */
internal suspend fun runSelfTest(accelerator: WebGpuErosion?): String {
    if (accelerator == null) return "SELFTEST no WebGPU device available"

    val config = WorldGenConfig(seed = 234475L, width = 512, height = 512)
    val uplift = PlateStage.generate(config, TerrainStage.generate(config)).height

    // Bisect before comparing: a zero-sweep run must hand the input straight back, which
    // separates a broken exchange from a broken shader.
    val roundTrip = accelerator.erode(
        config.width, config.height, uplift.data, config.erosion.talus, 0, config.erosion.rate
    ) ?: return "SELFTEST WebGPU declined a zero-sweep run"
    var roundTripWorst = 0f
    for (i in uplift.data.indices) {
        val delta = abs(uplift.data[i] - roundTrip[i])
        if (delta > roundTripWorst) roundTripWorst = delta
    }

    // And one sweep against one sweep, which isolates the shader from the ping-pong.
    val oneConfig = config.copy(erosion = config.erosion.copy(passes = 1))
    val oneCpu = ErosionStage.apply(oneConfig, uplift).height.data
    val oneGpu = accelerator.erode(
        config.width, config.height, uplift.data, config.erosion.talus, 1, config.erosion.rate
    ) ?: return "SELFTEST WebGPU declined a one-sweep run"
    var oneWorst = 0f
    for (i in oneCpu.indices) {
        val delta = abs(oneCpu[i] - oneGpu[i])
        if (delta > oneWorst) oneWorst = delta
    }

    var onCpu = FloatArray(0)

    val cpu = measureTime { onCpu = ErosionStage.apply(config, uplift).height.data }

    // Once to compile the shaders and warm the device, then the measurement.
    accelerator.erode(config.width, config.height, uplift.data, config.erosion.talus, 4, config.erosion.rate)

    var onGpu: FloatArray? = null
    val gpu = measureTime {
        onGpu = accelerator.erode(
            config.width, config.height, uplift.data,
            config.erosion.talus, config.erosion.passes, config.erosion.rate
        )
    }
    val gpuResult = onGpu ?: return "SELFTEST WebGPU declined the job"

    var worst = 0f
    var total = 0.0
    for (i in onCpu.indices) {
        val delta = abs(onCpu[i] - gpuResult[i])
        if (delta > worst) worst = delta
        total += delta.toDouble()
    }

    return "SELFTEST device=${accelerator.name} " +
        "roundTripWorst=$roundTripWorst oneSweepWorst=$oneWorst " +
        "cpu=${cpu.inWholeMilliseconds}ms gpu=${gpu.inWholeMilliseconds}ms " +
        "passes=${config.erosion.passes} " +
        "meanDelta=${total / onCpu.size} worstDelta=$worst"
}
