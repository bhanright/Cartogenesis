package com.cartogenesis.worldgen

import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A fingerprint of one generated world, printed on every target so the JVM's and Wasm's can be put
 * side by side.
 *
 * A save carries the whole world, so a world that generated differently on two platforms would
 * still reopen as itself on either; the comparison is informational, and CI reads these lines back
 * to make it. What a difference would say is that the platforms disagree somewhere in the
 * arithmetic: floating-point basics are specified exactly by IEEE-754, but transcendentals (`sin`,
 * `cos`, `pow`) are not, and those feed the FFT, where a difference in the last bit has plenty of
 * room to compound.
 *
 * The checksum is of the height field the map is drawn from. That the whole of a world is the same
 * from one generation to the next is `WorldFingerprintTest`'s, on the JVM, over every field.
 */
class CrossPlatformFingerprintTest {

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

        assertTrue(world.sea.landCellCount > 0, "the fingerprinted world has no land, so the lines above say nothing")
    }
}
