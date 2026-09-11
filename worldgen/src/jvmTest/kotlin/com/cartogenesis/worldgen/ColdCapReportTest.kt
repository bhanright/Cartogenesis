package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.ClimateStage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A5 measured why high-latitude west coasts stayed taiga/tundra despite abundant rainfall: not the
 * `coldCap` in `buildPrecipitation`, which this test was written to indict, but `classify` gating
 * temperate against taiga on the *annual mean* (`t < 7 -> TAIGA`) while the latitude curve puts 55
 * degrees near freezing — so even a strong warm-current anomaly could not lift a mild-winter coast
 * over the annual-mean bar. Bergen is temperate at an 8 C annual mean because its *coldest month*
 * is about 2 C, not because its year is warm. Rainfall was never the problem: 50-60 degree
 * west-facing coasts carried 1.88-3.83x their latitude's mean.
 *
 * A6 replaces the annual-mean gate with the seasonal one Koppen actually uses — see
 * `ClimateStage.classify` — and this test is A5's report turned into the assertion that finding
 * demanded. Per the ground rules, the assertion was run once against the classifier this
 * repository had before A6 and found to fail at 0.0/0.0/0.1% on seeds 7/42/1234 (see the A6 report
 * for the figure) before the fix made it pass.
 */
class ColdCapReportTest {

    private val seeds = listOf(7L, 42L, 1234L)

    /**
     * How far a cell must sit from any sea, east or west, to count as interior — far enough that
     * neither a coast's current nor its moisture plausibly reaches it. The Siberia check: taiga
     * inland at the same latitude must not have warmed along with the coast.
     */
    private val interiorReach = 40

    @Test
    fun `west-facing coasts with a warm current classify as temperate forest, and the interior does not`() {
        var seedsPassing = 0
        val perSeedShares = ArrayList<String>()

        for (seed in seeds) {
            val world = WorldGenerationEngine.generateBlocking(
                WorldGenConfig(seed = seed, width = 512, height = 512)
            )
            val w = world.width
            val h = world.height

            // Find land cells on a west-facing coast between 50-60° latitude.
            val coastCells = ArrayList<Int>()
            for (i in 0 until w * h) {
                if (!world.sea.isLand[i]) continue
                val y = i / w
                val x = i % w
                val lat = abs(ClimateStage.latitudeOf(y, h))
                if (lat !in 50f..60f) continue

                // West-facing: western neighbour (x-1, wrapping) is sea.
                val westX = (x - 1 + w) % w
                val westI = y * w + westX
                if (world.sea.isLand[westI]) continue

                coastCells.add(i)
            }

            // Classify the coast cells and measure their properties.
            var tempRainfallCount = 0
            var tempForestCount = 0
            var taigaCount = 0
            var tundraCount = 0
            var precipSum = 0f
            var anomalyWarmSum = 0f
            var anomalyWarmCount = 0
            var anomalyColdSum = 0f
            var anomalyColdCount = 0
            // Of the warm-anomaly coast cells specifically — the ones the guard is stated about.
            var warmAnomalyForestCount = 0

            coastCells.forEach { i ->
                val biome = world.climate.biome[i]
                when (biome) {
                    Biome.TEMPERATE_RAINFOREST -> tempRainfallCount++
                    Biome.TEMPERATE_FOREST -> tempForestCount++
                    Biome.TAIGA -> taigaCount++
                    Biome.TUNDRA -> tundraCount++
                    else -> {}
                }

                precipSum += world.climate.precipitation.data[i]

                // Measure ocean anomaly at the adjacent sea cells.
                val y = i / w
                val x = i % w
                var anomalySum = 0f
                var anomalyCount = 0
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny !in 0 until h) continue
                    for (dx in -1..1) {
                        val nx = (x + dx + w) % w
                        val ni = ny * w + nx
                        if (world.sea.isLand[ni]) continue
                        anomalySum += world.ocean.anomaly.data[ni]
                        anomalyCount++
                    }
                }
                if (anomalyCount > 0) {
                    val anom = anomalySum / anomalyCount
                    if (anom > 0f) {
                        anomalyWarmSum += anom
                        anomalyWarmCount++
                        if (biome == Biome.TEMPERATE_FOREST || biome == Biome.TEMPERATE_RAINFOREST) {
                            warmAnomalyForestCount++
                        }
                    } else {
                        anomalyColdSum += anom
                        anomalyColdCount++
                    }
                }
            }

            // Measure mean precipitation over all land at 50-60°.
            var latitudePrecipSum = 0f
            var latitudePrecipCount = 0
            for (i in 0 until w * h) {
                if (!world.sea.isLand[i]) continue
                val y = i / w
                val lat = abs(ClimateStage.latitudeOf(y, h))
                if (lat !in 50f..60f) continue
                latitudePrecipSum += world.climate.precipitation.data[i]
                latitudePrecipCount++
            }

            // Interior at the same latitude, far from any coast: the Siberia case. Taiga/tundra
            // here must not have collapsed just because the coast warmed.
            var interiorLand = 0
            var interiorTaigaTundra = 0
            for (i in 0 until w * h) {
                if (!world.sea.isLand[i]) continue
                val y = i / w
                val x = i % w
                val lat = abs(ClimateStage.latitudeOf(y, h))
                if (lat !in 50f..60f) continue
                var nearSea = false
                for (step in 1..interiorReach) {
                    val wx = (x - step + w) % w
                    val ex = (x + step) % w
                    if (!world.sea.isLand[y * w + wx] || !world.sea.isLand[y * w + ex]) {
                        nearSea = true
                        break
                    }
                }
                if (nearSea) continue
                interiorLand++
                val biome = world.climate.biome[i]
                if (biome == Biome.TAIGA || biome == Biome.TUNDRA) interiorTaigaTundra++
            }

            val rainforestForest = tempRainfallCount + tempForestCount
            val taigaTundra = taigaCount + tundraCount
            val rainforestShare = if (coastCells.isNotEmpty()) rainforestForest * 100 / coastCells.size else 0
            val taigaShare = if (coastCells.isNotEmpty()) taigaTundra * 100 / coastCells.size else 0
            val coastPrecipMean = if (coastCells.isNotEmpty()) precipSum / coastCells.size else 0f
            val latitudePrecipMean = if (latitudePrecipCount > 0) latitudePrecipSum / latitudePrecipCount else 0f
            val precipRatio = if (latitudePrecipMean > 0f) coastPrecipMean / latitudePrecipMean else 0f
            val warmAnomalyMean = if (anomalyWarmCount > 0) anomalyWarmSum / anomalyWarmCount else 0f
            val coldAnomalyMean = if (anomalyColdCount > 0) anomalyColdSum / anomalyColdCount else 0f
            val warmCoastForestShare =
                if (anomalyWarmCount > 0) warmAnomalyForestCount * 100.0 / anomalyWarmCount else 0.0
            val interiorTaigaTundraShare =
                if (interiorLand > 0) interiorTaigaTundra * 100.0 / interiorLand else 0.0

            if (warmCoastForestShare > 50.0) seedsPassing++
            perSeedShares.add("$seed=${"%.1f".format(warmCoastForestShare)}%")

            println(
                "COLDCAP seed $seed: ${coastCells.size} west-coast cells at 50-60°; " +
                    "$rainforestShare% rainforest/temperate-forest, $taigaShare% taiga/tundra; " +
                    "coast precip ${"%.2f".format(coastPrecipMean)} vs lat-mean ${"%.2f".format(latitudePrecipMean)} " +
                    "(${"%.2f".format(precipRatio)}x); " +
                    "warm coasts anom ${"%.1f".format(warmAnomalyMean)}°C ($anomalyWarmCount cells, " +
                    "${"%.1f".format(warmCoastForestShare)}% forest/rainforest); " +
                    "cold coasts ${"%.1f".format(coldAnomalyMean)}°C ($anomalyColdCount cells); " +
                    "interior taiga/tundra ${"%.1f".format(interiorTaigaTundraShare)}% of $interiorLand cells"
            )

            if (interiorLand > 0) {
                assertTrue(
                    interiorTaigaTundraShare > 50.0,
                    "seed $seed interior at 50-60° collapsed to only " +
                        "${"%.1f".format(interiorTaigaTundraShare)}% taiga/tundra ($interiorLand cells)"
                )
            }
        }

        // The guard: on at least two of three seeds, more than half of the west-facing coast at
        // 50-60° with a positive current anomaly classifies as temperate forest or rainforest.
        // Run against the classifier this repository had before A6, every seed reported 0.0-0.1%
        // (see the A6 report), so this is shown to fail without the fix and pass with it.
        assertTrue(
            seedsPassing >= 2,
            "only $seedsPassing of ${seeds.size} seeds put over half of their warm-current " +
                "west-facing coast at 50-60° into temperate forest/rainforest: " +
                perSeedShares.joinToString()
        )
    }
}
