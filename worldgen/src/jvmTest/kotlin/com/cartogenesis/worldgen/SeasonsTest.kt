package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.ClimateStage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Whether the year actually has two halves, and whether they are the halves they should be.
 *
 * Two claims, and they fail in different ways. The first is that the seasonal swing is a property
 * of the *land*: the sea's heat capacity means the ocean surface barely moves over a year at a
 * latitude where the continent beside it moves fifteen degrees, and if this stage had simply
 * offset the latitude curve everywhere the open Atlantic would swing as far as Kazakhstan.
 *
 * The second is the payoff. The point of moving the belts rather than merely the thermometer is
 * that a west coast in the horse latitudes spends its summer under the subtropical high and its
 * winter under the westerlies, and so is dry in the warm half of the year and wet in the cold
 * one — a Mediterranean climate, which is not a rainfall total and cannot be classified from one.
 * That claim is asserted with seasons on and shown to fail with them off, where the class is
 * unreachable by construction.
 */
class SeasonsTest {

    private companion object {
        val seeds = listOf(7L, 42L, 1234L)
        const val size = 512

        /** The latitude the guard is stated at, and how wide a strip of rows counts as "at" it. */
        const val SAMPLE_LATITUDE = 35f
        const val SAMPLE_SPAN = 1.5f

        /**
         * How far inland of a west-facing shore a cell may sit and still be that coast's climate.
         *
         * At 512 wide this is a little over two degrees of longitude — a coastal strip, not a
         * continent, so an interior basin that happens to be dry in summer cannot be counted as a
         * Mediterranean coast.
         */
        const val INLAND_REACH = 12

        /**
         * How many such cells make a band rather than a handful of strays.
         *
         * Set from what the three seeds actually produce (see the printed figures), well clear of
         * both zero and the counts observed, since the guard has to discriminate "there is a
         * Mediterranean coast on this map" from "there are four cells of it".
         */
        const val BAND_CELLS = 40
    }

    @Test
    fun `at 35 degrees the land swings through the year and the open sea does not`() {
        val world = WorldGenerationEngine.generateBlocking(
            WorldGenConfig(seed = 42L, width = size, height = size)
        )
        val w = world.width
        val h = world.height

        var landGap = 0.0
        var landCells = 0
        var seaGap = 0.0
        var seaCells = 0

        for (y in 0 until h) {
            val lat = abs(ClimateStage.latitudeOf(y, h))
            if (abs(lat - SAMPLE_LATITUDE) > SAMPLE_SPAN) continue
            for (x in 0 until w) {
                val i = y * w + x
                val gap = world.climate.summerTemperature.data[i] -
                    world.climate.winterTemperature.data[i]
                if (world.sea.isLand[i]) {
                    landGap += gap
                    landCells++
                } else if (world.sea.relativeElevation.data[i] <= -0.12f) {
                    // Open ocean rather than any water: the shelf beside a coast is the one place
                    // the two populations would blur into each other.
                    seaGap += gap
                    seaCells++
                }
            }
        }

        assertTrue(landCells > 0 && seaCells > 0, "no land or no open sea at 35 degrees")
        val land = landGap / landCells
        val sea = seaGap / seaCells
        println(
            "SEASONS seed 42 at ${SAMPLE_LATITUDE.toInt()} deg: " +
                "land swing ${"%.1f".format(land)} C over $landCells cells, " +
                "open sea ${"%.1f".format(sea)} C over $seaCells cells"
        )

        assertTrue(land > 8.0, "land at 35 degrees swings only ${"%.1f".format(land)} C")
        assertTrue(sea < 4.0, "open sea at 35 degrees swings ${"%.1f".format(sea)} C")
    }

    @Test
    fun `a Mediterranean band sits on west-facing coasts in the horse latitudes`() {
        val counts = seeds.map { seed -> seed to measure(seed, seasons = true) }
        counts.forEach { (seed, tally) -> println("SEASONS seed $seed with seasons: $tally") }

        val withABand = counts.count { (_, tally) -> tally.westCoastMediterranean >= BAND_CELLS }
        assertTrue(
            withABand >= 2,
            "only $withABand of ${seeds.size} seeds grew a Mediterranean coast of at least " +
                "$BAND_CELLS cells between 30 and 45 degrees: " +
                counts.joinToString { "${it.first}=${it.second.westCoastMediterranean}" }
        )
    }

    @Test
    fun `without seasons there is no Mediterranean coast to find`() {
        // The same measurement against the same seeds with the year switched off. This is the
        // case that gives the one above its meaning: a guard that has only ever been green proves
        // nothing, and here the failure is total rather than marginal, because a class defined by
        // the ratio between two halves of the year cannot be reached when the two halves are the
        // same number.
        seeds.forEach { seed ->
            val tally = measure(seed, seasons = false)
            println("SEASONS seed $seed with seasons off: $tally")
            assertEquals(
                0, tally.westCoastMediterranean,
                "seed $seed found Mediterranean coast with seasons off"
            )
            assertEquals(0, tally.mediterranean, "seed $seed classed Mediterranean with seasons off")
            assertEquals(0, tally.monsoon, "seed $seed classed monsoon forest with seasons off")
        }
    }

    private data class Tally(
        val land: Int,
        val mediterranean: Int,
        val monsoon: Int,
        val westCoastMediterranean: Int
    ) {
        override fun toString(): String =
            "Mediterranean $mediterranean cells (${share(mediterranean)}), " +
                "of which $westCoastMediterranean on a west-facing coast at 30-45 deg; " +
                "monsoon forest $monsoon cells (${share(monsoon)})"

        private fun share(cells: Int) = "%.2f%%".format(cells * 100.0 / land.coerceAtLeast(1))
    }

    private fun measure(seed: Long, seasons: Boolean): Tally {
        val base = WorldGenConfig(seed = seed, width = size, height = size)
        val world = WorldGenerationEngine.generateBlocking(
            base.copy(climate = base.climate.copy(seasons = seasons))
        )
        val w = world.width
        val h = world.height

        var land = 0
        var mediterranean = 0
        var monsoon = 0
        var onWestCoast = 0

        for (y in 0 until h) {
            val lat = abs(ClimateStage.latitudeOf(y, h))
            val inBand = lat in 30f..45f
            for (x in 0 until w) {
                val i = y * w + x
                if (!world.sea.isLand[i]) continue
                land++
                when (world.climate.biome[i]) {
                    Biome.MEDITERRANEAN -> {
                        mediterranean++
                        if (inBand && facesWest(world, x, y)) onWestCoast++
                    }
                    Biome.MONSOON_FOREST -> monsoon++
                    else -> Unit
                }
            }
        }
        return Tally(land, mediterranean, monsoon, onWestCoast)
    }

    /** Whether the sea lies within [INLAND_REACH] cells due west, with only land in between. */
    private fun facesWest(world: WorldMap, x: Int, y: Int): Boolean {
        val w = world.width
        for (step in 1..INLAND_REACH) {
            val nx = ((x - step) % w + w) % w
            if (!world.sea.isLand[y * w + nx]) return true
        }
        return false
    }
}
