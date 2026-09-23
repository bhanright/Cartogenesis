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
 * nothing — and there is no compiler warning for it. So there is a case here per setting whose
 * effect a function decides, each asserting the *effect* rather than the stored value through the
 * same function the application calls. The effects only a running window shows — the stored
 * chrome, scale and export format reaching it, the library moving to the stored folder, and what
 * Reset writes back — are `SettingsEffectTest`'s, in `:desktop`.
 */
class SettingsTest {

    /**
     * The river-density slider, from the knob to the rasterizer and through the settings codec.
     *
     * On a generated world, because "reaches the rasterizer" means the overlay's river count moves
     * with the mark, and only a real network can show that. 128 cells, the size
     * `GenerationProgressTest` already runs in the browser, and a sheet at a cell to the pixel, which
     * is the export's. What is followed is the functions the application calls, one by one: the
     * knob, `SettingsEffects.settingsAfterDrawing` which the window calls when the mark moves, the
     * codec, and `SettingsEffects.startingRenderOptions` which a fresh window starts from.
     *
     * What it does not prove: the store here is [FakePlatform]'s, held in memory, so neither the
     * desktop's file nor the browser's local storage is exercised, and no window is actually closed
     * and reopened. The desktop file under a burst of writes is `FileSettingsTest` in `:desktop`.
     * A world save is not on the path either way: it carries no setting of the drawing.
     */
    @Test
    fun `the river density slider reaches the rasterizer and round-trips the settings codec`() =
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

            // What a fresh window would start from, given what the store holds: the same map.
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

    /**
     * The chrome, read where the application reads it: `CartogenesisTheme` hands Material
     * `choice.scheme(dark)`, with `dark` the host's own answer, so that is what is compared here,
     * by the colours each scheme holds ([rolesOf]) rather than by the object it is — a
     * `ColorScheme` has no `equals`, and two choices holding the same colours in two objects would
     * count as two chromes to a set of schemes.
     */
    @Test
    fun `theme decides the chrome, and only System asks the host`() {
        fun colours(choice: ThemeChoice, hostDark: Boolean) = rolesOf(choice.scheme(systemDark = hostDark))
        assertEquals(colours(ThemeChoice.DARK, false), colours(ThemeChoice.SYSTEM, true), "System in a dark host is not the dark chrome")
        assertEquals(colours(ThemeChoice.LIGHT, false), colours(ThemeChoice.SYSTEM, false), "System in a light host is not the light chrome")
        // Every named chrome answers for itself, whatever the host says.
        val named = ThemeChoice.entries.filter { it != ThemeChoice.SYSTEM }
        named.forEach { choice ->
            assertEquals(colours(choice, true), colours(choice, false), "${choice.label} follows the host")
        }

        // Every chrome is a different chrome: two choices resolving to the same colours would mean
        // one of them silently did nothing.
        val distinct = named.groupBy { colours(it, false) }.values.filter { it.size > 1 }
        assertTrue(
            distinct.isEmpty(),
            "theme choices with the same colours: " + distinct.joinToString { group -> group.joinToString("/") { it.label } }
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

    /**
     * The export size's effect. The export format's is what the export row starts on, which only a
     * running window shows: `SettingsEffectTest` in `:desktop` reads it there.
     */
    @Test
    fun `the default export size is clamped by what this build can finish`() {
        val big = AppSettings(exportSize = 4096)
        assertEquals(4096, SettingsEffects.exportSizeWithin(big, ceiling = 4096))
        // A preference written by a build with a higher ceiling, opened by one without it.
        assertEquals(2048, SettingsEffects.exportSizeWithin(big, ceiling = 2048))
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

    /**
     * The scales the dialog offers straddle the reader's own size. What a stored scale does to the
     * window, and what Reset writes back, are asked of a running window in `SettingsEffectTest`.
     */
    @Test
    fun `interface scale is a real range around the reader's own size`() {
        assertTrue(AppSettings.SCALES.contains(1f))
        assertTrue(AppSettings.SCALES.first() < 1f && AppSettings.SCALES.last() > 1f)
    }

    private companion object {
        /** The world `GenerationProgressTest` generates, for the same reason: every stage runs. */
        val SMALL_WORLD = WorldGenConfig(seed = 42L, width = 128, height = 128)

        /** `GenerationProgressTest`'s allowance for one 128-cell generation in the browser. */
        val LONGEST_WAIT = 210.seconds
    }
}
