package com.cartogenesis.worldgen.pipeline

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The atmosphere and the rain, solved together: the latent heat of each lap's condensation heats
 * the atmosphere, the atmosphere's wind and vertical motion carry and lift the next lap's water.
 *
 * **The loop.** The march's first lap runs under the dry atmosphere. After each lap the half-years'
 * net condensation is carried down to the atmosphere's grid ([AtmosphereRemap.forcing], which keeps
 * its area integral), times the latent heat it releases, `L_v`; that is the heating `g(Q)` the lap's
 * water asks for, when the lap ran under the heating `Q`. The atmosphere is solved under the next
 * heating ([BoundaryLayer.Solver], every wave factored once for the whole loop and back-substituted
 * twice a lap), and its seasons are handed to the next lap ([seasonsOf]).
 *
 * **Settled** when the heating the last lap ran under stands within [MoistureMarch.CONVERGED_SHARE]
 * of the heating that lap's condensation asks for, `|g(Q) - Q|` over `|g(Q)|`: the fixed point's
 * own residual, not a relaxed step's change, which a small step would shrink whatever the loop did
 * (the second reader's point 6). Only the departures from each row's mean are read, the part the
 * stationary waves are forced by; the zonal mean's heating is the energy balance's.
 *
 * **The step.** The plain iteration `Q <- g(Q)` steps by the loop's own feedback along each of its
 * eigenvalues `mu`, and relaxing it by `theta` steps by `1 - theta + theta mu`, which shrinks a step
 * only while every `mu` stands below one and cannot stabilize one above it. The loop's slowest
 * feedback was measured at 0.93 to 0.95 a lap (docs/DESIGN_LEDGER.md, A1-5): below one, so it
 * converges, but over fifty laps to the bound. So the step is Anderson's (1965) acceleration as
 * Walker and Ni (2011, read) state it, their Algorithm AA in the unconstrained form of their section
 * 3 with [ACCELERATION_DEPTH] earlier laps: the next heating is the combination of the last laps'
 * whose residuals cancel best, by least squares, which on a linear loop is GMRES on its residual
 * (their section 2), so a feedback near one is taken in a few laps and not dozens. [relaxation]
 * is their mixing `beta`, one by default as in all their experiments; a depth of zero is the plain
 * relaxed iteration, the control the loop's tests hold it against.
 */
internal class AtmosphereCoupling(
    solver: BoundaryLayer.Solver,
    /** The two half-years' seasons the march reads under a solved atmosphere. */
    private val seasonsOf: (BoundaryLayer.Atmosphere) -> Pair<MoistureMarch.Season, MoistureMarch.Season>,
    /** Walker and Ni's mixing `beta`: the share of the residual each lap's heating moves by. */
    val relaxation: Double = RELAXATION,
    /** How many earlier laps the acceleration combines; zero for the plain relaxed iteration. */
    val depth: Int = ACCELERATION_DEPTH,
    /**
     * The heating the first lap runs under, each half's on the atmosphere's grid in watts per square
     * meter; none, the dry atmosphere, by default. Only the test of the loop's sensitivity to its
     * start hands one in.
     */
    initialLatentWPerM2: Pair<DoubleArray, DoubleArray>? = null
) : MoistureMarch.Coupling {

    private var solver: BoundaryLayer.Solver? = solver
    private val remap = solver.remap
    private val coarse = remap.coarse
    private val cellCount = coarse.cellCount
    private val widthInRows = WaveForcing.groundWidthInRows(remap)

    /** The heating the current atmosphere was solved under, both halves, July's cells then January's. */
    private var given: DoubleArray = initialLatentWPerM2?.let { (july, january) -> july + january } ?: DoubleArray(2 * cellCount)

    /** The heating the last lap's condensation asks for, both halves as [given]. */
    private var target: DoubleArray? = null

    /** The atmosphere the last lap ran under. */
    var atmosphere: BoundaryLayer.Atmosphere = solver.solve(initialLatentWPerM2?.first, initialLatentWPerM2?.second)
        private set

    /** The heating the current atmosphere was solved under, each half's, watts per square meter. */
    val julyLatentWPerM2: DoubleArray get() = given.copyOfRange(0, cellCount)
    val januaryLatentWPerM2: DoubleArray get() = given.copyOfRange(cellCount, 2 * cellCount)

    /** The heating the last lap's condensation asks for, each half's, watts per square meter. */
    val julyTargetWPerM2: DoubleArray? get() = target?.copyOfRange(0, cellCount)
    val januaryTargetWPerM2: DoubleArray? get() = target?.copyOfRange(cellCount, 2 * cellCount)

    /** The unrelaxed residual after each lap, as a share of the asked-for heating's departures. */
    val residuals = mutableListOf<Double>()

    /**
     * How far the heating each lap asks for moved from the last lap's, as the same share: the
     * march's own change, which a frozen atmosphere (no step) leaves as the loop's floor.
     */
    val targetChanges = mutableListOf<Double>()

    /** The earlier laps' heatings given and asked for, oldest first, for the acceleration. */
    private val givenHistory = ArrayDeque<DoubleArray>()
    private val targetHistory = ArrayDeque<DoubleArray>()

    /** Each cell's weight in the residual's norm: the square root of its area, on both halves. */
    private val weight = DoubleArray(2 * cellCount) { sqrt(coarse.cellAreaSquareMeters[(it % cellCount) / coarse.columns]) }

    /** The two seasons the first lap runs under. */
    fun firstSeasons(): Pair<MoistureMarch.Season, MoistureMarch.Season> = seasonsOf(atmosphere)

    override fun settledAfter(julyHalfCondensationMm: FloatArray, januaryHalfCondensationMm: FloatArray): Boolean {
        val asked = latentHeatingOf(julyHalfCondensationMm) + latentHeatingOf(januaryHalfCondensationMm)
        target?.let { previous -> targetChanges.add(eddyShare(asked, previous)) }
        target = asked
        val residual = eddyShare(asked, given)
        residuals.add(residual)
        return residual < MoistureMarch.CONVERGED_SHARE
    }

    override fun nextSeasons(): Pair<MoistureMarch.Season, MoistureMarch.Season> {
        val current = checkNotNull(solver) { "the coupling has been released" }
        val asked = checkNotNull(target) { "no lap has been read" }
        given = nextHeating(given, asked)
        atmosphere = current.solve(julyLatentWPerM2, januaryLatentWPerM2)
        return seasonsOf(atmosphere)
    }

    /** Lets the factored waves go once the march is done; the atmosphere and the history are kept. */
    fun release() {
        solver = null
    }

    /**
     * The heating the next lap runs under, from the last lap's [lastGiven] and the [lastAsked] its
     * condensation asks for: Walker and Ni's step `x + beta f - (dX + beta dF) gamma`, with `f` the
     * residual `g(x) - x` and `gamma` the least-squares fit of the residual's lap-to-lap changes
     * `dF` to it; the plain relaxed step at a depth of zero or on the first lap.
     */
    private fun nextHeating(lastGiven: DoubleArray, lastAsked: DoubleArray): DoubleArray {
        givenHistory.addLast(lastGiven)
        targetHistory.addLast(lastAsked)
        while (givenHistory.size > depth + 1) {
            givenHistory.removeFirst()
            targetHistory.removeFirst()
        }
        val size = lastGiven.size
        val residual = DoubleArray(size) { lastAsked[it] - lastGiven[it] }
        val columns = givenHistory.size - 1
        val gamma = if (columns > 0) {
            // dF and dX: each column the change from one lap to the next.
            val changeOfResidual = Array(columns) { column ->
                DoubleArray(size) { cell ->
                    (targetHistory[column + 1][cell] - givenHistory[column + 1][cell]) -
                        (targetHistory[column][cell] - givenHistory[column][cell])
                }
            }
            leastSquares(changeOfResidual, residual)
        } else DoubleArray(0)
        return DoubleArray(size) { cell ->
            var next = lastGiven[cell] + relaxation * residual[cell]
            for (column in gamma.indices) {
                val changeGiven = givenHistory[column + 1][cell] - givenHistory[column][cell]
                val changeResidual = (targetHistory[column + 1][cell] - givenHistory[column + 1][cell]) -
                    (targetHistory[column][cell] - givenHistory[column][cell])
                next -= gamma[column] * (changeGiven + relaxation * changeResidual)
            }
            next
        }
    }

    /**
     * The coefficients `gamma` minimizing `|f - A gamma|` in the residual's norm (each departure
     * from its row's mean, weighted by area), [columns] the columns of `A`: by Householder's QR
     * as Walker and Ni solve it, through modified Gram-Schmidt here, a column whose part independent
     * of the ones before it is under [INDEPENDENT_SHARE] of its size dropped, which keeps the
     * problem's conditioning as their section 4 asks.
     */
    private fun leastSquares(columns: Array<DoubleArray>, residual: DoubleArray): DoubleArray {
        val weighted = columns.map { weightedEddies(it) }
        val right = weightedEddies(residual)
        val kept = mutableListOf<Int>()
        val basis = mutableListOf<DoubleArray>()
        val upper = Array(weighted.size) { DoubleArray(weighted.size) }
        for (column in weighted.indices) {
            val vector = weighted[column].copyOf()
            val size = norm(vector)
            for ((index, previous) in basis.withIndex()) {
                val projection = dot(previous, vector)
                upper[index][kept.size] = projection
                for (cell in vector.indices) vector[cell] -= projection * previous[cell]
            }
            val independent = norm(vector)
            if (size == 0.0 || independent < INDEPENDENT_SHARE * size) continue
            for (cell in vector.indices) vector[cell] /= independent
            upper[basis.size][kept.size] = independent
            basis.add(vector)
            kept.add(column)
        }
        val rank = basis.size
        val projected = DoubleArray(rank) { dot(basis[it], right) }
        val solved = DoubleArray(rank)
        for (row in rank - 1 downTo 0) {
            var sum = projected[row]
            for (other in row + 1 until rank) sum -= upper[row][other] * solved[other]
            solved[row] = sum / upper[row][row]
        }
        val gamma = DoubleArray(columns.size)
        for ((index, column) in kept.withIndex()) gamma[column] = solved[index]
        return gamma
    }

    private fun weightedEddies(field: DoubleArray): DoubleArray {
        val result = eddies(field)
        for (cell in result.indices) result[cell] *= weight[cell]
        return result
    }

    private fun dot(first: DoubleArray, second: DoubleArray): Double {
        var sum = 0.0
        for (cell in first.indices) sum += first[cell] * second[cell]
        return sum
    }

    private fun norm(vector: DoubleArray): Double = sqrt(dot(vector, vector))

    /**
     * The latent heat of [condensationMm] (millimeters a year of water, row-major on the map) on the
     * atmosphere's grid, watts per square meter: carried down as a forcing is, so its area integral
     * is `L_v` times the map's.
     */
    fun latentHeatingOf(condensationMm: FloatArray): DoubleArray {
        val perMm = ColumnWater.LATENT_HEAT_J_PER_KG / MoistureMarch.SECONDS_PER_YEAR
        val ground = FloatArray(condensationMm.size) { (condensationMm[it] * perMm).toFloat() }
        return remap.forcing(ground, widthInRows)
    }

    /**
     * How far [other] stands from [reference], both halves together: the area integral of the
     * absolute difference of their departures from each row's mean, over [reference]'s.
     */
    private fun eddyShare(reference: DoubleArray, other: DoubleArray): Double {
        val referenceEddies = eddies(reference)
        val otherEddies = eddies(other)
        var moved = 0.0
        var size = 0.0
        for (cell in reference.indices) {
            val area = coarse.cellAreaSquareMeters[(cell % cellCount) / coarse.columns]
            moved += area * abs(referenceEddies[cell] - otherEddies[cell])
            size += area * abs(referenceEddies[cell])
        }
        return if (size > 0.0) moved / size else 0.0
    }

    /** [field] (both halves, as [given]) less each row's mean, half by half. */
    private fun eddies(field: DoubleArray): DoubleArray {
        val result = field.copyOf()
        for (row in 0 until 2 * coarse.rows) {
            val start = row * coarse.columns
            var mean = 0.0
            for (column in 0 until coarse.columns) mean += field[start + column]
            mean /= coarse.columns
            for (column in 0 until coarse.columns) result[start + column] -= mean
        }
        return result
    }

    companion object {
        /** Walker and Ni's mixing `beta`, one: the residual taken whole, as in all their experiments. */
        const val RELAXATION = 1.0

        /**
         * How many earlier laps the acceleration combines: three, the smallest of Walker and Ni's
         * experiments (their section 5, at three and at fifty), since the loop's slow part is one or
         * two modes and a longer memory carries laps the march's own settling has left behind.
         */
        const val ACCELERATION_DEPTH = 3

        /**
         * The least share of a column's size left once the earlier columns are taken out of it, under
         * which the column is dropped as nearly dependent: a millionth, a condition number of the
         * fit of about a million at worst.
         */
        const val INDEPENDENT_SHARE = 1.0e-6
    }
}
