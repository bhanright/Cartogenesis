package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldScale
import com.cartogenesis.worldgen.pipeline.AtmosphereLevels
import com.cartogenesis.worldgen.pipeline.LevelExtrapolation
import com.cartogenesis.worldgen.pipeline.SphericalGrid
import com.cartogenesis.worldgen.pipeline.WaveResponse
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [LevelExtrapolation] against the model's own answers on more levels (`LevelExtrapolationReport`
 * prints the figures in full, with twenty-four levels and two worlds beside them).
 */
class LevelExtrapolationTest {

    private val radius = WorldScale().radiusMeters

    /**
     * **The bar**: pooled over Gill's heating, Hoskins and Karoly's heating and mountain and Rodwell
     * and Hoskins' monsoon, the extrapolated surface pressure stands nearer the sixteen-level answer,
     * by area, than the eight-level answer it is made from: the correction must move the answer the
     * way more levels do, or it is not a correction for too few levels.
     *
     * **Shown failing on 23411966**, whose boundary layer read the eight-level answer alone: that
     * answer is the control here, and it stands at its own distance, never nearer.
     */
    @Test
    fun `the extrapolated surface pressure stands nearer sixteen levels than eight do`() {
        val rows = BENCHMARK_ROWS
        val grid = SphericalGrid(rows, 2 * rows, radius)
        val extrapolated = ArrayList<Double>()
        val eight = ArrayList<Double>()
        for (case in LevelBenchmarks.cases(grid)) {
            val answers = listOf(4, 8, 16).associateWith { case.solve(AtmosphereLevels.equalMass(it)) }
            val sixteen = answers.getValue(16).surfacePressurePa
            val corrected = LevelExtrapolation.extrapolate(answers.getValue(8), answers.getValue(4)).surfacePressurePa
            extrapolated += LevelBenchmarks.relative(grid, corrected, sixteen, sixteen)
            eight += LevelBenchmarks.relative(grid, answers.getValue(8).surfacePressurePa, sixteen, sixteen)
            println("LEVEL EXTRAPOLATION ${case.name}: the surface pressure against sixteen levels, extrapolated %.3f, eight %.3f"
                .format(extrapolated.last(), eight.last()))
        }
        println("LEVEL EXTRAPOLATION pooled: extrapolated %.3f, eight %.3f".format(extrapolated.average(), eight.average()))
        assertTrue(
            extrapolated.average() < eight.average(),
            "the extrapolation stands %.3f from sixteen levels, the eight levels it starts from %.3f".format(extrapolated.average(), eight.average())
        )
    }

    /**
     * Where the coarser answer is the finer one's own profile, nothing is added; and the coarser
     * answer is carried to the finer levels' places exactly when it is linear in pressure, beyond
     * its own outermost points too ([LevelExtrapolation.coarseAt]).
     */
    @Test
    fun `a profile linear in pressure is carried exactly and adds nothing`() {
        val coarse = AtmosphereLevels.equalMass(4)
        val fine = AtmosphereLevels.equalMass(8)
        fun linear(pressurePa: Double) = 3.0 - 2.0e-5 * pressurePa
        val fields = Array(coarse.levelCount) { DoubleArray(2) { linear(coarse.levelPressurePa[it]) } }
        for (pressure in fine.levelPressurePa) {
            val carried = LevelExtrapolation.coarseAt(fields, coarse.levelPressurePa, pressure, 2)
            assertTrue(abs(carried[0] - linear(pressure)) < 1e-12, "at $pressure Pa: ${carried[0]} against ${linear(pressure)}")
        }
        val grid = SphericalGrid(6, 12, radius)
        fun response(levels: AtmosphereLevels) = WaveResponse(
            grid, levels, DoubleArray(grid.cellCount) { 100.0 + it }
        ) {
            WaveResponse.Fields(
                Array(levels.levelCount) { level -> DoubleArray(grid.cellCount) { linear(levels.levelPressurePa[level]) } },
                Array(levels.levelCount) { level -> DoubleArray((grid.rows + 1) * grid.columns) { linear(levels.levelPressurePa[level]) } },
                Array(levels.levelCount) { DoubleArray(grid.cellCount) },
                Array(levels.interiorCount) { interior -> DoubleArray(grid.cellCount) { linear(levels.interiorPressurePa[interior]) - linear(levels.surfacePressurePa) } },
                Array(levels.interiorCount) { DoubleArray(grid.cellCount) },
                DoubleArray(grid.cellCount)
            )
        }
        val fineResponse = response(fine)
        val extrapolated = LevelExtrapolation.extrapolate(fineResponse, response(coarse))
        for (level in 0 until fine.levelCount) {
            for (cell in 0 until grid.cellCount) {
                assertTrue(abs(extrapolated.eastward[level][cell] - fineResponse.eastward[level][cell]) < 1e-12, "the wind moved at level $level")
            }
        }
        for (cell in 0 until grid.cellCount) assertTrue(extrapolated.surfacePressurePa[cell] == fineResponse.surfacePressurePa[cell])
    }

    private companion object {
        /** The benchmarks' grid: the report's 120 rows, at which `LevelExtrapolationReport` measured the order. */
        const val BENCHMARK_ROWS = 120
    }
}
