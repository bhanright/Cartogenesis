package com.cartogenesis.worldgen

import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

/**
 * How crinkled a coastline is — pooled over a whole world, octave by octave, and stretch by
 * stretch.
 *
 * One object rather than a helper inside each test, because three questions are asked of the same
 * arithmetic and they have to be asked the same way: what the world's coastline measures against
 * Earth's 1.2 to 1.3, at which scale the excess sits, and how much the answer varies from one
 * stretch of coast to another. Everything here reads a plain land mask, so a synthetic control can
 * be handed to the same function a generated world goes through.
 *
 * Earth's figures and their sources sit beside the constants that hold them.
 */
internal object CoastRoughness {

    /**
     * Coastline fractal dimension. Mandelbrot (1967), from Richardson's divider measurements:
     * Britain 1.25, Norway's fjord coast higher, Australia 1.13, South Africa's smooth arc 1.02.
     * A whole world's coastline pools all of those, so Britain's figure is the reference.
     */
    const val EARTH_COASTLINE_DIMENSION = 1.25

    /**
     * How far from Britain's figure a whole world's coastline may sit: 0.15, both ways.
     *
     * Copied with its derivation from `EarthLikeness.COASTLINE_DIMENSION_TOLERANCE` on `main`, where
     * M1 wrote it: "Richardson's coasts, as Mandelbrot (1967) reports them, run from South Africa's
     * single smooth arc at 1.02 to Britain's 1.25 and higher on a fjord coast. A whole world's
     * coastline pools every kind of coast it has, so it belongs near the middle of that spread and
     * not at either end: the floor is set well above the smoothest coast Richardson measured, and
     * the ceiling the same distance above Britain as the floor is below it."
     *
     * The plan's M1 *table* summarises this as 1.2 to 1.3, and F17's specification repeats the
     * table. The suite that does the measuring asserts the tolerance, and the tolerance is what is
     * asserted here, for the reason set out over `LittoralCoastTest`'s first case.
     */
    const val COASTLINE_DIMENSION_TOLERANCE = 0.15

    /**
     * The dimension at or below which a stretch of coast reads as smooth.
     *
     * Australia's 1.13, the same Richardson series Britain's 1.25 comes from. Australia is the
     * smoothest coast of any continent bar South Africa's 1.02 arc, and it is smooth for the reason
     * this measurement is about: long depositional sectors — the Ninety Mile Beach, the Coorong,
     * the Eighty Mile Beach — where a littoral drift system has graded the shoreline into arcs.
     * A stretch reading at or under it is a stretch Earth would call depositional rather than
     * drowned.
     */
    const val SMOOTH_SEGMENT_DIMENSION = 1.13

    /**
     * The share of Earth's shoreline that is depositional.
     *
     * Luijendijk et al. (2018), *Scientific Reports* 8:6641, classify 31% of the world's ice-free
     * shoreline as sandy from satellite imagery; Bird (2000), *Coastal Geomorphology: An
     * Introduction*, puts the depositional share of the world's coast at about a third, the
     * remainder being cliffed, rocky or ice. Both give the same figure to the precision this
     * measurement can resolve, so the bar is a third.
     */
    const val EARTH_DEPOSITIONAL_SHARE = 0.31

    /**
     * Box sizes the pooled dimension is fitted over, in cells: three octaves.
     *
     * Copied from `EarthLikeness.COASTLINE_BOX_SIZES` on `main`, where M1 measured the generator's
     * 1.20 pooled, so this chunk's figure and M1's are the same figure. Not starting at one cell,
     * where every box holding coast is its own box and the count is the coast's length rather than
     * a measure of it, and not going past a sixteenth of the grid, where a whole continent fits in
     * one box.
     */
    val POOLED_BOX_SIZES = intArrayOf(4, 8, 16)

    /**
     * Box sizes the octave table walks, in cells.
     *
     * Five octaves rather than M1's three, because the question this chunk asks is *at which scale*
     * the coast is too crinkled, and M1's fit starts two octaves above the cell. See
     * [boundaryBoxCount] for why one cell needs a slightly different box than four does.
     */
    val OCTAVE_BOX_SIZES = intArrayOf(1, 2, 4, 8, 16)

    /** How many boxes of each size the coastline passes through, and the slope that implies. */
    class BoxCount(val boxSizes: IntArray, val boxes: LongArray) {
        /**
         * The box-counting dimension: minus the slope of log count against log box size.
         *
         * A straight coast halves its box count when the boxes double, so the slope is -1 and the
         * dimension 1; a coast that keeps finding detail at every scale loses less than half and
         * the dimension climbs toward 2.
         */
        val dimension: Double = run {
            val used = boxSizes.indices.filter { boxes[it] > 0 }
            if (used.size < 2) 0.0
            else -slopeOf(used.map { ln(boxSizes[it].toDouble()) }, used.map { ln(boxes[it].toDouble()) })
        }

        /**
         * The dimension read off one octave alone: the count's fall from `boxSizes[index]` to the
         * size above it, which is what says where an excess sits rather than that there is one.
         */
        fun dimensionAcrossOctave(index: Int): Double {
            if (index + 1 >= boxSizes.size || boxes[index] <= 0L || boxes[index + 1] <= 0L) return 0.0
            val sizeRatio = ln(boxSizes[index + 1].toDouble() / boxSizes[index])
            return ln(boxes[index].toDouble() / boxes[index + 1]) / sizeRatio
        }

        operator fun plus(other: BoxCount): BoxCount =
            BoxCount(boxSizes, LongArray(boxes.size) { boxes[it] + other.boxes[it] })
    }

    /**
     * Counts the boxes of each size that hold both land and water, which is the boxes the
     * shoreline passes through.
     *
     * Copied verbatim from `EarthLikeness.coastlineBoxCount` on `main` — M1's own arithmetic, so
     * the pooled figure this chunk reports and the 1.20 M1 measured are comparable to the last
     * digit — with only the parameter names left as they were.
     *
     * Land-and-water rather than "holds a coast cell", because a coast cell has a thickness of one
     * cell and a box the size of one cell would then count the coastline's *area*. A box the
     * shoreline crosses is a box with something on both sides of it, at every size, which is the
     * divider method on a grid. Boxes tile from the left edge; the grid wraps in x and its width is
     * a power of two, so every box size here divides it and no box straddles the seam.
     */
    fun coastlineBoxCount(
        isLand: BooleanArray,
        cellsAcross: Int,
        cellsDown: Int,
        boxSizes: IntArray = POOLED_BOX_SIZES
    ): BoxCount {
        val boxes = LongArray(boxSizes.size)
        boxSizes.forEachIndexed { sizeIndex, size ->
            var crossed = 0L
            var boxTop = 0
            while (boxTop < cellsDown) {
                var boxLeft = 0
                while (boxLeft < cellsAcross) {
                    var sawLand = false
                    var sawWater = false
                    var row = boxTop
                    while (row < min(boxTop + size, cellsDown) && !(sawLand && sawWater)) {
                        var column = boxLeft
                        while (column < min(boxLeft + size, cellsAcross)) {
                            if (isLand[row * cellsAcross + column]) sawLand = true else sawWater = true
                            if (sawLand && sawWater) break
                            column++
                        }
                        row++
                    }
                    if (sawLand && sawWater) crossed++
                    boxLeft += size
                }
                boxTop += size
            }
            boxes[sizeIndex] = crossed
        }
        return BoxCount(boxSizes, boxes)
    }

    /**
     * The same count, extended down to a box one cell across.
     *
     * [coastlineBoxCount] asks whether a box holds both land and water, which a box of one cell
     * never can, so M1's fit starts at four. The octave table has to reach the cell, because that
     * is the scale the author's crops are about. The smallest change that gets there: a box still
     * tiles at its own size, but the test looks at the block one cell wider — the shoreline passes
     * through a box when there is land and water within a cell of it. At four cells and above the
     * extra row and column change the count by a percent or two and the two functions agree; at
     * one cell it is the standard boundary count, and it is what a divider walking the shoreline
     * cell by cell would measure.
     */
    fun boundaryBoxCount(
        isLand: BooleanArray,
        cellsAcross: Int,
        cellsDown: Int,
        boxSizes: IntArray = OCTAVE_BOX_SIZES
    ): BoxCount {
        val boxes = LongArray(boxSizes.size)
        boxSizes.forEachIndexed { sizeIndex, size ->
            var crossed = 0L
            var boxTop = 0
            while (boxTop < cellsDown) {
                var boxLeft = 0
                while (boxLeft < cellsAcross) {
                    if (mixed(isLand, cellsAcross, cellsDown, boxLeft, boxTop, size + 1)) crossed++
                    boxLeft += size
                }
                boxTop += size
            }
            boxes[sizeIndex] = crossed
        }
        return BoxCount(boxSizes, boxes)
    }

    /** Whether a block of [span] cells anchored at ([left], [top]) holds both land and water. */
    private fun mixed(
        isLand: BooleanArray,
        cellsAcross: Int,
        cellsDown: Int,
        left: Int,
        top: Int,
        span: Int
    ): Boolean {
        var sawLand = false
        var sawWater = false
        var row = top
        while (row < min(top + span, cellsDown)) {
            var column = left
            while (column < left + span) {
                // The map is a cylinder, so a block at the right-hand edge reads on round the seam.
                val wrapped = if (column >= cellsAcross) column - cellsAcross else column
                if (isLand[row * cellsAcross + wrapped]) sawLand = true else sawWater = true
                if (sawLand && sawWater) return true
                column++
            }
            row++
        }
        return false
    }

    /**
     * The shoreline's length in cell edges: pairs of side-by-side cells, one land and one water.
     *
     * The finest measure of a coast a grid has, and the one the cell-scale fringe shows up in
     * first. Wraps in x with the rest of the generator; the poles are an edge and contribute
     * nothing across it.
     */
    fun shorelineEdges(isLand: BooleanArray, cellsAcross: Int, cellsDown: Int): Long {
        var edges = 0L
        for (row in 0 until cellsDown) {
            for (column in 0 until cellsAcross) {
                val here = isLand[row * cellsAcross + column]
                val east = isLand[row * cellsAcross + (column + 1) % cellsAcross]
                if (here != east) edges++
                if (row + 1 < cellsDown && here != isLand[(row + 1) * cellsAcross + column]) edges++
            }
        }
        return edges
    }

    /**
     * The coastline's length measured with a ruler of [rulerCells] cells, in cells.
     *
     * Richardson's own method rather than a box count, and the reason is that a box count saturates
     * where this question is asked. [boundaryBoxCount] at one cell can return at most one box per
     * position, so on a coast with a tooth in every cell the count runs into its own ceiling and the
     * octave reads *smoother* than the one above it — which is what the shipped world does, 1.236
     * over the first octave against 1.32 over the second, and it is an artefact of the instrument
     * rather than a fact about the coast. A length has no ceiling: a cell can contribute up to four
     * edges.
     *
     * The ruler is applied by coarsening: the mask is reduced to blocks [rulerCells] on a side, each
     * taking the class of its majority, and the shoreline of the coarse mask is counted in edges and
     * multiplied back up. That is a divider walked at that step, and Richardson's law says the
     * length grows as `r^(1-D)` as the ruler shortens, so a straight coast measures the same at
     * every ruler and a crinkled one measures longer at the short ones.
     */
    fun richardsonLength(
        isLand: BooleanArray,
        cellsAcross: Int,
        cellsDown: Int,
        rulerCells: Int
    ): Double {
        if (rulerCells <= 1) {
            return shorelineEdges(isLand, cellsAcross, cellsDown).toDouble()
        }
        val coarseAcross = cellsAcross / rulerCells
        val coarseDown = cellsDown / rulerCells
        if (coarseAcross < 2 || coarseDown < 2) return 0.0
        val coarse = BooleanArray(coarseAcross * coarseDown)
        val half = rulerCells * rulerCells / 2
        for (blockRow in 0 until coarseDown) {
            for (blockColumn in 0 until coarseAcross) {
                var land = 0
                for (row in 0 until rulerCells) {
                    val sourceRow = blockRow * rulerCells + row
                    for (column in 0 until rulerCells) {
                        val sourceColumn = blockColumn * rulerCells + column
                        if (isLand[sourceRow * cellsAcross + sourceColumn]) land++
                    }
                }
                coarse[blockRow * coarseAcross + blockColumn] = land > half
            }
        }
        return shorelineEdges(coarse, coarseAcross, coarseDown).toDouble() * rulerCells
    }

    /**
     * The dimension Richardson's law implies between two rulers: `L` grows as `r^(1-D)`, so
     * doubling the ruler and watching the length fall by a factor `f` gives `D = 1 + log2(f)`.
     *
     * One means a line that measures the same however it is walked. Two means a line that fills the
     * plane.
     */
    fun richardsonDimension(shorterLength: Double, longerLength: Double): Double {
        if (shorterLength <= 0.0 || longerLength <= 0.0) return 0.0
        return 1.0 + ln(shorterLength / longerLength) / ln(2.0)
    }

    /**
     * The dimension over a span of rulers, fitted rather than taken two at a time: minus the slope
     * of log length against log ruler, plus one.
     */
    fun richardsonDimensionOver(lengths: List<Double>, rulers: List<Int>): Double {
        val used = lengths.indices.filter { lengths[it] > 0.0 }
        if (used.size < 2) return 0.0
        return 1.0 - slopeOf(
            used.map { ln(rulers[it].toDouble()) },
            used.map { ln(lengths[it]) }
        )
    }

    /**
     * One stretch of coast: a square window of the map, the coast inside it, and how crinkled that
     * coast is.
     */
    class Segment(
        val left: Int,
        val top: Int,
        val shorelineCells: Int,
        val boxes: BoxCount
    ) {
        val dimension: Double get() = boxes.dimension
        val isSmooth: Boolean get() = dimension <= SMOOTH_SEGMENT_DIMENSION
    }

    /**
     * How the coast's roughness varies from one stretch to the next, and what share of it is smooth.
     *
     * The map is cut into windows [windowCells] on a side and each window that holds enough
     * shoreline gets its own dimension, fitted over the three finest octaves — one, two and four
     * cells — because a window is too small to carry sixteen-cell boxes and because the fringe this
     * chunk is about lives at the cell. A window with only a handful of coast cells in it cannot
     * carry a slope at all and is left out; [minimumShorelineCells] is that floor.
     *
     * Weighted by the coast each window holds rather than by the count of windows, so a window
     * holding six coast cells does not out-vote one holding six hundred.
     */
    class Spread(val segments: List<Segment>) {
        val coastCells: Int = segments.sumOf { it.shorelineCells }

        /** The share of the shoreline lying in stretches Earth would call depositional. */
        val smoothShare: Double =
            if (coastCells == 0) 0.0
            else segments.filter { it.isSmooth }.sumOf { it.shorelineCells }.toDouble() / coastCells

        /** How far the per-stretch dimensions spread about their own coast-weighted mean. */
        val dimensionStandardDeviation: Double = run {
            if (coastCells == 0) return@run 0.0
            val mean = segments.sumOf { it.dimension * it.shorelineCells } / coastCells
            val variance =
                segments.sumOf { (it.dimension - mean) * (it.dimension - mean) * it.shorelineCells } /
                    coastCells
            sqrt(variance)
        }

        val meanDimension: Double =
            if (coastCells == 0) 0.0
            else segments.sumOf { it.dimension * it.shorelineCells } / coastCells

        operator fun plus(other: Spread): Spread = Spread(segments + other.segments)
    }

    /** Windows this many cells on a side; see [spreadOfCoast] for why. */
    const val SEGMENT_WINDOW_CELLS = 32

    /** The fewest coast cells a window needs before its dimension is fitted. */
    const val MINIMUM_SEGMENT_SHORELINE_CELLS = 12

    val SEGMENT_BOX_SIZES = intArrayOf(1, 2, 4)

    /**
     * Cuts the map into windows and measures each one's coast.
     *
     * [windowCells] on a side: 32 at 512, which on a 12,000 km world is 750 km of map and holds a
     * stretch of coast the length of the German Bight or of the Carolina barrier chain — one
     * coastal setting rather than a whole continent's worth of them. Held in cells rather than as a
     * map fraction, so the octaves inside a window are the same three octaves at every grid and a
     * finer grid answers the same question about a shorter stretch.
     */
    fun spreadOfCoast(
        isLand: BooleanArray,
        cellsAcross: Int,
        cellsDown: Int,
        windowCells: Int = SEGMENT_WINDOW_CELLS,
        minimumShorelineCells: Int = MINIMUM_SEGMENT_SHORELINE_CELLS
    ): Spread {
        val segments = ArrayList<Segment>()
        var top = 0
        while (top < cellsDown) {
            var left = 0
            while (left < cellsAcross) {
                val window = windowOf(isLand, cellsAcross, cellsDown, left, top, windowCells)
                val coastCells = shorelineCellCount(window, windowCells, windowCells)
                if (coastCells >= minimumShorelineCells) {
                    val boxes = boundaryBoxCount(window, windowCells, windowCells, SEGMENT_BOX_SIZES)
                    segments.add(Segment(left, top, coastCells, boxes))
                }
                left += windowCells
            }
            top += windowCells
        }
        return Spread(segments)
    }

    /** A window of the land mask, wrapping in x and clamping at the poles. */
    private fun windowOf(
        isLand: BooleanArray,
        cellsAcross: Int,
        cellsDown: Int,
        left: Int,
        top: Int,
        windowCells: Int
    ): BooleanArray {
        val window = BooleanArray(windowCells * windowCells)
        for (row in 0 until windowCells) {
            val sourceRow = (top + row).coerceAtMost(cellsDown - 1)
            for (column in 0 until windowCells) {
                val sourceColumn = (left + column) % cellsAcross
                window[row * windowCells + column] = isLand[sourceRow * cellsAcross + sourceColumn]
            }
        }
        return window
    }

    /** Cells with land on one side of them and water on the other, counted once each. */
    fun shorelineCellCount(isLand: BooleanArray, cellsAcross: Int, cellsDown: Int): Int {
        var count = 0
        for (row in 0 until cellsDown) {
            for (column in 0 until cellsAcross) {
                val cell = row * cellsAcross + column
                val here = isLand[cell]
                var differs = false
                if (isLand[row * cellsAcross + (column + 1) % cellsAcross] != here) differs = true
                if (isLand[row * cellsAcross + (column + cellsAcross - 1) % cellsAcross] != here) differs = true
                if (row > 0 && isLand[(row - 1) * cellsAcross + column] != here) differs = true
                if (row + 1 < cellsDown && isLand[(row + 1) * cellsAcross + column] != here) differs = true
                if (differs) count++
            }
        }
        return count
    }

    /**
     * What is wrong with a coastline's dimension, or nothing. The shape
     * `EarthLikeness.coastlineComplaint` on `main` has, so a run reports every seed that is out
     * rather than stopping at the first.
     */
    fun dimensionComplaint(label: String, coastline: BoxCount): String? =
        dimensionComplaint(label, coastline.dimension)

    /** The same, for a dimension measured any other way. */
    fun dimensionComplaint(label: String, dimension: Double): String? {
        if (dimension >= EARTH_COASTLINE_DIMENSION - COASTLINE_DIMENSION_TOLERANCE &&
            dimension <= EARTH_COASTLINE_DIMENSION + COASTLINE_DIMENSION_TOLERANCE
        ) {
            return null
        }
        return "$label: the coastline's box-counting dimension is ${"%.3f".format(dimension)}," +
            " outside $EARTH_COASTLINE_DIMENSION +/- $COASTLINE_DIMENSION_TOLERANCE" +
            " (Mandelbrot 1967: Britain 1.25, Richardson's smoothest coast 1.02)"
    }

    /**
     * The sizes of the separate bodies of land, largest first, by eight-connectivity and wrapping
     * in x — the same neighbour walk the rest of the generator uses.
     */
    fun landBodyCells(isLand: BooleanArray, cellsAcross: Int, cellsDown: Int): List<Int> =
        bodyCells(isLand, cellsAcross, cellsDown, wanted = true)

    /** The same, for the water: the ocean first, then whatever it cannot reach. */
    fun waterBodyCells(isLand: BooleanArray, cellsAcross: Int, cellsDown: Int): List<Int> =
        bodyCells(isLand, cellsAcross, cellsDown, wanted = false)

    private fun bodyCells(
        isLand: BooleanArray,
        cellsAcross: Int,
        cellsDown: Int,
        wanted: Boolean
    ): List<Int> {
        val body = IntArray(isLand.size) { -1 }
        val stack = IntArray(isLand.size)
        val sizes = ArrayList<Int>()
        for (start in isLand.indices) {
            if (isLand[start] != wanted || body[start] >= 0) continue
            var top = 0
            body[start] = sizes.size
            stack[top++] = start
            var found = 0
            while (top > 0) {
                val cell = stack[--top]
                found++
                val row = cell / cellsAcross
                val column = cell % cellsAcross
                for (rowStep in -1..1) {
                    val neighbourRow = row + rowStep
                    if (neighbourRow < 0 || neighbourRow >= cellsDown) continue
                    for (columnStep in -1..1) {
                        val neighbourColumn = (column + columnStep + cellsAcross) % cellsAcross
                        val neighbour = neighbourRow * cellsAcross + neighbourColumn
                        if (isLand[neighbour] == wanted && body[neighbour] < 0) {
                            body[neighbour] = sizes.size
                            stack[top++] = neighbour
                        }
                    }
                }
            }
            sizes.add(found)
        }
        return sizes.sortedDescending()
    }

    /** Least squares, for the log-log fits above. */
    private fun slopeOf(x: List<Double>, y: List<Double>): Double {
        val n = x.size
        val meanX = x.sum() / n
        val meanY = y.sum() / n
        var covariance = 0.0
        var varianceX = 0.0
        for (index in 0 until n) {
            covariance += (x[index] - meanX) * (y[index] - meanY)
            varianceX += (x[index] - meanX) * (x[index] - meanX)
        }
        return if (varianceX == 0.0) 0.0 else covariance / varianceX
    }
}
