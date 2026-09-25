package com.cartogenesis.desktop

import com.cartogenesis.cartography.MapStyle
import com.cartogenesis.cartography.MapView
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.SheetGeometry
import com.cartogenesis.ui.MapImage
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.Rect
import java.io.File

/**
 * Every picture on cartogenesis.com, rendered from the engine at assembly time.
 *
 * The landing page used to carry one hand-made poster, and a hand-made poster is a promise the
 * page cannot keep: a picture drawn by an older renderer goes on claiming things about the
 * generator long after the generator has stopped doing them. So the site holds no image files of
 * its own. `:web:assembleSite` runs this, this generates one world and cuts every figure on the
 * page out of it, and the release that changes what a coastline looks like changes the coastline
 * the page shows.
 *
 * Generating [SEED] at [GRID_CELLS] is a minute of arithmetic and a figure is a window onto that
 * map's sheet, cropped at the sheet's own pixels. Cropping rather than scaling is the point: every
 * mark the renderer makes is sized in *output pixels*, so a 1:1 window shows the pen the renderer
 * actually draws with, while a downscaled whole map shows a thinner one that exists nowhere.
 *
 * A figure is one window read one way, and each is one card's picture on the page; a pipeline step
 * that needs a wider stretch of ground is the one exception to 1:1 (see [Figure.reduction]). The
 * cards that compare readings — the three styles, the four data views — share one window per row,
 * which is what makes them the same ground: there is one window for the row's pictures to be cut
 * from. The page names each picture in its card's own heading rather than in lettering drawn into
 * the file, so nothing here sets type. See docs/DESIGN_LEDGER.md, Site 3 and Site 4, for what the
 * page has asked for and when.
 *
 * It has to run on the deploy runner, which is Linux with no graphics card and no display. Nothing
 * here asks for either: the rasteriser is called on its processor path, and Skia only ever writes
 * into memory.
 */
object SiteImagery {

    /**
     * The author's world, and the settings he generates with: 62% ocean, fourteen plates, twelve
     * realms asked for. (The generator settles on fourteen; the number in the panel is a target
     * that coastlines and watersheds are allowed to overrule.)
     */
    const val SEED = 718106L
    const val SEA_LEVEL = 0.62f
    const val PLATES = 14
    const val REALMS = 12

    /**
     * The world is generated on a grid 2048 cells square, drawn on its true-shape sheet — 4096
     * pixels by 2048 ([com.cartogenesis.cartography.SheetGeometry]) — and the figures are cut out
     * of that sheet at 1:1.
     *
     * 2048 is the size the desktop build exports at by default and the resolution the page's claims
     * are about. It is also what makes a 1600-wide crop possible without inventing pixels.
     */
    const val GRID_CELLS = 2048

    /**
     * How hard the WebP encoder is asked to work, where nothing says otherwise.
     *
     * The poster this replaces was 1600x800 in 153 KB, which is the quality a reader of this page
     * has already accepted, and 72 lands the Atlas band at 134 KB — the same picture, slightly
     * lighter. Skia exposes no lossless WebP path, so this is a choice about how much to keep
     * rather than whether to lose any. (A Pen and ink figure would resist it: hachures and a
     * stippled sea are high-frequency noise, and measured 317 KB at this quality, 255 at 40.)
     */
    const val WEBP_QUALITY = 72

    /**
     * A window onto the map, in the pixels of the 2048 world's 4096 by 2048 sheet.
     *
     * The windows are written down rather than searched for. A "find the most interesting band"
     * heuristic would move the picture every time the generator changed, which is the one property
     * a fixed identity must not have: the page should show the same country next release, drawn
     * better. These were chosen by rendering the whole map and looking at it (`-Pcontact`).
     *
     * [x] may run past the right-hand edge. The map is a cylinder — the sheet's last pixel column
     * and its first are neighbours — so a window that crosses the seam is an ordinary window. (The
     * widest river on this world lives on the seam, and was a figure for a day.)
     *
     * What a window *holds* is the generator's to decide, and the paragraphs below describe what
     * each held when it was chosen. A pipeline change moves the coastline under a fixed window —
     * which is the whole point of rendering the page from the engine — so every window is looked at
     * again before a release ships, and moved if it no longer shows what it was picked for. The
     * coordinates here were re-picked when the sheet took the world's true shape, from the contact
     * sheet of that sheet.
     */
    data class Window(val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * The band across the top of the page: the hero.
     *
     * A 2:1 window on the southern half of the northern continent, which carries in one frame
     * everything the page claims — the snow-capped range down its middle, rivers draining both
     * flanks to a south coast of bays and an estuary, lakes in the lowlands either side, and the
     * continent's east coast turning north at the right-hand edge.
     *
     * Drawn in `MapStyle.NATURAL`, for the colour. The window did not have to move with the style:
     * what it holds is what that style has most to say about — forest against a dry belt, a range
     * between them, and a cobalt sea around it.
     */
    val BAND = Window(1152, 320, 1600, 800)

    /**
     * The window the three physical styles are compared in: square, 640 on a side.
     *
     * The northern continent's south-western lowlands, chosen so that the three styles are asked
     * the questions they answer differently. It holds the dry tan belt that crosses them, the
     * green country either side of it, the western flank of the range at its right-hand edge and
     * the south coast below — which is where the styles part company, because Atlas and Natural
     * tint the ground by its climate as well as its height and Schoolroom tints it by height
     * alone. Three styles agreeing about a green coast would prove nothing. Which window each
     * release has used, and why each moved, is in docs/DESIGN_LEDGER.md.
     */
    val STYLES_WINDOW = Window(1152, 512, 640, 640)

    /**
     * The window the four data layers are read in: 480 wide and 600 tall.
     *
     * Taller than wide because the layers are latitude-organised — temperature bands, the trades
     * and the westerlies, a gyre turning between them — so a window that spans more latitude shows
     * more of what there is to see. 600 rows of 2048 is a little over fifty degrees of latitude,
     * and these are the fifty that carry the most: the belt where the westerlies give way to the
     * trades runs through the upper half of the frame, so the Winds card shows the two blowing
     * opposite ways rather than one of them filling the picture. Land above and water below, the
     * south coast of the northern continent across the middle, because two of the four layers draw
     * nothing on land and the other two draw nothing at sea.
     */
    val LAYERS_WINDOW = Window(2048, 512, 480, 600)

    /**
     * One picture on the page: a window of the map, read one way.
     *
     * [file] is the name the page references, so renaming one here renames it there, and
     * `SiteAssemblyTest` is what notices when only one of the two moves. The four data views ignore
     * [style] — their colours carry meaning, and a prettier ramp would make them lie (see
     * `MapView.styled`) — so `MapStyle.ATLAS` stands there as the renderer's own default and
     * decides nothing.
     *
     * [reduction] is how many sheet pixels go into each of the picture's, one way: 1 is the 1:1 cut
     * the class describes, and every comparison card is cut that way. A pipeline step whose work is
     * only legible over a wider stretch of ground — plates a continent across, realms a coast long —
     * is cut from a window that many times wider and taller and averaged down to the card's size,
     * by halving, so a power of two.
     */
    data class Figure(
        val file: String,
        val window: Window,
        val view: MapView,
        val style: MapStyle = MapStyle.ATLAS,
        val reduction: Int = 1,
        val quality: Int = WEBP_QUALITY
    ) {
        init {
            require(reduction >= 1 && reduction and (reduction - 1) == 0) {
                "$file: a reduction of $reduction is not a power of two, so it cannot be halved to"
            }
            require(window.width % reduction == 0 && window.height % reduction == 0) {
                "$file: a ${window.width}x${window.height} window does not divide by $reduction"
            }
        }

        val options: RenderOptions get() = RenderOptions(view = view, style = style)

        /** The picture's own width in pixels, which the page's `width` attribute states. */
        val width: Int get() = window.width / reduction

        /** The picture's own height in pixels, which the page's `height` attribute states. */
        val height: Int get() = window.height / reduction

        /**
         * What the reading is called in the application: the style's own label for a map drawn in
         * a style, the view's own label for a data view. A comparison card's heading on the page
         * is this word, so the card says what the toolbar says over the same picture.
         */
        val readingName: String get() = if (view == MapView.FANTASY) style.label else view.label
    }

    /** The hero: the band across the top of the page, and the picture a link to the page previews. */
    val HERO = Figure("natural.webp", BAND, MapView.FANTASY, MapStyle.NATURAL)

    /**
     * The three physical styles, one card each, all cut from [STYLES_WINDOW].
     *
     * Atlas, Schoolroom and Natural because they are the three that tint the ground differently for
     * a reason a reader can be told in one line: height and climate, height alone, and the colours
     * a satellite sees.
     */
    val STYLE_CARDS: List<Figure> = listOf(MapStyle.ATLAS, MapStyle.SCHOOLROOM, MapStyle.NATURAL)
        .map { style -> Figure("style-${style.name.lowercase()}.webp", STYLES_WINDOW, MapView.FANTASY, style) }

    /** The four data views, one card each, all cut from [LAYERS_WINDOW]. */
    val LAYER_CARDS: List<Figure> =
        listOf(MapView.TEMPERATURE, MapView.CURRENTS, MapView.WIND, MapView.RAINFALL)
            .map { view -> Figure("layer-${view.name.lowercase()}.webp", LAYERS_WINDOW, view) }

    /**
     * The six steps of "How a world is made", each pictured by the view that shows what that step
     * makes, in the page's order. Every picture is 480 by 320, the shape of a card in a row of
     * three, and each window was chosen off the full sheets for what its step has to show and for
     * what it must not: the ice caps on this world are drawn with a straight edge down a column and
     * a fan of rays from one point, which rule 13 of docs/CONVENTIONS.md exists to keep off the map,
     * so no window here holds one.
     *
     * - Plates and mountains: the tectonic plates over the continent's east coast and the island
     *   arc beyond it, where continental and oceanic plates meet round the coast, at a quarter.
     * - Erosion: the elevation view of the eastern lobe, where valleys are cut into the flanks and a
     *   river builds a small delta at the coast, at 1:1.
     * - Seas and ice: the eastern ridge in Natural, the shelf a pale band round every coast and a
     *   lake in the ridge, at a half.
     * - Climate: the same ground in the biomes view, snow on the ridge, forest, grassland and a
     *   dry patch in the lee, at a half.
     * - Rivers and lakes: the east coast of the southern continent in Natural, rivers gathering off
     *   the hills to the sea past two lakes, at 1:1. Not the northern lowlands, which have more
     *   river: the dry belt crosses them as a band ruled straight along a row, and at card size it
     *   reads as a line drawn on the picture.
     * - Realms and peoples: the political view of the west coast, where several realms meet along
     *   its rivers and watersheds, at a half.
     */
    val STEP_CARDS: List<Figure> = listOf(
        Figure("step-plates.webp", Window(2080, 0, 1920, 1280), MapView.PLATES, reduction = 4),
        Figure("step-erosion.webp", Window(2200, 560, 480, 320), MapView.ELEVATION),
        Figure(
            "step-seas.webp", Window(2750, 300, 960, 640), MapView.FANTASY, MapStyle.NATURAL,
            reduction = 2
        ),
        Figure("step-climate.webp", Window(2750, 300, 960, 640), MapView.BIOMES, reduction = 2),
        Figure(
            "step-rivers.webp", Window(2990, 1720, 480, 320), MapView.FANTASY, MapStyle.NATURAL
        ),
        Figure("step-realms.webp", Window(400, 300, 960, 640), MapView.POLITICAL, reduction = 2)
    )

    /** Every picture the page shows, in the order it shows them. */
    val FIGURES: List<Figure> = listOf(HERO) + STEP_CARDS + STYLE_CARDS + LAYER_CARDS

    @JvmStatic
    fun main(args: Array<String>) {
        val outputDir = File(args.firstOrNull() ?: "build/site-imagery").absoluteFile
        outputDir.mkdirs()
        // Whatever an earlier run left here would be published as though the page still asked
        // for it: assembleSite copies every WebP in this directory. So the directory starts empty,
        // and a figure the page has stopped showing stops being rendered and stops being shipped.
        // SiteAssemblyTest caught exactly that the first time the figure list shrank.
        outputDir.listFiles()?.forEach { it.delete() }

        // A contact sheet is the whole map at half size with a coordinate grid over it and the
        // page's windows outlined, written beside the figures so that whoever next moves a window
        // can read coordinates off a picture instead of guessing. Off by default: it is another
        // rasterisation and a megabyte of PNG per reading, and a deploy has no use for either.
        val contact = System.getProperty("cartogenesis.siteImagery.contact") == "true"

        val started = System.currentTimeMillis()
        val world = generate()
        val generated = System.currentTimeMillis()
        println(
            "SITE IMAGERY seed=$SEED ${world.width}x${world.height} generated in " +
                "${(generated - started) / 1000}s (rivers=${world.rivers.rivers.size}, " +
                "realms=${world.nations.nations.size})"
        )

        // One rasterisation per distinct reading rather than one per figure: the hero and the
        // Natural style card are the same reading of the same world, and a 2048 sheet is 32 MB and
        // most of a second.
        val sheets = Sheets(world)
        var total = 0L
        try {
            for (figure in FIGURES) {
                val bytes = write(sheets, figure, outputDir)
                total += bytes
                println(
                    "  ${figure.file.padEnd(24)} ${figure.width}x${figure.height}  " +
                        "$bytes bytes  (${figure.readingName}, window " +
                        "${figure.window.width}x${figure.window.height} at " +
                        "${figure.window.x},${figure.window.y} reduced ${figure.reduction}, " +
                        "quality ${figure.quality})"
                )
            }
            if (contact) writeContactSheets(sheets, outputDir)
        } finally {
            sheets.close()
        }

        val finished = System.currentTimeMillis()
        println(
            "SITE IMAGERY ${FIGURES.size} figures, $total bytes total, " +
                "${(finished - started) / 1000}s including generation"
        )
    }

    /** [SEED] at [GRID_CELLS], with the settings the page names. */
    fun generate(): WorldMap {
        val base = WorldGenConfig(seed = SEED, width = 512, height = 512, seaLevel = SEA_LEVEL)
        val config = base.copy(
            tectonics = base.tectonics.copy(plateCount = PLATES),
            nations = base.nations.copy(nationCount = REALMS)
        ).atResolution(GRID_CELLS, GRID_CELLS)
        return WorldGenerationEngine.generateBlocking(config)
    }

    /**
     * The whole world drawn each way the page asks for, kept until the run is over.
     *
     * The two-argument rasterisation is the processor's own path. The accelerated one is a
     * suspending call that wants a graphics device, and the runner has neither a device nor a
     * display; the processor is the reference path in any case.
     */
    private class Sheets(private val world: WorldMap) : AutoCloseable {
        private val bitmaps = LinkedHashMap<RenderOptions, Bitmap>()
        private val images = LinkedHashMap<RenderOptions, Image>()

        fun of(options: RenderOptions): Image = images.getOrPut(options) {
            val bitmap = MapImage.toBitmap(world, options)
            bitmaps[options] = bitmap
            Image.makeFromBitmap(bitmap)
        }

        /** The sheet's width in pixels: where a window wraps round the seam. */
        val mapWidth: Int get() = SheetGeometry.of(world).widthPixels

        /** The sheet's height in pixels. */
        val mapHeight: Int get() = SheetGeometry.of(world).heightPixels

        override fun close() {
            images.values.forEach { it.close() }
            bitmaps.values.forEach { it.close() }
        }
    }

    /** Cuts one figure out of its sheet and writes it as WebP. Returns the size on disk. */
    private fun write(sheets: Sheets, figure: Figure, outputDir: File): Long {
        val window = figure.window
        // The map wraps east-west and nowhere else: a window off the top or the bottom of the
        // sheet would be cut with a band of nothing in it.
        require(window.y >= 0 && window.y + window.height <= sheets.mapHeight) {
            "${figure.file}: rows ${window.y} to ${window.y + window.height} are not all on a " +
                "sheet ${sheets.mapHeight} pixels tall"
        }
        var picture = Bitmap().apply {
            allocPixels(ImageInfo.makeS32(window.width, window.height, ColorAlphaType.PREMUL))
        }
        drawWindow(Canvas(picture), sheets.of(figure.options), window, sheets.mapWidth)
        var reduced = 1
        while (reduced < figure.reduction) {
            val half = halved(picture)
            picture.close()
            picture = half
            reduced *= 2
        }

        val image = Image.makeFromBitmap(picture)
        val data = image.encodeToData(EncodedImageFormat.WEBP, figure.quality)
            ?: error("Skia could not encode ${figure.file} as WebP")
        val destination = File(outputDir, figure.file)
        destination.writeBytes(data.bytes)

        image.close()
        picture.close()
        return destination.length()
    }

    /**
     * [source] at half its width and height, each pixel the mean of the four it covers.
     *
     * Bilinear sampling at exactly half scale reads every destination pixel from the corner the
     * four source pixels share, so it weighs them equally: a box filter, which is what a reduction
     * of a map wants — a realm's colour averaged with its neighbour's at a border and nowhere else,
     * and no ringing at a coast.
     */
    private fun halved(source: Bitmap): Bitmap {
        val width = source.width / 2
        val height = source.height / 2
        val half = Bitmap().apply {
            allocPixels(ImageInfo.makeS32(width, height, ColorAlphaType.PREMUL))
        }
        val image = Image.makeFromBitmap(source)
        Canvas(half).drawImageRect(
            image,
            Rect.makeWH(source.width.toFloat(), source.height.toFloat()),
            Rect.makeWH(width.toFloat(), height.toFloat()),
            FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE),
            null,
            true
        )
        image.close()
        return half
    }

    /**
     * Copies [window] out of [image] at 1:1, in one piece or two.
     *
     * The map wraps in longitude, so a window may begin near the right-hand edge and finish past
     * it. That is not an edge case to be avoided: the widest river on this world runs over the
     * seam, and a window chosen by eye should be free to follow it. Two draws, and the reader
     * cannot tell.
     */
    private fun drawWindow(canvas: Canvas, image: Image, window: Window, mapWidth: Int) {
        val left = ((window.x % mapWidth) + mapWidth) % mapWidth
        val beforeSeam = minOf(window.width, mapWidth - left)
        canvas.drawImageRect(
            image,
            Rect.makeXYWH(
                left.toFloat(), window.y.toFloat(),
                beforeSeam.toFloat(), window.height.toFloat()
            ),
            Rect.makeXYWH(0f, 0f, beforeSeam.toFloat(), window.height.toFloat())
        )
        if (beforeSeam < window.width) {
            val afterSeam = window.width - beforeSeam
            canvas.drawImageRect(
                image,
                Rect.makeXYWH(0f, window.y.toFloat(), afterSeam.toFloat(), window.height.toFloat()),
                Rect.makeXYWH(
                    beforeSeam.toFloat(), 0f, afterSeam.toFloat(), window.height.toFloat()
                )
            )
        }
    }

    /** The contact sheet's fine grid, in the render's own pixels. */
    private const val GRID_MINOR_PIXELS = 128

    /** Every fourth line, so a coordinate can be counted off without counting to sixteen. */
    private const val GRID_MAJOR_PIXELS = 512

    /** A third of white: visible over land and over deep ocean, and over neither in the way. */
    private val GRID_MINOR_INK = 0x55FFFFFF.toInt()

    /** Two thirds of a red the map's own palettes never use, so the count line cannot be lost. */
    private val GRID_MAJOR_INK = 0xAAFF3355.toInt()

    /** Brass, for the windows the page actually shows. */
    private val WINDOW_OUTLINE_INK = 0xFFC9A227.toInt()

    /**
     * The whole map at half size with a grid, one sheet per reading the page uses.
     *
     * The grid is in the map sheet's own pixels rather than the contact sheet's: a light line every
     * [GRID_MINOR_PIXELS] map pixels and a heavier one every [GRID_MAJOR_PIXELS], so a window can be
     * read off the picture and typed straight into a [Window] without arithmetic. The windows the
     * page uses are outlined in brass on every sheet, so a proposed change can be judged against the
     * map before it is rendered.
     */
    private fun writeContactSheets(sheets: Sheets, outputDir: File) {
        val readings = FIGURES.distinctBy { it.view to it.style }
        val mapWidth = sheets.mapWidth
        val mapHeight = sheets.mapHeight
        val contactWidth = mapWidth / 2
        val contactHeight = mapHeight / 2
        for (reading in readings) {
            val name = "contact-${reading.view.name.lowercase()}-${reading.style.name.lowercase()}"
            val sheet = Bitmap().apply {
                allocPixels(ImageInfo.makeS32(contactWidth, contactHeight, ColorAlphaType.PREMUL))
            }
            val canvas = Canvas(sheet)
            canvas.drawImageRect(
                sheets.of(reading.options),
                Rect.makeWH(mapWidth.toFloat(), mapHeight.toFloat()),
                Rect.makeWH(contactWidth.toFloat(), contactHeight.toFloat())
            )
            val minorLine = Paint().apply { color = GRID_MINOR_INK; strokeWidth = 1f }
            val majorLine = Paint().apply { color = GRID_MAJOR_INK; strokeWidth = 1.5f }
            var mapPixel = 0
            while (mapPixel <= maxOf(mapWidth, mapHeight)) {
                val onSheet = mapPixel / 2f
                val paint = if (mapPixel % GRID_MAJOR_PIXELS == 0) majorLine else minorLine
                if (mapPixel <= mapWidth) {
                    canvas.drawLine(onSheet, 0f, onSheet, contactHeight.toFloat(), paint)
                }
                if (mapPixel <= mapHeight) {
                    canvas.drawLine(0f, onSheet, contactWidth.toFloat(), onSheet, paint)
                }
                mapPixel += GRID_MINOR_PIXELS
            }
            val outline = Paint().apply {
                color = WINDOW_OUTLINE_INK; strokeWidth = 3f; mode = PaintMode.STROKE
            }
            FIGURES.map { it.window }.distinct().forEach { window ->
                canvas.drawRect(
                    Rect.makeXYWH(
                        window.x / 2f, window.y / 2f, window.width / 2f, window.height / 2f
                    ),
                    outline
                )
            }
            val png = Image.makeFromBitmap(sheet).encodeToData(EncodedImageFormat.PNG)!!
            File(outputDir, "$name.png").writeBytes(png.bytes)
            sheet.close()
            println(
                "  contact sheet $name.png (grid: light $GRID_MINOR_PIXELS, " +
                    "heavy $GRID_MAJOR_PIXELS, map pixels)"
            )
        }
    }
}
