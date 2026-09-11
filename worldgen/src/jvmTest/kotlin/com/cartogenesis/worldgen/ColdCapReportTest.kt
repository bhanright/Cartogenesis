package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.ClimateStage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A5 found the target this chunk closes: on every seed, 0% of west-facing coast cells at 50-60
 * degrees with a warm offshore current classified as forest, despite carrying 1.9-3.8x their
 * latitude's mean rainfall. The rain was never the problem -- `classify` gated temperate against
 * taiga on the *annual mean* (`t < 7 -> TAIGA`), and the latitude curve puts 55 degrees near
 * freezing, so even a strong warm-current anomaly could not lift a mild-winter coast over the
 * annual-mean bar. Bergen is temperate at an 8 C mean because its coldest month is about 2 C, not
 * because its year is warm.
 *
 * A6 replaces that gate with the seasonal one Koppen actually uses: the coldest month, not the
 * annual mean. This is A5's measurement extended into an assertion -- and, per the ground rules, it
 * is run once against the classifier this repository had before A6 (see the report for that
 * figure) so the assertion below is shown to fail without the fix rather than only ever pass.
 */
class ColdCapReportTest {

    private companion object {
        val seeds = listOf(7L, 42L, 1234L)
        const val size = 512
        const val LAT_MIN = 50f
        const val LAT_MAX = 60f

        /** How far inland of a west-facing shore a cell may sit and still be that coast's climate. */
        const val INLAND_REACH = 12

        /**
         * How far a cell must sit from open water, in any east/west direction, to stand in for the
         * Siberia case: an interior far enough from any coast that neither a current's warmth nor a
         * coast's moisture is plausibly reaching it.
         */
        const val INTERIOR_REACH = 40
    }

    @Test
    fun `west-facing coasts with a warm current classify as temperate forest, not the interior`() {
        val results = seeds.map { seed -> seed to measure(seed) }
        results.forEach { (seed, tally) -> println("COLDCAP seed $seed: $tally") }

        val passing = results.count { (_, tally) -> tally.forestShare() > 50.0 }
        assertTrue(
            passing >= 2,
            "only $passing of ${seeds.size} seeds put over half of their warm-current west-facing " +
                "coast at 50-60 deg into temperate forest/rainforest: " +
                results.joinToString { "${it.first}=${"%.1f".format(it.second.forestShare())}%" }
        )

        // Siberia stays taiga: the fix must not have also warmed the deep interior at the same
        // latitude, which has no coast to carry a current's anomaly to it.
        results.forEach { (seed, tally) ->
            assertTrue(
                tally.interiorTaigaOrTundraShare() > 50.0,
                "seed $seed interior at $LAT_MIN-$LAT_MAX deg collapsed to only " +
                    "${"%.1f".format(tally.interiorTaigaOrTundraShare())}% taiga/tundra"
            )
        }
    }

    private data class Tally(
        val coastCandidates: Int,
        val coastForest: Int,
        val interiorLand: Int,
        val interiorTaigaOrTundra: Int
    ) {
        fun forestShare(): Double = coastForest * 100.0 / coastCandidates.coerceAtLeast(1)
        fun interiorTaigaOrTundraShare(): Double =
            interiorTaigaOrTundra * 100.0 / interiorLand.coerceAtLeast(1)

        override fun toString(): String =
            "coast forest $coastForest/$coastCandidates (${"%.1f".format(forestShare())}%), " +
                "interior taiga/tundra $interiorTaigaOrTundra/$interiorLand " +
                "(${"%.1f".format(interiorTaigaOrTundraShare())}%)"
    }

    private fun measure(seed: Long): Tally {
        val world = WorldGenerationEngine.generateBlocking(
            WorldGenConfig(seed = seed, width = size, height = size)
        )
        val w = world.width
        val h = world.height

        var coastCandidates = 0
        var coastForest = 0
        var interiorLand = 0
        var interiorTaigaOrTundra = 0
        var coldSum = 0.0
        var warmSum = 0.0
        var anomalySum = 0.0

        for (y in 0 until h) {
            val lat = abs(ClimateStage.latitudeOf(y, h))
            if (lat < LAT_MIN || lat > LAT_MAX) continue
            for (x in 0 until w) {
                val i = y * w + x
                if (!world.sea.isLand[i]) continue

                val anomaly = offshoreAnomaly(world, x, y)
                if (anomaly != null && anomaly > 0f) {
                    coastCandidates++
                    coldSum += world.climate.winterTemperature.data[i]
                    warmSum += world.climate.summerTemperature.data[i]
                    anomalySum += anomaly
                    if (world.climate.biome[i] == Biome.TEMPERATE_FOREST ||
                        world.climate.biome[i] == Biome.TEMPERATE_RAINFOREST
                    ) {
                        coastForest++
                    }
                } else if (isInterior(world, x, y)) {
                    interiorLand++
                    if (world.climate.biome[i] == Biome.TAIGA || world.climate.biome[i] == Biome.TUNDRA) {
                        interiorTaigaOrTundra++
                    }
                }
            }
        }
        println(
            "COLDCAP DEBUG seed $seed: mean coast cold ${"%.2f".format(coldSum / coastCandidates.coerceAtLeast(1))}, " +
                "mean coast warm ${"%.2f".format(warmSum / coastCandidates.coerceAtLeast(1))}, " +
                "mean anomaly ${"%.2f".format(anomalySum / coastCandidates.coerceAtLeast(1))}"
        )
        return Tally(coastCandidates, coastForest, interiorLand, interiorTaigaOrTundra)
    }

    /**
     * The nearest sea cell's own anomaly, due west within [INLAND_REACH] cells -- unblurred, so
     * this is the current itself rather than how far its warmth has already crept inland.
     */
    private fun offshoreAnomaly(world: WorldMap, x: Int, y: Int): Float? {
        val w = world.width
        for (step in 1..INLAND_REACH) {
            val nx = ((x - step) % w + w) % w
            val ni = y * w + nx
            if (!world.sea.isLand[ni]) return world.ocean.anomaly.data[ni]
        }
        return null
    }

    /** No sea within [INTERIOR_REACH] cells to the east or west -- deep interior, the Siberia case. */
    private fun isInterior(world: WorldMap, x: Int, y: Int): Boolean {
        val w = world.width
        for (step in 1..INTERIOR_REACH) {
            val nxW = ((x - step) % w + w) % w
            val nxE = (x + step) % w
            if (!world.sea.isLand[y * w + nxW] || !world.sea.isLand[y * w + nxE]) return false
        }
        return true
    }
}
