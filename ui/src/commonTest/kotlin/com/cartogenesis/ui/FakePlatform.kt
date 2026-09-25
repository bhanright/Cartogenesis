package com.cartogenesis.ui

import com.cartogenesis.cartography.Compressor
import com.cartogenesis.cartography.LibraryEntry
import com.cartogenesis.cartography.LoadOutcome
import com.cartogenesis.cartography.NoCompression
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.SaveProblem
import com.cartogenesis.cartography.SaveRefusal
import com.cartogenesis.cartography.WorldDocument
import com.cartogenesis.cartography.WorldLibrary
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ErosionAccelerator
import com.cartogenesis.worldgen.pipeline.ThermalLimits

/**
 * A host that does nothing, so a test can ask what the shared code does with it.
 *
 * Every question [Platform] asks is answered here with the least interesting possible answer, and
 * a test overrides the one it is about. It also *records*: [written] is what reached the settings
 * store, [fetched] is every URL the update check asked for, and [openedFolders] and [links] are the
 * two things the platform is asked to open — which is how a test observes an effect whose whole
 * point is that it leaves the application.
 */
internal open class FakePlatform(
    override val defaultResolution: Int = 512,
    override val accelerator: ErosionAccelerator? = null,
    /** What [exportCeiling] answers, whatever the window's shape. */
    private val ceiling: Int = 4096,
    override val canQuit: Boolean = false,
    override val graphicsApiPresent: Boolean = true,
    override val coarsePointer: Boolean = false,
    private val stored: String? = null
) : Platform {

    override fun exportCeiling(compact: Boolean): Int = ceiling

    override val library: WorldLibrary = EmptyLibrary
    override val compressor: Compressor = NoCompression
    override val libraryLocation: String = "nowhere in particular"
    override val accelerationUnavailableBecause: String? = "this is a test"

    /** Every settings document that has been written, in order. */
    val written = mutableListOf<String>()

    /** Every URL the application has asked for. Empty is the assertion that matters most. */
    val fetched = mutableListOf<String>()

    val links = mutableListOf<String>()

    /** Everything the application has put on the clipboard, in order. */
    val clipboard = mutableListOf<String>()
    val openedFolders = mutableListOf<String>()
    val libraryFolders = mutableListOf<String>()
    var quits: Int = 0
        private set

    /** What [fetchText] answers with. Null is offline, which is the default. */
    var response: String? = null

    override val settingsStore: SettingsStore = object : SettingsStore {
        private var held: String? = stored
        override val location: String = "a test"
        override suspend fun read(): String? = held
        override suspend fun write(text: String) {
            held = text
            written += text
        }
    }

    override fun quit() {
        quits += 1
    }

    override val canOpenLinks: Boolean = true

    override fun openLink(url: String) {
        links += url
    }

    override val canCopyToClipboard: Boolean = true

    override fun copyToClipboard(text: String) {
        clipboard += text
    }

    override val canRevealFolder: Boolean = true

    override fun revealFolder(path: String) {
        openedFolders += path
    }

    override suspend fun useLibraryFolder(path: String): Boolean {
        libraryFolders += path
        return true
    }

    override suspend fun fetchText(url: String): String? {
        fetched += url
        return response
    }

    override suspend fun export(
        world: WorldMap,
        options: RenderOptions,
        size: Int,
        format: ExportFormat
    ): ExportOutcome? = null

    private object EmptyLibrary : WorldLibrary {
        override suspend fun list(): List<LibraryEntry> = emptyList()
        override suspend fun save(document: WorldDocument, world: WorldMap, key: String?): String =
            key ?: "${document.id}.cgw"
        override suspend fun load(key: String): LoadOutcome =
            LoadOutcome.Refused(SaveRefusal(SaveProblem.UNREADABLE, "this library is empty"))
        override suspend fun delete(key: String) = Unit
    }
}

/** A stand-in device, for the cases where the question is what happens when there *is* one. */
internal object FakeAccelerator : ErosionAccelerator {
    override val name: String = "a fake graphics card"
    override suspend fun erode(
        width: Int,
        height: Int,
        heights: FloatArray,
        limits: ThermalLimits,
        passes: Int,
        rate: Float
    ): FloatArray? = null
}
