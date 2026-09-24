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
     * It fails today, on a defect the ruler does not reach, and runs as a known failure under it.
     * With every operator on the ground's ruler the plate stage's own coast reads 1.09 and 0.91 on
     * seeds 42 and 7 and the thermal sweeps leave it there, but the twelve hydraulic rounds take
     * the erosion stage's own land to 1.56 and 1.48: the incision is capped at half the drop to a
     * cell's receiver in a round, a drop is in proportion to the step's length on the ground, and a
     * step down a column is half as long as one along a row, so wherever the cap and not the
     * stream-power law sets the cut — most channels, by Audit III's B-D1 — a channel running
     * north-south is cut half as deep a round as one running east-west on the same slope, and the
     * valleys the coast is notched by run east-west. Weakening the law until the cap stops binding
     * takes the ratio to 1.14 and 1.04 at a tenth of its strength and to 1.05 and 0.91 at three
     * hundredths, on seeds 42 and 7. The erosion's units, which set how often the cap binds, are
     * the next chunk's; see docs/DESIGN_LEDGER.md, Fix 2.
     */
    @Test
    fun `the coastline runs as far north-south as east-west on the ground`() {
        val ratios = seeds.map { seed ->
            val world = SharedWorlds.world(WorldGenConfig(seed = seed, width = 512, height = 512))
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
        KnownFailures.expect(
            INCISION_CAPPED_PER_STEP,
            "seed 7 1.32, seed 42 1.41, seed 1234 1.41, seed 99 1.41, together 1.39"
        ) {
            val past = ratios.indices.filter { abs(ln(ratios[it].ratio)) > SPREADS * ratioSpread(ratios[it].lengthKm) }
            if (past.isNotEmpty() || abs(ln(pooled)) > pooledBar) {
                val found = past.joinToString { String.format(java.util.Locale.ROOT, "seed %d %.2f", seeds[it], ratios[it].ratio) } +
                    String.format(java.util.Locale.ROOT, ", together %.2f", pooled)
                throw RecordedViolation(
                    "the coasts project more east-west than north-south on the ground, past what their length allows: $found",
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
        seeds.forEach { seed ->
            val world = SharedWorlds.world(WorldGenConfig(seed = seed, width = 512, height = 512))
            val falls = steepFalls(world)
            println(
                "ISOTROPY seed %d: 95th percentile of land fall over a cell width of ground, %.1f m/km along a row and %.1f down a column"
                    .format(seed, falls[0], falls[1])
            )
            assertTrue(
                maxOf(falls[0], falls[1]) / minOf(falls[0], falls[1]) <= 1.0 + SLOPE_SPREAD,
                "seed $seed: the steep ground falls ${"%.1f".format(falls[0])} m/km along a row and " +
                    "${"%.1f".format(falls[1])} down a column"
            )
        }
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

        /** The known failure the coastline clause records. */
        const val INCISION_CAPPED_PER_STEP =
            "Audit III B-D1: the incision's cap sets the cut, and a cap per step cuts a north-south channel half as deep a round"


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
