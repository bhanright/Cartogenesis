package com.cartogenesis.cartography

import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap

/**
 * The world the drawing guards in this module measure on, generated once for all of them.
 *
 * Seed 234475 at 512 is the gallery's world — the one `StyleGalleryTest` writes out to be looked
 * at — so the numbers in these tests describe a picture somebody can go and see. Held here rather
 * than in each test's own companion because three of them want it: generating it three times cost
 * fifteen seconds a run and three copies of a world in the same test worker, which is what put the
 * suite over its heap.
 */
internal object TestWorlds {

    val gallery: WorldMap by lazy {
        WorldGenerationEngine.generateBlocking(
            WorldGenConfig(seed = 234475L, width = 512, height = 512)
        )
    }
}
