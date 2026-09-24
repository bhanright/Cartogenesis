package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.HydraulicErosion
import com.cartogenesis.worldgen.pipeline.OceanStage
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.SeaLevelStage
import com.cartogenesis.worldgen.pipeline.TerrainStage
import com.cartogenesis.worldgen.pipeline.erodeBlocking
import com.cartogenesis.worldgen.pipeline.thermalSweepBlocking
import kotlin.math.sqrt
import kotlin.system.measureTimeMillis
import kotlin.test.Test

/**
 * The three figures the climate-fed erosion was designed against: how far the rainfall moves while
 * the valleys are being cut, how much of it the skipped ocean currents are worth, and what the
 * march costs against a whole generation.
 *
 * All three are reports rather than assertions. They decide things — whether one march is enough,
 * whether the still ocean is an honest simplification, whether the cost needs a device path — and
 * a decision wants its evidence printed where a reader can re-take it, not a threshold that goes
 * green. The figures they produced are in docs/DESIGN_LEDGER.md, S3.
 */
class ClimateFedErosionMeasurementTest {

    /**
     * How much the provisional rainfall changes between the terrain the first round sees and the
     * terrain the last round leaves.
     *
     * This is the refresh question. If the rain the rounds would have cut with at the end is much
     * the same rain they cut with at the start, one march at the top is the whole of it; if not,
     * the stage owes a second march at the midpoint.
     *
     * Measured on the terrain the *fed* rounds leave as well as on the uniform-rain terrain, and
     * that matters: a world cut by rain-weighted discharge is not the world flat rain leaves, so
     * asking a uniform-rain terrain how far its rainfall has drifted answers a question about a
     * landscape this stage no longer produces. Both are printed. The field compared is the weight
     * the rounds actually route with - the march's rainfall over its own mean across that
     * terrain's land, which is what `normaliseOverLand` does inside the stage - so its mean is one
     * by construction and the root-mean-square change reads directly as a share of it.
     */
    @Test
    fun `report how far the rainfall drifts across the rounds`() {
        for (seed in longArrayOf(7L, 42L, 1234L)) {
            drift(WorldGenConfig(seed = seed, width = 512, height = 512))
        }
        // And once at an export grid, because the march's answer is a field over a grid and the
        // question is whether the drift is a property of the world or of the resolution.
        drift(WorldGenConfig(seed = 42L, width = 512, height = 512).atResolution(2048, 2048))
    }

    private fun drift(config: WorldGenConfig) {
        val plates = PlateStage.generate(config, TerrainStage.generate(config))
        val uplift = plates.height

        // The terrain the first round is handed: the uplift with the opening thermal budget spent
        // on it, which is what `HydraulicErosion.apply` receives.
        val weathered = thermalSweepBlocking(config, uplift, skipSettled = true).height
        val flatRain = config.copy(erosion = config.erosion.copy(climateFeed = false))

        for ((label, rounds) in listOf("fed" to config, "flat" to flatRain)) {
            val carved = erodeBlocking(rounds, uplift, upliftRateMmPerYear = plates.upliftRateMmPerYear).height
            // The ground halfway down, which is what the second march is taken on. Six of the
            // twelve rather than the twelfth round's own terrain: the lowstand schedule is written
            // against the configured round count, so a six-round world's sea stands a little
            // differently, and this is the midpoint to within that.
            val halfway = erodeBlocking(
                rounds.copy(erosion = rounds.erosion.copy(hydraulicRounds = 6)), uplift,
                upliftRateMmPerYear = plates.upliftRateMmPerYear
            ).height

            val atTheTop = weightsOver(config, weathered)
            val atTheMiddle = weightsOver(config, halfway)
            val atTheEnd = weightsOver(config, carved)
            val topLand =
                SeaLevelStage.percentileCut(weathered, config.seaLevel, config.scale).isLand
            val middleLand =
                SeaLevelStage.percentileCut(halfway, config.seaLevel, config.scale).isLand
            val endLand = SeaLevelStage.percentileCut(carved, config.seaLevel, config.scale).isLand
            val shared = BooleanArray(topLand.size) {
                topLand[it] && middleLand[it] && endLand[it]
            }
            println(
                ("S3 RAIN DRIFT seed=%d at %d, rounds run %s: top to end %.1f%%, middle to end " +
                    "%.1f%%, of the land mean, over %d cells").format(
                    config.seed, config.width, label,
                    drift(atTheTop, atTheEnd, shared) * 100,
                    drift(atTheMiddle, atTheEnd, shared) * 100,
                    shared.count { it }
                )
            )
        }
    }

    /** The weights a round over [terrain] would route with: rainfall over its own land mean. */
    private fun weightsOver(config: WorldGenConfig, terrain: FloatField): FloatArray {
        val cut = SeaLevelStage.percentileCut(terrain, config.seaLevel, config.scale)
        val rainfall =
            HydraulicErosion.provisionalWeather(config, terrain, config.seaLevel).rainfallMm
        var summed = 0.0
        for (cell in rainfall.indices) if (cut.isLand[cell]) summed += rainfall[cell].toDouble()
        val mean = (summed / cut.landCellCount).toFloat()
        return FloatArray(rainfall.size) { rainfall[it] / mean }
    }

    /** Root-mean-square difference between two flow-weight fields over the cells both call land. */
    private fun drift(before: FloatArray, after: FloatArray, land: BooleanArray): Double {
        var squared = 0.0
        var cells = 0
        for (cell in before.indices) {
            if (!land[cell]) continue
            val change = (after[cell] - before[cell]).toDouble()
            squared += change * change
            cells++
        }
        return if (cells == 0) 0.0 else sqrt(squared / cells)
    }

    /**
     * What the skipped gyre solve is worth to the rain the rounds cut with.
     *
     * The march runs on a still ocean, which is the same simplification the provisional snow
     * balance makes and for the same reason: the gyres are the expensive half of a climate. This
     * says how much rainfall that costs, so the simplification is stated with its figure rather
     * than asserted to be small.
     */
    @Test
    fun `report what the still ocean costs the provisional rainfall`() {
        val config = WorldGenConfig(seed = 42L, width = 512, height = 512)
        val plates = PlateStage.generate(config, TerrainStage.generate(config))
        val uplift = plates.height
        val weathered = thermalSweepBlocking(config, uplift, skipSettled = true).height
        val cut = SeaLevelStage.percentileCut(weathered, config.seaLevel, config.scale)

        val still =
            ClimateStage.generateWithSeasonalMm(config, cut, OceanStage.withoutCurrents(config, cut))
                .result.precipitationMm.data
        val circulating =
            ClimateStage.generateWithSeasonalMm(config, cut, OceanStage.generate(config, cut))
                .result.precipitationMm.data

        var squared = 0.0
        var summed = 0.0
        var cells = 0
        for (cell in still.indices) {
            if (!cut.isLand[cell]) continue
            val difference = (circulating[cell] - still[cell]).toDouble()
            squared += difference * difference
            summed += circulating[cell].toDouble()
            cells++
        }
        val rms = sqrt(squared / cells)
        val mean = summed / cells
        println(
            "S3 STILL OCEAN seed=42 at 512: rainfall differs by %.1f mm RMS over land, %.1f%% of the %.0f mm mean"
                .format(rms, rms / mean * 100, mean)
        )
    }

    /**
     * What the provisional climate costs against a whole generation, which is the share it would
     * be worth taking to a device.
     *
     * The *whole* provisional path and not the moisture march alone: the timed call is
     * `provisionalWeather`, which takes the sea-level percentile, solves the still ocean, runs the
     * seasonal marches, classifies the biomes and builds the vegetation density. All of that has
     * to happen before a round can cut, so all of it is the cost. Rule 8 — a new per-cell pass is
     * specified with a GPU path from the start — is declined for it on this figure: the moisture
     * march is a lock-step wavefront across the wind, a sequential dependency a card cannot widen.
     */
    @Test
    fun `report the provisional climate's share of a generation`() {
        for (cells in intArrayOf(512, 1024)) {
            val config = WorldGenConfig(seed = 42L, width = cells, height = cells)
            val uplift = PlateStage.generate(config, TerrainStage.generate(config)).height
            val weathered = thermalSweepBlocking(config, uplift, skipSettled = true).height

            val pathMs = measureTimeMillis {
                HydraulicErosion.provisionalWeather(config, weathered, config.seaLevel)
            }
            val worldMs = measureTimeMillis { WorldGenerationEngine.generateBlocking(config) }
            println(
                ("S3 MARCH COST %d: one provisional climate %d ms, the two the stage runs %d ms, " +
                    "of a %d ms generation, %.1f%%")
                    .format(cells, pathMs, pathMs * 2, worldMs, pathMs * 200.0 / worldMs)
            )
        }
    }
}
