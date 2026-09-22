package com.cartogenesis.desktop

import com.cartogenesis.cartography.MapRasterizer
import com.cartogenesis.cartography.MapScale
import com.cartogenesis.cartography.MapSheet
import com.cartogenesis.cartography.MapStyle
import com.cartogenesis.cartography.MapView
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.RiverInk
import com.cartogenesis.cartography.RiverSelection
import com.cartogenesis.ui.MapImage
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * X1c at the size the author looks at his worlds: how much river ink 969495 draws at 2048.
 *
 * The per-merge guards are `RiverSelectionTest` in `:cartography`, which measures the same things
 * at 512. This is here for the two the author's report is about — the world he generated, and
 * whether one pane draws the same map from a 512 world, a 1024 world and a 2048 world — and for
 * the pictures, which are the only way to judge whether drawing Earth's ink leaves a map somebody
 * would want to look at.
 *
 * In the audit tier for the reason `GeneralisationRenderTest` beside it is: four worlds, three of
 * them above 512, is minutes of generation before a pixel is drawn. One world is held alive at a
 * time and reduced to its figures before the next is generated (T4's row records what three whole
 * 2048 worlds did to a hosted runner).
 */
class RiverSelectionAuditTest {

    private companion object {
        /** The author's world, on the application's defaults, at the size he generated it. */
        const val AUTHORS_SEED = 969495L
        const val AUTHORS_SIDE = 2048

        /** A laptop's pane, across, the whole world fitted into it. */
        const val PANE_PIXELS_ACROSS = 900f

        /** The window the eastern peninsula's comb of short coastal courses is looked at in. */
        const val CROP = 640

        /** The four standard seeds, drawn at the grid the web and the phone default to. */
        val STANDARD_SEEDS = listOf(7L, 42L, 1234L, 99L)

        /** See `RiverSelectionTest`: the same figure, for the same reason. */
        const val ACROSS_RESOLUTIONS_BAND = 0.05
    }

    private val outputDir = File("build/x1c-renders")

    private fun paneSheet(cellsAcross: Int): MapSheet =
        MapSheet.onScreen(PANE_PIXELS_ACROSS / cellsAcross)

    /**
     * One seed at one grid, the way the application reaches a grid above 512.
     *
     * Through [WorldGenConfig.atResolution] from the 512 settings, which is what the interface does
     * when the reader asks for a bigger world and is how the author generated the world he
     * reported: the tectonic widths are in cells and have to be carried, and constructing the
     * config at 2048 outright would leave a 512-calibrated belt four times too narrow.
     */
    private fun world(seed: Long, side: Int): WorldMap =
        WorldGenerationEngine.generateBlocking(
            WorldGenConfig(seed = seed, width = 512, height = 512).atResolution(side, side)
        )

    /**
     * One pane, three grids: the figure the whole chunk turns on.
     *
     * The world is always drawn whole, so a 900-pixel pane showing a 512, a 1024 and a 2048 world
     * is the same 1:44 000 000 map three times over, and a rule that answers to the sheet's scale
     * has to put the same amount of ink on all three. A rule that keeps a share of the traced count
     * cannot: the three grids trace three networks.
     */
    @Test
    fun `one pane draws one map from three grids`() {
        val byRule = HashMap<RiverInk, MutableMap<Int, Double>>()
        listOf(512, 1024, 2048).forEach { side ->
            val map = world(7L, side)
            val sheet = paneSheet(side)
            val chosen = RiverSelection.select(map, sheet)
            val denominator =
                MapScale.representativeFractionDenominator(map.config.scale, side, sheet.pixelsPerCell)
            RiverInk.entries.forEach { rule ->
                val drawn = RiverSelection.drawnOn(map, sheet, rule)
                val kilometres = drawn.sumOf { RiverSelection.courseKilometres(map, it) }
                byRule.getOrPut(rule) { HashMap() }[side] = kilometres / chosen.landAreaSquareKm
                println(
                    "X1C PANE seed 7 at $side, ${rule.name}: ${drawn.size} courses, " +
                        "${kilometres.round()} km, " +
                        "${(kilometres / chosen.landAreaSquareKm).sig()} km/km2 " +
                        "(${map.rivers.rivers.size} traced, ${chosen.tracedKilometres.round()} km, " +
                        "over ${(chosen.landAreaSquareKm / 1e6).oneDecimal()} M km2 of land, " +
                        "1:${(denominator / 1e6).oneDecimal()}M)"
                )
            }
        }

        RiverInk.entries.forEach { rule ->
            val densities = byRule.getValue(rule)
            val spread = (densities.values.max() - densities.values.min()) / densities.values.max()
            println(
                "X1C PANE ${rule.name} over 512/1024/2048: " +
                    densities.toSortedMap().values.joinToString(", ") { it.sig() } +
                    " km/km2, a spread of ${(spread * 100).oneDecimal()}%"
            )
            if (rule == RiverInk.EARTH_DENSITY) {
                assertTrue(
                    spread <= ACROSS_RESOLUTIONS_BAND,
                    "one pane drew ${(spread * 100).oneDecimal()}% more ink from one grid than " +
                        "another under ${rule.name}"
                )
            } else {
                assertTrue(
                    spread > ACROSS_RESOLUTIONS_BAND,
                    "the radical law agreed across grids to ${(spread * 100).oneDecimal()}%, so " +
                        "this measurement cannot tell the two rules apart"
                )
            }
        }
    }

    /**
     * The author's world: the figures, then the pictures.
     *
     * Four sheets of 969495 at 2048 — the pane's drawing and the export's, under each rule — whole
     * and cropped on the window with the most river ink in it, which at 2048 is where the comb of
     * short coastal courses the author reported lives.
     */
    @Test
    fun `969495 at 2048, the pane's drawing and the export's, before and after`() {
        outputDir.mkdirs()
        val map = world(AUTHORS_SEED, AUTHORS_SIDE)
        val options = RenderOptions(view = MapView.FANTASY, style = MapStyle.ATLAS)
        val ground = MapRasterizer.rasterize(map, options)
        val written = ArrayList<String>()

        listOf("pane" to paneSheet(AUTHORS_SIDE), "export" to MapSheet.UNGENERALISED)
            .forEach { (where, sheet) ->
                val chosen = RiverSelection.select(map, sheet)
                val wanted = RiverSelection.drawnRiverKmPerSquareKm(chosen.denominator)
                println(
                    "X1C AUTHOR $AUTHORS_SEED at $AUTHORS_SIDE, $where: " +
                        "1:${(chosen.denominator / 1e6).oneDecimal()}M, " +
                        "${chosen.drawnCount} of ${map.rivers.rivers.size} courses drawn, " +
                        "${chosen.drawnKilometres.round()} of ${chosen.tracedKilometres.round()} km " +
                        "over ${(chosen.landAreaSquareKm / 1e6).oneDecimal()} M km2 of land: " +
                        "${chosen.drawnKmPerSquareKm.sig()} km/km2 against Earth's ${wanted.sig()}, " +
                        "budget ${chosen.budgetKilometres.round()} km, " +
                        "crowding pitch ${chosen.crowdingPitchKm.round()} km"
                )
                val byTheLaw = RiverSelection.drawnOn(map, sheet, RiverInk.RADICAL_LAW)
                val lawKilometres = byTheLaw.sumOf { RiverSelection.courseKilometres(map, it) }
                println(
                    "X1C AUTHOR $AUTHORS_SEED at $AUTHORS_SIDE, $where, RADICAL_LAW: " +
                        "${byTheLaw.size} courses, ${lawKilometres.round()} km, " +
                        "${(lawKilometres / chosen.landAreaSquareKm).sig()} km/km2, " +
                        "which is ${(lawKilometres / chosen.drawnKilometres).oneDecimal()} times " +
                        "the ink"
                )
            }

        // Rule 8: the selection walks every cell of the grid once, to find out which course owns
        // the cell a tributary stops at, so it states its cost against the drawing it is part of.
        MapRasterizer.rasterize(map, options)
        var rasterMs = 0L
        repeat(3) { rasterMs += measureTimeMillis { MapRasterizer.rasterize(map, options) } }
        rasterMs /= 3
        var selectMs = 0L
        repeat(3) {
            selectMs += measureTimeMillis { RiverSelection.select(map, MapSheet.UNGENERALISED) }
        }
        selectMs /= 3
        var overlayMs = 0L
        repeat(3) {
            overlayMs += measureTimeMillis {
                MapRasterizer.overlay(map, options, MapSheet.UNGENERALISED)
            }
        }
        overlayMs /= 3
        println(
            "X1C COST $AUTHORS_SEED at $AUTHORS_SIDE: selection $selectMs ms, whole overlay " +
                "$overlayMs ms, raster $rasterMs ms — the selection is " +
                "${(100.0 * selectMs / (rasterMs + overlayMs)).oneDecimal()}% of drawing the sheet"
        )

        // The window with the most river ink under the old rule, which is where the comb is.
        val window = busiestWindow(map, options)
        listOf("pane" to paneSheet(AUTHORS_SIDE), "export" to MapSheet.UNGENERALISED)
            .forEach { (where, sheet) ->
                RiverInk.entries.forEach { rule ->
                    val bitmap = MapImage.toBitmap(map, options.copy(riverInk = rule), ground, sheet)
                    val tag = if (rule == RiverInk.RADICAL_LAW) "before" else "after"
                    written += write("$AUTHORS_SEED-$where-$tag.png", bitmap)
                    written += write(
                        "$AUTHORS_SEED-$where-$tag-peninsula.png",
                        crop(bitmap, window.first, window.second)
                    )
                    bitmap.close()
                }
            }
        written.forEach { println("X1C RENDER $it") }
    }

    /** The four standard seeds at 512, before and after, whole. */
    @Test
    fun `the four standard seeds at 512, before and after`() {
        outputDir.mkdirs()
        val written = ArrayList<String>()
        STANDARD_SEEDS.forEach { seed ->
            val map = world(seed, 512)
            val options = RenderOptions(view = MapView.FANTASY, style = MapStyle.ATLAS)
            val ground = MapRasterizer.rasterize(map, options)
            RiverInk.entries.forEach { rule ->
                val bitmap = MapImage.toBitmap(
                    map, options.copy(riverInk = rule), ground, MapSheet.UNGENERALISED
                )
                val tag = if (rule == RiverInk.RADICAL_LAW) "before" else "after"
                written += write("$seed-512-$tag.png", bitmap)
                bitmap.close()
            }
        }
        written.forEach { println("X1C RENDER $it") }
    }

    /**
     * The top-left corner of the [CROP]-square window holding the most river ink there is.
     *
     * Measured under the rule that draws every course, because the question the crop answers is
     * what the new rule *removed*, and a window chosen on what it kept would never show that.
     */
    private fun busiestWindow(map: WorldMap, options: RenderOptions): Pair<Int, Int> {
        val rivers = MapRasterizer.overlay(
            map, options.copy(riverInk = RiverInk.RADICAL_LAW), MapSheet.UNGENERALISED
        ).rivers
        var best = 0
        var at = (map.width - CROP) / 2 to (map.height - CROP) / 2
        var top = 0
        while (top <= map.height - CROP) {
            var left = 0
            while (left <= map.width - CROP) {
                val inside = rivers.count { segment ->
                    segment.fromX >= left && segment.fromX < left + CROP &&
                        segment.fromY >= top && segment.fromY < top + CROP
                }
                if (inside > best) {
                    best = inside
                    at = left to top
                }
                left += CROP / 4
            }
            top += CROP / 4
        }
        println("X1C crop window at ${at.first}, ${at.second} with $best river segments in it")
        return at
    }

    private fun write(name: String, bitmap: Bitmap): String {
        val data = Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)!!
        val file = File(outputDir, name)
        file.writeBytes(data.bytes)
        return file.absolutePath
    }

    /** A [CROP]-square window out of [bitmap] at 1:1, its top-left corner at [left], [top]. */
    private fun crop(bitmap: Bitmap, left: Int, top: Int): Bitmap {
        val source = bitmap.readPixels() ?: error("could not read the rendered map back")
        val window = Bitmap()
        window.allocPixels(ImageInfo.makeS32(CROP, CROP, ColorAlphaType.PREMUL))
        val bytes = ByteArray(CROP * CROP * 4)
        for (row in 0 until CROP) {
            val from = ((top + row) * bitmap.width + left) * 4
            source.copyInto(bytes, row * CROP * 4, from, from + CROP * 4)
        }
        window.installPixels(bytes)
        return window
    }

    private fun Double.round(): Long = this.roundToLong()

    private fun Double.oneDecimal(): String {
        val tenths = (this * 10.0).roundToLong()
        return "${tenths / 10}.${abs(tenths % 10)}"
    }

    private fun Double.sig(): String = "${(this * 1e6).roundToLong()}e-6"
}
