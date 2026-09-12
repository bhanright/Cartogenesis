package com.cartogenesis.worldgen.noise

import kotlin.math.floor
import kotlin.random.Random

/**
 * Seeded periodic Perlin noise. Periodicity lets the generated world wrap seamlessly east-to-west.
 */
class PerlinNoise(seed: Long) {

    /**
     * Perlin's permutation table, stored twice end to end.
     *
     * [hashAt] adds two table entries before indexing, which can reach twice the table's length;
     * holding the second copy costs a kilobyte and saves a wrap on every lookup.
     */
    private val permutation = IntArray(DOUBLED_TABLE_SIZE)

    init {
        val shuffled = IntArray(TABLE_SIZE) { it }
        val random = Random(seed)
        // Fisher-Yates, so every seed gets one of the table's permutations with equal probability.
        for (slot in TABLE_SIZE - 1 downTo 1) {
            val pick = random.nextInt(slot + 1)
            val swapped = shuffled[slot]
            shuffled[slot] = shuffled[pick]
            shuffled[pick] = swapped
        }
        for (slot in 0 until DOUBLED_TABLE_SIZE) permutation[slot] = shuffled[slot and TABLE_MASK]
    }

    /**
     * One octave of noise at ([x], [y]) on the lattice, in -1..1.
     *
     * [x] and [y] are lattice coordinates, not cells: one unit is one lattice cell. [periodX] and
     * [periodY] are the lattice's tile size in those same units, so a caller that steps `period`
     * units across the map gets a field that joins up with itself.
     */
    fun noise(x: Float, y: Float, periodX: Int, periodY: Int): Float {
        val latticeX = floor(x).toInt()
        val latticeY = floor(y).toInt()
        val offsetX = x - latticeX
        val offsetY = y - latticeY

        val westColumn = wrap(latticeX, periodX)
        val eastColumn = wrap(latticeX + 1, periodX)
        val northRow = wrap(latticeY, periodY)
        val southRow = wrap(latticeY + 1, periodY)

        val blendX = fade(offsetX)
        val blendY = fade(offsetY)

        val northWestHash = hashAt(westColumn, northRow)
        val northEastHash = hashAt(eastColumn, northRow)
        val southWestHash = hashAt(westColumn, southRow)
        val southEastHash = hashAt(eastColumn, southRow)

        val northEdge = lerp(
            gradientDot(northWestHash, offsetX, offsetY),
            gradientDot(northEastHash, offsetX - 1f, offsetY),
            blendX
        )
        val southEdge = lerp(
            gradientDot(southWestHash, offsetX, offsetY - 1f),
            gradientDot(southEastHash, offsetX - 1f, offsetY - 1f),
            blendX
        )
        return lerp(northEdge, southEdge, blendY)
    }

    /**
     * Fractional Brownian motion at ([x], [y]): [octaves] octaves of [noise] summed and divided by
     * their total amplitude, so the result stays in -1..1 whatever the octave count.
     *
     * Each octave multiplies the frequency by [lacunarity] and the amplitude by [gain]. The lattice
     * period is multiplied by [lacunarity] too, so every octave stays seamless across the wrap.
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
        var octavePeriodX = periodX
        var octavePeriodY = periodY

        repeat(octaves) {
            sum += amplitude * noise(x * frequency, y * frequency, octavePeriodX, octavePeriodY)
            totalAmplitude += amplitude
            amplitude *= gain
            frequency *= lacunarity
            octavePeriodX = (octavePeriodX * lacunarity).toInt().coerceAtLeast(1)
            octavePeriodY = (octavePeriodY * lacunarity).toInt().coerceAtLeast(1)
        }
        return if (totalAmplitude > 0f) sum / totalAmplitude else 0f
    }

    /** The table entry a lattice corner draws its gradient direction from. */
    private fun hashAt(column: Int, row: Int): Int {
        val columnEntry = permutation[column and TABLE_MASK]
        return permutation[(columnEntry + (row and TABLE_MASK)) and DOUBLED_TABLE_MASK]
    }

    private companion object {
        /**
         * Entries in Perlin's permutation table before it is doubled.
         *
         * A power of two, so that wrapping a lattice index into it is one `and` with [TABLE_MASK]
         * rather than a division; 256 is the reference implementation's size and the seed's whole
         * effect on the field is which permutation of it the shuffle picks.
         */
        const val TABLE_SIZE = 256
        const val TABLE_MASK = TABLE_SIZE - 1
        const val DOUBLED_TABLE_SIZE = TABLE_SIZE * 2
        const val DOUBLED_TABLE_MASK = DOUBLED_TABLE_SIZE - 1

        /**
         * Distinct gradient directions a lattice corner can take, and the mask that picks one.
         *
         * Eight: the four axes and the four diagonals, which is the standard 2D set. The diagonals
         * are the unnormalised (±1, ±1) rather than unit vectors, exactly as Perlin's reference
         * does, so the field's extremes sit a little outside ±1 along them.
         */
        const val GRADIENT_DIRECTIONS = 8
        const val GRADIENT_MASK = GRADIENT_DIRECTIONS - 1

        /** Wraps a lattice index into 0 until [period], for negative indices as well. */
        fun wrap(index: Int, period: Int): Int {
            val remainder = index % period
            return if (remainder < 0) remainder + period else remainder
        }

        /**
         * Perlin's quintic ease curve, 6t^5 - 15t^4 + 10t^3.
         *
         * Its first and second derivatives are both zero at 0 and at 1, which is what stops the
         * lattice showing as a grid of creases the way a plain cubic smoothstep does.
         */
        fun fade(fraction: Float): Float =
            fraction * fraction * fraction * (fraction * (fraction * 6f - 15f) + 10f)

        fun lerp(from: Float, to: Float, fraction: Float): Float = from + fraction * (to - from)

        /**
         * Dot product of the gradient [hash] selects with the offset from that corner — the value
         * one lattice corner contributes before the two lerps blend the four together.
         */
        fun gradientDot(hash: Int, offsetX: Float, offsetY: Float): Float =
            when (hash and GRADIENT_MASK) {
                0 -> offsetX
                1 -> offsetX + offsetY
                2 -> offsetY
                3 -> -offsetX + offsetY
                4 -> -offsetX
                5 -> -offsetX - offsetY
                6 -> -offsetY
                else -> offsetX - offsetY
            }
    }
}
