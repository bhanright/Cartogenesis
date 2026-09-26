package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The synthetic sheet every [IceSheetAccelerator] is held to the processor on, and the measure of
 * how far a device's answer sits from [IceSheet]'s own.
 *
 * One fixture for both devices: the desktop's `GpuIceSheetTest` asserts on it, and the browser's
 * `?selftest` page, which is the only place a browser's device can be reached, reports on it. A
 * synthetic bed and mask rather than a generated world, because the seam takes arrays, so what is
 * measured is the arithmetic and not the pipeline that feeds it; and the same one on both, so the
 * two devices' figures are comparable. Built from [kotlin.random.Random], whose generator is the
 * same on every platform, so the JVM and the browser build the same bed to the bit.
 *
 * [cellsAcross] by [cellsDown] cells, 256 each way, with the default settings: a bed of uniform
 * noise over the lower 40% of the ruler, frozen north of the first third and south of the second,
 * and one frozen cell in twenty left off the sheet so its margins are ragged.
 */
class IceSheetParity private constructor() {

    private val config =
        WorldGenConfig(seed = FIXTURE_SEED, width = SIDE_CELLS, height = SIDE_CELLS)
    val cellsAcross = config.width
    val cellsDown = config.height
    private val cellCount = cellsAcross * cellsDown

    private val random = Random(NOISE_SEED)
    val bedRelative = FloatArray(cellCount) { random.nextFloat() * BED_SHARE_OF_RULER }
    private val frozen = BooleanArray(cellCount) { cell ->
        val row = cell / cellsAcross
        row < cellsDown / 3 || row > cellsDown * 2 / 3
    }
    val onTheSheet = BooleanArray(cellCount) {
        frozen[it] && random.nextFloat() > SHARE_LEFT_OFF_THE_SHEET
    }
    val metresPerRootKilometre =
        IceSheet.metresPerRootKilometre(config.isostasy.iceDensity, config.isostasy.gravity)
    val metresPerFieldUnit = config.scale.highestLandMetres
    val cellHeightInCellWidths = config.cellHeightInCellWidths.toFloat()
    val cellSpanKm = sqrt(config.squareKilometresPerCell).toFloat()
    val margin = IceSheet.marginDistanceKm(
        config, frozen, bedRelative, metresPerFieldUnit, metresPerRootKilometre, cellSpanKm
    )

    /** The processor's thickness, in metres, per cell: the reference. */
    val processorThicknessMetres = IceSheet.profile(
        margin, bedRelative, onTheSheet, metresPerRootKilometre, metresPerFieldUnit, cellSpanKm
    )

    /** The processor's flow receiver per cell, -1 where none is lower: the reference. */
    val processorFlowReceiver = IceSheet.flowReceivers(
        cellsAcross, cellsDown, bedRelative, processorThicknessMetres, onTheSheet,
        metresPerFieldUnit, cellHeightInCellWidths
    )

    /** Hands [device] this fixture exactly as the glaciation stage would hand it a world's. */
    suspend fun askDevice(device: IceSheetAccelerator): IceSheetAccelerator.Sheet? =
        device.sheet(
            cellsAcross, cellsDown, margin.distanceKm, margin.nearestCell, bedRelative, onTheSheet,
            metresPerRootKilometre, metresPerFieldUnit, cellHeightInCellWidths, cellSpanKm
        )

    /** How far [onTheDevice] sits from the processor over the sheet's cells. */
    fun compare(onTheDevice: IceSheetAccelerator.Sheet): Gap {
        var sheetCells = 0
        var worstMetres = 0f
        var thickestMetres = 0f
        var receiversDiffering = 0
        for (cell in 0 until cellCount) {
            if (!onTheSheet[cell]) continue
            sheetCells++
            val apart = abs(onTheDevice.thicknessMetres[cell] - processorThicknessMetres[cell])
            if (apart > worstMetres) worstMetres = apart
            if (processorThicknessMetres[cell] > thickestMetres) {
                thickestMetres = processorThicknessMetres[cell]
            }
            if (onTheDevice.flowReceiver[cell] != processorFlowReceiver[cell]) receiversDiffering++
        }
        return Gap(sheetCells, worstMetres, thickestMetres, receiversDiffering)
    }

    /**
     * A device's distance from the processor on the fixture: [worstThicknessMetres] is the largest
     * difference in thickness on any sheet cell, [thickestMetres] the processor's thickest ice, and
     * [receiversDiffering] how many of the [sheetCells] flow somewhere else.
     */
    class Gap(
        val sheetCells: Int,
        val worstThicknessMetres: Float,
        val thickestMetres: Float,
        val receiversDiffering: Int
    ) {
        /** The worst thickness difference as a share of the thickest ice. */
        val worstThicknessShare: Float
            get() = if (thickestMetres <= 0f) 0f else worstThicknessMetres / thickestMetres

        /** The receivers that differ as a share of the sheet's cells. */
        val receiversDifferingShare: Float
            get() = if (sheetCells == 0) 0f else receiversDiffering.toFloat() / sheetCells
    }

    companion object {
        /** The fixture, built afresh, with the processor's answer already worked out on it. */
        fun fixture(): IceSheetParity = IceSheetParity()

        /**
         * The world whose default settings the fixture borrows its scale, its ruler and its ice's
         * density from. Only the settings are read, so any seed would give the same fixture.
         */
        private const val FIXTURE_SEED = 718106L

        /** Big enough for sheets of real width, small enough for a page to run on load. */
        private const val SIDE_CELLS = 256

        /** The bed's noise, a seed of its own so the grid's seed does not move it. */
        private const val NOISE_SEED = 4242

        /** The bed stands anywhere between the waterline and this share of the ruler's top. */
        private const val BED_SHARE_OF_RULER = 0.4f

        /** One frozen cell in twenty left bare, so the margins are ragged, not straight rows. */
        private const val SHARE_LEFT_OFF_THE_SHEET = 0.05f
    }
}
