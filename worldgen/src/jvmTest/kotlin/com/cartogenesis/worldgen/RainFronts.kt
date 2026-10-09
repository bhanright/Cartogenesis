package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ClimateStage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The rain's fronts over land, and the longest of them that lies straight along a row or down a
 * column of the grid, in kilometers on the ground.
 *
 * A front is an isohyet where the rain changes fast: one of a ladder of rain levels a quarter
 * apart ([LEVEL_RATIO]), crossed between two cells of land whose rain differs by at least
 * [STEEPEST_DOUBLING_PER_KM] over the ground between them. It lies straight along a row for as many
 * consecutive columns as it crosses within one row either side of the row it is measured on, a
 * band 59 km across on the 1,024-row grid; down a column the band is the same width on the ground.
 * Rows nearer the poles than [MAX_LATITUDE] are left out, where a row is no longer a line on the
 * ground.
 */
internal object RainFronts {

    /** Rows nearer the poles than this are left out, as `RainGeometryTest` leaves them. */
    const val MAX_LATITUDE = 75f

    /**
     * The least rate at which the rain must change across a front, in natural logarithms per
     * kilometer: the steepest gradient a doubling of the rain makes once the climate's blur has
     * spread it, `ln 2 / (sigma sqrt(2 pi))` with sigma the blur's standard deviation on the ground
     * ([ClimateStage.RAIN_BLUR_SIGMA_KM], 76.5 km). A doubling across a line is a front a reader
     * sees; anything gentler than a blurred doubling is weather.
     */
    val STEEPEST_DOUBLING_PER_KM = ln(2.0) / (ClimateStage.RAIN_BLUR_SIGMA_KM * sqrt(2.0 * PI))

    /**
     * The ladder of rain levels the fronts are read on: from [LOWEST_LEVEL_MM], the 250 mm
     * isohyet `RainGeometryTest` reads as the desert's own line in Koppen's simplest form, a
     * quarter apart. Below it a doubling is a desert's own grain, 30 mm against 60, which the map
     * draws in one tint.
     */
    const val LEVEL_RATIO = 1.25
    const val LOWEST_LEVEL_MM = 250.0
    const val HIGHEST_LEVEL_MM = 8_000.0

    /** The longest straight run along a row and down a column, kilometers, with where the first lies. */
    class Longest(val alongRowKm: Double, val downColumnKm: Double, val rowLatitude: Float, val rowColumn: Int, val levelMm: Double, val downColumnAt: String)

    fun longest(world: WorldMap, rain: FloatArray): Longest {
        val across = world.width
        val down = world.height
        val land = world.sea.isLand
        val cellHeightKm = world.config.cellHeightKm
        fun latitude(row: Int) = ClimateStage.latitudeOf(row, down)
        fun step(a: Int, b: Int) = abs(ln((rain[b] + 1.0) / (rain[a] + 1.0)))
        val crossesRow = BooleanArray(across * down)
        val crossesColumn = BooleanArray(across * down)
        var alongRow = 0.0
        var alongRowLatitude = 0f
        var alongRowColumn = -1
        var alongRowLevel = 0.0
        var downColumn = 0.0
        var downColumnAt = ""
        var level = LOWEST_LEVEL_MM
        while (level <= HIGHEST_LEVEL_MM) {
            crossesRow.fill(false)
            crossesColumn.fill(false)
            for (row in 0 until down - 1) {
                if (abs(latitude(row)) > MAX_LATITUDE || abs(latitude(row + 1)) > MAX_LATITUDE) continue
                val cellKm = world.config.cellWidthKm * cos(latitude(row) * PI / 180.0)
                for (column in 0 until across) {
                    val here = row * across + column
                    val below = here + across
                    val east = row * across + (column + 1) % across
                    if (land[here] && land[below] && (rain[here] >= level) != (rain[below] >= level) &&
                        step(here, below) / cellHeightKm >= STEEPEST_DOUBLING_PER_KM
                    ) crossesRow[here] = true
                    if (land[here] && land[east] && (rain[here] >= level) != (rain[east] >= level) &&
                        step(here, east) / cellKm >= STEEPEST_DOUBLING_PER_KM
                    ) crossesColumn[here] = true
                }
            }
            for (row in 1 until down - 2) {
                val cellKm = world.config.cellWidthKm * cos(latitude(row) * PI / 180.0)
                var run = 0
                for (column in 0 until across) {
                    if (crossesRow[row * across + column]) {
                        run++
                        if (run * cellKm > alongRow) {
                            alongRow = run * cellKm
                            alongRowLatitude = latitude(row)
                            alongRowColumn = column
                            alongRowLevel = level
                        }
                    } else run = 0
                }
            }
            for (column in 0 until across) {
                var run = 0
                for (row in 0 until down) {
                    val cellKm = world.config.cellWidthKm * cos(latitude(row) * PI / 180.0)
                    val halfBand = ((cellHeightKm / cellKm - 1.0) / 2.0).roundToInt().coerceAtLeast(0)
                    if ((-halfBand..halfBand).any { crossesColumn[row * across + (column + it + across) % across] }) {
                        run++
                        if (run * cellHeightKm > downColumn) { downColumn = run * cellHeightKm; downColumnAt = "lat %.1f column %d level %.0f".format(latitude(row), column, level) }
                    } else run = 0
                }
            }
            level *= LEVEL_RATIO
        }
        return Longest(alongRow, downColumn, alongRowLatitude, alongRowColumn, alongRowLevel, downColumnAt)
    }
}
