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
class SeasonsTest : BorrowsSharedWorlds() {

    private companion object {
        val seeds = listOf(7L, 42L, 1234L)
        const val size = 512

        /** The latitude the guard is stated at, and how wide a strip of rows counts as "at" it. */
        const val SAMPLE_LATITUDE = 35f
        const val SAMPLE_SPAN = 1.5f

        /**
         * What the year is allowed to do at 35 degrees, in degrees Celsius, and the least the
         * land may beat the open sea by.
         *
         * All three are Earth's, at 35 degrees, and all three moved in W1, when the seasonal
         * amplitude stopped being a damping factor over water and became heat capacities in an
         * energy balance. The sea's ceiling was 4 C, which was the old model's own damped figure
         * rather than a measurement.
         *
         * **What the map's sea reads is marine air, not the sea surface**, and that is what sets
         * the ceiling. W1's third pass gave each sea band two temperatures — a fifty-metre mixed
         * layer and the air above it, coupled by a bulk surface flux — because a coast feels the
         * air and not the water; the map's temperature field over water is therefore the air. Over
         * the open North Atlantic at 35 N the water runs 19 to 27 C over the year and the North
         * Pacific 16 to 25, so the *water's* range there is 6 to 9; the air over it swings a degree
         * or two further, as air with a twentieth of the memory does, which puts the observed
         * marine-air range at 7 to 11 and the ceiling at 11.
         *
         * Land: a continental interior at that latitude swings about 26 C over the year (Kabul 26,
         * Tehran 25) and a west coast about 8 (Los Angeles), so a band mixing both keeps the floor
         * at 8. The ratio of land to marine air runs from 26/7 down to 8/11 across that spread, so
         * the guard asks for **1.5** — where the old figure of 2 was set against the *water*, which
         * swings less than the air over it and is not what the map stores.
         *
         * The model measures land 20.6 C, marine air 10.8 C, ratio 1.9.
         */
        const val LAND_SWING_FLOOR_C = 8.0
        const val SEA_SWING_CEILING_C = 11.0
        const val LAND_TO_SEA_RATIO = 1.5

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
         * Mediterranean coast on this map" from "there are four cells of it". A sample-size floor
         * chosen off this tree's worlds, then, and not a figure of Earth's.
         */
        const val BAND_CELLS = 40
    }

    @Test
    fun `at 35 degrees the land swings through the year and the open sea does not`() {
        val world = SharedWorlds.world(
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
                "land swing ${"%.1f".format(land)} C over $landCells cells (Earth 8-26), " +
                "marine air ${"%.1f".format(sea)} C over $seaCells cells (Earth 7-11, " +
                "the water under it 6-9), " +
                "ratio ${"%.1f".format(land / sea)} (Earth 0.7 to 3.7)"
        )

        assertTrue(land > LAND_SWING_FLOOR_C, "land at 35 degrees swings only ${"%.1f".format(land)} C")
        assertTrue(sea < SEA_SWING_CEILING_C, "open sea at 35 degrees swings ${"%.1f".format(sea)} C")
        assertTrue(
            land / sea >= LAND_TO_SEA_RATIO,
            "land swings only ${"%.1f".format(land / sea)} times as far as the open sea"
        )
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
        val world = SharedWorlds.world(
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
