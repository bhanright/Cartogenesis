package com.cartogenesis.desktop

import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.model.WorldGenConfig
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * Renders a world at export resolution and writes a PNG.
 *
 * As on Android, the whole pipeline is re-run at the target size rather than upscaling the
 * preview, so the detail is real. Unlike Android, there is enough heap to actually finish: 4096
 * wants roughly 2GB and 8192 around four times that, against the 600MB ceiling a phone allows.
 */
object Exporter {

    class Result(val file: File, val millis: Long, val bytes: Long)

    fun export(
        config: WorldGenConfig,
        options: RenderOptions,
        size: Int,
        destination: File
    ): Result {
        val started = System.currentTimeMillis()

        val exportConfig = config.atResolution(size, size)
        val world = WorldGenerationEngine.generate(exportConfig)

        val scale = size.toFloat() / config.width
        val bitmap = DesktopRenderer.toBitmap(
            world,
            options.copy(riverScale = options.riverScale * scale.coerceAtLeast(1f))
        )

        val data = Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)
            ?: error("Could not encode the map as PNG")
        destination.writeBytes(data.bytes)

        // The bitmap holds size*size*4 bytes; let it go before the caller renders anything else.
        bitmap.close()

        return Result(destination, System.currentTimeMillis() - started, destination.length())
    }

    fun defaultName(config: WorldGenConfig, size: Int): String =
        "cartogenesis-${config.seed}-$size.png"
}
