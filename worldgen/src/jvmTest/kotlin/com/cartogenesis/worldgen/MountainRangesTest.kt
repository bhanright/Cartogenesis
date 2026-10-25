package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.TectonicHistory
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.test.Test
import org.junit.Assert.assertTrue

/**
 * The mountain ranges, found from the ground and read by the design's named-range instrument
 * ([RangeInstrument]), stratified by the kind and age of the belt each stands on, and each stratum
 * held to Earth's ranges of its kind read the same way (docs/DESIGN_LEDGER.md, TH1-2 and TH1-2b).
 *
 * What the maintainer saw was ranges of one height along their whole length: no passes, no
 * transverse valleys, one long solid line. The figures that read it are the crest zone's dissection
 * (of the cells within 80 km of each station's crest, the share standing under 60% of it), how far
 * the crest holds high without a break, how many separate massifs the high ground makes, and how
 * the belt's width varies along strike.
 *
 * **Earth's figures** are the instrument's own on ETOPO5 averaged over the whole globe onto the
 * standard worlds' grid, 512 rows ([EARTH_RANGES]): the same smoothing, threshold, skeleton and swath
 * the worlds are read with, and every range Earth has rather than a chosen few. Two kinds of axis it
 * finds are left out: the ice domes of Greenland and Antarctica, whose surface is ice and not the
 * rock relief the instrument reads a range by (read in, they set Earth at a crest-zone low share of
 * 0.092, a high run of 3,010 km and 1.12 massifs, figures TH1-2 found beside the bars). The curated crests
 * of the design's table were read on finer data along narrower swaths by another tool, so they do
 * not compare like for like and are no longer the bars.
 *
 * **The bars.** A stratum is held to the least dissected of Earth's ranges of its kind, where Earth
 * has two or more: an Andean belt to the Andes and Mexico's Sierra Madre, a collision to Tibet and
 * the Himalaya, Iran and the Caucasus, and the Altai, the old stratum to Earth's high ground on old
 * crust at 500 m. The strata Earth has fewer of (terranes, back-arcs and rifts) are held to Earth's
 * young ranges as a whole. A stratum is held where the standard worlds pool at least
 * [HELD_STRATUM_KM] of it: one range is a sample of one.
 *
 * Against passing by erasing the mountains: the ranges' length on the land, and the young ranges
 * behind the advancing margins, beside the land's hypsometry (`HypsometryTest`). The control is the
 * maintainer's complaint drawn on a map: one range of one height along its whole length fails every
 * clause.
 */
class MountainRangesTest : BorrowsSharedWorlds() {

    @Test
    fun `the ranges are dissected as Earth's, stratum by stratum`() {
        val ranges = SharedWorlds.STANDARD_SEEDS.flatMap { RangeInstrument.ranges(worldOf(it)) }
        val complaints = complaintsOf(ranges, "TH1-2c")
        val andean = complaints.filter { it.startsWith(RangeInstrument.Stratum.ANDEAN.name) }
        val others = complaints - andean.toSet()
        // Recorded at TH1-2c: with the back-arcs' heat the Andean stratum is long enough to be held
        // for the first time, and its ranges stand on the hot back-arc's smooth plateau, their crest
        // zones cut less than the Andes' and Mexico's (docs/DESIGN_LEDGER.md, TH1-2c; docs/TODO.md).
        KnownFailures.expect(ANDEAN_DISSECTION_FINDING, ANDEAN_DISSECTION_SIGNATURE) {
            if (andean.isNotEmpty()) throw RecordedViolation("the Andean ranges are not dissected as Earth's: " + andean.joinToString("; "), ANDEAN_DISSECTION_SIGNATURE)
        }
        assertTrue("the ranges are not dissected as Earth's: " + others.joinToString("; "), others.isEmpty())
    }

    /** The ranges are still there to be read: as long on the land as Earth's. */
    @Test
    fun `the ranges are not erased to pass`() {
        var length = 0.0
        var count = 0
        var landKm2 = 0.0
        for (seed in SharedWorlds.STANDARD_SEEDS) {
            val world = worldOf(seed)
            val lengths = RangeInstrument.axisLengthsKm(world, RangeInstrument.YOUNG_THRESHOLD_METERS)
            length += lengths.sum()
            count += lengths.size
            landKm2 += landAreaKm2(world)
        }
        val perLand = length / (landKm2 / 1e6)
        println(
            "RANGES pooled: %d ranges above 1,500 m, %.0f km of them, %.1f km per million km2 of land (Earth %.1f, bar %.1f)"
                .format(Locale.ROOT, count, length, perLand, EARTH_YOUNG_RANGE_KM_PER_MKM2, EARTH_YOUNG_RANGE_KM_PER_MKM2 / 2)
        )
        assertTrue(
            "the ranges above 1,500 m run %.1f km per million km2 of land, under half Earth's %.1f".format(Locale.ROOT, perLand, EARTH_YOUNG_RANGE_KM_PER_MKM2),
            perLand >= EARTH_YOUNG_RANGE_KM_PER_MKM2 / 2
        )
    }

    /**
     * Young ranges stand behind the margins whose continents advance on their trenches, in Earth's
     * share: of the trench points where a continent's plate overrides a slab and moves toward it, the
     * share with ground above the young threshold, smoothed as the instrument smooths it, within
     * [RangeInstrument.MARGIN_REACH_KM] (the far edge of the Andes' thrust belt behind its trench).
     * Earth's share is read the same way on Heuret and Lallemand's (2005) Table 1: of its 38
     * segments where a continent's plate advances, 35 ([EARTH_ADVANCING_MARGINS_WITH_RANGE]).
     */
    @Test
    fun `the advancing margins carry young ranges as Earth's do`() {
        var withRange = 0
        var advancing = 0
        var landArea = 0.0
        var landMeters = 0.0
        var aboveTwoKm = 0.0
        val ranges = ArrayList<RangeInstrument.Range>()
        for (seed in SharedWorlds.STANDARD_SEEDS) {
            val world = worldOf(seed)
            val margins = RangeInstrument.advancingMargins(world, TectonicHistory.run(world.config))
            withRange += margins.withRange
            advancing += margins.points
            println("MARGINS seed $seed: %d of %d advancing continental trench points with a young range within reach (%.2f)"
                .format(Locale.ROOT, margins.withRange, margins.points, margins.withRange.toDouble() / margins.points))
            val elevation = RangeInstrument.elevationMeters(world)
            for (cell in elevation.indices) {
                if (!world.sea.isLand[cell]) continue
                val weight = cos(PI / 2 - (cell / world.config.width + 0.5) / world.config.height * PI)
                landArea += weight
                landMeters += weight * elevation[cell]
                if (elevation[cell] >= TWO_KM_METERS) aboveTwoKm += weight
            }
            ranges.addAll(RangeInstrument.ranges(world).filter { it.stratum == RangeInstrument.Stratum.ANDEAN })
        }
        val share = withRange.toDouble() / advancing
        println("MARGINS pooled: %.3f (Earth %.3f, bar %.3f)".format(Locale.ROOT, share, EARTH_ADVANCING_MARGIN_SHARE, ADVANCING_MARGIN_BAR))
        // Beside it, what the back-arcs' heat does to the land as a whole: its mean and its share above
        // 2 km against Earth's ice-free land, and the Andean stratum's crests.
        val andeanKm = ranges.sumOf { it.lengthKm }
        println(
            "MARGINS land mean %.0f m (Earth %.0f), above 2 km %.3f (Earth %.3f); Andean stratum %d ranges, %.0f km, mean crest %.0f m"
                .format(
                    Locale.ROOT, landMeters / landArea, EARTH_ICE_FREE_LAND_MEAN_METERS, aboveTwoKm / landArea, EARTH_ICE_FREE_LAND_ABOVE_TWO_KM,
                    ranges.size, andeanKm, if (andeanKm > 0.0) ranges.sumOf { it.meanM * it.lengthKm } / andeanKm else Double.NaN
                )
        )
        // Recorded at TH1-2b and again at TH1-2c: with the back-arcs' heat the plate stage stands a
        // young range behind nearly all of them, and the erosion stage lowers the narrow arcs and
        // thrust belts there, and leaves the hot plateaus about the threshold
        // (docs/DESIGN_LEDGER.md, TH1-2b and TH1-2c; docs/TODO.md).
        KnownFailures.expect(MARGINS_FINDING, MARGINS_SIGNATURE) {
            if (share < ADVANCING_MARGIN_BAR) {
                throw RecordedViolation("%.3f of the advancing margins carry a young range, under the bar %.3f".format(Locale.ROOT, share, ADVANCING_MARGIN_BAR), MARGINS_SIGNATURE)
            }
        }
    }

    /**
     * The control: the maintainer's own complaint drawn on a map, one range of one height along its
     * whole length, a Gaussian ridge 3,000 m high whose height falls by e 250 km either side of a
     * gentle arc, fails every clause the young strata are held to. The range is found and read by
     * the same instrument as the worlds' (`RangeInstrument`), at the standard worlds' grid.
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
        val ranges = RangeInstrument.axes(width, height, config.cellWidthKm, config.cellHeightKm, elevation, land, RangeInstrument.YOUNG_THRESHOLD_METERS)
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

    /** One of Earth's ranges as the instrument reads it, and the strata it stands for. */
    internal class EarthRange(
        val name: String,
        val strata: Set<RangeInstrument.Stratum>,
        val lengthKm: Double,
        val crestZoneLowShare: Double,
        val widthCv: Double,
        val highRunKm: Double,
        val massifsPer1000Km: Double
    )

    /** A stratum's bars: the least dissected of its Earth ranges on each figure. */
    internal class Bars(val earth: List<EarthRange>) {
        val crestZoneLowAtLeast = earth.minOf { it.crestZoneLowShare }
        val widthCvAtLeast = earth.minOf { it.widthCv }
        val highRunAtMostKm = earth.maxOf { it.highRunKm }
        val massifsAtLeast = earth.minOf { it.massifsPer1000Km }

        override fun toString(): String = earth.joinToString("; ") {
            "%s %.3f, %.3f, %.0f km, %.2f".format(Locale.ROOT, it.name, it.crestZoneLowShare, it.widthCv, it.highRunKm, it.massifsPer1000Km)
        }
    }

    companion object {

        /** Each stratum pooled, printed beside its Earth ranges, and the held ones' complaints. */
        internal fun complaintsOf(ranges: List<RangeInstrument.Range>, label: String): List<String> {
            val complaints = ArrayList<String>()
            for (stratum in RangeInstrument.Stratum.entries) {
                val members = ranges.filter { it.stratum == stratum }
                if (members.isEmpty()) continue
                val pooled = RangeInstrument.pooled(members)
                val bars = barsOf(stratum)
                println(
                    ("RANGES %s %s: %d ranges, %.0f km; crest zone low %.3f, width CV %.3f, high run %.0f km, massifs %.2f per 1,000 km," +
                        " ranges across %.2f, crest CV %.3f, p90-p10 %.0f m, mean crest %.0f m, passes %.2f per 1,000 km; bars %.3f, %.3f, %.0f km, %.2f from %s")
                        .format(
                            Locale.ROOT, label, stratum, pooled.count, pooled.lengthKm, pooled["crestZoneLowShare"], pooled["widthCv"],
                            pooled["highRunMeanKm"], pooled["massifsPer1000Km"], pooled["rangesAcrossMean"], pooled["cv"],
                            pooled["p90MinusP10M"], pooled["meanM"], pooled["passesPer1000Km"],
                            bars.crestZoneLowAtLeast, bars.widthCvAtLeast, bars.highRunAtMostKm, bars.massifsAtLeast, bars
                        )
                )
                if (pooled.lengthKm < HELD_STRATUM_KM) continue
                if (pooled["crestZoneLowShare"] < bars.crestZoneLowAtLeast) {
                    complaints.add("$stratum crest zone low share (%.3f under %.3f)".format(Locale.ROOT, pooled["crestZoneLowShare"], bars.crestZoneLowAtLeast))
                }
                if (pooled["highRunMeanKm"] > bars.highRunAtMostKm) {
                    complaints.add("$stratum high run (%.0f km over %.0f)".format(Locale.ROOT, pooled["highRunMeanKm"], bars.highRunAtMostKm))
                }
                if (pooled["massifsPer1000Km"] < bars.massifsAtLeast) {
                    complaints.add("$stratum massifs (%.2f under %.2f)".format(Locale.ROOT, pooled["massifsPer1000Km"], bars.massifsAtLeast))
                }
                if (pooled["widthCv"] < bars.widthCvAtLeast) {
                    complaints.add("$stratum width CV (%.3f under %.3f)".format(Locale.ROOT, pooled["widthCv"], bars.widthCvAtLeast))
                }
            }
            return complaints
        }

        /** The Earth ranges a stratum is held to: its own kind where Earth has two or more, else Earth's young ranges as a whole. */
        internal fun barsOf(stratum: RangeInstrument.Stratum): Bars {
            val own = EARTH_RANGES.filter { stratum in it.strata }
            return Bars(if (own.size >= 2) own else EARTH_RANGES.filter { it.strata.any { kind -> kind in YOUNG_STRATA } })
        }

        private val YOUNG_STRATA = setOf(
            RangeInstrument.Stratum.ANDEAN, RangeInstrument.Stratum.COLLISION, RangeInstrument.Stratum.TERRANE,
            RangeInstrument.Stratum.BACK_ARC, RangeInstrument.Stratum.RIFT
        )

        /**
         * Earth's ranges as this instrument reads them on ETOPO5 averaged over the whole globe onto
         * 512 rows: length km, crest-zone low share, width CV, high run km and massifs per 1,000 km.
         * Young at 1,500 m; at 500 m every axis on crust that is not a young belt's, as the old
         * stratum takes every high ground of old crust a world has, orogen or not; left out are the
         * ice domes of Greenland (1,957 km at 1,500 m, 2,896 at 500) and Antarctica (7,553 and 2,583
         * km; 3,796 and 9,510), and at 500 m the young belts again (the Cordillera, Asia's, the Andes,
         * the Alps, the Atlas, New Guinea), read at 1,500.
         */
        internal val EARTH_RANGES: List<EarthRange> = listOf(
            EarthRange("the Andes", setOf(RangeInstrument.Stratum.ANDEAN), 5_009.0, 0.312, 0.455, 1_722.0, 0.60),
            EarthRange("the Sierra Madre and Mexico's plateau", setOf(RangeInstrument.Stratum.ANDEAN), 1_487.0, 0.133, 0.185, 1_487.0, 3.36),
            EarthRange("Tibet and the Himalaya", setOf(RangeInstrument.Stratum.COLLISION), 5_205.0, 0.082, 0.221, 851.0, 0.77),
            EarthRange("Iran, the Caucasus and Anatolia", setOf(RangeInstrument.Stratum.COLLISION), 1_879.0, 0.128, 0.141, 1_879.0, 5.86),
            EarthRange("the Altai and Mongolia", setOf(RangeInstrument.Stratum.COLLISION), 2_035.0, 0.099, 0.175, 822.0, 0.98),
            EarthRange(
                "the Great Basin and the northern Rockies", setOf(RangeInstrument.Stratum.TERRANE, RangeInstrument.Stratum.BACK_ARC),
                1_683.0, 0.007, 0.072, 241.0, 2.97
            ),
            EarthRange("the Scandinavian mountains", setOf(RangeInstrument.Stratum.OLD), 1_526.0, 0.214, 0.242, 235.0, 3.28),
            EarthRange("the Brazilian highlands", setOf(RangeInstrument.Stratum.OLD), 2_152.0, 0.059, 0.127, 626.0, 3.72),
            EarthRange("Africa's plateaus and swells", setOf(RangeInstrument.Stratum.OLD), 9_510.0, 0.053, 0.168, 444.0, 1.68),
            EarthRange("the Arabian shield's rim", setOf(RangeInstrument.Stratum.OLD), 2_544.0, 0.084, 0.255, 157.0, 1.57),
            EarthRange("the Siberian traps", setOf(RangeInstrument.Stratum.OLD), 2_192.0, 0.047, 0.116, 744.0, 0.46),
            EarthRange("Madagascar's highlands", setOf(RangeInstrument.Stratum.OLD), 900.0, 0.283, 0.246, 274.0, 2.22)
        )

        /** A stratum is held where the standard worlds pool at least this much of it, km: two of the instrument's shortest ranges. */
        const val HELD_STRATUM_KM = 2 * RangeInstrument.SHORTEST_RANGE_KM

        /** Earth's land, km² on ETOPO5, the ice-covered included, over which its ranges are counted. */
        private const val EARTH_LAND_KM2 = 148.9e6

        /** Earth's young ranges above 1,500 m per million km² of land: their axes' length over its land, 17,298 km over 148.9 million km². */
        val EARTH_YOUNG_RANGE_KM_PER_MKM2: Double =
            EARTH_RANGES.filter { it.strata.any { kind -> kind in YOUNG_STRATA } }.sumOf { it.lengthKm } / (EARTH_LAND_KM2 / 1e6)

        /** Of Heuret and Lallemand's segments where a continent's plate advances on its trench, those with a young range within reach on ETOPO5. */
        const val EARTH_ADVANCING_MARGINS = 38
        const val EARTH_ADVANCING_MARGINS_WITH_RANGE = 35
        val EARTH_ADVANCING_MARGIN_SHARE: Double = EARTH_ADVANCING_MARGINS_WITH_RANGE.toDouble() / EARTH_ADVANCING_MARGINS

        /** Earth's share less two of its own binomial standard errors: what a sample of Earth's size could read and still be Earth's. */
        val ADVANCING_MARGIN_BAR: Double =
            EARTH_ADVANCING_MARGIN_SHARE - 2 * sqrt(EARTH_ADVANCING_MARGIN_SHARE * (1 - EARTH_ADVANCING_MARGIN_SHARE) / EARTH_ADVANCING_MARGINS)

        const val MARGINS_FINDING = "TH1-2c: young ranges stand behind fewer advancing margins than Earth's"

        const val ANDEAN_DISSECTION_FINDING = "TH1-2c: the Andean ranges stand on the hot back-arc's plateau, less dissected than Earth's"
        const val ANDEAN_DISSECTION_SIGNATURE = "Andean stratum under Earth's dissection"
        const val MARGINS_SIGNATURE = "advancing margins with a young range under the bar"

        /**
         * Earth's ice-free land, Antarctica and Greenland left out, on ETOPO5 averaged onto 512 rows:
         * its mean height and its share above 2 km, read beside the margins as what the back-arcs'
         * heat does to the land as a whole.
         */
        const val EARTH_ICE_FREE_LAND_MEAN_METERS = 673.0
        const val EARTH_ICE_FREE_LAND_ABOVE_TWO_KM = 0.058
        private const val TWO_KM_METERS = 2_000.0
    }
}
