package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.MoistureLedger
import com.cartogenesis.worldgen.pipeline.MoistureMarch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The moisture march's water budget closes, from the first lap's parcels to the annual field the
 * rest of the pipeline reads, and the march keeps its water positive, its tracer inside its
 * water and its numbers finite.
 *
 * [MoistureLedger] sums every term the march spends, lap by lap: the sea's evaporation, the
 * ground's return, the rain by each of its mechanisms and each surface, and what the march
 * holds at each lap's start and end, in the parcels carried along the rows and in the bank between
 * its two sweeps. The march carries water between rows and columns as fluxes, each face's taken
 * out of one row and given to the other, so the budget closes by construction; these clauses are
 * what would see a change that broke that. Until C1b it did not close: the rain over open sea was
 * never taken out, the cold cap dropped water, and the row blend made and lost it, together 15.7
 * times the march's sources (docs/DESIGN_LEDGER.md, C1b).
 *
 * At [SharedWorlds.COARSE_ROWS] on the standard seeds: a closure is a property of the march's
 * arithmetic, the same on every grid, and the cheapest standard world asks it as well as any.
 */
class MoistureClosureTest : BorrowsSharedWorlds() {

    private companion object {
        val seeds = SharedWorlds.STANDARD_SEEDS
        const val ROWS = SharedWorlds.COARSE_ROWS

        /**
         * How far a lap's storage change may stand from its sources less its rain, as a share of
         * its sources: a millionth. The march carries its parcels and banks in doubles and the
         * ledger takes each term off the water it moved, so the two agree to the doubles' rounding
         * over a few million steps, a hundred millionth or less.
         */
        const val CLOSURE_TOLERANCE = 1e-6

        /**
         * How far the last lap may still be filling or draining, as a share of its sources: six
         * thousandths, the twice-standard-error of GPCP's global precipitation over 17 years
         * (2.9 of 486.9 thousand km³; Trenberth and others 2007). The storage a lap gains is rain
         * the year has not yet given back, so a march nearer its steady state than this is nearer
         * than Earth's own budget is known.
         */
        const val STEADY_TOLERANCE = 2.9 / 486.9

        /**
         * How far the annual field's area-weighted rain may stand from its sources less the last
         * lap's storage change, as a share of the sources: a hundred thousandth. The blur and the
         * averaging of the two seasons conserve in doubles, and the field is stored in floats, whose
         * rounding over a few hundred thousand cells is well inside this.
         */
        const val FIELD_TOLERANCE = 1e-5

        /**
         * The float rounding a cell's surface budget may carry beyond the convergence bound, as a
         * share of its rain: a hundred thousandth, a float's rounding over the few sums that make the
         * rain, the return and the runoff.
         *
         * The bound itself is the march's own: the ground's return in the last lap was set from the
         * year the lap before made, and the rivers' runoff is the rest of the last year's, so at a
         * cell the two miss by Budyko's return of the change between those two years, which is never
         * more than the change; and the march stops when the land's mean change is under
         * [MoistureMarch.CONVERGED_SHARE] of its mean rain.
         */
        const val FLOAT_SLACK = 1e-5

        /** The synthetic world's rows: a grid small enough to march in a moment. */
        const val SYNTHETIC_ROWS = 32

        /**
         * What a world with no source may still hold after the march's laps, as a share of what
         * it started with. Its air starts at four fifths of its saturated column, and the column's
         * rain ([MoistureMarch.columnRainMmPerDay]) is a millimeter a day or more while it stands
         * above [MoistureMarch.COLUMN_RAIN_HUMIDITY_OFFSET] of it, which empties the 2 mm a column
         * holds at -20 C within days, well inside the laps; below the offset the rain slows
         * exponentially, which is why dry air keeps its water, so the bar is the offset over the
         * starting four fifths and not nothing.
         */
        val NO_SOURCE_REMAINDER = MoistureMarch.COLUMN_RAIN_HUMIDITY_OFFSET / 0.8
    }

    private fun ledgerFor(seed: Long): Pair<WorldMap, MoistureLedger> {
        val world = SharedWorlds.world(WorldGenConfig.forRows(seed, ROWS))
        return world to ClimateStage.moistureLedger(world.config, world.sea, world.ocean)
    }

    @Test
    fun `every lap's storage change is its sources less its rain, banks included`() {
        var worst = 0.0
        var worstLand = 0.0
        seeds.forEach { seed ->
            val (_, ledger) = ledgerFor(seed)
            assertTrue(ledger.laps.size == 2 * ledger.lapsRun, "seed $seed: the march filled ${ledger.laps.size} laps of ${ledger.lapsRun}")
            ledger.laps.forEach { lap ->
                worst = maxOf(worst, abs(lap.unaccounted) / lap.sources)
                if (lap.groundReturn > 0.0) worstLand = maxOf(worstLand, abs(lap.landUnaccounted) / lap.sources)
            }
            val last = ledger.laps.filter { it.lap == ledger.lapsRun - 1 }
            fun mm(term: (MoistureLedger.Lap) -> Double) = ledger.millimetersPerYearOverTheSphere(last.sumOf(term) / 2)
            val sinks = MoistureLedger.Sink.entries.joinToString { sink ->
                "%s %.0f".format(sink.name.lowercase(), mm { lap -> lap.rain[sink.ordinal].sum() })
            }
            println(
                "CLOSURE seed %d, last lap, mm a year over the sphere: sea %.0f, ground %.0f; rain %.0f (%s); bank %.0f of storage %.0f"
                    .format(seed, mm { it.seaEvaporation }, mm { it.groundReturn }, mm { it.totalRain }, sinks,
                        mm { it.bankAtEnd }, mm { it.storageAtEnd })
            )
        }
        println("CLOSURE worst lap over %d seeds: %.2e of its sources unaccounted, %.2e of the land-origin water".format(seeds.size, worst, worstLand))
        assertTrue(worst <= CLOSURE_TOLERANCE, "a lap leaves %.2e of its sources unaccounted".format(worst))
        assertTrue(worstLand <= CLOSURE_TOLERANCE, "the land-origin tracer leaves %.2e of the sources unaccounted".format(worstLand))
    }

    @Test
    fun `every parcel stays positive, every tracer inside its water and every value finite`() {
        seeds.forEach { seed ->
            val (_, ledger) = ledgerFor(seed)
            println("POSITIVITY seed %d: %d laps".format(seed, ledger.lapsRun))
            assertEquals(0, ledger.laps.sumOf { it.negativeParcels }, "seed $seed: parcels below zero after transport")
            assertEquals(0, ledger.laps.sumOf { it.tracerOutOfBounds }, "seed $seed: land-origin water outside 0..W")
            assertEquals(0, ledger.laps.sumOf { it.nonFinite }, "seed $seed: values that are not finite numbers")
        }
    }

    @Test
    fun `the march reaches its steady state`() {
        var worst = 0.0
        seeds.forEach { seed ->
            val (_, ledger) = ledgerFor(seed)
            ledger.laps.filter { it.lap == ledger.lapsRun - 1 }.forEach { lap ->
                val share = abs(lap.storageAtEnd - lap.storageAtStart) / lap.sources
                println("STEADY seed %d %s half: the last lap's storage changed by %.2e of its sources".format(seed, if (lap.julyHalf) "April-September" else "October-March", share))
                worst = maxOf(worst, share)
            }
        }
        assertTrue(worst <= STEADY_TOLERANCE, "the last lap still changes its storage by %.2e of its sources".format(worst))
    }

    @Test
    fun `the annual field the pipeline reads closes its budget`() {
        seeds.forEach { seed ->
            val (world, ledger) = ledgerFor(seed)
            val rain = ledger.finalAnnualRainMm!!.data
            val sources = ledger.finalAnnualSourcesMm!!.data
            val stored = world.climate.precipitationMm.data
            var area = 0.0
            var rainSum = 0.0
            var sourceSum = 0.0
            for (row in 0 until world.height) {
                val weight = cos(ClimateStage.latitudeOf(row, world.height) * PI / 180.0)
                for (column in 0 until world.width) {
                    val cell = row * world.width + column
                    area += weight
                    rainSum += weight * rain[cell]
                    sourceSum += weight * sources[cell]
                    assertEquals(stored[cell], rain[cell], 0f, "seed $seed cell $cell: the ledger's field is not the climate's")
                }
            }
            val last = ledger.laps.filter { it.lap == ledger.lapsRun - 1 }
            val storedMm = ledger.millimetersPerYearOverTheSphere(last.sumOf { it.storageAtEnd - it.storageAtStart } / 2)
            val residual = (rainSum - sourceSum) / area + storedMm
            println(
                "FIELD seed %d: the annual field rains %.2f mm over the sphere against sources of %.2f and %.2f gone into storage; residual %.2e of the sources"
                    .format(seed, rainSum / area, sourceSum / area, storedMm, residual / (sourceSum / area))
            )
            assertTrue(
                abs(residual) <= FIELD_TOLERANCE * sourceSum / area,
                "seed $seed: the annual field rains %.3f mm more than its sources less its storage".format(residual)
            )
        }
    }

    @Test
    fun `the surface budget closes cell by cell`() {
        seeds.forEach { seed ->
            val (world, ledger) = ledgerFor(seed)
            val residual = ledger.surfaceResidualMm!!.data
            val returnedFrom = ledger.returnRainMm!!.data
            var area = 0.0
            var rain = 0.0
            var off = 0.0
            var worst = 0.0
            var outside = 0
            for (row in 0 until world.height) {
                val weight = cos(ClimateStage.latitudeOf(row, world.height) * PI / 180.0)
                for (column in 0 until world.width) {
                    val cell = row * world.width + column
                    if (!world.sea.isLand[cell]) continue
                    area += weight
                    rain += weight * world.climate.precipitationMm.data[cell]
                    off += weight * abs(residual[cell])
                    // A cell misses by Budyko's return of the change between the year its return
                    // was set from and the year it rains, which is never more than that change.
                    val stillMoving = abs(world.climate.precipitationMm.data[cell] - returnedFrom[cell])
                    val allowed = stillMoving + FLOAT_SLACK * world.climate.precipitationMm.data[cell]
                    if (abs(residual[cell]) > allowed) outside++
                    worst = maxOf(worst, abs(residual[cell]).toDouble())
                }
            }
            val bound = MoistureMarch.CONVERGED_SHARE * rain / area
            println("SURFACE seed %d after %d laps: rain less return less runoff averages %.3f mm on land against the convergence bound of %.3f, a mean rain of %.0f; the worst cell %.1f mm; %d cells outside their own last change"
                .format(seed, ledger.lapsRun, off / area, bound, rain / area, worst, outside))
            assertTrue(ledger.lapsRun < MoistureMarch.MAX_LAPS, "seed $seed: the year's rain had not settled after ${ledger.lapsRun} laps")
            assertEquals(0, outside, "seed $seed: land cells whose surface budget misses by more than their year moved in the last lap")
            assertTrue(off / area <= bound, "seed $seed: the land's surface budget is %.3f mm off on average, over the bound of %.3f".format(off / area, bound))
        }
    }

    /**
     * A world whose sea is frozen from pole to pole, with no land: nothing evaporates and nothing
     * is given back, so the march has no source, and all it can do is rain out the water its
     * parcels started with. Every figure stays finite, nothing goes negative, the storage only
     * falls, and the last lap rains almost nothing.
     */
    @Test
    fun `a world with no source rains its water out and nothing more`() {
        val config = WorldGenConfig.forRows(1L, SYNTHETIC_ROWS)
        val cells = config.width * config.height
        fun season() = MoistureMarch.Season(
            airTemperatureC = FloatArray(cells) { -20f },
            seaSurfaceC = FloatArray(cells) { -1.8f },
            seaIce = BooleanArray(cells) { true },
            eastwardMps = FloatArray(cells) { cell -> if ((cell / config.width) % 7 < 4) 7.5f else -7.5f },
            southwardMps = FloatArray(cells) { cell -> if ((cell / config.width) % 2 == 0) 1.5f else -0.5f },
            largeScaleAscentMps = null,
            inversionSuppression = null,
            extraterrestrialOfRow = DoubleArray(config.height) { 10.0 }
        )
        val ledger = MoistureLedger()
        val result = MoistureMarch.run(
            MoistureMarch.Inputs(
                config = config,
                isLand = BooleanArray(cells),
                relativeElevation = FloatArray(cells) { -0.5f },
                elevationM = FloatArray(cells),
                julyHalf = season(),
                januaryHalf = season(),
                lidElevation = 0f,
                blurSigmaKm = 0.0
            ),
            ledger
        )
        assertTrue(result.julyHalfRainMm.data.all { it.isFinite() && it >= 0f }, "the rain is not finite and positive")
        ledger.laps.forEach { lap ->
            assertEquals(0.0, lap.sources, 0.0, "a world with no source evaporated something")
            assertTrue(lap.storageAtEnd <= lap.storageAtStart, "lap ${lap.lap}: the storage grew with no source")
            assertTrue(abs(lap.unaccounted) <= CLOSURE_TOLERANCE * lap.storageAtStart, "lap ${lap.lap}: unaccounted water")
            assertEquals(0, lap.negativeParcels + lap.tracerOutOfBounds + lap.nonFinite, "lap ${lap.lap}: a guard tripped")
        }
        val first = ledger.laps.first { it.lap == 0 }
        val last = ledger.laps.last()
        println("NO SOURCE: storage %.3e at the start, %.3e after %d laps".format(first.storageAtStart, last.storageAtEnd, ledger.lapsRun))
        assertTrue(last.storageAtEnd < first.storageAtStart * NO_SOURCE_REMAINDER, "the march kept its water with nothing to replace it")
    }
}
