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
import com.cartogenesis.cartography.SheetGeometry
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
        sheet: MapSheet = MapSheet.UNGENERALISED
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
        // Compose only converts from a Skia Image, not a Bitmap. Marked immutable first, so the
        // Image shares the bitmap's pixels rather than taking a copy of a sheet that can be 128 MB;
        // both are released straight away once Compose has its own. Closed by hand rather than
        // with `use`, which in common code would resolve to the JVM Closeable extension and does
        // not exist in the browser.
        bitmap.setImmutable()
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
        sheet: MapSheet = MapSheet.UNGENERALISED
    ): Bitmap = toBitmap(world, options, MapRasterizer.rasterize(world, options), sheet)

    /**
     * The same, with the raster already done.
     *
     * Export draws its pixels on the graphics card where there is one (see
     * [com.cartogenesis.cartography.RasterAccelerator]) and hands them here, so the overlays and the
     * bitmap do not care which processor drew what is underneath them.
     *
     * [pixels] is one colour per cell. The bitmap is the true-shape sheet ([SheetGeometry]): each
     * cell's colour is copied into every pixel its cell covers there, exactly — a biome, a realm or
     * a style's colour is never blended with its neighbour's — and then the ink is laid over it at
     * its own width, its positions carried onto the sheet point by point. Nothing is stretched.
     */
    fun toBitmap(
        world: WorldMap,
        options: RenderOptions,
        pixels: IntArray,
        sheet: MapSheet = MapSheet.UNGENERALISED
    ): Bitmap {
        val geometry = SheetGeometry.of(world)
        val bitmap = sheetBitmap(geometry, pixels)
        drawOverlay(world, bitmap, options, sheet)
        return bitmap
    }

    /**
     * [cellPixels], one ARGB colour per cell of [geometry]'s grid, copied out to a bitmap of the
     * whole true-shape sheet.
     *
     * Written a band of rows at a time, so the only copy of the picture the size of the sheet is the
     * bitmap itself: at a 4096 world the sheet is 8192 by 4096, 128 MB, and a byte array of it
     * alongside would be another 128 MB of heap for as long as the copy took.
     */
    fun sheetBitmap(geometry: SheetGeometry, cellPixels: IntArray): Bitmap {
        val cellCount = geometry.cellsAcross * geometry.cellsDown
        require(cellPixels.size == cellCount) {
            "raster is ${cellPixels.size} cells, not $cellCount"
        }
        val widthPixels = geometry.widthPixels
        val heightPixels = geometry.heightPixels

        val bitmap = Bitmap()
        bitmap.allocPixels(ImageInfo.makeS32(widthPixels, heightPixels, ColorAlphaType.PREMUL))
        val canvas = Canvas(bitmap)

        val bandRows = (BAND_PIXELS / widthPixels).coerceIn(1, heightPixels)
        val sheetRow = IntArray(widthPixels)
        val band = Bitmap()
        var bandTop = 0
        while (bandTop < heightPixels) {
            val rows = minOf(bandRows, heightPixels - bandTop)
            val bytes = ByteArray(rows * widthPixels * BYTES_PER_PIXEL)
            for (rowInBand in 0 until rows) {
                geometry.sheetRow(cellPixels, bandTop + rowInBand, sheetRow)
                var at = rowInBand * widthPixels * BYTES_PER_PIXEL
                for (argb in sheetRow) {
                    bytes[at] = (argb and 0xFF).toByte()             // B
                    bytes[at + 1] = ((argb shr 8) and 0xFF).toByte()  // G
                    bytes[at + 2] = ((argb shr 16) and 0xFF).toByte() // R
                    bytes[at + 3] = ((argb shr 24) and 0xFF).toByte() // A
                    at += BYTES_PER_PIXEL
                }
            }
            band.installPixels(
                ImageInfo.makeS32(widthPixels, rows, ColorAlphaType.PREMUL),
                bytes,
                widthPixels * BYTES_PER_PIXEL
            )
            canvas.writePixels(band, 0, bandTop)
            bandTop += rows
        }
        band.close()
        canvas.close()
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
        // Where each thing on the ground lands on the sheet. Only positions go through it: every
        // width, radius and length below is already in the sheet's pixels, so a line is the same
        // weight whichever way it runs.
        val onSheet = overlay.sheet

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
            overlay.coastline.forEach { canvas.drawPath(polylineOnSheet(it, onSheet), paint) }
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
                canvas.drawLine(
                    onSheet.sheetX(segment.fromX), onSheet.sheetY(segment.fromY),
                    onSheet.sheetX(segment.toX), onSheet.sheetY(segment.toY), paint
                )
            }
        }

        if (overlay.flow.isNotEmpty()) {
            val paint = Paint().apply {
                isAntiAlias = true
                mode = PaintMode.STROKE
                strokeCap = PaintStrokeCap.ROUND
            }
            overlay.flow.forEach { arrow ->
                val length = overlay.flowArrowReachPixels *
                    (SHORTEST_ARROW_SHARE + (1f - SHORTEST_ARROW_SHARE) * arrow.strength)
                val alpha = (FAINTEST_ARROW_ALPHA + ARROW_ALPHA_RANGE * arrow.strength)
                    .toInt().coerceIn(0, 255)
                paint.color = (arrow.color and 0x00FFFFFF) or (alpha shl 24)
                paint.strokeWidth =
                    (overlay.flowArrowReachPixels * ARROW_WIDTH_SHARE).coerceAtLeast(1f)
                val footX = onSheet.sheetX(arrow.x)
                val footY = onSheet.sheetY(arrow.y)
                val tipX = footX + arrow.directionX * length
                val tipY = footY + arrow.directionY * length
                canvas.drawLine(footX, footY, tipX, tipY, paint)
                // The barbs meet the shaft a little behind the tip and stand out either side of
                // it, which is a head drawn with two strokes rather than a filled triangle.
                val barbRootX = tipX - arrow.directionX * length * BARB_LENGTH_SHARE
                val barbRootY = tipY - arrow.directionY * length * BARB_LENGTH_SHARE
                val barbSpreadX = arrow.directionY * length * BARB_SPREAD_SHARE
                val barbSpreadY = arrow.directionX * length * BARB_SPREAD_SHARE
                canvas.drawLine(
                    tipX, tipY, barbRootX + barbSpreadX, barbRootY - barbSpreadY, paint
                )
                canvas.drawLine(
                    tipX, tipY, barbRootX - barbSpreadX, barbRootY + barbSpreadY, paint
                )
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
            val radius = glyph.radius
            val x = onSheet.sheetX(glyph.x)
            val y = onSheet.sheetY(glyph.y)
            when (glyph.shape) {
                GlyphShape.TRIANGLE -> {
                    // Its base is drawn short of the circumscribed circle, so a triangle and a
                    // diamond of the same radius look the same size rather than the triangle
                    // looking the larger of the two.
                    val baseY = y + radius * TRIANGLE_BASE_SHARE
                    val path = Path().apply {
                        moveTo(x, y - radius)
                        lineTo(x + radius, baseY)
                        lineTo(x - radius, baseY)
                        closePath()
                    }
                    canvas.drawPath(path, fill)
                    canvas.drawPath(path, outline)
                }

                GlyphShape.DIAMOND -> {
                    val path = Path().apply {
                        moveTo(x, y - radius)
                        lineTo(x + radius, y)
                        lineTo(x, y + radius)
                        lineTo(x - radius, y)
                        closePath()
                    }
                    canvas.drawPath(path, fill)
                    canvas.drawPath(path, outline)
                }

                GlyphShape.SQUARE -> {
                    val rect = Rect(x - radius, y - radius, x + radius, y + radius)
                    canvas.drawRect(rect, fill)
                    canvas.drawRect(rect, outline)
                }

                GlyphShape.CIRCLE -> {
                    canvas.drawCircle(x, y, radius, fill)
                    canvas.drawCircle(x, y, radius, outline)
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
        val figureHeight = placed.figureHeightPixels
        val labelWidth = Numerals.widthOf(placed.bar.label, figureHeight)
        // Everything about the plate is a share of the figure it has to hold, so the bar keeps its
        // proportions from a 512 preview to an 8192 sheet.
        val padding = figureHeight * PLATE_PADDING_SHARE
        val plate = Rect(
            placed.x - padding,
            placed.y - figureHeight * PLATE_ABOVE_BAR_SHARE - padding,
            placed.x + maxOf(placed.bar.lengthPixels, labelWidth) + padding,
            placed.y + figureHeight * PLATE_BELOW_BAR_SHARE + padding
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
        val tick = figureHeight * TICK_HALF_HEIGHT_SHARE
        canvas.drawLine(placed.x, placed.y - tick, placed.x, placed.y + tick, paint)
        canvas.drawLine(right, placed.y - tick, right, placed.y + tick, paint)

        paint.strokeWidth = overlay.graticuleWidth
        val baselineY = placed.y - figureHeight * LABEL_BASELINE_ABOVE_BAR_SHARE
        Numerals.strokes(placed.bar.label, placed.x, baselineY, figureHeight)
            .forEach { canvas.drawPath(polyline(it), paint) }
    }

    /** A run of `x, y` floats in cell coordinates as a Skia path on the sheet, point by point. */
    private fun polylineOnSheet(points: FloatArray, onSheet: SheetGeometry): Path {
        val path = Path()
        path.moveTo(onSheet.sheetX(points[0]), onSheet.sheetY(points[1]))
        var at = 2
        while (at < points.size) {
            path.lineTo(onSheet.sheetX(points[at]), onSheet.sheetY(points[at + 1]))
            at += 2
        }
        return path
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

    /** BGRA, one byte a channel, which is what Skia's S32 bitmap wants. */
    private const val BYTES_PER_PIXEL = 4

    /**
     * How many sheet pixels [sheetBitmap] copies in one band: a million, four megabytes of bytes
     * in hand at a time, which is a hundred and twenty-eight rows of an 8192 sheet and small beside
     * the sheet itself.
     */
    private const val BAND_PIXELS = 1 shl 20

    // ---- The flow arrows, all as shares so they scale with the sheet. ----

    /** How long the weakest arrow is, as a share of the strongest; the rest interpolate. */
    private const val SHORTEST_ARROW_SHARE = 0.45f

    /** The weakest arrow's ink, of 255: visible as a direction, not as a statement. */
    private const val FAINTEST_ARROW_ALPHA = 70f

    /** What full strength adds to [FAINTEST_ARROW_ALPHA], stopping short of opaque at 220. */
    private const val ARROW_ALPHA_RANGE = 150f

    /** The shaft's weight as a share of the arrow's own spacing, so arrows never touch. */
    private const val ARROW_WIDTH_SHARE = 0.15f

    /** How far back along the shaft the barbs meet it, as a share of the arrow's length. */
    private const val BARB_LENGTH_SHARE = 0.42f

    /** How far either side of the shaft the barbs stand, as a share of the arrow's length. */
    private const val BARB_SPREAD_SHARE = 0.26f

    /** A landmark triangle's base, as a share of its radius. See where it is drawn. */
    private const val TRIANGLE_BASE_SHARE = 0.8f

    // ---- The scale bar's plate, all as shares of the figure height it has to hold. ----

    /** Air between the plate's edge and what it holds. */
    private const val PLATE_PADDING_SHARE = 0.6f

    /** Room above the bar for the distance written over it, which is one figure plus its lead. */
    private const val PLATE_ABOVE_BAR_SHARE = 2f

    /** Room below the bar, which only has to clear the ticks. */
    private const val PLATE_BELOW_BAR_SHARE = 0.6f

    /** Half the height of the tick standing at each end of the bar. */
    private const val TICK_HALF_HEIGHT_SHARE = 0.45f

    /** Where the distance sits above the bar: clear of the ticks, close enough to belong to it. */
    private const val LABEL_BASELINE_ABOVE_BAR_SHARE = 0.9f
}
