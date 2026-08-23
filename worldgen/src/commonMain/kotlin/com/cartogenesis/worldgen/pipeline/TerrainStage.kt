package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.math.Fft2D
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.noise.PerlinNoise
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * The random slope field the user thinks of as the "normal map": per-cell surface normals
 * derived from noise, stored as their x/y gradient components.
 */
class NormalField(val gx: FloatField, val gy: FloatField) {
    val width: Int get() = gx.width
    val height: Int get() = gx.height

    /** Unit surface normal at a cell, as (nx, ny, nz) — the classic tangent-space normal. */
    fun normalAt(x: Int, y: Int): FloatArray {
        val nx = -gx[x, y]
        val ny = -gy[x, y]
        val len = sqrt(nx * nx + ny * ny + 1f)
        return floatArrayOf(nx / len, ny / len, 1f / len)
    }
}

data class TerrainResult(
    val normals: NormalField,
    /** Integrated elevation, normalized to 0..1. */
    val height: FloatField
)

/**
 * Step 1 of the pipeline: build a random gradient field, then recover the height field it
 * describes.
 *
 * The two gradient components are independent noise, so the field is not conservative — it is not
 * the exact derivative of any surface. Frankot–Chellappa handles that by returning the surface
 * whose gradient is the closest least-squares match, which is why this produces smooth continuous
 * terrain rather than the streaking artifacts of naive row-by-row summation.
 */
object TerrainStage {

    fun generate(config: WorldGenConfig): TerrainResult {
        val normals = buildNormalField(config)
        val height = integrate(normals)
        smooth(height, config.terrain.smoothing)
        return TerrainResult(normals, height.normalize())
    }

    private fun buildNormalField(config: WorldGenConfig): NormalField {
        val t = config.terrain
        val w = config.width
        val h = config.height

        // Two decorrelated noise fields, one per slope axis.
        val noiseX = PerlinNoise(config.seed)
        val noiseY = PerlinNoise(config.seed * 31 + 17)

        val periodX = t.baseFrequency
        val periodY = t.baseFrequency
        val sx = periodX.toFloat() / w
        val sy = periodY.toFloat() / h

        val gx = FloatField.of(w, h) { x, y ->
            t.gradientStrength * noiseX.fbm(x * sx, y * sy, t.octaves, periodX, periodY, t.lacunarity, t.gain)
        }
        val gy = FloatField.of(w, h) { x, y ->
            t.gradientStrength * noiseY.fbm(x * sx, y * sy, t.octaves, periodX, periodY, t.lacunarity, t.gain)
        }
        return NormalField(gx, gy)
    }

    /**
     * Frankot–Chellappa integration. In the frequency domain the surface that best matches a
     * gradient field (p, q) is
     *
     *     Z = -i (wx * P + wy * Q) / (wx^2 + wy^2),   Z(0,0) = 0
     *
     * Z is accumulated in place over P rather than into a third pair of buffers. At export
     * resolutions these arrays dominate the app's memory: six of them at 4096x4096 is over 800MB,
     * which no device will grant.
     */
    fun integrate(normals: NormalField): FloatField {
        val w = normals.width
        val h = normals.height
        val fft = Fft2D(w, h)

        val pRe = DoubleArray(w * h) { normals.gx.data[it].toDouble() }
        val pIm = DoubleArray(w * h)
        val qRe = DoubleArray(w * h) { normals.gy.data[it].toDouble() }
        val qIm = DoubleArray(w * h)

        fft.forward(pRe, pIm)
        fft.forward(qRe, qIm)

        for (v in 0 until h) {
            val fv = if (v <= h / 2) v else v - h
            val wy = 2.0 * PI * fv / h
            for (u in 0 until w) {
                val fu = if (u <= w / 2) u else u - w
                val wx = 2.0 * PI * fu / w
                val denom = wx * wx + wy * wy
                val i = v * w + u
                if (denom == 0.0) {
                    // DC term: the arbitrary constant of integration.
                    pRe[i] = 0.0
                    pIm[i] = 0.0
                    continue
                }

                val aRe = wx * pRe[i] + wy * qRe[i]
                val aIm = wx * pIm[i] + wy * qIm[i]
                // Multiply by -i.
                pRe[i] = aIm / denom
                pIm[i] = -aRe / denom
            }
        }

        fft.inverse(pRe, pIm)
        return FloatField(w, h, FloatArray(w * h) { pRe[it].toFloat() })
    }

    /** Blends toward a 3x3 box-blurred copy. Cheap, and the integration output is already smooth. */
    private fun smooth(field: FloatField, amount: Float) {
        if (amount <= 0f) return
        val blurred = FloatField(field.width, field.height)
        for (y in 0 until field.height) {
            for (x in 0 until field.width) {
                var sum = 0f
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        sum += field.sample(x + dx, y + dy)
                    }
                }
                blurred[x, y] = sum / 9f
            }
        }
        val a = amount.coerceIn(0f, 1f)
        for (i in field.data.indices) {
            field.data[i] = field.data[i] * (1f - a) + blurred.data[i] * a
        }
    }
}
