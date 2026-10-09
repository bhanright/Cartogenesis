package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.MoistureLedger
import com.cartogenesis.worldgen.pipeline.MoistureMarch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The year's rain and the water that makes it, held against Earth's, every figure summed by area
 * on the sphere and pooled over the standard seeds.
 *
 * Earth's budget is Trenberth and others' (2007): 372.8 thousand km³ of rain on the ocean and
 * 112.6 on the land, 40.0 returned by the rivers, so 412.8 evaporated from the ocean and 72.6 from
 * the land, over the ocean's 361.2 million km² and the land's 148.9. A pooled figure is held
 * within a quarter of Earth's either way: the spread of the budget's own published estimates,
 * which the paper sets beside each other (land rain from 99 to 115 thousand km³, ocean rain from
 * 324 to 458), is that wide. Where a figure stands outside it, the clause is recorded as a known
 * failure with the figure, so the miss is the world's and stays in view.
 *
 * Several figures are diagnosed and printed rather than held: how much water the atmosphere holds
 * and how long it keeps it, which the march's sinks decide and which the march was not fitted to,
 * and the shares of desert and ice, which other guards hold.
 *
 * At [SharedWorlds.DETAIL_ROWS] on the standard seeds; the application's 1,024 rows are in the deep
 * tier's clause on the rain's row steps, beside main's figures there.
 */
class RainAgainstEarthTest : BorrowsSharedWorlds() {

    private companion object {
        val seeds = SharedWorlds.STANDARD_SEEDS
        const val ROWS = SharedWorlds.DETAIL_ROWS

        /** Earth's ocean and land areas, km² (Earth's 510.1 million less its 148.9 of land). */
        const val OCEAN_KM2 = 361.2e6
        const val LAND_KM2 = 148.9e6

        /** Trenberth and others' (2007) fluxes, thousand km³ a year. */
        const val OCEAN_RAIN_KM3 = 372.8e3
        const val LAND_RAIN_KM3 = 112.6e3
        const val RUNOFF_KM3 = 40.0e3

        /** Millimeters of water in a cubic kilometer over a square kilometer. */
        const val MM_PER_KM = 1.0e6

        val EARTH_OCEAN_RAIN_MM = OCEAN_RAIN_KM3 / OCEAN_KM2 * MM_PER_KM
        val EARTH_LAND_RAIN_MM = LAND_RAIN_KM3 / LAND_KM2 * MM_PER_KM
        val EARTH_OCEAN_EVAPORATION_MM = (OCEAN_RAIN_KM3 + RUNOFF_KM3) / OCEAN_KM2 * MM_PER_KM
        val EARTH_LAND_RETURN_SHARE = (LAND_RAIN_KM3 - RUNOFF_KM3) / LAND_RAIN_KM3

        /** How far a pooled figure may stand from Earth's, as a factor either way. */
        const val EARTH_TOLERANCE = 1.25

        /**
         * Earth's atmosphere's water, 12.6 thousand km³ over 510.1 million km² (Trenberth and others
         * 2011, as van der Ent and Tuinenburg 2017 use it), mm; and its turnover, 8.9 days (van
         * der Ent and Tuinenburg 2017).
         */
        const val EARTH_COLUMN_WATER_MM = 12.6e3 / 510.1e6 * MM_PER_KM
        const val EARTH_RESIDENCE_DAYS = 8.9

        /**
         * How far from the equator the year's wettest band of ocean may sit, degrees: GPCP's is at
         * 7 N (Adler and others, GPCP version 2), and the thermal equator this model's seasons move
         * by is ten degrees; fifteen is the two with room for a world whose continents pull it.
         */
        const val ITCZ_FROM_EQUATOR_DEGREES = 15.0
        const val ZONAL_BAND_DEGREES = 2
        const val TROPICS_SEARCH_DEGREES = 30
    }

    /** One world's figures, summed by area. */
    private class Figures {
        var landArea = 0.0
        var landRain = 0.0
        var landReturn = 0.0
        var seaArea = 0.0
        var seaRain = 0.0
        var seaEvaporation = 0.0
        var columnWater = 0.0
        var allArea = 0.0
        var allRain = 0.0
        var desert = 0.0
        var ice = 0.0

        fun add(other: Figures) {
            landArea += other.landArea; landRain += other.landRain; landReturn += other.landReturn
            seaArea += other.seaArea; seaRain += other.seaRain; seaEvaporation += other.seaEvaporation
            columnWater += other.columnWater; allArea += other.allArea; allRain += other.allRain
            desert += other.desert; ice += other.ice
        }
    }

    private fun figuresOf(world: WorldMap, ledger: MoistureLedger): Figures {
        val figures = Figures()
        val julyHalf = ledger.julyHalf!!
        val januaryHalf = ledger.januaryHalf!!
        val rain = world.climate.precipitationMm.data
        for (row in 0 until world.height) {
            val area = cos(ClimateStage.latitudeOf(row, world.height) * PI / 180.0)
            for (column in 0 until world.width) {
                val cell = row * world.width + column
                figures.allArea += area
                figures.allRain += area * rain[cell]
                figures.columnWater += area * (julyHalf.columnWater.data[cell] + januaryHalf.columnWater.data[cell]) * 0.5
                if (world.sea.isLand[cell]) {
                    figures.landArea += area
                    figures.landRain += area * rain[cell]
                    figures.landReturn += area * (julyHalf.groundReturn.data[cell] + januaryHalf.groundReturn.data[cell]) * 0.5
                    if (world.climate.biome[cell] == Biome.DESERT) figures.desert += area
                    if (world.climate.biome[cell] == Biome.ICE_SHEET) figures.ice += area
                } else {
                    figures.seaArea += area
                    figures.seaRain += area * rain[cell]
                    figures.seaEvaporation += area * (julyHalf.seaEvaporation.data[cell] + januaryHalf.seaEvaporation.data[cell]) * 0.5
                }
            }
        }
        return figures
    }

    /** Throws when [measured] stands more than [EARTH_TOLERANCE] from [earth] either way. */
    private fun nearEarth(name: String, measured: Double, earth: Double) {
        val factor = measured / earth
        println("EARTH %s: %.3f against Earth's %.3f, x%.2f".format(name, measured, earth, factor))
        if (abs(ln(factor)) > ln(EARTH_TOLERANCE)) {
            throw RecordedViolation(
                "%s is %.3f, x%.2f Earth's %.3f, outside a quarter either way".format(name, measured, factor, earth),
                "x%.2f".format(factor)
            )
        }
    }

    @Test
    fun `the rain and its sources stand near Earth's`() {
        val pooled = Figures()
        seeds.forEach { seed ->
            val world = SharedWorlds.world(WorldGenConfig.forRows(seed, ROWS))
            val figures = figuresOf(world, ClimateStage.moistureLedger(world.config, world.sea, world.ocean))
            pooled.add(figures)
            val days = figures.columnWater / figures.allRain * DAYS_PER_YEAR
            println(
                ("EARTH seed %d: land %.0f mm, sea %.0f, sea evaporation %.0f, land return %.2f of its rain; " +
                    "column water %.1f mm, turnover %.1f days; desert %.1f%%, ice %.1f%% of the land")
                    .format(seed, figures.landRain / figures.landArea, figures.seaRain / figures.seaArea,
                        figures.seaEvaporation / figures.seaArea, figures.landReturn / figures.landRain,
                        figures.columnWater / figures.allArea, days,
                        100 * figures.desert / figures.landArea, 100 * figures.ice / figures.landArea)
            )
        }
        val days = pooled.columnWater / pooled.allRain * DAYS_PER_YEAR
        println(
            "EARTH diagnosed, pooled: the atmosphere holds %.1f mm (Earth %.1f) and turns it over in %.1f days (Earth %.1f)"
                .format(pooled.columnWater / pooled.allArea, EARTH_COLUMN_WATER_MM, days, EARTH_RESIDENCE_DAYS)
        )
        // The land's return stands inside Earth's and is held there.
        nearEarth("land's return over its rain", pooled.landReturn / pooled.landRain, EARTH_LAND_RETURN_SHARE)
        // The sea evaporates less than Earth's, and the rain with it, for the causes docs/TODO.md's
        // "The march's misses against Earth, after C1b2" measures: the marine air stands at the
        // sea's own temperature and the wind at one speed.
        KnownFailures.expect("C1b2: the sea evaporates less than Earth's", "x0.75") {
            nearEarth("open-sea evaporation, mm", pooled.seaEvaporation / pooled.seaArea, EARTH_OCEAN_EVAPORATION_MM)
        }
        KnownFailures.expect("C1b2: the open sea rains less than Earth's", "x0.74") {
            nearEarth("open-sea rain, mm", pooled.seaRain / pooled.seaArea, EARTH_OCEAN_RAIN_MM)
        }
        KnownFailures.expect("C1b2: the land rains less than Earth's", "x0.55") {
            nearEarth("land rain, mm", pooled.landRain / pooled.landArea, EARTH_LAND_RAIN_MM)
        }
    }

    @Test
    fun `the year's wettest band of ocean sits near the equator`() {
        seeds.forEach { seed ->
            val world = SharedWorlds.world(WorldGenConfig.forRows(seed, ROWS))
            var wettest = 0.0
            var wettestLatitude = 0.0
            var band = -TROPICS_SEARCH_DEGREES
            val profile = StringBuilder()
            while (band < TROPICS_SEARCH_DEGREES) {
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
                val mean = if (cells > 0) total / cells else 0.0
                profile.append("%d:%.0f ".format(band, mean))
                if (mean > wettest) { wettest = mean; wettestLatitude = band + ZONAL_BAND_DEGREES / 2.0 }
                band += ZONAL_BAND_DEGREES
            }
            println("ITCZ seed %d: wettest ocean %.0f mm at %.0f degrees; %s".format(seed, wettest, wettestLatitude, profile))
            assertTrue(
                abs(wettestLatitude) <= ITCZ_FROM_EQUATOR_DEGREES,
                "seed $seed: the year's wettest ocean band sits at $wettestLatitude degrees"
            )
        }
    }

    private val DAYS_PER_YEAR = MoistureMarch.SECONDS_PER_YEAR / 86_400.0
}
