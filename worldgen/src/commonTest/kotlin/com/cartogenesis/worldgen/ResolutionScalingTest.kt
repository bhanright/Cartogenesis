package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What is left of [WorldGenConfig.atResolution]: the tectonics, and the moisture march's rain rate.
 *
 * This class used to assert that a dozen named settings were multiplied by the grid ratio, which
 * was the only way to ask "is this still the same world at export size" while every reach was a
 * count of cells and every depth a fraction of an assumed range. They are lengths in kilometres and
 * depths in metres now, converted where each stage reads them, so there is nothing left to carry
 * for them and nothing here to assert about them — `ScaleFreeTest` asks the question the contracts
 * were standing in for, and asks it of the finished world rather than of the settings.
 *
 * Two groups are still carried by hand and both are held here, because a contract that is still a
 * contract still needs a guard. A belt's width could be a kilometre today but its *height* cannot
 * be a metre until the height field has an absolute vertical scale, and the two are read together
 * in one stamping expression; the moisture march's rain rate is one term of a sum whose other term
 * is charged against a per-cell rise in the same unitless field. Both pairs move together in S2 and
 * W3. See [WorldGenConfig.atResolution] for the whole of the reasoning.
 */
class ResolutionScalingTest {

    private val base = WorldGenConfig(seed = 1L, width = 512, height = 512)

    @Test
    fun `the tectonics are still carried by hand and the climate no longer is`() {
        val scaled = base.atResolution(2048, 2048)

        assertEquals(2048, scaled.width)
        // A belt four times as many cells wide, so it stays the same width on the map.
        assertEquals(base.tectonics.boundaryFalloffCells * 4f, scaled.tectonics.boundaryFalloffCells)
        assertEquals(base.tectonics.andeanWidthCells * 4f, scaled.tectonics.andeanWidthCells)
        assertEquals(base.tectonics.collisionWidthCells * 4f, scaled.tectonics.collisionWidthCells)
        assertEquals(base.tectonics.epochDriftCells * 4f, scaled.tectonics.epochDriftCells)
        assertEquals(base.tectonics.hotspotSpacingCells * 4f, scaled.tectonics.hotspotSpacingCells)
        // The climate's moisture budget used to need a line here, and no longer does: its three
        // rates are lengths in kilometres now and a length on the ground is not a function of how
        // many cells the ground is cut into. See W3 in docs/DESIGN_LEDGER.md.
        assertEquals(base.climate.depletionLengthKm, scaled.climate.depletionLengthKm)
        assertEquals(base.climate.oceanEvaporationLengthKm, scaled.climate.oceanEvaporationLengthKm)
        assertEquals(
            base.climate.evapotranspirationLengthKm, scaled.climate.evapotranspirationLengthKm
        )
    }

    @Test
    fun `everything that carries a unit is left alone`() {
        val scaled = base.atResolution(2048, 2048)

        // The world's own size and its clock: how many cells the map is cut into says nothing
        // about how wide the world is, how high its land stands or how long a round lasts.
        assertEquals(base.scale, scaled.scale)
        // A reach in kilometres, a depth in metres and an area in square kilometres are the same
        // reach, depth and area at every grid. The conversion is the stage's, not this function's.
        assertEquals(base.erosion.deltaReachKm, scaled.erosion.deltaReachKm)
        assertEquals(base.erosion.outletReachKm, scaled.erosion.outletReachKm)
        assertEquals(base.erosion.debrisTravelKm, scaled.erosion.debrisTravelKm)
        assertEquals(base.erosion.criticalFallMetresPerKm, scaled.erosion.criticalFallMetresPerKm)
        assertEquals(base.sea.shelfWidthKm, scaled.sea.shelfWidthKm)
        assertEquals(base.sea.lowstandMetres, scaled.sea.lowstandMetres)
        assertEquals(base.glaciation.valleyWidthKm, scaled.glaciation.valleyWidthKm)
        assertEquals(base.glaciation.basinDropMetres, scaled.glaciation.basinDropMetres)
        assertEquals(base.glaciation.maxLakeAreaKm2, scaled.glaciation.maxLakeAreaKm2)
        assertEquals(base.lakes.minLakeAreaKm2, scaled.lakes.minLakeAreaKm2)
    }

    @Test
    fun `anything expressed as a frequency or a fraction is left alone`() {
        val scaled = base.atResolution(2048, 2048)

        assertEquals(base.seed, scaled.seed)
        assertEquals(base.seaLevel, scaled.seaLevel)
        assertEquals(base.tectonics.plateCount, scaled.tectonics.plateCount)
        assertEquals(base.tectonics.detailFrequency, scaled.tectonics.detailFrequency)
        assertEquals(base.tectonics.rangeVariationCycles, scaled.tectonics.rangeVariationCycles)
        assertEquals(
            base.rivers.channelHeadAreaSlopeKm2,
            scaled.rivers.channelHeadAreaSlopeKm2
        )
        assertEquals(base.rivers.shortestDrawnCourseKm, scaled.rivers.shortestDrawnCourseKm)
        assertEquals(base.nations.reach, scaled.nations.reach)
    }

    @Test
    fun `changing resolution and changing back returns the original world`() {
        // The resolution picker calls this on every change, so the scaling has to be reversible or
        // a user moving the slider back and forth would slowly deform their world. Every offered
        // resolution is a power of two, and scaling a float by a power of two is exact, so this is
        // an equality rather than a tolerance.
        val roundTripped = base.atResolution(2048, 2048).atResolution(512, 512)
        assertEquals(base, roundTripped)

        val viaSteps = base.atResolution(1024, 1024).atResolution(2048, 2048)
        assertEquals(base.atResolution(2048, 2048), viaSteps)
    }

    @Test
    fun `rescaling actually changes something`() {
        // Guards against the rescaling being quietly dropped, which is how the bug looked: the
        // call was there in export, but the UI never made it.
        val scaled = base.atResolution(1024, 1024)
        assertTrue(scaled.tectonics.boundaryFalloffCells > base.tectonics.boundaryFalloffCells)
    }
}
