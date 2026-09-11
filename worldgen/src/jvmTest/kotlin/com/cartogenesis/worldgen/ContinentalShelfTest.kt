package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.math.DistanceTransform
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * B1: continental shelves.
 *
 * A percentile cut through one height field drops the sea floor straight off the coast unless
 * oceanic crust gets its own hypsometric mode — a shallow band near the coast, then a genuinely
 * deep floor. That shows up here as the share of *ocean* cells the climate stage already calls
 * [com.cartogenesis.worldgen.pipeline.Biome.SHALLOW_OCEAN] (`relativeElevation > -0.12`): high
 * near the coast, low once well clear of it.
 */
class ContinentalShelfTest {

    private val seeds = listOf(7L, 42L, 1234L)

    @Test
    fun `shallow water hugs the coast and the open ocean is deep`() {
        seeds.forEach { seed ->
            val config = WorldGenConfig(seed = seed, width = 512, height = 512)
            val shelfWidth = config.tectonics.shelfWidth
            val (near, far) = shallowShares(config, shelfWidth)
            println(
                "SHELF seed $seed: shelfWidth=%.0f near-coast shallow=%.1f%% far-from-coast shallow=%.1f%%"
                    .format(shelfWidth, near * 100, far * 100)
            )
            assertTrue(
                near > 0.40,
                "seed $seed: only ${(near * 100).toInt()}% of ocean within $shelfWidth cells of " +
                    "the coast is shallow"
            )
            assertTrue(
                far < 0.10,
                "seed $seed: ${(far * 100).toInt()}% of ocean beyond ${2 * shelfWidth} cells from " +
                    "the coast is still shallow"
            )
        }
    }

    /**
     * Ground rule 2 says show the guard fails without the fix. It does not, for the literal
     * near/far percentages above, and that is a real finding rather than an oversight: this
     * pipeline already had a partial hypsometric split before this chunk (`plateBase`'s own
     * continental/oceanic bias, blurred at a fixed radius tied to `boundaryFalloff`), and combined
     * with the ordinary spatial smoothness of Perlin terrain -- a cell right next to land is
     * usually close to the shoreline's own elevation, shelf or no shelf -- that is already enough
     * to clear both the 40% near-coast and the 10% far-from-coast thresholds at this seed and
     * resolution (measured below: `near-coast shallow` and `far-from-coast shallow` with the shelf
     * mechanism fully disabled). Widening the fringe or deepening the floor further to force this
     * specific percentage-based check to fail would mean tuning the feature to the test rather than
     * to the geography, which ground rule 5 rules out.
     *
     * What the shelf demonstrably does change -- shown in [DebugMapDump]'s renders and reported in
     * the chunk's final report -- is the *shape* of the transition: a visibly paler, wider shallow
     * margin along every coastline, and a deep ocean floor that reads as a distinct colour rather
     * than a continuation of the coastal gradient. That is the qualitative claim in the plan
     * ("shallow seas along continental margins") and it is real; it is just not what this
     * particular percentage cut isolates. This test exists to record that honestly rather than
     * silently drop the discrimination check.
     */
    @Test
    fun `the near-far percentages do not discriminate at shelfWidth 0 -- reported, not tuned`() {
        val defaultWidth = WorldGenConfig().tectonics.shelfWidth
        val config = WorldGenConfig(seed = 42L, width = 512, height = 512).let {
            it.copy(tectonics = it.tectonics.copy(shelfWidth = 0f))
        }
        val (near, far) = shallowShares(config, defaultWidth)
        println(
            "SHELF shelfWidth=0 control (feature fully disabled): near-coast shallow=%.1f%% far-from-coast shallow=%.1f%%"
                .format(near * 100, far * 100)
        )
        // Documented rather than asserted to fail: both already clear the thresholds above, which
        // is exactly the point of this test's doc comment.
        assertTrue(near > 0.40 && far < 0.10, "the pre-existing baseline moved outside the range this " +
            "comment describes ($near, $far) -- re-check whether the guard now discriminates")
    }

    /**
     * @return (share of ocean within [shelfWidth] cells of the coast that is shallow, share of
     *   ocean beyond `2 * shelfWidth` cells that is shallow)
     */
    private fun shallowShares(config: WorldGenConfig, shelfWidth: Float): Pair<Double, Double> {
        val world = WorldGenerationEngine.generateBlocking(config)
        val w = world.width
        val h = world.height
        val land = world.sea.isLand

        // Distance in cells from every ocean cell to the nearest land — i.e. to the coast.
        val dist = FloatArray(w * h) { DistanceTransform.INFINITE }
        val label = IntArray(w * h) { -1 }
        for (i in 0 until w * h) {
            if (land[i]) {
                dist[i] = 0f
                label[i] = i
            }
        }
        DistanceTransform.run(w, h, dist, label)

        var nearShallow = 0
        var nearTotal = 0
        var farShallow = 0
        var farTotal = 0
        for (i in 0 until w * h) {
            if (land[i]) continue
            val shallow = world.sea.relativeElevation.data[i] > -0.12f
            val d = dist[i]
            if (d <= shelfWidth) {
                nearTotal++
                if (shallow) nearShallow++
            } else if (d > 2f * shelfWidth) {
                farTotal++
                if (shallow) farShallow++
            }
        }
        val near = if (nearTotal == 0) 0.0 else nearShallow.toDouble() / nearTotal
        val far = if (farTotal == 0) 0.0 else farShallow.toDouble() / farTotal
        return near to far
    }
}
