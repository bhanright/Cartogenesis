package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A finished world is as isotropic on the ground as Earth is: its coasts run as far north-south as
 * east-west, and its slopes are as steep facing one way as the other.
 *
 * The operator-by-operator guards (`TectonicGroundTest`, `ThermalAspectTest`, `RoutingGroundTest`
 * and the rest) each hold one operator to the ground's ruler on ground made for it. This holds the
 * world they make together, on the four standard worlds at 512, and it is where an operator nobody
 * has thought of yet, counting a row as a column, shows first. Before the ruler was mended every
 * standard world's coastline ran about twice as far east-west as north-south on the ground
 * (`docs/GEOGRAPHY.md`, the land's outlines), which is what land isotropic in cells gives.
 *
 * At [SharedWorlds.DETAIL_ROWS]: a coast's length by bearing and a slope's steepness by aspect are
 * read off the outline and the ground cell by cell, and move with the cell.
 */
class GroundIsotropyTest : BorrowsSharedWorlds() {

    private val seeds = listOf(7L, 42L, 1234L, 99L)

    /**
     * The coastline's east-west and north-south projections on the ground are equal.
     *
     * Every edge between a land cell and a water cell is a piece of coastline: an edge between two
     * cells of one column runs east-west for a cell's width, one between two cells of one row runs
     * north-south for a cell's height. Summed, the two are the coastline's projections on the two
     * axes, and their ratio is 1 for an outline with no preferred direction however ragged it is.
     *
     * Earth's is 0.98 on Natural Earth's 1:50,000,000 coastline and 1.00 on its 1:10,000,000 one,
     * measured on the sphere, so the target is 1. The bar is how far a finite coast scatters about
     * it: a coastline of `L` kilometres whose bearing forgets itself every [REACH_KM] is `n = L /
     * REACH_KM` independent reaches, a sum of `n` randomly oriented reaches projects onto either
     * axis with a relative spread of `sqrt(1/2 - 4/pi^2) / (2/pi) / sqrt(n)`, 0.48 over the root of
     * `n`, and the ratio of the two projections, which move against each other, scatters by `sqrt(2)`
     * times that. Each world is held to three of its own spreads, and the four together to three of
     * theirs. A world isotropic in cells reads 2.
     *
     * It ran as a known failure on the 512 by 512 grid, at 1.13 pooled. Through Fix 3 the cause was
     * the incision's cap per step (Audit III's B-D1); the implicit update removed that cap and the
     * ratio did not follow the notches to 1. On square cells, where every operator that counts a
     * row as a column reaches as far both ways, it reads 1.02 pooled and is armed. See
     * docs/DESIGN_LEDGER.md, Fix 2, Fix 3, Fix 3b and Q2, for the figures.
     */
    @Test
    fun `the coastline runs as far north-south as east-west on the ground`() {
        val ratios = seeds.map { seed ->
            val world = SharedWorlds.world(WorldGenConfig.forRows(seed, SharedWorlds.DETAIL_ROWS))
            val projection = coastProjection(world)
            println(
                "ISOTROPY seed %d: %.0f km of coast, projecting %.0f km east-west and %.0f km north-south, ratio %.3f"
                    .format(seed, projection.lengthKm, projection.eastWestKm, projection.northSouthKm, projection.ratio)
            )
            projection
        }
        val pooled = ratios.sumOf { it.eastWestKm } / ratios.sumOf { it.northSouthKm }
        val pooledBar = SPREADS * ratioSpread(ratios.sumOf { it.lengthKm })
        println("ISOTROPY the four worlds together: ratio %.3f, the log of which may stand %.3f from nothing".format(pooled, pooledBar))
        // Armed on square cells at Q2, where the four worlds read 0.96 to 1.06 and 1.02 together;
        // on the 512 by 512 grid it ran as a known failure at 1.13 (docs/DESIGN_LEDGER.md, Q2).
        val past = ratios.indices.filter { abs(ln(ratios[it].ratio)) > SPREADS * ratioSpread(ratios[it].lengthKm) }
        val found = past.joinToString { String.format(java.util.Locale.ROOT, "seed %d %.2f", seeds[it], ratios[it].ratio) } +
            String.format(java.util.Locale.ROOT, ", together %.2f", pooled)
        // Recorded at K2: on the Earth-sized planet one world's coast projects further one way than
        // its length allows, against Earth's 0.98 to 1.00 (docs/DESIGN_LEDGER.md, K2).
        KnownFailures.expect(
            "K2: a world's coast on the Earth-sized planet projects further one way than its length allows", "seed 99 0.93, together 0.99"
        ) {
            if (past.isNotEmpty() || abs(ln(pooled)) > pooledBar) {
                throw RecordedViolation(
                    "the coasts project more one way than the other on the ground, past what their length allows: $found",
                    found
                )
            }
        }
    }

    /**
     * A finished world's land slopes are as steep facing one way as another on the ground.
     *
     * The fall in metres a kilometre over the same length of ground two ways: from a land cell to
     * the next cell along its row, and to the cell two rows down its column, each a cell width of
     * ground on this map's cells, and the ninety-fifth percentile of each — the steep ground, which
     * is where the thermal sweeps' critical slope and the incision's grade decide the answer. Over
     * the same length, because terrain is rougher at short range than at long and a fall read over
     * a row's height would read steeper than one read over a column's width on land with no
     * preferred direction at all. On land isotropic on the ground the two agree; a sweep that let
     * north- and south-facing slopes stand twice as steep, and an incision that cut a north-south
     * channel half as fast, leave the column's percentile standing above the row's (Audit III's
     * B-D2).
     */
    @Test
    fun `slopes stand as steep facing one way as another on the ground`() {
        val steeper = ArrayList<String>()
        seeds.forEach { seed ->
            val world = SharedWorlds.world(WorldGenConfig.forRows(seed, SharedWorlds.DETAIL_ROWS))
            val falls = steepFalls(world)
            println(
                "ISOTROPY seed %d: 95th percentile of land fall over a cell width of ground, %.1f m/km along a row and %.1f down a column"
                    .format(seed, falls[0], falls[1])
            )
            if (maxOf(falls[0], falls[1]) / minOf(falls[0], falls[1]) > 1.0 + SLOPE_SPREAD) {
                steeper += String.format(java.util.Locale.ROOT, "seed %d %.1f along a row, %.1f down a column", seed, falls[0], falls[1])
            }
        }
        // Armed again at Fix 3b: under the capped explicit update seed 99's steep ground read 14.9
        // m/km along a row and 18.0 down a column; with the cap gone it reads 25.9 and 26.7.
        assertTrue(
            steeper.isEmpty(),
            "the steep ground falls further down a column than along a row, past a fifth: " + steeper.joinToString()
        )
    }

    /**
     * The spread of the log of a coast's projection ratio, for a coast whose two projections add up
     * to [projectedKm]: see the case. The sum of a curve's projections is its length times `4/pi`
     * on average over bearings, so that is taken off before the reaches are counted.
     */
    private fun ratioSpread(projectedKm: Double): Double {
        val reaches = projectedKm * PI / 4.0 / REACH_KM
        val projectionSpread = sqrt(0.5 - 4.0 / (PI * PI)) / (2.0 / PI)
        return sqrt(2.0) * projectionSpread / sqrt(reaches)
    }

    private class Projection(val eastWestKm: Double, val northSouthKm: Double) {
        val ratio: Double get() = eastWestKm / northSouthKm
        val lengthKm: Double get() = eastWestKm + northSouthKm
    }

    private fun coastProjection(world: WorldMap): Projection {
        val w = world.width
        val h = world.height
        val land = world.sea.isLand
        var eastWestEdges = 0L
        var northSouthEdges = 0L
        for (row in 0 until h) {
            for (column in 0 until w) {
                val cell = row * w + column
                if (land[cell] != land[row * w + (column + 1) % w]) northSouthEdges++
                if (row + 1 < h && land[cell] != land[cell + w]) eastWestEdges++
            }
        }
        return Projection(eastWestEdges * world.config.cellWidthKm, northSouthEdges * world.config.cellHeightKm)
    }

    /**
     * The 95th percentile of the fall per kilometre over land, along a row and down a column, each
     * over one cell width of ground: one column, or as many rows as make the same kilometres.
     */
    private fun steepFalls(world: WorldMap): DoubleArray {
        val w = world.width
        val h = world.height
        val land = world.sea.isLand
        val metres = world.config.scale.highestLandMetres.toDouble()
        val elevation = world.sea.relativeElevation.data
        val cw = world.config.cellWidthKm
        val rowsPerCellWidth = kotlin.math.round(1.0 / world.config.cellHeightInCellWidths).toInt()
        val steps = listOf(Triple(1, 0, cw), Triple(0, rowsPerCellWidth, rowsPerCellWidth * world.config.cellHeightKm))
        return DoubleArray(2) { kind ->
            val (columnStep, rowStep, km) = steps[kind]
            val falls = ArrayList<Double>()
            for (row in 0 until h - rowStep) {
                for (column in 0 until w) {
                    val cell = row * w + column
                    val next = (row + rowStep) * w + (column + columnStep) % w
                    if (!land[cell] || !land[next]) continue
                    falls.add(abs(elevation[next] - elevation[cell]) * metres / km)
                }
            }
            falls.sort()
            falls[(falls.size * STEEP_PERCENTILE).toInt()]
        }
    }

    private companion object {
        /**
         * The length of coast over which a reach's bearing forgets the last one's: 300 km, about
         * half the wavelength of the broadest noise that shapes a coast inside its margin (the
         * margin's own, 24 cycles round a 12,000 km world, 500 km) and three quarters of the relief
         * band's corner, `TerrainConfig.reliefCornerKm`, 400 km.
         */
        const val REACH_KM = 300.0

        /** How many of its own spreads a coast's ratio may stand from 1. */
        const val SPREADS = 3.0

        /** The steep ground the slope case reads. */
        const val STEEP_PERCENTILE = 0.95

        /**
         * How far apart the two steep falls may be: a fifth. Land isotropic on the ground reads the
         * two at one gradient to within the scatter of a few thousand steep cells apiece, a few
         * percent; the square-celled sweep's north- and south-facing slopes stood at up to twice the
         * critical gradient.
         */
        const val SLOPE_SPREAD = 0.20
    }
}
