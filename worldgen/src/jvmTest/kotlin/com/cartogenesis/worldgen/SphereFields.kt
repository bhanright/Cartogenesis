package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.pipeline.SphericalGrid
import com.cartogenesis.worldgen.pipeline.SphericalOperators
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fields on the sphere whose derivatives are known exactly, for holding the spherical operators and
 * the remapping to the answer: Gaussian bumps of a given width on the ground, spherical harmonics,
 * and solid-body rotation about any axis.
 *
 * Latitude and longitude are in radians, longitude from the map's western edge as the grids count
 * it; vectors are eastward and northward in the local frame, the frame of the meridian at a pole.
 */
internal object SphereFields {

    /** The unit vector toward latitude [latitude], longitude [longitude]. */
    fun unit(latitude: Double, longitude: Double) =
        doubleArrayOf(cos(latitude) * cos(longitude), cos(latitude) * sin(longitude), sin(latitude))

    /** The local eastward unit vector. */
    fun east(longitude: Double) = doubleArrayOf(-sin(longitude), cos(longitude), 0.0)

    /** The local northward unit vector, the meridian's own at a pole. */
    fun north(latitude: Double, longitude: Double) =
        doubleArrayOf(-sin(latitude) * cos(longitude), -sin(latitude) * sin(longitude), cos(latitude))

    fun dot(first: DoubleArray, second: DoubleArray) = first[0] * second[0] + first[1] * second[1] + first[2] * second[2]

    /** A field with its gradient and Laplacian, exact. */
    interface Exact {
        fun value(latitude: Double, longitude: Double): Double
        fun eastward(latitude: Double, longitude: Double): Double
        fun northward(latitude: Double, longitude: Double): Double
        fun laplacian(latitude: Double, longitude: Double): Double
    }

    /**
     * `exp(-d^2 / (2 sigma^2))` with `d` the great-circle distance in meters from the point at
     * [centerLatitude], [centerLongitude], on a sphere of [radiusMeters].
     */
    class Bump(
        val centerLatitude: Double,
        val centerLongitude: Double,
        val sigmaMeters: Double,
        val radiusMeters: Double
    ) : Exact {
        private val center = unit(centerLatitude, centerLongitude)

        private fun angle(latitude: Double, longitude: Double) =
            acos(dot(unit(latitude, longitude), center).coerceIn(-1.0, 1.0))

        override fun value(latitude: Double, longitude: Double): Double {
            val distance = radiusMeters * angle(latitude, longitude)
            return exp(-distance * distance / (2 * sigmaMeters * sigmaMeters))
        }

        /** `f'(d) / sin(angle)`, finite at the center. */
        private fun slopeOverSine(latitude: Double, longitude: Double): Double {
            val angle = angle(latitude, longitude)
            val value = value(latitude, longitude)
            if (angle < 1e-9) return -radiusMeters * value / (sigmaMeters * sigmaMeters)
            val distance = radiusMeters * angle
            return -distance / (sigmaMeters * sigmaMeters) * value / sin(angle)
        }

        override fun eastward(latitude: Double, longitude: Double) =
            -slopeOverSine(latitude, longitude) * dot(east(longitude), center)

        override fun northward(latitude: Double, longitude: Double) =
            -slopeOverSine(latitude, longitude) * dot(north(latitude, longitude), center)

        override fun laplacian(latitude: Double, longitude: Double): Double {
            val angle = angle(latitude, longitude)
            val distance = radiusMeters * angle
            val value = value(latitude, longitude)
            val sigmaSquared = sigmaMeters * sigmaMeters
            val second = (distance * distance / (sigmaSquared * sigmaSquared) - 1 / sigmaSquared) * value
            return second + cos(angle) * slopeOverSine(latitude, longitude) / radiusMeters
        }
    }

    /**
     * The real spherical harmonic of degree [degree] and order [order], fully normalized:
     * `P_l^m(sin phi) cos(m lambda)`, whose Laplacian is `-l (l + 1) / a^2` times itself.
     */
    class Harmonic(val degree: Int, val order: Int, val radiusMeters: Double) : Exact {
        override fun value(latitude: Double, longitude: Double) =
            legendre(degree, order, sin(latitude), cos(latitude)) * cos(order * longitude)

        override fun eastward(latitude: Double, longitude: Double): Double = throw UnsupportedOperationException()
        override fun northward(latitude: Double, longitude: Double): Double = throw UnsupportedOperationException()

        override fun laplacian(latitude: Double, longitude: Double) =
            -degree * (degree + 1.0) / (radiusMeters * radiusMeters) * value(latitude, longitude)
    }

    /** The fully normalized associated Legendre function at `x = sin phi`, `c = cos phi`, by the standard recurrence. */
    fun legendre(degree: Int, order: Int, x: Double, c: Double): Double {
        // The diagonal's constant is the same for every degree of one order, so it only scales the
        // whole family, which the Laplacian's eigenvalue does not see.
        var diagonal = 1.0
        for (step in 1..order) diagonal *= sqrt((2.0 * step + 1) / (2.0 * step)) * c
        if (degree == order) return diagonal
        var previous = diagonal
        var current = sqrt(2.0 * order + 3) * x * previous
        for (step in order + 2..degree) {
            val lead = sqrt((4.0 * step * step - 1) / (step.toDouble() * step - order.toDouble() * order))
            val back = sqrt(((step - 1.0) * (step - 1.0) - order.toDouble() * order) / (4.0 * (step - 1.0) * (step - 1.0) - 1))
            val next = lead * (x * current - back * previous)
            previous = current
            current = next
        }
        return current
    }

    /** Solid-body rotation at [angularVelocity] (a vector, radians a second) on a sphere of [radiusMeters]. */
    class Rotation(val angularVelocity: DoubleArray, val radiusMeters: Double) {
        private fun velocity(latitude: Double, longitude: Double): DoubleArray {
            val position = unit(latitude, longitude)
            val omega = angularVelocity
            return doubleArrayOf(
                radiusMeters * (omega[1] * position[2] - omega[2] * position[1]),
                radiusMeters * (omega[2] * position[0] - omega[0] * position[2]),
                radiusMeters * (omega[0] * position[1] - omega[1] * position[0])
            )
        }

        fun eastward(latitude: Double, longitude: Double) = dot(velocity(latitude, longitude), east(longitude))
        fun northward(latitude: Double, longitude: Double) = dot(velocity(latitude, longitude), north(latitude, longitude))

        /** The vertical vorticity, `2 omega . r`. */
        fun vorticity(latitude: Double, longitude: Double) = 2.0 * dot(angularVelocity, unit(latitude, longitude))
    }

    /** Longitude of column [column] of [grid]'s centers. */
    fun longitude(grid: SphericalGrid, column: Int) = (column + 0.5) * grid.columnSpacingRadians

    /** Latitude of face [face] of [grid]. */
    fun faceLatitude(grid: SphericalGrid, face: Int) = PI / 2 - face * grid.rowSpacingRadians

    /** [function] at every center of [grid]. */
    fun atCenters(grid: SphericalGrid, function: (Double, Double) -> Double) = DoubleArray(grid.cellCount) { cell ->
        function(grid.latitudeRadians[cell / grid.columns], longitude(grid, cell % grid.columns))
    }

    /** [function] at every face of [grid]. */
    fun atFaces(grid: SphericalGrid, function: (Double, Double) -> Double) = DoubleArray((grid.rows + 1) * grid.columns) { slot ->
        function(faceLatitude(grid, slot / grid.columns), longitude(grid, slot % grid.columns))
    }

    /** The area each face's value stands for: the band between the centers on either side, or between a pole and its row. */
    fun faceAreas(grid: SphericalGrid): DoubleArray {
        val bandSines = DoubleArray(grid.rows + 2) { index ->
            when (index) {
                0 -> 1.0
                grid.rows + 1 -> -1.0
                else -> sin(grid.latitudeRadians[index - 1])
            }
        }
        val scale = grid.radiusMeters * grid.radiusMeters * grid.columnSpacingRadians
        return DoubleArray(grid.rows + 1) { face -> scale * (bandSines[face] - bandSines[face + 1]) }
    }

    /** Relative area-weighted root mean square of [computed] against [exact] at the centers. */
    fun relativeError(grid: SphericalGrid, computed: DoubleArray, exact: DoubleArray): Double {
        var error = 0.0
        var norm = 0.0
        for (cell in 0 until grid.cellCount) {
            val area = grid.cellAreaSquareMeters[cell / grid.columns]
            error += area * (computed[cell] - exact[cell]) * (computed[cell] - exact[cell])
            norm += area * exact[cell] * exact[cell]
        }
        return sqrt(error / norm)
    }

    /** Relative area-weighted root mean square of [computed] against [exact] on the faces, each face's band its weight. */
    fun relativeErrorOnFaces(grid: SphericalGrid, computed: DoubleArray, exact: DoubleArray): Double {
        val faceArea = faceAreas(grid)
        var error = 0.0
        var norm = 0.0
        for (slot in exact.indices) {
            val area = faceArea[slot / grid.columns]
            error += area * (computed[slot] - exact[slot]) * (computed[slot] - exact[slot])
            norm += area * exact[slot] * exact[slot]
        }
        return sqrt(error / norm)
    }

    /** Relative area-weighted root mean square of a vector, both components on their own stagger. */
    fun relativeError(grid: SphericalGrid, computed: SphericalOperators.Vector, exact: SphericalOperators.Vector): Double {
        val faceArea = faceAreas(grid)
        var error = 0.0
        var norm = 0.0
        for (cell in 0 until grid.cellCount) {
            val area = grid.cellAreaSquareMeters[cell / grid.columns]
            val difference = computed.eastAtCenters[cell] - exact.eastAtCenters[cell]
            error += area * difference * difference
            norm += area * exact.eastAtCenters[cell] * exact.eastAtCenters[cell]
        }
        for (slot in exact.northAtFaces.indices) {
            val area = faceArea[slot / grid.columns]
            val difference = computed.northAtFaces[slot] - exact.northAtFaces[slot]
            error += area * difference * difference
            norm += area * exact.northAtFaces[slot] * exact.northAtFaces[slot]
        }
        return sqrt(error / norm)
    }
}
