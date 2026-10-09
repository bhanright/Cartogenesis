package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.ClimateStage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The rain draws no line along a row (conventions rule 13), on the application's own grid.
 *
 * Two latitude-only things used to draw the dry belts' edges straight along the rows: the march
 * walled each circulation belt off from the next, so the rain stepped at the belts' edges, and the
 * belts' factor on the rain was clamped flat across the subtropics, so the desert's edge sat where
 * the clamp began (docs/DESIGN_LEDGER.md, C1b). Water now crosses the belts' edges on the eddies
 * and the mean meridional wind, which falls to zero at every edge, and the factor reaches its
 * floor without a kink.
 *
 * Two measures, over land within 75 degrees, where a row is still a line on the ground:
 *
 * - **The rain's row steps.** The mean of `|ln(P below / P above)|` across each row boundary,
 *   over the land columns that cross it; the largest boundary against the median one is how
 *   much one row of the map stands out from the rest. In the annual field and in each season's.
 * - **The straightest dry edge.** The longest run of the desert's edge, and of the 250 mm
 *   isohyet's, that lies straight along a row, in kilometers on the ground, beside the longest
 *   that lies straight down a column, which the grid's other axis gives as a control.
 *
 * **The bar is main's, for now, and that is a stand-in.** Each pooled figure is held to what main
 * measured at 10a82a9d on the same seeds and grid, so the clause says the change made no line
 * longer. The bar the review asks for is Earth's: the straightest dry-region edge on Earth's own
 * Koppen raster (Peel, Finlayson and McMahon 2007, or Beck and others 2018), read at this grid's
 * cell size, which needs that raster in the repository (docs/TODO.md).
 */
class RainGeometryTest : BorrowsSharedWorlds() {

    private companion object {
        val seeds = listOf(42L, 969495L, 7L)
        const val ROWS = 1024

        /** Rows nearer the poles than this are left out: a row there is not a line on the ground. */
        const val MAX_LATITUDE = 75f

        /** The fewest land columns a row boundary must cross before its step counts. */
        const val MIN_COLUMNS = 20

        /** The 250 mm isohyet, the desert's own line in Koppen's simplest form. */
        const val DRY_MM = 250f

        /**
         * Main's figures at 10a82a9d, seeds 42, 969495 and 7 at 1,024 rows, measured by this file's
         * own arithmetic: each field's largest row step over its median, and the longest edge
         * straight along a row in kilometers.
         */
        val MAIN_ANNUAL_STEP = doubleArrayOf(3.73, 3.44, 3.99)
        val MAIN_SUMMER_STEP = doubleArrayOf(3.47, 4.00, 5.26)
        val MAIN_WINTER_STEP = doubleArrayOf(4.54, 3.52, 4.05)
        val MAIN_DESERT_ROW_EDGE_KM = doubleArrayOf(330.0, 257.0, 346.0)
        val MAIN_DRY_ROW_EDGE_KM = doubleArrayOf(487.0, 505.0, 470.0)
    }

    @Test
    fun `the rain draws no line along a row at 1,024 rows`() {
        val annual = DoubleArray(seeds.size)
        val summer = DoubleArray(seeds.size)
        val winter = DoubleArray(seeds.size)
        val desertEdge = DoubleArray(seeds.size)
        val dryEdge = DoubleArray(seeds.size)
        seeds.forEachIndexed { index, seed ->
            val world = SharedWorlds.world(WorldGenConfig.forRows(seed, ROWS))
            val generated = ClimateStage.generateWithSeasonalMm(world.config, world.sea, world.ocean)
            annual[index] = largestRowStep(world, generated.result.precipitationMm.data)
            summer[index] = largestRowStep(world, generated.julyHalfPrecipitationMm.data)
            winter[index] = largestRowStep(world, generated.januaryHalfPrecipitationMm.data)
            val desert = BooleanArray(world.width * world.height) { world.climate.biome[it] == Biome.DESERT }
            val dry = BooleanArray(world.width * world.height) {
                world.sea.isLand[it] && world.climate.precipitationMm.data[it] < DRY_MM
            }
            val (desertRow, desertColumn) = straightestEdgesKm(world, desert)
            val (dryRow, dryColumn) = straightestEdgesKm(world, dry)
            desertEdge[index] = desertRow
            dryEdge[index] = dryRow
            println(
                ("RULE13 seed %d: row steps annual %.2f, April-September %.2f, October-March %.2f (main %.2f, %.2f, %.2f); " +
                    "desert edge along a row %.0f km, down a column %.0f (main %.0f); under %.0f mm along a row %.0f km, " +
                    "down a column %.0f (main %.0f)")
                    .format(seed, annual[index], summer[index], winter[index], MAIN_ANNUAL_STEP[index],
                        MAIN_SUMMER_STEP[index], MAIN_WINTER_STEP[index], desertRow, desertColumn,
                        MAIN_DESERT_ROW_EDGE_KM[index], DRY_MM, dryRow, dryColumn, MAIN_DRY_ROW_EDGE_KM[index])
            )
        }
        fun noWorse(name: String, branch: DoubleArray, main: DoubleArray) {
            assertTrue(branch.average() <= main.average(), "$name: %.2f pooled against main's %.2f".format(branch.average(), main.average()))
        }
        noWorse("the annual rain's largest row step", annual, MAIN_ANNUAL_STEP)
        // Main's two figures were each hemisphere's own warm and cold half; the march now runs the
        // calendar's, which no longer meet as mirror images at the equator.
        noWorse("the April-September half's largest row step", summer, MAIN_SUMMER_STEP)
        noWorse("the October-March half's largest row step", winter, MAIN_WINTER_STEP)
        noWorse("the desert's straightest edge along a row", desertEdge, MAIN_DESERT_ROW_EDGE_KM)
        noWorse("the 250 mm isohyet's straightest edge along a row", dryEdge, MAIN_DRY_ROW_EDGE_KM)
    }


    /**
     * No front of the rain runs straight along a row for longer than the climate's own weather
     * would bend it ([RainFronts] says what a front is, and how its straight run is measured).
     *
     * **The bar** is a quarter of the weather's wavelength on the ground
     * ([ClimateStage.WEATHER_NOISE_WAVELENGTH_KM], 2,400 km, so 600 km): over a quarter of its
     * cycle the climate's weather noise has turned from its crest to its slope, and a front the
     * weather drew would have moved with it. One that holds a single row for longer is held there
     * by a field that knows only latitude. The longest run down a column is printed beside it, the
     * grid's other axis, as the control.
     *
     * **Shown failing on the tree before C1b2** (cb877879), which carried the zonal wind's sign
     * flipping on a row and the full transport speed either side: 1,147, 788 and 821 km along a
     * row on seeds 42, 969495 and 7, the first the straight lower edge of seed 42's wet band at 12
     * S, the second the front at 70 S. C1b2's continuous wind takes that seam out; what still holds
     * a row is the belts' factor on the rain, a function of latitude and season alone, which the
     * condensate's conversion now reads too (docs/TODO.md, "Fronts along the belts' factor").
     */
    @Test
    fun `the rain draws no straight front along a row at 1,024 rows`() {
        val bar = ClimateStage.WEATHER_NOISE_WAVELENGTH_KM / 4.0
        val over = ArrayList<String>()
        seeds.forEach { seed ->
            val world = SharedWorlds.world(WorldGenConfig.forRows(seed, ROWS))
            val found = RainFronts.longest(world, world.climate.precipitationMm.data)
            println(
                "FRONT seed %d: the longest straight front along a row %.0f km at %.1f degrees (column %d), down a column %.0f km; bar %.0f"
                    .format(seed, found.alongRowKm, found.rowLatitude, found.rowColumn, found.downColumnKm, bar)
            )
            if (found.alongRowKm > bar) over += "%d %.0f km at %.0f".format(seed, found.alongRowKm, found.rowLatitude)
        }
        KnownFailures.expect("C1b2: fronts along the belts' factor", "42 2225 km at 49; 969495 3208 km at -50; 7 2427 km at 50") {
            if (over.isNotEmpty()) {
                throw RecordedViolation(
                    "fronts hold a row for longer than a quarter of the weather's wavelength: " + over.joinToString("; "),
                    over.joinToString("; ")
                )
            }
        }
    }

    /** The largest row step of [rain] over land within [MAX_LATITUDE], over the median step. */
    private fun largestRowStep(world: WorldMap, rain: FloatArray): Double {
        val steps = ArrayList<Double>()
        for (row in 0 until world.height - 1) {
            if (abs(ClimateStage.latitudeOf(row, world.height)) > MAX_LATITUDE ||
                abs(ClimateStage.latitudeOf(row + 1, world.height)) > MAX_LATITUDE
            ) continue
            var total = 0.0
            var columns = 0
            for (column in 0 until world.width) {
                val above = row * world.width + column
                val below = above + world.width
                if (!world.sea.isLand[above] || !world.sea.isLand[below]) continue
                total += abs(ln((rain[below] + 1.0) / (rain[above] + 1.0)))
                columns++
            }
            if (columns >= MIN_COLUMNS) steps.add(total / columns)
        }
        val sorted = steps.sorted()
        return sorted.last() / sorted[sorted.size / 2]
    }

    /**
     * The longest edge of [mask] over land lying straight along a row, and straight down a column,
     * in kilometers on the ground, within [MAX_LATITUDE].
     */
    private fun straightestEdgesKm(world: WorldMap, mask: BooleanArray): Pair<Double, Double> {
        var alongRow = 0.0
        for (row in 0 until world.height - 1) {
            val latitude = ClimateStage.latitudeOf(row, world.height)
            if (abs(latitude) > MAX_LATITUDE) continue
            val cellKm = world.config.cellWidthKm * cos(latitude * PI / 180.0)
            var run = 0
            for (column in 0 until world.width) {
                val above = row * world.width + column
                val below = above + world.width
                if (world.sea.isLand[above] && world.sea.isLand[below] && mask[above] != mask[below]) {
                    run++
                    alongRow = maxOf(alongRow, run * cellKm)
                } else run = 0
            }
        }
        var downColumn = 0.0
        for (column in 0 until world.width) {
            var run = 0
            for (row in 0 until world.height) {
                if (abs(ClimateStage.latitudeOf(row, world.height)) > MAX_LATITUDE) { run = 0; continue }
                val here = row * world.width + column
                val east = row * world.width + (column + 1) % world.width
                if (world.sea.isLand[here] && world.sea.isLand[east] && mask[here] != mask[east]) {
                    run++
                    downColumn = maxOf(downColumn, run * world.config.cellHeightKm)
                } else run = 0
            }
        }
        return alongRow to downColumn
    }
}
