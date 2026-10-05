package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.WorldScale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The river pen's full stroke is a width on the ground: [RiverPen.FULL_STROKE_KM] on a planet half,
 * once and twice as wide as the 12,000 km world, at every sheet width, and to the bit the share of
 * the map it was held at on the 12,000 km world (docs/DESIGN_LEDGER.md, K1).
 *
 * Held as a share of the map, the stroke grew with the planet: a planet twice as wide drew its
 * greatest river 57.6 km wide on the ground, twice the exaggeration a printed map gives a river of
 * that class.
 */
class RiverPenGroundTest {

    @Test
    fun `the full stroke is the same width on the ground on any planet and any sheet`() {
        for (widthKm in doubleArrayOf(6_000.0, 12_000.0, 24_000.0)) {
            for (sheetPixels in intArrayOf(1024, 2048, 4096)) {
                val strokeKm = RiverPen.fullPixels(sheetPixels, widthKm) * widthKm / sheetPixels
                assertEquals(
                    RiverPen.FULL_STROKE_KM, strokeKm, RiverPen.FULL_STROKE_KM * FLOAT_ROUNDING,
                    "the full stroke on a $sheetPixels-pixel sheet of a $widthKm km planet"
                )
            }
        }
    }

    @Test
    fun `on the 12,000 km world the stroke is the share of the map it was held at`() {
        val stockWidthKm = WorldScale().worldWidthKm
        for (sheetPixels in intArrayOf(512, 1024, 2048, 4096)) {
            assertEquals(
                sheetPixels * SHARE_OF_MAP_WIDTH_HELD, RiverPen.fullPixels(sheetPixels, stockWidthKm), 0f,
                "the full stroke on a $sheetPixels-pixel sheet"
            )
        }
    }

    private companion object {
        /** The share of the map's width the full stroke was held at until K1. */
        const val SHARE_OF_MAP_WIDTH_HELD = 0.0024f

        /** A float product's last few places, as a share of it. */
        const val FLOAT_ROUNDING = 1e-6
    }
}
