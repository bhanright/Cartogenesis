package com.cartogenesis.cartography

import com.cartogenesis.cartography.geometry.Contours
import com.cartogenesis.cartography.geometry.FacingShares
import com.cartogenesis.cartography.geometry.GridFrame
import com.cartogenesis.cartography.geometry.Outcome
import com.cartogenesis.worldgen.BorrowsSharedWorlds
import com.cartogenesis.worldgen.SharedWorlds
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That the coast is drawn on the shoreline the ground has, and the same on every side of the land
 * (docs/DESIGN_LEDGER.md, G2).
 *
 * The field's shoreline here is traced by the geometry census's own tracer ([Contours.ofField]) on
 * the cells' altitudes in metres, which shares no code with the drawing's [CoastLine]: the two
 * agree only if the drawing puts its line where the ground crosses the waterline. Where the mask
 * the coast is drawn round and the sign of the ground disagree — a narrow channel's bank, ground
 * the ice cut below the waterline — the ground cannot say where the shore is, and those cells are
 * left out and counted.
 *
 * Each clause has its control, the operator the drawing used before: the raster inked a bank cell
 * only where water lay east or south of it, and the overlay ran along the half-cell lattice.
 */
class CoastLineTest : BorrowsSharedWorlds() {

    private companion object {
        /** The standard worlds at the rows the per-merge guards read. */
        val SEEDS = listOf(42L, 7L)
        const val ROWS = 512

        /**
         * How far the raster's ink may stand from the pen's at the field's own distance: float
         * arithmetic on two routes to one number, a crossing worked out as a float share on one
         * side and a double on the other, and nothing larger.
         */
        const val INK_AGREEMENT = 1e-4f

        /**
         * The share of the inked cells, or of the drawn vertices, that must lie where the ground
         * and the mask agree for a world's clause to say anything: most of the coast, so the clause
         * is about the coast and not about a remnant of it. Where they disagree is land the mask
         * keeps below the waterline: 2,149 cells of seed 42 at 512 rows, down to 1,615 m, 166 of
         * them under lakes.
         */
        const val LEAST_SHARE_CHECKED = 0.5

        /** The planes' bearings, every seven and a half degrees round a half turn. */
        const val PLANE_BEARINGS = 24
    }

    private fun world(seed: Long): WorldMap = SharedWorlds.world(WorldGenConfig.forRows(seed = seed, rows = ROWS))

    // ---- on a plane, where the shoreline is known ---------------------------------------------

    /**
     * A sloping plane's shoreline is a straight line, and marching squares interpolate it exactly,
     * so at every bearing each cell's ink is the pen's at the cell's true distance from it, on cells
     * as wide on the sheet as tall and on cells twice as wide; and smoothing along the line leaves a
     * straight line where it is.
     */
    @Test
    fun `on a sloping plane the ink is the pen at the true distance from the shoreline, at every bearing`() {
        val across = 96
        val down = 48
        var worstInk = 0f
        var worstShift = 0f
        for ((pixelsAcross, pixelsDown) in listOf(1 to 1, 2 to 1)) {
            val reach = CoastLine.reachPixels(pixelsAcross, pixelsDown)
            for (step in 0 until PLANE_BEARINGS) {
                val bearing = Math.PI * step / PLANE_BEARINGS + 0.01
                // The plane rises along the unit normal (normalX, normalY) on the sheet, through the
                // middle of the grid: land where it is above zero, a metre for each pixel.
                val normalX = cos(bearing)
                val normalY = sin(bearing)
                val middleX = across * pixelsAcross / 2.0
                val middleY = down * pixelsDown / 2.0
                fun height(column: Int, row: Int): Double =
                    ((column + 0.5) * pixelsAcross - middleX) * normalX + ((row + 0.5) * pixelsDown - middleY) * normalY
                val metres = FloatArray(across * down) { height(it % across, it / across).toFloat() }
                val banks = BooleanArray(metres.size) { metres[it] >= 0f }
                val coast = CoastLine(banks, metres, across, down)
                // Clear of the seam, where the plane is cut, and of the edges, where there is no block.
                for (row in 2 until down - 2) for (column in 4 until across - 4) {
                    val truth = abs(height(column, row)).toFloat()
                    val expected = CoastLine.inkAtDistance(min(truth, reach), reach)
                    worstInk = max(worstInk, abs(coast.inkAt(column, row, pixelsAcross, pixelsDown) - expected))
                }
                Shoreline.trace(banks, across, down, coast).forEach { line ->
                    val onTheSheet = FloatArray(line.size) { if (it % 2 == 0) line[it] * pixelsAcross else line[it] * pixelsDown }
                    val smoothed = Shoreline.smoothedAlongTheCurve(onTheSheet, Shoreline.SMOOTHING_SIGMA_CELLS * reach)
                    for (vertex in 0 until smoothed.size / 2) {
                        val off = (smoothed[vertex * 2] - middleX) * normalX + (smoothed[vertex * 2 + 1] - middleY) * normalY
                        worstShift = max(worstShift, abs(off.toFloat()))
                    }
                }
            }
        }
        println("COAST on a plane at $PLANE_BEARINGS bearings: the ink is out by $worstInk at worst; the smoothed line stands $worstShift pixels off the shoreline at worst")
        assertTrue(worstInk <= INK_AGREEMENT, "the ink on a plane is out by $worstInk from the pen at the true distance")
        assertTrue(worstShift <= 1e-3f, "smoothing moved a straight shoreline by $worstShift pixels")
    }

    // ---- on the standard worlds ----------------------------------------------------------------

    /**
     * Every cell's ink is the pen's at its distance on the sheet from the field's shoreline, so the
     * line the raster draws is centred on the ground's own waterline. The control, the raster's
     * old operator, inks whole land cells and so stands a whole half cell off the line on average.
     */
    @Test
    fun `the raster inks every cell by its distance from the field's own shoreline`() {
        for (seed in SEEDS) {
            val map = world(seed)
            val field = FieldShoreline(map)
            val coast = CoastLine.of(map)
            val sheet = SheetGeometry.of(map)
            val reach = CoastLine.reachPixels(sheet.pixelsPerCellAcross, sheet.pixelsPerCellDown)
            // The cells whose blocks a cell's ink reads: those within the reach on the sheet.
            val radius = ceil(reach / min(sheet.pixelsPerCellAcross, sheet.pixelsPerCellDown)).toInt()
            val oldInk = oldRasterInk(coast.banks, map.width, map.height)
            var inked = 0
            var checked = 0
            var worst = 0f
            var oldWorst = 0f
            for (cell in 0 until map.width * map.height) {
                val column = cell % map.width
                val row = cell / map.width
                val ink = coast.inkAt(column, row, sheet.pixelsPerCellAcross, sheet.pixelsPerCellDown)
                if (ink > 0f) inked++
                if (!field.agreesAround(column, row, radius)) continue
                val expected = CoastLine.inkAtDistance(min(field.distancePixels(column, row), reach), reach)
                if (ink > 0f) checked++
                worst = max(worst, abs(ink - expected))
                oldWorst = max(oldWorst, abs((if (oldInk[cell]) 1f else 0f) - expected))
            }
            println(
                "COAST $seed@${map.width}: $inked cells inked, $checked of them checked where the ground and the mask agree " +
                    "(${field.disagreeing} cells disagree); the ink is out by $worst at worst from the pen at the field's " +
                    "distance, the old operator by $oldWorst"
            )
            assertTrue(checked >= inked * LEAST_SHARE_CHECKED, "only $checked of $inked inked cells could be checked on $seed")
            assertTrue(worst <= INK_AGREEMENT, "seed $seed: a cell's ink is out by $worst from the pen at the field's distance")
            assertTrue(oldWorst > INK_AGREEMENT, "seed $seed: the old operator passes too, so the clause cannot tell them apart")
        }
    }

    /**
     * The overlay, traced, smoothed and drawn at full detail, keeps every vertex within the
     * smoothing's bound of the field's shoreline ([Shoreline.largestShiftOf], 0.40 of a cell). The
     * control is the trace the drawing stroked before, on the half-cell lattice, which stands as
     * much as half a cell off it wherever the ground puts the waterline near one of the two cells.
     */
    @Test
    fun `the drawn coast keeps within the smoothing's bound of the field's own shoreline`() {
        for (seed in SEEDS) {
            val map = world(seed)
            val field = FieldShoreline(map)
            val sheet = SheetGeometry.of(map)
            val bound = Shoreline.largestShiftOf(
                Shoreline.SMOOTHING_SIGMA_CELLS * CoastLine.reachPixels(sheet.pixelsPerCellAcross, sheet.pixelsPerCellDown)
            )
            val lines = Shoreline.of(map, MapSheet.UNGENERALISED)
            val vertices = lines.sumOf { it.size / 2 }
            val drawn = field.strays(lines, sheet)
            val old = field.strays(Shoreline.trace(NarrowSea.banks(map), map.width, map.height), sheet)
            println(
                "COAST $seed@${map.width}: ${drawn.size} of $vertices drawn vertices checked where the ground and the mask agree; " +
                    "the drawn coast strays ${drawn.describe()} pixels from the field's shoreline against a bound of %.3f; ".format(bound) +
                    "the old trace ${old.describe()}"
            )
            assertTrue(drawn.size >= vertices * LEAST_SHARE_CHECKED, "seed $seed: only ${drawn.size} of $vertices vertices could be checked")
            assertTrue(drawn.max() <= bound * BOUND_QUADRATURE_ALLOWANCE, "seed $seed: the drawn coast strays ${drawn.max()} pixels, past $bound")
            assertTrue(old.max() > bound, "seed $seed: the old trace keeps within the bound too, so the clause cannot tell them apart")
        }
    }

    /**
     * The census's facing clause, on the rendering and on its control: every shore the coast is
     * drawn round takes the ink alike whichever way it faces, where the old operator, reproduced
     * here, inked only those facing east and south.
     */
    @Test
    fun `the coast is inked alike whichever way a shore faces, where the old operator was not`() {
        for (seed in SEEDS) {
            val map = world(seed)
            val frame = GridFrame.of(map.config)
            val banks = NarrowSea.banks(map)
            val withCoast = MapRasterizer.rasterize(map, RenderOptions(view = MapView.FANTASY, showCoastline = true))
            val withoutCoast = MapRasterizer.rasterize(map, RenderOptions(view = MapView.FANTASY, showCoastline = false))
            val drawn = FacingShares.of(banks, BooleanArray(frame.cellCount) { withCoast[it] != withoutCoast[it] }, frame)
            val old = FacingShares.of(banks, oldRasterInk(banks, map.width, map.height), frame)
            println("COAST $seed@${map.width}: drawn ${drawn.describe()}; the old operator ${old.describe()}")
            assertEquals(Outcome.CLEAN, drawn.outcome(), "seed $seed: ${drawn.describe()}")
            assertEquals(Outcome.VIOLATION, old.outcome(), "seed $seed: the old operator's facing passes, so the control is not one")
        }
    }

    /**
     * How much past [Shoreline.largestShiftOf] the integrated mean may come: the midpoint rule at
     * eight pieces to a sigma is good to a part in a thousand, and this is ten of them.
     */
    private val BOUND_QUADRATURE_ALLOWANCE = 1.01f

    /**
     * The raster's coast before G2, reproduced: a bank cell inked whole where the cell east of it
     * or south of it is open water, and the southern edge taken as land.
     */
    private fun oldRasterInk(banks: BooleanArray, across: Int, down: Int): BooleanArray = BooleanArray(banks.size) { cell ->
        val column = cell % across
        val row = cell / across
        banks[cell] && (!banks[row * across + (column + 1) % across] || (row + 1 < down && !banks[(row + 1) * across + column]))
    }

    /** How far each of a set of vertices stands from the field's shoreline, in sheet pixels. */
    private class Strays(val distances: FloatArray) {
        val size: Int get() = distances.size
        fun max(): Float = distances.maxOrNull() ?: 0f
        fun describe(): String {
            val sorted = distances.sorted()
            fun at(share: Double) = if (sorted.isEmpty()) 0f else sorted[((sorted.size - 1) * share).roundToInt()]
            return "%.3f at the median, %.3f at the 99th percentile, %.3f at worst".format(at(0.5), at(0.99), max())
        }
    }

    /**
     * The ground's own shoreline on [map], traced by the census's tracer on the cells' altitudes in
     * metres over the blocks whose four cells agree with the mask the coast is drawn round, and
     * held as segments on the sheet, bucketed by cell for the distance from a point.
     */
    private inner class FieldShoreline(map: WorldMap) {
        private val across = map.width
        private val down = map.height
        private val sheet = SheetGeometry.of(map)
        private val agrees: BooleanArray
        private val buckets = HashMap<Int, MutableList<FloatArray>>()

        /** How many cells the mask and the ground disagree on. */
        val disagreeing: Int

        init {
            val scale = map.config.scale
            val relative = map.sea.relativeElevation.data
            val metres = FloatArray(relative.size) { cell ->
                if (map.sea.isLand[cell]) relative[cell] * scale.highestLandMetres else relative[cell] * scale.deepestOceanMetres
            }
            val banks = NarrowSea.banks(map)
            agrees = BooleanArray(metres.size) { banks[it] == (metres[it] >= 0f) }
            disagreeing = agrees.count { !it }
            val frame = GridFrame.of(map.config)
            for (outline in Contours.ofField(metres, 0f, frame, agrees)) {
                val (xCells, yCells) = outline.inCells(frame)
                val last = if (outline.closed) outline.vertexCount else outline.vertexCount - 1
                for (vertex in 0 until last) {
                    val next = (vertex + 1) % outline.vertexCount
                    val segment = floatArrayOf(
                        (xCells[vertex] * sheet.pixelsPerCellAcross).toFloat(), (yCells[vertex] * sheet.pixelsPerCellDown).toFloat(),
                        (xCells[next] * sheet.pixelsPerCellAcross).toFloat(), (yCells[next] * sheet.pixelsPerCellDown).toFloat()
                    )
                    val column = floor((xCells[vertex] + xCells[next]) / 2).toInt().mod(across)
                    val row = floor((yCells[vertex] + yCells[next]) / 2).toInt().coerceIn(0, down - 1)
                    buckets.getOrPut(row * across + column) { ArrayList() }.add(segment)
                }
            }
        }

        /** Whether every cell within [radius] cells of ([column], [row]) agrees with the mask. */
        fun agreesAround(column: Int, row: Int, radius: Int): Boolean {
            for (dy in -radius..radius) for (dx in -radius..radius) {
                val y = row + dy
                if (y < 0 || y >= down) continue
                if (!agrees[y * across + (column + dx).mod(across)]) return false
            }
            return true
        }

        /** The distance on the sheet from ([x], [y]) in sheet pixels to the nearest segment within a few cells. */
        fun distanceFrom(x: Float, y: Float): Float {
            val widthPixels = (across * sheet.pixelsPerCellAcross).toFloat()
            val column = floor(x / sheet.pixelsPerCellAcross).toInt()
            val row = floor(y / sheet.pixelsPerCellDown).toInt()
            var nearest = Float.POSITIVE_INFINITY
            for (dy in -SEARCH_CELLS..SEARCH_CELLS) for (dx in -SEARCH_CELLS..SEARCH_CELLS) {
                val bucketRow = row + dy
                if (bucketRow < 0 || bucketRow >= down) continue
                for (segment in buckets[bucketRow * across + (column + dx).mod(across)] ?: continue) {
                    // The segment as near the point as the seam allows.
                    val shift = widthPixels * ((x - (segment[0] + segment[2]) / 2) / widthPixels).roundToInt()
                    nearest = min(nearest, Shoreline.distanceToSegment(x, y, segment[0] + shift, segment[1], segment[2] + shift, segment[3]))
                }
            }
            return nearest
        }

        /** The distance on the sheet from cell ([column], [row])'s centre to the shoreline. */
        fun distancePixels(column: Int, row: Int): Float =
            distanceFrom((column + 0.5f) * sheet.pixelsPerCellAcross, (row + 0.5f) * sheet.pixelsPerCellDown)

        /**
         * How far each vertex of [lines], in cell coordinates, stands from the shoreline, over the
         * vertices whose neighbourhood agrees with the mask.
         */
        fun strays(lines: List<FloatArray>, sheet: SheetGeometry): Strays {
            val distances = ArrayList<Float>()
            for (line in lines) {
                var at = 0
                while (at < line.size) {
                    val column = floor(line[at]).toInt()
                    val row = floor(line[at + 1]).toInt()
                    if (row in 0 until down && agreesAround(column.mod(across), row, 2)) {
                        distances.add(distanceFrom(line[at] * sheet.pixelsPerCellAcross, line[at + 1] * sheet.pixelsPerCellDown))
                    }
                    at += 2
                }
            }
            return Strays(distances.toFloatArray())
        }
    }

    /** How many cells about a point its nearest segment is looked for in: past any reach the clauses ask. */
    private val SEARCH_CELLS = 3
}
