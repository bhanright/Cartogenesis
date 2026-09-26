package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Culture
import com.cartogenesis.worldgen.pipeline.Lake
import com.cartogenesis.worldgen.pipeline.Landmark
import com.cartogenesis.worldgen.pipeline.Nation
import com.cartogenesis.worldgen.pipeline.Plate
import com.cartogenesis.worldgen.pipeline.River
import com.cartogenesis.worldgen.pipeline.SeaLevelResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything about a world that is not a value per cell.
 *
 * Rivers, lakes, realms, peoples and landmarks are lists of a few hundred entries between them, not
 * a value per cell, so they travel as JSON — but as the payload's first record rather than in the
 * header, because a river is a list of cells and at 2048 the rivers alone run to megabytes. In the
 * payload they are compressed with everything else, and a library listing, which reads only the
 * header, never reads them at all.
 */
@Serializable
data class WorldLists(
    val plates: List<Plate>,
    /**
     * Where the shoreline sits, in the height field's own units — see
     * [SeaLevelResult.shorelineHeight]. Cheap to store, and not derivable from the arrays: the
     * payload carries elevation *relative* to the shoreline, which is the one field that has
     * already had this number subtracted out of it.
     */
    val shorelineHeight: Float,
    /**
     * How fast this world's ridges spread, in kilometres per million years — solved from the plate
     * partition rather than declared, and so not derivable from the arrays either. See
     * [com.cartogenesis.worldgen.pipeline.PlateStage.seafloorAgeOf].
     */
    val seafloorHalfSpreadingRateKmPerMyr: Double,
    val landCellCount: Int,
    val rivers: List<River>,
    val lakes: List<Lake>,
    val nations: List<Nation>,
    val cultures: List<Culture>,
    val landmarks: List<Landmark>
) {
    companion object {
        fun of(world: WorldMap): WorldLists = WorldLists(
            plates = world.plates.plates,
            shorelineHeight = world.sea.shorelineHeight,
            seafloorHalfSpreadingRateKmPerMyr = world.plates.seafloorHalfSpreadingRateKmPerMyr,
            landCellCount = world.sea.landCellCount,
            rivers = world.rivers.rivers,
            lakes = world.rivers.lakes.lakes,
            nations = world.nations.nations,
            cultures = world.cultures.cultures,
            landmarks = world.landmarks.landmarks
        )
    }
}

/**
 * The text part of a container, which sits at the front uncompressed.
 *
 * A reader that only wants a title and a date — the library listing does — stops here, which is
 * why the section directory is in it: what a file holds, and how large each part is, is
 * answerable without expanding a byte of the payload. Every field is required. A save carries its
 * whole world or it is not a save, so there is no header that stands on its own.
 */
@Serializable
data class SaveHeader(
    val formatVersion: Int,
    val document: WorldDocument,
    /** How the payload's compressed chunks were squeezed: `gzip`, or `none` from a platform that cannot. */
    val compression: String,
    /** Which front end, on which version, produced these bytes. */
    val writtenBy: String,
    /** The lists' record and then every per-cell array, in the order the payload holds them. */
    val sections: List<SectionInfo>,
    /** The payload's length once expanded: every record, prefixes and all. */
    val payloadBytes: Long
)

/** A save as it comes off the shelf: the document, and the world it carried. */
class WorldSave(val document: WorldDocument, val world: WorldMap)

/**
 * The save format: a JSON header, then the world's lists and one binary section per per-cell
 * array, in compressed chunks.
 *
 * It used to be a seed and a config, and the world was rebuilt on open. That made a save a few
 * kilobytes and made bit-identical generation on every platform a hard requirement — a world
 * saved in a browser and opened on the desktop had to come out the same, so every change to the
 * pipeline was followed by an afternoon of proving the two agreed. The GPU toggle broke the rule
 * outright and had to carry its terrain in the file to get round it.
 *
 * So a save carries the world, and opening one is deserialisation and nothing else: no stage is
 * run, so nothing about the world depends on this machine or this build agreeing with the one
 * that wrote it. A file that does not hold a complete world, whole and consistent, is refused with
 * the reason — see [SaveProblem] — and never opened as whatever its settings would regenerate.
 *
 * The layout, all little-endian:
 *
 * ```
 * "CGWD"                 4 bytes of magic
 * int32                  format version
 * int32                  header length in bytes
 * int32                  CRC-32 of the header's bytes
 * header                 UTF-8 JSON: the document (settings, overrides, labels, title), the
 *                        compression, the writer, and the directory of the payload's records
 * frames                 one per chunk of the expanded payload, each:
 *                          int32 raw length, int32 stored length, int32 checksum of the raw
 *                          bytes bound to the header and to the chunk's place (see
 *                          [chunkChecksum]), int32 method (0 stored, 1 compressed), the stored bytes
 *                        and then a frame of sixteen zero bytes, and nothing after it
 * ```
 *
 * The expanded payload is a run of records, each an int32 name length, the ASCII name, an int32
 * element type, an int32 element count, an int64 byte length and the elements: first the lists as
 * JSON, then every array of [WorldSections.SECTIONS].
 *
 * Only the current version opens. Anything older is refused by name rather than read, because a
 * header is JSON decoded with unknown keys ignored: a file whose settings were written under the
 * names an older build used would parse without complaint and come back with this build's
 * *defaults* wherever a name has since moved, which is a world quietly unlike the one that was
 * saved. Refusing is the only honest answer, and nothing has been distributed for the refusal to
 * cost anybody a file.
 */
object WorldCodec {

    /**
     * The only version this build reads or writes.
     *
     * 14 because a save became a stream. The payload is cut into chunks, each compressed and
     * checksummed on its own, with every length counted in 64 bits, so a 4096 world — 2.45 GB of
     * arrays — saves and opens without any array its size existing in between. The lists moved out
     * of the header into the payload's first record, the header lost its header-only form and its
     * optional fields, and [WorldDocument] lost the terrain snapshot it had carried, empty, since
     * the container format. A format-13 file has its payload as one gzip stream and its lists in
     * its header, and this reader does not read either. The header is checksummed too, and every
     * chunk's checksum is bound to it, so one changed digit of a seed is found rather than opened
     * as the same arrays under another world's settings.
     *
     * 13 because a channel begins where the ground can cut one and the rounds that cut it read
     * the rain. Two chunks landed on this version and neither shipped without the other, so the
     * one entry covers both. `rivers.sourceFlowShare`, `rivers.maxRivers` and
     * `rivers.minLengthCells` are gone — a share of the world's runoff, a count of courses and a
     * count of cells, all three of them a different thing at every grid — and
     * `rivers.channelHeadAreaSlopeKm2`, `rivers.coverRaisesChannelHead` and
     * `rivers.shortestDrawnCourseKm` stand in their place. `erosion.climateFeed` arrived beside
     * them, and with it the hydraulic rounds weight their flow accumulation by a provisional
     * rainfall and hold their incision back by the plant cover under it, so the terrain a seed
     * produces is not the terrain the same seed produced before.
     *
     * A format-12 file has neither set of keys. It would open with this build's defaults wherever
     * one of the river names has moved and redraw its rivers by a rule its author never chose, and
     * it would be re-cut by the climate feed as well, whose default is *on* — worse than the usual
     * case of rule 11 of docs/CONVENTIONS.md, because the default is the *new* behaviour rather
     * than the old one, so nothing about the reopened world would look wrong enough to notice.
     *
     * 12 because the ground gained a cover. The climate result now carries a vegetation density
     * per cell and a permafrost zone per cell, saved as `climate.vegetationDensity` and
     * `climate.permafrost`, and the settings gained a `vegetation` section for the two controls
     * that switch them off; `climate.vegetationRecycling` is new beside them, and it changes the
     * moisture march itself, so a format-11 file's climate is not this build's climate on the same
     * seed. A format-11 save carries neither array, so the world in it would open with a cover of
     * zero everywhere — every land cell drawn as bare ground — and with the recycling setting
     * filled in from this build's default, which is a different rainfall from the one that was
     * saved. Rule 11 of docs/CONVENTIONS.md is what this is.
     *
     * 11 because the ice sheet gained a profile and the climate's moisture budget gained its
     * units. I1 and W3 took 11 on their own branches and met before either shipped, so one version
     * covers both changes rather than two covering one each; no format-11 file was ever written by
     * a build that had only half of them.
     *
     * The ice half: the two settings that guessed at a profile went.
     * `isostasy.iceSheetThicknessMetres` and `isostasy.iceSheetMarginRampKm` said how thick a
     * sheet was taken to be and over how far it thinned; I1 works the thickness out from Nye's and
     * Vialov's plastic profile instead, so both keys are gone and `glaciation` gained
     * `reliefWindowOctagon`, `outletTroughs` and `outletCatchment` in their place. A format-10
     * file carries the two dead keys, which this build ignores, and carries none of the three new
     * ones, which it would fill in with its own defaults: the world in the file would still draw,
     * and the moment a reader changed a knob and asked for it again it would be carved by rules
     * the file had never heard of. Rule 11 of docs/CONVENTIONS.md is what this is.
     *
     * The climate half: the moisture march charged its rain, its evaporation and its ground's
     * return per cell of wind travel, so the same journey emptied a parcel more times over on a
     * finer grid. `climate.baseRainRate`, `climate.evaporationRate` and `climate.landRecoveryRate`
     * are gone, and `climate.depletionLengthKm`, `climate.oceanEvaporationLengthKm` and
     * `climate.evapotranspirationLengthKm` stand in their places, with `climate.convergenceRain`
     * and `climate.marineInversion` beside them. A format-10 file's rates would be dropped and
     * this build's lengths used instead, which is a different climate on the same seed.
     *
     * 10 because the ice gained a thickness. `glaciation.valleyIceThicknessMetres` is how far above
     * its bed the ice in a trough stands, and the carving shares its cut by how deeply a cell lies
     * under that as well as by how far across the section it lies — which is the whole of I2's fix
     * and the reason the stage no longer planes a ridge down to the height of the valley beside it.
     * A format-9 file has no such key, so it would open with this build's 600 m and *regenerate* a
     * world carved by a rule the world in the file was never carved by. The world in the file would
     * still draw correctly, since a save has carried every stage since the container format; it is
     * the moment a reader changes a knob and asks for it again that the two would part company.
     *
     * 9 because the crust became a thing the world carries. The plate stage now writes which crust
     * each cell is made of and how fast the rock under it is still rising, the settings gained an
     * `isostasy` section for the densities and the elastic thickness, and the height field itself
     * changed meaning: it is an absolute altitude on the world's own ruler where it used to be
     * renormalised to whatever the tallest cell of that particular world happened to be. A format-8
     * file's heights would parse and mean something else, which is exactly the kind of silence
     * refusing by version exists to prevent. S2 and W1 each took 8 on their own branch, so the two
     * had to be told apart when they met and the solid earth's is 9.
     *
     * 8 because the climate gained two sea-ice masks, one per season, and lost the two anchors of
     * the latitude curve the energy balance replaced: `climate.equatorTemperatureC` and
     * `climate.poleTemperatureC` are gone and `climate.globalMeanShiftC` stands in their place,
     * along with `climate.continentality`, which is now two heat capacities and a coastline rather
     * than a setting. An older file would open with this build's defaults wherever one of those has
     * moved, which is a world quietly unlike the one that was saved.
     *
     * 7 because the world gained a `scale` section — its width in kilometres, the two ends of its
     * vertical range in metres and the years a hydraulic round stands for — and every physical
     * knob moved onto it: the sea's lowstand and the glacial depths became metres, every reach and
     * radius became kilometres, and `climate.maxAltitudeMetres` and `nations.worldWidthKm` left the
     * sections they were lodged in.
     *
     * 6 because a river's drawn size stopped being a width in cells and became
     * [com.cartogenesis.worldgen.pipeline.River.widthRatio], a fraction of the map's largest river:
     * the old key would parse and be ignored, leaving every river at the hairline. 5 and 4 were the
     * sweep for human-readable names, which moved serialised property names with no compatibility
     * shim — 4 its first pass over the shared model, the shoreline height and the terrain gradients
     * among them, and 5 its second over the tectonics, sea, erosion and glaciation settings, where
     * every cell-valued name took a `Cells` suffix. 3 was the container below with none of that, 2
     * the JSON text that preceded it; none of them opens.
     */
    const val FORMAT_VERSION = 14

    private val MAGIC = byteArrayOf('C'.code.toByte(), 'G'.code.toByte(), 'W'.code.toByte(), 'D'.code.toByte())

    /** Magic, version, header length and the header's checksum, before the header itself starts. */
    const val PREFIX_BYTES = 16

    /** Where the format version sits in that prefix: straight after the magic. */
    const val VERSION_OFFSET = 4

    /** Where the header's own length sits: after the magic and the version. */
    const val HEADER_LENGTH_OFFSET = 8

    /** Where the header's checksum sits: after its length. */
    const val HEADER_CHECKSUM_OFFSET = 12

    /**
     * How much of the expanded payload is compressed, checksummed and handed on at a time: one
     * mebibyte.
     *
     * What a save costs beyond the world itself is about three of these — the chunk being filled,
     * its compressed copy and the compressor's own buffers — so a mebibyte keeps that under 1% of
     * even a 512 world's 38 MB of arrays. Smaller costs more than it saves: each chunk is a frame's
     * sixteen bytes and a gzip header and trailer of eighteen, and in a browser a promise round trip
     * through `CompressionStream`, which at 2048 is already 586 of them.
     */
    const val CHUNK_BYTES = 1 shl 20

    /**
     * The most cells a save may have: 4096 by 4096, the largest working resolution the interface
     * offers (`Knobs.RESOLUTIONS` in `:ui`, whose test holds it to this).
     *
     * A file claiming more is refused before anything is allocated for it. The 8192 export is
     * made and drawn without ever being saved, so it does not need this raised.
     */
    const val LARGEST_GRID_CELLS = 4096 * 4096

    /**
     * The longest header this reads, in bytes: sixteen mebibytes.
     *
     * The header is the document and the directory. The directory is forty-two entries whatever
     * the grid, and the document is the settings, the title and the reader's own edits and labels:
     * 10.4 to 10.6 KB on every world measured at 512, 1024 and 2048 and a synthetic 4096, none of
     * them edited. What could make one long is a reader's edits and labels, so the bound leaves
     * room for a hundred thousand of them and is still a size a browser tab can hold twice over.
     */
    const val LARGEST_HEADER_BYTES = 16 shl 20

    /**
     * The longest the lists' JSON may be, in bytes: 64 mebibytes.
     *
     * Rivers are most of it, each a list of cells, so it grows about twofold with each doubling of
     * the grid: 0.26 to 0.39 MB over twelve seeds at 512, 0.73 and 0.77 MB over two at 1024, and
     * 1.8 MB for seed 42 at 2048, which puts a 4096 world near 4 MB. Sixteen times that is room for
     * a world with far more rivers than any measured, and still a string a browser tab can parse.
     */
    const val LARGEST_LISTS_BYTES = 64 shl 20

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Writes [world] to [sink] as a container, filed under [document].
     *
     * [document]'s settings must be the ones [world] was made with, and its id one this build
     * writes: a save that files one world under another's settings would reopen as a world nobody
     * made, and a bad id would name a file that is not in the library. Either is a mistake in the
     * caller, so it throws rather than writing.
     *
     * [writtenBy] names the front end and its version, which is the sort of thing that is only
     * ever wanted when a file will not open and nobody can remember where it came from.
     */
    suspend fun write(
        document: WorldDocument,
        world: WorldMap,
        sink: SaveSink,
        compressor: Compressor = NoCompression,
        writtenBy: String = "unknown"
    ) {
        require(document.config == world.config) {
            "the document's settings are not the settings its world was made with"
        }
        require(WorldDocument.isValidId(document.id)) { "'${document.id}' is not a save id" }
        val cells = world.width.toLong() * world.height
        require(cells <= LARGEST_GRID_CELLS) { "a ${world.width} by ${world.height} world is larger than a save holds" }

        val listsJson = json.encodeToString(WorldLists.of(world)).encodeToByteArray()
        require(listsJson.size <= LARGEST_LISTS_BYTES) {
            "this world's lists are ${listsJson.size} bytes, more than a save holds"
        }
        val directory = WorldSections.directory(cells.toInt(), listsJson.size)
        val header = SaveHeader(
            formatVersion = FORMAT_VERSION,
            document = document,
            compression = compressor.name,
            writtenBy = writtenBy,
            sections = directory,
            payloadBytes = WorldSections.payloadBytes(directory)
        )
        val headerBytes = json.encodeToString(header).encodeToByteArray()
        require(headerBytes.size <= LARGEST_HEADER_BYTES) { "the header is ${headerBytes.size} bytes, more than a save holds" }

        val headerChecksum = Crc32.of(headerBytes)
        val prefix = ByteArray(PREFIX_BYTES)
        MAGIC.copyInto(prefix)
        putInt(prefix, VERSION_OFFSET, FORMAT_VERSION)
        putInt(prefix, HEADER_LENGTH_OFFSET, headerBytes.size)
        putInt(prefix, HEADER_CHECKSUM_OFFSET, headerChecksum)
        sink.write(prefix)
        sink.write(headerBytes)

        val writer = PayloadWriter(sink, compressor, headerChecksum)
        WorldSections.write(world, listsJson, writer)
        check(writer.written == header.payloadBytes) {
            "wrote ${writer.written} payload bytes where the directory promised ${header.payloadBytes}"
        }
        writer.finish()
    }

    /** The whole container as one array: for a small world, a test or a fixture, never a library save. */
    suspend fun encode(
        document: WorldDocument,
        world: WorldMap,
        compressor: Compressor = NoCompression,
        writtenBy: String = "unknown"
    ): ByteArray {
        val sink = ByteArraySink()
        write(document, world, sink, compressor, writtenBy)
        return sink.toByteArray()
    }

    /** True when these bytes start with the container magic rather than being something else. */
    fun isContainer(bytes: ByteArray): Boolean =
        bytes.size >= PREFIX_BYTES && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    /**
     * The header alone, from the front of a file — no payload touched, so this stays cheap on a
     * file of any size. [bytes] may be the whole file or any prefix long enough to hold the header.
     *
     * Throws [WorldFormatException] for anything that is not this build's format, in both
     * directions, for a header that disagrees with this build's layout, and for a grid wider than
     * [limit] allows. See the note above on why an older file is turned away rather than read with
     * the keys it happens to share.
     */
    fun decodeHeader(bytes: ByteArray, limit: OpeningLimit? = null): SaveHeader {
        val headerLength = headerLengthFrom(bytes, minOf(bytes.size, PREFIX_BYTES))
        if (bytes.size - PREFIX_BYTES < headerLength) {
            throw WorldFormatException(
                SaveProblem.INCOMPLETE,
                "it ends inside its header, after ${bytes.size} bytes of ${PREFIX_BYTES + headerLength}"
            )
        }
        return headerFrom(
            bytes.copyOfRange(PREFIX_BYTES, PREFIX_BYTES + headerLength), getInt(bytes, HEADER_CHECKSUM_OFFSET), limit
        )
    }

    /**
     * The whole save from [source], checked from its first byte to its last.
     *
     * Throws [WorldFormatException] with the reason for anything short of a complete, consistent
     * world of this format, or for a grid wider than [limit] allows, which is refused from the
     * header before any array is allocated; [open] is the same with the reason handed back instead.
     */
    suspend fun read(
        source: SaveSource,
        compressor: Compressor = NoCompression,
        limit: OpeningLimit? = null
    ): WorldSave {
        val prefix = ByteArray(PREFIX_BYTES)
        val arrived = source.readFully(prefix, 0, PREFIX_BYTES)
        val headerLength = headerLengthFrom(prefix, arrived)
        val headerBytes = ByteArray(headerLength)
        if (source.readFully(headerBytes, 0, headerLength) < headerLength) {
            throw WorldFormatException(SaveProblem.INCOMPLETE, "it ends inside its header")
        }
        val headerChecksum = getInt(prefix, HEADER_CHECKSUM_OFFSET)
        val header = headerFrom(headerBytes, headerChecksum, limit)
        val reader = PayloadReader(source, header.compression, compressor, header.payloadBytes, headerChecksum)
        val world = WorldSections.read(reader, header.document.config, header.sections, header.document.labels) {
            listsFrom(it)
        }
        reader.finish()
        return WorldSave(header.document, world)
    }

    /**
     * The world in [source], or the reason it will not open.
     *
     * Only refusals are caught. A cancelled load is not one, and leaves as the cancellation it is;
     * a failure of the storage itself is the platform's to turn into [SaveProblem.UNREADABLE].
     */
    suspend fun open(
        source: SaveSource,
        compressor: Compressor = NoCompression,
        limit: OpeningLimit? = null
    ): LoadOutcome =
        try {
            LoadOutcome.Loaded(read(source, compressor, limit))
        } catch (refused: WorldFormatException) {
            LoadOutcome.Refused(SaveRefusal(refused.problem, refused.detail))
        }

    /** [read] over an array already in memory. */
    suspend fun decode(bytes: ByteArray, compressor: Compressor = NoCompression): WorldSave =
        read(ByteArraySource(bytes), compressor)

    /**
     * The header's length, from the sixteen bytes in front of it, of which [available] arrived.
     *
     * The first question is whether this is a save at all, and a file of zeros is answered as an
     * incomplete one rather than as a stranger: zeros where a save begins are what a copy that has
     * not finished arriving looks like, and a sync client's placeholder is often exactly that.
     */
    private fun headerLengthFrom(prefix: ByteArray, available: Int): Int {
        if (available == 0) throw WorldFormatException(SaveProblem.INCOMPLETE, "the file is empty")
        if ((0 until available).all { prefix[it].toInt() == 0 }) {
            throw WorldFormatException(SaveProblem.INCOMPLETE, "it holds nothing but zeros where a save begins")
        }
        if ((0 until minOf(available, MAGIC.size)).any { prefix[it] != MAGIC[it] }) {
            if (prefix[0] == '{'.code.toByte()) {
                throw WorldFormatException(
                    SaveProblem.WRONG_VERSION,
                    "it is a plain JSON save from before format 3, with no world in it; this build reads format $FORMAT_VERSION"
                )
            }
            throw WorldFormatException(SaveProblem.NOT_A_SAVE, "")
        }
        if (available < PREFIX_BYTES) {
            throw WorldFormatException(SaveProblem.INCOMPLETE, "it ends after $available bytes")
        }
        val version = getInt(prefix, VERSION_OFFSET)
        if (version > FORMAT_VERSION) {
            throw WorldFormatException(
                SaveProblem.WRONG_VERSION,
                "a newer build wrote it, in format $version; this one reads $FORMAT_VERSION"
            )
        }
        if (version < FORMAT_VERSION) {
            throw WorldFormatException(
                SaveProblem.WRONG_VERSION,
                "it is format $version and this build reads $FORMAT_VERSION; the settings in an older " +
                    "header are written under names this build no longer knows, so it would open as a " +
                    "different world rather than as the one that was saved"
            )
        }
        val headerLength = getInt(prefix, HEADER_LENGTH_OFFSET)
        if (headerLength <= 0) throw WorldFormatException(SaveProblem.DAMAGED, "its header is $headerLength bytes long")
        if (headerLength > LARGEST_HEADER_BYTES) {
            throw WorldFormatException(SaveProblem.TOO_LARGE, "its header claims $headerLength bytes")
        }
        return headerLength
    }

    /**
     * The header parsed and held to this build's layout: its id, its grid, and a directory that is
     * this build's own for that grid, entry for entry. What the directory promises the payload is
     * then held to as it is read.
     */
    private fun headerFrom(bytes: ByteArray, checksum: Int, limit: OpeningLimit?): SaveHeader {
        if (Crc32.of(bytes) != checksum) {
            throw WorldFormatException(SaveProblem.DAMAGED, "its header fails its checksum")
        }
        val header = try {
            json.decodeFromString<SaveHeader>(bytes.decodeToString())
        } catch (refused: WorldFormatException) {
            throw refused
        } catch (unparsed: IllegalArgumentException) {
            throw WorldFormatException(SaveProblem.DAMAGED, "its header does not parse: ${unparsed.message?.lineSequence()?.first()}")
        }
        fun damaged(detail: String): Nothing = throw WorldFormatException(SaveProblem.DAMAGED, detail)

        if (header.formatVersion != FORMAT_VERSION) {
            damaged("its header says format ${header.formatVersion} where its prefix says $FORMAT_VERSION")
        }
        val document = header.document
        if (!WorldDocument.isValidId(document.id)) damaged("its id '${document.id}' is not one this build writes")
        document.labels.forEach {
            if (!it.x.isFinite() || !it.y.isFinite()) damaged("the label '${it.text}' has no position")
        }
        val config = document.config
        val cells = config.width.toLong() * config.height
        if (config.width <= 0 || config.height <= 0) damaged("its grid is ${config.width} by ${config.height}")
        if (cells > LARGEST_GRID_CELLS) {
            throw WorldFormatException(SaveProblem.TOO_LARGE, "its grid is ${config.width} by ${config.height}")
        }
        if (limit != null && maxOf(config.width, config.height) > limit.largestSide) {
            throw WorldFormatException(
                SaveProblem.TOO_LARGE,
                "its grid is ${config.width} by ${config.height}; ${limit.because}"
            )
        }

        val lists = header.sections.firstOrNull() ?: damaged("its directory is empty")
        if (lists.name == WorldSections.LISTS && lists.count > LARGEST_LISTS_BYTES) {
            throw WorldFormatException(SaveProblem.TOO_LARGE, "its lists claim ${lists.count} bytes")
        }
        val expected = WorldSections.directory(cells.toInt(), lists.count.coerceAtLeast(0))
        if (header.sections.size != expected.size) {
            damaged("its directory lists ${header.sections.size} sections where this build's format has ${expected.size}")
        }
        header.sections.zip(expected).forEachIndexed { index, (found, wanted) ->
            if (found != wanted) damaged("entry $index of its directory is ${describe(found)} where this build's format has ${describe(wanted)}")
        }
        val payloadBytes = WorldSections.payloadBytes(expected)
        if (header.payloadBytes != payloadBytes) {
            damaged("its header promises ${header.payloadBytes} payload bytes where its directory adds up to $payloadBytes")
        }
        return header
    }

    private fun describe(entry: SectionInfo): String =
        "'${entry.name}' (${entry.type}, ${entry.count} elements, ${entry.bytes} bytes at ${entry.offset})"

    private fun listsFrom(bytes: ByteArray): WorldLists =
        try {
            json.decodeFromString<WorldLists>(bytes.decodeToString())
        } catch (unparsed: IllegalArgumentException) {
            throw WorldFormatException(SaveProblem.DAMAGED, "its lists do not parse: ${unparsed.message?.lineSequence()?.first()}")
        }
}

/**
 * The widest grid a host will open, below the format's own [WorldCodec.LARGEST_GRID_CELLS], and
 * what it tells a reader whose save is wider.
 *
 * The format's bound is what any build can read; this is what one host can *hold*. A browser tab
 * that cannot make a 4096 world cannot hold the 2.45 GB of arrays a saved one opens into either, so
 * it refuses the file from its header, with [because] saying where it can be opened, rather than
 * decoding it into a tab that dies.
 */
data class OpeningLimit(
    /** The most cells across or down a save may have to be opened here. */
    val largestSide: Int,
    /** The clause that follows the save's own grid in the refusal: why, and where else. */
    val because: String
)
