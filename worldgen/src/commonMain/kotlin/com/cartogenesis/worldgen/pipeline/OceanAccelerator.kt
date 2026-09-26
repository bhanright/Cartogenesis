package com.cartogenesis.worldgen.pipeline

/**
 * Somewhere other than the CPU to solve the wind-driven stream function.
 *
 * The solve is Stommel's balance relaxed on every grid of a multigrid cycle, which is per-cell
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
     * Runs [passes] red-black Gauss-Seidel passes of [stencil] from [start] and returns the
     * result, or null if this accelerator cannot do the job after all — no device, no driver, a
     * grid it cannot fit — in which case the caller runs the same passes on the processor.
     * Returning null is a normal outcome, not an error path.
     *
     * [start] is row-major over the stencil's grid and is not modified; the result is a separate
     * array of the same shape. Each pass updates one colour of `(column + row)` parity and then the
     * other, every water cell to `e ψ_east + w ψ_west + n ψ_north + s ψ_south - f`, with the four
     * weights read from the stencil's row and `f` from its cell.
     * Columns wrap, land is pinned at zero, and beyond either pole ψ is zero. [OceanCirculation.relax]
     * is the reference.
     *
     * Suspending for the same reason [ErosionAccelerator] is: WebGPU hands back promises for its
     * device, its queue and every read of a buffer, and Kotlin/Wasm has no way to block on one. An
     * accelerator that happens to be synchronous simply never suspends.
     */
    suspend fun solve(
        stencil: OceanStencil,
        start: FloatArray,
        passes: Int
    ): FloatArray?
}
