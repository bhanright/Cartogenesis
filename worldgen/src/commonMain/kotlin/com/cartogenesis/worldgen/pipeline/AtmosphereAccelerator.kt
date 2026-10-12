package com.cartogenesis.worldgen.pipeline

/**
 * Somewhere other than the processor to carry fields between the map's grid and the atmosphere's.
 *
 * Both directions are per ground cell: carrying down reads every ground cell once into the coarse
 * cell it overlaps, and carrying up evaluates a double Fourier series at every ground cell. Both are
 * sums with no dependence between their outputs, which is work a graphics card does well. As with
 * the ocean, the engine knows nothing about how that is done: this interface is the whole seam, and
 * the implementation lives with the front end that has the platform's graphics API.
 *
 * **The processor's [AtmosphereRemap] is the reference.** Anything implementing this is held to its
 * answer by a test in the manner of `GpuOceanTest`, within the rounding of single precision over the
 * sums each output is.
 *
 * Not yet called by the engine, though the boundary layer and the coupled loop carry fields both
 * ways every lap: with an upload and a readback a call the card was measured no faster than the
 * processor (docs/DESIGN_LEDGER.md, A1-2), and the loop's per-cell work wants its fields kept on the
 * device between laps first (docs/TODO.md). Until then the seam and its kernels are held to the
 * processor by their test alone.
 */
interface AtmosphereAccelerator {

    /** Shown to the user, so it should name the actual device where that is knowable. */
    val name: String

    /**
     * [AtmosphereRemap.areaMean] of [ground] on the device, or null if this accelerator cannot do
     * the job after all, in which case the caller runs the processor's. Returning null is a normal
     * outcome, not an error path. [ground] is not modified; the result is on the remap's coarse
     * centers, row-major.
     *
     * Suspending, as the other seams are, so an implementation that waits on a device can.
     */
    suspend fun areaMean(remap: AtmosphereRemap, ground: FloatArray): FloatArray?

    /**
     * [coefficients]' series at every ground cell of [remap]'s map, as [AtmosphereRemap.evaluate]
     * computes it, or null if this accelerator declines. Row-major on the map.
     */
    suspend fun toGround(remap: AtmosphereRemap, coefficients: DoubleFourierCoefficients): FloatArray?
}
