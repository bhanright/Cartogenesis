package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.MoistureLedger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * Where the land's rain falls, against Earth's, pooled over the standard seeds and summed by area
 * on the sphere: how much of the land stands far drier than its potential, how much water the land
 * imports, whether high tropical ground rains less than the lowland, how rare isolated wet spots
 * are, and how humid the boundary layer stands over the trades.
 *
 * Earth's figures, each from the source read:
 * - **Dryness**: the Climatic Research Unit's gridded rain and FAO-56 reference evapotranspiration,
 *   CRU TS 4.07 for 2001 to 2010 (Harris and others 2020), read cell by cell at half a degree
 *   without Antarctica: 21.2% of the land stands where the potential is over five times the rain.
 * - **The land's import**, its rain less its evaporation: Rodell and others (2015, J. Climate 28,
 *   section 5) 45,900 ± 4,400 km³ a year, and Trenberth and others' (2011) 40,000, which they set
 *   beside it; the model's land is scaled to Earth's 148.9 million km².
 * - **Rain by height**: CRU TS 4.07's rain over the land within 30 degrees, against the Climate
 *   Prediction Center's half-degree elevation (NOAA CPC, `elev.0.5-deg_tbase`): the land at or over
 *   1,500 m rains 0.56 of what the land under 200 m rains, 0.62 within 15 degrees. Anders and others
 *   (2006, TRMM over the Himalaya) find the same at ten kilometers: the rain favors low elevations
 *   and steep windward slopes, and the high plateau stays dry.
 * - **Isolated wet spots** (the measure of docs/DESIGN_LEDGER.md, W2e): land raining over 500 mm
 *   and over twice the mean of the land 160 to 320 km round it covers 0.67% of CRU's land.
 * - **The trades' boundary layer**: the R/V Meteor's surface air over EUREC4A, January and February
 *   2020 east of Barbados (Galewsky and Los 2026, section 3): 15.1 ± 0.83 g/kg at a relative
 *   humidity of 71.6 ± 4.6% over a 27.3 C sea.
 *
 * A pooled share is held within a quarter of Earth's, as `RainAgainstEarthTest` holds its budget;
 * the wet spots, a small count, within a factor of two, as `GeographyAuditTest` holds the desert's
 * bands; the import between Trenberth's figure and Rodell's upper bound; rain by height on its sign;
 * the boundary layer within two of the campaign's standard deviations.
 */
class RainPlacementEarthTest : BorrowsSharedWorlds() {

    private companion object {
        val seeds = SharedWorlds.STANDARD_SEEDS
        const val ROWS = SharedWorlds.DETAIL_ROWS

        const val EARTH_TOLERANCE = 1.25
        const val SPOT_FACTOR = 2.0

        /** CRU TS 4.07's land standing where the potential is over five times the rain, and that dryness. */
        const val EARTH_VERY_DRY_SHARE = 0.212
        const val VERY_DRY = 5.0

        /** The land's import, km³ a year: Trenberth's, Rodell's and Rodell's upper bound; Earth's land, km². */
        const val TRENBERTH_IMPORT_KM3 = 40_000.0
        const val RODELL_IMPORT_KM3 = 45_900.0
        const val RODELL_IMPORT_UNCERTAINTY_KM3 = 4_400.0
        const val EARTH_LAND_KM2 = 148.9e6

        /** Rain by height: the latitudes read, the lowland's top and the highland's floor, meters, and Earth's ratio. */
        const val HEIGHT_BAND_DEGREES = 30.0
        const val LOWLAND_TOP_M = 200f
        const val HIGHLAND_FLOOR_M = 1_500f
        const val EARTH_HIGHLAND_OVER_LOWLAND = 0.56

        /** The wet-spot measure: the rain a spot needs, its ring on the ground, and Earth's share of the land. */
        const val SPOT_LEAST_RAIN_MM = 500f
        const val SPOT_RING_INNER_KM = 160.0
        const val SPOT_RING_OUTER_KM = 320.0
        const val SPOT_OVER_RING = 2.0
        const val EARTH_SPOT_SHARE = 0.0067

        /**
         * The trades the boundary layer is read over: open sea 10 to 20 degrees from the equator
         * raining under 2 mm a day; and EUREC4A's air, its relative humidity and specific humidity
         * (kg/kg) with their standard deviations, over a sea 26.5 to 28 C, around the Meteor's 27.3.
         */
        const val TRADES_NEAREST_DEGREES = 10.0
        const val TRADES_FARTHEST_DEGREES = 20.0
        const val TRADES_RAIN_MM_PER_DAY = 2.0
        const val EUREC4A_HUMIDITY = 0.716
        const val EUREC4A_HUMIDITY_SPREAD = 0.046
        const val EUREC4A_SPECIFIC_HUMIDITY = 0.0151
        const val EUREC4A_SPECIFIC_HUMIDITY_SPREAD = 0.00083
        const val EUREC4A_SEA_COLDEST_C = 26.5
        const val EUREC4A_SEA_WARMEST_C = 28.0
        const val SPREADS = 2.0

        const val DAYS_PER_YEAR = 365.25

        /** The seed the grids are compared on, the application's rows, and how far apart the two may stand. */
        const val GRID_SEED = 42L
        const val APPLICATION_ROWS = 1024
        const val GRID_RAIN_SHARE = 0.05
        const val GRID_DESERT_SHARE = 0.02

        /** The least cosine a ring's row width is read at, so a polar row's ring stays a few cells wide. */
        const val MIN_RING_COS = 0.05

        val measured = HashMap<Long, Figures>()
    }

    private class Figures {
        var landArea = 0.0
        var veryDry = 0.0
        var importKm3 = 0.0
        var landKm2 = 0.0
        var lowArea = 0.0
        var lowRain = 0.0
        var highArea = 0.0
        var highRain = 0.0
        var spotArea = 0.0
        var tradeArea = 0.0
        var tradeHumidity = 0.0
        var warmTradeArea = 0.0
        var warmTradeSpecificHumidity = 0.0

        fun add(other: Figures) {
            landArea += other.landArea; veryDry += other.veryDry; importKm3 += other.importKm3; landKm2 += other.landKm2
            lowArea += other.lowArea; lowRain += other.lowRain; highArea += other.highArea; highRain += other.highRain
            spotArea += other.spotArea; tradeArea += other.tradeArea; tradeHumidity += other.tradeHumidity
            warmTradeArea += other.warmTradeArea; warmTradeSpecificHumidity += other.warmTradeSpecificHumidity
        }
    }

    private val pooled: Figures
        get() = synchronized(measured) {
            for (seed in seeds) measured.getOrPut(seed) {
                val world = SharedWorlds.world(WorldGenConfig.forRows(seed, ROWS))
                figuresOf(world, ClimateStage.moistureLedger(world.config, world.sea, world.ocean))
            }
            Figures().also { total -> measured.values.forEach(total::add) }
        }

    private fun figuresOf(world: WorldMap, ledger: MoistureLedger): Figures {
        val figures = Figures()
        val width = world.width
        val height = world.height
        val land = world.sea.isLand
        val rain = world.climate.precipitationMm.data
        val potential = world.climate.potentialEvapotranspirationMm.data
        val radiusKm = world.config.scale.radiusMeters / 1_000.0
        val halves = listOf(ledger.julyHalf!!, ledger.januaryHalf!!)
        val elevation = FloatArray(width * height) {
            if (land[it]) world.config.scale.metresAboveShoreline(world.sea.relativeElevation.data[it]).coerceAtLeast(0f) else 0f
        }
        val latitude = DoubleArray(height) { ClimateStage.latitudeOf(it, height).toDouble() }
        val cosRow = DoubleArray(height) { cos(latitude[it] * PI / 180.0) }
        for (row in 0 until height) {
            val area = cosRow[row]
            val cellKm2 = radiusKm * radiusKm * cosRow[row] * (2 * PI / width) * (PI / height)
            for (column in 0 until width) {
                val cell = row * width + column
                if (!land[cell]) {
                    tradeAir(figures, halves, cell, abs(latitude[row]), area)
                    continue
                }
                figures.landArea += area
                figures.landKm2 += cellKm2
                val returned = halves.sumOf { it.groundReturn.data[cell].toDouble() } / halves.size
                figures.importKm3 += (rain[cell] - returned) * 1e-6 * cellKm2
                if (rain[cell] <= 0f || potential[cell] / rain[cell] > VERY_DRY) figures.veryDry += area
                if (abs(latitude[row]) <= HEIGHT_BAND_DEGREES) {
                    if (elevation[cell] < LOWLAND_TOP_M) { figures.lowArea += area; figures.lowRain += area * rain[cell] }
                    if (elevation[cell] >= HIGHLAND_FLOOR_M) { figures.highArea += area; figures.highRain += area * rain[cell] }
                }
                if (rain[cell] >= SPOT_LEAST_RAIN_MM && isWetSpot(world, rain, cosRow, row, column)) figures.spotArea += area
            }
        }
        println("RAIN PLACEMENT seed %d: very dry %.3f of the land; import %.0f km3 (%.0f at Earth's land); highland over lowland %.2f; wet spots %.4f; trades' air %.3f, %.1f g/kg"
            .format(world.config.seed, figures.veryDry / figures.landArea, figures.importKm3, figures.importKm3 * EARTH_LAND_KM2 / figures.landKm2,
                (figures.highRain / figures.highArea) / (figures.lowRain / figures.lowArea), figures.spotArea / figures.landArea,
                figures.tradeHumidity / figures.tradeArea, 1000 * figures.warmTradeSpecificHumidity / figures.warmTradeArea))
        return figures
    }

    /** Adds the open sea's boundary layer to the trades' figures where [cell] is one of theirs. */
    private fun tradeAir(figures: Figures, halves: List<MoistureLedger.Cells>, cell: Int, latitude: Double, area: Double) {
        if (latitude < TRADES_NEAREST_DEGREES || latitude > TRADES_FARTHEST_DEGREES) return
        for (half in halves) {
            if (half.seaEvaporation.data[cell] <= 0f) continue
            if (half.rain.data[cell] / DAYS_PER_YEAR >= TRADES_RAIN_MM_PER_DAY) continue
            figures.tradeArea += area
            figures.tradeHumidity += area * half.boundaryLayerHumidity.data[cell]
            if (half.seaSurface.data[cell] in EUREC4A_SEA_COLDEST_C.toFloat()..EUREC4A_SEA_WARMEST_C.toFloat()) {
                figures.warmTradeArea += area
                figures.warmTradeSpecificHumidity += area * half.boundaryLayerSpecificHumidity.data[cell]
            }
        }
    }

    /** Whether the land cell at [row], [column] rains over twice the mean of the land in its ring. */
    private fun isWetSpot(world: WorldMap, rain: FloatArray, cosRow: DoubleArray, row: Int, column: Int): Boolean {
        val width = world.width
        val height = world.height
        val cellHeightKm = world.config.cellHeightKm
        val reachRows = (SPOT_RING_OUTER_KM / cellHeightKm).toInt() + 1
        var ringArea = 0.0
        var ringRain = 0.0
        for (offset in -reachRows..reachRows) {
            val other = row + offset
            if (other !in 0 until height) continue
            val widthKm = world.config.cellWidthKm * cosRow[other].coerceAtLeast(MIN_RING_COS)
            val reachColumns = minOf((SPOT_RING_OUTER_KM / widthKm).toInt() + 1, width / 2)
            for (across in -reachColumns..reachColumns) {
                val distance = sqrt((offset * cellHeightKm) * (offset * cellHeightKm) + (across * widthKm) * (across * widthKm))
                if (distance < SPOT_RING_INNER_KM || distance > SPOT_RING_OUTER_KM) continue
                val neighbor = other * width + Math.floorMod(column + across, width)
                if (!world.sea.isLand[neighbor]) continue
                ringArea += cosRow[other]
                ringRain += cosRow[other] * rain[neighbor]
            }
        }
        return ringArea > 0.0 && rain[row * width + column] > SPOT_OVER_RING * ringRain / ringArea
    }

    private fun nearEarth(name: String, measured: Double, earth: Double, factor: Double = EARTH_TOLERANCE) {
        val ratio = measured / earth
        println("EARTH %s: %.4f against Earth's %.4f, x%.2f".format(name, measured, earth, ratio))
        if (abs(ln(ratio)) > ln(factor)) {
            throw RecordedViolation("%s is %.4f, x%.2f Earth's %.4f".format(name, measured, ratio, earth), "x%.2f".format(ratio))
        }
    }

    @Test
    fun `as much of the land stands far drier than its potential as Earth's does`() {
        val figures = pooled
        // x2.63 on 6d5257e2; x1.74 at W2e, the subtropical highs too weak and the eddy winds still
        // carrying the interiors' water away (docs/DESIGN_LEDGER.md, W2e; docs/TODO.md).
        KnownFailures.expect("W2e: more of the land stands far drier than its potential than Earth's", "x1.74") {
            nearEarth("land over five times drier than its potential", figures.veryDry / figures.landArea, EARTH_VERY_DRY_SHARE)
        }
    }

    @Test
    fun `the land imports Earth's water`() {
        val figures = pooled
        val atEarthsLand = figures.importKm3 * EARTH_LAND_KM2 / figures.landKm2
        val most = RODELL_IMPORT_KM3 + RODELL_IMPORT_UNCERTAINTY_KM3
        println("EARTH the land's rain less its evaporation, at Earth's land area: %.0f km3 against %.0f to %.0f".format(atEarthsLand, TRENBERTH_IMPORT_KM3, most))
        if (atEarthsLand < TRENBERTH_IMPORT_KM3 || atEarthsLand > most) {
            throw RecordedViolation("the land imports %.0f km3 a year at Earth's land area".format(atEarthsLand), "%.0f".format(atEarthsLand / 1_000.0))
        }
    }

    @Test
    fun `high tropical ground rains less than the lowland`() {
        val figures = pooled
        val ratio = (figures.highRain / figures.highArea) / (figures.lowRain / figures.lowArea)
        println("EARTH land within %.0f degrees: the land over %.0f m rains %.2f of what the land under %.0f m rains; Earth %.2f"
            .format(HEIGHT_BAND_DEGREES, HIGHLAND_FLOOR_M, ratio, LOWLAND_TOP_M, EARTH_HIGHLAND_OVER_LOWLAND))
        if (ratio >= 1.0) throw RecordedViolation("high tropical ground rains %.2f times the lowland's".format(ratio), "%.2f".format(ratio))
    }

    @Test
    fun `isolated wet spots are as rare as Earth's`() {
        val figures = pooled
        // x1.92 on 6d5257e2, inside, though seeds 42 and 969495 stood at 2.6 and 2.4 times Earth's;
        // x0.28 at W2e, whose isolated high ground no longer raises the rain law and whose lifted
        // air rains only past its condensation level: fewer wet spots than Earth's isolated
        // mountains and volcanoes carry (docs/DESIGN_LEDGER.md, W2e; docs/TODO.md).
        KnownFailures.expect("W2e: the land carries fewer isolated wet spots than Earth's", "x0.28") {
            nearEarth("wet spots' share of the land", figures.spotArea / figures.landArea, EARTH_SPOT_SHARE, SPOT_FACTOR)
        }
    }

    @Test
    fun `the trades' boundary layer stands at EUREC4A's humidity`() {
        val figures = pooled
        val humidity = figures.tradeHumidity / figures.tradeArea
        val specific = figures.warmTradeSpecificHumidity / figures.warmTradeArea
        println("EARTH the trades' surface air: relative humidity %.3f against %.3f ± %.3f; over a sea of %.1f to %.1f C %.1f g/kg against %.1f ± %.1f"
            .format(humidity, EUREC4A_HUMIDITY, SPREADS * EUREC4A_HUMIDITY_SPREAD, EUREC4A_SEA_COLDEST_C, EUREC4A_SEA_WARMEST_C,
                1000 * specific, 1000 * EUREC4A_SPECIFIC_HUMIDITY, 1000 * SPREADS * EUREC4A_SPECIFIC_HUMIDITY_SPREAD))
        val misses = ArrayList<String>()
        if (abs(humidity - EUREC4A_HUMIDITY) > SPREADS * EUREC4A_HUMIDITY_SPREAD) misses += "relative humidity %.3f".format(humidity)
        if (abs(specific - EUREC4A_SPECIFIC_HUMIDITY) > SPREADS * EUREC4A_SPECIFIC_HUMIDITY_SPREAD) misses += "%.1f g/kg".format(1000 * specific)
        if (misses.isNotEmpty()) throw RecordedViolation("the trades' boundary layer stands at ${misses.joinToString()}", misses.joinToString())
    }

    /**
     * Seed 42 at the detail grid and at the application's 1,024 rows, built as the application
     * builds it: the land's rain within a twentieth and its desert within two points of the land,
     * where the climb read on each cell's own slope with no condensation level grew 11 to 15% between
     * the two (docs/DESIGN_LEDGER.md, W2e). Reported under `CROSS-GRID` (conventions rule 14).
     */
    @Test
    fun `the rain's placement is the same on the application's grid`() {
        val grids = listOf(WorldGenConfig.forRows(GRID_SEED, ROWS), WorldGenConfig(GRID_SEED, ROWS, ROWS).atResolution(2 * APPLICATION_ROWS, APPLICATION_ROWS))
        val figures = grids.map { config ->
            val world = SharedWorlds.world(config)
            var area = 0.0
            var rain = 0.0
            var desert = 0.0
            for (row in 0 until world.height) {
                val weight = cos(ClimateStage.latitudeOf(row, world.height) * PI / 180.0)
                for (column in 0 until world.width) {
                    val cell = row * world.width + column
                    if (!world.sea.isLand[cell]) continue
                    area += weight
                    rain += weight * world.climate.precipitationMm.data[cell]
                    if (world.climate.biome[cell] == Biome.DESERT) desert += weight
                }
            }
            rain / area to desert / area
        }
        val (coarseRain, coarseDesert) = figures[0]
        val (fineRain, fineDesert) = figures[1]
        val holds = abs(fineRain / coarseRain - 1.0) <= GRID_RAIN_SHARE && abs(fineDesert - coarseDesert) <= GRID_DESERT_SHARE
        CrossGridReport.report(
            "the land's rain and desert at %d and %d rows".format(ROWS, APPLICATION_ROWS), holds,
            "land rain %.0f and %.0f mm (x%.3f, bar %.2f either way), desert %.1f%% and %.1f%% (bar %.0f points)".format(
                coarseRain, fineRain, fineRain / coarseRain, GRID_RAIN_SHARE, 100 * coarseDesert, 100 * fineDesert, 100 * GRID_DESERT_SHARE)
        )
    }
}
