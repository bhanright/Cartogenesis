package com.cartogenesis.worldgen.pipeline

/** A device that can solve the coarse wind-driven stream function, with the CPU as reference. */
interface OceanAccelerator {
    /** The device name, where the platform can supply it. */
    val name: String

    /**
     * Starts from zero and performs [passes] red-black Gauss-Seidel over-relaxation passes.
     * [isWater] and [forcing] are row-major arrays of [cellsAcross] * [cellsDown] cells;
     * forcing is the discrete Laplacian of the stream function in arbitrary stream units.
     * [overRelaxation] is dimensionless, already bounded by the caller to 0.5..1.95.
     *
     * Columns wrap and rows clamp. Land remains zero; colour zero precedes colour one each pass.
     * Neither input is modified. Returns a separate row-major stream array, or null to decline
     * (including odd widths whose wrapped neighbours do not form an independent colour).
     * Suspends because WebGPU device calls and readback return promises.
     */
    suspend fun solve(
        cellsAcross: Int,
        cellsDown: Int,
        isWater: BooleanArray,
        forcing: FloatArray,
        passes: Int,
        overRelaxation: Float
    ): FloatArray?
}
