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
import com.cartogenesis.cartography.LibraryEntry
import com.cartogenesis.cartography.NoCompression
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.WorldDocument
import com.cartogenesis.cartography.WorldLibrary
import com.cartogenesis.cartography.WorldSave
import com.cartogenesis.ui.AppSettings
import com.cartogenesis.ui.CartogenesisRoot
import com.cartogenesis.ui.ExportFormat
import com.cartogenesis.ui.ExportOutcome
import com.cartogenesis.ui.Platform
import com.cartogenesis.ui.SettingsCodec
import com.cartogenesis.ui.SettingsStore
import com.cartogenesis.ui.ThemeChoice
import com.cartogenesis.worldgen.model.WorldGenConfig
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
 * Nothing generates a world: F0 leaves the canvas blank until Generate, so every one of these
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
        assertTrue(headerSays(AppSettings(), "Lossless"))
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

    // ---- the machinery ----

    /** A content hash of the window drawn under [settings]. */
    @OptIn(ExperimentalTestApi::class)
    private fun shoot(settings: AppSettings): Int {
        var hash = 0
        runDesktopComposeUiTest(width = 1440, height = 900) {
            setContent { CartogenesisRoot(FakeHost(settings)) }
            waitForIdle()
            val pixels = onRoot().captureToImage().asSkiaBitmap().readPixels()!!
            var value = 17
            for (k in pixels.indices step 997) value = value * 31 + pixels[k]
            hash = value
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
private class FakeHost(settings: AppSettings = AppSettings()) : Platform {

    private var stored: String = SettingsCodec.encode(settings)

    val written = mutableListOf<String>()
    val fetched = mutableListOf<String>()

    fun preload(settings: AppSettings) {
        stored = SettingsCodec.encode(settings)
    }

    override val defaultResolution: Int = 512
    override val library: WorldLibrary = Empty
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
        config: WorldGenConfig,
        options: RenderOptions,
        size: Int,
        format: ExportFormat
    ): ExportOutcome? = null

    private object Empty : WorldLibrary {
        override suspend fun list(): List<LibraryEntry> = emptyList()
        override suspend fun save(document: WorldDocument, world: WorldMap?) = Unit
        override suspend fun load(id: String): WorldSave? = null
        override suspend fun delete(id: String) = Unit
    }
}
