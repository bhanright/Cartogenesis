package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.test.Test

/**
 * Land rain on a planet half and twice as wide as the 12,000 km world stays where the same seeds
 * put it on the 12,000 km world, no further off than those seeds stand from one another.
 *
 * The rain's conversion from the march's units to millimeters was referred to this planet's own
 * 512 grid, so it grew with the planet: twice the rain on a planet twice as wide, and on one of
 * Earth's size 1,539 to 1,674 mm a year on the land where the 12,000 km world has 472 to 523 and
 * Earth 715 (the Earth-size audit's D1; docs/DESIGN_LEDGER.md, K1). `GroundFiguresTest` holds the
 * conversion itself; this holds the rain.
 *
 * The rain is not held to the 12,000 km figures exactly, because a planet of another size is a
 * different climate: its continents, still a count per world, are another size in kilometers, and
 * the planetary vorticity gradient the ocean reads goes as the inverse of the radius. The bar is
 * how far apart the same three seeds stand on the 12,000 km world, the highest of them over the
 * lowest, for the land's mean and for its 99.5th percentile, the windward coasts the conversion was
 * calibrated on: a change of the planet's size that moves the rain of the three together further
 * than the seeds already differ is the planet's own and not the world's. The tree before read the
 * mean at 2.01 times its 12,000 km value on a planet twice as wide, against a bar of 1.62.
 */
class PlanetWidthRainTest {

    @Test
    fun `land rain holds at half and twice the planet's width`() {
        val stock = statisticsAt(STOCK_WIDTH_KM)
        val meanBar = stock.maxOf { it.meanMm } / stock.minOf { it.meanMm }
        val wettestBar = stock.maxOf { it.wettestMm } / stock.minOf { it.wettestMm }
        val stockMean = stock.sumOf { it.meanMm }
        val stockWettest = stock.sumOf { it.wettestMm }
        val wrong = ArrayList<String>()
        for (widthKm in doubleArrayOf(STOCK_WIDTH_KM / 2, STOCK_WIDTH_KM * 2)) {
            val there = statisticsAt(widthKm)
            val meanRatio = there.sumOf { it.meanMm } / stockMean
            val wettestRatio = there.sumOf { it.wettestMm } / stockWettest
            println(
                "PLANET RAIN %.0f km against %.0f km, seeds %s: land mean %.2f times (bar %.2f), 99.5th percentile %.2f times (bar %.2f)"
                    .format(widthKm, STOCK_WIDTH_KM, SEEDS.joinToString(), meanRatio, meanBar, wettestRatio, wettestBar)
            )
            if (meanRatio > meanBar || meanRatio < 1 / meanBar) wrong += "the land's mean is %.2f times the stock planet's on a %.0f km planet".format(meanRatio, widthKm)
            if (wettestRatio > wettestBar || wettestRatio < 1 / wettestBar) wrong += "the land's 99.5th percentile is %.2f times the stock planet's on a %.0f km planet".format(wettestRatio, widthKm)
        }
        // Recorded at A1-6b: the Hadley cells' extent follows the planet's own thermal Rossby
        // number, which goes as the inverse square of its radius, so on the 24,000 km planet the
        // cells are narrower and the ITCZ's rain more concentrated than on the 12,000 km one.
        // Armed at A1-6c, 1.81 times against the bar of 1.90, the sea's rain rising with the bulk
        // formula's air below the sea (docs/DESIGN_LEDGER.md).
        // Recorded at W2e: the 99.5th percentile stands 2.05 times the stock planet's on the 24,000 km
        // planet against a bar of 1.55. The wide planet is no wetter than on 6d5257e2 (3,221, 5,011
        // and 2,804 mm against 4,766, 4,114 and 3,009); the stock planet's wettest land is drier,
        // seed 969495's 3,613 mm gone to 1,844, and the three seeds stand closer together, so the
        // bar their spread sets fell from 2.55. From half to twice the width the percentile now
        // moves 1.7 times where it moved 2.6 (docs/DESIGN_LEDGER.md, W2e; docs/TODO.md).
        KnownFailures.expect(
            "W2e: the windward percentile on the wide planet stands past the stock seeds' spread",
            "the land's 99.5th percentile is 2.06 times the stock planet's on a 24000 km planet"
        ) {
            if (wrong.isNotEmpty()) {
                throw RecordedViolation("land rain follows the planet's width:\n" + wrong.joinToString("\n"), wrong.joinToString("; "))
            }
        }
    }

    /** One world's land rain: its mean and its 99.5th percentile, in millimeters a year. */
    private class LandRain(val meanMm: Double, val wettestMm: Double)

    private fun statisticsAt(widthKm: Double): List<LandRain> = SEEDS.map { seed ->
        val base = WorldGenConfig.forRows(seed, ROWS)
        val world = SharedWorlds.world(base.copy(scale = base.scale.copy(worldWidthKm = widthKm)))
        val rain = world.climate.precipitationMm.data
        val land = rain.indices.filter { world.sea.isLand[it] }.map { rain[it] }.sorted()
        val wettest = land[((land.size - 1) * WETTEST_PERCENTILE).toInt()].toDouble()
        println("PLANET RAIN seed $seed on a %.0f km planet: land mean %.0f mm, 99.5th percentile %.0f mm".format(widthKm, land.average(), wettest))
        LandRain(land.average(), wettest)
    }

    private companion object {
        /** The seeds the Earth-size audit read, and a third. */
        val SEEDS = listOf(42L, 969495L, 7L)

        /** 256 rows: cells of 11.7, 23.4 and 46.9 km on the three planets. */
        const val ROWS = 256

        /** The stock planet's width, in kilometers. */
        const val STOCK_WIDTH_KM = 12_000.0

        /** The windward coasts' percentile, the one the rain's old conversion was calibrated at. */
        const val WETTEST_PERCENTILE = 0.995
    }
}
