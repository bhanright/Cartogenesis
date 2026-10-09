package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.ClimateStage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A world of square cells is the same world as the 512 by 512 one, latitude band by latitude band.
 *
 * The same seed, made at 512 by 512 (cells 23.4 km across and 11.7 km down) and by
 * [WorldGenConfig.forRows] at 512 rows (1,024 by 512, cells 11.7 km square), is compared in twelve
 * bands of fifteen degrees on four things a reader sees at once: how much of the band is desert,
 * how much rain its land gets, how warm it is, and how much of it is ice. A setting that still
 * counts rows as if they were as tall as today's shows here: the belts' slant, when it was a
 * count of rows a cell, sloped twice as steeply on the ground on square cells, and the subtropical
 * deserts shrank by a third.
 *
 * The tolerance is not a choice. Each is the largest difference the same four seeds show between
 * 512 by 512 and 1,024 by 1,024, a grid whose cells have today's shape and half today's size, over
 * all twelve bands, rounded up at the second figure: the most a change of grid alone moves the
 * figure. A square grid may differ from 512 by 512 by as much as a finer grid does, and no more.
 * The measurement is in docs/DESIGN_LEDGER.md, Q1.
 *
 * The comparison reports rather than fails ([CrossGridReport]): the application makes one grid
 * (docs/DESIGN_LEDGER.md, G1), and the grid of 512 by 512 is not it. The control still fails,
 * because it is about the instrument.
 */
class GridShapeTest : BorrowsSharedWorlds() {

    /** One band's four figures. */
    private class Band(
        val desertShareOfBand: Double,
        val landRainMm: Double,
        val temperatureC: Double,
        val iceShareOfBand: Double
    )

    private fun bands(world: WorldMap): List<Band> {
        val cellsAcross = world.width
        val cellsDown = world.height
        val cells = IntArray(BAND_COUNT)
        val landCells = IntArray(BAND_COUNT)
        val desertCells = IntArray(BAND_COUNT)
        val iceCells = IntArray(BAND_COUNT)
        val landRain = DoubleArray(BAND_COUNT)
        val temperature = DoubleArray(BAND_COUNT)
        for (row in 0 until cellsDown) {
            val band = bandOf(ClimateStage.latitudeOf(row, cellsDown))
            for (column in 0 until cellsAcross) {
                val cell = row * cellsAcross + column
                cells[band]++
                temperature[band] += world.climate.temperature.data[cell]
                val biome = world.climate.biome[cell]
                if (biome == Biome.ICE_SHEET) iceCells[band]++
                if (world.sea.isLand[cell]) {
                    landCells[band]++
                    landRain[band] += world.climate.precipitationMm.data[cell]
                    if (biome == Biome.DESERT) desertCells[band]++
                }
            }
        }
        return (0 until BAND_COUNT).map { band ->
            Band(
                desertShareOfBand = desertCells[band].toDouble() / cells[band],
                landRainMm = if (landCells[band] > 0) landRain[band] / landCells[band] else 0.0,
                temperatureC = temperature[band] / cells[band],
                iceShareOfBand = iceCells[band].toDouble() / cells[band]
            )
        }
    }

    /** The band a latitude falls in, counted from the north pole. */
    private fun bandOf(latitudeDegrees: Float): Int =
        ((NORTH_POLE_DEGREES - latitudeDegrees) / BAND_DEGREES).toInt().coerceIn(0, BAND_COUNT - 1)

    private fun bandName(band: Int): String {
        val north = NORTH_POLE_DEGREES - band * BAND_DEGREES
        return "%.0f to %.0f degrees".format(north, north - BAND_DEGREES)
    }

    /** Where two worlds of one seed part by more than a change of grid parts them, one line a miss. */
    private class Misses {
        val desert = ArrayList<String>()
        val rain = ArrayList<String>()
        val temperature = ArrayList<String>()
        val ice = ArrayList<String>()
    }

    private fun compare(seed: Long, reference: WorldMap, other: WorldMap, misses: Misses) {
        val before = bands(reference)
        val after = bands(other)
        for (band in 0 until BAND_COUNT) {
            val was = before[band]
            val now = after[band]
            val where = "seed $seed ${bandName(band)}"
            println(
                "GRID SHAPE %s: desert %.4f against %.4f, land rain %.0f against %.0f mm, %.2f against %.2f C, ice %.4f against %.4f"
                    .format(
                        where, was.desertShareOfBand, now.desertShareOfBand, was.landRainMm, now.landRainMm,
                        was.temperatureC, now.temperatureC, was.iceShareOfBand, now.iceShareOfBand
                    )
            )
            if (abs(now.desertShareOfBand - was.desertShareOfBand) > DESERT_SHARE_SPREAD) {
                misses.desert += "$where desert %+.3f".format(now.desertShareOfBand - was.desertShareOfBand)
            }
            if (was.landRainMm >= RAIN_COMPARED_FROM_MM &&
                abs(now.landRainMm - was.landRainMm) / was.landRainMm > LAND_RAIN_RELATIVE_SPREAD
            ) {
                misses.rain += "$where land rain %+.0f%%".format(100 * (now.landRainMm / was.landRainMm - 1))
            }
            if (abs(now.temperatureC - was.temperatureC) > TEMPERATURE_SPREAD_C) {
                misses.temperature += "$where %+.2f C".format(now.temperatureC - was.temperatureC)
            }
            if (abs(now.iceShareOfBand - was.iceShareOfBand) > ICE_SHARE_SPREAD) {
                misses.ice += "$where ice %+.3f".format(now.iceShareOfBand - was.iceShareOfBand)
            }
        }
    }

    @Test
    fun `a world of square cells has the 512 by 512 world's deserts, rain, warmth and ice in every band`() {
        val misses = Misses()
        SEEDS.forEach { seed ->
            compare(
                seed,
                SharedWorlds.world(WorldGenConfig(seed = seed, width = 512, height = 512)),
                SharedWorlds.world(WorldGenConfig.forRows(seed, SQUARE_ROWS)),
                misses
            )
        }
        // Recorded as known failures from Q1 to L1 (docs/DESIGN_LEDGER.md, Q1, Q2 and L1).
        CrossGridReport.report(
            "a world of square cells has the 512 by 512 world's deserts, rain, warmth and ice in every band",
            misses.desert.isEmpty() && misses.rain.isEmpty() && misses.temperature.isEmpty() && misses.ice.isEmpty(),
            "past the spread: deserts ${misses.desert}; land rain ${misses.rain}; warmth ${misses.temperature}; ice ${misses.ice}"
        )
    }

    /**
     * The control: the belts' slant spent as the count of rows a cell it was before it was a slope
     * on the ground. On square cells 0.3 rows a cell is a ground slope of 0.3, twice the 0.15 the
     * setting names, which is what the tree before chunk 4b-1 did to every square grid; the
     * comparison must see the deserts and the rain move.
     */
    @Test
    fun `the comparison sees the belts' slant counted in rows`() {
        val squareCells = WorldGenConfig.forRows(CONTROL_SEED, SQUARE_ROWS)
        val slantInRows = squareCells.copy(
            climate = squareCells.climate.copy(
                meridionalWindShare = squareCells.climate.meridionalWindShare * ROWS_PER_CELL_ON_TODAYS_GRID
            )
        )
        val misses = Misses()
        compare(
            CONTROL_SEED,
            SharedWorlds.world(WorldGenConfig(seed = CONTROL_SEED, width = 512, height = 512)),
            WorldGenerationEngine.generateBlocking(slantInRows),
            misses
        )
        // Recorded at K2, where the doubled slant moved one band's desert past the spread and no
        // band's rain; passing at C1b, whose march carries water across the rows by the
        // slant's own flux, and armed (docs/DESIGN_LEDGER.md, K2 and C1b).
        assertTrue(
            misses.desert.isNotEmpty() && misses.rain.isNotEmpty(),
            "a slant twice as steep should move both past the spread: deserts ${misses.desert}; land rain ${misses.rain}"
        )
    }

    private companion object {
        /** The four standard seeds. */
        val SEEDS = listOf(7L, 42L, 1234L, 99L)

        /** The seed whose control moves both the deserts and the rain furthest. */
        const val CONTROL_SEED = 1234L

        /** The square grid compared: 1,024 by 512, the same rows as 512 by 512. */
        const val SQUARE_ROWS = 512

        /**
         * How many rows a cell's slope on the ground is on today's grid: a cell of 512 by 512 is
         * twice as wide as it is tall, so a ground slope of 0.15 is 0.3 rows a cell there.
         */
        const val ROWS_PER_CELL_ON_TODAYS_GRID = 2f

        const val NORTH_POLE_DEGREES = 90f
        const val BAND_DEGREES = 15f
        const val BAND_COUNT = 12

        /**
         * The largest difference in a band's desert share, 512 by 512 against 1,024 by 1,024:
         * 0.0469, seed 7 at -30 to -45 degrees (0.151 against 0.198).
         */
        const val DESERT_SHARE_SPREAD = 0.047

        /**
         * The largest relative difference in a band's mean land rain: 26.4%, seed 7 at -30 to -45
         * degrees (216 against 159 mm).
         */
        const val LAND_RAIN_RELATIVE_SPREAD = 0.27

        /**
         * Rain is compared as a ratio only where the band's land gets at least this much, in
         * millimetres a year: under it, which is the polar bands at 1 to 5 mm, a ratio compares a
         * few millimetres of nothing. A hundred is Koppen's hyper-arid line.
         */
        const val RAIN_COMPARED_FROM_MM = 100.0

        /** The largest difference in a band's mean temperature: 0.38 C, seed 42 at 15 to 0 degrees. */
        const val TEMPERATURE_SPREAD_C = 0.38

        /** The largest difference in a band's ice share: 0.0158, seed 42 at 75 to 60 degrees. */
        const val ICE_SHARE_SPREAD = 0.016
    }
}
