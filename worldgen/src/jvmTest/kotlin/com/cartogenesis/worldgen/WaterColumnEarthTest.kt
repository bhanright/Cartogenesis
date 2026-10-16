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
import kotlin.test.Test

/**
 * The atmosphere's water against Earth's: how much the column holds, at what humidity the
 * convecting tropical sea rains, how much rain the tropics' and subtropics' land takes, and how
 * much of the land is desert, pooled over the standard seeds and summed by area on the sphere.
 *
 * Earth's figures, each from the source read:
 * - **Precipitable water**: 12.6 thousand km³ over 510.1 million km², 24.7 mm (Trenberth and
 *   others 2011, as van der Ent and Tuinenburg 2017 use it).
 * - **The convecting sea's column**: Bretherton, Peters and Back (2004), over the tropical oceans
 *   from 20 S to 20 N. Their monthly relation `P = exp(11.4 (r - 0.522))` rains 8 mm a day at
 *   `r` = 0.704, the days that rain over 8 mm stand at 0.75 to 0.8 (section 3a), and the
 *   saturated column of the regions of active convection is 72 mm (section 3b). A sea that rains
 *   over 8 mm a day in a half-year by its column's own law stands between the two humidities; one
 *   that rains it by other paths stands under the first. Its saturated column stands within two
 *   kelvin's worth of theirs (`SaturatedColumnTest`).
 * - **Land rain within 40 degrees**: GPCP version 2.3, 1979 to 2013, 2.81 mm a day over the land
 *   between 40 S and 40 N (Adler and others 2018, Atmosphere 9, 138, Table 4). Their land-only
 *   zonal means by ten-degree band are drawn, not tabulated, in what was read, so the bands are
 *   printed beside the one band held.
 * - **Desert**: Peel, Finlayson and McMahon (2007), BWh 14.2% of the land read in their text and
 *   BWk 4.9% from their table as `GeographyAuditTest` reads it, 19.1% in all, and its split by
 *   latitude band derived there (5.2%, 39.2% and 2.2% of the land within 15, 15 to 45 and past 45
 *   degrees).
 *
 * A pooled figure is held within a quarter of Earth's either way, as `RainAgainstEarthTest` holds
 * its budget, from the spread of the budget's own published estimates; a band's desert within a
 * factor of two, on the side that misplaces it, as `GeographyAuditTest` holds the bands' ratios.
 * The summer land rain's peak latitude is printed beside Earth's 5 to 12 degrees and not held: it
 * sits on the heat low through the local circulation (docs/TODO.md).
 */
class WaterColumnEarthTest : BorrowsSharedWorlds() {

    private companion object {
        val seeds = SharedWorlds.STANDARD_SEEDS
        const val ROWS = SharedWorlds.DETAIL_ROWS

        const val EARTH_COLUMN_WATER_MM = 12.6e3 / 510.1e6 * 1.0e6
        const val EARTH_TOLERANCE = 1.25

        /** The tropical sea Bretherton and others read, and the rain over which it convects, mm a day. */
        const val TROPICAL_SEA_DEGREES = 20.0
        const val CONVECTING_RAIN_MM_PER_DAY = 8.0
        const val HEAVY_RAIN_COLUMN_HUMIDITY_TOP = 0.8
        const val CONVECTING_SATURATED_COLUMN_MM = 72.0

        const val GPCP_LAND_WITHIN_40_MM = 2.81 * 365.25
        const val LAND_BAND_DEGREES = 40.0

        const val EARTH_DESERT_SHARE = 0.191
        val EARTH_BAND_DESERT_SHARE = doubleArrayOf(0.052, 0.392, 0.022)
        val BAND_EDGES_DEGREES = doubleArrayOf(15.0, 45.0)
        const val BAND_FACTOR = 2.0

        const val DAYS_PER_YEAR = 365.25

        val measured = HashMap<Long, Figures>()
    }

    private class Figures {
        var area = 0.0
        var columnWater = 0.0
        var landArea = 0.0
        var landColumnWater = 0.0
        var seaColumnWater = 0.0
        var convectingArea = 0.0
        var convectingHumidity = 0.0
        var convectingSaturated = 0.0
        var landWithinBand = 0.0
        var rainWithinBand = 0.0
        var desert = 0.0
        val bandLand = DoubleArray(3)
        val bandDesert = DoubleArray(3)
        val tenDegreeLand = DoubleArray(9)
        val tenDegreeRain = DoubleArray(9)

        fun add(other: Figures) {
            area += other.area; columnWater += other.columnWater; landArea += other.landArea
            landColumnWater += other.landColumnWater; seaColumnWater += other.seaColumnWater
            convectingArea += other.convectingArea; convectingHumidity += other.convectingHumidity
            convectingSaturated += other.convectingSaturated
            landWithinBand += other.landWithinBand; rainWithinBand += other.rainWithinBand; desert += other.desert
            for (band in 0 until 3) { bandLand[band] += other.bandLand[band]; bandDesert[band] += other.bandDesert[band] }
            for (band in 0 until 9) { tenDegreeLand[band] += other.tenDegreeLand[band]; tenDegreeRain[band] += other.tenDegreeRain[band] }
        }
    }

    /**
     * The seeds' figures, pooled, measured once for the class: each test runs on its own instance,
     * and the march is rerun on each world to read its column.
     */
    private val pooled: Figures
        get() = synchronized(measured) {
            for (seed in seeds) measured.getOrPut(seed) {
                val world = SharedWorlds.world(WorldGenConfig.forRows(seed, ROWS))
                figuresOf(world, ClimateStage.moistureLedger(world.config, world.sea, world.ocean)).also { figures ->
                    println("WATER COLUMN seed %d: %s".format(seed, summary(figures)))
                    println("WATER COLUMN seed %d: %s".format(seed, summerPeak(world)))
                }
            }
            Figures().also { total -> measured.values.forEach(total::add) }
        }

    private fun figuresOf(world: WorldMap, ledger: MoistureLedger): Figures {
        val figures = Figures()
        val halves = listOf(ledger.julyHalf!!, ledger.januaryHalf!!)
        val rain = world.climate.precipitationMm.data
        for (row in 0 until world.height) {
            val latitude = ClimateStage.latitudeOf(row, world.height).toDouble()
            val area = cos(latitude * PI / 180.0)
            val band = if (abs(latitude) <= BAND_EDGES_DEGREES[0]) 0 else if (abs(latitude) <= BAND_EDGES_DEGREES[1]) 1 else 2
            val tenDegree = (abs(latitude) / 10.0).toInt().coerceAtMost(8)
            for (column in 0 until world.width) {
                val cell = row * world.width + column
                val water = halves.sumOf { it.columnWater.data[cell].toDouble() } / halves.size
                figures.area += area
                figures.columnWater += area * water
                if (world.sea.isLand[cell]) {
                    figures.landArea += area
                    figures.landColumnWater += area * water
                    figures.bandLand[band] += area
                    figures.tenDegreeLand[tenDegree] += area
                    figures.tenDegreeRain[tenDegree] += area * rain[cell]
                    if (abs(latitude) <= LAND_BAND_DEGREES) {
                        figures.landWithinBand += area
                        figures.rainWithinBand += area * rain[cell]
                    }
                    if (world.climate.biome[cell] == Biome.DESERT) {
                        figures.desert += area
                        figures.bandDesert[band] += area
                    }
                } else {
                    figures.seaColumnWater += area * water
                    if (abs(latitude) > TROPICAL_SEA_DEGREES) continue
                    for (half in halves) {
                        if (half.seaEvaporation.data[cell] <= 0f) continue
                        if (half.rain.data[cell] <= CONVECTING_RAIN_MM_PER_DAY * DAYS_PER_YEAR) continue
                        val saturated = half.saturatedColumn.data[cell].toDouble()
                        figures.convectingArea += area
                        figures.convectingHumidity += area * half.columnWater.data[cell] / saturated
                        figures.convectingSaturated += area * saturated
                    }
                }
            }
        }
        return figures
    }

    private fun summary(figures: Figures): String {
        val seaArea = figures.area - figures.landArea
        val bands = (0 until 9).joinToString(" ") { band ->
            "%d-%d:%.0f".format(band * 10, band * 10 + 10, figures.tenDegreeRain[band] / figures.tenDegreeLand[band].coerceAtLeast(1e-12))
        }
        return ("column water %.1f mm (land %.1f, sea %.1f; Earth %.1f); the convecting tropical sea at humidity %.3f over a saturated column of %.1f mm; " +
            "land rain within %.0f degrees %.0f mm (GPCP %.0f), by ten degrees %s; desert %.1f%% of the land, by band %.1f / %.1f / %.1f%%")
            .format(figures.columnWater / figures.area, figures.landColumnWater / figures.landArea, figures.seaColumnWater / seaArea,
                EARTH_COLUMN_WATER_MM, figures.convectingHumidity / figures.convectingArea, figures.convectingSaturated / figures.convectingArea,
                LAND_BAND_DEGREES, figures.rainWithinBand / figures.landWithinBand, GPCP_LAND_WITHIN_40_MM, bands,
                100 * figures.desert / figures.landArea, 100 * figures.bandDesert[0] / figures.bandLand[0],
                100 * figures.bandDesert[1] / figures.bandLand[1], 100 * figures.bandDesert[2] / figures.bandLand[2])
    }

    /** Each hemisphere's summer half's zonal land rain in two-degree bands, and where it peaks. */
    private fun summerPeak(world: WorldMap): String {
        val parts = ArrayList<String>()
        for ((name, rain, north) in listOf(
            Triple("July north", world.climate.julyHalfPrecipitation.data, true),
            Triple("January south", world.climate.januaryHalfPrecipitation.data, false)
        )) {
            val sum = DoubleArray(45)
            val area = DoubleArray(45)
            for (row in 0 until world.height) {
                val latitude = ClimateStage.latitudeOf(row, world.height).toDouble()
                if (north != latitude > 0) continue
                val band = (abs(latitude) / 2).toInt().coerceAtMost(44)
                val weight = cos(latitude * PI / 180.0)
                for (column in 0 until world.width) {
                    val cell = row * world.width + column
                    if (!world.sea.isLand[cell]) continue
                    sum[band] += weight * rain[cell]
                    area[band] += weight
                }
            }
            val wettest = (0 until 30).filter { area[it] > 0 }.maxByOrNull { sum[it] / area[it] } ?: -1
            parts += "%s %d-%d degrees".format(name, wettest * 2, wettest * 2 + 2)
        }
        return "the summer half's land rain peaks at " + parts.joinToString(", ") + " (Earth 5 to 12)"
    }

    /** Throws when [measured] stands more than [factor] from [earth] either way. */
    private fun nearEarth(name: String, measured: Double, earth: Double, factor: Double = EARTH_TOLERANCE) {
        val ratio = measured / earth
        println("EARTH %s: %.3f against Earth's %.3f, x%.2f".format(name, measured, earth, ratio))
        if (abs(ln(ratio)) > ln(factor)) {
            throw RecordedViolation("%s is %.3f, x%.2f Earth's %.3f".format(name, measured, ratio, earth), "x%.2f".format(ratio))
        }
    }

    @Test
    fun `the atmosphere holds Earth's water`() {
        val figures = pooled
        nearEarth("column water, mm", figures.columnWater / figures.area, EARTH_COLUMN_WATER_MM)
    }

    @Test
    fun `the convecting tropical sea rains at Bretherton's column humidity, over Bretherton's saturated column`() {
        val figures = pooled
        val humidity = figures.convectingHumidity / figures.convectingArea
        val atLaw = COLUMN_HUMIDITY_AT_CONVECTING_RAIN
        println("EARTH the convecting tropical sea's column humidity: %.3f against %.3f to %.3f".format(humidity, atLaw, HEAVY_RAIN_COLUMN_HUMIDITY_TOP))
        if (humidity < atLaw || humidity > HEAVY_RAIN_COLUMN_HUMIDITY_TOP) {
            throw RecordedViolation("the convecting tropical sea stands at column humidity %.3f".format(humidity), "%.2f".format(humidity))
        }
        nearEarth("the convecting tropical sea's saturated column, mm", figures.convectingSaturated / figures.convectingArea,
            CONVECTING_SATURATED_COLUMN_MM, SaturatedColumnBand.FACTOR)
    }

    @Test
    fun `the land within forty degrees rains as GPCP's does`() {
        val figures = pooled
        nearEarth("land rain within 40 degrees, mm", figures.rainWithinBand / figures.landWithinBand, GPCP_LAND_WITHIN_40_MM)
    }

    @Test
    fun `desert covers Earth's share of the land, and of each band's`() {
        val figures = pooled
        nearEarth("desert share of the land", figures.desert / figures.landArea, EARTH_DESERT_SHARE)
        val misplaced = ArrayList<String>()
        for (band in 0 until 3) {
            val share = figures.bandDesert[band] / figures.bandLand[band]
            val earth = EARTH_BAND_DESERT_SHARE[band]
            println("EARTH desert share of band %d: %.3f against Earth's %.3f, x%.2f".format(band, share, earth, share / earth))
            // The two bands where a desert is out of place are held from above, the horse
            // latitudes' band from below.
            val wrong = if (band == 1) share < earth / BAND_FACTOR else share > earth * BAND_FACTOR
            if (wrong) misplaced += "band %d x%.2f".format(band, share / earth)
        }
        if (misplaced.isNotEmpty()) throw RecordedViolation("desert misplaced by band: $misplaced", misplaced.joinToString())
    }

    /** The column humidity at which Bretherton and others' monthly relation rains 8 mm a day. */
    private val COLUMN_HUMIDITY_AT_CONVECTING_RAIN =
        com.cartogenesis.worldgen.pipeline.MoistureMarch.COLUMN_RAIN_HUMIDITY_OFFSET +
            ln(CONVECTING_RAIN_MM_PER_DAY) / com.cartogenesis.worldgen.pipeline.MoistureMarch.COLUMN_RAIN_PER_HUMIDITY

    /** The band `SaturatedColumnTest` holds the column to: two kelvin of Clausius and Clapeyron at 300 K. */
    private object SaturatedColumnBand {
        val FACTOR = kotlin.math.exp(2.0 * 2.5e6 / (461.5 * 300.0 * 300.0))
    }
}
