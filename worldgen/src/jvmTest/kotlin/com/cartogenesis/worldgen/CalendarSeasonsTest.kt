package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.EnergyBalance
import com.cartogenesis.worldgen.pipeline.Season
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The climate's seasons are the calendar's: each half-year and each month is one moment of one
 * planet, the northern summer beside the southern winter (docs/DESIGN_LEDGER.md, A1-1).
 *
 * Three clauses. The **calendar**: in the half about July every band poleward of the thermal
 * equator's swing is in its summer north of the equator and its winter south of it, and the other
 * way about in the half about January, in all three of the energy balance's columns; shown failing
 * on each band's own warmer and cooler half, which is the both-summers-at-once climate the export
 * replaced. The **belts**: the march's winds ride one thermal equator, so the winter hemisphere's
 * trades cross the geographic equator toward the summer one. And **Koppen's months**: the warmest
 * and coldest month are still each band's own warmest and coldest window of its daily year, and
 * the calendar's months and halves the same fixed steps in every band; shown failing on the
 * calendar's months swapped by hemisphere, which lose the lag.
 */
class CalendarSeasonsTest : BorrowsSharedWorlds() {

    private companion object {
        /** The world the land profile is read from. */
        const val SEED = 42L

        /** Earth's obliquity, the energy balance's default tilt. */
        const val EARTH_OBLIQUITY_DEGREES = 23.44f

        /** A uniform land share for the Koppen months' fixed planet: Earth's, 148.9 of 510.1 million km². */
        const val EARTH_LAND_SHARE = 148.9f / 510.1f
    }

    /** Whether every band past [swingDegrees] is in summer in one hemisphere and winter in the other. */
    private fun oneMomentOfThePlanet(
        name: String,
        julyHalfC: FloatArray,
        januaryHalfC: FloatArray,
        swingDegrees: Float
    ): List<String> {
        val misses = ArrayList<String>()
        for (band in julyHalfC.indices) {
            val latitude = EnergyBalance.latitudeOfBand(band)
            if (abs(latitude) <= swingDegrees) continue
            val northernSummer = julyHalfC[band] > januaryHalfC[band]
            if (northernSummer != (latitude > 0f)) {
                misses += "%s at %+.1f: April-September %.2f C, October-March %.2f C"
                    .format(name, latitude, julyHalfC[band], januaryHalfC[band])
            }
        }
        return misses
    }

    @Test
    fun `each half-year is one moment of the planet, and each band's own warm half is not`() {
        val world = SharedWorlds.world(WorldGenConfig.forRows(SEED, SharedWorlds.COARSE_ROWS))
        val zonal = ClimateStage.zonalClimate(world.config, world.sea)
        val swing = world.config.climate.seasonalTiltDegrees
        val columns = listOf("land" to zonal.land, "marine air" to zonal.sea, "water" to zonal.water)
        val misses = columns.flatMap { (name, column) ->
            oneMomentOfThePlanet(name, column.julyHalfC, column.januaryHalfC, swing)
        }
        println("CALENDAR seed $SEED: ${misses.size} band-columns outside the calendar; ${misses.take(3)}")
        assertTrue(misses.isEmpty(), "the calendar's halves are not one moment of the planet: ${misses.take(5)}")

        // The control: each band's own warmer and cooler half, the window the climate took before,
        // is every hemisphere's summer at once.
        val ownHalves = columns.flatMap { (name, column) ->
            val warm = FloatArray(column.julyHalfC.size) { maxOf(column.julyHalfC[it], column.januaryHalfC[it]) }
            val cold = FloatArray(column.julyHalfC.size) { minOf(column.julyHalfC[it], column.januaryHalfC[it]) }
            oneMomentOfThePlanet(name, warm, cold, swing)
        }
        println("CALENDAR control: each band's own warm half misses ${ownHalves.size} band-columns")
        assertTrue(ownHalves.isNotEmpty(), "the guard does not see both hemispheres in summer at once")
    }

    @Test
    fun `the winter hemisphere's trades cross the equator to the summer one`() {
        val base = WorldGenConfig.forRows(SEED, SharedWorlds.COARSE_ROWS)
        val world = SharedWorlds.world(base.copy(climate = base.climate.copy(pressureWinds = false)))
        val cellsAcross = world.width
        val cellsDown = world.height
        fun equatorSouthwardMps(season: Season): Double {
            val wind = ClimateStage.seasonalSurfaceWindMps(world.config, world.sea, world.ocean, season)
            // The two rows either side of the equator.
            val rows = listOf(cellsDown / 2 - 1, cellsDown / 2)
            return rows.sumOf { row ->
                (0 until cellsAcross).sumOf { wind.southwardMps[row * cellsAcross + it].toDouble() }
            } / (rows.size * cellsAcross)
        }
        val july = equatorSouthwardMps(Season.JULY_HALF)
        val january = equatorSouthwardMps(Season.JANUARY_HALF)
        println("CALENDAR belts: the equator's wind toward the south %+.2f m/s April-September, %+.2f October-March".format(july, january))
        assertTrue(july < 0.0, "the April-September trades do not cross the equator northward: %+.2f".format(july))
        assertTrue(january > 0.0, "the October-March trades do not cross the equator southward: %+.2f".format(january))
    }

    /** A planet of Earth's land share in every band at Earth's tilt, which nothing here moves. */
    private fun koppenMonthsPlanet(recordedYear: (DoubleArray, DoubleArray, DoubleArray) -> Unit) =
        EnergyBalance.solve(
            FloatArray(EnergyBalance.BANDS) { EARTH_LAND_SHARE }, EARTH_OBLIQUITY_DEGREES,
            recordedYear = recordedYear
        )

    /** The mean of [year] over one band's [steps] steps from [first], round the year. */
    private fun windowMeanC(year: DoubleArray, band: Int, first: Int, steps: Int): Double =
        (0 until steps).sumOf { year[((first + it) % EnergyBalance.STEPS_PER_YEAR) * EnergyBalance.BANDS + band] } / steps

    /**
     * Where [warmestC] and [coldestC] are not the warmest and coldest 30-step window of [year]'s
     * own band, to [TOLERANCE_C]: the bands a Koppen month was taken from anything but the year.
     */
    private fun monthsNotTheYearsOwn(
        name: String,
        year: DoubleArray,
        warmestC: FloatArray,
        coldestC: FloatArray
    ): List<String> {
        val misses = ArrayList<String>()
        for (band in 0 until EnergyBalance.BANDS) {
            val windows = (0 until EnergyBalance.STEPS_PER_YEAR).map {
                windowMeanC(year, band, it, EnergyBalance.MONTH_STEPS)
            }
            val warmest = windows.max()
            val coldest = windows.min()
            if (abs(warmest - warmestC[band]) > TOLERANCE_C || abs(coldest - coldestC[band]) > TOLERANCE_C) {
                misses += "%s band %d: warmest %.3f against the year's %.3f, coldest %.3f against %.3f"
                    .format(name, band, warmestC[band], warmest, coldestC[band], coldest)
            }
        }
        return misses
    }

    @Test
    fun `Koppen's months are each band's own and the calendar's months are the calendar's`() {
        var years: Triple<DoubleArray, DoubleArray, DoubleArray>? = null
        val climate = koppenMonthsPlanet { land, marineAir, water -> years = Triple(land, marineAir, water) }
        val (land, marineAir, water) = years!!
        val columns = listOf(Triple("land", climate.land, land), Triple("marine air", climate.sea, marineAir),
            Triple("water", climate.water, water))

        // The Koppen months are the year's own warmest and coldest window, as before the export.
        val misses = columns.flatMap { (name, column, year) ->
            monthsNotTheYearsOwn(name, year, column.warmestMonthC, column.coldestMonthC)
        }
        assertTrue(misses.isEmpty(), "Koppen's months are not the year's own: ${misses.take(5)}")

        // The calendar's months and halves are the same fixed steps in every band.
        columns.forEach { (name, column, year) ->
            for (band in 0 until EnergyBalance.BANDS) {
                val july = windowMeanC(year, band, EnergyBalance.JULY_FIRST_STEP, EnergyBalance.MONTH_STEPS)
                val january = windowMeanC(year, band, EnergyBalance.JANUARY_FIRST_STEP, EnergyBalance.MONTH_STEPS)
                val julyHalf = windowMeanC(year, band, EnergyBalance.APRIL_FIRST_STEP, EnergyBalance.STEPS_PER_YEAR / 2)
                assertEquals(july, column.julyC[band].toDouble(), TOLERANCE_C, "$name band $band: July")
                assertEquals(january, column.januaryC[band].toDouble(), TOLERANCE_C, "$name band $band: January")
                assertEquals(julyHalf, column.julyHalfC[band].toDouble(), TOLERANCE_C, "$name band $band: April-September")
            }
        }

        // The control: the calendar's months swapped by hemisphere, July as the north's summer and
        // January as the south's, are not the Koppen months wherever the heat capacity's lag moves
        // the warmest month off the calendar's, so the clause above refuses them.
        val swapped = columns.flatMap { (name, column, year) ->
            val summer = FloatArray(EnergyBalance.BANDS) {
                if (EnergyBalance.latitudeOfBand(it) > 0f) column.julyC[it] else column.januaryC[it]
            }
            val winter = FloatArray(EnergyBalance.BANDS) {
                if (EnergyBalance.latitudeOfBand(it) > 0f) column.januaryC[it] else column.julyC[it]
            }
            monthsNotTheYearsOwn(name, year, summer, winter)
        }
        println("CALENDAR Koppen months: the year's own in every band; July and January swapped by hemisphere " +
            "miss ${swapped.size} band-columns, e.g. ${swapped.take(2)}")
        assertTrue(swapped.isNotEmpty(), "the guard cannot tell the calendar's months from each band's own")
    }

    /**
     * How near a window's mean in single precision must stand to the same window summed again in
     * double, degrees Celsius: a float holds a temperature of tens of degrees to a few millionths of
     * one, so a ten-thousandth is rounding with room and nothing physical.
     */
    private val TOLERANCE_C = 1.0e-4
}
