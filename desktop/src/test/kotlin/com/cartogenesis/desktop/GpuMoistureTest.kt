package com.cartogenesis.desktop

import com.cartogenesis.worldgen.SharedWorlds
import com.cartogenesis.worldgen.model.Acceleration
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.MoistureLapBudget
import com.cartogenesis.worldgen.pipeline.MoistureMarch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.extension.ExtendWith

/**
 * The climate's moisture march on the graphics card, held to the processor's.
 *
 * **Parity.** On the standard seeds at 512 rows, and seed 42 at the application's 1,024 in the deep
 * tier (`:desktop:deepTest`, the climate's stage), the card's climate (every lap of its march on
 * the card, every other part of the run on the processor) is held to the processor's within
 * [PARITY_TOLERANCE] of the rain, on the measure the coupled loop
 * stops on: the area-weighted mean change of the annual rain over the land, and over the whole
 * planet, as a share of the mean rain. The worst cell is printed. The control is a world a degree
 * warmer marched on the processor, which the bound tells apart from the world it was made from.
 *
 * **Conservation.** The card's own ledger ([GpuMoisture.measuresBudget]): every crossing's water out
 * against its water in, and every lap's storage change against its sources less its rain, each
 * within the processor's bar ([CLOSURE_TOLERANCE], `MoistureClosureTest`'s), and no parcel below
 * zero, no tracer outside its water and no value not finite. The control is a march that loses a
 * ten thousandth of the condensate's rain at every crossing, which both closures catch (a hundred
 * thousandth trips the crossings' bar and not the laps', the condensate being a share of the rain).
 *
 * **Determinism.** One world marched twice on the card gives the same rain to the bit: the order
 * of the crossings is the processor's, and the rows of a column share nothing a race could move.
 *
 * On a machine with no usable device every case here is skipped; a machine that has a device and
 * cannot compile the kernels fails.
 */
@ExtendWith(SharedWorldsCheck::class)
class GpuMoistureTest {

    private companion object {
        /**
         * How far the card's climate may stand from the processor's, as a share of the mean annual
         * rain, over the land and over the whole planet: twice the coupled loop's own settling bar,
         * [MoistureMarch.CONVERGED_SHARE]. The loop stops when the year's rain has moved less than
         * that from one lap to the next, which leaves each run within about that of its fixed point
         * (at the bar, W2f's runs stopped 0.01 to 0.51% from theirs, docs/DESIGN_LEDGER.md); the
         * card and the processor march the same fixed point, so two runs, one on each, stand within
         * twice it of each other.
         */
        const val PARITY_TOLERANCE = 2.0 * MoistureMarch.CONVERGED_SHARE

        /**
         * How far a crossing's or a lap's water may stand from closing, as a share of its water in
         * or of its sources: a millionth, the processor's own bar (`MoistureClosureTest`).
         */
        const val CLOSURE_TOLERANCE = 1e-6

        /** The rows the standard worlds are held at, and the application's. */
        const val ROWS = 512
        const val APPLICATION_ROWS = 1024
        const val APPLICATION_SEED = 42L

        /**
         * The control's warming, degrees Celsius of global mean: a degree, against which the
         * rain moves by more than the loop's settling bar on every standard world.
         */
        const val CONTROL_WARMING_C = 1f

        /** The control's loss, as a share of the condensate's rain at each crossing. */
        const val CONTROL_LEAK_SHARE = 1e-4

        val probed: GpuMoisture.Result by lazy { GpuMoisture.createOrNull() }

        /**
         * Each world's climate on the processor and on the card, the card's budget with it; made
         * once, and held as its few arrays rather than as the world, which the test worker's heap
         * does not hold several of beside a climate.
         */
        val runs = HashMap<Pair<Long, Int>, Run>()
    }

    private class Run(
        val columns: Int,
        val rows: Int,
        val isLand: BooleanArray,
        val processor: FloatArray,
        val card: FloatArray,
        val budgets: List<MoistureLapBudget>
    )

    private fun deviceOrSkip(): GpuMoisture {
        val probe = probed
        probe.accelerator?.let {
            println("MOISTURE GPU device: ${it.name}")
            return it
        }
        if (GlContext.ensure().device != null) {
            throw AssertionError("this machine has an OpenGL context but no moisture accelerator: ${probe.unavailableBecause}")
        }
        skipWithoutDevice(probe.unavailableBecause)
    }

    private fun configAt(seed: Long, rows: Int): WorldGenConfig =
        if (rows <= ROWS) WorldGenConfig.forRows(seed, rows) else WorldGenConfig.forRows(seed, ROWS).atResolution(2 * rows, rows)

    private fun onTheCard(config: WorldGenConfig) = config.copy(erosion = config.erosion.copy(acceleration = Acceleration.GPU))

    private fun run(gpu: GpuMoisture, seed: Long, rows: Int): Run = runs.getOrPut(seed to rows) {
        val world = SharedWorlds.world(configAt(seed, rows))
        val processor = ClimateStage.generate(world.config, world.sea, world.ocean).precipitationMm.data
        gpu.measuresBudget = true
        gpu.clearRecords()
        val card = try {
            ClimateStage.generate(onTheCard(world.config), world.sea, world.ocean, gpu).precipitationMm.data
        } finally {
            gpu.measuresBudget = false
        }
        Run(world.width, world.height, world.sea.isLand, processor, card, gpu.budgets.toList())
    }

    /**
     * The mean change from [reference] to [other] on a grid of [columns] by [rows], over the land
     * ([isLand]) and over the planet, each as a share of [reference]'s mean, by area.
     */
    private fun meanChanges(columns: Int, rows: Int, isLand: BooleanArray, reference: FloatArray, other: FloatArray): Pair<Double, Double> {
        var landRain = 0.0
        var landMoved = 0.0
        var rain = 0.0
        var moved = 0.0
        for (row in 0 until rows) {
            val weight = cos(ClimateStage.latitudeOf(row, rows) * PI / 180.0)
            for (column in 0 until columns) {
                val cell = row * columns + column
                val change = abs(other[cell] - reference[cell]).toDouble()
                rain += weight * reference[cell]
                moved += weight * change
                if (isLand[cell]) {
                    landRain += weight * reference[cell]
                    landMoved += weight * change
                }
            }
        }
        return landMoved / landRain to moved / rain
    }

    private fun worstCell(reference: FloatArray, other: FloatArray): String {
        var worst = 0.0
        var at = 0
        for (cell in reference.indices) {
            val change = abs(other[cell] - reference[cell]).toDouble()
            if (change > worst) { worst = change; at = cell }
        }
        return "%.3f mm at cell %d (%.1f against %.1f)".format(worst, at, other[at], reference[at])
    }

    @Test
    fun `the card's rain is the processor's within the coupled loop's settling tolerance`() {
        val gpu = deviceOrSkip()
        for (seed in SharedWorlds.STANDARD_SEEDS) holdsParity(gpu, seed, ROWS)
    }

    @Test
    fun `at the application's grid the card's rain is the processor's and its budget closes`() {
        val gpu = deviceOrSkip()
        holdsParity(gpu, APPLICATION_SEED, APPLICATION_ROWS)
        closes(gpu, APPLICATION_SEED, APPLICATION_ROWS)
    }

    private fun holdsParity(gpu: GpuMoisture, seed: Long, rows: Int) {
        val climates = run(gpu, seed, rows)
        val (land, planet) = meanChanges(climates.columns, climates.rows, climates.isLand, climates.processor, climates.card)
        println(
            "MOISTURE PARITY seed %d at %d rows: land %.2e, planet %.2e of the mean rain against %.2e; worst %s"
                .format(seed, rows, land, planet, PARITY_TOLERANCE, worstCell(climates.processor, climates.card))
        )
        assertTrue(land <= PARITY_TOLERANCE, "seed $seed at $rows rows: the card's land rain stands %.2e from the processor's".format(land))
        assertTrue(planet <= PARITY_TOLERANCE, "seed $seed at $rows rows: the card's rain stands %.2e from the processor's".format(planet))
    }

    @Test
    fun `control - a world a degree warmer is outside the parity bound`() {
        deviceOrSkip()
        for (seed in SharedWorlds.STANDARD_SEEDS) {
            val world = SharedWorlds.world(configAt(seed, ROWS))
            val reference = ClimateStage.generate(world.config, world.sea, world.ocean).precipitationMm.data
            val warmer = world.config.copy(climate = world.config.climate.copy(globalMeanShiftC = world.config.climate.globalMeanShiftC + CONTROL_WARMING_C))
            val warmed = ClimateStage.generate(warmer, world.sea, world.ocean).precipitationMm.data
            val (land, planet) = meanChanges(world.width, world.height, world.sea.isLand, reference, warmed)
            println("MOISTURE PARITY control seed %d: a degree warmer moves the land's rain %.2e and the planet's %.2e".format(seed, land, planet))
            assertTrue(land > PARITY_TOLERANCE || planet > PARITY_TOLERANCE, "seed $seed: a world a degree warmer passed the parity bound")
        }
    }

    @Test
    fun `every crossing and every lap on the card close their budget`() {
        val gpu = deviceOrSkip()
        for (seed in SharedWorlds.STANDARD_SEEDS) closes(gpu, seed, ROWS)
    }

    private fun closes(gpu: GpuMoisture, seed: Long, rows: Int) {
        val budgets = run(gpu, seed, rows).budgets
        assertTrue(budgets.isNotEmpty(), "seed $seed at $rows rows: the card measured no laps")
        val worstCrossing = budgets.maxOf { it.worstCrossingImbalance }
        val worstLap = budgets.maxOf { abs(it.unaccounted) / it.sources }
        println(
            "MOISTURE CLOSURE seed %d at %d rows, %d laps of two halves: worst crossing %.2e of its water in, worst lap %.2e of its sources"
                .format(seed, rows, budgets.size / 2, worstCrossing, worstLap)
        )
        assertTrue(worstCrossing <= CLOSURE_TOLERANCE, "seed $seed at $rows rows: a crossing leaves %.2e of its water unaccounted".format(worstCrossing))
        assertTrue(worstLap <= CLOSURE_TOLERANCE, "seed $seed at $rows rows: a lap leaves %.2e of its sources unaccounted".format(worstLap))
        assertTrue(budgets.all { it.negativeParcels == 0 && it.tracerOutOfBounds == 0 && it.nonFinite == 0 }, "seed $seed at $rows rows: a guard tripped")
    }

    @Test
    fun `control - a march that loses a ten thousandth of the condensate's rain fails both closures`() {
        deviceOrSkip()
        val leaking = GpuMoisture.createLeakingForTheControl(CONTROL_LEAK_SHARE).accelerator
            ?: throw AssertionError("the leaking control's kernels would not compile")
        val world = SharedWorlds.world(configAt(APPLICATION_SEED, ROWS))
        leaking.measuresBudget = true
        ClimateStage.generate(onTheCard(world.config), world.sea, world.ocean, leaking)
        val worstCrossing = leaking.budgets.maxOf { it.worstCrossingImbalance }
        val worstLap = leaking.budgets.maxOf { abs(it.unaccounted) / it.sources }
        println("MOISTURE CLOSURE control, a leak of %.0e of the condensate's rain: worst crossing %.2e, worst lap %.2e".format(CONTROL_LEAK_SHARE, worstCrossing, worstLap))
        assertTrue(worstCrossing > CLOSURE_TOLERANCE, "a leaking crossing closed within the bar")
        assertTrue(worstLap > CLOSURE_TOLERANCE, "a leaking lap closed within the bar")
    }

    @Test
    fun `one world marched twice on the card gives the same rain to the bit`() {
        val gpu = deviceOrSkip()
        val world = SharedWorlds.world(configAt(APPLICATION_SEED, ROWS))
        val config = onTheCard(world.config)
        val first = ClimateStage.generate(config, world.sea, world.ocean, gpu).precipitationMm.data
        val second = ClimateStage.generate(config, world.sea, world.ocean, gpu).precipitationMm.data
        assertContentEquals(first, second, "the card's rain moved between two runs of one world")
        assertTrue(first.any { it > 0f }, "the world rained nowhere, so nothing was compared")
    }
}
