package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.FlatRouting
import com.cartogenesis.worldgen.pipeline.FlowRouting
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Water runs down the ground's own slope, on this map's rectangular cells.
 *
 * A step north or south is half as long on the ground as a step east or west, so a router that
 * weighs every orthogonal step alike reads a north-south slope at half its gradient and sends
 * water toward the rows: on a plane falling at 45 degrees on the ground it routed at 14 (Audit III's
 * B-D2 and the X1d row in `docs/TODO.md`). Two operators carry that ruler, and each
 * is read here on ground built so the right answer is known: the facet rule on planes at a dozen
 * bearings, and the potential across a raised flat on a flat round on the ground.
 */
class RoutingGroundTest {

    private val config = WorldGenConfig(seed = 7L, width = SIDE, height = SIDE)
    private val rowScale = config.cellHeightInCellWidths

    /**
     * On a plane, the mean step the router takes points down the plane, on the ground, at every
     * bearing: along an axis, on the ground's diagonals, on the grid's own diagonal and between.
     */
    @Test
    fun `water on a plane runs down it at every bearing`() {
        val errors = PLANE_BEARINGS_DEGREES.associateWith { degrees ->
            val fall = degrees * PI / 180.0
            val plane = FloatField.of(SIDE, SIDE) { column, row ->
                // Falling toward `fall`, measured from east toward south, per cell width of ground.
                (0.5 - PLANE_FALL_PER_CELL_WIDTH * (column * cos(fall) + row * rowScale * sin(fall))).toFloat()
            }
            val receiver = FlowRouting.flowDirections(
                SIDE, SIDE, BooleanArray(SIDE * SIDE) { true }, plane, plane, config.seed, rowScale,
                byFacet = true, overPotential = false
            )
            var eastCellWidths = 0.0
            var southCellWidths = 0.0
            for (row in MARGIN until SIDE - MARGIN) {
                for (column in MARGIN until SIDE - MARGIN) {
                    val target = receiver[row * SIDE + column]
                    if (target < 0) continue
                    eastCellWidths += (target % SIDE) - column
                    southCellWidths += ((target / SIDE) - row) * rowScale
                }
            }
            val routed = atan2(southCellWidths, eastCellWidths) * 180.0 / PI
            var error = routed - degrees
            while (error > 180.0) error -= 360.0
            while (error < -180.0) error += 360.0
            println("ROUTING plane falling at %6.1f degrees on the ground: routed at %6.1f".format(degrees, routed))
            error
        }
        val worst = errors.maxBy { abs(it.value) }
        assertTrue(
            abs(worst.value) <= BEARING_TOLERANCE_DEGREES,
            "a plane falling at ${worst.key} degrees on the ground is routed ${"%.1f".format(worst.value)} degrees off it"
        )
    }

    /**
     * On planes at every bearing the facet rule takes a column, a row or a diagonal as often as its
     * own geometry says, and the mean of what it takes points down the plane.
     *
     * The rule's expectation, worked here from the facet's geometry and not from the router: a plane
     * descending at bearing `theta` lies in the facet between the cardinal and the diagonal that
     * flank `theta`, and the descent crosses that facet's far edge, which runs square to the leg out
     * to the cardinal, a share `tan(alpha) * leg / edge` of the way from the cardinal to the
     * diagonal, `alpha` being its angle off the leg. The draw takes the diagonal with that share.
     * Averaged over [SHARE_BEARINGS] bearings on this map's cells, half as tall as wide, that is
     * 44.87% down a column, 15.31% along a row and 39.82% on a diagonal. The nearest of the eight
     * bearings would give 35.2, 14.8 and 50.0: the rule is not that, and the column's share over
     * it is the rule's, not a defect.
     *
     * Read on production's [FlowRouting.flowDirections] over [SHARE_SEEDS], interior cells only.
     * Each share is held to four of its standard errors, from the draws, which are the only thing
     * random here. A cell's draw is the same at every bearing of one seed, so the independent
     * samples are the cells and not the cells times the bearings: the error is taken from the
     * spread, across cells, of the share of the bearings each cell took each way. The mean step's
     * bearing is held on each plane to [WORST_PLANE_SIGMAS] of its own standard errors, the
     * bound for the worst of the 1,080 planes.
     */
    @Test
    fun `the facet rule takes each bearing as often as its geometry says`() {
        var cellsRead = 0L
        val taken = DoubleArray(3)
        val expected = DoubleArray(3)
        // Per seed and cell, how many of the bearings went each way, and what the geometry expected.
        val perCell = Array(SHARE_SEEDS.size) { Array(3) { DoubleArray(SHARE_SIDE * SHARE_SIDE) } }
        var worstBearingSigmas = 0.0
        var worstBearingDegrees = 0.0
        var worstAt = 0.0
        for ((seedIndex, seed) in SHARE_SEEDS.withIndex()) {
            for (index in 0 until SHARE_BEARINGS) {
                val degrees = (index + 0.5) * 360.0 / SHARE_BEARINGS
                val fall = degrees * PI / 180.0
                val plane = FloatField.of(SHARE_SIDE, SHARE_SIDE) { column, row ->
                    (0.5 - PLANE_FALL_PER_CELL_WIDTH * (column * cos(fall) + row * rowScale * sin(fall))).toFloat()
                }
                val receiver = FlowRouting.flowDirections(
                    SHARE_SIDE, SHARE_SIDE, BooleanArray(SHARE_SIDE * SHARE_SIDE) { true }, plane, plane, seed, rowScale
                )
                val (share, diagonalIsColumnwise) = facetShare(fall)
                var east = 0.0
                var south = 0.0
                var eastSquares = 0.0
                var southSquares = 0.0
                var crossSquares = 0.0
                var n = 0
                for (row in MARGIN until SHARE_SIDE - MARGIN) {
                    for (column in MARGIN until SHARE_SIDE - MARGIN) {
                        val target = receiver[row * SHARE_SIDE + column]
                        if (target < 0) continue
                        val dc = (target % SHARE_SIDE) - column
                        val dr = (target / SHARE_SIDE) - row
                        val way = if (dc == 0) 0 else if (dr == 0) 1 else 2
                        taken[way] += 1.0
                        val cell = row * SHARE_SIDE + column
                        perCell[seedIndex][way][cell] += 1.0
                        perCell[seedIndex][2][cell] -= share
                        perCell[seedIndex][if (diagonalIsColumnwise) 0 else 1][cell] -= 1 - share
                        val x = dc.toDouble()
                        val y = dr * rowScale
                        east += x; south += y
                        eastSquares += x * x; southSquares += y * y; crossSquares += x * y
                        n++
                    }
                }
                cellsRead += n
                // Each cell takes the diagonal with probability `share`, else the cardinal.
                expected[2] += n * share
                if (diagonalIsColumnwise) expected[0] += n * (1 - share) else expected[1] += n * (1 - share)
                // The mean step's bearing, and its standard error across the plane's draws.
                val meanX = east / n
                val meanY = south / n
                var error = atan2(meanY, meanX) * 180.0 / PI - degrees
                while (error > 180.0) error -= 360.0
                while (error < -180.0) error += 360.0
                val across = -sin(fall) to cos(fall)
                val acrossVariance = (across.first * across.first * (eastSquares / n - meanX * meanX) +
                    across.second * across.second * (southSquares / n - meanY * meanY) +
                    2 * across.first * across.second * (crossSquares / n - meanX * meanY))
                val sigmaDegrees = sqrt(acrossVariance.coerceAtLeast(0.0) / n) / sqrt(meanX * meanX + meanY * meanY) * 180.0 / PI
                val sigmas = if (sigmaDegrees > 0.0) abs(error) / sigmaDegrees else if (abs(error) < 1e-9) 0.0 else Double.POSITIVE_INFINITY
                if (sigmas > worstBearingSigmas) { worstBearingSigmas = sigmas; worstBearingDegrees = error; worstAt = degrees }
            }
        }
        val names = listOf("down a column", "along a row", "on a diagonal")
        val complaints = ArrayList<String>()
        for (k in 0 until 3) {
            // The per-cell departures from the geometry, summed over bearings: their spread across
            // the cells is the error of the total.
            var sumSquares = 0.0
            for (seedCells in perCell) for (row in MARGIN until SHARE_SIDE - MARGIN) for (column in MARGIN until SHARE_SIDE - MARGIN) {
                val departure = seedCells[k][row * SHARE_SIDE + column]
                sumSquares += departure * departure
            }
            val sigma = sqrt(sumSquares)
            println(
                "ROUTING shares %s: %.3f%% taken, %.3f%% the rule's geometry, %.2f standard errors apart"
                    .format(names[k], 100.0 * taken[k] / cellsRead, 100.0 * expected[k] / cellsRead, abs(taken[k] - expected[k]) / sigma)
            )
            if (abs(taken[k] - expected[k]) > SHARE_SIGMAS * sigma) {
                complaints += "%s %.3f%% against %.3f%%".format(names[k], 100.0 * taken[k] / cellsRead, 100.0 * expected[k] / cellsRead)
            }
        }
        println("ROUTING the worst plane's mean step: %.3f degrees off at %.1f, %.2f standard errors".format(worstBearingDegrees, worstAt, worstBearingSigmas))
        assertTrue(complaints.isEmpty(), "the router's step shares are not its geometry's: $complaints")
        assertTrue(
            worstBearingSigmas <= WORST_PLANE_SIGMAS,
            "the mean step on the plane at $worstAt degrees points $worstBearingDegrees degrees off it, $worstBearingSigmas standard errors"
        )
    }

    /**
     * The share of a plane's steps the facet rule sends to the diagonal, for a plane descending at
     * [fall] (from east toward south, on the ground), and whether that facet's cardinal is a column
     * step. The facet is the one the descent lies in; see the case above.
     */
    private fun facetShare(fall: Double): Pair<Double, Boolean> {
        val x = cos(fall)
        val y = sin(fall)
        // The bearing of the diagonal's step on the ground, off the east-west axis.
        val diagonalOffRow = atan2(rowScale, 1.0)
        val offRow = atan2(abs(y), abs(x))
        return if (offRow <= diagonalOffRow) {
            // Between the row step (a cell width) and the diagonal: the far edge is a row's height.
            (1.0 * kotlin.math.tan(offRow) / rowScale) to false
        } else {
            // Between the column step (a row's height) and the diagonal: the far edge a cell width.
            (rowScale * kotlin.math.tan(PI / 2 - offRow) / 1.0) to true
        }
    }

    /**
     * Across a raised flat, the potential the water follows rises as fast north-south as east-west
     * from the flat's one outlet: its level lines are round on the ground, on cells of every shape.
     *
     * A flat round on the ground, raised one flat-gradient step over a basin floor, with its outlet
     * a single lower cell at its centre and a rim far above it all round. At the same distance on
     * the ground from the outlet, the mean potential in the north-south quarters and in the
     * east-west ones must agree. Read on the cells this map has, half as tall as they are wide, and
     * on the others a grid may have: every width and height is a power of two, so a cell is
     * `2^k` as tall as it is wide for some whole `k` (512 by 128 gives 2, 512 by 1024 a quarter).
     */
    @Test
    fun `the potential across a flat is round on the ground`() {
        val failures = ArrayList<String>()
        for (aspect in FLAT_ASPECTS) {
            val columns = FLAT_FIELD_CELL_WIDTHS
            val rows = kotlin.math.ceil(FLAT_FIELD_CELL_WIDTHS / aspect).toInt()
            val centreColumn = columns / 2
            val centreRow = rows / 2
            val isLand = BooleanArray(columns * rows) { true }
            val ground = FloatArray(columns * rows)
            val filled = FloatArray(columns * rows)
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    val cell = row * columns + column
                    val across = (column - centreColumn).toDouble()
                    val down = (row - centreRow) * aspect
                    val fromCentre = sqrt(across * across + down * down)
                    when {
                        column == centreColumn && row == centreRow -> { ground[cell] = OUTLET; filled[cell] = OUTLET }
                        fromCentre <= FLAT_RADIUS_CELL_WIDTHS -> { ground[cell] = 0f; filled[cell] = FLAT_SURFACE }
                        else -> { ground[cell] = RIM; filled[cell] = RIM }
                    }
                }
            }
            val surface = FlatRouting.surfaceOf(
                columns, rows, isLand, FloatField(columns, rows, ground), FloatField(columns, rows, filled),
                config.seed, aspect
            )
            if (surface.flats != 1 || surface.flatsKept != 0) {
                println("ROUTING flat potential on cells $aspect as tall as wide: not laid, ${surface.flats} flats, ${surface.flatsKept} kept")
                failures.add("cells $aspect as tall as wide: the flat was not laid")
                continue
            }
            val floor = surface.heights[centreRow * columns + centreColumn + 1]
            val ratios = RING_RADII_CELL_WIDTHS.map { radius ->
                var eastWestSum = 0.0
                var eastWestCells = 0
                var northSouthSum = 0.0
                var northSouthCells = 0
                for (row in 0 until rows) {
                    for (column in 0 until columns) {
                        val across = (column - centreColumn).toDouble()
                        val down = (row - centreRow) * aspect
                        if (abs(sqrt(across * across + down * down) - radius) > RING_HALF_WIDTH * maxOf(1.0, aspect)) continue
                        val potential = surface.heights[row * columns + column] - floor
                        if (abs(across) > abs(down)) { eastWestSum += potential; eastWestCells++ }
                        else { northSouthSum += potential; northSouthCells++ }
                    }
                }
                val ratio = (northSouthSum / northSouthCells) / (eastWestSum / eastWestCells)
                println(
                    "ROUTING flat potential on cells %.3f as tall as wide, %4.0f cell widths from its outlet: north-south quarters %.3f of the east-west ones"
                        .format(aspect, radius, ratio)
                )
                ratio
            }
            if (!ratios.all { abs(it - 1.0) <= POTENTIAL_TOLERANCE }) {
                failures.add("cells $aspect as tall as wide: north-south ${ratios.map { "%.2f".format(it) }} of east-west")
            }
        }
        assertTrue(failures.isEmpty(), "at the same distance on the ground the flat's potential is not round: $failures")
    }

    /**
     * The flat's Laplacian weighs every neighbour positively and weighs the ground alike both ways,
     * on cells of every shape a grid can have.
     *
     * Positive, because the discrete maximum principle — every member strictly above the mean of
     * its neighbours, and so strictly above the entry — is what guarantees the potential a lower
     * neighbour at every cell, and a negative weight voids it. Isotropic, because that is what the
     * weights are for: the operator's second derivatives east-west and north-south on the ground
     * carry one coefficient, `e + 2d = r^2 (n + 2d)` for weights `e`, `n` and `d` on cells `r` as
     * tall as they are wide. Over `r = 2^k` for `k` from -8 to 8.
     */
    @Test
    fun `the flat's weights are positive and isotropic on cells of every shape`() {
        val failures = ArrayList<String>()
        for (k in -8..8) {
            val aspect = Math.pow(2.0, k.toDouble())
            val weights = FlatRouting.stencil(aspect)
            val eastWestCoefficient = weights.eastWest + 2.0 * weights.diagonal
            val northSouthCoefficient = aspect * aspect * (weights.northSouth + 2.0 * weights.diagonal)
            println(
                "ROUTING flat stencil on cells %.4f as tall as wide: east-west %.4f, north-south %.4f, diagonal %.4f; coefficients %.6f and %.6f"
                    .format(aspect, weights.eastWest, weights.northSouth, weights.diagonal, eastWestCoefficient, northSouthCoefficient)
            )
            if (!(weights.eastWest > 0.0 && weights.northSouth > 0.0 && weights.diagonal > 0.0)) {
                failures.add("r=$aspect: a weight is not positive (${weights.eastWest}, ${weights.northSouth}, ${weights.diagonal})")
            }
            if (abs(eastWestCoefficient - northSouthCoefficient) > 1e-9 * eastWestCoefficient) {
                failures.add("r=$aspect: east-west ${eastWestCoefficient} against north-south $northSouthCoefficient")
            }
        }
        assertTrue(failures.isEmpty(), "the flat's Laplacian: $failures")
    }

    /**
     * With `WorldGenConfig.clampedDescentDraw` on, the router's two invariants still hold: every
     * receiver stands strictly lower on the filled surface than its cell, and following receivers
     * from any cell reaches the sea without coming back on itself, so the network is a forest.
     *
     * On rough ground cut by gullies down the columns and on the diagonals, so that many cells'
     * descent is clamped to one edge of its facet and the draw is exercised: the case also requires
     * that the draw changed some receivers, or it would be testing the rule it replaces.
     */
    @Test
    fun `the draw in clamped descent keeps every receiver lower and the network a forest`() {
        val side = 96
        val isLand = BooleanArray(side * side) { cell ->
            val row = cell / side
            val column = cell % side
            row in 2 until side - 2 && column in 2 until side - 2
        }
        val ground = FloatField.of(side, side) { column, row ->
            val rough = FlowRouting.seededNoise(column, row, 11L) * 0.02f
            val gullyDownTheColumns = if (column % 6 == 0) 0.05f else 0f
            val gullyOnTheDiagonal = if ((column + 2 * row) % 9 == 0) 0.03f else 0f
            (0.2f + 0.004f * row + rough - gullyDownTheColumns - gullyOnTheDiagonal)
        }
        val filled = FlowRouting.fillDepressions(side, side, isLand, ground)
        fun route(draw: Boolean) = FlowRouting.flowDirections(
            side, side, isLand, ground, filled, config.seed, rowScale,
            byFacet = true, overPotential = false, drawInClampedDescent = draw
        )
        val plain = route(draw = false)
        val drawn = route(draw = true)
        var changed = 0
        var higher = 0
        var cycles = 0
        for (cell in 0 until side * side) {
            if (!isLand[cell]) continue
            val receiver = drawn[cell]
            if (receiver != plain[cell]) changed++
            if (receiver >= 0 && isLand[receiver] && filled.data[receiver] >= filled.data[cell]) higher++
            var at = cell
            var steps = 0
            while (at >= 0 && isLand[at] && steps <= side * side) {
                at = drawn[at]
                steps++
            }
            if (steps > side * side) cycles++
        }
        println("ROUTING draw in clamped descent: $changed receivers changed, $higher not lower, $cycles cells on a cycle")
        assertTrue(changed > 0, "the draw changed no receiver, so nothing here exercised it")
        assertTrue(higher == 0, "$higher receivers stand no lower on the filled surface than their cells")
        assertTrue(cycles == 0, "$cycles cells never reach the sea: the network is not a forest")
    }

    private companion object {
        const val SIDE = 256

        /** The planes' bearings on the ground, measured from east toward south. */
        val PLANE_BEARINGS_DEGREES = listOf(0.0, 10.0, 26.565, 30.0, 45.0, 60.0, 75.0, 90.0, 120.0, 135.0, 200.0, 300.0)

        /** A gentle plane: a thousandth of the height field per cell width. */
        const val PLANE_FALL_PER_CELL_WIDTH = 1e-3

        /** Cells left out at every edge, so the seam's wrap and the poles' clamp decide nothing. */
        const val MARGIN = 16

        /** The share case's planes: bearings evenly spaced round the circle, seeds, and side. */
        const val SHARE_BEARINGS = 360
        val SHARE_SEEDS = listOf(7L, 42L, 1234L)
        const val SHARE_SIDE = 128

        /** Standard errors a share may stand from its expectation. */
        const val SHARE_SIGMAS = 4.0

        /**
         * And the worst of the 1,080 planes' mean bearings: the largest of that many independent
         * normal errors passes 4.5 standard errors one time in about a hundred and thirty.
         */
        const val WORST_PLANE_SIGMAS = 4.5

        /**
         * How far the mean routed bearing may stand from the plane's: two degrees. The draw that
         * decides each step is a hash of the cell, independent from one cell to the next, so over
         * the fifty thousand cells read the mean of its choices sits within a fraction of a degree
         * of the share it draws at; the square ruler's 31-degree error on the ground's diagonal is
         * fifteen times it.
         */
        const val BEARING_TOLERANCE_DEGREES = 2.0

        /** The synthetic flat: a floor, one flat-gradient step of fill over it, an outlet and a rim. */
        const val OUTLET = -1f
        const val FLAT_SURFACE = 1e-3f
        const val RIM = 10f
        const val FLAT_RADIUS_CELL_WIDTHS = 50.0

        /** The flat's field: this many cell widths of ground each way, however many rows that is. */
        const val FLAT_FIELD_CELL_WIDTHS = 128

        /**
         * The cell shapes the flat is laid on, as a row's height in cell widths: this map's half, the
         * square, and a quarter and twice on either side of them, which a 512 by 1024 and a 512 by
         * 128 grid have.
         */
        val FLAT_ASPECTS = listOf(0.25, 0.5, 1.0, 2.0)

        /** The rings the potential is read on, and how wide each is. */
        val RING_RADII_CELL_WIDTHS = listOf(10.0, 20.0, 30.0, 40.0)
        const val RING_HALF_WIDTH = 0.75

        /**
         * How far apart the two quarters' mean potentials may be: a tenth. The rain on the flat
         * varies by half either way over eight cells, so a ring's quarters differ by a few percent
         * on even ground; a Laplacian that weighs a row as a column puts four times the conductance
         * east-west and draws level lines twice as long that way as the other.
         */
        const val POTENTIAL_TOLERANCE = 0.10
    }
}
