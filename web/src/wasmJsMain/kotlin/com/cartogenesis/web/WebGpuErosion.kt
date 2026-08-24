package com.cartogenesis.web

import com.cartogenesis.worldgen.pipeline.ErosionAccelerator

/**
 * Runs the erosion sweeps on the browser's graphics device.
 *
 * The counterpart to the desktop's OpenGL path, and the same algorithm again: two dispatches per
 * sweep, ping-ponging between a pair of storage buffers, with one readback at the end. The desktop
 * writes GLSL and this writes WGSL, but they compute the same thing, and both are the CPU's rule
 * for how far a slope can stand before it fails.
 *
 * This is why the accelerator seam suspends. Asking for a device is a promise, and so is every
 * read of a buffer, and Kotlin/Wasm cannot block on either.
 *
 * As on the desktop, the arithmetic is not the CPU's. A world generated here carries its terrain
 * in the save rather than relying on being regenerated from its seed.
 */
class WebGpuErosion private constructor(
    private val device: JsHandle,
    private val label: String
) : ErosionAccelerator {

    override val name: String get() = label

    override suspend fun erode(
        width: Int,
        height: Int,
        heights: FloatArray,
        talus: Float,
        passes: Int,
        rate: Float
    ): FloatArray? {
        // Crossing into JavaScript is per-element, so this is the one genuinely wasteful part of
        // the exchange. It is still far cheaper than the sweeps it replaces: at 512 the CPU spends
        // seconds on those, and this copies a quarter of a million floats twice.
        val input = allocateFloats(heights.size)
        for (i in heights.indices) setFloat(input, i, heights[i])

        val result = awaitPromise(
            runErosion(device, width, height, input, talus, passes, rate)
        )
        if (result == null || isNullish(result)) return null

        return FloatArray(heights.size) { getFloat(result, it) }
    }

    /** What probing the browser found: an accelerator, or the reason there is not one. */
    class Result(val accelerator: WebGpuErosion?, val unavailableBecause: String?)

    companion object {
        suspend fun createOrNull(): Result {
            if (!webGpuPresent()) {
                return Result(
                    null,
                    "this browser has no WebGPU. Chrome and Edge have it; Safari and Firefox " +
                        "need it enabling, and a page served over plain HTTP may not offer it."
                )
            }
            val device = awaitPromise(requestDevice())
            if (device == null || isNullish(device)) {
                return Result(null, "the browser offered no graphics device")
            }
            return Result(WebGpuErosion(device, deviceLabel(device)), null)
        }
    }
}
