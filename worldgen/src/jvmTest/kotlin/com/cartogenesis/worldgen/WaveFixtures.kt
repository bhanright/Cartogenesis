package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.pipeline.AtmosphereLevels
import com.cartogenesis.worldgen.pipeline.SphericalGrid
import com.cartogenesis.worldgen.pipeline.WaveResponse
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shapes and readings the stationary-wave benchmarks share: the published forcings on the sphere, and
 * the measurements their figures are compared by.
 */
internal object WaveFixtures {

    const val DEGREES = PI / 180.0
    const val SECONDS_PER_DAY = 86_400.0

    /** The unit vector of a point on the sphere. */
    fun unit(latitude: Double, longitude: Double) =
        doubleArrayOf(cos(latitude) * cos(longitude), cos(latitude) * sin(longitude), sin(latitude))

    /** The angle between two points on the sphere, radians. */
    fun arc(first: DoubleArray, second: DoubleArray): Double {
        val dot = (first[0] * second[0] + first[1] * second[1] + first[2] * second[2]).coerceIn(-1.0, 1.0)
        return acos(dot)
    }

    /**
     * Hoskins and Karoly's (1981) shape: `cos^2(pi r / 2)` inside an ellipse on the plane tangent at
     * ([centerLatitude], [centerLongitude]), `r` the distance in units of the semi-axes, zero outside.
     * The tangent plane is the azimuthal equidistant one: a point's arc from the center along its
     * bearing. Semi-axes in radians of arc, northward and eastward.
     */
    fun ellipse(grid: SphericalGrid, centerLatitude: Double, centerLongitude: Double, northSemiAxis: Double, eastSemiAxis: Double): DoubleArray {
        val center = unit(centerLatitude, centerLongitude)
        return DoubleArray(grid.cellCount) { cell ->
            val latitude = grid.latitudeRadians[cell / grid.columns]
            val longitude = (cell % grid.columns + 0.5) * grid.columnSpacingRadians
            val distance = arc(center, unit(latitude, longitude))
            // The bearing from the center, by the spherical law for the initial course.
            val bearing = atan2(
                sin(longitude - centerLongitude) * cos(latitude),
                cos(centerLatitude) * sin(latitude) - sin(centerLatitude) * cos(latitude) * cos(longitude - centerLongitude)
            )
            val east = distance * sin(bearing) / eastSemiAxis
            val north = distance * cos(bearing) / northSemiAxis
            val radius = sqrt(east * east + north * north)
            if (radius < 1.0) cos(PI * radius / 2).let { it * it } else 0.0
        }
    }

    /** The zonal mean of each row removed, so only waves 1 and up are left (Hoskins and Karoly's practice). */
    fun withoutZonalMean(grid: SphericalGrid, field: DoubleArray): DoubleArray {
        val result = field.copyOf()
        for (row in 0 until grid.rows) {
            var sum = 0.0
            for (column in 0 until grid.columns) sum += field[row * grid.columns + column]
            val mean = sum / grid.columns
            for (column in 0 until grid.columns) result[row * grid.columns + column] -= mean
        }
        return result
    }

    /** An extremum of a field: where, how large, and its sign. */
    data class Extremum(val latitude: Double, val longitude: Double, val value: Double)

    /**
     * The local extrema of [field] (each cell against its eight neighbors, wrapping east-west) whose
     * size is at least [shareOfLargest] of the field's largest, between [southLatitude] and
     * [northLatitude].
     */
    fun extrema(grid: SphericalGrid, field: DoubleArray, shareOfLargest: Double, southLatitude: Double, northLatitude: Double): List<Extremum> {
        val columns = grid.columns
        var largest = 0.0
        for (cell in field.indices) {
            val latitude = grid.latitudeRadians[cell / columns]
            if (latitude in southLatitude..northLatitude) largest = maxOf(largest, abs(field[cell]))
        }
        val found = mutableListOf<Extremum>()
        for (row in 1 until grid.rows - 1) {
            val latitude = grid.latitudeRadians[row]
            if (latitude < southLatitude || latitude > northLatitude) continue
            for (column in 0 until columns) {
                val value = field[row * columns + column]
                if (abs(value) < shareOfLargest * largest) continue
                var isExtremum = true
                loop@ for (rowStep in -1..1) for (columnStep in -1..1) {
                    if (rowStep == 0 && columnStep == 0) continue
                    val neighbor = field[(row + rowStep) * columns + Math.floorMod(column + columnStep, columns)]
                    if ((value > 0 && neighbor > value) || (value < 0 && neighbor < value)) { isExtremum = false; break@loop }
                }
                if (isExtremum) found += Extremum(latitude, (column + 0.5) * grid.columnSpacingRadians, value)
            }
        }
        return found
    }

    /**
     * The great circle through [source] that best fits [points], weighted by their size: its pole is
     * the unit vector at right angles to [source] that minimizes the weighted squared distance of the
     * points from the circle's plane. Returns the pole.
     */
    fun bestGreatCircle(source: DoubleArray, points: List<Extremum>): DoubleArray {
        // An orthonormal pair at right angles to the source.
        val helper = if (abs(source[2]) < 0.9) doubleArrayOf(0.0, 0.0, 1.0) else doubleArrayOf(1.0, 0.0, 0.0)
        val first = normalize(cross(source, helper))
        val second = cross(source, first)
        var aa = 0.0; var ab = 0.0; var bb = 0.0
        for (point in points) {
            val position = unit(point.latitude, point.longitude)
            val weight = abs(point.value)
            val alongFirst = dot(position, first)
            val alongSecond = dot(position, second)
            aa += weight * alongFirst * alongFirst
            ab += weight * alongFirst * alongSecond
            bb += weight * alongSecond * alongSecond
        }
        // The smaller eigenvector of [[aa, ab], [ab, bb]].
        val angle = 0.5 * atan2(2 * ab, aa - bb) + PI / 2
        return DoubleArray(3) { cos(angle) * first[it] + sin(angle) * second[it] }
    }

    /** Each point's arc from the plane of the great circle with [pole], radians. */
    fun offCircle(pole: DoubleArray, point: Extremum): Double = asin(abs(dot(pole, unit(point.latitude, point.longitude))).coerceAtMost(1.0))

    fun dot(first: DoubleArray, second: DoubleArray) = first[0] * second[0] + first[1] * second[1] + first[2] * second[2]

    fun cross(first: DoubleArray, second: DoubleArray) = doubleArrayOf(
        first[1] * second[2] - first[2] * second[1],
        first[2] * second[0] - first[0] * second[2],
        first[0] * second[1] - first[1] * second[0]
    )

    fun normalize(vector: DoubleArray): DoubleArray {
        val length = sqrt(dot(vector, vector))
        return DoubleArray(3) { vector[it] / length }
    }

    /** Hoskins and Karoly's deep profile, `sin(pi sigma)`. */
    fun deepProfile(sigma: Double) = sin(PI * sigma)

    /**
     * A deep profile whose maximum is at [peakSigma], `sin(pi sigma^b)` with `b = ln(1/2) / ln(peakSigma)`:
     * Rodwell and Hoskins' (2001) heating maximizing at 400 hPa.
     */
    fun peakedProfile(peakSigma: Double): (Double) -> Double {
        val exponent = kotlin.math.ln(0.5) / kotlin.math.ln(peakSigma)
        return { sigma -> sin(PI * Math.pow(sigma, exponent)) }
    }

    /**
     * One zonal wave's amplitude of [field] on every row: `|X_m| * 2 / N`, the wave's half range.
     */
    fun waveAmplitude(grid: SphericalGrid, field: DoubleArray, wave: Int): DoubleArray = DoubleArray(grid.rows) { row ->
        var real = 0.0
        var imaginary = 0.0
        for (column in 0 until grid.columns) {
            val angle = 2 * PI * wave * column / grid.columns
            real += field[row * grid.columns + column] * cos(angle)
            imaginary -= field[row * grid.columns + column] * sin(angle)
        }
        2 * sqrt(real * real + imaginary * imaginary) / grid.columns
    }

    /** The level of [levels] nearest [pressurePa]. */
    fun nearestLevel(levels: AtmosphereLevels, pressurePa: Double): Int =
        levels.levelPressurePa.indices.minByOrNull { abs(levels.levelPressurePa[it] - pressurePa) }!!

    /**
     * [response]'s vertical motion at [pressurePa], linear in pressure between the interfaces that hold
     * it, zero at the top and the terrain's at the ground; pascals a second at each cell.
     */
    fun verticalMotionAt(response: WaveResponse, pressurePa: Double): DoubleArray {
        val levels = response.levels
        val pressures = doubleArrayOf(0.0) + levels.interiorPressurePa + doubleArrayOf(levels.surfacePressurePa)
        val upper = pressures.indexOfLast { it <= pressurePa }.coerceAtMost(pressures.size - 2)
        val share = (pressurePa - pressures[upper]) / (pressures[upper + 1] - pressures[upper])
        fun at(index: Int, cell: Int) = when (index) {
            0 -> 0.0
            pressures.size - 1 -> response.surfaceVerticalMotion[cell]
            else -> response.verticalMotion[index - 1][cell]
        }
        return DoubleArray(response.grid.cellCount) { (1 - share) * at(upper, it) + share * at(upper + 1, it) }
    }
}
