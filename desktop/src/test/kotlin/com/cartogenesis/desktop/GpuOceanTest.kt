package com.cartogenesis.desktop

import com.cartogenesis.cartography.WorldCodec
import com.cartogenesis.cartography.WorldDocument
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.model.Acceleration
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ErosionStage
import com.cartogenesis.worldgen.pipeline.OceanCirculation
import com.cartogenesis.worldgen.pipeline.OceanHeat
import com.cartogenesis.worldgen.pipeline.OceanResult
import com.cartogenesis.worldgen.pipeline.OceanStage
import com.cartogenesis.worldgen.pipeline.OceanStencil
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.SeaLevelResult
import com.cartogenesis.worldgen.pipeline.SeaLevelStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import kotlin.math.abs
import kotlin.math.sin
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The graphics card's relaxation of the ocean's two problems, held to the processor's.
 *
 * Two levels of agreement. A batch of passes on one stencil, at aspect 1.0 and 0.5, must agree with
 * the processor's to the rounding of the passes run; and a whole ocean, solved with the card doing
 * every batch it will take, must agree with the processor's ocean to the tolerance both solves stop
 * at. Each bound is shown rejecting a wrong kernel: a batch stopped a tenth of the way, and a batch
 * that reads east for west.
 *
 * On a machine with no usable device every case here is skipped, since a headless runner is not a
 * broken build and has not checked the kernel either. A machine that has a device and cannot
 * compile the shader is a different matter and fails.
 *
 * Each case says `: Unit` because `runBlocking` returns its block's last value, and JUnit does not
 * run a test method that returns one.
 */
class GpuOceanTest {

    /**
     * One batch of passes on a Stommel basin's stencil and on a heat stencil, at aspect 1.0 and
     * 0.5, on the card and on the processor from the same start.
     */
    @Test
    fun `a batch of passes agrees with the processor's at both aspects`(): Unit = runBlocking {
        val gpu = deviceOrSkip()
        for (aspect in listOf(1.0, 0.5)) {
            for ((name, stencil) in listOf("circulation" to circulationStencil(aspect), "heat with upwelling" to heatStencil(aspect, withUpwelling = true), "heat" to heatStencil(aspect))) {
                val start = FloatArray(stencil.forcing.size) { if (stencil.isWater[it]) 1f else 0f }
                val onCpu = start.copyOf().also { OceanCirculation.relax(stencil, it, BATCH_PASSES) }
                val onGpu = assertNotNull(gpu.solve(stencil, start, BATCH_PASSES), "the device declined a $name stencil")
                val worst = worstDifference(onCpu, onGpu)
                val bound = batchBound(onCpu, BATCH_PASSES)
                println("OCEAN batch parity $name at aspect $aspect: worst $worst against a bound of $bound")
                assertTrue(worst <= bound, "the $name batch at aspect $aspect differs by $worst, past the $bound rounding allows")
            }
        }
    }

    /**
     * The batch bound rejects a kernel that is wrong rather than rounded differently: one stopped a
     * tenth of the way, and one reading the eastern neighbor's weight for the western.
     */
    @Test
    fun `the batch bound rejects a batch stopped short and one that reads east for west`(): Unit = runBlocking {
        val gpu = deviceOrSkip()
        val stencil = circulationStencil(1.0)
        val start = FloatArray(stencil.forcing.size) { if (stencil.isWater[it]) 1f else 0f }
        val onCpu = start.copyOf().also { OceanCirculation.relax(stencil, it, BATCH_PASSES) }
        val bound = batchBound(onCpu, BATCH_PASSES)
        val stopped = assertNotNull(gpu.solve(stencil, start, BATCH_PASSES / UNCONVERGED_SHARE_OF_BATCH))
        val mirrored = OceanStencil(
            stencil.cellsAcross, stencil.cellsDown, stencil.isWater, stencil.westWeight, stencil.eastWeight,
            stencil.northWeight, stencil.southWeight, stencil.forcing, DoubleArray(stencil.forcing.size) { 1.0 }
        )
        val swapped = assertNotNull(gpu.solve(mirrored, start, BATCH_PASSES))
        println("OCEAN batch controls: stopped a tenth of the way ${worstDifference(onCpu, stopped)}, east read for west ${worstDifference(onCpu, swapped)}, bound $bound")
        assertTrue(worstDifference(onCpu, stopped) > bound, "a batch stopped a tenth of the way passed the bound")
        assertTrue(worstDifference(onCpu, swapped) > bound, "a batch reading east for west passed the bound")
    }

    /**
     * Whole oceans on the standard seeds at 512 rows of square cells and on seed 42 at 1024 rows,
     * 2048 by 1024: the card's against the processor's, currents and anomaly, to the tolerance both
     * solves stop at. The solve grid is the planet's and not the map's, square on the ground at
     * every size, so what the map's shape moves is only the resampling onto it.
     */
    @Test
    fun `whole oceans agree with the processor's on the standard seeds`(): Unit = runBlocking {
        val gpu = deviceOrSkip()
        val cases = listOf(42L to 512, 718106L to 512, 59758L to 512, 42L to 1024)
        for ((seed, rows) in cases) {
            val side = "$rows rows"
            val config = WorldGenConfig.forRows(seed = seed, rows = rows)
            val sea = seaFor(config)
            var onCpu: OceanResult? = null
            var onGpu: OceanResult? = null
            val cpuMillis = measureTimeMillis { onCpu = OceanStage.generate(config, sea) }
            val gpuMillis = measureTimeMillis { onGpu = OceanStage.generate(onGpuConfig(config), sea, gpu) }
            val speed = worstSpeedDifference(onCpu!!, onGpu!!, sea)
            val anomaly = worstAnomalyDifference(onCpu!!, onGpu!!, sea)
            val speedBound = speedBound(onCpu!!, sea)
            val anomalyBound = anomalyBound(onCpu!!, sea)
            println("OCEAN parity seed $seed at $side: currents $speed m/s against $speedBound, anomaly $anomaly C against $anomalyBound; processor $cpuMillis ms, device $gpuMillis ms")
            assertTrue(speed <= speedBound, "seed $seed at $side: the currents differ by $speed m/s, past $speedBound")
            assertTrue(anomaly <= anomalyBound, "seed $seed at $side: the anomaly differs by $anomaly C, past $anomalyBound")
        }
    }

    /**
     * That an accelerated ocean survives being saved and reopened: the only reason a world whose
     * ocean was solved on the card can be reopened at all.
     */
    @Test
    fun `an accelerated ocean is saved and reopens unchanged`(): Unit = runBlocking {
        val gpu = deviceOrSkip()
        val config = onGpuConfig(WorldGenConfig.forRows(seed = 42L, rows = 32))
        val world = WorldGenerationEngine.generate(config, oceanAccelerator = gpu)
        val document = WorldDocument(id = "gpu-ocean", title = "Currents", config = config, savedAt = 0L)
        val restored = assertNotNull(WorldCodec.decode(WorldCodec.encode(document, world)).world)
        assertContentEquals(world.ocean.velocityX.data, restored.ocean.velocityX.data)
        assertContentEquals(world.ocean.velocityY.data, restored.ocean.velocityY.data)
        assertContentEquals(world.ocean.temperature.data, restored.ocean.temperature.data)
        assertContentEquals(world.ocean.anomaly.data, restored.ocean.anomaly.data)
        val reused = WorldGenerationEngine.generate(config, previous = restored)
        assertSame(restored.ocean, reused.ocean)
    }

    /** The declines the seam promises, and that neither input is written through. */
    @Test
    fun `an odd width or a small grid declines, and no passes returns the start`(): Unit = runBlocking {
        val gpu = deviceOrSkip()
        val odd = OceanStencil(3, 2, BooleanArray(6) { true }, FloatArray(6), FloatArray(6), FloatArray(6), FloatArray(6), FloatArray(6) { 1f }, DoubleArray(6) { 1.0 })
        assertNull(gpu.solve(odd, FloatArray(6), 2))
        val tiny = circulationStencilOf(8, 8, 25_000.0, 25_000.0)
        assertNull(gpu.solve(tiny, FloatArray(64), 2), "a grid too small to be worth the card was taken")
        val stencil = circulationStencil(1.0)
        val start = FloatArray(stencil.forcing.size) { it.toFloat() }
        val forcing = stencil.forcing.copyOf()
        val result = assertNotNull(gpu.solve(stencil, start, 0))
        assertContentEquals(FloatArray(start.size) { it.toFloat() }, result)
        assertContentEquals(FloatArray(start.size) { it.toFloat() }, start)
        assertContentEquals(forcing, stencil.forcing)
    }

    private fun circulationStencil(aspect: Double): OceanStencil =
        circulationStencilOf(512, (256 / aspect).toInt(), 6_250.0, 6_250.0 * aspect)

    /** Stommel's basin: a land column at each edge, β of 30 degrees, a sine of negative curl. */
    private fun circulationStencilOf(across: Int, down: Int, dx: Double, dy: Double): OceanStencil {
        val isWater = BooleanArray(across * down) { val column = it % across; column != 0 && column != across - 1 }
        val forcing = DoubleArray(across * down) { cell -> -1e-12 * sin(Math.PI * (cell / across + 0.5) / down) }
        return OceanCirculation.stencil(across, down, dx, dy, isWater, DoubleArray(down) { 6.61e-11 }, OceanStage.BOTTOM_DRAG_PER_S, forcing)
    }

    /**
     * A heat stencil on a gyre-shaped current in the same basin, warm to the south; with
     * [withUpwelling], water rising along the eastern shore and weakly over the southern half, at
     * a temperature five degrees under the latitude's, which puts the entrainment's reaction in the
     * center weight and its source in the balance, the two places the upwelling enters the stencil.
     */
    private fun heatStencil(aspect: Double, withUpwelling: Boolean = false): OceanStencil {
        val across = 512
        val down = (256 / aspect).toInt()
        val dx = 6_250.0
        val dy = dx * aspect
        val isWater = BooleanArray(across * down) { val column = it % across; column != 0 && column != across - 1 }
        val stream = FloatArray(across * down) { cell ->
            val column = cell % across
            val row = cell / across
            (2e4 * sin(Math.PI * column / across) * sin(Math.PI * (row + 0.5) / down)).toFloat()
        }
        val target = FloatArray(across * down) { cell -> if (isWater[cell]) 5f + 20f * (cell / across) / down else 0f }
        val entrainment = if (withUpwelling) FloatArray(across * down) { cell ->
            val rising = when {
                cell % across == across - 2 -> COASTAL_RISE_MPS
                cell / across >= down / 2 -> OPEN_OCEAN_RISE_MPS
                else -> 0.0
            }
            (rising / MIXED_LAYER_DEPTH_M).toFloat()
        } else null
        return OceanHeat.stencil(
            across, down, dx, dy, isWater, stream, target, OceanStage.RELAXATION_SECONDS, withTarget = true,
            diffusivityAt = { OceanHeat.diffusivity(it, WorldGenConfig().scale.radiusMeters) },
            entrainmentPerS = entrainment,
            subsurfaceC = if (withUpwelling) FloatArray(across * down) { target[it] - 5f } else null
        )
    }

    private fun worstDifference(expected: FloatArray, actual: FloatArray): Double {
        var worst = 0.0
        for (cell in expected.indices) {
            assertTrue(actual[cell].isFinite(), "cell $cell is not a number")
            worst = maxOf(worst, abs(expected[cell].toDouble() - actual[cell]))
        }
        return worst
    }

    /**
     * What rounding alone can leave between two batches of [passes], as a value.
     *
     * A cell update is [ROUNDED_OPERATIONS_PER_UPDATE] rounded operations, the four products, the
     * three sums of them and the forcing's subtraction, each off by at most binary32's unit
     * roundoff of the largest value in play. Gauss-Seidel does not amplify an error it is handed,
     * every weight being at least zero and together at most one, so the worst a batch can drift is
     * that per pass, summed over the passes: `passes × operations × u × max|x|`.
     */
    private fun batchBound(values: FloatArray, passes: Int): Double {
        val largest = values.maxOf { abs(it) }.toDouble()
        return passes * ROUNDED_OPERATIONS_PER_UPDATE * UNIT_ROUNDOFF * largest
    }

    /**
     * How far two solves of the circulation may leave the currents apart, in meters a second.
     *
     * Each stops once its residual is under [OceanCirculation.RESIDUAL_TOLERANCE] of the largest
     * forcing, and in the Sverdrup balance that governs most of the ocean a share of the forcing is
     * the same share of the velocity; two solves stopped anywhere inside the tolerance are twice it
     * apart at most, of the fastest current.
     */
    private fun speedBound(ocean: OceanResult, sea: SeaLevelResult): Double {
        var fastest = 0.0
        for (cell in sea.isLand.indices) {
            if (sea.isLand[cell]) continue
            fastest = maxOf(fastest, abs(ocean.velocityX.data[cell].toDouble()), abs(ocean.velocityY.data[cell].toDouble()))
        }
        return 2 * OceanCirculation.RESIDUAL_TOLERANCE * fastest
    }

    /**
     * How far two solves of the heat may leave the anomaly apart, in degrees Celsius.
     *
     * The heat's operator has a margin of `1/τ` on every row, so a solution is never further from
     * the exact one than τ times the largest residual; that residual is under the tolerance of the
     * largest right-hand side, the warmest target over τ. So each solve is within the tolerance of
     * the warmest target, read here as the warmest water, and two are twice it apart at most.
     */
    private fun anomalyBound(ocean: OceanResult, sea: SeaLevelResult): Double {
        var warmest = 0.0
        for (cell in sea.isLand.indices) {
            if (!sea.isLand[cell]) warmest = maxOf(warmest, abs(ocean.temperature.data[cell].toDouble()))
        }
        return 2 * OceanCirculation.RESIDUAL_TOLERANCE * warmest
    }

    private fun worstSpeedDifference(onCpu: OceanResult, onGpu: OceanResult, sea: SeaLevelResult): Double {
        var worst = 0.0
        for (cell in sea.isLand.indices) {
            if (sea.isLand[cell]) {
                assertTrue(onGpu.velocityX.data[cell] == 0f && onGpu.velocityY.data[cell] == 0f, "water moves over land at cell $cell")
                continue
            }
            worst = maxOf(worst, abs(onCpu.velocityX.data[cell] - onGpu.velocityX.data[cell]).toDouble(),
                abs(onCpu.velocityY.data[cell] - onGpu.velocityY.data[cell]).toDouble())
        }
        return worst
    }

    private fun worstAnomalyDifference(onCpu: OceanResult, onGpu: OceanResult, sea: SeaLevelResult): Double {
        var worst = 0.0
        for (cell in sea.isLand.indices) {
            if (sea.isLand[cell]) continue
            worst = maxOf(worst, abs(onCpu.anomaly.data[cell] - onGpu.anomaly.data[cell]).toDouble())
        }
        return worst
    }

    /**
     * The device; the calling test is skipped when this machine has no graphics context at all. A
     * machine with a context whose driver would not compile the shader is a fault and is thrown.
     */
    private fun deviceOrSkip(): GpuOcean {
        val probe = probed
        probe.accelerator?.let {
            println("OCEAN GPU device: ${it.name}")
            return it
        }
        if (GlContext.ensure().device != null) {
            throw AssertionError("this machine has an OpenGL context but no ocean accelerator: ${probe.unavailableBecause}")
        }
        skipWithoutDevice(probe.unavailableBecause)
    }

    private fun onGpuConfig(config: WorldGenConfig) =
        config.copy(erosion = config.erosion.copy(acceleration = Acceleration.GPU))

    /** The land mask the gyres close against. Glaciation preserves it, so it stops here. */
    private suspend fun seaFor(config: WorldGenConfig): SeaLevelResult {
        val plates = PlateStage.generate(config, TerrainStage.generate(config))
        val erosion = ErosionStage.apply(config, plates.height, plates.upliftRateMmPerYear)
        return SeaLevelStage.apply(erosion.height, config)
    }

    private companion object {
        /** Binary32's unit roundoff, `2^-24`: half the gap between 1 and the next float. */
        const val UNIT_ROUNDOFF = 1.0 / (1 shl 24)

        /** Rounded operations in one cell update: four products, three sums, the forcing's subtraction. */
        const val ROUNDED_OPERATIONS_PER_UPDATE = 8.0

        /** Passes in the batch the parity is taken over: fifty, a V-cycle's worth many times over. */
        const val BATCH_PASSES = 50

        /** The share of the batch the stopped control runs: a tenth. */
        const val UNCONVERGED_SHARE_OF_BATCH = 10

        /** A coastal upwelling's rise in one cell beside the shore, meters a second: about 10 m a day. */
        const val COASTAL_RISE_MPS = 1.2e-4

        /** An open ocean's Ekman suction, meters a second: about 0.1 m a day. */
        const val OPEN_OCEAN_RISE_MPS = 1.2e-6

        /** The mixed layer the rise renews, meters: the energy balance's fifty-meter slab. */
        const val MIXED_LAYER_DEPTH_M = 50.0

        /** The one probe of this machine that every test in the class shares. */
        val probed: GpuOcean.Result by lazy { GpuOcean.createOrNull() }
    }
}
