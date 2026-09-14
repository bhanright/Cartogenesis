package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.Acceleration
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.OceanAccelerator
import com.cartogenesis.worldgen.pipeline.OceanStage
import com.cartogenesis.worldgen.pipeline.SeaLevelResult
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class OceanAcceleratorTest {
    @Test
    fun `disabled acceleration never calls the device and decline keeps the reference`() = runTest {
        val config = WorldGenConfig(width = 8, height = 8).let {
            it.copy(ocean = it.ocean.copy(solveResolution = 4, relaxationPasses = 3))
        }
        val land = BooleanArray(64) { it % 8 == 3 }
        val sea = SeaLevelResult(0.5f, land, FloatField(8, 8), land.count { it })
        val reference = OceanStage.generate(config, sea)
        var calls = 0
        val decline = object : OceanAccelerator {
            override val name = "declining test device"
            override suspend fun solve(
                cellsAcross: Int, cellsDown: Int, isWater: BooleanArray, forcing: FloatArray,
                passes: Int, overRelaxation: Float
            ): FloatArray? {
                calls++
                assertEquals(4, cellsAcross)
                assertEquals(4, cellsDown)
                assertEquals(3, passes)
                assertEquals(config.ocean.overRelaxation, overRelaxation)
                assertTrue(forcing.any { it != 0f }, "forcing must reach the device")
                return null
            }
        }
        val disabled = OceanStage.generate(config, sea, decline)
        assertEquals(0, calls)
        val acceleratedConfig = config.copy(erosion = config.erosion.copy(acceleration = Acceleration.GPU))
        val fallback = OceanStage.generate(acceleratedConfig, sea, decline)
        assertEquals(1, calls)
        for (actual in listOf(disabled, fallback)) {
            assertContentEquals(reference.velocityX.data, actual.velocityX.data)
            assertContentEquals(reference.velocityY.data, actual.velocityY.data)
            assertContentEquals(reference.temperature.data, actual.temperature.data)
            assertContentEquals(reference.anomaly.data, actual.anomaly.data)
        }
        OceanStage.generate(acceleratedConfig.copy(ocean = config.ocean.copy(enabled = false)), sea, decline)
        assertEquals(1, calls, "a disabled ocean must not dispatch")
    }
}
