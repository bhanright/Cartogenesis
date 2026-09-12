package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ClimateStage
import com.cartogenesis.worldgen.pipeline.OceanResult
import com.cartogenesis.worldgen.pipeline.OceanStage
import com.cartogenesis.worldgen.pipeline.PlateStage
import com.cartogenesis.worldgen.pipeline.SeaLevelStage
import com.cartogenesis.worldgen.pipeline.SnowBalance
import com.cartogenesis.worldgen.pipeline.TerrainStage
import com.cartogenesis.worldgen.pipeline.erodeBlocking
import kotlin.system.measureTimeMillis
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H2's cost, at export resolutions — the audit tier, because it generates the terrain of a 2048
 * world three sizes over (T1's split; see `worldgen/build.gradle.kts`).
 *
 * Two figures, and they answer different questions. The **balance** is the per-cell arithmetic rule
 * 8 asks to be measured before a shader is written for it. The **provisional climate** in front of
 * it is what the chunk actually costs a generation, since the balance is a rounding error beside
 * the moisture march that feeds it.
 */
class SnowBalanceAuditTest {

    @Test
    fun `report the cost of the balance and of the provisional climate`() {
        // Warm the JIT so the first size measured is not paying for compilation.
        WorldGenerationEngine.generateBlocking(
            WorldGenConfig(seed = 1L, width = 256, height = 256)
        )

        listOf(512, 1024, 2048).forEach { size ->
            val cfg = WorldGenConfig(seed = 42L, width = 128, height = 128).atResolution(size, size)
            val terrain = TerrainStage.generate(cfg)
            val plates = PlateStage.generate(cfg, terrain)
            val erosion = erodeBlocking(cfg, plates.height)
            val sea = SeaLevelStage.apply(erosion.height, cfg)

            var ocean: OceanResult? = null
            val oceanMs = measureTimeMillis { ocean = OceanStage.withoutCurrents(cfg, sea) }
            val climateMs = measureTimeMillis {
                ClimateStage.provisionalSnowBalance(cfg, sea, ocean!!)
            }

            // The balance on its own, out of the four fields the climate stage already has. Best of
            // five, because what rule 8 is asking is what the work costs, not what the slowest
            // scheduling of it costs.
            val generated = ClimateStage.generateWithSeasonalMm(cfg, sea, ocean!!)
            var balanceMs = Long.MAX_VALUE
            repeat(5) {
                val ms = measureTimeMillis {
                    SnowBalance.field(
                        sea.isLand,
                        generated.result.summerTemperature,
                        generated.result.winterTemperature,
                        generated.summerPrecipitationMm,
                        generated.winterPrecipitationMm
                    )
                }
                if (ms < balanceMs) balanceMs = ms
            }
            println(
                "SNOWBALANCE cost size=$size provisional ocean=${oceanMs}ms" +
                    " climate+balance=${climateMs}ms (total ${oceanMs + climateMs}ms added to a" +
                    " generation); balance alone=${balanceMs}ms"
            )
            if (size == 2048) {
                assertTrue(
                    "the balance costs ${balanceMs}ms at 2048, so rule 8's 50ms line has been" +
                        " crossed and the GPU path declined in SnowBalanceAccelerator needs" +
                        " revisiting",
                    balanceMs < 50
                )
            }
        }
    }
}
