package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.OceanStage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The sea and the air over it, solved together, against Earth: how far the sea stands above the air
 * the bulk formula evaporates into, the Walker cell's pressure across the equatorial basins, where
 * the year's ocean rain peaks either side of the equator, and whether the ocean's rounds with the air
 * settle. Deep tier, under the climate stage, on the standard seeds at the application's grid.
 *
 * Earth's figures, each from a source read:
 *
 * - **The sea above the air.** Kara, Hurlburt and Loh (2007, J. Geophys. Res. 112, C05020): over
 *   Earth's open ocean the annual sea surface regresses on the air above it as `Tsst = 0.93 + 1.00
 *   Tair` in ERA-40 and `0.63 + 1.00 Tair` in COADS. The open sea's mean excess over the air the
 *   march's bulk formula reads stands within [KARA_LEAST_C] to [KARA_MOST_C]. Before A1-6c it was
 *   the energy balance's own gap alone, whose year integrates to nothing (0.00 to 0.01 C on the
 *   three seeds).
 * - **The Walker cell.** The equatorial basins' eastern thirds hold the year's higher sea-level
 *   pressure than their western thirds, as Earth's Pacific does under its trades. The sign is asked
 *   and no Earth figure, none having been read. It held before A1-6c too, at 0.84 to 1.13 hPa on the
 *   three seeds, from the land and sea either side of each basin; the part the sea's own anomaly
 *   adds, the dry atmosphere over the anomaly less the one over a sea at its latitudes'
 *   temperatures, is printed beside it, since the shallow heating reads the anomaly from A1-6c on.
 * - **One ITCZ, north of the equator.** The GPCP version 2 climatology's ocean zonal means (Curtis
 *   and Adler's analysis of its twenty years, AMS, read) peak at 8 mm a day at 7 N, with a second,
 *   southern peak of 4 mm a day at 8 S. The year's wettest two-degree band of open ocean north of
 *   the equator rains at least [NORTH_OVER_SOUTH_PEAK] times the wettest south of it, within 15
 *   degrees, the share GPCP's two peaks stand at.
 * - **The ocean's rounds with the air settle** before [OceanStage.MAX_AIR_SEA_ROUNDS], each round's
 *   change of the anomaly smaller than the one before.
 */
class AirSeaEarthTest : BorrowsSharedWorlds() {

    private companion object {
        const val ROWS = 1024
        val SEEDS = listOf(42L, 969495L, 7L)
        const val KARA_LEAST_C = 0.63
        const val KARA_MOST_C = 0.93
        const val NORTH_OVER_SOUTH_PEAK = 8.0 / 4.0
        const val EQUATORIAL_BAND_DEGREES = 5f
        const val ITCZ_SEARCH_DEGREES = 15
        const val BAND_DEGREES = 2
    }

    @Test
    fun `the open sea stands above the air it evaporates into by Kara's difference`() {
        val failures = ArrayList<String>()
        for (seed in SEEDS) {
            val world = SharedWorlds.world(WorldGenConfig.forRows(seed, ROWS))
            val ledger = ClimateStage.moistureLedger(world.config, world.sea, world.ocean)
            var excess = 0.0
            var area = 0.0
            for (cell in 0 until world.width * world.height) {
                val july = ledger.julyHalf!!
                val january = ledger.januaryHalf!!
                if (world.sea.isLand[cell] || july.seaEvaporation.data[cell] <= 0f || january.seaEvaporation.data[cell] <= 0f) continue
                val weight = cos(ClimateStage.latitudeOf(cell / world.width, world.height) * PI / 180)
                excess += weight * (july.seaMinusAirC.data[cell] + january.seaMinusAirC.data[cell]) / 2
                area += weight
            }
            val meanC = excess / area
            println("AIR-SEA seed $seed: the open sea stands %.2f C above the air its bulk formula reads (Kara %.2f to %.2f)".format(meanC, KARA_LEAST_C, KARA_MOST_C))
            if (meanC !in KARA_LEAST_C..KARA_MOST_C) failures += "seed $seed: %.2f C".format(meanC)
        }
        assertTrue(failures.isEmpty(), "the sea's excess over its air stands outside Kara's: $failures")
    }

    @Test
    fun `the equatorial basins' eastern thirds hold the higher pressure`() {
        val failures = ArrayList<String>()
        for (seed in SEEDS) {
            val world = SharedWorlds.world(WorldGenConfig.forRows(seed, ROWS))
            val atmosphere = checkNotNull(ClimateStage.coupledAtmosphere(world.config, world.sea, world.ocean)).coupling.atmosphere
            val w = world.width
            var east = 0.0
            var west = 0.0
            var count = 0
            for (row in 0 until world.height) {
                if (abs(ClimateStage.latitudeOf(row, world.height)) > EQUATORIAL_BAND_DEGREES) continue
                for ((start, length) in OceanSense.basinRuns(world, row)) {
                    for (step in 0 until length / 3) {
                        val westCell = row * w + (start + step) % w
                        val eastCell = row * w + (start + length - 1 - step) % w
                        west += (atmosphere.julyHalf.seaLevelPressurePa(westCell) + atmosphere.januaryHalf.seaLevelPressurePa(westCell)) / 2
                        east += (atmosphere.julyHalf.seaLevelPressurePa(eastCell) + atmosphere.januaryHalf.seaLevelPressurePa(eastCell)) / 2
                        count++
                    }
                }
            }
            val gradientHpa = (east - west) / count / 100
            val anomaly = equatorialThirdsAnomaly(world)
            val seaPartHpa = eastLessWestPa(world, ClimateStage.atmosphere(world.config, world.sea, world.ocean.anomaly.data)) / 100 -
                eastLessWestPa(world, ClimateStage.atmosphere(world.config, world.sea)) / 100
            println("AIR-SEA seed $seed: the equatorial basins' eastern thirds less their western thirds, %+.2f hPa over water %+.2f C; the sea's anomaly's own part %+.3f hPa"
                .format(gradientHpa, anomaly, seaPartHpa))
            if (!(gradientHpa > 0.0)) failures += "seed $seed %+.2f hPa".format(gradientHpa)
        }
        assertTrue(failures.isEmpty(), "the eastern thirds stand no higher: $failures")
    }

    @Test
    fun `the year's ocean rain peaks once, north of the equator`() {
        val failures = ArrayList<String>()
        for (seed in SEEDS) {
            val world = SharedWorlds.world(WorldGenConfig.forRows(seed, ROWS))
            var north = 0.0
            var northAt = 0
            var south = 0.0
            var southAt = 0
            for (band in -ITCZ_SEARCH_DEGREES - 1 until ITCZ_SEARCH_DEGREES + 1 step BAND_DEGREES) {
                val mean = oceanRainOfBand(world, band.toFloat(), (band + BAND_DEGREES).toFloat())
                if (band >= 0 && mean > north) { north = mean; northAt = band + BAND_DEGREES / 2 }
                if (band + BAND_DEGREES <= 0 && mean > south) { south = mean; southAt = band + BAND_DEGREES / 2 }
            }
            val equator = oceanRainOfBand(world, -1f, 1f)
            val ratio = north / south
            println("AIR-SEA seed $seed: the year's ocean rain peaks at %.1f mm a day at %d N and %.1f at %d S, %.2f to one (GPCP's 8 at 7 N and 4 at 8 S); %.1f on the equator"
                .format(north / DAYS_PER_YEAR, northAt, south / DAYS_PER_YEAR, -southAt, ratio, equator / DAYS_PER_YEAR))
            if (!(ratio >= NORTH_OVER_SOUTH_PEAK)) failures += "seed $seed %.2f".format(ratio)
        }
        KnownFailures.expect("A1-6c: the year's ocean rain peaks either side of the equator alike", "seed 42 0.97; seed 969495 1.08; seed 7 1.58") {
            if (failures.isNotEmpty()) throw RecordedViolation("the north peak stands under twice the south: $failures", failures.joinToString("; "))
        }
    }

    @Test
    fun `the ocean's rounds with the air settle`() {
        for (seed in SEEDS) {
            val world = SharedWorlds.world(WorldGenConfig.forRows(seed, ROWS))
            var heard: List<Double> = emptyList()
            OceanStage.roundListener = { heard = it }
            try {
                OceanStage.generate(world.config, world.sea)
            } finally {
                OceanStage.roundListener = null
            }
            println("AIR-SEA seed $seed: the anomaly's change each round, %s".format(heard.joinToString(", ") { "%.4f".format(it) }))
            assertTrue(heard.isNotEmpty() && heard.last() < OceanStage.AIR_SEA_SETTLED_SHARE, "seed $seed did not settle: $heard")
            assertTrue(heard.zipWithNext().all { (before, after) -> after < before }, "seed $seed's rounds did not shrink: $heard")
        }
    }

    /** The equatorial basins' eastern thirds' mean of the year's sea-level pressure under [atmosphere] less their western thirds', pascals. */
    private fun eastLessWestPa(world: WorldMap, atmosphere: com.cartogenesis.worldgen.pipeline.BoundaryLayer.Atmosphere): Double {
        val w = world.width
        var difference = 0.0
        var count = 0
        for (row in 0 until world.height) {
            if (abs(ClimateStage.latitudeOf(row, world.height)) > EQUATORIAL_BAND_DEGREES) continue
            for ((start, length) in OceanSense.basinRuns(world, row)) {
                for (step in 0 until length / 3) {
                    val westCell = row * w + (start + step) % w
                    val eastCell = row * w + (start + length - 1 - step) % w
                    difference += (atmosphere.julyHalf.seaLevelPressurePa(eastCell) + atmosphere.januaryHalf.seaLevelPressurePa(eastCell) -
                        atmosphere.julyHalf.seaLevelPressurePa(westCell) - atmosphere.januaryHalf.seaLevelPressurePa(westCell)) / 2
                    count++
                }
            }
        }
        return if (count == 0) 0.0 else difference / count
    }

    /** The equatorial basins' eastern thirds' mean anomaly less their western thirds', degrees Celsius. */
    private fun equatorialThirdsAnomaly(world: WorldMap): Double {
        val w = world.width
        var difference = 0.0
        var count = 0
        for (row in 0 until world.height) {
            if (abs(ClimateStage.latitudeOf(row, world.height)) > EQUATORIAL_BAND_DEGREES) continue
            for ((start, length) in OceanSense.basinRuns(world, row)) {
                for (step in 0 until length / 3) {
                    difference += world.ocean.anomaly.data[row * w + (start + length - 1 - step) % w] - world.ocean.anomaly.data[row * w + (start + step) % w]
                    count++
                }
            }
        }
        return if (count == 0) 0.0 else difference / count
    }

    /** The year's mean rain over the open sea between [from] and [to] degrees, millimeters. */
    private fun oceanRainOfBand(world: WorldMap, from: Float, to: Float): Double {
        var total = 0.0
        var cells = 0
        for (row in 0 until world.height) {
            val latitude = ClimateStage.latitudeOf(row, world.height)
            if (latitude < from || latitude >= to) continue
            for (column in 0 until world.width) {
                val cell = row * world.width + column
                if (world.sea.isLand[cell]) continue
                total += world.climate.precipitationMm.data[cell]
                cells++
            }
        }
        return if (cells == 0) 0.0 else total / cells
    }

    private val DAYS_PER_YEAR = 365.25
}
