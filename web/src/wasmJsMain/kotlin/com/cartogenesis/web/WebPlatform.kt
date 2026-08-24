package com.cartogenesis.web

import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.TextWorldLibrary
import com.cartogenesis.cartography.WorldLibrary
import com.cartogenesis.ui.ExportFormat
import com.cartogenesis.ui.ExportOutcome
import com.cartogenesis.ui.MapImage
import com.cartogenesis.ui.Platform
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ErosionAccelerator
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * What a browser tab can offer.
 *
 * The three answers differ from the desktop's, and nothing else does: worlds live in local storage
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

    override val library: WorldLibrary = LocalStorageLibrary()

    override val libraryLocation: String =
        "This browser's local storage. Clearing site data will remove them, so export anything worth keeping."

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

/**
 * Saved worlds in `localStorage`.
 *
 * The shared [TextWorldLibrary] already knows how to turn a world into text and back, so this only
 * has to say where named blobs live. Keys are prefixed so the library can be listed without
 * disturbing anything else the page keeps.
 *
 * A caveat worth being honest about: local storage is a few megabytes, and a world generated on
 * the GPU carries its terrain, which is several. Such a world may simply not fit, so a failed
 * write is reported rather than swallowed.
 */
private class LocalStorageLibrary : TextWorldLibrary() {

    private val prefix = "cartogenesis/"

    override fun names(): List<String> =
        (0 until storageLength()).mapNotNull { storageKeyAt(it) }
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }

    override fun read(name: String): String? = storageGet(prefix + name)

    override fun write(name: String, text: String) {
        if (!storageSet(prefix + name, text)) {
            error("This browser's storage is full. Worlds made on the GPU carry their terrain and can be several megabytes.")
        }
    }

    override fun remove(name: String) = storageRemove(prefix + name)
}
