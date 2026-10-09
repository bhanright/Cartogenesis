package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.MoistureBudget
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Three things the moisture march does with its water beyond carrying it, each against Earth or
 * its control.
 *
 * **Continental recycling.** Rain that falls on land evaporates and rains again downwind, and each
 * parcel carries how much of its water last came off land: the share of the continents' rain that
 * is that water is the continental precipitation recycling ratio, [EARTH_RECYCLING] on Earth.
 *
 * **Convergence.** Where the wind gathers air the march's transport gathers its water, and a
 * column that fills rains what it cannot hold. A closure that rains the gathered air at the rate
 * it gathers is an assumption beside that, and `ClimateConfig.convergenceRain` switches it on; it
 * is checked here on its own against GPCP's equatorial band, which is why it is off.
 *
 * **The marine inversion.** A subtropical west coast washed by a cold current is a desert: the
 * Atacama, the Namib, Baja. The control is `ClimateConfig.marineInversion` off, which is the
 * generator before this chunk, where a cold current starved its coast of evaporation but nothing
 * stopped the moisture that was left from raining out on the first slope. **It is not
 * delivered**: the lid is built and applied and moves the coast by 0.2%, for the two measured
 * reasons [RAIN_LOST_TO_THE_LID] gives. What this test asserts is only that the lid the march
 * reads is a real one, so the figures beside it measure the term and not its absence.
 *
 * See docs/DESIGN_LEDGER.md, W3, and `MoistureBudget` for where each constant comes from.
 */
class MoistureBudgetTest : BorrowsSharedWorlds() {

    private companion object {
        val seeds = SharedWorlds.STANDARD_SEEDS

        /**
         * [SharedWorlds.COARSE_ROWS] for the convergence and the inversion: a pooled interior's
         * rainfall and a coast's share of its hinterland's rain are sums over the ground, and the
         * ITCZ's centroid is a mean over the tropics, none of them the grid's detail.
         */
        const val size = SharedWorlds.COARSE_ROWS

        /**
         * [SharedWorlds.DETAIL_ROWS] for the recycling ratio, which moves with the cell and stands
         * at the edge of Earth's band: 0.303 at 512 rows and 0.285 at 256 on the same tree,
         * measured at Q2b (docs/DESIGN_LEDGER.md, Q2b). Its bar is Earth's and is not moved.
         */
        const val RECYCLING_ROWS = SharedWorlds.DETAIL_ROWS

        /**
         * Earth's continental precipitation recycling ratio: the share of all rain over land whose
         * water last evaporated from land rather than from the sea, 40 percent (van der Ent and
         * others 2010).
         */
        const val EARTH_RECYCLING = 0.40

        /** How far a pooled figure may stand from Earth's, as a factor either way: a quarter. */
        const val EARTH_TOLERANCE = 1.25

        /**
         * GPCP's ocean zonal-mean rain at its wettest, 8 mm a day near 7 N (Adler and others,
         * GPCP version 2, twenty years), in millimeters a year.
         */
        const val GPCP_OCEAN_PEAK_MM = 8.0 * 365.25

        /** How far from the equator the ITCZ is looked for, and the bands it is read in, degrees. */
        const val ITCZ_SEARCH_DEGREES = 20
        const val ZONAL_BAND_DEGREES = 2

        /** The subtropics, in degrees, where an eastern-boundary current makes a coastal desert. */
        const val COAST_EQUATORWARD_DEGREES = 12f
        const val COAST_POLEWARD_DEGREES = 42f

        /**
         * How cold the water off a coast has to read, in degrees below the mean of its own
         * latitude, before the coast counts as one a cold current washes.
         *
         * Half a degree. Earth's eastern-boundary currents run three to seven degrees below their
         * latitude's mean; this generator's whole sea-surface anomaly field is a fraction of that
         * (W2 measured its shifts at 0.30 to 0.66 C), so a bar at Earth's figure would select no
         * coast on any seed and the claim would be vacuous rather than met. The bar here selects
         * the coasts the generator's own currents make coldest, and the *finding* beside the
         * assertion is how cold that is against Earth's.
         */
        const val COLD_COAST_ANOMALY_C = 0.5f

        /**
         * The coastal strip the lid caps and the hinterland it is compared against, in kilometres.
         *
         * A hundred and four hundred. **What makes a coastal desert is a coast drier than the
         * country behind it**, which is the Atacama's signature and not "a dry strip": the first
         * measurement this test made totalled the rain over three hundred kilometres and read a
         * loss of 0.6%, because rain the lid holds in at the shore falls a hundred kilometres
         * inland and lands inside the same total. The lid redistributes before it removes, so the
         * thing to measure is the ratio between the two.
         */
        const val COAST_STRIP_KM = 100.0
        const val HINTERLAND_KM = 400.0

        /**
         * **The marine inversion is not delivered, and this test measures rather than asserts it.**
         *
         * The lid is built, it is applied, and it changes nothing a reader could see: pooled over
         * the standard seeds it moves the coast's share of its own hinterland's rain by 0.2%. That
         * is not a term that is missing. The diagnostic this test prints beside the figures shows
         * a suppression field over the coast's own cells with a warm-season mean of 0.07 to 0.39
         * and a peak of 0.95, and none of those cells standing above the 1,000 m lid - so the
         * march is reading a strong lid and raining anyway.
         *
         * **Why, measured:** the march is a reservoir, and this term is a multiplier on the rate
         * at which the reservoir empties. Hold the rate down and the moisture simply stands
         * higher - evapotranspiration adds on the deficit `(1 - moisture)`, so a parcel that rains
         * less refills faster - and the product the march records, `moisture x rate`, comes back
         * to where it was within a few cells. It is the same fact GEOGRAPHY.md records the other
         * way up under "Where the deserts are": a multiplier cannot make a rain shadow wet again,
         * and it cannot make a wet coast dry either. A coastal desert needs the water taken out of
         * the column rather than the rain rate held down, which is a change to the march's shape
         * and not to this term.
         *
         * **And a second reason, which is not W3's:** the coasts these worlds put over their
         * coldest water sit at 33 to 42 degrees, in the westerlies with the storm track feeding
         * them 900 to 2,400 mm a year. Seed 7's coldest reads 4.48 C below its latitude's mean at
         * 40.6 degrees - Earth-strength water at a latitude Earth does not build the Atacama at.
         * Even a lid that bit would have no subject on these seeds. That is a finding about where
         * `OceanStage` puts its eastern-boundary currents, recorded in GEOGRAPHY.md for the chunk
         * that owns it.
         *
         * The figure below is kept as what the term would have to reach to be worth asserting.
         * See docs/DESIGN_LEDGER.md, W3.
         */
        const val RAIN_LOST_TO_THE_LID = 0.05

        /** The fewest cells a coast may have before it is a coast rather than an accident. */
        const val MIN_COAST_CELLS = 5
    }

    private fun generate(
        seed: Long,
        rows: Int = size,
        tune: (WorldGenConfig) -> WorldGenConfig
    ): WorldMap {
        val base = WorldGenConfig.forRows(seed, rows)
        return SharedWorlds.world(tune(base))
    }

    // ---------------------------------------------------------------- recycling

    /**
     * The share of the continents' rain whose water last evaporated from land, pooled by the rain's
     * volume: the tracer's rain over the land's rain, both summed by area on the sphere. Van der
     * Ent and others (2010) put Earth's at 40 percent of all continental precipitation, from
     * moisture tracking in ERA-Interim; held within a quarter of it either way, the tolerance the
     * other rain figures against Earth take (`RainAgainstEarthTest`).
     *
     * The ratio is the tracer's, and the tracer is checked by its own accounting in
     * `MoistureClosureTest`: the land-origin water closes its budget lap by lap and never stands
     * outside zero to the column's water, so this is a measurement of the march and not a figure
     * the march was fitted to.
     */
    @Test
    fun `the share of continental rain that last evaporated from land is Earth's`() {
        var pooledRain = 0.0
        var pooledRecycled = 0.0
        seeds.forEach { seed ->
            val world = generate(seed, RECYCLING_ROWS) { it }
            val (rain, recycled) = continentalRain(world)
            pooledRain += rain
            pooledRecycled += recycled
            println("RECYCLING seed %d: %.1f%% of continental rain last evaporated from land".format(seed, 100.0 * recycled / rain))
        }
        val ratio = pooledRecycled / pooledRain
        println(
            "RECYCLING pooled over %d seeds by volume: %.1f%%, against Earth's %.0f%% (van der Ent and others 2010)"
                .format(seeds.size, ratio * 100, EARTH_RECYCLING * 100)
        )
        assertTrue(
            ratio > EARTH_RECYCLING / EARTH_TOLERANCE && ratio < EARTH_RECYCLING * EARTH_TOLERANCE,
            "the continental recycling ratio is %.3f, outside Earth's %.2f by more than a quarter".format(ratio, EARTH_RECYCLING)
        )
    }

    /** Rain over land and the part of it whose water last came off land, area weighted, in mm. */
    private fun continentalRain(world: WorldMap): Pair<Double, Double> {
        val climate = ClimateStage.generateWithSeasonalMm(world.config, world.sea, world.ocean)
        var rain = 0.0
        var recycled = 0.0
        for (cell in 0 until world.width * world.height) {
            if (!world.sea.isLand[cell]) continue
            val area = cos(ClimateStage.latitudeOf(cell / world.width, world.height) * PI / 180.0)
            rain += area * climate.result.precipitationMm.data[cell]
            recycled += area * climate.landOriginPrecipitationMm.data[cell]
        }
        return rain to recycled
    }

    // ---------------------------------------------------------------- convergence

    /**
     * The convergence closure checked on its own: with `ClimateConfig.convergenceRain` on, the air
     * the wind gathers rains at the rate it gathers, and the band where the trades meet is held to
     * Earth's. GPCP's twenty-year analysis puts the ocean's zonal-mean rain at its wettest at 8 mm
     * a day near 7 N (Adler and others, GPCP version 2), 2,922 mm a year; this reads each world's
     * wettest two degrees of open ocean within 20 degrees of the equator, with the closure and
     * without.
     *
     * The closure is off because of what this measures, and the clause holds the decision to it:
     * the world without the closure stands nearer GPCP's figure than the world with it. What the
     * closure reads against GPCP is recorded beside it.
     */
    @Test
    fun `the convergence closure, checked on its own against the equatorial band`() {
        var withClosure = 0.0
        var shipped = 0.0
        seeds.forEach { seed ->
            val closed = itczOceanPeakMm(generate(seed) { it.copy(climate = it.climate.copy(convergenceRain = true)) })
            val open = itczOceanPeakMm(generate(seed) { it })
            withClosure += closed
            shipped += open
            println(
                "CONVERGENCE seed %d: the ocean's wettest two degrees take %.0f mm with the closure, %.0f without, against GPCP's %.0f"
                    .format(seed, closed, open, GPCP_OCEAN_PEAK_MM)
            )
        }
        withClosure /= seeds.size
        shipped /= seeds.size
        println(
            "CONVERGENCE mean over %d seeds: %.0f mm with the closure, %.0f without, against GPCP's %.0f"
                .format(seeds.size, withClosure, shipped, GPCP_OCEAN_PEAK_MM)
        )
        assertTrue(
            abs(ln(shipped / GPCP_OCEAN_PEAK_MM)) <= abs(ln(withClosure / GPCP_OCEAN_PEAK_MM)),
            ("the convergence closure now stands nearer GPCP's equatorial band (%.0f mm) than the shipped " +
                "march (%.0f), against %.0f: the reason it is off has changed")
                .format(withClosure, shipped, GPCP_OCEAN_PEAK_MM)
        )
        KnownFailures.expect("C1b: the convergence closure rains the equatorial band past GPCP's", "x0.0") {
            if (withClosure > GPCP_OCEAN_PEAK_MM * EARTH_TOLERANCE) {
                throw RecordedViolation(
                    "with the convergence closure the ocean's wettest band takes %.0f mm, x%.2f GPCP's %.0f"
                        .format(withClosure, withClosure / GPCP_OCEAN_PEAK_MM, GPCP_OCEAN_PEAK_MM),
                    "x%.1f".format(withClosure / GPCP_OCEAN_PEAK_MM)
                )
            }
        }
    }

    /** The open ocean's zonal-mean rain in its wettest two-degree band within 20 degrees, mm. */
    private fun itczOceanPeakMm(world: WorldMap): Double {
        var best = 0.0
        var band = -ITCZ_SEARCH_DEGREES
        while (band < ITCZ_SEARCH_DEGREES) {
            var total = 0.0
            var cells = 0
            for (row in 0 until world.height) {
                val latitude = ClimateStage.latitudeOf(row, world.height)
                if (latitude < band || latitude >= band + ZONAL_BAND_DEGREES) continue
                for (column in 0 until world.width) {
                    val cell = row * world.width + column
                    if (world.sea.isLand[cell]) continue
                    total += world.climate.precipitationMm.data[cell]
                    cells++
                }
            }
            if (cells > 0) best = maxOf(best, total / cells)
            band += ZONAL_BAND_DEGREES
        }
        return best
    }

    // ---------------------------------------------------------------- marine inversion

    @Test
    fun `what the marine inversion does to a cold west coast, which is not enough`() {
        var pooledWith = 0.0
        var pooledWithout = 0.0
        var seedsMeasured = 0
        var desertCoasts = 0
        var lidPeak = 0f
        seeds.forEach { seed ->
            val world = generate(seed) { it }
            val control = generate(seed) {
                it.copy(climate = it.climate.copy(marineInversion = false))
            }
            val coast = coldestWestCoast(world) ?: run {
                println("INVERSION seed $seed: no subtropical west coast over cold water")
                return@forEach
            }
            seedsMeasured++
            val rainWith = coastAgainstHinterland(world, coast)
            val rainWithout = coastAgainstHinterland(control, coast)
            pooledWith += rainWith
            pooledWithout += rainWithout
            val share = desertShareOfStrip(world, coast)
            val controlShare = desertShareOfStrip(control, coast)
            if (share >= 0.5) desertCoasts++
            lidPeak = maxOf(lidPeak, reportLidStrength(seed, world, coast))
            println(
                ("INVERSION seed %d: %d subtropical west-coast cells over cold water, coldest " +
                    "at %.1f degrees, column %d, %.2f C below its latitude's mean; the first " +
                    "%.0f km take %.3f of what the %.0f km behind them take, against %.3f with " +
                    "the lid off (%+.1f%%), and read %.0f%% desert against %.0f%%")
                    .format(
                        seed, coast.cells.size, coast.latitude, coast.column, -coast.anomalyC,
                        COAST_STRIP_KM, rainWith, HINTERLAND_KM, rainWithout,
                        100.0 * (rainWith - rainWithout) / rainWithout, share * 100,
                        controlShare * 100
                    )
            )
        }
        assertTrue(seedsMeasured > 0, "no standard seed carries a subtropical west coast over cold water")
        val lost = (pooledWithout - pooledWith) / pooledWithout
        println(
            ("INVERSION pooled over %d seeds: the lid drops the coast's share of its " +
                "hinterland's rain by %.1f%%, against a bar of %.0f%%; %d of %d coasts read " +
                "desert, which is the finding")
                .format(
                    seedsMeasured, lost * 100, RAIN_LOST_TO_THE_LID * 100, desertCoasts,
                    seedsMeasured
                )
        )
        // Not asserted: see [RAIN_LOST_TO_THE_LID]. What is asserted is that the lid the march
        // reads is a real one, so that whoever picks this up does not have to re-derive whether
        // the term was ever wired in.
        assertTrue(
            lidPeak > 0.5f,
            ("the strongest lid over any cold west coast on any standard seed is %.3f, so the " +
                "term is not reaching the march at all and the figures above are measuring its " +
                "absence rather than its effect").format(lidPeak)
        )
    }

    /** The west-facing coast a world's cold water washes, and how cold that water is. */
    private class ColdCoast(
        val latitude: Double,
        val column: Int,
        val anomalyC: Float,
        val cells: List<Int>
    )

    /**
     * Every subtropical west-facing coast cell whose water is colder than
     * [COLD_COAST_ANOMALY_C], pooled, with the coldest of them named.
     *
     * Pooled rather than cut into segments: a coastline on this grid is rarely straight for a
     * dozen rows at one longitude, and asking for one that is selected nothing on any standard
     * seed. What makes a coastal desert is the water off it and the latitude it sits at, and
     * neither is a claim about how straight the coast is.
     */
    private fun coldestWestCoast(world: WorldMap): ColdCoast? {
        val cellsAcross = world.width
        val cellsDown = world.height
        val cells = ArrayList<Int>()
        var coldest = 0f
        var coldestCell = -1
        var coldestOffshore = 0f
        for (row in 0 until cellsDown) {
            val latitude = abs(ClimateStage.latitudeOf(row, cellsDown))
            if (latitude < COAST_EQUATORWARD_DEGREES || latitude > COAST_POLEWARD_DEGREES) continue
            for (column in 0 until cellsAcross) {
                val cell = row * cellsAcross + column
                if (!world.sea.isLand[cell]) continue
                val westOf = row * cellsAcross + (column + cellsAcross - 1) % cellsAcross
                if (world.sea.isLand[westOf]) continue
                val anomaly = world.ocean.anomaly.data[westOf]
                if (anomaly > -COLD_COAST_ANOMALY_C) continue
                cells.add(cell)
                if (coldestCell < 0 || anomaly < coldestOffshore) {
                    coldestOffshore = anomaly
                    coldestCell = cell
                    coldest = anomaly
                }
            }
        }
        if (cells.size < MIN_COAST_CELLS) return null
        return ColdCoast(
            ClimateStage.latitudeOf(coldestCell / cellsAcross, cellsDown).toDouble(),
            coldestCell % cellsAcross, coldest, cells
        )
    }

    /**
     * The lid the march actually saw over [coast]'s own cells, in both seasons: a diagnostic, not
     * a claim, so that a term that is not biting can be told from a term that is not there.
     */
    private fun reportLidStrength(seed: Long, world: WorldMap, coast: ColdCoast): Float {
        val tilt = if (world.config.climate.seasons) {
            world.config.climate.seasonalTiltDegrees
        } else {
            0f
        }
        var strongest = 0f
        listOf(true, false).forEach { warm ->
            val field = MoistureBudget.inversionSuppression(
                world.config, world.sea,
                if (world.config.ocean.enabled) world.ocean.anomaly else null, tilt, warm
            ) ?: return@forEach
            var sum = 0.0
            var peak = 0f
            coast.cells.forEach { cell ->
                sum += field.data[cell].toDouble()
                if (field.data[cell] > peak) peak = field.data[cell]
            }
            val lid = world.config.scale.reliefShareOfMetres(MoistureBudget.INVERSION_LID_METRES)
            var aboveLid = 0
            coast.cells.forEach { cell ->
                if (world.sea.relativeElevation.data[cell] >= lid) aboveLid++
            }
            println(
                ("LID seed %d %s: strength over the coast's own cells mean %.3f, peak %.3f; " +
                    "%d of %d of those cells already stand above the %.0f m lid")
                    .format(
                        seed, if (warm) "warm" else "cold", sum / coast.cells.size, peak,
                        aboveLid, coast.cells.size, MoistureBudget.INVERSION_LID_METRES
                    )
            )
            strongest = maxOf(strongest, peak)
        }
        return strongest
    }

    /**
     * What the coastal strip behind [coast] takes as a share of what its own hinterland takes.
     *
     * Below one is a coast drier than the country behind it, which is what a marine inversion
     * makes and what a rain shadow does not: the shadow is behind the range, this is in front of
     * everything.
     */
    private fun coastAgainstHinterland(world: WorldMap, coast: ColdCoast): Double {
        val coastal = meanRainInland(world, coast, 0.0, COAST_STRIP_KM)
        val hinterland = meanRainInland(world, coast, COAST_STRIP_KM, HINTERLAND_KM)
        return if (hinterland <= 0.0) 0.0 else coastal / hinterland
    }

    /** Mean annual rainfall between [fromKm] and [toKm] inland of [coast], in millimetres. */
    private fun meanRainInland(
        world: WorldMap,
        coast: ColdCoast,
        fromKm: Double,
        toKm: Double
    ): Double {
        val cellsAcross = world.width
        val firstCell = world.config.cellsFor(fromKm).toInt()
        val lastCell = world.config.cellsFor(toKm).toInt().coerceAtLeast(firstCell + 1)
        var total = 0.0
        var land = 0
        coast.cells.forEach { cell ->
            val row = cell / cellsAcross
            val startColumn = cell % cellsAcross
            for (step in 0 until lastCell) {
                val column = (startColumn + step) % cellsAcross
                val here = row * cellsAcross + column
                if (!world.sea.isLand[here]) break
                if (step < firstCell) continue
                total += world.climate.precipitationMm.data[here].toDouble()
                land++
            }
        }
        return if (land == 0) 0.0 else total / land
    }

    /** The share of the land strip inland of [coast] that classifies as desert. */
    private fun desertShareOfStrip(world: WorldMap, coast: ColdCoast): Double {
        val cellsAcross = world.width
        val stripCells = world.config.cellsFor(COAST_STRIP_KM).toInt().coerceAtLeast(1)
        var desert = 0
        var land = 0
        coast.cells.forEach { cell ->
            val row = cell / cellsAcross
            val startColumn = cell % cellsAcross
            for (step in 0 until stripCells) {
                val column = (startColumn + step) % cellsAcross
                val here = row * cellsAcross + column
                if (!world.sea.isLand[here]) break
                land++
                if (world.climate.biome[here] == Biome.DESERT) desert++
            }
        }
        return if (land == 0) 0.0 else desert.toDouble() / land
    }
}
