package com.cartogenesis.web

import com.cartogenesis.cartography.Compressor
import com.cartogenesis.cartography.NoCompression
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.WorldCodec
import com.cartogenesis.cartography.WorldDocument
import com.cartogenesis.cartography.WorldLibrary
import com.cartogenesis.cartography.WorldSave
import com.cartogenesis.ui.ExportFormat
import com.cartogenesis.ui.ExportOutcome
import com.cartogenesis.ui.MapImage
import com.cartogenesis.ui.Platform
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ErosionAccelerator
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * What a browser tab can offer.
 *
 * The three answers differ from the desktop's, and nothing else does: worlds live in IndexedDB
 * rather than on disk, exporting means handing the browser a file to download rather than writing
 * a path, and the graphics device is reached through WebGPU rather than OpenGL.
 */
class WebPlatform(
    override val accelerator: ErosionAccelerator?,
    override val accelerationUnavailableBecause: String?
) : Platform {

    // One thread, and generating blocks the page while it runs. 512 takes a few seconds
    // here; 1024 would take over a minute and read as a hang.
    override val defaultResolution: Int = 512

    /**
     * `gzip` where `CompressionStream`/`DecompressionStream` exist, `none` otherwise.
     *
     * Checked once at startup for the header comment below, but [WebGzipCompressor] itself checks
     * on every call — see its doc comment for why that is worth the redundant check.
     */
    override val compressor: Compressor =
        if (compressionStreamsAvailable()) WebGzipCompressor else NoCompression

    override val library: WorldLibrary = IndexedDbLibrary(compressor, "web")

    override val libraryLocation: String =
        "This browser's IndexedDB storage. Clearing site data will remove them, so download " +
            "anything worth keeping."

    override val supportsFileTransfer: Boolean = true

    override suspend fun downloadWorld(document: WorldDocument, world: WorldMap?) {
        val bytes = WorldCodec.encode(document, world, compressor, "web")
        downloadBytes("cartogenesis-${document.id}.cgw", bytes, "application/octet-stream")
    }

    override suspend fun uploadWorld(): WorldSave? {
        val bytes = pickFile() ?: return null
        return WorldCodec.decodeOrNull(bytes, compressor)
    }

    override suspend fun export(
        config: WorldGenConfig,
        options: RenderOptions,
        size: Int,
        format: ExportFormat
    ): ExportOutcome? {
        val started = epochMillisNow()

        // As on the desktop, the whole pipeline re-runs at the target size rather than upscaling
        // the preview, so the detail is real.
        val exportConfig = config.atResolution(size, size)
        val world = WorldGenerationEngine.generate(exportConfig, accelerator = accelerator)

        val scale = size.toFloat() / config.width
        val bitmap = MapImage.toBitmap(
            world,
            options.copy(riverScale = options.riverScale * scale.coerceAtLeast(1f))
        )
        val encoded = Image.makeFromBitmap(bitmap)
            .encodeToData(skiaFormat(format), quality = 100)
            ?: error("Could not encode the map as ${format.label}")
        val bytes = encoded.bytes
        bitmap.close()

        val name = "cartogenesis-${config.seed}-$size.${format.extension}"
        downloadBytes(name, bytes, if (format == ExportFormat.PNG) "image/png" else "image/webp")

        return ExportOutcome(name, epochMillisNow() - started, bytes.size.toLong())
    }

    private fun skiaFormat(format: ExportFormat): EncodedImageFormat =
        if (format == ExportFormat.PNG) EncodedImageFormat.PNG else EncodedImageFormat.WEBP
}
