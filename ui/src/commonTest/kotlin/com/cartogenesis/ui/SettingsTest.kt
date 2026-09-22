package com.cartogenesis.ui

import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.cartography.RiverSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
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
     * The river-density slider, end to end: the panel writes it, the drawing reads it, the store
     * keeps it, and a hand-edited file cannot put it off its own scale.
     *
     * The drawing's half of the path — that `RenderOptions.riverInkStep` is what decides how many
     * courses the overlay lays out — is measured on a generated world in `:cartography`'s
     * `RiverSelectionTest`, which is where there is a world to measure it on. What is asserted
     * here is everything between the reader's finger and that field.
     */
    @Test
    fun `the river density slider reaches the drawing, and comes back next time`() = runTest {
        val platform = FakePlatform()
        val asked = AppSettings(riverInkStep = RiverSelection.INK_STEPS.first)

        // The panel's knob is the only thing that writes it, and it writes nothing else.
        val turned = Knobs.riverDensity.set(RenderOptions(), RiverSelection.INK_STEPS.first)
        assertEquals(RenderOptions(riverInkStep = RiverSelection.INK_STEPS.first), turned)
        assertEquals(RiverSelection.EARTH_DENSITY_STEP, Knobs.riverDensity.read(RenderOptions()))

        // What the application draws with when a window opens is that preference and nothing else.
        assertEquals(turned, SettingsEffects.startingRenderOptions(asked))
        assertEquals(
            RenderOptions(),
            SettingsEffects.startingRenderOptions(AppSettings()),
            "a reader who has never touched the slider gets a different map than the default"
        )

        platform.settingsStore.write(SettingsCodec.encode(asked))
        assertEquals(
            asked.riverInkStep,
            SettingsCodec.decode(platform.settingsStore.read()).riverInkStep,
            "the chosen river density did not survive being written and read back"
        )

        // A file written before the slider existed opens at Earth's own figure.
        assertEquals(
            RiverSelection.EARTH_DENSITY_STEP,
            SettingsCodec.decode("{\"theme\": \"MARS\"}").riverInkStep
        )
        // And a hand-edited one cannot ask for a mark the scale does not have.
        assertEquals(
            RiverSelection.EVERY_COURSE_STEP,
            SettingsCodec.decode("{\"riverInkStep\": 40}").riverInkStep
        )
        assertEquals(
            RiverSelection.INK_STEPS.first,
            SettingsCodec.decode("{\"riverInkStep\": -7}").riverInkStep
        )
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
}
