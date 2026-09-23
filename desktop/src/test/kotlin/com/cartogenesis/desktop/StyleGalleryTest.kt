package com.cartogenesis.desktop

import com.cartogenesis.cartography.MapStyle
import com.cartogenesis.cartography.MapView
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.ui.MapImage
import com.cartogenesis.worldgen.SharedWorlds
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Every style, on one world, written out to be looked at.
 *
 * A style is a claim about appearance and there is no numeric test for whether a map looks like
 * aged vellum. What can be checked without eyes is that each style actually produces a *different*
 * picture — a style that silently falls back to the default is the likely failure here, and it
 * would be invisible in any test that only asked whether rendering succeeded.
 */
@ExtendWith(SharedWorldsCheck::class)
class StyleGalleryTest {

    @Test
    fun `every style renders, and none of them look alike`() {
        val dir = File("build/styles").apply { mkdirs() }
        val world = SharedWorlds.world(
            WorldGenConfig(seed = 234475L, width = 512, height = 512).atResolution(1024, 1024)
        )

        val fingerprints = mutableMapOf<MapStyle, Int>()
        MapStyle.entries.forEach { style ->
            val bitmap = MapImage.toBitmap(
                world,
                RenderOptions(view = MapView.FANTASY, style = style)
            )
            val data = Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)!!
            File(dir, "style-${style.name.lowercase()}.png").writeBytes(data.bytes)

            // Cheap content hash: enough to tell two styles apart, and it costs nothing.
            val pixels = bitmap.readPixels()!!
            var hash = 17
            for (k in pixels.indices step 997) hash = hash * 31 + pixels[k]
            fingerprints[style] = hash
            bitmap.close()
            println("STYLE ${style.label}: ${style.detail}")
        }

        val distinct = fingerprints.values.toSet().size
        assertTrue(
            distinct == MapStyle.entries.size,
            "two styles rendered identically: $fingerprints"
        )
        println("STYLE wrote ${MapStyle.entries.size} maps to ${dir.absolutePath}")
    }

    /**
     * The colour-blind style, on its own, at the size the spec asks to look at it in.
     *
     * Two of the fifteen views are what the style is *for* — the fantasy view, where the land ramp
     * and the flat sea are the whole of it, and the political view, which is the worst case colour
     * vision has in this application and the only one with a realm set behind it — so those two are
     * written out to be reviewed. The other thirteen are rendered as well and thrown away, because
     * a style that cannot describe a view fails by throwing rather than by looking wrong.
     */
    @Test
    fun `the colour-blind style renders every view`() {
        val dir = File("build/styles").apply { mkdirs() }
        val world = SharedWorlds.world(
            WorldGenConfig(seed = 234475L, width = 512, height = 512)
        )

        MapView.entries.forEach { view ->
            val clear = MapImage.toBitmap(world, RenderOptions(view = view, style = MapStyle.CLEAR))
            if (view == MapView.FANTASY || view == MapView.POLITICAL) {
                val data = Image.makeFromBitmap(clear).encodeToData(EncodedImageFormat.PNG)!!
                File(dir, "clear-${view.name.lowercase()}.png").writeBytes(data.bytes)
            }
            clear.close()
        }
        println(
            "STYLE wrote the Colour-blind fantasy and political views at 512 to ${dir.absolutePath}"
        )
    }

    /**
     * The diagnostic views ignore the style: `MapStyle` says so, and a diagnostic view's colours
     * mean a height, a temperature, a plate, read against a legend a style does not change.
     *
     * Every view the style does not choose the colours of ([MapView.styled] false, Elevation and
     * Biomes among them) is drawn as the reader gets it, under Atlas and under Colour-blind — as
     * far apart as two styles in the list get — and then again with each pass alone that could
     * carry the style: the coast's ink, the relief, the lakes and the rivers. Where the two styles
     * differ, the view and the passes that make them differ are what the clause records. They do
     * differ today, through all four (Audit III, F-C2: under Pen and ink the lakes of Elevation
     * and Biomes are painted as blank paper), and the clause is kept running as a known failure by
     * view and pass.
     */
    @Test
    fun `the diagnostic views ignore the style`() {
        val world = SharedWorlds.world(
            WorldGenConfig(seed = 234475L, width = 512, height = 512)
        )
        val passes = listOf<Pair<String, (RenderOptions) -> RenderOptions>>(
            "the palette" to { it },
            "the coast" to { it.copy(showCoastline = true) },
            "the relief" to { it.copy(showHillshade = true) },
            "the lakes" to { it.copy(showLakes = true) },
            "the rivers" to { it.copy(showRivers = true) }
        )
        val reached = ArrayList<String>()
        MapView.entries.filter { !it.styled }.forEach { view ->
            val bare = RenderOptions(
                view = view, showCoastline = false, showHillshade = false, showLakes = false, showRivers = false
            )
            val through = passes.filter { (_, on) -> !samePicture(world, on(bare).copy(style = MapStyle.CLEAR), on(bare).copy(style = MapStyle.ATLAS)) }
                .map { it.first }
            val whole = samePicture(world, RenderOptions(view = view, style = MapStyle.CLEAR), RenderOptions(view = view, style = MapStyle.ATLAS))
            if (through.isNotEmpty() || !whole) reached.add("${view.name}: " + through.joinToString().ifEmpty { "passes together" })
        }
        println("STYLE the colour-blind style reaches the diagnostic views: " + reached.joinToString("; ").ifEmpty { "none" })
        KnownFailures.expect(
            DIAGNOSTIC_VIEWS_STYLED,
            "ELEVATION: the coast, the relief, the lakes, the rivers; BIOMES: the coast, the relief, the lakes, the rivers; " +
                "TEMPERATURE: the coast, the relief, the rivers; SUMMER_TEMPERATURE: the coast, the relief, the rivers; " +
                "WINTER_TEMPERATURE: the coast, the relief, the rivers; RAINFALL: the coast, the relief, the rivers; " +
                "SUMMER_RAINFALL: the coast, the relief, the rivers; WINTER_RAINFALL: the coast, the relief, the rivers; " +
                "PLATES: the coast, the relief, the rivers; CURRENTS: the coast, the relief; WIND: the coast, the relief; " +
                "NORMALS: the coast, the rivers"
        ) {
            if (reached.isNotEmpty()) {
                throw RecordedViolation(
                    "the colour-blind style changes ${reached.size} diagnostic views: " + reached.joinToString("; "),
                    reached.joinToString("; ")
                )
            }
        }
    }

    /** Whether [first] and [second] draw [world] to the same pixels, as the window shows them. */
    private fun samePicture(world: WorldMap, first: RenderOptions, second: RenderOptions): Boolean {
        val one = MapImage.toBitmap(world, first)
        val other = MapImage.toBitmap(world, second)
        val same = one.readPixels()!!.contentEquals(other.readPixels()!!)
        one.close()
        other.close()
        return same
    }

    /**
     * The pen-and-ink style, at the two sizes it has to be drawn by the same pen at.
     *
     * The engraving's claim is that a mark is a fixed number of pixels whatever the sheet, so the
     * two details below are the review that matters: a square of a 512 render and a square four
     * times the side of a 2048 render, each shown at its own pixels. What a reader should see is
     * the *same nib* in both — hachures a hair wide and a dozen pixels long — with four times as
     * many of them across the larger crop, the way a bigger plate carries more of the country
     * rather than a bigger picture of less of it. `PenAndInkTest` measures the same thing; this is
     * what it looks like.
     *
     * The 2048 world is the author's own probe — 62% ocean, fourteen plates, twelve realms — which
     * is a rougher, more crowded world than the gallery's and therefore the harder case for a
     * drawing that leaves flat ground blank.
     */
    @Test
    fun `the engraved style, at 512 and at 2048`() {
        val dir = File("build/styles").apply { mkdirs() }

        val small = SharedWorlds.world(
            WorldGenConfig(seed = 234475L, width = 512, height = 512)
        )
        write(dir, "f9-engraved-512", small, RenderOptions(style = MapStyle.PEN_AND_INK))
        write(
            dir, "f9-engraved-political-512", small,
            RenderOptions(view = MapView.POLITICAL, style = MapStyle.PEN_AND_INK)
        )
        val ink = RenderOptions(style = MapStyle.PEN_AND_INK)
        writeDetail(dir, "f9-engraved-detail-512", small, ink, DETAIL_CELLS) {
            small.sea.isLand[it]
        }

        val base = WorldGenConfig(seed = 718106L, width = 512, height = 512, seaLevel = 0.62f)
        val large = SharedWorlds.world(
            base.copy(
                tectonics = base.tectonics.copy(plateCount = 14),
                nations = base.nations.copy(nationCount = 12)
            ).atResolution(2048, 2048)
        )
        write(dir, "f9-engraved-2048", large, RenderOptions(style = MapStyle.PEN_AND_INK))
        writeDetail(
            dir, "f9-engraved-detail-2048", large, RenderOptions(style = MapStyle.PEN_AND_INK),
            DETAIL_CELLS * 4
        ) { large.sea.isLand[it] }
        // The lakes, from the larger world and blown up: a lake is a small thing and the ruling
        // inside it is a hairline, so at its own pixels there is nothing a reviewer can judge.
        writeDetail(
            dir, "f9-engraved-lakes-2048", large, RenderOptions(style = MapStyle.PEN_AND_INK),
            LAKE_DETAIL_CELLS, magnify = 2
        ) { large.rivers.lakes.isLake(it) }
        println("STYLE wrote the engraved renders to ${dir.absolutePath}")
    }

    private fun write(dir: File, name: String, world: WorldMap, options: RenderOptions) {
        val bitmap = MapImage.toBitmap(world, options)
        val data = Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)!!
        File(dir, "$name.png").writeBytes(data.bytes)
        bitmap.close()
    }

    /**
     * A square of the map at its own pixels, so the strokes can be looked at rather than the
     * continents.
     *
     * [side] is in this render's own cells, so a crop of 1024 from a 2048 render covers the same
     * share of the sheet as one of 256 from a 512 render: those two files are the comparison.
     */
    private fun writeDetail(
        dir: File,
        name: String,
        world: WorldMap,
        options: RenderOptions,
        side: Int,
        magnify: Int = 1,
        wanted: (Int) -> Boolean
    ) {
        val bitmap = MapImage.toBitmap(world, options)
        val whole = Image.makeFromBitmap(bitmap)
        val out = side * magnify
        val crop = Bitmap().apply {
            allocPixels(ImageInfo.makeS32(out, out, ColorAlphaType.PREMUL))
        }
        val corner = densestSquare(world, side, wanted)
        Canvas(crop).drawImageRect(
            whole,
            Rect.makeXYWH(
                corner.first.toFloat(), corner.second.toFloat(), side.toFloat(), side.toFloat()
            ),
            Rect.makeWH(out.toFloat(), out.toFloat())
        )
        val data = Image.makeFromBitmap(crop).encodeToData(EncodedImageFormat.PNG)!!
        File(dir, "$name.png").writeBytes(data.bytes)
        crop.close()
        whole.close()
        bitmap.close()
    }

    /**
     * Where a square of this side holds the most of what [wanted] picks out.
     *
     * So a detail is of the thing it is meant to show — strokes rather than open sea, or a lake
     * rather than the coast beside it — without anybody hand-picking a coordinate that stops being
     * right the day the generator changes.
     */
    private fun densestSquare(world: WorldMap, side: Int, wanted: (Int) -> Boolean): Pair<Int, Int> {
        val step = (side / 4).coerceAtLeast(1)
        var best = Pair(0, 0)
        var bestCount = -1
        var top = 0
        while (top + side <= world.height) {
            var left = 0
            while (left + side <= world.width) {
                var count = 0
                var y = top
                while (y < top + side) {
                    var x = left
                    while (x < left + side) {
                        if (wanted(y * world.width + x)) count++
                        x += 4
                    }
                    y += 4
                }
                if (count > bestCount) {
                    bestCount = count
                    best = Pair(left, top)
                }
                left += step
            }
            top += step
        }
        return best
    }

    private companion object {
        /** The known failure the diagnostic-view clause records. */
        const val DIAGNOSTIC_VIEWS_STYLED =
            "Audit III F-C2: the diagnostic views take the style through its coast, relief, lakes and rivers"

        /** Side of the detail crop in 512-render cells: half the sheet each way. */
        const val DETAIL_CELLS = 256

        /** Side of the lake crop, in the 2048 render's cells: small enough to see the ruling. */
        const val LAKE_DETAIL_CELLS = 320
    }
}
