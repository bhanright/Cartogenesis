package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.FloatField

/**
 * The moisture march's water budget, every term of it, as the march spends it: what the sea
 * evaporates, what the ground gives back, what rains, what the cold cap takes, what the sideways
 * blend between rows adds or loses, and how much the parcels hold at the start and end of a lap.
 *
 * A measurement and nothing else: the march fills it only when one is handed in
 * ([ClimateStage.moistureLedger]), reads nothing back from it, and a world generated without one
 * is the same world to the bit. It exists because the budget was found not to close by reading the
 * code (docs/TODO.md, "The moisture march does not conserve its water"), and a budget that is read
 * rather than summed cannot say how much each leak is worth.
 *
 * Every figure is in the march's own unit, a fraction of saturation, summed over the steps that
 * spent it. [ClimateStage.millimetresPerMarchUnit] over the number of cells marched turns any of
 * them into millimetres a year averaged over those cells, which is how `MoistureClosureTest` prints
 * them.
 */
internal class MoistureLedger {

    /**
     * One lap of one sweep of one circulation belt in one season: the rows [firstRow] until
     * [lastRow], swept toward [sweepDirection] (+1 eastward), in the warm half when [warm].
     *
     * The terms are kept apart so that each can be weighed against the others; [unaccounted] is
     * what a closed budget would hold at zero.
     */
    class Lap(
        val warm: Boolean,
        val firstRow: Int,
        val lastRow: Int,
        val sweepDirection: Int,
        /**
         * True for the second sweep, the one against the belt, which only the cells whose own wind
         * is reversed take their rain from; the first sweeps between them march every cell once.
         */
        val againstTheBelt: Boolean,
        val lap: Int,
        /** The cells this lap stepped through: its rows times the map's width. */
        val cellsMarched: Long
    ) {
        /** What the parcels hold before the lap's first step and after its last. */
        var storageAtStart = 0.0
        var storageAtEnd = 0.0

        /** Open sea's evaporation into the parcel. */
        var seaEvaporation = 0.0

        /** The ground's return into the parcel. */
        var groundReturn = 0.0

        /** Rain over land, taken out of the parcel. */
        var landRain = 0.0

        /** Rain over sea ice, taken out of the parcel. */
        var seaIceRain = 0.0

        /** Rain over open sea as the march records it. */
        var openSeaRainRecorded = 0.0

        /** Rain over open sea as the march takes it out of the parcel. */
        var openSeaRainRemoved = 0.0

        /** What the cold cap clips off a parcel over land after its rain is taken. */
        var coldCapRemoved = 0.0

        /**
         * What the sideways blend between rows adds to a column of parcels, summed over the lap's
         * column steps: the sum of the parcels after each step's blend less the sum before it. A
         * conservative transport holds this at zero.
         */
        var advectionGain = 0.0

        /** Every source the budget names: what the sea and the ground put into the air. */
        val sources: Double get() = seaEvaporation + groundReturn

        /** Every sink the budget names: the rain the march records, wherever it fell. */
        val recordedRain: Double get() = landRain + seaIceRain + openSeaRainRecorded

        /**
         * The storage change the sources and the recorded rain do not explain. Zero for a closed
         * budget; on a march with leaks, water that appeared (positive) or vanished (negative)
         * without being evaporated or rained.
         */
        val unaccounted: Double get() = (storageAtEnd - storageAtStart) - (sources - recordedRain)

        /**
         * The same storage change rebuilt from every term the ledger keeps, leaks included. Equal
         * to the measured change up to float rounding when the ledger has found every term, which
         * is the instrument's own check on itself.
         */
        val explainedByEveryTerm: Double
            get() = sources - landRain - seaIceRain - openSeaRainRemoved - coldCapRemoved +
                advectionGain
    }

    /** Every lap marched, in the order the march ran them: season, belt, sweep, lap. */
    val laps = ArrayList<Lap>()

    /**
     * The recorded lap's per-cell figures, one field per season, filled the way the march fills
     * its rain: from the sweep whose direction each cell's own wind blows. Zero over the sea,
     * except [rainBeforeBlur], which holds the sea's recorded rain too.
     */
    class Cells(cellsAcross: Int, cellsDown: Int) {
        /** The march's rain before the blur spreads it, in march units per cell. */
        val rainBeforeBlur = FloatField(cellsAcross, cellsDown)

        /** The ground's return into the parcel at each land cell. */
        val groundReturn = FloatField(cellsAcross, cellsDown)

        /** What the cold cap clipped off the parcel at each land cell. */
        val coldCapRemoved = FloatField(cellsAcross, cellsDown)
    }

    var warmHalf: Cells? = null
    var coldHalf: Cells? = null
}
