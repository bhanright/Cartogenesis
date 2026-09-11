package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Culture
import com.cartogenesis.worldgen.pipeline.Lake
import com.cartogenesis.worldgen.pipeline.Landmark
import com.cartogenesis.worldgen.pipeline.Nation
import com.cartogenesis.worldgen.pipeline.Plate
import com.cartogenesis.worldgen.pipeline.River
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything about a world that is small enough to read as text.
 *
 * Rivers, lakes, realms, peoples and landmarks are lists of a few hundred entries between them,
 * not a value per cell, so they live in the header with the settings rather than in the binary
 * payload — which also means a tool can look at what a save contains without knowing the layout.
 */
@Serializable
data class WorldLists(
    val plates: List<Plate>,
    /** The raw height the shoreline sits at. Cheap to store, and not derivable from the arrays. */
    val seaThreshold: Float,
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
            seaThreshold = world.sea.threshold,
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
 * why the section directory is repeated in it: sizes and names are answerable without expanding
 * ninety megabytes of arrays.
 */
@Serializable
data class SaveHeader(
    val formatVersion: Int,
    val document: WorldDocument,
    /** `gzip`, or `none` from a platform that cannot compress. */
    val compression: String,
    /** Which front end, on which version, produced these bytes. */
    val writtenBy: String,
    val world: WorldLists? = null,
    val sections: List<SectionInfo> = emptyList(),
    /** Length of the payload as stored, so a truncated file is obvious before it is parsed. */
    val payloadBytes: Int = 0
)

/** A save as it comes off the shelf: the document, and the world itself if the file carried one. */
class WorldSave(val document: WorldDocument, val world: WorldMap?)

/**
 * The save format: a JSON header, then one binary section per per-cell array.
 *
 * It used to be a seed and a config, and the world was rebuilt on open. That made a save a few
 * kilobytes and made bit-identical generation on every platform a hard requirement — a world
 * saved in a browser and opened on the desktop had to come out the same, so every change to the
 * pipeline was followed by an afternoon of proving the two agreed. The GPU toggle broke the rule
 * outright and had to carry its terrain in the file to get round it.
 *
 * So a save now carries the world. Opening one is deserialisation followed by a generation pass
 * that reuses every stage and computes none, which is the same reuse chain live editing already
 * runs on: edit a setting after opening and only what lies downstream of it recomputes.
 *
 * The layout, all little-endian:
 *
 * ```
 * "CGWD"                 4 bytes of magic
 * int32                  format version
 * int32                  header length in bytes
 * header                 UTF-8 JSON: settings, overrides, labels, lists, section directory
 * payload                gzip of, or raw, a run of sections:
 *                          int32 name length, ASCII name,
 *                          int32 element type, int32 element count, int32 byte length, elements
 * ```
 *
 * Version 2 saves are plain JSON with no magic and no payload. They still open: the world is
 * regenerated from the seed exactly as it was before, and the next save writes it in full.
 */
object WorldCodec {

    /**
     * 3: the container above. 2: JSON text, seed only, still read.
     *
     * This is finally load-bearing. Before, nothing ever looked at it.
     */
    const val FORMAT_VERSION = 3

    /** What a version-2 file is. Nothing writes one any more; everything still reads one. */
    const val LEGACY_TEXT_VERSION = 2

    private val MAGIC = byteArrayOf('C'.code.toByte(), 'G'.code.toByte(), 'W'.code.toByte(), 'D'.code.toByte())

    /** Magic, version and header length, before the header itself starts. */
    const val PREFIX_BYTES = 12

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Writes a container. A null [world] writes a header-only save, which opens by regenerating.
     *
     * [writtenBy] names the front end and its version, which is the sort of thing that is only
     * ever wanted when a file will not open and nobody can remember where it came from.
     */
    suspend fun encode(
        document: WorldDocument,
        world: WorldMap?,
        compressor: Compressor = NoCompression,
        writtenBy: String = "unknown"
    ): ByteArray {
        val sections = world?.let { WorldSections.of(it) }.orEmpty()
        val (raw, directory) = if (sections.isEmpty()) {
            ByteArray(0) to emptyList()
        } else {
            WorldSections.write(sections)
        }
        val compressed = if (raw.isEmpty()) null else compressor.compress(raw)
        val payload = compressed ?: raw

        val header = SaveHeader(
            formatVersion = FORMAT_VERSION,
            document = document,
            compression = if (compressed != null) compressor.name else NoCompression.name,
            writtenBy = writtenBy,
            world = world?.let { WorldLists.of(it) },
            sections = directory,
            payloadBytes = payload.size
        )
        val headerBytes = json.encodeToString(header).encodeToByteArray()

        val writer = ByteWriter(PREFIX_BYTES + headerBytes.size + payload.size)
        writer.putBytes(MAGIC)
        writer.putInt(FORMAT_VERSION)
        writer.putInt(headerBytes.size)
        writer.putBytes(headerBytes)
        writer.putBytes(payload)
        return writer.bytes
    }

    /** True when these bytes start with the container magic rather than being version-2 text. */
    fun isContainer(bytes: ByteArray): Boolean =
        bytes.size >= PREFIX_BYTES && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    /**
     * The header alone — no payload touched, so this stays cheap on a file of any size.
     *
     * Also reads a version-2 save, since the library has to list both.
     */
    fun decodeHeader(bytes: ByteArray): SaveHeader {
        if (!isContainer(bytes)) {
            return SaveHeader(
                formatVersion = LEGACY_TEXT_VERSION,
                document = decodeText(bytes.decodeToString()),
                compression = NoCompression.name,
                writtenBy = "version $LEGACY_TEXT_VERSION save"
            )
        }
        val reader = ByteReader(bytes, position = MAGIC.size)
        val version = reader.getInt()
        if (version > FORMAT_VERSION) {
            throw WorldFormatException(
                "this save was written by a newer build (format $version, this one reads $FORMAT_VERSION)"
            )
        }
        val headerLength = reader.getInt()
        return json.decodeFromString(reader.getBytes(headerLength).decodeToString())
    }

    fun decodeHeaderOrNull(bytes: ByteArray): SaveHeader? =
        runCatching { decodeHeader(bytes) }.getOrNull()

    /**
     * The whole thing.
     *
     * A version-2 save comes back with a null world, which the caller regenerates from the config
     * — the behaviour that build had. A container missing a section throws, because a save that
     * has lost an array is a file this build cannot open rather than a world with a hole in it.
     */
    suspend fun decode(bytes: ByteArray, compressor: Compressor = NoCompression): WorldSave {
        val header = decodeHeader(bytes)
        if (!isContainer(bytes) || header.world == null || header.sections.isEmpty()) {
            return WorldSave(header.document, null)
        }

        // Where the payload starts comes from the prefix rather than from re-encoding the
        // header: a second encoding need not be byte-identical to the one in the file.
        val reader = ByteReader(bytes, position = MAGIC.size + 4)
        val headerLength = reader.getInt()
        val stored = bytes.copyOfRange(PREFIX_BYTES + headerLength, bytes.size)
        if (header.payloadBytes != stored.size) {
            throw WorldFormatException(
                "payload is ${stored.size} bytes, header says ${header.payloadBytes}"
            )
        }

        val payload = when (header.compression) {
            NoCompression.name -> stored
            compressor.name -> compressor.decompress(stored)
                ?: throw WorldFormatException("this platform cannot expand ${header.compression} saves")
            else -> throw WorldFormatException(
                "save is compressed with '${header.compression}', which this platform cannot expand"
            )
        }

        val world = WorldSections.rebuild(
            config = header.document.config,
            lists = header.world,
            labels = header.document.labels,
            sections = WorldSections.read(payload)
        )
        return WorldSave(header.document, world)
    }

    suspend fun decodeOrNull(bytes: ByteArray, compressor: Compressor = NoCompression): WorldSave? =
        runCatching { decode(bytes, compressor) }.getOrNull()

    /** A version-2 save: JSON text, seed and settings only. */
    fun decodeText(text: String): WorldDocument = json.decodeFromString(text)

    /** Null rather than throwing, so one unreadable file cannot take the whole library down. */
    fun decodeTextOrNull(text: String): WorldDocument? =
        runCatching { decodeText(text) }.getOrNull()
}
