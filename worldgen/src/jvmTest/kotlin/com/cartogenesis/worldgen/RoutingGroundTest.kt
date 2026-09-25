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

    private companion object {
        const val SIDE = 256

        /** The planes' bearings on the ground, measured from east toward south. */
        val PLANE_BEARINGS_DEGREES = listOf(0.0, 10.0, 26.565, 30.0, 45.0, 60.0, 75.0, 90.0, 120.0, 135.0, 200.0, 300.0)

        /** A gentle plane: a thousandth of the height field per cell width. */
        const val PLANE_FALL_PER_CELL_WIDTH = 1e-3

        /** Cells left out at every edge, so the seam's wrap and the poles' clamp decide nothing. */
        const val MARGIN = 16

        /**
         * How far the mean routed bearing may stand from the plane's: two degrees. The draw that
         * decides each step is a smooth field eight cells to a period, so over the sixty thousand
         * cells read the mean of its choices sits within a fraction of a degree of the share it
         * draws at; the square ruler's 31-degree error on the ground's diagonal is fifteen times it.
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
