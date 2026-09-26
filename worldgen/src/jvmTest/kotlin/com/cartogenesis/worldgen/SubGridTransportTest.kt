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
 * The sub-grid transport experiments (`ErosionConfig.subGridTransport` and its two forms net of the
 * resolved incision, `subGridTransportAcrossTheFall` and `subGridTransportUndrainedShare`) do what
 * their derivations say, on fixtures where the answer is known: each conserves the land's material,
 * handing the sea only what crosses the shore, and each damps a ripple at the rate its law gives.
 */
class SubGridTransportTest {

    private val config = WorldGenConfig(seed = 3L, width = 64, height = 64)
    private val rates = HydraulicErosion.Rates(config)

    /** An island: what the land loses is what the sea gains, to the double's rounding, in every form. */
    @Test
    fun `the creep conserves the land and hands the sea only what crosses the shore`() {
        val side = 64
        // Receivers in all eight bearings and none, so every face weighting the net form can take
        // is exercised; and an undrained share running from nought to one.
        val receiver = IntArray(side * side) { cell ->
            val row = cell / side
            val column = cell % side
            when (cell % 9) {
                8 -> -1
                else -> {
                    val bearing = cell % 9
                    val columnStep = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)[bearing]
                    val rowStep = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)[bearing]
                    ((row + rowStep).coerceIn(0, side - 1)) * side + (column + columnStep + side) % side
                }
            }
        }
        val undrained = FloatArray(side * side) { (it % 11) / 10f }
        for ((form, net) in listOf<Pair<String, (FloatArray, BooleanArray, Float) -> Double>>(
            "whole" to { surface, isLand, shore -> creep(surface, isLand, shore) },
            "across the fall" to { surface, isLand, shore -> creep(surface, isLand, shore, receiver = receiver) },
            "undrained share" to { surface, isLand, shore -> creep(surface, isLand, shore, undrainedShare = undrained) }
        )) {
            conservesOnAnIsland(side, form, net)
        }
    }

    private fun creep(
        surface: FloatArray,
        isLand: BooleanArray,
        shore: Float,
        receiver: IntArray? = null,
        undrainedShare: FloatArray? = null
    ): Double {
        val cellCount = surface.size
        val side = kotlin.math.sqrt(cellCount.toDouble()).toInt()
        return HydraulicErosion.subGridCreep(
            rates, side, side, isLand, shore, FloatArray(cellCount) { 1f }, FloatArray(cellCount) { 1f }, surface,
            config.scale.yearsPerHydraulicRound, receiver, undrainedShare
        )
    }

    private fun conservesOnAnIsland(side: Int, form: String, creepOnce: (FloatArray, BooleanArray, Float) -> Double) {
        val isLand = BooleanArray(side * side) { val r = it / side; val c = it % side; r in 8 until 56 && c in 8 until 56 }
        val shore = 0.5f
        val surface = FloatArray(side * side) { cell ->
            if (!isLand[cell]) 0.4f else shore + 0.05f * (1f + sin(cell * 0.37f)) * (1f + (cell % 7) / 7f)
        }
        val before = surface.indices.filter { isLand[it] }.sumOf { surface[it].toDouble() }
        val toSea = creepOnce(surface, isLand, shore)
        val after = surface.indices.filter { isLand[it] }.sumOf { surface[it].toDouble() }
        println("SUBGRID island, %s: land %.6f to %.6f, %.6f to the sea".format(form, before, after, toSea))
        assertTrue(toSea > 0.0, "$form: nothing crept into the sea, so the shore saw nothing to test")
        assertTrue(abs(before - after - toSea) <= 1e-5 * before, "$form: the land lost ${before - after} and the sea took $toSea")
        assertTrue(surface.indices.none { !isLand[it] && surface[it] != 0.4f }, "$form: the creep moved a sea cell")
    }

    /**
     * The across-the-fall form diffuses only square to each cell's receiver. With every cell
     * draining down its column, a ripple running down the columns is left exactly as it was, and
     * one running along the rows decays at the whole `K dx dy`'s rate, to a hundredth; with every
     * cell draining along its row, the other way about. The whole form damps both.
     */
    @Test
    fun `the across-the-fall form leaves the fall along the receiver to the incision`() {
        val side = 64
        val isLand = BooleanArray(side * side) { true }
        val downTheColumn = IntArray(side * side) { cell -> if (cell / side + 1 < side) cell + side else cell - side }
        val alongTheRow = IntArray(side * side) { cell -> (cell / side) * side + (cell % side + 1) % side }
        val years = config.scale.yearsPerHydraulicRound
        for ((drains, receiver) in listOf("down the column" to downTheColumn, "along the row" to alongTheRow)) {
            for (rippleDownTheColumns in listOf(true, false)) {
                val surface = ripple(side, rippleDownTheColumns)
                creep(surface, isLand, 0f, receiver = receiver)
                val kept = amplitudeKept(surface, side, rippleDownTheColumns)
                val acrossTheFall = rippleDownTheColumns != (drains == "down the column")
                val expected = if (acrossTheFall) rippleDecay(rippleDownTheColumns, years, 1.0) else 1.0
                println(
                    "SUBGRID across the fall, draining %s, ripple %s: kept %.4f of its amplitude, the law's %.4f"
                        .format(drains, if (rippleDownTheColumns) "down the columns" else "along the rows", kept, expected)
                )
                assertTrue(
                    abs(kept - expected) <= 0.01,
                    "draining $drains, the ripple kept $kept where the across-the-fall law puts it at $expected"
                )
            }
        }
    }

    /** The undrained-share form damps a ripple as diffusion at `K dx dy` times that share does. */
    @Test
    fun `the undrained-share form damps at the share of the diffusivity`() {
        val side = 64
        val isLand = BooleanArray(side * side) { true }
        val share = 0.25f
        val surface = ripple(side, rippleDownTheColumns = true)
        creep(surface, isLand, 0f, undrainedShare = FloatArray(side * side) { share })
        val kept = amplitudeKept(surface, side, rippleDownTheColumns = true)
        val expected = rippleDecay(true, config.scale.yearsPerHydraulicRound, share.toDouble())
        println("SUBGRID undrained share %.2f: kept %.4f of its amplitude, the law's %.4f".format(share, kept, expected))
        assertTrue(abs(kept - expected) <= 0.01, "the ripple kept $kept where diffusion at the share puts it at $expected")
    }

    /** A ripple eight cells long, down the columns (varying with the row) or along the rows. */
    private fun ripple(side: Int, rippleDownTheColumns: Boolean): FloatArray = FloatArray(side * side) { cell ->
        val position = if (rippleDownTheColumns) cell / side else cell % side
        (0.5 + RIPPLE_AMPLITUDE * sin(2 * PI * position / 8.0)).toFloat()
    }

    /** The share of the ripple's amplitude left, read in the middle rows away from the closed poles. */
    private fun amplitudeKept(surface: FloatArray, side: Int, rippleDownTheColumns: Boolean): Double {
        var numerator = 0.0
        var denominator = 0.0
        for (row in 16 until 48) {
            for (column in 0 until side) {
                val basis = sin(2 * PI * (if (rippleDownTheColumns) row else column) / 8.0)
                numerator += (surface[row * side + column] - 0.5) * basis
                denominator += basis * basis
            }
        }
        return numerator / denominator / RIPPLE_AMPLITUDE
    }

    /** `exp(-share D k^2 t)` with the five-point Laplacian's discrete wavenumber on that axis. */
    private fun rippleDecay(rippleDownTheColumns: Boolean, years: Double, share: Double): Double {
        val step = if (rippleDownTheColumns) rates.cellHeightMetres else rates.cellWidthMetres
        val k2 = (2 - 2 * kotlin.math.cos(2 * PI / 8.0)) / (step * step)
        return exp(-share * rates.subGridDiffusivity * k2 * years)
    }

    private companion object {
        const val RIPPLE_AMPLITUDE = 1e-3
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
