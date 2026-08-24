package com.cartogenesis.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.cartogenesis.cartography.GlyphShape
import com.cartogenesis.cartography.MapRasterizer
import com.cartogenesis.cartography.MapSymbol
import com.cartogenesis.cartography.SymbolShape
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

    fun render(world: WorldMap, options: RenderOptions): ImageBitmap {
        val bitmap = toBitmap(world, options)
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
    fun toBitmap(world: WorldMap, options: RenderOptions): Bitmap {
        val w = world.width
        val h = world.height
        val pixels = MapRasterizer.rasterize(world, options)

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

        drawOverlay(world, bitmap, options)
        return bitmap
    }

    private fun drawOverlay(world: WorldMap, bitmap: Bitmap, options: RenderOptions) {
        val overlay = MapRasterizer.overlay(world, options)
        if (overlay.rivers.isEmpty() && overlay.landmarks.isEmpty() &&
            overlay.flow.isEmpty() && overlay.symbols.isEmpty()
        ) {
            return
        }

        val canvas = Canvas(bitmap)

        if (overlay.rivers.isNotEmpty()) {
            val paint = Paint().apply {
                isAntiAlias = true
                color = overlay.riverColor
                mode = PaintMode.STROKE
                strokeCap = PaintStrokeCap.ROUND
            }
            overlay.rivers.forEach { s ->
                paint.strokeWidth = s.width
                canvas.drawLine(s.x0, s.y0, s.x1, s.y1, paint)
            }
        }

        // Terrain marks go under the rivers and the borders: on a drawn map the water is inked
        // over the hills, not around them.
        if (overlay.symbols.isNotEmpty()) {
            val paint = Paint().apply {
                isAntiAlias = true
                mode = PaintMode.STROKE
                color = overlay.symbolColor
                strokeCap = PaintStrokeCap.ROUND
            }
            overlay.symbols.forEach { drawSymbol(canvas, it, paint) }
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
                val tipX = arrow.x + arrow.dx * length
                val tipY = arrow.y + arrow.dy * length
                canvas.drawLine(arrow.x, arrow.y, tipX, tipY, paint)
                val backX = tipX - arrow.dx * length * 0.42f
                val backY = tipY - arrow.dy * length * 0.42f
                val barbX = arrow.dy * length * 0.26f
                val barbY = arrow.dx * length * 0.26f
                canvas.drawLine(tipX, tipY, backX + barbX, backY - barbY, paint)
                canvas.drawLine(tipX, tipY, backX - barbX, backY + barbY, paint)
            }
        }

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

    /**
     * One terrain mark.
     *
     * Deliberately crude: a few strokes each, at a size where detail would not survive anyway. The
     * point is the texture a field of them makes, not any single one — which is also why they are
     * stroked rather than filled, so overlapping marks read as a range rather than a blob.
     */
    private fun drawSymbol(canvas: Canvas, symbol: MapSymbol, paint: Paint) {
        val x = symbol.x
        val y = symbol.y
        val s = symbol.size
        paint.strokeWidth = (s * 0.16f).coerceAtLeast(0.6f)

        when (symbol.shape) {
            // A peak with one shaded face, the way they are drawn by hand.
            SymbolShape.MOUNTAIN -> {
                val path = Path().apply {
                    moveTo(x - s * 0.6f, y + s * 0.35f)
                    lineTo(x, y - s * 0.5f)
                    lineTo(x + s * 0.6f, y + s * 0.35f)
                }
                canvas.drawPath(path, paint)
                canvas.drawLine(x, y - s * 0.5f, x - s * 0.18f, y + s * 0.35f, paint)
            }

            SymbolShape.HILL -> {
                val path = Path().apply {
                    moveTo(x - s * 0.5f, y + s * 0.2f)
                    quadTo(x, y - s * 0.45f, x + s * 0.5f, y + s * 0.2f)
                }
                canvas.drawPath(path, paint)
            }

            SymbolShape.CONIFER -> {
                val path = Path().apply {
                    moveTo(x - s * 0.32f, y + s * 0.25f)
                    lineTo(x, y - s * 0.45f)
                    lineTo(x + s * 0.32f, y + s * 0.25f)
                }
                canvas.drawPath(path, paint)
                canvas.drawLine(x, y + s * 0.25f, x, y + s * 0.45f, paint)
            }

            SymbolShape.BROADLEAF -> {
                canvas.drawCircle(x, y - s * 0.12f, s * 0.3f, paint)
                canvas.drawLine(x, y + s * 0.18f, x, y + s * 0.45f, paint)
            }

            // A low dune and the smaller one behind it.
            SymbolShape.DUNE -> {
                val path = Path().apply {
                    moveTo(x - s * 0.5f, y + s * 0.15f)
                    quadTo(x - s * 0.1f, y - s * 0.25f, x + s * 0.5f, y + s * 0.15f)
                }
                canvas.drawPath(path, paint)
            }

            // Two short crests, as water is drawn on every chart that bothers to draw it.
            SymbolShape.WAVE -> {
                val path = Path().apply {
                    moveTo(x - s * 0.45f, y)
                    quadTo(x - s * 0.22f, y - s * 0.3f, x, y)
                    quadTo(x + s * 0.22f, y + s * 0.3f, x + s * 0.45f, y)
                }
                canvas.drawPath(path, paint)
            }
        }
    }
}
