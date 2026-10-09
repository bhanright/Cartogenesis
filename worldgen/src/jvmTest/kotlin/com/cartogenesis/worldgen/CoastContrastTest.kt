package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.Season
import kotlin.test.Test

/**
 * The temperature contrast across a coast in July and January, against Earth's, read the way
 * Earth's is: a month's sea-level temperature less its latitude's mean, the land's extreme behind
 * the coast against the sea's in front of it ([CoastContrast]), on the application's own grid.
 *
 * **Earth.** July, across the west coasts of the subtropical continents, 18 to 20 C (Nakamura and
 * Miyasaka 2004, AMS preprint J1.1, from the NCEP/NCAR reanalysis's zonally asymmetric 1000 hPa
 * temperature, their Fig. 2d): the cold sea off California and Morocco against the heat of the Great
 * Basin and the Sahara. The same figure's east coasts, read off its 2 K contours, stand at about
 * 4 to 8 C over East Asia and 2 to 4 over eastern North America. January, read off Seager and
 * others' (2002, Q. J. R. Meteorol. Soc. 128, Fig. 1) map of the surface air's departure from its
 * zonal mean, at 3 K contours: the west coasts at 40 to 60 N stand 15 to 24 C colder inland than
 * offshore (Europe and the Atlantic, western Canada and the Pacific), and the east coasts at 35 to
 * 50 N 15 to 27 C (eastern North America and the Gulf Stream, Manchuria and the Kuroshio).
 *
 * **What is held.** The July west-coast contrast, pooled over the seeds and over both hemispheres'
 * summers (the northern coasts in July, the southern in January), within Earth's 18 to 20 C
 * widened by the figure's own 2 K contour interval; and that it stands above the east coasts'
 * contrast, which is the shape of Earth's. Both are recorded as known failures (docs/TODO.md, A1-1):
 * the world's west coasts stand at a half of Earth's and no further from their sea than its east
 * coasts, because the sea off them is barely colder than its latitude. The January figures are
 * printed beside Seager's and not held: a monthly map read at 3 K contours is a reading, and the
 * clause it would make is A1-1's July one over again.
 */
class CoastContrastTest : BorrowsSharedWorlds() {

    private companion object {
        val seeds = listOf(42L, 969495L, 7L)
        const val ROWS = 1024

        /** Nakamura and Miyasaka's July west-coast contrast, C, less and plus their figure's 2 K contours. */
        const val EARTH_JULY_WEST_LOW_C = 18.0 - 2.0
        const val EARTH_JULY_WEST_HIGH_C = 20.0 + 2.0
        const val EARTH_JULY_WEST_MID_C = 19.0

        /** The subtropical west coasts, degrees from the equator: Fig. 2d's couplets lie 20 to 40 N. */
        const val SUBTROPICS_FROM = 20f
        const val SUBTROPICS_TO = 40f

        /** The east coasts read on the same figure, 25 to 45 N. */
        const val EAST_FROM = 25f
        const val EAST_TO = 45f
    }

    /** A pooled reading: the coast-weighted mean contrast. */
    private class Pool {
        var sum = 0.0
        var coasts = 0
        fun add(reading: CoastContrast.Reading) {
            if (reading.coasts == 0) return
            sum += reading.contrastC * reading.coasts
            coasts += reading.coasts
        }
        val meanC get() = if (coasts == 0) Double.NaN else sum / coasts
    }

    private fun summerReading(world: WorldMap, west: Boolean, from: Float, to: Float, north: Boolean): CoastContrast.Reading {
        val zonal = ClimateStage.zonalClimate(world.config, world.sea)
        val marine = ClimateStage.marineAirFraction(world.config, world.sea).data
        val season = if (north) Season.JULY else Season.JANUARY
        val month = if (north) world.climate.julyTemperature.data else world.climate.januaryTemperature.data
        return CoastContrast.measure(
            world, month, if (north) from else -to, if (north) to else -from, westCoast = west, landWarm = true,
            columnGapC = { zonal.landC(it, season) - zonal.seaC(it, season) }, marineShare = marine
        )
    }

    @Test
    fun `a summer's contrast across a west coast stands against Earth's`() {
        val west = Pool()
        val east = Pool()
        seeds.forEach { seed ->
            val world = SharedWorlds.world(WorldGenConfig.forRows(seed, ROWS))
            listOf(true, false).forEach { north ->
                val westReading = summerReading(world, true, SUBTROPICS_FROM, SUBTROPICS_TO, north)
                val eastReading = summerReading(world, false, EAST_FROM, EAST_TO, north)
                west.add(westReading)
                east.add(eastReading)
                println("CONTRAST seed $seed ${if (north) "July, north" else "January, south"}: west $westReading; east $eastReading")
            }
            val zonal = ClimateStage.zonalClimate(world.config, world.sea)
            val marine = ClimateStage.marineAirFraction(world.config, world.sea).data
            val january = world.climate.januaryTemperature.data
            val gap = { latitude: Float -> zonal.landC(latitude, Season.JANUARY) - zonal.seaC(latitude, Season.JANUARY) }
            println(
                "CONTRAST seed $seed January, north: west 40-60 N %s (Earth -15 to -24); east 35-50 N %s (Earth -15 to -27)".format(
                    CoastContrast.measure(world, january, 40f, 60f, true, false, gap, marine),
                    CoastContrast.measure(world, january, 35f, 50f, false, false, gap, marine)
                )
            )
        }
        println(
            "CONTRAST pooled summer: west coasts %+.1f C over %d (Earth 18 to 20), east %+.1f over %d (Earth 2 to 8)"
                .format(west.meanC, west.coasts, east.meanC, east.coasts)
        )
        val signature = "x%.2f".format(west.meanC / EARTH_JULY_WEST_MID_C)
        KnownFailures.expect("A1-1: a summer's contrast across a west coast is under Earth's", "x0.46") {
            if (west.meanC !in EARTH_JULY_WEST_LOW_C..EARTH_JULY_WEST_HIGH_C) {
                throw RecordedViolation(
                    "the summer's west-coast contrast is %+.1f C, outside Earth's %.0f to %.0f"
                        .format(west.meanC, EARTH_JULY_WEST_LOW_C, EARTH_JULY_WEST_HIGH_C),
                    signature
                )
            }
        }
        val shape = "west %+.0f east %+.0f".format(west.meanC, east.meanC)
        KnownFailures.expect("A1-1: a summer's west coast stands no further from its sea than an east coast", "west +9 east +10") {
            if (west.meanC <= east.meanC) {
                throw RecordedViolation(
                    "the west coasts' summer contrast %+.1f C does not exceed the east coasts' %+.1f, where Earth's is 18 to 20 against 2 to 8"
                        .format(west.meanC, east.meanC),
                    shape
                )
            }
        }
    }
}
