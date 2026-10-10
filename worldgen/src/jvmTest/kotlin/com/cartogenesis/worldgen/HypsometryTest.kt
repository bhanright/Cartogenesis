package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * How the land is spread over altitude, held to Earth's own continents.
 *
 * Earth's continental crust stands near sea level: half its land is under 500 m, its coasts are
 * backed by broad plains and fronted by shelves, and only the orogens and the ice sheets stand
 * high. Every figure here is read on the sphere — a cell weighted by the cosine of its latitude, the
 * share of the planet it stands for — because Earth's figures are areas.
 *
 * Earth's figures are NOAA's ETOPO5 five-minute grid tabulated by continent in 500 m bands of
 * altitude, and the tolerance on each is Earth's own spread between its seven continents. A world
 * is not one continent but several, so what its land total can be held to is Earth's land total
 * within the scatter a total of seven continents carries: the continents' standard deviation over
 * the square root of seven. Pooled over the standard worlds, which is four such totals, the pooled
 * figure's own scatter is half that again, so the bar stands two of its standard errors wide.
 * See docs/DESIGN_LEDGER.md, H1.
 */
class HypsometryTest : BorrowsSharedWorlds() {

    /**
     * The land's share in each of [BAND_TOPS_METRES]' bands, pooled over the standard worlds,
     * against Earth's land and Earth's spread between continents.
     */
    @Test
    fun `the land stands at Earth's altitudes`() {
        val pooled = DoubleArray(EARTH_LAND_SHARE_BY_BAND.size)
        var landArea = 0.0
        SharedWorlds.STANDARD_SEEDS.forEach { seed ->
            val figures = figuresOf(world(seed))
            println(
                "HYPSOMETRY seed %d: land under 500 m %.3f, 500 m-1 km %.3f, 1-2 km %.3f, above 2 km %.3f; under 200 m %.3f; mean %.0f m; water shallower than 500 m %.3f of the land's area"
                    .format(Locale.ROOT, seed, *figures.bandShares.toTypedArray(), figures.underTwoHundredShare, figures.meanLandMetres, figures.shallowWaterShareOfLand)
            )
            for (band in pooled.indices) pooled[band] += figures.bandShares[band] * figures.landArea
            landArea += figures.landArea
        }
        for (band in pooled.indices) pooled[band] /= landArea
        val complaints = pooled.indices.mapNotNull { band ->
            val earth = EARTH_LAND_SHARE_BY_BAND[band]
            val tolerance = EARTH_BAND_SPREAD_BETWEEN_CONTINENTS[band] / sqrt(EARTH_CONTINENTS.toDouble())
            println(
                "HYPSOMETRY pooled band %s: %.3f of the land against Earth's %.3f +- %.3f"
                    .format(Locale.ROOT, BAND_NAMES[band], pooled[band], earth, tolerance)
            )
            if (abs(pooled[band] - earth) > tolerance) {
                "%s %.3f against Earth's %.3f +- %.3f".format(Locale.ROOT, BAND_NAMES[band], pooled[band], earth, tolerance)
            } else null
        }
        assertTrue(
            "the land is not spread over altitude as Earth's is: " + complaints.joinToString("; "),
            complaints.isEmpty()
        )
    }

    /** The land's mean altitude, pooled, against Earth's and the spread of Earth's continents' means. */
    @Test
    fun `the land's mean altitude is Earth's`() {
        var sum = 0.0
        var area = 0.0
        SharedWorlds.STANDARD_SEEDS.forEach { seed ->
            val figures = figuresOf(world(seed))
            sum += figures.meanLandMetres * figures.landArea
            area += figures.landArea
        }
        val pooled = sum / area
        val tolerance = EARTH_MEAN_SPREAD_BETWEEN_CONTINENTS_METRES / sqrt(EARTH_CONTINENTS.toDouble())
        println(
            "HYPSOMETRY pooled mean land altitude %.0f m against Earth's %.0f +- %.0f"
                .format(Locale.ROOT, pooled, EARTH_MEAN_LAND_METRES, tolerance)
        )
        assertTrue(
            "the land's mean altitude is %.0f m, outside Earth's %.0f +- %.0f"
                .format(Locale.ROOT, pooled, EARTH_MEAN_LAND_METRES, tolerance),
            abs(pooled - EARTH_MEAN_LAND_METRES) <= tolerance
        )
    }

    /**
     * Water shallower than 500 m — the shelves, the banks and the shallow seas on the drowned rim
     * of the continents — as a share of the land's own area, pooled, against Earth's.
     */
    @Test
    fun `the continents stand on shelves Earth's width`() {
        var shallow = 0.0
        var land = 0.0
        SharedWorlds.STANDARD_SEEDS.forEach { seed ->
            val figures = figuresOf(world(seed))
            shallow += figures.shallowWaterShareOfLand * figures.landArea
            land += figures.landArea
        }
        val pooled = shallow / land
        val tolerance = EARTH_SHELF_SPREAD_BETWEEN_CONTINENTS / sqrt(EARTH_CONTINENTS.toDouble())
        println(
            "HYPSOMETRY pooled water shallower than 500 m %.3f of the land's area against Earth's %.3f +- %.3f"
                .format(Locale.ROOT, pooled, EARTH_SHELF_SHARE_OF_LAND, tolerance)
        )
        assertTrue(
            "water shallower than 500 m is %.3f of the land's area, outside Earth's %.3f +- %.3f"
                .format(Locale.ROOT, pooled, EARTH_SHELF_SHARE_OF_LAND, tolerance),
            abs(pooled - EARTH_SHELF_SHARE_OF_LAND) <= tolerance
        )
    }

    private class Figures(
        /** The land's area on the sphere, in cosine-weighted cells. */
        val landArea: Double,
        /** The land's share in each band of [BAND_TOPS_METRES]. */
        val bandShares: List<Double>,
        val underTwoHundredShare: Double,
        val meanLandMetres: Double,
        /** Water shallower than [SHALLOW_WATER_METRES], over the land's area. */
        val shallowWaterShareOfLand: Double
    )

    private fun figuresOf(world: WorldMap): Figures {
        val scale = world.config.scale
        val across = world.width
        val down = world.height
        val isLand = world.sea.isLand
        val relative = world.sea.relativeElevation.data
        val bands = DoubleArray(BAND_TOPS_METRES.size + 1)
        var land = 0.0
        var underTwoHundred = 0.0
        var metresSum = 0.0
        var shallow = 0.0
        for (cell in isLand.indices) {
            val weight = cos(Math.toRadians(90.0 - 180.0 * (cell / across + 0.5) / down))
            if (!isLand[cell]) {
                if (-scale.metresBelowShoreline(relative[cell]) < SHALLOW_WATER_METRES) shallow += weight
                continue
            }
            val metres = scale.metresAboveShoreline(relative[cell])
            var band = 0
            while (band < BAND_TOPS_METRES.size && metres >= BAND_TOPS_METRES[band]) band++
            bands[band] += weight
            land += weight
            metresSum += weight * metres
            if (metres < LOWLAND_TOP_METRES) underTwoHundred += weight
        }
        return Figures(
            land,
            bands.map { it / land },
            underTwoHundred / land,
            metresSum / land,
            shallow / land
        )
    }

    private fun world(seed: Long): WorldMap =
        SharedWorlds.world(WorldGenConfig.forRows(seed, SharedWorlds.DETAIL_ROWS))

    private companion object {
        /** The tops of the bands Earth's figures are tabulated in, in metres; the last is open. */
        val BAND_TOPS_METRES = floatArrayOf(500f, 1_000f, 2_000f)
        val BAND_NAMES = listOf("under 500 m", "500 m-1 km", "1-2 km", "above 2 km")

        /**
         * Earth's land in those bands, from ETOPO5 by continent: North America, South America,
         * Africa, Europe, Asia, Australia and Antarctica, 152.0 million km² between them, the
         * Antarctic figures being the ice's surface as this generator's are.
         *
         * Each continent's own shares, under 500 m / 500 m-1 km / 1-2 km / above 2 km: North America
         * 0.542 / 0.182 / 0.188 / 0.089, South America 0.708 / 0.158 / 0.056 / 0.077, Africa 0.464 /
         * 0.304 / 0.219 / 0.013, Europe 0.844 / 0.107 / 0.042 / 0.008, Asia 0.498 / 0.220 / 0.168 /
         * 0.114, Australia 0.876 / 0.099 / 0.018 / 0.007 and Antarctica 0.132 / 0.087 / 0.205 /
         * 0.577; their standard deviations between continents are the spread below.
         */
        val EARTH_LAND_SHARE_BY_BAND = doubleArrayOf(0.537, 0.196, 0.154, 0.113)
        val EARTH_BAND_SPREAD_BETWEEN_CONTINENTS = doubleArrayOf(0.257, 0.078, 0.086, 0.203)

        /** The seven continents the spread is taken between. */
        const val EARTH_CONTINENTS = 7

        /**
         * The land's mean altitude, in metres: Earth's 840 (`IsostasyConfig.continentalFreeboardMetres`
         * carries the figure); ETOPO5's bands read at their midpoints give 853. The seven
         * continents' means read the same way — 768, 645, 667, 367, 956, 337 and 2,083 m — spread
         * by 593 m.
         */
        const val EARTH_MEAN_LAND_METRES = 840.0
        const val EARTH_MEAN_SPREAD_BETWEEN_CONTINENTS_METRES = 593.0

        /**
         * Water shallower than 500 m off the seven continents and New Zealand, 38.0 million km² in
         * ETOPO5, over the land's 152.0: 0.25. Continent by continent, the same water over the same
         * land reads 0.37, 0.17, 0.07, 0.51, 0.26, 0.38 and 0.16, spread by 0.15.
         */
        const val EARTH_SHELF_SHARE_OF_LAND = 0.25
        const val EARTH_SHELF_SPREAD_BETWEEN_CONTINENTS = 0.15
        const val SHALLOW_WATER_METRES = 500f

        /** The top of the lowland band the K2 entry and the brief quote, printed beside the rest. */
        const val LOWLAND_TOP_METRES = 200f
    }
}
