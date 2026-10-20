package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * The world every [CrustMapAccelerator] is held to the processor on, and the measure of how far a
 * device's answer sits from [CrustMap]'s own (docs/DESIGN_LEDGER.md, TH1-1).
 *
 * A real history rather than a synthetic one: the pass reads a lattice, its bins and its fields,
 * and a history is the only thing that makes all of them consistent. Seed [FIXTURE_SEED] at
 * [ROWS] rows, the everyday tier's grid.
 *
 * The bars are what single precision explains. The card weighs each point in a float, so a point
 * lying at the kernel's very reach may fall either side of it there and not here. Its weight there
 * is [TAIL_WEIGHT] of a point at the cell, and a cell's total weight is at least that of its
 * nearest point, which lies within the history's covering radius of it, [NEAREST_WEIGHT]: so a share
 * moves by at most their ratio, and a field by that share of its span. The thickness is compared
 * where the crust is continent and the floor's age where it is floor, since each is read off its
 * own crust's points and a cell with none of them takes a stand-in (the standard column, the
 * nearest point's age) that a single point at the reach may replace. A category is the heaviest
 * point's or plate's, which a float can tip only where two weigh within its last bits of each other.
 */
class CrustMapParity private constructor(private val inputs: CrustMapInputs, private val reference: CrustMapOutputs) {

    val cellsAcross: Int get() = inputs.mapColumns
    val cellsDown: Int get() = inputs.mapRows

    /** The device's answer on the fixture, or null where it declined. */
    suspend fun askDevice(device: CrustMapAccelerator): CrustMapOutputs? = device.read(inputs)

    /** How far a device's answer sits from the processor's, each against its bar. */
    class Gap(
        val shareWorst: Double,
        val shareBar: Double,
        val thicknessWorstKm: Double,
        val thicknessBarKm: Double,
        val floorAgeWorstMyr: Double,
        val floorAgeBarMyr: Double,
        val crustAgeWorstMyr: Double,
        val crustAgeBarMyr: Double,
        /** Cells whose plate, orogeny kind, terrane, or whether they were ever rifted, differ: a share of all cells. */
        val categoriesDiffering: Double,
        val categoriesBar: Double,
        /** Where the share stands farthest apart, row and column, and the two shares there. */
        val worstRow: Int,
        val worstColumn: Int,
        val worstProcessorShare: Float,
        val worstDeviceShare: Float
    ) {
        val withinBars: Boolean
            get() = shareWorst <= shareBar && thicknessWorstKm <= thicknessBarKm && floorAgeWorstMyr <= floorAgeBarMyr &&
                crustAgeWorstMyr <= crustAgeBarMyr && categoriesDiffering <= categoriesBar
    }

    fun compare(device: CrustMapOutputs): Gap {
        val cells = reference.continentalShare.size
        var shareWorst = 0.0
        var worstCell = 0
        var thicknessWorst = 0.0
        var floorWorst = 0.0
        var crustWorst = 0.0
        var differing = 0
        for (cell in 0 until cells) {
            val shareGap = abs((device.continentalShare[cell] - reference.continentalShare[cell]).toDouble())
            if (shareGap > shareWorst) {
                shareWorst = shareGap
                worstCell = cell
            }
            if (reference.continentalShare[cell] >= HALF) {
                thicknessWorst = max(thicknessWorst, abs((device.thicknessKm[cell] - reference.thicknessKm[cell]).toDouble()))
            } else {
                floorWorst = max(floorWorst, abs((device.seafloorAgeMyr[cell] - reference.seafloorAgeMyr[cell]).toDouble()))
            }
            crustWorst = max(crustWorst, abs((device.crustAgeMyr[cell] - reference.crustAgeMyr[cell]).toDouble()))
            if (device.plateId[cell] != reference.plateId[cell] || device.orogenyKind[cell] != reference.orogenyKind[cell] ||
                device.terrane[cell] != reference.terrane[cell] || (device.riftAgeMyr[cell] >= 0f) != (reference.riftAgeMyr[cell] >= 0f)
            ) differing++
        }
        fun span(values: FloatArray): Double = (values.max() - values.min()).toDouble()
        val shareBar = TAIL_WEIGHT / NEAREST_WEIGHT + FLOAT_ROUNDING
        return Gap(
            shareWorst = shareWorst,
            shareBar = shareBar,
            thicknessWorstKm = thicknessWorst,
            thicknessBarKm = shareBar * span(reference.thicknessKm),
            floorAgeWorstMyr = floorWorst,
            floorAgeBarMyr = shareBar * span(reference.seafloorAgeMyr),
            crustAgeWorstMyr = crustWorst,
            crustAgeBarMyr = shareBar * span(reference.crustAgeMyr),
            categoriesDiffering = differing.toDouble() / cells,
            categoriesBar = CATEGORY_TIES,
            worstRow = worstCell / inputs.mapColumns,
            worstColumn = worstCell % inputs.mapColumns,
            worstProcessorShare = reference.continentalShare[worstCell],
            worstDeviceShare = device.continentalShare[worstCell]
        )
    }

    companion object {
        const val FIXTURE_SEED = 42L
        const val ROWS = 512

        private const val HALF = 0.5f

        /** The weight of a point at the kernel's reach against one at the cell: `exp(-(1.5 / 0.6)^2)`, about 0.0019. */
        val TAIL_WEIGHT: Double = exp(-(CrustMap.KERNEL_REACH_SPACINGS / CrustMap.KERNEL_SPREAD_SPACINGS).let { it * it })

        /**
         * The least weight a cell's nearest point can have: one at the lattice's covering radius.
         * A hexagonal lattice of cell area `A` covers every place within `sqrt(2 A / (3 sqrt 3))`,
         * 0.62 of its spacing, and the spiral lattice is near-hexagonal; the history takes 0.8
         * spacings as the radius within which every place has a point, and so does this:
         * `exp(-(0.8 / 0.6)^2)`, about 0.17.
         */
        val NEAREST_WEIGHT: Double = exp(-(COVERING_SPACINGS / CrustMap.KERNEL_SPREAD_SPACINGS).let { it * it })

        /** The lattice's covering radius in spacings, as the history covers its points. */
        private const val COVERING_SPACINGS = 0.8

        /** A float's relative rounding over the few dozen operations a cell's sum takes: 2^-24 forty times over. */
        const val FLOAT_ROUNDING = 40.0 / 16_777_216.0

        /**
         * The share of cells whose category a float may tip: where two points or two plates weigh
         * within a float's last bits of each other, which on a lattice of distinct points is a tie
         * of measure nothing; a part in a thousand leaves room for the reach's own flips.
         */
        const val CATEGORY_TIES = 1e-3

        /** The fixture: seed [FIXTURE_SEED]'s history read at [ROWS] rows. */
        fun fixture(): CrustMapParity {
            val config = WorldGenConfig.forRows(FIXTURE_SEED, ROWS)
            val inputs = CrustMap.inputs(config, TectonicHistory.run(config))
            return CrustMapParity(inputs, CrustMap.readOnProcessor(inputs))
        }
    }
}
