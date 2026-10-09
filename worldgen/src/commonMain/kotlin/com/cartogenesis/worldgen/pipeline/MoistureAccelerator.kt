package com.cartogenesis.worldgen.pipeline

/**
 * Somewhere other than the processor to march the rain.
 *
 * The march is one column after another along the wind, every row of a column at once: a
 * wavefront whose rows are independent within a column but for the fluxes across their faces, so a
 * graphics card can carry a run of rows as one workgroup, a barrier per column. As with erosion and
 * the ocean, the engine knows nothing about how that is done: this interface is the whole seam, and
 * the implementation lives with the front end that has the platform's graphics API.
 *
 * **The processor's [MoistureMarch.run] is the reference.** Anything implementing this is held to
 * its answer by a test in the manner of `GpuErosionTest`: the rain's worst and mean difference
 * from the processor's on the standard worlds under a stated tolerance, and the budget's closure
 * asserted on the device's own ledger, since a march that conserves on the processor and leaks on
 * the card would pass a comparison of fields and fail the closure.
 *
 * Not yet called by the engine: the device path is the next chunk's (docs/TODO.md), and until it
 * exists the climate stage runs the processor's march.
 */
interface MoistureAccelerator {

    /** Shown to the user, so it should name the actual device where that is knowable. */
    val name: String

    /**
     * Marches [inputs] as [MoistureMarch.run] does and returns its result, or null if this
     * accelerator cannot do the job after all, in which case the caller runs the processor's
     * march. Returning null is a normal outcome, not an error path. [inputs] is not modified.
     *
     * Suspending, as the other seams are, so an implementation that waits on a device can.
     */
    suspend fun march(inputs: MoistureMarch.Inputs): MoistureMarch.Result?
}
