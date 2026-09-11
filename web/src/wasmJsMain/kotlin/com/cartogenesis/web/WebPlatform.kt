package com.cartogenesis.web

import com.cartogenesis.cartography.ByteWorldLibrary
import com.cartogenesis.cartography.Compressor
import com.cartogenesis.cartography.NoCompression
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.WorldLibrary
import com.cartogenesis.ui.ExportFormat
import com.cartogenesis.ui.ExportOutcome
import com.cartogenesis.ui.MapImage
import com.cartogenesis.ui.Platform
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ErosionAccelerator
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
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

    /**
     * No compression here yet.
     *
     * A browser's `CompressionStream` is asynchronous and works in streams, which does not fit a
     * library that saves and loads in a single call, and handing it tens of megabytes of Kotlin
     * bytes means copying them across the JS boundary one at a time. So the payload is stored raw
     * and the header says `none`, which every reader honours. Moving web storage to IndexedDB is
     * asynchronous throughout and is the natural place to revisit this.
     */
    override val compressor: Compressor = NoCompression

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
 * The shared [ByteWorldLibrary] knows the format; this only has to say where named blobs live.
 * Keys are prefixed so the library can be listed without disturbing anything else the page keeps.
 * Local storage holds text, so the container is base64'd on the way in and back on the way out.
 *
 * The caveat, stated plainly: local storage is a few megabytes per origin and a save now carries
 * the world, which at 512 is tens of megabytes. Most will not fit, and a failed write says so
 * rather than being swallowed. IndexedDB is where this belongs, and is the next piece of work.
 */
private class LocalStorageLibrary : ByteWorldLibrary(NoCompression, "web") {

    private val prefix = "cartogenesis/"

    override fun names(): List<String> =
        (0 until storageLength()).mapNotNull { storageKeyAt(it) }
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }

    @OptIn(ExperimentalEncodingApi::class)
    override fun read(name: String): ByteArray? {
        val text = storageGet(prefix + name) ?: return null
        // A version-2 entry is the JSON itself rather than base64 of a container, told apart by
        // the one character JSON must start with and base64 never does.
        if (text.startsWith("{")) return text.encodeToByteArray()
        return runCatching { Base64.decode(text) }.getOrNull()
    }

    @OptIn(ExperimentalEncodingApi::class)
    override fun write(name: String, bytes: ByteArray) {
        if (!storageSet(prefix + name, Base64.encode(bytes))) {
            error(
                "This browser's storage is full. A saved world now carries the world itself, " +
                    "which is more than local storage will hold."
            )
        }
    }

    override fun remove(name: String) = storageRemove(prefix + name)
}
