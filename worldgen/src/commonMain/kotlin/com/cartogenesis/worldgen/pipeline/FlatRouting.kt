package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.model.FloatField
import kotlin.math.sqrt

/**
 * The surface water follows across ground the depression fill raised.
 *
 * [FlowRouting.fillDepressions] lifts every cell of a hollow to its spill and then one
 * [FlowRouting.FLAT_GRADIENT_STEP] higher per cell of flood, so that a filled flat still slopes
 * back toward the cell the flood entered it from. That staircase is the flood's own expansion
 * order: its level sets are the shells of the grid's chessboard distance from the entry, so a cell
 * on the entry's diagonal has exactly one lower neighbour and a course crossing the flat is drawn
 * with a ruler at one of eight bearings, and neither the facet rule nor the sub-grid draw in
 * [FlowRouting.flowDirections] has a slope to work against. See `docs/DESIGN_LEDGER.md`, F30b, and
 * `GEOGRAPHY.md`, "A river crossing smooth ground does not run in a ruled line".
 *
 * A filled flat is, physically, a sheet of water or a floodplain, and water spread over a flat
 * sheet draining to one outlet follows a potential that satisfies Poisson's equation: rain falling
 * evenly on the flat is the source, the entry the flood came through is the sink, and the rim the
 * flat cannot cross lets nothing through. Its level sets are smooth curves closing on the entry, so
 * the facet rule sees a bearing that turns as the course goes and the draw has a share to spend.
 * Solved here per flat, in double precision, and laid into a band one flat-gradient step high just
 * above the entry, so every rim cell that drained into the flat still stands above all of it and
 * the entry still stands below: the network stays a forest and nothing downstream has to know.
 *
 * The staircase itself is kept on the filled field for everything that reads it as a level — the
 * outlet walk in [HydraulicErosion] tells a real gradient from the fill's by that step, and the
 * lakes read their depth off it. Only the routing sees the potential.
 */
internal object FlatRouting {

    /**
     * The routing surface for [FlowRouting.flowDirections]: the filled field, except across raised
     * flats, where it is the potential above.
     *
     * Land cells the fill left alone keep their filled value, and ocean cells their true elevation,
     * which is what the routing has always read for them. A flat whose potential cannot be laid
     * without leaving one of its cells with no lower neighbour — which a converged solve cannot do,
     * and a solve cut short by [MOST_ITERATIONS] might — keeps the staircase, and
     * [Surface.flatsKept] counts how many did.
     */
    class Surface(val heights: DoubleArray, val flats: Int, val flatsKept: Int, val raisedCells: Int)

    fun surfaceOf(
        width: Int,
        height: Int,
        isLand: BooleanArray,
        elevation: FloatField,
        filled: FloatField,
        seed: Long,
        cellHeightInCellWidths: Double
    ): Surface {
        val cellCount = width * height
        val ground = elevation.data
        val lifted = filled.data
        val surface = DoubleArray(cellCount) {
            (if (isLand[it]) lifted[it] else ground[it]).toDouble()
        }
        val raised = BooleanArray(cellCount) { isLand[it] && lifted[it] > ground[it] }

        val localIndex = IntArray(cellCount) { -1 }
        var stack = IntArray(1024)
        var members = IntArray(1024)
        var flats = 0
        var flatsKept = 0
        var raisedCells = 0

        for (start in 0 until cellCount) {
            if (!raised[start] || localIndex[start] >= 0) continue

            // One flat: every raised cell reachable from this one through raised cells.
            var memberCount = 0
            var stackTop = 0
            stack[stackTop++] = start
            localIndex[start] = 0
            members[0] = start
            memberCount = 1
            var lowestLevel = lifted[start]
            while (stackTop > 0) {
                val cell = stack[--stackTop]
                if (lifted[cell] < lowestLevel) lowestLevel = lifted[cell]
                FlowRouting.forEachNeighbour(width, height, cell % width, cell / width) { neighbour ->
                    if (raised[neighbour] && localIndex[neighbour] < 0) {
                        if (memberCount == members.size) members = members.copyOf(memberCount * 2)
                        if (stackTop == stack.size) stack = stack.copyOf(stack.size * 2)
                        localIndex[neighbour] = memberCount
                        members[memberCount++] = neighbour
                        stack[stackTop++] = neighbour
                    }
                }
            }
            flats++
            raisedCells += memberCount

            // The entry: the cell the flood raised this flat's lowest cell from, one step below it,
            // and every other unraised neighbour standing that low. Such a cell always exists,
            // because the lowest cell of a flat was raised from something lower that was not raised
            // itself — had it been, it would be in this flat and lower still.
            val entryLevel = lowestLevel.toDouble()
            val potential = solvePotential(
                width, height, members, memberCount, localIndex, surface, entryLevel, seed, cellHeightInCellWidths
            )

            if (potential != null && layInBand(width, height, members, memberCount, localIndex, surface, potential, entryLevel)) {
                // laid
            } else {
                flatsKept++
            }
            // The local index is per flat; clear it so the next flat's neighbours are not mistaken
            // for this one's. Raised cells of this flat stay marked as visited through `raised`.
            for (member in 0 until memberCount) {
                raised[members[member]] = false
                localIndex[members[member]] = LAID
            }
        }
        return Surface(surface, flats, flatsKept, raisedCells)
    }

    /** Marks a raised cell whose flat has been handled, so the component walk does not revisit it. */
    private const val LAID = Int.MAX_VALUE

    /** The Laplacian's weight on a neighbour east or west: the unit the other two are set against. */
    private const val EAST_WEST_WEIGHT = 1.0

    /**
     * The Laplacian's weight on a diagonal neighbour, `2 / (1 + r^2)` for a row scale `r`: one on
     * square cells, so the stencil there is the unit one it replaced, and less as the cells flatten,
     * in step with the diagonal's length on the ground. Any positive figure would make the operator
     * isotropic once the north-south weight is set against it; this one keeps all three positive on
     * any cell no taller than it is wide, which is every cell a square grid of this 2:1 world has.
     */
    private fun diagonalWeight(cellHeightInCellWidths: Double): Double =
        2.0 / (1.0 + cellHeightInCellWidths * cellHeightInCellWidths)

    /**
     * Poisson's equation over one flat: every member gathers a unit of rain, the entry holds it
     * at nothing, the rim lets nothing through. Conjugate gradients on the eight-neighbour
     * Laplacian, which is symmetric and positive definite as soon as one entry cell exists.
     *
     * The Laplacian is the ground's. On cells [cellHeightInCellWidths] as tall as they are wide, an
     * eight-neighbour stencil with every weight one is the operator `3 (w^2 d2/dx2 + h^2 d2/dy2)`
     * on the ground, four times as conductive east-west as north-south, and the level lines it
     * draws round an outlet are ellipses twice as long east-west. The weights here make it
     * isotropic: a diagonal neighbour weighs [diagonalWeight], one east or west one, and one north
     * or south whatever makes the two axes' second derivatives carry the same coefficient,
     * `(1 + 2 d) / r^2 - 2 d` for a diagonal weight `d` and a row scale `r`. All three are positive,
     * which keeps the discrete maximum principle the guarantee below rests on, and on square cells
     * they are all one, the stencil this replaced.
     *
     * The price of an isotropic ground is an anisotropic matrix: on this map's cells a member is
     * held to the members above and below it 13.6 times as hard as to the ones beside it, and plain
     * conjugate gradients pay for that in steps: seed 7's largest flat at 512, 1,283 cells, took
     * 350 of them against the square stencil's 270. So the steps are preconditioned by solving each
     * column's run of members exactly, its own tridiagonal share of the matrix, which takes the
     * stiff direction out whole and leaves the solve converging at the pace of the soft one: the
     * same flat takes 148. The block is symmetric and positive definite, being a principal part of
     * a matrix that is, so the steps are still conjugate gradients and the answer is the same one
     * to the tolerance; see [ColumnRuns].
     *
     * Returns null where the solve produced a non-positive value, which a converged solve cannot
     * (the discrete maximum principle puts every member strictly above the average of its
     * neighbours and so strictly above the entry) and which therefore means it was cut short.
     */
    private fun solvePotential(
        width: Int,
        height: Int,
        members: IntArray,
        memberCount: Int,
        localIndex: IntArray,
        surface: DoubleArray,
        entryLevel: Double,
        seed: Long,
        cellHeightInCellWidths: Double
    ): DoubleArray? {
        val diagonal = diagonalWeight(cellHeightInCellWidths)
        val rowScaleSquared = cellHeightInCellWidths * cellHeightInCellWidths
        val northSouth = (1.0 + 2.0 * diagonal) / rowScaleSquared - 2.0 * diagonal

        // The stencil per member: its weighted degree over members and entries, and its member
        // neighbours with their weights.
        val degree = DoubleArray(memberCount)
        val neighbourStart = IntArray(memberCount + 1)
        val neighbours = IntArray(memberCount * 8)
        val weights = DoubleArray(memberCount * 8)
        var written = 0
        for (member in 0 until memberCount) {
            val cell = members[member]
            val column = cell % width
            val row = cell / width
            neighbourStart[member] = written
            var total = 0.0
            for (rowStep in -1..1) {
                val neighbourRow = row + rowStep
                if (neighbourRow < 0 || neighbourRow >= height) continue
                for (columnStep in -1..1) {
                    if (columnStep == 0 && rowStep == 0) continue
                    var neighbourColumn = (column + columnStep) % width
                    if (neighbourColumn < 0) neighbourColumn += width
                    val neighbour = neighbourRow * width + neighbourColumn
                    val weight = when {
                        columnStep != 0 && rowStep != 0 -> diagonal
                        rowStep != 0 -> northSouth
                        else -> EAST_WEST_WEIGHT
                    }
                    val local = localIndex[neighbour]
                    if (local in 0 until memberCount && local != LAID) {
                        neighbours[written] = local
                        weights[written] = weight
                        written++
                        total += weight
                    } else if (local < 0 && surface[neighbour] < entryLevel) {
                        // An entry: it takes water and holds the potential at nothing.
                        total += weight
                    }
                }
            }
            degree[member] = total
        }
        neighbourStart[memberCount] = written

        fun applyLaplacian(x: DoubleArray, into: DoubleArray) {
            for (member in 0 until memberCount) {
                var sum = degree[member] * x[member]
                for (slot in neighbourStart[member] until neighbourStart[member + 1]) {
                    sum -= weights[slot] * x[neighbours[slot]]
                }
                into[member] = sum
            }
        }

        // The rain: a unit on every cell, varied by the relief a grid this coarse cannot hold. A
        // sheet fed evenly and draining to a straight edge flows in straight parallel lines, which
        // is a ruler again; fed unevenly on the scale of a few cells its flow lines bend around the
        // wetter patches. The variation stays inside the unit, so the source is positive everywhere
        // and the potential keeps its guarantee of a lower neighbour at every cell.
        val x = DoubleArray(memberCount)
        val residual = DoubleArray(memberCount) {
            val cell = members[it]
            1.0 + RAIN_RELIEF * FlowRouting.smoothSeededField(width, cell % width, cell / width, seed xor RAIN_RELIEF_SALT)
        }
        val runs = ColumnRuns(width, members, memberCount, localIndex, degree, northSouth)
        val preconditioned = DoubleArray(memberCount)
        runs.solve(residual, preconditioned)
        val direction = preconditioned.copyOf()
        val product = DoubleArray(memberCount)
        var residualNorm = 0.0
        var residualAgainstPreconditioned = 0.0
        for (member in 0 until memberCount) {
            residualNorm += residual[member] * residual[member]
            residualAgainstPreconditioned += residual[member] * preconditioned[member]
        }
        val stopAt = residualNorm * RESIDUAL_TOLERANCE * RESIDUAL_TOLERANCE
        val mostIterations = MOST_ITERATIONS_FLOOR + (MOST_ITERATIONS_PER_ROOT_CELL * sqrt(memberCount.toDouble())).toInt()

        var iteration = 0
        while (residualNorm > stopAt && iteration < mostIterations) {
            applyLaplacian(direction, product)
            var directionEnergy = 0.0
            for (member in 0 until memberCount) directionEnergy += direction[member] * product[member]
            if (directionEnergy <= 0.0 || residualAgainstPreconditioned <= 0.0) break
            val stepLength = residualAgainstPreconditioned / directionEnergy
            var nextNorm = 0.0
            for (member in 0 until memberCount) {
                x[member] += stepLength * direction[member]
                residual[member] -= stepLength * product[member]
                nextNorm += residual[member] * residual[member]
            }
            runs.solve(residual, preconditioned)
            var nextAgainstPreconditioned = 0.0
            for (member in 0 until memberCount) nextAgainstPreconditioned += residual[member] * preconditioned[member]
            val improvement = nextAgainstPreconditioned / residualAgainstPreconditioned
            for (member in 0 until memberCount) {
                direction[member] = preconditioned[member] + improvement * direction[member]
            }
            residualNorm = nextNorm
            residualAgainstPreconditioned = nextAgainstPreconditioned
            iteration++
        }

        for (member in 0 until memberCount) if (!(x[member] > 0.0)) return null
        return x
    }

    /**
     * The preconditioner of [solvePotential]: the flat's matrix with only the north-south couplings
     * inside each column kept, solved exactly.
     *
     * A run is a column's members one under the next, unbroken; each is a tridiagonal system whose
     * diagonal is the members' own weighted degrees and whose off-diagonal is the north-south
     * weight, factorised once here by Thomas's elimination and solved once a step. The pivots are
     * positive because the block is a principal part of a positive definite matrix, and the
     * elimination is stable on it because every row of it is diagonally dominant: a member's degree
     * is the sum of all its weights, of which the block holds at most two.
     */
    private class ColumnRuns(
        width: Int,
        members: IntArray,
        memberCount: Int,
        localIndex: IntArray,
        degree: DoubleArray,
        private val northSouth: Double
    ) {
        /** The members in run order, each run from its northernmost member down. */
        private val order = IntArray(memberCount)

        /** Where each run starts in [order], and one past the last. */
        private val runStart: IntArray

        /** Thomas's modified diagonal, per position in [order]. */
        private val pivot = DoubleArray(memberCount)

        init {
            fun memberAt(cell: Int): Int {
                if (cell < 0 || cell >= localIndex.size) return -1
                val local = localIndex[cell]
                return if (local in 0 until memberCount) local else -1
            }
            val starts = ArrayList<Int>()
            var placed = 0
            for (member in 0 until memberCount) {
                // A run starts at a member with no member directly north of it; a column does not
                // wrap north-south, so a member in the top row always starts one.
                if (memberAt(members[member] - width) >= 0) continue
                starts.add(placed)
                var here = member
                while (here >= 0) {
                    order[placed++] = here
                    here = memberAt(members[here] + width)
                }
            }
            starts.add(placed)
            runStart = starts.toIntArray()
            for (run in 0 until runStart.size - 1) {
                for (position in runStart[run] until runStart[run + 1]) {
                    val diagonal = degree[order[position]]
                    pivot[position] =
                        if (position == runStart[run]) diagonal
                        else diagonal - northSouth * northSouth / pivot[position - 1]
                }
            }
        }

        /** Writes the block's answer to [right] into [into]. */
        fun solve(right: DoubleArray, into: DoubleArray) {
            for (run in 0 until runStart.size - 1) {
                val first = runStart[run]
                val last = runStart[run + 1] - 1
                // Down the column: each member's equation with the one above it eliminated.
                for (position in first..last) {
                    val carried = if (position == first) 0.0 else northSouth * into[order[position - 1]]
                    into[order[position]] = (right[order[position]] + carried) / pivot[position]
                }
                // Back up it: each member's answer with the one below it put back.
                for (position in last - 1 downTo first) {
                    into[order[position]] += northSouth / pivot[position] * into[order[position + 1]]
                }
            }
        }
    }

    /**
     * Lays the potential into the band between the entry and one flat-gradient step above it,
     * highest potential highest, and confirms every member still has a strictly lower neighbour.
     * Returns false, leaving the staircase in place, where one does not.
     */
    private fun layInBand(
        width: Int,
        height: Int,
        members: IntArray,
        memberCount: Int,
        localIndex: IntArray,
        surface: DoubleArray,
        potential: DoubleArray,
        entryLevel: Double
    ): Boolean {
        var highest = 0.0
        for (member in 0 until memberCount) if (potential[member] > highest) highest = potential[member]
        if (highest <= 0.0) return false

        val staircase = DoubleArray(memberCount) { surface[members[it]] }
        val bandFloor = entryLevel - FlowRouting.FLAT_GRADIENT_STEP
        val bandHeight = FlowRouting.FLAT_GRADIENT_STEP * BAND_SHARE_OF_A_STEP
        for (member in 0 until memberCount) {
            surface[members[member]] = bandFloor + bandHeight * (potential[member] / highest)
        }

        for (member in 0 until memberCount) {
            val cell = members[member]
            val here = surface[cell]
            var hasLower = false
            FlowRouting.forEachNeighbour(width, height, cell % width, cell / width) { neighbour ->
                if (!hasLower && surface[neighbour] < here) hasLower = true
            }
            if (!hasLower) {
                for (undo in 0 until memberCount) surface[members[undo]] = staircase[undo]
                return false
            }
        }
        return true
    }

    /**
     * How far the solve is taken: to a residual this small relative to the rain put in, or to a
     * number of steps that grows with the flat's width. Conjugate gradients on a flat this shape
     * converge in about as many steps as the flat is cells across, and the square root of the
     * member count is that width for a round flat and an underestimate for a long one; the
     * multiplier covers the long ones and the floor the small ones.
     */
    private const val RESIDUAL_TOLERANCE = 1e-7

    /**
     * How far the rain over a flat departs from even, as a share of the unit: half, so the driest
     * patch takes half the wettest's and the source never reaches zero. The field is
     * [FlowRouting.smoothSeededField], eight cells to a period, salted so the flats' rain and the
     * routing's sub-grid draw are independent of each other.
     */
    private const val RAIN_RELIEF = 0.5
    private const val RAIN_RELIEF_SALT = 0x3c6ef372_fe94f82bL
    private const val MOST_ITERATIONS_FLOOR = 64
    private const val MOST_ITERATIONS_PER_ROOT_CELL = 8.0

    /**
     * How much of one flat-gradient step the band takes. Less than the whole of it, so the highest
     * cell of the flat stands strictly below any rim cell that was raised from it, which the
     * staircase put exactly one step higher.
     */
    private const val BAND_SHARE_OF_A_STEP = 0.9
}
