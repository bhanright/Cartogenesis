package com.cartogenesis.desktop

import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The same world at four times the grid keeps the same lakes, not four times the water in one of
 * them.
 *
 * `OutletIncisionTest` holds the outlet notch to draining a basin and to leaving no lake bigger
 * than the Caspian's share of the map, but it runs at 512, and the author exports at 2048. Measured
 * on his own settings, the notch was not resolution-invariant: on seed 59758 the largest lake went
 * 0.049% of the map at 512 to 0.050% at 1024 to **0.121% at 2048** — half again over the Caspian
 * bar, on the one grid the guard could not see. Seed 42 moved 0.037 -> 0.016 -> 0.018%. The cause
 * was a rate written in one unit and spent in another: the notch's depth per round came out in the
 * shoreline-relative field and was applied to the height field, so it was silently divided by the
 * range of the land — and that range is not the same number at every grid, because a finer grid
 * resolves finer and therefore steeper detail. The same class of defect as the glacier lattice, and
 * found the same way, by rendering the size the author actually uses.
 *
 * This lives in `:desktop` rather than beside its sibling because a 2048 world needs more heap than
 * `:worldgen`'s test worker is given, and this module's already runs with ten gigabytes.
 */
class OutletResolutionTest {

    /** The Caspian's share of the Earth's surface: the bar for "too big to be a lake". */
    private val caspianShare = 0.00073

    /**
     * How far the largest lake's share of the map may move between 512 and 2048.
     *
     * From the measurement after the fix and not before it: 1.33x on seed 59758 (0.0443, 0.0481,
     * 0.0361 percent at the three grids) and 1.01x on seed 42 (0.0153, 0.0154, 0.0153). The bar is
     * set a little above the worse of the two. Before the fix the same figures spread by 2.45x and
     * 2.38x, so this discriminates by a wide margin rather than by a whisker.
     */
    private val contract = 1.4

    @Test
    fun `the largest lake is the same lake at every grid`() {
        listOf(59758L, 42L).forEach { seed ->
            val shares = listOf(512, 1024, 2048).map { size ->
                val world = WorldGenerationEngine.generateBlocking(
                    WorldGenConfig(seed = seed, width = 512, height = 512).atResolution(size, size)
                )
                val largest = world.rivers.lakes.lakes.maxOfOrNull { it.cellCount } ?: 0
                val share = largest.toDouble() / (size.toDouble() * size)
                println(
                    ("OUTLET SCALE seed %d at %d: %d lakes, largest %d cells (%.4f%% of the map), " +
                        "water %.3f%% of land").format(
                        seed, size, world.rivers.lakes.lakes.size, largest, share * 100,
                        world.rivers.lakes.lakeId.count { it >= 0 } * 100.0 /
                            world.sea.landCellCount
                    )
                )
                assertTrue(
                    world.rivers.lakes.lakes.isNotEmpty(),
                    "seed $seed at $size has no lakes at all"
                )
                assertTrue(
                    share < caspianShare,
                    "seed $seed at $size keeps a lake of ${share * 100}% of the map, " +
                        "${share / caspianShare} times the Caspian's share of the Earth"
                )
                share
            }

            val growth = shares.max() / shares.min().coerceAtLeast(1e-12)
            println(
                "OUTLET SCALE seed %d: largest lake spreads %.2fx across 512, 1024 and 2048"
                    .format(seed, growth)
            )
            assertTrue(
                growth <= contract,
                "seed $seed: the largest lake's share of the map spreads ${growth}x from one grid " +
                    "to another, which is a different world at each size rather than the same " +
                    "world in more detail"
            )
        }
    }
}
