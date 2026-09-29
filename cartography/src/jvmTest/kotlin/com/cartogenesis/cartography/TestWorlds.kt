package com.cartogenesis.cartography

import com.cartogenesis.worldgen.SharedWorlds
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap

/**
 * The world the drawing guards in this module measure on.
 *
 * Seed 234475 at 512 rows of square cells, 1024 by 512, is the gallery's world — the one
 * `StyleGalleryTest` writes out to be looked at — so the numbers in these tests describe a picture
 * somebody can go and see. Its sheet is the 1024 by 512 pixels the 512 by 512 grid's was, a
 * cell to a pixel where that grid's cell was two pixels wide. Four classes want
 * it, and generating it in each cost fifteen seconds a run and put the suite over its heap, so it
 * is made once and lent. Asked of [SharedWorlds] on every read rather than held here, so that every
 * class that reads it is a borrower whose tests are checked for writing to it.
 */
internal object TestWorlds {

    /** The gallery's settings, for a figure that reads the grid without the world. */
    val galleryConfig: WorldGenConfig = WorldGenConfig.forRows(seed = 234475L, rows = 512)

    val gallery: WorldMap
        get() = SharedWorlds.world(galleryConfig)
}
