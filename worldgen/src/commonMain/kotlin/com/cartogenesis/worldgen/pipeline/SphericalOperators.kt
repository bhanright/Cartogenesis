package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.math.ComplexFft

/**
 * Gradient, divergence, curl, Laplacian and biharmonic on a [SphericalGrid], in the sphere's own
 * metric.
 *
 * **Longitude is Fourier.** Along a row every derivative is the row's own Fourier series
 * differentiated, exact for anything the row resolves; the atmosphere is solved one zonal
 * wavenumber at a time, so this is the derivative its model already takes. The longitude metric is
 * `1 / (a cos phi)`, so a pressure difference across a degree of longitude near a pole is a steep
 * gradient and not the gentle one the same difference makes at the equator.
 *
 * **Latitude is a finite volume on a staggered grid.** Scalars and the eastward component sit at
 * the cell centers; the northward component sits on the faces between rows (an Arakawa C-grid in
 * latitude). Divergence is the flux through a cell's faces over its area on the sphere: the north
 * face's length is `a cos(phi) dlambda`, zero at a pole, so the polar rows close without a special
 * case and the area integral of any divergence is zero to rounding. Second order in the row spacing;
 * `SphericalOperatorsTest` measures the order and the constant.
 *
 * **Scalars are even over a pole.** A scalar continued along a meridian over the pole arrives on the
 * meridian opposite, `s(-theta, lambda + pi) = s(theta, lambda)`, so the northward derivative on the
 * polar face is the difference across the pole to the opposite column, and is not set to zero.
 *
 * **Vectors are regular at a pole when their Cartesian components are smooth scalars there**
 * (Swarztrauber 1981, as Townsend, Wilber and Wright 2016 put it in their section 4.1): a single
 * vector at the pole, read in the frame of each meridian that reaches it. In eastward and northward
 * components that leaves only the first zonal wavenumber at the pole, with `u_1 = -i v_1` at the
 * north pole and `u_1 = +i v_1` at the south. A northward component of zero on the polar face is
 * not that condition; it holds only for a vector that vanishes at the pole. The across-the-pole
 * difference above gives the polar face the meridian frame's component of the gradient's one
 * vector, which `SphericalOperatorsTest` holds to the Cartesian condition.
 */
class SphericalOperators(val grid: SphericalGrid) {

    /**
     * A tangent vector field on [grid]: [eastAtCenters] at the cell centers, `rows * columns`, and
     * [northAtFaces] on the faces between rows, `(rows + 1) * columns`, faces 0 and `rows` being the
     * poles, where the northward component is the meridian's own: along that column toward the pole.
     * Meters a second or whatever the field's unit is per meter.
     */
    class Vector(val eastAtCenters: DoubleArray, val northAtFaces: DoubleArray)

    /** The gradient of [scalar], its unit per meter. */
    fun gradient(scalar: DoubleArray): Vector {
        checkCenters(scalar)
        val rows = grid.rows
        val columns = grid.columns
        val east = zonalDerivative(scalar, rows, order = 1)
        for (row in 0 until rows) {
            val metric = 1.0 / (grid.radiusMeters * grid.cosLatitude[row])
            for (column in 0 until columns) east[row * columns + column] *= metric
        }
        val north = DoubleArray((rows + 1) * columns)
        val spacing = grid.rowSpacingMeters
        val half = columns / 2
        for (column in 0 until columns) {
            val opposite = (column + half) % columns
            // Over each pole the meridian continues on the opposite column at the same distance from
            // the pole, so the difference spans one row spacing as every interior face's does.
            north[column] = (scalar[opposite] - scalar[column]) / spacing
            val lastRow = (rows - 1) * columns
            north[rows * columns + column] = (scalar[lastRow + column] - scalar[lastRow + opposite]) / spacing
        }
        for (face in 1 until rows) {
            for (column in 0 until columns) {
                north[face * columns + column] =
                    (scalar[(face - 1) * columns + column] - scalar[face * columns + column]) / spacing
            }
        }
        return Vector(east, north)
    }

    /** The divergence of [vector], its unit per meter: the net flux out of each cell over its area. */
    fun divergence(vector: Vector): DoubleArray {
        checkVector(vector)
        val rows = grid.rows
        val columns = grid.columns
        val result = zonalDerivative(vector.eastAtCenters, rows, order = 1)
        val radius = grid.radiusMeters
        for (row in 0 until rows) {
            // The zonal flux crosses faces of length a dphi; over the cell's area a^2 dlambda
            // (sin phi_n - sin phi_s) that is dphi / (a span) per radian of longitude.
            val zonalMetric = grid.rowSpacingRadians / (radius * grid.sinSpanOfRow[row])
            val meridionalMetric = 1.0 / (radius * grid.sinSpanOfRow[row])
            val northCos = grid.cosFace[row]
            val southCos = grid.cosFace[row + 1]
            for (column in 0 until columns) {
                val cell = row * columns + column
                val outNorth = northCos * vector.northAtFaces[row * columns + column]
                val inSouth = southCos * vector.northAtFaces[(row + 1) * columns + column]
                result[cell] = result[cell] * zonalMetric + (outNorth - inSouth) * meridionalMetric
            }
        }
        return result
    }

    /**
     * The vertical component of the curl of [vector], per second for a wind: `k . curl V`, positive
     * counterclockwise seen from above, on the faces between rows, `(rows + 1) * columns`.
     *
     * On the faces because that is where the C-grid's circulation closes without averaging: around
     * the band between two row centers the zonal wind is on its two edges and the northward wind at
     * its middle. The band's circulation over its area on the sphere, so the area integral of the
     * curl over the faces' bands is zero to rounding, as Stokes' theorem on a closed surface
     * requires. At a pole the band is the polar cap, whose only edge is the first row: the vorticity
     * there is the row's mean circulation over the cap, one value, as a scalar at a pole must be.
     */
    fun curl(vector: Vector): DoubleArray {
        checkVector(vector)
        val rows = grid.rows
        val columns = grid.columns
        val radius = grid.radiusMeters
        val result = zonalDerivative(vector.northAtFaces, rows + 1, order = 1)
        val latitudeSine = DoubleArray(rows) { kotlin.math.sin(grid.latitudeRadians[it]) }
        for (face in 0..rows) {
            val northSine = if (face == 0) 1.0 else latitudeSine[face - 1]
            val southSine = if (face == rows) -1.0 else latitudeSine[face]
            val bandSpan = northSine - southSine
            val zonalMetric = grid.rowSpacingRadians / (radius * bandSpan)
            val meridionalMetric = 1.0 / (radius * bandSpan)
            if (face == 0 || face == rows) {
                val row = if (face == 0) 0 else rows - 1
                var mean = 0.0
                for (column in 0 until columns) mean += vector.eastAtCenters[row * columns + column]
                mean /= columns
                val sign = if (face == 0) 1.0 else -1.0
                val polar = sign * mean * grid.cosLatitude[row] * meridionalMetric
                for (column in 0 until columns) result[face * columns + column] = polar
                continue
            }
            val northCos = grid.cosLatitude[face - 1]
            val southCos = grid.cosLatitude[face]
            for (column in 0 until columns) {
                val slot = face * columns + column
                val circulation = southCos * vector.eastAtCenters[face * columns + column] -
                    northCos * vector.eastAtCenters[(face - 1) * columns + column]
                result[slot] = result[slot] * zonalMetric + circulation * meridionalMetric
            }
        }
        return result
    }

    /** The Laplacian of [scalar], its unit per square meter: the divergence of its gradient, in one compact stencil. */
    fun laplacian(scalar: DoubleArray): DoubleArray {
        checkCenters(scalar)
        val rows = grid.rows
        val columns = grid.columns
        val result = zonalDerivative(scalar, rows, order = 2)
        val radius = grid.radiusMeters
        val spacing = grid.rowSpacingRadians
        for (row in 0 until rows) {
            val span = grid.sinSpanOfRow[row]
            val zonalMetric = spacing / (radius * radius * span * grid.cosLatitude[row])
            val meridionalMetric = 1.0 / (radius * radius * spacing * span)
            val northCos = grid.cosFace[row]
            val southCos = grid.cosFace[row + 1]
            for (column in 0 until columns) {
                val cell = row * columns + column
                val here = scalar[cell]
                val fromNorth = if (row > 0) northCos * (scalar[cell - columns] - here) else 0.0
                val toSouth = if (row < rows - 1) southCos * (here - scalar[cell + columns]) else 0.0
                result[cell] = result[cell] * zonalMetric + (fromNorth - toSouth) * meridionalMetric
            }
        }
        return result
    }

    /** The Laplacian of the Laplacian of [scalar], its unit per meter to the fourth. */
    fun biharmonic(scalar: DoubleArray): DoubleArray = laplacian(laplacian(scalar))

    /**
     * [field] spread by [steps] backward Euler steps of diffusion whose kernel approaches a Gaussian
     * of [widthMeters] standard deviation on the ground, the same width in every direction. A new
     * array; [field] is not touched.
     *
     * Each step solves `(1 - tau L) out = in` exactly: the row's Fourier series diagonalizes the
     * longitude part, taken as the second difference, whose symbol is
     * `(2 - 2 cos(m dlambda)) / dlambda^2`, and each wavenumber is then one tridiagonal system down
     * the rows, the latitude part in the flux form of [laplacian] with no flux through a pole. So
     * each step's matrix is an M-matrix: its inverse has no negative entry, the result stays inside
     * the range of [field], a constant is kept, and the area integral is unchanged to rounding.
     * Near a pole the longitude term is `m^2 / cos^2(phi)` times the latitude term's, so every
     * zonal wave but the mean is drawn toward the row beyond it, which is what leaves a field one
     * value at the pole where a row-by-row blur leaves whatever each row held.
     */
    fun diffuse(field: DoubleArray, widthMeters: Double, steps: Int): DoubleArray {
        checkCenters(field)
        if (widthMeters <= 0.0 || steps <= 0) return field.copyOf()
        // n steps of diffusivity-time tau spread a spike to a variance of 2 n tau in each direction.
        val tauSquareMeters = widthMeters * widthMeters / (2.0 * steps)
        var result = field.copyOf()
        repeat(steps) { result = diffusionStep(result, tauSquareMeters) }
        return result
    }

    private fun diffusionStep(field: DoubleArray, tauSquareMeters: Double): DoubleArray {
        val rows = grid.rows
        val columns = grid.columns
        val radius = grid.radiusMeters
        val spectrumReal = DoubleArray(rows * columns)
        val spectrumImaginary = DoubleArray(rows * columns)
        val transform = grid.rowTransform
        parallelChunks(0, rows) { startRow, endRow ->
            val scratch = ComplexFft.Scratch(columns)
            val real = DoubleArray(columns)
            val imaginary = DoubleArray(columns)
            for (row in startRow until endRow) {
                for (column in 0 until columns) {
                    real[column] = field[row * columns + column]
                    imaginary[column] = 0.0
                }
                transform.forward(real, imaginary, scratch)
                real.copyInto(spectrumReal, row * columns)
                imaginary.copyInto(spectrumImaginary, row * columns)
            }
        }
        val spacing = grid.rowSpacingRadians
        val columnSpacing = grid.columnSpacingRadians
        parallelChunks(0, columns) { startSlot, endSlot ->
            val lower = DoubleArray(rows)
            val diagonal = DoubleArray(rows)
            val upper = DoubleArray(rows)
            val lineReal = DoubleArray(rows)
            val lineImaginary = DoubleArray(rows)
            val modifiedUpper = DoubleArray(rows)
            for (slot in startSlot until endSlot) {
                val wavenumber = signedWavenumber(slot, columns)
                val zonalSymbol = (2.0 - 2.0 * kotlin.math.cos(wavenumber * columnSpacing)) / (columnSpacing * columnSpacing)
                for (row in 0 until rows) {
                    val span = grid.sinSpanOfRow[row]
                    val zonal = spacing / (radius * radius * span * grid.cosLatitude[row]) * zonalSymbol
                    val toNorth = grid.cosFace[row] / (radius * radius * spacing * span)
                    val toSouth = grid.cosFace[row + 1] / (radius * radius * spacing * span)
                    lower[row] = -tauSquareMeters * toNorth
                    upper[row] = -tauSquareMeters * toSouth
                    diagonal[row] = 1.0 + tauSquareMeters * (zonal + toNorth + toSouth)
                    lineReal[row] = spectrumReal[row * columns + slot]
                    lineImaginary[row] = spectrumImaginary[row * columns + slot]
                }
                // The Thomas sweep, for both halves of the wave at once.
                var pivot = diagonal[0]
                modifiedUpper[0] = upper[0] / pivot
                lineReal[0] /= pivot
                lineImaginary[0] /= pivot
                for (row in 1 until rows) {
                    pivot = diagonal[row] - lower[row] * modifiedUpper[row - 1]
                    modifiedUpper[row] = upper[row] / pivot
                    lineReal[row] = (lineReal[row] - lower[row] * lineReal[row - 1]) / pivot
                    lineImaginary[row] = (lineImaginary[row] - lower[row] * lineImaginary[row - 1]) / pivot
                }
                for (row in rows - 2 downTo 0) {
                    lineReal[row] -= modifiedUpper[row] * lineReal[row + 1]
                    lineImaginary[row] -= modifiedUpper[row] * lineImaginary[row + 1]
                }
                for (row in 0 until rows) {
                    spectrumReal[row * columns + slot] = lineReal[row]
                    spectrumImaginary[row * columns + slot] = lineImaginary[row]
                }
            }
        }
        val result = DoubleArray(rows * columns)
        parallelChunks(0, rows) { startRow, endRow ->
            val scratch = ComplexFft.Scratch(columns)
            val real = DoubleArray(columns)
            val imaginary = DoubleArray(columns)
            for (row in startRow until endRow) {
                spectrumReal.copyInto(real, 0, row * columns, (row + 1) * columns)
                spectrumImaginary.copyInto(imaginary, 0, row * columns, (row + 1) * columns)
                transform.inverse(real, imaginary, scratch)
                for (column in 0 until columns) result[row * columns + column] = real[column] / columns
            }
        }
        return result
    }

    /** [vector]'s northward component at the cell centers: the mean of each cell's two faces. */
    fun northAtCenters(vector: Vector): DoubleArray {
        val columns = grid.columns
        return DoubleArray(grid.cellCount) { cell ->
            val row = cell / columns
            val column = cell % columns
            0.5 * (vector.northAtFaces[row * columns + column] + vector.northAtFaces[(row + 1) * columns + column])
        }
    }

    /**
     * The [order]th derivative along each of the first [lineCount] rows of [field] with respect to
     * longitude in radians, from each row's Fourier series. The Nyquist wave's odd derivatives are
     * zero at the samples, since its sine vanishes on every one of them.
     */
    internal fun zonalDerivative(field: DoubleArray, lineCount: Int, order: Int): DoubleArray {
        val columns = grid.columns
        val result = DoubleArray(lineCount * columns)
        val transform = grid.rowTransform
        parallelChunks(0, lineCount) { startRow, endRow ->
            val real = DoubleArray(columns)
            val imaginary = DoubleArray(columns)
            val scratch = ComplexFft.Scratch(columns)
            for (row in startRow until endRow) {
                for (column in 0 until columns) {
                    real[column] = field[row * columns + column]
                    imaginary[column] = 0.0
                }
                transform.forward(real, imaginary, scratch)
                for (index in 0 until columns) {
                    val wavenumber = signedWavenumber(index, columns).toDouble()
                    val valueReal = real[index]
                    val valueImaginary = imaginary[index]
                    if (order == 1) {
                        // Multiplying by i m.
                        val nyquist = 2 * index == columns
                        real[index] = if (nyquist) 0.0 else -wavenumber * valueImaginary
                        imaginary[index] = if (nyquist) 0.0 else wavenumber * valueReal
                    } else {
                        real[index] = -wavenumber * wavenumber * valueReal
                        imaginary[index] = -wavenumber * wavenumber * valueImaginary
                    }
                }
                transform.inverse(real, imaginary, scratch)
                for (column in 0 until columns) result[row * columns + column] = real[column] / columns
            }
        }
        return result
    }

    private fun checkCenters(field: DoubleArray) =
        require(field.size == grid.cellCount) { "a field of ${field.size} values on a grid of ${grid.cellCount} cells" }

    private fun checkVector(vector: Vector) {
        checkCenters(vector.eastAtCenters)
        require(vector.northAtFaces.size == (grid.rows + 1) * grid.columns) {
            "a northward component of ${vector.northAtFaces.size} values on ${grid.rows + 1} faces of ${grid.columns}"
        }
    }

    companion object {
        /** The wavenumber of transform slot [index] of [length]: 0, 1, ..., then negative from the middle on. */
        fun signedWavenumber(index: Int, length: Int): Int = if (2 * index < length) index else index - length
    }
}
