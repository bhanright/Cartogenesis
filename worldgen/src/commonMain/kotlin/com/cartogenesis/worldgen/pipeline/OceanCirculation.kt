package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import kotlin.math.abs
import kotlin.math.expm1
import kotlin.math.floor
import kotlin.math.max

/**
 * One steady linear problem on one grid of the ocean, discretized, with every weight already
 * divided by the cell's own center weight.
 *
 * Two problems take this shape: the circulation (Stommel's balance, see [OceanCirculation]) and
 * the heat the circulation carries (see [OceanHeat]). In both, the discrete cell that satisfies
 * the balance given its four neighbors is `x = e x_east + w x_west + n x_north + s x_south - f`,
 * which is the whole of what a relaxation pass computes, on the processor and on both graphics
 * paths alike. Every weight is at least zero and they sum to at most one, which is what makes the
 * operator monotone.
 *
 * Handed across [OceanAccelerator] as it stands, so a device relaxes exactly the processor's
 * problem. The center weight is divided out here, once and in double precision, rather than in
 * each kernel, so there is one rounding of it and not three.
 */
class OceanStencil(
    /** The grid's width and height in cells; columns wrap east to west. */
    val cellsAcross: Int,
    val cellsDown: Int,
    /** Row-major, one entry per cell. Land cells are held at zero and relaxed no further. */
    val isWater: BooleanArray,
    /** Per cell, dimensionless, the weight of the neighbor on each side. */
    val eastWeight: FloatArray,
    val westWeight: FloatArray,
    val northWeight: FloatArray,
    val southWeight: FloatArray,
    /** Per cell, in the unknown's own unit: the balance's right-hand side over the center weight. */
    val forcing: FloatArray,
    /**
     * Per cell, the center weight itself: what turns a mismatch in the unknown back into a
     * mismatch in the balance, for the residual. The processor's alone.
     */
    internal val centreWeight: DoubleArray
)

/**
 * The wind-driven circulation's discrete operator, and the multigrid solve every ocean problem
 * shares.
 *
 * **The balance** is `r ∇²ψ + β ∂ψ/∂x = curl_z τ / (ρ H)` (Stommel 1948, *Trans. AGU* 29,
 * 202-206; Vallis 2017, *Atmospheric and Oceanic Fluid Dynamics*, 2nd ed., ch. 19). ψ is the
 * stream function of the wind-driven layer's mean velocity, in square meters a second, in the frame
 * x east, y north: `u = -∂ψ/∂y`, `v = ∂ψ/∂x`. Land holds ψ at zero, which closes every basin, and
 * each pole is a wall on the map's edge.
 *
 * **The β term.** The Stommel layer `δ_S = r/β` is where the return flow of the whole gyre runs,
 * and a central difference of `β ∂ψ/∂x` gives a western weight of `r/Δx² - β/(2Δx)`, negative
 * once `βΔx/(2r)` passes one. A negative weight is an operator that is not monotone, and its
 * solution rings across the boundary current. Upwinding cures that at the price of a boundary
 * current wider than the physics by about half a cell. So the x-diffusion is exponentially fitted
 * instead (Allen and Southwell 1955, *Q. J. Mech. Appl. Math.* 8, 129-145; Il'in 1969): `r` along x
 * becomes `r p coth p` with `p = βΔx/(2r)`. The scheme is then exact at the nodes for the
 * one-dimensional layer `r ψ'' + β ψ' = 0`, whose solution decays as `exp(-x/δ_S)`, at every
 * spacing; its western weight, `r p (coth p - 1)/Δx²`, is never negative; and where the grid
 * resolves the layer `p coth p = 1 + p²/3 + ...`, which is the central difference to second order.
 * Along y there is no β and the weights are `r/Δy²`.
 *
 * **The relaxation** is Gauss-Seidel, with no over-relaxation. The β term makes the operator far
 * from normal: with walls it is similar to a symmetric one only through a scaling that grows as
 * `(e/w)^(columns/2)`, and a periodic row (a sea that runs round the world) gives the Jacobi
 * iteration complex eigenvalues of modulus near one. Over-relaxation tuned for the symmetric part
 * overflowed within a hundred passes on every grid measured; Gauss-Seidel, whose eigenvalues are
 * the Jacobi ones squared, did not diverge on any.
 *
 * **Multigrid.** A single grid's relaxation still takes of the order of its rows squared in passes,
 * because a correction smooth across the basin spreads a cell a pass. So the solve is a V-cycle
 * (Brandt 1977, *Math. Comp.* 31, 333-390): relax, carry the residual to a grid half as fine, solve
 * for the correction there by the same means, carry it back and relax again. Cell-centered: the
 * residual is restricted as the mean of the cells it covers and the correction prolonged bilinearly.
 * A coarse cell is water only when every cell under it is, so a coarse grid never joins two basins
 * a narrow strip of land keeps apart; the correction it gives is a correction, and the relaxation on
 * the finest grid is what decides the answer.
 */
object OceanCirculation {

    /**
     * Below this `p` the fitted factor is written as its series, `1 + p²/3`: at 1e-4 the next
     * term, `-p⁴/45`, is 2e-18, and the direct form would lose digits dividing by `e^2p - 1`.
     */
    private const val SERIES_BELOW_CELL_PECLET = 1e-4

    /**
     * The circulation's residual, over the largest forcing, below which its solve has converged:
     * a thousandth, so no cell's balance is out by more than a thousandth of the strongest forcing
     * on the grid.
     */
    const val RESIDUAL_TOLERANCE = 1e-3

    /**
     * Relaxation passes on each grid of a V-cycle before the residual is carried down and after the
     * correction is carried back: two and two, the textbook V(2,2).
     */
    internal const val PASSES_EACH_WAY = 2

    /** The coarsest grid of the cycle, in rows: coarsening stops before a grid would have fewer. */
    const val COARSEST_ROWS = 8

    /**
     * The coarsest grid is relaxed until its own residual is this share of the solve's tolerance,
     * so the correction it hands up is not what limits the cycle.
     */
    internal const val COARSEST_TOLERANCE_SHARE = 0.1

    /** Passes on the coarsest grid between readings of its residual. */
    internal const val COARSEST_PASSES_PER_CHECK = 20

    /** V-cycles after which a solve that has not converged is returned and reported rather than run forever. */
    const val MOST_CYCLES = 200

    /**
     * How far from square a cell may be and still be halved along both axes: a quarter. A grid of
     * cells more oblong than that is halved along their short side alone.
     */
    private const val SQUARE_ENOUGH = 1.25

    /** What a solve returned: the unknown on the finest grid, the V-cycles it took and the residual it reached. */
    class Solution(val values: FloatArray, val cycles: Int, val relativeResidual: Double)

    /**
     * A solve that ended without an answer: its residual not under its tolerance when it stopped,
     * or a value, or the residual itself, not a number. Thrown rather than handed on, because a
     * stopped solve's field looks like an ocean and is not one, and a world saved with it would carry
     * the wrong sea for its seed with nothing to say so.
     */
    class OceanSolveFailure(message: String) : IllegalStateException(message)

    /**
     * [solution] if it is an answer to [tolerance], and otherwise an [OceanSolveFailure] naming
     * [what] failed and how. A residual that is not a number compares false with any tolerance, so it
     * is asked for as finite first, and every value with it.
     */
    fun requireSolved(what: String, solution: Solution, tolerance: Double): Solution {
        val residualFinite = solution.relativeResidual.isFinite()
        val firstBad = solution.values.indexOfFirst { !it.isFinite() }
        if (!residualFinite || firstBad >= 0 || solution.relativeResidual >= tolerance) {
            throw OceanSolveFailure(
                "$what did not solve: relative residual ${solution.relativeResidual} after ${solution.cycles} " +
                    "iterations against a tolerance of $tolerance" +
                    (if (firstBad >= 0) ", and cell $firstBad holds ${solution.values[firstBad]}" else "")
            )
        }
        return solution
    }

    /**
     * Builds the circulation's problem for one grid.
     *
     * [cellWidthMeters] and [cellHeightMeters] are the grid's own spacing on the ground; they need
     * not be equal. [betaPerMeterSecond] is β at each row's center, [bottomDragPerSecond] is `r`,
     * and [balancePerSecondSquared] is the right-hand side per cell, `curl_z τ / (ρ H)` for the
     * circulation itself. Land cells' right-hand side is ignored.
     */
    fun stencil(
        cellsAcross: Int,
        cellsDown: Int,
        cellWidthMeters: Double,
        cellHeightMeters: Double,
        isWater: BooleanArray,
        betaPerMeterSecond: DoubleArray,
        bottomDragPerSecond: Double,
        balancePerSecondSquared: DoubleArray
    ): OceanStencil {
        val cells = cellsAcross * cellsDown
        val east = FloatArray(cells)
        val west = FloatArray(cells)
        val north = FloatArray(cells)
        val south = FloatArray(cells)
        val centre = DoubleArray(cells)
        val alongY = bottomDragPerSecond / (cellHeightMeters * cellHeightMeters)
        val widthSquared = cellWidthMeters * cellWidthMeters
        for (row in 0 until cellsDown) {
            val beta = betaPerMeterSecond[row]
            val cellPeclet = abs(beta) * cellWidthMeters / (2.0 * bottomDragPerSecond)
            val eastAlongX: Double
            val westAlongX: Double
            if (cellPeclet < SERIES_BELOW_CELL_PECLET) {
                val fitted = bottomDragPerSecond * (1.0 + cellPeclet * cellPeclet / 3.0) / widthSquared
                val upstream = abs(beta) / (2.0 * cellWidthMeters)
                eastAlongX = fitted + upstream
                westAlongX = fitted - upstream
            } else {
                // r p (coth p ± 1) / Δx², written so neither side subtracts two near-equal
                // numbers: coth p - 1 = 2 / (e^2p - 1). β is positive in both hemispheres, so the
                // side the fitting leans on is always the east.
                val scale = bottomDragPerSecond * cellPeclet / widthSquared
                val cothLessOne = 2.0 / expm1(2.0 * cellPeclet)
                eastAlongX = scale * (2.0 + cothLessOne)
                westAlongX = scale * cothLessOne
            }
            // The poles are walls at the map's top and bottom edges, which are cell faces: the
            // value beyond the first and last rows is minus the row's own, so ψ is zero on the
            // face. Folded into the weights here, the north or south weight of an edge row is
            // zero and its center carries the wall, and every grid of a cycle puts the wall in
            // the same place.
            val northAlongY = if (row == 0) 0.0 else alongY
            val southAlongY = if (row == cellsDown - 1) 0.0 else alongY
            val centreWeight = eastAlongX + westAlongX + 2.0 * alongY +
                (alongY - northAlongY) + (alongY - southAlongY)
            for (cell in row * cellsAcross until (row + 1) * cellsAcross) {
                east[cell] = (eastAlongX / centreWeight).toFloat()
                west[cell] = (westAlongX / centreWeight).toFloat()
                north[cell] = (northAlongY / centreWeight).toFloat()
                south[cell] = (southAlongY / centreWeight).toFloat()
                centre[cell] = centreWeight
            }
        }
        return withBalance(
            OceanStencil(cellsAcross, cellsDown, isWater, east, west, north, south, FloatArray(cells), centre),
            balancePerSecondSquared
        )
    }

    /** The same operator with another right-hand side, in the balance's unit per cell; zero on land. */
    fun withBalance(stencil: OceanStencil, balance: DoubleArray): OceanStencil {
        val forcing = FloatArray(stencil.cellsAcross * stencil.cellsDown)
        for (cell in forcing.indices) {
            if (stencil.isWater[cell]) forcing[cell] = (balance[cell] / stencil.centreWeight[cell]).toFloat()
        }
        return OceanStencil(
            stencil.cellsAcross, stencil.cellsDown, stencil.isWater, stencil.eastWeight, stencil.westWeight,
            stencil.northWeight, stencil.southWeight, forcing, stencil.centreWeight
        )
    }

    /**
     * [passes] red-black Gauss-Seidel passes over [stencil], in place in [values].
     *
     * The reference every accelerator is held to. Land is held at zero; columns wrap; an edge row's
     * weight toward its pole is zero, so what lies beyond is never read. Coloring by
     * `(column + row)` parity on an even-width cylinder means no two cells of one color are
     * neighbors, so a color updates in parallel and in place.
     */
    fun relax(stencil: OceanStencil, values: FloatArray, passes: Int) {
        val across = stencil.cellsAcross
        val down = stencil.cellsDown
        repeat(passes) {
            for (colour in 0..1) {
                parallelChunks(0, down) { startRow, endRow ->
                    for (row in startRow until endRow) {
                        for (column in 0 until across) {
                            if ((column + row) and 1 != colour) continue
                            val cell = row * across + column
                            values[cell] = if (stencil.isWater[cell]) updated(stencil, values, row, column) else 0f
                        }
                    }
                }
            }
        }
    }

    /**
     * The value the balance asks of one water cell given its neighbors, summed in the order both
     * shaders sum it, which is part of what holds them to this answer.
     */
    private fun updated(stencil: OceanStencil, values: FloatArray, row: Int, column: Int): Float {
        val across = stencil.cellsAcross
        val cell = row * across + column
        val columnEast = if (column + 1 == across) 0 else column + 1
        val columnWest = if (column == 0) across - 1 else column - 1
        val valueNorth = if (row > 0) values[cell - across] else 0f
        val valueSouth = if (row + 1 < stencil.cellsDown) values[cell + across] else 0f
        return stencil.eastWeight[cell] * values[row * across + columnEast] +
            stencil.westWeight[cell] * values[row * across + columnWest] +
            stencil.northWeight[cell] * valueNorth + stencil.southWeight[cell] * valueSouth -
            stencil.forcing[cell]
    }

    /**
     * What the balance still lacks at every water cell, in the balance's unit; zero on land.
     *
     * The balance's right-hand side less what the values make of it, `F - Lx`, with the operator
     * `Lx = (e x_east + w x_west + n x_north + s x_south - x) × center`: the sign a correction
     * solving `L δ = F - Lx` must be added with.
     *
     * Computed in double precision from the single-precision values, so it is the residual of the
     * values themselves and not of the values plus the rounding of the arithmetic that measures
     * them. Summed in single precision, the four products and the forcing each round at a part in
     * seventeen million of ψ, and where ψ is large beside the forcing, as in the wide southern oceans
     * of seeds 42 and 99 under a weak curl, that rounding alone stood at 1.05e-3 of the largest
     * balance, above the solve's tolerance, and no number of cycles could get under it
     * (docs/DESIGN_LEDGER.md, 4b-1). The relaxation itself stays in single precision, the arithmetic
     * both devices share.
     */
    fun residual(stencil: OceanStencil, values: FloatArray): DoubleArray {
        val across = stencil.cellsAcross
        val down = stencil.cellsDown
        val mismatch = DoubleArray(across * down)
        parallelChunks(0, down) { startRow, endRow ->
            for (row in startRow until endRow) {
                for (column in 0 until across) {
                    val cell = row * across + column
                    if (!stencil.isWater[cell]) continue
                    val columnEast = if (column + 1 == across) 0 else column + 1
                    val columnWest = if (column == 0) across - 1 else column - 1
                    val valueNorth = if (row > 0) values[cell - across].toDouble() else 0.0
                    val valueSouth = if (row + 1 < down) values[cell + across].toDouble() else 0.0
                    val balanced = stencil.eastWeight[cell].toDouble() * values[row * across + columnEast] +
                        stencil.westWeight[cell].toDouble() * values[row * across + columnWest] +
                        stencil.northWeight[cell].toDouble() * valueNorth +
                        stencil.southWeight[cell].toDouble() * valueSouth -
                        stencil.forcing[cell].toDouble()
                    mismatch[cell] = (values[cell] - balanced) * stencil.centreWeight[cell]
                }
            }
        }
        return mismatch
    }

    /** The largest magnitude in [values], for reading a residual against a forcing. */
    fun largest(values: DoubleArray): Double {
        var largest = 0.0
        for (value in values) largest = max(largest, abs(value))
        return largest
    }

    /** A stencil's right-hand side back in the balance's own unit. */
    fun balanceOf(stencil: OceanStencil): DoubleArray = DoubleArray(stencil.forcing.size) { cell ->
        stencil.forcing[cell] * stencil.centreWeight[cell]
    }

    /**
     * A residual carried to the grid [coarseAcross] by [coarseDown]: each coarse cell takes the
     * mean over the fine cells it covers, which is what a balance per unit area restricts as. The
     * coarse grid is the fine one halved along each axis whose count it halves.
     */
    fun restrict(fine: DoubleArray, fineAcross: Int, fineDown: Int, coarseAcross: Int, coarseDown: Int): DoubleArray {
        val columnsPerCoarse = fineAcross / coarseAcross
        val rowsPerCoarse = fineDown / coarseDown
        val coarse = DoubleArray(coarseAcross * coarseDown)
        val share = 1.0 / (columnsPerCoarse * rowsPerCoarse)
        for (row in 0 until fineDown) {
            val coarseRow = row / rowsPerCoarse
            for (column in 0 until fineAcross) {
                coarse[coarseRow * coarseAcross + column / columnsPerCoarse] += fine[row * fineAcross + column] * share
            }
        }
        return coarse
    }

    /**
     * Adds [coarse], a correction on the grid [coarseAcross] by [coarseDown], to [fine]'s water
     * cells, read bilinearly between coarse cell centers: columns wrap. Beyond either pole the
     * correction is minus the edge row's where [poleIsWall] (the circulation, zero on the pole's
     * face as ψ is) and the edge row's own elsewhere (the heat, which crosses no pole).
     */
    fun prolongAdd(
        coarse: FloatArray,
        coarseAcross: Int,
        coarseDown: Int,
        fine: FloatArray,
        fineAcross: Int,
        fineDown: Int,
        isWater: BooleanArray,
        poleIsWall: Boolean
    ) {
        val columnsPerCoarse = fineAcross / coarseAcross
        val rowsPerCoarse = fineDown / coarseDown
        val beyondPole = if (poleIsWall) -1f else 1f
        parallelChunks(0, fineDown) { startRow, endRow ->
            for (row in startRow until endRow) {
                val coarseRow = (row + 0.5f) / rowsPerCoarse - 0.5f
                val above = floor(coarseRow).toInt()
                val downBlend = coarseRow - above
                for (column in 0 until fineAcross) {
                    val cell = row * fineAcross + column
                    if (!isWater[cell]) continue
                    val coarseColumn = (column + 0.5f) / columnsPerCoarse - 0.5f
                    val left = floor(coarseColumn).toInt()
                    val acrossBlend = coarseColumn - left
                    val westColumn = (left + coarseAcross) % coarseAcross
                    val eastColumn = (left + 1) % coarseAcross
                    fun at(coarseRowIndex: Int, coarseColumnIndex: Int): Float = when {
                        coarseRowIndex < 0 -> beyondPole * coarse[coarseColumnIndex]
                        coarseRowIndex >= coarseDown ->
                            beyondPole * coarse[(coarseDown - 1) * coarseAcross + coarseColumnIndex]
                        else -> coarse[coarseRowIndex * coarseAcross + coarseColumnIndex]
                    }
                    val top = at(above, westColumn) * (1f - acrossBlend) + at(above, eastColumn) * acrossBlend
                    val bottom = at(above + 1, westColumn) * (1f - acrossBlend) + at(above + 1, eastColumn) * acrossBlend
                    fine[cell] += top * (1f - downBlend) + bottom * downBlend
                }
            }
        }
    }

    /**
     * A coarse grid's water.
     *
     * With [everyCellWater], a coarse cell is water only when every fine cell under it is: for a
     * problem whose coast holds the unknown at zero, the circulation, where a coarse cell that
     * crossed a strip of land would join two basins the fine grid keeps apart. Without it, when any
     * fine cell under it is: for a problem whose coast passes no flux, the heat, where a coarse cell
     * cut off from water its fine cells reach would have nothing but the relaxation to hold it, and
     * would hand back a correction of τ times its residual.
     */
    fun coarseWater(
        fine: BooleanArray,
        fineAcross: Int,
        fineDown: Int,
        coarseAcross: Int,
        coarseDown: Int,
        everyCellWater: Boolean
    ): BooleanArray {
        val columnsPerCoarse = fineAcross / coarseAcross
        val rowsPerCoarse = fineDown / coarseDown
        val coarse = BooleanArray(coarseAcross * coarseDown) { everyCellWater }
        for (row in 0 until fineDown) {
            for (column in 0 until fineAcross) {
                if (fine[row * fineAcross + column] != everyCellWater) {
                    coarse[(row / rowsPerCoarse) * coarseAcross + column / columnsPerCoarse] = !everyCellWater
                }
            }
        }
        return coarse
    }

    /**
     * The grids of a V-cycle below [finest], finest first, each built by [coarser] from its size
     * and its water, which [coarseWater] decides with [everyCellWater].
     *
     * A grid whose cells are twice as wide as they are tall is first halved in rows only, so every
     * grid below it has cells square on the ground; after that both counts are halved together.
     * Coarsening stops at [COARSEST_ROWS], or where a count will not halve evenly, or where the
     * width would lose the even count red-black coloring needs.
     */
    fun levels(
        finest: OceanStencil,
        cellWidthMeters: Double,
        cellHeightMeters: Double,
        everyCellWater: Boolean,
        coarser: (cellsAcross: Int, cellsDown: Int, isWater: BooleanArray) -> OceanStencil
    ): List<OceanStencil> {
        val levels = arrayListOf(finest)
        var across = finest.cellsAcross
        var down = finest.cellsDown
        var widthMeters = cellWidthMeters
        var heightMeters = cellHeightMeters
        var isWater = finest.isWater
        while (true) {
            val wideCells = widthMeters > heightMeters * SQUARE_ENOUGH
            val tallCells = heightMeters > widthMeters * SQUARE_ENOUGH
            val nextAcross = if (wideCells) across else across / 2
            val nextDown = if (tallCells) down else down / 2
            val halvesEvenly = (nextAcross == across || across % 2 == 0) && (nextDown == down || down % 2 == 0)
            if (!halvesEvenly || nextDown < COARSEST_ROWS || nextAcross % 2 != 0) break
            isWater = coarseWater(isWater, across, down, nextAcross, nextDown, everyCellWater)
            widthMeters *= across.toDouble() / nextAcross
            heightMeters *= down.toDouble() / nextDown
            across = nextAcross
            down = nextDown
            levels.add(coarser(across, down, isWater))
        }
        return levels
    }

    /**
     * V-cycles on [levels] from [start] until the finest grid's residual is under [tolerance] of
     * its largest right-hand side, or [MOST_CYCLES] have run.
     *
     * [relax] runs passes of [relax]'s own arithmetic on a stencil from a start and returns the
     * result; it is how a device takes the relaxation. The residual that decides when to stop is the
     * processor's, whoever relaxed. [poleIsWall] is [prolongAdd]'s. With [bodies], each cycle is
     * followed by [correctBodyMeans].
     */
    internal inline fun solve(
        levels: List<OceanStencil>,
        start: FloatArray,
        tolerance: Double,
        poleIsWall: Boolean,
        bodies: WaterBodies?,
        relax: (OceanStencil, FloatArray, Int) -> FloatArray
    ): Solution {
        val finest = levels.first()
        var values = start
        val largestBalance = largest(balanceOf(finest))
        var cycles = 0
        var residual = if (largestBalance > 0.0) largest(residual(finest, values)) / largestBalance else 0.0
        while (residual >= tolerance && cycles < MOST_CYCLES) {
            values = vCycle(levels, values, tolerance, poleIsWall, relax = relax)
            if (bodies != null) correctBodyMeans(finest, values, bodies)
            cycles++
            residual = largest(residual(finest, values)) / largestBalance
        }
        return Solution(values, cycles, residual)
    }

    /**
     * The same solve by BiCGSTAB (van der Vorst 1992, *SIAM J. Sci. Stat. Comput.* 13, 631-644),
     * each iteration preconditioned by one V-cycle and [correctBodyMeans], until the residual is
     * under [tolerance] of the largest right-hand side or [mostIterations] have run.
     *
     * For the heat, whose V-cycle alone stalls where a coarse grid cannot represent the water: a
     * channel one cell wide, a sea behind a strip of land, a boundary current narrower than a coarse
     * cell. Those are few modes against the grid's million, and a Krylov method removes a few modes
     * in a few iterations whatever the cycle makes of them. Right-preconditioned, so the residual it
     * tracks is the balance's own and the tolerance means what [solve]'s does.
     */
    internal inline fun solveByKrylov(
        levels: List<OceanStencil>,
        start: FloatArray,
        tolerance: Double,
        poleIsWall: Boolean,
        bodies: WaterBodies,
        mostIterations: Int = MOST_CYCLES,
        relax: (OceanStencil, FloatArray, Int) -> FloatArray
    ): Solution {
        val finest = levels.first()
        val cells = start.size
        val homogeneous = withBalance(finest, DoubleArray(cells))
        val largestBalance = largest(balanceOf(finest))
        val x = DoubleArray(cells) { start[it].toDouble() }
        val balance = balanceOf(finest)
        val r = DoubleArray(cells)
        applyOperator(homogeneous, x, r)
        for (cell in 0 until cells) r[cell] = if (finest.isWater[cell]) balance[cell] - r[cell] else 0.0
        var residualNow = if (largestBalance > 0.0) largest(r) / largestBalance else 0.0
        val shadow = r.copyOf()
        val p = DoubleArray(cells)
        val v = DoubleArray(cells)
        var rho = 1.0
        var alpha = 1.0
        var omega = 1.0
        var iterations = 0
        while (residualNow >= tolerance && iterations < mostIterations) {
            val rhoNext = dot(shadow, r)
            if (rhoNext == 0.0 || omega == 0.0) break
            val beta = (rhoNext / rho) * (alpha / omega)
            for (cell in 0 until cells) p[cell] = r[cell] + beta * (p[cell] - omega * v[cell])
            val pHat = precondition(levels, bodies, p, tolerance, poleIsWall, relax)
            applyOperator(homogeneous, pHat, v)
            val shadowV = dot(shadow, v)
            if (shadowV == 0.0) break
            alpha = rhoNext / shadowV
            for (cell in 0 until cells) r[cell] -= alpha * v[cell]
            for (cell in 0 until cells) x[cell] += alpha * pHat[cell]
            rho = rhoNext
            iterations++
            residualNow = largest(r) / largestBalance
            if (residualNow < tolerance) break
            val sHat = precondition(levels, bodies, r, tolerance, poleIsWall, relax)
            val t = DoubleArray(cells)
            applyOperator(homogeneous, sHat, t)
            val tt = dot(t, t)
            omega = if (tt == 0.0) 0.0 else dot(t, r) / tt
            for (cell in 0 until cells) {
                x[cell] += omega * sHat[cell]
                r[cell] -= omega * t[cell]
            }
            residualNow = largest(r) / largestBalance
        }
        // Judged by the recurrence's residual, which is carried in double precision. The residual of
        // the single-precision answer has a floor of its own: a temperature stored to one part in
        // ten million, times the center weight, is a thousandth or so of the largest right-hand
        // side where the eddies are strongest, since that weight is some eight thousand times the
        // relaxation's.
        return Solution(FloatArray(cells) { x[it].toFloat() }, iterations, residualNow)
    }

    /** One V-cycle on the correction problem `L z = [balance]` from zero, then the bodies' means. */
    internal inline fun precondition(
        levels: List<OceanStencil>,
        bodies: WaterBodies,
        balance: DoubleArray,
        tolerance: Double,
        poleIsWall: Boolean,
        relax: (OceanStencil, FloatArray, Int) -> FloatArray
    ): DoubleArray {
        val corrected = listOf(withBalance(levels.first(), balance)) + levels.drop(1)
        val z = vCycle(corrected, FloatArray(balance.size), tolerance, poleIsWall, PRECONDITIONER_COARSEST_PASSES, relax)
        correctBodyMeans(corrected.first(), z, bodies)
        return DoubleArray(z.size) { z[it].toDouble() }
    }

    /**
     * Passes on the coarsest grid when a V-cycle is a Krylov solve's preconditioner, which must be
     * the same linear operator at every iteration: two hundred, several times the coarsest grid's
     * fifteen rows squared over the number of rows a pass carries a correction.
     */
    internal const val PRECONDITIONER_COARSEST_PASSES = 200

    /**
     * `L z` into [into], in double precision: `(e z_east + w z_west + n z_north + s z_south - z) ×
     * center` over the water, zero on land. Double because the center weight is thousands of times
     * the relaxation where the eddies are strongest, and a product carried in single precision
     * would hand the Krylov iteration an operator a thousandth out, which it cannot converge past.
     */
    fun applyOperator(homogeneous: OceanStencil, z: DoubleArray, into: DoubleArray) {
        val across = homogeneous.cellsAcross
        val down = homogeneous.cellsDown
        parallelChunks(0, down) { startRow, endRow ->
            for (row in startRow until endRow) {
                for (column in 0 until across) {
                    val cell = row * across + column
                    if (!homogeneous.isWater[cell]) {
                        into[cell] = 0.0
                        continue
                    }
                    val columnEast = if (column + 1 == across) 0 else column + 1
                    val columnWest = if (column == 0) across - 1 else column - 1
                    val north = if (row > 0) z[cell - across] else 0.0
                    val south = if (row + 1 < down) z[cell + across] else 0.0
                    val neighbors = homogeneous.eastWeight[cell] * z[row * across + columnEast] +
                        homogeneous.westWeight[cell] * z[row * across + columnWest] +
                        homogeneous.northWeight[cell] * north + homogeneous.southWeight[cell] * south
                    into[cell] = (neighbors - z[cell]) * homogeneous.centreWeight[cell]
                }
            }
        }
    }

    fun dot(a: DoubleArray, b: DoubleArray): Double {
        var sum = 0.0
        for (cell in a.indices) sum += a[cell] * b[cell]
        return sum
    }

    /**
     * The connected bodies of water on one grid: [bodyOf] per cell, -1 on land, and [count] of
     * them. Four-connected, columns wrapping.
     */
    class WaterBodies(val bodyOf: IntArray, val count: Int)

    /** Labels [isWater]'s connected bodies. */
    fun waterBodies(isWater: BooleanArray, cellsAcross: Int, cellsDown: Int): WaterBodies {
        val bodyOf = IntArray(isWater.size) { -1 }
        var count = 0
        val pending = IntArray(isWater.size)
        for (seed in isWater.indices) {
            if (!isWater[seed] || bodyOf[seed] >= 0) continue
            var top = 0
            pending[top++] = seed
            bodyOf[seed] = count
            while (top > 0) {
                val cell = pending[--top]
                val row = cell / cellsAcross
                val column = cell - row * cellsAcross
                val east = row * cellsAcross + if (column + 1 == cellsAcross) 0 else column + 1
                val west = row * cellsAcross + if (column == 0) cellsAcross - 1 else column - 1
                for (next in intArrayOf(east, west, if (row > 0) cell - cellsAcross else -1, if (row + 1 < cellsDown) cell + cellsAcross else -1)) {
                    if (next >= 0 && isWater[next] && bodyOf[next] < 0) {
                        bodyOf[next] = count
                        pending[top++] = next
                    }
                }
            }
            count++
        }
        return WaterBodies(bodyOf, count)
    }

    /**
     * Shifts each body of water by the uniform amount that zeroes its summed residual.
     *
     * For an operator in which every row's weights fall short of one by its own margin (the heat's,
     * whose margin is `1/τ` and the rising water's `w/h`, or the pressure's α), a uniform shift δ
     * of one body changes each of its cells' residual by exactly the margin times δ, whatever the currents and eddies do inside it. So the shift that
     * zeroes the body's summed residual is minus the sum of its residuals over the sum of its margins,
     * and it is the correction a coarse grid cannot give: a coarse cell that straddles a strip of
     * land joins two bodies the fine grid keeps apart, and a small sea cut off from the ocean would
     * otherwise have only the relaxation, a few parts in ten thousand a pass, to settle its mean.
     */
    fun correctBodyMeans(stencil: OceanStencil, values: FloatArray, bodies: WaterBodies) {
        val residual = residual(stencil, values)
        val residualSum = DoubleArray(bodies.count)
        val marginSum = DoubleArray(bodies.count)
        for (cell in values.indices) {
            val body = bodies.bodyOf[cell]
            if (body < 0) continue
            val weights = stencil.eastWeight[cell].toDouble() + stencil.westWeight[cell] +
                stencil.northWeight[cell] + stencil.southWeight[cell]
            residualSum[body] += residual[cell]
            marginSum[body] += stencil.centreWeight[cell] * (1.0 - weights)
        }
        for (cell in values.indices) {
            val body = bodies.bodyOf[cell]
            if (body < 0 || marginSum[body] <= 0.0) continue
            values[cell] -= (residualSum[body] / marginSum[body]).toFloat()
        }
    }

    /**
     * One V-cycle from [start] on [levels], returning the finest grid's values.
     *
     * Down the grids: relax, restrict the residual as the next grid's right-hand side. The coarsest
     * grid is relaxed on the processor, since it is a few hundred cells: to convergence, or for
     * [fixedCoarsestPasses] passes when the cycle must be one fixed linear operator (a Krylov
     * solve's preconditioner). Back up: add the correction, relax again. Every relaxation above the
     * coarsest goes through [relax].
     */
    internal inline fun vCycle(
        levels: List<OceanStencil>,
        start: FloatArray,
        tolerance: Double,
        poleIsWall: Boolean,
        fixedCoarsestPasses: Int = 0,
        relax: (OceanStencil, FloatArray, Int) -> FloatArray
    ): FloatArray {
        val count = levels.size
        val stencils = arrayOfNulls<OceanStencil>(count)
        val values = arrayOfNulls<FloatArray>(count)
        stencils[0] = levels[0]
        values[0] = start
        for (level in 0 until count - 1) {
            val stencil = stencils[level]!!
            values[level] = relax(stencil, values[level]!!, PASSES_EACH_WAY)
            val coarse = levels[level + 1]
            val coarseBalance = restrict(
                residual(stencil, values[level]!!),
                stencil.cellsAcross, stencil.cellsDown, coarse.cellsAcross, coarse.cellsDown
            )
            stencils[level + 1] = withBalance(coarse, coarseBalance)
            values[level + 1] = FloatArray(coarse.cellsAcross * coarse.cellsDown)
        }
        val coarsest = stencils[count - 1]!!
        if (count > 1) {
            // On the processor either way: the coarsest grid is a few hundred cells.
            if (fixedCoarsestPasses > 0) OceanCirculation.relax(coarsest, values[count - 1]!!, fixedCoarsestPasses)
            else relaxToConvergence(coarsest, values[count - 1]!!, tolerance * COARSEST_TOLERANCE_SHARE)
        }
        for (level in count - 2 downTo 0) {
            val stencil = stencils[level]!!
            val coarse = stencils[level + 1]!!
            prolongAdd(
                values[level + 1]!!, coarse.cellsAcross, coarse.cellsDown,
                values[level]!!, stencil.cellsAcross, stencil.cellsDown, stencil.isWater, poleIsWall
            )
            values[level] = relax(stencil, values[level]!!, PASSES_EACH_WAY)
        }
        if (count == 1) values[0] = relax(coarsest, values[0]!!, PASSES_EACH_WAY)
        return values[0]!!
    }

    /** Relaxes a small grid until its residual is under [tolerance] of its largest right-hand side. */
    fun relaxToConvergence(stencil: OceanStencil, values: FloatArray, tolerance: Double) {
        val largestBalance = largest(balanceOf(stencil))
        if (largestBalance <= 0.0) return
        var passes = 0
        while (passes < MOST_CYCLES * COARSEST_PASSES_PER_CHECK &&
            largest(residual(stencil, values)) > largestBalance * tolerance
        ) {
            relax(stencil, values, COARSEST_PASSES_PER_CHECK)
            passes += COARSEST_PASSES_PER_CHECK
        }
    }

    /**
     * The layer's velocity at cell centers from ψ by central differences, in meters a second, zero
     * on land: an eastward `-∂ψ/∂y` and a northward `∂ψ/∂x`. Beyond a pole ψ is minus the edge
     * row's, the wall on the pole's face that the stencil holds.
     */
    fun velocities(
        stencil: OceanStencil,
        stream: FloatArray,
        cellWidthMeters: Double,
        cellHeightMeters: Double,
        eastwardMps: FloatArray,
        northwardMps: FloatArray
    ) {
        val across = stencil.cellsAcross
        val down = stencil.cellsDown
        for (row in 0 until down) {
            for (column in 0 until across) {
                val cell = row * across + column
                if (!stencil.isWater[cell]) {
                    eastwardMps[cell] = 0f
                    northwardMps[cell] = 0f
                    continue
                }
                val columnEast = if (column + 1 == across) 0 else column + 1
                val columnWest = if (column == 0) across - 1 else column - 1
                val streamNorth = if (row > 0) stream[cell - across] else -stream[cell]
                val streamSouth = if (row + 1 < down) stream[cell + across] else -stream[cell]
                eastwardMps[cell] = (-(streamNorth - streamSouth) / (2.0 * cellHeightMeters)).toFloat()
                northwardMps[cell] = ((stream[row * across + columnEast] -
                    stream[row * across + columnWest]) / (2.0 * cellWidthMeters)).toFloat()
            }
        }
    }
}
