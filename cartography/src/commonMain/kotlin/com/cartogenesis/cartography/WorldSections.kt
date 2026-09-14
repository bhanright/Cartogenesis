package com.cartogenesis.cartography

import com.cartogenesis.worldgen.GenerationStage
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.LoadedWorld
import com.cartogenesis.worldgen.model.MapLabel
import com.cartogenesis.worldgen.model.PartialWorld
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
enum class SectionType(val code: Int, val bytesPerElement: Int) {
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
    val bytes: ByteArray? = null
) {
    val count: Int get() = floats?.size ?: ints?.size ?: bytes!!.size

    /** Name, element type, element count and byte length, so the payload parses on its own. */
    val recordLength: Int get() =
        RECORD_PREFIX_BYTES + name.length + count * type.bytesPerElement

    fun floatsOrFail(): FloatArray = floats ?: throw WorldFormatException("$name is not float data")

    fun intsOrFail(): IntArray = ints ?: throw WorldFormatException("$name is not int data")

    fun bytesOrFail(): ByteArray = bytes ?: throw WorldFormatException("$name is not byte data")

    companion object {
        /**
         * The four int32 fields in front of a section's elements: the name's length, the element
         * type's code, the element count, and the byte length. The name's own characters are
         * counted separately, since they are one byte each.
         */
        const val RECORD_PREFIX_BYTES = 4 * 4
    }
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
        Section(
            "terrain.normals.gradientX", SectionType.F32,
            floats = world.terrain.normals.gradientX.data
        ),
        Section(
            "terrain.normals.gradientY", SectionType.F32,
            floats = world.terrain.normals.gradientY.data
        ),
        Section("terrain.height", SectionType.F32, floats = world.terrain.height.data),
        Section("plates.plateId", SectionType.I32, ints = world.plates.plateId),
        Section("plates.boundaryDistance", SectionType.F32, floats = world.plates.boundaryDistance.data),
        Section("plates.nearestBoundaryType", SectionType.I32, ints = world.plates.nearestBoundaryType),
        Section(
            "plates.nearestBoundaryClass", SectionType.I32,
            ints = world.plates.nearestBoundaryClass
        ),
        Section("plates.height", SectionType.F32, floats = world.plates.height.data),
        // Which crust each cell is made of, and how fast it is still rising. Neither can be
        // recovered from the height field — the first is what isostasy turned *into* that field
        // and the second never appears in it at all — and erosion reads the second every round, so
        // both are written like any other per-cell array.
        Section("plates.continentalShare", SectionType.F32, floats = world.plates.continentalShare.data),
        Section("plates.seafloorAgeMyr", SectionType.F32, floats = world.plates.seafloorAgeMyr.data),
        Section(
            "plates.upliftRateMmPerYear", SectionType.F32,
            floats = world.plates.upliftRateMmPerYear.data
        ),
        // How long ago each cell's crust was last built. Not derivable from anything else in the
        // file — it is the record of epochs that left no other trace — and erosion reads it, so it
        // is written like any other per-cell array rather than recomputed on open.
        Section("plates.crustAge", SectionType.F32, floats = world.plates.crustAge.data),
        Section("erosion.height", SectionType.F32, floats = world.erosion.height.data),
        Section("sea.isLand", SectionType.U8, bytes = ByteArray(world.sea.isLand.size) {
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
        Section(
            "climate.precipitationMm", SectionType.F32,
            floats = world.climate.precipitationMm.data
        ),
        Section("climate.windDirection", SectionType.I32, ints = world.climate.windDirection),
        // A byte per cell for each half of the year: what that season's sea surface froze, which
        // the moisture march reads as a lid and the biome reads as pack ice. Saved rather than
        // recomputed on open because it is a per-cell fact of the finished climate, as the biome is.
        Section(
            "climate.summerSeaIce", SectionType.U8,
            bytes = ByteArray(world.climate.summerSeaIce.size) {
                if (world.climate.summerSeaIce[it]) 1 else 0
            }
        ),
        Section(
            "climate.winterSeaIce", SectionType.U8,
            bytes = ByteArray(world.climate.winterSeaIce.size) {
                if (world.climate.winterSeaIce[it]) 1 else 0
            }
        ),
        Section(
            "climate.windMeridional", SectionType.F32,
            floats = world.climate.windMeridional.data
        ),
        Section("climate.biome", SectionType.U8, bytes = ByteArray(world.climate.biome.size) {
            world.climate.biome[it].ordinal.toByte()
        }),
        Section("rivers.filledElevation", SectionType.F32, floats = world.rivers.filledElevation.data),
        Section("rivers.flowAccumulation", SectionType.F32, floats = world.rivers.flowAccumulation.data),
        Section("rivers.flowTarget", SectionType.I32, ints = world.rivers.flowTarget),
        Section("rivers.lakeId", SectionType.I32, ints = world.rivers.lakes.lakeId),
        // A byte per cell rather than a list of indices, because a playa is a per-cell fact
        // exactly as a lake is, and salt flats will be drawn the same way lake ids are.
        Section("rivers.playa", SectionType.U8, bytes = ByteArray(world.rivers.lakes.playa.size) {
            if (world.rivers.lakes.playa[it]) 1 else 0
        }),
        Section("nations.nationId", SectionType.I32, ints = world.nations.nationId),
        Section("nations.habitability", SectionType.F32, floats = world.nations.habitability.data),
        Section("cultures.cultureId", SectionType.I32, ints = world.cultures.cultureId)
    )

    /**
     * Which sections make up each stage's result, so a reader can tell whether a *stage* survived
     * rather than merely a section: an old save is missing every section a later chunk added to a
     * stage's result, not just one of them, and reusing that stage from the sections it does have
     * would be a different (wrong) answer, not a partial one. [GenerationStage.LANDMARKS] has no
     * entry because it has no binary section at all — a landmark list lives entirely in
     * [WorldLists], so it is always present whenever the header carries a world.
     */
    private val SECTIONS_BY_STAGE: Map<GenerationStage, List<String>> = mapOf(
        GenerationStage.TERRAIN to listOf(
            "terrain.normals.gradientX", "terrain.normals.gradientY", "terrain.height"
        ),
        GenerationStage.TECTONICS to listOf(
            "plates.plateId", "plates.boundaryDistance", "plates.nearestBoundaryType",
            // Both of these were added to the stage after the others. A save written before one
            // of them has every other section of this stage and not that one, so the stage counts
            // as absent and is regenerated rather than half-built from what happens to be there.
            "plates.nearestBoundaryClass", "plates.height", "plates.crustAge",
            "plates.continentalShare", "plates.upliftRateMmPerYear", "plates.seafloorAgeMyr"
        ),
        GenerationStage.EROSION to listOf("erosion.height"),
        GenerationStage.SEA_LEVEL to listOf("sea.isLand", "sea.relativeElevation"),
        GenerationStage.OCEAN to listOf(
            "ocean.velocityX", "ocean.velocityY", "ocean.temperature", "ocean.anomaly"
        ),
        GenerationStage.CLIMATE to listOf(
            "climate.temperature", "climate.summerTemperature", "climate.winterTemperature",
            "climate.precipitation", "climate.summerPrecipitation", "climate.winterPrecipitation",
            "climate.precipitationMm",
            "climate.windDirection", "climate.windMeridional",
            "climate.summerSeaIce", "climate.winterSeaIce", "climate.biome"
        ),
        GenerationStage.RIVERS to listOf(
            "rivers.filledElevation", "rivers.flowAccumulation", "rivers.flowTarget",
            // Listed for the same reason the tectonic additions above are: a save written before
            // endorheic basins existed knows nothing of them, so its river stage counts as absent
            // rather than partially present, and is regenerated on open.
            "rivers.lakeId", "rivers.playa"
        ),
        GenerationStage.NATIONS to listOf("nations.nationId", "nations.habitability"),
        GenerationStage.CULTURES to listOf("cultures.cultureId")
    )

    /**
     * Which stages a reader holding exactly these section names can reuse without recomputing.
     *
     * Reads only the names — never a section's bytes — so this is cheap enough for a library
     * listing to call on every save's header, which is what lets the listing say "opens with
     * regeneration" without ever touching a payload.
     */
    fun presentStages(sectionNames: Set<String>): Set<GenerationStage> =
        SECTIONS_BY_STAGE.filterValues { required -> required.all { it in sectionNames } }.keys +
            GenerationStage.LANDMARKS

    /** The payload, plus the directory that describes it to a reader who has not expanded it. */
    fun write(sections: List<Section>): Pair<ByteArray, List<SectionInfo>> {
        val writer = ByteWriter(sections.sumOf { it.recordLength })
        val directory = ArrayList<SectionInfo>(sections.size)
        for (section in sections) {
            val offset = writer.position
            writer.putAscii(section.name)
            writer.putInt(section.type.code)
            writer.putInt(section.count)
            writer.putInt(section.count * section.type.bytesPerElement)
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
                    bytes = section.count * section.type.bytesPerElement,
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
            if (length != count * type.bytesPerElement) {
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
                SectionType.U8 -> Section(name, type, bytes = raw)
            }
            sections[name] = section
        }
        return sections
    }

    /**
     * Rebuilds as much of the world as the sections allow.
     *
     * A stage whose sections are all present comes back built from them; a stage missing even one
     * — an old save opened by a build that has since added a field to that stage's result — comes
     * back `null` rather than throwing. That is not "inventing an array the file never had": no
     * stage here reads another's *object*, only its own named sections and the lists, so building
     * the stages that did survive is sound regardless of which others did not. It is
     * [WorldGenerationEngine.generate], not this function, that turns "missing" into "regenerated,
     * and everything downstream of it too" — this function only has to say honestly what it found.
     *
     * A corrupt section is a different thing from a missing one and still throws: a length that
     * disagrees with its own element count ([read]) or a cell count that disagrees with the
     * config's resolution ([field]/[ints]/[bytes] below) is not a save from an older build, it is
     * bytes this build cannot trust at all.
     */
    fun rebuild(
        config: WorldGenConfig,
        lists: WorldLists,
        labels: List<MapLabel>,
        sections: Map<String, Section>
    ): PartialWorld {
        val cells = config.width * config.height
        val present = presentStages(sections.keys)

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

        val terrain = if (GenerationStage.TERRAIN in present) {
            TerrainResult(
                normals = NormalField(
                    field("terrain.normals.gradientX"), field("terrain.normals.gradientY")
                ),
                height = field("terrain.height")
            )
        } else null

        val plates = if (GenerationStage.TECTONICS in present) {
            PlateResult(
                plates = lists.plates,
                plateId = ints("plates.plateId"),
                boundaryDistance = field("plates.boundaryDistance"),
                nearestBoundaryType = ints("plates.nearestBoundaryType"),
                nearestBoundaryClass = ints("plates.nearestBoundaryClass"),
                height = field("plates.height"),
                continentalShare = field("plates.continentalShare"),
                seafloorAgeMyr = field("plates.seafloorAgeMyr"),
                seafloorHalfSpreadingRateKmPerMyr = lists.seafloorHalfSpreadingRateKmPerMyr,
                upliftRateMmPerYear = field("plates.upliftRateMmPerYear"),
                crustAge = field("plates.crustAge")
            )
        } else null

        val erosion = if (GenerationStage.EROSION in present) {
            ErosionResult(height = field("erosion.height"))
        } else null

        val sea = if (GenerationStage.SEA_LEVEL in present) {
            val isLandBytes = bytes("sea.isLand")
            SeaLevelResult(
                shorelineHeight = lists.shorelineHeight,
                isLand = BooleanArray(cells) { isLandBytes[it].toInt() != 0 },
                relativeElevation = field("sea.relativeElevation"),
                landCellCount = lists.landCellCount
            )
        } else null

        val ocean = if (GenerationStage.OCEAN in present) {
            OceanResult(
                velocityX = field("ocean.velocityX"),
                velocityY = field("ocean.velocityY"),
                temperature = field("ocean.temperature"),
                anomaly = field("ocean.anomaly")
            )
        } else null

        val climate = if (GenerationStage.CLIMATE in present) {
            val biomes = Biome.entries
            val biomeBytes = bytes("climate.biome")
            val summerSeaIceBytes = bytes("climate.summerSeaIce")
            val winterSeaIceBytes = bytes("climate.winterSeaIce")
            ClimateResult(
                temperature = field("climate.temperature"),
                summerTemperature = field("climate.summerTemperature"),
                winterTemperature = field("climate.winterTemperature"),
                precipitation = field("climate.precipitation"),
                summerPrecipitation = field("climate.summerPrecipitation"),
                winterPrecipitation = field("climate.winterPrecipitation"),
                precipitationMm = field("climate.precipitationMm"),
                windDirection = ints("climate.windDirection"),
                windMeridional = field("climate.windMeridional"),
                summerSeaIce = BooleanArray(cells) { summerSeaIceBytes[it].toInt() != 0 },
                winterSeaIce = BooleanArray(cells) { winterSeaIceBytes[it].toInt() != 0 },
                biome = Array(cells) { i ->
                    val ordinal = biomeBytes[i].toInt() and 0xFF
                    if (ordinal !in biomes.indices) {
                        throw WorldFormatException("unknown biome $ordinal at cell $i")
                    }
                    biomes[ordinal]
                }
            )
        } else null

        val rivers = if (GenerationStage.RIVERS in present) {
            RiverResult(
                filledElevation = field("rivers.filledElevation"),
                flowAccumulation = field("rivers.flowAccumulation"),
                flowTarget = ints("rivers.flowTarget"),
                rivers = lists.rivers,
                lakes = LakeResult(
                    lakeId = ints("rivers.lakeId"),
                    lakes = lists.lakes,
                    playa = bytes("rivers.playa").let { raw -> BooleanArray(raw.size) { raw[it].toInt() != 0 } },
                    cellsAcross = config.width
                )
            )
        } else null

        val nations = if (GenerationStage.NATIONS in present) {
            NationResult(
                nationId = ints("nations.nationId"),
                nations = lists.nations,
                habitability = field("nations.habitability")
            )
        } else null

        val cultures = if (GenerationStage.CULTURES in present) {
            CultureResult(cultureId = ints("cultures.cultureId"), cultures = lists.cultures)
        } else null

        // No section of its own — a landmark list lives entirely in WorldLists — so it is built
        // whenever the header carries a world at all.
        val landmarks = LandmarkResult(landmarks = lists.landmarks)

        return LoadedWorld(
            config = config,
            terrain = terrain,
            plates = plates,
            erosion = erosion,
            sea = sea,
            ocean = ocean,
            climate = climate,
            rivers = rivers,
            nations = nations,
            cultures = cultures,
            landmarks = landmarks,
            labels = labels
        )
    }
}
