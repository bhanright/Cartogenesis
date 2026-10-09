package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.FloatField

/**
 * The moisture march's water budget, every term of it, as the march spends it: what the sea
 * evaporates, what the ground gives back, what rains and by which mechanism, and what the march
 * holds at the start and end of each lap, in transit along the rows and banked between its two
 * sweeps.
 *
 * A measurement and nothing else: the march fills it only when one is handed in
 * ([ClimateStage.moistureLedger]), reads nothing back from it, and a world generated without one is
 * the same world to the bit.
 *
 * **The unit.** Every lap term is in the march's own unit, millimeters of column water carried
 * across one cell, which is a mass flux: a millimeter of column water moving at the transport
 * speed through the side of a cell one row tall carries `U x cellHeight` kilograms a second,
 * whatever the latitude, because a row's side is the same length at every latitude. So the terms
 * add across rows without weights, and [millimetersPerYearOverTheSphere] turns any of them into a
 * mean over the planet's surface. See [MoistureMarch].
 */
internal class MoistureLedger {

    /** Which surface a term fell on. */
    enum class Surface { LAND, OPEN_SEA, SEA_ICE }

    /** Which mechanism rained it. Each is charged once, on the water left by the ones before. */
    enum class Sink {
        /**
         * The column's own rain at its relative humidity, Bretherton, Peters and Back's relation,
         * under the belts' descent and the inversion's lid ([MoistureMarch.columnRainMmPerDay]).
         */
        COLUMN,

        /** The air the wind gathers into a cell rising and raining what it brought, when switched on. */
        CONVERGENCE,

        /**
         * Cloud the column could not hold at its temperature, from the climb or the cold, fallen
         * out over Smith and Barstad's two delays, or where the wind turns and does not carry it on.
         */
        CONDENSATE
    }

    /** One lap of one season's march: both sweeps, every cell once. */
    class Lap(val warm: Boolean, val lap: Int) {
        /** What the march holds before the lap's first column and after its last: parcels and banks. */
        var storageAtStart = 0.0
        var storageAtEnd = 0.0

        /** The part of [storageAtEnd] banked between the two sweeps rather than carried in a parcel. */
        var bankAtEnd = 0.0

        /** The land-origin part of the same. */
        var landStorageAtStart = 0.0
        var landStorageAtEnd = 0.0

        /** Open sea's evaporation into the parcels. */
        var seaEvaporation = 0.0

        /** The ground's return into the parcels: Budyko's share of each land cell's own rain. */
        var groundReturn = 0.0

        /** Rain by mechanism and surface, indexed `[sink.ordinal][surface.ordinal]`. */
        val rain = Array(Sink.entries.size) { DoubleArray(Surface.entries.size) }

        /** Rain of land-origin water, every mechanism and surface together. */
        var landOriginRain = 0.0

        /** Vapor turned to cloud where the column could not hold it, and cloud evaporated again. */
        var condensed = 0.0
        var reevaporated = 0.0

        /** Parcels found below zero after transport; zero is what positivity means. */
        var negativeParcels = 0

        /** Steps where the land-origin water stood outside zero to the column's water. */
        var tracerOutOfBounds = 0

        /** Values that were not finite numbers anywhere in the lap. */
        var nonFinite = 0

        /** Every source the budget names. */
        val sources: Double get() = seaEvaporation + groundReturn

        /** Every sink: the rain, by every mechanism, wherever it fell. */
        val totalRain: Double get() = rain.sumOf { it.sum() }

        /**
         * The storage change the sources and the rain do not explain. Zero, to the rounding of the
         * sums, for a march that conserves its water.
         */
        val unaccounted: Double get() = (storageAtEnd - storageAtStart) - (sources - totalRain)

        /** The same for the land-origin water: its source is the ground's return alone. */
        val landUnaccounted: Double
            get() = (landStorageAtEnd - landStorageAtStart) - (groundReturn - landOriginRain)
    }

    /** Every lap marched, in the order the march ran them: lap, then season. */
    val laps = ArrayList<Lap>()

    /**
     * The recorded lap's per-cell figures for one season, in millimeters a year at each cell,
     * before the blur.
     */
    class Cells(cellsAcross: Int, cellsDown: Int) {
        val rain = FloatField(cellsAcross, cellsDown)
        val groundReturn = FloatField(cellsAcross, cellsDown)
        val seaEvaporation = FloatField(cellsAcross, cellsDown)

        /** The column's water as the march leaves each cell, millimeters. */
        val columnWater = FloatField(cellsAcross, cellsDown)

        /** The saturated column at each cell's air temperature, millimeters. */
        val saturatedColumn = FloatField(cellsAcross, cellsDown)

        /** The sea's surface less the air over it, degrees, at open-sea cells. */
        val seaMinusAirC = FloatField(cellsAcross, cellsDown)

        /**
         * The rain the air's own convergence makes at each cell. Charged only where the air the
         * cell takes in through its four sides exceeds what it sends on, so it falls where the
         * wind gathers and nowhere else, and cannot cancel against a divergence beside it.
         */
        val convergenceRain = FloatField(cellsAcross, cellsDown)

        /** The rain the condensate makes at each cell: the climb's and the cold's cloud, fallen. */
        val condensateRain = FloatField(cellsAcross, cellsDown)
    }

    var warmHalf: Cells? = null
    var coldHalf: Cells? = null

    /**
     * The annual field the rest of the pipeline reads, after the two seasons are averaged and the
     * blur has spread them, in millimeters a year per cell; and the annual sources the last lap
     * spent, sea and ground, the same way. Their area-weighted sums are the closure of the final
     * field.
     */
    var finalAnnualRainMm: FloatField? = null
    var finalAnnualSourcesMm: FloatField? = null

    /**
     * The surface's own budget at each land cell: the rain the pipeline reads less what the
     * march's ground returned less the runoff the lakes and rivers route, in millimeters a year.
     * Zero where the march's return and the rivers' runoff read the same rain and the same
     * potential evaporation; what is left is the last lap's distance from convergence.
     */
    var surfaceResidualMm: FloatField? = null

    /**
     * The year of rain the last lap's ground return was set from, at each land cell, millimeters:
     * the lap before the last one's, which the convergence test holds within a thousandth of the
     * land's mean rain of the last.
     */
    var returnRainMm: FloatField? = null

    /** How many laps the march ran before the year's rain settled. */
    var lapsRun = 0

    /**
     * After each lap, the land's mean change of annual rain from the lap before, over its mean
     * rain: what [MoistureMarch.CONVERGED_SHARE] is held against.
     */
    val settling = ArrayList<Double>()

    /**
     * Millimeters a year over the planet's surface per unit of a lap term: the transport speed
     * times a year over the sphere's area in meters of the row's side, which is the grid's cells
     * summed by their widths. Set by the march that fills the ledger.
     */
    var millimetersPerYearPerUnit = 0.0

    fun millimetersPerYearOverTheSphere(units: Double): Double = units * millimetersPerYearPerUnit
}
