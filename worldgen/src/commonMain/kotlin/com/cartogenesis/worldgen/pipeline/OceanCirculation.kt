package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import kotlin.math.abs
import kotlin.math.expm1
import kotlin.math.max

/**
 * One wind-driven circulation problem on one grid: Stommel's vorticity balance, discretised, with
 * every weight already divided by the cell's own centre weight.
 *
 * The balance is `r ∇²ψ + β ∂ψ/∂x = curl_z τ / (ρ H)` (Stommel 1948, *Trans. AGU* 29, 202-206;
 * Vallis 2017, *Atmospheric and Oceanic Fluid Dynamics*, 2nd ed., ch. 19). ψ is the stream
 * function of the wind-driven layer's mean velocity, in square metres a second, in the frame x
 * east, y north: `u = -∂ψ/∂y`, `v = ∂ψ/∂x`. The discrete cell that satisfies it given its four
 * neighbours is `ψ = e ψ_east + w ψ_west + n ψ_north + s ψ_south - f`, which is the whole of what
 * a relaxation pass computes, on the processor and on both graphics paths alike: the weights
 * `e, w, n, s` are per row, because β is, and [forcing] is per cell, in ψ's own unit.
 *
 * Handed across [OceanAccelerator] as it stands, so a device relaxes exactly the processor's
 * problem. The centre weight is divided out here, once and in double precision, rather than in each
 * kernel, so there is one rounding of it and not three.
 */
class OceanStencil(
    /** The grid's width and height in cells; columns wrap east to west. */
    val cellsAcross: Int,
    val cellsDown: Int,
    /** Row-major, one entry per cell. Land pins ψ at zero, which is what closes a basin. */
    val isWater: BooleanArray,
    /** Per row, dimensionless, each at least zero and together summing to one. */
    val eastWeight: FloatArray,
    val westWeight: FloatArray,
    val northWeight: FloatArray,
    val southWeight: FloatArray,
    /** Per cell, square metres a second: the balance's right-hand side divided by the row's centre weight. */
    val forcing: FloatArray,
    /**
     * Per row, the centre weight itself, per second per square metre: what turns a mismatch in ψ
     * back into a mismatch in the balance, for the residual. The processor's alone.
     */
    internal val centreWeight: DoubleArray
)

/**
 * The discrete Stommel operator, its relaxation and the multigrid pieces that make the relaxation
 * converge on any grid.
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
 * the Jacobi ones squared, cannot diverge on this operator.
 *
 * **Multigrid.** A single grid's relaxation still takes of the order of its rows squared in passes,
 * because a correction smooth across the basin spreads a cell a pass. So the solve is a V-cycle
 * (Brandt 1977, *Math. Comp.* 31, 333-390): relax, carry the residual to a grid half as fine, solve
 * for the correction there by the same means, carry it back and relax again. Cell-centred: the
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
     * The steady balance's residual, over the largest forcing, below which the solve has
     * converged: a thousandth, so no cell's balance is out by more than a thousandth of the
     * strongest forcing on the grid.
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
     * The coarsest grid is relaxed until its own residual is this share of [RESIDUAL_TOLERANCE],
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

    /** What a solve returned: ψ on the finest grid, the V-cycles it took and the residual it reached. */
    class Solution(val stream: FloatArray, val cycles: Int, val relativeResidual: Double)

    /**
     * Builds the problem for one grid.
     *
     * [cellWidthMetres] and [cellHeightMetres] are the grid's own spacing on the ground; they need
     * not be equal. [betaPerMetreSecond] is β at each row's centre, [bottomDragPerSecond] is `r`,
     * and [balancePerSecondSquared] is the right-hand side per cell, `curl_z τ / (ρ H)` for the
     * circulation itself. Land cells' right-hand side is ignored.
     */
    fun stencil(
        cellsAcross: Int,
        cellsDown: Int,
        cellWidthMetres: Double,
        cellHeightMetres: Double,
        isWater: BooleanArray,
        betaPerMetreSecond: DoubleArray,
        bottomDragPerSecond: Double,
        balancePerSecondSquared: DoubleArray
    ): OceanStencil {
        val east = FloatArray(cellsDown)
        val west = FloatArray(cellsDown)
        val north = FloatArray(cellsDown)
        val south = FloatArray(cellsDown)
        val centre = DoubleArray(cellsDown)
        val alongY = bottomDragPerSecond / (cellHeightMetres * cellHeightMetres)
        val widthSquared = cellWidthMetres * cellWidthMetres
        for (row in 0 until cellsDown) {
            val beta = betaPerMetreSecond[row]
            val cellPeclet = abs(beta) * cellWidthMetres / (2.0 * bottomDragPerSecond)
            val eastAlongX: Double
            val westAlongX: Double
            if (cellPeclet < SERIES_BELOW_CELL_PECLET) {
                val fitted = bottomDragPerSecond * (1.0 + cellPeclet * cellPeclet / 3.0) / widthSquared
                val upstream = abs(beta) / (2.0 * cellWidthMetres)
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
            val centreWeight = eastAlongX + westAlongX + 2.0 * alongY
            east[row] = (eastAlongX / centreWeight).toFloat()
            west[row] = (westAlongX / centreWeight).toFloat()
            north[row] = (alongY / centreWeight).toFloat()
            south[row] = (alongY / centreWeight).toFloat()
            centre[row] = centreWeight
        }
        val stencil = OceanStencil(
            cellsAcross, cellsDown, isWater, east, west, north, south,
            FloatArray(cellsAcross * cellsDown), centre
        )
        return withBalance(stencil, balancePerSecondSquared)
    }

    /** The same operator with another right-hand side, per second squared per cell; zero on land. */
    fun withBalance(stencil: OceanStencil, balancePerSecondSquared: DoubleArray): OceanStencil {
        val across = stencil.cellsAcross
        val forcing = FloatArray(across * stencil.cellsDown)
        for (cell in forcing.indices) {
            if (stencil.isWater[cell]) {
                forcing[cell] = (balancePerSecondSquared[cell] / stencil.centreWeight[cell / across]).toFloat()
            }
        }
        return OceanStencil(
            across, stencil.cellsDown, stencil.isWater, stencil.eastWeight, stencil.westWeight,
            stencil.northWeight, stencil.southWeight, forcing, stencil.centreWeight
        )
    }

    /**
     * [passes] red-black Gauss-Seidel passes over [stencil], in place in [stream].
     *
     * The reference every accelerator is held to. Land is pinned at zero; columns wrap; beyond
     * either pole ψ is zero, which is a wall: no water crosses a pole. Colouring by
     * `(column + row)` parity on an even-width cylinder means no two cells of one colour are
     * neighbours, so a colour updates in parallel and in place.
     */
    fun relax(stencil: OceanStencil, stream: FloatArray, passes: Int) {
        val across = stencil.cellsAcross
        val down = stencil.cellsDown
        repeat(passes) {
            for (colour in 0..1) {
                parallelChunks(0, down) { startRow, endRow ->
                    for (row in startRow until endRow) {
                        for (column in 0 until across) {
                            if ((column + row) and 1 != colour) continue
                            val cell = row * across + column
                            stream[cell] = if (stencil.isWater[cell]) updated(stencil, stream, row, column) else 0f
                        }
                    }
                }
            }
        }
    }

    /**
     * The value the balance asks of one water cell given its neighbours, summed in the order both
     * shaders sum it, which is part of what holds them to this answer.
     */
    private fun updated(stencil: OceanStencil, stream: FloatArray, row: Int, column: Int): Float {
        val across = stencil.cellsAcross
        val columnEast = if (column + 1 == across) 0 else column + 1
        val columnWest = if (column == 0) across - 1 else column - 1
        val streamNorth = if (row > 0) stream[(row - 1) * across + column] else 0f
        val streamSouth = if (row + 1 < stencil.cellsDown) stream[(row + 1) * across + column] else 0f
        return stencil.eastWeight[row] * stream[row * across + columnEast] +
            stencil.westWeight[row] * stream[row * across + columnWest] +
            stencil.northWeight[row] * streamNorth + stencil.southWeight[row] * streamSouth -
            stencil.forcing[row * across + column]
    }

    /** What the balance still lacks at every water cell, per second squared; zero on land. */
    fun residual(stencil: OceanStencil, stream: FloatArray): DoubleArray {
        val across = stencil.cellsAcross
        val mismatch = DoubleArray(across * stencil.cellsDown)
        parallelChunks(0, stencil.cellsDown) { startRow, endRow ->
            for (row in startRow until endRow) {
                for (column in 0 until across) {
                    val cell = row * across + column
                    if (!stencil.isWater[cell]) continue
                    // The balance's right-hand side less what ψ makes of it, `F - Lψ`, with the
                    // operator `Lψ = (e ψ_east + w ψ_west + n ψ_north + s ψ_south - ψ) × centre`:
                    // the sign a correction solving `L δ = F - Lψ` must be added with.
                    mismatch[cell] = (stream[cell] - updated(stencil, stream, row, column)).toDouble() *
                        stencil.centreWeight[row]
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

    /**
     * A residual carried to the grid [coarseAcross] by [coarseDown]: each coarse cell takes the
     * mean over the fine cells it covers, which is what a balance per unit area restricts as. The
     * coarse grid is the fine one halved along each axis whose count it halves.
     */
    fun restrict(
        fine: DoubleArray,
        fineAcross: Int,
        fineDown: Int,
        coarseAcross: Int,
        coarseDown: Int
    ): DoubleArray {
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
     * cells, read bilinearly between coarse cell centres: columns wrap, and beyond either pole the
     * correction is the wall's zero.
     */
    fun prolongAdd(
        coarse: FloatArray,
        coarseAcross: Int,
        coarseDown: Int,
        fine: FloatArray,
        fineAcross: Int,
        fineDown: Int,
        isWater: BooleanArray
    ) {
        val columnsPerCoarse = fineAcross / coarseAcross
        val rowsPerCoarse = fineDown / coarseDown
        parallelChunks(0, fineDown) { startRow, endRow ->
            for (row in startRow until endRow) {
                val coarseRow = (row + 0.5f) / rowsPerCoarse - 0.5f
                val above = kotlin.math.floor(coarseRow).toInt()
                val downBlend = coarseRow - above
                for (column in 0 until fineAcross) {
                    val cell = row * fineAcross + column
                    if (!isWater[cell]) continue
                    val coarseColumn = (column + 0.5f) / columnsPerCoarse - 0.5f
                    val left = kotlin.math.floor(coarseColumn).toInt()
                    val acrossBlend = coarseColumn - left
                    val westColumn = (left + coarseAcross) % coarseAcross
                    val eastColumn = (left + 1) % coarseAcross
                    fun at(coarseRowIndex: Int, coarseColumnIndex: Int): Float =
                        if (coarseRowIndex < 0 || coarseRowIndex >= coarseDown) 0f
                        else coarse[coarseRowIndex * coarseAcross + coarseColumnIndex]
                    val top = at(above, westColumn) * (1f - acrossBlend) + at(above, eastColumn) * acrossBlend
                    val bottom = at(above + 1, westColumn) * (1f - acrossBlend) + at(above + 1, eastColumn) * acrossBlend
                    fine[cell] += top * (1f - downBlend) + bottom * downBlend
                }
            }
        }
    }

    /**
     * A coarse grid's water: a coarse cell is water only when every fine cell under it is, so the
     * coarse problem never connects what the fine one keeps apart.
     */
    fun coarseWater(fine: BooleanArray, fineAcross: Int, fineDown: Int, coarseAcross: Int, coarseDown: Int): BooleanArray {
        val columnsPerCoarse = fineAcross / coarseAcross
        val rowsPerCoarse = fineDown / coarseDown
        val coarse = BooleanArray(coarseAcross * coarseDown) { true }
        for (row in 0 until fineDown) {
            for (column in 0 until fineAcross) {
                if (!fine[row * fineAcross + column]) {
                    coarse[(row / rowsPerCoarse) * coarseAcross + column / columnsPerCoarse] = false
                }
            }
        }
        return coarse
    }

    /**
     * The grids of a V-cycle below [finest], finest first, each built by [coarser] from its size
     * and its water.
     *
     * A grid whose cells are twice as wide as they are tall — the map's own at any N by N size — is
     * first halved in rows only, so every grid below it has cells square on the ground; after that
     * both counts are halved together. Coarsening stops at [COARSEST_ROWS], or where a count will
     * not halve evenly, or where the width would lose the even count red-black colouring needs.
     */
    fun levels(
        finest: OceanStencil,
        cellWidthMetres: Double,
        cellHeightMetres: Double,
        coarser: (cellsAcross: Int, cellsDown: Int, isWater: BooleanArray) -> OceanStencil
    ): List<OceanStencil> {
        val levels = arrayListOf(finest)
        var across = finest.cellsAcross
        var down = finest.cellsDown
        var widthMetres = cellWidthMetres
        var heightMetres = cellHeightMetres
        var isWater = finest.isWater
        while (true) {
            val wideCells = widthMetres > heightMetres * SQUARE_ENOUGH
            val tallCells = heightMetres > widthMetres * SQUARE_ENOUGH
            val nextAcross = if (wideCells) across else across / 2
            val nextDown = if (tallCells) down else down / 2
            val halvesEvenly = (nextAcross == across || across % 2 == 0) && (nextDown == down || down % 2 == 0)
            if (!halvesEvenly || nextDown < COARSEST_ROWS || nextAcross % 2 != 0) break
            isWater = coarseWater(isWater, across, down, nextAcross, nextDown)
            widthMetres *= across.toDouble() / nextAcross
            heightMetres *= down.toDouble() / nextDown
            across = nextAcross
            down = nextDown
            levels.add(coarser(across, down, isWater))
        }
        return levels
    }

    /** A stencil's right-hand side back in the balance's own unit, per second squared. */
    fun balanceOf(stencil: OceanStencil): DoubleArray = DoubleArray(stencil.forcing.size) { cell ->
        stencil.forcing[cell] * stencil.centreWeight[cell / stencil.cellsAcross]
    }

    /**
     * V-cycles from ψ of zero on [levels] until the finest grid's residual is under
     * [RESIDUAL_TOLERANCE] of its largest forcing, or [MOST_CYCLES] have run.
     *
     * [relax] runs passes of [relax]'s own arithmetic on a stencil from a start and returns the
     * result; it is how a device takes the relaxation. The residual that decides when to stop is the
     * processor's, whoever relaxed.
     */
    internal inline fun solve(
        levels: List<OceanStencil>,
        relax: (OceanStencil, FloatArray, Int) -> FloatArray
    ): Solution {
        val finest = levels.first()
        var stream = FloatArray(finest.cellsAcross * finest.cellsDown)
        val largestBalance = largest(balanceOf(finest))
        var cycles = 0
        var residual = if (largestBalance > 0.0) Double.MAX_VALUE else 0.0
        while (residual >= RESIDUAL_TOLERANCE && cycles < MOST_CYCLES) {
            stream = vCycle(levels, stream, relax)
            cycles++
            residual = largest(residual(finest, stream)) / largestBalance
        }
        return Solution(stream, cycles, residual)
    }

    /**
     * One V-cycle from [start] on [levels], returning the finest grid's ψ.
     *
     * Down the grids: relax, restrict the residual as the next grid's right-hand side. The coarsest
     * grid is relaxed to convergence on the processor, since it is a few hundred cells. Back up: add
     * the correction, relax again. Every relaxation above the coarsest goes through [relax].
     */
    internal inline fun vCycle(
        levels: List<OceanStencil>,
        start: FloatArray,
        relax: (OceanStencil, FloatArray, Int) -> FloatArray
    ): FloatArray {
        val count = levels.size
        val stencils = arrayOfNulls<OceanStencil>(count)
        val streams = arrayOfNulls<FloatArray>(count)
        stencils[0] = levels[0]
        streams[0] = start
        for (level in 0 until count - 1) {
            val stencil = stencils[level]!!
            streams[level] = relax(stencil, streams[level]!!, PASSES_EACH_WAY)
            val coarse = levels[level + 1]
            val coarseBalance = restrict(
                residual(stencil, streams[level]!!),
                stencil.cellsAcross, stencil.cellsDown, coarse.cellsAcross, coarse.cellsDown
            )
            stencils[level + 1] = withBalance(coarse, coarseBalance)
            streams[level + 1] = FloatArray(coarse.cellsAcross * coarse.cellsDown)
        }
        val coarsest = stencils[count - 1]!!
        if (count > 1) relaxToConvergence(coarsest, streams[count - 1]!!)
        for (level in count - 2 downTo 0) {
            val stencil = stencils[level]!!
            val coarse = stencils[level + 1]!!
            prolongAdd(
                streams[level + 1]!!, coarse.cellsAcross, coarse.cellsDown,
                streams[level]!!, stencil.cellsAcross, stencil.cellsDown, stencil.isWater
            )
            streams[level] = relax(stencil, streams[level]!!, PASSES_EACH_WAY)
        }
        if (count == 1) streams[0] = relax(coarsest, streams[0]!!, PASSES_EACH_WAY)
        return streams[0]!!
    }

    /** Relaxes a small grid until its residual is [COARSEST_TOLERANCE_SHARE] of the tolerance. */
    fun relaxToConvergence(stencil: OceanStencil, stream: FloatArray) {
        val largestBalance = largest(balanceOf(stencil))
        if (largestBalance <= 0.0) return
        var passes = 0
        while (passes < MOST_CYCLES * COARSEST_PASSES_PER_CHECK &&
            largest(residual(stencil, stream)) > largestBalance * RESIDUAL_TOLERANCE * COARSEST_TOLERANCE_SHARE
        ) {
            relax(stencil, stream, COARSEST_PASSES_PER_CHECK)
            passes += COARSEST_PASSES_PER_CHECK
        }
    }

    /**
     * The layer's velocity from ψ by central differences, in metres a second, zero on land: an
     * eastward `-∂ψ/∂y` and a northward `∂ψ/∂x`. Beyond a pole ψ is the wall's zero, as in [relax].
     */
    fun velocities(
        stencil: OceanStencil,
        stream: FloatArray,
        cellWidthMetres: Double,
        cellHeightMetres: Double,
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
                val streamNorth = if (row > 0) stream[(row - 1) * across + column] else 0f
                val streamSouth = if (row + 1 < down) stream[(row + 1) * across + column] else 0f
                eastwardMps[cell] = (-(streamNorth - streamSouth) / (2.0 * cellHeightMetres)).toFloat()
                northwardMps[cell] = ((stream[row * across + columnEast] -
                    stream[row * across + columnWest]) / (2.0 * cellWidthMetres)).toFloat()
            }
        }
    }
}
