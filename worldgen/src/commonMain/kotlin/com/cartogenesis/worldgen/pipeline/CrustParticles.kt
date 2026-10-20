package com.cartogenesis.worldgen.pipeline

import kotlin.math.sqrt

/**
 * What a piece of crust has done in the history, recorded where it was last deformed. The ordinal
 * is saved (`PlateResult.orogenyKind`), so the order is fixed.
 */
enum class OrogenyKind {
    /** Never deformed in the history or in the assembly before it: a craton, or ocean floor. */
    NONE,

    /** A collision that welded the supercontinent's cratons together, before the history began. */
    ASSEMBLY_SUTURE,

    /** Two continents meeting during the history. */
    COLLISION,

    /** A continental margin above a subducting slab: the Andes. */
    ANDEAN,

    /** An island arc's crust, grown on ocean floor above a slab. */
    ISLAND_ARC,

    /** Continental crust stretched where a plate split. */
    RIFT,

    /** A small piece of crust carried in on another plate and welded on. */
    ACCRETED_TERRANE
}

/**
 * The crust the tectonic history carries: one sample per [FibonacciSphere.cellAreaKm2] of the
 * planet's surface, each riding a plate and moved by its rotation exactly, so nothing about where a
 * sample is depends on the lattice it is read on.
 *
 * Every sample stands for the same area, so the continental crust's area is a count and its volume
 * a sum of thicknesses, and a sample is only ever made or removed through [add] and [removeWhere],
 * which is where [CrustBudget] books it. That is what makes the transport conservative: the crust
 * is not resampled from one step to the next, so nothing is duplicated or lost by a lookup.
 *
 * The thickness is kept in two parts. [baseKm] is the crust as it was built — a craton, a stretched
 * margin, an arc, ocean floor — and [excessKm] what orogeny has piled on it since, which erosion
 * takes off and lateral flow spreads; their sum is the crust's thickness.
 */
internal class CrustParticles(initialCapacity: Int) {

    var count = 0
        private set

    var x = DoubleArray(initialCapacity); private set
    var y = DoubleArray(initialCapacity); private set
    var z = DoubleArray(initialCapacity); private set
    var plate = IntArray(initialCapacity); private set
    var continental = BooleanArray(initialCapacity); private set
    var baseKm = FloatArray(initialCapacity); private set
    var excessKm = FloatArray(initialCapacity); private set

    /** When the crust was made, Myr before the present: a ridge's age for ocean floor, a craton's for its own. */
    var formedMyrAgo = FloatArray(initialCapacity); private set

    /** When it was last deformed, Myr before the present; NaN where never. */
    var orogenyMyrAgo = FloatArray(initialCapacity); private set
    var orogenyKind = ByteArray(initialCapacity); private set

    /** Which craton, microcontinent or arc a piece of continent began as; -1 for ocean floor. */
    var terrane = IntArray(initialCapacity); private set

    /** When the margin here was stretched, Myr before the present; NaN where never. */
    var riftMyrAgo = FloatArray(initialCapacity); private set

    fun thicknessKm(sample: Int): Float = baseKm[sample] + excessKm[sample]

    /** Appends a sample at the unit vector given and returns its index. */
    fun add(
        unitX: Double, unitY: Double, unitZ: Double, plateId: Int, isContinental: Boolean,
        base: Float, excess: Float, formed: Float, orogeny: Float, kind: OrogenyKind, terraneId: Int, rift: Float
    ): Int {
        if (count == x.size) grow()
        val sample = count++
        val length = sqrt(unitX * unitX + unitY * unitY + unitZ * unitZ)
        x[sample] = unitX / length
        y[sample] = unitY / length
        z[sample] = unitZ / length
        plate[sample] = plateId
        continental[sample] = isContinental
        baseKm[sample] = base
        excessKm[sample] = excess
        formedMyrAgo[sample] = formed
        orogenyMyrAgo[sample] = orogeny
        orogenyKind[sample] = kind.ordinal.toByte()
        terrane[sample] = terraneId
        riftMyrAgo[sample] = rift
        return sample
    }

    /** Moves a sample to the unit vector given, renormalised so rounding never walks it off the sphere. */
    fun place(sample: Int, unitX: Double, unitY: Double, unitZ: Double) {
        val length = sqrt(unitX * unitX + unitY * unitY + unitZ * unitZ)
        x[sample] = unitX / length
        y[sample] = unitY / length
        z[sample] = unitZ / length
    }

    /**
     * Drops every sample [removed] marks, keeping the rest in their order, so the indices that
     * remain are a fixed function of what was there and the history stays deterministic.
     */
    fun removeWhere(removed: BooleanArray) {
        var kept = 0
        for (sample in 0 until count) {
            if (removed[sample]) continue
            if (kept != sample) {
                x[kept] = x[sample]; y[kept] = y[sample]; z[kept] = z[sample]
                plate[kept] = plate[sample]; continental[kept] = continental[sample]
                baseKm[kept] = baseKm[sample]; excessKm[kept] = excessKm[sample]
                formedMyrAgo[kept] = formedMyrAgo[sample]; orogenyMyrAgo[kept] = orogenyMyrAgo[sample]
                orogenyKind[kept] = orogenyKind[sample]; terrane[kept] = terrane[sample]
                riftMyrAgo[kept] = riftMyrAgo[sample]
            }
            kept++
        }
        count = kept
    }

    private fun grow() {
        val capacity = x.size * 2
        x = x.copyOf(capacity); y = y.copyOf(capacity); z = z.copyOf(capacity)
        plate = plate.copyOf(capacity); continental = continental.copyOf(capacity)
        baseKm = baseKm.copyOf(capacity); excessKm = excessKm.copyOf(capacity)
        formedMyrAgo = formedMyrAgo.copyOf(capacity); orogenyMyrAgo = orogenyMyrAgo.copyOf(capacity)
        orogenyKind = orogenyKind.copyOf(capacity); terrane = terrane.copyOf(capacity)
        riftMyrAgo = riftMyrAgo.copyOf(capacity)
    }
}

/**
 * The continental crust's books: what the history began with, every way it was made and lost, and
 * what it ended with, as areas in km² and volumes in km³.
 *
 * The area is booked by sample, so it closes exactly: [closingAreaKm2] is what the books say and
 * the present samples are counted separately; `CrustBudgetTest` holds the two equal. The volume
 * closes to the rounding of single-precision thicknesses summed in double.
 */
class CrustBudget {
    var startingAreaKm2 = 0.0
    var startingVolumeKm3 = 0.0

    /** Arc crust that grew thick enough to stand as continent. */
    var arcAreaMadeKm2 = 0.0

    /** New continental crust laid where a rift stretched the margin wider. */
    var stretchedAreaMadeKm2 = 0.0

    /** Continental crust that went under another continent and became thickness there. */
    var shortenedAreaKm2 = 0.0

    /** Forearc crust worn thinner than a continent can be by subduction erosion. */
    var erodedForearcAreaKm2 = 0.0

    /** Ocean floor that thick crust flowing sideways off a continent built up into continent. */
    var escapedAreaMadeKm2 = 0.0

    var arcVolumeAddedKm3 = 0.0
    var oceanArcVolumeBecameContinentKm3 = 0.0

    /** Continental crust that flowed out onto ocean floor, and the floor's whole column where that made it continent. */
    var escapedOntoOceanKm3 = 0.0
    var escapedBecameContinentKm3 = 0.0
    var recycledAtTrenchesKm3 = 0.0
    var recycledAtCollisionsKm3 = 0.0
    var erodedFromOrogensKm3 = 0.0

    /** The area the books end with: the start, plus what was made, less what was lost. */
    val closingAreaKm2: Double
        get() = startingAreaKm2 + arcAreaMadeKm2 + stretchedAreaMadeKm2 + escapedAreaMadeKm2 - shortenedAreaKm2 - erodedForearcAreaKm2

    /**
     * The volume the books end with. Collision moves volume from the sample that went under to the
     * belt, and stretching from the margin to the crust laid beside it, so neither appears here.
     */
    val closingVolumeKm3: Double
        get() = startingVolumeKm3 + arcVolumeAddedKm3 + oceanArcVolumeBecameContinentKm3 + escapedBecameContinentKm3 - escapedOntoOceanKm3 -
            recycledAtTrenchesKm3 - recycledAtCollisionsKm3 - erodedFromOrogensKm3 -
            stretchingStillHeldKm3 - stretchingLostWithPlatesKm3

    /** The continental crust counted at the end, set by the history once it has run. */
    var endingAreaKm2 = 0.0
    var endingVolumeKm3 = 0.0

    /**
     * Volume a stretched margin gave up that the opening has not yet laid out as crust, still held by
     * its plate at the end, and what a plate that went down a trench took with it. The first is
     * continental crust in waiting, and the volume's books close with it counted in.
     */
    var stretchingStillHeldKm3 = 0.0
    var stretchingLostWithPlatesKm3 = 0.0
}
