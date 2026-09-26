package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.Acceleration
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.OceanHeatGrid
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.OceanAccelerator
import com.cartogenesis.worldgen.pipeline.OceanCirculation
import com.cartogenesis.worldgen.pipeline.OceanResult
import com.cartogenesis.worldgen.pipeline.OceanStage
import com.cartogenesis.worldgen.pipeline.OceanStencil
import com.cartogenesis.worldgen.pipeline.SeaLevelResult
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The seam itself, with no device behind it.
 *
 * Three promises, none of which need graphics hardware to check: the accelerator is asked only when
 * the reader has turned acceleration on, it is handed the stencil the processor would have relaxed,
 * and a decline leaves the reference answer untouched rather than a half-solved one.
 */
class OceanAcceleratorTest {

    @Test
    fun `the device is asked only when acceleration is on`() = runTest {
        var asks = 0
        val counting = decliningAccelerator { asks++ }

        OceanStage.generate(config, sea, counting)
        assertEquals(0, asks, "the processor path must not reach for a device")

        OceanStage.generate(acceleratedConfig, sea, counting)
        val asked = asks
        assertTrue(asked > 0, "the accelerated path asked")

        val noOcean = acceleratedConfig.copy(ocean = config.ocean.copy(enabled = false))
        OceanStage.generate(noOcean, sea, counting)
        assertEquals(asked, asks, "a world with no currents has none to solve")

        OceanStage.generate(acceleratedConfig, sea, null)
        assertEquals(asked, asks, "a host with no device solves on the processor")
    }

    @Test
    fun `the device is handed a stencil with a forcing in it`() = runTest {
        var seen: OceanStencil? = null
        val recording = object : OceanAccelerator {
            override val name = "recording test device"
            override suspend fun solve(stencil: OceanStencil, start: FloatArray, passes: Int): FloatArray? {
                seen = stencil
                return null
            }
        }
        OceanStage.generate(acceleratedConfig, sea, recording)
        val stencil = checkNotNull(seen)
        assertEquals(CELLS_ACROSS, stencil.cellsAcross)
        assertEquals(CELLS_ACROSS, stencil.cellsDown)
        assertTrue(stencil.forcing.any { it != 0f }, "a forcing of nothing would spin nothing")
    }

    /**
     * A device that answers with the reference relaxation of whatever it was handed gives back the
     * reference ocean to the last bit only if the stencil, the start and the passes it was handed
     * are the ones the processor would have relaxed.
     */
    @Test
    fun `the device is handed exactly the problem the processor solves`() = runTest {
        val reference = OceanStage.generate(config, sea)
        val answeringLikeTheCpu = object : OceanAccelerator {
            override val name = "reference-solving test device"
            override suspend fun solve(stencil: OceanStencil, start: FloatArray, passes: Int): FloatArray {
                val stream = start.copyOf()
                OceanCirculation.relax(stencil, stream, passes)
                return stream
            }
        }
        assertSameOcean(reference, OceanStage.generate(acceleratedConfig, sea, answeringLikeTheCpu))
    }

    @Test
    fun `a decline leaves the reference answer exactly`() = runTest {
        val reference = OceanStage.generate(config, sea)
        val declined = OceanStage.generate(acceleratedConfig, sea, decliningAccelerator {})
        assertSameOcean(reference, declined)
    }

    private fun decliningAccelerator(onAsk: () -> Unit) = object : OceanAccelerator {
        override val name = "declining test device"
        override suspend fun solve(stencil: OceanStencil, start: FloatArray, passes: Int): FloatArray? {
            onAsk()
            return null
        }
    }

    private fun assertSameOcean(expected: OceanResult, actual: OceanResult) {
        assertContentEquals(expected.velocityX.data, actual.velocityX.data)
        assertContentEquals(expected.velocityY.data, actual.velocityY.data)
        assertContentEquals(expected.temperature.data, actual.temperature.data)
        assertContentEquals(expected.anomaly.data, actual.anomaly.data)
    }

    private companion object {
        const val CELLS_ACROSS = 8

        val config = WorldGenConfig(width = CELLS_ACROSS, height = CELLS_ACROSS).let {
            it.copy(ocean = it.ocean.copy(heatGrid = OceanHeatGrid.FINE))
        }

        val acceleratedConfig =
            config.copy(erosion = config.erosion.copy(acceleration = Acceleration.GPU))

        /** A meridional wall of land, so the gyres have a coast to close against. */
        val sea = BooleanArray(CELLS_ACROSS * CELLS_ACROSS) { it % CELLS_ACROSS == 3 }.let { land ->
            SeaLevelResult(0.5f, land, FloatField(CELLS_ACROSS, CELLS_ACROSS), land.count { it })
        }
    }
}
