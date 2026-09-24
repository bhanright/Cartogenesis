package com.cartogenesis.desktop

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.cartogenesis.cartography.Compressor
import com.cartogenesis.cartography.NoCompression
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.WorldLibrary
import com.cartogenesis.ui.AppSettings
import com.cartogenesis.ui.CartogenesisRoot
import com.cartogenesis.ui.ExportFormat
import com.cartogenesis.ui.ExportOutcome
import com.cartogenesis.ui.Platform
import com.cartogenesis.ui.SettingsCodec
import com.cartogenesis.ui.SettingsStore
import com.cartogenesis.ui.ThemeChoice
import com.cartogenesis.worldgen.model.WorldMap
import com.cartogenesis.worldgen.pipeline.ErosionAccelerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The other half of the settings guard: the half that needs a running application.
 *
 * `SettingsTest` in `:ui` checks that each preference's effect *function* does what it says. This
 * checks that the application actually calls them — that a stored document reaches the window,
 * changes what is drawn, and, in the one case where the answer is a thing not done, that nothing
 * is drawn *and* nothing is fetched. Every case here starts from a settings document in a fake
 * store, which is the same path the desktop's real JSON file takes.
 *
 * Nothing generates a world: the canvas stays blank until Generate, so every one of these
 * composes in a moment.
 */
class SettingsEffectTest {

    @Test
    fun `the stored chrome and the stored scale change what is drawn`() {
        val plain = shoot(AppSettings())
        val mars = shoot(AppSettings(theme = ThemeChoice.MARS))
        val large = shoot(AppSettings(interfaceScale = 1.5f))

        assertNotEquals(plain, mars, "the stored theme never reached the window")
        assertNotEquals(plain, large, "the stored interface scale never reached the window")
        assertNotEquals(mars, large)
    }

    @Test
    fun `the stored working resolution is the grid the header opens on`() {
        assertTrue(headerSays(AppSettings(workingResolution = 2048), "2048 px"))
        // And the default follows the platform, which this fake says is 512.
        assertTrue(headerSays(AppSettings(), "512 px"))
    }

    @Test
    fun `the stored export format is the one the export row starts on`() {
        // The format's own line of small print is the only text that differs between the two, and
        // it is what a reader actually sees, so it is what is asserted.
        assertTrue(headerSays(AppSettings(exportFormat = ExportFormat.WEBP), "quarter the size"))
        assertTrue(headerSays(AppSettings(), "PNG keeps every pixel"))
    }

    /**
     * The guard that matters most on the web: opening the application must not talk to GitHub.
     *
     * The web bundle would otherwise make a cross-origin request in the page's load path, which is
     * both slower and something nobody asked for. The preference is off by default, so the default
     * case is the one that must fetch nothing at all.
     */
    @Test
    fun `nothing reaches the network at launch unless the preference says so`() {
        val quiet = FakeHost()
        compose(quiet, AppSettings())
        assertTrue(
            quiet.fetched.isEmpty(),
            "the application called out to ${quiet.fetched} without being asked"
        )

        val asked = FakeHost()
        compose(asked, AppSettings(checkForUpdatesOnLaunch = true))
        assertEquals(1, asked.fetched.size, "the launch check did not run when it was turned on")
        assertTrue(
            asked.fetched.single().startsWith("https://api.github.com/repos/bhanright/"),
            "the launch check asked for ${asked.fetched.single()}"
        )
    }

    @Test
    fun `a preference chosen in the dialog is written back through the seam`() {
        val host = FakeHost()
        @OptIn(ExperimentalTestApi::class)
        runDesktopComposeUiTest(width = 1440, height = 900) {
            setContent { CartogenesisRoot(host) }
            waitForIdle()
            onNodeWithText("File").performClick()
            waitForIdle()
            onNodeWithText("Settings…").performClick()
            waitForIdle()
            // "Dark" is the one theme label that is not also the name of a map style, and the
            // style strip over the map is on screen too.
            onNodeWithText("Dark").performClick()
            waitForIdle()
        }
        assertTrue(host.written.isNotEmpty(), "choosing a theme wrote nothing to the store")
        assertTrue(
            host.written.last().contains("\"DARK\""),
            "what was written was not the chosen theme: ${host.written.last()}"
        )
    }

    /**
     * The stored library folder is where the library is, from the moment the window opens.
     *
     * The folder is the one setting whose effect is not a default for next time but a move: the
     * platform is told to use it ([Platform.useLibraryFolder]), and the listing follows. Until
     * Audit III's G-D3 was fixed nothing did that at launch — only a change made in the dialog
     * moved the library — so after a restart the dialog named the reader's folder and the library
     * listed the default one; this clause ran as a known failure recording `[]` until then.
     */
    @Test
    fun `the stored library folder is where the library is at launch`() {
        val host = FakeHost(AppSettings(libraryFolder = "D:/atlas/worlds"))
        compose(host, AppSettings(libraryFolder = "D:/atlas/worlds"))
        assertEquals(
            listOf("D:/atlas/worlds"), host.libraryFolders,
            "a window opened with the library folder set to D:/atlas/worlds asked the platform to use ${host.libraryFolders}"
        )
    }

    /**
     * Reset puts the library back in the default folder as well as blanking the preference, and a
     * folder the platform refuses is not stored: the dialog never names a folder the library is not
     * in. Before G-D3's fix a blank folder moved nothing, and a refused one was stored anyway.
     */
    @Test
    fun `the library folder is stored only once the library has moved there`() {
        val host = FakeHost(AppSettings(libraryFolder = "D:/atlas/worlds"))
        @OptIn(ExperimentalTestApi::class)
        runDesktopComposeUiTest(width = 1440, height = 900) {
            setContent { CartogenesisRoot(host) }
            waitForIdle()
            onNodeWithText("File").performClick()
            waitForIdle()
            onNodeWithText("Settings…").performClick()
            waitForIdle()
            onNodeWithText("Reset to defaults").performClick()
            waitForIdle()
        }
        assertEquals(listOf("D:/atlas/worlds", ""), host.libraryFolders, "Reset did not move the library back")
        assertEquals("", SettingsCodec.decode(host.written.last()).libraryFolder)

        // A platform that will not move — the default folder unreachable, say — keeps the folder
        // the library is in, and the store keeps naming it.
        val refusing = FakeHost(AppSettings(libraryFolder = "D:/atlas/worlds"), acceptsFolders = false)
        @OptIn(ExperimentalTestApi::class)
        runDesktopComposeUiTest(width = 1440, height = 900) {
            setContent { CartogenesisRoot(refusing) }
            waitForIdle()
            onNodeWithText("File").performClick()
            waitForIdle()
            onNodeWithText("Settings…").performClick()
            waitForIdle()
            onNodeWithText("Reset to defaults").performClick()
            waitForIdle()
        }
        assertTrue(refusing.written.isNotEmpty(), "Reset wrote nothing at all")
        assertTrue(
            refusing.written.all { SettingsCodec.decode(it).libraryFolder == "D:/atlas/worlds" },
            "the default folder was stored though the platform refused to move there: ${refusing.written}"
        )
    }

    /**
     * Reset writes the defaults back, every one of them: "Reset to defaults" in the dialog, pressed
     * over a document in which every setting differs from its default, leaves the store holding
     * exactly the default document.
     */
    @Test
    fun `Reset to defaults writes every default back through the seam`() {
        val everyOneMoved = AppSettings(
            theme = ThemeChoice.MARS,
            workingResolution = 2048,
            graphicsAccelerationAtLaunch = true,
            exportFormat = ExportFormat.WEBP,
            exportSize = 4096,
            libraryFolder = "D:/atlas/worlds",
            interfaceScale = 1.3f,
            checkForUpdatesOnLaunch = true,
            riverInkStep = 0
        )
        val host = FakeHost(everyOneMoved)
        @OptIn(ExperimentalTestApi::class)
        runDesktopComposeUiTest(width = 1440, height = 900) {
            setContent { CartogenesisRoot(host) }
            waitForIdle()
            onNodeWithText("File").performClick()
            waitForIdle()
            onNodeWithText("Settings…").performClick()
            waitForIdle()
            onNodeWithText("Reset to defaults").performClick()
            waitForIdle()
        }
        assertTrue(host.written.isNotEmpty(), "pressing Reset wrote nothing to the store")
        assertEquals(AppSettings(), SettingsCodec.decode(host.written.last()), "Reset left a setting away from its default")
    }

    // ---- the machinery ----

    /** A content hash of the window drawn under [settings]. */
    @OptIn(ExperimentalTestApi::class)
    private fun shoot(settings: AppSettings): Int {
        var hash = 0
        runDesktopComposeUiTest(width = 1440, height = 900) {
            setContent { CartogenesisRoot(FakeHost(settings)) }
            waitForIdle()
            // Every byte: a sample of every 997th could hash a small change the same as none.
            hash = onRoot().captureToImage().asSkiaBitmap().readPixels()!!.contentHashCode()
        }
        return hash
    }

    /** Whether the window drawn under [settings] has [text] anywhere in it. */
    @OptIn(ExperimentalTestApi::class)
    private fun headerSays(settings: AppSettings, text: String): Boolean {
        var found = false
        runDesktopComposeUiTest(width = 1440, height = 900) {
            setContent { CartogenesisRoot(FakeHost(settings)) }
            waitForIdle()
            found = onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        return found
    }

    @OptIn(ExperimentalTestApi::class)
    private fun compose(host: FakeHost, settings: AppSettings) {
        host.preload(settings)
        runDesktopComposeUiTest(width = 1440, height = 900) {
            setContent { CartogenesisRoot(host) }
            waitForIdle()
        }
    }
}

/**
 * A desktop that does nothing but remember what it was asked.
 *
 * Deliberately not [DesktopPlatform]: this test is about what the application does with a settings
 * document, and the real platform would open a GL context, read the user's own library and — the
 * point of one of these cases — actually reach GitHub.
 */
private class FakeHost(
    settings: AppSettings = AppSettings(),
    /** Whether [useLibraryFolder] takes a folder, or refuses it as a missing drive would. */
    private val acceptsFolders: Boolean = true
) : Platform {

    private var stored: String = SettingsCodec.encode(settings)

    val written = mutableListOf<String>()
    val fetched = mutableListOf<String>()

    /** Every folder the application asked this platform to move the library to, in order. */
    val libraryFolders = mutableListOf<String>()

    fun preload(settings: AppSettings) {
        stored = SettingsCodec.encode(settings)
    }

    override suspend fun useLibraryFolder(path: String): Boolean {
        libraryFolders += path
        return acceptsFolders
    }

    override val defaultResolution: Int = 512
    override val library: WorldLibrary = EmptyWorldLibrary
    override val compressor: Compressor = NoCompression
    override val libraryLocation: String = "a test"
    override val accelerator: ErosionAccelerator? = null
    override val accelerationUnavailableBecause: String = "this is a test"
    override val canQuit: Boolean = true

    override val settingsStore: SettingsStore = object : SettingsStore {
        override val location: String = "a test"
        override suspend fun read(): String = stored
        override suspend fun write(text: String) {
            stored = text
            written += text
        }
    }

    override suspend fun fetchText(url: String): String? {
        fetched += url
        return null
    }

    override suspend fun export(
        world: WorldMap,
        options: RenderOptions,
        size: Int,
        format: ExportFormat
    ): ExportOutcome? = null
}
