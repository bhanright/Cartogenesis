package com.worldforge.worldgen

import com.worldforge.worldgen.math.Fft2D
import com.worldforge.worldgen.model.FloatField
import com.worldforge.worldgen.pipeline.NormalField
import com.worldforge.worldgen.pipeline.TerrainStage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class HeightIntegrationTest {

    @Test
    fun `fft round trip returns the original signal`() {
        val size = 32
        val fft = Fft2D(size, size)
        val re = DoubleArray(size * size) { (it % 7) - 3.0 }
        val im = DoubleArray(size * size)
        val originalRe = re.copyOf()

        fft.forward(re, im)
        fft.inverse(re, im)

        for (i in re.indices) {
            assertEquals(originalRe[i], re[i], 1e-9, "real part at $i")
            assertEquals(0.0, im[i], 1e-9, "imaginary part at $i")
        }
    }

    /**
     * The key correctness check for the normal-map to height-map step: given the exact gradient of
     * a known surface, integration must return that surface (up to the constant of integration,
     * which the DC term discards).
     */
    @Test
    fun `integrating a known gradient field recovers the original surface`() {
        val size = 64
        // z = sin(2*pi*x/size) * cos(2*pi*y/size)
        val kx = 2.0 * PI / size
        val ky = 2.0 * PI / size

        val expected = FloatField.of(size, size) { x, y ->
            (sin(kx * x) * cos(ky * y)).toFloat()
        }
        val gx = FloatField.of(size, size) { x, y ->
            (kx * cos(kx * x) * cos(ky * y)).toFloat()
        }
        val gy = FloatField.of(size, size) { x, y ->
            (-ky * sin(kx * x) * sin(ky * y)).toFloat()
        }

        val recovered = TerrainStage.integrate(NormalField(gx, gy))

        val expectedMean = expected.data.average()
        val recoveredMean = recovered.data.average()
        var maxError = 0.0
        for (i in expected.data.indices) {
            val e = expected.data[i] - expectedMean
            val r = recovered.data[i] - recoveredMean
            maxError = maxOf(maxError, abs(e - r))
        }
        assertTrue(maxError < 1e-4, "max reconstruction error was $maxError")
    }

    @Test
    fun `integration output is finite and varies`() {
        val normals = NormalField(
            FloatField.of(32, 32) { x, _ -> if (x < 16) 0.2f else -0.2f },
            FloatField(32, 32)
        )
        val height = TerrainStage.integrate(normals)
        assertTrue(height.data.all { it.isFinite() })
        assertTrue(height.max() - height.min() > 1e-3f, "integrated field should not be flat")
    }
}
