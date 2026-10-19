package com.cartogenesis.worldgen.pipeline

/**
 * Somewhere other than the processor to march the rain.
 *
 * The march is one column after another along the wind, every row of a column at once: a
 * wavefront whose rows are independent within a column but for the fluxes across their faces, so a
 * graphics card solves a column's faces as one workgroup and crosses its rows side by side, a
 * barrier a column. As with erosion and the ocean, the engine knows nothing about how that is done:
 * this interface and [MoistureDevice] are the whole seam, and the implementation lives with the
 * front end that has the platform's graphics API.
 *
 * **What the device marches is a lap**, both seasons and both layers, and nothing else: the
 * processor builds each lap's fields from the season's air and wind (`MoistureMarch`'s
 * precompute), sets the ground's return, carries the year's rain and solves the atmosphere between
 * laps, as on its own path. The water in flight (the parcels, the banks and the inboxes) stays on
 * the device from the run's first lap to its last, and comes back once at the end.
 *
 * **The processor's march is the reference.** A device is held to its answer by a test in the
 * manner of `GpuErosionTest`: the climate's rain within the coupled loop's own settling tolerance
 * on the standard worlds, and the budget's closure asserted on the device's own ledger
 * ([measuresBudget]), since a march that conserves on the processor and leaks on the card would
 * pass a comparison of fields and fail the closure.
 *
 * **The lap's order is [MarchSchedule]'s**: the free troposphere one stage behind the boundary
 * layer, so the inboxes the layers exchange through are one array each. A device marches a slot's
 * two stages a column of each at a time, which the schedule allows because in a slot the two layers
 * stand on different columns; within a column it may round differently from the processor (a face
 * solve in another order, the per-cell arithmetic in single precision), but the order of the
 * crossings is the processor's, so a device gives one answer for a given world, run after run.
 */
interface MoistureAccelerator {

    /** Shown to the user, so it should name the actual device where that is knowable. */
    val name: String

    /**
     * Whether the march should hand the device's own budget back after every lap
     * ([MoistureDevice.budgetMeasured]): each crossing's closure and each lap's storage, sources and
     * rain. For the tests; it costs a readback of the march's state every lap.
     */
    val measuresBudget: Boolean get() = false

    /**
     * A device march of [run], its fields and its water in flight uploaded, or null if this
     * accelerator cannot take it (no device, a grid taller than a workgroup can solve, a switch
     * the device's kernels do not carry), in which case the caller runs the processor's march.
     * Returning null is a normal outcome, not an error path. Blocking, unlike the other seams: the
     * climate stage that asks is not suspending, and each lap waits on the last.
     */
    fun open(run: MoistureDeviceRun): MoistureDevice?
}

/**
 * One climate run's march on a device, opened by [MoistureAccelerator.open] and closed by the march
 * when the run ends, however it ends.
 *
 * Every call returns false where the device failed; the march then hands the whole run back to the
 * processor from its start (`MoistureMarch.DeviceFailed`), since the water in flight was the
 * device's and is gone with it.
 */
interface MoistureDevice : AutoCloseable {

    /**
     * Uploads the lap's fields of both seasons ([MoistureDeviceSeason]'s wind fields and the
     * ground's return; the fields the air's temperature sets as well when [fixedFieldsChanged]) and
     * starts the lap's march, returning once it is queued, so the processor can do work the lap
     * does not read while the device marches.
     */
    fun startLap(fixedFieldsChanged: Boolean): Boolean

    /**
     * Waits for the lap [startLap] queued and reads its results into each season's
     * [MoistureDeviceSeason.boundaryRainMm], [MoistureDeviceSeason.freeRainMm],
     * [MoistureDeviceSeason.condensationMm] and [MoistureDeviceSeason.humidity]; with
     * [MoistureDeviceRun.measuring], its budget arrays and its water in flight as well.
     */
    fun finishLap(): Boolean

    /**
     * Reads the water in flight into each season's state arrays, and the last lap's land-origin
     * rain and sources: the march's end.
     */
    fun readState(): Boolean

    /** One lap's budget, measured on the device, handed back when [MoistureAccelerator.measuresBudget]. */
    fun budgetMeasured(budget: MoistureLapBudget) {}

    /** Lets the device's buffers go. */
    override fun close()
}

/**
 * One season's lap of the march on a device, its water budget read on the device's own arithmetic:
 * every term in the march's unit, millimeters of column water carried across one cell
 * (`MoistureLedger`), summed over the season's cells and both layers.
 */
class MoistureLapBudget(
    /** The half about July, or the half about January. */
    val julyHalf: Boolean,
    /** The lap, from zero. */
    val lap: Int,
    /** What the march held before the lap and after it: parcels, banks and inboxes. */
    val storageAtStart: Double,
    val storageAtEnd: Double,
    /** The sea's evaporation and the ground's return, into the parcels. */
    val sources: Double,
    /** Every rain, both layers. */
    val rain: Double,
    /** The worst crossing's water out against its water in, as a share of the water in. */
    val worstCrossingImbalance: Double,
    /** Parcels found below zero after transport, tracers outside their water, values not finite. */
    val negativeParcels: Int,
    val tracerOutOfBounds: Int,
    val nonFinite: Int
) {
    /** The storage change the sources and the rain do not explain; zero but for rounding. */
    val unaccounted: Double get() = (storageAtEnd - storageAtStart) - (sources - rain)
}
