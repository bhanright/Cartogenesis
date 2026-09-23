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

        /**
         * The clauses that fail today at 2048, by `seed/layer/DETECTOR`, under their findings'
         * names: which worlds each layer and detector fails on, as the census found them.
         */
        val KNOWN: Map<String, String> = buildMap {
            fun known(layer: String, detector: Detector, finding: String, vararg seeds: Long) {
                for (seed in seeds) put("$seed/$layer/${detector.name}", finding)
            }
            val all = SEEDS.toLongArray()
            known("coast as inked", Detector.FACING, GeometryFindings.COAST_INK, *all)
            for (layer in listOf("ice sheet ground", "ice carving")) {
                known(layer, Detector.ALIGNED_SIDE, GeometryFindings.ICE_EDGE_ALONG_A_ROW, *all)
                known(layer, Detector.FACETS, GeometryFindings.ICE_EDGE_ALONG_A_ROW, 1234L, 718106L, 59758L, 969495L)
            }
            known("ice occupancy", Detector.ALIGNED_SIDE, GeometryFindings.ICE_EDGE_ALONG_A_ROW, 7L, 1234L, 718106L, 59758L, 969495L)
            known("ice occupancy", Detector.FACETS, GeometryFindings.ICE_EDGE_ALONG_A_ROW, 7L, 1234L, 59758L, 969495L)
            known("ice as drawn", Detector.ALIGNED_SIDE, GeometryFindings.ICE_EDGE_ALONG_A_ROW, 7L, 42L, 99L, 718106L, 59758L, 969495L)
            known("ice as drawn", Detector.FACETS, GeometryFindings.ICE_EDGE_ALONG_A_ROW, 7L, 42L, 99L, 59758L)
            known("ice sheet ground", Detector.RIGHT_ANGLES, GeometryFindings.ICE_EDGE_CORNERS, 1234L)
            known("ice carving", Detector.RIGHT_ANGLES, GeometryFindings.ICE_EDGE_CORNERS, 99L)
            known("ice as drawn", Detector.RIGHT_ANGLES, GeometryFindings.ICE_EDGE_CORNERS, 99L)
            known("ice carving", Detector.ARCS, GeometryFindings.ICE_ARCS, 718106L)
            known("ice surface", Detector.ARCS, GeometryFindings.ICE_ARCS, 99L, 969495L)
            known("outlet troughs", Detector.ALIGNED_SIDE, GeometryFindings.OUTLET_TROUGH_ON_THE_GRID, 969495L)
            known("scour basins", Detector.ALIGNED_SIDE, GeometryFindings.SCOUR_BASIN_ON_THE_GRID, 7L, 42L)
            known("scour basins", Detector.FACETS, GeometryFindings.SCOUR_BASIN_ON_THE_GRID, 42L)
            for (layer in listOf("lakes", "lakes' open water")) {
                known(layer, Detector.ALIGNED_SIDE, GeometryFindings.LAKE_SHORE_ON_THE_GRID, 42L, 99L, 718106L, 59758L, 969495L)
            }
            known("lakes", Detector.FACETS, GeometryFindings.LAKE_SHORE_ON_THE_GRID, 718106L)
            known("lakes' open water", Detector.FACETS, GeometryFindings.LAKE_SHORE_ON_THE_GRID, 59758L)
            known("delta lobes", Detector.ARCS, GeometryFindings.DELTA_LOBES_ROUND, 99L, 718106L, 59758L, 969495L)
            known("delta lobes", Detector.FACETS, GeometryFindings.DELTA_LOBES_ROUND, 42L)
            known("delta lobes", Detector.ALIGNED_SIDE, GeometryFindings.DELTA_LOBES_ROUND, 59758L)
            known("coast", Detector.ARCS, GeometryFindings.DELTA_LOBES_ROUND, 99L, 718106L)
            known("coast as drawn", Detector.ARCS, GeometryFindings.DELTA_LOBES_ROUND, 718106L, 969495L)
            known("coast as drawn", Detector.ALIGNED_SIDE, GeometryFindings.DRAWN_COAST_ON_THE_GRID, 99L)
            known("lake fans", Detector.ALIGNED_SIDE, GeometryFindings.LAKE_FANS_STAMPED, 7L, 59758L)
            known("lake fans", Detector.ARCS, GeometryFindings.LAKE_FANS_STAMPED, 59758L)
            known("river courses", Detector.ARCS, GeometryFindings.RIVER_ARC, 99L, 718106L, 59758L)
            known("isobaths", Detector.FACETS, GeometryFindings.SEA_FLOOR_RAMPS, 7L, 42L, 1234L, 99L, 718106L)
            known("isobaths", Detector.ALIGNED_SIDE, GeometryFindings.SEA_FLOOR_RAMPS, 7L, 969495L)
            known("isobaths", Detector.ARCS, GeometryFindings.SEAMOUNT_CONES, *all)
            known("terrain contours", Detector.FACETS, GeometryFindings.STRAIGHT_RANGE_FRONTS, 1234L, 99L, 59758L)
            known("terrain contours", Detector.ALIGNED_SIDE, GeometryFindings.STRAIGHT_RANGE_FRONTS, 718106L, 59758L)
            known("terrain contours", Detector.ISOTROPY, GeometryFindings.STRAIGHT_RANGE_FRONTS, 42L)
            known("terrain contours", Detector.ARCS, GeometryFindings.TERRAIN_ARC, 99L)
            known("biome edges", Detector.ALIGNED_SIDE, GeometryFindings.BIOME_EDGES_ALONG_ROWS, *all)
            known("biome edges", Detector.FACETS, GeometryFindings.BIOME_EDGES_ALONG_ROWS, *all)
            known("biome edges", Detector.RIGHT_ANGLES, GeometryFindings.BIOME_EDGES_ALONG_ROWS, 99L)
            known("peoples' borders", Detector.ALIGNED_SIDE, GeometryFindings.PEOPLES_BORDERS_ON_THE_GRID, 7L, 42L, 99L, 718106L, 59758L, 969495L)
            known("peoples' borders", Detector.FACETS, GeometryFindings.PEOPLES_BORDERS_ON_THE_GRID, 42L)
            known("peoples' borders", Detector.RIGHT_ANGLES, GeometryFindings.PEOPLES_BORDERS_ON_THE_GRID, 99L)
            known("realm borders", Detector.RIGHT_ANGLES, GeometryFindings.REALM_BORDER_CORNERS, 718106L)
            known("isotherms", Detector.ISOTROPY, GeometryFindings.ISOTHERMS_ALONG_ROWS, *all)
            known("isotherms", Detector.FACETS, GeometryFindings.ISOTHERMS_ALONG_ROWS, 7L, 42L)
            known("isotherms", Detector.ALIGNED_SIDE, GeometryFindings.ISOTHERMS_ALONG_ROWS, 42L, 969495L)
            known("isohyets", Detector.FACETS, GeometryFindings.ISOHYETS_STRAIGHT, 42L, 969495L)
        }
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
