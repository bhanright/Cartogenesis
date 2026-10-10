package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.AtmosphereRemap
import com.cartogenesis.worldgen.pipeline.BoundaryLayer
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.SphericalGrid
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The boundary layer's wind and pressure against Earth's, on the application's own grid, 1,024 rows,
 * seeds 42, 969495 and 7 (the A1 chunks' Earth table).
 *
 * - **The wind's speed.** The mean speed at 10 m over the sea and over land against Archer and
 *   Jacobson's (2005) 6.64 and 3.28 m/s.
 * - **The trades and the westerlies.** On Earth the surface wind over the sea blows from the east at
 *   5 to 25 degrees and from the west at 35 to 60, the prevailing winds sailing was planned on; most
 *   of each band's sea must blow its way in each half-year.
 * - **The subtropical highs**, printed: the closed cells of high pressure over the sea at 15 to 45
 *   degrees, each with its central pressure, its prominence (the deepest closed isobar under it) and
 *   where it sits in its basin, west 0 to east 1. Nakamura and Miyasaka's (2004) July cells stand at
 *   about 35 N in the eastern portions of the basins over 1,020 hPa; a dry atmosphere was expected to
 *   make them weak (docs/DESIGN_LEDGER.md, A1-4), so this is a record and not a bar.
 */
class BoundaryLayerEarthTest : BorrowsSharedWorlds() {

    private companion object {
        val seeds = listOf(42L, 969495L, 7L)
        const val ROWS = 1024

        /**
         * How far the world's mean speeds may stand from Archer and Jacobson's, as a share: a
         * quarter. Their averages are of stations and buoys where they are, mostly on northern land
         * and coasts, and the speeds here are built from their ocean and land figures by way of the
         * belts' own mean square ([BoundaryLayer.TRANSIENT_WIND_MPS], [BoundaryLayer.LAND_ROUGHNESS_SHARE]),
         * so the clause holds the drag balance's land and eddy winds to them and not the derivation.
         */
        const val SPEED_SHARE = 0.25

        /** A prevailing wind is the band's majority: more than half of its sea. */
        const val PREVAILING_SHARE = 0.5
    }

    private fun world(seed: Long): WorldMap = SharedWorlds.world(WorldGenConfig.forRows(seed, ROWS))

    @Test
    fun `the surface wind's speed, the trades and the westerlies against Earth's`() {
        val complaints = ArrayList<String>()
        for (seed in seeds) {
            val world = world(seed)
            val atmosphere = ClimateStage.atmosphere(world.config, world.sea)
            for ((name, half) in listOf("July" to atmosphere.julyHalf, "January" to atmosphere.januaryHalf)) {
                val columns = world.width
                var seaSpeed = 0.0; var seaArea = 0.0; var landSpeed = 0.0; var landArea = 0.0
                var trades = 0.0; var tradeArea = 0.0; var westerlies = 0.0; var westerlyArea = 0.0
                for (cell in half.eastwardMps.indices) {
                    val latitude = abs(ClimateStage.latitudeOf(cell / columns, world.height))
                    val weight = cos(latitude * PI / 180)
                    val isLand = world.sea.isLand[cell]
                    val speed = BoundaryLayer.scalarWindAt10mMps(half.eastwardMps[cell], half.southwardMps[cell], isLand)
                    if (isLand) { landSpeed += weight * speed; landArea += weight; continue }
                    seaSpeed += weight * speed; seaArea += weight
                    if (latitude in 5f..25f) { tradeArea += weight; if (half.eastwardMps[cell] < 0f) trades += weight }
                    if (latitude in 35f..60f) { westerlyArea += weight; if (half.eastwardMps[cell] > 0f) westerlies += weight }
                }
                val sea = seaSpeed / seaArea
                val land = landSpeed / landArea
                val tradeShare = trades / tradeArea
                val westerlyShare = westerlies / westerlyArea
                println(("BOUNDARY LAYER EARTH seed %d %s: mean speed at 10 m over the sea %.2f m/s (Earth %.2f), over land %.2f (%.2f); " +
                    "the trades %.0f%% of the sea at 5 to 25 degrees, the westerlies %.0f%% at 35 to 60")
                    .format(seed, name, sea, BoundaryLayer.EARTH_OCEAN_WIND_AT_10_M_MPS, land, BoundaryLayer.EARTH_LAND_WIND_AT_10_M_MPS,
                        100 * tradeShare, 100 * westerlyShare))
                if (abs(sea / BoundaryLayer.EARTH_OCEAN_WIND_AT_10_M_MPS - 1) > SPEED_SHARE) complaints += "seed $seed $name sea speed %.2f".format(sea)
                if (abs(land / BoundaryLayer.EARTH_LAND_WIND_AT_10_M_MPS - 1) > SPEED_SHARE) complaints += "seed $seed $name land speed %.2f".format(land)
                if (tradeShare <= PREVAILING_SHARE) complaints += "seed $seed $name trades %.2f".format(tradeShare)
                if (westerlyShare <= PREVAILING_SHARE) complaints += "seed $seed $name westerlies %.2f".format(westerlyShare)
            }
        }
        assertTrue(complaints.isEmpty(), "the surface wind stands off Earth's: $complaints")
    }

    @Test
    fun `report the subtropical highs`() {
        for (seed in seeds) {
            val world = world(seed)
            val atmosphere = ClimateStage.atmosphere(world.config, world.sea)
            for ((name, half) in listOf("July" to atmosphere.julyHalf, "January" to atmosphere.januaryHalf)) {
                val pressure = FloatArray(world.width * world.height) { half.seaLevelPressurePa(it, world.width).toFloat() }
                val cells = SubtropicalHighs.find(world, pressure, atmosphere.remap)
                println("BOUNDARY LAYER HIGHS seed $seed $name: " + cells.ifEmpty { listOf("none") }.joinToString("; "))
            }
        }
    }
}

/**
 * Closed cells of high pressure over the sea at 15 to 45 degrees, on the atmosphere's grid: a local
 * maximum over water, at least [MIN_CENTRAL_PA], whose region above a drop of [MIN_PROMINENCE_PA]
 * holds nothing higher and does not run round the globe.
 */
internal object SubtropicalHighs {

    /** Nakamura and Miyasaka's (2004) shading of the July highs, over 1,020 hPa. */
    const val MIN_CENTRAL_PA = 102_000.0

    /** Half their 4 hPa isobar interval: a closed isobar under the center. */
    const val MIN_PROMINENCE_PA = 200.0

    private const val PROMINENCE_STEP_PA = 50.0
    private const val MAX_PROMINENCE_PA = 3_000.0

    fun find(world: WorldMap, pressurePa: FloatArray, remap: AtmosphereRemap): List<String> {
        val grid = remap.coarse
        val pressure = remap.areaMean(pressurePa)
        val landShare = remap.areaMean(FloatArray(world.sea.isLand.size) { if (world.sea.isLand[it]) 1f else 0f })
        val columns = grid.columns
        val found = ArrayList<String>()
        for (row in 1 until grid.rows - 1) {
            val latitude = grid.latitudeRadians[row] * 180 / PI
            if (abs(latitude) < 15 || abs(latitude) > 45) continue
            for (column in 0 until columns) {
                val cell = row * columns + column
                if (landShare[cell] >= 0.5 || pressure[cell] < MIN_CENTRAL_PA) continue
                var isMaximum = true
                for (rowStep in -1..1) for (columnStep in -1..1) {
                    if ((rowStep != 0 || columnStep != 0) && pressure[(row + rowStep) * columns + Math.floorMod(column + columnStep, columns)] > pressure[cell]) isMaximum = false
                }
                if (!isMaximum) continue
                var prominence = 0.0
                var drop = PROMINENCE_STEP_PA
                while (drop <= MAX_PROMINENCE_PA && closedAt(grid, pressure, cell, pressure[cell] - drop)) {
                    prominence = drop
                    drop += PROMINENCE_STEP_PA
                }
                if (prominence < MIN_PROMINENCE_PA) continue
                var west = 0
                var east = 0
                while (west < columns && landShare[row * columns + Math.floorMod(column - west - 1, columns)] < 0.5) west++
                while (east < columns && landShare[row * columns + Math.floorMod(column + east + 1, columns)] < 0.5) east++
                val position = if (west + east >= columns) Double.NaN else west.toDouble() / (west + east)
                found += "%.1f degrees, longitude %.0f, %.1f hPa, prominence %.1f hPa, basin position %.2f".format(
                    latitude, (column + 0.5) * 360.0 / columns, pressure[cell] / 100, prominence / 100, position)
            }
        }
        return found
    }

    private fun closedAt(grid: SphericalGrid, pressure: DoubleArray, start: Int, threshold: Double): Boolean {
        val columns = grid.columns
        val seen = BooleanArray(grid.cellCount)
        val stack = ArrayDeque<Int>()
        stack.addLast(start)
        seen[start] = true
        val touched = BooleanArray(columns)
        while (stack.isNotEmpty()) {
            val cell = stack.removeLast()
            if (pressure[cell] > pressure[start]) return false
            touched[cell % columns] = true
            val row = cell / columns
            val column = cell % columns
            for ((rowStep, columnStep) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                val nextRow = row + rowStep
                if (nextRow < 0 || nextRow >= grid.rows) continue
                val next = nextRow * columns + Math.floorMod(column + columnStep, columns)
                if (!seen[next] && pressure[next] >= threshold) {
                    seen[next] = true
                    stack.addLast(next)
                }
            }
        }
        return touched.count { it } < columns
    }
}
