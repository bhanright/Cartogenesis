package com.cartogenesis.cartography

import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.model.Acceleration
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A stored terrain has to come back exactly, and has to rebuild exactly the world it came from.
 *
 * This was the guarantee that let a world generated on the graphics card be saved at all. A save
 * has carried every stage since the container format, so nothing written today needs it; the
 * replay seam is kept, and tested, because it is what proves a stored terrain is a terrain.
 */
class TerrainSnapshotTest {

    @Test
    fun `every float survives the round trip`() = runTest(timeout = 10.minutes) {
        // Includes the values most likely to be mangled by a careless encoding.
        val heights = floatArrayOf(
            0f, -0f, 1f, -1f, 0.5f, 1e-8f, -1e-8f, 3.4e38f, -3.4e38f,
            Float.MIN_VALUE, Float.MAX_VALUE, 0.1f, 0.2f, 0.3f, 123.456f, -987.654f
        )
        val restored = TerrainSnapshot.of(4, 4, heights).decode()

        assertEquals(heights.size, restored.size)
        for (i in heights.indices) {
            // Raw bits, not equality: this must be exact, and it should also carry the sign of a
            // negative zero rather than quietly normalising it.
            assertEquals(
                heights[i].toRawBits(), restored[i].toRawBits(),
                "float $i did not survive: ${heights[i]} became ${restored[i]}"
            )
        }
    }

    @Test
    fun `a stored terrain rebuilds the same world`() = runTest(timeout = 10.minutes) {
        val config = WorldGenConfig(seed = 234475L, width = 256, height = 256)
            .let { it.copy(erosion = it.erosion.copy(acceleration = Acceleration.GPU)) }

        // No accelerator, so this falls back to the CPU — which is exactly the situation of a GPU
        // world being opened on a machine that has none.
        val original = WorldGenerationEngine.generate(config)
        val snapshot = TerrainSnapshot.of(
            original.width, original.height, original.erosion.height.data
        )

        // Now rebuild from the snapshot rather than by eroding again.
        val reopened = WorldGenerationEngine.generate(
            config, accelerator = StoredTerrain(snapshot)
        )

        for (i in original.erosion.height.data.indices) {
            assertEquals(
                original.erosion.height.data[i].toRawBits(),
                reopened.erosion.height.data[i].toRawBits(),
                "terrain differed at cell $i"
            )
        }
        assertEquals(original.sea.landCellCount, reopened.sea.landCellCount)
        assertEquals(original.rivers.rivers.size, reopened.rivers.rivers.size)
        assertEquals(original.nations.nations.size, reopened.nations.nations.size)
        for (i in original.nations.nationId.indices) {
            assertEquals(original.nations.nationId[i], reopened.nations.nationId[i])
        }
    }

    @Test
    fun `a stored terrain declines a grid it was not taken at`() = runTest(timeout = 10.minutes) {
        // Export re-runs at a larger size, where a snapshot of the working resolution has no
        // business being used. It must decline rather than stretch what it holds.
        val snapshot = TerrainSnapshot.of(4, 4, FloatArray(16))
        val stored = StoredTerrain(snapshot)

        assertNotNull(stored.erode(4, 4, FloatArray(16), 9f, 1, 0.25f))
        assertNull(stored.erode(8, 8, FloatArray(64), 9f, 1, 0.25f))
    }

    @Test
    fun `a snapshot travels through a header and comes back exactly`() = runTest {
        // The field is dead weight on the wire now (see WorldDocument.terrain), but while it is
        // still on the wire it has to survive it: base64 of raw float bits, through JSON, intact.
        val heights = FloatArray(16) { it * 0.001f }
        val document = WorldDocument(
            id = "stored",
            title = "Stored",
            config = WorldGenConfig(seed = 7L, width = 4, height = 4),
            terrain = TerrainSnapshot.of(4, 4, heights),
            savedAt = 1L
        )

        val save = assertNotNull(WorldCodec.decodeOrNull(WorldCodec.encode(document, null)))
        val terrain = assertNotNull(save.document.terrain)
        assertEquals(4, terrain.width)
        val values = terrain.decode()
        for (i in values.indices) {
            assertEquals(heights[i].toRawBits(), values[i].toRawBits())
        }
    }
}
