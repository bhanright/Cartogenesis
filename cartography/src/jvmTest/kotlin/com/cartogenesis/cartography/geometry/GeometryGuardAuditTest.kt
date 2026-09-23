package com.cartogenesis.cartography.geometry

import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The geometry guard at 2048, where the artefacts show: the six audited seeds and the author's
 * own reported world, 969495, all on default settings carried to 2048 the way the program carries
 * them (`WorldGenConfig.atResolution` from the 512 defaults).
 *
 * The census is printed whole, as `GeometryGuardTest`'s is at 512, and every measurable clause is
 * asserted, the known failures through [KnownFailures.expect].
 *
 * Ruled lines get one more question here. A comb whose spacing is fixed in cells is the grid's and
 * one whose spacing is fixed on the ground may be the country's, and only the same world at two
 * grids tells the two apart: so wherever the census at 2048 finds combs in a layer, the same seed
 * is generated at 1024 and that layer read again, and each comb at 2048 is matched with the
 * nearest comb at 1024 along the same bearing. A spacing fixed in cells keeps its count of cells
 * (ratio 1) and one fixed on the ground halves it (ratio 2).
 *
 * One 2048 world at a time, dropped before the next.
 */
class GeometryGuardAuditTest {

    private companion object {
        val SEEDS = listOf(7L, 42L, 1234L, 99L, 718106L, 59758L, 969495L)
        const val SIDE = 2048
        const val PAIRED_SIDE = 1024

        /** How far apart, in kilometres, a comb at 2048 and one at 1024 may lie and be one comb. */
        const val SAME_COMB_KM = 150.0

        /** The clauses that fail today at 2048, by `seed/layer/DETECTOR`, under their findings' names. */
        val KNOWN = mapOf<String, String>()
    }

    private fun config(seed: Long, side: Int): WorldGenConfig =
        WorldGenConfig(seed = seed, width = 512, height = 512).atResolution(side, side)

    @Test
    fun `every layer of the audited worlds and 969495 at 2048, against every detector`() {
        val census = Census(SIDE, Census.familySize(SEEDS.size))
        val pairing = ArrayList<String>()
        SEEDS.forEach { seed ->
            val fine = census.read(config(seed, SIDE))
            val withCombs = fine.readings.filter { it.combs.isNotEmpty() }
            if (withCombs.isEmpty()) return@forEach
            val coarseCensus = Census(PAIRED_SIDE, Census.familySize(SEEDS.size))
            val coarse = coarseCensus.read(config(seed, PAIRED_SIDE))
            val fineFrame = GridFrame.of(config(seed, SIDE))
            val coarseFrame = GridFrame.of(config(seed, PAIRED_SIDE))
            for (reading in withCombs) {
                val partner = coarse.readings.first { it.layer == reading.layer }
                for (comb in reading.combs) {
                    val x = comb.anchorXCells * fineFrame.cellWidthKm
                    val y = comb.anchorYCells * fineFrame.cellHeightKm
                    val match = partner.combs
                        .filter { fineFrame.bearingGapDegrees(it.bearingDegrees, comb.bearingDegrees) <= Combs.PARALLEL_DEGREES * 2 }
                        .minByOrNull { lengthOf(it.anchorXCells * coarseFrame.cellWidthKm - x, it.anchorYCells * coarseFrame.cellHeightKm - y) }
                    val line = if (match == null ||
                        lengthOf(match.anchorXCells * coarseFrame.cellWidthKm - x, match.anchorYCells * coarseFrame.cellHeightKm - y) > SAME_COMB_KM
                    ) {
                        "no comb at $PAIRED_SIDE within $SAME_COMB_KM km"
                    } else {
                        val ratio = comb.spacingCells / match.spacingCells
                        "at $PAIRED_SIDE %.2f cells apart: %.2f times the cells at %d, so %s".format(
                            match.spacingCells, ratio, SIDE,
                            if (ratio < 1.5) "fixed in cells — the grid's" else "fixed on the ground"
                        )
                    }
                    pairing.add("GEOMETRY PAIRED ${fine.name} | ${reading.layer} | ${comb.describe(fineFrame)} | $line")
                }
            }
        }
        println("GEOMETRY SUMMARY $SIDE\n" + census.summary())
        pairing.forEach { println(it) }
        val failures = census.failures(KNOWN)
        assertTrue(failures.isEmpty(), "geometry guard at $SIDE:\n" + failures.joinToString("\n"))
    }
}
