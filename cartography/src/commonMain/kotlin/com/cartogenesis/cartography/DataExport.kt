package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.IceSheet
import com.cartogenesis.worldgen.pipeline.NationResult
import kotlin.math.roundToInt

/**
 * What an export writes when it writes the world rather than a picture of it.
 *
 * A picture export is finished when it looks right. These are finished when another program can
 * read them: Blender and Unreal want a heightmap they can displace a mesh with, QGIS wants one it
 * can put a contour interval on, and somebody drawing their own map by hand wants the biomes and
 * the realms as flat regions they can select and fill. All three want a number per cell, exactly,
 * with the scale written down — which is what the sidecar is for.
 */
enum class DataLayer(
    val label: String,
    /** What the file is called after the seed and the size, and what the JSON beside it is called. */
    val fileSuffix: String,
    /** The line of small print under the chip. */
    val detail: String
) {
    HEIGHTMAP(
        "Heightmap",
        "heightmap",
        "16-bit greyscale, sea level at 32768, with the metre scale in the JSON beside it. " +
            "Where there is an ice sheet this is the top of the ice, not the bed under it."
    ),
    BIOMES(
        "Biomes",
        "biomes",
        "One palette index per cell, with the legend in the JSON beside it."
    ),
    REALMS(
        "Realms",
        "realms",
        "One palette index per cell, sea and wilderness first, then each realm."
    );
}

/**
 * A data export: the image, and the JSON that says what its numbers mean.
 *
 * Always the pair. A sixteen-bit heightmap with no scale beside it is a grey picture, and an index
 * map with no legend is noise — so the two files are produced together and neither host is given
 * the chance to write one without the other.
 */
class DataFiles(
    val imageName: String,
    val image: ByteArray,
    val sidecarName: String,
    val sidecar: ByteArray
) {
    val totalBytes: Long get() = image.size.toLong() + sidecar.size.toLong()

    /**
     * Both files in one zip, stored rather than deflated.
     *
     * For the browser, where two downloads from one click is a thing browsers ask the reader to
     * approve and Safari simply drops. Stored because a PNG is already deflated and the JSON is a
     * few hundred bytes: compressing the pair again would spend seconds of the page's only thread
     * to save nothing.
     */
    fun asZip(): ByteArray = Zip.of(
        listOf(imageName to image, sidecarName to sidecar)
    )
}

object DataExports {

    /**
     * The grey level the shoreline sits at, in every heightmap this writes, on every world.
     *
     * A fixed value rather than one derived per world, and the midpoint rather than anything
     * cleverer, because the number's whole job is to be typed into somebody else's program. "Sea
     * level is 0.5" is a sentence that works in Blender's displacement node, Unreal's landscape
     * import, Unity's terrain and QGIS's band rendering alike; "sea level is 0.5083 on this
     * particular world" is a number that has to be looked up every time and got wrong once.
     *
     * The cost is half a grey level of asymmetry — see [LEVELS_PER_SIDE] — which is nothing next to
     * a scale a reader can remember.
     */
    const val SEA_LEVEL_GREY_LEVEL = 32768

    /**
     * Grey levels between the shoreline and each end of the range.
     *
     * The same count above and below, so the shoreline sits at the middle grey whatever the two
     * halves of the world's relief are worth in metres. That costs the single darkest level: the
     * deepest sea floor lands on 1 rather than 0, and nothing is ever written as 0.
     */
    const val LEVELS_PER_SIDE = 32767

    /**
     * Bumped when a field in the sidecar changes meaning, so a reader's parser can tell.
     *
     * 3 raised the heightmap's ceiling so the ice fits under it. The elevation field carries the
     * ice sheet's surface since I1, which stands over the top of the land's own ruler, so
     * `metresPerGreyLevel` is now that ruler times [heightmapCeilingRulers] and a new
     * `heightmapCeilingMetres` states where white lands. A version-2 reader would put every cell
     * 80% too low.
     *
     * 2 gave the sea a depth of its own: a grey level below the waterline is worth
     * `metresPerGreyLevelBelowSeaLevel` rather than the land's `metresPerGreyLevel`, and
     * `maxAltitudeMetres` became `highestLandMetres` beside a new `deepestOceanMetres`.
     */
    const val SIDECAR_VERSION = 3

    /**
     * Where the shoreline-relative elevation of a cell lands in the sixteen-bit range.
     *
     * [relativeElevation] is [com.cartogenesis.worldgen.pipeline.SeaLevelResult.relativeElevation]:
     * 0..1 from the shoreline to the highest ground, -1..0 from the shoreline to the deepest sea
     * floor. That is the field every stage downstream of sea level reads and the field the map is
     * drawn from, so it is the field the heightmap carries — not the raw uplift, which the shelf,
     * the ice and the drowned-basin outlets have all since moved.
     */
    /** The most a sixteen-bit sample can hold. */
    const val HIGHEST_GREY_LEVEL = 65535

    /**
     * How much taller than the land's own ruler the heightmap's top has to be, to fit the ice.
     *
     * The elevation field's contract is that `1` is `WorldScale.highestLandMetres` and that no
     * cell need reach it. I1 broke the second half: the field now carries the ice sheet's
     * *surface*, so a dome on high ground stands over the top of the land's half of the ruler —
     * measured on the standard seeds at 512, the highest cell reads 1.1076, 1.1272, 0.9008 and
     * 1.2368, and every cell over 1 on all four is ice-sheet biome. Clamped at 1, as this encoder
     * did until now, those cells all came back as white and none of them round-tripped: seed 42's
     * summit returned 0.1076 of the range from where it started, against a bar of one grey level.
     *
     * The factor is derived from the envelope guard rather than from those measurements, so that
     * a world with more ice than any of them still fits: the highest surface the generator can
     * produce is the highest ground plus the thickest ice it will draw, and that thickness is
     * capped at [IceSheet.THICKEST_ICE_ON_EARTH_METRES], Bedmap2's deepest sounding. On the stock
     * ruler that is 6,000 metres of land plus 4,776 of ice, or 1.796 rulers; a world configured
     * with a shorter ruler gets a proportionally taller factor, which is why this is read off the
     * world rather than written down as one number.
     *
     * The cost is precision, and it is small: a grey level goes from 0.183 m to 0.329 m, still a
     * third of a metre over a range of ten kilometres, and the eight-bit control this is measured
     * against is still forty times worse.
     *
     * What this is *not* is the right long-term answer. A heightmap is a terrain, and a terrain
     * tool wants the ground: the bed, with the ice carried separately. That needs the ice
     * thickness on the world, and today it leaves the glaciation stage as an observer — see
     * `GlacialMass` — so nothing downstream of the pipeline can see it. Putting it on the model is
     * a save-format change and belongs to whoever takes that on; the sidecar and the export chip
     * both say plainly that what is written is the surface.
     */
    fun heightmapCeilingRulers(config: WorldGenConfig): Float =
        1f + IceSheet.THICKEST_ICE_ON_EARTH_METRES / config.scale.highestLandMetres

    fun greyLevelFor(relativeElevation: Float, ceilingRulers: Float): Int =
        (
            SEA_LEVEL_GREY_LEVEL +
                (relativeElevation / ceilingRulers).coerceIn(-1f, 1f) * LEVELS_PER_SIDE
            )
            .roundToInt()
            .coerceIn(0, HIGHEST_GREY_LEVEL)

    /** The inverse, which is the arithmetic a reader of the file has to do. */
    fun relativeElevationFor(greyLevel: Int, ceilingRulers: Float): Float =
        (greyLevel - SEA_LEVEL_GREY_LEVEL).toFloat() / LEVELS_PER_SIDE * ceilingRulers

    /**
     * Metres of altitude one grey level above the sea-level grey is worth.
     *
     * [com.cartogenesis.worldgen.model.WorldScale.highestLandMetres] is the top of the land's half
     * of the generator's ruler: the full 0..1 above the shoreline is that many metres, which is
     * what the lapse rate in `ClimateStage` is computed against.
     *
     * Below the waterline the scale is a different one — see [metresPerGreyLevelBelowSeaLevel] —
     * because the sea now has a depth of its own rather than borrowing the land's. The sidecar
     * states both, and both ends of the range, so a reader can convert either half.
     */
    fun metresPerGreyLevel(config: WorldGenConfig): Double =
        config.scale.highestLandMetres.toDouble() * heightmapCeilingRulers(config) / LEVELS_PER_SIDE

    /**
     * Metres of depth one grey level below the sea-level grey is worth.
     *
     * The sea's half of the ruler, [com.cartogenesis.worldgen.model.WorldScale.deepestOceanMetres]
     * over the same number of grey levels. Steeper than the land's, because the deepest floor is
     * further from the waterline than the highest ground is.
     */
    fun metresPerGreyLevelBelowSeaLevel(config: WorldGenConfig): Double =
        config.scale.deepestOceanMetres.toDouble() / LEVELS_PER_SIDE

    /** What the file is called, before the extension. */
    fun baseName(config: WorldGenConfig, size: Int, layer: DataLayer): String =
        "cartogenesis-${config.seed}-$size-${layer.fileSuffix}"

    /**
     * Renders [layer] from [world] at whatever size [world] was generated at.
     *
     * No resampling happens here, and none is wanted: an export re-runs the whole pipeline at the
     * export size — see the desktop's `Exporter` and the browser's `WebPlatform` — so the world
     * handed in already has one cell per exported pixel. That is the picture export's own rule
     * ("the detail is real rather than interpolated") applied to the data, and it is why a
     * heightmap at 4096 carries 4096 cells of real terrain rather than a 1024 world stretched.
     */
    suspend fun write(
        world: WorldMap,
        layer: DataLayer,
        compressor: Compressor,
        appVersion: String
    ): DataFiles {
        val deflater = GzipRewrappingDeflater(compressor)
        val base = baseName(world.config, world.width, layer)
        val image: ByteArray
        val sidecar: String
        when (layer) {
            DataLayer.HEIGHTMAP -> {
                image = heightmapPng(world, deflater)
                sidecar = heightmapSidecar(world, appVersion)
            }
            DataLayer.BIOMES -> {
                val painted = biomeIndices(world)
                image = PngWriter.indexed8(
                    world.width, world.height, painted.indices, painted.palette, deflater
                )
                sidecar = layerSidecar(world, layer, painted, appVersion)
            }
            DataLayer.REALMS -> {
                val painted = realmIndices(world)
                image = PngWriter.indexed8(
                    world.width, world.height, painted.indices, painted.palette, deflater
                )
                sidecar = layerSidecar(world, layer, painted, appVersion)
            }
        }
        return DataFiles("$base.png", image, "$base.json", sidecar.encodeToByteArray())
    }

    private suspend fun heightmapPng(world: WorldMap, deflater: ZlibDeflater): ByteArray {
        val relative = world.sea.relativeElevation.data
        val ceilingRulers = heightmapCeilingRulers(world.config)
        val samples = IntArray(relative.size) { greyLevelFor(relative[it], ceilingRulers) }
        return PngWriter.greyscale16(world.width, world.height, samples, deflater)
    }

    /** One palette entry per index, the pixels, and what each index is called. */
    private class Painted(
        val indices: ByteArray,
        val palette: IntArray,
        val names: List<String>,
        val cellCounts: IntArray
    )

    /**
     * Biomes, indexed by the ordinal the enum already declares.
     *
     * Deliberately the ordinal rather than a compacted list of only the biomes this world has: a
     * biome's ordinal is already the number a save writes for it, so an index map from one world
     * means the same thing as an index map from another, and a script that reads two of them does
     * not have to reconcile two legends.
     */
    private fun biomeIndices(world: WorldMap): Painted {
        val biomes = Biome.entries
        val field = world.climate.biome
        val indices = ByteArray(field.size)
        val counts = IntArray(biomes.size)
        for (cell in field.indices) {
            val ordinal = field[cell].ordinal
            indices[cell] = ordinal.toByte()
            counts[ordinal]++
        }
        return Painted(
            indices = indices,
            palette = IntArray(biomes.size) { MapPalette.biome(biomes[it]) },
            names = biomes.map { readable(it.name) },
            cellCounts = counts
        )
    }

    /**
     * Realms, with the two kinds of unclaimed ground told apart.
     *
     * `nationId` is [NationResult.UNCLAIMED] for open sea and for wilderness alike, which is right
     * for the generator and useless in an index map — the whole reason to export realms is to
     * select a country and fill it, and "everything that is not a country" being one index would
     * put the Atlantic and the Siberian interior in the same selection. So index 0 is water, index
     * 1 is land no realm holds, and a realm's own id is offset past both.
     */
    private fun realmIndices(world: WorldMap): Painted {
        val realms = world.nations.nations
        val nationId = world.nations.nationId
        val indices = ByteArray(nationId.size)
        val entryCount = realms.size + INDICES_BEFORE_THE_REALMS
        val counts = IntArray(entryCount)
        for (cell in nationId.indices) {
            val id = nationId[cell]
            val index = when {
                id != NationResult.UNCLAIMED && id < realms.size ->
                    id + INDICES_BEFORE_THE_REALMS
                world.sea.isLand[cell] -> UNCLAIMED_LAND_INDEX
                else -> SEA_INDEX
            }
            indices[cell] = index.toByte()
            counts[index]++
        }
        return Painted(
            indices = indices,
            palette = IntArray(entryCount) { index ->
                when (index) {
                    SEA_INDEX -> MapPalette.biome(Biome.OCEAN)
                    UNCLAIMED_LAND_INDEX -> MapPalette.WILDERNESS
                    else -> MapPalette.nation(realms[index - INDICES_BEFORE_THE_REALMS].id)
                }
            },
            names = listOf("Sea", "Unclaimed land") + realms.map { it.name },
            cellCounts = counts
        )
    }

    /** The two entries every realm layer starts with, before a realm's own id is offset past them. */
    private const val SEA_INDEX = 0
    private const val UNCLAIMED_LAND_INDEX = 1
    private const val INDICES_BEFORE_THE_REALMS = 2

    /** `TEMPERATE_RAINFOREST` as a person would write it. */
    private fun readable(enumName: String): String =
        enumName.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun heightmapSidecar(world: WorldMap, appVersion: String): String {
        val config = world.config
        val metresPerLevel = metresPerGreyLevel(config)
        val metresPerLevelBelow = metresPerGreyLevelBelowSeaLevel(config)
        val json = JsonLines()
        common(json, world, DataLayer.HEIGHTMAP, appVersion)
        json.number("bitsPerSample", GREYSCALE_BITS_PER_SAMPLE)
        json.number("seaLevelGreyLevel", SEA_LEVEL_GREY_LEVEL)
        json.number("greyLevelsPerSide", LEVELS_PER_SIDE)
        json.number("metresPerGreyLevel", metresPerLevel)
        json.number("metresPerGreyLevelBelowSeaLevel", metresPerLevelBelow)
        json.number(
            "metresAtGreyLevel$HIGHEST_GREY_LEVEL",
            metresPerLevel * (HIGHEST_GREY_LEVEL - SEA_LEVEL_GREY_LEVEL)
        )
        json.number("metresAtGreyLevel0", metresPerLevelBelow * (0 - SEA_LEVEL_GREY_LEVEL))
        json.number(
            "heightmapCeilingMetres",
            config.scale.highestLandMetres.toDouble() * heightmapCeilingRulers(config)
        )
        json.text(
            "heightmapCarries",
            "the ice sheet's surface where there is one, not the bed beneath it"
        )
        json.number("highestLandMetres", config.scale.highestLandMetres.toDouble())
        json.number("deepestOceanMetres", config.scale.deepestOceanMetres.toDouble())
        json.text(
            "metresFromGreyLevel",
            "(greyLevel - $SEA_LEVEL_GREY_LEVEL) * (greyLevel >= $SEA_LEVEL_GREY_LEVEL ?" +
                " metresPerGreyLevel : metresPerGreyLevelBelowSeaLevel)"
        )
        return json.finish()
    }

    private fun layerSidecar(
        world: WorldMap,
        layer: DataLayer,
        painted: Painted,
        appVersion: String
    ): String {
        val json = JsonLines()
        common(json, world, layer, appVersion)
        json.number("bitsPerSample", INDEXED_BITS_PER_SAMPLE)
        json.number("paletteSize", painted.palette.size)
        json.legend(painted.names, painted.palette, painted.cellCounts)
        return json.finish()
    }

    /** How many bits a sample of each of the two exports takes. See [PngWriter]. */
    private const val GREYSCALE_BITS_PER_SAMPLE = 16
    private const val INDEXED_BITS_PER_SAMPLE = 8

    private fun common(json: JsonLines, world: WorldMap, layer: DataLayer, appVersion: String) {
        val config = world.config
        val scale = config.scale
        json.text("generator", "Cartogenesis")
        json.text("appVersion", appVersion)
        json.number("sidecarVersion", SIDECAR_VERSION)
        json.number("saveFormatVersion", WorldCodec.FORMAT_VERSION)
        json.text("layer", layer.fileSuffix)
        json.number("seed", config.seed)
        json.number("widthPixels", world.width)
        json.number("heightPixels", world.height)
        json.number("worldWidthKm", scale.worldWidthKm)
        json.number("cellWidthKm", scale.cellWidthKm(world.width))
        json.number("cellHeightKm", scale.cellHeightKm(world.height))
        json.number(
            "squareKilometresPerCell",
            scale.squareKilometresPerCell(world.width, world.height)
        )
    }
}

/**
 * A JSON document written a line at a time.
 *
 * By hand rather than through `kotlinx.serialization` because this document is read by people as
 * often as by programs — it is the file that explains the other file — and one field per line in a
 * declared order is worth more here than a serialiser's field order. There are five value shapes
 * and no nesting beyond the legend, so the escaping below covers everything that can appear.
 */
private class JsonLines {

    private val fields = mutableListOf<String>()

    fun text(name: String, value: String) {
        fields += "  ${quoted(name)}: ${quoted(value)}"
    }

    fun number(name: String, value: Int) {
        fields += "  ${quoted(name)}: $value"
    }

    fun number(name: String, value: Long) {
        fields += "  ${quoted(name)}: $value"
    }

    fun number(name: String, value: Double) {
        fields += "  ${quoted(name)}: ${plain(value)}"
    }

    fun legend(names: List<String>, palette: IntArray, cellCounts: IntArray) {
        val entries = names.indices.joinToString(",\n") { i ->
            "    { \"index\": $i, \"name\": ${quoted(names[i])}, " +
                "\"colour\": ${quoted(hex(palette[i]))}, \"cells\": ${cellCounts[i]} }"
        }
        fields += "  \"legend\": [\n$entries\n  ]"
    }

    fun finish(): String = "{\n" + fields.joinToString(",\n") + "\n}\n"

    private fun quoted(text: String): String {
        val out = StringBuilder(text.length + 2)
        out.append('"')
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c.code < 0x20 -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> out.append(c)
            }
        }
        out.append('"')
        return out.toString()
    }

    /**
     * A double as JSON has it, never in exponent form.
     *
     * Kotlin writes small magnitudes as `1.83E-4`, which is valid JSON and which several of the
     * tools this file exists for read as a string. The scales here are between a ten-thousandth and
     * a few tens of thousands, so six decimal places is exact enough for all of them and always
     * ordinary notation.
     */
    private fun plain(value: Double): String {
        if (value == value.toLong().toDouble()) return "${value.toLong()}.0"
        val negative = value < 0
        val scaled = kotlin.math.round(kotlin.math.abs(value) * 1_000_000.0).toLong()
        val whole = scaled / 1_000_000
        val fraction = (scaled % 1_000_000).toString().padStart(6, '0').trimEnd('0')
        val digits = if (fraction.isEmpty()) "0" else fraction
        return "${if (negative) "-" else ""}$whole.$digits"
    }

    private fun hex(argb: Int): String {
        val rgb = argb and 0xFFFFFF
        return "#" + rgb.toString(16).padStart(6, '0').uppercase()
    }
}

/**
 * A zip of stored entries, which is the only kind this needs to write.
 *
 * The browser hands a reader one file per click, so the heightmap and its sidecar travel as one
 * archive; the desktop writes the two side by side and never comes here. Stored rather than
 * deflated because both members are already as small as they are going to get.
 */
private object Zip {

    /** The three record signatures of the format, as PKWARE's specification numbers them. */
    private const val LOCAL_HEADER_SIGNATURE = 0x04034B50
    private const val CENTRAL_ENTRY_SIGNATURE = 0x02014B50
    private const val END_OF_DIRECTORY_SIGNATURE = 0x06054B50

    /** Version 2.0 of the format, written as tenths: the one that first defined stored entries. */
    private const val FORMAT_VERSION_IN_TENTHS = 20

    /** The compression method field. Zero is "stored", which is the only one this writes. */
    private const val METHOD_STORED = 0

    /**
     * The modification time and date every entry carries.
     *
     * Fixed rather than taken from a clock, so the same export twice is the same bytes: 0x21 is
     * the first day of 1980, which is the epoch an MS-DOS date counts from and the earliest one it
     * can express. A zero date is not legal.
     */
    private const val DOS_TIME = 0
    private const val DOS_DATE = 0x21

    /** Nothing this writes uses these fields: no flags, no extra data, one disk, no comment. */
    private const val UNUSED_FIELD = 0

    /** Room for the two headers and the directory of a two-entry archive. */
    private const val HEADER_ALLOWANCE_BYTES = 512

    fun of(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteSink(entries.sumOf { it.second.size } + HEADER_ALLOWANCE_BYTES)
        val offsets = IntArray(entries.size)
        val checksums = IntArray(entries.size)

        entries.forEachIndexed { index, (name, data) ->
            offsets[index] = out.length
            checksums[index] = Crc32.of(data)
            out.littleInt(LOCAL_HEADER_SIGNATURE)
            out.littleShort(FORMAT_VERSION_IN_TENTHS)
            out.littleShort(UNUSED_FIELD) // flags: no encryption, no data descriptor
            out.littleShort(METHOD_STORED)
            out.littleShort(DOS_TIME)
            out.littleShort(DOS_DATE)
            out.littleInt(checksums[index])
            // Compressed and uncompressed size, which are the same thing for a stored entry.
            out.littleInt(data.size)
            out.littleInt(data.size)
            out.littleShort(name.length)
            out.littleShort(UNUSED_FIELD) // extra field
            out.ascii(name)
            out.bytes(data)
        }

        val directoryAt = out.length
        entries.forEachIndexed { index, (name, data) ->
            out.littleInt(CENTRAL_ENTRY_SIGNATURE)
            out.littleShort(FORMAT_VERSION_IN_TENTHS) // made by
            out.littleShort(FORMAT_VERSION_IN_TENTHS) // needed to extract
            out.littleShort(UNUSED_FIELD) // flags
            out.littleShort(METHOD_STORED)
            out.littleShort(DOS_TIME)
            out.littleShort(DOS_DATE)
            out.littleInt(checksums[index])
            out.littleInt(data.size)
            out.littleInt(data.size)
            out.littleShort(name.length)
            out.littleShort(UNUSED_FIELD) // extra
            out.littleShort(UNUSED_FIELD) // comment
            out.littleShort(UNUSED_FIELD) // disk
            out.littleShort(UNUSED_FIELD) // internal attributes
            out.littleInt(UNUSED_FIELD)   // external attributes
            out.littleInt(offsets[index])
            out.ascii(name)
        }
        val directorySize = out.length - directoryAt

        out.littleInt(END_OF_DIRECTORY_SIGNATURE)
        out.littleShort(UNUSED_FIELD) // this disk's number
        out.littleShort(UNUSED_FIELD) // the disk the directory starts on
        out.littleShort(entries.size)
        out.littleShort(entries.size)
        out.littleInt(directorySize)
        out.littleInt(directoryAt)
        out.littleShort(UNUSED_FIELD) // archive comment
        return out.toByteArray()
    }
}
