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
 * ground's return, the rain by each of its four mechanisms and each surface, and what the march
 * holds at each lap's start and end, in the parcels carried along the rows and in the bank between
 * its two sweeps. The march carries water between rows and columns as fluxes, each face's taken
 * out of one row and given to the other, so the budget closes by construction; these clauses are
 * what would see a change that broke that. Until C1 it did not close: the rain over open sea was
 * never taken out, the cold cap dropped water, and the row blend made and lost it, together 15.7
 * times the march's sources (docs/DESIGN_LEDGER.md, C1).
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
         * How far the surface budget may stand from closing, as a share of the land's mean rain:
         * a hundredth. The march's ground return is Budyko's share of the year's rain from the lap
         * before; the rivers' runoff is the rest of the final year's, so what is left at a cell is
         * the march's distance from its fixed point.
         */
        const val SURFACE_TOLERANCE = 0.01

        /** The synthetic world's rows: a grid small enough to march in a moment. */
        const val SYNTHETIC_ROWS = 32

        /**
         * What a world with no source may still hold after the march's laps, as a share of what
         * it started with: a tenth. The slowest parcel is the one beside the pole, whose lap round
         * a 32-row grid's last row is 1,960 km, 2.9 days at the transport speed; ten laps are 3.3
         * of the 8.9-day lifetime, `exp(-3.3)` = 0.04 left, and every other row keeps less.
         */
        const val NO_SOURCE_REMAINDER = 0.1
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
            assertTrue(ledger.laps.size == 2 * MoistureMarch.LAPS, "seed $seed: the march filled ${ledger.laps.size} laps")
            ledger.laps.forEach { lap ->
                worst = maxOf(worst, abs(lap.unaccounted) / lap.sources)
                if (lap.groundReturn > 0.0) worstLand = maxOf(worstLand, abs(lap.landUnaccounted) / lap.sources)
            }
            val last = ledger.laps.filter { it.lap == MoistureMarch.LAPS - 1 }
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
            val substeps = ledger.laps.maxOf { it.mostSubsteps }
            println("POSITIVITY seed %d: at most %d transport sub-steps a column".format(seed, substeps))
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
            ledger.laps.filter { it.lap == MoistureMarch.LAPS - 1 }.forEach { lap ->
                val share = abs(lap.storageAtEnd - lap.storageAtStart) / lap.sources
                println("STEADY seed %d %s half: the last lap's storage changed by %.2e of its sources".format(seed, if (lap.warm) "warm" else "cold", share))
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
            val last = ledger.laps.filter { it.lap == MoistureMarch.LAPS - 1 }
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
            var area = 0.0
            var rain = 0.0
            var off = 0.0
            var worst = 0.0
            for (row in 0 until world.height) {
                val weight = cos(ClimateStage.latitudeOf(row, world.height) * PI / 180.0)
                for (column in 0 until world.width) {
                    val cell = row * world.width + column
                    if (!world.sea.isLand[cell]) continue
                    area += weight
                    rain += weight * world.climate.precipitationMm.data[cell]
                    off += weight * abs(residual[cell])
                    worst = maxOf(worst, abs(residual[cell]).toDouble())
                }
            }
            println("SURFACE seed %d: rain less return less runoff averages %.2f mm on land, against a mean rain of %.0f; the worst cell %.0f mm"
                .format(seed, off / area, rain / area, worst))
            assertTrue(off / rain <= SURFACE_TOLERANCE, "seed $seed: the surface budget is %.2f%% off".format(100 * off / rain))
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
        fun season(warm: Boolean) = MoistureMarch.Season(
            warm = warm,
            airTemperatureC = FloatArray(cells) { -20f },
            seaSurfaceC = FloatArray(cells) { -1.8f },
            seaIce = BooleanArray(cells) { true },
            zonalDirection = IntArray(cells) { cell -> if ((cell / config.width) % 7 < 4) 1 else -1 },
            southwardMps = FloatArray(cells) { cell -> if ((cell / config.width) % 2 == 0) 1.5f else -0.5f },
            beltRainFactorOfRow = FloatArray(config.height) { 1f },
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
                warm = season(true),
                cold = season(false),
                lidElevation = 0f,
                blurSigmaKm = 0.0
            ),
            ledger
        )
        assertTrue(result.warmRainMm.data.all { it.isFinite() && it >= 0f }, "the rain is not finite and positive")
        ledger.laps.forEach { lap ->
            assertEquals(0.0, lap.sources, 0.0, "a world with no source evaporated something")
            assertTrue(lap.storageAtEnd <= lap.storageAtStart, "lap ${lap.lap}: the storage grew with no source")
            assertTrue(abs(lap.unaccounted) <= CLOSURE_TOLERANCE * lap.storageAtStart, "lap ${lap.lap}: unaccounted water")
            assertEquals(0, lap.negativeParcels + lap.tracerOutOfBounds + lap.nonFinite, "lap ${lap.lap}: a guard tripped")
        }
        val first = ledger.laps.first { it.lap == 0 }
        val last = ledger.laps.last()
        println("NO SOURCE: storage %.3e at the start, %.3e after %d laps".format(first.storageAtStart, last.storageAtEnd, MoistureMarch.LAPS))
        assertTrue(last.storageAtEnd < first.storageAtStart * NO_SOURCE_REMAINDER, "the march kept its water with nothing to replace it")
    }
}
