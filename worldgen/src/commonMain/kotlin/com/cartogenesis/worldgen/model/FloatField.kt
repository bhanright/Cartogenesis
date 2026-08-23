package com.cartogenesis.worldgen.model

/**
 * A 2D scalar field stored row-major. The X axis wraps (the world is a cylinder east-to-west);
 * the Y axis clamps at the poles.
 */
class FloatField(
    val width: Int,
    val height: Int,
    val data: FloatArray = FloatArray(width * height)
) {
    init {
        require(data.size == width * height) { "data size ${data.size} != $width x $height" }
    }

    operator fun get(x: Int, y: Int): Float = data[y * width + x]

    operator fun set(x: Int, y: Int, value: Float) {
        data[y * width + x] = value
    }

    fun wrapX(x: Int): Int {
        val m = x % width
        return if (m < 0) m + width else m
    }

    fun clampY(y: Int): Int = y.coerceIn(0, height - 1)

    /** Sample with X wrapping and Y clamping, so callers can index freely around edges. */
    fun sample(x: Int, y: Int): Float = this[wrapX(x), clampY(y)]

    fun copy(): FloatField = FloatField(width, height, data.copyOf())

    fun min(): Float = data.min()

    fun max(): Float = data.max()

    /** Rescales in place so values span exactly [0, 1]. A constant field becomes all zeroes. */
    fun normalize(): FloatField {
        val lo = min()
        val hi = max()
        val range = hi - lo
        if (range <= 0f) {
            data.fill(0f)
            return this
        }
        for (i in data.indices) data[i] = (data[i] - lo) / range
        return this
    }

    inline fun forEachIndexed(action: (x: Int, y: Int, value: Float) -> Unit) {
        var i = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                action(x, y, data[i])
                i++
            }
        }
    }

    companion object {
        fun of(width: Int, height: Int, init: (x: Int, y: Int) -> Float): FloatField {
            val field = FloatField(width, height)
            var i = 0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    field.data[i] = init(x, y)
                    i++
                }
            }
            return field
        }
    }
}
