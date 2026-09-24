package com.cartogenesis.cartography

import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.model.FloatField
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.ClimateResult
import com.cartogenesis.worldgen.pipeline.Culture
import com.cartogenesis.worldgen.pipeline.CultureResult
import com.cartogenesis.worldgen.pipeline.ErosionResult
import com.cartogenesis.worldgen.pipeline.Lake
import com.cartogenesis.worldgen.pipeline.LakeResult
import com.cartogenesis.worldgen.pipeline.Landmark
import com.cartogenesis.worldgen.pipeline.LandmarkKind
import com.cartogenesis.worldgen.pipeline.LandmarkResult
import com.cartogenesis.worldgen.pipeline.Nation
import com.cartogenesis.worldgen.pipeline.NationResult
import com.cartogenesis.worldgen.pipeline.NormalField
import com.cartogenesis.worldgen.pipeline.OceanResult
import com.cartogenesis.worldgen.pipeline.Plate
import com.cartogenesis.worldgen.pipeline.PlateResult
import com.cartogenesis.worldgen.pipeline.PlateType
import com.cartogenesis.worldgen.pipeline.River
import com.cartogenesis.worldgen.pipeline.RiverResult
import com.cartogenesis.worldgen.pipeline.SeaLevelResult
import com.cartogenesis.worldgen.pipeline.TerrainResult
import kotlinx.serialization.json.Json

/**
 * Worlds for the save's tests that cost nothing to make.
 *
 * Every array holds values of its own at every [SPACING]th cell and zero between, so a section read
 * into the wrong place or cut short is visible, and the zeros give a toy compressor something to
 * squeeze; every list has one entry, so each kind of reference has something to point at and
 * something to point past. None of it is a world the generator would make, which is the point: the
 * format has to carry any world whole, and a refusal has to be the file's fault.
 */
internal object SyntheticWorlds {

    /** Which cells of a field hold a value rather than zero. */
    private const val SPACING = 97

    /** One lake, one realm, one people, one landmark, one river, one plate. */
    fun of(config: WorldGenConfig = WorldGenConfig(seed = 5L, width = 64, height = 64)): WorldMap {
        val cells = config.width * config.height
        var ramp = 0
        fun field(): FloatField {
            val start = ++ramp
            return FloatField(
                config.width, config.height,
                FloatArray(cells) { if (it % SPACING == 0) (it + start) * 0.001f else 0f }
            )
        }
        val lakeId = IntArray(cells) { if (it < 4) 0 else LakeResult.NO_LAKE }
        return WorldMap(
            config = config,
            terrain = TerrainResult(NormalField(field(), field()), field()),
            plates = PlateResult(
                plates = listOf(Plate(0, 1, 1, 0.5f, -0.5f, PlateType.CONTINENTAL)),
                plateId = IntArray(cells),
                boundaryDistance = field(),
                nearestBoundaryType = IntArray(cells) { -1 },
                nearestBoundaryClass = IntArray(cells) { -1 },
                height = field(),
                seafloorAgeMyr = field(),
                seafloorHalfSpreadingRateKmPerMyr = 25.0,
                continentalShare = field(),
                upliftRateMmPerYear = field(),
                crustAge = field()
            ),
            erosion = ErosionResult(field()),
            sea = SeaLevelResult(
                shorelineHeight = 0.4f,
                isLand = BooleanArray(cells) { it % 3 == 0 },
                relativeElevation = field(),
                landCellCount = (cells + 2) / 3
            ),
            ocean = OceanResult(field(), field(), field(), field()),
            climate = ClimateResult(
                temperature = field(),
                summerTemperature = field(),
                winterTemperature = field(),
                precipitation = field(),
                summerPrecipitation = field(),
                winterPrecipitation = field(),
                precipitationMm = field(),
                windDirection = IntArray(cells) { it % 3 - 1 },
                windMeridional = field(),
                summerSeaIce = BooleanArray(cells) { it % 5 == 0 },
                winterSeaIce = BooleanArray(cells) { it % 7 == 0 },
                biome = Array(cells) { Biome.entries[it % Biome.entries.size] },
                vegetationDensity = field(),
                permafrost = ByteArray(cells) { (it % 3).toByte() }
            ),
            rivers = RiverResult(
                filledElevation = field(),
                flowAccumulation = field(),
                flowTarget = IntArray(cells) { if (it == 0) -1 else it - 1 },
                rivers = listOf(River(intArrayOf(10, 11, 12), floatArrayOf(0.2f, 0.6f, 1f))),
                lakes = LakeResult(
                    lakeId = lakeId,
                    lakes = listOf(Lake(0, 4, 0.3f, 5, spillElevation = 0.3f)),
                    playa = BooleanArray(cells) { it == 20 },
                    cellsAcross = config.width
                )
            ),
            nations = NationResult(
                nationId = IntArray(cells) { if (it % 2 == 0) 0 else NationResult.UNCLAIMED },
                nations = listOf(
                    Nation(
                        id = 0, name = "Realm", capitalName = "Capital", originCell = 30,
                        capitalCell = 31, cultureSeed = 7L, cellCount = cells / 2, population = 1000L,
                        biomeShare = listOf(Biome.GRASSLAND to 1f), coastalCells = 3, riverCells = 2,
                        neighbours = emptySet(), heartlandBiome = Biome.GRASSLAND, government = "Kingdom",
                        exports = listOf("salt"), imports = listOf("iron"), lore = "Old."
                    )
                ),
                habitability = field()
            ),
            cultures = CultureResult(
                cultureId = IntArray(cells) { if (it % 2 == 0) 0 else CultureResult.UNSETTLED },
                cultures = listOf(Culture(0, "People", 30, cells / 2, Biome.GRASSLAND, 11L))
            ),
            landmarks = LandmarkResult(
                listOf(Landmark(0, 40, LandmarkKind.RUIN, "Ruin", "old stones", inWilderness = true))
            )
        )
    }
}

/** A generated world, made once per test process and shared, for the cases that need real data. */
internal object GeneratedWorlds {
    private var world256: WorldMap? = null

    /** Small enough to run on every target, large enough to have rivers, realms and peoples. */
    suspend fun at256(): WorldMap =
        world256 ?: WorldGenerationEngine.generate(WorldGenConfig(seed = 99L, width = 256, height = 256))
            .also { world256 = it }
}

/**
 * A container taken apart into its header and its expanded payload, so a test can damage exactly
 * one thing and put the rest back with a fresh header checksum, fresh frames and fresh chunk
 * checksums bound to that header — which is what makes the reader meet the damage itself rather
 * than a checksum that no longer adds up.
 *
 * Only for containers whose chunks are all stored raw, which is what [NoCompression] writes.
 */
internal class TakenApart(val header: SaveHeader, val payload: ByteArray) {

    /** Where the elements of section [name] begin in [payload]. */
    fun elementsOf(name: String): Int {
        val entry = header.sections.single { it.name == name }
        return (entry.offset + WorldSections.RECORD_PREFIX_BYTES + entry.name.length).toInt()
    }

    /** Where the record of section [name] begins in [payload]. */
    fun recordOf(name: String): Int = header.sections.single { it.name == name }.offset.toInt()

    /** Back into a file, with [header] written as it is — however little it matches [payload]. */
    fun reassemble(header: SaveHeader = this.header, payload: ByteArray = this.payload): ByteArray =
        reassembleText(json.encodeToString(header), payload)

    fun reassembleText(headerText: String, payload: ByteArray = this.payload): ByteArray {
        val headerBytes = headerText.encodeToByteArray()
        val headerChecksum = Crc32.of(headerBytes)
        val out = ArrayList<Byte>()
        fun int(value: Int) = repeat(4) { out.add((value ushr (8 * it)).toByte()) }
        "CGWD".forEach { out.add(it.code.toByte()) }
        int(WorldCodec.FORMAT_VERSION)
        int(headerBytes.size)
        int(headerChecksum)
        headerBytes.forEach { out.add(it) }
        var at = 0
        var index = 0
        while (at < payload.size) {
            val length = minOf(WorldCodec.CHUNK_BYTES, payload.size - at)
            int(length)
            int(length)
            int(chunkChecksum(headerChecksum, index++, payload, at, length))
            int(PayloadWriter.METHOD_STORED)
            for (index in at until at + length) out.add(payload[index])
            at += length
        }
        repeat(PayloadWriter.FRAME_HEADER_BYTES) { out.add(0) }
        return out.toByteArray()
    }

    companion object {
        private val json = Json { encodeDefaults = true }

        fun of(bytes: ByteArray): TakenApart {
            val header = WorldCodec.decodeHeader(bytes)
            val headerLength = getInt(bytes, WorldCodec.HEADER_LENGTH_OFFSET)
            var at = WorldCodec.PREFIX_BYTES + headerLength
            val payload = ArrayList<Byte>()
            while (true) {
                val rawLength = getInt(bytes, at)
                val method = getInt(bytes, at + 12)
                at += PayloadWriter.FRAME_HEADER_BYTES
                if (rawLength == 0) break
                check(method == PayloadWriter.METHOD_STORED) { "only raw containers come apart" }
                for (index in at until at + rawLength) payload.add(bytes[index])
                at += rawLength
            }
            return TakenApart(header, payload.toByteArray())
        }
    }
}

/**
 * A compressor that actually shrinks something, for the round trip through the seam: runs of one
 * byte become the byte and the run's length. The synthetic worlds' flag and id maps are long runs;
 * their float ramps are not, and come out longer, which is the case where the writer stores the
 * chunk raw instead.
 */
internal object RunLengthCompressor : Compressor {
    override val name: String get() = "runs"

    override suspend fun compress(data: ByteArray): ByteArray {
        val out = ArrayList<Byte>()
        var at = 0
        while (at < data.size) {
            var run = 1
            while (at + run < data.size && run < 255 && data[at + run] == data[at]) run++
            out.add(data[at])
            out.add(run.toByte())
            at += run
        }
        return out.toByteArray()
    }

    override suspend fun decompress(data: ByteArray, limitBytes: Int): ByteArray {
        val out = ArrayList<Byte>()
        var at = 0
        while (at + 1 < data.size && out.size <= limitBytes) {
            repeat(data[at + 1].toInt() and 0xFF) { out.add(data[at]) }
            at += 2
        }
        return out.take(limitBytes + 1).toByteArray()
    }
}
