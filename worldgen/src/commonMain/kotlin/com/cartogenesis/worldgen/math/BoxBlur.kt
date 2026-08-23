package com.cartogenesis.worldgen.math

import com.cartogenesis.worldgen.model.FloatField

/** Separable box blur with running sums: O(width * height) per pass regardless of radius. */
object BoxBlur {

    /** Repeated box passes approximate a Gaussian; three is the usual sweet spot. */
    fun apply(field: FloatField, radius: Int, passes: Int = 3) {
        if (radius <= 0) return
        val scratch = FloatArray(field.data.size)
        repeat(passes) {
            horizontal(field, radius, scratch)
            vertical(field, radius, scratch)
        }
    }

    private fun horizontal(field: FloatField, radius: Int, scratch: FloatArray) {
        val w = field.width
        val h = field.height
        val data = field.data
        val window = 2 * radius + 1
        val inv = 1f / window

        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            for (k in -radius..radius) {
                sum += data[row + wrap(k, w)]
            }
            for (x in 0 until w) {
                scratch[row + x] = sum * inv
                sum -= data[row + wrap(x - radius, w)]
                sum += data[row + wrap(x + radius + 1, w)]
            }
        }
        scratch.copyInto(data, 0, 0, data.size)
    }

    private fun vertical(field: FloatField, radius: Int, scratch: FloatArray) {
        val w = field.width
        val h = field.height
        val data = field.data
        val window = 2 * radius + 1
        val inv = 1f / window

        for (x in 0 until w) {
            var sum = 0f
            for (k in -radius..radius) {
                sum += data[clamp(k, h) * w + x]
            }
            for (y in 0 until h) {
                scratch[y * w + x] = sum * inv
                sum -= data[clamp(y - radius, h) * w + x]
                sum += data[clamp(y + radius + 1, h) * w + x]
            }
        }
        scratch.copyInto(data, 0, 0, data.size)
    }

    private fun wrap(v: Int, n: Int): Int {
        val m = v % n
        return if (m < 0) m + n else m
    }

    private fun clamp(v: Int, n: Int): Int = v.coerceIn(0, n - 1)
}
