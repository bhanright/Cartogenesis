package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import java.util.Locale
import kotlin.math.cos
import kotlin.math.PI
import kotlin.test.Test
import org.junit.Assert.assertTrue

/**
 * The mountain ranges, found from the ground and read by the design's named-range instrument
 * ([RangeInstrument]), stratified by the kind and age of the belt each stands on, and each stratum
 * held to Earth's (docs/DESIGN_LEDGER.md, TH1-2).
 *
 * What the maintainer saw was ranges of one height along their whole length: no passes, no
 * transverse valleys, one long solid line. The figures that read it are the crest zone's dissection
 * (of the cells within 80 km of each station's crest, the share standing under 60% of it), how far
 * the crest holds high without a break, how many separate massifs the high ground makes, and how
 * the belt's width varies along strike. Earth's are ETOPO5's at 39 km cells, the grid the standard
 * worlds are read on (the design's `measures.tsv`), and each stratum is printed beside the Earth
 * ranges its kind matches in the curated table (GMRT, read at the same 39 km): the Andes for an
 * Andean belt, the Himalaya and the Alps for a collision, the Rockies for accreted terranes and a
 * flat slab's foreland, the Appalachians for an old belt.
 *
 * The bars are the design's, each where ETOPO5's Earth reads: the crest zone's low share at least
 * 0.10 (Earth 0.121, ETOPO5 being coarse in Asia and South America a floor), the high-ground run at
 * most 1.3 times Earth's 1,150 km, the massifs at least 1.9 per 1,000 km (Earth 2.2), the width's
 * variation at least 0.19 (Earth 0.225). A stratum is held where the standard worlds pool at least
 * [HELD_STRATUM_KM] of it: one range is a sample of one.
 *
 * Against passing by erasing the mountains: the ranges' count and length on the land, beside the
 * land's hypsometry (`HypsometryTest`). The control is the maintainer's complaint drawn on a map:
 * one range of one height along its whole length fails every clause. TH1-1b's worlds, read by the
 * same instrument at TH1-2, are in docs/DESIGN_LEDGER.md (TH1-2).
 */
class MountainRangesTest : BorrowsSharedWorlds() {

    @Test
    fun `the ranges are dissected as Earth's, stratum by stratum`() {
        val ranges = SharedWorlds.STANDARD_SEEDS.flatMap { RangeInstrument.ranges(worldOf(it)) }
        val complaints = complaintsOf(ranges, "TH1-2")
        assertTrue("the ranges are not dissected as Earth's: " + complaints.joinToString("; "), complaints.isEmpty())
    }

    /** The ranges are still there to be read: as many, and as long on the land, as Earth's. */
    @Test
    fun `the ranges are not erased to pass`() {
        var length = 0.0
        var count = 0
        var landKm2 = 0.0
        for (seed in SharedWorlds.STANDARD_SEEDS) {
            val world = worldOf(seed)
            val lengths = RangeInstrument.axisLengthsKm(world, RangeInstrument.YOUNG_THRESHOLD_METRES)
            length += lengths.sum()
            count += lengths.size
            landKm2 += landAreaKm2(world)
        }
        val perLand = length / (landKm2 / 1e6)
        println(
            "RANGES pooled: %d ranges above 1,500 m, %.0f km of them, %.1f km per million km2 of land (Earth %.1f, bar %.1f)"
                .format(Locale.ROOT, count, length, perLand, EARTH_YOUNG_RANGE_KM_PER_MKM2, EARTH_YOUNG_RANGE_KM_PER_MKM2 / 2)
        )
        // Recorded at TH1-2: the belts the history thickens stand lower than Earth's and TH1-1b's
        // stamped ones, and the replay of their window lowers them a further sixth to a third
        // (docs/DESIGN_LEDGER.md, TH1-2; docs/TODO.md).
        KnownFailures.expect(ERASED_FINDING, ERASED_SIGNATURE) {
            if (perLand < EARTH_YOUNG_RANGE_KM_PER_MKM2 / 2) {
                throw RecordedViolation("the ranges above 1,500 m run %.1f km per million km2 of land, under half Earth's".format(Locale.ROOT, perLand), ERASED_SIGNATURE)
            }
        }
    }

    /**
     * The control: the maintainer's own complaint drawn on a map, one range of one height along its
     * whole length, a Gaussian ridge 3,000 m high whose height falls by e 250 km either side of a
     * gentle arc,
     * fails every clause the young strata are held to. The range is found and read by the same
     * instrument as the worlds' (`RangeInstrument`), at the standard worlds' grid.
     */
    @Test
    fun `a range of one height along its whole length fails the guard`() {
        val config = WorldGenConfig.forRows(SharedWorlds.STANDARD_SEEDS.first(), SharedWorlds.DETAIL_ROWS)
        val width = config.width
        val height = config.height
        val elevation = DoubleArray(width * height)
        val land = BooleanArray(width * height) { true }
        for (row in 0 until height) {
            for (column in 0 until width) {
                // The arc's axis: a row that drifts a quarter of the grid's height over the range's span.
                val axisRow = height / 2.0 + height / 8.0 * kotlin.math.sin(PI * column / width)
                val acrossKm = (row - axisRow) * config.cellHeightKm
                val inSpan = column in width / 8 until width * 5 / 8
                elevation[row * width + column] = if (inSpan) 3_000.0 * kotlin.math.exp(-(acrossKm / 250.0).let { it * it }) else 200.0
            }
        }
        val ranges = RangeInstrument.axes(width, height, config.cellWidthKm, config.cellHeightKm, elevation, land, RangeInstrument.YOUNG_THRESHOLD_METRES)
            .map { axis ->
                RangeInstrument.figures(
                    RangeInstrument.swath(width, height, config.cellWidthKm, config.cellHeightKm, elevation, land, axis),
                    config.cellWidthKm, RangeInstrument.Stratum.COLLISION, axis
                )
            }
        val complaints = complaintsOf(ranges, "control, one height along its length")
        println("RANGES control: " + complaints.joinToString("; "))
        assertTrue("the instrument found no range in the control", ranges.isNotEmpty())
        for (clause in listOf("crest zone low share", "high run", "massifs", "width CV")) {
            assertTrue("a range of one height along its length passes the $clause clause", complaints.any { clause in it })
        }
    }

    private fun worldOf(seed: Long): WorldMap = SharedWorlds.world(WorldGenConfig.forRows(seed, SharedWorlds.DETAIL_ROWS))

    private fun landAreaKm2(world: WorldMap): Double {
        val config = world.config
        var area = 0.0
        for (cell in world.sea.isLand.indices) {
            if (!world.sea.isLand[cell]) continue
            val latitude = (90.0 - (cell / config.width + 0.5) * 180.0 / config.height) * PI / 180.0
            area += config.cellWidthKm * config.cellHeightKm * cos(latitude)
        }
        return area
    }

    companion object {

        /** Each stratum pooled, printed beside its Earth ranges, and the held ones' complaints. */
        internal fun complaintsOf(ranges: List<RangeInstrument.Range>, label: String): List<String> {
            val complaints = ArrayList<String>()
            for (stratum in RangeInstrument.Stratum.entries) {
                val members = ranges.filter { it.stratum == stratum }
                if (members.isEmpty()) continue
                val pooled = RangeInstrument.pooled(members)
                println(
                    ("RANGES %s %s: %d ranges, %.0f km; crest zone low %.3f, width CV %.3f, high run %.0f km, massifs %.2f per 1,000 km," +
                        " ranges across %.2f, crest CV %.3f, p90-p10 %.0f m, mean crest %.0f m, passes %.2f per 1,000 km; Earth's %s")
                        .format(
                            Locale.ROOT, label, stratum, pooled.count, pooled.lengthKm, pooled["crestZoneLowShare"], pooled["widthCv"],
                            pooled["highRunMeanKm"], pooled["massifsPer1000Km"], pooled["rangesAcrossMean"], pooled["cv"],
                            pooled["p90MinusP10M"], pooled["meanM"], pooled["passesPer1000Km"], EARTH_MATCHES[stratum].orEmpty()
                        )
                )
                if (pooled.lengthKm < HELD_STRATUM_KM) continue
                if (pooled["crestZoneLowShare"] < CREST_ZONE_LOW_BAR) {
                    complaints.add("$stratum crest zone low share (%.3f under %.2f)".format(Locale.ROOT, pooled["crestZoneLowShare"], CREST_ZONE_LOW_BAR))
                }
                if (pooled["highRunMeanKm"] > HIGH_RUN_BAR_KM) {
                    complaints.add("$stratum high run (%.0f km over %.0f)".format(Locale.ROOT, pooled["highRunMeanKm"], HIGH_RUN_BAR_KM))
                }
                if (pooled["massifsPer1000Km"] < MASSIFS_BAR) {
                    complaints.add("$stratum massifs (%.2f under %.1f)".format(Locale.ROOT, pooled["massifsPer1000Km"], MASSIFS_BAR))
                }
                if (pooled["widthCv"] < WIDTH_CV_BAR) {
                    complaints.add("$stratum width CV (%.3f under %.2f)".format(Locale.ROOT, pooled["widthCv"], WIDTH_CV_BAR))
                }
            }
            return complaints
        }

        /** ETOPO5's Earth at 39 km and the design's bars (`notes`' `measures.tsv`, TH1 design section 9). */
        const val CREST_ZONE_LOW_BAR = 0.10
        const val EARTH_HIGH_RUN_KM = 1_150.0
        const val HIGH_RUN_BAR_KM = 1.3 * EARTH_HIGH_RUN_KM
        const val MASSIFS_BAR = 1.9
        const val WIDTH_CV_BAR = 0.19

        /** A stratum is held where the standard worlds pool at least this much of it, km: two of the instrument's shortest ranges. */
        const val HELD_STRATUM_KM = 2 * RangeInstrument.SHORTEST_RANGE_KM

        /**
         * Earth's ranges above 1,500 m per million km² of land, read by the same instrument on ETOPO5
         * at 39 km equatorward of 70 degrees: 29,391 km of range axis over Earth's 148.9 million km² of
         * land (the polar land's ranges are not read, so this is a floor).
         */
        const val EARTH_YOUNG_RANGE_KM_PER_MKM2 = 29_391.0 / 148.9

        /** The curated table's ranges (GMRT at 39 km) each stratum is printed beside. */
        internal val EARTH_MATCHES: Map<RangeInstrument.Stratum, String> = mapOf(
            RangeInstrument.Stratum.ANDEAN to "Andes: crest zone low 0.328, width CV 0.375, high run 958 km, massifs 0.56, ranges across 1.02, crest CV 0.396",
            RangeInstrument.Stratum.COLLISION to "Himalaya: 0.254, 0.179, 1,170 km, 1.26, 0.10, 0.102; Alps: 0.227, 0.163, 704 km, 1.16, 0.09, 0.160",
            RangeInstrument.Stratum.TERRANE to "Rockies: 0.066, 0.034, 241 km, 1.94, 0.55, 0.220",
            RangeInstrument.Stratum.BACK_ARC to "Rockies: 0.066, 0.034, 241 km, 1.94, 0.55, 0.220",
            RangeInstrument.Stratum.OLD to "Appalachians: 0.337, 0.217, 704 km, 0.85, 0.02, 0.365"
        )

        const val ERASED_FINDING = "TH1-2: the ranges above 1,500 m run shorter on the land than Earth's"
        const val ERASED_SIGNATURE = "ranges above 1,500 m under half Earth's"
    }
}
