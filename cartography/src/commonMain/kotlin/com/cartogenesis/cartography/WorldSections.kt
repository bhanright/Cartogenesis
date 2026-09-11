package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.MapLabel
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.ClimateResult
import com.cartogenesis.worldgen.pipeline.CultureResult
import com.cartogenesis.worldgen.pipeline.ErosionResult
import com.cartogenesis.worldgen.pipeline.LakeResult
import com.cartogenesis.worldgen.pipeline.LandmarkResult
import com.cartogenesis.worldgen.pipeline.NationResult
import com.cartogenesis.worldgen.pipeline.NormalField
import com.cartogenesis.worldgen.pipeline.OceanResult
import com.cartogenesis.worldgen.pipeline.PlateResult
import com.cartogenesis.worldgen.pipeline.RiverResult
import com.cartogenesis.worldgen.pipeline.SeaLevelResult
import com.cartogenesis.worldgen.pipeline.TerrainResult
import kotlinx.serialization.Serializable

/** Thrown when a container is not a container, or is one this build cannot make a world from. */
class WorldFormatException(message: String) : IllegalArgumentException(message)

/** How a section's elements are laid out. Little-endian throughout, on every platform. */
enum class SectionType(val code: Int, val width: Int) {
    /** Heights and every other field that has to come back bit for bit. */
    F32(1, 4),

    /** Cell ids: plates, realms, cultures, lakes, flow targets. */
    I32(2, 4),

    /** One byte per cell: land or sea, biome. */
    U8(3, 1);

    companion object {
        fun ofCode(code: Int): SectionType =
            entries.firstOrNull { it.code == code }
                ?: throw WorldFormatException("unknown section element type $code")
    }
}

/**
 * A section as the header advertises it, so a reader can see what a file holds — and how big it
 * is — without expanding a byte of the payload. That is what keeps a library listing cheap.
 */
@Serializable
data class SectionInfo(
    val name: String,
    val type: String,
    val count: Int,
    val bytes: Int,
    /** Where the section's own record starts within the expanded payload. */
    val offset: Int
)

/** One per-cell array, named, on its way to or from the payload. */
internal class Section(
    val name: String,
    val type: SectionType,
    val floats: FloatArray? = null,
    val ints: IntArray? = null,
    val raw: ByteArray? = null
) {
    val count: Int get() = floats?.size ?: ints?.size ?: raw!!.size

    /** Name, element type, element count and byte length, so the payload parses on its own. */
    val recordLength: Int get() = 16 + name.length + count * type.width

    fun floatsOrFail(): FloatArray = floats ?: throw WorldFormatException("$name is not float data")

    fun intsOrFail(): IntArray = ints ?: throw WorldFormatException("$name is not int data")

    fun bytesOrFail(): ByteArray = raw ?: throw WorldFormatException("$name is not byte data")
}

/** Little-endian writes over a fixed buffer, since every length is known before anything is written. */
internal class ByteWriter(size: Int) {
    val bytes = ByteArray(size)
    var position = 0
        private set

    fun putInt(value: Int) {
        bytes[position] = (value and 0xFF).toByte()
        bytes[position + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[position + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[position + 3] = ((value shr 24) and 0xFF).toByte()
        position += 4
    }

    fun putBytes(source: ByteArray) {
        source.copyInto(bytes, position)
        position += source.size
    }

    /** Names are ASCII by construction — they are the field paths written in this file. */
    fun putAscii(text: String) {
        putInt(text.length)
        for (c in text) bytes[position++] = c.code.toByte()
    }
}

internal class ByteReader(private val bytes: ByteArray, var position: Int = 0) {

    val remaining: Int get() = bytes.size - position

    fun getInt(): Int {
        if (remaining < 4) throw WorldFormatException("truncated: wanted 4 bytes, had $remaining")
        val value = (bytes[position].toInt() and 0xFF) or
            ((bytes[position + 1].toInt() and 0xFF) shl 8) or
            ((bytes[position + 2].toInt() and 0xFF) shl 16) or
            ((bytes[position + 3].toInt() and 0xFF) shl 24)
        position += 4
        return value
    }

    fun getBytes(length: Int): ByteArray {
        if (length < 0 || remaining < length) {
            throw WorldFormatException("truncated: wanted $length bytes, had $remaining")
        }
        val slice = bytes.copyOfRange(position, position + length)
        position += length
        return slice
    }

    fun getAscii(): String {
        val length = getInt()
        val raw = getBytes(length)
        return buildString(length) { for (b in raw) append((b.toInt() and 0xFF).toChar()) }
    }
}

/**
 * The per-cell arrays of a world, as bytes and back.
 *
 * Binary rather than JSON because the arrays are the file: a 1024x1024 world is fifteen float
 * fields, seven id maps and two byte maps — ninety megabytes before compression, and JSON would
 * roughly triple that while also having to promise that a float survives a decimal round trip.
 * Raw little-endian bits promise it by construction.
 *
 * Every section carries its own name, element type, element count and byte length, so the payload
 * can be walked without the header; the header repeats the directory so a reader that only wants
 * the title and the date never touches the payload at all.
 */
internal object WorldSections {

    /**
     * Every array in a [WorldMap], in the order the pipeline produces them.
     *
     * Nothing here is derived from anything else: the point of the format is that opening a save
     * recomputes no stage, so a field that a stage merely *could* rebuild is still written.
     */
    fun of(world: WorldMap): List<Section> = listOf(
        Section("terrain.normals.gx", SectionType.F32, floats = world.terrain.normals.gx.data),
        Section("terrain.normals.gy", SectionType.F32, floats = world.terrain.normals.gy.data),
        Section("terrain.height", SectionType.F32, floats = world.terrain.height.data),
        Section("plates.plateId", SectionType.I32, ints = world.plates.plateId),
        Section("plates.boundaryDistance", SectionType.F32, floats = world.plates.boundaryDistance.data),
        Section("plates.nearestBoundaryType", SectionType.I32, ints = world.plates.nearestBoundaryType),
        Section("plates.height", SectionType.F32, floats = world.plates.height.data),
        Section("erosion.height", SectionType.F32, floats = world.erosion.height.data),
        Section("sea.isLand", SectionType.U8, raw = ByteArray(world.sea.isLand.size) {
            if (world.sea.isLand[it]) 1 else 0
        }),
        Section("sea.relativeElevation", SectionType.F32, floats = world.sea.relativeElevation.data),
        Section("ocean.velocityX", SectionType.F32, floats = world.ocean.velocityX.data),
        Section("ocean.velocityY", SectionType.F32, floats = world.ocean.velocityY.data),
        Section("ocean.temperature", SectionType.F32, floats = world.ocean.temperature.data),
        Section("ocean.anomaly", SectionType.F32, floats = world.ocean.anomaly.data),
        Section("climate.temperature", SectionType.F32, floats = world.climate.temperature.data),
        Section(
            "climate.summerTemperature", SectionType.F32,
            floats = world.climate.summerTemperature.data
        ),
        Section(
            "climate.winterTemperature", SectionType.F32,
            floats = world.climate.winterTemperature.data
        ),
        Section("climate.precipitation", SectionType.F32, floats = world.climate.precipitation.data),
        Section(
            "climate.summerPrecipitation", SectionType.F32,
            floats = world.climate.summerPrecipitation.data
        ),
        Section(
            "climate.winterPrecipitation", SectionType.F32,
            floats = world.climate.winterPrecipitation.data
        ),
        Section("climate.windDirection", SectionType.I32, ints = world.climate.windDirection),
        Section("climate.biome", SectionType.U8, raw = ByteArray(world.climate.biome.size) {
            world.climate.biome[it].ordinal.toByte()
        }),
        Section("rivers.filledElevation", SectionType.F32, floats = world.rivers.filledElevation.data),
        Section("rivers.flowAccumulation", SectionType.F32, floats = world.rivers.flowAccumulation.data),
        Section("rivers.flowTarget", SectionType.I32, ints = world.rivers.flowTarget),
        Section("rivers.lakeId", SectionType.I32, ints = world.rivers.lakes.lakeId),
        Section("nations.nationId", SectionType.I32, ints = world.nations.nationId),
        Section("nations.habitability", SectionType.F32, floats = world.nations.habitability.data),
        Section("cultures.cultureId", SectionType.I32, ints = world.cultures.cultureId)
    )

    /** The payload, plus the directory that describes it to a reader who has not expanded it. */
    fun write(sections: List<Section>): Pair<ByteArray, List<SectionInfo>> {
        val writer = ByteWriter(sections.sumOf { it.recordLength })
        val directory = ArrayList<SectionInfo>(sections.size)
        for (section in sections) {
            val offset = writer.position
            writer.putAscii(section.name)
            writer.putInt(section.type.code)
            writer.putInt(section.count)
            writer.putInt(section.count * section.type.width)
            when (section.type) {
                SectionType.F32 -> for (v in section.floatsOrFail()) writer.putInt(v.toRawBits())
                SectionType.I32 -> for (v in section.intsOrFail()) writer.putInt(v)
                SectionType.U8 -> writer.putBytes(section.bytesOrFail())
            }
            directory.add(
                SectionInfo(
                    name = section.name,
                    type = section.type.name,
                    count = section.count,
                    bytes = section.count * section.type.width,
                    offset = offset
                )
            )
        }
        return writer.bytes to directory
    }

    fun read(payload: ByteArray): Map<String, Section> {
        val reader = ByteReader(payload)
        val sections = LinkedHashMap<String, Section>()
        while (reader.remaining > 0) {
            val name = reader.getAscii()
            val type = SectionType.ofCode(reader.getInt())
            val count = reader.getInt()
            val length = reader.getInt()
            if (length != count * type.width) {
                throw WorldFormatException("section $name claims $length bytes for $count ${type.name}")
            }
            val raw = reader.getBytes(length)
            val section = when (type) {
                SectionType.F32 -> {
                    val values = ByteReader(raw)
                    Section(name, type, floats = FloatArray(count) { Float.fromBits(values.getInt()) })
                }
                SectionType.I32 -> {
                    val values = ByteReader(raw)
                    Section(name, type, ints = IntArray(count) { values.getInt() })
                }
                SectionType.U8 -> Section(name, type, raw = raw)
            }
            sections[name] = section
        }
        return sections
    }

    /**
     * Rebuilds the world the sections came from.
     *
     * A missing section throws rather than being quietly filled in: a save that has lost an array
     * is not a world with a gap in it, it is a file this build cannot open, and saying so is the
     * only way the round-trip guard can tell the difference.
     */
    fun rebuild(
        config: WorldGenConfig,
        lists: WorldLists,
        labels: List<MapLabel>,
        sections: Map<String, Section>
    ): WorldMap {
        val cells = config.width * config.height

        fun section(name: String): Section =
            sections[name] ?: throw WorldFormatException("save is missing the section '$name'")

        fun field(name: String): FloatField {
            val values = section(name).floatsOrFail()
            if (values.size != cells) {
                throw WorldFormatException("section '$name' has ${values.size} cells, expected $cells")
            }
            return FloatField(config.width, config.height, values)
        }

        fun ints(name: String): IntArray {
            val values = section(name).intsOrFail()
            if (values.size != cells) {
                throw WorldFormatException("section '$name' has ${values.size} cells, expected $cells")
            }
            return values
        }

        fun bytes(name: String): ByteArray {
            val values = section(name).bytesOrFail()
            if (values.size != cells) {
                throw WorldFormatException("section '$name' has ${values.size} cells, expected $cells")
            }
            return values
        }

        val isLandBytes = bytes("sea.isLand")
        val isLand = BooleanArray(cells) { isLandBytes[it].toInt() != 0 }
        val biomes = Biome.entries
        val biomeBytes = bytes("climate.biome")

        return WorldMap(
            config = config,
            terrain = TerrainResult(
                normals = NormalField(field("terrain.normals.gx"), field("terrain.normals.gy")),
                height = field("terrain.height")
            ),
            plates = PlateResult(
                plates = lists.plates,
                plateId = ints("plates.plateId"),
                boundaryDistance = field("plates.boundaryDistance"),
                nearestBoundaryType = ints("plates.nearestBoundaryType"),
                height = field("plates.height")
            ),
            erosion = ErosionResult(height = field("erosion.height")),
            sea = SeaLevelResult(
                threshold = lists.seaThreshold,
                isLand = isLand,
                relativeElevation = field("sea.relativeElevation"),
                landCellCount = lists.landCellCount
            ),
            ocean = OceanResult(
                velocityX = field("ocean.velocityX"),
                velocityY = field("ocean.velocityY"),
                temperature = field("ocean.temperature"),
                anomaly = field("ocean.anomaly")
            ),
            climate = ClimateResult(
                temperature = field("climate.temperature"),
                summerTemperature = field("climate.summerTemperature"),
                winterTemperature = field("climate.winterTemperature"),
                precipitation = field("climate.precipitation"),
                summerPrecipitation = field("climate.summerPrecipitation"),
                winterPrecipitation = field("climate.winterPrecipitation"),
                windDirection = ints("climate.windDirection"),
                biome = Array(cells) { i ->
                    val ordinal = biomeBytes[i].toInt() and 0xFF
                    if (ordinal !in biomes.indices) {
                        throw WorldFormatException("unknown biome $ordinal at cell $i")
                    }
                    biomes[ordinal]
                }
            ),
            rivers = RiverResult(
                filledElevation = field("rivers.filledElevation"),
                flowAccumulation = field("rivers.flowAccumulation"),
                flowTarget = ints("rivers.flowTarget"),
                rivers = lists.rivers,
                lakes = LakeResult(lakeId = ints("rivers.lakeId"), lakes = lists.lakes)
            ),
            nations = NationResult(
                nationId = ints("nations.nationId"),
                nations = lists.nations,
                habitability = field("nations.habitability")
            ),
            cultures = CultureResult(
                cultureId = ints("cultures.cultureId"),
                cultures = lists.cultures
            ),
            landmarks = LandmarkResult(landmarks = lists.landmarks),
            labels = labels
        )
    }
}
