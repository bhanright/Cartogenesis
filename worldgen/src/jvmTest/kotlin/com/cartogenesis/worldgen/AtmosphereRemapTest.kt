package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldScale
import com.cartogenesis.worldgen.pipeline.AtmosphereRemap
import com.cartogenesis.worldgen.pipeline.PressureWind
import com.cartogenesis.worldgen.pipeline.SphericalGrid
import com.cartogenesis.worldgen.pipeline.SphericalOperators
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The remapping between the map's grid and the atmosphere's: conservation down, exactness and
 * smoothness up, the coast's ringing bounded, and no trace of the coarse grid.
 *
 * The ground is 1,024 by 512 on Earth's planet and the coarse grid the atmosphere's own, so the two
 * do not nest (512 rows against 90) and every overlap weight is a fraction.
 */
class AtmosphereRemapTest {

    private val scale = WorldScale()
    private val radius = scale.radiusMeters
    private val coarse = SphericalGrid.forAtmosphere(scale)
    private val groundColumns = 1024
    private val groundRows = 512
    private val remap = AtmosphereRemap(groundColumns, groundRows, coarse)
    private val ground = SphericalGrid.forGround(groundColumns, groundRows, scale)
    private val deformationRadiusMeters = PressureWind.rossbyRadiusKm() * 1_000.0

    private fun groundField(function: (Double, Double) -> Double) = FloatArray(groundRows * groundColumns) { cell ->
        function(ground.latitudeRadians[cell / groundColumns], SphereFields.longitude(ground, cell % groundColumns)).toFloat()
    }

    private fun groundTotal(field: FloatArray): Double {
        var total = 0.0
        for (cell in field.indices) total += field[cell] * ground.cellAreaSquareMeters[cell / groundColumns]
        return total
    }

    private fun coarseTotal(field: DoubleArray): Double {
        var total = 0.0
        for (cell in field.indices) total += field[cell] * coarse.cellAreaSquareMeters[cell / coarse.columns]
        return total
    }

    /**
     * A continent whose coast is a step from one to zero: a cap of 2,500 km about 30 N with a coast
     * that swings 15% in and out five times round, so the step crosses the coarse grid at every
     * bearing.
     */
    private fun continent(latitude: Double, longitude: Double): Double {
        val center = SphereFields.unit(PI / 6, 2.0)
        val here = SphereFields.unit(latitude, longitude)
        val angle = kotlin.math.acos(SphereFields.dot(here, center).coerceIn(-1.0, 1.0))
        val bearing = atan2(SphereFields.dot(here, SphereFields.east(2.0)), SphereFields.dot(here, SphereFields.north(PI / 6, 2.0)))
        val coastMeters = 2_500_000.0 * (1 + 0.15 * sin(5 * bearing))
        return if (angle * radius < coastMeters) 1.0 else 0.0
    }

    @Test
    fun `carrying down conserves the area integral and keeps a constant`() {
        val random = java.util.Random(5)
        val field = FloatArray(groundRows * groundColumns) { (random.nextGaussian() + 2.0).toFloat() }
        val down = remap.areaMean(field)
        val relative = abs(coarseTotal(down) - groundTotal(field)) / groundTotal(field)
        val filtered = remap.forcing(field)
        val filteredRelative = abs(coarseTotal(filtered) - coarseTotal(down)) / coarseTotal(down)
        println("REMAP conservation: down %.2e of the total, the filter %.2e".format(relative, filteredRelative))
        assertTrue(relative < 1e-13, "carrying down changes the area integral by $relative")
        assertTrue(filteredRelative < 1e-13, "the filter changes the area integral by $filteredRelative")
        val ones = remap.areaMean(FloatArray(groundRows * groundColumns) { 1f })
        assertTrue(ones.all { abs(it - 1.0) < 1e-12 }, "a constant does not come down as itself")
        assertTrue(remap.forcing(FloatArray(groundRows * groundColumns) { 1f }).all { abs(it - 1.0) < 1e-12 }, "the filter moves a constant")
        val up = remap.toGround(DoubleArray(coarse.cellCount) { 1.0 })
        assertTrue(up.all { abs(it - 1f) < 1e-6f }, "a constant does not come up as itself")
        // And with nesting grids, 512 by 256 onto 128 by 64, where the weights are block sums.
        val nested = AtmosphereRemap(512, 256, SphericalGrid(64, 128, radius))
        val nestedGround = SphericalGrid.forGround(512, 256, scale)
        val nestedField = FloatArray(512 * 256) { (random.nextGaussian() + 2.0).toFloat() }
        var nestedTotal = 0.0
        for (cell in nestedField.indices) nestedTotal += nestedField[cell] * nestedGround.cellAreaSquareMeters[cell / 512]
        val nestedDown = nested.areaMean(nestedField)
        var nestedCoarse = 0.0
        for (cell in nestedDown.indices) nestedCoarse += nestedDown[cell] * nested.coarse.cellAreaSquareMeters[cell / 128]
        println("REMAP conservation on nesting grids: %.2e".format(abs(nestedCoarse - nestedTotal) / nestedTotal))
        assertTrue(abs(nestedCoarse - nestedTotal) / nestedTotal < 1e-13)
    }

    /** A harmonic the coarse grid resolves comes up through its own samples to rounding, poles included. */
    @Test
    fun `a field the coarse grid resolves comes up exactly`() {
        for ((degree, order) in listOf(3 to 0, 7 to 2, 12 to 5, 20 to 11, 30 to 1)) {
            val harmonic = SphereFields.Harmonic(degree, order, radius)
            val up = remap.toGround(SphereFields.atCenters(coarse, harmonic::value))
            val exact = groundField(harmonic::value)
            var worst = 0.0
            var largest = 0.0
            for (cell in up.indices) {
                worst = maxOf(worst, abs(up[cell] - exact[cell]).toDouble())
                largest = maxOf(largest, abs(exact[cell]).toDouble())
            }
            println("REMAP up, harmonic l=$degree m=$order: worst %.2e of its largest value".format(worst / largest))
            assertTrue(worst / largest < 1e-6, "l=$degree m=$order comes up off by ${worst / largest}")
        }
    }

    /**
     * The derivatives of what comes up, not only its values: a bump of the storm track's width,
     * sampled on the coarse grid and carried up, has the bump's own gradient on the ground; a
     * bilinear interpolant's gradient is a staircase of the coarse cells and misses it.
     */
    @Test
    fun `the gradient of what comes up is the field's own`() {
        val bump = SphereFields.Bump(PI / 4, 1.0, deformationRadiusMeters, radius)
        val exact = SphericalOperators.Vector(
            SphereFields.atCenters(ground, bump::eastward), SphereFields.atFaces(ground, bump::northward)
        )
        val samples = SphereFields.atCenters(coarse, bump::value)
        val operators = SphericalOperators(ground)
        val fourier = operators.gradient(remap.toGround(samples).map { it.toDouble() }.toDoubleArray())
        val bilinear = operators.gradient(bilinear(samples).map { it.toDouble() }.toDoubleArray())
        val fourierError = SphereFields.relativeError(ground, fourier, exact)
        val bilinearError = SphereFields.relativeError(ground, bilinear, exact)
        val groundAlone = SphereFields.relativeError(ground, operators.gradient(SphereFields.atCenters(ground, bump::value)), exact)
        println("REMAP gradient of a bump of sigma L_d carried up: double Fourier %.2e, bilinear %.2e; the ground's own operator on the exact field %.2e"
            .format(fourierError, bilinearError, groundAlone))
        assertTrue(fourierError < SphericalGrid.OPERATOR_TOLERANCE, "the carried-up field's gradient errs by $fourierError")
        assertTrue(bilinearError > 2 * fourierError, "the bilinear control's gradient is as good: $bilinearError")
    }

    /**
     * Forcing carried down, filtered and carried back up: how much of a smooth feature survives at
     * each forcing scale, and how far a coast's step overshoots, against the filter's order. The
     * order in use must hold the step to [AtmosphereRemap.STEP_OVERSHOOT_BOUND] and be the highest
     * on the sweep that does, since every lower order costs the smooth features more.
     */
    @Test
    fun `the forcing filter bounds a coast's ringing`() {
        val step = groundField(::continent)
        val scales = listOf("storm track L_d" to deformationRadiusMeters, "monsoon 1,000 km" to 1_000_000.0, "subtropical cell 2,000 km" to 2_000_000.0)
        val bumpFields = scales.map { (_, sigma) -> groundField(SphereFields.Bump(PI / 4, 1.0, sigma, radius)::value) }
        var chosen: Pair<Double, Double>? = null
        var narrowestPassing = Double.NaN
        for (order in listOf(0, AtmosphereRemap.FILTER_ORDER)) {
            for (width in listOf(0.0, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.25)) {
                val up = remap.toGround(remap.forcing(step, width, order))
                val overshoot = maxOf(up.max() - 1.0, -up.min().toDouble())
                val losses = bumpFields.map { exact ->
                    val carried = remap.toGround(remap.forcing(exact, width, order))
                    var error = 0.0
                    var norm = 0.0
                    for (cell in carried.indices) {
                        val area = ground.cellAreaSquareMeters[cell / groundColumns]
                        error += area * (carried[cell] - exact[cell]).let { it * it }
                        norm += area * exact[cell] * exact[cell]
                    }
                    sqrt(error / norm)
                }
                val down = CoarsePeriod.reading(up, groundColumns, groundRows, coarse.rows, alongRows = false)
                val along = CoarsePeriod.reading(up, groundColumns, groundRows, coarse.columns, alongRows = true)
                println("REMAP diffusion %.2f rows, series filter ${if (order == 0) "none" else "order $order"}: step overshoot %.2f%%; ".format(width, overshoot * 100) +
                    scales.indices.joinToString { "${scales[it].first} %.2f%% off".format(losses[it] * 100) } + "; period down $down, along $along")
                if (order == AtmosphereRemap.FILTER_ORDER) {
                    if (overshoot <= AtmosphereRemap.STEP_OVERSHOOT_BOUND && narrowestPassing.isNaN()) narrowestPassing = width
                    if (width == AtmosphereRemap.FILTER_WIDTH_IN_ROWS) chosen = overshoot to losses.first()
                }
            }
        }
        val (overshoot, loss) = chosen ?: error("the filter's width ${AtmosphereRemap.FILTER_WIDTH_IN_ROWS} is not on the sweep")
        println("REMAP the filter in use, %.2f rows and order ${AtmosphereRemap.FILTER_ORDER}: overshoot %.2f%%, the storm track's bump %.2f%% off; the narrowest width within the bound %.2f"
            .format(AtmosphereRemap.FILTER_WIDTH_IN_ROWS, overshoot * 100, loss * 100, narrowestPassing))
        assertTrue(overshoot <= AtmosphereRemap.STEP_OVERSHOOT_BOUND, "a coast overshoots by $overshoot with the filter in use")
        assertEquals(narrowestPassing, AtmosphereRemap.FILTER_WIDTH_IN_ROWS, "the filter is not the narrowest that holds the bound")
        assertTrue(loss <= SphericalGrid.OPERATOR_TOLERANCE, "the forcing path loses $loss of the storm track's bump, past the tolerance")
    }

    /**
     * The coarse grid leaves no period in what comes up (conventions rule 13): the phase statistic on
     * a coast's step and on a smooth bump, carried up by double Fourier, passes down the map and along
     * it, and fails on the nearest coarse cell and on bilinear interpolation, which it exists to catch.
     */
    @Test
    fun `nothing carried up shows the coarse grid's period`() {
        val stepCoarse = remap.forcing(groundField(::continent))
        val bump = SphereFields.Bump(0.6, 3.0, 2_000_000.0, radius)
        val bumpCoarse = remap.forcing(groundField(bump::value))
        for ((name, field) in listOf("coast" to stepCoarse, "bump" to bumpCoarse)) {
            val readings = listOf("double Fourier" to remap.toGround(field), "nearest" to nearest(field), "bilinear" to bilinear(field)).map { (method, up) ->
                val down = CoarsePeriod.reading(up, groundColumns, groundRows, coarse.rows, alongRows = false)
                val along = CoarsePeriod.reading(up, groundColumns, groundRows, coarse.columns, alongRows = true)
                println("PERIOD $name, $method: down the map $down, along it $along")
                Triple(method, down, along)
            }
            val (_, fourierDown, fourierAlong) = readings[0]
            if (name == "coast") {
                // Recorded at A1-4, where the statistic reads lines against their neighbors: the
                // step, filtered to a 2% overshoot, still rings at the coarse grid's own scale down
                // the map, a trace the cell-by-cell error had hidden (docs/TODO.md).
                KnownFailures.expect("A1-4: a coast's step rings at the coarse grid's scale", "down x1.095 (bar 1.071)") {
                    if (!fourierDown.passes || !fourierAlong.passes) {
                        throw RecordedViolation(
                            "the coast carried up by double Fourier shows the coarse grid: $fourierDown, $fourierAlong",
                            (if (!fourierDown.passes) "down $fourierDown" else "") + (if (!fourierAlong.passes) " along $fourierAlong" else "")
                        )
                    }
                }
            } else {
                assertTrue(fourierDown.passes && fourierAlong.passes, "the $name carried up by double Fourier shows the coarse grid: $fourierDown, $fourierAlong")
            }
            for ((method, down, along) in readings.drop(1)) {
                assertTrue(!down.passes && !along.passes, "the detector misses $method on the $name: $down, $along")
            }
        }
    }

    /** A scalar carried up takes one value at each pole: its series there does not depend on longitude. */
    @Test
    fun `a scalar comes up single-valued at the poles`() {
        val field = remap.forcing(groundField(::continent)).let { coarseField ->
            // Something lopsided at the pole too, so the condition has work to do.
            DoubleArray(coarseField.size) { coarseField[it] + 0.3 * cos(2 * SphereFields.longitude(coarse, it % coarse.columns)) * (it / coarse.columns).let { row -> if (row < 2) 1.0 else 0.0 } }
        }
        val coefficients = remap.coefficients(field)
        var worst = 0.0
        for (wavenumber in 1 until coefficients.zonalCount) {
            for (pole in listOf(0.0, PI)) {
                var real = 0.0
                var imaginary = 0.0
                for (meridional in -coefficients.coarseRows..coefficients.coarseRows) {
                    val at = (meridional + coefficients.coarseRows) * coefficients.zonalCount + wavenumber
                    real += coefficients.real[at] * cos(meridional * pole) - coefficients.imaginary[at] * sin(meridional * pole)
                    imaginary += coefficients.real[at] * sin(meridional * pole) + coefficients.imaginary[at] * cos(meridional * pole)
                }
                worst = maxOf(worst, sqrt(real * real + imaginary * imaginary))
            }
        }
        println("REMAP the poles: the largest zonal wave left at either pole %.2e".format(worst))
        assertTrue(worst < 1e-12, "a zonal wave of $worst survives at a pole")
    }

    /**
     * A wind crossing the poles comes up whole when carried as Cartesian components; carried as its
     * eastward and northward components, each a scalar, it is torn at the poles, where those
     * components change sign across the pole and are not scalars at all.
     */
    @Test
    fun `a vector comes up regular at the poles`() {
        val rotation = SphereFields.Rotation(doubleArrayOf(8e-6, 3e-6, 5e-6), radius)
        val east = SphereFields.atCenters(coarse, rotation::eastward)
        val north = SphereFields.atCenters(coarse, rotation::northward)
        val (upEast, upNorth) = remap.vectorToGround(east, north)
        val naiveEast = remap.toGround(east)
        val naiveNorth = remap.toGround(north)
        var worst = 0.0
        var worstNaive = 0.0
        var speed = 0.0
        for (cell in upEast.indices) {
            val latitude = ground.latitudeRadians[cell / groundColumns]
            val longitude = SphereFields.longitude(ground, cell % groundColumns)
            val exactEast = rotation.eastward(latitude, longitude)
            val exactNorth = rotation.northward(latitude, longitude)
            speed = maxOf(speed, sqrt(exactEast * exactEast + exactNorth * exactNorth))
            worst = maxOf(worst, abs(upEast[cell] - exactEast), abs(upNorth[cell] - exactNorth))
            worstNaive = maxOf(worstNaive, abs(naiveEast[cell] - exactEast), abs(naiveNorth[cell] - exactNorth))
        }
        println("REMAP a wind over the poles: Cartesian %.2e of the fastest, as two scalars %.2e".format(worst / speed, worstNaive / speed))
        assertTrue(worst / speed < 1e-5, "the wind carried as Cartesian components is off by ${worst / speed}")
        assertTrue(worstNaive / speed > 100 * worst / speed, "carrying the components as scalars is as good, so the test shows nothing")
    }

    /** The coarse cell each ground cell falls in. */
    private fun nearest(field: DoubleArray) = FloatArray(groundRows * groundColumns) { cell ->
        val row = (cell / groundColumns) * coarse.rows / groundRows
        val column = (cell % groundColumns) * coarse.columns / groundColumns
        field[row * coarse.columns + column].toFloat()
    }

    /** Bilinear between coarse centers, wrapping round the rows and held at the outermost rows. */
    private fun bilinear(field: DoubleArray) = FloatArray(groundRows * groundColumns) { cell ->
        val rowPosition = ((cell / groundColumns) + 0.5) * coarse.rows / groundRows - 0.5
        val columnPosition = ((cell % groundColumns) + 0.5) * coarse.columns / groundColumns - 0.5
        val northRow = floor(rowPosition).toInt().coerceIn(0, coarse.rows - 1)
        val southRow = (northRow + 1).coerceAtMost(coarse.rows - 1)
        val down = (rowPosition - northRow).coerceIn(0.0, 1.0)
        val westColumn = Math.floorMod(floor(columnPosition).toInt(), coarse.columns)
        val eastColumn = (westColumn + 1) % coarse.columns
        val across = columnPosition - floor(columnPosition)
        fun at(row: Int, column: Int) = field[row * coarse.columns + column]
        val northValue = at(northRow, westColumn) * (1 - across) + at(northRow, eastColumn) * across
        val southValue = at(southRow, westColumn) * (1 - across) + at(southRow, eastColumn) * across
        (northValue * (1 - down) + southValue * down).toFloat()
    }
}
