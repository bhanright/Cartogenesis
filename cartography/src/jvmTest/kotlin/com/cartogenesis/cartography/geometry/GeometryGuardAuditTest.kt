package com.cartogenesis.cartography.geometry

import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The geometry guard at 2048, where the artefacts show: the six audited seeds and the author's
 * own reported world, 969495, all on default settings carried to 2048 the way the program carries
 * them (`WorldGenConfig.atResolution` from the 512 defaults).
 *
 * The census is printed whole, as `GeometryGuardTest`'s is at 512, and every clause is held to
 * [EXPECTED]: known failures by finding and signature, and the clauses too small to measure.
 *
 * Ruled lines get one more question here. A comb whose spacing is fixed in cells is the grid's and
 * one whose spacing is fixed on the ground may be the country's, and only the same world at two
 * grids tells the two apart: so wherever the census at 2048 finds combs in a layer, the same seed
 * is generated at 1024 and that layer read again, and each comb at 2048 is matched with the
 * nearest comb at 1024 along the same bearing ([Census.pairCombs]). A comb whose spacing in cells
 * has doubled is fixed on the ground and let stand; one at the same count of cells, or with no
 * partner, stays a violation.
 *
 * One 2048 world at a time, dropped before the next.
 */
class GeometryGuardAuditTest {

    private companion object {
        val SEEDS = listOf(7L, 42L, 1234L, 99L, 718106L, 59758L, 969495L)
        const val SIDE = 2048
        const val PAIRED_SIDE = 1024

        /** What the census expects at 2048; see `GeometryGuardTest.EXPECTED`. */
        val EXPECTED = Expectations().apply {
            GeometryExpectations.findings(this)
            GeometryExpectations.at2048(this)
        }
    }

    private fun config(seed: Long, side: Int): WorldGenConfig =
        WorldGenConfig(seed = seed, width = 512, height = 512).atResolution(side, side)

    @Test
    fun `every layer of the audited worlds and 969495 at 2048, against every detector`() {
        val census = Census(SIDE, SEEDS.size)
        val pairing = ArrayList<String>()
        SEEDS.forEach { seed ->
            val fine = census.read(config(seed, SIDE))
            val withCombs = fine.readings.filter { it.combs.isNotEmpty() }
            if (withCombs.isEmpty()) return@forEach
            val coarse = Census(PAIRED_SIDE, SEEDS.size).read(config(seed, PAIRED_SIDE))
            val fineFrame = GridFrame.of(config(seed, SIDE))
            val coarseFrame = GridFrame.of(config(seed, PAIRED_SIDE))
            for (reading in withCombs) {
                val pairings = Census.pairCombs(reading, fineFrame, coarse.readings.first { it.layer == reading.layer }, coarseFrame)
                reading.exemptGroundFixed(pairings.filter { it.groundFixed }.map { it.comb })
                pairings.forEach { pairing.add("GEOMETRY PAIRED ${fine.name} | ${reading.layer} | ${it.line}") }
            }
        }
        println("GEOMETRY SUMMARY $SIDE\n" + census.summary())
        pairing.forEach { println(it) }
        census.recordLines().forEach(::println)
        val failures = census.failures(EXPECTED)
        assertTrue(failures.isEmpty(), "geometry guard at $SIDE:\n" + failures.joinToString("\n"))
    }
}
