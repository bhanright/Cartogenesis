package com.cartogenesis.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.cartogenesis.cartography.GlyphShape
import com.cartogenesis.cartography.MapRasterizer
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.worldgen.model.WorldMap

/**
 * Wraps the shared rasterizer in an Android bitmap.
 *
 * All the decisions — colours, relief shading, which river segments to draw, which glyph a
 * landmark gets — live in `:cartography` so every platform makes them identically. What is left
 * here is just the Android drawing calls.
 */
object MapRenderer {

    fun render(world: WorldMap, options: RenderOptions = RenderOptions()): Bitmap {
        val pixels = MapRasterizer.rasterize(world, options)

        // Allocate then fill, rather than Bitmap.createBitmap(pixels, ...) — that overload returns
        // an immutable bitmap, which the Canvas constructor rejects when the overlay is drawn.
        val bitmap = Bitmap.createBitmap(world.width, world.height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, world.width, 0, 0, world.width, world.height)

        drawOverlay(world, bitmap, options)
        return bitmap
    }

    private fun drawOverlay(world: WorldMap, bitmap: Bitmap, options: RenderOptions) {
        val overlay = MapRasterizer.overlay(world, options)
        if (overlay.rivers.isEmpty() && overlay.landmarks.isEmpty() && overlay.flow.isEmpty()) return

        val canvas = Canvas(bitmap)

        if (overlay.rivers.isNotEmpty()) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = overlay.riverColor
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            overlay.rivers.forEach { segment ->
                paint.strokeWidth = segment.width
                canvas.drawLine(segment.x0, segment.y0, segment.x1, segment.y1, paint)
            }
        }

        if (overlay.flow.isNotEmpty()) drawFlow(canvas, overlay)

        if (overlay.landmarks.isEmpty()) return

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
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
                        close()
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
                        close()
                    }
                    canvas.drawPath(path, fill)
                    canvas.drawPath(path, outline)
                }

                GlyphShape.SQUARE -> {
                    canvas.drawRect(glyph.x - r, glyph.y - r, glyph.x + r, glyph.y + r, fill)
                    canvas.drawRect(glyph.x - r, glyph.y - r, glyph.x + r, glyph.y + r, outline)
                }

                GlyphShape.CIRCLE -> {
                    canvas.drawCircle(glyph.x, glyph.y, r, fill)
                    canvas.drawCircle(glyph.x, glyph.y, r, outline)
                }
            }
        }
    }

    /**
     * Short direction arrows for a flow field. Weak flow fades out rather than filling the sea
     * with stubs, so the strong western boundary currents are what the eye actually lands on.
     */
    private fun drawFlow(canvas: Canvas, overlay: com.cartogenesis.cartography.MapOverlay) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        overlay.flow.forEach { arrow ->
            val length = overlay.flowScale * (0.45f + 0.55f * arrow.strength)
            paint.color = arrow.color
            paint.alpha = (70 + 150 * arrow.strength).toInt().coerceIn(0, 255)
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
}
