package com.cartogenesis.desktop

import com.cartogenesis.worldgen.pipeline.BeltFieldsParity
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Rule 8's parity clause for the belts' map pass (docs/DESIGN_LEDGER.md, TH1-2): the card's ridges,
 * province and rock erodibility against `BeltFields`' own, on [BeltFieldsParity]'s fixture and to
 * its bars.
 *
 * The processor is the reference and the card is asked to agree with it. The bars are what single
 * precision explains: each kernel's rounding, and a kernel at its very reach falling the other side
 * of it on a few cells.
 *
 * Skipped rather than failed where there is no device.
 */
class GpuBeltFieldsTest {

    @Test
    fun `the card draws the belts the processor does`() {
        val probe = GpuCrustMap.createOrNull()
        val accelerator = probe.accelerator
        assumeTrue(accelerator != null, "no graphics device: ${probe.unavailableBecause}")
        val fixture = BeltFieldsParity.fixture()
        val onTheCard = runBlocking { fixture.askDevice(accelerator!!) }
        assumeTrue(onTheCard != null, "the device declined the job")
        val gap = fixture.compare(onTheCard!!)
        println(
            ("TH1-2 BELT PARITY %dx%d cells, %d with relief: ridges at worst %.2e of their amplitude apart, province %.2e (bar %.2e);" +
                " %.5f of the cells with relief past rounding's %.2e (bar %.5f); erodibility %.2e of itself (bar %.2e)")
                .format(
                    fixture.cellsAcross, fixture.cellsDown, gap.cellsWithRelief, gap.ridgeWorstShare, gap.provinceWorstShare,
                    gap.reliefWorstBar, gap.reliefOffShare, gap.reliefRoundingBar, gap.reliefOffBar, gap.erodibilityWorstRelative,
                    gap.erodibilityBar
                )
        )
        assertTrue(gap.cellsWithRelief > 0, "the fixture has no belts to compare")
        assertTrue(gap.ridgeWorstShare <= gap.reliefWorstBar, "the card's ridges are ${gap.ridgeWorstShare} of their amplitude off")
        assertTrue(gap.provinceWorstShare <= gap.reliefWorstBar, "the card's province is ${gap.provinceWorstShare} of its amplitude off")
        assertTrue(gap.reliefOffShare <= gap.reliefOffBar, "${gap.reliefOffShare} of the cells with relief are off past rounding")
        assertTrue(gap.erodibilityWorstRelative <= gap.erodibilityBar, "the card's erodibility is ${gap.erodibilityWorstRelative} of itself off")
    }
}
