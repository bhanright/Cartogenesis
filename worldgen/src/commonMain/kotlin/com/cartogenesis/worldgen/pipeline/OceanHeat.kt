package com.cartogenesis.worldgen.pipeline

import com.cartogenesis.worldgen.concurrent.parallelChunks
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The sea-surface temperature the currents carry: the steady state of
 * `∂T/∂t + u·∇T = -(T - T_latitude) / τ`, solved along each water parcel's path.
 *
 * With a steady current and a target that depends on latitude alone, that equation has a closed
 * answer on its characteristics. Follow the water at a cell backward in time along the current;
 * the temperature there is the latitude temperature the parcel passed through, each moment of its
 * past weighted by how much of it the relaxation has not yet erased:
 *
 * `T(x) = ∫₀^∞ (1/τ) e^(-s/τ) T_latitude(X(x, -s)) ds`
 *
 * So the steady state is computed rather than iterated to: no pass count, no convergence test on a
 * whole grid, and no numerical diffusion from resampling the field every step — each cell reads
 * only the velocity and the latitude profile. It is the semi-Lagrangian scheme taken to its steady
 * limit: the departure points are the parcel's own path, sampled bilinearly, at whatever speed the
 * water moves, so water moving a tenth of a cell a day carries its heat a tenth of a cell a day in
 * every direction alike.
 *
 * **The path.** Midpoint (second-order Runge-Kutta) steps backward along the bilinearly sampled
 * velocity. A step moves at most [STEP_CELLS] of a cell along either axis, each axis in its own
 * cells, and lasts at most [STEPS_PER_RELAXATION_TIME]'s share of τ. Half a cell, so that a step
 * can never pass over a whole cell without landing in it, which is what keeps a parcel from
 * crossing a land barrier one cell wide.
 *
 * **The coast.** The velocity is zero on land and the bilinear sample blends it in, so water
 * slows as it nears a coast. A path whose step lands on land stops there, and the weight it has
 * left is given the latitude temperature where it stopped: water that arrives from the coast
 * carries its own latitude's heat and nothing from beyond it. That is the coastal boundary
 * condition, stated.
 *
 * **Where it stops.** When the weight left, `e^(-s/τ)`, times the widest contrast the latitude
 * profile holds, falls below [TAIL_TOLERANCE_C]; the tail is then given the latitude temperature
 * where the parcel is. That is a length of history derived from the answer's own precision.
 */
object OceanHeat {

    /** The furthest one step may move a parcel along either axis, in that axis's cells. */
    const val STEP_CELLS = 0.5f

    /**
     * The longest one step may last, as a share of τ: an eighth. For water so slow that half a
     * cell takes longer, the latitude temperature along the step is read at its midpoint, and an
     * eighth of τ keeps the step's weight, `1 - e^(-1/8)`, under 12% of what is left.
     */
    const val STEPS_PER_RELAXATION_TIME = 8f

    /**
     * How far from its converged value the truncated tail may leave a cell, in degrees Celsius: a
     * hundredth, below every figure the anomaly is reported to.
     */
    const val TAIL_TOLERANCE_C = 0.01f

    /**
     * Carries heat on one grid and returns the temperature of every water cell, in degrees
     * Celsius; land cells read the latitude temperature of their row.
     *
     * [eastwardMps] and [northwardMps] are the current per cell, zero on land. [latitudeRowC] is
     * the target temperature at each row's centre, read between rows linearly. [relaxationSeconds]
     * is τ. [stepCells] and [stepsPerRelaxationTime] are this object's constants except where the
     * accuracy guard halves them.
     */
    fun carry(
        cellsAcross: Int,
        cellsDown: Int,
        cellWidthMetres: Double,
        cellHeightMetres: Double,
        isWater: BooleanArray,
        eastwardMps: FloatArray,
        northwardMps: FloatArray,
        latitudeRowC: FloatArray,
        relaxationSeconds: Double,
        stepCells: Float = STEP_CELLS,
        stepsPerRelaxationTime: Float = STEPS_PER_RELAXATION_TIME
    ): FloatArray {
        val temperature = FloatArray(cellsAcross * cellsDown)
        var coldest = Float.MAX_VALUE
        var warmest = -Float.MAX_VALUE
        for (value in latitudeRowC) { coldest = min(coldest, value); warmest = max(warmest, value) }
        val contrastC = max(warmest - coldest, TAIL_TOLERANCE_C)
        val tailWeight = TAIL_TOLERANCE_C / contrastC

        // Everything a path reads is in cells and seconds from here: the velocity as cells a
        // second along each axis, so a step's length on either axis is one product.
        val columnsPerSecond = FloatArray(eastwardMps.size) { (eastwardMps[it] / cellWidthMetres).toFloat() }
        val rowsSouthPerSecond = FloatArray(northwardMps.size) { (-northwardMps[it] / cellHeightMetres).toFloat() }
        val tau = relaxationSeconds.toFloat()
        val longestStepSeconds = tau / stepsPerRelaxationTime

        parallelChunks(0, cellsDown) { startRow, endRow ->
            for (row in startRow until endRow) {
                for (column in 0 until cellsAcross) {
                    val cell = row * cellsAcross + column
                    if (!isWater[cell]) {
                        temperature[cell] = latitudeRowC[row]
                        continue
                    }
                    temperature[cell] = followPath(
                        cellsAcross, cellsDown, isWater, columnsPerSecond, rowsSouthPerSecond,
                        latitudeRowC, column.toFloat(), row.toFloat(), tau, longestStepSeconds,
                        stepCells, tailWeight
                    )
                }
            }
        }
        return temperature
    }

    private fun followPath(
        cellsAcross: Int,
        cellsDown: Int,
        isWater: BooleanArray,
        columnsPerSecond: FloatArray,
        rowsSouthPerSecond: FloatArray,
        latitudeRowC: FloatArray,
        startColumn: Float,
        startRow: Float,
        tau: Float,
        longestStepSeconds: Float,
        stepCells: Float,
        tailWeight: Float
    ): Float {
        var column = startColumn
        var row = startRow
        var weightLeft = 1f
        var temperatureC = 0f
        while (weightLeft > tailWeight) {
            val speedAcross = sample(columnsPerSecond, cellsAcross, cellsDown, column, row)
            val speedDown = sample(rowsSouthPerSecond, cellsAcross, cellsDown, column, row)
            val seconds = stepSeconds(speedAcross, speedDown, stepCells, longestStepSeconds)
            val midColumn = column - speedAcross * seconds * 0.5f
            val midRow = row - speedDown * seconds * 0.5f
            val midAcross = sample(columnsPerSecond, cellsAcross, cellsDown, midColumn, midRow)
            val midDown = sample(rowsSouthPerSecond, cellsAcross, cellsDown, midColumn, midRow)
            // The midpoint's speed may be faster than the start's; the step is re-limited by it so
            // no step moves more than [stepCells] however the current changes along it.
            val limited = min(seconds, stepSeconds(midAcross, midDown, stepCells, longestStepSeconds))
            val nextColumn = column - midAcross * limited
            val nextRow = row - midDown * limited
            if (!isWater[cellAt(cellsAcross, cellsDown, nextColumn, nextRow)]) break
            val stepWeight = weightLeft * (1f - exp(-limited / tau))
            temperatureC += stepWeight * latitudeC(latitudeRowC, (row + nextRow) * 0.5f)
            weightLeft -= stepWeight
            column = wrap(nextColumn, cellsAcross)
            row = nextRow
        }
        return temperatureC + weightLeft * latitudeC(latitudeRowC, row)
    }

    private fun stepSeconds(speedAcross: Float, speedDown: Float, stepCells: Float, longest: Float): Float {
        val fastest = max(abs(speedAcross), abs(speedDown))
        return if (fastest * longest <= stepCells) longest else stepCells / fastest
    }

    /** The target temperature at a fractional row, linearly between row centres, flat beyond them. */
    private fun latitudeC(latitudeRowC: FloatArray, row: Float): Float {
        val last = latitudeRowC.size - 1
        if (row <= 0f) return latitudeRowC[0]
        if (row >= last) return latitudeRowC[last]
        val above = row.toInt()
        val blend = row - above
        return latitudeRowC[above] + (latitudeRowC[above + 1] - latitudeRowC[above]) * blend
    }

    private fun wrap(column: Float, cellsAcross: Int): Float {
        var wrapped = column
        if (wrapped < -0.5f) wrapped += cellsAcross
        if (wrapped >= cellsAcross - 0.5f) wrapped -= cellsAcross
        return wrapped
    }

    /** The cell whose area holds a fractional position: nearest centre, columns wrapped, rows clamped. */
    private fun cellAt(cellsAcross: Int, cellsDown: Int, column: Float, row: Float): Int {
        var whole = floor(column + 0.5f).toInt() % cellsAcross
        if (whole < 0) whole += cellsAcross
        val wholeRow = floor(row + 0.5f).toInt().coerceIn(0, cellsDown - 1)
        return wholeRow * cellsAcross + whole
    }

    /** A per-cell field bilinearly at a fractional position between cell centres; columns wrap, rows clamp. */
    internal fun sample(field: FloatArray, cellsAcross: Int, cellsDown: Int, column: Float, row: Float): Float {
        val left = floor(column).toInt()
        val across = column - left
        var westColumn = left % cellsAcross
        if (westColumn < 0) westColumn += cellsAcross
        val eastColumn = if (westColumn + 1 == cellsAcross) 0 else westColumn + 1
        val clampedRow = row.coerceIn(0f, (cellsDown - 1).toFloat())
        val above = min(clampedRow.toInt(), cellsDown - 1)
        val below = min(above + 1, cellsDown - 1)
        val down = clampedRow - above
        val top = field[above * cellsAcross + westColumn] * (1f - across) +
            field[above * cellsAcross + eastColumn] * across
        val bottom = field[below * cellsAcross + westColumn] * (1f - across) +
            field[below * cellsAcross + eastColumn] * across
        return top * (1f - down) + bottom * down
    }

    /** The most steps any one path can take, from the fastest water and the tail's length: a bound for a device's loop. */
    fun stepBound(fastestCellsPerSecond: Float, tau: Float, tailWeight: Float, stepCells: Float, stepsPerRelaxationTime: Float): Int {
        val historySeconds = tau * kotlin.math.ln(1f / tailWeight)
        return ceil(historySeconds * max(fastestCellsPerSecond / stepCells, stepsPerRelaxationTime / tau)).toInt() + 1
    }
}
