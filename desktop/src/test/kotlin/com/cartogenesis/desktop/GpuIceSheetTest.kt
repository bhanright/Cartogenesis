package com.cartogenesis.desktop

import com.cartogenesis.worldgen.pipeline.IceSheet
import com.cartogenesis.worldgen.pipeline.IceSheetParity
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

        // A synthetic bed and a synthetic mask rather than a generated world: the seam takes
        // arrays, so what is being measured is the arithmetic and not the pipeline that feeds it.
        // The browser's self-test measures its device on the same fixture.
        val fixture = IceSheetParity.fixture()
        val onTheCard = runBlocking { fixture.askDevice(accelerator!!) }
        assumeTrue(onTheCard != null, "the device declined the job")
        val gap = fixture.compare(onTheCard!!)
        val sheetCells = gap.sheetCells
        val worstThickness = gap.worstThicknessMetres
        val relative = gap.worstThicknessShare
        val disagreedShare = gap.receiversDifferingShare
        println(
            ("I1 PARITY %d sheet cells: the thickness is at worst %.4f m apart, %.2e of the" +
                " thickest %.0f m; %d receivers differ, %.4f%% of them")
                .format(
                    sheetCells, worstThickness, relative, gap.thickestMetres,
                    gap.receiversDiffering, disagreedShare * 100
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
