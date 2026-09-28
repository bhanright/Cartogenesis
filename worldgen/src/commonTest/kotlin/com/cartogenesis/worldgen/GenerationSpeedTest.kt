package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.concurrent.parallelism
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ErosionStage
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.measureTime

/**
 * How long a world takes on whichever platform is running the tests.
 *
 * Reported rather than asserted, because the number is a property of the machine. It exists to
 * make one comparison possible: the JVM spreads generation across the pool, while Wasm has no
 * threads at all and runs every stage in order. Erosion is the great majority of the work, so the
 * gap between the two targets is close to the whole cost of losing parallelism — which is what
 * decides whether a browser front end can generate at a usable size.
 */
class GenerationSpeedTest {

    @Test
    fun `report generation time`() = runTest(timeout = 10.minutes) {
        // Warm whatever compiler is underneath before the measurement that counts.
        WorldGenerationEngine.generate(WorldGenConfig.forRows(1L, 128))

        listOf(256, 512).forEach { size ->
            val config = WorldGenConfig.forRows(234475L, 128)
                .atResolution(2 * size, size)
            val elapsed = measureTime { WorldGenerationEngine.generate(config) }
            println(
                "SPEED ${size}x$size in ${elapsed.inWholeMilliseconds} ms " +
                    "on ${parallelism()} thread(s), ${ErosionStage.sweepsFor(config)} erosion sweeps"
            )
        }
    }
}
