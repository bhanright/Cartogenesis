package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.OceanStage
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [OceanCurrentTest]'s sense bars on the author's own world at 2048, built as the app builds it.
 * In the audit tier: one 2048 world is minutes of erosion for one more world under the same bars.
 */
class OceanCurrentAuditTest {

    @Test
    fun `the gyres turn with the wind on the author's world at 2048`() {
        val config = WorldGenConfig(seed = 969495L, width = 512, height = 512).atResolution(2048, 2048)
        val world = WorldGenerationEngine.generateBlocking(config)
        val beltsOnly = config.copy(climate = config.climate.copy(pressureWinds = false))
        val failures = OceanSense.check("seed 969495 at 2048", config, world.sea, world.ocean, OceanStage.generate(beltsOnly, world.sea))
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
