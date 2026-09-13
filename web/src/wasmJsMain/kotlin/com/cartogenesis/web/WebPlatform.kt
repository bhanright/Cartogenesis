package com.cartogenesis.web

import com.cartogenesis.cartography.Compressor
import com.cartogenesis.cartography.DataExports
import com.cartogenesis.cartography.DataLayer
import com.cartogenesis.cartography.MapSheet
import com.cartogenesis.cartography.NoCompression
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.WorldCodec
import com.cartogenesis.cartography.WorldDocument
import com.cartogenesis.cartography.WorldLibrary
import com.cartogenesis.cartography.WorldSave
import com.cartogenesis.ui.BuildInfo
import com.cartogenesis.ui.ExportFormat
import com.cartogenesis.ui.ExportOutcome
import com.cartogenesis.ui.MapImage
import com.cartogenesis.ui.Platform
import com.cartogenesis.ui.SettingsStore
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
     * Whether this browser has WebGPU at all, which is a different question from whether a device
     * could be got out of it.
     *
     * [accelerator] is null in both cases — no `navigator.gpu`, and a `requestAdapter` that came
     * back empty — and the interface treats them differently: a browser that has the API and
     * declined gets the switch disabled with the reason printed beside it, and one that has never
     * heard of it gets no switch, because there is nothing to explain. On a phone this is the usual
     * case: iOS Safari and most Android browsers ship no `navigator.gpu` at all.
     */
    override val graphicsApiPresent: Boolean = webGpuPresent()

    /** `(pointer: coarse)`. See [pointerIsCoarse]. */
    override val coarsePointer: Boolean = pointerIsCoarse()

    /**
     * Erosion alone, and that is not a simplification.
     *
     * The desktop draws the export raster on its device as well, through OpenGL compute; the WGSL
     * port of that raster has not been written, so in a browser the device runs the erosion sweeps
     * and nothing else. Saying otherwise here would be promising a speed-up that does not exist.
     */
    override fun acceleratedWork(device: String): String =
        "Erosion runs on $device, many times faster."

    /**
     * 2048 on a phone, 4096 otherwise.
     *
     * An export re-runs the whole pipeline at the target size and then rasterises it, which at 4096
     * is sixteen times the working grid's cells in one blocking pass on the page's only thread —
     * survivable on a laptop, and on a phone it is a tab the browser kills for memory. The chip for
     * 4096 stays in the row, disabled, saying why, exactly as 8192 does everywhere.
     */
    override fun exportCeiling(compact: Boolean): Int = if (compact) 2048 else 4096

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

    /**
     * `localStorage`, which is exactly the right size for this and exactly the wrong size for a
     * world.
     *
     * The library outgrew local storage the moment a save carried the world and moved to IndexedDB
     * (see [IndexedDbLibrary]); the settings are a few hundred bytes of JSON and want the simplest
     * durable thing there is, which is a synchronous key. It survives a reload and a restart, and
     * goes when the reader clears site data — which is the same promise the library makes.
     */
    override val settingsStore: SettingsStore = LocalStorageSettings

    /** A tab cannot close itself, so File offers no Quit here. */
    override val canQuit: Boolean = false

    override val canOpenLinks: Boolean = true

    override fun openLink(url: String) {
        openInNewTab(url)
    }

    /**
     * One `fetch`, made only when a reader asks for it.
     *
     * GitHub's releases API sends `Access-Control-Allow-Origin: *`, so this is an ordinary
     * cross-origin `GET` from the page with no proxy in the middle. Nothing calls it while the page
     * is loading: the launch check is off by default and the menu item is the only other caller,
     * which is what keeps the bundle's load path free of a request to another origin.
     */
    override suspend fun fetchText(url: String): String? = fetchTextOrNull(url)

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

        // Drawn with exactly the preview's options: every mark the renderer makes is sized where it
        // is made, in output pixels or as a share of the sheet, so an export needs no scaling here.
        // A printed sheet, as on the desktop: nothing generalised away, and its own scale bar.
        val bitmap = MapImage.toBitmap(world, options, MapSheet.PRINTED)
        // Quality is ignored by the PNG encoder and lossless for WebP at 100; JPEG is the one
        // format with a real quality to choose, and it is chosen once, in [ExportFormat].
        val quality =
            if (format == ExportFormat.JPEG) ExportFormat.JPEG_QUALITY else LOSSLESS_QUALITY
        val encoded = Image.makeFromBitmap(bitmap)
            .encodeToData(skiaFormat(format), quality = quality)
            ?: error("Could not encode the map as ${format.label}")
        val bytes = encoded.bytes
        bitmap.close()

        val name = "cartogenesis-${config.seed}-$size.${format.extension}"
        downloadBytes(name, bytes, mimeType(format))

        return ExportOutcome(name, epochMillisNow() - started, bytes.size.toLong())
    }

    /**
     * The world's own numbers, as one zip.
     *
     * A data export is always two files — the image and the sidecar that says what its numbers mean
     * — and a browser gives a page one clean way to hand over two files, which is to hand over one.
     * Two `downloadBytes` calls in a row work in Chrome only after the reader approves a "download
     * multiple files" prompt that appears without explanation, and Safari has historically kept the
     * first and dropped the second. A zip needs no permission, arrives as one thing, and keeps the
     * heightmap and its metre scale together where a reader cannot separate them by accident. The
     * archive stores rather than deflates: the PNG inside is already compressed, and the page has
     * one thread. See [com.cartogenesis.cartography.DataFiles.asZip].
     */
    override suspend fun exportData(
        config: WorldGenConfig,
        size: Int,
        layer: DataLayer
    ): ExportOutcome? {
        val started = epochMillisNow()

        val exportConfig = config.atResolution(size, size)
        val world = WorldGenerationEngine.generate(exportConfig, accelerator = accelerator)
        val files = DataExports.write(world, layer, compressor, BuildInfo.VERSION)

        val bytes = files.asZip()
        val name = "${DataExports.baseName(config, size, layer)}.zip"
        downloadBytes(name, bytes, "application/zip")

        return ExportOutcome(
            "$name (${files.imageName} and ${files.sidecarName})",
            epochMillisNow() - started,
            bytes.size.toLong()
        )
    }

    private fun skiaFormat(format: ExportFormat): EncodedImageFormat = when (format) {
        ExportFormat.PNG -> EncodedImageFormat.PNG
        ExportFormat.WEBP -> EncodedImageFormat.WEBP
        ExportFormat.JPEG -> EncodedImageFormat.JPEG
    }

    private fun mimeType(format: ExportFormat): String = when (format) {
        ExportFormat.PNG -> "image/png"
        ExportFormat.WEBP -> "image/webp"
        ExportFormat.JPEG -> "image/jpeg"
    }

    private companion object {
        /** Lossless for WebP and ignored by the PNG encoder, so one figure does for both. */
        const val LOSSLESS_QUALITY = 100
    }
}

/**
 * The settings document, under one key in `localStorage`.
 *
 * An object rather than a class: there is one settings document per origin, and two instances of a
 * store over the same key would only invite the question of which one is authoritative. Both calls
 * swallow their failures — a browser in private mode, or one with site data blocked, throws on
 * `setItem` rather than declining it, and a preference that could not be saved is not a reason to
 * stop the application.
 */
private object LocalStorageSettings : SettingsStore {

    private const val KEY = "cartogenesis.settings"

    override val location: String =
        "This browser's local storage. Clearing site data resets them to the defaults."

    override suspend fun read(): String? = runCatching { storageGet(KEY) }.getOrNull()

    override suspend fun write(text: String) {
        runCatching { storageSet(KEY, text) }
    }
}
