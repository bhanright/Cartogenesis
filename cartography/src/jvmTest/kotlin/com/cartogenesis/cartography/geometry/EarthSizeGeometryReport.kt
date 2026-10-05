package com.cartogenesis.cartography.geometry

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldScale
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The geometry census on a planet of Earth's size: seeds 42 and 969495 at 40,075 km and 1,024
 * rows, every layer and every detector, printed and not asserted.
 *
 * A report and not a gate, because nothing is calibrated on a planet this size yet: the counts per
 * world, the epoch drift and the shares of a world's totals still wait in `TODO.md`, and the bars
 * the census holds were derived on the 12,000 km world. What it is for is the figures the
 * Earth-size audit read off this census and K1 was built to bring down — the plate boundaries and
 * the coasts drawn as straight facets, the biome edges along rows — so the next chunk that touches
 * the planet's size reads them again rather than taking them on trust (docs/DESIGN_LEDGER.md, K1).
 * Each violation's signature, a count and its worst place, is printed as a `GEOMETRY EARTH` line.
 */
class EarthSizeGeometryReport {

    @Test
    fun `report the census on a planet of Earth's size`() {
        val census = Census(EARTH_ROWS * 2, SEEDS.size)
        for (seed in SEEDS) {
            census.read(WorldGenConfig.forRows(seed, EARTH_ROWS).copy(scale = WorldScale(worldWidthKm = EARTH_WIDTH_KM)))
        }
        println("GEOMETRY EARTH summary, a planet of ${EARTH_WIDTH_KM.toInt()} km at $EARTH_ROWS rows\n" + census.summary())
        val violations = census.recordLines().filter { it.contains("known(") }
        violations.forEach { println("GEOMETRY EARTH $it") }
        println("GEOMETRY EARTH ${violations.size} clauses in violation over ${SEEDS.size} worlds")
        assertTrue(census.worlds.size == SEEDS.size, "the census read ${census.worlds.size} worlds")
    }

    private companion object {
        /** The two seeds the Earth-size audit read. */
        val SEEDS = longArrayOf(42L, 969495L)

        /** Earth's equator, in kilometers. */
        const val EARTH_WIDTH_KM = 40_075.0

        /** The grid the application makes every world on. */
        const val EARTH_ROWS = 1024
    }
}
