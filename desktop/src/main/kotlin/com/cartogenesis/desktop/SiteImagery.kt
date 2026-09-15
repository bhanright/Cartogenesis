package com.cartogenesis.desktop

import com.cartogenesis.cartography.MapStyle
import com.cartogenesis.cartography.MapView
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.ui.MapImage
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontEdging
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Typeface
import java.io.File
import kotlin.math.roundToInt

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
 * Generating [SEED] at [RENDER_PIXELS] is a minute of arithmetic and a figure is a window onto that
 * map, cropped at the render's own pixels. Cropping rather than scaling is the point: every mark
 * the renderer makes is sized in *output pixels*, so a 1:1 window shows the pen the renderer
 * actually draws with, while a downscaled whole map shows a thinner one that exists nowhere.
 *
 * A figure is one window and one or more [Panel]s — the same window read a different way in each,
 * laid out with a naming band under every panel, in the manner of a game's resolution-comparison
 * screenshot. One image rather than several, because the claim being made is that these are the
 * *same ground*, and separate pictures in a row on a page are not evidence of that: a strip cut
 * from one window is. A strip with more than one panel is published twice, once [Layout.ACROSS] and
 * once [Layout.DOWN], because the shape that carries the comparison on a desktop does not fit a
 * phone. See REALISM_PLAN.md, Site 3, for what the page has asked for and when.
 *
 * It has to run on the deploy runner, which is Linux with no graphics card and no display. Nothing
 * here asks for either: the rasteriser is called on its processor path, and Skia only ever writes
 * into memory. The band's type is the page's own, read out of the application's font resources,
 * whose directory the Gradle task passes in; a runner without them falls back to a system sans.
 */
object SiteImagery {

    /**
     * William's world, and the settings he generates with: 62% ocean, fourteen plates, twelve
     * realms asked for. (The generator settles on fourteen; the number in the panel is a target
     * that coastlines and watersheds are allowed to overrule.)
     */
    const val SEED = 718106L
    const val SEA_LEVEL = 0.62f
    const val PLATES = 14
    const val REALMS = 12

    /**
     * The world is generated at 2048 pixels square and the figures are cut out of it at 1:1.
     *
     * 2048 is the size the desktop build exports at by default and the resolution the page's claims
     * are about. It is also what makes a 1600-wide crop possible without inventing pixels.
     */
    const val RENDER_PIXELS = 2048

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
     * A window onto the map, in the 2048 render's own pixels.
     *
     * The windows are written down rather than searched for. A "find the most interesting band"
     * heuristic would move the picture every time the generator changed, which is the one property
     * a fixed identity must not have: the page should show the same country next release, drawn
     * better. These were chosen by rendering the whole map and looking at it (`-Pcontact`).
     *
     * [x] may run past the right-hand edge. The map is a cylinder — column 2047 and column 0 are
     * neighbours — so a window that crosses the seam is an ordinary window. (The widest river on
     * this world lives on the seam, at a mouth near cell (104, 1094), and was a figure for a day.)
     *
     * What a window *holds* is the generator's to decide, and the paragraphs below describe what
     * each held when it was chosen. A pipeline change moves the coastline under a fixed window —
     * which is the whole point of rendering the page from the engine — so every window is looked at
     * again before a release ships, and moved if it no longer shows what it was picked for. The
     * coordinates here were chosen against the 2.0 generator.
     */
    data class Window(val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * The band across the top of the page: the hero.
     *
     * A 2:1 window on the northern continent, which carries in one frame everything the page
     * claims — a cordillera along its spine, a river system that gathers the whole northern half,
     * lakes, a coast broken into peninsulas and a long spit, islands offshore, and the west coast
     * of the next continent with its own lake and snowfield at the right-hand edge.
     *
     * Drawn in `MapStyle.NATURAL`, for the colour. The window did not have to move with the style:
     * what it holds is what that style has most to say about — forest against dry interior, a
     * rust-coloured range between them, and a cobalt sea around it.
     */
    val BAND = Window(448, 64, 1600, 800)

    /**
     * The window the three physical styles are compared in: square, 640 on a side.
     *
     * The central continent of the 3.0 world, chosen so that the three styles are asked the
     * questions they answer differently. It holds the north coast and the strait to its south,
     * the grey range that crosses it, the rivers draining both flanks, and the dry tan interior
     * on its eastern half — which is where the styles part company, because Atlas and Natural
     * tint the ground by its climate as well as its height and Schoolroom tints it by height
     * alone. Three styles agreeing about a green coast would prove nothing. The 2.0 line's window
     * at (704, 64) sat over the same seed's old northern continent; the 3.0 pipeline redraws the
     * seed, and that square is now mostly open sea, so the window was re-picked from the contact
     * sheet before 3.0.0 shipped.
     */
    val STYLES_WINDOW = Window(660, 460, 640, 640)

    /**
     * The window the four data layers are read in: 480 wide and 600 tall.
     *
     * Taller than wide on purpose, twice over. The layers are latitude-organised — temperature
     * bands, the trades and the westerlies, a gyre turning between them — so a window that spans
     * more latitude shows more of what there is to see; and a taller panel carries a taller naming
     * band, which is what keeps the four bands' second line readable once four panels are fitted
     * into the page's column (three panels can afford a wider window, four cannot).
     *
     * 600 rows of 2048 is a little over fifty degrees of latitude, and these are the fifty that
     * carry the most: the belt where the westerlies give way to the trades runs through the upper
     * third of the frame, so the Winds panel shows the two blowing opposite ways rather than one of
     * them filling the panel. Half land and half water, because two of the four layers draw nothing
     * on land and the other two draw nothing at sea.
     */
    val LAYERS_WINDOW = Window(200, 500, 480, 600)

    /**
     * The gap between two panels of a strip, in the strip's own pixels.
     *
     * A hairline, not a gutter: the panels are edge to edge, and this only says where one stops.
     * Two pixels rather than one because the strip is fitted to the page's column at a little over
     * half size, and a single pixel would fall between two of the reader's.
     */
    const val DIVIDER = 2

    /**
     * The divider's colour: `--brass-dim` from the page.
     *
     * It has to read against both of the grounds it can land between — the Atlas style's near-black
     * sea and the Schoolroom style's pale paper — and the page's own hairline, which is what a
     * rule on the page is drawn in, disappears against the first of those.
     */
    const val DIVIDER_COLOUR = 0xFF8D7326.toInt()

    /**
     * The naming band's height, as one part in this many of the map above it.
     *
     * A ninth, so the band is a tenth of the finished panel — the proportion the comparison
     * screenshots this borrows from use, which is enough to carry a name and a line under it and
     * little enough that it reads as a caption on the picture rather than as a strip of interface.
     */
    const val BAND_IN_PANELS = 9

    /**
     * A naming band's flat tint and the colour its text is set in, both named as the page names
     * them in its own `:root`.
     *
     * Flat tints rather than a wash over the map: a band has to be legible whatever the panel above
     * it happens to be doing, and a panel is free to be a pale classroom map or a black sea. Each
     * panel of a strip takes the next tint in this order, so the bands are told apart at a glance
     * the way the reference comparison's are.
     *
     * The names are the contract with the page: `SiteAssemblyTest` resolves each against the page's
     * own custom properties, fails if the two have drifted apart, and measures the pair against
     * WCAG AA — the same bar, by the same arithmetic, that `SitePaletteContrastTest` holds the rest
     * of the page to. `--brass-dim` is a tint here and never an ink: it carries no pairing with
     * either `--parchment` or `--ink` that reaches 4.5:1.
     */
    enum class BandTint(
        val groundName: String,
        val ground: Int,
        val inkName: String,
        val ink: Int
    ) {
        SUNK("ink-sunk", 0xFF21252A.toInt(), "parchment", 0xFFF2E7CF.toInt()),
        HAIRLINE("hairline", 0xFF363C44.toInt(), "parchment", 0xFFF2E7CF.toInt()),
        OXBLOOD("oxblood", 0xFF5D0000.toInt(), "parchment", 0xFFF2E7CF.toInt()),
        BRASS("brass", 0xFFC9A227.toInt(), "ink", 0xFF121417.toInt())
    }

    /**
     * One panel of a figure: a reading of the window, and the band that names it.
     *
     * [name] is set in capitals in the band and is the word the page's `alt` text has to list;
     * [detail] is the line under it, and says what the reading *is*. Both are taken from what the
     * code already says — a style's own `detail`, a climate field's own KDoc — rather than written
     * for the page, because the page's prose is William's to write and a caption that describes a
     * map only works when a person wrote it. [detail] may be empty where there is nothing factual
     * to add.
     */
    data class Panel(
        val view: MapView,
        val style: MapStyle,
        val name: String,
        val detail: String,
        val band: BandTint,
        val options: RenderOptions = RenderOptions(view = view, style = style)
    )

    /**
     * Which way a figure's panels are laid out.
     *
     * A strip laid [ACROSS] is the comparison William asked for, and it is the wrong shape for a
     * phone: fitted to a 375px screen its bands come out five pixels tall, and left at its own size
     * it has to be scrolled sideways, which is a thing readers do not discover. So the same panels
     * are composed a second time [DOWN] and the page hands that file to a narrow screen. Two files
     * and one list of panels: the page lays out neither, so the two variants cannot come to
     * disagree about what a panel is or about what its band says.
     */
    enum class Layout { ACROSS, DOWN }

    /**
     * What the page asks for: one window, and the panels it is read in.
     *
     * The window belongs to the figure rather than to the panel, which is the invariant the whole
     * idea rests on — the panels of a strip cannot be showing different ground, because there is
     * only one window for them to be cut from.
     *
     * [file] is the name the page references, so renaming one here renames it there, and
     * `SiteAssemblyTest` is what notices when only one of the two moves. [stackedFile] is the same
     * panels laid [Layout.DOWN], or null for a figure the page shows only one way.
     */
    data class Figure(
        val file: String,
        val window: Window,
        val panels: List<Panel>,
        val stackedFile: String? = null,
        val quality: Int = WEBP_QUALITY
    ) {
        /**
         * Zero for a figure of one panel.
         *
         * A band answers "which of these is this?", and a picture with nothing beside it poses no
         * such question. It is also what keeps the hero the plain 1600x800 export it has always
         * been, and the link-preview image a scraper reads unlettered.
         */
        val bandHeight: Int
            get() = if (panels.size > 1) (window.height.toFloat() / BAND_IN_PANELS).roundToInt()
            else 0

        /** One finished panel: the window, and the band under it. */
        val panelHeight: Int get() = window.height + bandHeight

        /** Every way this figure is published, in the order the files are written. */
        val layouts: List<Layout>
            get() = if (stackedFile == null) listOf(Layout.ACROSS)
            else listOf(Layout.ACROSS, Layout.DOWN)

        fun fileFor(layout: Layout): String =
            if (layout == Layout.ACROSS) file
            else stackedFile ?: error("$file is not published stacked")

        fun width(layout: Layout): Int = when (layout) {
            Layout.ACROSS -> panels.size * window.width + (panels.size - 1) * DIVIDER
            Layout.DOWN -> window.width
        }

        fun height(layout: Layout): Int = when (layout) {
            Layout.ACROSS -> panelHeight
            Layout.DOWN -> panels.size * panelHeight + (panels.size - 1) * DIVIDER
        }
    }

    /**
     * Every figure the page shows, in the order it shows them: the hero, the three styles that draw
     * the world as a physical map, and the four layers the map is drawn from.
     *
     * A list rather than a fixed set, because the page has carried other figures before and may
     * again — four readings of one band and three annotated details, for a day. See
     * REALISM_PLAN.md, Site 2 and Site 3, for what was tried and what was kept.
     *
     * The four data views ignore the style — their colours carry meaning, and a prettier ramp would
     * make them lie (see `MapView.styled`) — so `MapStyle.ATLAS` stands there as the renderer's own
     * default and decides nothing.
     */
    val FIGURES: List<Figure> = listOf(
        Figure(
            "natural.webp", BAND,
            listOf(panelFor(MapStyle.NATURAL, BandTint.SUNK))
        ),
        Figure(
            "styles.webp", STYLES_WINDOW,
            listOf(
                panelFor(MapStyle.ATLAS, BandTint.SUNK),
                panelFor(MapStyle.SCHOOLROOM, BandTint.HAIRLINE),
                panelFor(MapStyle.NATURAL, BandTint.OXBLOOD)
            ),
            stackedFile = "styles-stacked.webp"
        ),
        Figure(
            "layers.webp", LAYERS_WINDOW,
            listOf(
                layer(MapView.TEMPERATURE, "Mean annual, in degrees Celsius", BandTint.SUNK),
                layer(
                    MapView.CURRENTS,
                    "Surface flow, warm and cold against its latitude",
                    BandTint.HAIRLINE
                ),
                layer(MapView.WIND, "The prevailing wind through the year", BandTint.OXBLOOD),
                layer(MapView.RAINFALL, "Annual rainfall", BandTint.BRASS)
            ),
            stackedFile = "layers-stacked.webp"
        )
    )

    /**
     * A style's panel, named and described in the style's own words.
     *
     * `MapStyle.label` and `MapStyle.detail` are what the application's own style picker shows, so
     * the band under the picture says what the toolbar says over it, and neither the page nor this
     * file holds a second copy to fall out of step.
     */
    private fun panelFor(style: MapStyle, band: BandTint) =
        Panel(MapView.FANTASY, style, style.label, style.detail, band)

    /**
     * A data layer's panel, named in the view's own words.
     *
     * [detail] says what the view actually draws, taken from the field's KDoc in `ClimateStage` and
     * `OceanStage`: the mean annual temperature; the ocean's surface flow, coloured by how much
     * warmer or colder the water is than the average for its latitude; the annual prevailing wind;
     * the annual rainfall.
     */
    private fun layer(view: MapView, detail: String, band: BandTint) =
        Panel(view, MapStyle.ATLAS, view.label, detail, band)

    @JvmStatic
    fun main(args: Array<String>) {
        val outputDir = File(args.firstOrNull() ?: "build/site-imagery").absoluteFile
        outputDir.mkdirs()
        // Whatever an earlier run left here would be published as though the page still asked
        // for it: assembleSite copies every WebP in this directory. So the directory starts empty,
        // and a figure the page has stopped showing stops being rendered and stops being shipped.
        // SiteAssemblyTest caught exactly that the first time the figure list shrank.
        outputDir.listFiles()?.forEach { it.delete() }

        val lettering = Lettering(args.getOrNull(1)?.let(::File))
        println("SITE IMAGERY band type: ${lettering.provenance}")

        // A contact sheet is the whole map at half size with a coordinate grid over it and the
        // page's windows outlined, written beside the figures so that whoever next moves a window
        // can read coordinates off a picture instead of guessing. A preview is a finished strip at
        // the width the page shows it, as a PNG, so it can be judged without a WebP decoder. Both
        // are off by default: they are another rasterisation each and a megabyte of PNG per sheet,
        // and a deploy has no use for either.
        val contact = System.getProperty("cartogenesis.siteImagery.contact") == "true"

        val started = System.currentTimeMillis()
        val world = generate()
        val generated = System.currentTimeMillis()
        println(
            "SITE IMAGERY seed=$SEED ${world.width}x${world.height} generated in " +
                "${(generated - started) / 1000}s (rivers=${world.rivers.rivers.size}, " +
                "realms=${world.nations.nations.size})"
        )

        // One rasterisation per distinct reading rather than one per panel: the hero and the styles
        // strip's first panel are the same reading of the same world, and a 2048 sheet is 16 MB and
        // most of a second.
        val sheets = Sheets(world)
        var total = 0L
        var files = 0
        try {
            for (figure in FIGURES) {
                for (layout in figure.layouts) {
                    val bytes = write(sheets, figure, layout, lettering, outputDir)
                    total += bytes
                    files++
                    println(
                        "  ${figure.fileFor(layout).padEnd(22)} " +
                            "${figure.width(layout)}x${figure.height(layout)}  " +
                            "${bytes / 1024} KB  (${figure.panels.joinToString { it.name }} " +
                            "${layout.name.lowercase()}, window " +
                            "${figure.window.width}x${figure.window.height} at " +
                            "${figure.window.x},${figure.window.y}, quality ${figure.quality})"
                    )
                    if (contact) writePreview(sheets, figure, layout, lettering, outputDir)
                }
            }
            if (contact) writeContactSheets(sheets, outputDir)
        } finally {
            sheets.close()
            lettering.close()
        }

        val finished = System.currentTimeMillis()
        println(
            "SITE IMAGERY ${FIGURES.size} figures in $files files, ${total / 1024} KB total, " +
                "${(finished - started) / 1000}s including generation"
        )
    }

    /** [SEED] at [RENDER_PIXELS], with the settings the page names. */
    fun generate(): WorldMap {
        val base = WorldGenConfig(seed = SEED, width = 512, height = 512, seaLevel = SEA_LEVEL)
        val config = base.copy(
            tectonics = base.tectonics.copy(plateCount = PLATES),
            nations = base.nations.copy(nationCount = REALMS)
        ).atResolution(RENDER_PIXELS, RENDER_PIXELS)
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

        val mapWidth: Int get() = world.width

        override fun close() {
            images.values.forEach { it.close() }
            bitmaps.values.forEach { it.close() }
        }
    }

    /** Draws one figure one way round and writes it as WebP. Returns the size on disk. */
    private fun write(
        sheets: Sheets,
        figure: Figure,
        layout: Layout,
        lettering: Lettering,
        outputDir: File
    ): Long {
        val name = figure.fileFor(layout)
        val strip = Bitmap().apply {
            allocPixels(
                ImageInfo.makeS32(
                    figure.width(layout), figure.height(layout), ColorAlphaType.PREMUL
                )
            )
        }
        drawStrip(Canvas(strip), sheets, figure, layout, lettering)

        val image = Image.makeFromBitmap(strip)
        val data = image.encodeToData(EncodedImageFormat.WEBP, figure.quality)
            ?: error("Skia could not encode $name as WebP")
        val destination = File(outputDir, name)
        destination.writeBytes(data.bytes)

        image.close()
        strip.close()
        return destination.length()
    }

    /**
     * The panels one after another, the hairlines between them, and a naming band under each.
     *
     * The only thing [layout] changes is which way "after" runs. Everything that decides what a
     * panel *is* — the window, the reading, the band's tint and its two lines, and the one pair of
     * type sizes the whole figure is lettered at — is computed once and used by both variants, so a
     * phone and a desktop are looking at the same figure turned a different way round.
     */
    private fun drawStrip(
        canvas: Canvas,
        sheets: Sheets,
        figure: Figure,
        layout: Layout,
        lettering: Lettering
    ) {
        val window = figure.window
        val bandHeight = figure.bandHeight
        // One size for a strip's bands rather than one per panel: a band whose own text happened to
        // be long would otherwise be set smaller than the band beside it, and the three would read
        // as three different captions instead of one comparison.
        val plan = lettering.plan(figure, window.width.toFloat(), bandHeight.toFloat())
        val across = layout == Layout.ACROSS

        var along = 0
        for ((index, panel) in figure.panels.withIndex()) {
            canvas.save()
            if (across) canvas.translate(along.toFloat(), 0f)
            else canvas.translate(0f, along.toFloat())
            drawWindow(canvas, sheets.of(panel.options), window, sheets.mapWidth)
            if (bandHeight > 0) {
                drawBand(canvas, panel, window.width, window.height, bandHeight, plan)
            }
            canvas.restore()

            along += if (across) window.width else figure.panelHeight
            if (index < figure.panels.lastIndex) {
                val rule = if (across) {
                    Rect.makeXYWH(
                        along.toFloat(), 0f, DIVIDER.toFloat(), figure.height(layout).toFloat()
                    )
                } else {
                    Rect.makeXYWH(
                        0f, along.toFloat(), figure.width(layout).toFloat(), DIVIDER.toFloat()
                    )
                }
                canvas.drawRect(rule, Paint().apply { color = DIVIDER_COLOUR })
                along += DIVIDER
            }
        }
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

    /** The flat tint under one panel, with the panel's name in it and a line saying what it is. */
    private fun drawBand(
        canvas: Canvas,
        panel: Panel,
        width: Int,
        top: Int,
        height: Int,
        plan: Lettering.Plan
    ) {
        canvas.drawRect(
            Rect.makeXYWH(0f, top.toFloat(), width.toFloat(), height.toFloat()),
            Paint().apply { color = panel.band.ground }
        )
        plan.draw(canvas, panel, width / 2f, top.toFloat(), height.toFloat())
    }

    /**
     * The band's type, and how a band's two lines are set in it.
     *
     * The faces are the application's own — IBM Plex Sans, the face the page measures things in —
     * loaded from the resource directory the Gradle task passes in so that the strip is lettered in
     * the same cut of the same typeface the page around it uses. A run without that directory
     * (someone running the class by hand, a checkout without the resources) falls back to whatever
     * sans the host has, because a strip lettered in the wrong face is still a strip and a build
     * that fails for want of a font is not.
     */
    private class Lettering(fontDir: File?) : AutoCloseable {
        private val medium: Typeface?
        private val regular: Typeface?

        /** What to print in the build log, so a deploy says which type it actually used. */
        val provenance: String

        init {
            val fromResources = face(fontDir, "plex_sans_medium.ttf") to
                face(fontDir, "plex_sans_regular.ttf")
            if (fromResources.first != null && fromResources.second != null) {
                medium = fromResources.first
                regular = fromResources.second
                provenance = "IBM Plex Sans, from ${fontDir?.absolutePath}"
            } else {
                medium = systemSans(FontStyle.BOLD)
                regular = systemSans(FontStyle.NORMAL)
                provenance = "the host's default sans (no Plex Sans at ${fontDir?.absolutePath})"
            }
        }

        private fun face(dir: File?, name: String): Typeface? {
            val file = dir?.resolve(name)?.takeIf { it.isFile } ?: return null
            return runCatching { FontMgr.default.makeFromFile(file.absolutePath) }.getOrNull()
        }

        private fun systemSans(style: FontStyle): Typeface? =
            runCatching { FontMgr.default.matchFamilyStyle("", style) }.getOrNull()

        /**
         * How one strip's bands are set: the two sizes, and where the two baselines fall.
         *
         * The name is set at two fifths of the band and the line under it at a little over a
         * quarter, then both are brought down together until the longest text in the strip fits
         * the panel with a margin — so a strip's bands share one pair of sizes whatever any one of
         * them has to say, and nothing is ever cropped or allowed to run to the panel's edge.
         */
        fun plan(figure: Figure, panelWidth: Float, bandHeight: Float): Plan {
            val margin = bandHeight * SIDE_MARGIN
            val room = panelWidth - 2 * margin
            var scale = 1f
            for (panel in figure.panels) {
                val name = panel.name.uppercase()
                scale = minOf(
                    scale, fit(name, medium, NAME_SHARE, bandHeight, room, NAME_TRACKING)
                )
                if (panel.detail.isNotEmpty()) {
                    scale = minOf(
                        scale, fit(panel.detail, regular, DETAIL_SHARE, bandHeight, room, 0f)
                    )
                }
            }
            return Plan(
                Font(medium, bandHeight * NAME_SHARE * scale).tuned(),
                Font(regular, bandHeight * DETAIL_SHARE * scale).tuned()
            )
        }

        /** How far a line has to be brought down to fit [room], or 1 if it already fits. */
        private fun fit(
            text: String,
            face: Typeface?,
            share: Float,
            bandHeight: Float,
            room: Float,
            tracking: Float
        ): Float {
            val font = Font(face, bandHeight * share)
            val width = font.measureTextWidth(text) + tracking * font.size * (text.length - 1)
            font.close()
            return if (width <= room) 1f else room / width
        }

        private fun Font.tuned(): Font = apply {
            edging = FontEdging.ANTI_ALIAS
            isSubpixel = true
        }

        override fun close() {
            medium?.close()
            regular?.close()
        }

        /** One strip's band type, ready to letter a panel with. */
        inner class Plan(private val nameFont: Font, private val detailFont: Font) {

            fun draw(canvas: Canvas, panel: Panel, centre: Float, top: Float, height: Float) {
                val paint = Paint().apply { color = panel.band.ink; mode = PaintMode.FILL }
                val name = panel.name.uppercase()
                // Measured rather than taken from the metrics: capHeight is optional in a font's
                // tables, and the ink of the actual string is what has to sit in the middle of the
                // band. A run of capitals has no descender, so its bounds are its cap height.
                val nameInk = -nameFont.measureText(name).top
                val detailInk =
                    if (panel.detail.isEmpty()) 0f else -detailFont.measureText(panel.detail).top
                val gap = if (panel.detail.isEmpty()) 0f else height * LINE_GAP
                val block = nameInk + gap + detailInk
                val nameBaseline = top + (height - block) / 2f + nameInk

                tracked(canvas, name, nameFont, paint, centre, nameBaseline, NAME_TRACKING)
                if (panel.detail.isNotEmpty()) {
                    val baseline = nameBaseline + gap + detailInk
                    val width = detailFont.measureTextWidth(panel.detail)
                    canvas.drawString(panel.detail, centre - width / 2f, baseline, detailFont, paint)
                }
            }

            /**
             * A line of capitals, centred, with air between the letters.
             *
             * Drawn glyph by glyph because Skia has no tracking on a plain `drawString`, and a run
             * of capitals set solid reads as a word rather than as a label. The kerning a font would
             * apply between a pair is lost with it, which for spaced capitals is the point.
             */
            private fun tracked(
                canvas: Canvas,
                text: String,
                font: Font,
                paint: Paint,
                centre: Float,
                baseline: Float,
                tracking: Float
            ) {
                val space = font.size * tracking
                val width = text.sumOf { font.measureTextWidth(it.toString()).toDouble() }
                    .toFloat() + space * (text.length - 1)
                var x = centre - width / 2f
                for (character in text) {
                    val glyph = character.toString()
                    canvas.drawString(glyph, x, baseline, font, paint)
                    x += font.measureTextWidth(glyph) + space
                }
            }
        }

        private companion object {
            /** The name's size, as a share of the band. */
            const val NAME_SHARE = 0.40f

            /** The line under it, small enough to be read second and large enough to be read. */
            const val DETAIL_SHARE = 0.28f

            /** Air between the letters of the name, as a share of its size. */
            const val NAME_TRACKING = 0.07f

            /** Between the two baselines' ink, as a share of the band. */
            const val LINE_GAP = 0.11f

            /**
             * Clear at each end of a band, as a share of the band's height.
             *
             * It is what a line is brought down to fit inside, and the longest line in either strip
             * — the Natural style's own description of itself, which is this file's to letter and
             * not to shorten — is what spends it.
             */
            const val SIDE_MARGIN = 0.30f
        }
    }

    /**
     * A finished figure at the width the page's column gives it, as a PNG.
     *
     * The figures ship as WebP, which is not a format a person can open everywhere, and the thing
     * that most needs looking at before a strip is published is whether its lettering survives
     * being fitted into the page — which is a question about the *displayed* size, not the file's.
     * So this is the picture the reader actually gets, at the size they get it.
     */
    private fun writePreview(
        sheets: Sheets,
        figure: Figure,
        layout: Layout,
        lettering: Lettering,
        outputDir: File
    ) {
        val drawnWidth = figure.width(layout)
        val drawnHeight = figure.height(layout)
        val column = if (layout == Layout.ACROSS) PAGE_COLUMN else PHONE_COLUMN
        val scale = column.toFloat() / drawnWidth
        val height = (drawnHeight * scale).roundToInt()
        val strip = Bitmap().apply {
            allocPixels(ImageInfo.makeS32(drawnWidth, drawnHeight, ColorAlphaType.PREMUL))
        }
        drawStrip(Canvas(strip), sheets, figure, layout, lettering)
        val full = Image.makeFromBitmap(strip)

        val shrunk = Bitmap().apply {
            allocPixels(ImageInfo.makeS32(column, height, ColorAlphaType.PREMUL))
        }
        Canvas(shrunk).drawImageRect(
            full,
            Rect.makeWH(drawnWidth.toFloat(), drawnHeight.toFloat()),
            Rect.makeWH(column.toFloat(), height.toFloat())
        )
        val name = "preview-" + figure.fileFor(layout).removeSuffix(".webp") + ".png"
        val png = Image.makeFromBitmap(shrunk).encodeToData(EncodedImageFormat.PNG)!!
        File(outputDir, name).writeBytes(png.bytes)
        println("  preview $name (${column}x$height, the figure as the page shows it)")

        shrunk.close()
        full.close()
        strip.close()
    }

    /**
     * How wide a figure is drawn on the page: the 1120px measure less its 24px gutters, and the
     * same measure on a 375px phone.
     *
     * Only [writePreview] uses them, and only to answer "is this legible where it lands?". Nothing
     * that ships is sized by either — a figure is cut at the render's own pixels and left to the
     * browser to fit.
     */
    private const val PAGE_COLUMN = 1072
    private const val PHONE_COLUMN = 327

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
     * The grid is in the *render's* coordinates rather than the sheet's: a light line every
     * [GRID_MINOR_PIXELS] map pixels and a heavier one every [GRID_MAJOR_PIXELS], so a window can be
     * read off the picture and typed straight into a [Window] without arithmetic. The windows the
     * page uses are outlined in brass on every sheet, so a proposed change can be judged against the
     * map before it is rendered.
     */
    private fun writeContactSheets(sheets: Sheets, outputDir: File) {
        val readings = FIGURES.flatMap { it.panels }
            .map { Triple(it.view, it.style, it.options) }
            .distinctBy { it.first to it.second }
        val sheetPixels = RENDER_PIXELS / 2
        for ((view, style, options) in readings) {
            val name = "contact-${view.name.lowercase()}-${style.name.lowercase()}"
            val sheet = Bitmap().apply {
                allocPixels(ImageInfo.makeS32(sheetPixels, sheetPixels, ColorAlphaType.PREMUL))
            }
            val canvas = Canvas(sheet)
            canvas.drawImageRect(
                sheets.of(options),
                Rect.makeWH(RENDER_PIXELS.toFloat(), RENDER_PIXELS.toFloat()),
                Rect.makeWH(sheetPixels.toFloat(), sheetPixels.toFloat())
            )
            val minorLine = Paint().apply { color = GRID_MINOR_INK; strokeWidth = 1f }
            val majorLine = Paint().apply { color = GRID_MAJOR_INK; strokeWidth = 1.5f }
            var mapPixel = 0
            while (mapPixel <= RENDER_PIXELS) {
                val onSheet = mapPixel / 2f
                val paint = if (mapPixel % GRID_MAJOR_PIXELS == 0) majorLine else minorLine
                canvas.drawLine(onSheet, 0f, onSheet, sheetPixels.toFloat(), paint)
                canvas.drawLine(0f, onSheet, sheetPixels.toFloat(), onSheet, paint)
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
