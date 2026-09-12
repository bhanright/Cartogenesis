package com.cartogenesis.worldgen.model

/**
 * A 2D scalar field stored row-major. The X axis wraps (the world is a cylinder east-to-west);
 * the Y axis clamps at the poles.
 */
class FloatField(
    val width: Int,
    val height: Int,
    /** One entry per cell, row-major: cell `y * width + x`. Raw, because every stage sweeps it. */
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
        val wrapped = x % width
        return if (wrapped < 0) wrapped + width else wrapped
    }

    fun clampY(y: Int): Int = y.coerceIn(0, height - 1)

    /** Sample with X wrapping and Y clamping, so callers can index freely around edges. */
    fun sample(x: Int, y: Int): Float = this[wrapX(x), clampY(y)]

    fun copy(): FloatField = FloatField(width, height, data.copyOf())

    fun min(): Float = data.min()

    fun max(): Float = data.max()

    /** Rescales in place so values span exactly [0, 1]. A constant field becomes all zeroes. */
    fun normalize(): FloatField {
        val lowest = min()
        val highest = max()
        val range = highest - lowest
        if (range <= 0f) {
            data.fill(0f)
            return this
        }
        for (cell in data.indices) data[cell] = (data[cell] - lowest) / range
        return this
    }

    inline fun forEachIndexed(action: (x: Int, y: Int, value: Float) -> Unit) {
        var cell = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                action(x, y, data[cell])
                cell++
            }
        }
    }

    companion object {
        fun of(width: Int, height: Int, init: (x: Int, y: Int) -> Float): FloatField {
            val field = FloatField(width, height)
            var cell = 0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    field.data[cell] = init(x, y)
                    cell++
                }
            }
            return field
        }
    }
}
