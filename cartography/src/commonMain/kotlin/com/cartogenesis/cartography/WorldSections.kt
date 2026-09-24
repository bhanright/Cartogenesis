package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.MapLabel
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.BoundaryClass
import com.cartogenesis.worldgen.pipeline.BoundaryType
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
import com.cartogenesis.worldgen.pipeline.VegetationDensity
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable

/** How a section's elements are laid out. Little-endian throughout, on every platform. */
enum class SectionType(val code: Int, val bytesPerElement: Int) {
    /** Heights and every other field that has to come back bit for bit. */
    F32(1, 4),

    /** Cell ids: plates, realms, cultures, lakes, flow targets. */
    I32(2, 4),

    /** One byte per cell: land or sea, biome. */
    U8(3, 1),

    /** Text: the world's lists, as JSON. One element is one byte of UTF-8. */
    UTF8(4, 1);

    companion object {
        fun ofCode(code: Int): SectionType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * A section as the header advertises it, so a reader can see what a file holds — and how big it
 * is — without expanding a byte of the payload, and can hold the payload to it as it is read.
 *
 * [bytes] and [offset] are in bytes of the expanded payload, and are `Long` because a 4096 world's
 * payload is 2.45 GB, past what an `Int` counts.
 */
@Serializable
data class SectionInfo(
    val name: String,
    val type: String,
    /** Elements: cells for a per-cell array, bytes of UTF-8 for the lists. */
    val count: Int,
    /** The elements' own bytes, not counting the record's name and lengths in front of them. */
    val bytes: Long,
    /** Where the section's record starts within the expanded payload. */
    val offset: Long
)

/**
 * A run of bytes a section is written from, a stretch at a time, so that a flag array or the
 * biome map is never copied whole into a byte array just to be written.
 */
internal fun interface ByteElements {
    fun copyInto(target: ByteArray, targetOffset: Int, from: Int, count: Int)
}

/** What a byte section's values mean, and so which values a reader accepts. */
internal enum class ByteMeaning(val valuesBelow: Int) {
    /** Zero or one. Any other byte is damage, not "true". */
    FLAG(2),

    /** A [VegetationDensity.Permafrost] ordinal. */
    PERMAFROST(VegetationDensity.Permafrost.entries.size),

    /** A [Biome] ordinal. */
    BIOME(Biome.entries.size)
}

/**
 * What a reader needs from the world's lists to check a section's values: how many cells there
 * are, and how many of each thing a per-cell id can name.
 */
internal class IdBounds(val cells: Int, val lists: WorldLists)

/** One per-cell array of a [WorldMap]: its wire name, its type, and how it is read and checked. */
internal sealed class SectionSpec(val name: String, val type: SectionType)

/** Every float is written by its raw bits and must come back finite: no field holds a NaN. */
internal class FloatSection(name: String, val of: (WorldMap) -> FloatArray) :
    SectionSpec(name, SectionType.F32)

/** Ids, each of which must lie in [allowed]: a lake id must name a lake the lists carry. */
internal class IntSection(
    name: String,
    val allowed: (IdBounds) -> IntRange,
    val of: (WorldMap) -> IntArray
) : SectionSpec(name, SectionType.I32)

internal class ByteSection(
    name: String,
    val meaning: ByteMeaning,
    val of: (WorldMap) -> ByteElements
) : SectionSpec(name, SectionType.U8)

/**
 * The per-cell arrays of a world, as a payload of chunks and back.
 *
 * Binary rather than JSON because the arrays are the file: 146 bytes a cell, which is 153 MB at
 * 1024 before compression, and JSON would roughly triple that while also having to promise that a
 * float survives a decimal round trip. Raw little-endian bits promise it by construction.
 *
 * The payload is a run of records — the world's lists as JSON first, then one record per array in
 * [SECTIONS] order — cut into chunks of [WorldCodec.CHUNK_BYTES], each compressed on its own and
 * carried in a frame with its lengths and a checksum. Chunks are what keep a save's memory bounded:
 * the writer holds one chunk and the reader one chunk and the array it is filling, never the
 * payload. Every record carries its own name, type, count and length, and the reader holds each to
 * the header's directory, which it has already held to this build's own list: a section renamed,
 * dropped, resized or reordered is damage, and is refused rather than regenerated.
 */
internal object WorldSections {

    /** The lists' record, which comes first so the arrays' ids can be checked against them. */
    const val LISTS = "lists"

    /**
     * The four fields in front of a record's elements besides its name: the name's length, the
     * element type's code and the element count as int32, and the byte length as int64.
     */
    const val RECORD_PREFIX_BYTES = 4 + 4 + 4 + 8

    /** Every array in a [WorldMap], in the order the pipeline produces them. */
    val SECTIONS: List<SectionSpec> = listOf(
        FloatSection("terrain.normals.gradientX") { it.terrain.normals.gradientX.data },
        FloatSection("terrain.normals.gradientY") { it.terrain.normals.gradientY.data },
        FloatSection("terrain.height") { it.terrain.height.data },
        IntSection("plates.plateId", { 0 until it.lists.plates.size }) { it.plates.plateId },
        FloatSection("plates.boundaryDistance") { it.plates.boundaryDistance.data },
        // -1 where a cell has no boundary to be nearest to, which a one-plate partition has.
        IntSection("plates.nearestBoundaryType", { -1 until BoundaryType.entries.size }) {
            it.plates.nearestBoundaryType
        },
        IntSection("plates.nearestBoundaryClass", { -1 until BoundaryClass.entries.size }) {
            it.plates.nearestBoundaryClass
        },
        FloatSection("plates.height") { it.plates.height.data },
        // Which crust each cell is made of, and how fast it is still rising. Neither can be
        // recovered from the height field — the first is what isostasy turned *into* that field
        // and the second never appears in it at all — and erosion reads the second every round.
        FloatSection("plates.continentalShare") { it.plates.continentalShare.data },
        FloatSection("plates.seafloorAgeMyr") { it.plates.seafloorAgeMyr.data },
        FloatSection("plates.upliftRateMmPerYear") { it.plates.upliftRateMmPerYear.data },
        // How long ago each cell's crust was last built: the record of epochs that left no other
        // trace, and erosion reads it.
        FloatSection("plates.crustAge") { it.plates.crustAge.data },
        FloatSection("erosion.height") { it.erosion.height.data },
        ByteSection("sea.isLand", ByteMeaning.FLAG) { flagsOf(it.sea.isLand) },
        FloatSection("sea.relativeElevation") { it.sea.relativeElevation.data },
        FloatSection("ocean.velocityX") { it.ocean.velocityX.data },
        FloatSection("ocean.velocityY") { it.ocean.velocityY.data },
        FloatSection("ocean.temperature") { it.ocean.temperature.data },
        FloatSection("ocean.anomaly") { it.ocean.anomaly.data },
        FloatSection("climate.temperature") { it.climate.temperature.data },
        FloatSection("climate.summerTemperature") { it.climate.summerTemperature.data },
        FloatSection("climate.winterTemperature") { it.climate.winterTemperature.data },
        FloatSection("climate.precipitation") { it.climate.precipitation.data },
        FloatSection("climate.summerPrecipitation") { it.climate.summerPrecipitation.data },
        FloatSection("climate.winterPrecipitation") { it.climate.winterPrecipitation.data },
        FloatSection("climate.precipitationMm") { it.climate.precipitationMm.data },
        // +1 blows east, -1 west, 0 where the belts meet.
        IntSection("climate.windDirection", { -1..1 }) { it.climate.windDirection },
        // What each season's sea surface froze, which the moisture march reads as a lid and the
        // biome reads as pack ice: a per-cell fact of the finished climate, as the biome is.
        ByteSection("climate.summerSeaIce", ByteMeaning.FLAG) { flagsOf(it.climate.summerSeaIce) },
        ByteSection("climate.winterSeaIce", ByteMeaning.FLAG) { flagsOf(it.climate.winterSeaIce) },
        FloatSection("climate.windMeridional") { it.climate.windMeridional.data },
        FloatSection("climate.vegetationDensity") { it.climate.vegetationDensity.data },
        ByteSection("climate.permafrost", ByteMeaning.PERMAFROST) { bytesOf(it.climate.permafrost) },
        ByteSection("climate.biome", ByteMeaning.BIOME) { biomesOf(it.climate.biome) },
        FloatSection("rivers.filledElevation") { it.rivers.filledElevation.data },
        FloatSection("rivers.flowAccumulation") { it.rivers.flowAccumulation.data },
        // -1 where the water leaves the world, which only the polar rows do.
        IntSection("rivers.flowTarget", { -1 until it.cells }) { it.rivers.flowTarget },
        IntSection("rivers.lakeId", { LakeResult.NO_LAKE until it.lists.lakes.size }) {
            it.rivers.lakes.lakeId
        },
        // A playa is a per-cell fact exactly as a lake is, and salt flats will be drawn the same
        // way lake ids are.
        ByteSection("rivers.playa", ByteMeaning.FLAG) { flagsOf(it.rivers.lakes.playa) },
        IntSection("nations.nationId", { NationResult.UNCLAIMED until it.lists.nations.size }) {
            it.nations.nationId
        },
        FloatSection("nations.habitability") { it.nations.habitability.data },
        IntSection("cultures.cultureId", { CultureResult.UNSETTLED until it.lists.cultures.size }) {
            it.cultures.cultureId
        }
    )

    private fun bytesOf(values: ByteArray) = ByteElements { target, targetOffset, from, count ->
        values.copyInto(target, targetOffset, from, from + count)
    }

    private fun flagsOf(values: BooleanArray) = ByteElements { target, targetOffset, from, count ->
        for (step in 0 until count) target[targetOffset + step] = if (values[from + step]) 1 else 0
    }

    private fun biomesOf(values: Array<Biome>) = ByteElements { target, targetOffset, from, count ->
        for (step in 0 until count) target[targetOffset + step] = values[from + step].ordinal.toByte()
    }

    /**
     * The directory a save of [cells] cells with [listsBytes] bytes of lists has, entry for entry:
     * what the writer produces and what a reader holds a file's own directory to.
     */
    fun directory(cells: Int, listsBytes: Int): List<SectionInfo> {
        var offset = 0L
        fun entry(name: String, type: SectionType, count: Int): SectionInfo {
            val bytes = count.toLong() * type.bytesPerElement
            val info = SectionInfo(name, type.name, count, bytes, offset)
            offset += RECORD_PREFIX_BYTES + name.length + bytes
            return info
        }
        return listOf(entry(LISTS, SectionType.UTF8, listsBytes)) +
            SECTIONS.map { entry(it.name, it.type, cells) }
    }

    /** The expanded payload's whole length, from its directory. */
    fun payloadBytes(directory: List<SectionInfo>): Long =
        directory.sumOf { RECORD_PREFIX_BYTES + it.name.length + it.bytes }

    /** Writes the lists' JSON and every array of [world], in [SECTIONS] order. */
    suspend fun write(world: WorldMap, listsJson: ByteArray, writer: PayloadWriter) {
        val cells = world.width * world.height
        writer.record(LISTS, SectionType.UTF8, listsJson.size)
        writer.bytes(listsJson, 0, listsJson.size)
        for (spec in SECTIONS) {
            writer.record(spec.name, spec.type, cells)
            when (spec) {
                is FloatSection -> writer.floats(spec.of(world).also { requireCells(spec, it.size, cells) })
                is IntSection -> writer.ints(spec.of(world).also { requireCells(spec, it.size, cells) })
                is ByteSection -> writer.elements(spec.of(world), cells)
            }
        }
    }

    private fun requireCells(spec: SectionSpec, size: Int, cells: Int) =
        require(size == cells) { "the world's ${spec.name} holds $size cells where its grid has $cells" }

    /**
     * Reads the lists and every array back, checking each record against [directory] and each
     * value against what it may hold, and builds the world from them. Everything is present or the
     * file is refused: a save carries its whole world or it is damaged.
     */
    suspend fun read(
        reader: PayloadReader,
        config: WorldGenConfig,
        directory: List<SectionInfo>,
        labels: List<MapLabel>,
        decodeLists: (ByteArray) -> WorldLists
    ): WorldMap {
        val cells = config.width * config.height
        val listsEntry = directory.first()
        reader.expectRecord(listsEntry, 0)
        val listsBytes = ByteArray(listsEntry.count)
        reader.bytes(listsBytes)
        val lists = decodeLists(listsBytes)
        lists.checkAgainst(config.width, config.height)
        val bounds = IdBounds(cells, lists)

        val arrays = HashMap<String, Any>(SECTIONS.size * 2)
        SECTIONS.forEachIndexed { index, spec ->
            reader.expectRecord(directory[index + 1], index + 1)
            arrays[spec.name] = when (spec) {
                is FloatSection -> FloatArray(cells).also { reader.floats(it, spec.name) }
                is IntSection -> IntArray(cells).also { reader.ints(it, spec.allowed(bounds), spec.name) }
                is ByteSection -> {
                    val raw = ByteArray(cells).also { reader.elements(it, spec.meaning, spec.name) }
                    when (spec.meaning) {
                        ByteMeaning.FLAG -> BooleanArray(cells) { raw[it].toInt() != 0 }
                        ByteMeaning.PERMAFROST -> raw
                        ByteMeaning.BIOME -> Array(cells) { Biome.entries[raw[it].toInt()] }
                    }
                }
            }
        }
        return assemble(config, lists, labels, arrays)
    }

    @Suppress("UNCHECKED_CAST")
    private fun assemble(
        config: WorldGenConfig,
        lists: WorldLists,
        labels: List<MapLabel>,
        arrays: Map<String, Any>
    ): WorldMap {
        fun field(name: String) = FloatField(config.width, config.height, arrays.getValue(name) as FloatArray)
        fun ints(name: String) = arrays.getValue(name) as IntArray
        fun flags(name: String) = arrays.getValue(name) as BooleanArray

        return WorldMap(
            config = config,
            terrain = TerrainResult(
                normals = NormalField(
                    field("terrain.normals.gradientX"), field("terrain.normals.gradientY")
                ),
                height = field("terrain.height")
            ),
            plates = PlateResult(
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
            ),
            erosion = ErosionResult(height = field("erosion.height")),
            sea = SeaLevelResult(
                shorelineHeight = lists.shorelineHeight,
                isLand = flags("sea.isLand"),
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
                precipitationMm = field("climate.precipitationMm"),
                windDirection = ints("climate.windDirection"),
                windMeridional = field("climate.windMeridional"),
                vegetationDensity = field("climate.vegetationDensity"),
                permafrost = arrays.getValue("climate.permafrost") as ByteArray,
                summerSeaIce = flags("climate.summerSeaIce"),
                winterSeaIce = flags("climate.winterSeaIce"),
                biome = arrays.getValue("climate.biome") as Array<Biome>
            ),
            rivers = RiverResult(
                filledElevation = field("rivers.filledElevation"),
                flowAccumulation = field("rivers.flowAccumulation"),
                flowTarget = ints("rivers.flowTarget"),
                rivers = lists.rivers,
                lakes = LakeResult(
                    lakeId = ints("rivers.lakeId"),
                    lakes = lists.lakes,
                    playa = flags("rivers.playa"),
                    cellsAcross = config.width
                )
            ),
            nations = NationResult(
                nationId = ints("nations.nationId"),
                nations = lists.nations,
                habitability = field("nations.habitability")
            ),
            cultures = CultureResult(cultureId = ints("cultures.cultureId"), cultures = lists.cultures),
            // No section of its own: a landmark list lives entirely in the lists.
            landmarks = LandmarkResult(landmarks = lists.landmarks),
            labels = labels
        )
    }
}

/**
 * Every reference the lists make, checked against the grid and against each other before a
 * single array is read: a river's cells, a realm's capital and neighbours, a landmark's cell, and
 * that each list's ids are its own indices, which is what lets a per-cell id index one.
 *
 * A lake's outlet and a people's hearth may be -1: the stages that set them start from -1 and are
 * not obliged to replace it, and nothing indexes by either without looking first.
 */
internal fun WorldLists.checkAgainst(width: Int, height: Int) {
    val cells = width * height
    fun damaged(detail: String): Nothing = throw WorldFormatException(SaveProblem.DAMAGED, detail)
    fun checkIds(list: String, ids: List<Int>) {
        ids.forEachIndexed { index, id -> if (id != index) damaged("$list entry $index calls itself $id") }
    }
    fun checkCell(what: String, cell: Int, noneAllowed: Boolean = false) {
        if (cell in 0 until cells || (noneAllowed && cell == -1)) return
        damaged("$what is cell $cell, and the grid has $cells")
    }

    if (!shorelineHeight.isFinite()) damaged("the shoreline height is $shorelineHeight")
    if (!seafloorHalfSpreadingRateKmPerMyr.isFinite()) {
        damaged("the spreading rate is $seafloorHalfSpreadingRateKmPerMyr")
    }
    if (landCellCount !in 0..cells) damaged("$landCellCount land cells on a grid of $cells")

    checkIds("plate", plates.map { it.id })
    plates.forEach { plate ->
        if (plate.seedX !in 0 until width || plate.seedY !in 0 until height) {
            damaged("plate ${plate.id}'s seed is at (${plate.seedX}, ${plate.seedY}), off the grid")
        }
        if (!plate.driftX.isFinite() || !plate.driftY.isFinite()) damaged("plate ${plate.id}'s drift is not a number")
    }
    rivers.forEachIndexed { index, river ->
        river.cells.forEach { checkCell("river $index's course", it) }
        if (river.widthRatio.size != river.cells.size) {
            damaged("river $index has ${river.cells.size} cells and ${river.widthRatio.size} widths")
        }
        if (river.widthRatio.any { !it.isFinite() }) damaged("river $index has a width that is not a number")
    }
    checkIds("lake", lakes.map { it.id })
    lakes.forEach { lake ->
        checkCell("lake ${lake.id}'s outlet", lake.outletCell, noneAllowed = true)
        if (!lake.surfaceElevation.isFinite() || !lake.spillElevation.isFinite()) {
            damaged("lake ${lake.id}'s surface is not a number")
        }
    }
    checkIds("realm", nations.map { it.id })
    nations.forEach { nation ->
        checkCell("realm ${nation.id}'s origin", nation.originCell)
        checkCell("realm ${nation.id}'s capital", nation.capitalCell)
        nation.neighbours.forEach {
            if (it !in nations.indices) damaged("realm ${nation.id} borders realm $it, and there are ${nations.size}")
        }
    }
    checkIds("people", cultures.map { it.id })
    cultures.forEach { checkCell("people ${it.id}'s hearth", it.hearthCell, noneAllowed = true) }
    checkIds("landmark", landmarks.map { it.id })
    landmarks.forEach { checkCell("landmark ${it.id}", it.cell) }
}

/**
 * A chunk's checksum: the CRC-32 of its raw bytes, run on from the header's own checksum and the
 * chunk's place in the payload.
 *
 * Bound to both because each is a way a whole-looking file can be the wrong one: a header from
 * one save put in front of another's chunks, or two chunks of one save swapped, would pass a
 * checksum of the chunk's bytes alone. Run on from the header's, every chunk of the spliced file
 * fails; run on from its index, a chunk out of place does.
 */
internal fun chunkChecksum(headerChecksum: Int, index: Int, raw: ByteArray, from: Int, length: Int): Int {
    val place = ByteArray(Int.SIZE_BYTES).also { putInt(it, 0, index) }
    return Crc32.of(raw, from, length, continuing = Crc32.of(place, continuing = headerChecksum))
}

/**
 * The expanded payload going out, a chunk at a time.
 *
 * Fills one chunk and hands it to [sink] as a frame — raw length, stored length and the chunk's
 * checksum ([chunkChecksum], bound to [headerChecksum]) as int32, then whether it is compressed,
 * then the stored bytes — compressing it first when [compressor] can and the result is smaller.
 * Ends with a frame of zeros.
 */
internal class PayloadWriter(
    private val sink: SaveSink,
    private val compressor: Compressor,
    private val headerChecksum: Int
) {

    /** How many chunks have gone out, which is the next one's place. */
    private var frames = 0

    private val chunk = ByteArray(WorldCodec.CHUNK_BYTES)
    private var filled = 0
    private val frameHeader = ByteArray(FRAME_HEADER_BYTES)

    /** Expanded bytes written so far, which the header's directory promised in advance. */
    var written: Long = 0L
        private set

    suspend fun record(name: String, type: SectionType, count: Int) {
        int(name.length)
        for (character in name) byte(character.code.toByte())
        int(type.code)
        int(count)
        long(count.toLong() * type.bytesPerElement)
    }

    suspend fun int(value: Int) {
        if (chunk.size - filled < Int.SIZE_BYTES) {
            for (shift in 0 until Int.SIZE_BITS step Byte.SIZE_BITS) byte((value ushr shift).toByte())
            return
        }
        putInt(chunk, filled, value)
        advance(Int.SIZE_BYTES)
    }

    suspend fun long(value: Long) {
        int(value.toInt())
        int((value ushr Int.SIZE_BITS).toInt())
    }

    suspend fun bytes(source: ByteArray, offset: Int, length: Int) {
        var from = offset
        val end = offset + length
        while (from < end) {
            if (filled == chunk.size) flush()
            val count = minOf(end - from, chunk.size - filled)
            source.copyInto(chunk, filled, from, from + count)
            from += count
            advance(count)
        }
    }

    suspend fun floats(values: FloatArray) {
        var index = 0
        while (index < values.size) {
            val room = (chunk.size - filled) / Float.SIZE_BYTES
            if (room == 0) {
                int(values[index++].toRawBits())
                continue
            }
            val count = minOf(room, values.size - index)
            var at = filled
            for (step in 0 until count) {
                putInt(chunk, at, values[index + step].toRawBits())
                at += Float.SIZE_BYTES
            }
            index += count
            advance(count * Float.SIZE_BYTES)
        }
    }

    suspend fun ints(values: IntArray) {
        var index = 0
        while (index < values.size) {
            val room = (chunk.size - filled) / Int.SIZE_BYTES
            if (room == 0) {
                int(values[index++])
                continue
            }
            val count = minOf(room, values.size - index)
            var at = filled
            for (step in 0 until count) {
                putInt(chunk, at, values[index + step])
                at += Int.SIZE_BYTES
            }
            index += count
            advance(count * Int.SIZE_BYTES)
        }
    }

    suspend fun elements(source: ByteElements, count: Int) {
        var from = 0
        while (from < count) {
            if (filled == chunk.size) flush()
            val run = minOf(count - from, chunk.size - filled)
            source.copyInto(chunk, filled, from, run)
            from += run
            advance(run)
        }
    }

    /** Sends the last, partly filled chunk and the frame of zeros that ends the payload. */
    suspend fun finish() {
        flush()
        frameHeader.fill(0)
        sink.write(frameHeader, 0, FRAME_HEADER_BYTES)
    }

    private suspend fun byte(value: Byte) {
        if (filled == chunk.size) flush()
        chunk[filled] = value
        advance(1)
    }

    private fun advance(count: Int) {
        filled += count
        written += count
    }

    private suspend fun flush() {
        if (filled == 0) return
        val raw = if (filled == chunk.size) chunk else chunk.copyOf(filled)
        val compressed = compressor.compress(raw)?.takeIf { it.size < filled }
        putInt(frameHeader, 0, filled)
        putInt(frameHeader, 4, compressed?.size ?: filled)
        putInt(frameHeader, 8, chunkChecksum(headerChecksum, frames, chunk, 0, filled))
        putInt(frameHeader, 12, if (compressed != null) METHOD_COMPRESSED else METHOD_STORED)
        sink.write(frameHeader, 0, FRAME_HEADER_BYTES)
        if (compressed != null) sink.write(compressed, 0, compressed.size) else sink.write(chunk, 0, filled)
        filled = 0
        frames++
    }

    companion object {
        /** Raw length, stored length, checksum and method, each an int32. */
        const val FRAME_HEADER_BYTES = 16
        const val METHOD_STORED = 0
        const val METHOD_COMPRESSED = 1
    }
}

/**
 * The expanded payload coming back, a chunk at a time, with every frame, record and value checked
 * before anything is built from it.
 *
 * [compression] is the method the header names for compressed frames; [compressor] is this
 * platform's, which has to be the same one to expand them.
 */
internal class PayloadReader(
    private val source: SaveSource,
    private val compression: String,
    private val compressor: Compressor,
    private val expectedBytes: Long,
    private val headerChecksum: Int
) {
    private var chunk = ByteArray(0)
    private var position = 0
    private var frames = 0
    private val frameHeader = ByteArray(PayloadWriter.FRAME_HEADER_BYTES)

    /** Expanded bytes delivered so far. */
    private var delivered = 0L

    private fun damaged(detail: String): Nothing = throw WorldFormatException(SaveProblem.DAMAGED, detail)

    private fun incomplete(detail: String): Nothing = throw WorldFormatException(SaveProblem.INCOMPLETE, detail)

    /** Reads a record's prefix and holds it to its directory [entry], the [index]th. */
    suspend fun expectRecord(entry: SectionInfo, index: Int) {
        val nameLength = int()
        if (nameLength != entry.name.length) {
            damaged("record $index's name is $nameLength letters long where the directory's '${entry.name}' is ${entry.name.length}")
        }
        val name = CharArray(nameLength) { (byte().toInt() and 0xFF).toChar() }.concatToString()
        if (name != entry.name) damaged("record $index is called '$name' where the directory says '${entry.name}'")
        val type = SectionType.ofCode(int())
        if (type?.name != entry.type) damaged("'$name' is stored as ${type?.name ?: "an unknown type"}, not ${entry.type}")
        val count = int()
        if (count != entry.count) damaged("'$name' holds $count elements where the directory says ${entry.count}")
        val length = long()
        if (length != entry.bytes) damaged("'$name' claims $length bytes where the directory says ${entry.bytes}")
    }

    suspend fun int(): Int {
        if (chunk.size - position < Int.SIZE_BYTES) {
            var value = 0
            for (shift in 0 until Int.SIZE_BITS step Byte.SIZE_BITS) value = value or ((byte().toInt() and 0xFF) shl shift)
            return value
        }
        val value = getInt(chunk, position)
        position += Int.SIZE_BYTES
        return value
    }

    suspend fun long(): Long {
        val low = int().toLong() and 0xFFFFFFFFL
        val high = int().toLong()
        return low or (high shl Int.SIZE_BITS)
    }

    suspend fun byte(): Byte {
        if (position == chunk.size) nextFrame()
        return chunk[position++]
    }

    suspend fun bytes(target: ByteArray) {
        var at = 0
        while (at < target.size) {
            if (position == chunk.size) nextFrame()
            val count = minOf(target.size - at, chunk.size - position)
            chunk.copyInto(target, at, position, position + count)
            position += count
            at += count
        }
    }

    suspend fun floats(target: FloatArray, name: String) {
        var index = 0
        while (index < target.size) {
            val available = (chunk.size - position) / Float.SIZE_BYTES
            if (available == 0) {
                target[index] = checkedFloat(Float.fromBits(int()), name, index)
                index++
                continue
            }
            val count = minOf(available, target.size - index)
            var at = position
            for (step in 0 until count) {
                target[index + step] = checkedFloat(Float.fromBits(getInt(chunk, at)), name, index + step)
                at += Float.SIZE_BYTES
            }
            position = at
            index += count
        }
    }

    private fun checkedFloat(value: Float, name: String, cell: Int): Float {
        if (!value.isFinite()) damaged("'$name' holds $value at cell $cell")
        return value
    }

    suspend fun ints(target: IntArray, allowed: IntRange, name: String) {
        var index = 0
        while (index < target.size) {
            val available = (chunk.size - position) / Int.SIZE_BYTES
            if (available == 0) {
                target[index] = checkedInt(int(), allowed, name, index)
                index++
                continue
            }
            val count = minOf(available, target.size - index)
            var at = position
            for (step in 0 until count) {
                target[index + step] = checkedInt(getInt(chunk, at), allowed, name, index + step)
                at += Int.SIZE_BYTES
            }
            position = at
            index += count
        }
    }

    private fun checkedInt(value: Int, allowed: IntRange, name: String, cell: Int): Int {
        if (value !in allowed) damaged("'$name' holds $value at cell $cell, outside ${allowed.first}..${allowed.last}")
        return value
    }

    suspend fun elements(target: ByteArray, meaning: ByteMeaning, name: String) {
        bytes(target)
        for (cell in target.indices) {
            val value = target[cell].toInt() and 0xFF
            if (value >= meaning.valuesBelow) {
                damaged("'$name' holds $value at cell $cell, where only 0..${meaning.valuesBelow - 1} mean anything")
            }
        }
    }

    /**
     * After the last record: nothing left over in the chunk, a frame of zeros, nothing after it,
     * and every byte the directory promised delivered.
     */
    suspend fun finish() {
        if (position != chunk.size) damaged("the last chunk runs ${chunk.size - position} bytes past the last section")
        if (source.readFully(frameHeader, 0, frameHeader.size) < frameHeader.size) {
            incomplete("it ends where the frame that closes the world should be")
        }
        if (frameHeader.any { it.toInt() != 0 }) damaged("the world runs on past where its directory ends")
        val trailing = ByteArray(1)
        if (source.read(trailing, 0, 1) >= 0) damaged("there are bytes after the end of the world")
        if (delivered != expectedBytes) damaged("the payload is $delivered bytes where the directory says $expectedBytes")
    }

    private suspend fun nextFrame() {
        val arrived = source.readFully(frameHeader, 0, frameHeader.size)
        if (arrived < frameHeader.size) {
            incomplete("it ends partway through its world, after $delivered of $expectedBytes bytes")
        }
        if (frameHeader.all { it.toInt() == 0 }) {
            incomplete("it holds zeros where its world should continue, after $delivered of $expectedBytes bytes")
        }
        val rawLength = getInt(frameHeader, 0)
        val storedLength = getInt(frameHeader, 4)
        val checksum = getInt(frameHeader, 8)
        val method = getInt(frameHeader, 12)
        val frame = frames
        if (rawLength !in 1..WorldCodec.CHUNK_BYTES) damaged("chunk $frame claims $rawLength bytes")
        if (delivered + rawLength > expectedBytes) damaged("chunk $frame runs past the $expectedBytes bytes the directory promises")
        val raw = when (method) {
            PayloadWriter.METHOD_STORED -> {
                if (storedLength != rawLength) damaged("chunk $frame is stored raw but claims $storedLength bytes for $rawLength")
                readStored(rawLength, frame)
            }
            PayloadWriter.METHOD_COMPRESSED -> {
                // The writer stores a chunk compressed only when that is smaller than the chunk,
                // so a compressed frame longer than its own expansion was not written by it.
                if (storedLength !in 1 until rawLength) {
                    damaged("chunk $frame claims $storedLength compressed bytes for $rawLength")
                }
                val stored = readStored(storedLength, frame)
                try {
                    expand(stored, rawLength, frame)
                } catch (refused: WorldFormatException) {
                    if (refused.problem == SaveProblem.DAMAGED && endsUnfilled(stored)) unfilled(frame)
                    throw refused
                }
            }
            else -> damaged("chunk $frame names an unknown method $method")
        }
        if (chunkChecksum(headerChecksum, frame, raw, 0, raw.size) != checksum) {
            if (endsUnfilled(raw)) unfilled(frame)
            damaged("chunk $frame fails its checksum")
        }
        chunk = raw
        position = 0
        delivered += rawLength
        frames++
    }

    /**
     * Whether a chunk that did not check out ends in zeros, which is how a file a sync client has
     * made room for and not finished filling looks: the right length, and nothing in its tail.
     */
    private fun endsUnfilled(bytes: ByteArray): Boolean =
        bytes.size >= UNFILLED_TAIL_BYTES &&
            (bytes.size - UNFILLED_TAIL_BYTES until bytes.size).all { bytes[it].toInt() == 0 }

    private fun unfilled(frame: Int): Nothing =
        incomplete("it holds zeros where its world should continue, in chunk $frame after $delivered of $expectedBytes bytes")

    private suspend fun readStored(length: Int, frame: Int): ByteArray {
        val stored = ByteArray(length)
        if (source.readFully(stored, 0, length) < length) {
            incomplete("it ends partway through chunk $frame, after $delivered of $expectedBytes bytes")
        }
        return stored
    }

    private suspend fun expand(stored: ByteArray, rawLength: Int, frame: Int): ByteArray {
        if (compression == NoCompression.name) {
            damaged("chunk $frame is compressed in a file whose header says it stores its chunks raw")
        }
        if (compression != compressor.name) {
            throw WorldFormatException(
                SaveProblem.CANNOT_EXPAND,
                "its chunks are compressed with '$compression' and this platform expands '${compressor.name}'"
            )
        }
        val expanded = try {
            compressor.decompress(stored, rawLength)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            damaged("chunk $frame does not expand: ${failure.message ?: failure::class.simpleName}")
        } ?: throw WorldFormatException(SaveProblem.CANNOT_EXPAND, "this platform cannot expand '$compression'")
        if (expanded.size != rawLength) {
            damaged("chunk $frame expands to ${if (expanded.size > rawLength) "more than" else expanded.size} where its frame says $rawLength bytes")
        }
        return expanded
    }
}

/**
 * How many zero bytes at the end of a chunk that fails its checks make it read as unfilled rather
 * than damaged: sixty-four. A chunk written by the codec ends in zeros only where its data does,
 * and then it checks out; one whose checks fail and whose last sixty-four bytes are zero is far
 * likelier to be a file cut off and padded than one damaged into sixteen zero floats in a row.
 */
private const val UNFILLED_TAIL_BYTES = 64

internal fun putInt(target: ByteArray, at: Int, value: Int) {
    target[at] = value.toByte()
    target[at + 1] = (value ushr 8).toByte()
    target[at + 2] = (value ushr 16).toByte()
    target[at + 3] = (value ushr 24).toByte()
}

internal fun getInt(source: ByteArray, at: Int): Int =
    (source[at].toInt() and 0xFF) or
        ((source[at + 1].toInt() and 0xFF) shl 8) or
        ((source[at + 2].toInt() and 0xFF) shl 16) or
        ((source[at + 3].toInt() and 0xFF) shl 24)
