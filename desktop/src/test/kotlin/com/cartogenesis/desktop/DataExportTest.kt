package com.cartogenesis.desktop

import com.cartogenesis.cartography.DataExports
import com.cartogenesis.cartography.DataFiles
import com.cartogenesis.cartography.DataLayer
import com.cartogenesis.cartography.MapPalette
import com.cartogenesis.cartography.MapRasterizer
import com.cartogenesis.cartography.NoCompression
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.WorldCodec
import com.cartogenesis.ui.BuildInfo
import com.cartogenesis.ui.ExportFormat
import com.cartogenesis.ui.MapImage
import com.cartogenesis.worldgen.SharedWorlds
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.Biome
import com.cartogenesis.worldgen.pipeline.NationResult
import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.extension.ExtendWith

/**
 * What the three data exports promise, held to.
 *
 * A picture export is judged by eye. These are judged by whether another program can read them, and
 * that is a question with an exact answer: the heightmap has to come back off disk as the elevation
 * field it went on as, the index maps have to come back as the same indices, and the JSON beside
 * them has to say the same numbers the generator was configured with. Everything below reads its
 * files back through `ImageIO` rather than through the encoder that wrote them — a round trip
 * through one's own code proves nothing about whether Blender or QGIS will open the file.
 *
 * 512, which takes a few seconds; the sizes the app actually offers are timed in `ExportAuditTest`.
 */
@ExtendWith(SharedWorldsCheck::class)
class DataExportTest {

    private val config = WorldGenConfig(seed = 42L, width = 512, height = 512)
    private val world: WorldMap by lazy { SharedWorlds.world(config) }

    private fun write(layer: DataLayer): DataFiles = runBlocking {
        DataExports.write(world, layer, GzipCompressor, BuildInfo.VERSION)
    }

    // ---- the heightmap ------------------------------------------------------------------------

    /**
     * The guard the chunk turns on: sixteen bits of elevation survive the round trip.
     *
     * The failure this exists to catch is not a subtle one, it is the obvious one — routing the
     * heightmap through the encoder the picture export already uses, which is eight bits a channel
     * and would look perfectly fine on screen. So the eight-bit quantisation of the same field is
     * measured beside it: the bar has to pass what was built and fail what would have been easier
     * to build, or it is not a bar.
     */
    @Test
    fun `a heightmap read back is the elevation field to sixteen-bit precision`() {
        val files = write(DataLayer.HEIGHTMAP)
        val image = decode(files.image)

        assertEquals(
            BufferedImage.TYPE_USHORT_GRAY,
            image.type,
            "the heightmap did not come back as a 16-bit greyscale image"
        )
        assertEquals(world.width, image.width)
        assertEquals(world.height, image.height)

        val relative = world.sea.relativeElevation.data
        // One grey level, in the units the field is held in. Anything at or under this is
        // quantisation and nothing else; anything over it is a value that was not carried.
        //
        // A grey level is worth more of the field than it was, because the heightmap's ceiling now
        // covers the ice standing on the ground as well as the ground: see
        // [DataExports.heightmapCeilingRulers]. The bar follows the encoding rather than being
        // restated, so it is still exactly one level and still forty times tighter than eight bits.
        val ceilingRulers = DataExports.heightmapCeilingRulers(config)
        val oneGreyLevel = ceilingRulers.toDouble() / DataExports.LEVELS_PER_SIDE

        var worst = 0.0
        var worstAt = 0
        val shorelineLevels = mutableSetOf<Int>()
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val i = y * image.width + x
                val level = image.raster.getSample(x, y, 0)
                val error =
                    abs(DataExports.relativeElevationFor(level, ceilingRulers) - relative[i])
                        .toDouble()
                if (error > worst) {
                    worst = error
                    worstAt = i
                }
                // Every cell exactly at the waterline must sit on the stated level, or the number
                // in the sidecar is a number a reader cannot act on.
                if (relative[i] == 0f) shorelineLevels += level
            }
        }

        // The same field with only eight bits to spend, which is what any picture encoder would
        // have given: 256 levels over the same range instead of 65,535.
        var worstAtEightBits = 0.0
        for (value in relative) {
            val level = (128 + value.coerceIn(-1f, 1f) * 127).roundToInt().coerceIn(0, 255)
            val back = (level - 128) / 127f
            worstAtEightBits = maxOf(worstAtEightBits, abs(back - value).toDouble())
        }

        println(
            "HEIGHTMAP worst error %.3e of the field's range (one grey level is %.3e); ".format(
                worst, oneGreyLevel
            ) + "the same field at 8 bits: %.3e, %.0f grey levels".format(
                worstAtEightBits, worstAtEightBits / oneGreyLevel
            )
        )
        assertTrue(
            worst <= oneGreyLevel,
            "cell $worstAt came back $worst from where it started, past one grey level of $oneGreyLevel"
        )
        assertTrue(
            worstAtEightBits > oneGreyLevel,
            "eight bits would have passed this bar too, so the bar proves nothing"
        )
        assertTrue(
            shorelineLevels.isEmpty() ||
                shorelineLevels == setOf(DataExports.SEA_LEVEL_GREY_LEVEL),
            "the shoreline landed on $shorelineLevels rather than ${DataExports.SEA_LEVEL_GREY_LEVEL}"
        )
    }

    /**
     * Open sea is never lighter than the stated sea level, and the land that is darker than it is
     * land that is genuinely below the waterline.
     *
     * The obvious assertion — no land cell under 32768 — is false, and finding out why is worth the
     * paragraph. `SeaConfig.enclosedSeaIsLand` turns a body of water the ocean cannot reach into
     * land with a hollow floor below the waterline, which is the Caspian, the Dead Sea and the
     * Qattara — and with `postCutOutlet` on it is then drained out into a salt flat, so the floor
     * is dry ground below the waterline rather than a lake. On seed 42 at 512 that is 497 cells and
     * the ice accounts for none of them (measured with `GlaciationConfig.enabled` off, which
     * changes nothing here). A heightmap that clamped that floor to sea level would be flattening a
     * real depression, so what is held is the pair that is not allowed: no *open sea* cell may come
     * back above the line, and with every rule that puts land below the waterline switched off no
     * land cell may come back below it. There are three of those rules since S2's fourth pass —
     * see the control itself.
     */
    @Test
    fun `only water and drowned basin floors come back darker than the stated sea level`() {
        val sea = DataExports.SEA_LEVEL_GREY_LEVEL
        val basins = write(DataLayer.HEIGHTMAP)
        val image = decode(basins.image)

        var landBelow = 0
        var waterAbove = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val i = y * image.width + x
                val level = image.raster.getSample(x, y, 0)
                if (world.sea.isLand[i]) {
                    if (level < sea) landBelow++
                } else if (level > sea) {
                    waterAbove++
                }
            }
        }

        // The same world with every rule that can leave land below the waterline switched off.
        // Enclosed seas alone were enough until S2's fourth pass, which put more coast within a
        // hundred metres of the waterline and let the other two show: the drowned-valley fill,
        // which raises a channel narrower than its own cell back to the ground either side of it
        // and can leave that floor below the shoreline, and glacial overdeepening, which carves
        // *after* the sea-level cut and so lowers ground that stays marked land. The third is a
        // fjord floor and is not a defect — Sognefjord's is 1,300 m below the sea — but it is a
        // fourth thing this clause has to name before it can say the export is faithful.
        // Measured on seed 718106 at 512: 23 cells with only the enclosed seas switched off, 19
        // with the valley fill off as well, 0 with the ice off too.
        val undrowned = SharedWorlds.world(
            config.copy(
                sea = config.sea.copy(
                    enclosedSeaIsLand = false,
                    drownedValleyFill = false,
                    littoralGrading = false
                ),
                glaciation = config.glaciation.copy(enabled = false)
            )
        )
        val without = decode(
            runBlocking {
                DataExports.write(undrowned, DataLayer.HEIGHTMAP, GzipCompressor, BuildInfo.VERSION)
            }.image
        )
        var landBelowWithoutBasins = 0
        for (y in 0 until without.height) {
            for (x in 0 until without.width) {
                val i = y * without.width + x
                if (undrowned.sea.isLand[i] && without.raster.getSample(x, y, 0) < sea) {
                    landBelowWithoutBasins++
                }
            }
        }

        println(
            "HEIGHTMAP $landBelow land cells below sea level, " +
                "$landBelowWithoutBasins with enclosed seas left as sea"
        )
        assertTrue(landBelow > 0, "this world was supposed to have a below-sea-level basin on it")
        assertEquals(0, waterAbove, "open sea came back above sea level")
        assertEquals(
            0,
            landBelowWithoutBasins,
            "$landBelowWithoutBasins land cells are below the waterline with no drowned basin to explain them"
        )
    }

    @Test
    fun `the heightmap's sidecar says what the generator was configured with`() {
        val files = write(DataLayer.HEIGHTMAP)
        val json = parse(files.sidecar)

        assertEquals("heightmap", json.text("layer"))
        assertEquals("Cartogenesis", json.text("generator"))
        assertEquals(BuildInfo.VERSION, json.text("appVersion"))
        assertEquals(WorldCodec.FORMAT_VERSION, json.int("saveFormatVersion"))
        assertEquals(config.seed, json.int("seed").toLong())
        assertEquals(world.width, json.int("widthPixels"))
        assertEquals(world.height, json.int("heightPixels"))
        assertEquals(16, json.int("bitsPerSample"))
        assertEquals(DataExports.SEA_LEVEL_GREY_LEVEL, json.int("seaLevelGreyLevel"))

        // The scale, read back against `WorldScale` itself rather than against a repeated
        // constant: the sidecar is the one place a reader of the file meets the generator's ruler,
        // so it must be that ruler and not a second copy of it.
        val scale = config.scale
        assertClose(scale.worldWidthKm, json.number("worldWidthKm"))
        assertClose(scale.cellWidthKm(world.width), json.number("cellWidthKm"))
        assertClose(scale.cellHeightKm(world.height), json.number("cellHeightKm"))
        assertClose(
            scale.squareKilometresPerCell(world.width, world.height),
            json.number("squareKilometresPerCell")
        )
        assertClose(scale.highestLandMetres.toDouble(), json.number("highestLandMetres"))
        assertClose(scale.deepestOceanMetres.toDouble(), json.number("deepestOceanMetres"))
        // And the figures a reader actually multiplies by: one grey level in metres on each side
        // of the waterline, and where each end of the range lands. Blackness must be the full
        // declared depth; whiteness is the *heightmap's* ceiling, which since I1 is taller than
        // the land's own ruler because the field carries the ice standing on the ground as well as
        // the ground — see [DataExports.heightmapCeilingRulers]. A reader multiplying by
        // `metresPerGreyLevel` gets metres either way, which is the whole job of the sidecar.
        val ceilingMetres = json.number("heightmapCeilingMetres")
        assertTrue(
            ceilingMetres > scale.highestLandMetres.toDouble(),
            "the heightmap's ceiling (${ceilingMetres}) has to clear the land's ruler" +
                " (${scale.highestLandMetres}) or the ice standing on the ground will not fit"
        )
        val metresPerLevel = json.number("metresPerGreyLevel")
        val metresPerLevelBelow = json.number("metresPerGreyLevelBelowSeaLevel")
        // Stated against each other rather than against the formula restated here: what a reader
        // relies on is that the two numbers in the file agree, and restating the arithmetic in the
        // test only measures whether it was typed twice the same way.
        assertClose(
            ceilingMetres / DataExports.LEVELS_PER_SIDE,
            metresPerLevel
        )
        assertClose(
            scale.deepestOceanMetres.toDouble() / DataExports.LEVELS_PER_SIDE,
            metresPerLevelBelow
        )
        assertScaledFromPrinted(
            metresPerLevel * (65535 - DataExports.SEA_LEVEL_GREY_LEVEL),
            json.number("metresAtGreyLevel65535")
        )
        assertScaledFromPrinted(
            metresPerLevelBelow * (0 - DataExports.SEA_LEVEL_GREY_LEVEL),
            json.number("metresAtGreyLevel0")
        )
        println(
            "HEIGHTMAP sidecar: %.4f m per grey level, white = %.0f m, black = %.0f m".format(
                metresPerLevel,
                json.number("metresAtGreyLevel65535"),
                json.number("metresAtGreyLevel0")
            )
        )
    }

    // ---- the two index maps -------------------------------------------------------------------

    @Test
    fun `the biome map round-trips every index, and its legend names every biome present`() {
        val files = write(DataLayer.BIOMES)
        val image = decode(files.image)
        val palette = image.colorModel as IndexColorModel

        assertEquals(
            Biome.entries.size,
            paletteEntriesIn(files.image),
            "the PNG's own palette is not one entry per biome"
        )

        var wrong = 0
        val present = mutableSetOf<Int>()
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val expected = world.climate.biome[y * image.width + x].ordinal
                if (image.raster.getSample(x, y, 0) != expected) wrong++
                present += expected
            }
        }
        assertEquals(0, wrong, "$wrong cells came back as a different biome")

        // The colour in the PNG's own palette is the colour the map is drawn with, so an index map
        // laid beside a rendered biome view matches it rather than nearly matching it.
        Biome.entries.forEachIndexed { index, biome ->
            assertEquals(
                MapPalette.biome(biome) and 0xFFFFFF,
                palette.getRGB(index) and 0xFFFFFF,
                "${biome.name} is a different colour in the PNG than on the map"
            )
        }

        val legend = parse(files.sidecar).legend()
        assertEquals(Biome.entries.size, legend.size)
        present.forEach { index ->
            val entry = legend.first { it.index == index }
            assertTrue(entry.cells > 0, "${entry.name} is on the map but the legend counts no cells")
            assertTrue(entry.name.isNotBlank())
        }
        // Every biome on the map is named, and — the half that makes the half above worth
        // asserting — the same check said no when one of them was taken out of the legend.
        assertTrue(namesEveryOneOf(present, legend), "a biome on the map is missing from the legend")
        assertFalse(
            namesEveryOneOf(present, legend.filterNot { it.index == present.first() }),
            "the legend check passed a legend a biome on the map had been removed from"
        )
        println(
            "BIOMES ${present.size} of ${Biome.entries.size} biomes on this world, " +
                "${files.image.size / 1024} KB"
        )
    }

    @Test
    fun `the realm map round-trips every index, and tells sea from unclaimed land`() {
        val files = write(DataLayer.REALMS)
        val image = decode(files.image)
        val palette = image.colorModel as IndexColorModel
        val realms = world.nations.nations

        assertEquals(
            realms.size + 2,
            paletteEntriesIn(files.image),
            "the PNG's own palette is not sea, wilderness and one entry per realm"
        )

        assertTrue(
            world.nations.nationId.all {
                it == NationResult.UNCLAIMED || it in realms.indices
            },
            "a cell claims a realm that is not in the world's list"
        )

        var wrong = 0
        var wilderness = 0
        var sea = 0
        val present = mutableSetOf<Int>()
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val i = y * image.width + x
                val id = world.nations.nationId[i]
                val expected = when {
                    id != NationResult.UNCLAIMED -> id + 2
                    world.sea.isLand[i] -> 1
                    else -> 0
                }
                val found = image.raster.getSample(x, y, 0)
                if (found != expected) wrong++
                if (found == 0) sea++
                if (found == 1) wilderness++
                present += found
            }
        }
        assertEquals(0, wrong, "$wrong cells came back as a different realm")
        assertTrue(sea > 0, "no cell came back as sea")
        assertEquals(
            world.width * world.height - world.sea.landCellCount,
            sea,
            "index 0 is not exactly the water"
        )

        val legend = parse(files.sidecar).legend()
        assertEquals(realms.size + 2, legend.size)
        assertEquals("Sea", legend[0].name)
        assertEquals("Unclaimed land", legend[1].name)
        realms.forEachIndexed { index, realm ->
            assertEquals(realm.name, legend[index + 2].name)
            assertEquals(
                MapPalette.nation(realm.id) and 0xFFFFFF,
                palette.getRGB(index + 2) and 0xFFFFFF,
                "${realm.name} is a different colour in the PNG than on the political map"
            )
        }
        assertTrue(namesEveryOneOf(present, legend), "a realm on the map is missing from the legend")
        assertFalse(
            namesEveryOneOf(present, legend.filterNot { it.index == present.first() }),
            "the legend check passed a legend a realm on the map had been removed from"
        )
        println("REALMS ${realms.size} realms, $wilderness wilderness cells, ${files.image.size / 1024} KB")
    }

    // ---- the encoder's own two paths ------------------------------------------------------------

    /**
     * The heightmap comes out the same whether the host had a deflate encoder or not.
     *
     * The image data is a zlib stream, which neither front end can build in common code, so the
     * deflate comes out of the host's gzip — and a host that has none falls back to a stored stream,
     * which is larger and still a PNG. Both have to be a PNG `ImageIO` reads to the same samples,
     * or the fallback is a file that silently is not what it claims.
     */
    @Test
    fun `a host with no compressor writes a larger heightmap with identical samples`() {
        val compressed = write(DataLayer.HEIGHTMAP)
        val stored = runBlocking {
            DataExports.write(world, DataLayer.HEIGHTMAP, NoCompression, BuildInfo.VERSION)
        }

        val a = decode(compressed.image)
        val b = decode(stored.image)
        assertEquals(a.width, b.width)
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                assertEquals(
                    a.raster.getSample(x, y, 0),
                    b.raster.getSample(x, y, 0),
                    "the stored-deflate PNG differs at $x,$y"
                )
            }
        }
        assertTrue(
            stored.image.size > compressed.image.size,
            "the stored fallback (${stored.image.size}) was not larger than the deflated one"
        )
        println(
            "HEIGHTMAP deflated ${compressed.image.size / 1024} KB, " +
                "stored fallback ${stored.image.size / 1024} KB, " +
                "raw samples ${world.width * world.height * 2 / 1024} KB"
        )
    }

    /** What a browser downloads: one archive holding exactly the two files the desktop writes. */
    @Test
    fun `the browser's zip holds the image and the sidecar, byte for byte`() {
        val files = write(DataLayer.HEIGHTMAP)
        val members = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(files.asZip())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                members[entry.name] = zip.readBytes()
            }
        }
        assertEquals(setOf(files.imageName, files.sidecarName), members.keys)
        assertTrue(files.image.contentEquals(members.getValue(files.imageName)))
        assertTrue(files.sidecar.contentEquals(members.getValue(files.sidecarName)))
    }

    /** The desktop's promise: one dialog, two files, named alike, in one directory. */
    @Test
    fun `the desktop writes the image and its sidecar side by side`() {
        val directory = File("build/exports/data").apply { mkdirs() }
        val destination = File(directory, "chosen-name.png")
        val sidecar = File(directory, "chosen-name.json")
        destination.delete()
        sidecar.delete()

        val result = runBlocking {
            Exporter.exportData(config, 512, destination, DataLayer.BIOMES)
        }

        assertTrue(destination.isFile, "no image at ${destination.absolutePath}")
        assertTrue(sidecar.isFile, "no sidecar beside it")
        assertEquals(destination.length() + sidecar.length(), result.bytes)
        // Renaming the image in the save dialog renames the JSON with it, so the pair stays a pair.
        assertEquals("chosen-name.json", Exporter.sidecarNameFor("chosen-name.png"))
        assertEquals(
            "cartogenesis-42-2048-heightmap.png",
            Exporter.defaultDataName(config, 2048, DataLayer.HEIGHTMAP)
        )
    }

    // ---- JPEG ------------------------------------------------------------------------------------

    /**
     * What the third picture format costs, measured rather than asserted.
     *
     * The bound is on the 99th percentile of the per-pixel worst channel, because a JPEG's error is
     * concentrated where the sharp marks are — the river lines, the borders, the coastline — and a
     * single worst pixel on a ringing edge says nothing about a map. Quality 30 is encoded from the
     * same rendered bitmap and measured beside it, so the bar is shown to be a bar: if quality 30
     * passed it, quality 90 passing it would mean nothing.
     */
    @Test
    fun `JPEG at the shipped quality stays inside its stated bound, where a low quality does not`() {
        val pixels = MapRasterizer.rasterize(world, RenderOptions())
        val bitmap = MapImage.toBitmap(world, RenderOptions(), pixels)

        val reference = org.jetbrains.skia.Image.makeFromBitmap(bitmap)
            .encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG, quality = 100)!!.bytes
        val shipped = Exporter.encodeJpeg(bitmap, ExportFormat.JPEG_QUALITY)
        val poor = Exporter.encodeJpeg(bitmap, 30)
        // The same encoder asked for everything it has, which is the "matched quality" half of the
        // comparison the chip's small print used to be written around.
        val lossless = Exporter.encodeJpeg(bitmap, 100)
        bitmap.close()

        // WebP of the same picture, at the quality the application ships it at.
        val webp = org.jetbrains.skia.Image.makeFromEncoded(reference)
            .encodeToData(org.jetbrains.skia.EncodedImageFormat.WEBP, quality = 100)!!.bytes

        // The JPEG has to open in an ordinary reader at the size that was asked for, which is what
        // `ImageIO` is asked here; the fidelity below goes through Skia for all of them, so that no
        // part of the comparison is a difference between two decoders.
        val opened = decode(shipped)
        assertEquals(world.width, opened.width, "the JPEG did not decode at the export size")
        assertEquals(world.height, opened.height)

        val truth = decodeThroughSkia(reference)
        val decoded = decodeThroughSkia(shipped)
        val shippedDrift = percentile99(truth, decoded)
        val poorDrift = percentile99(truth, decodeThroughSkia(poor))
        val webpDrift = percentile99(truth, decodeThroughSkia(webp))

        println("JPEG the PNG of the same view is ${reference.size / 1024} KB")
        println(
            "JPEG q${ExportFormat.JPEG_QUALITY} ${shipped.size / 1024} KB, ${spread(truth, decoded)}"
        )
        println("JPEG q30 ${poor.size / 1024} KB, ${spread(truth, decodeThroughSkia(poor))}")
        println("JPEG q100 ${lossless.size / 1024} KB, ${spread(truth, decodeThroughSkia(lossless))}")
        println("JPEG WebP q100 ${webp.size / 1024} KB, ${spread(truth, decodeThroughSkia(webp))}")

        assertTrue(
            shippedDrift <= MAX_JPEG_DRIFT,
            "JPEG drifted $shippedDrift of 255 at the 99th percentile, past $MAX_JPEG_DRIFT"
        )
        assertTrue(
            poorDrift > MAX_JPEG_DRIFT,
            "quality 30 also stayed inside the bound, so the bound proves nothing"
        )
        // The bound above as a relation rather than a constant, which is the version that survives
        // the map changing under it: the compatibility format may cost a little more than the one
        // it stands in for, and not much more.
        assertTrue(
            shippedDrift <= webpDrift + OVER_WEBP,
            "JPEG drifted $shippedDrift where WebP on the same picture drifted $webpDrift"
        )
        assertTrue(
            poorDrift > webpDrift + OVER_WEBP,
            "quality 30 was also within $OVER_WEBP of WebP, so the relation proves nothing"
        )
        // What the chip is allowed to say about size, held to the measurement rather than to the
        // intuition: at the qualities this application ships, the JPEG is the smaller file.
        assertTrue(
            shipped.size < webp.size,
            "the JPEG (${shipped.size}) was not smaller than the WebP (${webp.size})"
        )
        assertTrue(
            lossless.size > webp.size,
            "JPEG at 100 (${lossless.size}) was not larger than WebP (${webp.size})"
        )
    }

    private companion object {
        /**
         * How far a colour channel may drift at the 99th percentile before the JPEG chip's small
         * print stops being honest.
         *
         * The 99th and not the 99.9th `ExportSmokeTest` holds WebP to, because this is the coarser
         * question: is the compatibility format in the same band as the one it substitutes for.
         * The bound is WebP's own figure on the same picture with seven of room, and quality 30,
         * which the test above measures beside it, has to miss it.
         *
         * Re-derived once when the river pen changed, and the reason is worth keeping: the
         * figures were 53 for WebP
         * and 55 for JPEG when the widest river on a 512 sheet was a five-pixel channel, and are
         * 61 and 63 now that it is a 1.2-pixel one. A thin line is nearly all edge — almost every
         * pixel of it is a partial blend rather than a run of one colour — and a lossy codec pays
         * for that. Both formats moved by the same eight, which is what says the picture changed
         * and not the relation between them; quality 30 is 74 and still misses the bound.
         *
         * Re-derived a second time when channels began where the water can cut one (R1): the
         * drawn network is chosen by discharge rather than by a count, so the sheet carries
         * different thin lines, and WebP's figure on the new picture is 69 against 61. The bound
         * follows it by the same rule, WebP plus seven: JPEG at quality 90 reads 72 and sits
         * inside it, quality 30 reads 82 and still misses it.
         *
         * Re-derived a third time when the sheet stopped drawing every traced course (X1c). The
         * map now carries a fifth of the river line it did, so there is a fifth of the thin ink a
         * lossy codec pays for, and **every figure fell rather than the relation between them**:
         * WebP 36 against 69, JPEG at quality 90 **38**, at quality 30 **53**. At 76 both sat
         * inside the bound and the control clause — that a poor quality must miss it — failed,
         * which is the bar saying it had stopped being a bar. **45** is halfway between the two
         * measured drifts, which is the only place a bar whose whole meaning is that one passes
         * and the other does not can honestly sit; the relation against WebP below is the half of
         * this guard that survives the picture changing, and it did not move.
         */
        const val MAX_JPEG_DRIFT = 45

        /** And the same bound as a relation, measured against WebP in the same run. */
        const val OVER_WEBP = 5
    }

    // ---- reading files back ----------------------------------------------------------------------

    /**
     * How many colours the PNG's own `PLTE` chunk carries.
     *
     * Read out of the file rather than off `IndexColorModel.mapSize`, which is 256 whatever the
     * chunk says: the JDK's reader pads the table out to the full range of the bit depth. The
     * question being asked is about the file, so the file is what is measured.
     */
    private fun paletteEntriesIn(png: ByteArray): Int {
        var at = 8 // past the eight-byte signature
        while (at + 8 <= png.size) {
            val length = ((png[at].toInt() and 0xFF) shl 24) or
                ((png[at + 1].toInt() and 0xFF) shl 16) or
                ((png[at + 2].toInt() and 0xFF) shl 8) or
                (png[at + 3].toInt() and 0xFF)
            if (png.decodeToString(at + 4, at + 8) == "PLTE") return length / 3
            at += 12 + length
        }
        error("the PNG carries no palette")
    }

    /** Whether every index the map actually uses has an entry in [legend]. */
    private fun namesEveryOneOf(present: Set<Int>, legend: List<LegendEntry>): Boolean =
        present.all { index -> legend.any { it.index == index } }

    private fun decode(bytes: ByteArray): BufferedImage =
        ImageIO.read(ByteArrayInputStream(bytes))
            ?: error("ImageIO could not read the ${bytes.size}-byte image back")

    /**
     * The same, through Skia, which is the only decoder here that reads WebP.
     *
     * `ImageIO` has no WebP reader at all, so a comparison of the three picture formats against one
     * another has to go through one decoder that reads all three, or half of it would be measuring
     * the difference between two decoders instead of between two encoders.
     */
    private fun decodeThroughSkia(bytes: ByteArray): BufferedImage {
        val image = org.jetbrains.skia.Image.makeFromEncoded(bytes)
        val bitmap = org.jetbrains.skia.Bitmap()
        bitmap.allocPixels(
            org.jetbrains.skia.ImageInfo.makeS32(
                image.width, image.height, org.jetbrains.skia.ColorAlphaType.UNPREMUL
            )
        )
        check(image.readPixels(bitmap)) { "Skia could not read the image back" }
        val raw = bitmap.readPixels() ?: error("no pixels")
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        for (i in 0 until image.width * image.height) {
            val o = i * 4
            out.setRGB(
                i % image.width,
                i / image.width,
                ((raw[o + 2].toInt() and 0xFF) shl 16) or
                    ((raw[o + 1].toInt() and 0xFF) shl 8) or
                    (raw[o].toInt() and 0xFF)
            )
        }
        bitmap.close()
        image.close()
        return out
    }

    /** The whole distribution of the per-pixel worst channel, so the report carries more than a bar. */
    private fun spread(truth: BufferedImage, seen: BufferedImage): String {
        val counts = IntArray(256)
        var total = 0L
        for (y in 0 until truth.height) {
            for (x in 0 until truth.width) {
                val a = truth.getRGB(x, y)
                val b = seen.getRGB(x, y)
                var worst = 0
                for (shift in 0..16 step 8) {
                    val delta = abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF))
                    if (delta > worst) worst = delta
                }
                counts[worst]++
                total += worst
            }
        }
        val pixels = truth.width.toLong() * truth.height
        fun at(share: Double): Int {
            val target = (pixels * share).toLong()
            var seenSoFar = 0L
            for (drift in 0 until 256) {
                seenSoFar += counts[drift]
                if (seenSoFar >= target) return drift
            }
            return 255
        }
        return "mean %.2f, median %d, 90th %d, 99th %d, 99.9th %d, worst %d".format(
            total.toDouble() / pixels, at(0.5), at(0.9), at(0.99), at(0.999),
            counts.indexOfLast { it > 0 }
        )
    }

    /** The worst channel per pixel, at the 99th percentile over the whole image. */
    private fun percentile99(truth: BufferedImage, seen: BufferedImage): Int {
        val counts = IntArray(256)
        for (y in 0 until truth.height) {
            for (x in 0 until truth.width) {
                val a = truth.getRGB(x, y)
                val b = seen.getRGB(x, y)
                var worst = 0
                for (shift in 0..16 step 8) {
                    val delta = abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF))
                    if (delta > worst) worst = delta
                }
                counts[worst]++
            }
        }
        val target = (truth.width.toLong() * truth.height * 0.99).toInt()
        var seenSoFar = 0
        for (drift in 0 until 256) {
            seenSoFar += counts[drift]
            if (seenSoFar >= target) return drift
        }
        return 255
    }

    private fun parse(sidecar: ByteArray): JsonObject =
        Json.parseToJsonElement(sidecar.decodeToString()).jsonObject

    private fun JsonObject.text(field: String): String =
        getValue(field).jsonPrimitive.content

    private fun JsonObject.int(field: String): Int =
        getValue(field).jsonPrimitive.content.toInt()

    private fun JsonObject.number(field: String): Double =
        getValue(field).jsonPrimitive.content.toDouble()

    private class LegendEntry(val index: Int, val name: String, val colour: String, val cells: Int)

    private fun JsonObject.legend(): List<LegendEntry> =
        getValue("legend").jsonArray.map {
            val entry = it.jsonObject
            LegendEntry(
                entry.int("index"),
                entry.text("name"),
                entry.text("colour"),
                entry.int("cells")
            )
        }

    /** The sidecar prints six decimal places, so equality is to within half of the last one. */
    /**
     * The same, for a figure a reader gets by multiplying a *printed* one by 32,767 levels.
     *
     * The sidecar states six decimal places, so a value read out of the file carries up to half a
     * millionth of slack before it is multiplied by anything, and 32,767 of those is 0.016 — which
     * is larger than [assertClose]'s relative bar the moment the range is measured in kilometres.
     * That is not a disagreement between the two numbers in the file, it is the file's own stated
     * precision, so the bar is the file's own stated precision. Sixteen millimetres over a ten
     * kilometre range is not a figure anybody importing a terrain can act on differently.
     */
    private fun assertScaledFromPrinted(expected: Double, found: Double) {
        val slack = 0.5e-6 * DataExports.LEVELS_PER_SIDE
        assertTrue(
            abs(expected - found) <= slack,
            "expected $expected, the sidecar says $found, past the $slack m the sidecar's own" +
                " six decimal places allow"
        )
    }

    private fun assertClose(expected: Double, found: Double) {
        assertTrue(
            abs(expected - found) <= 1e-6 * maxOf(1.0, abs(expected)),
            "expected $expected, the sidecar says $found"
        )
    }
}
