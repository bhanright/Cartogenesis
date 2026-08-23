package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The contract of [WorldGenConfig.atResolution]: settings measured in cells scale with the grid.
 *
 * This is not decoration. Re-targeting the grid without rescaling them leaves mountain belts a
 * fraction of their proper width, which surfaces plate edges as straight cliffs and turns
 * coastlines angular — the UI shipped that bug by changing resolution with a plain
 * `copy(width = ...)`, so both the app and the desktop build now go through [atResolution] and
 * these assertions pin the behaviour they depend on.
 */
class ResolutionScalingTest {

    private val base = WorldGenConfig(seed = 1L, width = 512, height = 512)

    @Test
    fun `settings measured in cells scale with the grid`() {
        val scaled = base.atResolution(2048, 2048)

        assertEquals(2048, scaled.width)
        // A belt four times as many cells wide, so it stays the same width on the map.
        assertEquals(base.tectonics.boundaryFalloff * 4f, scaled.tectonics.boundaryFalloff)
        // Charged per cell of wind travel, so a four-times-wider grid must charge a quarter as
        // much or every interior parches.
        assertEquals(base.climate.baseRainRate / 4f, scaled.climate.baseRainRate)
        // Charged against the climb between adjacent cells, which halves as the cells do.
        assertEquals(base.nations.slopeResistance * 4f, scaled.nations.slopeResistance)
    }

    @Test
    fun `anything expressed as a frequency or a fraction is left alone`() {
        val scaled = base.atResolution(2048, 2048)

        assertEquals(base.seed, scaled.seed)
        assertEquals(base.seaLevel, scaled.seaLevel)
        assertEquals(base.tectonics.plateCount, scaled.tectonics.plateCount)
        assertEquals(base.tectonics.detailFrequency, scaled.tectonics.detailFrequency)
        assertEquals(base.tectonics.rangeVariationScale, scaled.tectonics.rangeVariationScale)
        assertEquals(base.rivers.sourceThreshold, scaled.rivers.sourceThreshold)
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
        assertTrue(scaled.tectonics.boundaryFalloff > base.tectonics.boundaryFalloff)
        assertTrue(scaled.climate.baseRainRate < base.climate.baseRainRate)
    }
}
