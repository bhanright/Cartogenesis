package com.cartogenesis.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.cartogenesis.cartography.GlyphShape
import com.cartogenesis.cartography.MapRasterizer
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
        if (overlay.rivers.isEmpty() && overlay.landmarks.isEmpty() && overlay.flow.isEmpty()) return

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
}
