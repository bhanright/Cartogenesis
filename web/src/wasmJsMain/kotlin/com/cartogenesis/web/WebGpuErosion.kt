package com.cartogenesis.web

import com.cartogenesis.worldgen.pipeline.ErosionAccelerator

/** Placeholder while the browser front end is brought up; the real implementation follows. */
class WebGpuErosion private constructor() : ErosionAccelerator {

    override val name: String get() = "WebGPU"

    override suspend fun erode(
        width: Int,
        height: Int,
        heights: FloatArray,
        talus: Float,
        passes: Int,
        rate: Float
    ): FloatArray? = null

    class Result(val accelerator: WebGpuErosion?, val unavailableBecause: String?)

    companion object {
        suspend fun createOrNull(): Result = Result(null, "not implemented yet")
    }
}
