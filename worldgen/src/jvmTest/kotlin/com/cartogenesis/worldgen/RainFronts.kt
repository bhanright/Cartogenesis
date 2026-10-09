package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ClimateStage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The rain's fronts over land, and the longest of them that lies straight along a row of the
 * grid, or down a column, in kilometers on the ground.
 *
 * A cell of land stands on a front along a row where the rain at least doubles between the land
 * one blur's width north of it and the land one blur's width south of it
 * ([ClimateStage.RAIN_BLUR_SIGMA_KM], 76.5 km each way), the wetter side taking at least
 * [WETTEST_SIDE_MM]: a doubling across the width the climate's own weather is drawn at is a front a
 * reader sees, and the 250 mm isohyet is the desert's line, under which a doubling is a desert's own
 * grain. The front lies straight along the row for as many consecutive columns as the same holds
 * on that row; down a column the same, with the doubling taken between the land a blur's width east
 * and west. Rows nearer the poles than [MAX_LATITUDE] are left out, where a row is no longer a line
 * on the ground.
 */
internal object RainFronts {

    /** Rows nearer the poles than this are left out, as `RainGeometryTest` leaves them. */
    const val MAX_LATITUDE = 75f

    /** The least rain on a front's wetter side, millimeters a year: Koppen's simplest desert line. */
    const val WETTEST_SIDE_MM = 250f

    /** A front is at least a doubling. */
    val FRONT_RATIO = ln(2.0)

    /** The longest straight front along a row and down a column, kilometers, and where the first lies. */
    class Longest(val alongRowKm: Double, val downColumnKm: Double, val rowLatitude: Float, val rowColumn: Int)

    fun longest(world: WorldMap, rain: FloatArray): Longest {
        val across = world.width
        val down = world.height
        val land = world.sea.isLand
        val blurKm = ClimateStage.RAIN_BLUR_SIGMA_KM
        fun latitude(row: Int) = ClimateStage.latitudeOf(row, down)
        fun front(a: Int, b: Int, middle: Int) = land[a] && land[b] && land[middle] &&
            maxOf(rain[a], rain[b]) >= WETTEST_SIDE_MM && abs(ln((rain[b] + 1.0) / (rain[a] + 1.0))) >= FRONT_RATIO

        val rowsAcrossBlur = (blurKm / world.config.cellHeightKm).roundToInt().coerceAtLeast(1)
        var alongRow = 0.0
        var alongRowLatitude = 0f
        var alongRowColumn = -1
        for (row in rowsAcrossBlur until down - rowsAcrossBlur) {
            if (abs(latitude(row)) > MAX_LATITUDE) continue
            val cellKm = world.config.cellWidthKm * cos(latitude(row) * PI / 180.0)
            var run = 0
            for (column in 0 until across) {
                val middle = row * across + column
                if (front(middle - rowsAcrossBlur * across, middle + rowsAcrossBlur * across, middle)) {
                    run++
                    if (run * cellKm > alongRow) {
                        alongRow = run * cellKm
                        alongRowLatitude = latitude(row)
                        alongRowColumn = column
                    }
                } else run = 0
            }
        }
        var downColumn = 0.0
        for (column in 0 until across) {
            var run = 0
            for (row in 0 until down) {
                if (abs(latitude(row)) > MAX_LATITUDE) { run = 0; continue }
                val cellKm = world.config.cellWidthKm * cos(latitude(row) * PI / 180.0)
                val columnsAcrossBlur = (blurKm / cellKm).roundToInt().coerceAtLeast(1)
                val middle = row * across + column
                val west = row * across + (column - columnsAcrossBlur + across * 4) % across
                val east = row * across + (column + columnsAcrossBlur) % across
                if (front(west, east, middle)) {
                    run++
                    downColumn = maxOf(downColumn, run * world.config.cellHeightKm)
                } else run = 0
            }
        }
        return Longest(alongRow, downColumn, alongRowLatitude, alongRowColumn)
    }
}
