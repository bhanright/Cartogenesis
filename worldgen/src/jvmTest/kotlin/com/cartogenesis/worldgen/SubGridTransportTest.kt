package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.HydraulicErosion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The sub-grid transport experiment (`ErosionConfig.subGridTransport`) does what its derivation
 * says, on fixtures where the answer is known: it conserves the land's material, handing the sea
 * only what crosses the shore, and it damps a ripple at the rate linear diffusion with
 * `K dx dy` does.
 */
class SubGridTransportTest {

    private val config = WorldGenConfig(seed = 3L, width = 64, height = 64)
    private val rates = HydraulicErosion.Rates(config)

    /** An island: what the land loses is what the sea gains, to the double's rounding. */
    @Test
    fun `the creep conserves the land and hands the sea only what crosses the shore`() {
        val side = 64
        val isLand = BooleanArray(side * side) { val r = it / side; val c = it % side; r in 8 until 56 && c in 8 until 56 }
        val shore = 0.5f
        val surface = FloatArray(side * side) { cell ->
            if (!isLand[cell]) 0.4f else shore + 0.05f * (1f + sin(cell * 0.37f)) * (1f + (cell % 7) / 7f)
        }
        val before = surface.indices.filter { isLand[it] }.sumOf { surface[it].toDouble() }
        val toSea = HydraulicErosion.subGridCreep(
            rates, side, side, isLand, shore, FloatArray(side * side) { 1f }, FloatArray(side * side) { 1f }, surface,
            config.scale.yearsPerHydraulicRound
        )
        val after = surface.indices.filter { isLand[it] }.sumOf { surface[it].toDouble() }
        println("SUBGRID island: land %.6f to %.6f, %.6f to the sea".format(before, after, toSea))
        assertTrue(toSea > 0.0, "nothing crept into the sea, so the shore saw nothing to test")
        assertTrue(abs(before - after - toSea) <= 1e-5 * before, "the land lost ${before - after} and the sea took $toSea")
        assertTrue(surface.indices.none { !isLand[it] && surface[it] != 0.4f }, "the creep moved a sea cell")
    }

    /**
     * A ripple two rows long, running down the columns across a closed field, decays as
     * `exp(-D k^2 t)` with `D = K dx dy` and the discrete wavenumber of the five-point Laplacian,
     * `k^2 = (2 - 2 cos(k dy)) / dy^2`, to a hundredth.
     */
    @Test
    fun `a ripple decays at the rate the derived diffusivity gives`() {
        val side = 64
        val isLand = BooleanArray(side * side) { true }
        val amplitude = 1e-3
        val surface = FloatArray(side * side) { cell ->
            val row = cell / side
            (0.5 + amplitude * sin(2 * PI * row / 8.0)).toFloat()
        }
        val years = config.scale.yearsPerHydraulicRound
        HydraulicErosion.subGridCreep(
            rates, side, side, isLand, 0f, FloatArray(side * side) { 1f }, FloatArray(side * side) { 1f }, surface, years
        )
        // Read the amplitude in the middle rows, away from the closed poles.
        var numerator = 0.0
        var denominator = 0.0
        for (row in 16 until 48) {
            val basis = sin(2 * PI * row / 8.0)
            numerator += (surface[row * side + 5] - 0.5) * basis
            denominator += basis * basis
        }
        val measured = numerator / denominator / amplitude
        val dy = rates.cellHeightMetres
        val k2 = (2 - 2 * kotlin.math.cos(2 * PI / 8.0)) / (dy * dy)
        val expected = exp(-rates.subGridDiffusivity * k2 * years)
        println("SUBGRID ripple of eight rows: kept %.4f of its amplitude, the law's %.4f; D %.1f m2/yr".format(measured, expected, rates.subGridDiffusivity))
        assertTrue(abs(measured - expected) <= 0.01, "the ripple kept $measured where diffusion at D puts it at $expected")
    }
}
