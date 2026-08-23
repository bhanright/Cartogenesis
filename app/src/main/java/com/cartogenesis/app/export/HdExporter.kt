package com.cartogenesis.app.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.cartogenesis.cartography.MapPalette
import com.cartogenesis.app.render.MapRenderer
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.model.LabelKind
import com.cartogenesis.worldgen.model.MapLabel
import com.cartogenesis.worldgen.model.WorldGenConfig
import java.io.IOException

/**
 * Export sizes, capped at 2048.
 *
 * 4096 is out of reach on device: generation holds around a dozen full-resolution float fields
 * plus the integrator's double-precision FFT buffers, and at 4096x4096 a single one of those
 * buffers is 134MB. It reliably exhausts even a largeHeap process. Going higher needs the
 * pipeline reworked to run in tiles rather than a bigger number here.
 */
enum class ExportResolution(val label: String, val size: Int) {
    STANDARD("1024 x 1024", 1024),
    HIGH("2048 x 2048", 2048)
}

/**
 * Renders a world at export resolution and saves it to the gallery.
 *
 * Generation is deterministic for a given config, so this re-runs the whole pipeline at the target
 * size rather than upscaling the on-screen preview. Coastlines, rivers and mountains are therefore
 * genuinely sharper at 4K, not interpolated.
 */
object HdExporter {

    fun export(
        context: Context,
        config: WorldGenConfig,
        labels: List<MapLabel>,
        renderOptions: RenderOptions,
        resolution: ExportResolution
    ): Uri {
        val exportConfig = config.atResolution(resolution.size, resolution.size)
        val world = WorldGenerationEngine.generate(exportConfig)

        val scale = resolution.size.toFloat() / config.width
        val bitmap = MapRenderer.render(
            world,
            renderOptions.copy(riverScale = renderOptions.riverScale * scale.coerceAtLeast(1f))
        )

        if (labels.isNotEmpty()) drawLabels(bitmap, labels)

        return save(context, bitmap, config.seed)
    }

    private fun drawLabels(bitmap: Bitmap, labels: List<MapLabel>) {
        val canvas = Canvas(bitmap)
        val baseSize = bitmap.width / 42f

        labels.forEach { label ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(255, 26, 26, 26)
                textAlign = Paint.Align.CENTER
                textSize = baseSize * sizeFactor(label.kind)
                typeface = Typeface.create(Typeface.SERIF, styleFor(label.kind))
                letterSpacing = if (label.kind == LabelKind.REGION) 0.22f else 0.06f
            }
            // A light halo keeps text legible over dark ocean and busy terrain alike.
            val halo = Paint(paint).apply {
                style = Paint.Style.STROKE
                strokeWidth = paint.textSize / 7f
                color = MapPalette.PARCHMENT
            }

            val x = label.x * bitmap.width
            val y = label.y * bitmap.height
            canvas.drawText(label.text, x, y, halo)
            canvas.drawText(label.text, x, y, paint)
        }
    }

    private fun sizeFactor(kind: LabelKind): Float = when (kind) {
        LabelKind.REGION -> 1.35f
        LabelKind.WATER -> 1.1f
        LabelKind.MOUNTAIN -> 0.85f
        LabelKind.SETTLEMENT -> 0.7f
        LabelKind.POINT_OF_INTEREST -> 0.7f
    }

    private fun styleFor(kind: LabelKind): Int = when (kind) {
        LabelKind.REGION, LabelKind.WATER -> Typeface.BOLD_ITALIC
        LabelKind.MOUNTAIN -> Typeface.ITALIC
        else -> Typeface.NORMAL
    }

    private fun save(context: Context, bitmap: Bitmap, seed: Long): Uri {
        val filename = "cartogenesis-$seed-${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/Cartogenesis"
            )
            // Hides the entry from the gallery until the pixels are actually written.
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create a file in the gallery")

        try {
            resolver.openOutputStream(uri)?.use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw IOException("Failed to encode the map")
                }
            } ?: throw IOException("Could not open the gallery file for writing")
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
