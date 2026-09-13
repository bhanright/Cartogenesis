package com.cartogenesis.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.cartogenesis.cartography.GlyphShape
import com.cartogenesis.cartography.MapOverlay
import com.cartogenesis.cartography.MapRasterizer
import com.cartogenesis.cartography.MapSheet
import com.cartogenesis.cartography.Numerals
import com.cartogenesis.cartography.PlacedScaleBar
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.worldgen.model.WorldMap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.PaintStrokeJoin
import org.jetbrains.skia.Path
import org.jetbrains.skia.Rect

/**
 * The drawing half of rendering.
 *
 * Everything that decides what the map *looks like* lives in `:cartography` — colours, relief
 * shading, which river segments to draw, which glyph a landmark gets — so this only executes the
 * result through Skia. The pixel buffer arrives as ARGB ints, which Skia wants as BGRA bytes.
 *
 * Shared rather than written twice: Compose Multiplatform carries the same Skia on the desktop and
 * in the browser, so a map drawn in a tab is drawn by exactly this code.
 */
object MapImage {

    fun render(
        world: WorldMap,
        options: RenderOptions,
        sheet: MapSheet = MapSheet.SHEET
    ): ImageBitmap = finish(toBitmap(world, options, sheet))

    /**
     * The same, with the raster already done.
     *
     * A change of zoom generalises the overlay differently but leaves the ground underneath exactly
     * as it was, so the interface keeps the raster and hands it back here rather than paying for a
     * whole world of pixels again every time the reader turns the wheel.
     */
    fun render(
        world: WorldMap,
        options: RenderOptions,
        pixels: IntArray,
        sheet: MapSheet
    ): ImageBitmap = finish(toBitmap(world, options, pixels, sheet))

    /** A finished Skia bitmap as something Compose can draw, releasing the bitmap. */
    private fun finish(bitmap: Bitmap): ImageBitmap {
        // Compose only converts from a Skia Image, not a Bitmap; the Image takes its own copy,
        // so both can be released straight away rather than holding width*height*4 bytes twice.
        // Closed by hand rather than with `use`, which in common code would resolve to the JVM
        // Closeable extension and does not exist in the browser.
        val image = Image.makeFromBitmap(bitmap)
        val composed = image.toComposeImageBitmap()
        image.close()
        bitmap.close()
        return composed
    }

    /** Kept separate from [render] so export can encode without going through Compose. */
    fun toBitmap(
        world: WorldMap,
        options: RenderOptions,
        sheet: MapSheet = MapSheet.SHEET
    ): Bitmap = toBitmap(world, options, MapRasterizer.rasterize(world, options), sheet)

    /**
     * The same, with the raster already done.
     *
     * Export draws its pixels on the graphics card where there is one (see
     * [com.cartogenesis.cartography.RasterAccelerator]) and hands them here, so the overlays and the
     * bitmap do not care which processor drew what is underneath them.
     */
    fun toBitmap(
        world: WorldMap,
        options: RenderOptions,
        pixels: IntArray,
        sheet: MapSheet = MapSheet.SHEET
    ): Bitmap {
        val w = world.width
        val h = world.height
        require(pixels.size == w * h) { "raster is ${pixels.size} pixels, not ${w * h}" }

        val bytes = ByteArray(w * h * 4)
        for (i in pixels.indices) {
            val argb = pixels[i]
            val o = i * 4
            bytes[o] = (argb and 0xFF).toByte()             // B
            bytes[o + 1] = ((argb shr 8) and 0xFF).toByte()  // G
            bytes[o + 2] = ((argb shr 16) and 0xFF).toByte() // R
            bytes[o + 3] = ((argb shr 24) and 0xFF).toByte() // A
        }

        val bitmap = Bitmap()
        bitmap.allocPixels(ImageInfo.makeS32(w, h, ColorAlphaType.PREMUL))
        bitmap.installPixels(bytes)

        drawOverlay(world, bitmap, options, sheet)
        return bitmap
    }

    private fun drawOverlay(
        world: WorldMap,
        bitmap: Bitmap,
        options: RenderOptions,
        sheet: MapSheet
    ) {
        val overlay = MapRasterizer.overlay(world, options, sheet)
        if (overlay.isEmpty) return

        val canvas = Canvas(bitmap)

        // The graticule first, because it is the sheet's reference grid and everything the world
        // itself puts on the paper is drawn over it.
        drawGraticule(canvas, overlay)

        if (overlay.coastline.isNotEmpty()) {
            val paint = Paint().apply {
                isAntiAlias = true
                color = overlay.coastColor
                mode = PaintMode.STROKE
                strokeWidth = overlay.coastWidth
                strokeCap = PaintStrokeCap.ROUND
                strokeJoin = PaintStrokeJoin.ROUND
            }
            overlay.coastline.forEach { canvas.drawPath(polyline(it), paint) }
        }

        if (overlay.rivers.isNotEmpty()) {
            val paint = Paint().apply {
                isAntiAlias = true
                color = overlay.riverColor
                mode = PaintMode.STROKE
                strokeCap = PaintStrokeCap.ROUND
            }
            overlay.rivers.forEach { segment ->
                paint.strokeWidth = segment.widthPixels
                canvas.drawLine(segment.fromX, segment.fromY, segment.toX, segment.toY, paint)
            }
        }

        if (overlay.flow.isNotEmpty()) {
            val paint = Paint().apply {
                isAntiAlias = true
                mode = PaintMode.STROKE
                strokeCap = PaintStrokeCap.ROUND
            }
            overlay.flow.forEach { arrow ->
                val length = overlay.flowScale * (0.45f + 0.55f * arrow.strength)
                val alpha = (70 + 150 * arrow.strength).toInt().coerceIn(0, 255)
                paint.color = (arrow.color and 0x00FFFFFF) or (alpha shl 24)
                paint.strokeWidth = (overlay.flowScale * 0.15f).coerceAtLeast(1f)
                val tipX = arrow.x + arrow.directionX * length
                val tipY = arrow.y + arrow.directionY * length
                canvas.drawLine(arrow.x, arrow.y, tipX, tipY, paint)
                val backX = tipX - arrow.directionX * length * 0.42f
                val backY = tipY - arrow.directionY * length * 0.42f
                val barbX = arrow.directionY * length * 0.26f
                val barbY = arrow.directionX * length * 0.26f
                canvas.drawLine(tipX, tipY, backX + barbX, backY - barbY, paint)
                canvas.drawLine(tipX, tipY, backX - barbX, backY + barbY, paint)
            }
        }

        overlay.scaleBar?.let { drawScaleBar(canvas, overlay, it) }

        if (overlay.landmarks.isEmpty()) return

        val fill = Paint().apply { isAntiAlias = true; mode = PaintMode.FILL }
        val outline = Paint().apply {
            isAntiAlias = true
            mode = PaintMode.STROKE
            strokeWidth = overlay.glyphOutlineWidth
            color = overlay.glyphOutline
        }

        overlay.landmarks.forEach { glyph ->
            fill.color = glyph.fill
            val r = glyph.radius
            when (glyph.shape) {
                GlyphShape.TRIANGLE -> {
                    val path = Path().apply {
                        moveTo(glyph.x, glyph.y - r)
                        lineTo(glyph.x + r, glyph.y + r * 0.8f)
                        lineTo(glyph.x - r, glyph.y + r * 0.8f)
                        closePath()
                    }
                    canvas.drawPath(path, fill)
                    canvas.drawPath(path, outline)
                }

                GlyphShape.DIAMOND -> {
                    val path = Path().apply {
                        moveTo(glyph.x, glyph.y - r)
                        lineTo(glyph.x + r, glyph.y)
                        lineTo(glyph.x, glyph.y + r)
                        lineTo(glyph.x - r, glyph.y)
                        closePath()
                    }
                    canvas.drawPath(path, fill)
                    canvas.drawPath(path, outline)
                }

                GlyphShape.SQUARE -> {
                    val rect = Rect(glyph.x - r, glyph.y - r, glyph.x + r, glyph.y + r)
                    canvas.drawRect(rect, fill)
                    canvas.drawRect(rect, outline)
                }

                GlyphShape.CIRCLE -> {
                    canvas.drawCircle(glyph.x, glyph.y, r, fill)
                    canvas.drawCircle(glyph.x, glyph.y, r, outline)
                }
            }
        }
    }

    /** Meridians, parallels and the figures in the margin, all in the graticule's one hairline. */
    private fun drawGraticule(canvas: Canvas, overlay: MapOverlay) {
        val graticule = overlay.graticule ?: return
        val paint = Paint().apply {
            isAntiAlias = true
            color = overlay.graticuleColor
            mode = PaintMode.STROKE
            strokeWidth = overlay.graticuleWidth
            strokeCap = PaintStrokeCap.ROUND
            strokeJoin = PaintStrokeJoin.ROUND
        }
        graticule.lines.forEach { canvas.drawLine(it.fromX, it.fromY, it.toX, it.toY, paint) }

        // The figures are drawn firmly rather than at the lines' weight: a reader looks for a
        // number and only glances at the grid it belongs to.
        paint.color = overlay.marginInk
        graticule.labels.forEach { label ->
            Numerals.strokes(label.text, label.leftX, label.baselineY, label.heightPixels)
                .forEach { canvas.drawPath(polyline(it), paint) }
        }
    }

    /**
     * The scale bar in the corner of a printed sheet: a plate of the style's paper, the bar with a
     * tick at each end, and the distance written above its far end.
     *
     * On its own plate because the corner of a world map is as likely to be deep ocean as coast,
     * and a bar inked straight onto that would be a dark line on a dark ground.
     */
    private fun drawScaleBar(canvas: Canvas, overlay: MapOverlay, placed: PlacedScaleBar) {
        val figure = placed.figureHeightPixels
        val labelWidth = Numerals.widthOf(placed.bar.label, figure)
        val padding = figure * 0.6f
        val plate = Rect(
            placed.x - padding,
            placed.y - figure * 2f - padding,
            placed.x + maxOf(placed.bar.lengthPixels, labelWidth) + padding,
            placed.y + figure * 0.6f + padding
        )
        canvas.drawRect(
            plate,
            Paint().apply {
                isAntiAlias = true
                mode = PaintMode.FILL
                color = (overlay.marginPaper and 0x00FFFFFF) or (PLATE_ALPHA shl 24)
            }
        )

        val paint = Paint().apply {
            isAntiAlias = true
            color = overlay.marginInk
            mode = PaintMode.STROKE
            strokeWidth = overlay.coastWidth
            strokeCap = PaintStrokeCap.BUTT
        }
        val right = placed.x + placed.bar.lengthPixels
        canvas.drawLine(placed.x, placed.y, right, placed.y, paint)
        val tick = figure * 0.45f
        canvas.drawLine(placed.x, placed.y - tick, placed.x, placed.y + tick, paint)
        canvas.drawLine(right, placed.y - tick, right, placed.y + tick, paint)

        paint.strokeWidth = overlay.graticuleWidth
        Numerals.strokes(placed.bar.label, placed.x, placed.y - figure * 0.9f, figure)
            .forEach { canvas.drawPath(polyline(it), paint) }
    }

    /** A run of `x, y` floats as a Skia path. Nothing is closed: a ring already repeats its end. */
    private fun polyline(points: FloatArray): Path {
        val path = Path()
        path.moveTo(points[0], points[1])
        var at = 2
        while (at < points.size) {
            path.lineTo(points[at], points[at + 1])
            at += 2
        }
        return path
    }

    /** How opaque the scale bar's plate is: enough to read against, not enough to be a hole. */
    private const val PLATE_ALPHA = 0xD0
}
