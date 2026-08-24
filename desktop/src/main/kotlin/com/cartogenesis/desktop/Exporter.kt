package com.cartogenesis.desktop

import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.ui.MapImage
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.generateBlocking
import com.cartogenesis.worldgen.model.WorldGenConfig
import java.io.File
import com.cartogenesis.ui.ExportFormat
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * Renders a world at export resolution and writes it out.
 *
 * The whole pipeline is re-run at the target size rather than upscaling the preview, so the detail
 * is real rather than interpolated, and there is enough heap to actually finish: 4096 wants roughly
 * 2GB.
 */
object Exporter {

    class Result(
        val file: File,
        val millis: Long,
        val bytes: Long,
        val format: ExportFormat
    )

    fun export(
        config: WorldGenConfig,
        options: RenderOptions,
        size: Int,
        destination: File,
        format: ExportFormat = ExportFormat.PNG
    ): Result {
        val started = System.currentTimeMillis()

        val exportConfig = config.atResolution(size, size)
        val world = WorldGenerationEngine.generateBlocking(exportConfig)

        val scale = size.toFloat() / config.width
        val bitmap = MapImage.toBitmap(
            world,
            options.copy(riverScale = options.riverScale * scale.coerceAtLeast(1f))
        )

        // Quality 100 is lossless for WebP and ignored by the PNG encoder, so one call covers both.
        val data = Image.makeFromBitmap(bitmap).encodeToData(skiaFormat(format), quality = 100)
            ?: error("Could not encode the map as ${format.label}")
        destination.writeBytes(data.bytes)

        // The bitmap holds size*size*4 bytes; let it go before the caller renders anything else.
        bitmap.close()

        return Result(destination, System.currentTimeMillis() - started, destination.length(), format)
    }

    fun defaultName(config: WorldGenConfig, size: Int, format: ExportFormat): String =
        "cartogenesis-${config.seed}-$size.${format.extension}"
}

/** Skia's name for a format. Kept here so the shared enum needs no knowledge of Skia. */
private fun skiaFormat(format: ExportFormat): EncodedImageFormat =
    if (format == ExportFormat.PNG) EncodedImageFormat.PNG else EncodedImageFormat.WEBP
