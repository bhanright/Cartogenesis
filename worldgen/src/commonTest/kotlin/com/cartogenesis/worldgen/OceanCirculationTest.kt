package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.pipeline.OceanCirculation
import com.cartogenesis.worldgen.pipeline.OceanStencil
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The circulation's discrete operator against Stommel's analytic basin, on synthetic grids whose
 * cells are square on the ground (aspect 1.0) and twice as wide as tall (aspect 0.5, today's map at
 * N by N). Nothing here is counted in cells: every basin is given in kilometers and every grid's
 * spacing follows from it.
 */
class OceanCirculationTest {

    /** β at 30 degrees on this generator's default world, per meter-second. */
    private val beta = 6.61e-11

    /** The bottom drag the stage uses, per second: see `OceanStage.BOTTOM_DRAG_PER_S`. */
    private val drag = 9.91e-7

    /**
     * A closed basin of [widthMeters] by [heightMeters] between two land columns and the grid's
     * two pole walls, forced by `F0 sin(π y / b)`: Stommel's own problem.
     */
    private class Basin(
        val stencil: OceanStencil,
        val levels: List<OceanStencil>,
        val across: Int,
        val down: Int,
        val dx: Double,
        val dy: Double,
        val forcingAmplitude: Double
    )

    private fun basin(spacingMeters: Double, aspect: Double, forcingAmplitude: Double): Basin {
        val dx = spacingMeters
        val dy = spacingMeters * aspect
        // A basin 3,200 km by 1,600 km at 6.25 or 25 km a cell, counts that halve down to a coarse
        // grid, and a land column at each edge.
        val across = (BASIN_WIDTH_M / dx).toInt()
        val down = (BASIN_HEIGHT_M / dy).toInt()
        val isWater = BooleanArray(across * down) { val column = it % across; column != 0 && column != across - 1 }
        val b = down * dy
        val forcing = DoubleArray(across * down) { cell ->
            val y = (down - 0.5 - cell / across) * dy
            forcingAmplitude * sin(PI * y / b)
        }
        val finest = OceanCirculation.stencil(across, down, dx, dy, isWater, DoubleArray(down) { beta }, drag, forcing)
        val levels = OceanCirculation.levels(finest, dx, dy, everyCellWater = true) { a, d, w ->
            OceanCirculation.stencil(a, d, dx * across / a, dy * down / d, w, DoubleArray(d) { beta }, drag, DoubleArray(a * d))
        }
        return Basin(finest, levels, across, down, dx, dy, forcingAmplitude)
    }

    private fun solve(basin: Basin): FloatArray = OceanCirculation.solve(
        basin.levels, FloatArray(basin.across * basin.down), OceanCirculation.RESIDUAL_TOLERANCE * 1e-2,
        poleIsWall = true, bodies = null
    ) { stencil, values, passes -> OceanCirculation.relax(stencil, values, passes); values }.values

    /** Stommel's solution at a point: walls at x = 0 and λ, and at y = 0 and b. */
    private fun analytic(basin: Basin, x: Double, y: Double): Double {
        val lambda = (basin.across - 1) * basin.dx
        val b = basin.down * basin.dy
        val alpha = beta / drag
        val k = PI / b
        val particular = basin.forcingAmplitude / drag / (k * k)
        val mPlus = (-alpha + sqrt(alpha * alpha + 4 * k * k)) / 2
        val mMinus = (-alpha - sqrt(alpha * alpha + 4 * k * k)) / 2
        val p = particular * (1 - exp(mMinus * lambda)) / (exp(mPlus * lambda) - exp(mMinus * lambda))
        val q = particular - p
        return sin(k * y) * (-particular + p * exp(mPlus * x) + q * exp(mMinus * x))
    }

    /**
     * On a grid that holds the Stommel layer (more than two cells to `δ_S = r/β`, 15 km here), the discrete
     * stream function matches Stommel's to within the discretization's own error.
     *
     * The x-difference is fitted and exact at the nodes for the boundary layer; what is left is the
     * y-difference's `(kΔy)²/12` on the forcing's sine and the relaxation's thousandth, below a
     * tenth of a percent at these spacings. The bar is one percent.
     */
    @Test
    fun `the discrete basin matches Stommel's analytic one`() {
        for (aspect in listOf(1.0, 0.5)) {
            val basin = basin(spacingMeters = 6_250.0, aspect = aspect, forcingAmplitude = -1e-12)
            val stream = solve(basin)
            var worst = 0.0
            var largest = 0.0
            for (row in 0 until basin.down) {
                for (column in 1 until basin.across - 1) {
                    val exact = analytic(basin, column * basin.dx, (basin.down - 0.5 - row) * basin.dy)
                    worst = max(worst, abs(stream[row * basin.across + column] - exact))
                    largest = max(largest, abs(exact))
                }
            }
            println("OCEAN Stommel basin at aspect $aspect: largest departure ${worst / largest} of the largest ψ")
            assertTrue(worst / largest < ONE_PERCENT, "at aspect $aspect the basin departs from Stommel's by ${worst / largest}")
        }
    }

    /**
     * The three facts of a wind-driven gyre, on the solve's own kind of grid and at both aspects.
     * A negative curl, the subtropical gyre's in the north:
     *  - the basin turns anticyclonically, clockwise in the north: eastward in its northern part,
     *    westward in its southern;
     *  - the interior flows equatorward at Sverdrup's `v = F / β`;
     *  - the western boundary carries the whole interior's transport back, poleward.
     */
    @Test
    fun `a basin under a negative curl turns clockwise, with Sverdrup's interior and a western return`() {
        for ((spacing, aspect) in listOf(6_250.0 to 1.0, 6_250.0 to 0.5, 25_000.0 to 1.0, 25_000.0 to 0.5)) {
            val amplitude = -1e-12
            val basin = basin(spacing, aspect, amplitude)
            val stream = solve(basin)
            val across = basin.across
            val middleRow = basin.down / 2
            val northRow = basin.down / 4
            val southRow = basin.down * 3 / 4
            val middleColumn = across / 2
            fun eastward(row: Int, column: Int): Double =
                -(stream[(row - 1) * across + column] - stream[(row + 1) * across + column]) / (2 * basin.dy)
            fun northward(row: Int, column: Int): Double =
                (stream[row * across + column + 1] - stream[row * across + column - 1]) / (2 * basin.dx)
            assertTrue(eastward(northRow, middleColumn) > 0.0 && eastward(southRow, middleColumn) < 0.0,
                "at ${spacing / 1000} km and aspect $aspect the basin does not turn clockwise")

            val sverdrup = amplitude / beta
            val interior = northward(middleRow, middleColumn)
            println("OCEAN interior at ${spacing / 1000} km, aspect $aspect: v = $interior m/s against Sverdrup's $sverdrup")
            assertTrue(abs(interior - sverdrup) < abs(sverdrup) * FIVE_PERCENT,
                "the interior flows at $interior m/s where Sverdrup's balance gives $sverdrup")

            // Transport across the middle row, west of the interior and east of it: equal and opposite.
            var returnTransport = 0.0
            var interiorTransport = 0.0
            val boundaryEdge = (WESTERN_LAYERS * drag / beta / basin.dx).toInt().coerceAtLeast(2)
            for (column in 1 until across - 1) {
                val flux = northward(middleRow, column) * basin.dx
                if (column <= boundaryEdge) returnTransport += flux else interiorTransport += flux
            }
            println("OCEAN transport at ${spacing / 1000} km, aspect $aspect: western ${returnTransport} m²/s against interior $interiorTransport")
            assertTrue(returnTransport > 0.0, "the western boundary does not carry water poleward")
            assertTrue(abs(returnTransport + interiorTransport) < abs(interiorTransport) * FIVE_PERCENT,
                "the western boundary returns $returnTransport m²/s of the interior's $interiorTransport")
        }
    }

    /**
     * Isotropy on the ground: a source at the center of a round basin, with β off, spreads as far
     * east-west as north-south in kilometers, at aspect 1.0 and at 0.5.
     *
     * A source that varies in space, because a uniform one in a round basin is solved by
     * `A (R² - x² - y²)` under the isotropic operator and under `4ψ_xx + ψ_yy` alike, and cannot
     * tell them apart. The control is the old operator, a weight of a quarter on every neighbor
     * whatever the cell's shape, which on 2:1 cells is `4ψ_xx + ψ_yy` on the ground.
     */
    @Test
    fun `a source spreads as far east-west as north-south on the ground`() {
        for (aspect in listOf(1.0, 0.5)) {
            val (across, down, width, height, stream) = sourceBasin(aspect, equalWeights = false)
            val cell = max(width, height)
            println("OCEAN isotropy at aspect $aspect: half-height extent ${width / 1000} km east-west, ${height / 1000} km north-south")
            assertTrue(abs(width - height) <= cell, "at aspect $aspect the source spreads ${width / 1000} km east-west and ${height / 1000} km north-south")
            assertTrue(across > 0 && down > 0 && stream.isNotEmpty())
        }
        val control = sourceBasin(0.5, equalWeights = true)
        println("OCEAN isotropy control, equal weights on 2:1 cells: ${control.width / 1000} km against ${control.height / 1000} km")
        assertTrue(abs(control.width - control.height) > max(control.width, control.height) * 0.3,
            "the equal-weight control should spread twice as far east-west, and did not")
    }

    private data class Spread(val across: Int, val down: Int, val width: Double, val height: Double, val stream: FloatArray)

    private fun sourceBasin(aspect: Double, equalWeights: Boolean): Spread {
        val dx = 20_000.0
        val dy = dx * aspect
        val radius = 1_000e3
        val across = 128
        val down = (2 * radius / dy).toInt() + 4
        val centerX = across / 2 * dx
        val centerY = down / 2 * dy
        val isWater = BooleanArray(across * down) { cell ->
            val x = (cell % across) * dx - centerX
            val y = (cell / across) * dy - centerY
            x * x + y * y < radius * radius
        }
        val sourceWidth = 150e3
        val forcing = DoubleArray(across * down) { cell ->
            val x = (cell % across) * dx - centerX
            val y = (cell / across) * dy - centerY
            -1e-12 * exp(-(x * x + y * y) / (2 * sourceWidth * sourceWidth))
        }
        var stencil = OceanCirculation.stencil(across, down, dx, dy, isWater, DoubleArray(down), drag, forcing)
        if (equalWeights) {
            val quarter = FloatArray(across * down) { 0.25f }
            stencil = OceanStencil(across, down, isWater, quarter, quarter, quarter, quarter, stencil.forcing, stencil.centreWeight)
        }
        val stream = FloatArray(across * down)
        OceanCirculation.relaxToConvergence(stencil, stream, 1e-6)
        val peakCell = stream.indices.maxByOrNull { abs(stream[it]) }!!
        val peak = abs(stream[peakCell])
        val peakRow = peakCell / across
        val peakColumn = peakCell % across
        fun extent(step: Int, spacing: Double, along: (Int) -> Int): Double {
            var offset = 0
            while (abs(stream[along(offset + step)]) >= peak / 2) offset += step
            val inside = abs(stream[along(offset)])
            val outside = abs(stream[along(offset + step)])
            return (abs(offset) + (inside - peak / 2) / (inside - outside)) * spacing
        }
        val width = extent(1, dx) { peakRow * across + peakColumn + it } + extent(-1, dx) { peakRow * across + peakColumn + it }
        val height = extent(1, dy) { (peakRow + it) * across + peakColumn } + extent(-1, dy) { (peakRow + it) * across + peakColumn }
        return Spread(across, down, width, height, stream)
    }

    private companion object {
        const val ONE_PERCENT = 0.01
        const val BASIN_WIDTH_M = 3_200e3
        const val BASIN_HEIGHT_M = 1_600e3
        const val FIVE_PERCENT = 0.05

        /**
         * How many Stommel layers wide the western boundary is taken to be when its transport is
         * summed: the layer decays as `exp(-x/δ_S)`, so five hold all but `e⁻⁵`, 0.7%, of it.
         */
        const val WESTERN_LAYERS = 5.0
    }
}
