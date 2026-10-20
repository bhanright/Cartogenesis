package com.cartogenesis.desktop

import com.cartogenesis.cartography.MapRasterizer
import com.cartogenesis.cartography.WorldCodec
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
 * its own. `:desktop:assembleSite` runs this, this generates one world and cuts every figure on the
 * page out of it, and the release that changes what a coastline looks like changes the coastline
 * the page shows.
 *
 * Generating [SEED] at [GRID_ROWS] is a few minutes of arithmetic and a figure is a window onto
 * that map's sheet, cropped at the sheet's own pixels. Cropping rather than scaling is the point: every
 * mark the renderer makes is sized in *output pixels*, so a 1:1 window shows the pen the renderer
 * actually draws with, while a downscaled whole map shows a thinner one that exists nowhere.
 *
 * A figure is one window read one way. Most are one card's picture on the page; the rest are the
 * band round the world the page opens on, at two sizes, the nine styles the comparison slider
 * fetches when picked, the data frame's relief and the layers laid over it, the whole world under
 * the lens at a quarter and at full size, the patch drawn in relief (whose heights are written
 * beside the pictures, [RELIEF_HEIGHTS_FILE]), five other worlds whole, and the picture a link to
 * the page previews.
 * Wider ground is halved rather than cropped wider (see [Figure.reduction]). Pictures that are
 * compared share one window — the six steps, the twelve styles, the four data views — which is
 * what makes them the same ground: there is one window for the row's pictures to be cut from.
 * The page names each picture in its own words rather than in lettering drawn into the file, so
 * nothing here sets type. See docs/DESIGN_LEDGER.md, Site 3, Site 4, Site 5a and Site 6, for
 * what the page has asked for and when.
 *
 * It has to run on the deploy runner, which is Linux with no graphics card and no display. Nothing
 * here asks for either: the rasteriser is called on its processor path, and Skia only ever writes
 * into memory.
 */
object SiteImagery {

    /**
     * The world the page's figures are cut from, chosen for its range, its coast and its climates:
     * a long ice-capped plateau range with lakes in its ice, a dry plateau on one side of it and
     * forested slopes on the other, a bay, and a cold south where the winds and the currents turn
     * both ways. Its settings are the ones below: 62% ocean, fourteen plates, twelve realms asked
     * for. (The generator settles on fourteen; the number in the panel is a target that coastlines
     * and watersheds are allowed to overrule.)
     */
    const val SEED = 42L
    const val SEA_LEVEL = 0.62f
    const val PLATES = 14
    const val REALMS = 12

    /**
     * The world is made at the size "2048", as the application names sizes: 2048 rows and twice as
     * many columns ([WorldCodec.COLUMNS_PER_ROW]), 4096 by 2048 cells, square on the ground and
     * 9.8 km a side on the Earth-sized planet. Its sheet
     * ([com.cartogenesis.cartography.SheetGeometry]) is a cell to a pixel, 4096 by 2048, and the
     * figures are cut out of that sheet at 1:1.
     *
     * 2048 is the largest size every desktop build makes and the resolution the page's claims are
     * about. It is also what makes a 1600-wide crop possible without inventing pixels.
     */
    const val GRID_ROWS = 2048

    /** [rows] rows and [WorldCodec.COLUMNS_PER_ROW] columns for each, as the application's size chips make a world. */
    private fun WorldGenConfig.atRows(rows: Int): WorldGenConfig =
        atResolution(WorldCodec.COLUMNS_PER_ROW * rows, rows)

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
     * A window onto the map, in the pixels of the 2048 world's 4096 by 2048 sheet, which are its
     * cells.
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
     * coordinates here were picked from the half-size sheets of the candidate seeds, windows laid
     * over each, when the page's world moved to [SEED]; a seed makes a different world on a
     * different grid (docs/DESIGN_LEDGER.md, Q6), so they are only the right ground at
     * [GRID_ROWS] rows and these settings.
     */
    data class Window(val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * The world the page's opening shows, and a link to the page previews: seed 1, made as the
     * application makes a world opened with only a seed and then taken to 2048 (the generator's
     * defaults, brought to [GRID_ROWS] rows the way the panel's size chips take a world), so the
     * page's link to it, `/app/?seed=1`, opens the same world at the reader's own size.
     *
     * Not the page's own world, [SEED], because the opening was chosen when that world was another
     * seed whose every band of 800 rows carried a grid-shaped mark the page's first picture must
     * not: an ice cap's straight edges, dry belts ruled along rows, or a subduction coast running
     * straight down a column. Chosen by the maintainer from three strips measured across 21 worlds
     * for straight runs, comb and land (docs/DESIGN_LEDGER.md and docs/TODO.md, Site 5c). Every
     * other figure on the page, except the seed reel, is cut from [SEED]'s world.
     */
    const val BAND_SEED = 1L

    /** The settings [BAND_SEED]'s world is made with: the defaults, taken to [GRID_ROWS] rows. */
    fun bandConfig(): WorldGenConfig = WorldGenConfig(seed = BAND_SEED).atRows(GRID_ROWS)

    /**
     * The window the page's opening starts from, and the picture a link to the page previews, on
     * [BAND_SEED]'s sheet.
     *
     * A 2:1 window on a continent's snow-capped range, its rivers draining to coasts on both
     * sides, lakes in the lowlands and the shelf and the ocean round it: height, water, climate
     * and sea in one frame, which is what the page is about.
     *
     * Its rows matter most, since [WORLD_BAND] is these rows the whole way round the world: rows
     * 848 to 1,648 hold no ice flat and no belt ruled along a row. Of every run on a coast, a
     * shelf break or an ice edge that stays within 12 km of its chord for more than 403 km (the
     * Himalayan front's straightest stretch), they held two when the window was chosen on cells
     * twice as wide as tall, fewer than any other 800 rows of 21 worlds; on square cells they
     * hold five, all within 61 km of the bar, and one belt edge. The same window was kept when the
     * cells became square, by the maintainer's eye over two others (docs/DESIGN_LEDGER.md, Q6):
     * the seed makes the same country there. It starts at column 3,264, so the preview's first
     * 1,600 columns cross the sheet's seam, which the map does not have: the world wraps east and
     * west. Drawn in `MapStyle.SATELLITE`, the application's default.
     */
    val BAND = Window(3264, 848, 1600, 800)

    /**
     * The window the map styles are compared in: 600 wide and 400 tall, at 1:1.
     *
     * A snow-capped plateau range crossing the frame from the lower left to the upper right, with
     * lakes standing in its ice; dry, tan hills on its north-western side and forested hills cut
     * by rivers on its south-eastern; a bay with an inlet in the south-west; and the shelf falling
     * to the deep sea off the south-eastern coast. Three quarters land, the rest water, and the
     * ground runs from ice to forest to desert hills: height, water, climate and sea in one frame,
     * which is what the thirteen styles part company over.
     */
    val STYLES_WINDOW = Window(1650, 800, 600, 400)

    /**
     * The window the four data layers are read in: 480 wide and 600 tall, inside
     * [DATA_FRAME_WINDOW].
     *
     * Taller than wide because the layers are latitude-organised — temperature bands, the trades
     * and the westerlies, a gyre turning between them — so a window that spans more latitude shows
     * more of what there is to see: 600 rows of 2048 is a little over fifty degrees. Land and
     * water both, a forested north and the south's ice cap round a gulf, because two of the four
     * layers draw nothing on land and the other two draw nothing at sea.
     */
    val LAYERS_WINDOW = Window(DATA_FRAME_LEFT_PIXELS + LAYERS_PIXELS_INTO_FRAME, DATA_FRAME_TOP_PIXELS, 480, 600)

    /**
     * Which of the page's three kinds of world a figure is cut from: the page's own world, [SEED]
     * at [GRID_ROWS] rows with the page's settings; the opening's, [BAND_SEED] at [GRID_ROWS] rows
     * with the generator's defaults; or one of the seed reel's, at [REEL_ROWS] rows with the
     * defaults. A seed alone does not say which: the reel's seed 42 and the page's world are both
     * seed 42, and a seed makes a different world at a different size.
     */
    enum class Source { SITE, OPENING, REEL }

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
     *
     * [showWater] off draws the map without its rivers and lakes, which is how the stage before the
     * water is routed is shown: the same sheet, with only the step's own work taken off it.
     */
    data class Figure(
        val file: String,
        val window: Window,
        val view: MapView,
        val style: MapStyle = MapStyle.ATLAS,
        val reduction: Int = 1,
        val quality: Int = WEBP_QUALITY,
        val showWater: Boolean = true,
        val cut: Cut = Cut.WHOLE,
        val seed: Long = SEED,
        val source: Source = Source.SITE
    ) {
        init {
            require(source != Source.SITE || seed == SEED) { "$file: the page's world is seed $SEED, not $seed" }
            require(source != Source.OPENING || seed == BAND_SEED) { "$file: the opening's world is seed $BAND_SEED, not $seed" }
            require(reduction >= 1 && reduction and (reduction - 1) == 0) {
                "$file: a reduction of $reduction is not a power of two, so it cannot be halved to"
            }
            require(window.width % reduction == 0 && window.height % reduction == 0) {
                "$file: a ${window.width}x${window.height} window does not divide by $reduction"
            }
        }

        /**
         * How the application would draw it. A layer cut from its reading to lie over another
         * picture leaves out the coastline, which the picture under it already carries.
         */
        val options: RenderOptions
            get() = RenderOptions(
                view = view, style = style, showRivers = showWater, showLakes = showWater,
                showCoastline = cut.keepsCoastline
            )

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

    /**
     * How much of a reading a figure keeps.
     *
     * Most figures are a reading as the application draws it. The data frame's layers are laid
     * one over another on the page and faded in and out over the relief, so each keeps only its
     * own part and leaves the rest of the sheet clear. The application draws a map in two passes,
     * one colour per cell and then marks over the cells at their own width (river courses, a flow
     * view's arrows, the coastline), so the marks come away as a transparent layer for nothing: the
     * same overlay drawn over a clear raster. The realm borders do not: the application lays them
     * into the cells as a blend of ink and ground, so there is no layer of them to take (see
     * docs/DESIGN_LEDGER.md, Site 5b). [keepsCoastline] is false for a layer that lies over a
     * picture which already has one.
     */
    enum class Cut(val keepsCoastline: Boolean) {
        /** The reading as the application draws it: its cells and every mark over them. */
        WHOLE(true),
        /** The reading's cells alone, without the marks drawn over them. */
        GROUND(true),
        /** The whole reading over land; the sea left clear, where the view carries nothing. */
        LAND(true),
        /** The reading's cells over the sea and its marks; the land left clear. */
        SEA_AND_MARKS(false),
        /** Only the marks drawn over the cells, on a clear sheet: a flow view's arrows. */
        MARKS(false),
        /** The lakes' cells and the river courses, on a clear sheet. */
        WATER(false)
    }

    /**
     * The picture a link to the page previews: [BAND] in the Natural style, named by the page's
     * `og:image`. The page itself does not draw it; its opening is [WORLD_BAND], whose first
     * stretch is this window.
     */
    val HERO = Figure(
        "natural.webp", BAND, MapView.FANTASY, MapStyle.NATURAL, seed = BAND_SEED, source = Source.OPENING
    )

    /**
     * Every map style the application offers, each cut from [STYLES_WINDOW]: the comparison
     * slider's thirteen pictures, in the application's own order.
     */
    val STYLE_PICTURES: List<Figure> = MapStyle.entries
        .map { style -> Figure("style-${style.name.lowercase()}.webp", STYLES_WINDOW, MapView.FANTASY, style) }

    /**
     * The three styles that have a card each, the same files the slider shows.
     *
     * Atlas, Schoolroom and Satellite because they are the three that colour the ground differently
     * for a reason a reader can be told in one line: height and climate, height alone, and the
     * ground as seen from orbit, which is the application's default and so what every other map on
     * the page is drawn in.
     */
    val STYLE_CARDS: List<Figure> = listOf(MapStyle.ATLAS, MapStyle.SCHOOLROOM, MapStyle.SATELLITE)
        .map { style -> STYLE_PICTURES.single { it.style == style } }

    /** The four data views, one card each, all cut from [LAYERS_WINDOW]. */
    val LAYER_CARDS: List<Figure> =
        listOf(MapView.TEMPERATURE, MapView.CURRENTS, MapView.WIND, MapView.RAINFALL)
            .map { view -> Figure("layer-${view.name.lowercase()}.webp", LAYERS_WINDOW, view) }

    /** Where the data frame's top-left corner lies on the sheet, in pixels. */
    private const val DATA_FRAME_LEFT_PIXELS = 1500
    private const val DATA_FRAME_TOP_PIXELS = 1440

    /**
     * How far inside the data frame's left edge [LAYERS_WINDOW] begins, in pixels: the frame is 800
     * wide and the cards 480, so 160 leaves 160 either side.
     */
    private const val LAYERS_PIXELS_INTO_FRAME = 160

    /**
     * The data frame's window: 800 by 600 at 1:1, holding [LAYERS_WINDOW] [LAYERS_PIXELS_INTO_FRAME]
     * columns in from its left.
     *
     * The same ground as the four data cards and more of it either side, so the frame is wide
     * enough to be read as one picture on a computer: a forested north with its rivers, a gulf, and
     * the south's ice cap with fjords along its edge, the ocean off the west and a snowy range
     * along the east coast. Of the world's ground it is the best for the layers: the temperature
     * runs from warm in the north to cold in the south, the winds blow east in the north and west
     * in the south, a gyre turns in the gulf, and the rain falls on the north-eastern forest and
     * leaves the ground dry elsewhere. 62% land, so the layers that keep land and the layers that
     * keep sea each have ground to cover.
     */
    val DATA_FRAME_WINDOW = Window(DATA_FRAME_LEFT_PIXELS, DATA_FRAME_TOP_PIXELS, 800, 600)

    /**
     * The data frame's pictures, bottom to top as the page lays them: the relief, which is the
     * ground the application draws its winds over (the land shaded and tinted by height, the sea
     * flat), then the four layers the cards name, then the rivers and lakes. (No realm borders,
     * though the frame's land holds several realms: the engine blends a border into the cells it
     * crosses, so there is no layer of them to cut, see [Cut].)
     * Temperature is whole, since the view says something at sea as on land; rainfall keeps the
     * land and the currents the sea, each leaving clear where its view draws only a placeholder
     * colour; the winds are their arrows alone.
     */
    val DATA_FRAME: List<Figure> = listOf(
        Figure("data-relief.webp", DATA_FRAME_WINDOW, MapView.WIND, showWater = false, cut = Cut.GROUND),
        Figure("data-temperature.webp", DATA_FRAME_WINDOW, MapView.TEMPERATURE, showWater = false),
        Figure("data-rainfall.webp", DATA_FRAME_WINDOW, MapView.RAINFALL, showWater = false, cut = Cut.LAND),
        Figure(
            "data-currents.webp", DATA_FRAME_WINDOW, MapView.CURRENTS, showWater = false,
            cut = Cut.SEA_AND_MARKS
        ),
        Figure("data-wind.webp", DATA_FRAME_WINDOW, MapView.WIND, showWater = false, cut = Cut.MARKS),
        Figure("data-rivers.webp", DATA_FRAME_WINDOW, MapView.FANTASY, MapStyle.SATELLITE, cut = Cut.WATER)
    )

    /**
     * The whole world in the Satellite style at a quarter of the sheet's pixels each way, 1024 by
     * 512: the picture the zoom lens is moved over.
     */
    val WORLD_WHOLE: Figure by lazy {
        Figure(
            "world-whole.webp", Window(0, 0, SHEET_WIDTH_PIXELS, SheetGeometry.of(config()).heightPixels),
            MapView.FANTASY, MapStyle.SATELLITE, reduction = 4
        )
    }

    /**
     * The same at the sheet's own pixels, 4096 by 2048: what the lens shows under the pointer,
     * fetched only when a reader first uses the lens. At the bands' quality, because it is read
     * moving under a lens and never still at its full size.
     */
    val WORLD_FULL: Figure by lazy {
        Figure(
            "world-full.webp", WORLD_WHOLE.window, MapView.FANTASY, MapStyle.SATELLITE,
            quality = WORLD_BAND_QUALITY
        )
    }

    /**
     * The patch of ground the page draws in relief: 640 by 400 at 1:1 of the plateau range's
     * south-western half. Its ice-covered crest runs along the patch from the lower left to the
     * upper right with the valleys glaciers cut down both flanks, dry, ridged hills to the north,
     * forest and a drowned valley to the south-east, and the sea in two corners. Land over
     * 90% of it, and the highest ground of any window on the page, which is what a relief is for.
     */
    val RELIEF_WINDOW = Window(1190, 900, 640, 400)

    /** The patch in the Satellite style: the picture the relief is textured with, and its still. */
    val RELIEF_TEXTURE = Figure("relief-satellite.webp", RELIEF_WINDOW, MapView.FANTASY, MapStyle.SATELLITE)

    /** The file the patch's heights are published in. */
    const val RELIEF_HEIGHTS_FILE = "relief-heights.png"

    /**
     * Sheet pixels between neighboring points of the relief's mesh, both ways: two, which is two
     * cells each way. One would follow every cell, but 641 by 401 points is past the 65,536 a
     * 16-bit index reaches, which every WebGL has; two is 321 by 201 for the 640 by 400 patch,
     * 64,521, under it.
     */
    const val RELIEF_POINT_SPACING_PIXELS = 2

    /**
     * Height steps per metre in the heights file: a quarter of a metre a step, which puts 16,383
     * metres at the top of sixteen bits, above any ground the generator makes (its ruler's land
     * half is [com.cartogenesis.worldgen.model.WorldScale.highestLandMetres], 6,000, and an ice
     * sheet stands on it at most 4,776 more).
     */
    const val RELIEF_STEPS_PER_METRE = 4

    /** The relief's points across and down: one every [RELIEF_POINT_SPACING_PIXELS], both edges included. */
    val RELIEF_POINTS_ACROSS: Int get() = RELIEF_WINDOW.width / RELIEF_POINT_SPACING_PIXELS + 1
    val RELIEF_POINTS_DOWN: Int get() = RELIEF_WINDOW.height / RELIEF_POINT_SPACING_PIXELS + 1

    /**
     * The worlds of "Every seed is a world", each made the way the application makes a world it
     * is opened at with only a seed: the generator's defaults at the browser's starting size, so
     * the picture is the world the card's link opens. Five of fifteen looked at on the implicit
     * erosion's terrain (1, 7, 12, 42, 99, 123, 777, 1066, 2024, 2026, 5000, 31337, 65536, 90210
     * and 424242), picked to differ from one another and to show the fewest of the generator's
     * grid-shaped marks at the size the page draws them: a long continent pinched in the middle, a
     * branching one, an inland sea with islands, two lands across open sea, and two lands across
     * shallow bays. Seven of the others were left for an ice cap ending in a wall down a column, a
     * slab of ice with straight edges, a fan of rays, or a dry belt ruled along a row.
     *
     * Seed 42 is also the page's own world, [SEED]; the reel's is a different world, since a seed makes
     * another world at another size, which is why a figure says its [Figure.source].
     */
    val REEL_SEEDS: List<Long> = listOf(42L, 1066L, 2024L, 777L, 7L)

    /**
     * The size the reel's worlds are made at: 512 rows, 1024 by 512 cells, where a browser window
     * starts, so the pictures are the worlds the links open. It also keeps the site's build quick:
     * seconds a world, where the page's world at 2048 is minutes.
     */
    const val REEL_ROWS = 512

    /** A reel world's settings: the generator's defaults at [REEL_ROWS] rows, as a link with only a seed makes. */
    fun reelConfig(seed: Long): WorldGenConfig = WorldGenConfig(seed = seed).atRows(REEL_ROWS)

    /**
     * Each reel world whole in the Satellite style at its sheet's own pixels, 1024 by 512. The page
     * draws the first two worlds half its column wide on a computer, about 525 CSS pixels, and
     * every world the width of a phone's screen, about 340, so 1024 covers both at two device
     * pixels to the CSS pixel, where the half-size pictures of Site 5b were enlarged.
     */
    val REEL: List<Figure> by lazy {
        REEL_SEEDS.map { seed ->
            val sheet = SheetGeometry.of(reelConfig(seed))
            Figure(
                "seed-$seed.webp", Window(0, 0, sheet.widthPixels, sheet.heightPixels),
                MapView.FANTASY, MapStyle.SATELLITE, seed = seed, source = Source.REEL
            )
        }
    }

    /**
     * The one window every step of "How a world is made" is pictured in: 1040 by 560 of the sheet,
     * halved to 520 by 280.
     *
     * One window and one scale, because the page plays the six pictures in turn in one frame and
     * the land must not jump between them: what changes from frame to frame is the step's own
     * work. The plateau range whole, halved so that plates and realms have room to show: the
     * plate boundary the range stands on, the range itself with the lakes in its ice, the shelf
     * round a bay, a climate that runs from desert on the north-western side to forest on the
     * south-eastern, rivers reaching the coast, and several realms. 71% land.
     */
    val STAGE_WINDOW = Window(1300, 800, 1040, 560)

    /**
     * The six steps, each pictured by the view that shows what that step makes, in the page's order:
     * the tectonic plates; the elevation, for the land the erosion cut; Satellite, for the coast and
     * shelf the sea level drew; the biomes, for the climate; Satellite again with its rivers and lakes,
     * for the water; and the political view, for the realms. The first four are drawn without the
     * rivers and lakes, which the fifth step makes, so the water arrives in the frame when its step
     * does rather than being there from the start.
     */
    val STEP_CARDS: List<Figure> = listOf(
        Figure("step-plates.webp", STAGE_WINDOW, MapView.PLATES, reduction = 2, showWater = false),
        Figure("step-erosion.webp", STAGE_WINDOW, MapView.ELEVATION, reduction = 2, showWater = false),
        Figure(
            "step-seas.webp", STAGE_WINDOW, MapView.FANTASY, MapStyle.SATELLITE, reduction = 2,
            showWater = false
        ),
        Figure("step-climate.webp", STAGE_WINDOW, MapView.BIOMES, reduction = 2, showWater = false),
        Figure("step-rivers.webp", STAGE_WINDOW, MapView.FANTASY, MapStyle.SATELLITE, reduction = 2),
        Figure("step-realms.webp", STAGE_WINDOW, MapView.POLITICAL, reduction = 2)
    )

    /**
     * The sheet's width in pixels for the world [generate] makes, which is how wide a strip round
     * the whole world is.
     */
    val SHEET_WIDTH_PIXELS: Int get() = SheetGeometry.of(config()).widthPixels

    /**
     * How hard the encoder works on the two bands, the one picture every reader fetches as the
     * page opens.
     *
     * Lower than [WEBP_QUALITY] because the band is never read still at its own pixels: it is
     * drawn at most the height of the screen and it drifts, so the encoder's softening of a coast's
     * last pixel is not something a reader is shown, while the full band is the page's largest
     * request. See docs/DESIGN_LEDGER.md, Site 6, for the bytes measured at each quality.
     */
    const val WORLD_BAND_QUALITY = 60

    /**
     * The band the page opens on: [BAND]'s rows all the way round the world at the sheet's own
     * pixels, 4096 by 800.
     *
     * It starts where [BAND] starts, so its first 1600 columns are the link preview's picture, and
     * the settled opening, a 2:1 plate showing the band's first stretch, is the link preview's window. It
     * runs the whole circumference, so its last column is the sheet column west of its first and
     * the band joins itself end to end as it drifts. Full size because the page draws it up to the
     * height of the screen, where half its rows would be enlarged on any wide or dense one.
     */
    val WORLD_BAND: Figure by lazy {
        Figure(
            "world-band.webp", Window(BAND.x, BAND.y, SHEET_WIDTH_PIXELS, BAND.height),
            MapView.FANTASY, MapStyle.SATELLITE, quality = WORLD_BAND_QUALITY, seed = BAND_SEED,
            source = Source.OPENING
        )
    }

    /**
     * [WORLD_BAND] halved, 2048 by 400, for a narrow screen at about one device pixel to the CSS
     * pixel.
     *
     * The settled plate on a screen narrower than the page's 700-pixel breakpoint is at most 326
     * CSS pixels tall (the 700 less two 24-pixel gutters, halved), which these 400 rows cover up to
     * 1.2 device pixels to the CSS pixel; the page's `<picture>` sends every wider or denser screen
     * the full band.
     */
    val WORLD_BAND_HALF: Figure by lazy {
        Figure(
            "world-band-half.webp", WORLD_BAND.window, MapView.FANTASY, MapStyle.SATELLITE,
            reduction = 2, quality = WORLD_BAND_QUALITY, seed = BAND_SEED, source = Source.OPENING
        )
    }

    /** Every picture the page shows, in the order it shows them. */
    val FIGURES: List<Figure> by lazy {
        listOf(HERO, WORLD_BAND, WORLD_BAND_HALF) + STEP_CARDS + listOf(WORLD_WHOLE, WORLD_FULL, RELIEF_TEXTURE) +
            STYLE_PICTURES + DATA_FRAME + LAYER_CARDS + REEL
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val outputDir = File(args.firstOrNull() ?: "build/site-imagery").absoluteFile
        outputDir.mkdirs()
        // Whatever an earlier run left here would be published as though the page still asked
        // for it: assembleSite copies every WebP in this directory, and the relief's heights. So
        // the directory starts empty, and a figure the page has stopped showing stops being
        // rendered and stops being shipped.
        // SiteAssemblyTest caught exactly that the first time the figure list shrank.
        outputDir.listFiles()?.forEach { it.delete() }

        // A contact sheet is the whole map at half size with a coordinate grid over it and the
        // page's windows outlined, written beside the figures so that whoever next moves a window
        // can read coordinates off a picture instead of guessing. Off by default: it is another
        // rasterisation and a megabyte of PNG per reading, and a deploy has no use for either.
        val contact = System.getProperty("cartogenesis.siteImagery.contact") == "true"

        val started = System.currentTimeMillis()
        // The page's world is made and cut in a function of its own so that nothing here holds
        // it once its figures are written: the opening's world is as large, and with both alive
        // the run filled the task's 8 GB (the page's world alone was 3.8 GB live when measured). See
        // docs/DESIGN_LEDGER.md, S1 Satellite look, round 2.
        var total = writeSiteWorld(outputDir, contact, started)

        // The opening's world and the reel's, one at a time, each let go before the next is made.
        FIGURES.filter { it.source != Source.SITE }.groupBy { it.source to it.seed }.forEach { (kindAndSeed, figures) ->
            val (source, seed) = kindAndSeed
            val madeAt = System.currentTimeMillis()
            val otherWorld = WorldGenerationEngine.generateBlocking(if (source == Source.OPENING) bandConfig() else reelConfig(seed))
            println("SITE IMAGERY $source seed=$seed ${otherWorld.width}x${otherWorld.height} generated in ${System.currentTimeMillis() - madeAt} ms")
            val otherSheets = Sheets(otherWorld)
            try {
                total += writeFigures(otherSheets, figures, outputDir)
            } finally {
                otherSheets.close()
            }
        }

        val finished = System.currentTimeMillis()
        println(
            "SITE IMAGERY ${FIGURES.size} figures, $total bytes total, " +
                "${(finished - started) / 1000}s including generation"
        )
    }

    /**
     * Generates [SEED]'s world and writes its figures and the relief's heights, returning the
     * bytes written. The world is local to this call, so it can be collected before the next is
     * generated.
     */
    private fun writeSiteWorld(outputDir: File, contact: Boolean, started: Long): Long {
        val world = generate()
        val generated = System.currentTimeMillis()
        println(
            "SITE IMAGERY seed=$SEED ${world.width}x${world.height} generated in " +
                "${(generated - started) / 1000}s (rivers=${world.rivers.rivers.size}, " +
                "realms=${world.nations.nations.size})"
        )
        var total = 0L
        val sheets = Sheets(world)
        try {
            total += writeFigures(sheets, FIGURES.filter { it.source == Source.SITE }, outputDir)
            total += writeReliefHeights(world, outputDir)
            if (contact) writeContactSheets(sheets, outputDir)
        } finally {
            sheets.close()
        }
        return total
    }

    /**
     * Writes [figures] from [sheets] into [outputDir], one line each to the log, and returns the
     * bytes written. One rasterisation per distinct reading rather than one per figure: the
     * Satellite style's picture, the relief's still and the whole world at both sizes are one reading
     * of one world, and a 2048 sheet is 32 MB and most of a second.
     */
    private fun writeFigures(sheets: Sheets, figures: List<Figure>, outputDir: File): Long {
        var total = 0L
        for (figure in figures) {
            val bytes = write(sheets, figure, outputDir)
            total += bytes
            println(
                "  ${figure.file.padEnd(24)} ${figure.width}x${figure.height}  " +
                    "$bytes bytes  (${figure.readingName} ${figure.cut}, window " +
                    "${figure.window.width}x${figure.window.height} at " +
                    "${figure.window.x},${figure.window.y} reduced ${figure.reduction}, " +
                    "quality ${figure.quality})"
            )
        }
        return total
    }

    /** [SEED] at [GRID_ROWS] rows, with the settings the page names. */
    fun generate(): WorldMap = WorldGenerationEngine.generateBlocking(config())

    /** The settings [generate] runs: [SEED] at [GRID_ROWS] rows with the page's sea, plates and realms. */
    fun config(): WorldGenConfig {
        val base = WorldGenConfig(seed = SEED, seaLevel = SEA_LEVEL)
        return base.copy(
            tectonics = base.tectonics.withPlateCount(PLATES, base.scale),
            nations = base.nations.copy(nationCount = REALMS)
        ).atRows(GRID_ROWS)
    }

    /**
     * The whole world drawn each way the page asks for, kept until the run is over.
     *
     * The two-argument rasterisation is the processor's own path. The accelerated one is a
     * suspending call that wants a graphics device, and the runner has neither a device nor a
     * display; the processor is the reference path in any case.
     */
    private class Sheets(private val world: WorldMap) : AutoCloseable {
        private val bitmaps = LinkedHashMap<Pair<RenderOptions, Cut>, Bitmap>()
        private val images = LinkedHashMap<Pair<RenderOptions, Cut>, Image>()
        private val geometry = SheetGeometry.of(world)

        fun of(options: RenderOptions, cut: Cut = Cut.WHOLE): Image = images.getOrPut(options to cut) {
            val bitmap = when (cut) {
                Cut.WHOLE -> MapImage.toBitmap(world, options)
                Cut.GROUND -> MapImage.sheetBitmap(geometry, MapRasterizer.rasterize(world, options))
                Cut.LAND, Cut.SEA_AND_MARKS, Cut.MARKS, Cut.WATER ->
                    MapImage.toBitmap(world, options, cellsKept(options, cut))
            }
            bitmaps[options to cut] = bitmap
            Image.makeFromBitmap(bitmap)
        }

        /**
         * The reading's cells with every cell [cut] does not keep made clear, for the overlay to be
         * drawn over: the marks then land on a transparent sheet with their own edges' coverage.
         */
        private fun cellsKept(options: RenderOptions, cut: Cut): IntArray {
            val cells = MapRasterizer.rasterize(world, options)
            val land = world.sea.isLand
            val lakes = world.rivers.lakes
            for (cell in cells.indices) {
                val kept = when (cut) {
                    Cut.LAND -> land[cell]
                    Cut.SEA_AND_MARKS -> !land[cell]
                    Cut.WATER -> lakes.isLake(cell)
                    else -> false
                }
                if (!kept) cells[cell] = CLEAR
            }
            return cells
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
            // A layer leaves most of its sheet clear, so what the allocation held must not show.
            erase(CLEAR)
        }
        drawWindow(Canvas(picture), sheets.of(figure.options, figure.cut), window, sheets.mapWidth)
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
            erase(CLEAR)
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

    /** A pixel with nothing in it: transparent black, which is also its premultiplied form. */
    private const val CLEAR = 0

    /**
     * Writes the relief patch's heights beside the pictures, as [RELIEF_HEIGHTS_FILE], and returns
     * its size on disk.
     *
     * One point every [RELIEF_POINT_SPACING_PIXELS] of [RELIEF_WINDOW], both edges included, each
     * the ground's height in metres at that place on the sheet, read bilinearly between the four
     * cells round it: the land's height above the sea, a lake's water surface where it stands above
     * the ground, and the sea flat at nought, since the picture shows the water's surface and not
     * the floor under it. Stored in [RELIEF_STEPS_PER_METRE] steps as sixteen bits across the red
     * (high byte) and green (low byte) of a PNG, which every browser decodes without loss; the page
     * reads it back through a canvas.
     */
    private fun writeReliefHeights(world: WorldMap, outputDir: File): Long {
        val sheet = SheetGeometry.of(world)
        val elevation = world.sea.relativeElevation
        val land = world.sea.isLand
        val lakes = world.rivers.lakes
        val metresPerUnit = world.config.scale.highestLandMetres
        fun groundMetres(column: Int, row: Int): Float {
            val cell = elevation.clampY(row) * elevation.width + elevation.wrapX(column)
            if (!land[cell]) return 0f
            val ground = if (lakes.isLake(cell)) maxOf(elevation.data[cell], lakes.surfaceAt(cell)) else elevation.data[cell]
            return (ground * metresPerUnit).coerceAtLeast(0f)
        }
        val across = RELIEF_POINTS_ACROSS
        val down = RELIEF_POINTS_DOWN
        val bytes = ByteArray(across * down * 4)
        var highest = 0f
        for (row in 0 until down) for (column in 0 until across) {
            // The point's place in cells, measured from cell centres.
            val cellX = (RELIEF_WINDOW.x + column * RELIEF_POINT_SPACING_PIXELS).toFloat() / sheet.pixelsPerCellAcross - 0.5f
            val cellY = (RELIEF_WINDOW.y + row * RELIEF_POINT_SPACING_PIXELS).toFloat() / sheet.pixelsPerCellDown - 0.5f
            val left = kotlin.math.floor(cellX).toInt()
            val top = kotlin.math.floor(cellY).toInt()
            val east = cellX - left
            val south = cellY - top
            val metres = (groundMetres(left, top) * (1 - east) + groundMetres(left + 1, top) * east) * (1 - south) +
                (groundMetres(left, top + 1) * (1 - east) + groundMetres(left + 1, top + 1) * east) * south
            highest = maxOf(highest, metres)
            val steps = Math.round(metres * RELIEF_STEPS_PER_METRE).coerceIn(0, 0xFFFF)
            val at = (row * across + column) * 4
            // Skia's S32 is blue, green, red, alpha in memory on this platform.
            bytes[at] = 0
            bytes[at + 1] = (steps and 0xFF).toByte()
            bytes[at + 2] = (steps shr 8).toByte()
            bytes[at + 3] = 0xFF.toByte()
        }
        val bitmap = Bitmap()
        bitmap.installPixels(ImageInfo.makeS32(across, down, ColorAlphaType.OPAQUE), bytes, across * 4)
        val image = Image.makeFromBitmap(bitmap)
        val png = image.encodeToData(EncodedImageFormat.PNG) ?: error("Skia could not encode the relief's heights")
        val destination = File(outputDir, RELIEF_HEIGHTS_FILE)
        destination.writeBytes(png.bytes)
        image.close()
        bitmap.close()
        println(
            "  ${RELIEF_HEIGHTS_FILE.padEnd(24)} ${across}x$down  ${destination.length()} bytes  (heights, highest %.0f m, ".format(highest) +
                "${sheet.kilometresPerPixel} km a sheet pixel)"
        )
        return destination.length()
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
        val readings = FIGURES.filter { it.source == Source.SITE && it.cut == Cut.WHOLE }.distinctBy { it.view to it.style }
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
            FIGURES.filter { it.source == Source.SITE }.map { it.window }.distinct().forEach { window ->
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
