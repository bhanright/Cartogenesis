package com.cartogenesis.worldgen

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What [RangeFront] reads off a range whose answer is known before it is asked.
 *
 * X1d measures a spacing in order to decide whether the landscape or the grid sets it, and the one
 * way that measurement can go wrong without saying so is for the instrument to be reading the grid
 * itself. So the instrument is driven here by two synthetic ranges with outlets laid exactly five
 * cells apart along their fronts — one front running east-west, one north-south — on this
 * project's own grid, where a cell is twice as wide as it is tall. The same five cells is 117 km
 * along one front and 59 km along the other, and a finder that could not tell those apart could not
 * answer the question it was built for.
 *
 * Cheap: no world is generated. The fields are written straight into the arrays the finder reads.
 */
class RangeFrontTest {

    private companion object {
        const val CELLS_ACROSS = 512
        const val CELLS_DOWN = 512

        /** The 512 grid this project measures at: 12,000 km over 512 columns, 6,000 over 512 rows. */
        const val CELL_WIDTH_KM = 12_000.0 / CELLS_ACROSS
        const val CELL_HEIGHT_KM = 6_000.0 / CELLS_DOWN

        const val BELT_METRES = 2_000f
        const val LOWLAND_METRES = 100f

        /**
         * Outlets every five cells along the front: half the author's reported ten, so a range
         * forty rows deep still carries a dozen of them along a front of fifty columns.
         */
        const val OUTLET_SPACING_CELLS = 5
    }

    /** A grid with nothing on it but one rectangular range and the routing that drains it. */
    private class Fixture(
        val metres: FloatArray,
        val flowTarget: IntArray,
        val isLand: BooleanArray
    )

    /**
     * A rectangular range whose basins all leave by one edge, with their outlets evenly spaced.
     *
     * [frontOnSouthEdge] puts the front along a row, so the outlets are spaced by cell widths;
     * otherwise it runs along the range's eastern column and they are spaced by cell heights. Water
     * inside the range moves first onto its own outlet's line and then straight out.
     */
    private fun rectangularRange(
        firstColumn: Int,
        lastColumn: Int,
        firstRow: Int,
        lastRow: Int,
        frontOnSouthEdge: Boolean
    ): Fixture {
        val cellCount = CELLS_ACROSS * CELLS_DOWN
        val metres = FloatArray(cellCount) { LOWLAND_METRES }
        val flowTarget = IntArray(cellCount) { -1 }
        val isLand = BooleanArray(cellCount) { true }

        fun cellAt(column: Int, row: Int) = row * CELLS_ACROSS + column

        for (row in firstRow..lastRow) {
            for (column in firstColumn..lastColumn) {
                metres[cellAt(column, row)] = BELT_METRES
            }
        }

        if (frontOnSouthEdge) {
            for (row in firstRow..lastRow) {
                for (column in firstColumn..lastColumn) {
                    val outletColumn = outletLine(column, firstColumn, lastColumn)
                    flowTarget[cellAt(column, row)] = when {
                        column < outletColumn -> cellAt(column + 1, row)
                        column > outletColumn -> cellAt(column - 1, row)
                        else -> cellAt(column, row + 1)
                    }
                }
            }
        } else {
            for (row in firstRow..lastRow) {
                for (column in firstColumn..lastColumn) {
                    val outletRow = outletLine(row, firstRow, lastRow)
                    flowTarget[cellAt(column, row)] = when {
                        row < outletRow -> cellAt(column, row + 1)
                        row > outletRow -> cellAt(column, row - 1)
                        else -> cellAt(column + 1, row)
                    }
                }
            }
        }
        return Fixture(metres, flowTarget, isLand)
    }

    /** The evenly spaced line a cell drains onto, snapped to the nearest one inside the range. */
    private fun outletLine(at: Int, first: Int, last: Int): Int {
        val steps = ((at - first).toDouble() / OUTLET_SPACING_CELLS).toInt()
        return (first + steps * OUTLET_SPACING_CELLS).coerceAtMost(last)
    }

    private fun report(fixture: Fixture) = RangeFront.measure(
        seed = 0L,
        cellsAcross = CELLS_ACROSS,
        cellsDown = CELLS_DOWN,
        cellWidthKm = CELL_WIDTH_KM,
        cellHeightKm = CELL_HEIGHT_KM,
        isLand = fixture.isLand,
        metresAboveShoreline = fixture.metres,
        flowTarget = fixture.flowTarget,
        beltFloorMetres = 1_000f
    )

    /**
     * Outlets five cells apart along a front that runs east-west read as five cells and 117 km.
     *
     * The range is 51 columns wide and 40 rows deep, which is 1,195 by 469 km, so its southern edge
     * is a front well over the 300 km bar and its basins run the range's whole depth to the divide
     * along its northern edge.
     */
    @Test
    fun `a front along a row reads its outlet spacing in cell widths`() {
        val report = report(rectangularRange(100, 150, 10, 49, frontOnSouthEdge = true))
        val front = southernEastWestFront(report)

        val spacingKm = assertNotNull(front.medianSpacingKm, "the front carries a spacing")
        val expectedKm = OUTLET_SPACING_CELLS * CELL_WIDTH_KM
        assertTrue(
            abs(spacingKm - expectedKm) <= CELL_WIDTH_KM / 2,
            "spacing $spacingKm km against the $expectedKm km laid down"
        )
        val spacingCells = assertNotNull(report.spacingInCells(front))
        assertTrue(
            abs(spacingCells - OUTLET_SPACING_CELLS) <= 0.5,
            "spacing $spacingCells cells against the $OUTLET_SPACING_CELLS laid down"
        )

        // The divide is the range's northern edge, 39 rows from the front. The front is found on
        // the square reference grid, whose cells are two of these rows tall, so where its chord
        // lies is known to one reference cell and no better.
        val depthKm = assertNotNull(front.medianDivideToFrontKm)
        val expectedDepthKm = (49 - 10) * CELL_HEIGHT_KM
        assertTrue(
            abs(depthKm - expectedDepthKm) <= CELL_WIDTH_KM,
            "divide-to-front $depthKm km against the $expectedDepthKm km laid down"
        )
        front.spacingsKm.forEach {
            assertTrue(
                abs(it - expectedKm) <= CELL_WIDTH_KM / 2,
                "every gap is the one laid down, not just the median: $it km"
            )
        }
        // Eleven outlet lines were laid across the 51 columns and the chord is cut a cell or two
        // short of each corner by the straightness walk, so the outermost may fall outside it.
        assertTrue(
            front.trunks.size >= 10,
            "at least ten of the eleven outlet lines are trunks: ${front.trunks.size}"
        )
        println(
            "X1d FIXTURE east-west front: %.1f km, %.2f cells, divide %.1f km, Hovius %.2f".format(
                spacingKm, spacingCells, depthKm, front.hoviusRatio ?: Double.NaN
            )
        )
    }

    /**
     * The same five cells along a front that runs north-south reads as five cells and 59 km.
     *
     * This is the whole discriminator X1d turns on. A spacing set by the grid is the same count of
     * cells whichever way the front runs and half the kilometres on one of them; a spacing set by
     * the ground is the same kilometres and twice the cells. The finder has to report both, and
     * here it is shown doing so on a range where the answer was written in by hand.
     */
    @Test
    fun `a front along a column reads the same cells and half the kilometres`() {
        val report = report(rectangularRange(200, 239, 100, 200, frontOnSouthEdge = false))
        val front = easternNorthSouthFront(report)

        val spacingKm = assertNotNull(front.medianSpacingKm, "the front carries a spacing")
        val expectedKm = OUTLET_SPACING_CELLS * CELL_HEIGHT_KM
        assertTrue(
            abs(spacingKm - expectedKm) <= CELL_HEIGHT_KM / 2,
            "spacing $spacingKm km against the $expectedKm km laid down"
        )
        val spacingCells = assertNotNull(report.spacingInCells(front))
        assertTrue(
            abs(spacingCells - OUTLET_SPACING_CELLS) <= 0.5,
            "spacing $spacingCells cells against the $OUTLET_SPACING_CELLS laid down"
        )
        println(
            "X1d FIXTURE north-south front: %.1f km, %.2f cells, divide %.1f km".format(
                spacingKm, spacingCells, front.medianDivideToFrontKm ?: Double.NaN
            )
        )
    }

    /**
     * A world with no ground above the belt floor reports no sample, and not a spacing of zero.
     *
     * The rule the audit's tables depend on: a median over nothing is null, and the census says
     * which filter emptied the sample.
     */
    @Test
    fun `a world with no belt reports an empty sample rather than a zero`() {
        val cellCount = CELLS_ACROSS * CELLS_DOWN
        val report = RangeFront.measure(
            seed = 0L,
            cellsAcross = CELLS_ACROSS,
            cellsDown = CELLS_DOWN,
            cellWidthKm = CELL_WIDTH_KM,
            cellHeightKm = CELL_HEIGHT_KM,
            isLand = BooleanArray(cellCount) { true },
            metresAboveShoreline = FloatArray(cellCount) { LOWLAND_METRES },
            flowTarget = IntArray(cellCount) { -1 }
        )
        assertEquals(0, report.census.beltCells)
        assertEquals(0, report.census.fronts)
        assertTrue(report.sample.isEmpty(), "no front carries a spacing")
        assertNull(RangeFront.median(report.gapsKm(trunksOnly = true)))
        assertNull(RangeFront.median(report.halfWidthsKm()))
        assertTrue(report.census.toString().contains("0 over 300 km"), "${report.census}")
    }

    /**
     * A gully whose head is enclosed by its neighbours is a catchment and not a trunk basin.
     *
     * The range above is re-laid with one extra outlet halfway between two of the others, fed only
     * by the four rows nearest the front — sixteen cells, 4,400 km2, over the catchment floor — so
     * it reaches the front and not the divide. Hovius's ratio is over trunk basins, and counting that
     * gully would halve the spacing without the landscape having changed; the comb, which counts
     * every catchment, is where it belongs.
     */
    @Test
    fun `a basin that does not reach the divide is a catchment and not a trunk`() {
        val plain = report(rectangularRange(100, 150, 10, 49, frontOnSouthEdge = true))
        val plainFront = southernEastWestFront(plain)

        val fixture = rectangularRange(100, 150, 10, 49, frontOnSouthEdge = true)
        // Four rows either side of column 122 now leave by a gully of their own at the front.
        for (row in 46..49) {
            for (column in 121..123) {
                val cell = row * CELLS_ACROSS + column
                fixture.flowTarget[cell] = when {
                    column < 122 -> cell + 1
                    column > 122 -> cell - 1
                    else -> cell + CELLS_ACROSS
                }
            }
        }
        val gullied = report(fixture)
        val gulliedFront = southernEastWestFront(gullied)

        assertEquals(
            plainFront.trunks.size, gulliedFront.trunks.size,
            "the gully reached the front but not the divide, so it is not a trunk"
        )
        assertEquals(
            plainFront.catchments.size + 1, gulliedFront.catchments.size,
            "the gully is a catchment of its own, so the comb counts it"
        )
        assertTrue(
            RangeFront.median(gulliedFront.catchmentSpacingsKm)!! <
                RangeFront.median(gulliedFront.spacingsKm)!! + 1e-9,
            "the comb's spacing is no wider than the trunks'"
        )
    }

    /**
     * A basin under the catchment floor is set aside as a corner of the grid, not counted.
     *
     * Two cells at the front given an exit of their own: a real routing leaves such pairs all along
     * a belt's edge, and counted as outlets they would put the comb at a cell or two whatever the
     * landscape did.
     */
    @Test
    fun `a basin under the catchment floor is a corner and not a catchment`() {
        val plain = report(rectangularRange(100, 150, 10, 49, frontOnSouthEdge = true))
        val plainFront = southernEastWestFront(plain)

        val fixture = rectangularRange(100, 150, 10, 49, frontOnSouthEdge = true)
        val corner = 49 * CELLS_ACROSS + 123
        fixture.flowTarget[corner] = corner + CELLS_ACROSS
        fixture.flowTarget[corner - CELLS_ACROSS] = corner
        val cornered = report(fixture)
        val corneredFront = southernEastWestFront(cornered)

        assertEquals(plainFront.catchments.size, corneredFront.catchments.size)
        assertEquals(plainFront.cornersSetAside + 1, corneredFront.cornersSetAside)
    }

    /**
     * The stamp's half-width in kilometres depends on which way the front runs.
     *
     * `PlateStage` counts a belt's half-width in cells with no row scale, so fourteen cells across
     * a front running east-west is fourteen cell heights and across one running north-south is
     * fourteen cell widths — 164 and 328 km on the 512 grid. The audit prints the measured
     * half-width beside this figure, and it has to be the right figure for the front's bearing.
     */
    @Test
    fun `the stamped half-width is converted along the front's own normal`() {
        val eastWest = southernEastWestFront(
            report(rectangularRange(100, 150, 10, 49, frontOnSouthEdge = true))
        ).front
        val northSouth = easternNorthSouthFront(
            report(rectangularRange(200, 239, 100, 200, frontOnSouthEdge = false))
        ).front
        val acrossEastWest = eastWest.kilometresOfCellsAcross(14.0, CELL_WIDTH_KM, CELL_HEIGHT_KM)
        val acrossNorthSouth =
            northSouth.kilometresOfCellsAcross(14.0, CELL_WIDTH_KM, CELL_HEIGHT_KM)
        assertTrue(abs(acrossEastWest - 14 * CELL_HEIGHT_KM) < 1.0, "$acrossEastWest km")
        assertTrue(abs(acrossNorthSouth - 14 * CELL_WIDTH_KM) < 1.0, "$acrossNorthSouth km")
    }

    private fun southernEastWestFront(report: RangeFront.Report): RangeFront.Measured {
        val fronts = report.measured.filter { it.front.bearing == RangeFront.Bearing.EAST_WEST }
        assertTrue(fronts.isNotEmpty(), "an east-west front was found: ${report.census}")
        return fronts.maxBy { it.front.midKmY }
    }

    private fun easternNorthSouthFront(report: RangeFront.Report): RangeFront.Measured {
        val fronts = report.measured.filter { it.front.bearing == RangeFront.Bearing.NORTH_SOUTH }
        assertTrue(fronts.isNotEmpty(), "a north-south front was found: ${report.census}")
        return fronts.maxBy { it.front.midKmX }
    }
}
