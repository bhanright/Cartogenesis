package com.cartogenesis.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cartogenesis.cartography.MapStyle
import com.cartogenesis.cartography.MapView

/**
 * One layout tree, two arrangements.
 *
 * Nothing in the application was wrong on a phone except its shape. The engine already runs at 512
 * in a mobile browser, Compose for Wasm already delivers touch events as pointer events, and the
 * theme, the panel and the map all draw correctly at any size. What broke was geometry: a 320 dp
 * column beside a map leaves 70 dp of map on a 390 dp screen, a toolbar of ten style names needs
 * 660 dp of one, and a menu strip of three words is three targets of about 40 by 24 dp each.
 *
 * So there are two arrangements of the *same* interface rather than a second interface. Which one
 * is drawn is decided once, at the root, from the window's width and the pointer the host reports;
 * everything below is handed the answer. [Arrangements] then declares, per arrangement, exactly
 * what the reader can reach — which knobs, which styles, which views, which sizes, which menu
 * commands — and the composables draw from that declaration rather than from their own idea of it.
 * That is what makes "the compact arrangement exposes every knob the wide one does" a thing a test
 * can ask (see `PanelKnobsTest`) instead of a thing somebody has to check by hand on a phone.
 */
internal enum class WindowShape {
    /** A window with room for the panel beside the map: the desktop, and a landscape tablet. */
    WIDE,

    /** A phone, or anything driven by a fingertip: the map fills the screen, the panel is a sheet. */
    COMPACT
}

internal object Layouts {

    /**
     * The width below which the panel cannot sit beside the map.
     *
     * 320 dp of panel, 10 dp of gutter either side and the map's own toolbar — whose narrowest
     * useful form is still the style menu, the view menu and the small print — need about 800 dp
     * between them before the map is the larger half of the window. Below that the map is a strip,
     * and a map that is a strip is not the instrument F3 made it.
     */
    const val COMPACT_BELOW_DP: Float = 800f

    /**
     * The working resolution a compact window starts at, whatever the preference says.
     *
     * A phone browser is one thread and a few hundred megabytes; 512 takes a few seconds there and
     * 1024 reads as a hang. A settings file carried over from a desktop can perfectly well say
     * 2048, and honouring it on a phone would be honouring a preference into a crash.
     */
    const val COMPACT_RESOLUTION: Int = 512

    /**
     * Which arrangement a window of this width, driven by this pointer, gets.
     *
     * The pointer matters independently of the width: a tablet held in landscape is wide enough for
     * the panel but is still driven by a fingertip, and a 13 dp slider thumb is not a fingertip
     * target. A coarse pointer therefore forces the compact arrangement at any width.
     */
    fun shape(widthDp: Float, coarsePointer: Boolean): WindowShape =
        if (widthDp < COMPACT_BELOW_DP || coarsePointer) WindowShape.COMPACT else WindowShape.WIDE
}

/** Which arrangement is being drawn, for the few places too deep to be handed it. */
internal val LocalWindowShape = staticCompositionLocalOf { WindowShape.WIDE }

/**
 * How big a thing has to be before a finger can hit it.
 *
 * Provided by [CartogenesisTheme] and read by [Slider], [Switch] and the two glyph buttons, so that
 * making the controls touchable is one change to the theme rather than an edit to every control —
 * which is the same argument `Controls.kt` is written around. [POINTER] is exactly what F1 shipped,
 * to the pixel, so a mouse-driven window is not touched by any of this.
 */
internal class TouchTargets(
    val sliderHeight: Dp,
    val sliderThumb: Dp,
    /** The least a switch or a glyph button may be tall. Zero leaves it exactly as drawn. */
    val minTarget: Dp,
    /** Added to the vertical padding of a row that can be tapped. */
    val extraRowPadding: Dp
) {
    companion object {
        /** A mouse. The numbers F1 chose, unchanged. */
        val POINTER = TouchTargets(
            sliderHeight = 26.dp,
            sliderThumb = 13.dp,
            minTarget = 0.dp,
            extraRowPadding = 0.dp
        )

        /**
         * A fingertip. 48 dp is the figure both platform guidelines settle on for the smallest
         * target a thumb hits reliably, and a 20 dp thumb on a 44 dp rail is a slider that can be
         * caught without aiming.
         */
        val TOUCH = TouchTargets(
            sliderHeight = 44.dp,
            sliderThumb = 20.dp,
            minTarget = 48.dp,
            extraRowPadding = 8.dp
        )
    }
}

internal val LocalTouchTargets = staticCompositionLocalOf { TouchTargets.POINTER }

/** A part of the chart legend along the map's bottom edge. */
internal enum class LegendPart { CARTOUCHE, ZOOM_OUT, ZOOM_IN, FIT }

/**
 * Everything an arrangement puts within reach, named.
 *
 * Two independently written lists (see [Arrangements.wide] and [Arrangements.compact]), compared by
 * `PanelKnobsTest`. The composables render from these, so a control dropped from the compact
 * arrangement is dropped from this declaration as well and the comparison fails.
 */
internal class Reachable(
    /** Every knob the panel draws, header first, then the sections in pipeline order. */
    val knobs: List<Knob>,
    val styles: List<MapStyle>,
    val views: List<MapView>,
    val exportSizes: List<Int>,
    val commands: List<MenuCommand>,
    val legend: List<LegendPart>
)

internal object Arrangements {

    fun of(shape: WindowShape, platform: Platform): Reachable = when (shape) {
        WindowShape.WIDE -> wide(platform)
        WindowShape.COMPACT -> compact(platform)
    }

    /**
     * The knobs above the sections, which is the graphics-card switch and nothing else.
     *
     * Empty on a host with no graphics API at all — a phone browser without WebGPU, which is most
     * of them. Until now that switch was drawn disabled with a line of small print saying why, and
     * on a desktop that is right: the machine has OpenGL, this driver declined, and the reader is
     * owed the reason. In a browser that has never heard of WebGPU there is no "why" worth 60 dp of
     * a 390 dp screen — the feature does not exist on the device — so the row goes. See
     * [Platform.graphicsApiPresent], which is the query this asks.
     */
    fun headerKnobs(platform: Platform): List<Knob> =
        Knobs.inSection(PanelSection.HEADER)
            .filter { platform.graphicsApiPresent || !it.needsGraphicsDevice }

    fun knobsIn(section: PanelSection, platform: Platform): List<Knob> =
        if (section == PanelSection.HEADER) headerKnobs(platform)
        else Knobs.inSection(section)

    /**
     * The wide window, which is what F1 to F4 built: a 320 dp panel column, a segmented row of
     * every style over the map, the views behind a menu, and the legend carrying the cartouche,
     * the zoom readout, its two steps and Fit.
     */
    private fun wide(platform: Platform) = Reachable(
        knobs = headerKnobs(platform) + PANEL_SECTIONS.flatMap { Knobs.inSection(it) },
        styles = MapChrome.styles,
        views = MapChrome.views,
        exportSizes = Exports.SIZES,
        commands = Menus.file(platform) + MenuCommand.TOOLBAR + Menus.help,
        legend = listOf(
            LegendPart.CARTOUCHE,
            LegendPart.ZOOM_OUT,
            LegendPart.ZOOM_IN,
            LegendPart.FIT
        )
    )

    /**
     * The compact window: the map takes the screen, and the panel is a sheet that pulls up from the
     * bottom edge carrying the same header and the same six sections.
     *
     * Every list here is deliberately spelled out again rather than delegated to [wide], because
     * the whole point of the guard is that these two can disagree — and the one place they do is
     * the legend. The two zoom steps go: pinch is the gesture a phone already has for this, the
     * strip is 390 dp wide and has a world's name to print on it, and Fit stays because there is no
     * gesture for "show me all of it" that anyone would guess. Nothing else is lost: the ten styles
     * move from a segmented row into a menu, the three menus into one button, and every knob is in
     * the sheet.
     */
    private fun compact(platform: Platform) = Reachable(
        knobs = headerKnobs(platform) + PANEL_SECTIONS.flatMap { Knobs.inSection(it) },
        styles = MapChrome.styles,
        views = MapChrome.views,
        exportSizes = Exports.SIZES,
        commands = Menus.file(platform) + MenuCommand.TOOLBAR + Menus.help,
        legend = listOf(LegendPart.CARTOUCHE, LegendPart.FIT)
    )
}
