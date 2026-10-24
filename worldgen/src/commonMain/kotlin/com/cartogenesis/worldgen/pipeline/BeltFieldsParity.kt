package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The world every device's belts' pass ([CrustMapAccelerator.beltFields]) is held to the
 * processor on, and the measure of how far its answer sits from [BeltFields]'s own
 * (docs/DESIGN_LEDGER.md, TH1-2).
 *
 * A real world rather than a synthetic one: the pass reads the strike, the amplitudes and the
 * rock the plate stage builds from a history, and only a history makes them consistent. Seed
 * [FIXTURE_SEED] at [ROWS] rows, the everyday tier's grid.
 *
 * The bars are what single precision explains. Each kernel of the noise is weighed in a float, so
 * a sum of a cell's [TERMS_PER_OCTAVE] kernels, each at most the normalization `K` in size,
 * carries their rounding: the cosine's argument, up to `2 pi` times the reach over the wavelength
 * plus a phase, rounds to a part in `2^24` of itself, and the exponential and the products to a few
 * parts more, [TERM_ROUNDING] of a term at worst. Apart from that a kernel lying at its very reach
 * may fall either side of it there and not here, where it weighs `exp(-pi)` of its peak: so a cell
 * may sit one such kernel apart at worst, and few do ([REACH_FLIPS] of the belts' cells). The
 * erodibility is one exponential of a float, a few parts in `2^24` of itself.
 */
class BeltFieldsParity private constructor(private val inputs: BeltFieldInputs, private val reference: BeltFieldOutputs) {

    val cellsAcross: Int get() = inputs.mapColumns
    val cellsDown: Int get() = inputs.mapRows

    /** The device's answer on the fixture, or null where it declined. */
    suspend fun askDevice(device: CrustMapAccelerator): BeltFieldOutputs? = device.beltFields(inputs)

    /** How far a device's answer sits from the processor's, each against its bar; relief as a share of the cell's own amplitude. */
    class Gap(
        val ridgeWorstShare: Double,
        val provinceWorstShare: Double,
        val reliefWorstBar: Double,
        /** The belts' cells off by more than rounding explains, as a share of the cells with relief. */
        val reliefOffShare: Double,
        val reliefRoundingBar: Double,
        val reliefOffBar: Double,
        val erodibilityWorstRelative: Double,
        val erodibilityBar: Double,
        val cellsWithRelief: Int
    ) {
        val withinBars: Boolean
            get() = ridgeWorstShare <= reliefWorstBar && provinceWorstShare <= reliefWorstBar && reliefOffShare <= reliefOffBar &&
                erodibilityWorstRelative <= erodibilityBar
    }

    fun compare(device: BeltFieldOutputs): Gap {
        val cells = reference.ridgeMetres.size
        val roundingBar = roundingBar()
        var ridgeWorst = 0.0
        var provinceWorst = 0.0
        var erodibilityWorst = 0.0
        var withRelief = 0
        var off = 0
        for (cell in 0 until cells) {
            erodibilityWorst = max(
                erodibilityWorst,
                abs((device.rockErodibility[cell] - reference.rockErodibility[cell]).toDouble()) / reference.rockErodibility[cell]
            )
            val ridgeAmplitude = inputs.ridgeAmplitudeMetres[cell].toDouble()
            val provinceAmplitude = inputs.provinceAmplitudeMetres[cell].toDouble()
            if (ridgeAmplitude <= 0.0 && provinceAmplitude <= 0.0) continue
            withRelief++
            var cellOff = false
            if (ridgeAmplitude > 0.0) {
                val share = abs((device.ridgeMetres[cell] - reference.ridgeMetres[cell]).toDouble()) / ridgeAmplitude
                ridgeWorst = max(ridgeWorst, share)
                if (share > roundingBar) cellOff = true
            }
            if (provinceAmplitude > 0.0) {
                val share = abs((device.provinceMetres[cell] - reference.provinceMetres[cell]).toDouble()) / provinceAmplitude
                provinceWorst = max(provinceWorst, share)
                if (share > roundingBar) cellOff = true
            }
            if (cellOff) off++
        }
        val normalization = sqrt(BeltFieldsHash.VARIANCE_NORMALIZER / inputs.impulsesPerCube)
        val heaviestOctave = inputs.ridgeOctaveWeights.maxOrNull() ?: 1.0
        return Gap(
            ridgeWorstShare = ridgeWorst,
            provinceWorstShare = provinceWorst,
            reliefWorstBar = exp(-PI) * normalization * max(heaviestOctave, 1.0) + roundingBar,
            reliefOffShare = if (withRelief > 0) off.toDouble() / withRelief else 0.0,
            reliefRoundingBar = roundingBar,
            reliefOffBar = REACH_FLIPS,
            erodibilityWorstRelative = erodibilityWorst,
            erodibilityBar = EXPONENTIAL_ROUNDING,
            cellsWithRelief = withRelief
        )
    }

    /** Rounding's bar on a cell's relief, a share of its amplitude: every octave's terms each off by [TERM_ROUNDING] of `K`. */
    private fun roundingBar(): Double {
        val normalization = sqrt(BeltFieldsHash.VARIANCE_NORMALIZER / inputs.impulsesPerCube)
        val octaveWeights = max(inputs.ridgeOctaveWeights.sum(), 1.0)
        return TERMS_PER_OCTAVE * inputs.impulsesPerCube * normalization * TERM_ROUNDING * octaveWeights
    }

    companion object {
        const val FIXTURE_SEED = 42L
        const val ROWS = 512

        /** The cubes a cell's kernels are drawn from: its own and its 26 neighbors; each holds the configured impulses. */
        private const val TERMS_PER_OCTAVE = 27

        /**
         * A term's rounding in single precision: the cosine's argument is at most `2 pi` times the
         * kernel's reach in wavelengths ([PlateStage]'s elongation, 3) plus a phase, about 25, and
         * rounds to `25 * 2^-24`; a device's cosine and exponential add a few parts in `2^22` more.
         */
        private const val TERM_ROUNDING = 25.0 / 16_777_216.0 + 4.0 / 4_194_304.0

        /**
         * The share of the belts' cells a kernel at the very reach may tip: the reach is a sphere
         * about each impulse, and a cell lies within a float's rounding of one of the hundred
         * spheres about it on a set of measure nothing; a part in a thousand leaves room.
         */
        const val REACH_FLIPS = 1e-3

        /** One exponential of a float: a few parts in `2^24`. */
        const val EXPONENTIAL_ROUNDING = 8.0 / 16_777_216.0

        /** The fixture: seed [FIXTURE_SEED]'s world's belts' inputs at [ROWS] rows. */
        fun fixture(): BeltFieldsParity {
            val config = WorldGenConfig.forRows(FIXTURE_SEED, ROWS)
            val inputs = PlateStage.beltFieldInputsOf(config)
            return BeltFieldsParity(inputs, BeltFields.readOnProcessor(inputs))
        }
    }
}
