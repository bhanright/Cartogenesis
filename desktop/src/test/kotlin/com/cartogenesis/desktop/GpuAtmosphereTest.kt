package com.cartogenesis.desktop

import com.cartogenesis.worldgen.model.WorldScale
import com.cartogenesis.worldgen.pipeline.AtmosphereRemap
import com.cartogenesis.worldgen.pipeline.DoubleFourierCoefficients
import com.cartogenesis.worldgen.pipeline.SphericalGrid
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The graphics card's carrying of fields to and from the atmosphere's grid, held to the processor's.
 *
 * Both directions on Earth's planet at the application's grid, 2,048 by 1,024, and at a small one,
 * each against a bound derived from single precision's rounding over the sums each output is, and
 * each bound shown rejecting a wrong kernel: a ground field read one column off, and a series read
 * with its waves turned the wrong way. The times printed are the measurement the graphics path's
 * case rests on (docs/DESIGN_LEDGER.md, A1-2).
 *
 * On a machine with no usable device every case here is skipped; a machine that has a device and
 * cannot compile the shaders fails.
 */
class GpuAtmosphereTest {

    private val scale = WorldScale()
    private val coarse = SphericalGrid.forAtmosphere(scale)

    /** A ground field with a continent's step and smooth structure on it, row-major. */
    private fun groundField(columns: Int, rows: Int) = FloatArray(columns * rows) { cell ->
        val latitude = PI / 2 - (cell / columns + 0.5) * PI / rows
        val longitude = (cell % columns + 0.5) * 2 * PI / columns
        val land = if (cos(latitude) * cos(longitude - 2.0) > 0.7 + 0.1 * sin(5 * longitude)) 20.0 else 0.0
        (land + 10 * sin(latitude) * sin(3 * longitude) + 5).toFloat()
    }

    @Test
    fun `carrying down agrees with the processor's within single precision's rounding`(): Unit = runBlocking {
        val gpu = deviceOrSkip()
        for ((columns, rows) in listOf(2048 to 1024, 512 to 256)) {
            val remap = AtmosphereRemap(columns, rows, coarse)
            val field = groundField(columns, rows)
            var onCpu: DoubleArray? = null
            var onGpu: FloatArray? = null
            val cpuMillis = timed { onCpu = remap.areaMean(field) }
            val gpuMillis = timed { onGpu = assertNotNull(gpu.areaMean(remap, field), "the device declined to carry down") }
            val largest = field.maxOf { abs(it) }.toDouble()
            val worst = worstDifference(onCpu!!, onGpu!!)
            val bound = areaMeanBound(remap, largest)
            println("ATMOSPHERE down $columns by $rows: worst %.3e against a bound of %.3e; processor %.1f ms, device %.1f ms".format(worst, bound, cpuMillis, gpuMillis))
            assertTrue(worst <= bound, "carrying down differs by $worst, past the $bound rounding allows")
            // The control: the ground read one column off.
            val shifted = FloatArray(field.size) { cell -> field[(cell / columns) * columns + (cell % columns + 1) % columns] }
            val wrong = assertNotNull(gpu.areaMean(remap, shifted))
            val wrongWorst = worstDifference(onCpu!!, wrong)
            println("ATMOSPHERE down control, one column off: %.3e".format(wrongWorst))
            assertTrue(wrongWorst > bound, "a ground read one column off passed the bound")
        }
    }

    @Test
    fun `carrying up agrees with the processor's within single precision's rounding`(): Unit = runBlocking {
        val gpu = deviceOrSkip()
        for ((columns, rows) in listOf(2048 to 1024, 512 to 256)) {
            val remap = AtmosphereRemap(columns, rows, coarse)
            val coarseField = remap.forcing(groundField(columns, rows))
            val coefficients = remap.coefficients(coarseField)
            var onCpu: DoubleArray? = null
            var onGpu: FloatArray? = null
            val cpuMillis = timed { onCpu = remap.evaluate(remap.coefficients(coarseField), rows, columns) }
            val gpuMillis = timed { onGpu = assertNotNull(gpu.toGround(remap, coefficients), "the device declined to carry up") }
            val worst = worstDifference(onCpu!!, onGpu!!)
            val bound = seriesBound(coefficients)
            println("ATMOSPHERE up $columns by $rows: worst %.3e against a bound of %.3e; processor %.1f ms, device %.1f ms".format(worst, bound, cpuMillis, gpuMillis))
            assertTrue(worst <= bound, "carrying up differs by $worst, past the $bound rounding allows")
            // The control: every wave turned the wrong way, the series read in a mirror.
            val mirrored = DoubleFourierCoefficients(coefficients.coarseRows, coefficients.coarseColumns,
                coefficients.real, DoubleArray(coefficients.imaginary.size) { -coefficients.imaginary[it] })
            val wrongWorst = worstDifference(onCpu!!, assertNotNull(gpu.toGround(remap, mirrored)))
            println("ATMOSPHERE up control, waves turned the wrong way: %.3e".format(wrongWorst))
            assertTrue(wrongWorst > bound, "a series read in a mirror passed the bound")
        }
    }

    /**
     * What rounding alone can leave in one coarse cell's mean: every product and every sum of the
     * cell's double loop rounded once at binary32's unit roundoff, each weight rounded once, and
     * the weights summing to one, so `(2 n + 2) u max|ground|` with `n` the cell's ground cells.
     */
    private fun areaMeanBound(remap: AtmosphereRemap, largest: Double): Double {
        val most = remap.rowOverlaps.count.max() * remap.columnOverlaps.count.max()
        return (2.0 * most + 2.0) * UNIT_ROUNDOFF * largest
    }

    /**
     * What rounding alone can leave in one ground cell's value: each coefficient and table entry
     * rounded once, and every term of the two sums carried through at most as many roundings as there
     * are terms, of the size of the terms' magnitudes summed with their weights.
     */
    private fun seriesBound(coefficients: DoubleFourierCoefficients): Double {
        var magnitudes = 0.0
        for (meridional in 0 until coefficients.meridionalCount) {
            for (wave in 0 until coefficients.zonalCount) {
                val at = meridional * coefficients.zonalCount + wave
                val weight = DoubleFourierCoefficients.zonalWeight(wave, coefficients.coarseColumns)
                magnitudes += weight * (abs(coefficients.real[at]) + abs(coefficients.imaginary[at]))
            }
        }
        val roundings = coefficients.meridionalCount + coefficients.zonalCount + ROUNDINGS_PER_TERM
        return roundings * UNIT_ROUNDOFF * magnitudes
    }

    private fun worstDifference(expected: DoubleArray, actual: FloatArray): Double {
        var worst = 0.0
        for (index in expected.indices) {
            assertTrue(actual[index].isFinite(), "value $index is not a number")
            worst = maxOf(worst, abs(expected[index] - actual[index]))
        }
        return worst
    }

    private inline fun timed(body: () -> Unit): Double {
        val started = System.nanoTime()
        body()
        return (System.nanoTime() - started) / 1e6
    }

    private fun deviceOrSkip(): GpuAtmosphere {
        val probe = probed
        probe.accelerator?.let {
            println("ATMOSPHERE GPU device: ${it.name}")
            return it
        }
        if (GlContext.ensure().device != null) {
            throw AssertionError("this machine has an OpenGL context but no atmosphere accelerator: ${probe.unavailableBecause}")
        }
        skipWithoutDevice(probe.unavailableBecause)
    }

    private companion object {
        /** Binary32's unit roundoff, `2^-24`. */
        const val UNIT_ROUNDOFF = 1.0 / (1 shl 24)

        /** A term's own roundings beyond the sums': its coefficient, its table entry, its product. */
        const val ROUNDINGS_PER_TERM = 4

        val probed: GpuAtmosphere.Result by lazy { GpuAtmosphere.createOrNull() }
    }
}
