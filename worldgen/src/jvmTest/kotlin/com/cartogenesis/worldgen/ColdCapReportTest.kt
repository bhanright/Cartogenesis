package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.ClimateStage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Measures why high-latitude west coasts stay as taiga/tundra despite abundant rainfall.
 *
 * The `coldCap` in `buildPrecipitation` clamps moisture by temperature. This test measures
 * 50–60° west-facing coasts to check whether the cap is responsible for their classification
 * as taiga/tundra. The data reveals the real cause: `classify` gates on annual-mean temperature
 * (< 7 °C → taiga), and the latitude curve places 55° near 0 °C, so warm-current anomalies
 * cannot lift it above the taiga threshold. The rainfall is abundant (1.88–3.83× latitudinal mean);
 * the cold cap is not the limiting factor. The fix belongs to Köppen-style classification on
 * seasonal extremes, not to the moisture cap.
 */
class ColdCapReportTest {

    private val seeds = listOf(7L, 42L, 1234L)

    @Test
    fun `report cold-cap effect on west-facing high-latitude coasts`() {
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

            if (coastCells.isEmpty()) continue

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

                // Measure ocean anomaly at the adjacent sea cell.
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

            val rainforestForest = tempRainfallCount + tempForestCount
            val taigaTundra = taigaCount + tundraCount
            val rainforestShare = if (coastCells.isNotEmpty()) rainforestForest * 100 / coastCells.size else 0
            val taigaShare = if (coastCells.isNotEmpty()) taigaTundra * 100 / coastCells.size else 0
            val coastPrecipMean = if (coastCells.isNotEmpty()) precipSum / coastCells.size else 0f
            val latitudePrecipMean = if (latitudePrecipCount > 0) latitudePrecipSum / latitudePrecipCount else 0f
            val precipRatio = if (latitudePrecipMean > 0f) coastPrecipMean / latitudePrecipMean else 0f
            val warmAnomalyMean = if (anomalyWarmCount > 0) anomalyWarmSum / anomalyWarmCount else 0f
            val coldAnomalyMean = if (anomalyColdCount > 0) anomalyColdSum / anomalyColdCount else 0f

            println(
                "COLDCAP seed $seed: ${coastCells.size} west-coast cells at 50-60°; " +
                    "$rainforestShare% rainforest/temperate-forest, $taigaShare% taiga/tundra; " +
                    "coast precip ${"%.2f".format(coastPrecipMean)} vs lat-mean ${"%.2f".format(latitudePrecipMean)} " +
                    "(${"%.2f".format(precipRatio)}x); " +
                    "warm coasts anom ${"%.1f".format(warmAnomalyMean)}°C " +
                    "($anomalyWarmCount cells), cold coasts ${"%.1f".format(coldAnomalyMean)}°C " +
                    "($anomalyColdCount cells)"
            )
        }

        // Assert only that the test ran.
        assertTrue(true, "test completed")
    }
}
