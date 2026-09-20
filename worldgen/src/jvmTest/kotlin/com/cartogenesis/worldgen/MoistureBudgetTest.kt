package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.ClimateStage
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
 * stopped the moisture that was left from raining out on the first slope.
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
         * How much more rain the interior of a summer continent has to take with the convergence
         * term than without it, as a share.
         *
         * A twentieth. The claim is that a thermal low rains in its own interior rather than only
         * on the coast it draws air across, and the size of it is set by how strong this
         * generator's regional wind is: W2 measured its monsoon flow in tenths of a metre a second
         * where Earth's is metres, and the convergence of a wind ten times too weak is ten times
         * too weak with it. Five per cent is a change a reader can see on the map against a march
         * whose own column-to-column noise the rain blur has already taken out; it is not Earth's
         * monsoon, and GEOGRAPHY.md's monsoon note says so.
         */
        const val INTERIOR_CONVERGENCE_GAIN = 0.05

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

        /** How far inland the coastal strip a marine inversion caps reaches, in kilometres. */
        const val COAST_STRIP_KM = 300.0

        /** How much of that strip has to be desert for the coast to be called a desert coast. */
        const val DESERT_SHARE_OF_STRIP = 0.5

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
    fun `the regional wind's convergence rains in a summer continent's interior`() {
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
        val gain = (withTerm - withoutTerm) / withoutTerm
        println(
            ("CONVERGENCE pooled over %d seeds: interior warm-half rain %.0f mm with the term " +
                "and %.0f without, %+.1f%%, against a bar of %+.0f%%")
                .format(
                    seedsMeasured, withTerm / seedsMeasured, withoutTerm / seedsMeasured,
                    gain * 100, INTERIOR_CONVERGENCE_GAIN * 100
                )
        )
        assertTrue(
            gain > INTERIOR_CONVERGENCE_GAIN,
            ("the convergence term adds %+.1f%% to the interior's warm-half rain, under the " +
                "%+.0f%% a reader could see").format(gain * 100, INTERIOR_CONVERGENCE_GAIN * 100)
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
    fun `a subtropical west coast over a cold current is a desert`() {
        var desertCoasts = 0
        var controlDesertCoasts = 0
        seeds.forEach { seed ->
            val world = generate(seed) { it }
            val control = generate(seed) {
                it.copy(climate = it.climate.copy(marineInversion = false))
            }
            val coast = coldestWestCoast(world) ?: run {
                println("INVERSION seed $seed: no subtropical west coast over cold water")
                return@forEach
            }
            val share = desertShareOfStrip(world, coast)
            val controlShare = desertShareOfStrip(control, coast)
            if (share >= DESERT_SHARE_OF_STRIP) desertCoasts++
            if (controlShare >= DESERT_SHARE_OF_STRIP) controlDesertCoasts++
            println(
                ("INVERSION seed %d: %d subtropical west-coast cells over cold water, coldest " +
                    "at %.1f degrees, column %d, %.2f C below its latitude's mean; the strip " +
                    "%.0f km behind them runs %.0f%% desert, against %.0f%% with the inversion off")
                    .format(
                        seed, coast.cells.size, coast.latitude, coast.column, -coast.anomalyC,
                        COAST_STRIP_KM, share * 100, controlShare * 100
                    )
            )
        }
        println(
            ("INVERSION: %d of %d standard seeds carry a desert on a subtropical west coast over " +
                "a cold current, against %d with the term off")
                .format(desertCoasts, seeds.size, controlDesertCoasts)
        )
        assertTrue(
            desertCoasts > 0,
            "no standard seed's subtropical west coast over a cold current reads as desert"
        )
        assertTrue(
            desertCoasts > controlDesertCoasts,
            ("the marine inversion makes no difference to how many coasts read desert: %d with " +
                "it and %d without").format(desertCoasts, controlDesertCoasts)
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
