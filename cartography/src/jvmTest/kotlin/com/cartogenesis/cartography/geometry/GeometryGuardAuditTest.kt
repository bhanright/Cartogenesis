package com.cartogenesis.cartography.geometry

import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The geometry guard at 2048 columns, where the artefacts show: the six audited seeds and the
 * author's own reported world, 969495, all on default settings at 1024 rows of square cells,
 * 2048 by 1024, with the 2048 by 2048 grid's cell width (`WorldGenConfig.forRows`).
 *
 * The census is printed whole, as `GeometryGuardTest`'s is at 512, and every clause is held to
 * [EXPECTED]: known failures by finding and signature, and the clauses too small to measure.
 *
 * Ruled lines get one more question here. A comb whose spacing is fixed in cells is the grid's and
 * one whose spacing is fixed on the ground may be the country's, and only the same world at two
 * grids tells the two apart: so wherever the census finds combs in a layer, the same seed is
 * generated at half the rows, 1024 by 512, and that layer read again, and each comb is matched
 * with the nearest comb on the coarser grid along the same bearing ([Census.pairCombs]). A comb
 * whose spacing in cells has doubled is fixed on the ground and let stand; one at the same count
 * of cells, or with no partner, stays a violation.
 *
 * One world at a time, dropped before the next.
 */
class GeometryGuardAuditTest {

    private companion object {
        val SEEDS = listOf(7L, 42L, 1234L, 99L, 718106L, 59758L, 969495L)
        const val ROWS = 1024
        const val PAIRED_ROWS = 512

        /** The census's name for its grid, by its columns: the 2048 of `GeometryExpectations.at2048`. */
        const val COLUMNS = 2 * ROWS

        /** What the census expects at 2048 columns; see `GeometryGuardTest.EXPECTED`. */
        val EXPECTED = Expectations().apply {
            GeometryExpectations.findings(this)
            GeometryExpectations.at2048(this)
        }
    }

    private fun config(seed: Long, rows: Int): WorldGenConfig = WorldGenConfig.forRows(seed = seed, rows = rows)

    @Test
    fun `every layer of the audited worlds and 969495 at 2048 columns, against every detector`() {
        val census = Census(COLUMNS, SEEDS.size)
        val pairing = ArrayList<String>()
        SEEDS.forEach { seed ->
            val fine = census.read(config(seed, ROWS))
            val withCombs = fine.readings.filter { it.combs.isNotEmpty() }
            if (withCombs.isEmpty()) return@forEach
            val coarse = Census(PAIRED_ROWS * 2, SEEDS.size).read(config(seed, PAIRED_ROWS))
            val fineFrame = GridFrame.of(config(seed, ROWS))
            val coarseFrame = GridFrame.of(config(seed, PAIRED_ROWS))
            for (reading in withCombs) {
                val pairings = Census.pairCombs(reading, fineFrame, coarse.readings.first { it.layer == reading.layer }, coarseFrame)
                reading.exemptGroundFixed(pairings.filter { it.groundFixed }.map { it.comb })
                pairings.forEach { pairing.add("GEOMETRY PAIRED ${fine.name} | ${reading.layer} | ${it.line}") }
            }
        }
        println("GEOMETRY SUMMARY $COLUMNS columns\n" + census.summary())
        pairing.forEach { println(it) }
        census.recordLines().forEach(::println)
        val failures = census.failures(EXPECTED)
        assertTrue(failures.isEmpty(), "geometry guard at $COLUMNS columns:\n" + failures.joinToString("\n"))
    }
}
