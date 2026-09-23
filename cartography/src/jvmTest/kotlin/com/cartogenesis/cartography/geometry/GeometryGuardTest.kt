package com.cartogenesis.cartography.geometry

import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rule 13's guard over every layer the map draws, on the four standard worlds at 512.
 *
 * Every layer [MapLayers] lists, every detector, every world: the census is printed whole, and
 * every clause is held to [EXPECTED]. A clause that fails today is run through
 * [KnownFailures.expect] under its finding's name and its violation's signature — a known failure
 * that turns this test red the day it is fixed, so the fix arms its own guard, or the day another
 * violation takes its place — rather than skipped or loosened. A clause too small to measure at
 * this grid is listed as expected-insufficient and written to the tier's report; one that could be
 * measured and no longer can fails, and so does one listed that can now be measured.
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
         * What the census expects at 512. See the census's printout for every figure and place,
         * `build/geometry-census/512` for a picture of each violation, and its `GEOMETRY RECORD`
         * lines for these entries as the census finds them.
         */
        val EXPECTED = Expectations().apply {
            GeometryExpectations.findings(this)
            GeometryExpectations.at512(this)
        }
    }

    @Test
    fun `every layer of the four standard worlds, against every detector`() {
        val census = Census(SIDE, SEEDS.size)
        SEEDS.forEach { seed ->
            val reading = census.read(WorldGenConfig(seed = seed, width = SIDE, height = SIDE))
            assertEquals(Census.LAYERS, reading.readings.size, "the census's family counts ${Census.LAYERS} layers")
        }
        println("GEOMETRY SUMMARY $SIDE\n" + census.summary())
        census.recordLines().forEach(::println)
        val failures = census.failures(EXPECTED)
        assertTrue(failures.isEmpty(), "geometry guard at $SIDE:\n" + failures.joinToString("\n"))
    }
}
