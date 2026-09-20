package com.cartogenesis.desktop

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.IceSheet
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Rule 8's parity clause for the ice sheet: the card's profile and flow against [IceSheet]'s own.
 *
 * The CPU path is the reference and the card is asked to agree with it, not the other way round.
 * Neither half of this iterates, so there is nothing for a rounding difference to compound
 * through — a thickness is one square root and one subtraction, and a receiver is the largest of
 * eight quotients. The thickness is therefore held to a relative bar a float's own precision
 * explains, and the receivers are asked to agree outright on all but the cells where two
 * neighbours are within that bar of being equally steep, which is a tie the two are entitled to
 * break differently.
 *
 * Skipped rather than failed where there is no device: a machine with no graphics card has
 * nothing to be in parity with, and `GlContext` says so.
 */
class GpuIceSheetTest {

    @Test
    fun `the card draws the same sheet the processor does`() {
        val probe = GpuIceSheet.createOrNull()
        val accelerator = probe.accelerator
        assumeTrue(accelerator != null, "no graphics device: ${probe.unavailableBecause}")

        val config = WorldGenConfig(seed = 718106L, width = 256, height = 256)
        val cellsAcross = config.width
        val cellsDown = config.height
        val cellCount = cellsAcross * cellsDown

        // A synthetic bed and a synthetic mask rather than a generated world: the seam takes
        // arrays, so what is being measured is the arithmetic and not the pipeline that feeds it.
        val random = Random(4242)
        val bed = FloatArray(cellCount) { random.nextFloat() * 0.4f }
        val frozen = BooleanArray(cellCount) { cell ->
            val row = cell / cellsAcross
            row < cellsDown / 3 || row > cellsDown * 2 / 3
        }
        val onTheSheet = BooleanArray(cellCount) { frozen[it] && random.nextFloat() > 0.05f }
        val margin = IceSheet.marginDistanceKm(config, frozen)
        val metresPerRootKm =
            IceSheet.metresPerRootKilometre(config.isostasy.iceDensity, config.isostasy.gravity)
        val metresPerFieldUnit = config.scale.highestLandMetres
        val rowScale = config.cellHeightInCellWidths.toFloat()
        val cellWidthKm = config.cellWidthKm.toFloat()

        val onTheProcessor = IceSheet.profile(
            margin, bed, onTheSheet, metresPerRootKm, metresPerFieldUnit, cellWidthKm
        )
        val processorFlow = IceSheet.flowReceivers(
            cellsAcross, cellsDown, bed, onTheProcessor, onTheSheet, metresPerFieldUnit, rowScale
        )
        val onTheCard = runBlocking {
            accelerator!!.sheet(
                cellsAcross, cellsDown, margin.distanceKm, margin.nearestCell, bed, onTheSheet,
                metresPerRootKm, metresPerFieldUnit, rowScale, cellWidthKm
            )
        }
        assumeTrue(onTheCard != null, "the device declined the job")

        var worstThickness = 0f
        var thickest = 0f
        var disagreed = 0
        var sheetCells = 0
        for (cell in 0 until cellCount) {
            if (!onTheSheet[cell]) continue
            sheetCells++
            val apart = abs(onTheCard!!.thicknessMetres[cell] - onTheProcessor[cell])
            if (apart > worstThickness) worstThickness = apart
            if (onTheProcessor[cell] > thickest) thickest = onTheProcessor[cell]
            if (onTheCard.flowReceiver[cell] != processorFlow[cell]) disagreed++
        }
        val relative = if (thickest <= 0f) 0f else worstThickness / thickest
        val disagreedShare = if (sheetCells == 0) 0f else disagreed.toFloat() / sheetCells
        println(
            ("I1 PARITY %d sheet cells: the thickness is at worst %.4f m apart, %.2e of the" +
                " thickest %.0f m; %d receivers differ, %.4f%% of them")
                .format(
                    sheetCells, worstThickness, relative, thickest, disagreed,
                    disagreedShare * 100
                )
        )
        assertTrue(sheetCells > 0, "the synthetic mask left no sheet to measure")
        assertTrue(
            relative <= THICKNESS_PARITY,
            "the card's thickness is ${"%.4f".format(worstThickness)} m off the processor's," +
                " ${"%.2e".format(relative)} of the thickest ice, over the" +
                " ${"%.0e".format(THICKNESS_PARITY)} a float's own precision explains"
        )
        assertTrue(
            disagreedShare <= FLOW_DISAGREEMENT,
            "${"%.4f".format(disagreedShare * 100)}% of the card's flow receivers differ from the" +
                " processor's, over the ${"%.2f".format(FLOW_DISAGREEMENT * 100)}% two all but" +
                " equally steep neighbours explain"
        )
    }

    private companion object {
        /**
         * How far the card's thickness may sit from the processor's, as a share of the thickest
         * ice on the grid.
         *
         * A float carries about seven decimal digits, and the profile is a square root, a
         * multiply, a subtract and a max — four operations, each of which may round the last bit
         * the other way. A part in a million is several times that and is nowhere near a metre.
         */
        const val THICKNESS_PARITY = 1e-6f

        /**
         * How many receivers may differ, as a share of the sheet.
         *
         * A receiver differs only where two of the eight neighbours are within a float's last bit
         * of being equally steep, which on a bed with real relief is rare and on a flat one is
         * everywhere. A part in a thousand is the former with room to spare; anything above it is
         * a different rule rather than a different rounding.
         */
        const val FLOW_DISAGREEMENT = 1e-3f
    }
}
