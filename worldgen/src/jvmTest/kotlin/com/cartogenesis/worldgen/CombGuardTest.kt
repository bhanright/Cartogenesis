package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import java.util.Locale
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
 * reaches with a [RIDGE_METRES] ridge between them come to 0.007 to 0.020 km per 1,000 km², so
 * [COMB_FLOOR_KM_PER_1000_KM2] is the most either axis may carry. The worlds carry eight to ten times
 * it on both axes, a comb no longer one-sided but not gone, and that runs as a known failure,
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
            "the square cell: the flanks carry a comb of straight parallel gullies on both axes alike, eight to ten times the router's own"

        const val RECORDED = "seed 7 0.16 down a column and 0.15 along a row; seed 42 0.20 down a column and 0.18 along a row"

        /** The grid the guard is taken on: square cells, 1,024 by 512, 11.7 km a side. */
        const val STANDARD_ROWS = 512

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
         */
        const val AXIS_FACTOR = 1.5

        /**
         * The comb an axis may carry, in km per 1,000 km² of land: the most the router's own parallel
         * reaches carry with a [RIDGE_METRES] ridge between them on the isotropic control over the
         * same land at 512 rows, 0.020 on seed 7's columns (0.007 on its rows, 0.011 and 0.014 on
         * seed 42's). From the control, not from any world under test.
         */
        const val COMB_FLOOR_KM_PER_1000_KM2 = 0.02

        /** How far the network may thin: `ScaleFreeTest`'s grid tolerance. */
        const val NETWORK_FACTOR = 1.35

        /** The initiated network at 512 rows on Q2's head, km of channel per 1,000 km² of land, by seed. */
        val NETWORK_ON_THE_HEAD = listOf(7L to 43.07, 42L to 49.10)

        val WORLD_SEEDS = NETWORK_ON_THE_HEAD.map { it.first }
    }
}
