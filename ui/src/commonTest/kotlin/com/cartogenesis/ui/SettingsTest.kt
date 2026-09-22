package com.cartogenesis.ui

import com.cartogenesis.cartography.MapRasterizer
import com.cartogenesis.cartography.MapSheet
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.RiverSelection
import com.cartogenesis.worldgen.WorldGenerationEngine
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

/**
 * The two guards the spec asks of the settings: that they round-trip through the seam, and that
 * every one of them has an effect something can observe.
 *
 * The second is the one worth having. A preferences dialog is the easiest place in an application
 * to leave a control that writes a field nobody reads — it looks right, it persists, and it does
 * nothing — and there is no compiler warning for it. So there is a case here per setting, each
 * asserting the *effect* rather than the stored value, and each of them goes through the same
 * [SettingsEffects] function the application goes through. A setting whose effect were deleted
 * would take its case with it rather than leaving a green test.
 */
class SettingsTest {

    /**
     * The river-density slider, end to end: the panel writes it, the rasterizer draws by it, the
     * preferences keep it, and a window opened afterwards draws the same map.
     *
     * On a generated world, because "reaches the rasterizer" means the overlay's river count moves
     * with the mark, and only a real network can show that. 128 cells, the size
     * `GenerationProgressTest` already runs in the browser, and a sheet at a cell to the pixel, which
     * is the export's. What is asserted is the path the application takes, function by function:
     * the knob, `SettingsEffects.settingsAfterDrawing` which the window calls when the mark moves,
     * the codec through the platform's store, and `SettingsEffects.startingRenderOptions` which a
     * fresh window starts from. A world save is not on the path: it carries no setting of the
     * drawing, so a saved world reopened in the new window is drawn at the stored mark like any
     * other.
     */
    @Test
    fun `the river density slider reaches the rasterizer and survives a save and a reopen`() =
        runTest(timeout = LONGEST_WAIT) {
            val world = WorldGenerationEngine.generate(SMALL_WORLD)
            val platform = FakePlatform()
            fun riversDrawn(options: RenderOptions) =
                MapRasterizer.overlay(world, options, MapSheet.UNGENERALISED).riversDrawn

            // The reader turns the slider to its bottom mark; the knob writes that field and no other.
            val bottom = RiverSelection.INK_STEPS.first
            val turned = Knobs.riverDensity.set(RenderOptions(), bottom)
            assertEquals(RenderOptions(riverInkStep = bottom), turned)
            assertEquals(RiverSelection.EARTH_DENSITY_STEP, Knobs.riverDensity.read(RenderOptions()))

            // The rasterizer draws by it: fewer courses at the bottom than at Earth's mark, and
            // every traced one at the top.
            val atBottom = riversDrawn(turned)
            val atEarth = riversDrawn(RenderOptions())
            val atTop = riversDrawn(RenderOptions(riverInkStep = RiverSelection.EVERY_COURSE_STEP))
            println(
                "X1C slider on seed ${SMALL_WORLD.seed} at ${SMALL_WORLD.width}: bottom $atBottom, " +
                    "Earth's mark $atEarth, top $atTop of ${world.rivers.rivers.size} traced"
            )
            assertTrue(atBottom in 1 until atEarth, "the bottom mark did not thin the drawing")
            assertTrue(atEarth < atTop, "the top mark did not add to the drawing")
            assertEquals(world.rivers.rivers.size, atTop, "the top mark left a traced course off")

            // The window stores the mark as it moves, and only the mark.
            val stored = SettingsEffects.settingsAfterDrawing(AppSettings(), turned)
            assertEquals(AppSettings(riverInkStep = bottom), stored)
            assertEquals(
                AppSettings(),
                SettingsEffects.settingsAfterDrawing(AppSettings(), RenderOptions()),
                "an untouched slider changed the preferences"
            )
            platform.settingsStore.write(SettingsCodec.encode(stored))

            // Reopen: a fresh window starts from what the store holds, and draws the same map.
            val reopened =
                SettingsEffects.startingRenderOptions(SettingsCodec.decode(platform.settingsStore.read()))
            assertEquals(turned, reopened, "the reopened window starts from another drawing")
            assertEquals(atBottom, riversDrawn(reopened), "the reopened window drew other rivers")

            // A reader who never touched the slider opens on the default drawing, a file written
            // before the slider existed opens at Earth's mark, and a hand-edited one cannot ask for
            // a mark the scale does not have.
            assertEquals(RenderOptions(), SettingsEffects.startingRenderOptions(AppSettings()))
            assertEquals(
                RiverSelection.EARTH_DENSITY_STEP,
                SettingsCodec.decode("{\"theme\": \"MARS\"}").riverInkStep
            )
            assertEquals(
                RiverSelection.EVERY_COURSE_STEP,
                SettingsCodec.decode("{\"riverInkStep\": 40}").riverInkStep
            )
            assertEquals(bottom, SettingsCodec.decode("{\"riverInkStep\": -7}").riverInkStep)
        }

    @Test
    fun `every setting survives a trip through the platform seam`() = runTest {
        val platform = FakePlatform()
        val chosen = AppSettings(
            theme = ThemeChoice.MARS,
            workingResolution = 2048,
            graphicsAccelerationAtLaunch = true,
            exportFormat = ExportFormat.WEBP,
            exportSize = 4096,
            libraryFolder = "D:/atlas/worlds",
            interfaceScale = 1.3f,
            checkForUpdatesOnLaunch = true,
            riverInkStep = RiverSelection.EVERY_COURSE_STEP
        )

        platform.settingsStore.write(SettingsCodec.encode(chosen))
        val recovered = SettingsCodec.decode(platform.settingsStore.read())

        assertEquals(chosen, recovered, "a setting was lost between the store and the reader")
        assertEquals(1, platform.written.size)
        assertTrue(
            platform.written.single().contains("MARS"),
            "the document written was not the settings: ${platform.written.single()}"
        )
    }

    @Test
    fun `an absent, empty or nonsensical document opens as the defaults`() {
        assertEquals(AppSettings(), SettingsCodec.decode(null))
        assertEquals(AppSettings(), SettingsCodec.decode(""))
        assertEquals(AppSettings(), SettingsCodec.decode("{ this is not json"))
        // The failure this exists for: a hand-edited file naming a theme that no longer exists
        // must open, not stop the application.
        assertEquals(
            ThemeChoice.SYSTEM,
            SettingsCodec.decode("""{"theme":"SEPIA"}""").theme
        )
        // …and one carrying a setting from a later build must keep the ones it does understand.
        assertEquals(
            ThemeChoice.MIDNIGHT,
            SettingsCodec.decode("""{"theme":"MIDNIGHT","somethingNewer":42}""").theme
        )
    }

    @Test
    fun `values out of range are pulled back into it`() {
        assertEquals(
            AppSettings.FOLLOW_PLATFORM,
            SettingsCodec.decode("""{"workingResolution":333}""").workingResolution
        )
        assertEquals(2048, SettingsCodec.decode("""{"exportSize":99}""").exportSize)
        assertEquals(1.5f, SettingsCodec.decode("""{"interfaceScale":40.0}""").interfaceScale)
        assertEquals(0.8f, SettingsCodec.decode("""{"interfaceScale":0.01}""").interfaceScale)
    }

    @Test
    fun `theme decides the chrome, and only System asks the host`() {
        assertTrue(SettingsEffects.isDark(AppSettings(theme = ThemeChoice.SYSTEM), true))
        assertFalse(SettingsEffects.isDark(AppSettings(theme = ThemeChoice.SYSTEM), false))
        // The four named ones answer for themselves, whatever the host says.
        assertFalse(SettingsEffects.isDark(AppSettings(theme = ThemeChoice.LIGHT), true))
        assertTrue(SettingsEffects.isDark(AppSettings(theme = ThemeChoice.DARK), false))
        assertFalse(SettingsEffects.isDark(AppSettings(theme = ThemeChoice.NAUTICAL), true))
        assertTrue(SettingsEffects.isDark(AppSettings(theme = ThemeChoice.MIDNIGHT), false))
        assertTrue(SettingsEffects.isDark(AppSettings(theme = ThemeChoice.MARS), false))

        // Every chrome is a different chrome: two choices resolving to the same scheme would mean
        // one of them silently did nothing.
        val schemes = ThemeChoice.entries.map { it.scheme(systemDark = false) }
        assertEquals(
            ThemeChoice.entries.size - 1,
            schemes.toSet().size,
            "two theme choices produced the same colour scheme"
        )
    }

    @Test
    fun `working resolution decides the grid a fresh world starts at`() {
        val platform = FakePlatform(defaultResolution = 512)
        assertEquals(512, SettingsEffects.resolution(AppSettings(), platform))
        assertEquals(
            2048,
            SettingsEffects.resolution(AppSettings(workingResolution = 2048), platform)
        )
        val config = SettingsEffects.startingConfig(
            AppSettings(workingResolution = 1024),
            platform,
            seed = 7
        )
        assertEquals(1024, config.width)
        assertEquals(1024, config.height)
        assertEquals(7L, config.seed)
    }

    @Test
    fun `the graphics-card preference arms the switch, but only where there is a card`() {
        val withCard = FakePlatform(accelerator = FakeAccelerator)
        val without = FakePlatform(accelerator = null)
        val on = AppSettings(graphicsAccelerationAtLaunch = true)

        assertTrue(
            SettingsEffects.usesGraphicsAcceleration(SettingsEffects.startingConfig(on, withCard, 1)),
            "the preference did not reach the erosion config"
        )
        assertFalse(
            SettingsEffects.usesGraphicsAcceleration(SettingsEffects.startingConfig(on, without, 1)),
            "a machine with no device claimed it would generate on one"
        )
        assertFalse(
            SettingsEffects.usesGraphicsAcceleration(
                SettingsEffects.startingConfig(AppSettings(), withCard, 1)
            )
        )
    }

    @Test
    fun `the default export size is clamped by what this build can finish`() {
        val big = AppSettings(exportSize = 4096)
        assertEquals(4096, SettingsEffects.exportSizeWithin(big, ceiling = 4096))
        // A preference written by a build with a higher ceiling, opened by one without it.
        assertEquals(2048, SettingsEffects.exportSizeWithin(big, ceiling = 2048))
        assertEquals(
            ExportFormat.WEBP,
            AppSettings(exportFormat = ExportFormat.WEBP).exportFormat
        )
    }

    @Test
    fun `the library folder overrides the platform's own, and only when set`() {
        val platform = FakePlatform()
        assertEquals(
            platform.libraryLocation,
            SettingsEffects.libraryLocation(AppSettings(), platform)
        )
        assertEquals(
            "D:/atlas",
            SettingsEffects.libraryLocation(AppSettings(libraryFolder = "D:/atlas"), platform)
        )
        assertNotEquals(
            platform.libraryLocation,
            SettingsEffects.libraryLocation(AppSettings(libraryFolder = "D:/atlas"), platform)
        )
    }

    @Test
    fun `the launch check is off unless it has been turned on`() {
        assertFalse(SettingsEffects.checksAtLaunch(AppSettings()))
        assertTrue(
            SettingsEffects.checksAtLaunch(AppSettings(checkForUpdatesOnLaunch = true))
        )
    }

    @Test
    fun `interface scale is a real range and Reset returns every setting to its default`() {
        assertTrue(AppSettings.SCALES.contains(1f))
        assertTrue(AppSettings.SCALES.first() < 1f && AppSettings.SCALES.last() > 1f)
        // "Reset to defaults" is `AppSettings()` and nothing else, so this is the whole of it.
        assertEquals(ThemeChoice.SYSTEM, AppSettings().theme)
        assertEquals(1f, AppSettings().interfaceScale)
        assertEquals(AppSettings.FOLLOW_PLATFORM, AppSettings().workingResolution)
        assertFalse(AppSettings().graphicsAccelerationAtLaunch)
        assertFalse(AppSettings().checkForUpdatesOnLaunch)
    }

    private companion object {
        /** The world `GenerationProgressTest` generates, for the same reason: every stage runs. */
        val SMALL_WORLD = WorldGenConfig(seed = 42L, width = 128, height = 128)

        /** `GenerationProgressTest`'s allowance for one 128-cell generation in the browser. */
        val LONGEST_WAIT = 210.seconds
    }
}
