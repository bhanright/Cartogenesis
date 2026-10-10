package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import com.cartogenesis.worldgen.math.ComplexFft
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Carries fields between the map's grid and the atmosphere's coarse grid, [coarse].
 *
 * **Down** ([areaMean]) is conservative: each coarse cell takes the mean of the ground cells it
 * overlaps, each weighted by the area of the overlap on the sphere, `a^2 dlambda d(sin phi)`. The
 * overlap is exact, the weights separate into a longitude factor and a latitude factor, and the area
 * integral of the result is the ground's to rounding, so heat or rain carried down is neither made
 * nor lost.
 *
 * **Up** ([toGround]) is a double Fourier interpolant (Merilees' double Fourier sphere, after
 * Townsend, Wilber and Wright 2016, section 2.2). Each meridian is continued over both poles onto the
 * meridian opposite, which makes a periodic great circle on which a scalar is smooth; the field is
 * then a trigonometric series in both colatitude and longitude, and the ground's cells read that
 * series at their own centers. Nothing in it changes at a coarse cell's edge, so the coarse grid
 * leaves no period in what comes up (conventions rule 13). The series is held to the sphere's
 * structure: a scalar takes one value at each pole (their BMC-I condition), imposed by the smallest
 * change to its coefficients; a vector is carried as its three Cartesian components, each a scalar
 * regular at the poles, which is how they represent a tangent field (section 4.1).
 *
 * **The filter** is applied to forcing on the way down ([forcing]), once, so the model reads a field
 * whose series ends smoothly at the coarse grid's resolution. A coast is a step; the area mean
 * spreads it over one coarse cell, and a trigonometric series through that ramp still rings (Gibbs,
 * 15.7% over a continent's step) and leaves the coarse grid's period in the field. Two filters do it,
 * in turn. Implicit diffusion on the sphere ([SphericalOperators.diffuse]) of [FILTER_WIDTH_IN_ROWS]
 * rows conserves, keeps a constant and makes no extreme of its own, and holds the step's overshoot to
 * [STEP_OVERSHOOT_BOUND]; then Gelb and Gottlieb's exponential filter, `exp(-alpha eta^p)` on the
 * double Fourier coefficients, `eta` the wave's distance from the origin over the grid's own limits
 * in each direction and `alpha` the double's machine zero, takes out the waves nearest the limit that
 * the diffusion's second difference leaves (*The Resolution of the Gibbs Phenomenon for Fourier
 * Spectral Methods*, 2007, section 2). Either alone fails: the exponential filter does not bound the
 * step, and the diffusion alone leaves the period. Carrying up applies no filter of its own: what
 * comes up is the series of what is there, exactly.
 */
class AtmosphereRemap(val groundColumns: Int, val groundRows: Int, val coarse: SphericalGrid) {

    init {
        require(groundColumns >= 2 && groundRows >= 2) { "a ground grid of $groundColumns by $groundRows" }
    }

    /** One coarse row's or column's overlapping ground lines: the first, how many, and each one's weight. */
    class Overlaps(val first: IntArray, val count: IntArray, val offset: IntArray, val weight: DoubleArray, val total: DoubleArray)

    /**
     * For each coarse column, the ground columns it overlaps and the longitude each shares with it,
     * in radians; [Overlaps.total] is the coarse column's own width as their sum.
     */
    val columnOverlaps: Overlaps = overlaps(coarse.columns, groundColumns, 2.0 * PI) { lo, hi -> hi - lo }

    /**
     * For each coarse row, the ground rows it overlaps and the sine of latitude each shares with it,
     * so that a weight times a column weight times `a^2` is an area on the sphere.
     */
    val rowOverlaps: Overlaps = overlaps(coarse.rows, groundRows, PI) { lo, hi ->
        // The colatitudes lo..hi, as the fall in sin(latitude): cos(lo) - cos(hi), written so the
        // polar rows keep their digits.
        2.0 * sin((hi + lo) / 2) * sin((hi - lo) / 2)
    }

    /**
     * The area-weighted mean of [ground] over each coarse cell; [ground] is row-major on the map's
     * grid, `groundRows * groundColumns`, and the result is on [coarse]'s centers. Conservative:
     * `sum(result * coarse area) = sum(ground * ground area)` to rounding.
     */
    fun areaMean(ground: FloatArray): DoubleArray {
        require(ground.size == groundRows * groundColumns) { "a ground field of ${ground.size} values" }
        val coarseColumns = coarse.columns
        // Along each ground row first: the row's share of every coarse column.
        val rowSums = DoubleArray(groundRows * coarseColumns)
        parallelChunks(0, groundRows) { startRow, endRow ->
            for (groundRow in startRow until endRow) {
                val rowStart = groundRow * groundColumns
                for (coarseColumn in 0 until coarseColumns) {
                    var sum = 0.0
                    val first = columnOverlaps.first[coarseColumn]
                    val offset = columnOverlaps.offset[coarseColumn]
                    for (index in 0 until columnOverlaps.count[coarseColumn]) {
                        sum += ground[rowStart + first + index] * columnOverlaps.weight[offset + index]
                    }
                    rowSums[groundRow * coarseColumns + coarseColumn] = sum
                }
            }
        }
        val result = DoubleArray(coarse.cellCount)
        parallelChunks(0, coarse.rows) { startRow, endRow ->
            for (coarseRow in startRow until endRow) {
                val first = rowOverlaps.first[coarseRow]
                val offset = rowOverlaps.offset[coarseRow]
                for (coarseColumn in 0 until coarseColumns) {
                    var sum = 0.0
                    for (index in 0 until rowOverlaps.count[coarseRow]) {
                        sum += rowSums[(first + index) * coarseColumns + coarseColumn] * rowOverlaps.weight[offset + index]
                    }
                    result[coarseRow * coarseColumns + coarseColumn] =
                        sum / (rowOverlaps.total[coarseRow] * columnOverlaps.total[coarseColumn])
                }
            }
        }
        return result
    }

    /**
     * Forcing as the atmosphere reads it, on [coarse]'s centers: the [areaMean] of [ground], spread
     * by implicit diffusion to a Gaussian of [widthInRows] row spacings, then its double Fourier
     * series filtered at order [filterOrder] and read back at the coarse centers (either step left
     * out at zero). The diffusion conserves the area integral exactly; what the series filter moves,
     * which is no more than its highest waves carry, is put back as a constant, so the forcing's
     * integral is the ground's to rounding.
     */
    fun forcing(
        ground: FloatArray,
        widthInRows: Double = FILTER_WIDTH_IN_ROWS,
        filterOrder: Int = FILTER_ORDER
    ): DoubleArray {
        val mean = areaMean(ground)
        val spread = smooth(mean, widthInRows)
        if (filterOrder <= 0) return spread
        val filtered = evaluate(coefficients(spread, filterOrder), coarse.rows, coarse.columns)
        var before = 0.0
        var after = 0.0
        for (cell in spread.indices) {
            val area = coarse.cellAreaSquareMeters[cell / coarse.columns]
            before += spread[cell] * area
            after += filtered[cell] * area
        }
        val restored = (before - after) / coarse.totalAreaSquareMeters
        return DoubleArray(spread.size) { filtered[it] + restored }
    }

    /**
     * [field] on [coarse]'s centers spread by [FILTER_STEPS] implicit diffusion steps whose kernel
     * approaches a Gaussian of [widthInRows] row spacings' standard deviation on the ground
     * ([SphericalOperators.diffuse]). A new array; [field] is not touched.
     */
    fun smooth(field: DoubleArray, widthInRows: Double = FILTER_WIDTH_IN_ROWS): DoubleArray {
        if (widthInRows <= 0.0) return field.copyOf()
        return SphericalOperators(coarse).diffuse(field, widthInRows * coarse.rowSpacingMeters, FILTER_STEPS)
    }

    /**
     * The double Fourier coefficients of [field], a scalar on [coarse]'s centers, filtered at order
     * [filterOrder] (none at zero), with each pole's single value imposed after; see
     * [DoubleFourierCoefficients] for what they mean.
     */
    fun coefficients(field: DoubleArray, filterOrder: Int = 0): DoubleFourierCoefficients {
        require(field.size == coarse.cellCount) { "a coarse field of ${field.size} values" }
        val rows = coarse.rows
        val columns = coarse.columns
        val zonalCount = columns / 2 + 1
        val circle = 2 * rows
        // The zonal series of every row, as Z[m] with the half-cell origin folded in, so that the
        // row is Re sum_m w_m Z[m] exp(i m lambda) with lambda from the map's western edge.
        val zonalReal = DoubleArray(rows * zonalCount)
        val zonalImaginary = DoubleArray(rows * zonalCount)
        parallelChunks(0, rows) { startRow, endRow ->
            val scratch = ComplexFft.Scratch(columns)
            val real = DoubleArray(columns)
            val imaginary = DoubleArray(columns)
            for (row in startRow until endRow) {
                for (column in 0 until columns) {
                    real[column] = field[row * columns + column]
                    imaginary[column] = 0.0
                }
                coarse.rowTransform.forward(real, imaginary, scratch)
                for (wavenumber in 0 until zonalCount) {
                    val angle = -wavenumber * coarse.columnSpacingRadians / 2
                    val rotatedReal = real[wavenumber] * cos(angle) - imaginary[wavenumber] * sin(angle)
                    val rotatedImaginary = real[wavenumber] * sin(angle) + imaginary[wavenumber] * cos(angle)
                    zonalReal[row * zonalCount + wavenumber] = rotatedReal / columns
                    zonalImaginary[row * zonalCount + wavenumber] = rotatedImaginary / columns
                }
            }
        }
        // Down each continued meridian: rows 0..J-1 are the meridian, rows J..2J-1 the opposite one
        // read back up, whose wavenumber m is this one's times exp(i m pi).
        val meridionalCount = circle + 1
        val coefficientReal = DoubleArray(meridionalCount * zonalCount)
        val coefficientImaginary = DoubleArray(meridionalCount * zonalCount)
        val circleTransform = ComplexFft(circle)
        parallelChunks(0, zonalCount) { startWave, endWave ->
            val scratch = ComplexFft.Scratch(circle)
            val real = DoubleArray(circle)
            val imaginary = DoubleArray(circle)
            for (wavenumber in startWave until endWave) {
                val opposite = if (wavenumber % 2 == 0) 1.0 else -1.0
                for (index in 0 until circle) {
                    val row = if (index < rows) index else circle - 1 - index
                    val sign = if (index < rows) 1.0 else opposite
                    real[index] = sign * zonalReal[row * zonalCount + wavenumber]
                    imaginary[index] = sign * zonalImaginary[row * zonalCount + wavenumber]
                }
                circleTransform.forward(real, imaginary, scratch)
                // Meridional wavenumbers -J..J, the Nyquist wave split between its two ends, each
                // with the half-row origin folded in so the series reads colatitude from the pole.
                for (meridional in -rows..rows) {
                    val slot = if (meridional < 0) meridional + circle else meridional % circle
                    val share = if (meridional == -rows || meridional == rows) 0.5 else 1.0
                    val angle = -meridional * coarse.rowSpacingRadians / 2
                    val valueReal = real[slot] * share / circle
                    val valueImaginary = imaginary[slot] * share / circle
                    val at = (meridional + rows) * zonalCount + wavenumber
                    coefficientReal[at] = valueReal * cos(angle) - valueImaginary * sin(angle)
                    coefficientImaginary[at] = valueReal * sin(angle) + valueImaginary * cos(angle)
                }
                if (filterOrder > 0) {
                    for (meridional in -rows..rows) {
                        val at = (meridional + rows) * zonalCount + wavenumber
                        val response = filterResponse(meridional.toDouble() / rows, wavenumber.toDouble() / (columns / 2), filterOrder)
                        coefficientReal[at] *= response
                        coefficientImaginary[at] *= response
                    }
                }
                if (wavenumber > 0) singleValueAtThePoles(coefficientReal, coefficientImaginary, wavenumber, zonalCount)
            }
        }
        return DoubleFourierCoefficients(rows, columns, coefficientReal, coefficientImaginary)
    }

    /**
     * Makes zonal wave [wavenumber] vanish at both poles, so the series takes one value there.
     *
     * At colatitude zero the wave's amplitude is the sum of its meridional coefficients, and at pi
     * the alternating sum, so both vanish exactly when the even-numbered coefficients and the odd ones
     * each sum to zero. The smallest change that does it subtracts each parity's mean from its own.
     * Odd zonal waves already vanish there (their continued meridian is odd about the pole), so for
     * them this moves nothing but rounding.
     */
    private fun singleValueAtThePoles(real: DoubleArray, imaginary: DoubleArray, wavenumber: Int, zonalCount: Int) {
        val rows = coarse.rows
        for (parity in 0..1) {
            var sumReal = 0.0
            var sumImaginary = 0.0
            var count = 0
            for (meridional in -rows..rows) {
                if (((meridional % 2) + 2) % 2 != parity) continue
                val at = (meridional + rows) * zonalCount + wavenumber
                sumReal += real[at]
                sumImaginary += imaginary[at]
                count++
            }
            for (meridional in -rows..rows) {
                if (((meridional % 2) + 2) % 2 != parity) continue
                val at = (meridional + rows) * zonalCount + wavenumber
                real[at] -= sumReal / count
                imaginary[at] -= sumImaginary / count
            }
        }
    }

    /** [field], a scalar on [coarse]'s centers, read at every ground cell's center by its double Fourier series. */
    fun toGround(field: DoubleArray): FloatArray =
        evaluate(coefficients(field), groundRows, groundColumns).let { values -> FloatArray(values.size) { values[it].toFloat() } }

    /**
     * [field], a scalar the model made on [coarse]'s centers, read at every ground cell's center
     * through the forcing's own two filters, the diffusion of [FILTER_WIDTH_IN_ROWS] rows and the
     * series filter of [FILTER_ORDER]: the model was forced with nothing near the grid's limit, so
     * what it holds there is its differences' own and not the planet's, and carried up unfiltered it
     * left the coarse grid's period down the map (docs/DESIGN_LEDGER.md, A1-4).
     */
    fun outputToGround(field: DoubleArray): FloatArray =
        evaluate(coefficients(smooth(field), FILTER_ORDER), groundRows, groundColumns).let { values -> FloatArray(values.size) { values[it].toFloat() } }

    /**
     * The tangent vector [east], [north] (both at [coarse]'s centers) at every ground cell's center:
     * carried as its three Cartesian components, each a scalar, and read back in each ground cell's
     * own east and north. Returns the eastward then the northward component, row-major on the map.
     */
    fun vectorToGround(east: DoubleArray, north: DoubleArray): Pair<FloatArray, FloatArray> {
        val columns = coarse.columns
        val towardX = DoubleArray(coarse.cellCount)
        val towardY = DoubleArray(coarse.cellCount)
        val towardZ = DoubleArray(coarse.cellCount)
        for (cell in 0 until coarse.cellCount) {
            val row = cell / columns
            val longitude = (cell % columns + 0.5) * coarse.columnSpacingRadians
            val sinLatitude = sin(coarse.latitudeRadians[row])
            val cosLatitude = coarse.cosLatitude[row]
            towardX[cell] = -sin(longitude) * east[cell] - sinLatitude * cos(longitude) * north[cell]
            towardY[cell] = cos(longitude) * east[cell] - sinLatitude * sin(longitude) * north[cell]
            towardZ[cell] = cosLatitude * north[cell]
        }
        val groundX = toGround(towardX)
        val groundY = toGround(towardY)
        val groundZ = toGround(towardZ)
        val groundEast = FloatArray(groundRows * groundColumns)
        val groundNorth = FloatArray(groundRows * groundColumns)
        for (row in 0 until groundRows) {
            val latitude = PI / 2 - (row + 0.5) * PI / groundRows
            val sinLatitude = sin(latitude)
            val cosLatitude = cos(latitude)
            for (column in 0 until groundColumns) {
                val cell = row * groundColumns + column
                val longitude = (column + 0.5) * 2.0 * PI / groundColumns
                val x = groundX[cell].toDouble()
                val y = groundY[cell].toDouble()
                groundEast[cell] = (-sin(longitude) * x + cos(longitude) * y).toFloat()
                groundNorth[cell] = (-sinLatitude * (cos(longitude) * x + sin(longitude) * y) + cosLatitude * groundZ[cell]).toFloat()
            }
        }
        return groundEast to groundNorth
    }

    /**
     * [coefficients]' series at the centers of a grid of [targetRows] by [targetColumns] covering the
     * sphere as the map does, row-major: down the colatitudes first, one transform of the continued
     * meridian's length per zonal wave, then along each row. Any target size is exact: a wave beyond
     * what the target holds folds onto the one it equals at the target's points.
     */
    fun evaluate(coefficients: DoubleFourierCoefficients, targetRows: Int, targetColumns: Int): DoubleArray {
        val groundRows = targetRows
        val groundColumns = targetColumns
        val zonalCount = coefficients.zonalCount
        val coarseRows = coefficients.coarseRows
        val circle = 2 * groundRows
        // G[t][m] = sum_k c[k][m] exp(i k theta_t), theta_t = (t + 1/2) pi / H.
        val alongReal = DoubleArray(groundRows * zonalCount)
        val alongImaginary = DoubleArray(groundRows * zonalCount)
        val circleTransform = ComplexFft(circle)
        parallelChunks(0, zonalCount) { startWave, endWave ->
            val scratch = ComplexFft.Scratch(circle)
            val real = DoubleArray(circle)
            val imaginary = DoubleArray(circle)
            for (wavenumber in startWave until endWave) {
                real.fill(0.0)
                imaginary.fill(0.0)
                for (meridional in -coarseRows..coarseRows) {
                    val at = (meridional + coarseRows) * zonalCount + wavenumber
                    val angle = meridional * PI / circle
                    val valueReal = coefficients.real[at]
                    val valueImaginary = coefficients.imaginary[at]
                    val slot = meridional.mod(circle)
                    real[slot] += valueReal * cos(angle) - valueImaginary * sin(angle)
                    imaginary[slot] += valueReal * sin(angle) + valueImaginary * cos(angle)
                }
                circleTransform.inverse(real, imaginary, scratch)
                for (row in 0 until groundRows) {
                    alongReal[row * zonalCount + wavenumber] = real[row]
                    alongImaginary[row * zonalCount + wavenumber] = imaginary[row]
                }
            }
        }
        // Then Re sum_m w_m G[t][m] exp(i m lambda_c), lambda_c = (c + 1/2) 2 pi / W.
        val result = DoubleArray(groundRows * groundColumns)
        val rowTransform = ComplexFft(groundColumns)
        val coarseColumns = coefficients.coarseColumns
        parallelChunks(0, groundRows) { startRow, endRow ->
            val scratch = ComplexFft.Scratch(groundColumns)
            val real = DoubleArray(groundColumns)
            val imaginary = DoubleArray(groundColumns)
            for (row in startRow until endRow) {
                real.fill(0.0)
                imaginary.fill(0.0)
                for (wavenumber in 0 until zonalCount) {
                    val weight = DoubleFourierCoefficients.zonalWeight(wavenumber, coarseColumns)
                    val angle = wavenumber * PI / groundColumns
                    val valueReal = alongReal[row * zonalCount + wavenumber] * weight
                    val valueImaginary = alongImaginary[row * zonalCount + wavenumber] * weight
                    val slot = wavenumber % groundColumns
                    real[slot] += valueReal * cos(angle) - valueImaginary * sin(angle)
                    imaginary[slot] += valueReal * sin(angle) + valueImaginary * cos(angle)
                }
                rowTransform.inverse(real, imaginary, scratch)
                for (column in 0 until groundColumns) result[row * groundColumns + column] = real[column]
            }
        }
        return result
    }

    companion object {
        /**
         * The series filter's order, `p` in `exp(-alpha eta^p)`. The diffusion leaves a little of the
         * waves nearest the grid's limit, since a second difference damps them less than the true
         * Laplacian would, and those are the waves that put the coarse grid's period into what comes
         * up. At order eight a wave at a quarter of the limit keeps all but 0.06% of itself and one at
         * half the limit 87%, so on the sweep in `AtmosphereRemapTest` the storm track's bump comes
         * back 0.94% off with the filter and without it, the step overshoots 1.67% against 1.42%,
         * and the period goes. Other orders were not swept beside the diffusion. See
         * docs/DESIGN_LEDGER.md, A1-2.
         */
        const val FILTER_ORDER = 8

        /**
         * The forcing's diffusion, as the standard deviation of the Gaussian its kernel approaches, in
         * coarse row spacings: the narrowest at which a coast's step carried down and back up
         * overshoots by no more than [STEP_OVERSHOOT_BOUND], on the sweep in `AtmosphereRemapTest`.
         */
        const val FILTER_WIDTH_IN_ROWS = 0.8

        /**
         * Implicit steps the diffusion is split into. Four, as `SphereBlur` takes across rows: one
         * backward step spreads a spike into a two-sided exponential, and four of a quarter the
         * strength are within a few percent of the Gaussian of the same variance.
         */
        const val FILTER_STEPS = 4

        /**
         * The filter's strength, `alpha`: the negative log of the double's unit roundoff, so the
         * last wave the grid holds is multiplied by its machine zero, as Gelb and Gottlieb choose it.
         */
        val FILTER_STRENGTH: Double = -ln(DOUBLE_UNIT_ROUNDOFF)

        /** Binary64's unit roundoff, `2^-53`. */
        private const val DOUBLE_UNIT_ROUNDOFF = 1.1102230246251565e-16

        /**
         * The largest overshoot, as a share of the step, a coast may leave in forcing carried down
         * and up: two percent, 0.4 C on the 18 to 20 C July contrast across a west coast that
         * docs/DESIGN_LEDGER.md, A1-1, reads off Nakamura and Miyasaka, a fifth of the 2 C contour
         * interval that figure was read at.
         */
        const val STEP_OVERSHOOT_BOUND = 0.02

        /**
         * The filter's factor on the wave whose meridional and zonal wavenumbers are [meridionalShare]
         * and [zonalShare] of the grid's limits.
         */
        fun filterResponse(meridionalShare: Double, zonalShare: Double, order: Int): Double {
            val distance = sqrt(meridionalShare * meridionalShare + zonalShare * zonalShare)
            return exp(-FILTER_STRENGTH * distance.pow(order))
        }

        /**
         * For each of [coarseCount] equal intervals of a span, the [fineCount] equal intervals it
         * overlaps, and each overlap's [measure] given its two ends in radians along a [span] of
         * pi (colatitude) or 2 pi (longitude).
         */
        private fun overlaps(coarseCount: Int, fineCount: Int, span: Double, measure: (Double, Double) -> Double): Overlaps {
            val first = IntArray(coarseCount)
            val count = IntArray(coarseCount)
            val offset = IntArray(coarseCount)
            val weights = ArrayList<Double>()
            val total = DoubleArray(coarseCount)
            for (coarseIndex in 0 until coarseCount) {
                val low = coarseIndex * span / coarseCount
                val high = (coarseIndex + 1) * span / coarseCount
                val firstFine = floor(coarseIndex.toDouble() * fineCount / coarseCount).toInt()
                val lastFine = (ceil((coarseIndex + 1).toDouble() * fineCount / coarseCount).toInt() - 1).coerceAtMost(fineCount - 1)
                first[coarseIndex] = firstFine
                offset[coarseIndex] = weights.size
                var sum = 0.0
                for (fine in firstFine..lastFine) {
                    val overlapLow = maxOf(low, fine * span / fineCount)
                    val overlapHigh = minOf(high, (fine + 1) * span / fineCount)
                    val weight = if (overlapHigh > overlapLow) measure(overlapLow, overlapHigh) else 0.0
                    weights.add(weight)
                    sum += weight
                }
                count[coarseIndex] = lastFine - firstFine + 1
                total[coarseIndex] = sum
            }
            return Overlaps(first, count, offset, weights.toDoubleArray(), total)
        }
    }
}

/**
 * A scalar's double Fourier series on the sphere:
 * `f(theta, lambda) = Re sum_{m=0}^{N/2} w_m sum_{k=-J}^{J} c[k][m] exp(i k theta) exp(i m lambda)`,
 * theta the colatitude from the north pole and lambda the longitude from the map's western edge,
 * both in radians, with `w_0 = w_{N/2} = 1` and `w_m = 2` between ([zonalWeight]); J is
 * [coarseRows] and N [coarseColumns]. [real] and [imaginary] hold c, row k + J, column m.
 */
class DoubleFourierCoefficients(
    val coarseRows: Int,
    val coarseColumns: Int,
    val real: DoubleArray,
    val imaginary: DoubleArray
) {
    /** Zonal waves 0..N/2. */
    val zonalCount: Int get() = coarseColumns / 2 + 1

    /** Meridional waves -J..J. */
    val meridionalCount: Int get() = 2 * coarseRows + 1

    companion object {
        /** The weight a real field's zonal wave [wavenumber] carries in a half spectrum of [columns]. */
        fun zonalWeight(wavenumber: Int, columns: Int): Double =
            if (wavenumber == 0 || 2 * wavenumber == columns) 1.0 else 2.0
    }
}
