package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.Plate
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.PlateType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The plate stage's operators measure the ground, whichever way the ground is walked.
 *
 * A cell of this map is twice as wide as it is tall: at 512 by 512 on a world 12,000 km round and
 * 6,000 from pole to pole it is 23.4 km across and 11.7 km down. An operator that counts a row as a
 * column therefore does everything twice as far east-west as north-south on the ground, and draws
 * the grid's axes into whatever it shapes. Each case here lays a synthetic feature at several
 * bearings on the ground — a boundary, a crust edge, two seeds, a drift, a rift, an old belt — hands
 * it to the operator the stage itself runs, and reads the answer back in kilometres. Audit III's
 * A-D2, D7 and R13-6 and Astra's 1.1 and 1.3 are the findings; `docs/DESIGN_LEDGER.md` has the row.
 */
class TectonicGroundTest {

    private val config = WorldGenConfig(seed = 1L, width = 512, height = 512)
    private val cellsAcross = config.width
    private val cellsDown = config.height
    private val rowScale = config.cellHeightInCellWidths

    /**
     * A belt's distance from its boundary is a length on the ground, not a count of cells.
     *
     * Two boundaries, one along a row and one along a column, and the cell that stands 398 km off
     * each: 34 rows one way, 17 columns the other. The distance the stage hands every belt profile
     * must be the same 17 cell widths for both.
     */
    @Test
    fun `a boundary's distance is the same length on the ground whichever way it runs`() {
        val alongARow = (0 until cellsAcross).map { EDGE * cellsAcross + it }
        val alongAColumn = (0 until cellsDown).map { it * cellsAcross + EDGE }
        val southOfTheRow = PlateStage.boundaryDistance(config, alongARow)
            .distanceCellWidths[(EDGE + ROWS_OFF) * cellsAcross + cellsAcross / 2]
        val eastOfTheColumn = PlateStage.boundaryDistance(config, alongAColumn)
            .distanceCellWidths[cellsDown / 2 * cellsAcross + EDGE + COLUMNS_OFF]
        println(
            "TECTONIC boundary distance %d rows south of a boundary along a row: %.3f cell widths; %d columns east of one along a column: %.3f"
                .format(ROWS_OFF, southOfTheRow, COLUMNS_OFF, eastOfTheColumn)
        )
        val onTheGround = COLUMNS_OFF.toFloat()
        assertTrue(
            abs(southOfTheRow - onTheGround) < DISTANCE_ROUNDING && abs(eastOfTheColumn - onTheGround) < DISTANCE_ROUNDING,
            "both cells stand $onTheGround cell widths from their boundary on the ground; the stage reads " +
                "$southOfTheRow south of the row and $eastOfTheColumn east of the column"
        )
    }

    /**
     * The continental margin runs from one crust to the other over the same width on the ground at
     * every bearing, and that width is the one `TectonicsConfig.crustMarginKm` names.
     *
     * A straight crust edge laid through the middle of the map at each of six bearings, blurred by
     * the stage's own margin operator, and the share read back across the edge in kilometres: the
     * distance between where it passes a tenth and where it passes nine tenths.
     */
    @Test
    fun `the continental margin is as wide as the setting says at every bearing`() {
        val widths = MARGIN_BEARINGS_DEGREES.associateWith { degrees ->
            val normal = degrees * PI / 180.0
            val crust = straightEdge(normal)
            PlateStage.blurAcrossTheMargin(config, crust)
            tenToNinetyKm(crust, normal)
        }
        val declared = config.tectonics.crustMarginKm
        widths.forEach { (degrees, km) ->
            println("TECTONIC margin with its normal at %5.1f degrees on the ground: 10-90%% width %.1f km against %.0f km declared".format(degrees, km, declared))
        }
        val widest = widths.values.max()
        val narrowest = widths.values.min()
        assertTrue(
            widths.values.all { abs(it - declared) <= declared * MARGIN_TOLERANCE },
            "the margin's 10-90% width is ${widths.values.map { it.roundToInt() }} km at bearings " +
                "$MARGIN_BEARINGS_DEGREES, against the ${declared.roundToInt()} km declared"
        )
        assertTrue(
            widest / narrowest <= 1.0 + MARGIN_TOLERANCE,
            "the margin is ${"%.2f".format(widest / narrowest)} times as wide at one bearing as at another"
        )
    }

    /**
     * A plate owns the cells nearer its seed than any other seed's, on the ground: the partition's
     * metric is Euclid's in kilometres, before the warp moves anything.
     *
     * Four seeds around the middle of the map, and every cell of the window around them checked
     * against the brute-force nearest seed on the ground — except the cells within a cell width of
     * a tie, where the grid itself cannot say.
     */
    @Test
    fun `plates are divided by distance on the ground`() {
        val seeds = listOf(200 to 200, 240 to 240, 300 to 180, 190 to 300)
        val plates = seeds.mapIndexed { id, (x, y) -> Plate(id, x, y, 1f, 0f, PlateType.OCEANIC) }
        val labels = PlateStage.nearestSeedPartition(config, plates)
        var checked = 0
        var wrong = 0
        var firstWrong = ""
        for (row in 120 until 380) {
            for (column in 120 until 380) {
                val distances = seeds.map { (x, y) -> groundCellWidths(column - x, row - y) }
                val sorted = distances.sorted()
                if (sorted[1] - sorted[0] < 1.0) continue
                checked++
                val nearest = distances.indexOf(sorted[0])
                if (labels[row * cellsAcross + column] != nearest) {
                    if (wrong == 0) firstWrong = "($column,$row) given plate ${labels[row * cellsAcross + column]}, nearest on the ground is $nearest"
                    wrong++
                }
            }
        }
        println("TECTONIC partition: $wrong of $checked cells clear of a tie belong to a seed that is not the nearest on the ground; $firstWrong")
        assertTrue(checked > 10_000, "only $checked cells were clear of a tie")
        assertTrue(wrong == 0, "$wrong of $checked cells belong to a seed that is not the nearest on the ground, first $firstWrong")
    }

    /**
     * A plate carried back along its drift travels the same distance on the ground whichever way it
     * drifts.
     */
    @Test
    fun `a plate drifts as far on the ground whichever way it goes`() {
        val distance = config.tectonics.epochDriftCells
        val travelled = DRIFT_BEARINGS_DEGREES.associateWith { degrees ->
            val angle = degrees * PI / 180.0
            val plate = Plate(0, cellsAcross / 2, cellsDown / 2, cos(angle).toFloat(), sin(angle).toFloat(), PlateType.OCEANIC)
            val moved = PlateStage.displacedPlates(config, listOf(plate), distance).single()
            groundCellWidths(moved.seedX - plate.seedX, moved.seedY - plate.seedY)
        }
        travelled.forEach { (degrees, cellWidths) ->
            println("TECTONIC drift at %5.1f degrees: %.2f cell widths on the ground against %.1f asked".format(degrees, cellWidths, distance))
        }
        assertTrue(
            travelled.values.all { abs(it - distance) <= DRIFT_ROUNDING },
            "a drift of $distance cell widths moves a seed ${travelled.values.map { "%.1f".format(it) }} cell widths on the " +
                "ground at bearings $DRIFT_BEARINGS_DEGREES"
        )
    }

    /**
     * A rift's length along its own course is the length of the ground it crosses, whichever way
     * it runs, so its half-grabens are cut to the same lengths in kilometres at every bearing.
     */
    @Test
    fun `a rift is measured along its course on the ground`() {
        val measured = RIFT_BEARINGS_DEGREES.associateWith { degrees ->
            val angle = degrees * PI / 180.0
            val run = straightRun(angle, RIFT_LENGTH_CELL_WIDTHS)
            val first = run.first()
            val last = run.last()
            val trueLength = groundCellWidths(last % cellsAcross - first % cellsAcross, last / cellsAcross - first / cellsAcross)
            PlateStage.arcAlongRun(config, run.toIntArray()).lengthCellWidths / trueLength
        }
        measured.forEach { (degrees, ratio) ->
            println("TECTONIC rift at %5.1f degrees: arc length %.3f of its length on the ground".format(degrees, ratio))
        }
        assertTrue(
            measured.values.all { abs(it - 1.0) <= RIFT_TOLERANCE },
            "a straight rift's measured arc length is ${measured.values.map { "%.2f".format(it) }} of its length on the " +
                "ground at bearings $RIFT_BEARINGS_DEGREES"
        )
    }

    /**
     * An old belt rounds as far north-south as east-west on the ground: the kernel that ages it is
     * round in kilometres.
     */
    @Test
    fun `an old belt rounds as far one way as the other`() {
        val impulse = FloatField(cellsAcross, cellsDown)
        impulse[cellsAcross / 2, cellsDown / 2] = 1f
        PlateStage.roundWithAge(config, impulse, epochsAgo = 2)
        var total = 0.0
        var eastWest = 0.0
        var northSouth = 0.0
        for (row in 0 until cellsDown) {
            for (column in 0 until cellsAcross) {
                val weight = impulse[column, row].toDouble()
                if (weight == 0.0) continue
                val acrossKm = (column - cellsAcross / 2) * config.cellWidthKm
                val downKm = (row - cellsDown / 2) * config.cellHeightKm
                total += weight
                eastWest += weight * acrossKm * acrossKm
                northSouth += weight * downKm * downKm
            }
        }
        val spreadEastWestKm = sqrt(eastWest / total)
        val spreadNorthSouthKm = sqrt(northSouth / total)
        println("TECTONIC age rounding of a point, two epochs old: spread %.1f km east-west, %.1f km north-south".format(spreadEastWestKm, spreadNorthSouthKm))
        assertTrue(
            abs(spreadEastWestKm / spreadNorthSouthKm - 1.0) <= SPREAD_TOLERANCE,
            "an old belt rounds over ${"%.0f".format(spreadEastWestKm)} km east-west and ${"%.0f".format(spreadNorthSouthKm)} km north-south"
        )
    }

    /** The length on the ground of a step of [columns] and [rows], in cell widths. */
    private fun groundCellWidths(columns: Int, rows: Int): Double {
        val down = rows * rowScale
        return sqrt(columns.toDouble() * columns + down * down)
    }

    /**
     * Continental crust on the far side of a straight edge through the middle of the map whose
     * normal points [normalRadians] from east toward south on the ground; oceanic on the near side.
     */
    private fun straightEdge(normalRadians: Double): FloatField = FloatField.of(cellsAcross, cellsDown) { column, row ->
        if (signedKmFromEdge(column, row, normalRadians) > 0.0) 1f else 0f
    }

    private fun signedKmFromEdge(column: Int, row: Int, normalRadians: Double): Double {
        val eastKm = (column - cellsAcross / 2 + 0.5) * config.cellWidthKm
        val southKm = (row - cellsDown / 2 + 0.5) * config.cellHeightKm
        return eastKm * cos(normalRadians) + southKm * sin(normalRadians)
    }

    /**
     * Where the blurred share crosses a tenth and nine tenths across the edge, from the cells of the
     * middle of the map averaged in bins of distance, and the distance between the two in km.
     */
    private fun tenToNinetyKm(share: FloatField, normalRadians: Double): Double {
        val binKm = 2.0
        val reachKm = 1_200.0
        val bins = (2 * reachKm / binKm).toInt()
        val sums = DoubleArray(bins)
        val counts = IntArray(bins)
        for (row in cellsDown / 4 until cellsDown * 3 / 4) {
            for (column in cellsAcross / 4 until cellsAcross * 3 / 4) {
                val km = signedKmFromEdge(column, row, normalRadians)
                val bin = ((km + reachKm) / binKm).toInt()
                if (bin < 0 || bin >= bins) continue
                sums[bin] += share[column, row]
                counts[bin]++
            }
        }
        fun crossing(level: Double): Double {
            var previousKm = Double.NaN
            var previousShare = Double.NaN
            for (bin in 0 until bins) {
                if (counts[bin] == 0) continue
                val km = -reachKm + (bin + 0.5) * binKm
                val mean = sums[bin] / counts[bin]
                if (!previousShare.isNaN() && previousShare < level && mean >= level) {
                    return previousKm + (km - previousKm) * (level - previousShare) / (mean - previousShare)
                }
                previousKm = km
                previousShare = mean
            }
            error("the share never crosses $level")
        }
        return crossing(0.9) - crossing(0.1)
    }

    /** The cells of a straight line [lengthCellWidths] long on the ground at [angle], eight-connected. */
    private fun straightRun(angle: Double, lengthCellWidths: Double): List<Int> {
        val cells = LinkedHashSet<Int>()
        val stepsAlong = (lengthCellWidths * 8).toInt()
        for (step in 0..stepsAlong) {
            val along = step / 8.0
            val column = (cellsAcross / 2 + along * cos(angle)).roundToInt()
            val row = (cellsDown / 2 + along * sin(angle) / rowScale).roundToInt()
            cells.add(row * cellsAcross + column)
        }
        return cells.toList()
    }

    private companion object {
        /** Where the synthetic boundaries run, a quarter of the way into the grid. */
        const val EDGE = 128

        /**
         * How far off each boundary the distance is read: 34 rows and 17 columns, which are the
         * same 398.4 km on the ground at 512 on a 12,000 by 6,000 km world.
         */
        const val ROWS_OFF = 34
        const val COLUMNS_OFF = 17

        /** A distance field read back through a float: a few steps of its last place. */
        const val DISTANCE_ROUNDING = 1e-3f

        /**
         * The bearings the margin's edge is laid at: the two axes, the ground's own diagonal and
         * the grid's (26.6 degrees on the ground, where a cell's corner points), and two between.
         */
        val MARGIN_BEARINGS_DEGREES = listOf(0.0, 90.0, 45.0, atan(0.5) * 180.0 / PI, 60.0, 120.0)

        /**
         * How far a margin's width may stand from the declared one, and from its own width at any
         * other bearing: five percent, which is a bin and a half of the measurement's two-kilometre
         * bins against a 300 km width, well above what a Gaussian sampled at 23 km and 12 km steps
         * departs from round, and a quarter of the 389-against-195 km the box blur drew.
         */
        const val MARGIN_TOLERANCE = 0.05

        /** Drift bearings, on the ground. */
        val DRIFT_BEARINGS_DEGREES = listOf(0.0, 30.0, 45.0, 90.0, 135.0, 250.0)

        /**
         * How far a drifted seed may land from the distance asked, in cell widths: the seed lands on
         * a whole cell, so up to half a cell across and half a row down, and the hypotenuse of the
         * two is 0.56 of a cell width.
         */
        const val DRIFT_ROUNDING = 0.6

        /** Rift bearings, on the ground. */
        val RIFT_BEARINGS_DEGREES = listOf(0.0, 90.0, 45.0, 30.0, 60.0)

        /** A synthetic rift's length: a hundred cell widths, 2,344 km at 512. */
        const val RIFT_LENGTH_CELL_WIDTHS = 100.0

        /**
         * How far a straight rift's arc length may stand from its true length: a twentieth. The
         * cells of a straight run lie within half a cell of the line, so a centreline drawn through
         * them is the line to well under that; a walk from cell to cell would not be, since an
         * eight-connected staircase between an axis and a diagonal overstates the line by up to
         * 18% on cells half as tall as they are wide. A count of rows makes a north-south rift
         * twice its length.
         */
        const val RIFT_TOLERANCE = 0.05

        /** How far the two spreads of an old belt's rounding may differ: a twentieth. */
        const val SPREAD_TOLERANCE = 0.05
    }
}
