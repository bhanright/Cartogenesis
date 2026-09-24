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
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/**
 * The save format is shared between every front end, so it is tested in `commonTest` and runs on
 * every target — a format that only round-trips on one platform would be worse than none.
 *
 * The round-trip cases are the ones that matter for a world that opens: every per-cell array has
 * to come back *identical*, not close, because what comes out of the file is what the reader sees
 * and edits from then on. The refusal cases are the ones that matter for a world that does not: a
 * save that is damaged, cut short or crafted is refused with its own reason, and none of them is
 * opened as whatever its settings would have regenerated. Most of these run on a synthetic world
 * that costs nothing to make — see [SyntheticWorlds] — so each kind of damage can be tried alone.
 */
class WorldCodecTest {

    private fun document(world: WorldMap) = WorldDocument(
        id = "a-world",
        title = "Test World",
        config = world.config,
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

    private val synthetic = SyntheticWorlds.of()

    private suspend fun rawSave(world: WorldMap = synthetic): ByteArray =
        WorldCodec.encode(document(world), world)

    /** The refusal [bytes] meet, which the case then says must be of [problem] and say [words]. */
    private suspend fun assertRefused(
        bytes: ByteArray,
        problem: SaveProblem,
        words: String,
        compressor: Compressor = NoCompression
    ) {
        val outcome = WorldCodec.open(ByteArraySource(bytes), compressor)
        val refused = outcome as? LoadOutcome.Refused
            ?: throw AssertionError("a save that should have been refused ($words) opened")
        assertEquals(problem, refused.refusal.problem, refused.refusal.message)
        assertTrue(words in refused.refusal.detail, "the refusal said '${refused.refusal.detail}', not '$words'")
    }

    // ---- Opening ----

    @Test
    fun `a saved world survives a round trip intact`() = runTest {
        val original = document(synthetic).copy(
            config = synthetic.config,
            title = "Test World"
        )
        val restored = WorldCodec.decode(WorldCodec.encode(original, synthetic)).document

        assertEquals(original, restored)
        assertEquals(listOf("salt", "iron"), restored.overrides.forNation(3).exports)
        assertEquals(2, restored.overrides.territory[10])
        assertEquals(LabelKind.MOUNTAIN, restored.labels.single().kind)
    }

    @Test
    fun `untouched override fields stay null rather than freezing generated values`() = runTest {
        val override = WorldCodec.decode(rawSave()).document.overrides.forNation(3)

        // If these came back non-null, an edit to one field would pin every other field to
        // whatever the generator happened to produce at save time.
        assertNull(override.government)
        assertNull(override.lore)
        assertNull(override.capitalName)
        assertEquals("Rewritten", override.name)
    }

    @Test
    fun `every per-cell array and every list comes back identical`() = runTest(timeout = 10.minutes) {
        val world = GeneratedWorlds.at256()
        // A case can only mean anything if there was something to compare. An empty list is equal
        // to an empty list, and a world with no realms would pass this without looking at one.
        assertTrue(world.rivers.rivers.isNotEmpty(), "world has no rivers to compare")
        assertTrue(world.rivers.lakes.lakes.isNotEmpty(), "world has no lakes to compare")
        assertTrue(world.nations.nations.isNotEmpty(), "world has no realms to compare")
        assertTrue(world.cultures.cultures.isNotEmpty(), "world has no peoples to compare")
        assertTrue(world.landmarks.landmarks.isNotEmpty(), "world has no landmarks to compare")

        val bytes = WorldCodec.encode(document(world), world)
        val restored = WorldCodec.decode(bytes).world

        // The file's directory is this build's whole layout: every section, nothing optional.
        val header = WorldCodec.decodeHeader(bytes)
        assertEquals(
            listOf(WorldSections.LISTS) + WorldSections.SECTIONS.map { it.name },
            header.sections.map { it.name }
        )
        assertArraysIdentical(world, restored)
        assertListsEqual(world, restored)
        assertEquals(world.sea.shorelineHeight.toRawBits(), restored.sea.shorelineHeight.toRawBits())
        assertEquals(world.sea.landCellCount, restored.sea.landCellCount)
    }

    @Test
    fun `a loaded save reuses every stage and generates nothing`() = runTest(timeout = 10.minutes) {
        val world = GeneratedWorlds.at256()
        val loaded = WorldCodec.decode(WorldCodec.encode(document(world), world)).world

        // What the application does with an opened world when a setting is next edited: hand it
        // back to the engine as the world to reuse. Every stage's guard should match, so every
        // result should be the very object that came out of the file — identity, not equality.
        val opened = WorldGenerationEngine.generate(loaded.config, previous = loaded)

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
    fun `the comparison the browser's self-test uses finds a difference in any field`() = runTest {
        // The browser's round trip compared the heights alone; it asks this now, which has to
        // see a change anywhere a save carries one.
        // Filed with no labels: a saved world's labels are its document's, and the synthetic world has none.
        val plain = WorldDocument(id = "plain", title = "Plain", config = synthetic.config, savedAt = 1L)
        val restored = WorldCodec.decode(WorldCodec.encode(plain, synthetic)).world
        assertNull(WorldComparison.firstDifference(synthetic, restored))
        restored.climate.permafrost[9] = 2
        assertEquals("'climate.permafrost' at cell 9", WorldComparison.firstDifference(synthetic, restored))
        restored.climate.permafrost[9] = synthetic.climate.permafrost[9]
        restored.ocean.anomaly.data[3] = -0f
        assertEquals("'ocean.anomaly' at cell 3", WorldComparison.firstDifference(synthetic, restored))
        restored.ocean.anomaly.data[3] = synthetic.ocean.anomaly.data[3]
        restored.rivers.rivers.single().cells[1] = 13
        assertEquals("the lists", WorldComparison.firstDifference(synthetic, restored))
    }

    @Test
    fun `a payload stored raw and one stored compressed both read back`() = runTest {
        val raw = rawSave()
        assertEquals("none", WorldCodec.decodeHeader(raw).compression)
        assertArraysIdentical(synthetic, WorldCodec.decode(raw).world)

        val squeezed = WorldCodec.encode(document(synthetic), synthetic, RunLengthCompressor)
        assertEquals("runs", WorldCodec.decodeHeader(squeezed).compression)
        assertTrue(squeezed.size < raw.size, "the flag and id maps should have squeezed")
        assertArraysIdentical(synthetic, WorldCodec.decode(squeezed, RunLengthCompressor).world)

        // A platform that cannot expand what it was handed says so rather than guessing.
        assertRefused(squeezed, SaveProblem.CANNOT_EXPAND, "'runs'")
    }

    @Test
    fun `a save larger than one chunk is carried across its chunks`() = runTest {
        // 512 by 512 is 38 MB of arrays: thirty-odd chunks, with records and values straddling
        // every boundary between them.
        val world = SyntheticWorlds.of(WorldGenConfig(seed = 6L, width = 512, height = 512))
        val bytes = WorldCodec.encode(document(world), world)
        assertTrue(bytes.size > 30 * WorldCodec.CHUNK_BYTES)
        assertArraysIdentical(world, WorldCodec.decode(bytes).world)
    }

    @Test
    fun `the header reads without touching the payload`() = runTest {
        val bytes = WorldCodec.encode(document(synthetic), synthetic, writtenBy = "a test")
        val headerLength = getInt(bytes, WorldCodec.HEADER_LENGTH_OFFSET)
        val prefixOnly = bytes.copyOfRange(0, WorldCodec.PREFIX_BYTES + headerLength)

        val header = WorldCodec.decodeHeader(prefixOnly)
        assertEquals("Test World", header.document.title)
        assertEquals("a test", header.writtenBy)
        assertEquals(WorldSections.SECTIONS.size + 1, header.sections.size)
        assertTrue(prefixOnly.size < bytes.size / 4, "the header should be a small part of the file")
    }

    @Test
    fun `a 4096 world's payload is counted past what an Int holds`() {
        // 146 bytes a cell at 4096 is 2,449,473,536 bytes of arrays, which wrapped the old Int
        // sum negative. Counted here from the layout itself, so a narrowing anywhere shows.
        val cells = 4096 * 4096
        val directory = WorldSections.directory(cells, listsBytes = 1_000)
        val arrays = directory.drop(1).sumOf { it.bytes }
        assertEquals(146L * cells, arrays)
        assertTrue(WorldSections.payloadBytes(directory) > Int.MAX_VALUE)
        assertEquals(directory.last().offset + WorldSections.RECORD_PREFIX_BYTES + directory.last().name.length +
            directory.last().bytes, WorldSections.payloadBytes(directory))
    }

    // ---- What is not written ----

    @Test
    fun `a document whose settings are not its world's is not written`() = runTest {
        // What Save did after a stopped change of seed: the settings on the panel filed with the
        // world still on screen, which reopened as a world nobody made.
        val moved = document(synthetic).copy(config = synthetic.config.copy(seed = 6L))
        assertFailsWith<IllegalArgumentException> { WorldCodec.encode(moved, synthetic) }
        val resized = document(synthetic).copy(config = synthetic.config.atResolution(128, 128))
        assertFailsWith<IllegalArgumentException> { WorldCodec.encode(resized, synthetic) }
    }

    @Test
    fun `an id with a path in it is not written`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            WorldCodec.encode(document(synthetic).copy(id = "../escape"), synthetic)
        }
    }

    // ---- What is refused, each with its own reason ----

    @Test
    fun `a save from an older format is refused rather than misread`() = runTest {
        // Exactly what the build before the container wrote: JSON, no magic, no payload.
        val olderText = """{ "id": "old", "title": "Old World", "config": { "seed": 7 }, "savedAt": 1 }"""
        assertRefused(olderText.encodeToByteArray(), SaveProblem.WRONG_VERSION, "before format 3")

        // And a container one version behind, which is the case a real older save would be.
        val older = rawSave().also { putInt(it, WorldCodec.VERSION_OFFSET, WorldCodec.FORMAT_VERSION - 1) }
        assertRefused(older, SaveProblem.WRONG_VERSION, "format ${WorldCodec.FORMAT_VERSION - 1}")
        val newer = rawSave().also { putInt(it, WorldCodec.VERSION_OFFSET, WorldCodec.FORMAT_VERSION + 1) }
        assertRefused(newer, SaveProblem.WRONG_VERSION, "a newer build")
    }

    @Test
    fun `bytes that are not a save are refused as not a save`() = runTest {
        assertRefused("this is not json".encodeToByteArray(), SaveProblem.NOT_A_SAVE, "")
    }

    @Test
    fun `a section renamed in the payload is refused, not regenerated`() = runTest {
        // Audit III's case: the directory still advertises erosion.height and the payload's record is
        // called something else. The old reader took the stage as missing and regenerated erosion
        // and everything after it on the processor.
        val apart = TakenApart.of(rawSave())
        val payload = apart.payload.copyOf()
        val nameAt = apart.recordOf("erosion.height") + 4 + "erosion.height".length - 1
        payload[nameAt] = 'u'.code.toByte()
        assertRefused(apart.reassemble(payload = payload), SaveProblem.DAMAGED, "is called 'erosion.heighu'")
    }

    @Test
    fun `a directory that is not this build's layout is refused`() = runTest {
        val apart = TakenApart.of(rawSave())
        val renamed = apart.header.sections.map {
            if (it.name == "erosion.height") it.copy(name = "erosion.heighu") else it
        }
        assertRefused(
            apart.reassemble(header = apart.header.copy(sections = renamed)),
            SaveProblem.DAMAGED, "'erosion.heighu'"
        )
        // A save that carries some stages and not others is not a save: there is no partial form.
        val shorter = apart.header.sections.filterNot { it.name.startsWith("climate.") }
        assertRefused(
            apart.reassemble(header = apart.header.copy(sections = shorter)),
            SaveProblem.DAMAGED, "sections where this build's format has"
        )
        assertRefused(
            apart.reassemble(header = apart.header.copy(sections = emptyList())),
            SaveProblem.DAMAGED, "directory is empty"
        )
    }

    @Test
    fun `a header without its world does not parse`() = runTest {
        // The header-only save the old codec wrote for a null world, and opened by regenerating.
        val apart = TakenApart.of(rawSave())
        val headerOnly = """{"formatVersion":${WorldCodec.FORMAT_VERSION},"document":""" +
            kotlinx.serialization.json.Json.encodeToString(WorldDocument.serializer(), apart.header.document) +
            ""","compression":"none","writtenBy":"old"}"""
        assertRefused(apart.reassembleText(headerOnly, ByteArray(0)), SaveProblem.DAMAGED, "header does not parse")
    }

    @Test
    fun `a truncated save is refused as incomplete`() = runTest {
        val bytes = rawSave()
        assertRefused(bytes.copyOf(bytes.size / 2), SaveProblem.INCOMPLETE, "partway through")
        assertRefused(bytes.copyOf(bytes.size - 1), SaveProblem.INCOMPLETE, "frame that closes the world")
        assertRefused(bytes.copyOf(40), SaveProblem.INCOMPLETE, "inside its header")
        assertRefused(ByteArray(0), SaveProblem.INCOMPLETE, "empty")
    }

    @Test
    fun `zeros where a save should be are refused as incomplete`() = runTest {
        // What a file a sync client has made room for and not yet filled looks like.
        val bytes = rawSave()
        assertRefused(ByteArray(bytes.size), SaveProblem.INCOMPLETE, "nothing but zeros")
        val halfFilled = bytes.copyOf().also { it.fill(0, bytes.size / 2, bytes.size) }
        assertRefused(halfFilled, SaveProblem.INCOMPLETE, "zeros where its world should continue")
    }

    @Test
    fun `an element count that overflows is refused before anything is allocated`() = runTest {
        // A count of 2^30 floats with a byte length of zero passed the old length check, because
        // 2^30 * 4 wraps to zero in an Int, and then asked for a 4 GB array.
        val apart = TakenApart.of(rawSave())
        val payload = apart.payload.copyOf()
        val countAt = apart.recordOf("terrain.height") + 4 + "terrain.height".length + 4
        putInt(payload, countAt, 1 shl 30)
        putInt(payload, countAt + 4, 0)
        putInt(payload, countAt + 8, 0)
        assertRefused(apart.reassemble(payload = payload), SaveProblem.DAMAGED, "holds 1073741824 elements")

        // A grid, a header or a list beyond what this build holds is refused by its size alone.
        val huge = apart.header.document.config.copy(width = 65536, height = 65536)
        assertRefused(
            apart.reassemble(header = apart.header.copy(document = apart.header.document.copy(config = huge))),
            SaveProblem.TOO_LARGE, "65536 by 65536"
        )
        val lists = apart.header.sections.first().copy(count = Int.MAX_VALUE)
        assertRefused(
            apart.reassemble(header = apart.header.copy(sections = listOf(lists) + apart.header.sections.drop(1))),
            SaveProblem.TOO_LARGE, "lists claim"
        )
        val longHeader = rawSave().also { putInt(it, WorldCodec.HEADER_LENGTH_OFFSET, Int.MAX_VALUE) }
        assertRefused(longHeader, SaveProblem.TOO_LARGE, "header claims")
    }

    @Test
    fun `an id that names nothing in the world is refused`() = runTest {
        // A lake id one past the lakes: the old reader accepted it, and the raster threw on the
        // first lake cell it drew.
        val apart = TakenApart.of(rawSave())
        val lakes = synthetic.rivers.lakes.lakes.size
        val payload = apart.payload.copyOf()
        putInt(payload, apart.elementsOf("rivers.lakeId") + 4 * 7, lakes)
        assertRefused(apart.reassemble(payload = payload), SaveProblem.DAMAGED, "'rivers.lakeId' holds $lakes at cell 7")

        val flow = apart.payload.copyOf()
        putInt(flow, apart.elementsOf("rivers.flowTarget") + 4 * 9, synthetic.width * synthetic.height)
        assertRefused(apart.reassemble(payload = flow), SaveProblem.DAMAGED, "'rivers.flowTarget' holds")
    }

    @Test
    fun `a list that points off the grid is refused`() = runTest {
        val apart = TakenApart.of(rawSave())
        val cells = synthetic.width * synthetic.height
        val listsText = apart.payload.copyOfRange(
            apart.elementsOf(WorldSections.LISTS),
            apart.elementsOf(WorldSections.LISTS) + apart.header.sections.first().count
        ).decodeToString()
        // The river's course is cells 10, 11 and 12; move its last cell past the grid, keeping
        // the text the same length so the directory still adds up.
        val moved = listsText.replaceFirst("\"cells\":[10,11,12]", "\"cells\":[10,11,${cells + 1}]")
        check(moved != listsText)
        val movedBytes = moved.encodeToByteArray()
        val listsEntry = apart.header.sections.first()
        val payload = ByteArray(apart.payload.size + movedBytes.size - listsEntry.count)
        val listsStart = apart.elementsOf(WorldSections.LISTS)
        apart.payload.copyInto(payload, 0, 0, listsStart)
        movedBytes.copyInto(payload, listsStart)
        apart.payload.copyInto(payload, listsStart + movedBytes.size, listsStart + listsEntry.count)
        // The record's element count, then the low half of its int64 byte length.
        putInt(payload, listsStart - 12, movedBytes.size)
        putInt(payload, listsStart - 8, movedBytes.size)
        val directory = WorldSections.directory(cells, movedBytes.size)
        val header = apart.header.copy(sections = directory, payloadBytes = WorldSections.payloadBytes(directory))
        assertRefused(apart.reassemble(header, payload), SaveProblem.DAMAGED, "river 0's course is cell ${cells + 1}")
    }

    @Test
    fun `a NaN or an infinity is refused`() = runTest {
        val apart = TakenApart.of(rawSave())
        val payload = apart.payload.copyOf()
        putInt(payload, apart.elementsOf("terrain.height") + 4 * 5, Float.NaN.toRawBits())
        assertRefused(apart.reassemble(payload = payload), SaveProblem.DAMAGED, "'terrain.height' holds NaN at cell 5")

        val infinite = apart.payload.copyOf()
        putInt(infinite, apart.elementsOf("ocean.temperature"), Float.POSITIVE_INFINITY.toRawBits())
        assertRefused(apart.reassemble(payload = infinite), SaveProblem.DAMAGED, "'ocean.temperature' holds Infinity")
    }

    @Test
    fun `a flag that is neither 0 nor 1 is refused`() = runTest {
        // The old reader read any byte that was not zero as true.
        val apart = TakenApart.of(rawSave())
        val payload = apart.payload.copyOf()
        payload[apart.elementsOf("sea.isLand") + 3] = 2
        assertRefused(apart.reassemble(payload = payload), SaveProblem.DAMAGED, "'sea.isLand' holds 2 at cell 3")
    }

    @Test
    fun `an id with a path in it is refused`() = runTest {
        // The library files a new save as <id>.cgw, so a crafted id reaches the file system.
        val apart = TakenApart.of(rawSave())
        val crafted = apart.header.copy(document = apart.header.document.copy(id = "../../somewhere/x"))
        assertRefused(apart.reassemble(header = crafted), SaveProblem.DAMAGED, "its id '../../somewhere/x'")
    }

    @Test
    fun `a flipped byte is caught by its chunk's checksum`() = runTest {
        // Stored raw, nothing else would notice: gzip's own checksum only covers a compressed file.
        val bytes = rawSave()
        val headerLength = getInt(bytes, WorldCodec.HEADER_LENGTH_OFFSET)
        val inside = WorldCodec.PREFIX_BYTES + headerLength + PayloadWriter.FRAME_HEADER_BYTES + 5_000
        bytes[inside] = (bytes[inside] + 1).toByte()
        assertRefused(bytes, SaveProblem.DAMAGED, "fails its checksum")
    }

    @Test
    fun `one changed character in the header is refused, not opened under other settings`() = runTest {
        // Every chunk checked out and nothing checked the header: a seed of 5 edited to 6 opened
        // the same arrays under another world's settings.
        val bytes = rawSave()
        val seed = "\"seed\":5,".encodeToByteArray()
        val at = (0..bytes.size - seed.size).first { start -> seed.indices.all { bytes[start + it] == seed[it] } }
        bytes[at + seed.size - 2] = '6'.code.toByte()
        assertRefused(bytes, SaveProblem.DAMAGED, "its header fails its checksum")
        assertEquals(
            SaveProblem.DAMAGED,
            assertFailsWith<WorldFormatException> { WorldCodec.decodeHeader(bytes) }.problem,
            "the listing read the edited header as whole"
        )
    }

    @Test
    fun `a header from one save in front of another's chunks is refused`() = runTest {
        // The same grid and layout at another seed: its header is whole and so are the other
        // save's chunks, and only the binding between them says they were never one file.
        val mine = rawSave()
        val theirs = rawSave(SyntheticWorlds.of(WorldGenConfig(seed = 6L, width = 64, height = 64)))
        val headerEnd = WorldCodec.PREFIX_BYTES + getInt(theirs, WorldCodec.HEADER_LENGTH_OFFSET)
        assertEquals(headerEnd, WorldCodec.PREFIX_BYTES + getInt(mine, WorldCodec.HEADER_LENGTH_OFFSET))
        val spliced = theirs.copyOfRange(0, headerEnd) + mine.copyOfRange(headerEnd, mine.size)
        assertRefused(spliced, SaveProblem.DAMAGED, "chunk 0 fails its checksum")
    }

    @Test
    fun `bytes after the end of the world are refused`() = runTest {
        assertRefused(rawSave() + byteArrayOf(1, 2, 3), SaveProblem.DAMAGED, "bytes after the end")
    }

    @Test
    fun `a chunk its decompressor expands past its frame is refused`() = runTest {
        // What the codec does with the one byte past the limit a decompressor hands back. Whether
        // the real decompressors stop at that byte rather than expanding everything is asked of
        // each of them: the JVM's in `GzipCompressorTest` in `:desktop`, the browser's in its
        // self-test.
        val squeezed = WorldCodec.encode(document(synthetic), synthetic, RunLengthCompressor)
        val bomb = object : Compressor {
            override val name = "runs"
            override suspend fun compress(data: ByteArray): ByteArray? = null
            override suspend fun decompress(data: ByteArray, limitBytes: Int): ByteArray = ByteArray(limitBytes + 1)
        }
        assertRefused(squeezed, SaveProblem.DAMAGED, "expands to more than", bomb)
    }

    @Test
    fun `a cancelled load leaves as a cancellation, not as no such save`() = runTest {
        // decodeOrNull caught everything, so a load cancelled halfway read as a file that did not
        // exist. The reason a load did not finish is the caller's to know.
        val bytes = rawSave()
        var served = 0
        val cancelling = object : SaveSource {
            override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
                if (served > 10_000) throw kotlin.coroutines.cancellation.CancellationException("the reader went away")
                val count = minOf(length, bytes.size - served)
                bytes.copyInto(into, offset, served, served + count)
                served += count
                return count
            }
        }
        assertFailsWith<kotlin.coroutines.cancellation.CancellationException> { WorldCodec.open(cancelling) }
    }

    /**
     * Every array in the world, compared by raw bits.
     *
     * Driven off the writer's own section list rather than a hand-written one, so an array added
     * to the format in future is compared the day it is added instead of the day someone
     * remembers to add it here.
     */
    private suspend fun assertArraysIdentical(expected: WorldMap, actual: WorldMap) {
        val sink = { world: WorldMap ->
            WorldSections.SECTIONS.associate { spec ->
                spec.name to when (spec) {
                    is FloatSection -> spec.of(world).map { it.toRawBits() }
                    is IntSection -> spec.of(world).toList()
                    is ByteSection -> ByteArray(world.width * world.height).also {
                        spec.of(world).copyInto(it, 0, 0, it.size)
                    }.toList()
                }
            }
        }
        val before = sink(expected)
        val after = sink(actual)
        for ((name, values) in before) {
            val other = after.getValue(name)
            assertEquals(values.size, other.size, "$name changed length")
            val first = values.indices.firstOrNull { values[it] != other[it] }
            assertNull(first, "$name differs at ${first}")
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
            for (p in river.widthRatio.indices) {
                assertEquals(
                    river.widthRatio[p].toRawBits(), other.widthRatio[p].toRawBits(),
                    "river $i width $p differs"
                )
            }
        }
    }
}
