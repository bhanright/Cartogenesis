package com.cartogenesis.cartography

import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.model.Acceleration
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A stored terrain has to come back exactly, and has to rebuild exactly the world it came from.
 *
 * This is the guarantee that lets a world generated on the graphics card be saved at all: the
 * hardware need not agree with anything, because the file carries the answer rather than the
 * instructions for finding it again.
 */
class TerrainSnapshotTest {

    @Test
    fun `every float survives the round trip`() {
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
    fun `a stored terrain rebuilds the same world`() {
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
    fun `a stored terrain declines a grid it was not taken at`() {
        // Export re-runs at a larger size, where a snapshot of the working resolution has no
        // business being used. It must decline rather than stretch what it holds.
        val snapshot = TerrainSnapshot.of(4, 4, FloatArray(16))
        val stored = StoredTerrain(snapshot)

        assertNotNull(stored.erode(4, 4, FloatArray(16), 9f, 1, 0.25f))
        assertNull(stored.erode(8, 8, FloatArray(64), 9f, 1, 0.25f))
    }

    @Test
    fun `the snapshot travels in the save file`() {
        val config = WorldGenConfig(seed = 7L, width = 64, height = 64)
        val document = WorldDocument(
            id = "test",
            title = "Stored",
            config = config,
            terrain = TerrainSnapshot.of(64, 64, FloatArray(64 * 64) { it * 0.001f }),
            savedAt = 0L
        )

        val restored = WorldCodec.decode(WorldCodec.encode(document))
        val terrain = assertNotNull(restored.terrain)
        assertEquals(64, terrain.width)
        val values = terrain.decode()
        for (i in values.indices) {
            assertEquals((i * 0.001f).toRawBits(), values[i].toRawBits())
        }
    }

    @Test
    fun `a world without acceleration stores no terrain`() {
        // The size of a save is the whole reason this is conditional, so it is worth pinning: a
        // CPU world reproduces from its seed and must stay a few kilobytes.
        val document = WorldDocument(
            id = "test",
            title = "Plain",
            config = WorldGenConfig(seed = 7L, width = 64, height = 64),
            savedAt = 0L
        )
        assertNull(document.terrain)
        assertTrue(
            WorldCodec.encode(document).length < 8000,
            "a seed-only save should be small, was ${WorldCodec.encode(document).length} chars"
        )
    }
}
