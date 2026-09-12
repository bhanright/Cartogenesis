package com.cartogenesis.cartography

import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.model.LabelKind
import com.cartogenesis.worldgen.model.MapLabel
import com.cartogenesis.worldgen.model.WildernessMode
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.LandmarkKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The save format is shared between every front end, so it is tested in `commonTest` and runs on
 * every target — a format that only round-trips on one platform would be worse than none.
 *
 * The round-trip case is the one that matters now that a save carries the world rather than the
 * recipe for it: every per-cell array has to come back *identical*, not close, because what comes
 * out of the file is what the user sees and edits from then on. It was shown to fail by dropping
 * a section from the writer, which is also pinned here as a case of its own.
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

    /** Writes one little-endian int at a fixed offset, to forge a header from an older build. */
    private class ByteWriterAt(private val bytes: ByteArray, private val offset: Int) {
        fun putInt(value: Int) {
            for (byte in 0 until 4) {
                bytes[offset + byte] = ((value shr (8 * byte)) and 0xFF).toByte()
            }
        }
    }

    /** Small enough to run on every target, large enough to have rivers, realms and peoples. */
    private val worldConfig = WorldGenConfig(seed = 99L, width = 256, height = 256)

    @Test
    fun `a saved world survives a round trip intact`() = runTest {
        val original = document()
        val restored = assertNotNull(WorldCodec.decode(WorldCodec.encode(original, null)).document)

        assertEquals(original, restored)
        // Spot-check the parts that would quietly break the app rather than fail to parse.
        assertEquals(4242L, restored.config.seed)
        assertEquals(WildernessMode.LEAVE_WILDERNESS, restored.config.nations.wilderness)
        assertEquals(listOf("salt", "iron"), restored.overrides.forNation(3).exports)
        assertEquals(2, restored.overrides.territory[10])
        assertEquals(LabelKind.MOUNTAIN, restored.labels.single().kind)
    }

    @Test
    fun `untouched override fields stay null rather than freezing generated values`() = runTest {
        val restored = WorldCodec.decode(WorldCodec.encode(document(), null)).document
        val override = restored.overrides.forNation(3)

        // If these came back non-null, an edit to one field would pin every other field to
        // whatever the generator happened to produce at save time.
        assertNull(override.government)
        assertNull(override.lore)
        assertNull(override.capitalName)
        assertEquals("Rewritten", override.name)
    }

    @Test
    fun `every per-cell array and every list comes back identical`() = runTest(timeout = 10.minutes) {
        val world = WorldGenerationEngine.generate(worldConfig)
        // A case can only mean anything if there was something to compare. An empty list is equal
        // to an empty list, and a world with no realms would pass this without looking at one.
        assertTrue(world.rivers.rivers.isNotEmpty(), "world has no rivers to compare")
        assertTrue(world.nations.nations.isNotEmpty(), "world has no realms to compare")
        assertTrue(world.cultures.cultures.isNotEmpty(), "world has no peoples to compare")
        assertTrue(world.landmarks.landmarks.isNotEmpty(), "world has no landmarks to compare")
        assertTrue(world.plates.plates.isNotEmpty(), "world has no plates to compare")

        val bytes = WorldCodec.encode(document().copy(config = worldConfig), world)
        val restored = assertNotNull(WorldCodec.decode(bytes).world, "the save carried no world")

        assertArraysIdentical(world, restored)
        assertListsEqual(world, restored)
        assertEquals(
            world.sea.shorelineHeight.toRawBits(), restored.sea.shorelineHeight.toRawBits()
        )
        assertEquals(world.sea.landCellCount, restored.sea.landCellCount)
    }

    @Test
    fun `a stage missing all of its sections rebuilds as null instead of refusing to open`() =
        runTest(timeout = 10.minutes) {
            // Erosion has exactly one section, so dropping it drops the whole stage cleanly: this
            // is what an old save looks like to a reader that has since added a field to some
            // *other* stage's result. It has to come back as a world with a hole in it, not throw
            // - the old behaviour here (`assertFailsWith<WorldFormatException>`) is precisely the
            // bug D4 fixes, and refusing a file this way is what broke every version-3 save written
            // before A1's climate fields, D2's checked-in gzip fixture included.
            val world = WorldGenerationEngine.generate(worldConfig)
            val complete = WorldSections.of(world)
            val short = complete.filterNot { it.name == "erosion.height" }

            val (payload, directory) = WorldSections.write(short)
            val partial = WorldSections.rebuild(
                config = worldConfig,
                lists = WorldLists.of(world),
                labels = emptyList(),
                sections = WorldSections.read(payload)
            )

            assertNull(partial.erosion, "erosion's only section was dropped, so it should not rebuild")
            assertNotNull(partial.terrain, "terrain's sections were untouched")
            assertNotNull(partial.plates, "plates' sections were untouched")
            assertNotNull(partial.sea, "sea level's sections were untouched")
            assertEquals(complete.size - 1, directory.size)
        }

    @Test
    fun `a section that disagrees about its own element count still throws`() =
        runTest(timeout = 10.minutes) {
            // Different from a missing section, and still refused: a section that lies about its
            // own shape is bytes this build cannot trust at all, not an old file it can work around.
            val world = WorldGenerationEngine.generate(worldConfig)
            val (payload, _) = WorldSections.write(WorldSections.of(world))

            // Every section's record starts with its ASCII name (int32 length, then the bytes),
            // then an int32 element type, then an int32 element count, then an int32 byte length
            // the reader checks the count against. Bumping the count without touching the length
            // it is supposed to agree with is exactly that disagreement.
            val corrupted = payload.copyOf()
            val nameLength = ByteReader(corrupted, position = 0).getInt()
            val countOffset = 4 + nameLength + 4
            corrupted[countOffset] = (corrupted[countOffset] + 1).toByte()

            assertFailsWith<WorldFormatException> { WorldSections.read(corrupted) }
        }

    @Test
    fun `a save missing the climate sections opens, regenerating climate and everything after it`() =
        runTest(timeout = 10.minutes) {
            // The exact shape of A1's fallout: a version-3 save written before a chunk added fields
            // to one stage's result is missing that stage's sections and no others. It must open,
            // reusing the stages whose sections survived and regenerating climate and everything
            // the pipeline runs after it - not refuse the whole file.
            val world = WorldGenerationEngine.generate(worldConfig)
            val complete = WorldSections.of(world)
            val withoutClimate = complete.filterNot { it.name.startsWith("climate.") }
            assertTrue(withoutClimate.size < complete.size, "the fixture should actually drop something")

            val (payload, directory) = WorldSections.write(withoutClimate)
            val lists = WorldLists.of(world)

            // What WorldSections.rebuild() hands back for a save like this: every stage whose
            // sections survived, present; climate, missing.
            val partial = WorldSections.rebuild(worldConfig, lists, emptyList(), WorldSections.read(payload))
            assertNotNull(partial.terrain, "terrain's own sections survived and should have rebuilt")
            assertNotNull(partial.rivers, "rivers' own sections survived and should have rebuilt")
            assertNull(partial.climate, "climate's sections were dropped and should not have rebuilt")

            // What WorldCodec.decode() does with that: hand it to the engine, which reuses the
            // stages that survived and regenerates climate and everything downstream of it. Identity,
            // not equality - a stage that recomputed the same answer would pass an equality check
            // while costing exactly what reuse exists to avoid, and reusing a stage that should not
            // have been reused would still pass a null check.
            val opened = WorldGenerationEngine.generate(worldConfig, previous = partial)
            assertSame(partial.terrain, opened.terrain, "terrain was regenerated")
            assertSame(partial.plates, opened.plates, "plates were regenerated")
            assertSame(partial.erosion, opened.erosion, "erosion was regenerated")
            assertSame(partial.sea, opened.sea, "sea level was regenerated")
            assertSame(partial.ocean, opened.ocean, "ocean was regenerated")
            assertNotSame(partial.rivers, opened.rivers, "rivers should regenerate along with climate")
            assertNotSame(partial.nations, opened.nations, "realms should regenerate along with climate")
            assertNotSame(partial.cultures, opened.cultures, "peoples should regenerate along with climate")
            assertNotSame(
                partial.landmarks, opened.landmarks, "landmarks should regenerate along with climate"
            )

            // And the codec's own path over real bytes must open rather than refuse - this is the
            // actual bug: the pre-fix reader threw WorldFormatException on exactly this file.
            val original = document().copy(config = worldConfig)
            val fullBytes = WorldCodec.encode(original, world)
            val header = WorldCodec.decodeHeader(fullBytes)
            val stripped = containerBytes(
                header.copy(sections = directory, payloadBytes = payload.size), payload
            )
            val decoded = assertNotNull(
                WorldCodec.decode(stripped).world,
                "a save missing only climate's sections should still open"
            )
            assertEquals(worldConfig.seed, decoded.config.seed)
            assertEquals(worldConfig.width, decoded.config.width)
        }

    /** Hand-assembles a container from a header and payload, the way [WorldCodec.encode] does. */
    private fun containerBytes(header: SaveHeader, payload: ByteArray): ByteArray {
        val headerBytes = Json.encodeToString(header).encodeToByteArray()
        val writer = ByteWriter(WorldCodec.PREFIX_BYTES + headerBytes.size + payload.size)
        writer.putBytes(byteArrayOf('C'.code.toByte(), 'G'.code.toByte(), 'W'.code.toByte(), 'D'.code.toByte()))
        writer.putInt(WorldCodec.FORMAT_VERSION)
        writer.putInt(headerBytes.size)
        writer.putBytes(headerBytes)
        writer.putBytes(payload)
        return writer.bytes
    }

    @Test
    fun `a loaded save reuses every stage and generates nothing`() = runTest(timeout = 10.minutes) {
        val world = WorldGenerationEngine.generate(worldConfig)
        val save = WorldCodec.decode(WorldCodec.encode(document().copy(config = worldConfig), world))
        val loaded = assertNotNull(save.world)

        // Opening a save is this: hand the stored world back to the engine as the world to reuse.
        // Every stage's guard should match, so every result should be the very object that came
        // out of the file — identity, not equality, because equality would also pass if the stage
        // had been recomputed to the same answer, which is the expensive thing this avoids.
        val opened = WorldGenerationEngine.generate(save.document.config, previous = loaded)

        assertSame(loaded.terrain, opened.terrain, "terrain was regenerated")
        assertSame(loaded.plates, opened.plates, "plates were regenerated")
        assertSame(loaded.erosion, opened.erosion, "erosion was regenerated")
        assertSame(loaded.sea, opened.sea, "sea level was regenerated")
        assertSame(loaded.ocean, opened.ocean, "ocean was regenerated")
        assertSame(loaded.climate, opened.climate, "climate was regenerated")
        assertSame(loaded.rivers, opened.rivers, "rivers were regenerated")
        assertSame(loaded.nations, opened.nations, "realms were regenerated")
        assertSame(loaded.cultures, opened.cultures, "peoples were regenerated")
        assertSame(loaded.landmarks, opened.landmarks, "landmarks were regenerated")
        assertArraysIdentical(world, opened)
    }

    @Test
    fun `a save from an older format is refused rather than misread`() = runTest {
        // Exactly what the build before the container wrote: JSON, no magic, no payload.
        val olderText = """
            {
              "id": "old",
              "title": "Old World",
              "config": { "seed": 7, "width": 128, "height": 128 },
              "savedAt": 1
            }
        """.trimIndent()

        // A header decoded with unknown keys ignored would take this build's defaults wherever a
        // setting has since been renamed, so it would open as a different world with no complaint.
        // Refusing is the only honest answer while the format is still moving.
        assertFailsWith<WorldFormatException> {
            WorldCodec.decodeHeader(olderText.encodeToByteArray())
        }
        assertNull(WorldCodec.decodeOrNull(olderText.encodeToByteArray()))

        // And a container one version behind, which is the case a real older save would be.
        val current = WorldCodec.encode(document(), null)
        val older = current.copyOf().also {
            ByteWriterAt(it, WorldCodec.VERSION_OFFSET).putInt(WorldCodec.FORMAT_VERSION - 1)
        }
        val refusal = assertFailsWith<WorldFormatException> { WorldCodec.decodeHeader(older) }
        assertTrue(
            refusal.message!!.contains("${WorldCodec.FORMAT_VERSION - 1}"),
            "the refusal should name the version it found: ${refusal.message}"
        )
    }

    @Test
    fun `the header reads without touching the payload`() = runTest(timeout = 10.minutes) {
        // What a library listing does, and the reason the header sits uncompressed at the front.
        val world = WorldGenerationEngine.generate(worldConfig)
        val bytes = WorldCodec.encode(document().copy(config = worldConfig), world, writtenBy = "a test")
        val headerLength = ByteReader(bytes, position = WorldCodec.HEADER_LENGTH_OFFSET).getInt()
        val prefixOnly = bytes.copyOfRange(0, WorldCodec.PREFIX_BYTES + headerLength)

        val header = WorldCodec.decodeHeader(prefixOnly)
        assertEquals("Test World", header.document.title)
        assertEquals("a test", header.writtenBy)
        assertEquals(WorldSections.of(world).size, header.sections.size)
        assertTrue(prefixOnly.size < bytes.size / 4, "the header should be a small part of the file")
    }

    @Test
    fun `a payload stored raw and one stored compressed both read back`() = runTest(timeout = 10.minutes) {
        // The browser cannot gzip, so it stores raw and says so; the desktop gzips. Either file
        // has to open on either side, which is what the flag in the header is for.
        val world = WorldGenerationEngine.generate(WorldGenConfig(seed = 7L, width = 128, height = 128))
        val doc = document().copy(config = world.config)

        val raw = WorldCodec.encode(doc, world, NoCompression)
        assertEquals("none", WorldCodec.decodeHeader(raw).compression)
        assertArraysIdentical(world, assertNotNull(WorldCodec.decode(raw).world))

        // A stand-in for a platform that can compress: reversing the bytes is not gzip, but it is
        // a transform the reader has to undo through the seam, which is what is under test.
        val flipped = WorldCodec.encode(doc, world, ReversingCompressor)
        assertEquals("reversed", WorldCodec.decodeHeader(flipped).compression)
        assertTrue(flipped.size < raw.size + 64, "a compressed save should not balloon")
        assertArraysIdentical(world, assertNotNull(WorldCodec.decode(flipped, ReversingCompressor).world))

        // And a platform that cannot expand what it was handed says so rather than guessing.
        assertNull(WorldCodec.decodeOrNull(flipped, NoCompression))
    }

    @Test
    fun `unreadable bytes are rejected without throwing`() = runTest {
        assertNull(WorldCodec.decodeOrNull("this is not json".encodeToByteArray()))
        assertNull(WorldCodec.decodeOrNull(ByteArray(0)))
        assertNull(WorldCodec.decodeOrNull(byteArrayOf(67, 71, 87, 68, 3, 0, 0, 0, 99, 0, 0, 0)))
    }

    /**
     * Every array in the world, compared by raw bits.
     *
     * Driven off the writer's own section list rather than a hand-written one, so an array added
     * to the format in future is compared the day it is added instead of the day someone
     * remembers to add it here.
     */
    private fun assertArraysIdentical(expected: WorldMap, actual: WorldMap) {
        val before = WorldSections.of(expected)
        val after = WorldSections.of(actual).associateBy { it.name }
        assertEquals(before.size, after.size, "a section went missing")
        for (section in before) {
            val other = assertNotNull(after[section.name], "no section ${section.name}")
            assertEquals(section.type, other.type, "${section.name} changed element type")
            assertEquals(section.count, other.count, "${section.name} changed length")
            when (section.type) {
                SectionType.F32 -> {
                    val a = section.floatsOrFail()
                    val b = other.floatsOrFail()
                    for (i in a.indices) {
                        // Raw bits, not equality: identical, and carrying the sign of a negative
                        // zero rather than quietly normalising it.
                        assertEquals(
                            a[i].toRawBits(), b[i].toRawBits(),
                            "${section.name} differs at $i: ${a[i]} became ${b[i]}"
                        )
                    }
                }
                SectionType.I32 -> {
                    val a = section.intsOrFail()
                    val b = other.intsOrFail()
                    for (i in a.indices) assertEquals(a[i], b[i], "${section.name} differs at $i")
                }
                SectionType.U8 -> {
                    val a = section.bytesOrFail()
                    val b = other.bytesOrFail()
                    for (i in a.indices) assertEquals(a[i], b[i], "${section.name} differs at $i")
                }
            }
        }
    }

    private fun assertListsEqual(expected: WorldMap, actual: WorldMap) {
        assertEquals(expected.plates.plates, actual.plates.plates)
        assertEquals(expected.rivers.lakes.lakes, actual.rivers.lakes.lakes)
        assertEquals(expected.nations.nations, actual.nations.nations)
        assertEquals(expected.cultures.cultures, actual.cultures.cultures)
        assertEquals(expected.landmarks.landmarks, actual.landmarks.landmarks)

        // A river holds arrays, so its generated equality is identity and would pass on anything.
        assertEquals(expected.rivers.rivers.size, actual.rivers.rivers.size)
        expected.rivers.rivers.forEachIndexed { i, river ->
            val other = actual.rivers.rivers[i]
            assertTrue(river.cells.contentEquals(other.cells), "river $i took a different course")
            for (p in river.widths.indices) {
                assertEquals(
                    river.widths[p].toRawBits(), other.widths[p].toRawBits(),
                    "river $i width $p differs"
                )
            }
        }
    }
}

/** Not a compression scheme; a transform the reader must undo through the seam to get the bytes back. */
private object ReversingCompressor : Compressor {
    override val name: String get() = "reversed"
    override suspend fun compress(data: ByteArray): ByteArray = data.reversedArray()
    override suspend fun decompress(data: ByteArray): ByteArray = data.reversedArray()
}
