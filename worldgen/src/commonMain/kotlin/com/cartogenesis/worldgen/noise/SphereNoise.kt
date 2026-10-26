package com.cartogenesis.worldgen.noise

import kotlin.math.floor
import kotlin.math.pow
import kotlin.random.Random

/**
 * Seeded gradient noise in three dimensions, read on the sphere.
 *
 * The map's noise ([PerlinNoise] on a [GroundLattice]) is a plane wrapped into a cylinder, which is
 * right for a field that lives on the map and wrong for one that lives on the planet: its lattice
 * squeezes toward the poles and has a seam it must close. A point on the sphere read as a point in
 * space has neither, so a field drawn this way has the same grain at every latitude and every
 * bearing, which is what the tectonic history's own grid needs (docs/CONVENTIONS.md, rule 13).
 *
 * Perlin's improved noise (2002): a gradient at each lattice corner, chosen from the twelve edge
 * directions of a cube by a hash of the corner, blended by the quintic fade.
 */
class SphereNoise private constructor(private val permutation: IntArray) {

    constructor(seed: Long) : this(shuffledTable(seed))

    /** This noise's permutation table, [PERMUTATION_ENTRIES] entries, for a device to read the same noise. */
    fun permutationTable(): IntArray = permutation.copyOf()

    /** One octave at ([x], [y], [z]) in lattice units, about -1..1. */
    fun noise(x: Double, y: Double, z: Double): Double {
        val cornerX = floor(x).toInt()
        val cornerY = floor(y).toInt()
        val cornerZ = floor(z).toInt()
        val offsetX = x - cornerX
        val offsetY = y - cornerY
        val offsetZ = z - cornerZ
        val blendX = fade(offsetX)
        val blendY = fade(offsetY)
        val blendZ = fade(offsetZ)
        val hashX = cornerX and TABLE_MASK
        val hashY = cornerY and TABLE_MASK
        val hashZ = cornerZ and TABLE_MASK
        val a = permutation[hashX] + hashY
        val aa = permutation[a] + hashZ
        val ab = permutation[a + 1] + hashZ
        val b = permutation[hashX + 1] + hashY
        val ba = permutation[b] + hashZ
        val bb = permutation[b + 1] + hashZ
        val near = lerp(
            lerp(gradientDot(permutation[aa], offsetX, offsetY, offsetZ), gradientDot(permutation[ba], offsetX - 1, offsetY, offsetZ), blendX),
            lerp(gradientDot(permutation[ab], offsetX, offsetY - 1, offsetZ), gradientDot(permutation[bb], offsetX - 1, offsetY - 1, offsetZ), blendX),
            blendY
        )
        val far = lerp(
            lerp(gradientDot(permutation[aa + 1], offsetX, offsetY, offsetZ - 1), gradientDot(permutation[ba + 1], offsetX - 1, offsetY, offsetZ - 1), blendX),
            lerp(gradientDot(permutation[ab + 1], offsetX, offsetY - 1, offsetZ - 1), gradientDot(permutation[bb + 1], offsetX - 1, offsetY - 1, offsetZ - 1), blendX),
            blendY
        )
        return lerp(near, far, blendZ)
    }

    /**
     * Fractal sum over [octaves] at the unit vector ([x], [y], [z]) on a sphere of [radiusKm], with
     * the longest wavelength [wavelengthKm] on the ground; each octave half the wavelength of the
     * last and [persistence] its amplitude, normalized by the amplitudes' sum, so the result stays
     * about -1..1. A half makes a field whose level lines are smooth curves; [EARTH_OUTLINE_PERSISTENCE]
     * one whose level lines are as rough at every scale as Earth's continental edges.
     */
    fun fbmOnSphere(
        x: Double,
        y: Double,
        z: Double,
        radiusKm: Double,
        wavelengthKm: Double,
        octaves: Int,
        persistence: Double = 0.5
    ): Double {
        // A lattice cell spans one wavelength, so a unit vector is scaled by the radius in wavelengths.
        var frequency = radiusKm / wavelengthKm
        var amplitude = 1.0
        var sum = 0.0
        var amplitudes = 0.0
        for (octave in 0 until octaves) {
            sum += amplitude * noise(x * frequency + OCTAVE_OFFSET * octave, y * frequency, z * frequency)
            amplitudes += amplitude
            frequency *= 2.0
            amplitude *= persistence
        }
        return sum / amplitudes
    }

    companion object {
        /**
         * The divider dimension of Earth's continental edge at map scale: ETOPO5's 2,000 m isobath,
         * the foot of the continental slope where the continent's crust gives way to the ocean's,
         * block-averaged to 512 rows and walked with D3's divider over a 256th to a 32nd of the
         * map's width (`CrustMeasures.dividerDimension`), read 1.17; the coast, the 1,000 m and the
         * 3,000 m isobaths read 1.17, 1.16 and 1.18 the same way (docs/DESIGN_LEDGER.md, TH1-1).
         */
        const val EARTH_EDGE_DIVIDER_DIMENSION = 1.17

        /**
         * The octave persistence whose level lines have that dimension. A fractional Brownian field
         * of Hurst exponent H has level lines of dimension 2 - H, and an octave
         * sum whose amplitude falls by 2^-H for every halving of the wavelength has exponent H:
         * 2^-(2 - 1.17), about 0.56.
         */
        val EARTH_OUTLINE_PERSISTENCE: Double = 2.0.pow(-(2.0 - EARTH_EDGE_DIVIDER_DIMENSION))

        /** How many entries a permutation table has: Perlin's 256, stored twice end to end. */
        const val PERMUTATION_ENTRIES = 512

        /** The noise whose table is [table]'s [PERMUTATION_ENTRIES] entries from [offset]. */
        fun fromTable(table: IntArray, offset: Int): SphereNoise =
            SphereNoise(table.copyOfRange(offset, offset + PERMUTATION_ENTRIES))

        private fun shuffledTable(seed: Long): IntArray {
            val shuffled = IntArray(TABLE_SIZE) { it }
            val random = Random(seed)
            for (slot in TABLE_SIZE - 1 downTo 1) {
                val pick = random.nextInt(slot + 1)
                val swapped = shuffled[slot]
                shuffled[slot] = shuffled[pick]
                shuffled[pick] = swapped
            }
            return IntArray(DOUBLED_TABLE_SIZE) { shuffled[it and TABLE_MASK] }
        }

        private const val TABLE_SIZE = 256
        private const val TABLE_MASK = TABLE_SIZE - 1
        private const val DOUBLED_TABLE_SIZE = 2 * TABLE_SIZE

        /** Moves each octave off the others' lattice, so their corners never coincide. */
        const val OCTAVE_OFFSET = 17.31

        private fun fade(fraction: Double): Double = fraction * fraction * fraction * (fraction * (fraction * 6 - 15) + 10)

        private fun lerp(from: Double, to: Double, fraction: Double): Double = from + fraction * (to - from)

        /** The dot of the offset with one of the cube's twelve edge directions, Perlin's choice. */
        private fun gradientDot(hash: Int, x: Double, y: Double, z: Double): Double {
            val selector = hash and 15
            val first = if (selector < 8) x else y
            val second = if (selector < 4) y else if (selector == 12 || selector == 14) x else z
            return (if (selector and 1 == 0) first else -first) + (if (selector and 2 == 0) second else -second)
        }
    }
}
