package com.cartogenesis.ui

import com.cartogenesis.worldgen.model.Acceleration
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the reader has told the application to do by default, and where that is kept.
 *
 * The distinction that matters here, and the one the settings dialog is written around, is between
 * a **setting of a world** and a **setting of the application**. Ocean coverage, plate count and
 * seasonal tilt are settings of a world: they belong to the map on screen, they travel in the save
 * file, and they live in the panel. Nothing in this file is one of those. These are preferences —
 * which chrome the window wears, how big a world to start at, whether to reach for the graphics
 * card without being asked — and they belong to the person rather than to the map, so they persist
 * between sessions and never enter a save.
 *
 * The rule that follows from that, and that the dialog obeys throughout: **a preference is a
 * default, not a command.** Changing the default working resolution does not resize the world on
 * screen, and changing the default export size does not re-export anything. What is on screen is
 * the reader's; what is here is what the next one starts as. The two exceptions are the two that
 * are not defaults at all but properties of the running application — the chrome and the interface
 * scale — and those apply the moment they change.
 */
@Serializable
data class AppSettings(
    /** Which chrome. [ThemeChoice.SYSTEM] follows the host's own light/dark setting. */
    @SerialName("theme")
    val theme: ThemeChoice = ThemeChoice.SYSTEM,

    /**
     * The grid a new world is generated at, or 0 to take the platform's own answer.
     *
     * Zero rather than a number is the default on purpose: the desktop starts at 1024 and the web
     * at 512, for the reasons [Platform.defaultResolution] gives, and a settings file written on
     * one would otherwise impose its answer on the other the first time it was carried across.
     */
    @SerialName("workingResolution")
    val workingResolution: Int = FOLLOW_PLATFORM,

    /**
     * Whether the graphics device is reached for without being asked.
     *
     * The stored key is the Kotlin name. It used to be `graphicsCardAtLaunch`, from before the
     * switch was reworded — "card" is wrong on a phone, where the device is a block of cores on
     * the processor's own die — and a wire name that disagrees with the property it carries is a
     * thing a reader has to hold in their head. Nothing is distributed, so it moves; the cost is
     * that a settings file written before this opens with the switch off, which is the default
     * and is the safe end of it. See `docs/CONVENTIONS.md`, "Serialised names".
     */
    @SerialName("graphicsAccelerationAtLaunch")
    val graphicsAccelerationAtLaunch: Boolean = false,

    @SerialName("exportFormat")
    val exportFormat: ExportFormat = ExportFormat.PNG,

    /** Clamped against [Platform.exportCeiling] on the way out; see [exportSizeWithin]. */
    @SerialName("exportSize")
    val exportSize: Int = 2048,

    /** Empty means the platform's own library location. Only the desktop has anywhere else. */
    @SerialName("libraryFolder")
    val libraryFolder: String = "",

    /** 1.0 is the interface as designed. See [SCALES] for what the dialog offers. */
    @SerialName("interfaceScale")
    val interfaceScale: Float = 1f,

    /**
     * Off by default, and deliberately.
     *
     * An application that talks to GitHub because it was opened is doing something the reader did
     * not ask for, and on the web it would put a cross-origin request in the page's load path. So
     * the check is a menu item until someone turns this on.
     */
    @SerialName("checkForUpdatesOnLaunch")
    val checkForUpdatesOnLaunch: Boolean = false
) {
    companion object {
        const val FOLLOW_PLATFORM = 0

        /** What the scale control offers. Below 0.8 the type stops being readable at all. */
        val SCALES: List<Float> = listOf(0.8f, 0.9f, 1.0f, 1.15f, 1.3f, 1.5f)
    }
}

/**
 * Turning settings into text and back, and surviving whatever is found in the file.
 *
 * `ignoreUnknownKeys` and `coerceInputValues` between them mean that a file written by a later
 * build (with a setting this one has never heard of) and a file written by an earlier one (missing
 * half of these) both open, and a file with `"theme": "SEPIA"` in it comes back as the default
 * rather than throwing. That matters more here than anywhere else in the application: a settings
 * file is the one thing a reader might edit by hand, and the failure mode of a strict parser is an
 * application that will not start.
 *
 * Public, unlike most of this module, because a front end's own tests have to be able to put a
 * settings document into a store and see what the application makes of it — and writing that JSON
 * by hand in each of them would be a second, silently diverging copy of the format.
 */
object SettingsCodec {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    fun encode(settings: AppSettings): String = json.encodeToString(AppSettings.serializer(), settings)

    /** Whatever could be read, defaulted where it could not. Never throws. */
    fun decode(text: String?): AppSettings {
        if (text.isNullOrBlank()) return AppSettings()
        return runCatching { json.decodeFromString(AppSettings.serializer(), text) }
            .getOrElse { AppSettings() }
            .sane()
    }

    /**
     * Clamps anything a hand-edited file could put out of range.
     *
     * `coerceInputValues` only replaces nulls and unknown enum constants; a scale of 40 is a
     * perfectly good float as far as the parser is concerned, and would leave a window in which a
     * single control filled the screen and there was no way back to the dialog.
     */
    private fun AppSettings.sane(): AppSettings = copy(
        workingResolution = if (workingResolution in Knobs.RESOLUTIONS) workingResolution
        else AppSettings.FOLLOW_PLATFORM,
        exportSize = if (exportSize in Exports.SIZES) exportSize else Exports.SIZES.first(),
        interfaceScale = interfaceScale.coerceIn(AppSettings.SCALES.first(), AppSettings.SCALES.last())
    )
}

/**
 * What each setting actually *does*, as a pure function.
 *
 * Every one of these exists so that the guard the spec asks for — "every setting has an effect a
 * test can observe" — can be written without driving a composition. Each function here is the
 * whole of one setting's effect, the application calls exactly these, and `SettingsTest` calls
 * exactly these; there is no second path by which a preference reaches the interface.
 */
internal object SettingsEffects {

    /**
     * The grid a fresh world starts at: the preference, or the platform's own if there is none —
     * and never above 512 in a phone-shaped window.
     *
     * The cap is not a preference being overruled for the sake of it. The settings document is one
     * string per origin and per user, so a reader who works at 2048 on a desktop and then opens the
     * web build on their phone arrives with `workingResolution = 2048` in local storage; honouring
     * it there is a minute of a blocked page on one thread, which reads as a browser that has hung.
     * 512 is what the web front end already starts at for the same reason.
     */
    fun resolution(settings: AppSettings, platform: Platform, compact: Boolean = false): Int {
        val preferred =
            if (settings.workingResolution == AppSettings.FOLLOW_PLATFORM) platform.defaultResolution
            else settings.workingResolution
        return if (compact) minOf(preferred, Layouts.COMPACT_RESOLUTION) else preferred
    }

    /**
     * The config the application opens with.
     *
     * `atResolution` rather than a copy, for the reason [Knobs.atResolution] gives, and the
     * acceleration preference is written through [Knobs.graphicsAcceleration] rather than by
     * reaching into the erosion config here — one writer per setting, so the switch in the header and the
     * preference in the dialog cannot come to disagree about what "on" means.
     */
    fun startingConfig(
        settings: AppSettings,
        platform: Platform,
        seed: Long,
        compact: Boolean = false
    ): WorldGenConfig {
        val size = resolution(settings, platform, compact)
        val base = WorldGenConfig(seed = seed, width = 512, height = 512).atResolution(size, size)
        // A machine with no device gets the CPU whatever the preference says: a config claiming
        // GPU acceleration that silently ran on the CPU would be a lie told to the header switch.
        val accelerate = settings.graphicsAccelerationAtLaunch && platform.accelerator != null
        return Knobs.graphicsAcceleration.set(base, accelerate)
    }

    /** Whether [startingConfig] will have asked for the graphics device. */
    fun usesGraphicsAcceleration(config: WorldGenConfig): Boolean =
        config.erosion.acceleration == Acceleration.GPU

    /** The default export size, never above what this build can finish. */
    fun exportSizeWithin(settings: AppSettings, ceiling: Int): Int =
        Exports.clamp(settings.exportSize, ceiling)

    /** Where the library is, in the reader's terms: their folder if they chose one. */
    fun libraryLocation(settings: AppSettings, platform: Platform): String =
        settings.libraryFolder.ifBlank { platform.libraryLocation }

    /** Whether the update check runs by itself when the application opens. */
    fun checksAtLaunch(settings: AppSettings): Boolean = settings.checkForUpdatesOnLaunch

    /**
     * Which colour scheme is in force, resolved the way [CartogenesisTheme] resolves it.
     *
     * Returned as the dark/light answer rather than as a `ColorScheme`, because that is the whole
     * of what a test can meaningfully assert about a palette without reading pixels — and the
     * screenshots in `ChromeGalleryTest` are what actually check the palettes.
     */
    fun isDark(settings: AppSettings, systemDark: Boolean): Boolean =
        settings.theme.isDark() ?: systemDark
}
