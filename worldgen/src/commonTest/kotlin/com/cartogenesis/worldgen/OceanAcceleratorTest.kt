package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.Acceleration
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.OceanAccelerator
import com.cartogenesis.worldgen.pipeline.OceanResult
import com.cartogenesis.worldgen.pipeline.OceanStage
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
 * the reader has turned acceleration on, it is handed the coarse problem the CPU would have solved,
 * and a decline leaves the reference answer untouched rather than a half-solved one.
 */
class OceanAcceleratorTest {

    @Test
    fun `the device is asked only when acceleration is on`() = runTest {
        val asks = mutableListOf<Int>()
        val counting = decliningAccelerator { passes -> asks += passes }

        OceanStage.generate(config, sea, counting)
        assertEquals(emptyList(), asks, "the processor path must not reach for a device")

        OceanStage.generate(acceleratedConfig, sea, counting)
        assertEquals(listOf(RELAXATION_PASSES), asks, "the accelerated path asked once")

        val noOcean = acceleratedConfig.copy(ocean = config.ocean.copy(enabled = false))
        OceanStage.generate(noOcean, sea, counting)
        assertEquals(listOf(RELAXATION_PASSES), asks, "a world with no currents has none to solve")

        OceanStage.generate(acceleratedConfig, sea, null)
        assertEquals(listOf(RELAXATION_PASSES), asks, "a host with no device solves on the CPU")
    }

    @Test
    fun `the device is handed the coarse problem and not the full grid`() = runTest {
        var seen: Problem? = null
        val recording = object : OceanAccelerator {
            override val name = "recording test device"
            override suspend fun solve(
                cellsAcross: Int,
                cellsDown: Int,
                isWater: BooleanArray,
                forcing: FloatArray,
                passes: Int,
                overRelaxation: Float
            ): FloatArray? {
                seen = Problem(cellsAcross, cellsDown, isWater.size, forcing, overRelaxation)
                return null
            }
        }
        OceanStage.generate(acceleratedConfig, sea, recording)

        val problem = checkNotNull(seen)
        assertEquals(SOLVE_RESOLUTION, problem.cellsAcross)
        assertEquals(SOLVE_RESOLUTION, problem.cellsDown)
        assertEquals(SOLVE_RESOLUTION * SOLVE_RESOLUTION, problem.maskCells)
        assertEquals(config.ocean.overRelaxation, problem.overRelaxation)
        assertTrue(problem.forcing.any { it != 0f }, "a forcing of nothing would spin nothing")
    }

    /**
     * The problem the device is handed is the CPU's own, array for array: a device that answers
     * with the reference solve of whatever it was handed gives back the reference ocean to the last
     * bit only if the mask, the forcing, the passes and the relaxation it was handed are the ones
     * the CPU would have solved. The case above checks the sizes and that the forcing is not
     * nothing; this is what says it is the right forcing on the right mask.
     */
    @Test
    fun `the device is handed exactly the problem the CPU solves`() = runTest {
        val reference = OceanStage.generate(config, sea)
        val answeringLikeTheCpu = object : OceanAccelerator {
            override val name = "reference-solving test device"
            override suspend fun solve(
                cellsAcross: Int,
                cellsDown: Int,
                isWater: BooleanArray,
                forcing: FloatArray,
                passes: Int,
                overRelaxation: Float
            ): FloatArray = OceanStage.solveOnCpu(cellsAcross, cellsDown, isWater, forcing, passes, overRelaxation)
        }
        assertSameOcean(reference, OceanStage.generate(acceleratedConfig, sea, answeringLikeTheCpu))
    }

    @Test
    fun `a decline leaves the reference answer exactly`() = runTest {
        val reference = OceanStage.generate(config, sea)
        val declined = OceanStage.generate(acceleratedConfig, sea, decliningAccelerator {})
        assertSameOcean(reference, declined)
    }

    private data class Problem(
        val cellsAcross: Int,
        val cellsDown: Int,
        val maskCells: Int,
        val forcing: FloatArray,
        val overRelaxation: Float
    )

    private fun decliningAccelerator(onAsk: (Int) -> Unit) = object : OceanAccelerator {
        override val name = "declining test device"
        override suspend fun solve(
            cellsAcross: Int,
            cellsDown: Int,
            isWater: BooleanArray,
            forcing: FloatArray,
            passes: Int,
            overRelaxation: Float
        ): FloatArray? {
            onAsk(passes)
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
        const val SOLVE_RESOLUTION = 4
        const val RELAXATION_PASSES = 3

        val config = WorldGenConfig(width = CELLS_ACROSS, height = CELLS_ACROSS).let {
            it.copy(
                ocean = it.ocean.copy(
                    solveResolution = SOLVE_RESOLUTION,
                    relaxationPasses = RELAXATION_PASSES
                )
            )
        }

        val acceleratedConfig =
            config.copy(erosion = config.erosion.copy(acceleration = Acceleration.GPU))

        /** A meridional wall of land, so the gyres have a coast to close against. */
        val sea = BooleanArray(CELLS_ACROSS * CELLS_ACROSS) { it % CELLS_ACROSS == 3 }.let { land ->
            SeaLevelResult(
                0.5f,
                land,
                FloatField(CELLS_ACROSS, CELLS_ACROSS),
                land.count { it }
            )
        }
    }
}
