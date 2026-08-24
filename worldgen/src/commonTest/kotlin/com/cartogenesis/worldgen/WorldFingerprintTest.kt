package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The save format stores a seed rather than a world, so a saved world only travels between
 * platforms if the same seed generates the same map everywhere.
 *
 * This prints a fingerprint of a generated world. Run it on every target and compare: the numbers
 * agreeing means an Android save would reopen identically in a browser, and disagreeing means the
 * format needs to carry more than a seed. Floating-point basics are specified exactly by IEEE-754,
 * but transcendentals (`sin`, `cos`, `pow`) are not, and those feed the FFT — where a difference in
 * the last bit has plenty of room to compound.
 */
class WorldFingerprintTest {

    @Test
    fun `print a cross-platform fingerprint`() = runTest(timeout = 10.minutes) {
        val world = WorldGenerationEngine.generate(
            WorldGenConfig(seed = 42L, width = 128, height = 128)
        )

        // Mixing raw bits rather than the float values, so the checksum is sensitive to a
        // difference in the very last bit.
        var checksum = 0L
        world.sea.relativeElevation.data.forEach { value ->
            checksum = checksum * 31 + value.toRawBits()
        }

        val elevation = world.sea.relativeElevation.data
        println("FINGERPRINT elevation=$checksum")
        println("FINGERPRINT land=${world.sea.landCellCount} rivers=${world.rivers.rivers.size}")
        println("FINGERPRINT realms=${world.nations.nations.size} marks=${world.landmarks.landmarks.size}")
        println("FINGERPRINT samples=${elevation[0].toRawBits()},${elevation[8191].toRawBits()},${elevation[16383].toRawBits()}")
        println("FINGERPRINT firstRealm=${world.nations.nations.firstOrNull()?.name}")
        println("FINGERPRINT capital=${world.nations.nations.firstOrNull()?.capitalName}")

        assertTrue(world.sea.landCellCount > 0)
    }
}
