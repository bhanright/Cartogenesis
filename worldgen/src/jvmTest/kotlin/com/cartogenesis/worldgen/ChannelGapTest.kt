package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ChannelInitiation
import kotlin.test.Test
import org.junit.Assert.assertTrue

/**
 * An initiated channel does not stop and start again, and in particular it does not stop on a flat.
 *
 * R1's criterion reads the gradient of the **true ground**, which across a depression the fill has
 * raised is zero or uphill — and since F30b the routing crosses such a flat over a laid potential,
 * which has no length in it at all and is not a slope either. So a reach running over a filled flat
 * initiates nothing of its own, and if the mask were the head test alone the map would draw a river
 * in two pieces with the flat between them blank. `ChannelInitiation.channelMask` carries a started
 * channel downstream for exactly that reason, and this is the clause that says the carrying works.
 *
 * Two claims. The first is the invariant: no channel cell drains into a land cell that is not
 * channel, open water excepted, because open water is where a channel is *supposed* to stop. The
 * second is that the case is real and the rule is not free — the flats where the true ground does
 * not fall carry channel cells, counted here, and with the downstream rule off every one of them
 * would be a gap.
 */
class ChannelGapTest {

    private companion object {
        /** `EarthLikenessTest`'s seeds, at the size a preview is drawn at. */
        val SEEDS = listOf(7L, 42L, 1234L, 99L)
        const val SIDE = 512
    }

    @Test
    fun `no initiated channel drains into land that carries none`() {
        val complaints = ArrayList<String>()
        SEEDS.forEach { seed ->
            val world: WorldMap = WorldGenerationEngine.generateBlocking(
                WorldGenConfig(seed = seed, width = SIDE, height = SIDE)
            )
            val channel = ChannelInitiation.channelMaskOf(world)
            val gradient = ChannelInitiation.gradientToReceiver(
                world.config, world.sea.isLand, world.sea.relativeElevation, world.rivers.flowTarget
            )
            var channelCells = 0
            var onFlatGround = 0
            var gaps = 0
            for (cell in channel.indices) {
                if (!channel[cell]) continue
                channelCells++
                if (gradient[cell] <= 0f) onFlatGround++
                val receiver = world.rivers.flowTarget[cell]
                if (receiver < 0) continue
                if (!world.sea.isLand[receiver]) continue
                if (world.rivers.lakes.isOpenWater(receiver)) continue
                if (!channel[receiver]) gaps++
            }
            println(
                ("CHANNELGAP seed=%d %d channel cells, %d of them on ground that does not fall," +
                    " %d gaps").format(seed, channelCells, onFlatGround, gaps)
            )
            if (gaps > 0) {
                complaints.add("seed $seed: $gaps channel cells drain into land carrying none")
            }
            // The control for the clause above: if no channel cell stood on ground with no fall in
            // it, the downstream rule would be carrying nothing and the clause would pass by
            // measuring nothing.
            if (onFlatGround == 0) {
                complaints.add(
                    "seed $seed: no channel cell stands on ground that does not fall, so the" +
                        " downstream rule is untested on this world"
                )
            }
        }
        assertTrue(
            "an initiated channel stops somewhere it should not: ${complaints.joinToString("; ")}",
            complaints.isEmpty()
        )
    }
}
