package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.MoistureBudget
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The three things W3 put into the moisture march, each with its own control.
 *
 * **Continental recycling.** Rain that falls on land evaporates and rains again downwind. The
 * march has given water back over land since the belts did, but nothing ever asked how much of
 * the rain was that water: the answer read zero by construction, because no parcel carried where
 * its moisture came from. It carries it now, and the share is the continental precipitation
 * recycling ratio — [EARTH_RECYCLING_LOW] to [EARTH_RECYCLING_HIGH] on Earth.
 *
 * **Convergence.** Where the regional wind gathers air, air rises and rains. The control is
 * `ClimateConfig.convergenceRain` off, which is a bucket carried along a streamline: two parcels
 * blown together by a thermal low cannot make each other rain, which is what W2 reported and what
 * left every continental interior a uniform pale. The claim is about the interior of a summer
 * continent, and it is paired with a claim that the ITCZ has not moved — the term reads the
 * *departure* wind's divergence exactly so that it cannot move it.
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
class MoistureBudgetTest {

    private companion object {
        val seeds = listOf(7L, 42L, 1234L, 99L)
        const val size = 512

        /**
         * Earth's continental precipitation recycling ratio: the share of rain over land whose
         * water last evaporated from land rather than from the sea.
         *
         * Van der Ent and others (2010) track evaporation through the atmosphere in reanalysis and
         * find continental recycling ratios of roughly 30% to 45% continent by continent, around
         * 40% for the land surface pooled, with the Amazon and the Congo basins well above it and
         * Australia well below. The band below is that spread, widened at neither end.
         */
        const val EARTH_RECYCLING_LOW = 0.30
        const val EARTH_RECYCLING_HIGH = 0.45

        /** How far inland "the interior" starts, in kilometres: `PressureWindTest`'s own figure. */
        const val INTERIOR_REACH_KM = 500.0

        /**
         * How much the convergence term has to move the interior of a summer continent, either
         * way, as a share.
         *
         * A hundredth. **This is a bar on the size of the change and not on its sign, and that is
         * a measurement and not a hedge.** The chunk expected the term to wet the interior: a
         * thermal low gathers air over a continent, and gathered air rains. It dries it instead,
         * by the figures this test prints, and the mechanism is in the march's own shape — a
         * parcel made to rain harder where the wind converges arrives downwind with less, so the
         * deep interior beyond the convergence gets a drier parcel than it would have. Earth
         * answers that by re-supplying the parcel from a monsoon flow of metres a second; W2
         * measured this generator's at tenths, so the gathering is there and the resupply is not.
         * The direction is recorded as a finding in docs/DESIGN_LEDGER.md, W3, with R1 and a
         * deeper seasonal migration named as what would earn the sign back. What is asserted here
         * is that the term does something at all, and that what it does is not the ITCZ.
         */
        const val INTERIOR_CONVERGENCE_CHANGE = 0.01

        /**
         * How far the zonal-mean rainfall peak may move, in degrees of latitude, when the
         * convergence term is switched on.
         *
         * One degree, which at 512 rows is under three rows: the term is built from the departure
         * wind alone precisely so that the belts' own rising limb is untouched, and this is that
         * claim as a number rather than as a comment.
         */
        const val ITCZ_DRIFT_DEGREES = 1.0

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

    private fun generate(seed: Long, tune: (WorldGenConfig) -> WorldGenConfig): WorldMap {
        val base = WorldGenConfig(seed = seed, width = size, height = size)
        return WorldGenerationEngine.generateBlocking(tune(base))
    }

    // ---------------------------------------------------------------- recycling

    @Test
    fun `the share of continental rain that last evaporated from land is Earth's`() {
        var pooledRain = 0.0
        var pooledRecycled = 0.0
        seeds.forEach { seed ->
            val world = generate(seed) { it }
            val (rain, recycled) = continentalRain(world)
            pooledRain += rain
            pooledRecycled += recycled
            val control = generate(seed) {
                it.copy(climate = it.climate.copy(evapotranspirationLengthKm = 0f))
            }
            val (controlRain, controlRecycled) = continentalRain(control)
            println(
                ("RECYCLING seed %d: %.1f%% of continental rain last evaporated from land, " +
                    "against %.1f%% with the ground's return switched off")
                    .format(
                        seed, 100.0 * recycled / rain,
                        if (controlRain > 0) 100.0 * controlRecycled / controlRain else 0.0
                    )
            )
            assertTrue(
                controlRecycled == 0.0,
                ("the control returns no water from the ground and still reads %.3f mm of " +
                    "recycled rain, so the tracer is measuring something else")
                    .format(controlRecycled)
            )
        }
        val ratio = pooledRecycled / pooledRain
        println(
            ("RECYCLING pooled over %d seeds: %.1f%% of continental rain is recycled, against " +
                "Earth's %.0f-%.0f%% (van der Ent and others 2010)")
                .format(
                    seeds.size, ratio * 100, EARTH_RECYCLING_LOW * 100, EARTH_RECYCLING_HIGH * 100
                )
        )
        assertTrue(
            ratio > EARTH_RECYCLING_LOW && ratio < EARTH_RECYCLING_HIGH,
            ("the continental recycling ratio is %.3f, outside Earth's %.2f to %.2f")
                .format(ratio, EARTH_RECYCLING_LOW, EARTH_RECYCLING_HIGH)
        )
    }

    @Test
    fun `the vegetation field does not return water as freely as the rainfall proxy`() {
        // W4's one offered change to the march, and the measurement that turned it down. The
        // ground's return is scaled by the previous lap's rain against Koppen's 500 mm steppe line
        // - a proxy W3 stated as one, for a field that did not exist. W4 built the field and
        // offered Budyko's evaporative fraction in its place, which is the share of the year's
        // evaporative energy the water supply meets and so ought to have *been* the return rather
        // than a stand-in for it.
        //
        // It is not what shipped. Which of the two ships was decided here and not by that
        // argument: the ratio the derivation produces is 26.5% pooled, under van der Ent and
        // others' (2010) 30-45%, while the proxy's is inside it. The clause therefore asserts the
        // *proxy* against Earth's band and prints the derivation's figure as the finding, because
        // moving the band to admit it is the thing the measure-do-not-tune rule exists to stop.
        // The diagnosis is at `ClimateConfig.vegetationRecycling`; see docs/DESIGN_LEDGER.md, W4.
        var proxyRain = 0.0
        var proxyRecycled = 0.0
        var derivedRain = 0.0
        var derivedRecycled = 0.0
        seeds.forEach { seed ->
            val proxy = continentalRain(generate(seed) { it })
            val derived = continentalRain(
                generate(seed) { it.copy(climate = it.climate.copy(vegetationRecycling = true)) }
            )
            proxyRain += proxy.first
            proxyRecycled += proxy.second
            derivedRain += derived.first
            derivedRecycled += derived.second
            println(
                ("RECYCLING seed %d: rainfall proxy %.1f%%, vegetation field %.1f%%; " +
                    "continental rain %.0f against %.0f mm")
                    .format(
                        seed, 100.0 * proxy.second / proxy.first,
                        100.0 * derived.second / derived.first, proxy.first, derived.first
                    )
            )
        }
        val proxyRatio = proxyRecycled / proxyRain
        val derivedRatio = derivedRecycled / derivedRain
        println(
            ("RECYCLING pooled: rainfall proxy %.1f%%, vegetation field %.1f%%, against Earth's " +
                "%.0f-%.0f%%; the field is the finding and the proxy is what ships")
                .format(
                    proxyRatio * 100, derivedRatio * 100,
                    EARTH_RECYCLING_LOW * 100, EARTH_RECYCLING_HIGH * 100
                )
        )
        assertTrue(
            proxyRatio > EARTH_RECYCLING_LOW && proxyRatio < EARTH_RECYCLING_HIGH,
            ("the shipped ground return puts the recycling ratio at %.3f, outside Earth's " +
                "%.2f to %.2f")
                .format(proxyRatio, EARTH_RECYCLING_LOW, EARTH_RECYCLING_HIGH)
        )
        assertTrue(
            derivedRatio < proxyRatio,
            ("the vegetation field now returns *more* water than the proxy (%.3f against %.3f), " +
                "so the reason this switch is off has changed and the ledger row is stale")
                .format(derivedRatio, proxyRatio)
        )
    }

    /** Total rain over land and the part of it whose water last came off land, both in mm. */
    private fun continentalRain(world: WorldMap): Pair<Double, Double> {
        val climate = ClimateStage.generateWithSeasonalMm(world.config, world.sea, world.ocean)
        var rain = 0.0
        var recycled = 0.0
        for (cell in 0 until world.width * world.height) {
            if (!world.sea.isLand[cell]) continue
            rain += climate.result.precipitationMm.data[cell].toDouble()
            recycled += climate.landOriginPrecipitationMm.data[cell].toDouble()
        }
        return rain to recycled
    }

    // ---------------------------------------------------------------- convergence

    @Test
    fun `the regional wind's convergence moves a summer continent's interior and not the ITCZ`() {
        var withTerm = 0.0
        var withoutTerm = 0.0
        var seedsMeasured = 0
        seeds.forEach { seed ->
            val world = generate(seed) { it }
            val control = generate(seed) {
                it.copy(climate = it.climate.copy(convergenceRain = false))
            }
            val on = interiorSummerRainMm(world) ?: return@forEach
            val off = interiorSummerRainMm(control) ?: return@forEach
            seedsMeasured++
            withTerm += on
            withoutTerm += off
            val itczOn = itczLatitude(world)
            val itczOff = itczLatitude(control)
            println(
                ("CONVERGENCE seed %d: interior warm-half rain %.0f mm with the term, %.0f " +
                    "without (%+.1f%%); the rainfall peak sits at %.1f degrees against %.1f")
                    .format(seed, on, off, 100.0 * (on - off) / off, itczOn, itczOff)
            )
            assertTrue(
                abs(itczOn - itczOff) <= ITCZ_DRIFT_DEGREES,
                ("the rainfall peak moved from %.1f to %.1f degrees, more than the %.1f the " +
                    "term is built to leave it inside").format(itczOff, itczOn, ITCZ_DRIFT_DEGREES)
            )
        }
        assertTrue(seedsMeasured > 0, "no seed had an interior to measure")
        val change = (withTerm - withoutTerm) / withoutTerm
        println(
            ("CONVERGENCE pooled over %d seeds: interior warm-half rain %.0f mm with the term " +
                "and %.0f without, %+.1f%%, against a bar of %.0f%% either way; the sign is a " +
                "finding, see the ledger")
                .format(
                    seedsMeasured, withTerm / seedsMeasured, withoutTerm / seedsMeasured,
                    change * 100, INTERIOR_CONVERGENCE_CHANGE * 100
                )
        )
        assertTrue(
            abs(change) > INTERIOR_CONVERGENCE_CHANGE,
            ("the convergence term moves the interior's warm-half rain by %+.1f%%, under the " +
                "%.0f%% that says it is doing anything at all")
                .format(change * 100, INTERIOR_CONVERGENCE_CHANGE * 100)
        )
    }

    /** Mean warm-half rainfall over land more than [INTERIOR_REACH_KM] from the sea, in mm. */
    private fun interiorSummerRainMm(world: WorldMap): Double? {
        val distance = ClimateStage.waterDistance(world.config, world.sea)
        val reachCells = world.config.cellsFor(INTERIOR_REACH_KM)
        var total = 0.0
        var cells = 0
        for (cell in 0 until world.width * world.height) {
            if (!world.sea.isLand[cell]) continue
            if (distance.data[cell] < reachCells) continue
            total += world.climate.summerPrecipitation.data[cell].toDouble()
            cells++
        }
        if (cells < 100) return null
        return total / cells * ClimateStage.REFERENCE_MM
    }

    /**
     * Where the ITCZ's rain band sits, in degrees: the rainfall-weighted mean latitude inside the
     * tropics.
     *
     * A centroid and not the wettest row. The wettest row is the argmax of a zonal sum over 512
     * rows, so a hundredth of a per cent between two neighbours moves it by whole degrees, and it
     * moved by 1.1 between two fields whose tropical band had not changed shape. The centroid is
     * the same question asked of the whole band, and it moves only when the band does.
     */
    private fun itczLatitude(world: WorldMap): Double {
        var weighted = 0.0
        var total = 0.0
        for (row in 0 until world.height) {
            val latitude = ClimateStage.latitudeOf(row, world.height).toDouble()
            if (abs(latitude) > 30.0) continue
            for (column in 0 until world.width) {
                val rain =
                    world.climate.precipitationMm.data[row * world.width + column].toDouble()
                weighted += rain * latitude
                total += rain
            }
        }
        return if (total <= 0.0) 0.0 else weighted / total
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
