package com.cartogenesis.worldgen.pipeline

/**
 * Somewhere other than the CPU to draw an ice sheet's profile and the flow down its surface.
 *
 * Rule 8's case exactly: both halves are per-cell arithmetic over grid-sized float buffers with no
 * iteration and no neighbourhood wider than one cell. The profile is a square root of a distance
 * field; the flow is the steepest of eight differences. At 2048 that is four million square roots
 * and thirty-two million differences, which a card does in the time the upload takes and a CPU
 * does in tens of milliseconds — so the seam is cut from the start, as rule 8 asks, rather than
 * after somebody finds it slow.
 *
 * The region work stays on the CPU and is not offered here. Basins, troughs and the blob ranking
 * are flood fills and priority queues over irregular regions, which are not per-cell and would be
 * a worse shader than they are a loop.
 *
 * As with [OceanAccelerator], an implementation is expected to produce *approximately* the CPU's
 * answer: a card is free to round a square root differently. `IceSheetParityTest` measures how far
 * apart the two are and holds them to a bar rather than to the bit.
 */
interface IceSheetAccelerator {

    /** Shown to the user, so it should name the actual device where that is knowable. */
    val name: String

    /** The thickness in metres and the flow receiver per cell, as [IceSheet] computes them. */
    class Sheet(val thicknessMetres: FloatArray, val flowReceiver: IntArray)

    /**
     * Both fields in one dispatch pair, or null if this accelerator cannot do the job after all —
     * no device, no driver, a grid it cannot fit — in which case the caller falls back to
     * [IceSheet.profile] and [IceSheet.flowReceivers]. Returning null is a normal outcome.
     *
     * [marginDistanceKm], [nearestMarginCell], [bedRelative] and [onTheSheet] are row-major over
     * [cellsAcross] by
     * [cellsDown] cells and none of them is modified. The grid is the world's cylinder: columns
     * wrap east to west and rows stop at the poles.
     *
     * [onTheSheet] is the ice that is a *sheet*, which is not all the frozen ground: a body under
     * [IceSheet.SMALLEST_SHEET_SQUARE_KM] is an ice cap and the caller has already taken it out.
     * [cellWidthKm] is how wide a cell is on the ground, and it is not a detail — the profile is
     * the mean of the plastic curve over a cell, not its value at the cell's middle, which is the
     * whole of why the ice does not end in a cliff. See [IceSheet.profileMetres].
     *
     * Suspending for the reason the other two seams are: WebGPU hands back promises for its
     * device and for every buffer read, and Kotlin/Wasm cannot block on one.
     */
    suspend fun sheet(
        cellsAcross: Int,
        cellsDown: Int,
        marginDistanceKm: FloatArray,
        nearestMarginCell: IntArray,
        bedRelative: FloatArray,
        onTheSheet: BooleanArray,
        metresPerRootKilometre: Float,
        metresPerFieldUnit: Float,
        cellHeightInCellWidths: Float,
        cellWidthKm: Float
    ): Sheet?
}
