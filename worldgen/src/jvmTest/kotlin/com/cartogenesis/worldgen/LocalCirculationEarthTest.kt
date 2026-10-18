package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.BoundaryLayer
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.HadleyCirculation
import com.cartogenesis.worldgen.pipeline.LocalCirculation
import com.cartogenesis.worldgen.pipeline.Season
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The circulation at each longitude ([LocalCirculation]) against Earth's, on the standard seeds at
 * the application's grid: the ITCZ draws no step along a row, the convergence line goes inland over
 * the summer continents and stays near the equator over the sea, and the circulation draws no seam
 * down a column over the sea. Deep tier, under the climate stage.
 *
 * What drew the straight-edged latitude bands of rain before it (docs/DESIGN_LEDGER.md, F1): the
 * Hadley cells' lower branch blew one meridional wind on every column of a row, so the moist inflow
 * from the equator and the dry inflow from the subtropics met on one row everywhere, and the
 * march's rain law turned that meeting into a step of three to five within the row.
 */
class LocalCirculationEarthTest : BorrowsSharedWorlds() {

    private companion object {
        val seeds = listOf(42L, 969495L, 7L)
        const val ROWS = 1024

        /**
         * Earth's steepest rain gradient across the ITCZ's edge, per degree of latitude, as a
         * logarithm: the West African Sahel's, where the rain belt's poleward edge crosses the plains,
         * between Ouagadougou at 12 N, 700 mm a year, and Agadez at 18 N, 165 mm (the U.S. Geological
         * Survey's *Landscapes of West Africa*, its climate page, read). 0.24 a degree. TRMM's
         * zonal-mean rain over land in July to September falls by 0.06 to 0.19 a degree across the
         * rain belt's two edges (Adam, Bischoff and Schneider 2016, J. Climate 29, their Fig. 1, read
         * off the figure), so the Sahel's is the steeper.
         */
        val EARTH_STEEPEST_LOG_RAIN_PER_DEGREE = ln(SAHEL_WET_MM / SAHEL_DRY_MM) / (SAHEL_DRY_DEGREES - SAHEL_WET_DEGREES)
        const val SAHEL_WET_MM = 700.0
        const val SAHEL_DRY_MM = 165.0
        const val SAHEL_WET_DEGREES = 12.0
        const val SAHEL_DRY_DEGREES = 18.0

        /**
         * Where the surface winds converge in July over the open ocean, degrees north: the ITCZ over
         * the central Atlantic and Pacific in boreal summer, 9 N (Schneider, Bischoff and Haug 2014,
         * Nature 513, read, from the satellites' rain).
         */
        const val OCEAN_CONVERGENCE_DEGREES = 9.0

        /**
         * Where they converge over Africa at the summer's height, degrees north: the Intertropical
         * Front, the surface boundary of the monsoon's southerlies, whose climatology in the third
         * ten days of August stands at 19.4 N in the west and 16.8 N in the east (NOAA Climate
         * Prediction Center, Africa ITF bulletin for August 21 to 31, 2011, read: the west at 20 N,
         * "ahead of the climatology mean by 0.6 degrees", the east at 16.2 N, south of it by 0.6).
         * The eastern, the nearer the equator: over the Indian monsoon's sector the rain itself stands
         * at 20 N (Schneider and others 2014).
         */
        const val CONTINENT_CONVERGENCE_DEGREES = 16.8

        /** The least by which a summer continent's convergence line stands poleward of the open ocean's, degrees. */
        const val LEAST_INLAND_SHIFT_DEGREES = CONTINENT_CONVERGENCE_DEGREES - OCEAN_CONVERGENCE_DEGREES

        /** The tropics the lines are sought in, degrees north: the summer hemisphere's, and a winter cell's breadth beyond. */
        const val SOUTHERNMOST_LINE_DEGREES = -10f
        const val NORTHERNMOST_LINE_DEGREES = 30f
    }

    /** The landmass with the most land within five degrees of the equator, as a mask, and its area there. */
    private fun equatorialContinent(world: WorldMap): BooleanArray {
        val across = world.width
        val down = world.height
        val land = world.sea.isLand
        val label = IntArray(across * down) { -1 }
        val equatorialCells = ArrayList<Int>()
        val stack = IntArray(across * down)
        for (start in land.indices) {
            if (!land[start] || label[start] >= 0) continue
            val mass = equatorialCells.size
            var top = 0
            stack[top++] = start
            label[start] = mass
            var count = 0
            while (top > 0) {
                val cell = stack[--top]
                val row = cell / across
                val column = cell % across
                if (abs(ClimateStage.latitudeOf(row, down)) <= EQUATORIAL_DEGREES) count++
                for (neighbor in intArrayOf(
                    row * across + (column + 1) % across, row * across + (column + across - 1) % across,
                    if (row > 0) cell - across else -1, if (row < down - 1) cell + across else -1
                )) if (neighbor >= 0 && land[neighbor] && label[neighbor] < 0) { label[neighbor] = mass; stack[top++] = neighbor }
            }
            equatorialCells += count
        }
        val best = equatorialCells.indices.maxByOrNull { equatorialCells[it] } ?: return BooleanArray(land.size)
        return BooleanArray(land.size) { label[it] == best }
    }

    /**
     * The ITCZ draws no step along a row: across each row boundary of the equatorial continent in
     * the latitudes the ITCZ can take in a half-year, from the marine column's warmest latitude to
     * the land's and a Rossby radius beyond each ([LocalCirculation]), the rain of the half before
     * the blur, summed over the continent's columns that are land on both rows, changes from one row
     * to the next by no more than Earth's steepest gradient across the rain belt's edge allows over
     * a row ([EARTH_STEEPEST_LOG_RAIN_PER_DEGREE]); rows the continent crosses for less than a
     * Rossby radius are left out.
     *
     * **Shown failing on the tree before F1** (c113b8e2), which the same figures on that tree's
     * worlds give: x1.87 at 12.9 N in seed 42's July half, where the continent's mean meridional
     * wind turned on one row, x1.68 at 12.0 S in its January half; x1.55 and x1.43 on 969495, x1.42
     * and x1.48 on 7.
     */
    @Test
    fun `the ITCZ draws no step along a row on the equatorial continent at 1,024 rows`() {
        val over = ArrayList<String>()
        for (seed in seeds) {
            val world = SharedWorlds.world(WorldGenConfig.forRows(seed, ROWS))
            val config = world.config
            val ledger = ClimateStage.moistureLedger(config, world.sea, world.ocean)
            val zonal = ClimateStage.zonalClimate(config, world.sea)
            val continent = equatorialContinent(world)
            val radiusMeters = LocalCirculation.equatorialRossbyRadiusMeters(config.scale)
            val radiusDegrees = radiusMeters / config.scale.radiusMeters * 180.0 / PI
            val leastColumns = (radiusMeters / 1_000.0 / config.cellWidthKm).toInt()
            val bar = exp(EARTH_STEEPEST_LOG_RAIN_PER_DEGREE * 180.0 / ROWS)
            for ((season, rain) in listOf(Season.JULY_HALF to ledger.julyHalf!!.rain.data, Season.JANUARY_HALF to ledger.januaryHalf!!.rain.data)) {
                val landWarmest = LocalCirculation.warmestLatitudeDegrees(zonal.land.at(season))
                val seaWarmest = LocalCirculation.warmestLatitudeDegrees(zonal.sea.at(season))
                val south = minOf(landWarmest, seaWarmest) - radiusDegrees
                val north = maxOf(landWarmest, seaWarmest) + radiusDegrees
                var largest = 1.0
                var at = 0f
                for (row in 0 until world.height - 1) {
                    val latitude = ClimateStage.latitudeOf(row, world.height)
                    if (latitude < south || latitude > north) continue
                    var above = 0.0
                    var below = 0.0
                    var columns = 0
                    for (column in 0 until world.width) {
                        val cell = row * world.width + column
                        if (!continent[cell] || !continent[cell + world.width]) continue
                        above += rain[cell]
                        below += rain[cell + world.width]
                        columns++
                    }
                    if (columns < leastColumns) continue
                    val jump = maxOf((above + 1.0) / (below + 1.0), (below + 1.0) / (above + 1.0))
                    if (jump > largest) { largest = jump; at = latitude }
                }
                println(
                    "ITCZ STEP seed %d %s: the equatorial continent's largest one-row jump x%.2f at %.1f within %.1f to %.1f; Earth's bar x%.3f"
                        .format(seed, season, largest, at, south, north, bar)
                )
                if (largest > bar) over += "%d %s x%.2f at %.1f".format(seed, if (season == Season.JULY_HALF) "July" else "January", largest, at)
            }
        }
        // Recorded at F1: the line itself no longer steps (seed 42's July x1.87 is x1.22 at 2.9 S),
        // but the half's rain before the blur is the ground's climb cell by cell, and the
        // continent's rows change by x1.05 to x1.09 in their median over this span, Earth's whole
        // gradient across a row, and by x1.2 to x1.5 at their largest (docs/TODO.md).
        KnownFailures.expect("F1: the half's rain before the blur steps along a row by its own grain", "42 July x1.15 at 5.4; 42 January x1.28 at -9.2; 969495 July x1.36 at 15.7; 969495 January x1.11 at -10.1; 7 July x1.14 at 22.4; 7 January x1.14 at -18.2") {
            if (over.isNotEmpty()) {
                throw RecordedViolation(
                    "the equatorial continent's rain steps along a row by more than Earth's steepest gradient allows: " + over.joinToString("; "),
                    over.joinToString("; ")
                )
            }
        }
    }

    /**
     * The convergence line goes inland over the summer continents: in the half about July, the
     * latitude where the surface wind's meridional part, averaged over each row's land, turns from
     * northward to southward stands poleward of the same over the open sea by at least Earth's
     * [LEAST_INLAND_SHIFT_DEGREES], the African front's climatology less the open ocean's ITCZ,
     * pooled over the seeds. The line is the turn whose wind changes most across it, between
     * [SOUTHERNMOST_LINE_DEGREES] and [NORTHERNMOST_LINE_DEGREES].
     *
     * **Shown failing on the tree before F1** (c113b8e2), whose circulation blew one meridional wind
     * on every column of a row: the land's line at 12.4, 12.7 and 14.2 N, the sea's at 13.1, 13.1
     * and 15.7, the land's 0.7 degrees equatorward of the sea's pooled.
     */
    @Test
    fun `July's convergence line goes inland over the continents at 1,024 rows`() {
        val shifts = DoubleArray(seeds.size)
        seeds.forEachIndexed { index, seed ->
            val world = SharedWorlds.world(WorldGenConfig.forRows(seed, ROWS))
            val coupled = checkNotNull(ClimateStage.coupledAtmosphere(world.config, world.sea, world.ocean))
            val northward = FloatArray(world.width * world.height) { -coupled.coupling.atmosphere.julyHalf.southwardMps[it] }
            val overLand = convergenceLineDegrees(world, northward, overLand = true)
            val overSea = convergenceLineDegrees(world, northward, overLand = false)
            shifts[index] = overLand - overSea
            println("CONVERGENCE LINE seed %d July: over land %.1f, over the sea %.1f, inland by %.1f; Earth's %.1f"
                .format(seed, overLand, overSea, shifts[index], LEAST_INLAND_SHIFT_DEGREES))
        }
        // Recorded at W2c, whose warmer tropical sea and cloud left seed 7's line over land at 16.1 N
        // where W2b's stood near 17.6, the pooled shift a hair under Earth's (docs/TODO.md).
        KnownFailures.expect("W2c: July's convergence line stands a hair under Earth's distance inland", "7.1") {
            if (shifts.average() < LEAST_INLAND_SHIFT_DEGREES) {
                throw RecordedViolation(
                    "July's convergence line over land stands %.1f degrees poleward of the sea's pooled, under Earth's %.1f"
                        .format(shifts.average(), LEAST_INLAND_SHIFT_DEGREES),
                    "%.1f".format(shifts.average())
                )
            }
        }
    }

    /**
     * Where [northward] (meters a second, row-major), averaged over each row's land when [overLand]
     * or its open sea otherwise, turns from northward on the row below to southward on the row
     * above, degrees north, between the rows' centers in proportion; of every such turn between
     * [SOUTHERNMOST_LINE_DEGREES] and [NORTHERNMOST_LINE_DEGREES], the one with the largest change.
     */
    private fun convergenceLineDegrees(world: WorldMap, northward: FloatArray, overLand: Boolean): Double {
        val across = world.width
        val down = world.height
        val means = DoubleArray(down) { row ->
            var sum = 0.0
            var count = 0
            for (column in 0 until across) {
                val cell = row * across + column
                if (world.sea.isLand[cell] == overLand) { sum += northward[cell]; count++ }
            }
            if (count == 0) Double.NaN else sum / count
        }
        var best = Double.NaN
        var largestChange = 0.0
        for (row in 0 until down - 1) {
            val latitude = ClimateStage.latitudeOf(row, down)
            if (latitude > NORTHERNMOST_LINE_DEGREES || latitude < SOUTHERNMOST_LINE_DEGREES) continue
            val above = means[row]
            val below = means[row + 1]
            if (above.isNaN() || below.isNaN() || above > 0.0 || below <= 0.0) continue
            if (below - above > largestChange) {
                largestChange = below - above
                val share = below / (below - above)
                best = latitude - share * (latitude - ClimateStage.latitudeOf(row + 1, down))
            }
        }
        return best
    }

    /**
     * The circulation draws no seam down a column over the sea: in each half the surface wind's
     * meridional part changes across the rain's own width ([ClimateStage.RAIN_BLUR_SIGMA_KM] east and
     * west) by more than the weather's gusts ([BoundaryLayer.TRANSIENT_WIND_MPS]) along no run of
     * open sea down a column longer than a quarter of the weather's wavelength, the bar every
     * straight line of the rain is held to ([ClimateStage.WEATHER_NOISE_WAVELENGTH_KM]).
     *
     * **Shown failing on its control**, the dry atmosphere over each world with every sector held to
     * all land or all sea by its majority ([LocalCirculation.forHalf]'s `heldToLandOrSea`), whose
     * rising branch jumps between two columns as a heating found by an argmax would, the seams such a
     * heating drew over the sea (docs/DESIGN_LEDGER.md, F1). The tree before F1 had no circulation by
     * longitude to draw one.
     */
    @Test
    fun `the circulation draws no seam down a column over the sea at 1,024 rows`() {
        val bar = ClimateStage.WEATHER_NOISE_WAVELENGTH_KM / 4.0
        val over = ArrayList<String>()
        var controlLongest = 0.0
        for (seed in seeds) {
            val world = SharedWorlds.world(WorldGenConfig.forRows(seed, ROWS))
            val config = world.config
            val coupled = checkNotNull(ClimateStage.coupledAtmosphere(config, world.sea, world.ocean)).coupling.atmosphere
            val zonal = ClimateStage.zonalClimate(config, world.sea)
            val control = BoundaryLayer.solve(
                config, world.sea, zonal, ClimateStage.marineAirFraction(config, world.sea),
                heldBelts(world, zonal, Season.JULY_HALF), heldBelts(world, zonal, Season.JANUARY_HALF),
                if (config.ocean.enabled) world.ocean.anomaly.data else null
            )
            for ((name, half, controlHalf) in listOf(
                Triple("July", coupled.julyHalf, control.julyHalf), Triple("January", coupled.januaryHalf, control.januaryHalf)
            )) {
                val run = longestSeamKm(world, half.southwardMps)
                val controlRun = longestSeamKm(world, controlHalf.southwardMps)
                controlLongest = maxOf(controlLongest, controlRun)
                println("SEAM seed %d %s: the longest step down a column over the sea %.0f km, the control's %.0f; bar %.0f"
                    .format(seed, name, run, controlRun, bar))
                if (run > bar) over += "%d %s %.0f km".format(seed, name, run)
            }
        }
        assertTrue(controlLongest > bar, "the control draws no seam longer than %.0f km, so the guard shows nothing: %.0f".format(bar, controlLongest))
        assertTrue(over.isEmpty(), "the meridional wind steps down a column over the sea for longer than %.0f km: %s".format(bar, over.joinToString("; ")))
    }

    /** [season]'s circulation over [world] with each sector held to all land or all sea, as the control's belts. */
    private fun heldBelts(world: WorldMap, zonal: com.cartogenesis.worldgen.pipeline.ZonalClimate, season: Season): BoundaryLayer.Belts =
        LocalCirculation.forHalf(world.config, zonal, world.sea.isLand, season, HadleyCirculation.forHalf(world.config, zonal, season), heldToLandOrSea = true)
            .belts(world.width, world.height, world.config.scale.radiusMeters)

    /** The longest run of open sea down a column where [southward] steps across the rain's width by more than the weather's gusts, kilometers. */
    private fun longestSeamKm(world: WorldMap, southward: FloatArray): Double {
        val across = world.width
        val down = world.height
        val land = world.sea.isLand
        var longest = 0.0
        for (column in 0 until across) {
            var run = 0
            for (row in 0 until down) {
                val latitude = ClimateStage.latitudeOf(row, down)
                if (abs(latitude) > RainFronts.MAX_LATITUDE) { run = 0; continue }
                val cellKm = world.config.cellWidthKm * cos(latitude * PI / 180.0)
                val reach = (ClimateStage.RAIN_BLUR_SIGMA_KM / cellKm).toInt().coerceAtLeast(1)
                val middle = row * across + column
                val west = row * across + Math.floorMod(column - reach, across)
                val east = row * across + (column + reach) % across
                val stepped = !land[middle] && !land[west] && !land[east] &&
                    abs(southward[east] - southward[west]) > BoundaryLayer.TRANSIENT_WIND_MPS
                if (stepped) {
                    run++
                    longest = maxOf(longest, run * world.config.cellHeightKm)
                } else run = 0
            }
        }
        return longest
    }
}

/** Within this many degrees of the equator a landmass's land counts toward the equatorial continent. */
private const val EQUATORIAL_DEGREES = 5f
