package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.math.GaussianBlur
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ChannelInitiation
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Whether the channels on steep ground follow the ground or the grid: how often a channel's course
 * takes one of the grid's bearings, against how often the fall line of the ground it crosses does.
 *
 * `CombCensus` counts straight parallel reaches along an axis, which is the comb the eye sees; this
 * asks the question under it. For every channel cell on steep ground, the bearing of the channel's
 * next [REACH_KM] downstream, start to end on the ground, is set beside the bearing of the fall line
 * of the ground smoothed over [FALL_LINE_SMOOTHING_KM] at the same cell. Each is folded into 0 to
 * 90 degrees off east-west and counted within [BIN_HALF_WIDTH_DEGREES] of a grid bearing: the row,
 * the column and the grid's own diagonal, which is 45 degrees on square cells and 26.6 on cells half
 * as tall as they are wide. A channel that follows the ground takes a grid bearing as often as the
 * fall line does. A uniform bearing puts 2.8% of the cells in each axis's bin, which is half a bin
 * since the fold ends there, and 5.6% in the diagonal's.
 *
 * A reach of few cells has few bearings open to it, so part of any excess over the fall line is
 * the lattice and shrinks as the cells per reach grow; the fair comparison is between grids at equal
 * cells per reach. The design of the square-grid switch took it first (docs/DESIGN_LEDGER.md, Q2).
 */
internal object BearingCensus {

    /** One grid bearing's share, of the channel's courses and of the fall line's, in percent. */
    class Bin(val name: String, val channelPercent: Double, val fallLinePercent: Double) {
        override fun toString(): String = "%s %.1f/%.1f".format(name, channelPercent, fallLinePercent)
    }

    class Result(val steepCells: Int, val bins: List<Bin>) {
        override fun toString(): String = "$steepCells steep channel cells, channel/fall line: ${bins.joinToString(" · ")}"
    }

    fun of(world: WorldMap): Result {
        val config = world.config
        val cellsAcross = world.width
        val cellsDown = world.height
        val cellWidthKm = config.cellWidthKm
        val cellHeightKm = config.cellHeightKm
        val steps = config.groundSteps
        val scale = config.scale
        val isLand = world.sea.isLand
        val target = world.rivers.flowTarget
        val channel = ChannelInitiation.channelMaskOf(world)
        val metres = FloatField(cellsAcross, cellsDown)
        for (cell in metres.data.indices) {
            metres.data[cell] = scale.metresAboveShoreline(world.sea.relativeElevation.data[cell])
        }
        val smoothed = metres.copy()
        GaussianBlur.apply(smoothed, FALL_LINE_SMOOTHING_KM / cellWidthKm, FALL_LINE_SMOOTHING_KM / cellHeightKm)

        val diagonalDegrees = atan(cellHeightKm / cellWidthKm) * 180.0 / PI
        val gridBearings = listOf("E-W" to 0.0, "diagonal" to diagonalDegrees, "N-S" to 90.0)
        val channelCounts = IntArray(gridBearings.size)
        val fallCounts = IntArray(gridBearings.size)
        var steepCells = 0
        for (cell in isLand.indices) {
            if (!isLand[cell] || !channel[cell]) continue
            val receiver = target[cell]
            if (receiver < 0 || !isLand[receiver]) continue
            val stepKm = steps.between(cell, receiver, cellsAcross) * cellWidthKm
            if ((metres.data[cell] - metres.data[receiver]) / stepKm <= STEEP_METRES_PER_KM) continue

            // The course's next reach, walked on the ground; one that reaches the sea first is too
            // short to have a bearing.
            var eastKm = 0.0
            var southKm = 0.0
            var walkedKm = 0.0
            var at = cell
            while (walkedKm < REACH_KM) {
                val next = target[at]
                if (next < 0 || !isLand[next]) break
                var columns = next % cellsAcross - at % cellsAcross
                if (columns > cellsAcross / 2) columns -= cellsAcross
                if (columns < -cellsAcross / 2) columns += cellsAcross
                eastKm += columns * cellWidthKm
                southKm += (next / cellsAcross - at / cellsAcross) * cellHeightKm
                walkedKm += steps.between(at, next, cellsAcross) * cellWidthKm
                at = next
            }
            if (walkedKm < REACH_KM) continue

            val column = cell % cellsAcross
            val row = cell / cellsAcross
            val east = (column + 1) % cellsAcross
            val west = (column - 1 + cellsAcross) % cellsAcross
            val north = (row - 1).coerceAtLeast(0)
            val south = (row + 1).coerceAtMost(cellsDown - 1)
            val downEastward = (smoothed[west, row] - smoothed[east, row]) / (2.0 * cellWidthKm)
            val downSouthward = (smoothed[column, north] - smoothed[column, south]) / ((south - north) * cellHeightKm)
            if (hypot(downEastward, downSouthward) == 0.0) continue
            steepCells++
            count(folded(eastKm, southKm), gridBearings, channelCounts)
            count(folded(downEastward, downSouthward), gridBearings, fallCounts)
        }
        val bins = gridBearings.mapIndexed { index, (name, _) ->
            Bin(name, 100.0 * channelCounts[index] / steepCells.coerceAtLeast(1), 100.0 * fallCounts[index] / steepCells.coerceAtLeast(1))
        }
        return Result(steepCells, bins)
    }

    /** The bearing of a vector off east-west, folded into 0 to 90 degrees. */
    private fun folded(east: Double, south: Double): Double = atan2(abs(south), abs(east)) * 180.0 / PI

    private fun count(degrees: Double, gridBearings: List<Pair<String, Double>>, counts: IntArray) {
        gridBearings.forEachIndexed { index, (_, bearing) ->
            if (abs(degrees - bearing) <= BIN_HALF_WIDTH_DEGREES) counts[index]++
        }
    }

    /** How far down its course a channel's bearing is read, on the ground. */
    const val REACH_KM = 60.0

    /** How far the ground is smoothed before its fall line is read: past the cell's own relief. */
    const val FALL_LINE_SMOOTHING_KM = 30.0

    /** A bin's half-width round each grid bearing. */
    const val BIN_HALF_WIDTH_DEGREES = 2.5

    /** Steep ground: a fall to the receiver over 20 m a kilometer, the comb's own ground. */
    const val STEEP_METRES_PER_KM = 20.0
}
