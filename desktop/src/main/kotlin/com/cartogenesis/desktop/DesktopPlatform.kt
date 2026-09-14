package com.cartogenesis.desktop

import com.cartogenesis.cartography.Compressor
import com.cartogenesis.cartography.DataLayer
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.WorldLibrary
import com.cartogenesis.ui.ExportFormat
import com.cartogenesis.ui.ExportOutcome
import com.cartogenesis.ui.Platform
import com.cartogenesis.ui.SettingsStore
import com.cartogenesis.worldgen.model.WorldGenConfig
import com.cartogenesis.worldgen.pipeline.ErosionAccelerator
import com.cartogenesis.worldgen.pipeline.OceanAccelerator
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the desktop can do that the shared interface cannot assume.
 *
 * Files on disk, a native save dialog, and a real graphics device reached through OpenGL. The
 * browser build answers the same questions with local storage, a download, and WebGPU, and neither
 * front end knows the other exists.
 */
class DesktopPlatform(
    /**
     * How File ▸ Quit closes the window.
     *
     * Handed in rather than calling `exitProcess` here, so that the real front end can use
     * Compose's own `exitApplication` (which lets the window close properly) while a test that
     * happens to construct a platform cannot bring the test JVM down by mistake.
     */
    private val onQuit: () -> Unit = {}
) : Platform {

    // Every core available and a 12GB heap, so there is no reason to start small.
    override val defaultResolution: Int = 1024

    /**
     * Where saved worlds live, which the reader can move — see [useLibraryFolder].
     *
     * A `var` behind a getter rather than a `val`, because the library folder is a setting now and
     * a setting that could only be applied by restarting is a setting nobody trusts.
     */
    private var store: DesktopWorldStore = DesktopWorldStore()

    override val library: WorldLibrary get() = store

    override val compressor: Compressor = GzipCompressor

    override val libraryLocation: String get() = store.location

    override suspend fun useLibraryFolder(path: String): Boolean {
        val directory = File(path)
        if (!directory.isDirectory && !directory.mkdirs()) return false
        if (!directory.canWrite()) return false
        store = DesktopWorldStore(directory)
        return true
    }

    /**
     * `%APPDATA%\Cartogenesis\settings.json` on Windows, and the equivalent elsewhere.
     *
     * The user's configuration directory rather than beside the library or beside the jar: a
     * preference belongs to the person, not to their worlds and not to this installation, so it
     * survives moving the library, reinstalling, and running the portable build from a stick.
     */
    override val settingsStore: SettingsStore = FileSettings(configDirectory().resolve("settings.json"))

    override val canQuit: Boolean = true

    override fun quit() = onQuit()

    override val canOpenLinks: Boolean = Desktop.isDesktopSupported() &&
        Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)

    override fun openLink(url: String) {
        runCatching { Desktop.getDesktop().browse(URI(url)) }
    }

    override val canRevealFolder: Boolean = Desktop.isDesktopSupported() &&
        Desktop.getDesktop().isSupported(Desktop.Action.OPEN)

    override fun revealFolder(path: String) {
        runCatching { Desktop.getDesktop().open(File(path)) }
    }

    /**
     * One `GET`, with short timeouts, on a thread that is not the interface's.
     *
     * `java.net.http` rather than a dependency: this is the only HTTP the desktop build does, and
     * the JDK has had a perfectly good client since 11. Every failure — offline, DNS, a proxy, a
     * rate limit, a body that never arrives — comes back as null, because the caller has exactly
     * one question and null is one of its two answers.
     */
    override suspend fun fetchText(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
            val request = HttpRequest.newBuilder(URI(url))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Cartogenesis")
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in HTTP_SUCCESS) response.body() else null
        }.getOrNull()
    }

    // Probed once, at startup. A machine with no usable device gets the switch disabled and told
    // why, which is more use than a switch that silently does nothing.
    private val erosionProbe = GpuErosion.createOrNull()

    // The export raster shares that device and the context it runs on. It is deliberately not
    // behind the same switch: the erosion one is a promise about whether the world can be
    // regenerated from its seed, and drawing pixels makes no such promise either way.
    private val rasterProbe = GpuRaster.createOrNull()

    // The stream-function solve shares them too, and is behind the switch, because a gyre solved
    // on the card is a different world in the same sense erosion's terrain is.
    private val oceanProbe = GpuOcean.createOrNull()

    override val accelerator: ErosionAccelerator? get() = erosionProbe.accelerator

    override val oceanAccelerator: OceanAccelerator? get() = oceanProbe.accelerator

    override val accelerationUnavailableBecause: String? get() = erosionProbe.unavailableBecause

    override suspend fun export(
        config: WorldGenConfig,
        options: RenderOptions,
        size: Int,
        format: ExportFormat
    ): ExportOutcome? {
        // The dialog is native and has to run on the caller's thread; the rendering behind it must
        // not, or the window stops answering for the best part of a minute.
        val destination = chooseSaveFile(Exporter.defaultName(config, size, format)) ?: return null
        val result = withContext(Dispatchers.Default) {
            Exporter.export(config, options, size, destination, format, rasterProbe.accelerator)
        }
        return ExportOutcome(result.file.name, result.millis, result.bytes)
    }

    /**
     * One dialog, two files, side by side in the directory the reader chose.
     *
     * The alternative — a second dialog for the sidecar — was not seriously considered: the JSON is
     * not a file anybody has an opinion about the location of, it is the label on the PNG, and
     * asking twice invites somebody to put the label somewhere else and lose it. The notice names
     * both so nobody is surprised by a file they did not ask for.
     */
    override suspend fun exportData(
        config: WorldGenConfig,
        size: Int,
        layer: DataLayer
    ): ExportOutcome? {
        val destination = chooseSaveFile(Exporter.defaultDataName(config, size, layer)) ?: return null
        val result = withContext(Dispatchers.Default) {
            Exporter.exportData(config, size, destination, layer)
        }
        val sidecar = Exporter.sidecarNameFor(result.file.name)
        return ExportOutcome("${result.file.name} and $sidecar", result.millis, result.bytes)
    }

    private companion object {
        /** Long enough for a slow DNS lookup, short enough that an offline check gives up. */
        const val CONNECT_TIMEOUT_SECONDS = 8L

        /** The whole request. A release document is a few kilobytes; this is generous. */
        const val REQUEST_TIMEOUT_SECONDS = 12L

        /** 2xx. A redirect is followed by the client, so anything else here is a refusal. */
        val HTTP_SUCCESS = 200..299
    }
}

/**
 * The user's configuration directory, by the convention of whichever system this is.
 *
 * `%APPDATA%\Cartogenesis` on Windows, `~/Library/Application Support/Cartogenesis` on macOS, and
 * `$XDG_CONFIG_HOME/cartogenesis` (falling back to `~/.config/cartogenesis`) elsewhere. The
 * directory is created on demand; if it cannot be, [FileSettings] simply never finds a file and
 * every read comes back as the defaults, which is the right failure for a preferences file.
 */
internal fun configDirectory(): File {
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    val home = System.getProperty("user.home")
    val directory = when {
        osName.contains("win") ->
            File(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", "Cartogenesis")
        osName.contains("mac") -> File(home, "Library/Application Support/Cartogenesis")
        else -> File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", "cartogenesis")
    }
    runCatching { directory.mkdirs() }
    return directory
}

/**
 * One JSON file, read and written whole.
 *
 * Written to a sibling `.tmp` and moved into place, so a crash halfway through a write leaves the
 * previous settings rather than half a document — a settings file is small enough that this costs
 * nothing, and it is the difference between "your preferences are as they were" and "the
 * application opens with everything reset".
 */
internal class FileSettings(private val file: File) : SettingsStore {

    override val location: String get() = file.absolutePath

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()
    }

    override suspend fun write(text: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                val temporary = File(file.parentFile, file.name + ".tmp")
                temporary.writeText(text)
                if (!temporary.renameTo(file)) {
                    // Windows refuses a rename onto an existing file, so fall back to the obvious.
                    file.writeText(text)
                    temporary.delete()
                }
            }
        }
    }
}
