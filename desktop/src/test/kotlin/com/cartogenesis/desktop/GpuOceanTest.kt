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
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * What the graphics card buys on the stream-function solve, and what it costs in agreement.
 *
 * The comparison is made on the current field rather than on the stream function itself, because
 * the current field is what the rest of the generator sees: it drives the temperature advection,
 * and through that the sea-surface anomaly the climate reads. A solve that agreed on the stream
 * function and disagreed on its gradients would be no use.
 *
 * On a machine with no usable device this reports that and returns, since a headless runner is not
 * a broken build. A machine that *has* a device and cannot compile the shader is a different
 * matter and fails, because that is a real fault hiding behind the same silence.
 */
class GpuOceanTest {

    @Test
    fun `gpu currents match the cpu on the standard seeds at preview and export sizes`() =
        runBlocking {
            val gpu = deviceOrSkip() ?: return@runBlocking
            for (side in listOf(512, 2048)) {
                for (seed in listOf(42L, 718106L, 59758L)) {
                    val config = WorldGenConfig(seed = seed).atResolution(side, side)
                    val sea = seaFor(config)
                    val onCpu = OceanStage.generate(config, sea)
                    val onGpu = OceanStage.generate(onGpuConfig(config), sea, mustNotDecline(gpu))
                    val difference = difference(onCpu, onGpu, sea)
                    println(
                        "OCEAN parity seed=$seed grid=$side $difference " +
                            "(bound ${tolerance(config)})"
                    )
                    assertParity(difference, config)
                }
            }
        }

    /**
     * Ground rule 1: the two fields drawn side by side, on the geography reviews' own seeds.
     *
     * The numbers above say the arithmetic agrees; these say the gyres are in the same places, with
     * the same handedness, closed against the same coasts.
     */
    @Test
    fun `the gyres draw the same on the review seeds`() = runBlocking {
        val gpu = deviceOrSkip() ?: return@runBlocking
        for (seed in listOf(7L, 42L, 1234L)) {
            val config = WorldGenConfig(seed = seed)
            val sea = seaFor(config)
            val onCpu = OceanStage.generate(config, sea)
            val onGpu = OceanStage.generate(onGpuConfig(config), sea, mustNotDecline(gpu))
            assertParity(difference(onCpu, onGpu, sea), config)
            drawCurrents(config, sea, onCpu, onGpu)
        }
    }

    /**
     * Ground rule 2: the bound shown failing on a kernel that is wrong rather than rounded
     * differently.
     *
     * Two demonstrations, because the obvious one does not work and the reason it does not is
     * worth writing down.
     *
     * *Stopping the solve early.* Measured on seed 42 at 512, against the bound of 1.7e-4 cells
     * per pass: 2999 passes of 3000 gives 6.3e-6, 2970 gives 6.4e-6, 2700 gives 6.5e-6 and 1500
     * gives 6.6e-5 - all inside the bound, and the first three indistinguishable from the full
     * schedule. Three thousand passes converge this grid, so the last pass moves almost nothing;
     * and the current field is renormalised to a fixed peak speed, which divides out most of what
     * a uniform shortfall in convergence would otherwise have shown. The pass count bites only
     * once the solve is nowhere near converged: 300 passes gives 0.044, which is 260 times the
     * bound, and that is what this test holds.
     *
     * *Breaking the cylinder.* The mistake a port of this kernel would actually make is forgetting
     * that columns wrap, and that one the bound catches enormously. Shown by hand, by replacing
     * the shader's two modulo neighbours with a clamp to the grid's edges and running the parity
     * test above: seed 42 at 512 gave a worst cell of 1.272 and a mean of 0.151 cells per
     * advection pass, 7,400 and 880 times the bound. The gyres close against an invented wall down
     * the seam instead of running round the world.
     */
    @Test
    fun `the parity bound rejects a solve stopped a tenth of the way`() = runBlocking {
        val gpu = deviceOrSkip() ?: return@runBlocking
        val config = WorldGenConfig(seed = 42L)
        val sea = seaFor(config)
        val onCpu = OceanStage.generate(config, sea)
        val stoppedEarly = object : OceanAccelerator by gpu {
            override suspend fun solve(
                cellsAcross: Int, cellsDown: Int, isWater: BooleanArray, forcing: FloatArray,
                passes: Int, overRelaxation: Float
            ): FloatArray = assertNotNull(
                gpu.solve(
                    cellsAcross, cellsDown, isWater, forcing,
                    passes / UNCONVERGED_SHARE_OF_SCHEDULE, overRelaxation
                )
            )
        }
        val wrong = OceanStage.generate(onGpuConfig(config), sea, stoppedEarly)
        val difference = difference(onCpu, wrong, sea)
        println(
            "OCEAN a solve stopped a tenth of the way: $difference " +
                "(bound ${tolerance(config)})"
        )
        assertFailsWith<AssertionError> { assertParity(difference, config) }
    }

    /**
     * The wall clock on both paths, and where it goes.
     *
     * Two figures per size, because the stage's headline time is mostly not the part that moved.
     * The solve is on a `solveResolution` grid however large the world is, so it costs the same at
     * every size; what grows is the rest of the stage, above all the two-hundred-pass temperature
     * advection over every cell, which is lock-step per cell and stays on the processor. The
     * solve's own share is measured by differencing against a run with no relaxation passes, at
     * the smallest size, where the stage around it is cheap enough that the difference is not
     * buried in the noise of a four-second measurement.
     *
     * Nothing here is asserted, and that is deliberate. The solve's cost can only be had by
     * differencing two stage timings, and it is 50 to 250 ms inside a JVM that runs for twenty
     * minutes; the same kernel on the same machine gave 7.4x, 4.3x, 1.8x, 4.1x and 1.8x across
     * five runs, the last two with the rest of the suite around it, and the processor term alone
     * moved between 132 ms and 246 ms. Turning the advection off removed most of the noise and did
     * not remove enough. A bar on a number that unstable would fail on a busy machine while the
     * kernel worked - exactly the fault this chunk was asked to remove from `GpuErosionTest` -
     * and moving the bar until it passed would be tuning a guard to fit, which ground rule 5
     * forbids. So the figures are reported and the correctness clauses carry the test.
     *
     * The erosion sweeps do carry a bar, because `hydraulicRounds = 0` isolates them into a
     * directly measured quantity rather than a difference. The ocean has no equivalent seam from
     * outside :worldgen: `solveOnCpu` is private, and duplicating the reference solver in a test
     * to time it would be a second copy of the thing under test. If this is wanted as a guard, the
     * way in is an internal entry point for the coarse solve, which is a change to :worldgen this
     * chunk should not smuggle in.
     */
    @Test
    fun `ocean wall clock at export sizes`() = runBlocking {
        val gpu = deviceOrSkip() ?: return@runBlocking
        val accelerated = mustNotDecline(gpu)

        // The advection is turned off for this measurement. The solve's cost is obtained by
        // differencing a run against one with no relaxation passes, and at the shipped settings
        // the two-hundred-pass advection being subtracted is larger than the solve being measured,
        // so the difference of two noisy numbers was itself noisier than the answer: the same
        // machine gave 7.4x, 4.3x and 1.8x on three runs. With the advection out, what is left to
        // subtract is an interpolation and a gradient, and the difference is mostly signal.
        val solveConfig = WorldGenConfig(seed = 42L).let {
            it.copy(ocean = it.ocean.copy(advectionPasses = 0))
        }
        val noSolve = solveConfig.copy(ocean = solveConfig.ocean.copy(relaxationPasses = 0))
        val solveSea = seaFor(solveConfig)
        repeat(WARM_UP_RUNS) {
            OceanStage.generate(solveConfig, solveSea)
            OceanStage.generate(noSolve, solveSea)
            OceanStage.generate(onGpuConfig(solveConfig), solveSea, accelerated)
            OceanStage.generate(onGpuConfig(noSolve), solveSea, accelerated)
        }
        val cpuSolveMillis =
            fastestMillis { OceanStage.generate(solveConfig, solveSea) } -
                fastestMillis { OceanStage.generate(noSolve, solveSea) }
        val gpuSolveMillis =
            fastestMillis { OceanStage.generate(onGpuConfig(solveConfig), solveSea, accelerated) } -
                fastestMillis { OceanStage.generate(onGpuConfig(noSolve), solveSea, accelerated) }
        val solveSpeedUp = cpuSolveMillis.toDouble() / gpuSolveMillis
        println(
            "OCEAN timing device=${gpu.name} the solve alone " +
                "CPU=${cpuSolveMillis}ms GPU=${gpuSolveMillis}ms: " +
                "${"%.1f".format(solveSpeedUp)}x (reported, not asserted - see the KDoc)"
        )

        for (side in listOf(2048, 4096)) {
            val config = WorldGenConfig(seed = 42L).atResolution(side, side)
            val sea = seaFor(config)
            repeat(WARM_UP_RUNS) {
                OceanStage.generate(config, sea)
                OceanStage.generate(onGpuConfig(config), sea, accelerated)
            }
            val cpuStageMillis = fastestMillis { OceanStage.generate(config, sea) }
            val gpuStageMillis =
                fastestMillis { OceanStage.generate(onGpuConfig(config), sea, accelerated) }
            println(
                "OCEAN timing grid=$side whole stage " +
                    "CPU=${cpuStageMillis}ms GPU=${gpuStageMillis}ms"
            )
        }
    }

    /**
     * The quickest of [TIMED_RUNS] runs of [body], in milliseconds.
     *
     * The quickest rather than the mean: every source of noise on this machine — the scheduler, a
     * garbage collection, another core's work — can only make a run slower, so the floor is the
     * closest any of them comes to the cost being measured.
     */
    private fun fastestMillis(body: suspend () -> Unit): Long =
        (1..TIMED_RUNS).minOf { measureTimeMillis { runBlocking { body() } } }

    /**
     * That an accelerated ocean survives being saved and reopened.
     *
     * The save has carried all four ocean fields since the world went into it, so nothing here
     * needed adding for this chunk — but that is a claim worth holding, since it is the only
     * reason a world solved on the card can be reopened at all.
     */
    @Test
    fun `an accelerated ocean is saved and reopens unchanged`() = runBlocking {
        val gpu = deviceOrSkip() ?: return@runBlocking
        val config = onGpuConfig(WorldGenConfig(seed = 42L, width = 64, height = 64))
        val world = WorldGenerationEngine.generate(config, oceanAccelerator = mustNotDecline(gpu))
        val document =
            WorldDocument(id = "gpu-ocean", title = "Currents", config = config, savedAt = 0L)
        val restored = assertNotNull(WorldCodec.decode(WorldCodec.encode(document, world)).world)
        assertContentEquals(world.ocean.velocityX.data, restored.ocean.velocityX.data)
        assertContentEquals(world.ocean.velocityY.data, restored.ocean.velocityY.data)
        assertContentEquals(world.ocean.temperature.data, restored.ocean.temperature.data)
        assertContentEquals(world.ocean.anomaly.data, restored.ocean.anomaly.data)
        val reused = WorldGenerationEngine.generate(config, previous = restored)
        assertSame(restored.ocean, reused.ocean)
    }

    /** The two declines the seam promises, and that neither input is written through. */
    @Test
    fun `an odd width declines and no passes leaves the stream at zero`() = runBlocking {
        val gpu = deviceOrSkip() ?: return@runBlocking
        assertNull(gpu.solve(3, 2, BooleanArray(6) { true }, FloatArray(6) { 1f }, 2, 1.7f))
        val isWater = booleanArrayOf(true, false, true, true)
        val forcing = floatArrayOf(1f, 2f, 3f, 4f)
        assertContentEquals(FloatArray(4), gpu.solve(2, 2, isWater, forcing, 0, 1.7f))
        assertContentEquals(booleanArrayOf(true, false, true, true), isWater)
        assertContentEquals(floatArrayOf(1f, 2f, 3f, 4f), forcing)
    }

    /**
     * The device, or null when this machine has no graphics context at all.
     *
     * A machine with a context whose driver would not compile the shader is a fault and is thrown,
     * not skipped: silence there would let a broken kernel sit green for ever.
     */
    private fun deviceOrSkip(): GpuOcean? {
        // Probed once for the whole class: each probe compiles a program on the shared context,
        // and one per test would leave five of them behind for nothing.
        val probe = probed
        probe.accelerator?.let {
            println("OCEAN GPU device: ${it.name}")
            return it
        }
        if (GlContext.ensure().device != null) {
            throw AssertionError(
                "this machine has an OpenGL context but no ocean accelerator: " +
                    "${probe.unavailableBecause}"
            )
        }
        println("OCEAN GPU unavailable here: ${probe.unavailableBecause}")
        return null
    }

    /**
     * The accelerator with its decline taken away.
     *
     * A silent fall back to the CPU would make every comparison below trivially pass, which is the
     * one way this suite could lie about the device.
     */
    private fun mustNotDecline(gpu: OceanAccelerator): OceanAccelerator =
        object : OceanAccelerator by gpu {
            override suspend fun solve(
                cellsAcross: Int, cellsDown: Int, isWater: BooleanArray, forcing: FloatArray,
                passes: Int, overRelaxation: Float
            ): FloatArray = assertNotNull(
                gpu.solve(cellsAcross, cellsDown, isWater, forcing, passes, overRelaxation),
                "an available device declined the measured solve; a CPU fallback is not parity"
            )
        }

    private fun onGpuConfig(config: WorldGenConfig) =
        config.copy(erosion = config.erosion.copy(acceleration = Acceleration.GPU))

    /** The land mask the gyres close against. Glaciation preserves it, so it stops here. */
    private suspend fun seaFor(config: WorldGenConfig): SeaLevelResult {
        val plates = PlateStage.generate(config, TerrainStage.generate(config))
        val erosion = ErosionStage.apply(config, plates.height, plates.upliftRateMmPerYear)
        return SeaLevelStage.apply(erosion.height, config)
    }

    private data class Difference(val worstCell: Double, val mean: Double) {
        override fun toString() = "worst=$worstCell mean=$mean cells per advection pass"
    }

    /**
     * The largest and the average disagreement between two current fields, over both components of
     * every water cell, in cells per advection pass.
     */
    private fun difference(
        onCpu: OceanResult,
        onGpu: OceanResult,
        sea: SeaLevelResult
    ): Difference {
        var worstCell = 0.0
        var total = 0.0
        var compared = 0
        val expected = arrayOf(onCpu.velocityX.data, onCpu.velocityY.data)
        val actual = arrayOf(onGpu.velocityX.data, onGpu.velocityY.data)
        for (cell in sea.isLand.indices) {
            for (axis in expected.indices) {
                val accelerated = actual[axis][cell]
                assertTrue(accelerated.isFinite(), "a current at cell $cell is not a number")
                if (sea.isLand[cell]) {
                    assertTrue(accelerated == 0f, "water is moving over land at cell $cell")
                    continue
                }
                val delta = abs(expected[axis][cell].toDouble() - accelerated)
                worstCell = maxOf(worstCell, delta)
                total += delta
                compared++
            }
        }
        assertTrue(compared > 0, "this world has no open water to compare")
        return Difference(worstCell, total / compared)
    }

    private fun assertParity(difference: Difference, config: WorldGenConfig) {
        val limit = tolerance(config)
        assertTrue(
            difference.worstCell <= limit,
            "the worst cell differs by ${difference.worstCell} cells per pass, more than the " +
                "$limit that rounding alone can account for"
        )
        assertTrue(
            difference.mean <= limit,
            "the current field differs by ${difference.mean} cells per pass on average, more " +
                "than the $limit that rounding alone can account for"
        )
    }

    /**
     * How far one current component may differ from the CPU's before the difference is something
     * other than float arithmetic, in cells per advection pass.
     *
     * What this is not: a rigorous bound on the solve. The discrete Poisson operator on a grid
     * this size has a condition number in the thousands, and carrying the unit roundoff through it
     * honestly gives a bound of order a tenth of the peak speed - which every broken kernel this
     * suite tries would also satisfy, and which would therefore guard nothing. The ill-conditioned
     * part of the error is the low-frequency part, and the low-frequency part is smooth, so it is
     * not what survives a *difference* of neighbouring cells. This bounds the part that does.
     *
     * *One update.* Binary32's unit roundoff is [UNIT_ROUNDOFF]. A cell update is
     * [ROUNDED_OPERATIONS_PER_UPDATE] operations that round - three adds for the four neighbours,
     * the subtraction of the forcing, the subtraction of the cell's own value, and the blend's
     * multiply and add; the quarter is a power of two and is exact. Gauss-Seidel damps
     * cell-to-cell error in a handful of passes whatever the schedule, so the high-frequency error
     * sits at that floor rather than growing with the pass count.
     *
     * *The gradient.* `streamToVelocity` takes a central difference, so two uncertain values are
     * subtracted and their uncertainties add; then it rescales the whole field so its fastest cell
     * sits at `speedCellsPerPass`, which divides by the largest one-cell step in the field. The
     * ratio that leaves - the stream function's range over its steepest step - is at most the
     * coarse grid's side, because a path from the pinned coast to the extremum crosses at most
     * that many cells and its steepest step is therefore at least the range divided by that many.
     * Rows clamp rather than wrap, so the longest such path is a full column, not the half width
     * the wrapped rows would allow. Interpolating up to full resolution does not change the ratio:
     * a bilinear interpolant's fine-cell differences are the coarse ones divided by the same
     * number of cells, signal and error alike.
     *
     * *The units.* What is left is a fraction of the peak speed, so it is multiplied by
     * `speedCellsPerPass` to reach the units the two fields are compared in.
     *
     * At the shipped ocean settings that is 1.7e-4 cells per advection pass. The figures measured
     * on this machine run from 7.8e-6 to 4.1e-5 at the worst cell and from 4.2e-7 to 1.6e-6 on the
     * mean, so the bound is four to twenty times the worst disagreement seen and two to three
     * orders above the average one. The same figure holds both statistics: a separate, tighter
     * bound on the mean could only be a number read off these runs, which is the one thing a guard
     * may not be.
     */
    private fun tolerance(config: WorldGenConfig): Double {
        val ocean = config.ocean
        return ROUNDED_OPERATIONS_PER_UPDATE * UNIT_ROUNDOFF * CENTRAL_DIFFERENCE_TERMS *
            ocean.solveResolution * ocean.speedCellsPerPass
    }

    /** Draws the two current fields side by side, CPU on the left. See ground rule 1. */
    private fun drawCurrents(
        config: WorldGenConfig,
        sea: SeaLevelResult,
        onCpu: OceanResult,
        onGpu: OceanResult
    ) {
        val image = BufferedImage(config.width * 2, config.height, BufferedImage.TYPE_INT_RGB)
        val panels = arrayOf(onCpu, onGpu)
        for (panel in panels.indices) {
            val ocean = panels[panel]
            for (cell in sea.isLand.indices) {
                // Red is eastward flow and green southward, on one fixed scale for both panels, so
                // a gyre that turned the other way would be the other colour.
                val fullScale = 2 * config.ocean.speedCellsPerPass
                val red = ((0.5f + ocean.velocityX.data[cell] / fullScale) * 255)
                    .toInt().coerceIn(0, 255)
                val green = ((0.5f + ocean.velocityY.data[cell] / fullScale) * 255)
                    .toInt().coerceIn(0, 255)
                val colour =
                    if (sea.isLand[cell]) LAND_GREY else (red shl 16) or (green shl 8) or WATER_BLUE
                image.setRGB(
                    panel * config.width + cell % config.width, cell / config.width, colour
                )
            }
        }
        val directory = File("../worldgen/build/maps").apply { mkdirs() }
        ImageIO.write(image, "png", File(directory, "g3-currents-${config.seed}-cpu-then-gpu.png"))
    }

    private companion object {
        /** Binary32's unit roundoff, `2^-24`: half the gap between 1 and the next float. */
        const val UNIT_ROUNDOFF = 1.0 / (1 shl 24)

        /**
         * Operations in one cell update that round. Three adds for the neighbour sum, the
         * forcing's subtraction, the cell's own subtraction, and the blend's multiply-add; the
         * quarter is exact because it is a power of two.
         */
        const val ROUNDED_OPERATIONS_PER_UPDATE = 7.0

        /** A central difference subtracts two uncertain values, so their uncertainties add. */
        const val CENTRAL_DIFFERENCE_TERMS = 2.0

        /**
         * The share of the configured schedule that leaves the solve plainly unconverged.
         *
         * A tenth. Half the schedule is still inside the bound - the figures are on the test that
         * uses this - so a tenth is where a wrong pass count becomes visible at all.
         */
        const val UNCONVERGED_SHARE_OF_SCHEDULE = 10

        /** The one probe of this machine that every test in the class shares. */
        val probed: GpuOcean.Result by lazy { GpuOcean.createOrNull() }

        /** Runs made before the clock starts, to warm the driver and the code caches. */
        const val WARM_UP_RUNS = 1

        /** How many times each measurement is repeated before its floor is taken. */
        const val TIMED_RUNS = 3

        /** Land in the side-by-side render, dark enough not to be read as water. */
        const val LAND_GREY = 0x303030

        /** The blue channel every water cell carries, so still water is not black. */
        const val WATER_BLUE = 128
    }
}
