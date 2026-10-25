package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Everything the belts' map pass reads, one entry per map cell, row-major, and the figures it is
 * drawn with. Built by `PlateStage` from the crust read onto the map.
 *
 * [strikeEastEast], [strikeEastNorth] and [strikeNorthNorth] are the orogenic excess's gradient
 * tensor in each cell's east and north ([CrustOnMap]); [ridgeAmplitudeMeters] is the standard
 * deviation the ridges along strike carry there and [provinceAmplitudeMeters] the cell-mean relief a
 * province of sub-cell ranges carries, meters, nothing where there is none; [softness] is how weak
 * the rock is, 0 a craton's crystalline basement to 1 the weakest a belt's rocks come, and
 * [weakZoneKm] how far the cell is from a terrane boundary, km, negative where there is none.
 */
class BeltFieldInputs internal constructor(
    val mapColumns: Int,
    val mapRows: Int,
    val radiusKm: Double,
    val seed: Int,
    val strikeEastEast: FloatArray,
    val strikeEastNorth: FloatArray,
    val strikeNorthNorth: FloatArray,
    val ridgeAmplitudeMeters: FloatArray,
    val provinceAmplitudeMeters: FloatArray,
    val softness: FloatArray,
    val weakZoneKm: FloatArray,
    /** The ridges' two octaves' wavelengths, km, longest first, and each octave's share of the amplitude. */
    val ridgeWavelengthsKm: DoubleArray,
    val ridgeOctaveWeights: DoubleArray,
    /** The province's one wavelength, km. */
    val provinceWavelengthKm: Double,
    /** A kernel's reach over its wavelength: how many crests long a ridge runs along strike. */
    val elongation: Double,
    /** Impulses in each cube of the noise's lattice. */
    val impulsesPerCube: Int,
    /** How far a weak zone's softness reaches from its boundary, km, and the band of erodibility factors. */
    val weakZoneEfoldKm: Double,
    val erodibilityBandFactor: Double
)

/** What the belts' map pass writes, one entry per map cell: ridge and province relief, meters, and the rock's erodibility factor. */
class BeltFieldOutputs(val ridgeMeters: FloatArray, val provinceMeters: FloatArray, val rockErodibility: FloatArray)

/**
 * The belts' relief and rock at the map's own scale, from the crust the history left: ridges laid
 * along each belt's strike, the cell-mean roughness of a province whose ranges are finer than a
 * cell, and how readily the rock is cut (docs/DESIGN_LEDGER.md, TH1-2).
 *
 * **Ridges along strike** are sparse-convolution Gabor noise (Lagae, Lefebvre, Drettakis and Dutré,
 * *Procedural noise using sparse Gabor convolution*, ACM TOG 28, 2009), the noise Cortial,
 * Peytavie, Galin and Guérin (2019) amplify their planets' crust with along its ridges and folds:
 * kernels `w exp(-pi a^2 r^2) cos(2 pi F (omega . d) + phi)` scattered at random through space, each
 * crest of a kernel square to `omega`, so with `omega` across the belt's strike every kernel's crests
 * run along it and the sum is ridges parallel to the belt, a few crests long, of every length and
 * spacing near the octave's. Read in three dimensions at the point on the sphere, so the noise has no
 * seam, no pole and no grid of the map's in it (docs/CONVENTIONS.md, rule 13); `omega` is the cell's
 * own, from the history's strike tensor, as Lagae et al. vary a kernel's orientation over space.
 *
 * **Rock erodibility** is a factor on the stream-power law's K across the configured bedrock band,
 * `band ^ softness`, the softness the larger of the rock's own and a terrane boundary's.
 *
 * The processor's [readOnProcessor] is the reference a card is held to ([BeltFieldsParity]).
 */
internal object BeltFields {

    /** The belts' map pass on the processor. */
    fun readOnProcessor(inputs: BeltFieldInputs): BeltFieldOutputs {
        val columns = inputs.mapColumns
        val rows = inputs.mapRows
        val cells = columns * rows
        val ridge = FloatArray(cells)
        val province = FloatArray(cells)
        val erodibility = FloatArray(cells)
        val logBand = kotlin.math.ln(inputs.erodibilityBandFactor)
        parallelChunks(0, rows) { fromRow, toRow ->
            for (row in fromRow until toRow) {
                val latitude = PI / 2 - (row + 0.5) / rows * PI
                for (column in 0 until columns) {
                    val cell = row * columns + column
                    val weak = if (inputs.weakZoneKm[cell] < 0f) 0.0 else exp(-inputs.weakZoneKm[cell] / inputs.weakZoneEfoldKm)
                    val softness = maxOf(inputs.softness[cell].toDouble(), weak)
                    erodibility[cell] = exp(softness * logBand).toFloat()
                    val ridgeAmplitude = inputs.ridgeAmplitudeMeters[cell]
                    val provinceAmplitude = inputs.provinceAmplitudeMeters[cell]
                    if (ridgeAmplitude <= 0f && provinceAmplitude <= 0f) continue
                    val longitude = (column + 0.5) / columns * 2.0 * PI
                    val unitX = cos(latitude) * cos(longitude)
                    val unitY = cos(latitude) * sin(longitude)
                    val unitZ = sin(latitude)
                    // Across strike: the gradient tensor's leading direction in the cell's east and
                    // north, `atan2(2 b, a - c) / 2` from east toward north.
                    val across = 0.5 * atan2(
                        2.0 * inputs.strikeEastNorth[cell],
                        (inputs.strikeEastEast[cell] - inputs.strikeNorthNorth[cell]).toDouble()
                    )
                    val eastShare = cos(across)
                    val northShare = sin(across)
                    val omegaX = eastShare * -sin(longitude) + northShare * -sin(latitude) * cos(longitude)
                    val omegaY = eastShare * cos(longitude) + northShare * -sin(latitude) * sin(longitude)
                    val omegaZ = northShare * cos(latitude)
                    val px = unitX * inputs.radiusKm
                    val py = unitY * inputs.radiusKm
                    val pz = unitZ * inputs.radiusKm
                    if (ridgeAmplitude > 0f) {
                        var sum = 0.0
                        for (octave in inputs.ridgeWavelengthsKm.indices) {
                            sum += inputs.ridgeOctaveWeights[octave] * gaborAt(
                                px, py, pz, omegaX, omegaY, omegaZ, inputs.ridgeWavelengthsKm[octave], inputs.elongation,
                                inputs.impulsesPerCube, inputs.seed, octave
                            )
                        }
                        ridge[cell] = (ridgeAmplitude * sum).toFloat()
                    }
                    if (provinceAmplitude > 0f) {
                        province[cell] = (provinceAmplitude * gaborAt(
                            px, py, pz, omegaX, omegaY, omegaZ, inputs.provinceWavelengthKm, inputs.elongation,
                            inputs.impulsesPerCube, inputs.seed, BeltFieldsHash.PROVINCE_OCTAVE
                        )).toFloat()
                    }
                }
            }
        }
        return BeltFieldOutputs(ridge, province, erodibility)
    }

    /**
     * Unit-variance sparse Gabor noise at the point ([px], [py], [pz]) km, its crests square to the
     * unit vector `omega`, of wavelength [wavelengthKm]: the kernels whose impulses lie in the 27
     * cubes about the point's, each cube [elongation] wavelengths on a side, the kernel's reach, and
     * holding [impulses] impulses at places, weights and phases drawn from a hash of the cube.
     */
    fun gaborAt(
        px: Double, py: Double, pz: Double,
        omegaX: Double, omegaY: Double, omegaZ: Double,
        wavelengthKm: Double, elongation: Double, impulses: Int, seed: Int, octave: Int
    ): Double {
        val cubeKm = elongation * wavelengthKm
        val bandwidth = 1.0 / cubeKm
        val frequency = 1.0 / wavelengthKm
        val reach2 = cubeKm * cubeKm
        val cubeX = floor(px / cubeKm).toInt()
        val cubeY = floor(py / cubeKm).toInt()
        val cubeZ = floor(pz / cubeKm).toInt()
        var sum = 0.0
        for (offsetX in -1..1) for (offsetY in -1..1) for (offsetZ in -1..1) {
            val cx = cubeX + offsetX
            val cy = cubeY + offsetY
            val cz = cubeZ + offsetZ
            var hash = cubeHash(seed, octave, cx, cy, cz)
            for (impulse in 0 until impulses) {
                hash = mix(hash + BeltFieldsHash.IMPULSE_STEP)
                val ix = (cx + unit(hash)) * cubeKm
                hash = mix(hash)
                val iy = (cy + unit(hash)) * cubeKm
                hash = mix(hash)
                val iz = (cz + unit(hash)) * cubeKm
                hash = mix(hash)
                val weight = 2.0 * unit(hash) - 1.0
                hash = mix(hash)
                val phase = 2.0 * PI * unit(hash)
                val dx = px - ix
                val dy = py - iy
                val dz = pz - iz
                val distance2 = dx * dx + dy * dy + dz * dz
                if (distance2 >= reach2) continue
                val along = omegaX * dx + omegaY * dy + omegaZ * dz
                sum += weight * exp(-PI * bandwidth * bandwidth * distance2) * cos(2.0 * PI * frequency * along + phase)
            }
        }
        return sum * sqrt(BeltFieldsHash.VARIANCE_NORMALIZER / impulses)
    }

    /** A cube's hash: [seed], the octave and the cube's three indices mixed in turn. */
    fun cubeHash(seed: Int, octave: Int, x: Int, y: Int, z: Int): Int {
        var hash = mix(seed + BeltFieldsHash.OCTAVE_STEP * (octave + 1))
        hash = mix(hash xor x)
        hash = mix(hash xor y)
        hash = mix(hash xor z)
        return hash
    }

    /** Wellons's low-bias 32-bit integer hash, written so a shader's unsigned arithmetic gives the same bits. */
    fun mix(value: Int): Int {
        var bits = value
        bits = bits xor (bits ushr 16)
        bits *= 0x7feb352d
        bits = bits xor (bits ushr 15)
        bits *= 0x846ca68b.toInt()
        bits = bits xor (bits ushr 16)
        return bits
    }

    /** A hash's top 24 bits as a number in 0..1. */
    fun unit(hash: Int): Double = (hash ushr 8).toDouble() / UNIT_SPAN

    private const val UNIT_SPAN = 16_777_216.0

    /**
     * How much of a sinusoid's amplitude survives a cell's mean, for ground whose ranges are
     * [wavelengthKm] apart read on cells [cellKm] across: `|sin(pi x) / (pi x)|`, `x` their ratio.
     */
    fun cellMeanShare(wavelengthKm: Double, cellKm: Double): Double {
        val ratio = cellKm / wavelengthKm
        if (ratio <= 0.0) return 1.0
        return kotlin.math.abs(sin(PI * ratio) / (PI * ratio))
    }

    /** The share of a two-octave sum's amplitude in each octave, a Hurst exponent [hurst] apart: weights summing in quadrature to one. */
    fun octaveWeights(octaves: Int, hurst: Double): DoubleArray {
        val raw = DoubleArray(octaves) { 0.5.pow(hurst * it) }
        val norm = sqrt(raw.sumOf { it * it })
        return DoubleArray(octaves) { raw[it] / norm }
    }
}

/**
 * The figures the belts' noise is hashed and normalized by, public so a device's pass draws the
 * same impulses ([BeltFields.gaborAt]): a shader is handed them as literals, the steps as the
 * unsigned numbers with the same bits.
 */
object BeltFieldsHash {

    /**
     * What makes the noise's variance one: sparse convolution with `n` impulses in each cube of side
     * `1 / a`, weights uniform on -1..1 (mean square a third) and random phases (a cosine's mean
     * square a half), has variance `n a^3 (1/3) K^2 (1/2) (2 a^2)^(-3/2)`, which is `n K^2 / (12
     * sqrt 2)` whatever `a` is; so `K = sqrt(12 sqrt 2 / n)`. The kernel is read only within `1 /
     * a`, where its square has fallen to `exp(-2 pi)`; the Gaussian's square holds all but 0.6% of
     * its volume inside that.
     */
    val VARIANCE_NORMALIZER: Double = 12.0 * sqrt(2.0)

    /** The province's noise is its own octave of the hash, apart from the ridges'. */
    const val PROVINCE_OCTAVE = 7

    /** Golden-ratio steps that keep the octaves' and the impulses' hashes apart. */
    const val OCTAVE_STEP = -0x61c88647
    const val IMPULSE_STEP = 0x632be5ab

    /** The same steps' bits read as unsigned numbers, as a shader's literals take them. */
    val OCTAVE_STEP_UNSIGNED: Long = OCTAVE_STEP.toLong() and 0xFFFF_FFFFL
    val IMPULSE_STEP_UNSIGNED: Long = IMPULSE_STEP.toLong() and 0xFFFF_FFFFL
}
