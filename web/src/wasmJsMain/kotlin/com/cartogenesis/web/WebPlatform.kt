package com.cartogenesis.web

import com.cartogenesis.cartography.Compressor
import com.cartogenesis.cartography.DataExports
import com.cartogenesis.cartography.DataLayer
import com.cartogenesis.cartography.LoadOutcome
import com.cartogenesis.cartography.MapSheet
import com.cartogenesis.cartography.NoCompression
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.WorldCodec
import com.cartogenesis.cartography.WorldDocument
import com.cartogenesis.cartography.WorldLibrary
import com.cartogenesis.ui.BuildInfo
import com.cartogenesis.ui.ExportFormat
import com.cartogenesis.ui.ExportOutcome
import com.cartogenesis.ui.ExportSubjects
import com.cartogenesis.ui.FolderChooser
import com.cartogenesis.ui.MapImage
import com.cartogenesis.ui.Platform
import com.cartogenesis.ui.SettingsStore
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ErosionAccelerator
import com.cartogenesis.worldgen.pipeline.IceSheetAccelerator
import com.cartogenesis.worldgen.pipeline.OceanAccelerator
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * What a browser tab can offer.
 *
 * The three answers differ from the desktop's, and nothing else does: worlds live in IndexedDB, or
 * in a folder on the disk the reader chose where the browser allows a page one, rather than in a
 * folder named by its path; exporting means handing the browser a file to download rather than
 * writing a path; and the graphics device is reached through WebGPU rather than OpenGL.
 */
class WebPlatform(
    override val accelerator: ErosionAccelerator?,
    override val accelerationUnavailableBecause: String?,
    override val oceanAccelerator: OceanAccelerator? = null,
    override val iceAccelerator: IceSheetAccelerator? = null
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
     * Erosion, the currents and the ice sheet, and leaving the raster out is not a simplification.
     *
     * The desktop draws the export raster on its device as well, through OpenGL compute; the WGSL
     * port of that raster has not been written, so in a browser the device runs the erosion sweeps,
     * the stream-function solve and the sheet's profile, and nothing else. Saying otherwise here
     * would be promising a speed-up that does not exist.
     */
    override val acceleratedWork: String = "erosion, ocean currents and the ice sheet"

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

    /**
     * A folder on the disk, where the browser offers `showDirectoryPicker`: Chrome and Edge. Asked
     * of the feature rather than of the browser's name, so a browser that adds the API gets the
     * choice and one that removes it loses it; without it this is null and nothing else about the
     * library changes. See [BrowserFolderChooser].
     */
    override val folderChooser: FolderChooser? =
        if (directoryPickerAvailable()) BrowserFolderChooser(compressor) else null

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

    /** "Browser", which is the word the bug form's platform dropdown offers. */
    override val hostName: String = "Browser"

    /** See [clipboardAvailable]: the modern call in a secure context, or the old one. */
    override val canCopyToClipboard: Boolean = clipboardAvailable()

    override fun copyToClipboard(text: String) {
        copyTextToClipboard(text)
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

    /**
     * The save written a chunk at a time into pieces the browser holds, and handed over as one file.
     * The name is the reader's to change: the desktop's library lists and opens a save by its file
     * name, whatever that is.
     */
    override suspend fun downloadWorld(document: WorldDocument, world: WorldMap) {
        val sink = DownloadSink()
        WorldCodec.write(document, world, sink, compressor, "web")
        downloadParts("cartogenesis-${document.id}.cgw", sink.parts, "application/octet-stream")
    }

    override suspend fun uploadWorld(): LoadOutcome? {
        val source = pickFile() ?: return null
        return WorldCodec.open(source, compressor)
    }

    override suspend fun export(
        world: WorldMap,
        options: RenderOptions,
        size: Int,
        format: ExportFormat
    ): ExportOutcome? {
        val started = epochMillisNow()

        // As on the desktop: the world on screen at its own size, and at any other one made again
        // at that size rather than stretched, so the detail is real.
        val subject = ExportSubjects.at(world, size, accelerator, oceanAccelerator, iceAccelerator)

        // Drawn with exactly the preview's options: every mark the renderer makes is sized where it
        // is made, in output pixels or as a share of the sheet, so an export needs no scaling here.
        // A printed sheet, as on the desktop: nothing generalised away, and its own scale bar. The
        // whole true-shape sheet, so a 4096 world is an 8192 by 4096 picture; immutable, so the
        // encoder's Image shares its 128 MB of pixels rather than copying them.
        val bitmap = MapImage.toBitmap(subject.world, options, MapSheet.PRINTED)
        bitmap.setImmutable()
        // Quality is ignored by the PNG encoder and lossless for WebP at 100; JPEG is the one
        // format with a real quality to choose, and it is chosen once, in [ExportFormat].
        val quality =
            if (format == ExportFormat.JPEG) ExportFormat.JPEG_QUALITY else LOSSLESS_QUALITY
        val image = Image.makeFromBitmap(bitmap)
        val encoded = image.encodeToData(skiaFormat(format), quality = quality)
            ?: error("Could not encode the map as ${format.label}")
        val bytes = encoded.bytes
        image.close()
        bitmap.close()

        val name = "cartogenesis-${world.config.seed}-$size.${format.extension}"
        downloadBytes(name, bytes, mimeType(format))

        return ExportOutcome(name, epochMillisNow() - started, bytes.size.toLong(), subject.source)
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
        world: WorldMap,
        size: Int,
        layer: DataLayer
    ): ExportOutcome? {
        val started = epochMillisNow()

        val subject = ExportSubjects.at(world, size, accelerator, oceanAccelerator, iceAccelerator)
        val files = DataExports.write(subject.world, layer, compressor, BuildInfo.VERSION, subject.source)

        val bytes = files.asZip()
        val name = "${DataExports.baseName(world.config, size, layer)}.zip"
        downloadBytes(name, bytes, "application/zip")

        return ExportOutcome(
            "$name (${files.imageName} and ${files.sidecarName})",
            epochMillisNow() - started,
            bytes.size.toLong(),
            subject.source
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
