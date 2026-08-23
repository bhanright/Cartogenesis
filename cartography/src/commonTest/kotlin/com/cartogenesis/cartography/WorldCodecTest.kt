package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.LabelKind
import com.cartogenesis.worldgen.model.MapLabel
import com.cartogenesis.worldgen.model.WildernessMode
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.LandmarkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The save format is shared between Android, desktop and any future web build, so it is tested in
 * `commonTest` and runs on every target — a format that only round-trips on one platform would be
 * worse than none.
 */
class WorldCodecTest {

    private fun document() = WorldDocument(
        id = "a-world",
        title = "Test World",
        config = WorldGenConfig(seed = 4242L, width = 256, height = 256).copy(
            seaLevel = 0.55f,
            nations = WorldGenConfig().nations.copy(
                nationCount = 9,
                wilderness = WildernessMode.LEAVE_WILDERNESS
            )
        ),
        overrides = WorldOverrides(
            nations = mapOf(
                3 to NationOverride(
                    name = "Rewritten",
                    population = 1234567L,
                    exports = listOf("salt", "iron")
                )
            ),
            landmarks = mapOf(1 to LandmarkOverride(kind = LandmarkKind.RUIN, notes = "a note")),
            territory = mapOf(10 to 2, 11 to 2)
        ),
        labels = listOf(MapLabel(1L, "Somewhere", 0.25f, 0.75f, LabelKind.MOUNTAIN)),
        savedAt = 1_700_000_000_000L
    )

    @Test
    fun `a saved world survives a round trip intact`() {
        val original = document()
        val restored = WorldCodec.decode(WorldCodec.encode(original))

        assertEquals(original, restored)
        // Spot-check the parts that would quietly break the app rather than fail to parse.
        assertEquals(4242L, restored.config.seed)
        assertEquals(WildernessMode.LEAVE_WILDERNESS, restored.config.nations.wilderness)
        assertEquals(listOf("salt", "iron"), restored.overrides.forNation(3).exports)
        assertEquals(2, restored.overrides.territory[10])
        assertEquals(LabelKind.MOUNTAIN, restored.labels.single().kind)
    }

    @Test
    fun `untouched override fields stay null rather than freezing generated values`() {
        val restored = WorldCodec.decode(WorldCodec.encode(document()))
        val override = restored.overrides.forNation(3)

        // If these came back non-null, an edit to one field would pin every other field to
        // whatever the generator happened to produce at save time.
        assertNull(override.government)
        assertNull(override.lore)
        assertNull(override.capitalName)
        assertEquals("Rewritten", override.name)
    }

    @Test
    fun `a save written before a setting existed still opens`() {
        // Fields the current build knows about are simply absent here, and an unknown one is
        // present, which is what an older or newer save looks like.
        val older = """
            {
              "id": "old",
              "title": "Old World",
              "config": { "seed": 7, "width": 128, "height": 128 },
              "savedAt": 1,
              "somethingRemovedLater": { "a": 1 }
            }
        """.trimIndent()

        val restored = WorldCodec.decodeOrNull(older)
        assertNotNull(restored, "an older save should still open")
        assertEquals(7L, restored.config.seed)
        // Missing settings fall back to today's defaults rather than zero.
        assertEquals(WorldGenConfig().nations.nationCount, restored.config.nations.nationCount)
        assertEquals(WorldGenConfig().seaLevel, restored.config.seaLevel)
    }

    @Test
    fun `unreadable text is rejected without throwing`() {
        assertNull(WorldCodec.decodeOrNull("this is not json"))
        assertNull(WorldCodec.decodeOrNull(""))
    }
}
