package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The flanks of east-west ranges are not combed by straight parallel gullies down the columns.
 *
 * What the eye sees on the renders of chunk 3b (seed 7's upper-right range at 1024, 969495's
 * eastern flank at 2048) is channel after channel running dead straight along one grid axis, a
 * cell or two apart, with a ridge between each pair. So the census counts exactly that, in
 * [CombCensus]: channel cells in a straight reach along one axis at least [SUSTAINED_KM] long, with
 * a second such reach running the same way [NEAREST_KM] to [FURTHEST_KM] across it and a ridge at
 * least [RIDGE_METRES] above both standing between them. Down a column and along a row alike, at
 * the same kilometres on the ground.
 *
 * **The control is the other axis on the same world.** A comb is a bearing's artefact, so what
 * condemns it is one axis carrying far more of it than the other on ground that has both. The
 * router is not isotropic in its straight reaches, and the bar allows for what the rule does by
 * itself: on an isotropic synthetic surface over the same land, routed by production's router with
 * every land cell a channel, parallel sustained reaches run 3.1 and 2.0 times as often down the
 * columns as along the rows on seeds 7 and 42, at any ridge; so the column may carry up to
 * [ROW_FACTOR] times the row's comb. That surface's parallel reaches never have a ridge of 50 m
 * between them (0.00 to 0.01 km per 1,000 km²), so a [RIDGE_METRES] ridge is twice what the rule's
 * own parallel reaches reach.
 *
 * **Held with the network,** because a comb can be removed by removing channels. The channel
 * network the criterion initiates may not thin below its figure on the head this guard was written
 * on by more than `ScaleFreeTest`'s 1.35, the most it may move between grids; and the dissection
 * clauses this branch armed (the valley notch, the ranges' texture and flank, the dissection
 * contrast and the wet flank) are run beside it wherever a change to the erosion is weighed.
 *
 * On chunk 3b's head (bb7b606) the columns carry 0.41 and 0.52 km of comb per 1,000 km² of land on
 * seeds 7 and 42 at 512, and the rows 0.06 and 0.08: recorded, and the comb experiments in
 * docs/DESIGN_LEDGER.md, Fix 3b, are measured against it.
 */
class CombGuardTest {

    @Test
    fun `the flanks are not combed down the columns`() {
        val complaints = ArrayList<String>()
        val figures = ArrayList<String>()
        for ((seed, networkFloor) in NETWORK_ON_THE_HEAD) {
            val world = SharedWorlds.world(WorldGenConfig(seed = seed, width = 512, height = 512))
            val census = CombCensus.of(world, SUSTAINED_KM, NEAREST_KM, FURTHEST_KM, RIDGE_METRES)
            println(
                String.format(
                    Locale.ROOT,
                    "COMB seed %d@512: channel %.2f km per 1000 km2; sustained %.2f down a column and %.2f along a row; combed %.3f and %.3f",
                    seed, census.channelKmPer1000Km2, census.column.sustainedKmPer1000Km2, census.row.sustainedKmPer1000Km2,
                    census.column.combedKmPer1000Km2, census.row.combedKmPer1000Km2
                )
            )
            assertTrue(
                census.channelKmPer1000Km2 >= networkFloor / NETWORK_FACTOR,
                "seed $seed's network thinned to ${census.channelKmPer1000Km2} km per 1000 km2 from $networkFloor, " +
                    "past ScaleFreeTest's $NETWORK_FACTOR: a comb removed by removing channels"
            )
            val allowed = ROW_FACTOR * census.row.combedKmPer1000Km2
            val figure = String.format(Locale.ROOT, "seed %d %.2f down a column against %.2f along a row", seed,
                census.column.combedKmPer1000Km2, census.row.combedKmPer1000Km2)
            if (census.column.combedKmPer1000Km2 > allowed) {
                complaints += figure
                figures += figure
            }
        }
        KnownFailures.expect(COMB_ON_THE_FLANKS, RECORDED) {
            if (complaints.isNotEmpty()) {
                throw RecordedViolation(
                    "the flanks are combed down the columns, over $ROW_FACTOR times the rows' comb, in km of comb per " +
                        "1000 km2 of land: ${complaints.joinToString("; ")}",
                    figures.joinToString("; ")
                )
            }
        }
    }

    private companion object {
        /**
         * The known failure: the comb chunk 3b's renders show, which the guard was written against.
         * Not the router's (`RoutingGroundTest`), and more than the rule's own straight reaches
         * make; what the rounds do to build it is measured in docs/DESIGN_LEDGER.md, Fix 3b.
         */
        const val COMB_ON_THE_FLANKS = "the erosion: the flanks are combed by straight parallel gullies down the columns"

        const val RECORDED = "seed 7 0.41 down a column against 0.06 along a row; seed 42 0.52 down a column against 0.08 along a row"

        /**
         * A straight reach's least length on the ground: 60 km, three cell widths along a row at
         * 512 and six rows down a column, long enough that a reach is a run and not two steps.
         */
        const val SUSTAINED_KM = 60.0

        /**
         * How far across a partner reach is looked for: 20 to 50 km, which holds two cell widths
         * at 512 (the comb's every other column) and two to four rows on the other axis, and at
         * 1024 two to four cell widths. At least two cells across in either case, so there is a
         * cell between the two for the ridge to stand on.
         */
        const val NEAREST_KM = 20.0
        const val FURTHEST_KM = 50.0

        /** The ridge between: twice what the rule's own parallel reaches never reach. */
        const val RIDGE_METRES = 100.0

        /** How much more comb the columns may carry than the rows: the rule's own 3.1, rounded up. */
        const val ROW_FACTOR = 3.5

        /** How far the network may thin: `ScaleFreeTest`'s grid tolerance. */
        const val NETWORK_FACTOR = 1.35

        /** The initiated network on bb7b606, km of channel per 1,000 km² of land, by seed. */
        val NETWORK_ON_THE_HEAD = listOf(7L to 31.64, 42L to 36.61)
    }
}
