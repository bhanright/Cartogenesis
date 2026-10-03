package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.FlowRouting
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The flanks are not combed by straight parallel gullies, down the columns or along the rows.
 *
 * What the eye saw on the renders of chunk 3b (seed 7's upper-right range at 1024, 969495's eastern
 * flank at 2048) was channel after channel running dead straight along one grid axis, a cell or two
 * apart, with a ridge between each pair. So the census counts exactly that, in [CombCensus]: channel
 * cells in a straight reach along one axis at least [SUSTAINED_KM] long, with a second such reach
 * running the same way [NEAREST_KM] to [FURTHEST_KM] across it and a ridge at least [RIDGE_METRES]
 * above both standing between them. Down a column and along a row alike, at the same kilometers on
 * the ground.
 *
 * **On square cells, since Q2.** On cells half as tall as they are wide the columns carried six to
 * seven times the rows' comb (0.41 and 0.52 km per 1,000 km² of land against 0.06 and 0.08 on seeds
 * 7 and 42), which was the defect the square grid was built to remove, and this guard was its
 * acceptance test. It is taken now on the square grid of 512 rows, where it asks two things.
 *
 * **Neither axis carries much more comb than the other.** Two-sided, the larger against the smaller,
 * with the bar from the control: an isotropic synthetic surface over the same land, routed by
 * production's router with every land cell a channel, whose parallel sustained reaches run 1.00 and
 * 1.30 times as often along one axis as the other on seeds 7 and 42 on square cells; so the larger
 * axis may carry up to [AXIS_FACTOR] times the smaller's comb, or [COMB_FLOOR_KM_PER_1000_KM2]
 * whatever the smaller carries. Armed: the square cells read 1.10 and 1.09, and the same clause
 * fails the half-height cell at 6.8 and 6.6 (the second case).
 *
 * **Neither axis carries more comb than the router makes on its own.** The same control's parallel
 * reaches with a [RIDGE_METRES] ridge between them come to 0.0068 to 0.0201 km per 1,000 km², so
 * [COMB_FLOOR_KM_PER_1000_KM2] is the most either axis may carry. The worlds carry seven to ten
 * times it on both axes, a comb no longer one-sided but not gone, and that runs as a known failure,
 * [SYMMETRIC_COMB], with the bearing census ([BearingCensus]) printed beside it as its second
 * instrument.
 *
 * **Held with the network,** because a comb can be removed by removing channels. The channel
 * network the criterion initiates may not thin below its figure on the head this guard was written
 * on by more than `ScaleFreeTest`'s 1.35, the most it may move between grids.
 */
class CombGuardTest : BorrowsSharedWorlds() {

    /** One seed's census, and the two clauses' complaints about it. */
    private class Seed(val seed: Long, val census: CombCensus.Result) {
        val column = census.column.combedKmPer1000Km2
        val row = census.row.combedKmPer1000Km2
        val larger = maxOf(column, row)
        val smaller = minOf(column, row)
        val oneSided = larger > maxOf(AXIS_FACTOR * smaller, COMB_FLOOR_KM_PER_1000_KM2)
        val combed = larger > COMB_FLOOR_KM_PER_1000_KM2
        val figure = String.format(Locale.ROOT, "seed %d %.2f down a column and %.2f along a row", seed, column, row)
    }

    private fun measure(config: WorldGenConfig): Seed {
        val world = SharedWorlds.world(config)
        val census = CombCensus.of(world, SUSTAINED_KM, NEAREST_KM, FURTHEST_KM, RIDGE_METRES)
        println(
            String.format(
                Locale.ROOT,
                "COMB seed %d@%dx%d: channel %.2f km per 1000 km2; sustained %.3f down a column and %.3f along a row; combed %.3f and %.3f",
                config.seed, config.width, config.height, census.channelKmPer1000Km2, census.column.sustainedKmPer1000Km2,
                census.row.sustainedKmPer1000Km2, census.column.combedKmPer1000Km2, census.row.combedKmPer1000Km2
            )
        )
        return Seed(config.seed, census)
    }

    @Test
    fun `the flanks are not combed along one axis more than the other, nor past the router's own`() {
        val seeds = NETWORK_ON_THE_HEAD.map { (seed, networkFloor) ->
            val measured = measure(WorldGenConfig.forRows(seed, STANDARD_ROWS))
            assertTrue(
                measured.census.channelKmPer1000Km2 >= networkFloor / NETWORK_FACTOR,
                "seed $seed's network thinned to ${measured.census.channelKmPer1000Km2} km per 1000 km2 from " +
                    "$networkFloor, past ScaleFreeTest's $NETWORK_FACTOR: a comb removed by removing channels"
            )
            measured
        }
        val oneSided = seeds.filter { it.oneSided }
        assertTrue(
            oneSided.isEmpty(),
            "the flanks are combed along one axis over $AXIS_FACTOR times the other, in km of comb per 1000 km2 of " +
                "land: ${oneSided.joinToString("; ") { it.figure }}"
        )
        val bearings = WORLD_SEEDS.map { "seed $it ${BearingCensus.of(SharedWorlds.world(WorldGenConfig.forRows(it, STANDARD_ROWS)))}" }
        bearings.forEach { println("COMB BEARINGS $it") }
        KnownFailures.expect(SYMMETRIC_COMB, RECORDED) {
            val combed = seeds.filter { it.combed }
            if (combed.isNotEmpty()) {
                throw RecordedViolation(
                    "the flanks carry more comb on each axis than the router makes on isotropic ground, " +
                        "$COMB_FLOOR_KM_PER_1000_KM2 km per 1000 km2: ${combed.joinToString("; ") { it.figure }}\n" +
                        bearings.joinToString("\n") { "bearings: $it" },
                    combed.joinToString("; ") { it.figure }
                )
            }
        }
    }

    /**
     * The control for the first clause: the half-height cell's comb, down the columns, which the
     * square grid was built to remove, is one-sided past [AXIS_FACTOR] on both seeds.
     */
    @Test
    fun `the comparison sees the half-height cell's comb down the columns`() {
        val halfHeight = WORLD_SEEDS.map { measure(WorldGenConfig(seed = it, width = 512, height = 512)) }
        assertTrue(
            halfHeight.all { it.oneSided && it.column > it.row },
            "the 512 by 512 worlds' comb down the columns was not seen: ${halfHeight.joinToString("; ") { it.figure }}"
        )
    }

    /**
     * The control for both clauses, the other way: isotropic ground routed by production's router
     * passes the guard. An isotropic synthetic surface over each world's own land at 512 rows,
     * every land cell a channel, is combed by the router alone; neither axis may carry more than
     * [AXIS_FACTOR] times the other nor more than [COMB_FLOOR_KM_PER_1000_KM2], which is where
     * both bars were taken from. A guard that failed here would be failing the router's own
     * geometry and not a defect of the flanks.
     */
    @Test
    fun `isotropic ground routed by the router passes the guard`() {
        val controls = WORLD_SEEDS.map { seed ->
            val config = WorldGenConfig.forRows(seed, STANDARD_ROWS)
            val isLand = SharedWorlds.world(config).sea.isLand
            val metres = isotropicMetres(config, seed)
            val field = FloatField(
                config.width, config.height,
                FloatArray(metres.size) { (metres[it] / SYNTHETIC_FIELD_METRES + 0.5).toFloat() }
            )
            val filled = FlowRouting.fillDepressions(config.width, config.height, isLand, field)
            val target = FlowRouting.flowDirections(
                config.width, config.height, isLand, field, filled, config.seed,
                config.cellHeightInCellWidths, FlowRouting.smoothFieldPeriodCells(config)
            )
            val census = CombCensus.of(
                config, isLand, isLand, target, metres, SUSTAINED_KM, NEAREST_KM, FURTHEST_KM, RIDGE_METRES
            )
            val sustained = CombCensus.of(config, isLand, isLand, target, metres, SUSTAINED_KM, NEAREST_KM, FURTHEST_KM, 0.0)
            println(
                String.format(
                    Locale.ROOT,
                    "COMB CONTROL seed %d isotropic: parallel sustained %.4f down a column and %.4f along a row; combed %.4f and %.4f",
                    seed, sustained.column.combedKmPer1000Km2, sustained.row.combedKmPer1000Km2,
                    census.column.combedKmPer1000Km2, census.row.combedKmPer1000Km2
                )
            )
            Seed(seed, census)
        }
        assertTrue(
            controls.none { it.oneSided || it.combed },
            "the guard fails isotropic ground the router routed: ${controls.joinToString("; ") { it.figure }}"
        )
    }

    /**
     * An isotropic surface on the ground, in meters: [SYNTHETIC_COMPONENTS] cosines at uniform
     * bearings and wavelengths log-uniform over [SYNTHETIC_SHORTEST_KM] to [SYNTHETIC_LONGEST_KM],
     * each as steep as the others, so the whole stands at an rms slope of [SYNTHETIC_RMS_SLOPE]
     * whichever way it is crossed. Laid out in kilometers, so it is as isotropic on the ground as
     * the cells' shape allows.
     */
    private fun isotropicMetres(config: WorldGenConfig, seed: Long): DoubleArray {
        val random = Random(seed * 7919 + 13)
        val w = config.width
        val h = config.height
        val out = DoubleArray(w * h)
        // A component of slope amplitude s has rms slope s / sqrt(2); n of them, sqrt(n / 2) s.
        val slopeAmplitude = SYNTHETIC_RMS_SLOPE / (2 * PI * sqrt(SYNTHETIC_COMPONENTS / 2.0))
        repeat(SYNTHETIC_COMPONENTS) {
            val bearing = random.nextDouble() * 2 * PI
            val wavelengthKm = exp(
                ln(SYNTHETIC_SHORTEST_KM) + random.nextDouble() * (ln(SYNTHETIC_LONGEST_KM) - ln(SYNTHETIC_SHORTEST_KM))
            )
            val phase = random.nextDouble() * 2 * PI
            val kx = cos(bearing) * 2 * PI / wavelengthKm
            val ky = sin(bearing) * 2 * PI / wavelengthKm
            val amplitudeMetres = slopeAmplitude * wavelengthKm * 1000.0
            for (row in 0 until h) {
                val southKm = row * config.cellHeightKm
                for (column in 0 until w) {
                    out[row * w + column] += amplitudeMetres * cos(kx * column * config.cellWidthKm + ky * southKm + phase)
                }
            }
        }
        return out
    }

    private companion object {
        /**
         * The known failure: what is left of the comb on square cells, the same on both axes.
         *
         * Made in the hydraulic rounds: the same network on the same land, routed over the ground
         * the rounds were handed, combs 0.000 to 0.001 km per 1,000 km² on both axes and seeds, and
         * over the rounds the steep cells' steps turn from the diagonal to the two axes alike (a
         * diagonal share of 41.7 and 42.0% before, 32.2 and 33.0% after). Whether that turn is what
         * combs the flanks is not isolated, and the figure is the census's as much as the ground's:
         * a sustained reach of 5 cells (58.6 km) rather than the 6 that 60 km takes on these cells
         * reads 1.6 to 1.8 times as much. See docs/DESIGN_LEDGER.md, Q2.
         */
        const val SYMMETRIC_COMB =
            "the square cell: the flanks carry a comb of straight parallel gullies on both axes alike, seven to ten times the router's own"

        /** Re-recorded at L1, whose rifts are Earth's half-grabens and the same at every grid (docs/DESIGN_LEDGER.md, L1). */
        const val RECORDED = "seed 7 0.19 down a column and 0.13 along a row; seed 42 0.22 down a column and 0.15 along a row"

        /**
         * The grid the guard is taken on, [SharedWorlds.DETAIL_ROWS]: square cells, 1,024 by 512,
         * 11.7 km a side. A comb is a rank of gullies a cell apart, and its floor and its axis bar
         * were taken from the control on this grid.
         */
        const val STANDARD_ROWS = SharedWorlds.DETAIL_ROWS

        /**
         * A straight reach's least length on the ground: 60 km, six cells on either axis at 512
         * rows, long enough that a reach is a run and not two steps.
         */
        const val SUSTAINED_KM = 60.0

        /**
         * How far across a partner reach is looked for: 20 to 50 km, which holds two to four cells
         * on either axis at 512 rows. At least two cells across, so there is a cell between the two
         * for the ridge to stand on.
         */
        const val NEAREST_KM = 20.0
        const val FURTHEST_KM = 50.0

        /** The ridge between: what the router's own parallel reaches on isotropic ground rarely reach. */
        const val RIDGE_METRES = 100.0

        /**
         * How much more comb one axis may carry than the other: the control's 1.30, the larger of its
         * two seeds' ratios of parallel sustained reaches, rounded up to the next half as the
         * half-height cell's 3.1 was to 3.5.
         *
         * Taken from the sustained reaches, the census with no ridge asked, and not from the comb
         * the clause itself reads, which asks a [RIDGE_METRES] ridge. On the control the ridged comb
         * is too small for a ratio to mean anything: 0.020 against 0.007 km per 1,000 km² on seed 7
         * and 0.011 against 0.014 on seed 42, a few tens of kilometers of channel, whose ratio of 2.9
         * is a count of a handful of reaches. Where either axis is that small the clause falls back
         * to [COMB_FLOOR_KM_PER_1000_KM2] instead, which is what the floor in its comparison is for.
         */
        const val AXIS_FACTOR = 1.5

        /**
         * The comb an axis may carry, in km per 1,000 km² of land: the most the router's own parallel
         * reaches carry with a [RIDGE_METRES] ridge between them on the isotropic control over the
         * same land at 512 rows, rounded up at the second figure. From the control, not from any
         * world under test, and the third case holds the control to it: at 0.02, rounded to the
         * nearest, the guard failed its own control by the 0.0001 it had been rounded down. First
         * 0.0201 on seed 7's columns (0.0068 on its rows, 0.0111 and 0.0137 on seed 42's), so 0.021;
         * re-derived at L1's review round, whose rift joins are relay ramps and moved the land the
         * control is laid over: 0.0229 on seed 42's rows (0.0076 on its columns, 0.0162 and 0.0085
         * on seed 7's), so 0.023.
         */
        const val COMB_FLOOR_KM_PER_1000_KM2 = 0.023

        /**
         * The isotropic control's surface: 400 cosines, 40 to 4,000 km long, standing at an rms
         * slope of 20 m/km, the steep ground the comb is found on; written into a field of 20 km of
         * relief about its middle so the router reads it at a float's full precision.
         */
        const val SYNTHETIC_COMPONENTS = 400
        const val SYNTHETIC_SHORTEST_KM = 40.0
        const val SYNTHETIC_LONGEST_KM = 4_000.0
        const val SYNTHETIC_RMS_SLOPE = 0.020
        const val SYNTHETIC_FIELD_METRES = 20_000.0

        /** How far the network may thin: `ScaleFreeTest`'s grid tolerance. */
        const val NETWORK_FACTOR = 1.35

        /** The initiated network at 512 rows on Q2's head, km of channel per 1,000 km² of land, by seed. */
        val NETWORK_ON_THE_HEAD = listOf(7L to 43.07, 42L to 49.10)

        val WORLD_SEEDS = NETWORK_ON_THE_HEAD.map { it.first }
    }
}
