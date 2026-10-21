package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.BoundaryClass
import com.cartogenesis.worldgen.pipeline.CrustHistory
import com.cartogenesis.worldgen.pipeline.TectonicHistory
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * The tectonic history's own guards, read on its lattice before any map is drawn
 * (docs/DESIGN_LEDGER.md, TH1-1 and TH1-1b): the crust's budget closes, the plates are Earth's in
 * number, size and speed, the floor ages from its ridges, the crust stands within Earth's
 * thicknesses, and the same seed makes the same history.
 *
 * The history does not depend on the map's grid, so one history per standard seed serves every
 * clause; it is run once and kept for the class.
 *
 * Main had no history: its crust was a plate's type, its area whatever the plates' polygons
 * enclosed and its floor's age a blur of the boundary distance, so none of these has a figure on
 * main to fail on, and each clause's control is said where it is written.
 */
class TectonicHistoryTest {

    /**
     * The crust's area and volume at the end are what they were at the start plus everything the
     * budget booked as made and less everything it booked as lost, and the net is no more than
     * stretching could make.
     *
     * The closing is an independent count: the end is the samples counted, the closing is the start
     * plus the budget's terms, and every place crust is made or lost books itself. The net bar is
     * twice what Earth's own rifted margins added: Bradley's 105,000 km of passive margin today,
     * each adding `W (1 - 1/1.853)` of stretched crust per kilometer of its length
     * ([TectonicHistory.STRETCHED_WIDTH_PER_UNSTRETCHED], Watts's 300 km), over Earth's continental
     * crust, 0.41 of the sphere: 6.9%. Earth is midway through its dispersal; a world dispersed
     * twice as far, or assembled as tightly as it is dispersed, moves twice that.
     */
    @Test
    fun `the crust's area and volume budgets close`() {
        for (seed in SEEDS) {
            val history = historyOf(seed)
            val budget = history.budget
            val areaGap = abs(budget.closingAreaKm2 - budget.endingAreaKm2) / budget.startingAreaKm2
            val volumeGap = abs(budget.closingVolumeKm3 - budget.endingVolumeKm3) / budget.startingVolumeKm3
            val net = budget.endingAreaKm2 / budget.startingAreaKm2 - 1.0
            val config = configOf(seed)
            val gainPerKm = config.tectonics.riftedMarginWidthKm * (1.0 - 1.0 / TectonicHistory.STRETCHED_WIDTH_PER_UNSTRETCHED)
            val earthGain = EARTH_PASSIVE_MARGIN_KM * gainPerKm / (EARTH_CONTINENTAL_CRUST_SHARE * EARTH_SURFACE_KM2)
            val netBar = NET_BAR_IN_EARTH_GAINS * earthGain
            println(
                "HISTORY BUDGET seed %d: area %.4g -> %.4g km2 (net %+.4f, bar %.4f), closing gap %.2e; volume %.4g -> %.4g km3, closing gap %.2e"
                    .format(Locale.ROOT, seed, budget.startingAreaKm2, budget.endingAreaKm2, net, netBar, areaGap,
                        budget.startingVolumeKm3, budget.endingVolumeKm3, volumeGap)
            )
            assertTrue("seed $seed: the area budget does not close, gap $areaGap of the start", areaGap <= CLOSING_TOLERANCE)
            assertTrue("seed $seed: the volume budget does not close, gap $volumeGap of the start", volumeGap <= CLOSING_TOLERANCE)
            assertTrue("seed $seed: the crust's area moved by $net, more than $netBar", abs(net) <= netBar)
        }
    }

    /** The same seed, run twice, makes the same history to the bit. */
    @Test
    fun `the same seed makes the same history`() {
        val seed = SEEDS.first()
        val first = historyOf(seed)
        val second = TectonicHistory.run(configOf(seed))
        assertTrue(first.plateOf.contentEquals(second.plateOf))
        assertTrue(first.continental.contentEquals(second.continental))
        assertTrue(first.baseKm.contentEquals(second.baseKm))
        assertTrue(first.excessKm.contentEquals(second.excessKm))
        assertTrue(first.formedMyrAgo.contentEquals(second.formedMyrAgo))
        assertTrue(first.boundaryClass.contentEquals(second.boundaryClass))
        assertEquals(first.budget.endingVolumeKm3, second.budget.endingVolumeKm3, 0.0)
        for (plate in first.plateRotations.indices) assertTrue(first.plateRotations[plate].contentEquals(second.plateRotations[plate]))
    }

    /**
     * A dozen plates or more, and none larger than [LARGEST_PLATE_SHARE] of the sphere: Earth had
     * a dozen or more even while Pangaea stood, and its largest, the Pacific, is 0.205 of the sphere
     * (Bird 2003, Table 1: 2.577 of 12.566 steradians). The lattice is equal-area, so a plate's
     * share is its share of the points.
     *
     * Main's plates were drawn, not grown, and stood inside this (0.13 to 0.25 on the design's three
     * seeds); the design's prototype, which welded whole plates at every collision, did not, at 0.27
     * to 0.35, and that is the failure this guard is for.
     */
    @Test
    fun `the plates are Earth's in number and size`() {
        for (seed in SEEDS) {
            val history = historyOf(seed)
            val counts = IntArray(history.plateRotations.size)
            for (plate in history.plateOf) counts[plate]++
            val live = counts.count { it > 0 }
            val largest = counts.max().toDouble() / history.plateOf.size
            println("HISTORY PLATES seed %d: %d plates, the largest %.3f of the sphere".format(Locale.ROOT, seed, live, largest))
            assertTrue("seed $seed: $live plates, fewer than $FEWEST_PLATES", live >= FEWEST_PLATES)
            assertTrue("seed $seed: the largest plate is $largest of the sphere", largest <= LARGEST_PLATE_SHARE)
        }
    }

    /**
     * Once the supercontinent parts, the plates move at Earth's speeds: those more than half
     * continental at Zahirovic et al.'s 28 mm a year, the median of every such plate over the last
     * 200 Myr (*Tectonic speed limits from plate kinematic reconstructions*, EPSL 418, 2015,
     * section 3.5), the plates a slab pulls being scaled to their 85.
     *
     * The speeds are each live plate's root-mean-square speed at every step after the breakup, the
     * sample Zahirovic et al. take of Earth's plates since Pangea's; pooled over the seeds that
     * broke. The bars stand midway between Earth's figure and the history TH1-1 left, whose plates
     * felt only the drag under their bases: plates more than half continental at a median of 8 mm a
     * year against Earth's 28, so the bar is 18, and above it midway to the 85 of the fastest kind of
     * plate, 56.5. Printed beside it, not held: how a slab-pulled plate's speed falls with its area,
     * the slope of its log speed on its log area, where Earth's NNR-MORVEL56 Pacific, Nazca, Cocos and
     * Philippine Sea plates, 36 times apart in area, read 0.00 (Argus, Gordon and DeMets 2011,
     * Table 1); the slab's resistance takes the history's from basal drag's -0.29 to -0.22, a
     * third of the way.
     *
     * The control is the same histories with the slab's resistance at nothing, basal drag alone
     * (`TectonicsConfig.slabResistanceKm`), which fails it.
     */
    @Test
    fun `the plates move at Earth's speeds once the supercontinent parts`() {
        val model = speedsOf(SEEDS.map { historyOf(it) }, "model")
        val control = speedsOf(SEEDS.map { TectonicHistory.run(configOf(it).let { config -> config.copy(tectonics = config.tectonics.copy(slabResistanceKm = 0.0)) }) }, "basal drag alone")
        assertTrue("plates more than half continental move at ${model.first} mm a year at the median, under $CONTINENTAL_SPEED_LOW_BAR", model.first >= CONTINENTAL_SPEED_LOW_BAR)
        assertTrue("plates more than half continental move at ${model.first} mm a year at the median, over $CONTINENTAL_SPEED_HIGH_BAR", model.first <= CONTINENTAL_SPEED_HIGH_BAR)
        assertTrue(
            "the control passes, so the slab's resistance is not what brings the speeds to Earth's: ${control.first}",
            control.first < CONTINENTAL_SPEED_LOW_BAR
        )
    }

    /**
     * The median speed of the plates more than half continental after their breakup, mm a year, and
     * the slope of a slab-pulled plate's log speed against its log area, over [histories]; printed
     * with Zahirovic et al.'s other categories beside Earth's figures.
     */
    private fun speedsOf(histories: List<CrustHistory>, label: String): Pair<Double, Double> {
        val continental = ArrayList<Double>()
        val bounded = ArrayList<Double>()
        val noTrench = ArrayList<Double>()
        val all = ArrayList<Double>()
        val logArea = ArrayList<Double>()
        val logSpeed = ArrayList<Double>()
        for (history in histories) {
            if (history.breakupMyrAgo.isNaN()) continue
            val record = history.plateSpeeds
            for (index in 0 until record.size) {
                if (record.myrAgo[index] >= history.breakupMyrAgo) continue
                val speed = record.rmsSpeedMmPerYear[index].toDouble()
                val continent = record.continentalShare[index]
                val subducting = record.subductingEdgeShare[index]
                all.add(speed)
                if (continent > CONTINENTAL_PLATE_SHARE) continental.add(speed)
                if (subducting == 0f) noTrench.add(speed)
                if (continent < OCEANIC_PLATE_SHARE && subducting > SLAB_BOUNDED_EDGE_SHARE) {
                    bounded.add(speed)
                    if (speed > 0.0) {
                        logArea.add(ln(record.areaShare[index].toDouble()))
                        logSpeed.add(ln(speed))
                    }
                }
            }
        }
        fun median(values: List<Double>) = values.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }
        val meanArea = logArea.average()
        val meanSpeed = logSpeed.average()
        var covariance = 0.0
        var variance = 0.0
        for (index in logArea.indices) {
            covariance += (logArea[index] - meanArea) * (logSpeed[index] - meanSpeed)
            variance += (logArea[index] - meanArea) * (logArea[index] - meanArea)
        }
        val slope = if (variance > 0.0) covariance / variance else Double.NaN
        println(
            ("HISTORY SPEEDS %s: every plate %.1f mm/yr (Earth 42), over half continental %.1f (28), slab-bounded oceanic %.1f (85)," +
                " no trench %.1f (26); slope of log speed on log area for the slab-bounded %.3f (Earth 0.00); samples %d, %d, %d, %d")
                .format(Locale.ROOT, label, median(all), median(continental), median(bounded), median(noTrench), slope,
                    all.size, continental.size, bounded.size, noTrench.size)
        )
        return median(continental) to slope
    }

    /**
     * The floor is youngest at its ridges and falls in area with age: the floor at a ridge is no
     * older than its own spreading made it, and each sixty million years of age holds less floor than
     * the sixty before. A ridge point lies within a neighbor's reach of the axis, 1.6 of the
     * lattice's spacings, and floor that far out has been spreading for that distance over the
     * ridge's half rate; one step's carry besides, since a point is read at the end of the step it
     * moved in. The median, not the mean: a ridge newly opened through old floor, as a ridge jump
     * opens one, has old floor at its axis for its first steps. A floor made at the ridges and consumed at the trenches, oldest first, has its area
     * fall with age; one whose age were drawn some other way, as main's blur of the boundary
     * distance, has no reason to (main's floor read 12 Myr mean, 24 at its 90th percentile: the
     * design's measures).
     */
    @Test
    fun `the ocean floor ages from its ridges`() {
        for (seed in SEEDS) {
            val history = historyOf(seed)
            val config = configOf(seed)
            var ridgePoints = 0
            val ridgeAges = ArrayList<Double>()
            var ridgeHalfRateSum = 0.0
            val bins = DoubleArray(AGE_BINS)
            for (point in history.plateOf.indices) {
                if (history.continental[point]) continue
                val age = history.formedMyrAgo[point].toDouble()
                if (history.boundaryClass[point] == BoundaryClass.OCEAN_RIDGE.ordinal) {
                    ridgePoints++
                    ridgeAges.add(age)
                    // Opening is a negative closing; mm a year is km per Myr; half of it is each flank's.
                    ridgeHalfRateSum += -history.boundaryClosingMmPerYear[point] / 2.0
                }
                val bin = (age / AGE_BIN_MYR).toInt()
                if (bin < AGE_BINS) bins[bin]++
            }
            val ridgeMedian = ridgeAges.sorted().getOrElse(ridgeAges.size / 2) { 0.0 }
            val halfRate = ridgeHalfRateSum / ridgePoints.coerceAtLeast(1)
            val bound = RIDGE_REACH_SPACINGS * history.sphere.spacingKm / halfRate + config.tectonics.historyStepMyr
            println(
                "HISTORY FLOOR seed %d: %d ridge points of median age %.1f Myr, mean half rate %.1f km/Myr, bound %.1f; floor by 60 Myr of age %s"
                    .format(Locale.ROOT, seed, ridgePoints, ridgeMedian, halfRate, bound, bins.joinToString(" ") { "%.0f".format(Locale.ROOT, it) })
            )
            assertTrue("seed $seed: no ridge at all", ridgePoints > 0)
            assertTrue("seed $seed: the ridges do not open, half rate $halfRate", halfRate > 0.0)
            assertTrue("seed $seed: the floor at the ridges is $ridgeMedian Myr old at the median, older than $bound", ridgeMedian <= bound)
            for (bin in 1 until AGE_BINS) {
                assertTrue("seed $seed: more floor ${bin * 60}-${bin * 60 + 60} Myr old than younger", bins[bin] < bins[bin - 1])
            }
        }
    }

    /**
     * Continental crust thinner than the ocean's own has been stretched at a rift, as the
     * hyper-extended margins off Iberia and Newfoundland have; and crust thicker than Tibet's covers
     * no more of the continents than Earth's land above 4 km covers of its land.
     *
     * Tibet's crust is the standard column and the 33 km of excess the history's sutures begin with,
     * 74 km, which stands about 4.6 km above the standard column by Airy; Earth's land above 4 km is
     * 2.0% of its land (ETOPO5 at 39 km, the design's measures), and a share of the land is more
     * than the same area's share of the crust, so the bar is loose by the land's share of the crust.
     * No cap holds the crust under it: the lower crust's flow and erosion do (docs/DESIGN_LEDGER.md,
     * TH1-1).
     */
    @Test
    fun `the crust stands within Earth's thicknesses`() {
        for (seed in SEEDS) {
            val history = historyOf(seed)
            val config = configOf(seed)
            val tibetKm = config.isostasy.continentalCrustThicknessKm + TectonicHistory.TIBET_EXCESS_KM.toFloat()
            var continental = 0
            var thicker = 0
            var thinnest = Float.MAX_VALUE
            var thickest = 0f
            var thinUnstretched = 0
            for (point in history.plateOf.indices) {
                if (!history.continental[point]) continue
                val thickness = history.baseKm[point] + history.excessKm[point]
                continental++
                if (thickness > tibetKm) thicker++
                thinnest = minOf(thinnest, thickness)
                thickest = maxOf(thickest, thickness)
                if (thickness < config.isostasy.oceanicCrustThicknessKm && history.riftMyrAgo[point].isNaN()) thinUnstretched++
            }
            val share = thicker.toDouble() / continental
            println(
                "HISTORY THICKNESS seed %d: %.1f to %.1f km, %.4f of the crust thicker than Tibet's %.0f"
                    .format(Locale.ROOT, seed, thinnest, thickest, share, tibetKm)
            )
            assertEquals("seed $seed: continental points thinner than ocean floor that were never stretched", 0, thinUnstretched)
            assertTrue("seed $seed: $share of the crust is thicker than Tibet's", share <= EARTH_LAND_ABOVE_FOUR_KM_SHARE)
        }
    }

    private companion object {
        /** The standard seeds the rest of the suite reads, plus the author's own world. */
        val SEEDS = SharedWorlds.STANDARD_SEEDS + 718106L

        private val histories = HashMap<Long, CrustHistory>()

        fun configOf(seed: Long): WorldGenConfig = WorldGenConfig.forRows(seed, SharedWorlds.DETAIL_ROWS)

        @Synchronized
        fun historyOf(seed: Long): CrustHistory = histories.getOrPut(seed) { TectonicHistory.run(configOf(seed)) }

        /**
         * How far a budget's closing may sit from its count, as a share of the start: a sample's
         * thickness is single precision, 6e-8 of itself, and a history changes it a dozen times
         * or so; the area is counted in whole samples and closes to the double's own rounding.
         */
        const val CLOSING_TOLERANCE = 1e-6

        /** Bradley 2008: the length of Earth's passive margins today. */
        const val EARTH_PASSIVE_MARGIN_KM = 105_000.0

        /** Earth's continental crust, as a share of the sphere: the design's 0.41 (decisions 1). */
        const val EARTH_CONTINENTAL_CRUST_SHARE = 0.41
        const val EARTH_SURFACE_KM2 = 510.1e6

        /** The net bar in Earth's own gains from its rifted margins: see the clause. */
        const val NET_BAR_IN_EARTH_GAINS = 2.0

        const val FEWEST_PLATES = 12

        /**
         * Earth's Pacific's 0.205 and the five hundredths of the sphere a world's largest plate may
         * stand above it while a split through its floor is still being looked for (the history
         * looks again every few steps): the design's bar.
         */
        const val LARGEST_PLATE_SHARE = 0.25

        /** How far from a ridge's axis a point still reads as the ridge: a neighbor's reach (`FibonacciSphere`). */
        const val RIDGE_REACH_SPACINGS = 1.6

        const val AGE_BIN_MYR = 60.0
        const val AGE_BINS = 3

        const val EARTH_LAND_ABOVE_FOUR_KM_SHARE = 0.020

        /** Zahirovic et al.'s sorting of plates: over half continental, under a quarter, and more than a fifth of the edge subducting. */
        const val CONTINENTAL_PLATE_SHARE = 0.5f
        const val OCEANIC_PLATE_SHARE = 0.25f
        const val SLAB_BOUNDED_EDGE_SHARE = 0.2f

        /** Midway between Earth's 28 mm a year and the 8 of basal drag alone, and midway from 28 to the fastest plates' 85: see the clause. */
        const val CONTINENTAL_SPEED_LOW_BAR = 18.0
        const val CONTINENTAL_SPEED_HIGH_BAR = 56.5
    }
}
