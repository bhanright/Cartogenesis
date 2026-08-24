package com.cartogenesis.web

import com.cartogenesis.worldgen.model.Acceleration
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
 * The two runs go through the whole erosion stage, differing only in what the config asks for.
 * Comparing the accelerator against the stage directly — which is what this did at first — stopped
 * being a fair test the moment hydraulic erosion was added, because the accelerator only ever
 * carries the thermal sweeps and the stage now does both. It did not fail; it reported a
 * disagreement of 0.18 where the truth was seven parts in a million, which is worse than failing.
 *
 * The answers are still expected to differ slightly — that is the premise of the whole feature —
 * so this reports the size of the difference rather than asserting there is none.
 */
internal suspend fun runSelfTest(accelerator: WebGpuErosion?): String {
    if (accelerator == null) return "SELFTEST no WebGPU device available"

    val config = WorldGenConfig(seed = 234475L, width = 512, height = 512)
    val uplift = PlateStage.generate(config, TerrainStage.generate(config)).height
    val gpuConfig = config.copy(
        erosion = config.erosion.copy(acceleration = Acceleration.GPU)
    )

    // A zero-sweep run must hand the input straight back, which separates a broken exchange across
    // the wasm boundary from a broken shader.
    val roundTrip = accelerator.erode(
        config.width, config.height, uplift.data, config.erosion.talus, 0, config.erosion.rate
    ) ?: return "SELFTEST WebGPU declined a zero-sweep run"
    var roundTripWorst = 0f
    for (i in uplift.data.indices) {
        val delta = abs(uplift.data[i] - roundTrip[i])
        if (delta > roundTripWorst) roundTripWorst = delta
    }

    var onCpu = FloatArray(0)
    val cpu = measureTime { onCpu = ErosionStage.apply(config, uplift).height.data }

    // Once to compile the shaders and warm the device, then the measurement.
    ErosionStage.apply(gpuConfig, uplift, accelerator)
    var onGpu = FloatArray(0)
    val gpu = measureTime {
        onGpu = ErosionStage.apply(gpuConfig, uplift, accelerator).height.data
    }

    var worst = 0f
    var total = 0.0
    for (i in onCpu.indices) {
        val delta = abs(onCpu[i] - onGpu[i])
        if (delta > worst) worst = delta
        total += delta.toDouble()
    }

    return "SELFTEST device=${accelerator.name} roundTripWorst=$roundTripWorst " +
        "cpu=${cpu.inWholeMilliseconds}ms gpu=${gpu.inWholeMilliseconds}ms " +
        "meanDelta=${total / onCpu.size} worstDelta=$worst"
}
