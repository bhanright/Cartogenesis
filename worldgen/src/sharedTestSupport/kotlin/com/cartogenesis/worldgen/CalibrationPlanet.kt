package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig

/**
 * The planet the drawing's constants were derived on: 12,000 km round with fourteen plates, the
 * default until K2 made the default Earth's.
 *
 * Every world a tier draws its geography from is Earth-sized. A few guards measure a constant of
 * the drawing — the relief shading's floor, haze and exaggeration, the isobaths' interval, the
 * river pen's span, the engraving's ink — that was read off a world of this planet at a cell size
 * the Earth-sized planet's 512 rows do not have (11.7 km there, 39 km here), and whose own
 * derivation would draw a different picture at Earth's; re-deriving them is the drawing's chunk,
 * not the geography's (docs/TODO.md). Those guards build their worlds here, so they keep holding
 * the drawing to what it was set on. The fourteen plates are set explicitly because the plate
 * count is a mean plate area now, Earth's, which on this planet makes two.
 */
object CalibrationPlanet {

    /** Its circumference, in kilometers. */
    const val WIDTH_KM = 12_000.0

    /** Its plates. */
    const val PLATES = 14

    /** [config] on this planet, every other setting as it was. */
    fun of(config: WorldGenConfig): WorldGenConfig {
        val scale = config.scale.copy(worldWidthKm = WIDTH_KM)
        return config.copy(scale = scale, tectonics = config.tectonics.withPlateCount(PLATES, scale))
    }
}
