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
 *
 * Only the thermal sweeps cross this seam today. The two-height closure's per-cell solve
 * (`GroundCells.closeChannels`, the rate [GroundClosure.channelRateMetresPerYear] solves for on
 * each channel cell) is the next per-cell kernel and is to join it, held to the processor's answer
 * by its own equation's residual and by the cross-device difference, a fixed count of Newton steps
 * not being a guarantee of agreement; the head areas and the hillslope stretches walk the drainage
 * tree and stay on the processor (docs/DESIGN_LEDGER.md, E1).
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
     * [limits] are the steepest drops one cell may hold toward a neighbour along a row, down a
     * column and on a diagonal, in the height field's own units, already converted from the
     * critical slope and this grid's cells: a row is not as tall as a column is wide, so the three
     * differ. Every physical constant arrives on the device converted: a kernel has no business
     * knowing how wide the world is.
     *
     * Suspending, because the obvious second implementation cannot be anything else: WebGPU hands
     * back promises for its device, its queue and every read of a buffer, and Kotlin/Wasm has no
     * way to block on one. An accelerator that happens to be synchronous simply never suspends.
     */
    suspend fun erode(
        width: Int,
        height: Int,
        heights: FloatArray,
        limits: ThermalLimits,
        passes: Int,
        rate: Float
    ): FloatArray?
}

/**
 * The steepest drop a cell may hold toward each kind of neighbour, in the height field's own units:
 * the critical slope times that step's length on the ground.
 *
 * Three figures because a cell of this map is not square. A step along a row is a cell width, a
 * step down a column is a row's height, half of that on this project's grids, and a diagonal step
 * is the hypotenuse of the two; the same critical gradient allows a drop in proportion to each.
 * One figure for every orthogonal neighbour let north- and south-facing slopes stand at twice the
 * critical gradient.
 */
class ThermalLimits(
    /** Toward a neighbour east or west. */
    val eastWest: Float,
    /** Toward a neighbour north or south. */
    val northSouth: Float,
    /** Toward a diagonal neighbour. */
    val diagonal: Float
)
