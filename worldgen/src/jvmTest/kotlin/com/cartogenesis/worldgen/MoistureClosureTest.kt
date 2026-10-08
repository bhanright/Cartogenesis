package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.MoistureLedger
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The moisture march's water budget closes: in every lap, what the parcels hold at its end less
 * what they held at its start is what the sea and the ground evaporated into them less the rain the
 * march records, and nothing else.
 *
 * Water has three ways to leave the books that this test exists to see. The cold cap over land
 * clips a parcel after its rain is taken, and what it clips is neither rained nor carried. The rain
 * over open sea is recorded and never taken out of the parcel, so the ocean's air stands near
 * saturation whatever the sea's rain rate. And the sideways blend between rows, which carries the
 * air along a slanting wind, is an interpolation rather than a flux, so a converging wind loses the
 * air that meets and a diverging one copies it. The first two were found by reading the code
 * (docs/TODO.md, "The moisture march does not conserve its water"); [MoistureLedger] sums all
 * three, and this test weighs each against the water the sources put in.
 *
 * The first clause is the instrument's check on itself: rebuilt from every term the ledger keeps,
 * leaks included, the storage change is the measured one, so no term is missing from the ledger.
 * The second is the budget, and on main it fails: recorded with its residuals as a known failure.
 */
class MoistureClosureTest : BorrowsSharedWorlds() {

    private companion object {
        val seeds = SharedWorlds.STANDARD_SEEDS

        /**
         * [SharedWorlds.COARSE_ROWS]: the closure is a property of the march's arithmetic, which is
         * the same on every grid, so the cheapest standard world asks the question as well as any.
         */
        const val ROWS = SharedWorlds.COARSE_ROWS

        /**
         * How far a lap's measured storage change may stand from the one rebuilt from every term,
         * as a share of the lap's gross flux (sources, recorded rain and the storage it started
         * with): a millionth. The parcels are floats, whose rounding is about six parts in a
         * hundred million a step; the ledger sums in doubles and takes each term off the parcel
         * itself, so the two agree to the rounding of the storage sum, far inside this.
         */
        const val INSTRUMENT_TOLERANCE = 1e-6

        /**
         * How much water a lap may gain or lose outside its sources and its recorded rain, as a
         * share of what its sources put in: a ten-thousandth. A closed budget is zero to the
         * floats' rounding, which [INSTRUMENT_TOLERANCE] bounds a hundred times tighter; a leak
         * worth a reader's attention is a percent or more of the rain somewhere. The bar sits
         * between the two so that it says closed or not and nothing about how well.
         */
        const val CLOSURE_TOLERANCE = 1e-4
    }

    /** What one seed's recorded laps add up to, over the sweeps that march every cell once. */
    private class Pooled {
        var sources = 0.0
        var recordedRain = 0.0
        var unaccounted = 0.0
        var openSeaUnremoved = 0.0
        var coldCap = 0.0
        var advection = 0.0

        fun add(lap: MoistureLedger.Lap) {
            sources += lap.sources
            recordedRain += lap.recordedRain
            unaccounted += lap.unaccounted
            openSeaUnremoved += lap.openSeaRainRecorded - lap.openSeaRainRemoved
            coldCap += lap.coldCapRemoved
            advection += lap.advectionGain
        }

        fun add(other: Pooled) {
            sources += other.sources
            recordedRain += other.recordedRain
            unaccounted += other.unaccounted
            openSeaUnremoved += other.openSeaUnremoved
            coldCap += other.coldCap
            advection += other.advection
        }
    }

    private fun ledgerFor(seed: Long): Pair<WorldGenConfig, MoistureLedger> {
        val world = SharedWorlds.world(WorldGenConfig.forRows(seed, ROWS))
        return world.config to ClimateStage.moistureLedger(world.config, world.sea, world.ocean)
    }

    @Test
    fun `the ledger finds every term of the march's water budget`() {
        seeds.forEach { seed ->
            val (_, ledger) = ledgerFor(seed)
            assertTrue(ledger.laps.isNotEmpty(), "seed $seed: the march filled no ledger")
            ledger.laps.forEach { lap ->
                val measured = lap.storageAtEnd - lap.storageAtStart
                val gross = lap.sources + lap.recordedRain + lap.storageAtStart
                assertTrue(
                    abs(measured - lap.explainedByEveryTerm) <= INSTRUMENT_TOLERANCE * gross,
                    ("seed %d, rows %d-%d, lap %d: the parcels' storage changed by %.6f and the " +
                        "ledger's terms explain %.6f, so a term is missing from it")
                        .format(seed, lap.firstRow, lap.lastRow, lap.lap, measured, lap.explainedByEveryTerm)
                )
            }
        }
    }

    @Test
    fun `every lap's storage change is its sources less its rain`() {
        val pooled = Pooled()
        var worstShare = 0.0
        var worstLap = ""
        seeds.forEach { seed ->
            val (config, ledger) = ledgerFor(seed)
            val millimetresPerUnit = ClimateStage.millimetresPerMarchUnit(config).toDouble()
            val cellCount = config.width.toDouble() * config.height
            val seedPooled = Pooled()
            ledger.laps.forEach { lap ->
                val share = abs(lap.unaccounted) / lap.sources
                if (share > worstShare) {
                    worstShare = share
                    worstLap = "seed %d, %s half, rows %d-%d, sweep %+d, lap %d"
                        .format(seed, if (lap.warm) "warm" else "cold", lap.firstRow, lap.lastRow, lap.sweepDirection, lap.lap + 1)
                }
                if (lap.lap == ledger.laps.maxOf { it.lap } && !lap.againstTheBelt) seedPooled.add(lap)
            }
            // In millimetres a year over the map's cells: two seasonal marches, so half their sum.
            fun mm(units: Double) = units * millimetresPerUnit / cellCount / 2.0
            println(
                ("CLOSURE seed %d, recorded lap, mm a year over the map's cells: sources %.0f " +
                    "(sea %.0f, ground %.0f), recorded rain %.0f (land %.0f, open sea %.0f, sea ice %.0f); " +
                    "unaccounted %+.0f = open sea rain never taken out %+.0f, cold cap %+.0f, " +
                    "row blend %+.0f")
                    .format(
                        seed, mm(seedPooled.sources),
                        mm(ledger.recorded { it.seaEvaporation }), mm(ledger.recorded { it.groundReturn }),
                        mm(seedPooled.recordedRain),
                        mm(ledger.recorded { it.landRain }), mm(ledger.recorded { it.openSeaRainRecorded }),
                        mm(ledger.recorded { it.seaIceRain }),
                        mm(seedPooled.unaccounted), mm(seedPooled.openSeaUnremoved),
                        mm(-seedPooled.coldCap), mm(seedPooled.advection)
                    )
            )
            pooled.add(seedPooled)
        }
        val signature = "unaccounted %+.3f of the sources: open sea rain %+.3f, cold cap %+.3f, row blend %+.3f".format(
            pooled.unaccounted / pooled.sources, pooled.openSeaUnremoved / pooled.sources,
            -pooled.coldCap / pooled.sources, pooled.advection / pooled.sources
        )
        println("CLOSURE pooled over %d seeds, recorded laps: %s; worst lap %.3f of its sources (%s)"
            .format(seeds.size, signature, worstShare, worstLap))
        // The two leaks docs/TODO.md records and the row blend's own, measured: see the class
        // comment, and notes on the fix in docs/TODO.md's entry.
        KnownFailures.expect("C1: the moisture march does not conserve its water", "unaccounted +15.657 of the sources: open sea rain +15.731, cold cap -0.029, row blend -0.045") {
            if (worstShare > CLOSURE_TOLERANCE) {
                throw RecordedViolation(
                    ("the march's water budget does not close: the worst lap leaves %.3f of its " +
                        "sources unaccounted (%s); pooled, %s").format(worstShare, worstLap, signature),
                    signature
                )
            }
        }
    }

    /** One term summed over the recorded lap of the sweeps that march every cell once. */
    private fun MoistureLedger.recorded(term: (MoistureLedger.Lap) -> Double): Double {
        val last = laps.maxOf { it.lap }
        return laps.filter { it.lap == last && !it.againstTheBelt }.sumOf(term)
    }
}
