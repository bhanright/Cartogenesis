package com.cartogenesis.ui

import com.cartogenesis.cartography.Compressor
import com.cartogenesis.cartography.DataLayer
import com.cartogenesis.cartography.ExportedWorld
import com.cartogenesis.cartography.LoadOutcome
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.WorldDocument
import com.cartogenesis.cartography.WorldLibrary
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ErosionAccelerator
import com.cartogenesis.worldgen.pipeline.IceSheetAccelerator
import com.cartogenesis.worldgen.pipeline.OceanAccelerator

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
    // Three sentences of the same shape, each saying what the format keeps and what it costs: the
    // row is read as one choice, and a reader comparing three lines written three different ways
    // is comparing the writing rather than the formats.
    PNG("PNG", "png", "PNG keeps every pixel and makes the largest file."),
    WEBP(
        "WebP",
        "webp",
        "WebP is about a quarter the size and softens rivers and borders slightly."
    ),
    JPEG(
        "JPEG",
        "jpg",
        "JPEG is for tools that will not open a WebP; it is smaller still and softens " +
            "rivers and borders more."
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

/**
 * Where an exported map ended up, in whatever terms the host can describe, and whether it drew the
 * world on screen or one made again at the export's size — see [ExportSubjects].
 */
class ExportOutcome(
    val description: String,
    val millis: Long,
    val bytes: Long,
    val source: ExportedWorld = ExportedWorld.OnScreen
)

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
     * The one grid this host makes every world on, named by its rows (the grid is twice as many
     * cells across).
     *
     * A platform decision rather than a preference, and no longer a starting point: the interface
     * offers no other grid (docs/DESIGN_LEDGER.md, G1). A value on the platform rather than a
     * constant, so that nothing in the interface depends on the number, and a test platform can
     * make its worlds small.
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
     * How the reader may move [library] into a folder on the disk they choose, or null where this
     * host offers no such thing.
     *
     * A browser question. Chrome and Edge let a page ask for a folder and keep it across visits
     * (the File System Access API); Firefox and Safari do not, and there the library stays in the
     * browser's own storage with nothing offered in its place. The desktop's library is a folder
     * already, chosen in Settings by its path, so it answers null too. See [LibraryPlaces], which
     * is what the interface asks rather than asking this directly.
     */
    val folderChooser: FolderChooser? get() = null

    /**
     * Whether the library pane's download/upload buttons appear.
     *
     * The desktop's library already lives on disk as ordinary `.cgw` files a user can move by
     * hand, so it declines this rather than duplicating a file dialog the OS already gives them.
     * The browser's library lives in IndexedDB, invisible to anything outside the page, so a
     * download and a file picker are the only way a save moves in or out of it — and, in a browser
     * with no [folderChooser], the only way a world crosses between the two front ends, since the
     * format is shared. Where the reader has put the library in a folder, the buttons stay: a
     * download is still the way to hand one world to somebody else.
     *
     * A runtime flag rather than an `expect`/`actual` split: the pane is shared code, and what it
     * draws should depend on what this platform can do, not on which target compiled it.
     */
    val supportsFileTransfer: Boolean get() = false

    /**
     * Hands [world] to the user as a `.cgw` file, exactly as [library] would have written it. A
     * no-op where [supportsFileTransfer] is false.
     */
    suspend fun downloadWorld(document: WorldDocument, world: WorldMap) {}

    /**
     * Opens a file picker and reads whatever the user chose: the world, or the reason it will not
     * open. Null only when they cancelled or this platform offers no such picker — a file that does
     * not parse is a refusal with its reason, not a cancellation.
     */
    suspend fun uploadWorld(): LoadOutcome? = null

    /**
     * The accelerator to offer, or null if this machine cannot provide one — in which case
     * [accelerationUnavailableBecause] should say why, since a disabled switch with no explanation
     * is worse than no switch.
     */
    val accelerator: ErosionAccelerator?

    /**
     * Where to solve the ocean's stream function, or null to leave it on the processor.
     *
     * Behind the same switch as [accelerator], and for the same reason: the gyres set the sea
     * temperature, which sets the climate, so a solve that rounds differently is a different world
     * in exactly the sense erosion's terrain is.
     */
    val oceanAccelerator: OceanAccelerator? get() = null

    /**
     * Where to draw the ice sheet's profile and the flow down its surface, or null to leave both
     * on the processor.
     *
     * Behind the same switch as [accelerator], and with more riding on it than either of the
     * others: the sheet's surface *is* the elevation the rest of the pipeline reads, so a profile
     * that rounds differently is a different world in the plainest possible sense. The switch is
     * the config's, and the glaciation stage reads it where it would call the device, so a host
     * offers its device here whatever the reader has chosen and nothing is drawn on it while the
     * switch is off.
     */
    val iceAccelerator: IceSheetAccelerator? get() = null

    val accelerationUnavailableBecause: String?

    /**
     * What the graphics device is actually doing here, as a list that fits inside a sentence.
     *
     * The two sentences the interface prints from it — [accelerationRunning] under a switch that
     * is on, [accelerationOffered] under one that is off — are built in shared code, so the offer
     * cannot come to promise work the running note does not claim.
     *
     * The two front ends do not do the same amount on it, and one sentence for both would tell
     * one of them a smaller truth than it is owed: the desktop draws the export raster on the
     * device as well as running the erosion sweeps and the current solve, while the browser's WGSL
     * raster has not been written, so there it really is erosion and the currents alone. A sentence
     * that names the raster everywhere is wrong in a browser; one that omits it understates the
     * desktop. So the host answers, which is what this seam is for. The default is the desktop's,
     * because a `Platform` that has not thought about the question is one with a real graphics API
     * behind it.
     */
    val acceleratedWork: String get() =
        "erosion, ocean currents, the ice sheet and export rendering"

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
     * The largest world this host can hold, as its size is named, by its rows (the grid is twice
     * as many cells across).
     *
     * The interface makes one grid, [defaultResolution], so this no longer bounds a choice; it is
     * the guard on the worlds that arrive at another grid — a save or a link of the years when
     * the grid was a choice — which are refused in a sentence, with [WorldCeilings.whyOutOfReach],
     * rather than begun on a heap that cannot finish them. [WorldCeilings.DESKTOP] by default,
     * [WorldCeilings.forDesktopHeap] of [heapBytes] on the desktop; each says what was measured
     * to put it there.
     */
    val generationCeiling: Int get() = WorldCeilings.DESKTOP

    /**
     * The most memory this host's runtime will hold, in bytes, where it can say: the desktop's
     * largest heap, which decides whether it offers [WorldCeilings.LARGEST_DESKTOP] and lets a
     * reason for a size out of reach say what the machine lacks. Null where the host cannot say,
     * as a browser cannot.
     */
    val heapBytes: Long? get() = null

    /**
     * Draws [world] at the size named [size], by its rows, and puts the result wherever this
     * platform puts finished files: a chosen path on the desktop, a download in a browser. Returns
     * null if the user backed out.
     *
     * [world] is the world on screen. At its own size it is what is drawn; at any other size the
     * platform draws the world [ExportSubjects.at] makes from it, and says so in the outcome.
     */
    suspend fun export(
        world: WorldMap,
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
        world: WorldMap,
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

    /**
     * Whether [copyToClipboard] puts anything anywhere.
     *
     * The same shape as [canOpenLinks] and for the same reason: a bug report's dialog says what
     * was copied, and a host with no clipboard to copy to has to be able to make it say something
     * else — see [BugReportDialog] — rather than claim a copy that never happened.
     */
    val canCopyToClipboard: Boolean get() = false

    /**
     * Puts [text] on the system clipboard. A no-op where [canCopyToClipboard] is false.
     *
     * AWT's clipboard on the desktop, `navigator.clipboard` in a browser. The one caller is the
     * bug report, which is also why nothing here reads the clipboard: the application has no
     * business knowing what a reader has copied.
     */
    fun copyToClipboard(text: String) {}

    /**
     * The whole address this application was opened at, query and fragment included, or null where
     * it was not opened at an address at all.
     *
     * A browser answers with the page's own address, read once when the page loads, and that is
     * where a link to a world arrives: see [WorldLinks.read]. The desktop is started from a
     * program, not an address, and answers null, so it opens as it always has.
     */
    val openedAt: String? get() = null

    /**
     * The page address a copied link to a world begins with, with no query or fragment of its own.
     *
     * The browser answers with its own page, so a link copied from a test deployment or a local
     * build opens in that build. The desktop is no page, and its link has to open for somebody who
     * has never installed anything, so it takes the published application's address, which is the
     * default: [WorldLinks.PUBLIC_APP_ADDRESS].
     */
    val worldLinkBase: String get() = WorldLinks.PUBLIC_APP_ADDRESS

    /**
     * Which front end this is, in the word a bug report's form offers: "Desktop" or "Browser".
     *
     * A report of a browser's behaviour and a report of the desktop's are different reports — the
     * ceiling on an export, the thread the generation runs on and the graphics API all differ —
     * and it is the one fact about the host that the world in the window cannot supply.
     *
     * The desktop's answer is the default, as [acceleratedWork]'s and [graphicsApiPresent]'s are:
     * a `Platform` that has not been asked this question is a real window on a real machine.
     */
    val hostName: String get() = "Desktop"

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
 * The small print under a graphics switch that is on: what is running on [device] rather than on
 * the processor.
 *
 * Built from [Platform.acceleratedWork] rather than written out, so a host that does less on its
 * device says less here without anyone having to remember that it should.
 */
internal fun Platform.accelerationRunning(device: String): String =
    acceleratedWork.replaceFirstChar { it.uppercase() } + " run on $device, many times faster."

/**
 * The same under a switch that is off, and a different offer: not what is happening, but what
 * could be, and against what.
 *
 * "Many times faster" is measured rather than hopeful — see the erosion benchmarks in
 * docs/DESIGN_LEDGER.md — and "than the processor" is the comparison it is faster *than*, which the
 * sentence used to leave the reader to supply.
 */
internal fun Platform.accelerationOffered(device: String): String =
    "$device can run $acceleratedWork, many times faster than the processor."

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

    /**
     * Writes [text], replacing whatever was there. Failures are swallowed by the caller.
     *
     * When calls overlap, the text of the last call made is what is left stored: the application
     * writes the preferences on every mark a slider passes, so overlapping calls are the ordinary
     * case and not a corner of one.
     */
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

/**
 * The ceilings a host can have on the size of world it makes, and the sentence for a size above
 * one of them. See [Platform.generationCeiling]. Sizes are named by their rows, the grid twice as
 * many cells across. What each was measured at is in docs/DESIGN_LEDGER.md, Q5 and Q6.
 *
 * Public because the desktop front end, which is a module of its own, declares its own.
 */
object WorldCeilings {

    /**
     * The largest world every desktop offers: 2048 rows, a grid 4096 by 2048.
     *
     * Held to the heap the packaged app takes on a 16 GB machine, three quarters of its memory or
     * 12 GB, because a size every desktop offers has to finish on an ordinary machine and not only
     * on the one it was measured on. 2048 rows finished in three and a half minutes on the
     * processor with 3.0 GB live at its fullest and 4.8 GB resident. A desktop whose heap holds
     * more offers [LARGEST_DESKTOP] as well; see [forDesktopHeap].
     */
    const val DESKTOP: Int = 2048

    /**
     * The largest world a desktop offers where its heap can hold it: 4096 rows, a grid 8192 by
     * 4096, offered where the heap is at least [HEAP_FOR_LARGEST_DESKTOP_BYTES].
     */
    const val LARGEST_DESKTOP: Int = 4096

    /** A mebibyte, the unit the 4096 world's memory was measured in. */
    private const val MEBIBYTE: Long = 1L shl 20

    /** A gibibyte, the unit a machine's memory is sold in and a reason states it in. */
    private const val GIBIBYTE: Long = 1L shl 30

    /**
     * The most of the heap the 4096 world held live at once, after a collection: 10,279 MiB, seed
     * 42 with acceleration on, generated, drawn, exported and saved under a 12 GiB heap
     * (docs/DESIGN_LEDGER.md, Q5).
     */
    private const val LIVE_AT_LARGEST_DESKTOP_BYTES: Long = 10_279 * MEBIBYTE

    /**
     * What the 4096 world held outside the heap: its peak working set, 14,037 MiB, less the
     * 12,288 MiB heap, the most of it that could have been committed; at least 1,749 MiB of the
     * graphics card's buffers, Skia's bitmaps and the runtime's own, none of which the heap's
     * limit bounds (docs/DESIGN_LEDGER.md, Q5).
     */
    private const val OUTSIDE_HEAP_AT_LARGEST_DESKTOP_BYTES: Long = (14_037 - 12_288) * MEBIBYTE

    /**
     * Memory the system keeps for itself while the app runs: 4 GiB, Windows 11's stated minimum
     * memory, which a machine that runs it has to leave the system to go on running.
     */
    private const val SYSTEM_RESERVE_BYTES: Long = 4 * GIBIBYTE

    /**
     * The share of a machine's memory the packaged desktop app takes as its heap: three quarters,
     * `-XX:MaxRAMPercentage=75` in `desktop/build.gradle.kts` (`DesktopCeilingTest` holds the two
     * together). The quarter left is where the system and everything the app holds outside its
     * heap must fit.
     */
    const val PACKAGED_HEAP_SHARE_OF_MEMORY: Double = 0.75

    /**
     * The share of the heap the live set may fill at its fullest and still leave the collector room
     * to work: two thirds. A choice, not a measurement: the one 4096 run filled 84% and finished,
     * which bounds how little room works rather than saying how much is comfortable.
     */
    private const val LIVE_SHARE_OF_HEAP: Double = 2.0 / 3.0

    /**
     * The least heap on which a desktop offers [LARGEST_DESKTOP]: 17,535 MiB, 17.1 GiB, the larger
     * of what two things ask.
     *
     * - The live set with room for the collector: 10,279 MiB over two thirds, 15,419 MiB.
     * - The memory outside the heap: the app's own there (1,749 MiB) and the system's (4 GiB)
     *   must fit in the quarter the packaged heap leaves, which is a third of the heap, so the
     *   heap must be three times their sum, 17,535 MiB.
     *
     * The second binds, and would so long as less than 41% of the heap were asked to be free. A
     * machine with three quarters of its memory at least this has 22.8 GiB: a 24 GB machine is
     * offered 4096 and a 16 GB machine, whose 12 GiB heap is the one Q5 measured with 2 GB left
     * for everything else, is not.
     */
    val HEAP_FOR_LARGEST_DESKTOP_BYTES: Long = maxOf(
        (LIVE_AT_LARGEST_DESKTOP_BYTES / LIVE_SHARE_OF_HEAP).toLong(),
        ((OUTSIDE_HEAP_AT_LARGEST_DESKTOP_BYTES + SYSTEM_RESERVE_BYTES) *
            (PACKAGED_HEAP_SHARE_OF_MEMORY / (1 - PACKAGED_HEAP_SHARE_OF_MEMORY))).toLong()
    )

    /**
     * The ceiling of a desktop whose largest heap is [heapBytes]: [LARGEST_DESKTOP] where the heap
     * is at least [HEAP_FOR_LARGEST_DESKTOP_BYTES], [DESKTOP] below it.
     */
    fun forDesktopHeap(heapBytes: Long): Int =
        if (heapBytes >= HEAP_FOR_LARGEST_DESKTOP_BYTES) LARGEST_DESKTOP else DESKTOP

    /** [bytes] in gibibytes to a tenth, as a reason prints them. */
    private fun gibibytes(bytes: Long): String {
        val tenths = kotlin.math.round(bytes * 10.0 / GIBIBYTE).toLong()
        return "${tenths / 10}.${tenths % 10}"
    }

    /** The least memory, in whole gibibytes, a machine needs for the packaged heap to reach [HEAP_FOR_LARGEST_DESKTOP_BYTES]. */
    val MEMORY_FOR_LARGEST_DESKTOP_GIBIBYTES: Long =
        kotlin.math.ceil(HEAP_FOR_LARGEST_DESKTOP_BYTES / PACKAGED_HEAP_SHARE_OF_MEMORY / GIBIBYTE).toLong()

    /**
     * The largest world a browser tab makes: 1024 rows, a grid 2048 by 1024.
     *
     * Measured in a tab of the production build on this project's desktop: 1024 rows generated
     * and was drawn in 172 s with the tab's JavaScript heap sampled at 1.7 GB at its fullest. 2048
     * rows is four times the cells, 8.4 million, twice the world as many cells tall as wide that
     * finished in a tab near 2.3 GB, and half the one whose tab died past 2.7 GB; at that heap per
     * cell it wants more than the 4 GB a Wasm heap can address, so a tab neither makes nor opens it.
     * See docs/TODO.md, "A 4096 world cannot be made in a browser tab".
     */
    const val BROWSER_TAB: Int = 1024

    /**
     * Why a world of the size named [size] cannot be made under [ceiling], on a host whose largest
     * heap is [heapBytes] where it can say ([Platform.heapBytes]), or null when it can.
     *
     * A size every desktop reaches and this host does not can only be a browser's limit, because
     * the browser's is the only ceiling below [DESKTOP]. A size only a desktop with the memory
     * reaches, [LARGEST_DESKTOP], is the machine's limit: the sentence says how much the app needs
     * and, where the host can say, how much this machine gives it. A size above that waits for a
     * later release everywhere. Each sentence says where the size can be had, because a reader told
     * only "no" has no idea whether to ask again.
     */
    fun whyOutOfReach(size: Int, ceiling: Int, heapBytes: Long? = null): String? {
        val appNeeds = "${gibibytes(HEAP_FOR_LARGEST_DESKTOP_BYTES)} GB of memory for the app"
        val machineWith = "a machine with $MEMORY_FOR_LARGEST_DESKTOP_GIBIBYTES GB of memory or more"
        return when {
            size <= ceiling -> null
            size <= DESKTOP -> "A $size world needs more memory than a browser tab is given; the desktop app makes it"
            size <= LARGEST_DESKTOP -> when {
                ceiling < DESKTOP ->
                    "A $size world needs more memory than a browser tab is given; the desktop app makes it on $machineWith"
                heapBytes != null ->
                    "A $size world needs $appNeeds and this machine gives it ${gibibytes(heapBytes)} GB; $machineWith makes it"
                else -> "A $size world needs $appNeeds; $machineWith makes it"
            }
            else -> "$size needs more memory than this build can hold; it waits for a later release"
        }
    }
}
