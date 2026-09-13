package com.cartogenesis.ui

import com.cartogenesis.cartography.Compressor
import com.cartogenesis.cartography.DataLayer
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.WorldDocument
import com.cartogenesis.cartography.WorldLibrary
import com.cartogenesis.cartography.WorldSave
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ErosionAccelerator

/**
 * What an exported map is written as.
 *
 * PNG is lossless. WebP is about a quarter the size but is *not* lossless: Skia exposes only the
 * lossy encoder, and even at maximum quality the loss falls where it is least welcome on a map.
 * Measured against the PNG of the same world, the average pixel drifts about 4 of 255 — invisible
 * — but the worst 0.1% drift by about 75, and those are the river lines, borders and coastlines,
 * because that is where the sharp edges are.
 *
 * JPEG is here for compatibility, for the programs that still will not open a WebP, and the small
 * print says so rather than presenting it as a third equally good choice. What it does *not* say is
 * that WebP is the smaller file, which was the expectation this chunk went in with and is not what
 * the encoders do: at the qualities shipped here — WebP at Skia's lossy maximum, JPEG at
 * [JPEG_QUALITY] — the JPEG is smaller and slightly worse, and at a matched quality of 100 the JPEG
 * is the larger of the two. So the note claims the one thing that measures true both ways round,
 * which is that WebP is kinder to the thin marks a map is made of. `DataExportTest` prints all four
 * figures on every run.
 */
enum class ExportFormat(val label: String, val extension: String, val detail: String) {
    PNG("PNG", "png", "Lossless. Larger file, exact detail."),
    WEBP("WebP", "webp", "About a quarter the size. Slightly softens rivers and borders."),
    JPEG(
        "JPEG",
        "jpg",
        "For tools that will not open a WebP. Smaller than WebP here, and harder on thin " +
            "lines: rivers and borders soften more."
    );

    companion object {
        /**
         * Where the JPEG encoders on both hosts are asked to sit.
         *
         * High enough that the loss stays in the same band as the WebP this stands in for — 55
         * against WebP's 53 of 255 at the 99th percentile on seed 42 at 512 — and low enough to be
         * worth writing at all: the same encoder at 100 produces a file larger than the WebP and
         * still not lossless.
         */
        const val JPEG_QUALITY = 90
    }
}

/** Where an exported map ended up, in whatever terms the host can describe. */
class ExportOutcome(val description: String, val millis: Long, val bytes: Long)

/**
 * Everything the shared interface cannot do for itself.
 *
 * The list is deliberately short. A browser tab and a desktop window differ in where files live,
 * what "save this image" means, and whether there is a graphics device to offer — and essentially
 * nowhere else. Anything longer than this would be a sign that platform detail had leaked into the
 * application rather than staying at its edge.
 */
interface Platform {

    /**
     * The working resolution to start at.
     *
     * A platform decision rather than a preference. The JVM spreads generation across every core
     * and can reach for a GPU; a browser tab has one thread, and `Dispatchers.Default` there is
     * that same thread, so generating does not merely take longer — it stops the page answering
     * until it finishes. Starting the web build smaller is the difference between a wait and an
     * apparent hang.
     */
    val defaultResolution: Int

    /** Where saved worlds are kept. */
    val library: WorldLibrary

    /**
     * How a save's payload is squeezed on the way out, and expanded on the way in.
     *
     * A save carries the world now, which is tens of megabytes of arrays, and neither the zip
     * code the JVM has nor the `CompressionStream` a browser has exists in common code. A
     * platform with neither returns [com.cartogenesis.cartography.NoCompression] and its files
     * say so in their header, so they still open anywhere.
     */
    val compressor: Compressor

    /** Shown in the library so someone can find their files. */
    val libraryLocation: String

    /**
     * Whether the library pane's download/upload buttons appear.
     *
     * The desktop's library already lives on disk as ordinary `.cgw` files a user can move by
     * hand, so it declines this rather than duplicating a file dialog the OS already gives them.
     * The browser's library lives in IndexedDB, invisible to anything outside the page, so a
     * download and a file picker are the only way a save moves in or out of it — which is also
     * the only way a world crosses between the two front ends, since the format is shared.
     *
     * A runtime flag rather than an `expect`/`actual` split: the pane is shared code, and what it
     * draws should depend on what this platform can do, not on which target compiled it.
     */
    val supportsFileTransfer: Boolean get() = false

    /**
     * Hands [world] to the user as a `.cgw` file, exactly as [library] would have written it. A
     * no-op where [supportsFileTransfer] is false.
     */
    suspend fun downloadWorld(document: WorldDocument, world: WorldMap?) {}

    /**
     * Opens a file picker and decodes whatever the user chose, or returns null if they cancelled,
     * the file did not parse, or this platform offers no such picker.
     */
    suspend fun uploadWorld(): WorldSave? = null

    /**
     * The accelerator to offer, or null if this machine cannot provide one — in which case
     * [accelerationUnavailableBecause] should say why, since a disabled switch with no explanation
     * is worse than no switch.
     */
    val accelerator: ErosionAccelerator?

    val accelerationUnavailableBecause: String?

    /**
     * What the graphics device is actually doing here, for the line of small print under the
     * switch. [device] is [accelerator]'s own name.
     *
     * The two front ends do not do the same amount on it, and one sentence for both would tell
     * one of them a smaller truth than it is owed: the desktop draws the export raster on the
     * device as well as running the erosion sweeps, while the browser's WGSL raster has not been
     * written, so there it really is erosion alone. A sentence that says "erosion" everywhere understates
     * the desktop; one that says "erosion and export rendering" everywhere is simply wrong in a
     * browser. So the host answers, which is what this seam is for. The default is the desktop's,
     * because a `Platform` that has not thought about the question is one with a real graphics API
     * behind it.
     */
    fun acceleratedWork(device: String): String =
        "Erosion and export rendering run on $device, many times faster."

    /**
     * Whether this host has a graphics API at all — OpenGL on the desktop, WebGPU in a browser.
     *
     * Not the same question as [accelerator] being non-null, and the difference is the whole reason
     * this exists. A desktop whose driver refused the context has OpenGL and a reason; the switch
     * is drawn disabled and the reason is printed beside it, which is what a reader is owed. A
     * phone browser with no `navigator.gpu` has no such feature to explain, and 60 dp of a 390 dp
     * screen spent saying so is 60 dp taken from the map — so the switch is not drawn at all. See
     * [Arrangements.headerKnobs].
     *
     * True by default: a host that does not answer is assumed to have one, which leaves the switch
     * where it was and lets [accelerationUnavailableBecause] do the explaining.
     */
    val graphicsApiPresent: Boolean get() = true

    /**
     * Whether the reader is pointing at this with a fingertip rather than a mouse.
     *
     * `(pointer: coarse)` in a browser, and false on the desktop. It decides two things: the
     * arrangement (a tablet in landscape is wide enough for the panel and still cannot be driven
     * with a 13 dp slider thumb — see [Layouts.shape]) and the size of every touch target in the
     * theme.
     */
    val coarsePointer: Boolean get() = false

    /**
     * The largest export this build can actually finish.
     *
     * Not a taste: 8192 does not complete. It exhausts a 10 GB heap inside the generator after
     * about nineteen minutes, before a single pixel of the map is drawn — so the chip for it is
     * offered disabled rather than removed, and any size above this one falls back to it. See
     * REALISM_PLAN.md for the measurement. It is a value on the platform, and not a constant in the panel, so that the build
     * which fixes the memory can raise the ceiling without the interface being touched: the export
     * row draws whatever this says.
     *
     * [compact] is true in a phone-shaped window, and is a question rather than an assumption
     * because the answer differs by host: a desktop window narrowed to 700 dp is still a desktop
     * with every core and a 12 GB heap, while the same 700 dp in a browser is a phone with one
     * thread. The web front end caps itself at 2048 there; the desktop ignores the argument.
     */
    fun exportCeiling(compact: Boolean): Int = 4096

    /**
     * Renders at [size] and puts the result wherever this platform puts finished files: a chosen
     * path on the desktop, a download in a browser. Returns null if the user backed out.
     */
    suspend fun export(
        config: WorldGenConfig,
        options: RenderOptions,
        size: Int,
        format: ExportFormat
    ): ExportOutcome?

    /**
     * The same at [size], but writing the world's own numbers rather than a picture of them: a
     * sixteen-bit heightmap, or a biome or realm index map. Returns null if the user backed out.
     *
     * No [RenderOptions], and that is the whole difference in the signature: a data export carries
     * no style, no view and no overlays, because none of those are properties of the world. What it
     * carries instead is a sidecar — see [com.cartogenesis.cartography.DataFiles] — which is why
     * this is a second call rather than another [ExportFormat]: the result is two files, and how a
     * host hands somebody two files is exactly the sort of question this interface exists to ask.
     * The desktop writes them side by side; the browser sends one zip.
     *
     * Declining by default, as [downloadWorld] does: a host with nowhere to put a file says so by
     * saying nothing, and the interface reports that the export did not happen.
     */
    suspend fun exportData(
        config: WorldGenConfig,
        size: Int,
        layer: DataLayer
    ): ExportOutcome? = null

    // ---- What a menu strip, a settings file and an update check need from the host. ----

    /**
     * Where this platform keeps the settings, and how it reads and writes them.
     *
     * Deliberately a store of *text* rather than of [AppSettings]. Both hosts can keep a string
     * somewhere durable and neither can be trusted with the shape of the settings object, so the
     * serialising, the defaulting and the forward compatibility all stay in shared code where they
     * are testable — see [SettingsCodec] — and the platform is left with the one thing only it can
     * do, which is to put a string somewhere it survives a restart.
     *
     * The default is in memory, so a host that has not implemented it (and every test fake that
     * does not care) still round-trips within a session instead of dropping writes.
     */
    val settingsStore: SettingsStore get() = EphemeralSettings

    /** Whether "Quit" belongs on the File menu. A window can be closed; a browser tab cannot. */
    val canQuit: Boolean get() = false

    /** Closes the application. Only ever called when [canQuit]. */
    fun quit() {}

    /**
     * Opens [url] outside the application — a release page, in practice.
     *
     * `Desktop.browse` on the desktop, `window.open` in a browser. False in [canOpenLinks] means
     * the About and update dialogs print the address instead of offering a button, which is more
     * use than a button that does nothing.
     */
    val canOpenLinks: Boolean get() = false

    fun openLink(url: String) {}

    /** Whether Settings can offer "Open folder" beside the library path. */
    val canRevealFolder: Boolean get() = false

    fun revealFolder(path: String) {}

    /**
     * Points the library at another folder, returning whether it took.
     *
     * A folder is a desktop idea: the browser's library is IndexedDB and has nowhere else to be,
     * so it declines. Suspend because moving the library means listing the new place.
     */
    suspend fun useLibraryFolder(path: String): Boolean = false

    /**
     * Fetches [url] as text, or null if this platform cannot, the request failed, or the machine
     * is offline.
     *
     * The one call the application makes over the network, and the only reason it exists is the
     * update check. Null rather than an exception because every failure here — no network, a
     * proxy, GitHub down, a platform with no HTTP client at all — is the same answer to the only
     * question being asked, which is "is there a newer release?". [Updates] turns null into a
     * sentence for the reader.
     *
     * Nothing calls this at launch unless [AppSettings.checkForUpdatesOnLaunch] is on, which is
     * off by default: opening the application must not talk to GitHub because it was opened.
     */
    suspend fun fetchText(url: String): String? = null
}

/**
 * Somewhere durable to keep one string.
 *
 * A JSON file under the user's configuration directory on the desktop; browser storage on the web.
 * Both are asked for and given the whole document at once: the settings are a few hundred bytes
 * and there is nothing to be gained by making this a key-value store, which would only move the
 * question of what the keys are out of shared code and into two places.
 */
interface SettingsStore {

    /** The stored text, or null if nothing has been written yet or it could not be read. */
    suspend fun read(): String?

    /** Writes [text], replacing whatever was there. Failures are swallowed by the caller. */
    suspend fun write(text: String)

    /** Where this is kept, in the host's own terms, for the dialog's small print. */
    val location: String get() = "This session only"
}

/**
 * The fallback store: durable for as long as the application is running, and no longer.
 *
 * An object rather than a class because an interface's default property has to return the *same*
 * store on every call or a write and the read after it would land in different places.
 */
internal object EphemeralSettings : SettingsStore {
    private var held: String? = null
    override suspend fun read(): String? = held
    override suspend fun write(text: String) {
        held = text
    }
}

/** Wall-clock milliseconds, for stamping a save. */
expect fun epochMillis(): Long

/** A fresh identifier for a saved world. */
expect fun randomId(): String

/** A saved world's timestamp, in whatever form is natural for the host. */
expect fun formatTimestamp(millis: Long): String
