package com.worldforge.worldgen.noise

import kotlin.math.floor
import kotlin.random.Random

/**
 * Seeded periodic Perlin noise. Periodicity lets the generated world wrap seamlessly east-to-west.
 */
class PerlinNoise(seed: Long) {

    private val perm = IntArray(512)

    init {
        val p = IntArray(256) { it }
        val rnd = Random(seed)
        for (i in 255 downTo 1) {
            val j = rnd.nextInt(i + 1)
            val t = p[i]
            p[i] = p[j]
            p[j] = t
        }
        for (i in 0 until 512) perm[i] = p[i and 255]
    }

    fun noise(x: Float, y: Float, periodX: Int, periodY: Int): Float {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val xf = x - xi
        val yf = y - yi

        val x0 = mod(xi, periodX)
        val x1 = mod(xi + 1, periodX)
        val y0 = mod(yi, periodY)
        val y1 = mod(yi + 1, periodY)

        val u = fade(xf)
        val v = fade(yf)

        val aa = hash(x0, y0)
        val ba = hash(x1, y0)
        val ab = hash(x0, y1)
        val bb = hash(x1, y1)

        val top = lerp(grad(aa, xf, yf), grad(ba, xf - 1f, yf), u)
        val bottom = lerp(grad(ab, xf, yf - 1f), grad(bb, xf - 1f, yf - 1f), u)
        return lerp(top, bottom, v)
    }

    /**
     * Fractional Brownian motion. The lattice period doubles with each octave so every octave
     * stays seamless across the wrap.
     */
    fun fbm(
        x: Float,
        y: Float,
        octaves: Int,
        periodX: Int,
        periodY: Int,
        lacunarity: Float = 2f,
        gain: Float = 0.5f
    ): Float {
        var sum = 0f
        var amplitude = 1f
        var totalAmplitude = 0f
        var frequency = 1f
        var px = periodX
        var py = periodY

        repeat(octaves) {
            sum += amplitude * noise(x * frequency, y * frequency, px, py)
            totalAmplitude += amplitude
            amplitude *= gain
            frequency *= lacunarity
            px = (px * lacunarity).toInt().coerceAtLeast(1)
            py = (py * lacunarity).toInt().coerceAtLeast(1)
        }
        return if (totalAmplitude > 0f) sum / totalAmplitude else 0f
    }

    private fun hash(x: Int, y: Int): Int = perm[(perm[x and 255] + (y and 255)) and 511]

    private companion object {
        fun mod(a: Int, b: Int): Int {
            val m = a % b
            return if (m < 0) m + b else m
        }

        fun fade(t: Float): Float = t * t * t * (t * (t * 6f - 15f) + 10f)

        fun lerp(a: Float, b: Float, t: Float): Float = a + t * (b - a)

        fun grad(hash: Int, x: Float, y: Float): Float = when (hash and 7) {
            0 -> x
            1 -> x + y
            2 -> y
            3 -> -x + y
            4 -> -x
            5 -> -x - y
            6 -> -y
            else -> x - y
        }
    }
}
