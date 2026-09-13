package com.cartogenesis.ui

import androidx.compose.runtime.mutableStateMapOf
import com.cartogenesis.cartography.DataLayer
import com.cartogenesis.cartography.MapStyle
import com.cartogenesis.cartography.MapView
import com.cartogenesis.cartography.RenderOptions
import com.cartogenesis.worldgen.model.Acceleration
import com.cartogenesis.worldgen.model.WildernessMode
import com.cartogenesis.worldgen.model.WorldGenConfig
import kotlin.math.roundToInt

/**
 * The settings panel, declared rather than drawn.
 *
 * The panel used to be a single composable in which every control both described itself and
 * reached into [WorldGenConfig] to change it, which had two consequences. The order of the
 * controls was whatever they had been written in, rather than the order the generator runs; and
 * the only way to ask "can this panel still set every setting it used to?" was to read it. So the
 * knobs are declared here as data — which section a knob belongs to, what it is called, what it
 * reads and what it writes — and [CartogenesisApp] renders that list. `PanelKnobsTest` walks the
 * same list, which is what makes the guard a guard rather than a second copy of the panel.
 *
 * Nothing here knows about Compose beyond [SectionState], and nothing here generates anything: a
 * knob is a pair of pure functions over an immutable settings object.
 */

/**
 * A division of the panel, in the order the generator runs.
 *
 * That order is the whole idea. The pipeline builds a world by tectonics, then erosion, then sea
 * level, then climate, then rivers and lakes, then peoples — so reading down the panel is reading
 * the pipeline, and a setting is found by asking when in the making of a world it applies rather
 * than by remembering which box someone filed it in. [ATLAS] is the exception and is not part of
 * the panel at all: its two settings live in the Atlas pane, under the button that opens it.
 */
internal enum class PanelSection(val title: String) {
    /**
     * Not a section: the slim header above them all, which never rolls up.
     *
     * It holds the two things that are not settings of the world — which world (the seed and the
     * name) and how finely it is computed — and, since this change, the one setting that is not
     * about the world either: where the work runs. The graphics-acceleration switch spent F2 in [WORLD],
     * directly under Ocean coverage, where it read as something to do with the sea. It decides
     * which processor erodes the terrain, which is a fact about this machine, and it belongs with
     * the resolution it is the other half of.
     */
    HEADER("Header"),

    /** Before anything: how much of the world is sea. */
    WORLD("World"),

    /** Plates, and the two dials that decide how tall and how worn the land comes out. */
    TERRAIN("Terrain"),

    /** The year, and how wet the far side of a mountain is. */
    CLIMATE("Climate"),

    /** Rivers and lakes: what the rain does once it has fallen. */
    WATER("Water"),

    /** Who lives there. */
    PEOPLES("Peoples"),

    /**
     * Nothing about the world; everything about the drawing of it.
     *
     * Style and view used to be here, as two stacked columns of chips twenty-four rows long. F3
     * lifts both onto the map, where what they do can be seen — see [MapChrome]. What is left is
     * the pair of marks that are about the drawing and not about the world.
     */
    CARTOGRAPHY("Cartography"),

    /** Not on the panel either. Declared here so the guard can see the whole interface at once. */
    ATLAS("Atlas");

    /**
     * Only the first section is unrolled when the app opens.
     *
     * Six open sections is the wall of controls this chunk exists to take apart, and the World
     * section holds the one setting — how much ocean — that someone about to press Generate for
     * the first time is most likely to want.
     */
    val openByDefault: Boolean get() = this == WORLD
}

/**
 * The panel's own sections, in pipeline order. [PanelSection.HEADER] and [PanelSection.ATLAS] are
 * deliberately absent: both are drawn, but neither is a division of the panel that rolls up.
 */
internal val PANEL_SECTIONS: List<PanelSection> = listOf(
    PanelSection.WORLD,
    PanelSection.TERRAIN,
    PanelSection.CLIMATE,
    PanelSection.WATER,
    PanelSection.PEOPLES,
    PanelSection.CARTOGRAPHY
)

/** Anything the reader can change, wherever it is drawn. */
internal sealed class Knob {
    abstract val section: PanelSection
    abstract val label: String

    /**
     * Whether this knob is meaningless on a machine with no graphics API at all.
     *
     * Asked by [Arrangements.headerKnobs], which drops such a knob rather than drawing it disabled
     * when [Platform.graphicsApiPresent] is false. Declared on [Knob] rather than only on [Latch]
     * so the filter is one expression over the list rather than a cast at the call site.
     */
    val needsGraphicsDevice: Boolean get() = this is Latch && needsAccelerator
}

/**
 * A continuous setting of the world: a slider.
 *
 * [show] turns the raw value into what is printed beside the label. Several of these are in
 * normalized elevation units or in coefficients of a stream-power law, which mean nothing to a
 * reader, so they are shown against their own default instead — 100% is the world the generator
 * makes when left alone.
 */
internal class Dial(
    override val section: PanelSection,
    override val label: String,
    val range: ClosedFloatingPointRange<Float>,
    val show: (Float) -> String,
    val read: (WorldGenConfig) -> Float,
    private val write: (WorldGenConfig, Float) -> WorldGenConfig
) : Knob() {
    fun set(config: WorldGenConfig, value: Float): WorldGenConfig =
        write(config, value.coerceIn(range.start, range.endInclusive))
}

/**
 * A count, changed one at a time.
 *
 * Plates and realms were sliders, and should not have been: a world of eleven plates and a world
 * of twelve are different worlds, not the same world adjusted, and dragging a slider through them
 * regenerates every world in between. A stepper asks for exactly the number wanted. [range] is the
 * slider's old range, so nothing that could be chosen before is out of reach now.
 */
internal class Stepper(
    override val section: PanelSection,
    override val label: String,
    val range: IntRange,
    val read: (WorldGenConfig) -> Int,
    private val write: (WorldGenConfig, Int) -> WorldGenConfig
) : Knob() {
    fun set(config: WorldGenConfig, value: Int): WorldGenConfig =
        write(config, value.coerceIn(range.first, range.last))
}

/** A setting of the world that is on or off. */
internal class Latch(
    override val section: PanelSection,
    override val label: String,
    /** True for the graphics-acceleration switch, which a machine with no usable device cannot offer. */
    val needsAccelerator: Boolean = false,
    val read: (WorldGenConfig) -> Boolean,
    private val write: (WorldGenConfig, Boolean) -> WorldGenConfig
) : Knob() {
    fun set(config: WorldGenConfig, value: Boolean): WorldGenConfig = write(config, value)
}

/**
 * Something that changes only how a finished world is drawn, and so never regenerates anything.
 *
 * These sit in the section of the thing they draw rather than being herded into a "Features" box:
 * whether rivers are drawn is a question about water, and it belongs beside the question of how
 * much water the dry basins hold.
 */
internal class Mark(
    override val section: PanelSection,
    override val label: String,
    val read: (RenderOptions) -> Boolean,
    private val write: (RenderOptions, Boolean) -> RenderOptions
) : Knob() {
    fun set(options: RenderOptions, value: Boolean): RenderOptions = write(options, value)
}

/** Prints a value against its own default, for settings whose raw units mean nothing. */
private fun relativeTo(default: Float): (Float) -> String =
    { "${(it / default * 100f).roundToInt()}%" }

/**
 * Every knob on the panel and in the atlas, in the order they are drawn.
 *
 * The ranges are the same ones the old panel used wherever a control already existed, so no world
 * that could be asked for before is out of reach now; the four new ones state their range and the
 * reason for it beside themselves.
 */
internal object Knobs {

    /** Powers of two, because the terrain integrator is FFT-based. */
    val RESOLUTIONS: List<Int> = listOf(512, 1024, 2048, 4096)

    fun withSeed(config: WorldGenConfig, seed: Long): WorldGenConfig = config.copy(seed = seed)

    /**
     * `atResolution`, never a raw copy: settings measured in cells have to be rescaled with the
     * grid or the world changes character instead of gaining detail. A raw copy leaves mountain
     * belts a fraction of their proper width, which surfaces plate edges as straight cliffs.
     */
    fun atResolution(config: WorldGenConfig, size: Int): WorldGenConfig =
        config.atResolution(size, size)

    val oceanCoverage = Dial(
        section = PanelSection.WORLD,
        label = "Ocean coverage",
        range = 0.05f..0.95f,
        show = { "${(it * 100).roundToInt()}%" },
        read = { it.seaLevel },
        write = { config, v -> config.copy(seaLevel = v) }
    )

    /**
     * Where erosion runs. In the header, directly under the working resolution, which is the other
     * half of the same question: how finely the world is computed, and by what.
     *
     * It has moved twice. Before F2 it sat beside the export buttons under "Acceleration", which
     * implied it was something about the picture; F2 filed it in World, where it fell under Ocean
     * coverage and read as if it applied to the sea. It is not a setting of the world at all — the
     * world is the same world either way — it is a fact about this machine, and on a large one it
     * is most of the difference between a minute and a quarter of an hour.
     */
    val graphicsAcceleration = Latch(
        section = PanelSection.HEADER,
        // Not "graphics card". The device on the phone this was reported from is a block of cores
        // on the same die as the processor, and there is no card anywhere in it; "acceleration" is
        // what the switch does and is true of every host that offers one.
        label = "Graphics acceleration",
        needsAccelerator = true,
        read = { it.erosion.acceleration == Acceleration.GPU },
        write = { config, on ->
            config.copy(
                erosion = config.erosion.copy(
                    acceleration = if (on) Acceleration.GPU else Acceleration.CPU
                )
            )
        }
    )

    val plates = Stepper(
        section = PanelSection.TERRAIN,
        label = "Plates",
        range = 3..40,
        read = { it.tectonics.plateCount },
        write = { config, n -> config.copy(tectonics = config.tectonics.copy(plateCount = n)) }
    )

    /**
     * The crest height of a coastal range, and not [com.cartogenesis.worldgen.model.
     * TectonicsConfig.mountainHeight], which is the obvious-looking field and the wrong one.
     * With crust-pair profiles on — the default since B2 — `mountainHeight` feeds only ocean
     * ridges and transform faults, both of them under water; the mountains anyone can see on the
     * map are the Andean margins, which are the commonest convergent boundary a world gets, and
     * this is their height. 0.20 gives a coast with hills on it; 0.90 gives a wall.
     */
    val mountainHeight = Dial(
        section = PanelSection.TERRAIN,
        label = "Mountain height",
        range = 0.20f..0.90f,
        show = relativeTo(0.52f),
        read = { it.tectonics.andeanHeight },
        write = { config, v -> config.copy(tectonics = config.tectonics.copy(andeanHeight = v)) }
    )

    /**
     * How readily running water cuts down, per hydraulic round. Raising it deepens valleys and
     * sharpens divides; the range stops a fifth of the default, where the land is barely
     * dissected, and twice it, above which channels cut clean to the sea and leave the ground
     * between them as unconnected plateaux.
     */
    val erosionStrength = Dial(
        section = PanelSection.TERRAIN,
        label = "Erosion strength",
        range = 0.011f..0.110f,
        show = relativeTo(0.055f),
        read = { it.erosion.erodibility },
        write = { config, v -> config.copy(erosion = config.erosion.copy(erodibility = v)) }
    )

    /**
     * How far the thermal equator swings toward the summer hemisphere, in degrees. Everything
     * seasonal follows from it: the wind belts and the rain belts ride on it, so it is also the
     * monsoon dial. Zero is a world with no seasons at all; 25 is past Earth's own obliquity.
     */
    val seasonalTilt = Dial(
        section = PanelSection.CLIMATE,
        label = "Seasonal tilt",
        range = 0f..25f,
        show = { "${it.roundToInt()}°" },
        read = { it.climate.seasonalTilt },
        write = { config, v -> config.copy(climate = config.climate.copy(seasonalTilt = v)) }
    )

    /**
     * How much moisture windward slopes wring out of passing air — the moisture knob of the
     * climate stage, and the one whose effect is unmistakable on the map, since it is what puts a
     * desert behind every range. Continentality would have been the other candidate, but it moves
     * the *seasonal swing*, which is what [seasonalTilt] beside it already governs; this moves the
     * rain. Zero flattens every rain shadow; 5 lets a range take essentially all the rain.
     */
    val rainShadow = Dial(
        section = PanelSection.CLIMATE,
        label = "Rain shadow",
        range = 0f..5f,
        show = relativeTo(2.0f),
        read = { it.climate.orographicStrength },
        write = { config, v -> config.copy(climate = config.climate.copy(orographicStrength = v)) }
    )

    val ice = Latch(
        section = PanelSection.CLIMATE,
        label = "Ice sheets and glaciers",
        read = { it.glaciation.enabled },
        write = { config, on -> config.copy(glaciation = config.glaciation.copy(enabled = on)) }
    )

    val rivers = Mark(
        section = PanelSection.WATER,
        label = "Rivers",
        read = { it.showRivers },
        write = { options, on -> options.copy(showRivers = on) }
    )

    val lakes = Mark(
        section = PanelSection.WATER,
        label = "Lakes",
        read = { it.showLakes },
        write = { options, on -> options.copy(showLakes = on) }
    )

    /**
     * Off, every closed basin fills to its own rim; on, it settles where its catchment's runoff
     * matches evaporation off the water, which is why the Caspian and Lake Eyre are not the size
     * of the hollows they sit in.
     */
    val dryBasins = Latch(
        section = PanelSection.WATER,
        label = "Dry basins hold less water",
        read = { it.lakes.waterBalance },
        write = { config, on -> config.copy(lakes = config.lakes.copy(waterBalance = on)) }
    )

    val realms = Stepper(
        section = PanelSection.PEOPLES,
        label = "Realms",
        range = 0..40,
        read = { it.nations.nationCount },
        write = { config, n -> config.copy(nations = config.nations.copy(nationCount = n)) }
    )

    /**
     * The two wilderness modes were a pair of chips filling the width of the panel, which is a
     * radio group for a yes-or-no question. Off — the default — every last cell of land ends up
     * belonging to somebody; on, realms stop where expansion gets expensive and hostile country
     * is left unclaimed.
     */
    val wilderness = Latch(
        section = PanelSection.PEOPLES,
        label = "Leave wilderness unclaimed",
        read = { it.nations.wilderness == WildernessMode.LEAVE_WILDERNESS },
        write = { config, on ->
            config.copy(
                nations = config.nations.copy(
                    wilderness = if (on) WildernessMode.LEAVE_WILDERNESS
                    else WildernessMode.CLAIM_ALL_LAND
                )
            )
        }
    )

    /**
     * Reads [RenderOptions.showBorders], where the old switch read `bordersVisible` and wrote
     * `showBorders`. Those are not the same question: `bordersVisible` is also true whenever the
     * political view is up, which draws borders whatever this says — so in that view the switch
     * showed on, and turning it off left it on, since what it wrote was not what it read. A
     * control has to show its own setting.
     */
    val borders = Mark(
        section = PanelSection.PEOPLES,
        label = "Realm borders",
        read = { it.showBorders },
        write = { options, on -> options.copy(showBorders = on) }
    )

    val hillshade = Mark(
        section = PanelSection.CARTOGRAPHY,
        label = "Relief shading",
        read = { it.showHillshade },
        write = { options, on -> options.copy(showHillshade = on) }
    )

    /**
     * The one control here that the old panel did not have at all. [RenderOptions.showCoastline]
     * has existed since the rasterizer did and was reachable only by editing code, which for a
     * line that is drawn on every map of every style is an odd thing to have hidden.
     */
    val coastline = Mark(
        section = PanelSection.CARTOGRAPHY,
        label = "Coastline",
        read = { it.showCoastline },
        write = { options, on -> options.copy(showCoastline = on) }
    )

    /** The atlas's own two, drawn in the Atlas pane rather than here. */
    val landmarkCount = Dial(
        section = PanelSection.ATLAS,
        label = "Points of interest",
        range = 0f..200f,
        show = { it.roundToInt().toString() },
        read = { it.landmarks.count.toFloat() },
        write = { config, v ->
            config.copy(landmarks = config.landmarks.copy(count = v.roundToInt()))
        }
    )

    val landmarks = Mark(
        section = PanelSection.ATLAS,
        label = "Landmarks",
        read = { it.showLandmarks },
        write = { options, on -> options.copy(showLandmarks = on) }
    )

    /** Every knob there is, panel and atlas alike, in the order they are drawn. */
    val all: List<Knob> = listOf(
        graphicsAcceleration,
        oceanCoverage,
        plates, mountainHeight, erosionStrength,
        seasonalTilt, rainShadow, ice,
        rivers, lakes, dryBasins,
        realms, wilderness, borders,
        hillshade, coastline,
        landmarkCount, landmarks
    )

    fun inSection(section: PanelSection): List<Knob> = all.filter { it.section == section }
}

/**
 * What sizes a finished map can be written at, and which of them this build can actually reach.
 *
 * Declared here for the same reason the knobs are: the ceiling is a *rule*, and a rule drawn only
 * inside a composable can only be checked by looking at it. The rule is that no export ever runs
 * above [Platform.exportCeiling] — 8192 exhausts a 10 GB heap inside the generator after about
 * nineteen minutes and never draws a pixel, so the chip for it is disabled and any request for it,
 * including one restored from a preference written by an older build, comes back as 4096.
 *
 * [clamp] is on the path every export takes rather than only on the button, because a disabled
 * control is a courtesy and not a guarantee: the size that reaches the platform is the one that
 * went through here.
 */
internal object Exports {

    /** The three the row offers. Powers of two, as the working resolutions are. */
    val SIZES: List<Int> = listOf(2048, 4096, 8192)

    /** The picture formats, in the order the chips sit: lossless, small, compatible. */
    val PICTURES: List<ExportFormat> = ExportFormat.entries

    /** The data layers, which are the second kind of export F12 added. */
    val LAYERS: List<DataLayer> = DataLayer.entries

    fun reachable(size: Int, ceiling: Int): Boolean = size <= ceiling

    /**
     * The largest offered size this build can finish — what an unreachable request falls back to.
     *
     * A ceiling below the smallest offered size would leave nothing to fall back *to*, so that
     * case returns the smallest rather than nothing: a build that cannot manage 2048 has a worse
     * problem than the export row.
     */
    fun clamp(size: Int, ceiling: Int): Int = when {
        reachable(size, ceiling) -> size
        else -> SIZES.filter { it <= ceiling }.maxOrNull() ?: SIZES.min()
    }

    /** Why a size is greyed out, in the small print, when someone reaches for it. */
    fun unreachableNote(size: Int): String = "$size needs more memory than this build can hold"
}

/**
 * What an Export button writes when it is pressed.
 *
 * One selection across both chip rows rather than one per row, and that is a deliberate choice
 * about the interface rather than about the data. The size buttons are the verb — they are what
 * "do it" looks like here — and a verb needs one object. Two selections would need two rows of
 * size buttons, which is 48 dp of a phone sheet spent saying the same thing twice, or a size row
 * whose meaning depends on which chip you touched last, which is worse than either.
 *
 * The reader's preferred *picture* format is still the only thing the settings file carries (see
 * [AppSettings.exportFormat]): a data layer is something someone reaches for on the occasion they
 * need it, not a standing preference, so a session that has exported a heightmap does not open
 * tomorrow pointed at one.
 */
internal sealed interface ExportChoice {

    /** The chip's word, which is also what the notice says was written. */
    val label: String

    /** The one line of small print under the two rows. */
    val detail: String

    data class Picture(val format: ExportFormat) : ExportChoice {
        override val label: String get() = format.label
        override val detail: String get() = format.detail
    }

    data class Layer(val layer: DataLayer) : ExportChoice {
        override val label: String get() = layer.label
        override val detail: String get() = layer.detail
    }
}

/**
 * The two choices that moved out of the panel and onto the map: which style, which view.
 *
 * They are not knobs, and forcing them into [Knob] would have been a lie — a knob is a row in a
 * section, and these are a segmented control and a menu over the chart itself. But the same
 * argument that made the panel data applies to them: [PanelKnobsTest] has to be able to ask "can
 * the interface still set the style?" without driving a composition, and the guard F2 wrote must
 * not go quiet merely because the control it was watching moved. So the toolbar is declared here
 * too, and `MapChrome` draws exactly this.
 *
 * Both writers are `RenderOptions.copy`, so neither ever regenerates a world: changing the style
 * or the view re-renders the picture the world already is.
 */
internal object MapChrome {

    /** Ten, in the order [MapStyle] declares them: modern, through hand-drawn, to Mars. */
    val styles: List<MapStyle> = MapStyle.entries

    /** Fifteen, the two readable ones first and the diagnostics behind them. */
    val views: List<MapView> = MapView.entries

    fun withStyle(options: RenderOptions, style: MapStyle): RenderOptions =
        options.copy(style = style)

    fun withView(options: RenderOptions, view: MapView): RenderOptions =
        options.copy(view = view)

    /**
     * Whether the style has any say over what is on screen.
     *
     * The diagnostic views carry meaning in their colours — a rainfall map drawn in Vellum's
     * earths would be a lie — so they ignore the style. The toolbar says so rather than leaving a
     * row of controls that quietly do nothing.
     */
    fun styleApplies(view: MapView): Boolean = view.showsTerrain

    /** The one line of small print the toolbar carries: what this style is, or why it is unused. */
    fun note(options: RenderOptions): String =
        if (styleApplies(options.view)) options.style.detail
        else "The ${options.view.label.lowercase()} view ignores the style: its colours mean something."
}

/**
 * Which sections are unrolled, remembered for as long as the app is open.
 *
 * Held here rather than inside the section composable so that switching to the atlas or the
 * library and back does not roll every section up again: the panel a reader left is the panel they
 * come back to. A section absent from the map is at its default, which is closed for all but
 * [PanelSection.WORLD] — so the initial state costs nothing to represent.
 */
internal class SectionState {
    private val open = mutableStateMapOf<PanelSection, Boolean>()

    fun isOpen(section: PanelSection): Boolean = open[section] ?: section.openByDefault

    fun toggle(section: PanelSection) {
        open[section] = !isOpen(section)
    }
}
