package com.cartogenesis.desktop

import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.model.WorldGenConfig
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * What an exported map is written as.
 *
 * PNG is the default and is lossless. WebP is about a quarter the size but is *not* lossless here:
 * Skia exposes only the lossy encoder, and even at maximum quality the loss falls where it is
 * least welcome on a map. Measured against the PNG of the same world, the average pixel drifts
 * about 3 of 255 — invisible — but the worst 0.1% drift by nearly 60, and those are the river
 * lines, borders and coastlines, because that is where the sharp edges are. `ExportSmokeTest`
 * keeps those numbers honest.
 *
 * It is offered anyway, since a quarter of the size matters for a 4096 map and the softening is
 * hard to see at full extent. But the choice is a real one, so the UI says so rather than calling
 * both formats lossless.
 */
enum class ExportFormat(val label: String, val extension: String, val detail: String) {
    PNG("PNG", "png", "Lossless. Larger file, exact detail."),
    WEBP("WebP", "webp", "About a quarter the size. Slightly softens rivers and borders.");

    internal val skiaFormat: EncodedImageFormat
        get() = if (this == PNG) EncodedImageFormat.PNG else EncodedImageFormat.WEBP
}

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
        val world = WorldGenerationEngine.generate(exportConfig)

        val scale = size.toFloat() / config.width
        val bitmap = DesktopRenderer.toBitmap(
            world,
            options.copy(riverScale = options.riverScale * scale.coerceAtLeast(1f))
        )

        // Quality 100 is lossless for WebP and ignored by the PNG encoder, so one call covers both.
        val data = Image.makeFromBitmap(bitmap).encodeToData(format.skiaFormat, quality = 100)
            ?: error("Could not encode the map as ${format.label}")
        destination.writeBytes(data.bytes)

        // The bitmap holds size*size*4 bytes; let it go before the caller renders anything else.
        bitmap.close()

        return Result(destination, System.currentTimeMillis() - started, destination.length(), format)
    }

    fun defaultName(config: WorldGenConfig, size: Int, format: ExportFormat): String =
        "cartogenesis-${config.seed}-$size.${format.extension}"
}
