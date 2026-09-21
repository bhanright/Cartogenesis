package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ChannelInitiation
import com.cartogenesis.worldgen.pipeline.FlowRouting
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What the channel-head criterion costs, at every grid the generator offers.
 *
 * Rule 8 asks for a graphics path on any per-cell work, and this is per-cell work: an accumulation
 * over the flow tree, a gradient to each cell's receiver, an exponential for the cover factor and a
 * comparison, plus one topological order to carry a started channel downstream. This is the
 * measurement the decision is made on, in the manner of `VegetationCostTest` and
 * `PressureWindCostTest` — both measured and declined, and both recorded as such rather than left
 * as an open gap.
 *
 * Measured over made fields rather than over a generated world, because the question is what the
 * arithmetic costs and not what a world costs. The surface is a slope with a ripple on it so that
 * the flow tree branches rather than running as one comb: a tree that branches is the case the
 * accumulation and the drainage order are slowest on.
 */
class ChannelInitiationCostTest {

    private companion object {
        val grids = listOf(512, 1024, 2048)

        /** Runs per grid: two to let the just-in-time compiler settle, the rest measured. */
        const val WARM_UP_RUNS = 2
        const val MEASURED_RUNS = 5

        /**
         * The share of a 2048 world's whole generation above which this work would be worth a
         * graphics path.
         *
         * One per cent, rule 8's own figure and the same bar `VegetationCostTest` uses: below it a
         * kernel cannot pay for the device round trip, the parity test and the second
         * implementation in two shading languages. The accumulation and the topological order are
         * sequential over a tree in any case, which is the shape a graphics device is worst at.
         */
        const val WORTH_A_DEVICE_SHARE = 0.01

        /**
         * A 2048 world's generation, in seconds, as `GenerationSpeedTest` last reported it.
         *
         * Quoted rather than measured here so this stays a measurement of one piece of arithmetic.
         */
        const val WORLD_AT_2048_SECONDS = 180.0
    }

    @Test
    fun `the channel-head criterion costs a fraction of a world`() {
        grids.forEach { side ->
            val config = WorldGenConfig(seed = 7L, width = side, height = side)
            val cellCount = side * side
            val isLand = BooleanArray(cellCount) { true }
            val filled = slopeWithARipple(side)
            val flowTarget = FlowRouting.flowDirections(
                side, side, isLand, filled, filled, config.seed
            )
            val rainfall = madeRainfall(side)
            val cover = madeCover(side)
            val squareKilometresPerCell = config.squareKilometresPerCell

            fun run(): Int {
                val areaKm2 = ChannelInitiation.runoffWeightedAreaKm2(
                    side, side, isLand, cellCount, filled, flowTarget, rainfall,
                    squareKilometresPerCell
                )
                val gradient =
                    ChannelInitiation.gradientToReceiver(config, isLand, filled, flowTarget)
                val channel = BooleanArray(cellCount)
                for (cell in 0 until cellCount) {
                    channel[cell] = ChannelInitiation.isChannelHead(
                        areaKm2.data[cell], gradient[cell], cover.data[cell], config.rivers
                    )
                }
                val order =
                    FlowRouting.drainageOrder(side, side, isLand, flowTarget, cellCount)
                var heads = 0
                for (cell in order) {
                    if (!channel[cell]) continue
                    heads++
                    val receiver = flowTarget[cell]
                    if (receiver >= 0) channel[receiver] = true
                }
                return heads
            }

            repeat(WARM_UP_RUNS) { run() }
            val started = System.nanoTime()
            var channelCells = 0
            repeat(MEASURED_RUNS) { channelCells = run() }
            val millisecondsEach = (System.nanoTime() - started) / 1e6 / MEASURED_RUNS
            println(
                ("CHANNEL INITIATION COST at %d: %.1f ms over a whole grid of land, %d channel " +
                    "cells (%.3f%% of a %.0f s world at 2048)")
                    .format(
                        side, millisecondsEach, channelCells,
                        millisecondsEach / 1000.0 / WORLD_AT_2048_SECONDS * 100,
                        WORLD_AT_2048_SECONDS
                    )
            )
            if (side == 2048) {
                val share = millisecondsEach / 1000.0 / WORLD_AT_2048_SECONDS
                assertTrue(
                    share < WORTH_A_DEVICE_SHARE,
                    "the channel-head criterion is ${"%.3f".format(share * 100)}% of a 2048" +
                        " world, over rule 8's ${WORTH_A_DEVICE_SHARE * 100}% — it wants a device" +
                        " path behind the accelerator seam"
                )
            }
        }
    }

    /** Ground falling to the south with a ripple across it, so the flow tree branches. */
    private fun slopeWithARipple(side: Int): FloatField {
        val field = FloatField(side, side)
        for (row in 0 until side) {
            for (column in 0 until side) {
                val fall = 1f - row.toFloat() / side
                val ripple = 0.01f * sin(column * 0.35f) * sin(row * 0.11f)
                field.data[row * side + column] = fall + ripple
            }
        }
        return field
    }

    /** Rainfall in millimetres, from a desert band to a wet one and back. */
    private fun madeRainfall(side: Int): FloatField {
        val field = FloatField(side, side)
        for (row in 0 until side) {
            val millimetres = 900f + 850f * sin(row * 6.28318f / side)
            for (column in 0 until side) field.data[row * side + column] = millimetres
        }
        return field
    }

    /** Plant cover from bare to closed, so the exponential is exercised over its whole range. */
    private fun madeCover(side: Int): FloatField {
        val field = FloatField(side, side)
        for (cell in 0 until side * side) {
            field.data[cell] = (cell % 100) / 99f
        }
        return field
    }
}
