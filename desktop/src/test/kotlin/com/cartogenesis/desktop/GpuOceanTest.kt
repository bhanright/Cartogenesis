package com.cartogenesis.desktop

import com.cartogenesis.cartography.WorldCodec
import com.cartogenesis.cartography.WorldDocument
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.model.Acceleration
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ErosionStage
import com.cartogenesis.worldgen.pipeline.OceanAccelerator
import com.cartogenesis.worldgen.pipeline.OceanResult
import com.cartogenesis.worldgen.pipeline.OceanStage
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.SeaLevelResult
import com.cartogenesis.worldgen.pipeline.SeaLevelStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class GpuOceanTest {
    @Test
    fun `seeded currents agree at preview and export resolutions`() = runBlocking {
        val gpu = device() ?: return@runBlocking
        for (side in listOf(512, 2048)) {
            for (seed in listOf(42L, 718106L, 59758L)) {
                val config = WorldGenConfig(seed = seed).atResolution(side, side)
                val sea = seaFor(config)
                val cpu = OceanStage.generate(config, sea)
                val accelerated = required(gpu)
                val onGpu = OceanStage.generate(acceleratedConfig(config), sea, accelerated)
                val difference = difference(cpu, onGpu, sea)
                println("OCEAN seed=$seed grid=$side $difference tolerance=${tolerance(config)} cells/pass")
                assertParity(difference, config)
                if (side == 512 && seed == 42L) drawCurrents(config, sea, cpu, onGpu)
            }
        }
    }

    @Test
    fun `current renders on the other geography review seeds`() = runBlocking {
        val gpu = device() ?: return@runBlocking
        for (seed in listOf(7L, 1234L)) {
            val config = WorldGenConfig(seed = seed)
            val sea = seaFor(config)
            val cpu = OceanStage.generate(config, sea)
            val onGpu = OceanStage.generate(acceleratedConfig(config), sea, required(gpu))
            assertParity(difference(cpu, onGpu, sea), config)
            drawCurrents(config, sea, cpu, onGpu)
        }
    }

    @Test
    fun `a wrong relaxation schedule fails the same parity guard`() = runBlocking {
        val gpu = device() ?: return@runBlocking
        val config = WorldGenConfig(seed = 42L)
        val sea = seaFor(config)
        val cpu = OceanStage.generate(config, sea)
        // An omitted solve is a deliberately broken dispatch schedule, not a fallback.
        val wrong = object : OceanAccelerator by gpu {
            override suspend fun solve(
                cellsAcross: Int, cellsDown: Int, isWater: BooleanArray, forcing: FloatArray,
                passes: Int, overRelaxation: Float
            ): FloatArray = assertNotNull(
                gpu.solve(cellsAcross, cellsDown, isWater, forcing, 0, overRelaxation)
            )
        }
        val actual = OceanStage.generate(acceleratedConfig(config), sea, wrong)
        val difference = difference(cpu, actual, sea)
        println("OCEAN deliberately omitted dispatches: $difference tolerance=${tolerance(config)}")
        assertFailsWith<AssertionError> { assertParity(difference, config) }
    }

    @Test
    fun `ocean stage wall clock at export sizes`() = runBlocking {
        val gpu = device() ?: return@runBlocking
        for (side in listOf(2048, 4096)) {
            val config = WorldGenConfig(seed = 42L).atResolution(side, side)
            val sea = seaFor(config)
            val accelerated = required(gpu)
            val gpuConfig = acceleratedConfig(config)
            // Include transfers, interpolation, velocity conversion and temperature advection.
            OceanStage.generate(config, sea)
            OceanStage.generate(gpuConfig, sea, accelerated)
            val cpuMillis = measureTimeMillis { OceanStage.generate(config, sea) }
            val gpuMillis = measureTimeMillis { OceanStage.generate(gpuConfig, sea, accelerated) }
            println("OCEAN timing grid=$side CPU=${cpuMillis}ms GPU=${gpuMillis}ms device=${gpu.name}")
        }
    }

    @Test
    fun `saved accelerated ocean reopens exactly without recomputation`() = runBlocking {
        val gpu = device() ?: return@runBlocking
        val config = acceleratedConfig(WorldGenConfig(seed = 42L, width = 64, height = 64))
        val world = WorldGenerationEngine.generate(config, oceanAccelerator = required(gpu))
        val document = WorldDocument(id = "gpu-ocean", title = "Currents", config = config, savedAt = 0L)
        val bytes = WorldCodec.encode(document, world)
        val restored = assertNotNull(WorldCodec.decode(bytes).world)
        assertContentEquals(world.ocean.velocityX.data, restored.ocean.velocityX.data)
        assertContentEquals(world.ocean.velocityY.data, restored.ocean.velocityY.data)
        assertContentEquals(world.ocean.temperature.data, restored.ocean.temperature.data)
        assertContentEquals(world.ocean.anomaly.data, restored.ocean.anomaly.data)
        val reused = WorldGenerationEngine.generate(config, previous = restored)
        assertSame(restored.ocean, reused.ocean)
    }

    @Test
    fun `odd wrapped widths decline and zero passes preserve zero`() = runBlocking {
        val gpu = device() ?: return@runBlocking
        assertNull(gpu.solve(3, 2, BooleanArray(6) { true }, FloatArray(6) { 1f }, 2, 1.7f))
        val water = booleanArrayOf(true, false, true, true)
        val forcing = floatArrayOf(1f, 2f, 3f, 4f)
        assertContentEquals(FloatArray(4), gpu.solve(2, 2, water, forcing, 0, 1.7f))
        assertContentEquals(booleanArrayOf(true, false, true, true), water)
        assertContentEquals(floatArrayOf(1f, 2f, 3f, 4f), forcing)
    }

    private fun device(): GpuOcean? {
        val probe = GpuOcean.createOrNull()
        if (probe.accelerator == null) println("OCEAN GPU unavailable here: ${probe.unavailableBecause}")
        else println("OCEAN GPU device: ${probe.accelerator.name}")
        return probe.accelerator
    }

    private fun required(gpu: OceanAccelerator): OceanAccelerator = object : OceanAccelerator by gpu {
        override suspend fun solve(
            cellsAcross: Int, cellsDown: Int, isWater: BooleanArray, forcing: FloatArray,
            passes: Int, overRelaxation: Float
        ): FloatArray = assertNotNull(
            gpu.solve(cellsAcross, cellsDown, isWater, forcing, passes, overRelaxation),
            "An available device declined the measured solve; CPU fallback is not GPU parity"
        )
    }

    private fun acceleratedConfig(config: WorldGenConfig) =
        config.copy(erosion = config.erosion.copy(acceleration = Acceleration.GPU))

    private suspend fun seaFor(config: WorldGenConfig): SeaLevelResult {
        val plates = PlateStage.generate(config, TerrainStage.generate(config))
        val erosion = ErosionStage.apply(config, plates.height, plates.upliftRateMmPerYear)
        // Glaciation preserves this mask; velocity depends on the mask and wind forcing only.
        return SeaLevelStage.apply(erosion.height, config)
    }

    private data class Difference(val worst: Double, val mean: Double) {
        override fun toString() = "worst=$worst mean=$mean cells/pass"
    }

    private fun difference(cpu: OceanResult, gpu: OceanResult, sea: SeaLevelResult): Difference {
        var worst = 0.0
        var total = 0.0
        var components = 0
        val expectedFields = arrayOf(cpu.velocityX.data, cpu.velocityY.data)
        val actualFields = arrayOf(gpu.velocityX.data, gpu.velocityY.data)
        for (cell in sea.isLand.indices) {
            for (axis in expectedFields.indices) {
                val expected = expectedFields[axis][cell]
                val actual = actualFields[axis][cell]
                assertTrue(actual.isFinite(), "non-finite current at cell $cell")
                if (sea.isLand[cell]) assertTrue(actual == 0f, "current on land at cell $cell")
                val delta = abs(expected.toDouble() - actual)
                worst = maxOf(worst, delta)
                if (!sea.isLand[cell]) { total += delta; components++ }
            }
        }
        assertTrue(components > 0, "no water compared")
        return Difference(worst, total / components)
    }

    private fun assertParity(difference: Difference, config: WorldGenConfig) {
        val limit = tolerance(config)
        assertTrue(difference.worst <= limit, "worst ${difference.worst} exceeds $limit")
        assertTrue(difference.mean <= limit, "mean ${difference.mean} exceeds $limit")
    }

    private fun tolerance(config: WorldGenConfig): Double {
        // Binary32 unit roundoff is 2^-24. Eight rounded operations per update, accumulated as
        // sqrt(passes), give an engineering roundoff budget (not a universal SOR error theorem).
        // Scale by the configured peak current, since streamToVelocity normalises to that speed.
        // GLSL precise keeps the arithmetic order; WGSL may contract the multiply and add.
        val unitRoundoff = 1.0 / (1 shl 24)
        return 8 * sqrt(config.ocean.relaxationPasses.toDouble()) * unitRoundoff *
            config.ocean.speedCellsPerPass
    }

    private fun drawCurrents(
        config: WorldGenConfig, sea: SeaLevelResult, cpu: OceanResult, gpu: OceanResult
    ) {
        val image = BufferedImage(config.width * 2, config.height, BufferedImage.TYPE_INT_RGB)
        val results = arrayOf(cpu, gpu)
        for (panel in results.indices) {
            val ocean = results[panel]
            for (cell in sea.isLand.indices) {
                // Red is eastward flow, green southward; one fixed speed scale for both panels.
                val red = ((0.5f + ocean.velocityX.data[cell] / (2 * config.ocean.speedCellsPerPass)) * 255)
                    .toInt().coerceIn(0, 255)
                val green = ((0.5f + ocean.velocityY.data[cell] / (2 * config.ocean.speedCellsPerPass)) * 255)
                    .toInt().coerceIn(0, 255)
                val colour = if (sea.isLand[cell]) 0x303030 else (red shl 16) or (green shl 8) or 128
                image.setRGB(panel * config.width + cell % config.width, cell / config.width, colour)
            }
        }
        val directory = File("../worldgen/build/maps").apply { mkdirs() }
        ImageIO.write(image, "png", File(directory, "g3-currents-${config.seed}-cpu-left-gpu-right.png"))
    }
}
