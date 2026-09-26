package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.OceanCirculation
import com.cartogenesis.worldgen.pipeline.OceanHeat
import com.cartogenesis.worldgen.pipeline.OceanStage
import com.cartogenesis.worldgen.pipeline.OceanStencil
import com.cartogenesis.worldgen.pipeline.SeaLevelResult
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The heat the currents carry, on synthetic grids at aspect 1.0 and 0.5: carried the same way in
 * every direction, never across land, converged, and mixed as the drifters measure.
 */
class OceanHeatTest {

    private val tau = OceanStage.RELAXATION_SECONDS

    /** Solves one heat problem to the stage's own tolerance. */
    private fun solve(
        across: Int,
        down: Int,
        dx: Double,
        dy: Double,
        isWater: BooleanArray,
        stream: FloatArray,
        target: FloatArray,
        diffusivity: (Double) -> Double = OceanHeat::diffusivity,
        tolerance: Double = OceanCirculation.RESIDUAL_TOLERANCE,
        mostIterations: Int = OceanCirculation.MOST_CYCLES
    ): OceanCirculation.Solution {
        val finest = OceanHeat.stencil(across, down, dx, dy, isWater, stream, target, tau, withTarget = true, diffusivityAt = diffusivity)
        val levels = OceanCirculation.levels(finest, dx, dy, everyCellWater = false) { a, d, w ->
            val coarseStream = FloatArray(a * d) { cell ->
                stream[((cell / a) * down / d) * across + (cell % a) * across / a]
            }
            OceanHeat.stencil(a, d, dx * across / a, dy * down / d, w, coarseStream, FloatArray(a * d), tau, withTarget = false, diffusivityAt = diffusivity)
        }
        val solution = OceanCirculation.solveByKrylov(
            levels, target.copyOf(), tolerance, poleIsWall = false, OceanCirculation.waterBodies(isWater, across, down), mostIterations
        ) { stencil, values, passes -> OceanCirculation.relax(stencil, values, passes); values }
        val trueResidual = OceanCirculation.largest(OceanCirculation.residual(finest, solution.values)) /
            OceanCirculation.largest(OceanCirculation.balanceOf(finest))
        println("OCEAN heat solve: ${solution.cycles} iterations, recurrence residual ${solution.relativeResidual}, single-precision residual $trueResidual")
        return solution
    }

    /**
     * The same current carries a step of temperature the same distance downstream whichever way it
     * flows: east as west, north as south (mirror images, to rounding), and east as north on the
     * ground, at aspect 1.0 and 0.5, against the one-dimensional balance's own decay length
     * `1/|m|`, `m = (u - sqrt(u² + 4K/τ)) / 2K`.
     *
     * The old carry failed the first clause by construction: it truncated a fractional departure
     * point toward zero, so water moving west or north slower than a cell a pass read its own cell
     * and carried nothing, and water moving east or south read a whole cell upstream. The control
     * below is that step, reproduced, and it fails the mirror.
     */
    @Test
    fun `a current carries heat the same distance whichever way it flows`() {
        val speed = 0.1
        val diffusivity = 5_000.0
        val constant = { _: Double -> diffusivity }
        val expected = 2 * diffusivity / (sqrt(speed * speed + 4 * diffusivity / tau) - speed)
        for (aspect in listOf(1.0, 0.5)) {
            val dx = 25_000.0
            val dy = dx * aspect
            val across = 256
            // The same ground at either aspect: twice the rows where they are half as tall.
            val down = (128 / aspect).toInt()
            val east = zonalChannel(across, down, dx, dy, speed, constant)
            val west = zonalChannel(across, down, dx, dy, -speed, constant)
            val middle = down / 2
            var worstMirror = 0.0
            for (column in 0 until across) {
                val mirrored = (across - 1 - column)
                worstMirror = max(worstMirror, abs((east[middle * across + column] - west[middle * across + mirrored]).toDouble()))
            }
            val north = meridionalChannel(across, down, dx, dy, speed, constant)
            val south = meridionalChannel(across, down, dx, dy, -speed, constant)
            var worstNorthSouth = 0.0
            val column = across / 2
            for (row in 0 until down) {
                worstNorthSouth = max(worstNorthSouth, abs((north[row * across + column] - south[(down - 1 - row) * across + column]).toDouble()))
            }
            val eastLength = decayLength(DoubleArray(across / 2) { east[middle * across + across / 2 + it].toDouble() }, dx)
            val northLength = decayLength(DoubleArray(down / 2) { north[(down / 2 - 1 - it) * across + column].toDouble() }, dy)
            println("OCEAN heat carry at aspect $aspect: mirror east-west $worstMirror C, north-south $worstNorthSouth C; " +
                "decay east ${eastLength / 1000} km, north ${northLength / 1000} km, against ${expected / 1000} km")
            assertTrue(worstMirror < MIRROR_TOLERANCE_C, "at aspect $aspect east and west differ by $worstMirror C")
            assertTrue(worstNorthSouth < MIRROR_TOLERANCE_C, "at aspect $aspect north and south differ by $worstNorthSouth C")
            assertTrue(abs(eastLength - expected) < expected * DECAY_TOLERANCE, "at aspect $aspect the heat decays over ${eastLength / 1000} km east against ${expected / 1000} km")
            assertTrue(abs(northLength - expected) < expected * DECAY_TOLERANCE, "at aspect $aspect the heat decays over ${northLength / 1000} km north against ${expected / 1000} km")
        }
        // The control: the old truncating step, a tenth of a cell a pass east and west.
        val cells = 200
        val eastward = truncatingCarry(cells, 0.1f)
        val westward = truncatingCarry(cells, -0.1f)
        var worst = 0f
        for (cell in 0 until cells) worst = maxOf(worst, abs(eastward[cell] - westward[cells - 1 - cell]))
        println("OCEAN the old truncating carry: east and west differ by $worst C")
        assertTrue(worst > MIRROR_TOLERANCE_C * 100, "the truncating control should carry east and west differently, and did not")
    }

    /** The distance over which a profile falling away from its first value drops by a factor of e. */
    private fun decayLength(profile: DoubleArray, spacing: Double): Double {
        val threshold = profile[0] / kotlin.math.E
        for (index in 1 until profile.size) {
            if (profile[index] <= threshold) {
                val share = (profile[index - 1] - threshold) / (profile[index - 1] - profile[index])
                return (index - 1 + share) * spacing
            }
        }
        return Double.NaN
    }

    /**
     * ψ across a channel of [cells], one cell of it [perCell]: rising steadily through the middle
     * two thirds, where the current is uniform, and falling back to zero at either shore over the
     * outer sixths, where it returns at twice the speed. A ψ that ran straight into the coast's zero
     * would make a jet along each shore fast enough to swamp everything else in the channel.
     */
    private fun channelStream(index: Int, cells: Int, perCell: Double): Float {
        val half = cells / 2.0 - SHORE_CELLS
        val offset = index + 0.5 - cells / 2.0
        val steady = half * 2.0 / 3.0
        val value = if (abs(offset) <= steady) offset else kotlin.math.sign(offset) * steady * (half - abs(offset)) / (half - steady)
        return (perCell * value).toFloat()
    }

    /**
     * A channel round the world flowing east at [speed] (west where it is negative), warm upstream,
     * between two strips of land along the poles. See [channelStream].
     */
    private fun zonalChannel(across: Int, down: Int, dx: Double, dy: Double, speed: Double, diffusivity: (Double) -> Double): FloatArray {
        val isWater = BooleanArray(across * down) { (it / across) in SHORE_CELLS until down - SHORE_CELLS }
        // u = -∂ψ/∂y with y north and rows running south: ψ rising one row south by u Δy.
        val stream = FloatArray(across * down) { cell ->
            if (isWater[cell]) channelStream(cell / across, down, speed * dy) else 0f
        }
        // Warm upstream: the western half for an eastward current, the eastern for a westward one.
        val warmHalf = if (speed > 0) 0 until across / 2 else across / 2 until across
        val target = FloatArray(across * down) { cell -> if (isWater[cell] && (cell % across) in warmHalf) STEP_C else 0f }
        return solve(across, down, dx, dy, isWater, stream, target, diffusivity).values
    }

    /** The same pole to pole, flowing north at [speed], warm upstream, between two meridional shores. */
    private fun meridionalChannel(across: Int, down: Int, dx: Double, dy: Double, speed: Double, diffusivity: (Double) -> Double): FloatArray {
        val isWater = BooleanArray(across * down) { (it % across) in SHORE_CELLS until across - SHORE_CELLS }
        // v = ∂ψ/∂x.
        val stream = FloatArray(across * down) { cell ->
            if (isWater[cell]) channelStream(cell % across, across, speed * dx) else 0f
        }
        val warmHalf = if (speed > 0) down / 2 until down else 0 until down / 2
        val target = FloatArray(across * down) { cell -> if (isWater[cell] && (cell / across) in warmHalf) STEP_C else 0f }
        return solve(across, down, dx, dy, isWater, stream, target, diffusivity).values
    }

    /** The carry this chunk replaced, on a ring of cells: truncated departure, half a blend, 2% relaxation, 200 passes. */
    private fun truncatingCarry(cells: Int, cellsPerPass: Float): FloatArray {
        val target = FloatArray(cells) { if (it < cells / 2) STEP_C else 0f }
        var current = target.copyOf()
        repeat(200) {
            val next = FloatArray(cells)
            for (cell in 0 until cells) {
                var upstream = (cell - cellsPerPass).toInt() % cells
                if (upstream < 0) upstream += cells
                val carried = current[cell] + (current[upstream] - current[cell]) * 0.5f
                next[cell] = carried + (target[cell] - carried) * 0.02f
            }
            current = next
        }
        return current
    }

    /**
     * No heat crosses a strip of land one cell wide. Two basins on either side of it: changing the
     * far basin's temperature by ten degrees and stirring it with a current against the strip moves
     * the near basin by no more than the solve's own tolerance.
     */
    @Test
    fun `no heat crosses a strip of land one cell wide`() {
        for (aspect in listOf(1.0, 0.5)) {
            val across = 128
            val down = 64
            val dx = 50_000.0
            val dy = dx * aspect
            val strip = across / 2
            val isWater = BooleanArray(across * down) { cell -> val column = cell % across; column != strip && column != 0 }
            val stirred = FloatArray(across * down) { cell ->
                val column = cell % across
                if (isWater[cell] && column > strip) (2_000.0 * (column - strip) * dx * 1e-3).toFloat() else 0f
            }
            fun farTarget(c: Float) = FloatArray(across * down) { cell ->
                if (!isWater[cell]) 0f else if (cell % across < strip) 5f else c
            }
            val cold = solve(across, down, dx, dy, isWater, FloatArray(across * down), farTarget(0f)).values
            val warm = solve(across, down, dx, dy, isWater, stirred, farTarget(10f)).values
            var worst = 0.0
            for (cell in cold.indices) {
                if (!isWater[cell] || cell % across >= strip) continue
                worst = max(worst, abs((cold[cell] - warm[cell]).toDouble()))
            }
            println("OCEAN barrier at aspect $aspect: the near basin moved $worst C")
            assertTrue(worst < BARRIER_TOLERANCE_C, "at aspect $aspect $worst C crossed a strip of land one cell wide")
        }
    }

    /**
     * The solve grid keeps a strip of land one map cell wide as land, at every map size: the ocean's
     * water is only what every map cell under a solve cell calls water.
     *
     * At 2048 a map cell, 5.9 km, is narrower than a solve cell, 6.25 km, so a mask read at each
     * solve cell's center would step over one strip in fifteen; sixteen neighboring positions of
     * the strip are tried there for that reason.
     */
    @Test
    fun `a strip of land one map cell wide is land on the solve grid at every size`() {
        for (size in listOf(128, 512, 2048)) {
            val config = WorldGenConfig(seed = 1L, width = size, height = size)
            val (across, down) = OceanStage.solveGrid(config.scale)
            val positions = if (size == 2048) (size / 3 until size / 3 + 16) else listOf(size / 3)
            for (strip in positions) {
                val isLand = BooleanArray(size * size) { it % size == strip }
                val sea = SeaLevelResult(0.5f, isLand, FloatField(size, size), size)
                val water = OceanStage.waterOn(config, sea, across, down)
                val firstColumn = strip * across / size
                val lastColumn = ((strip + 1) * across + size - 1) / size - 1
                var leaks = 0
                for (row in 0 until down) {
                    if ((firstColumn..lastColumn).all { water[row * across + it] }) leaks++
                }
                assertEquals(0, leaks, "at $size a strip at column $strip opens on $leaks rows of the solve grid")
            }
        }
    }

    /**
     * The solve converges on a deliberately difficult fixture: a narrow inlet one cell wide, a pond
     * cut off from the sea behind a strip of land a coarse cell straddles, and a current; and the
     * same solve stopped at a tenth of its iterations has not.
     */
    @Test
    fun `the heat solve converges on a difficult fixture, and a tenth of it does not`() {
        val across = 256
        val down = 128
        val dx = 25_000.0
        val isWater = BooleanArray(across * down) { cell ->
            val row = cell / across
            val column = cell % across
            val sea = column in 20 until 200 && row in 10 until 118
            val inlet = row == 60 && column in 200 until 240
            // Behind a strip of land one cell wide, which a coarse cell straddles.
            val pond = row in 30..33 && column in 201..204
            sea || inlet || pond
        }
        val stream = FloatArray(across * down) { cell ->
            val row = cell / across
            val column = cell % across
            if (!isWater[cell] || column >= 200) 0f
            else (20_000.0 * kotlin.math.sin(kotlin.math.PI * (column - 20) / 180.0) * kotlin.math.sin(kotlin.math.PI * (row - 10) / 108.0)).toFloat()
        }
        val target = FloatArray(across * down) { cell -> if (isWater[cell]) 30f - 0.4f * (cell / across) else 0f }
        val solved = solve(across, down, dx, dx, isWater, stream, target, tolerance = OceanCirculation.RESIDUAL_TOLERANCE)
        println("OCEAN difficult heat fixture: ${solved.cycles} iterations to ${solved.relativeResidual}")
        assertTrue(solved.relativeResidual < OceanCirculation.RESIDUAL_TOLERANCE, "the heat solve stopped at ${solved.relativeResidual}")

        val stopped = solve(across, down, dx, dx, isWater, stream, target, tolerance = OceanCirculation.RESIDUAL_TOLERANCE,
            mostIterations = (solved.cycles / 10).coerceAtLeast(1))
        println("OCEAN the same solve stopped at a tenth of its iterations: ${stopped.cycles} to ${stopped.relativeResidual}")
        assertTrue(stopped.relativeResidual >= OceanCirculation.RESIDUAL_TOLERANCE,
            "a tenth of the iterations already converged, so the fixture does not test the iteration count")
    }

    /** Zhurbas and Oh's figures, read back: 2,500 m²/s at 45 degrees, the cap by 10.2, the pole's least. */
    @Test
    fun `the eddy diffusivity is the drifters' at the latitudes they were measured`() {
        assertEquals(OceanHeat.MIDLATITUDE_DIFFUSIVITY_M2_PER_S, OceanHeat.diffusivity(45.0), 1e-6)
        assertEquals(OceanHeat.MIDLATITUDE_DIFFUSIVITY_M2_PER_S, OceanHeat.diffusivity(-45.0), 1e-6)
        assertEquals(OceanHeat.EQUATORIAL_DIFFUSIVITY_M2_PER_S, OceanHeat.diffusivity(0.0), 1e-6)
        assertEquals(OceanHeat.EQUATORIAL_DIFFUSIVITY_M2_PER_S, OceanHeat.diffusivity(10.0), 1e-6)
        assertTrue(OceanHeat.diffusivity(10.5) < OceanHeat.EQUATORIAL_DIFFUSIVITY_M2_PER_S)
        assertTrue(OceanHeat.diffusivity(89.0) < OceanHeat.MIDLATITUDE_DIFFUSIVITY_M2_PER_S)
        val stencil: OceanStencil = OceanHeat.stencil(
            8, 8, 10_000.0, 5_000.0, BooleanArray(64) { true }, FloatArray(64) { (it * 1000).toFloat() },
            FloatArray(64) { 10f }, tau, withTarget = true
        )
        for (cell in 0 until 64) {
            val sum = stencil.eastWeight[cell] + stencil.westWeight[cell] + stencil.northWeight[cell] + stencil.southWeight[cell]
            assertTrue(stencil.eastWeight[cell] >= 0f && stencil.westWeight[cell] >= 0f && stencil.northWeight[cell] >= 0f && stencil.southWeight[cell] >= 0f)
            assertTrue(sum < 1f, "the heat's weights at cell $cell sum to $sum, leaving no margin")
        }
    }

    private companion object {
        const val STEP_C = 10f

        /** The land along each shore of a channel, in cells. */
        const val SHORE_CELLS = 2

        /** Mirror images agree to the solve's tolerance, in degrees Celsius: a thousandth. */
        const val MIRROR_TOLERANCE_C = 1e-3

        /**
         * The decay length's bar, a twentieth: the fitted flux is exact for the one-dimensional
         * balance at the nodes, and what departs is the profile's reading between them.
         */
        const val DECAY_TOLERANCE = 0.05

        /** What may cross a strip of land, in degrees Celsius: the solve's own tolerance, a thousandth. */
        const val BARRIER_TOLERANCE_C = 1e-3
    }
}
