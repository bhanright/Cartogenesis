package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.FlowRouting
import com.cartogenesis.worldgen.pipeline.PitStage
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.RoundMass
import com.cartogenesis.worldgen.pipeline.SeaLevelStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import com.cartogenesis.worldgen.pipeline.erodeBlockingWithReceiverClamp
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * H5b, part one: a channel cannot end a round below the cell it drains into.
 *
 * Stream-power incision lowers a cell by what its own discharge and its own slope allow and says
 * nothing about what the cell below it is doing in the same round. Two neighbours on one channel
 * are cut by different amounts and often enough the upper one is cut further — it may carry nearly
 * the same catchment down a steeper reach — so the round ends with a hole in the river's bed. The
 * next round's priority flood has to raise that hole to route through it, which makes it standing
 * water, and along a channel the holes line up into a rank of thin bars lying at a grid bearing.
 * That is exactly the shape `GlaciationTest`'s comb measurement exists to catch the ice making, and
 * it is what took the comb bar from 3.5% to 5% at H5: the lowstand grades the lower valleys to a
 * sea a stand below today's, cutting the near-coastal channels deeper and leaving more such holes.
 *
 * The cure is Braun and Willett's (2013, *Geomorphology* 180-181, 170-179 — the FastScape scheme),
 * carried by every landscape-evolution model since: `z_i' >= z_r'`, a node's new elevation is never
 * below its receiver's new elevation. This asserts that it holds, and measures what each of a
 * round's mechanisms contributed before it did.
 *
 * ### The census, and why only the incision is clamped
 *
 * The plan asks for exactly one clamp added on evidence rather than four on suspicion, so
 * [RoundMass.channelPits] counts the offending cells after each mechanism of each round: as the
 * round opens, after the outlet notch, after the incision, after the spoil is laid on the rock,
 * after the closing breach and the distributary grooves, and after the thermal relaxation. Cells
 * that were already pits when the round opened are excluded from every later count, so what each
 * slot holds is the holes that mechanism *made*. Measured on 718106, 42 and 7 at 512, summed over
 * the twelve rounds, with the clamp off and then on:
 *
 * | seed | notch | incision | spoil | closing | relax | channel cells drawn under water |
 * |---|---|---|---|---|---|---|
 * | 718106 off | 0 | 6383 | 6748 | 6423 | 4167 | 1627 |
 * | 718106 on  | 0 | 0 | 1173 | 894 | 830 | 780 |
 * | 42 off | 0 | 10 | 344 | 311 | 328 | 106 |
 * | 42 on  | 0 | 0 | 420 | 404 | 418 | 54 |
 * | 7 off | 0 | 5 | 433 | 377 | 421 | 323 |
 * | 7 on  | 0 | 0 | 509 | 473 | 513 | 265 |
 *
 * The middle columns are cumulative through a round — a cell the spoil put below its receiver is
 * still there when the closing breach is counted — so the closing and relax figures are what is
 * *standing* at those points, not new holes of their own. On 718106 the relax takes some away.
 *
 * The incision is the mechanism worth clamping and the only one clamped. The notch cuts a surface
 * that falls away from the new lip by construction and leaves nothing, on any seed, in any round.
 * The thermal sweeps move nothing on ground gentler than the critical slope, which a channel floor
 * is, and in fact take pits away rather than adding them on the author's world. What is left after
 * the clamp is the spoil: a few hundred cells where a floodplain or a fan laid at the end of the
 * last round stands above the channel feeding it. That is an alluvial dam, which is a real
 * landform, and the existing no-uphill rule already holds it to the margin below each donor — so
 * it is measured and left alone rather than clamped on suspicion, and `DeltaMouthTest` and E5's
 * chunk own the deposition.
 *
 * Worth knowing about that residual, and recorded in TODO.md rather than acted on here: the
 * no-uphill margin is computed off `settled`, which is seeded from the shoreline-relative field and
 * then updated with height-unit amounts, so the room a cell is given is about `1/landRange` times
 * the room the rule means — four-odd on a typical world. It is the same unit muddle the clamp above
 * closes for the incision, and it is why the spoil's count rises slightly when the incision's falls
 * (a less deeply cut channel leaves a floodplain standing relatively higher).
 */
class ReceiverClampTest {

    /** The author's own world and the two the plan names, at the grid the guards run at. */
    private val seeds = listOf(718106L, 42L, 7L)

    @Test
    fun `the incision leaves no channel cell below its receiver, and does without the clamp`() {
        val loose = ArrayList<String>()
        val tight = ArrayList<String>()
        seeds.forEach { seed ->
            val config = WorldGenConfig(seed = seed, width = 512, height = 512)
            val uplift = PlateStage.generate(config, TerrainStage.generate(config)).height

            listOf(false to loose, true to tight).forEach { (clamp, into) ->
                val rounds = ArrayList<RoundMass>()
                val eroded = erodeBlockingWithReceiverClamp(config, uplift, clamp) { rounds.add(it) }
                val totals = IntArray(PitStage.COUNT)
                rounds.forEach { r ->
                    for (s in 0 until PitStage.COUNT) totals[s] += r.channelPits[s]
                }
                println(
                    "CLAMP seed $seed clamp=$clamp: channel pits over ${rounds.size} rounds — " +
                        "${PitStage.names[PitStage.OPENING]} ${totals[PitStage.OPENING]} " +
                        "standing when the rounds began, then newly made by " +
                        (1 until PitStage.COUNT).joinToString(", ") {
                            "${PitStage.names[it]} ${totals[it]}"
                        }
                )
                // What the reader eventually sees: a channel cell the river stage's fill has to
                // raise deep enough to draw as water. It cannot be zero and should not be — a lake
                // in a tectonic bowl is a channel cell below its receiver by definition — so this
                // is reported and the by-construction claim is asserted on the census above.
                val ponded = pondedChannelCells(config, eroded.height)
                // What the clamp costs, reported rather than asserted. The cap it replaces was
                // `drop * 0.5` in the shoreline-relative units the drop is measured in, spent on a
                // height field whose land range is about a quarter — so it allowed a cell to be cut
                // by about twice the height it actually stood above its receiver, every round, on
                // every well-fed channel. That is where the holes came from, and closing it takes
                // real material out of the budget.
                println(
                    "CLAMP seed $seed clamp=$clamp: $ponded channel cells drawn under water; " +
                        "incised %.2f, deposited %.2f, lost %.2f over the rounds".format(
                            rounds.sumOf { it.incised }, rounds.sumOf { it.deposited },
                            rounds.sumOf { it.lostToSea }
                        )
                )
                into.add("$seed $ponded ponded, ${totals[PitStage.INCISION]} cut into a hole")
                if (clamp) {
                    assertTrue(
                        totals[PitStage.INCISION] == 0,
                        "seed $seed: the incision put ${totals[PitStage.INCISION]} channel cells " +
                            "below the cell they drain into over the twelve rounds, where the " +
                            "FastScape bound says none"
                    )
                }
            }
        }
        println("CLAMP with the clamp off: $loose; with it on: $tight")
        assertTrue(
            loose.any { it.contains(Regex("[1-9]\\d* cut into a hole")) },
            "the world without the clamp was expected to cut channel cells below their receivers " +
                "and cut none on any seed, so this guard proves nothing: $loose"
        )
    }

    /**
     * Channel cells the river stage's fill would draw as standing water.
     *
     * Cut, routed and filled exactly as the sea-level and river stages do it, since that is the
     * surface the reader is shown. Reported rather than asserted: a river running into a lake in a
     * tectonic bowl puts channel cells under water for a reason that is not a defect, and this
     * figure counts those too. What it is good for is the comparison between the clamped world and
     * the unclamped one on the same seed.
     */
    private fun pondedChannelCells(
        config: WorldGenConfig,
        height: com.cartogenesis.worldgen.model.FloatField
    ): Int {
        val w = config.width
        val h = config.height
        val sea = SeaLevelStage.apply(height, config)
        if (sea.landCellCount == 0) return 0
        val filled = FlowRouting.fillDepressions(w, h, sea.isLand, sea.relativeElevation)
        val flow =
            FlowRouting.flowDirections(w, h, sea.isLand, sea.relativeElevation, filled, config.seed)
        val area = FlowRouting.accumulate(w, h, sea.isLand, filled, flow, sea.landCellCount) { 1f }
        val land = sea.landCellCount.toFloat()
        // The same figure `RiverConfig.sourceFlowShare` draws a river at, and the same one the
        // in-round census uses; the depth is `LakesConfig.minDepthMetres`.
        val channel = 0.0006f
        val minDepth = config.scale.reliefShareOfMetres(config.lakes.minDepthMetres)
        val ground = sea.relativeElevation.data
        var ponded = 0
        for (i in 0 until w * h) {
            if (!sea.isLand[i]) continue
            if (area.data[i] / land < channel) continue
            if (filled.data[i] - ground[i] >= minDepth) ponded++
        }
        return ponded
    }
}
