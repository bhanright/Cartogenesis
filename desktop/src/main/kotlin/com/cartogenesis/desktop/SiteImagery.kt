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
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
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
 * One world, cut many ways. Generating [SEED] at [SIZE] is a minute of arithmetic and every figure
 * is a window onto the same map, so the world is built once and each figure is a rasterisation of
 * it — seconds by comparison — cropped at the render's own pixels. Cropping rather than scaling is
 * the point: every mark the renderer makes is sized in *output pixels* (F9's lesson, and F10's
 * river pen), so a 1:1 window shows the pen the renderer actually draws with, while a downscaled
 * whole map shows a thinner one that exists nowhere.
 *
 * It has to run on the deploy runner, which is Linux with no graphics card and no display. Nothing
 * here asks for either: the rasteriser is called on its processor path, and Skia only ever writes
 * into memory.
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
     * The world is generated at 2048 and the figures are cut out of it at 1:1.
     *
     * 2048 is the size the desktop build exports at by default and the resolution the page's claims
     * are about. It is also what makes a 1600-wide crop possible without inventing pixels.
     */
    const val SIZE = 2048

    /**
     * How hard the WebP encoder is asked to work, where nothing says otherwise.
     *
     * The poster this replaces was 1600x800 in 153 KB, which is the quality a reader of this page
     * has already accepted, and 72 lands the Atlas band at 134 KB — the same picture, slightly
     * lighter. Skia exposes no lossless WebP path, so this is a choice about how much to keep
     * rather than whether to lose any.
     */
    const val QUALITY = 72

    /**
     * A window onto the map, in the 2048 render's own pixels.
     *
     * The windows are written down rather than searched for. A "find the most interesting band"
     * heuristic would move the picture every time the generator changed, which is the one property
     * a fixed identity must not have: the page should show the same country next release, drawn
     * better. These were chosen by rendering the whole map and looking at it (`-Pcontact`).
     *
     * [x] may run past the right-hand edge. The map is a cylinder — column 2047 and column 0 are
     * neighbours — so a window that crosses the seam is an ordinary window, and the widest river on
     * this world happens to live on it.
     */
    data class Window(val x: Int, val y: Int, val width: Int, val height: Int)

    /**
     * The band across the page: the hero, and the frame all four readings share.
     *
     * A 2:1 window on the northern continent, which carries in one frame everything the page
     * claims — a cordillera along its spine, a river system that gathers the whole northern half,
     * lakes, a coast broken into peninsulas and a long spit, islands offshore, and the west coast
     * of the next continent with its own lake and snowfield at the right-hand edge.
     */
    val BAND = Window(448, 64, 1600, 800)

    /**
     * What the page asks for.
     *
     * [file] is the name the page references, so renaming one here renames it there, and
     * `SiteAssemblyTest` is what notices when only one of the two moves.
     */
    data class Figure(
        val file: String,
        val view: MapView,
        val style: MapStyle,
        val window: Window,
        val quality: Int = QUALITY,
        val options: RenderOptions = RenderOptions(view = view, style = style)
    )

    /** Every figure the page shows, in the order it shows them. */
    val FIGURES: List<Figure> = listOf(
        Figure("atlas.webp", MapView.FANTASY, MapStyle.ATLAS, BAND)
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val outputDir = File(args.firstOrNull() ?: "build/site-imagery").absoluteFile
        outputDir.mkdirs()

        // A contact sheet is the whole map at half size with a coordinate grid over it and the
        // page's windows outlined, written beside the figures so that whoever next moves a window
        // can read coordinates off a picture instead of guessing. Off by default: it is five more
        // rasterisations and 3 MB of PNG, and a deploy has no use for it.
        val contact = System.getProperty("cartogenesis.siteImagery.contact") == "true"

        val started = System.currentTimeMillis()
        val world = generate()
        val generated = System.currentTimeMillis()
        println(
            "SITE IMAGERY seed=$SEED ${world.width}x${world.height} generated in " +
                "${(generated - started) / 1000}s (rivers=${world.rivers.rivers.size}, " +
                "realms=${world.nations.nations.size})"
        )

        var total = 0L
        for (figure in FIGURES) {
            val bytes = write(world, figure, outputDir)
            total += bytes
            println(
                "  ${figure.file.padEnd(20)} ${figure.window.width}x${figure.window.height}  " +
                    "${bytes / 1024} KB  (${figure.view.label}, ${figure.style.label}, " +
                    "at ${figure.window.x},${figure.window.y}, quality ${figure.quality})"
            )
        }
        if (contact) writeContactSheets(world, outputDir)

        val finished = System.currentTimeMillis()
        println(
            "SITE IMAGERY ${FIGURES.size} figures, ${total / 1024} KB total, " +
                "${(finished - started) / 1000}s including generation"
        )
    }

    /** [SEED] at [SIZE], with the settings the page names. */
    fun generate(): WorldMap {
        val base = WorldGenConfig(seed = SEED, width = 512, height = 512, seaLevel = SEA_LEVEL)
        val config = base.copy(
            tectonics = base.tectonics.copy(plateCount = PLATES),
            nations = base.nations.copy(nationCount = REALMS)
        ).atResolution(SIZE, SIZE)
        return WorldGenerationEngine.generateBlocking(config)
    }

    /** Rasterises one figure, cuts its window out at 1:1 and writes it as WebP. Returns the size. */
    private fun write(world: WorldMap, figure: Figure, outputDir: File): Long {
        // The two-argument rasterisation is the processor's own path. The accelerated one is a
        // suspending call that wants a graphics device, and the runner has neither a device nor a
        // display; the processor is the reference path in any case.
        val whole = MapImage.toBitmap(world, figure.options)
        val image = Image.makeFromBitmap(whole)

        val window = figure.window
        val crop = Bitmap().apply {
            allocPixels(ImageInfo.makeS32(window.width, window.height, ColorAlphaType.PREMUL))
        }
        drawWindow(Canvas(crop), image, window, world.width)

        val cropped = Image.makeFromBitmap(crop)
        val data = cropped.encodeToData(EncodedImageFormat.WEBP, figure.quality)
            ?: error("Skia could not encode ${figure.file} as WebP")
        val destination = File(outputDir, figure.file)
        destination.writeBytes(data.bytes)

        cropped.close()
        crop.close()
        image.close()
        whole.close()
        return destination.length()
    }

    /**
     * Copies [window] out of [image] at 1:1, in one piece or two.
     *
     * The map wraps in longitude, so a window may begin near the right-hand edge and finish past
     * it. That is not an edge case to be avoided: it is where the widest river on this world runs,
     * and refusing to cross the seam would mean choosing a lesser river to keep the arithmetic
     * simple. Two draws, and the reader cannot tell.
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

    /**
     * The whole map at half size with a grid, one sheet per view the page uses.
     *
     * The grid is in the *render's* coordinates rather than the sheet's: a light line every 128 map
     * pixels and a heavier one every 512, so a window can be read off the picture and typed
     * straight into a [Window] without arithmetic. The windows the page uses are outlined in brass
     * on every sheet, so a proposed change can be judged against the map before it is rendered.
     */
    private fun writeContactSheets(world: WorldMap, outputDir: File) {
        val sheets = listOf(
            "contact-atlas" to RenderOptions(view = MapView.FANTASY, style = MapStyle.ATLAS),
            "contact-rainfall" to RenderOptions(view = MapView.RAINFALL, style = MapStyle.ATLAS),
            "contact-biomes" to RenderOptions(view = MapView.BIOMES, style = MapStyle.ATLAS),
            "contact-political" to RenderOptions(view = MapView.POLITICAL, style = MapStyle.ATLAS),
            "contact-pen-and-ink" to
                RenderOptions(view = MapView.FANTASY, style = MapStyle.PEN_AND_INK)
        )
        val half = SIZE / 2
        for ((name, options) in sheets) {
            val bitmap = MapImage.toBitmap(world, options)
            val image = Image.makeFromBitmap(bitmap)
            val sheet = Bitmap().apply {
                allocPixels(ImageInfo.makeS32(half, half, ColorAlphaType.PREMUL))
            }
            val canvas = Canvas(sheet)
            canvas.drawImageRect(
                image,
                Rect.makeWH(SIZE.toFloat(), SIZE.toFloat()),
                Rect.makeWH(half.toFloat(), half.toFloat())
            )
            val thin = Paint().apply { color = 0x55FFFFFF.toInt(); strokeWidth = 1f }
            val thick = Paint().apply { color = 0xAAFF3355.toInt(); strokeWidth = 1.5f }
            var mapPixel = 0
            while (mapPixel <= SIZE) {
                val at = mapPixel / 2f
                val paint = if (mapPixel % 512 == 0) thick else thin
                canvas.drawLine(at, 0f, at, half.toFloat(), paint)
                canvas.drawLine(0f, at, half.toFloat(), at, paint)
                mapPixel += 128
            }
            val outline = Paint().apply {
                color = 0xFFC9A227.toInt(); strokeWidth = 3f; mode = PaintMode.STROKE
            }
            FIGURES.map { it.window }.distinct().forEach { w ->
                canvas.drawRect(
                    Rect.makeXYWH(w.x / 2f, w.y / 2f, w.width / 2f, w.height / 2f), outline
                )
            }
            val png = Image.makeFromBitmap(sheet).encodeToData(EncodedImageFormat.PNG)!!
            File(outputDir, "$name.png").writeBytes(png.bytes)
            sheet.close()
            image.close()
            bitmap.close()
            println("  contact sheet $name.png (grid: light 128, heavy 512, map pixels)")
        }
    }
}
