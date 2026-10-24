package com.cartogenesis.desktop

import com.cartogenesis.worldgen.pipeline.CrustMapParity
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Rule 8's parity clause for the crust's map pass: the card's reading of the history against
 * `CrustMap`'s own, on [CrustMapParity]'s fixture and to its bars (docs/DESIGN_LEDGER.md, TH1-1).
 *
 * The processor is the reference and the card is asked to agree with it. The bars are what single
 * precision explains: a point at the kernel's very reach may fall either side of it, which moves a
 * cell's share by at most that point's weight and a field by that share of its span, and a category
 * may tip where two candidates weigh within a float's last bits of each other.
 *
 * Skipped rather than failed where there is no device: a machine with no graphics card has nothing
 * to be in parity with, and `GlContext` says so.
 */
class GpuCrustMapTest {

    @Test
    fun `the card reads the crust the processor does`() {
        val probe = GpuCrustMap.createOrNull()
        val accelerator = probe.accelerator
        assumeTrue(accelerator != null, "no graphics device: ${probe.unavailableBecause}")
        val fixture = CrustMapParity.fixture()
        val onTheCard = runBlocking { fixture.askDevice(accelerator!!) }
        assumeTrue(onTheCard != null, "the device declined the job")
        val gap = fixture.compare(onTheCard!!)
        println(
            ("TH1-1 PARITY %dx%d cells: share at worst %.2e apart (bar %.2e); thickness %.3f km (bar %.3f); floor age %.3f Myr" +
                " (bar %.3f); crust age %.3f Myr (bar %.3f); the belts' books %.2e of their spans (bar %.2e);" +
                " categories differ on %.5f of the cells (bar %.5f); the worst share at row %d column %d, %.4f here and %.4f on the card")
                .format(
                    fixture.cellsAcross, fixture.cellsDown, gap.shareWorst, gap.shareBar, gap.thicknessWorstKm, gap.thicknessBarKm,
                    gap.floorAgeWorstMyr, gap.floorAgeBarMyr, gap.crustAgeWorstMyr, gap.crustAgeBarMyr, gap.beltBooksWorstShare, gap.shareBar,
                    gap.categoriesDiffering, gap.categoriesBar, gap.worstRow, gap.worstColumn, gap.worstProcessorShare, gap.worstDeviceShare
                )
        )
        assertTrue(gap.shareWorst <= gap.shareBar, "the card's crust share is ${gap.shareWorst} off the processor's")
        assertTrue(gap.thicknessWorstKm <= gap.thicknessBarKm, "the card's thickness is ${gap.thicknessWorstKm} km off")
        assertTrue(gap.floorAgeWorstMyr <= gap.floorAgeBarMyr, "the card's floor age is ${gap.floorAgeWorstMyr} Myr off")
        assertTrue(gap.crustAgeWorstMyr <= gap.crustAgeBarMyr, "the card's crust age is ${gap.crustAgeWorstMyr} Myr off")
        assertTrue(gap.beltBooksWorstShare <= gap.shareBar, "the card's belt books are ${gap.beltBooksWorstShare} of their spans off")
        assertTrue(gap.categoriesDiffering <= gap.categoriesBar, "the card's categories differ on ${gap.categoriesDiffering} of the cells")
    }
}
