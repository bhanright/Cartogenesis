package com.cartogenesis.worldgen.pipeline

/**
 * Somewhere other than the CPU to run the erosion sweeps.
 *
 * Erosion is the great majority of a generation and is a pure stencil over independent cells, so
 * it is the one stage where hardware acceleration is worth the trouble. The engine deliberately
 * knows nothing about how that is done: this interface is the entire seam, and the implementation
 * lives with the front end that has the platform APIs to do it.
 *
 * Anything implementing this is expected to produce *approximately* the CPU's answer and not
 * exactly it — different hardware rounds differently, fuses multiplies and adds differently, and
 * may reorder a sum. That is the whole reason acceleration is opt-in and recorded in the save.
 */
interface ErosionAccelerator {

    /** Shown to the user, so it should name the actual device where that is knowable. */
    val name: String

    /**
     * Runs [passes] sweeps and returns the new heights, or null if this accelerator cannot do the
     * job after all — no device, no driver, a grid it cannot fit — in which case the caller falls
     * back to the CPU. Returning null is a normal outcome, not an error path.
     *
     * [heights] must not be modified; the result is a separate array.
     *
     * Suspending, because the obvious second implementation cannot be anything else: WebGPU hands
     * back promises for its device, its queue and every read of a buffer, and Kotlin/Wasm has no
     * way to block on one. An accelerator that happens to be synchronous simply never suspends.
     */
    suspend fun erode(
        width: Int,
        height: Int,
        heights: FloatArray,
        talus: Float,
        passes: Int,
        rate: Float
    ): FloatArray?
}
