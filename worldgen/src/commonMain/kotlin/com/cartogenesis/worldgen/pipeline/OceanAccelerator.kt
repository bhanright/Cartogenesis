package com.cartogenesis.worldgen.pipeline

/**
 * Somewhere other than the CPU to solve the wind-driven stream function.
 *
 * The solve is a Poisson problem relaxed a few thousand times over a small grid, which is per-cell
 * arithmetic and nothing else, so it is work a graphics card does well. As with erosion, the
 * engine knows nothing about how that is done: this interface is the entire seam, and the
 * implementation lives with the front end that has the platform's graphics API.
 *
 * Anything implementing this is expected to produce *approximately* the CPU's answer and not
 * exactly it — different hardware rounds differently and may reorder a sum — which is why
 * acceleration is opt-in and why an accelerated world carries its ocean in the save.
 */
interface OceanAccelerator {

    /** Shown to the user, so it should name the actual device where that is knowable. */
    val name: String

    /**
     * Relaxes a stream function from zero and returns it, or null if this accelerator cannot do
     * the job after all — no device, no driver, a grid it cannot fit — in which case the caller
     * falls back to the CPU. Returning null is a normal outcome, not an error path.
     *
     * [isWater] and [forcing] are row-major over [cellsAcross] by [cellsDown] cells and neither is
     * modified; the result is a separate array of the same shape. [forcing] is the curl of the wind
     * stress, which is what the discrete Laplacian of the stream function must equal, in the same
     * arbitrary stream units as the result. [overRelaxation] is dimensionless and already bounded
     * by the caller to the range that converges.
     *
     * The grid is the world's own cylinder: columns wrap east to west and rows clamp at the poles.
     * Water outside the mask is pinned at zero, which is what closes a basin against its coasts.
     * [passes] red-black Gauss-Seidel passes are run, each updating one colour and then the other,
     * because over-relaxation above one diverges on Jacobi.
     *
     * Suspending for the same reason [ErosionAccelerator] is: WebGPU hands back promises for its
     * device, its queue and every read of a buffer, and Kotlin/Wasm has no way to block on one. An
     * accelerator that happens to be synchronous simply never suspends.
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
