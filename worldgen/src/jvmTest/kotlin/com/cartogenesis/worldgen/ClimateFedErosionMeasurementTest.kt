package com.cartogenesis.worldgen

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
     * the stage owes a second march at the midpoint. Measured as a root-mean-square change in the
     * flow weight over the land both terrains agree is land, and the weight's mean over land is
     * one by construction, so the figure reads directly as a share.
     */
    @Test
    fun `report how far the rainfall drifts across the rounds`() {
        for (seed in longArrayOf(7L, 42L, 1234L)) {
            val config = WorldGenConfig(seed = seed, width = 512, height = 512)
            val uplift = PlateStage.generate(config, TerrainStage.generate(config)).height

            // The terrain the first round is handed: the uplift with the opening thermal budget
            // spent on it, which is what `HydraulicErosion.apply` receives.
            val weathered = thermalSweepBlocking(config, uplift, skipSettled = true).height
            // And the terrain the last round leaves, cut the way the stage cut before it could
            // see the weather — the honest "after" for a question about what the cutting did.
            val flatRain = config.copy(erosion = config.erosion.copy(climateFeed = false))
            val carved = erodeBlocking(flatRain, uplift).height

            // And the ground halfway down, which is what the second march is taken on. Six rounds
            // of the twelve rather than the twelfth round's own terrain: the lowstand schedule is
            // written against the configured round count, so a six-round world's sea stands a
            // little differently, and this is the midpoint to within that.
            val halfway = erodeBlocking(
                flatRain.copy(erosion = flatRain.erosion.copy(hydraulicRounds = 6)), uplift
            ).height

            val atTheTop = HydraulicErosion.provisionalWeather(config, weathered, config.seaLevel)
            val atTheMiddle = HydraulicErosion.provisionalWeather(config, halfway, config.seaLevel)
            val atTheEnd = HydraulicErosion.provisionalWeather(config, carved, config.seaLevel)

            val topLand =
                SeaLevelStage.percentileCut(weathered, config.seaLevel, config.scale).isLand
            val middleLand =
                SeaLevelStage.percentileCut(halfway, config.seaLevel, config.scale).isLand
            val endLand =
                SeaLevelStage.percentileCut(carved, config.seaLevel, config.scale).isLand
            val shared = BooleanArray(topLand.size) {
                topLand[it] && middleLand[it] && endLand[it]
            }
            println(
                "S3 RAIN DRIFT seed=%d  top to end %.1f%%, middle to end %.1f%%, over %d cells"
                    .format(
                        seed,
                        drift(atTheTop.runoff, atTheEnd.runoff, shared) * 100,
                        drift(atTheMiddle.runoff, atTheEnd.runoff, shared) * 100,
                        shared.count { it }
                    )
            )
        }
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
        val uplift = PlateStage.generate(config, TerrainStage.generate(config)).height
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
     * The march's share of the erosion stage, which is the share it is worth taking to a device.
     *
     * Printed at the two grids the cost of a climate was last measured at. Rule 8 — a new per-cell
     * pass is specified with a GPU path from the start — is declined for this one, and this is the
     * figure it is declined on: the moisture march is a lock-step wavefront across the wind, which
     * is a sequential dependency a card cannot widen, and it is a low single-digit share of a
     * generation besides.
     */
    @Test
    fun `report the march's share of the erosion stage`() {
        for (cells in intArrayOf(512, 1024)) {
            val config = WorldGenConfig(seed = 42L, width = cells, height = cells)
            val uplift = PlateStage.generate(config, TerrainStage.generate(config)).height
            val weathered = thermalSweepBlocking(config, uplift, skipSettled = true).height

            val marchMs = measureTimeMillis {
                HydraulicErosion.provisionalWeather(config, weathered, config.seaLevel)
            }
            val worldMs = measureTimeMillis { WorldGenerationEngine.generateBlocking(config) }
            println(
                "S3 MARCH COST %d: one march %d ms, two %d ms, of a %d ms generation, %.1f%%"
                    .format(cells, marchMs, marchMs * 2, worldMs, marchMs * 200.0 / worldMs)
            )
        }
    }
}
