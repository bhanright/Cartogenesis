package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.math.ComplexFft
import com.cartogenesis.worldgen.model.WorldScale
import com.cartogenesis.worldgen.pipeline.AtmosphereRemap
import com.cartogenesis.worldgen.pipeline.PressureWind
import com.cartogenesis.worldgen.pipeline.SphericalGrid
import com.cartogenesis.worldgen.pipeline.SphericalOperators
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The spherical operators held to exact answers, and the atmosphere's grid derived from them.
 *
 * The resolution rule in [SphericalGrid.rowsForAtmosphere] is proved here rather than argued: each
 * operator is run on Gaussian bumps whose width is the storm track's deformation radius, on a ladder
 * of grids, against the bump's exact derivatives; the error falls as the square of the row spacing,
 * its constant is measured, and [SphericalGrid.CELLS_PER_DEFORMATION_RADIUS] must be the fewest rows
 * that meet [SphericalGrid.OPERATOR_TOLERANCE] with every operator. The Laplacian is held to the
 * spherical harmonics' exact eigenvalues, the vector operators to solid-body rotation about a tilted
 * axis, and the polar faces to the Cartesian condition for a regular vector.
 */
class SphericalOperatorsTest {

    private val radius = WorldScale().radiusMeters
    private val deformationRadiusMeters = PressureWind.rossbyRadiusKm() * 1_000.0

    /** Rows on the ladder, every one with 5-smooth longitudes. */
    private val ladder = listOf(24, 30, 36, 45, 48, 60, 72, 90, 96, 120, 150, 180, 240)

    /** Bumps at the storm track's latitude and near a pole, off every grid line, on a sphere of [sphereRadius]. */
    private fun bumps(sigmaMeters: Double, sphereRadius: Double) = listOf(
        SphereFields.Bump(PI / 4, 1.0, sigmaMeters, sphereRadius),
        SphereFields.Bump(-1.2, 4.0, sigmaMeters, sphereRadius)
    )

    /** Each operator's worst relative error over [bumps] on [grid]. */
    private fun errors(grid: SphericalGrid, sigmaMeters: Double): Map<String, Double> {
        val operators = SphericalOperators(grid)
        val worst = mutableMapOf("gradient" to 0.0, "divergence" to 0.0, "curl" to 0.0, "laplacian" to 0.0)
        for (bump in bumps(sigmaMeters, grid.radiusMeters)) {
            val scalar = SphereFields.atCenters(grid, bump::value)
            val exactGradient = SphericalOperators.Vector(
                SphereFields.atCenters(grid, bump::eastward), SphereFields.atFaces(grid, bump::northward)
            )
            val exactLaplacian = SphereFields.atCenters(grid, bump::laplacian)
            val gradient = operators.gradient(scalar)
            // The divergence of the exact gradient and the curl of the exact gradient turned a quarter
            // are both the exact Laplacian.
            val turned = SphericalOperators.Vector(
                SphereFields.atCenters(grid) { lat, lon -> -bump.northward(lat, lon) },
                SphereFields.atFaces(grid, bump::eastward)
            )
            worst["gradient"] = maxOf(worst["gradient"]!!, SphereFields.relativeError(grid, gradient, exactGradient))
            worst["divergence"] = maxOf(worst["divergence"]!!, SphereFields.relativeError(grid, operators.divergence(exactGradient), exactLaplacian))
            worst["curl"] = maxOf(worst["curl"]!!, SphereFields.relativeErrorOnFaces(grid, operators.curl(turned), SphereFields.atFaces(grid, bump::laplacian)))
            worst["laplacian"] = maxOf(worst["laplacian"]!!, SphereFields.relativeError(grid, operators.laplacian(scalar), exactLaplacian))
        }
        return worst
    }

    @Test
    fun `each operator converges at second order on the storm track's scale`() {
        println("OPERATORS on Gaussians of sigma = L_d = %.0f km, Earth's radius; relative RMS error by rows".format(deformationRadiusMeters / 1e3))
        println("OPERATORS rows  spacing/L_d  gradient    divergence  curl        laplacian")
        val table = ladder.map { rows -> rows to errors(SphericalGrid(rows, 2 * rows, radius), deformationRadiusMeters) }
        for ((rows, error) in table) {
            val spacingOverScale = PI * radius / rows / deformationRadiusMeters
            println("OPERATORS %4d  %.3f        %.3e   %.3e   %.3e   %.3e".format(rows, spacingOverScale,
                error["gradient"], error["divergence"], error["curl"], error["laplacian"]))
        }
        for (name in listOf("gradient", "divergence", "curl", "laplacian")) {
            val coarse = table.first { it.first == 120 }.second[name]!!
            val fine = table.first { it.first == 240 }.second[name]!!
            val order = ln(coarse / fine) / ln(2.0)
            println("OPERATORS $name: order %.2f between 120 and 240 rows".format(order))
            assertTrue(order > 1.8, "$name converges at order $order, short of the second order the stencils promise")
        }
    }

    /**
     * How much of each storm-track bump the forcing path keeps: carried down from a ground of 1,024 by
     * 512 to [grid], filtered as forcing is, and carried back up; the worst relative error.
     */
    private fun forcingLoss(grid: SphericalGrid, sigmaMeters: Double): Double {
        val ground = SphericalGrid.forGround(1024, 512, WorldScale())
        val remap = AtmosphereRemap(ground.columns, ground.rows, grid)
        return bumps(sigmaMeters, grid.radiusMeters).maxOf { bump ->
            val exact = SphereFields.atCenters(ground, bump::value)
            val carried = remap.toGround(remap.forcing(FloatArray(exact.size) { exact[it].toFloat() }))
            var error = 0.0
            var norm = 0.0
            for (cell in exact.indices) {
                val area = ground.cellAreaSquareMeters[cell / ground.columns]
                error += area * (carried[cell] - exact[cell]) * (carried[cell] - exact[cell])
                norm += area * exact[cell] * exact[cell]
            }
            sqrt(error / norm)
        }
    }

    /**
     * The rule against the measurement. On every grid with 5-smooth longitudes from 60 to 240 rows
     * on Earth's planet, the four operators on bumps of the storm track's width and the forcing path
     * on the same bumps (down, the coast's filter, up); the coarsest grid on which all five are
     * within [SphericalGrid.OPERATOR_TOLERANCE] must be the grid the rule makes, so the rule is the
     * coarsest that meets it and not a guess on the safe side. Then the rule's grids at half and
     * twice Earth's radius, which must meet it too.
     */
    @Test
    fun `the atmosphere's grid is the coarsest that meets the tolerance`() {
        val rungs = (60..240).filter { ComplexFft.isFiveSmooth(2 * it) }
        var coarsestPassing = -1
        println("OPERATORS the rule's ladder, Earth's radius, sigma = L_d: rows, rows per L_d, gradient, divergence, curl, Laplacian, forcing path")
        for (rows in rungs) {
            val grid = SphericalGrid(rows, 2 * rows, radius)
            val error = errors(grid, deformationRadiusMeters)
            val forcing = forcingLoss(grid, deformationRadiusMeters)
            val worst = maxOf(error.values.max(), forcing)
            println("OPERATORS rung %4d  %.2f  %.2e  %.2e  %.2e  %.2e  %.2e%s".format(rows, rows * deformationRadiusMeters / (PI * radius),
                error["gradient"], error["divergence"], error["curl"], error["laplacian"], forcing,
                if (worst <= SphericalGrid.OPERATOR_TOLERANCE) "  within" else ""))
            if (worst <= SphericalGrid.OPERATOR_TOLERANCE && coarsestPassing < 0) coarsestPassing = rows
        }
        val ruleRows = SphericalGrid.rowsForAtmosphere(radius)
        println("OPERATORS the coarsest rung within the tolerance: $coarsestPassing rows; the rule's: $ruleRows (%.2f rows per L_d)"
            .format(SphericalGrid.CELLS_PER_DEFORMATION_RADIUS))
        assertEquals(coarsestPassing, ruleRows, "the rule's grid is not the coarsest that meets the tolerance")

        for ((label, factor) in listOf("half Earth's radius" to 0.5, "Earth's radius" to 1.0, "twice Earth's radius" to 2.0)) {
            val grid = SphericalGrid.forAtmosphere(WorldScale(worldWidthKm = WorldScale.EARTH_EQUATOR_KM * factor))
            val cells = grid.cellCount
            println("OPERATORS grid at $label: ${grid.rows} rows by ${grid.columns}, %.1f km a row, %d cells, %.2f MB a field in doubles"
                .format(grid.rowSpacingMeters / 1e3, cells, cells * 8.0 / 1e6))
            assertTrue(ComplexFft.isFiveSmooth(grid.columns))
            val onGrid = errors(grid, deformationRadiusMeters) + ("forcing path" to forcingLoss(grid, deformationRadiusMeters))
            println("OPERATORS   errors there: " + onGrid.entries.joinToString { "${it.key} %.2e".format(it.value) })
            onGrid.forEach { (name, value) ->
                assertTrue(value <= SphericalGrid.OPERATOR_TOLERANCE, "$name errs by $value at $label, past the tolerance")
            }
        }
    }

    /** The Laplacian of a harmonic is `-l (l + 1) / a^2` times it, to the scheme's order. */
    @Test
    fun `spherical harmonics are the Laplacian's eigenfunctions`() {
        val harmonics = listOf(2 to 0, 2 to 1, 6 to 3, 10 to 0, 10 to 7, 20 to 5, 20 to 20)
        for ((degree, order) in harmonics) {
            val line = ladder.filter { it >= 36 }.map { rows ->
                val grid = SphericalGrid(rows, 2 * rows, radius)
                val harmonic = SphereFields.Harmonic(degree, order, radius)
                val computed = SphericalOperators(grid).laplacian(SphereFields.atCenters(grid, harmonic::value))
                rows to SphereFields.relativeError(grid, computed, SphereFields.atCenters(grid, harmonic::laplacian))
            }
            println("HARMONIC l=$degree m=$order: " + line.joinToString { (rows, error) -> "$rows rows %.2e".format(error) })
            val at120 = line.first { it.first == 120 }.second
            val at240 = line.first { it.first == 240 }.second
            assertTrue(at240 < at120 / 3.5, "l=$degree m=$order does not converge at second order: $at120 then $at240")
            // Second order: the relative error near (l spacing)^2 / 12 down a meridian.
            val bound = (degree * PI / 120) * (degree * PI / 120) / 4
            assertTrue(at120 < bound, "l=$degree m=$order errs by $at120 at 120 rows, past $bound")
        }
    }

    /**
     * Solid-body rotation about an axis tilted 60 degrees from the pole, so the wind crosses both
     * poles at speed: no divergence, and a vorticity of `2 omega . r`, polar rows included.
     */
    @Test
    fun `solid-body rotation about a tilted axis has no divergence and twice its spin as vorticity`() {
        val spin = 1e-5
        val axis = doubleArrayOf(spin * kotlin.math.sin(PI / 3), 0.3 * spin, spin * kotlin.math.cos(PI / 3))
        val rotation = SphereFields.Rotation(axis, radius)
        var previous = Double.NaN
        for (rows in listOf(30, 60, 120, 240)) {
            val grid = SphericalGrid(rows, 2 * rows, radius)
            val operators = SphericalOperators(grid)
            val wind = SphericalOperators.Vector(
                SphereFields.atCenters(grid, rotation::eastward), SphereFields.atFaces(grid, rotation::northward)
            )
            val divergence = operators.divergence(wind)
            val vorticity = SphereFields.atFaces(grid, rotation::vorticity)
            val curl = operators.curl(wind)
            val curlError = SphereFields.relativeErrorOnFaces(grid, curl, vorticity)
            var squared = 0.0
            for (cell in 0 until grid.cellCount) squared += grid.cellAreaSquareMeters[cell / grid.columns] * divergence[cell] * divergence[cell]
            val divergenceScale = sqrt(squared / grid.totalAreaSquareMeters) / spin
            val divergenceWorst = divergence.maxOf { abs(it) } / spin
            val polarRows = (0 until grid.columns).maxOf { column ->
                maxOf(abs(curl[column] - vorticity[column]), abs(curl[rows * grid.columns + column] - vorticity[rows * grid.columns + column]))
            } / spin
            println("ROTATION $rows rows: divergence %.2e of the spin (root mean square; worst cell %.2e), curl error %.2e, worst error at the poles %.2e of the spin"
                .format(divergenceScale, divergenceWorst, curlError, polarRows))
            assertTrue(divergenceScale < 0.1 * (PI / rows) * (PI / rows), "solid-body rotation diverges by $divergenceScale of its spin at $rows rows")
            if (!previous.isNaN()) assertTrue(curlError < previous / 3.5, "the curl does not converge at second order: $previous then $curlError")
            previous = curlError
            assertTrue(polarRows < 0.05, "the poles' vorticity is off by $polarRows of the spin at $rows rows")
        }
    }

    /**
     * The polar faces against the Cartesian condition: the gradient of `x = cos(phi) cos(lambda)`, a
     * field whose gradient at the pole is a single nonzero vector, is read on each polar face as that
     * vector's component along the face's meridian, so `v(lambda) = -cos(lambda)` at the north pole
     * and `+cos(lambda)` at the south. A polar face held at zero, the blanket condition, fails this.
     */
    @Test
    fun `the gradient is regular at the poles and a zero polar face is not`() {
        for (rows in listOf(30, 120)) {
            val grid = SphericalGrid(rows, 2 * rows, radius)
            val x = SphereFields.atCenters(grid) { lat, lon -> kotlin.math.cos(lat) * kotlin.math.cos(lon) }
            val gradient = SphericalOperators(grid).gradient(x)
            var worst = 0.0
            var worstZeroed = 0.0
            for (column in 0 until grid.columns) {
                val longitude = SphereFields.longitude(grid, column)
                val northPole = -kotlin.math.cos(longitude) / radius
                val southPole = kotlin.math.cos(longitude) / radius
                worst = maxOf(worst, abs(gradient.northAtFaces[column] - northPole) * radius,
                    abs(gradient.northAtFaces[rows * grid.columns + column] - southPole) * radius)
                worstZeroed = maxOf(worstZeroed, abs(northPole) * radius, abs(southPole) * radius)
            }
            // And at the first row's centers the computed vector, in Cartesian components, is the
            // exact one: a smooth vector field, not one whose frame spins about the pole.
            val north = SphericalOperators(grid).northAtCenters(gradient)
            var cartesianWorst = 0.0
            for (column in 0 until grid.columns) {
                val longitude = SphereFields.longitude(grid, column)
                for (row in listOf(0, rows - 1)) {
                    val latitude = grid.latitudeRadians[row]
                    val position = SphereFields.unit(latitude, longitude)
                    for (axis in 0..2) {
                        val exact = ((if (axis == 0) 1.0 else 0.0) - position[0] * position[axis]) / radius
                        val computed = SphereFields.east(longitude)[axis] * gradient.eastAtCenters[row * grid.columns + column] +
                            SphereFields.north(latitude, longitude)[axis] * north[row * grid.columns + column]
                        cartesianWorst = maxOf(cartesianWorst, abs(computed - exact) * radius)
                    }
                }
            }
            println("POLES $rows rows: polar face error %.2e of the gradient's size, a zero face %.2f; the polar rows' Cartesian components within %.2e"
                .format(worst, worstZeroed, cartesianWorst))
            assertTrue(worst < 0.01, "the polar faces miss the meridian component by $worst at $rows rows")
            assertTrue(worstZeroed > 0.5, "a zero polar face would pass")
            assertTrue(cartesianWorst < (PI / rows) * (PI / rows), "the polar rows' gradient misses the one vector by $cartesianWorst")
        }
    }

    /** Divergence and curl integrate to zero over the sphere, as Gauss and Stokes require on a closed surface. */
    @Test
    fun `divergence and curl integrate to zero over the sphere`() {
        val grid = SphericalGrid(45, 90, radius)
        val operators = SphericalOperators(grid)
        val random = java.util.Random(7)
        val wind = SphericalOperators.Vector(
            DoubleArray(grid.cellCount) { random.nextGaussian() },
            DoubleArray((grid.rows + 1) * grid.columns) { random.nextGaussian() }
        )
        val faceArea = SphereFields.faceAreas(grid)
        for ((name, field) in listOf("divergence" to operators.divergence(wind), "curl" to operators.curl(wind))) {
            var integral = 0.0
            var magnitude = 0.0
            for (index in field.indices) {
                val area = if (name == "curl") faceArea[index / grid.columns] else grid.cellAreaSquareMeters[index / grid.columns]
                integral += field[index] * area
                magnitude += abs(field[index]) * area
            }
            println("CONSERVATION $name of random winds integrates to %.2e of its magnitude".format(abs(integral) / magnitude))
            assertTrue(abs(integral) / magnitude < 1e-13, "the $name of random winds integrates to ${integral / magnitude}")
        }
    }

    /**
     * The biharmonic is the Laplacian twice, which on a harmonic is the eigenvalue squared: second
     * order equatorward of 80 degrees. Within a few rows of a pole it is not accurate and cannot be:
     * a second-order Laplacian's first rows carry an error of the order of the field's own small
     * value there for every zonal wave but the mean, and the second Laplacian multiplies that by
     * `m^2 / cos^2(phi)`. What it keeps everywhere is what a diffusion needs: it is symmetric in the
     * area-weighted product and never negative, so `-nu del^4` only removes variance, most of all
     * from the zonal waves a pole cannot hold (docs/TODO.md).
     */
    @Test
    fun `the biharmonic of a harmonic is its eigenvalue squared away from the poles, and it only damps`() {
        val capLatitude = 80.0 * PI / 180.0
        for ((degree, order) in listOf(8 to 0, 8 to 1, 8 to 3, 12 to 6)) {
            val eigenvalue = degree * (degree + 1.0) / (radius * radius)
            val line = listOf(60, 120, 240).map { rows ->
                val grid = SphericalGrid(rows, 2 * rows, radius)
                val values = SphereFields.atCenters(grid, SphereFields.Harmonic(degree, order, radius)::value)
                val computed = SphericalOperators(grid).biharmonic(values)
                var error = 0.0
                var norm = 0.0
                var whole = 0.0
                for (cell in 0 until grid.cellCount) {
                    val area = grid.cellAreaSquareMeters[cell / grid.columns]
                    val exact = eigenvalue * eigenvalue * values[cell]
                    val squared = area * (computed[cell] - exact) * (computed[cell] - exact)
                    whole += squared
                    norm += area * exact * exact
                    if (abs(grid.latitudeRadians[cell / grid.columns]) < capLatitude) error += squared
                }
                Triple(rows, sqrt(error / norm), sqrt(whole / norm))
            }
            println("BIHARMONIC l=$degree m=$order equatorward of 80 degrees: " +
                line.joinToString { (rows, away, whole) -> "$rows rows %.2e (whole sphere %.2e)".format(away, whole) })
            assertTrue(line[2].second < line[1].second / 3.5, "l=$degree m=$order: the biharmonic does not converge at second order away from the poles")
            assertTrue(line[1].second < 0.01, "l=$degree m=$order: the biharmonic errs by ${line[1].second} at 120 rows away from the poles")
        }
        // Symmetric and never negative: <B s, t> = <s, B t> and <B s, s> >= 0 for random fields.
        val grid = SphericalGrid(45, 90, radius)
        val operators = SphericalOperators(grid)
        val random = java.util.Random(3)
        repeat(5) {
            val first = DoubleArray(grid.cellCount) { random.nextGaussian() }
            val second = DoubleArray(grid.cellCount) { random.nextGaussian() }
            val firstBiharmonic = operators.biharmonic(first)
            val secondBiharmonic = operators.biharmonic(second)
            var across = 0.0
            var back = 0.0
            var self = 0.0
            var scale = 0.0
            for (cell in 0 until grid.cellCount) {
                val area = grid.cellAreaSquareMeters[cell / grid.columns]
                across += area * firstBiharmonic[cell] * second[cell]
                back += area * first[cell] * secondBiharmonic[cell]
                self += area * firstBiharmonic[cell] * first[cell]
                scale += area * abs(firstBiharmonic[cell] * first[cell])
            }
            assertTrue(abs(across - back) < 1e-10 * scale, "the biharmonic is not symmetric: $across against $back")
            assertTrue(self > 0, "the biharmonic gives a field negative variance: $self")
        }
    }

    /** The transform against the direct sum, at lengths with every radix and a prime. */
    @Test
    fun `the transform matches the direct sum at any length`() {
        val random = java.util.Random(11)
        for (length in listOf(1, 2, 3, 5, 7, 12, 30, 45, 64, 90, 97, 240, 360)) {
            val real = DoubleArray(length) { random.nextGaussian() }
            val imaginary = DoubleArray(length) { random.nextGaussian() }
            val outReal = real.copyOf()
            val outImaginary = imaginary.copyOf()
            ComplexFft(length).forward(outReal, outImaginary)
            var worst = 0.0
            for (k in 0 until length) {
                var sumReal = 0.0
                var sumImaginary = 0.0
                for (j in 0 until length) {
                    val angle = -2 * PI * j * k / length
                    sumReal += real[j] * kotlin.math.cos(angle) - imaginary[j] * kotlin.math.sin(angle)
                    sumImaginary += real[j] * kotlin.math.sin(angle) + imaginary[j] * kotlin.math.cos(angle)
                }
                worst = maxOf(worst, abs(sumReal - outReal[k]), abs(sumImaginary - outImaginary[k]))
            }
            assertTrue(worst < 1e-10 * length, "length $length: the transform differs from the direct sum by $worst")
            ComplexFft(length).inverse(outReal, outImaginary)
            for (j in 0 until length) {
                assertEquals(real[j], outReal[j] / length, 1e-12)
                assertEquals(imaginary[j], outImaginary[j] / length, 1e-12)
            }
        }
    }
}
