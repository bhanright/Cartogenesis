package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.math.GroundSteps
import com.cartogenesis.worldgen.pipeline.GroundCells
import com.cartogenesis.worldgen.pipeline.GroundClosure
import com.cartogenesis.worldgen.pipeline.HydraulicErosion
import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import org.junit.Assert.assertTrue

/**
 * The two-height closure against a resolved landscape of the same ground: how far the reduced
 * erosion's lowering departs from one whose every hillslope and channel is drawn, through time and
 * at every grid (docs/DESIGN_LEDGER.md, E1a).
 *
 * **The resolved landscape.** An 8 km square at 50 m cells: stream power (`K` 1e-6 a year, `m`
 * one half, `n` one) by the implicit update on every cell whose catchment reaches a head's
 * [SUPPORT_AREA_SQUARE_METRES], Roering, Kirchner and Dietrich's transport between every pair of
 * neighbours short of it, and slopes past the critical gradient failing to it. **The reduced
 * one.** The same ground on a coarse grid, its bed cut by the same law in the stage's count of
 * sub-steps and its ground closed by the stage's own `GroundCells.closeChannels`, the in-cell
 * geometry formed as `GroundCells.shape` forms it but with the head's support area held at the
 * resolved landscape's, since the comparison is of the closure and not of the head rule.
 *
 * **The same ground, the same footprint.** Each coarse grid has its own sea, a ring one coarse cell
 * wide; the resolved landscape is run once for each, with its sea on the same ring, from a dome
 * that rises from that shore, and the coarse grid starts from the block means of the resolved
 * landscape's first heights. So both lower the same ground into the same sea, and the lowering is
 * compared over the same land. A dome cut off by a sea ring it does not rise from stands a cliff
 * there, which the resolved landscape fails at once and a coarse cell cannot hold, and a footprint
 * that differs between the two compares different ground: each made an earlier reading of this
 * benchmark (a lowering of 262 m resolved against 421 m reduced at 2 km after 4 Myr) a comparison of
 * two different domes.
 *
 * Every clause that fails today is recorded under its finding with the figures it fails by.
 */
class GroundClosureBenchmarkTest {

    /**
     * The reduced closure's lowering over the resolved landscape's, each grid's own, at every
     * million years from the second to the sixteenth: within [FIDELITY] of one. The full curve is
     * printed.
     */
    @Test
    fun `the closure lowers the ground as a resolved landscape does, through time, at every grid`() {
        val over = ArrayList<String>()
        val curves = ArrayList<String>()
        for (coarseCells in COARSE_GRIDS) {
            val resolved = resolvedLowering(coarseCells)
            val reduced = reducedLowering(coarseCells)
            val points = ArrayList<String>()
            for (million in 1..TOTAL_MILLION_YEARS) {
                val ratio = reduced[million - 1] / resolved[million - 1]
                points += "%d: %.0f/%.0f".format(million, reduced[million - 1], resolved[million - 1])
                if (million >= FIRST_JUDGED_MILLION_YEARS && abs(ratio - 1.0) > FIDELITY) {
                    over += "%.0f m cells at %d Myr x%.2f".format(DOMAIN_METRES / coarseCells, million, ratio)
                }
            }
            val curve = "%.0f m cells, reduced/resolved m: %s".format(DOMAIN_METRES / coarseCells, points.joinToString(", "))
            println("BENCHMARK $curve")
            curves += curve
        }
        KnownFailures.expect(CLOSURE_DEPARTS, CLOSURE_RECORD) {
            if (over.isNotEmpty()) {
                throw RecordedViolation(
                    "the closure departs from the resolved landscape by more than $FIDELITY: ${curves.joinToString("; ")}",
                    over.joinToString("; ")
                )
            }
        }
    }

    // ================================================================== the ground

    /**
     * The dome's first heights at 50 m, in metres: 400 m over a smooth rise from the shore of the
     * coarse grid of [coarseCells], waves of 60 and 30 m that the coarser grids hold inside a
     * cell, and a metre of fixed roughness so the first routing has somewhere to go.
     */
    private fun dome(coarseCells: Int): DoubleArray {
        val shoreMetres = DOMAIN_METRES / coarseCells
        val field = DoubleArray(FINE_CELLS * FINE_CELLS)
        for (row in 0 until FINE_CELLS) for (column in 0 until FINE_CELLS) {
            val x = (column + 0.5) * FINE_CELL_METRES
            val y = (row + 0.5) * FINE_CELL_METRES
            val fromShore = min(min(x, DOMAIN_METRES - x), min(y, DOMAIN_METRES - y)) - shoreMetres
            val share = (fromShore / RISE_METRES).coerceIn(0.0, 1.0)
            val rise = share * share * (3 - 2 * share)
            var wave = WAVE_METRES * sin(2 * PI * x / WAVE_X_METRES + 0.7) * sin(2 * PI * y / WAVE_Y_METRES + 0.3)
            wave += SHORT_WAVE_METRES * sin(2 * PI * (x + y) / SHORT_WAVE_METRES_LONG + 1.1)
            val cell = row * FINE_CELLS + column
            val roughness = ((cell.toLong() * 2654435761L) % 1000L) / 1000.0
            field[cell] = (DOME_METRES + wave * WAVES + roughness) * rise
        }
        return field
    }

    /** The sea: the ring of coarse cells round the square, at the resolution of [cells] across. */
    private fun isSea(row: Int, column: Int, cells: Int, coarseCells: Int): Boolean {
        val factor = cells / coarseCells
        val coarseRow = row / factor
        val coarseColumn = column / factor
        return coarseRow == 0 || coarseColumn == 0 || coarseRow == coarseCells - 1 || coarseColumn == coarseCells - 1
    }

    // ================================================================== the resolved landscape

    /** Mean lowering of the land below its first heights and the uplift since, in metres, at each million years. */
    private fun resolvedLowering(coarseCells: Int): DoubleArray {
        val cells = FINE_CELLS
        val count = cells * cells
        val step = FINE_CELL_METRES
        val sea = BooleanArray(count) { isSea(it / cells, it % cells, cells, coarseCells) }
        val height = dome(coarseCells)
        for (cell in 0 until count) if (sea[cell]) height[cell] = 0.0
        val first = height.copyOf()
        val routing = Routing(cells, step)
        val isChannel = BooleanArray(count)
        val years = RESOLVED_STEP_YEARS
        val stepsPerMillion = (1e6 / years).toInt()
        val lowering = DoubleArray(TOTAL_MILLION_YEARS)
        var uplift = 0.0
        var channelOrder = IntArray(0)
        val flux = DoubleArray(count)
        for (stepIndex in 0 until stepsPerMillion * TOTAL_MILLION_YEARS) {
            for (cell in 0 until count) if (!sea[cell]) height[cell] += UPLIFT_METRES_PER_YEAR * years
            uplift += UPLIFT_METRES_PER_YEAR * years
            if (stepIndex % RESOLVED_REROUTE_STEPS == 0) {
                routing.route(height, sea)
                for (cell in 0 until count) isChannel[cell] = !sea[cell] && routing.area[cell] >= SUPPORT_AREA_SQUARE_METRES
                channelOrder = routing.order.filter { isChannel[it] }.toIntArray()
            }
            for (cell in channelOrder) {
                val receiver = routing.receiver[cell]
                if (receiver < 0) continue
                val base = height[receiver]
                if (height[cell] <= base) continue
                val courant = ERODIBILITY_PER_YEAR * years * sqrt(routing.area[cell]) / routing.stepMetres[cell]
                height[cell] = base + (height[cell] - base) / (1 + courant)
            }
            hillslopeTransport(height, sea, isChannel, flux, cells, step, years)
            failSteepSlopes(height, sea, cells, step)
            if ((stepIndex + 1) % stepsPerMillion == 0) {
                var sum = 0.0
                var land = 0
                for (cell in 0 until count) if (!sea[cell]) {
                    sum += first[cell] + uplift - height[cell]
                    land++
                }
                lowering[(stepIndex + 1) / stepsPerMillion - 1] = sum / land
            }
        }
        return lowering
    }

    /**
     * Roering transport `q = D S / (1 - (S / S_c)^2)` between every pair of row and column
     * neighbours, explicit, in as many sub-steps as keep it stable at the steepest link; channel
     * cells neither give nor take, the law alone setting their beds. The flux is capped short of
     * the critical gradient, where the law diverges, and [failSteepSlopes] takes what lies past it.
     */
    private fun hillslopeTransport(
        height: DoubleArray, sea: BooleanArray, isChannel: BooleanArray, change: DoubleArray,
        cells: Int, step: Double, years: Double
    ) {
        var steepest = 0.0
        for (row in 0 until cells) for (column in 0 until cells) {
            val cell = row * cells + column
            if (column + 1 < cells) steepest = max(steepest, abs(height[cell + 1] - height[cell]) / step)
            if (row + 1 < cells) steepest = max(steepest, abs(height[cell + cells] - height[cell]) / step)
        }
        val share = min(steepest / CRITICAL, FLUX_CAP_SHARE)
        val effective = DIFFUSIVITY / (1 - share * share)
        var subSteps = 1
        while (years / subSteps > STABLE_SHARE * step * step / effective) subSteps *= 2
        val dt = years / subSteps
        val capSquared = FLUX_CAP_SHARE * FLUX_CAP_SHARE
        repeat(subSteps) {
            change.fill(0.0)
            for (row in 0 until cells) for (column in 0 until cells) {
                val cell = row * cells + column
                if (column + 1 < cells) {
                    val gradient = (height[cell + 1] - height[cell]) / step
                    val flux = -DIFFUSIVITY * gradient / (1 - min(gradient * gradient / (CRITICAL * CRITICAL), capSquared))
                    change[cell] += flux / step
                    change[cell + 1] -= flux / step
                }
                if (row + 1 < cells) {
                    val gradient = (height[cell + cells] - height[cell]) / step
                    val flux = -DIFFUSIVITY * gradient / (1 - min(gradient * gradient / (CRITICAL * CRITICAL), capSquared))
                    change[cell] += flux / step
                    change[cell + cells] -= flux / step
                }
            }
            for (cell in height.indices) {
                if (sea[cell]) continue
                if (!isChannel[cell]) height[cell] -= dt * change[cell]
            }
        }
    }

    /**
     * Slopes past the critical gradient fail to it, conserving mass: every row and column link
     * steeper than `S_c` hands a quarter of its excess downhill, Jacobi, until none is past it by a
     * micrometre or [MAX_FAILURE_SWEEPS] have run.
     */
    private fun failSteepSlopes(height: DoubleArray, sea: BooleanArray, cells: Int, step: Double) {
        val limit = CRITICAL * step
        val delta = DoubleArray(height.size)
        repeat(MAX_FAILURE_SWEEPS) {
            delta.fill(0.0)
            var largest = 0.0
            for (row in 0 until cells) for (column in 0 until cells) {
                val cell = row * cells + column
                if (column + 1 < cells) {
                    val drop = height[cell + 1] - height[cell]
                    val excess = (abs(drop) - limit).coerceAtLeast(0.0) * FAILURE_SHARE * (if (drop < 0) -1.0 else 1.0)
                    delta[cell] += excess
                    delta[cell + 1] -= excess
                    largest = max(largest, abs(excess))
                }
                if (row + 1 < cells) {
                    val drop = height[cell + cells] - height[cell]
                    val excess = (abs(drop) - limit).coerceAtLeast(0.0) * FAILURE_SHARE * (if (drop < 0) -1.0 else 1.0)
                    delta[cell] += excess
                    delta[cell + cells] -= excess
                    largest = max(largest, abs(excess))
                }
            }
            if (largest < FAILURE_DONE_METRES) return
            for (cell in height.indices) height[cell] = if (sea[cell]) 0.0 else height[cell] + delta[cell]
        }
    }

    // ================================================================== the reduced landscape

    /** The same, on the coarse grid of [coarseCells] across, by the stage's own closure. */
    private fun reducedLowering(coarseCells: Int): DoubleArray {
        val cells = coarseCells
        val count = cells * cells
        val factor = FINE_CELLS / coarseCells
        val cellMetres = DOMAIN_METRES / coarseCells
        val cellArea = cellMetres * cellMetres
        val sea = BooleanArray(count) { isSea(it / cells, it % cells, cells, coarseCells) }
        val fine = dome(coarseCells)
        val first = DoubleArray(count)
        for (row in 0 until FINE_CELLS) for (column in 0 until FINE_CELLS) {
            first[(row / factor) * cells + column / factor] += fine[row * FINE_CELLS + column] / (factor * factor)
        }
        for (cell in 0 until count) if (sea[cell]) first[cell] = 0.0
        val ground = FloatArray(count) { first[it].toFloat() }
        val ground0 = ground.copyOf()
        val closure = GroundCells(count, ground)
        closure.bedShare.fill(0f)
        val years = TOTAL_MILLION_YEARS * 1e6 / REDUCED_ROUNDS
        val ruler = GroundCells.Ruler(cellMetres, cellArea, GroundSteps(1.0), 1.0, years, ERODIBILITY_PER_YEAR)
        val routing = Routing(cells, cellMetres)
        val ones = FloatArray(count) { 1f }
        val bedCut = DoubleArray(count)
        val production = DoubleArray(count)
        val headLength = sqrt(SUPPORT_AREA_SQUARE_METRES)
        val lowering = DoubleArray(TOTAL_MILLION_YEARS)
        val roundsPerMillion = REDUCED_ROUNDS / TOTAL_MILLION_YEARS
        var uplift = 0.0
        val bedHeights = DoubleArray(count)
        for (round in 0 until REDUCED_ROUNDS) {
            for (cell in 0 until count) if (!sea[cell]) {
                ground[cell] = (ground[cell] + UPLIFT_METRES_PER_YEAR * years).toFloat()
                closure.bed[cell] = (closure.bed[cell] + UPLIFT_METRES_PER_YEAR * years).toFloat()
            }
            uplift += UPLIFT_METRES_PER_YEAR * years
            for (cell in 0 until count) bedHeights[cell] = closure.bed[cell].toDouble()
            routing.route(bedHeights, sea)
            // The in-cell geometry, as `GroundCells.shape` forms it, with the head held.
            for (cell in 0 until count) {
                val channel = !sea[cell] && routing.area[cell] >= SUPPORT_AREA_SQUARE_METRES
                closure.isChannel[cell] = channel
                closure.isHillslope[cell] = false
                if (!channel) continue
                val stepMetres = routing.stepMetres[cell]
                val inflow = routing.area[cell] - cellArea
                val channelLength =
                    if (inflow >= SUPPORT_AREA_SQUARE_METRES) stepMetres
                    else stepMetres * (routing.area[cell] - SUPPORT_AREA_SQUARE_METRES) / (routing.area[cell] - inflow)
                val reach = min(cellArea / (2 * channelLength), cellMetres)
                closure.hillslopeLengthMetres[cell] = min(headLength, reach).toFloat()
                closure.networkFactor[cell] = GroundClosure.networkFactor(reach / headLength).toFloat()
            }
            closure.noteInterfluves(ground)
            bedCut.fill(0.0)
            production.fill(0.0)
            // The trunk, by the implicit update in the stage's count of sub-steps.
            var lowest = Double.POSITIVE_INFINITY
            var highest = 0.0
            for (cell in routing.order) {
                if (!closure.isChannel[cell] || routing.receiver[cell] < 0) continue
                val courant = ERODIBILITY_PER_YEAR * years * sqrt(routing.area[cell]) / routing.stepMetres[cell]
                lowest = min(lowest, courant)
                highest = max(highest, courant)
            }
            val subSteps = HydraulicErosion.subStepsFor(lowest, highest)
            repeat(subSteps) {
                for (cell in routing.order) {
                    val receiver = routing.receiver[cell]
                    if (!closure.isChannel[cell] || receiver < 0) continue
                    val base = closure.bed[receiver].toDouble()
                    val before = closure.bed[cell].toDouble()
                    if (before <= base) continue
                    val courant = ERODIBILITY_PER_YEAR * years / subSteps * sqrt(routing.area[cell]) / routing.stepMetres[cell]
                    val after = (base + (before - base) / (1 + courant)).toFloat()
                    bedCut[cell] += before - after.toDouble()
                    closure.bed[cell] = after
                }
            }
            closure.closeChannels(ground, bedCut, ones, ones, ruler, production)
            if ((round + 1) % roundsPerMillion == 0) {
                var sum = 0.0
                var land = 0
                for (cell in 0 until count) if (!sea[cell]) {
                    sum += ground0[cell] + uplift - ground[cell]
                    land++
                }
                lowering[(round + 1) / roundsPerMillion - 1] = sum / land
            }
        }
        return lowering
    }

    // ================================================================== routing

    /**
     * D8 over a priority-flood fill, the sea its outlet: each cell's receiver, the step to it and
     * its catchment, and the cells in an order that puts every receiver before its donors. Its own
     * and not the stage's, so the resolved landscape and the reduced one route alike.
     */
    private class Routing(private val cells: Int, private val cellMetres: Double) {
        val receiver = IntArray(cells * cells)
        val stepMetres = DoubleArray(cells * cells)
        val area = DoubleArray(cells * cells)
        var order = IntArray(0)
        private val filled = DoubleArray(cells * cells)

        fun route(height: DoubleArray, sea: BooleanArray) {
            val count = cells * cells
            val done = BooleanArray(count)
            val queue = PriorityQueue<Pair<Double, Int>>(compareBy({ it.first }, { it.second }))
            for (cell in 0 until count) {
                filled[cell] = height[cell]
                if (sea[cell]) {
                    done[cell] = true
                    queue.add(filled[cell] to cell)
                }
            }
            while (queue.isNotEmpty()) {
                val (level, cell) = queue.poll()
                forEachNeighbour(cell) { neighbour, _ ->
                    if (!done[neighbour]) {
                        done[neighbour] = true
                        if (filled[neighbour] <= level) filled[neighbour] = level + FILL_STEP_METRES
                        queue.add(filled[neighbour] to neighbour)
                    }
                }
            }
            val donors = IntArray(count)
            for (cell in 0 until count) {
                receiver[cell] = -1
                stepMetres[cell] = cellMetres
                if (sea[cell]) continue
                var best = 0.0
                forEachNeighbour(cell) { neighbour, length ->
                    val slope = (filled[cell] - filled[neighbour]) / length
                    if (slope > best) {
                        best = slope
                        receiver[cell] = neighbour
                        stepMetres[cell] = length
                    }
                }
                if (receiver[cell] >= 0) donors[receiver[cell]]++
            }
            // Receivers before donors: outlets first, then each cell once its receiver is placed.
            val start = IntArray(count + 1)
            for (cell in 0 until count) start[cell + 1] = start[cell] + donors[cell]
            val fillAt = start.copyOf()
            val donorList = IntArray(start[count])
            for (cell in 0 until count) if (receiver[cell] >= 0) donorList[fillAt[receiver[cell]]++] = cell
            val placed = ArrayList<Int>(count)
            val stack = ArrayDeque<Int>()
            for (cell in 0 until count) if (receiver[cell] < 0) stack.addLast(cell)
            while (stack.isNotEmpty()) {
                val cell = stack.removeLast()
                placed += cell
                for (index in start[cell] until start[cell + 1]) stack.addLast(donorList[index])
            }
            order = placed.toIntArray()
            for (cell in 0 until count) area[cell] = cellMetres * cellMetres
            for (index in order.indices.reversed()) {
                val cell = order[index]
                if (receiver[cell] >= 0) area[receiver[cell]] += area[cell]
            }
        }

        private inline fun forEachNeighbour(cell: Int, action: (Int, Double) -> Unit) {
            val row = cell / cells
            val column = cell % cells
            for (rowStep in -1..1) for (columnStep in -1..1) {
                if (rowStep == 0 && columnStep == 0) continue
                val r = row + rowStep
                val c = column + columnStep
                if (r < 0 || c < 0 || r >= cells || c >= cells) continue
                val length = if (rowStep != 0 && columnStep != 0) cellMetres * SQRT_TWO else cellMetres
                action(r * cells + c, length)
            }
        }
    }

    private companion object {
        /** The square's side, and the resolved landscape's cells across it and their width. */
        const val DOMAIN_METRES = 8_000.0
        const val FINE_CELLS = 160
        const val FINE_CELL_METRES = DOMAIN_METRES / FINE_CELLS

        /** The coarse grids: 2 km, 1 km, 500 m and 250 m cells. */
        val COARSE_GRIDS = listOf(4, 8, 16, 32)

        /** The dome: its height, the width of its rise from the shore, and its waves. */
        const val DOME_METRES = 400.0
        const val RISE_METRES = 3_000.0
        const val WAVE_METRES = 60.0
        const val WAVE_X_METRES = 5_300.0
        const val WAVE_Y_METRES = 3_700.0
        const val SHORT_WAVE_METRES = 30.0
        const val SHORT_WAVE_METRES_LONG = 2_300.0

        /** One where the dome carries its waves, nought for a smooth one. */
        const val WAVES = 1.0

        /** The laws: stream power, Roering's transport and the head's support area. */
        const val ERODIBILITY_PER_YEAR = 1e-6
        val DIFFUSIVITY = GroundClosure.HILLSLOPE_DIFFUSIVITY_M2_PER_YEAR
        val CRITICAL = GroundClosure.CRITICAL_HILLSLOPE_GRADIENT
        const val SUPPORT_AREA_SQUARE_METRES = 0.05e6

        /** A tenth of a millimetre a year of rock uplift, over sixteen million years. */
        const val UPLIFT_METRES_PER_YEAR = 1e-4
        const val TOTAL_MILLION_YEARS = 16

        /** The resolved landscape's step, and how often it is routed afresh. */
        const val RESOLVED_STEP_YEARS = 5_000.0
        const val RESOLVED_REROUTE_STEPS = 5

        /** The reduced landscape's rounds: a third of a million years each, about the stage's. */
        const val REDUCED_ROUNDS = 48

        /**
         * The transport's flux is capped at this share of the critical gradient, where the law's
         * denominator would vanish; an explicit step is stable under [STABLE_SHARE] of `dx^2 / D`.
         */
        const val FLUX_CAP_SHARE = 0.98
        const val STABLE_SHARE = 0.2

        /** Failure's sweeps: a quarter of each link's excess a sweep, done at a micrometre. */
        const val FAILURE_SHARE = 0.25
        const val FAILURE_DONE_METRES = 1e-6
        const val MAX_FAILURE_SWEEPS = 200

        /** The fill's rise across a flat, so every filled cell has somewhere lower to drain to. */
        const val FILL_STEP_METRES = 1e-6
        val SQRT_TWO = sqrt(2.0)

        /**
         * From the second million years: the first is the few metres the dome loses before its
         * channels have cut in, where a ratio reads the cut's first rounds and not the closure.
         */
        const val FIRST_JUDGED_MILLION_YEARS = 2

        /** The ground's bar across grids: the closure's departure on any one grid must be inside it. */
        const val FIDELITY = 0.05

        const val CLOSURE_DEPARTS = "E1a: the closure departs from a resolved landscape"
        const val CLOSURE_RECORD = ""
    }
}
