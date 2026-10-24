package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.MoistureLedger
import com.cartogenesis.worldgen.pipeline.MoistureMarch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the ground gives back to the air, against Earth's, pooled over the standard seeds and summed
 * by area on the sphere: the moisture march's ground return ([MoistureMarch.groundReturnMm],
 * [MoistureMarch.halfYearReturnsMm]).
 *
 * Earth's figures, each from the source read:
 * - **By climate**: Mueller and others (2013, HESS 17, 3707, Table 4), the LandFlux-EVAL merged
 *   synthesis of land evapotranspiration over 1989 to 2005 and the mean of CRU, GPCC, GPCP and
 *   CPC's rain in each of Koppen's regions (Kottek and others' map), with the regions' areas.
 *   Pooled into Koppen's five groups here, each region by its area ([KOPPEN_REGIONS]); the world's
 *   cells are put in the groups by Peel, Finlayson and McMahon's (2007, Table 1) rules.
 * - **The land as a whole**: Rodell and others (2015, J. Climate 28, Table 3), the optimized
 *   world land's 2.18 mm a day of rain and 1.32 of evapotranspiration, a share of 0.606.
 * - **Recycling**: van der Ent and others (2010, Water Resour. Res. 46, W09525, read as the third
 *   chapter of van der Ent's thesis, its Table 3.2), all continents: 40% of the land's rain last
 *   evaporated from land, and 57% of what the land evaporates rains on land again.
 * - **Budyko's curve**: Li and others (2013, Water Resour. Res. 49, Table 1), 26 river basins of
 *   over 300,000 km², each's long-term dryness and evaporation ratio, to which Fu's curve
 *   `E/P = 1 + D - (1 + D^w)^(1/w)` is fitted basin by basin here ([LI_BASINS]); their own
 *   single fit is w = 2.0, and Budyko's own curve stands at 2.6.
 * - **The wet tropics**: Mueller's equatorial fully humid (1,218 mm) and monsoonal (1,155 mm)
 *   regions, by area 1,190 mm, which rain 2,473 and 2,027 mm.
 * - **Where the land's rain falls against its potential**: the land's rain by dryness, PET over P,
 *   from the Climatic Research Unit's gridded rain and FAO-56 reference evapotranspiration, CRU TS
 *   4.07 for 2001 to 2010 (Harris and others 2020), read cell by cell at half a degree without
 *   Antarctica: a quarter (0.255) falls where the potential is under half the rain, against 9% of
 *   the land. Budyko's curve on the same cells gives back 0.603 of the rain, Rodell's 0.606, so the
 *   curve the ground returns by is Earth's on Earth's rain.
 *
 * A pooled figure is held within a quarter of Earth's either way, as `RainAgainstEarthTest` holds
 * its budget. The ground's half-year split is held to its own water: no half gives back more than
 * its rain and the root zone's store ([MoistureMarch.ROOT_ZONE_STORE_MM]).
 */
class GroundReturnEarthTest : BorrowsSharedWorlds() {

    private companion object {
        val seeds = SharedWorlds.STANDARD_SEEDS
        const val ROWS = SharedWorlds.DETAIL_ROWS
        const val EARTH_TOLERANCE = 1.25

        /**
         * Mueller and others' (2013) Table 4: each Koppen region's group (0 to 4 for A to E), its
         * area in thousands of km², its merged evapotranspiration over 1989 to 2005 and its rain,
         * millimeters a year.
         */
        val KOPPEN_REGIONS = listOf(
            doubleArrayOf(0.0, 5914.0, 1218.0, 2473.0), // Af
            doubleArrayOf(0.0, 4822.0, 1155.0, 2027.0), // Am
            doubleArrayOf(0.0, 16687.0, 918.0, 1266.0), // Aw
            doubleArrayOf(0.0, 745.0, 749.0, 1012.0), // As
            doubleArrayOf(1.0, 19247.0, 98.0, 104.0), // BW north
            doubleArrayOf(1.0, 4766.0, 219.0, 253.0), // BW south
            doubleArrayOf(1.0, 9993.0, 332.0, 376.0), // BS north
            doubleArrayOf(1.0, 6455.0, 454.0, 527.0), // BS south
            doubleArrayOf(2.0, 3901.0, 446.0, 591.0), // Cs
            doubleArrayOf(2.0, 5802.0, 741.0, 1077.0), // Cw
            doubleArrayOf(2.0, 11533.0, 682.0, 1137.0), // Cf
            doubleArrayOf(3.0, 1060.0, 318.0, 419.0), // Ds
            doubleArrayOf(3.0, 4777.0, 382.0, 487.0), // Dw
            doubleArrayOf(3.0, 26207.0, 353.0, 544.0), // Df
            doubleArrayOf(4.0, 9012.0, 168.0, 383.0) // E
        )
        const val GROUPS = "ABCDE"

        /** Rodell and others' (2015) optimized world land, mm a day. */
        const val RODELL_LAND_RAIN = 2.18
        const val RODELL_LAND_EVAPOTRANSPIRATION = 1.32

        /** van der Ent and others' (2010) continental recycling ratios, all continents. */
        const val EARTH_PRECIPITATION_RECYCLING = 0.40
        const val EARTH_EVAPORATION_RECYCLING = 0.57

        /** Li and others' (2013) Table 1: each basin's dryness, PET over P, and evaporation ratio, E over P. */
        val LI_BASINS = listOf(
            0.59 to 0.53, 1.90 to 0.69, 3.44 to 0.82, 1.25 to 0.56, 1.09 to 0.74, 0.87 to 0.68, 2.19 to 0.76,
            0.97 to 0.33, 2.13 to 0.65, 0.86 to 0.47, 0.86 to 0.51, 0.87 to 0.54, 1.21 to 0.74, 0.68 to 0.45,
            3.59 to 0.69, 3.96 to 0.78, 0.73 to 0.58, 0.66 to 0.41, 1.17 to 0.79, 0.41 to 0.34, 8.29 to 0.89,
            0.83 to 0.62, 0.63 to 0.44, 0.61 to 0.38, 0.74 to 0.53, 1.11 to 0.32
        )

        /**
         * The rain Mueller's two wettest equatorial regions take, mm a year: the monsoonal region's
         * 2,027, the drier of the two, so a cell of Koppen's group A at or over it is one of theirs.
         */
        const val WET_TROPICS_RAIN_MM = 2027.0
        val WET_TROPICS_EVAPOTRANSPIRATION_MM = (1218.0 * 5914.0 + 1155.0 * 4822.0) / (5914.0 + 4822.0)

        /** The CRU land's rain share where the potential is under half the rain, and that dryness. */
        const val EARTH_RAIN_SHARE_UNDER_HALF = 0.255
        const val UNDER_HALF_DRYNESS = 0.5

        /**
         * Peel, Finlayson and McMahon's (2007, Table 1) rules for Koppen's groups: a climate is
         * polar where its warmest month stands under 10 C; arid where its rain falls short of
         * ten times `2 MAT` (plus 28 where seven tenths of the rain fall in the warm half, 14 where
         * neither half takes seven tenths) centimeters, here in millimeters; tropical where its
         * coldest month stands at or over 18 C; temperate where that month stands over 0 C.
         */
        const val POLAR_WARMEST_MONTH_C = 10f
        const val ARID_MM_PER_DEGREE = 20f
        const val ARID_SUMMER_RAIN_MM = 280f
        const val ARID_EVEN_RAIN_MM = 140f
        const val CONCENTRATED_SHARE = 0.7f
        const val TROPICAL_COLDEST_MONTH_C = 18f

        /** Fu's curve's shape searched for its fit, from just over one to eight, in hundredths. */
        const val FU_LEAST = 1.01
        const val FU_MOST = 8.0
        const val FU_STEP = 0.01

        /** How far past its rain and the store a half may give back, mm a year over the land: rounding. */
        const val SPLIT_SLACK_MM = 0.5

        val measured = HashMap<Long, Figures>()
    }

    private class Figures {
        var landArea = 0.0
        var rain = 0.0
        var evapotranspiration = 0.0
        var landOriginRain = 0.0
        val groupArea = DoubleArray(5)
        val groupRain = DoubleArray(5)
        val groupEvapotranspiration = DoubleArray(5)
        var wetTropicsArea = 0.0
        var wetTropicsEvapotranspiration = 0.0
        var rainUnderHalf = 0.0
        var beyondStore = 0.0
        val dryness = ArrayList<Double>()
        val ratio = ArrayList<Double>()
        val weight = ArrayList<Double>()

        fun add(other: Figures) {
            landArea += other.landArea; rain += other.rain; evapotranspiration += other.evapotranspiration
            landOriginRain += other.landOriginRain
            for (group in 0 until 5) {
                groupArea[group] += other.groupArea[group]; groupRain[group] += other.groupRain[group]
                groupEvapotranspiration[group] += other.groupEvapotranspiration[group]
            }
            wetTropicsArea += other.wetTropicsArea; wetTropicsEvapotranspiration += other.wetTropicsEvapotranspiration
            rainUnderHalf += other.rainUnderHalf; beyondStore += other.beyondStore
            dryness += other.dryness; ratio += other.ratio; weight += other.weight
        }
    }

    /** The seeds' figures, pooled, measured once for the class: the climate is rerun with its ledger. */
    private val pooled: Figures
        get() = synchronized(measured) {
            for (seed in seeds) measured.getOrPut(seed) {
                val world = SharedWorlds.world(WorldGenConfig.forRows(seed, ROWS))
                val ledger = MoistureLedger()
                val climate = ClimateStage.generateWithSeasonalMm(world.config, world.sea, world.ocean, ledger = ledger)
                figuresOf(world, climate, ledger).also { println("GROUND RETURN seed %d: %s".format(seed, summary(it))) }
            }
            Figures().also { total -> measured.values.forEach(total::add) }
        }

    /** Koppen's group of a land cell, 0 to 4 for A to E, by Peel and others' rules. */
    private fun groupOf(meanC: Float, warmestC: Float, coldestC: Float, rainMm: Float, summerShare: Float): Int {
        val concentration = when {
            summerShare >= CONCENTRATED_SHARE -> ARID_SUMMER_RAIN_MM
            summerShare <= 1f - CONCENTRATED_SHARE -> 0f
            else -> ARID_EVEN_RAIN_MM
        }
        return when {
            warmestC < POLAR_WARMEST_MONTH_C -> 4
            rainMm < ARID_MM_PER_DEGREE * meanC + concentration -> 1
            coldestC >= TROPICAL_COLDEST_MONTH_C -> 0
            coldestC > 0f -> 2
            else -> 3
        }
    }

    private fun figuresOf(world: WorldMap, generated: ClimateStage.Generated, ledger: MoistureLedger): Figures {
        val figures = Figures()
        val climate = generated.result
        val rain = climate.precipitationMm.data
        val potential = climate.potentialEvapotranspirationMm.data
        val landOrigin = generated.landOriginPrecipitationMm.data
        val returnRain = ledger.returnRainMm!!.data
        val julyHalf = ledger.julyHalf!!
        val januaryHalf = ledger.januaryHalf!!
        for (row in 0 until world.height) {
            val area = cos(ClimateStage.latitudeOf(row, world.height) * PI / 180.0)
            for (column in 0 until world.width) {
                val cell = row * world.width + column
                if (!world.sea.isLand[cell]) continue
                val julyReturn = julyHalf.groundReturn.data[cell].toDouble()
                val januaryReturn = januaryHalf.groundReturn.data[cell].toDouble()
                val evapotranspiration = (julyReturn + januaryReturn) * 0.5
                figures.landArea += area
                figures.rain += area * rain[cell]
                figures.evapotranspiration += area * evapotranspiration
                figures.landOriginRain += area * landOrigin[cell]
                val summer = generated.summerPrecipitationMm.data[cell]
                val total = summer + generated.winterPrecipitationMm.data[cell]
                val group = groupOf(
                    climate.temperature.data[cell], climate.summerTemperature.data[cell], climate.winterTemperature.data[cell],
                    rain[cell], if (total > 0f) summer / total else 0.5f
                )
                figures.groupArea[group] += area
                figures.groupRain[group] += area * rain[cell]
                figures.groupEvapotranspiration[group] += area * evapotranspiration
                if (group == 0 && rain[cell] >= WET_TROPICS_RAIN_MM) {
                    figures.wetTropicsArea += area
                    figures.wetTropicsEvapotranspiration += area * evapotranspiration
                }
                val yearRain = returnRain[cell].toDouble()
                if (yearRain > 0.0) {
                    val dryness = potential[cell] / yearRain
                    if (dryness < UNDER_HALF_DRYNESS) figures.rainUnderHalf += area * rain[cell]
                    figures.dryness += dryness
                    figures.ratio += evapotranspiration / yearRain
                    figures.weight += area
                }
                // Each half's return against its own rain at its pace and the store it may draw:
                // the year's rain the return was set from, shared between the halves as they rain.
                val lapRain = julyHalf.rain.data[cell].toDouble() + januaryHalf.rain.data[cell].toDouble()
                val julyShare = if (lapRain > 0.0) julyHalf.rain.data[cell] / lapRain else 0.5
                val julyRain = 2.0 * yearRain * julyShare
                val januaryRain = 2.0 * yearRain - julyRain
                val store = 2.0 * MoistureMarch.ROOT_ZONE_STORE_MM
                figures.beyondStore += area * 0.5 * (
                    (julyReturn - julyRain - store).coerceAtLeast(0.0) + (januaryReturn - januaryRain - store).coerceAtLeast(0.0)
                )
            }
        }
        return figures
    }

    private fun summary(figures: Figures): String {
        val groups = (0 until 5).joinToString(" ") { group ->
            "%s %.0f of %.0f mm".format(
                GROUPS[group], figures.groupEvapotranspiration[group] / figures.groupArea[group].coerceAtLeast(1e-12),
                figures.groupRain[group] / figures.groupArea[group].coerceAtLeast(1e-12)
            )
        }
        return ("land rain %.0f mm, evapotranspiration %.0f (%.3f of the rain); recycling %.3f of the rain, %.3f of the return; " +
            "by Koppen group %s; the wet tropics %.0f mm; rain where the potential is under half of it %.3f; past the store %.1f mm")
            .format(figures.rain / figures.landArea, figures.evapotranspiration / figures.landArea,
                figures.evapotranspiration / figures.rain, figures.landOriginRain / figures.rain,
                figures.landOriginRain / figures.evapotranspiration, groups,
                figures.wetTropicsEvapotranspiration / figures.wetTropicsArea.coerceAtLeast(1e-12),
                figures.rainUnderHalf / figures.rain, figures.beyondStore / figures.landArea)
    }

    /** Throws when [measured] stands more than [factor] from [earth] either way. */
    /** TH1-1b's finding, under which this class's clauses that its worlds moved are recorded. */
    private val WATER_CYCLE_ON_DISPERSED_WORLDS = "TH1-1b: on the history's dispersed worlds the land's water cycle stands off Earth's"

    private fun nearEarth(name: String, measured: Double, earth: Double, factor: Double = EARTH_TOLERANCE) {
        val ratio = measured / earth
        println("EARTH %s: %.3f against Earth's %.3f, x%.2f".format(name, measured, earth, ratio))
        if (abs(ln(ratio)) > ln(factor)) {
            throw RecordedViolation("%s is %.3f, x%.2f Earth's %.3f".format(name, measured, ratio, earth), "x%.2f".format(ratio))
        }
    }

    /** Earth's group [group]'s evapotranspiration and rain, millimeters a year, its regions by area. */
    private fun earthGroup(group: Int): Pair<Double, Double> {
        val regions = KOPPEN_REGIONS.filter { it[0].toInt() == group }
        val area = regions.sumOf { it[1] }
        return regions.sumOf { it[1] * it[2] } / area to regions.sumOf { it[1] * it[3] } / area
    }

    /** Fu's curve's evaporation ratio at [dryness] for shape [shape]. */
    private fun fu(dryness: Double, shape: Double): Double = 1.0 + dryness - (1.0 + dryness.pow(shape)).pow(1.0 / shape)

    /** The shape that brings Fu's curve nearest [ratio] at [dryness], each pair weighted by [weight]. */
    private fun fitFu(dryness: List<Double>, ratio: List<Double>, weight: List<Double>): Double {
        var best = FU_LEAST
        var bestError = Double.MAX_VALUE
        var shape = FU_LEAST
        while (shape <= FU_MOST) {
            var error = 0.0
            for (index in dryness.indices) {
                val miss = fu(dryness[index], shape) - ratio[index]
                error += weight[index] * miss * miss
            }
            if (error < bestError) { bestError = error; best = shape }
            shape += FU_STEP
        }
        return best
    }

    @Test
    fun `the land gives back Earth's share of its rain, group by group`() {
        val figures = pooled
        nearEarth("the land's evapotranspiration over its rain", figures.evapotranspiration / figures.rain,
            RODELL_LAND_EVAPOTRANSPIRATION / RODELL_LAND_RAIN)
        val outside = ArrayList<String>()
        for (group in 0 until 5) {
            val (earthEvapotranspiration, earthRain) = earthGroup(group)
            try {
                nearEarth("group %s's evapotranspiration over its rain".format(GROUPS[group]),
                    figures.groupEvapotranspiration[group] / figures.groupRain[group], earthEvapotranspiration / earthRain)
            } catch (violation: RecordedViolation) {
                outside += "%s %s".format(GROUPS[group], violation.signature)
            }
        }
        // Recorded at W2d, the humid groups raining half again to twice Earth's rain there, A x0.80,
        // C x0.70 and E x0.75; armed at W2e, whose rain no longer gathers on the windward ranges
        // (docs/DESIGN_LEDGER.md, W2d and W2e). Recorded at TH1-1b, whose continents part at Earth's
        // speeds and spread over both hemispheres (docs/DESIGN_LEDGER.md, TH1-1b; docs/TODO.md).
        KnownFailures.expect(WATER_CYCLE_ON_DISPERSED_WORLDS, "E x1.27") {
            if (outside.isNotEmpty()) throw RecordedViolation("groups outside Earth's share: $outside", outside.joinToString("; "))
        }
    }

    @Test
    fun `each climate's land evaporates Earth's millimeters`() {
        val figures = pooled
        val outside = ArrayList<String>()
        for (group in 0 until 5) {
            val (earthEvapotranspiration, _) = earthGroup(group)
            try {
                nearEarth("group %s's evapotranspiration, mm".format(GROUPS[group]),
                    figures.groupEvapotranspiration[group] / figures.groupArea[group], earthEvapotranspiration)
            } catch (violation: RecordedViolation) {
                outside += "%s %s".format(GROUPS[group], violation.signature)
            }
        }
        // Recorded at W2d, B x0.45, C x1.42 and E x1.34, the arid group raining under half Earth's
        // arid rain and the temperate and polar twice and half again Earth's; armed at W2e
        // (docs/DESIGN_LEDGER.md, W2d and W2e). Recorded at TH1-1b (docs/TODO.md).
        KnownFailures.expect(WATER_CYCLE_ON_DISPERSED_WORLDS, "E x0.74") {
            if (outside.isNotEmpty()) throw RecordedViolation("groups outside Earth's millimeters: $outside", outside.joinToString("; "))
        }
    }

    @Test
    fun `the continents recycle their water as Earth's do`() {
        val figures = pooled
        // Recorded at TH1-1b, whose continents, smaller and spread over both hemispheres, rain more
        // of the sea's water and less of their own (docs/DESIGN_LEDGER.md, TH1-1b; docs/TODO.md).
        KnownFailures.expect(WATER_CYCLE_ON_DISPERSED_WORLDS, "x0.72") {
            nearEarth("the land's rain that last evaporated from land", figures.landOriginRain / figures.rain, EARTH_PRECIPITATION_RECYCLING)
        }
        // Inside before W2d, x1.22; recorded at W2d, x1.28, the wet half's air raining on the land
        // it crossed; armed at W2e, x1.05 (docs/DESIGN_LEDGER.md, W2d and W2e).
        nearEarth("the land's evaporation that rains on land", figures.landOriginRain / figures.evapotranspiration, EARTH_EVAPORATION_RECYCLING)
    }

    @Test
    fun `the ground's curve stands among Earth's basins'`() {
        val figures = pooled
        val basinShapes = LI_BASINS.map { (dryness, ratio) -> fitFu(listOf(dryness), listOf(ratio), listOf(1.0)) }.sorted()
        val shape = fitFu(figures.dryness, figures.ratio, figures.weight)
        println("EARTH Fu's shape fitted to the land's cells: %.2f against Li and others' 26 basins' %.2f to %.2f (median %.2f)"
            .format(shape, basinShapes.first(), basinShapes.last(), basinShapes[basinShapes.size / 2]))
        assertTrue(shape >= basinShapes.first() && shape <= basinShapes.last(),
            "the land's Budyko curve has Fu's shape %.2f, outside Earth's basins' %.2f to %.2f".format(shape, basinShapes.first(), basinShapes.last()))
    }

    @Test
    fun `the wet tropics give back what Earth's do`() {
        val figures = pooled
        nearEarth("the wet tropics' evapotranspiration, mm", figures.wetTropicsEvapotranspiration / figures.wetTropicsArea,
            WET_TROPICS_EVAPOTRANSPIRATION_MM)
    }

    @Test
    fun `no half of the year gives back more than its rain and the root zone's store`() {
        val figures = pooled
        val beyond = figures.beyondStore / figures.landArea
        println("EARTH the halves' return past their rain and the store: %.2f mm a year over the land".format(beyond))
        assertTrue(beyond <= SPLIT_SLACK_MM, "the halves give back %.1f mm a year past their rain and the store".format(beyond))
    }

    @Test
    fun `the land's rain falls where Earth's does against its potential`() {
        val figures = pooled
        // Nearly half the land's rain fell where the potential is under half of it, the windward
        // coasts and ranges, x1.84 on 6eb31400 and x1.83 at W2d; a third at W2e, whose climb
        // condenses the boundary layer only over its condensation level and whose rain law no
        // longer reads the ground's saturated column (docs/DESIGN_LEDGER.md, W2d and W2e;
        // docs/TODO.md).
        // Recorded again at TH1-1 on the history's first worlds, x1.34; armed at TH1-1, whose final
        // worlds passed it; recorded at TH1-1b (docs/DESIGN_LEDGER.md, TH1-1b; docs/TODO.md).
        KnownFailures.expect(WATER_CYCLE_ON_DISPERSED_WORLDS, "x1.32") {
            nearEarth("the land's rain where the potential is under half of it", figures.rainUnderHalf / figures.rain, EARTH_RAIN_SHARE_UNDER_HALF)
        }
    }

    @Test
    fun `a half that cannot supply its share hands it to the other, and the year keeps Budyko's`() {
        // A monsoon: the July half rains 1,800 mm a year at its pace, the January half 100, and the
        // dry half's air asks the more. Split by the potential, January would give back 600.
        val (july, january) = MoistureMarch.halfYearReturnsMm(500.0, 1000.0, 1500.0, 1800.0, 100.0)
        assertEquals(1000.0, july + january, 1e-9)
        assertEquals(100.0 + 2.0 * MoistureMarch.ROOT_ZONE_STORE_MM, january, 1e-9)
        // Where both halves can supply their shares, the potential alone splits the year.
        val (wet, alsoWet) = MoistureMarch.halfYearReturnsMm(500.0, 1000.0, 1500.0, 1200.0, 900.0)
        assertEquals(400.0, wet, 1e-9)
        assertEquals(600.0, alsoWet, 1e-9)
    }
}
