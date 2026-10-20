package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.TectonicsConfig
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.Plate
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
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
 * A-D2, D7 and R13-6 are the findings; `docs/DESIGN_LEDGER.md` has the row. Each case is read on
 * those cells and on square ones, which is the grid the generator is moving to (Q2).
 *
 * Two cases go the other way, from one grid to another rather than from one bearing to another:
 * a world of square cells made by [WorldGenConfig.forRows] raises its belts as wide on the ground as
 * the 512 by 512 world, and a control whose lengths are the 512 grid's counts on half-width cells
 * does not (Q1's and Q2's rows).
 */
class TectonicGroundTest {

    /** One synthetic grid the operators are read on, and the figures of its cells. */
    private class Grid(val config: WorldGenConfig) {
        val cellsAcross = config.width
        val cellsDown = config.height
        val rowScale = config.cellHeightInCellWidths
    }

    /**
     * Cells half as tall as they are wide, at 512 by 512, and square, at 1,024 by 512: each case
     * below is read on both.
     */
    private val grids = listOf(
        Grid(WorldGenConfig(seed = 1L, width = 512, height = 512)),
        Grid(WorldGenConfig.forRows(1L, 512))
    )

    /**
     * The same two grids on the 12,000 km planet ([CalibrationPlanet]), whose 512 rows are cells of
     * 11.7 km, the size the margin's and the rift's bearings were measured at: on the Earth-sized
     * planet's 39 km cells the margin's 300 km is under eight cells and a rift's 93.75 km centerline
     * step under three, and what those two cases read there is the staircase of the grid, not the
     * operator's bearing. Not the drift's case: a plate's drift is Earth's 8,400 km an epoch, which
     * on the smaller planet runs most of the way round it.
     */
    private val calibrationGrids = grids.map { Grid(CalibrationPlanet.of(it.config)) }

    /**
     * A belt's distance from its boundary is a length on the ground, not a count of cells.
     *
     * Two boundaries, one along a row and one along a column, and the cell that stands [OFFSET_KM]
     * off each: 34 rows one way and 17 columns the other on the 512 by 512 grid, 34 each way on
     * square cells. The distance the stage hands every belt profile must be the same cell widths
     * for both.
     */
    @Test
    fun `a boundary's distance is the same length on the ground whichever way it runs`() = grids.forEach { grid -> with(grid) {
        val rowsOff = kotlin.math.round(config.rowsFor(OFFSET_KM)).toInt()
        val columnsOff = kotlin.math.round(config.cellsFor(OFFSET_KM)).toInt()
        val alongARow = (0 until cellsAcross).map { EDGE * cellsAcross + it }
        val alongAColumn = (0 until cellsDown).map { it * cellsAcross + EDGE }
        val southOfTheRow = PlateStage.boundaryDistance(config, alongARow)
            .distanceCellWidths[(EDGE + rowsOff) * cellsAcross + cellsAcross / 2]
        val eastOfTheColumn = PlateStage.boundaryDistance(config, alongAColumn)
            .distanceCellWidths[cellsDown / 2 * cellsAcross + EDGE + columnsOff]
        println(
            "TECTONIC %dx%d boundary distance %d rows south of a boundary along a row: %.3f cell widths; %d columns east of one along a column: %.3f"
                .format(cellsAcross, cellsDown, rowsOff, southOfTheRow, columnsOff, eastOfTheColumn)
        )
        val onTheGround = columnsOff.toFloat()
        assertTrue(
            abs(southOfTheRow - onTheGround) < DISTANCE_ROUNDING && abs(eastOfTheColumn - onTheGround) < DISTANCE_ROUNDING,
            "on ${cellsAcross}x$cellsDown both cells stand $onTheGround cell widths from their boundary on the ground; " +
                "the stage reads $southOfTheRow south of the row and $eastOfTheColumn east of the column"
        )
    } }

    /**
     * A rift's length along its own course is the length of the ground it crosses, whichever way
     * it runs, so its half-grabens are cut to the same lengths in kilometres at every bearing.
     */
    @Test
    fun `a rift is measured along its course on the ground`() = calibrationGrids.forEach { grid -> with(grid) {
        val measured = RIFT_BEARINGS_DEGREES.associateWith { degrees ->
            val angle = degrees * PI / 180.0
            val run = straightRun(angle, RIFT_LENGTH_CELL_WIDTHS)
            val first = run.first()
            val last = run.last()
            val trueLength = groundCellWidths(last % cellsAcross - first % cellsAcross, last / cellsAcross - first / cellsAcross)
            PlateStage.arcAlongRun(config, run.toIntArray()).lengthCellWidths / trueLength
        }
        measured.forEach { (degrees, ratio) ->
            println("TECTONIC ${cellsAcross}x$cellsDown rift at %5.1f degrees: arc length %.3f of its length on the ground".format(degrees, ratio))
        }
        assertTrue(
            measured.values.all { abs(it - 1.0) <= RIFT_TOLERANCE },
            "a straight rift's measured arc length is ${measured.values.map { "%.2f".format(it) }} of its length on the " +
                "ground at bearings $RIFT_BEARINGS_DEGREES"
        )
    } }

    /**
     * How much of a world the present epoch's belts cover, and how far from their boundaries they
     * reach on the ground.
     *
     * [shareOfMap] is the share of the map's cells whose rock is still rising, which on an
     * equal-area-per-cell grid is the share of the map's area. [reachKm] is the 90th percentile of
     * those cells' distance from the nearest boundary in kilometers, which is where the widest
     * belts end: the Andean and Tibetan half-widths and the arcs' offsets.
     */
    private class Belts(val shareOfMap: Double, val reachKm: Double)

    private fun belts(world: WorldGenConfig): Belts {
        val plates = PlateStage.generate(world, TerrainStage.generate(world))
        val rising = plates.upliftRateMmPerYear.data.indices.filter { plates.upliftRateMmPerYear.data[it] > 0f }
        val distancesKm = rising.map { plates.boundaryDistance.data[it] * world.cellWidthKm }.sorted()
        return Belts(
            shareOfMap = rising.size.toDouble() / plates.upliftRateMmPerYear.data.size,
            reachKm = distancesKm[((distancesKm.size - 1) * BELT_REACH_PERCENTILE).toInt()]
        )
    }

    /** Where [square] parts from [reference] by more than a change of grid parts them, one line a miss. */
    private fun beltMisses(seed: Long, reference: WorldGenConfig, square: WorldGenConfig): List<String> {
        val was = belts(reference)
        val now = belts(square)
        println(
            "TECTONIC belts, seed %d: %.4f of the map rising out to %.0f km at %dx%d, %.4f out to %.0f km at %dx%d".format(
                seed, was.shareOfMap, was.reachKm, reference.width, reference.height,
                now.shareOfMap, now.reachKm, square.width, square.height
            )
        )
        val misses = ArrayList<String>()
        if (abs(now.shareOfMap / was.shareOfMap - 1.0) > BELT_SHARE_SPREAD) {
            misses += "seed $seed: the belts cover %.4f of the map against %.4f".format(now.shareOfMap, was.shareOfMap)
        }
        if (abs(now.reachKm / was.reachKm - 1.0) > BELT_REACH_SPREAD) {
            misses += "seed $seed: the belts reach %.0f km from their boundaries against %.0f".format(now.reachKm, was.reachKm)
        }
        return misses
    }

    /**
     * A world of square cells raises its belts as wide on the ground as the 512 by 512 world does.
     *
     * The widths are lengths in kilometers, converted to cell widths of whichever grid the world is
     * built on; this measures that the square grid's 1,024 columns give a belt twice the cells it
     * had across 512. Reported rather than asserted ([CrossGridReport]): the application makes one
     * grid, and the grid of 512 by 512 is not it (docs/DESIGN_LEDGER.md, G1).
     */
    @Test
    fun `a world of square cells raises its belts as wide on the ground as the 512 by 512 world`() {
        val misses = BELT_SEEDS.flatMap { seed ->
            beltMisses(seed, WorldGenConfig(seed = seed, width = 512, height = 512), WorldGenConfig.forRows(seed, 512))
        }
        CrossGridReport.report(
            "a world of square cells raises its belts as wide on the ground as the 512 by 512 world",
            misses.isEmpty(),
            misses.joinToString("; ").ifEmpty { "every seed's belts within the spread" }
        )
    }

    /**
     * The control: the square world with every tectonic length halved, which is the 512 grid's
     * counts of cells spent on cells half as wide. That is what a world built directly at 1,024 by
     * 512 did while the widths were counts (Q1's control, and this one is that world to the bit),
     * so every belt is half as wide on the ground.
     */
    @Test
    fun `the comparison sees widths counted on a grid they were not set on`() {
        val square = WorldGenConfig.forRows(CONTROL_SEED, 512)
        val misses = beltMisses(
            CONTROL_SEED,
            WorldGenConfig(seed = CONTROL_SEED, width = 512, height = 512),
            square.copy(tectonics = lengthsScaled(square.tectonics, 0.5))
        )
        assertTrue(misses.size == 2, "belts half as wide on the ground were not seen: $misses")
    }

    /** Every belt and trail length in [tectonics] times [factor]: the widths, offsets and trails. */
    private fun lengthsScaled(tectonics: TectonicsConfig, factor: Double): TectonicsConfig = tectonics.copy(
        boundaryFalloffKm = tectonics.boundaryFalloffKm * factor,
        andeanWidthKm = tectonics.andeanWidthKm * factor,
        arcOffsetKm = tectonics.arcOffsetKm * factor,
        arcWidthKm = tectonics.arcWidthKm * factor,
        collisionWidthKm = tectonics.collisionWidthKm * factor,
        islandArcOffsetKm = tectonics.islandArcOffsetKm * factor,
        islandArcWidthKm = tectonics.islandArcWidthKm * factor,
        riftWidthKm = tectonics.riftWidthKm * factor,
        riftShoulderOffsetKm = tectonics.riftShoulderOffsetKm * factor,
        riftShoulderWidthKm = tectonics.riftShoulderWidthKm * factor,
        hotspotChainLengthKm = tectonics.hotspotChainLengthKm * factor,
        hotspotSpacingKm = tectonics.hotspotSpacingKm * factor,
        hotspotRadiusKm = tectonics.hotspotRadiusKm * factor
    )

    /** The length on the ground of a step of [columns] and [rows], in cell widths. */
    private fun Grid.groundCellWidths(columns: Int, rows: Int): Double {
        val down = rows * rowScale
        return sqrt(columns.toDouble() * columns + down * down)
    }

    /**
     * Continental crust on the far side of a straight edge through the middle of the map whose
     * normal points [normalRadians] from east toward south on the ground; oceanic on the near side.
     */
    private fun Grid.straightEdge(normalRadians: Double): FloatField = FloatField.of(cellsAcross, cellsDown) { column, row ->
        if (signedKmFromEdge(column, row, normalRadians) > 0.0) 1f else 0f
    }

    private fun Grid.signedKmFromEdge(column: Int, row: Int, normalRadians: Double): Double {
        val eastKm = (column - cellsAcross / 2 + 0.5) * config.cellWidthKm
        val southKm = (row - cellsDown / 2 + 0.5) * config.cellHeightKm
        return eastKm * cos(normalRadians) + southKm * sin(normalRadians)
    }

    /**
     * Where the blurred share crosses a tenth and nine tenths across the edge, from the cells of the
     * middle of the map averaged in bins of distance, and the distance between the two in km.
     */
    private fun Grid.tenToNinetyKm(share: FloatField, normalRadians: Double): Double {
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
    private fun Grid.straightRun(angle: Double, lengthCellWidths: Double): List<Int> {
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
         * How far off each boundary the distance is read, in kilometers: 398.4375, which is 34
         * rows and 17 columns on the 512 by 512 grid of a 12,000 by 6,000 km world and a whole
         * number of rows and of columns on every grid of these cases.
         */
        const val OFFSET_KM = 398.4375

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

        /** The four standard seeds. */
        val BELT_SEEDS = listOf(7L, 42L, 1234L, 99L)

        /** The seed the belts' control is taken on. */
        const val CONTROL_SEED = 42L

        /** The percentile of the rising cells' distance from a boundary read as the belts' reach. */
        const val BELT_REACH_PERCENTILE = 0.9

        /**
         * How far the share of the map the belts cover may move between two grids: the largest
         * relative difference the four standard seeds show between 512 by 512 and 1,024 by 1,024,
         * 1.72% on seed 99 (0.2264 against 0.2303), rounded up at the second figure. A change of
         * grid moves it that much and a square grid may move it no more. See
         * docs/DESIGN_LEDGER.md, Q1.
         */
        const val BELT_SHARE_SPREAD = 0.018

        /**
         * The same for the belts' reach: 2.73% on seed 99 (388 against 399 km), rounded up at the
         * second figure.
         */
        const val BELT_REACH_SPREAD = 0.028
    }
}
