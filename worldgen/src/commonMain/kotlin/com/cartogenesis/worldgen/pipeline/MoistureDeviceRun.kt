package com.cartogenesis.worldgen.pipeline

/**
 * One climate run's march as a device reads it ([MoistureAccelerator.open]): the grid, the order
 * of a lap, the constants the per-cell arithmetic uses and both seasons' fields and water.
 *
 * Every per-cell array is column-major, cell (row, column) at `column * cellsDown + row`, as the
 * processor's march holds them, unless its property says otherwise; per-row arrays are one entry a
 * row. The arrays are the processor's own, not copies: a device reads its inputs from them on
 * [MoistureDevice.startLap] and writes its results into them on [MoistureDevice.finishLap] and
 * [MoistureDevice.readState].
 */
class MoistureDeviceRun internal constructor(
    val cellsAcross: Int,
    val cellsDown: Int,
    /** The order of a lap. */
    private val schedule: MarchSchedule,
    /** Seconds the march's unit of water takes to cross one cell of each row ([MoistureMarch.referenceSecondsPerColumn]). */
    val secondsPerColumn: DoubleArray,
    /** The eddies' share of a row's water traded through the face below each row ([MoistureMarch.eddyMixingShare]). */
    val faceMixing: DoubleArray,
    /** `ClimateConfig.rainShadowScale`, which the cloud's conversion is slowed by. */
    val rainShadowScale: Double,
    /** Whether the device measures each lap's budget ([MoistureAccelerator.measuresBudget], or a ledger asked). */
    val measuring: Boolean,
    val julyHalf: MoistureDeviceSeason,
    val januaryHalf: MoistureDeviceSeason
) {
    val cellCount: Int get() = cellsAcross * cellsDown

    /** The two seasons, July's half first. */
    val seasons: List<MoistureDeviceSeason> get() = listOf(julyHalf, januaryHalf)

    /**
     * A lap as a device marches it, a column of each layer at a time: [STEP_WIDTH] entries a step,
     * the boundary layer's column and sweep (+1 east, -1 west) and the free troposphere's, a column
     * of -1 where the layer rests that step. Each of [MarchSchedule]'s slots is [MarchSchedule.columnsPerStage]
     * steps, the two layers' stages marched column by column side by side, which gives the
     * processor's answer because a slot's two stages stand on different columns; steps where both
     * rest are left out.
     */
    fun steps(): IntArray {
        val steps = ArrayList<Int>()
        for (slot in 0 until schedule.slotCount) {
            for (offset in 0 until schedule.columnsPerStage) {
                val boundary = columnAt(true, slot, offset)
                val free = columnAt(false, slot, offset)
                if (boundary[0] < 0 && free[0] < 0) continue
                steps += boundary[0]; steps += boundary[1]; steps += free[0]; steps += free[1]
            }
        }
        return steps.toIntArray()
    }

    /** The column and sweep the layer marches [offset] columns into its stage of [slot], or -1 and 0. */
    private fun columnAt(boundaryLayer: Boolean, slot: Int, offset: Int): IntArray {
        val stage = schedule.stageAt(boundaryLayer, slot)
        if (stage < 0) return intArrayOf(-1, 0)
        val sweep = if (schedule.isEastward(stage)) 1 else -1
        val column = schedule.firstColumn(stage) + offset * sweep
        val last = schedule.lastColumn(stage)
        val inside = if (sweep > 0) column <= last else column >= last
        return if (inside) intArrayOf(column, sweep) else intArrayOf(-1, 0)
    }

    /**
     * The constants the device's per-cell arithmetic reads, by the names its kernels use: the same
     * figures the processor's march, crossing and thermodynamics are written with, so a kernel
     * holds no number of its own but its precision's.
     */
    val constants: Map<String, Double> = MoistureMarch.deviceConstants()

    companion object {
        /** Entries a step of [steps] holds. */
        const val STEP_WIDTH = 4

        /** The surfaces [MoistureDeviceSeason.surface] holds. */
        const val LAND = 0
        const val OPEN_SEA = 1
        const val SEA_ICE = 2
    }
}

/**
 * One season's march as a device reads and writes it; see [MoistureDeviceRun] for the layout.
 */
class MoistureDeviceSeason internal constructor(
    /** The cell's surface: [MoistureDeviceRun.LAND], [MoistureDeviceRun.OPEN_SEA] or [MoistureDeviceRun.SEA_ICE]. */
    val surface: ByteArray,
    val boundary: MoistureDeviceLayer,
    val free: MoistureDeviceLayer,
    /**
     * Each cell's place in the boundary layer's condensate banks, or -1: the cells where a row's
     * wind turns into the other sweep, [turnCells] of them.
     */
    val turnSlot: IntArray,
    val turnCells: Int,

    // What the air's temperature sets, through the run unless it moves.

    /** The boundary layer's cap, millimeters, the ground's pressure, kilopascals, and the layer's depth, meters. */
    val boundaryCapMm: FloatArray,
    val groundPressureKpa: FloatArray,
    val layerDepthM: FloatArray,
    /** The half-year's air at the ground, degrees Celsius: row-major, as the season holds it. */
    val airTemperatureCRowMajor: () -> FloatArray,
    /** The specific humidity at the sea's surface, kg/kg, at open-sea cells. */
    val seaSurfaceHumidity: FloatArray,
    /** The marine inversion's lid, 0..1. */
    val suppression: FloatArray,
    /**
     * Fills the per-cell terms the crossings read in doubles, which the processor computes at each
     * crossing from the fields above (`MoistureMarch`'s `exchangePerSecondAt` and the free
     * troposphere's saturated columns): [FreeTroposphereTerms]. Built on demand, so a run does not
     * hold five more double fields for a device that keeps them.
     */
    val fillCrossingTerms: (FreeTroposphereTerms) -> Unit,

    // What the wind and the ground's return set, every lap.

    /** The bulk formula's `rho C_E U`, kg m⁻² s⁻¹, at open-sea cells. */
    val seaTransferMassFlux: FloatArray,
    /** The boundary layer's climb up the ground, m/s, and what its descent can evaporate, kg m⁻² s⁻¹. */
    val liftMps: FloatArray,
    val descentRate: FloatArray,
    /** The ground's return, millimeters a second, at land cells. */
    val groundReturnRate: FloatArray,

    // The water in flight.

    /** What each layer sent the other at each cell, with its land-origin part, in the march's units. */
    val toFree: DoubleArray,
    val toFreeLand: DoubleArray,
    val toBoundary: DoubleArray,
    val toBoundaryLand: DoubleArray,
    /** The share of the boundary layer's air its faces took out of each cell, per second. */
    val replacedPerSecond: FloatArray,
    /** The boundary layer's cloud and falling water banked at each turn, by [turnSlot], with their land-origin parts. */
    val bankCloud: DoubleArray,
    val bankCloudLand: DoubleArray,
    val bankFalling: DoubleArray,
    val bankFallingLand: DoubleArray,

    // The lap's results, millimeters a year at each cell.

    /** The boundary layer's rain (its condensate's) and its land-origin part. */
    val boundaryRainMm: FloatArray,
    val boundaryLandRainMm: FloatArray,
    /** The boundary layer's net condensation. */
    val condensationMm: FloatArray,
    /** The boundary layer's relative humidity as it leaves each cell, 0..1. */
    val humidity: FloatArray,
    /** What the sea and the ground put into the boundary layer. */
    val sourceMm: FloatArray,
    /** The free troposphere's rain and its land-origin part. */
    val freeRainMm: FloatArray,
    val freeLandRainMm: FloatArray
)

/**
 * The per-cell terms a device's crossings read in doubles, column-major: the rate the boundary
 * layer's top is stirred per second of its mass, and the free troposphere's saturated column read
 * from sea level, the water the rain law counts beside its own, its saturated column, and the share
 * of its saturation its lowest air carries down, each as the processor computes it at the crossing.
 */
class FreeTroposphereTerms(cellCount: Int) {
    val exchangePerSecond = DoubleArray(cellCount)
    val lawSaturatedMm = DoubleArray(cellCount)
    val lawOffsetMm = DoubleArray(cellCount)
    val freeSaturatedMm = DoubleArray(cellCount)
    val baseShare = DoubleArray(cellCount)
}

/** One layer of a season's march as a device reads and writes it; see [MoistureDeviceRun]. */
class MoistureDeviceLayer internal constructor(
    val isBoundary: Boolean,
    /** Each cell's sweep, held through the run: +1 east, -1 west. */
    val direction: ByteArray,
    /** Each cell's place among the cells whose sent parcel the westward sweep reads, or -1; [sentSlots] of them. */
    val sentSlot: IntArray,
    val sentSlots: Int,
    /** `U / |u|` at each cell, every lap. */
    val stretch: FloatArray,
    /** The meridional wind's share through the face below each cell, every lap. */
    val faceShare: FloatArray,
    /** Each row's parcel arriving at the next column, the eastward sweep's and the westward's. */
    val eastParcel: MoistureDeviceParcels,
    val westParcel: MoistureDeviceParcels,
    /** The bank at every cell, with its land-origin part, and the boundary layer's air; null for the free troposphere. */
    val bank: DoubleArray,
    val bankLand: DoubleArray,
    val bankAir: DoubleArray?,
    /** With [MoistureDeviceRun.measuring], what each crossing did; null otherwise. */
    val budget: MoistureDeviceCellBudget?
)

/** A sweep's parcels, one entry a row, in the march's units: vapor, cloud and falling water with their land-origin parts, and air. */
class MoistureDeviceParcels internal constructor(
    val vapor: DoubleArray,
    val vaporLand: DoubleArray,
    val cloud: DoubleArray,
    val cloudLand: DoubleArray,
    val falling: DoubleArray,
    val fallingLand: DoubleArray,
    val air: DoubleArray
)

/**
 * What each crossing of a layer did in the last lap, column-major, in the march's units (millimeters
 * of column water carried across one cell): written by a measuring device for the budget.
 */
class MoistureDeviceCellBudget(cellCount: Int) {
    /** The sea's evaporation or the ground's return put in. */
    val added = DoubleArray(cellCount)
    /** The rain, and its land-origin part. */
    val rain = DoubleArray(cellCount)
    val landRain = DoubleArray(cellCount)
    /** Vapor condensed to cloud, and cloud evaporated again. */
    val condensed = DoubleArray(cellCount)
    val reevaporated = DoubleArray(cellCount)
    /** The crossing's water out against its water in, as a share of the water in. */
    val imbalance = DoubleArray(cellCount)
    /** [NEGATIVE], [TRACER] and [NON_FINITE], as bits. */
    val flags = IntArray(cellCount)
    /** The layer's water as it leaves the cell, millimeters. */
    val waterMm = FloatArray(cellCount)

    companion object {
        const val NEGATIVE = 1
        const val TRACER = 2
        const val NON_FINITE = 4
    }
}
