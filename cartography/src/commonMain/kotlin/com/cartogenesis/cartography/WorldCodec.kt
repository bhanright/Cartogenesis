package com.cartogenesis.cartography

import com.cartogenesis.worldgen.GenerationStage
import com.cartogenesis.worldgen.WorldGenerationEngine
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
 * Everything about a world that is small enough to read as text.
 *
 * Rivers, lakes, realms, peoples and landmarks are lists of a few hundred entries between them,
 * not a value per cell, so they live in the header with the settings rather than in the binary
 * payload — which also means a tool can look at what a save contains without knowing the layout.
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
 * One line for the library listing: "complete", or which stage opening this save will have to
 * recompute first (everything after it follows, by the same reuse-chain rule that makes opening
 * one at all safe rather than a refusal).
 *
 * Reads only [SaveHeader.sections] — names already sitting in the header — never the payload, so a
 * listing of any number of saves costs nothing more than it already did.
 */
val SaveHeader.openStatus: String
    get() {
        if (world == null) return "regenerates everything"
        val present = WorldSections.presentStages(sections.mapTo(HashSet()) { it.name })
        val firstMissing = GenerationStage.entries.firstOrNull { it !in present }
        return if (firstMissing == null) "complete" else "regenerates ${firstMissing.shortLabel}…"
    }

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
    const val FORMAT_VERSION = 8

    private val MAGIC = byteArrayOf('C'.code.toByte(), 'G'.code.toByte(), 'W'.code.toByte(), 'D'.code.toByte())

    /** Magic, version and header length, before the header itself starts. */
    const val PREFIX_BYTES = 12

    /** Where the format version sits in that prefix: straight after the magic. */
    const val VERSION_OFFSET = 4

    /** Where the header's own length sits: after the magic and the version. */
    const val HEADER_LENGTH_OFFSET = 8

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

    /** True when these bytes start with the container magic rather than being something else. */
    fun isContainer(bytes: ByteArray): Boolean =
        bytes.size >= PREFIX_BYTES && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    /**
     * The header alone — no payload touched, so this stays cheap on a file of any size.
     *
     * Refuses anything that is not this build's format, in both directions. See the note above on
     * why an older file is turned away rather than read with the keys it happens to share.
     */
    fun decodeHeader(bytes: ByteArray): SaveHeader {
        if (!isContainer(bytes)) {
            throw WorldFormatException(
                "this is not a Cartogenesis save, or it is one from before format $FORMAT_VERSION " +
                    "(plain JSON, with no world in it); this build reads format $FORMAT_VERSION only"
            )
        }
        val reader = ByteReader(bytes, position = MAGIC.size)
        val version = reader.getInt()
        if (version > FORMAT_VERSION) {
            throw WorldFormatException(
                "this save was written by a newer build (format $version, this one reads $FORMAT_VERSION)"
            )
        }
        if (version < FORMAT_VERSION) {
            throw WorldFormatException(
                "this save is format $version and this build reads $FORMAT_VERSION; the settings in " +
                    "an older header are written under names this build no longer knows, so it " +
                    "would open as a different world rather than as the one that was saved"
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
     * A header-only save comes back with a null world, which the caller regenerates from the
     * config. A container missing a *whole stage's* sections does not throw:
     * [WorldSections.rebuild] hands back the stages it could build and `null` for the rest, and
     * the reuse chain in [WorldGenerationEngine.generate] regenerates a missing stage and
     * everything downstream of it, the same way it already regenerates anything whose settings
     * changed. So this always hands the caller a complete [WorldMap] — never a partial one — with
     * such a save simply costing the recompute of whatever it could not carry forward, once, here,
     * rather than every place that ever asks for `save.world` having to know the difference. A
     * corrupt section (wrong length, bad magic) still throws — see [WorldSections.rebuild].
     */
    suspend fun decode(bytes: ByteArray, compressor: Compressor = NoCompression): WorldSave {
        val header = decodeHeader(bytes)
        if (header.world == null || header.sections.isEmpty()) {
            return WorldSave(header.document, null)
        }

        // Where the payload starts comes from the prefix rather than from re-encoding the
        // header: a second encoding need not be byte-identical to the one in the file.
        val reader = ByteReader(bytes, position = HEADER_LENGTH_OFFSET)
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

        val partial = WorldSections.rebuild(
            config = header.document.config,
            lists = header.world,
            labels = header.document.labels,
            sections = WorldSections.read(payload)
        )
        val world = WorldGenerationEngine.generate(header.document.config, previous = partial)
        return WorldSave(header.document, world)
    }

    /** Null rather than throwing, so one unreadable file cannot take the whole library down. */
    suspend fun decodeOrNull(bytes: ByteArray, compressor: Compressor = NoCompression): WorldSave? =
        runCatching { decode(bytes, compressor) }.getOrNull()
}
