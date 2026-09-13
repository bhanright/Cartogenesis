package com.cartogenesis.cartography

import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
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
        "16-bit greyscale, sea level at 32768, with the metre scale in the JSON beside it."
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
     * The same count above and below, so there is one metres-per-grey-level for the whole image
     * rather than one for the land and another for the sea. That costs the single darkest level:
     * the deepest sea floor lands on 1 rather than 0, and nothing is ever written as 0.
     */
    const val LEVELS_PER_SIDE = 32767

    /** Bumped when a field in the sidecar changes meaning, so a reader's parser can tell. */
    const val SIDECAR_VERSION = 1

    /**
     * Where the shoreline-relative elevation of a cell lands in the sixteen-bit range.
     *
     * [relativeElevation] is [com.cartogenesis.worldgen.pipeline.SeaLevelResult.relativeElevation]:
     * 0..1 from the shoreline to the highest ground, -1..0 from the shoreline to the deepest sea
     * floor. That is the field every stage downstream of sea level reads and the field the map is
     * drawn from, so it is the field the heightmap carries — not the raw uplift, which the shelf,
     * the ice and the drowned-basin outlets have all since moved.
     */
    fun greyLevelFor(relativeElevation: Float): Int =
        (SEA_LEVEL_GREY_LEVEL + relativeElevation.coerceIn(-1f, 1f) * LEVELS_PER_SIDE)
            .roundToInt()
            .coerceIn(0, 65535)

    /** The inverse, which is the arithmetic a reader of the file has to do. */
    fun relativeElevationFor(greyLevel: Int): Float =
        (greyLevel - SEA_LEVEL_GREY_LEVEL).toFloat() / LEVELS_PER_SIDE

    /**
     * Metres of altitude one grey level is worth.
     *
     * [com.cartogenesis.worldgen.model.ClimateConfig.maxAltitudeMetres] is the metre scale the
     * generator declares, and it declares it for the land: the full 0..1 above the shoreline is
     * that many metres, which is what the lapse rate in `ClimateStage` is computed against. The sea
     * floor is normalised to the same span below the shoreline, so continuing the same scale
     * downwards is the reading that keeps one number for the whole image; it puts the deepest sea
     * floor as far below the waterline as the highest summit is above it, which for the default
     * 6,000 m is deeper than Earth's mean ocean and shallower than its trenches. The sidecar states
     * both ends, so a reader who wants a different ocean can rescale the lower half themselves.
     */
    fun metresPerGreyLevel(config: WorldGenConfig): Double =
        config.climate.maxAltitudeMetres.toDouble() / LEVELS_PER_SIDE

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
        val samples = IntArray(relative.size) { greyLevelFor(relative[it]) }
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
        for (i in field.indices) {
            val ordinal = field[i].ordinal
            indices[i] = ordinal.toByte()
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
        val counts = IntArray(realms.size + 2)
        for (i in nationId.indices) {
            val id = nationId[i]
            val index = when {
                id != NationResult.UNCLAIMED && id < realms.size -> id + 2
                world.sea.isLand[i] -> 1
                else -> 0
            }
            indices[i] = index.toByte()
            counts[index]++
        }
        return Painted(
            indices = indices,
            palette = IntArray(realms.size + 2) {
                when (it) {
                    0 -> MapPalette.biome(Biome.OCEAN)
                    1 -> MapPalette.WILDERNESS
                    else -> MapPalette.nation(realms[it - 2].id)
                }
            },
            names = listOf("Sea", "Unclaimed land") + realms.map { it.name },
            cellCounts = counts
        )
    }

    /** `TEMPERATE_RAINFOREST` as a person would write it. */
    private fun readable(enumName: String): String =
        enumName.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun heightmapSidecar(world: WorldMap, appVersion: String): String {
        val config = world.config
        val metresPerLevel = metresPerGreyLevel(config)
        val json = JsonLines()
        common(json, world, DataLayer.HEIGHTMAP, appVersion)
        json.number("bitsPerSample", 16)
        json.number("seaLevelGreyLevel", SEA_LEVEL_GREY_LEVEL)
        json.number("greyLevelsPerSide", LEVELS_PER_SIDE)
        json.number("metresPerGreyLevel", metresPerLevel)
        json.number("metresAtGreyLevel65535", metresPerLevel * (65535 - SEA_LEVEL_GREY_LEVEL))
        json.number("metresAtGreyLevel0", metresPerLevel * (0 - SEA_LEVEL_GREY_LEVEL))
        json.number("maxAltitudeMetres", config.climate.maxAltitudeMetres.toDouble())
        json.text(
            "metresFromGreyLevel",
            "(greyLevel - $SEA_LEVEL_GREY_LEVEL) * metresPerGreyLevel"
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
        json.number("bitsPerSample", 8)
        json.number("paletteSize", painted.palette.size)
        json.legend(painted.names, painted.palette, painted.cellCounts)
        return json.finish()
    }

    private fun common(json: JsonLines, world: WorldMap, layer: DataLayer, appVersion: String) {
        val config = world.config
        val nations = config.nations
        json.text("generator", "Cartogenesis")
        json.text("appVersion", appVersion)
        json.number("sidecarVersion", SIDECAR_VERSION)
        json.number("saveFormatVersion", WorldCodec.FORMAT_VERSION)
        json.text("layer", layer.fileSuffix)
        json.number("seed", config.seed)
        json.number("widthPixels", world.width)
        json.number("heightPixels", world.height)
        json.number("worldWidthKm", nations.worldWidthKm)
        json.number("cellWidthKm", nations.worldWidthKm / world.width)
        json.number("cellHeightKm", nations.worldWidthKm / 2.0 / world.height)
        json.number(
            "squareKilometresPerCell",
            nations.squareKilometresPerCell(world.width, world.height)
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

    /** The 1980 epoch MS-DOS dates start at. Fixed, so the same export twice is the same bytes. */
    private const val DOS_TIME = 0
    private const val DOS_DATE = 0x21

    fun of(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteSink(entries.sumOf { it.second.size } + 512)
        val offsets = IntArray(entries.size)
        val checksums = IntArray(entries.size)

        entries.forEachIndexed { i, (name, data) ->
            offsets[i] = out.length
            checksums[i] = Crc32.of(data)
            out.littleInt(0x04034B50)
            out.littleShort(20) // the version that first understood stored entries
            out.littleShort(0)  // no flags: no encryption, no data descriptor
            out.littleShort(0)  // stored
            out.littleShort(DOS_TIME)
            out.littleShort(DOS_DATE)
            out.littleInt(checksums[i])
            out.littleInt(data.size)
            out.littleInt(data.size)
            out.littleShort(name.length)
            out.littleShort(0)  // no extra field
            out.ascii(name)
            out.bytes(data)
        }

        val directoryAt = out.length
        entries.forEachIndexed { i, (name, data) ->
            out.littleInt(0x02014B50)
            out.littleShort(20) // made by
            out.littleShort(20) // needed to extract
            out.littleShort(0)
            out.littleShort(0)
            out.littleShort(DOS_TIME)
            out.littleShort(DOS_DATE)
            out.littleInt(checksums[i])
            out.littleInt(data.size)
            out.littleInt(data.size)
            out.littleShort(name.length)
            out.littleShort(0) // extra
            out.littleShort(0) // comment
            out.littleShort(0) // disk
            out.littleShort(0) // internal attributes
            out.littleInt(0)   // external attributes
            out.littleInt(offsets[i])
            out.ascii(name)
        }
        val directorySize = out.length - directoryAt

        out.littleInt(0x06054B50)
        out.littleShort(0)
        out.littleShort(0)
        out.littleShort(entries.size)
        out.littleShort(entries.size)
        out.littleInt(directorySize)
        out.littleInt(directoryAt)
        out.littleShort(0) // no archive comment
        return out.toByteArray()
    }
}
