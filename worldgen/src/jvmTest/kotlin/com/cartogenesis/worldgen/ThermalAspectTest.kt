package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ErosionStage
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Rock settles to the same critical slope on the ground whichever way it faces.
 *
 * `ErosionConfig.criticalFallMetresPerKm` is a gradient: past sixty metres of fall a kilometre the
 * thermal sweeps move material off a cell until the slope comes down to it. A step to a neighbour
 * north or south is half as long on the ground as one east or west on this map's cells, so the drop
 * a neighbour may hold is half as large there; a sweep that allowed the east-west drop to every
 * orthogonal neighbour left north- and south-facing slopes standing at twice the critical gradient
 * (Audit III's B-D2).
 *
 * A mesa round on the ground and taller than the critical slope lets any wall stand is swept until
 * it settles, and the steepest fall left between neighbours is read off each kind of step in metres
 * a kilometre: along a row, down a column and on the diagonal. None stands past the critical slope
 * and each has settled against it. The three need not be the same figure: a wall of a given height
 * settles as a staircase whose top step is whatever is left over the whole limits below it, and the
 * limits are different drops on the three kinds of step, so each kind's steepest step sits its own
 * way inside the limit.
 */
class ThermalAspectTest {

    @Test
    fun `a settled slope stands at the critical gradient whichever way it faces`() {
        val config = WorldGenConfig(seed = 1L, width = SIDE, height = SIDE)
        val span = config.scale.reliefSpanMetres
        val cellWidthKm = config.cellWidthKm
        val cellHeightKm = config.cellHeightKm
        val mesa = FloatField.of(SIDE, SIDE) { column, row ->
            val eastKm = (column - SIDE / 2) * cellWidthKm
            val southKm = (row - SIDE / 2) * cellHeightKm
            val onTop = sqrt(eastKm * eastKm + southKm * southKm) < MESA_RADIUS_KM
            (BASE_METRES + if (onTop) MESA_METRES else 0.0).toFloat() / span
        }
        val settled = runBlocking {
            ErosionStage.thermalSweep(config, mesa, skipSettled = true, sweeps = SWEEPS)
        }.height

        var eastWest = 0.0
        var northSouth = 0.0
        var diagonal = 0.0
        val diagonalKm = sqrt(cellWidthKm * cellWidthKm + cellHeightKm * cellHeightKm)
        for (row in 1 until SIDE - 1) {
            for (column in 1 until SIDE - 1) {
                val here = settled[column, row].toDouble() * span
                eastWest = maxOf(eastWest, abs(settled[column + 1, row] * span - here) / cellWidthKm)
                northSouth = maxOf(northSouth, abs(settled[column, row + 1] * span - here) / cellHeightKm)
                diagonal = maxOf(diagonal, abs(settled[column + 1, row + 1] * span - here) / diagonalKm)
            }
        }
        val critical = config.erosion.criticalFallMetresPerKm.toDouble()
        println(
            "THERMAL after $SWEEPS sweeps the steepest fall left is %.1f m/km along a row, %.1f down a column and %.1f on a diagonal, against %.0f critical"
                .format(eastWest, northSouth, diagonal, critical)
        )
        listOf("along a row" to eastWest, "down a column" to northSouth, "on a diagonal" to diagonal).forEach { (step, fall) ->
            assertTrue(
                fall <= critical * (1.0 + SETTLED_EXCESS) && fall >= critical * SETTLED_AGAINST_THE_LIMIT,
                "the steepest settled fall $step is ${"%.1f".format(fall)} m/km against the critical ${critical.toInt()}"
            )
        }
    }

    private companion object {
        /**
         * A 256 grid: cells 46.9 km across and 23.4 km down, so the critical slope allows a drop of
         * 2,812 m to a neighbour east or west and 1,406 m to one north or south.
         */
        const val SIDE = 256

        /** The mesa: 6,000 m above its plain, which no wall of one cell can hold either way. */
        const val MESA_METRES = 6_000.0
        const val MESA_RADIUS_KM = 1_200.0

        /** The plain, well above the floor of the height field. */
        const val BASE_METRES = 9_000.0

        /** Enough sweeps for the walls to settle: each moves a share of the excess a cell. */
        const val SWEEPS = 1_500

        /**
         * How far past the critical slope a settled fall may stand: a hundredth. A cell stops
         * handing material on once its whole excess over every neighbour is a thousandth of the
         * limit, so no single step stands further past it than that and its rounding; the
         * square-celled sweep left north- and south-facing falls at twice the limit.
         */
        const val SETTLED_EXCESS = 0.01

        /**
         * How far below the critical slope the steepest settled fall may stand: three quarters of
         * it. Each sweep hands on a share of the excess and no more than half the steepest drop, so
         * a wall relaxes to a staircase a little inside the limit rather than onto it — measured at
         * 0.85 of it along a row — and a reading far below would mean the walls had been flattened
         * rather than settled.
         */
        const val SETTLED_AGAINST_THE_LIMIT = 0.75

    }
}
