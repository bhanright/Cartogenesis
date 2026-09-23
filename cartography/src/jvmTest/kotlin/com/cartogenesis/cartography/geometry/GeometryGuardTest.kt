package com.cartogenesis.cartography.geometry

import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rule 13's guard over every layer the map draws, on the four standard worlds at 512.
 *
 * Every layer [MapLayers] lists, every detector, every world: the census is printed whole, and
 * every clause a detector could measure is asserted. A clause that fails today is run through
 * [KnownFailures.expect] under the name of its finding — a known failure that turns this test red
 * the day it is fixed, so the fix arms its own guard — rather than skipped or loosened. Where a
 * layer is too small at this grid for a detector to bind, the census says so and asserts nothing.
 *
 * The same census at 2048 over the six audited seeds and 969495 is `GeometryGuardAuditTest`, in
 * the audit tier.
 */
class GeometryGuardTest {

    private companion object {
        /** The four standard seeds, at the grid every per-merge guard in this repository uses. */
        val SEEDS = listOf(7L, 42L, 1234L, 99L)
        const val SIDE = 512

        /**
         * The clauses that fail today, by `seed/layer/DETECTOR`, each under the name of the
         * finding it records. See the census's printout for the figures and places, and
         * `build/geometry-census/512` for a picture of each.
         */
        val KNOWN: Map<String, String> = buildMap {
            for (seed in SEEDS) put("$seed/coast as inked/FACING", GeometryFindings.COAST_INK)
            put("7/ice as drawn/ALIGNED_SIDE", GeometryFindings.ICE_EDGE_ALONG_A_ROW)
            put("99/ice as drawn/ALIGNED_SIDE", GeometryFindings.ICE_EDGE_ALONG_A_ROW)
            put("1234/ice occupancy/ALIGNED_SIDE", GeometryFindings.ICE_EDGE_ALONG_A_ROW)
            put("1234/ice sheet ground/ALIGNED_SIDE", GeometryFindings.ICE_EDGE_ALONG_A_ROW)
            put("1234/ice carving/ALIGNED_SIDE", GeometryFindings.ICE_EDGE_ALONG_A_ROW)
            put("7/biome edges/ALIGNED_SIDE", GeometryFindings.BIOME_EDGES_ALONG_ROWS)
            for (seed in listOf(7L, 42L, 1234L)) put("$seed/biome edges/FACETS", GeometryFindings.BIOME_EDGES_ALONG_ROWS)
            put("42/isotherms/FACETS", GeometryFindings.ISOTHERMS_ALONG_ROWS)
            put("1234/isotherms/FACETS", GeometryFindings.ISOTHERMS_ALONG_ROWS)
            put("42/sea temperature anomaly/ALIGNED_SIDE", GeometryFindings.ANOMALY_ALONG_ROWS)
            put("42/sea temperature anomaly/FACETS", GeometryFindings.ANOMALY_ALONG_ROWS)
            put("99/sea temperature anomaly/RIGHT_ANGLES", GeometryFindings.ANOMALY_SQUARE_CORNERS)
            put("42/terrain contours/FACETS", GeometryFindings.STRAIGHT_RANGE_FRONTS)
            put("1234/terrain contours/FACETS", GeometryFindings.STRAIGHT_RANGE_FRONTS)
            put("7/plate boundaries/ALIGNED_SIDE", GeometryFindings.PLATE_BOUNDARY_ON_THE_GRID)
            put("1234/plate boundaries/ARCS", GeometryFindings.PLATE_BOUNDARY_ARC)
        }
    }

    @Test
    fun `every layer of the four standard worlds, against every detector`() {
        val census = Census(SIDE, Census.familySize(SEEDS.size))
        SEEDS.forEach { seed ->
            val reading = census.read(WorldGenConfig(seed = seed, width = SIDE, height = SIDE))
            assertEquals(Census.LAYERS, reading.readings.size, "the census's family counts ${Census.LAYERS} layers")
        }
        println("GEOMETRY SUMMARY $SIDE\n" + census.summary())
        val failures = census.failures(KNOWN)
        assertTrue(failures.isEmpty(), "geometry guard at $SIDE:\n" + failures.joinToString("\n"))
    }
}
