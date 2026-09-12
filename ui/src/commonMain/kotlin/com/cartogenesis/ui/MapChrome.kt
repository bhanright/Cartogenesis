package com.cartogenesis.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cartogenesis.cartography.MapStyle
import com.cartogenesis.cartography.MapView
import com.cartogenesis.cartography.RenderOptions
import kotlin.math.roundToInt

/**
 * The two strips that turn the map into the instrument.
 *
 * Everything a reader does *to the picture* — which style it is drawn in, which layer of the world
 * it shows, how far in it is zoomed — now happens on the picture, and everything the picture says
 * about itself is said along its bottom edge. The settings panel keeps what makes a world; the map
 * keeps what looks at one. Before F3 the style and the view were forty-eight rows of chips in a
 * side panel, so choosing between Vellum and Nautical meant reading two words in a column and
 * watching something change three hundred pixels away.
 *
 * Both strips are drawn in [OverMap]'s colours rather than the theme's, for the reason that object
 * gives: they lie on a rendered chart whose paper is the *style's*, and a paper-coloured strip over
 * Vellum would disappear. They are translucent ink either way, in daylight and after dark alike.
 */

/**
 * Along the top edge of the map, inside it: the styles as a segmented control, the views as a menu.
 *
 * Why the two are drawn differently is a matter of arithmetic at the width this application is
 * designed for. With the right-hand column folded away (F3 moved Export into the header panel) the
 * map is about 1080 dp wide at a 1440 dp window. The eleven style names — Atlas, Vellum, Ink wash,
 * Nautical, Midnight, Schoolroom, Verdant, Scroll, Pen and ink, Mars (F4) and Colour-blind (F6) —
 * measure some 740 dp set as cells, so they still fit on one row with room left for the small
 * print, which is the first thing to be elided as the row fills. The fifteen view names run past 1300
 * dp, largely because four of them are things like "Temperature, summer"; a second segmented row
 * would either wrap or be cut, and a wrapped segmented control is no longer a segmented control.
 * So the views are a menu, which also puts the current view in words at the right of the strip
 * where a chart would print its subject.
 *
 * No tonal fill anywhere: the current style is *inked* — full-strength parchment with a rule under
 * the cell — and the rest are the same word at less weight, which is how a printed key marks the
 * sheet you are looking at.
 */
@Composable
internal fun MapToolbar(
    options: RenderOptions,
    styles: List<MapStyle>,
    views: List<MapView>,
    onOptions: (RenderOptions) -> Unit
) {
    Surface(color = LocalChromeDetail.current.strip(), contentColor = OverMap.Parchment) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            styles.forEachIndexed { index, style ->
                if (index > 0) CellRule()
                StripCell(
                    label = style.label,
                    selected = options.style == style,
                    dimmed = !MapChrome.styleApplies(options.view),
                    onClick = { onOptions(MapChrome.withStyle(options, style)) }
                )
            }

            // The one line of small print: what this style is, or why the current view ignores it.
            Text(
                MapChrome.note(options),
                style = MaterialTheme.typography.labelSmall,
                color = OverMap.ParchmentFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
            )

            ViewMenu(options, views, onOptions)
        }
    }
}

/**
 * The same toolbar on a phone: three targets and one name, instead of eleven names and a menu.
 *
 * The segmented row above is a chart's key — every style named, the current one inked — and it
 * needs about 740 dp to be that. At 390 dp the same information has to be a menu, so the row
 * becomes: the single menu button that replaces the whole menu strip, a palette glyph carrying the
 * current style's *name* (the one word worth its width, since it is the answer to "what am I
 * looking at"), and the view menu, which was already a menu and stays one. The small print goes: it
 * is a sentence about a style the reader has just chosen from a list that said the same thing.
 *
 * Drawn in the same [OverMap] ink as the wide strip, for the same reason — this lies on a rendered
 * chart whose paper belongs to the style, not to the theme.
 */
@Composable
internal fun CompactMapToolbar(
    options: RenderOptions,
    styles: List<MapStyle>,
    views: List<MapView>,
    choices: Boolean,
    onOptions: (RenderOptions) -> Unit,
    menu: @Composable () -> Unit
) {
    Surface(color = LocalChromeDetail.current.strip(), contentColor = OverMap.Parchment) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            menu()
            if (choices) {
                StyleMenu(options, styles, onOptions)
                Box(Modifier.weight(1f))
                ViewMenu(options, views, onOptions)
            }
        }
    }
}

/**
 * `◑ Vellum ▾`, and the other ten behind it.
 *
 * The compact counterpart of the segmented row, and the only place in the application where a
 * choice of eleven is offered as a menu rather than as a key — which is a loss, and is why the current
 * style's name is spelled out on the button rather than left to an icon.
 */
@Composable
private fun StyleMenu(
    options: RenderOptions,
    styles: List<MapStyle>,
    onOptions: (RenderOptions) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val dimmed = !MapChrome.styleApplies(options.view)
    Box {
        RuledButton(onClick = { open = true }) {
            Icon(
                Icons.Filled.Palette,
                contentDescription = "Style",
                tint = OverMap.ParchmentFaint,
                modifier = Modifier.size(16.dp)
            )
            Text(
                options.style.label,
                style = MaterialTheme.typography.labelMedium,
                color = if (dimmed) OverMap.ParchmentDim else OverMap.Parchment,
                maxLines = 1
            )
            Text("▾", style = MaterialTheme.typography.labelMedium, color = OverMap.ParchmentDim)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            styles.forEach { style ->
                DropdownMenuItem(
                    text = {
                        Text(
                            style.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (style == options.style) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = {
                        onOptions(MapChrome.withStyle(options, style))
                        open = false
                    }
                )
            }
        }
    }
}

/** One name in the style strip. Inked when it is the one on screen, and never filled. */
@Composable
private fun StripCell(
    label: String,
    selected: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit
) {
    val colour = when {
        selected && dimmed -> OverMap.ParchmentDim
        selected -> OverMap.Parchment
        else -> OverMap.ParchmentFaint
    }
    Box(
        Modifier
            .clickable(onClick = onClick)
            .drawBehind {
                if (!selected) return@drawBehind
                // The rule that marks the chosen cell, drawn rather than laid out so that it is
                // exactly as wide as the cell and cannot be pushed around by the row.
                drawRect(
                    color = colour,
                    topLeft = Offset(0f, size.height - 1.5f),
                    size = Size(size.width, 1.5f)
                )
            }
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colour, maxLines = 1)
    }
}

/** The hairline between two cells of the segmented control. */
@Composable
private fun CellRule() {
    Box(Modifier.width(1.dp).height(13.dp).background(OverMap.Rule))
}

/**
 * A box ruled on all four sides, which is what a control over the chart looks like here.
 *
 * Lifted out of [ViewMenu], where it was written inline, so that the style menu beside it is
 * plainly the same object and not a second one that happens to look similar. The only thing F5
 * adds is the minimum size, which is zero under a mouse — so the wide toolbar's View button is the
 * same pixels it was — and a fingertip's worth under a coarse pointer.
 */
@Composable
private fun RuledButton(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .clickable(onClick = onClick)
            .drawBehind {
                val rule = OverMap.Rule
                drawRect(rule, Offset(0f, 0f), Size(size.width, 1f))
                drawRect(rule, Offset(0f, size.height - 1f), Size(size.width, 1f))
                drawRect(rule, Offset(0f, 0f), Size(1f, size.height))
                drawRect(rule, Offset(size.width - 1f, 0f), Size(1f, size.height))
            }
            .sizeIn(minHeight = LocalTouchTargets.current.minTarget)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/**
 * `View  Fantasy ▾`, and fifteen of them behind it.
 *
 * The menu itself is an ordinary Material menu and so takes the *theme's* paper rather than
 * [OverMap]'s: it is a sheet that opens over the application, not an annotation on the chart.
 */
@Composable
private fun ViewMenu(
    options: RenderOptions,
    views: List<MapView>,
    onOptions: (RenderOptions) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        RuledButton(onClick = { open = true }) {
            Text(
                "View",
                style = MaterialTheme.typography.labelSmall,
                color = OverMap.ParchmentFaint
            )
            Text(
                options.view.label,
                style = MaterialTheme.typography.labelMedium,
                color = OverMap.Parchment,
                maxLines = 1
            )
            Text("▾", style = MaterialTheme.typography.labelMedium, color = OverMap.ParchmentDim)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            views.forEach { view ->
                DropdownMenuItem(
                    text = {
                        Text(
                            view.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (view == options.view) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = {
                        onOptions(MapChrome.withView(options, view))
                        open = false
                    }
                )
            }
        }
    }
}

/**
 * Along the bottom edge: the cartouche on the left, the zoom on the right.
 *
 * A chart's legend is at the foot of the sheet, and carries the name of the thing and the scale of
 * it. This one carries the name of the world, its seed and largest realm, and how far in the view
 * is — which is as close to a scale bar as a world with no stated size can honestly get.
 *
 * With no world on screen there is nothing to name, so the left side falls back to the one line
 * F0 wrote for the empty canvas, which is the only instruction the application has ever given.
 */
@Composable
internal fun ChartLegend(
    cartouche: Cartouche?,
    prompt: String,
    camera: MapCamera,
    /**
     * Which halves of the legend this arrangement carries. A compact window drops the zoom readout
     * and its two steps and keeps Fit — pinch is the gesture a phone already has for zooming, and
     * there is no gesture anyone would guess for "show me all of it". Declared by [Arrangements]
     * rather than decided here, so the test can ask what a phone loses.
     */
    parts: List<LegendPart>
) {
    Surface(color = LocalChromeDetail.current.strip(), contentColor = OverMap.Parchment) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            // Boxed where the chrome asks — Allied, whose subject is a map margin, and where the
            // title block of a sheet is always ruled off from the sheet.
            val boxed = LocalChromeDetail.current.boxedCartouche
            Column(
                Modifier.weight(1f).then(
                    if (boxed) Modifier.border(1.dp, OverMap.Rule).padding(horizontal = 7.dp, vertical = 4.dp)
                    else Modifier
                )
            ) {
                if (cartouche == null) {
                    Text(
                        prompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = OverMap.ParchmentDim
                    )
                } else {
                    Text(
                        cartouche.worldName,
                        style = MaterialTheme.typography.titleMedium,
                        color = OverMap.Parchment,
                        maxLines = 1
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            cartouche.facts,
                            style = MaterialTheme.typography.labelSmall,
                            color = OverMap.ParchmentDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (cartouche.footnote.isNotEmpty()) {
                            Text(
                                "· ${cartouche.footnote}",
                                style = MaterialTheme.typography.labelSmall,
                                color = OverMap.ParchmentFaint,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // The wheel and the pinch are both invisible, so the same thing is offered where it
            // can be seen. These sat loose over the bottom-right corner of the map before F3;
            // they are the right-hand half of the legend now.
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 12.dp)
            ) {
                if (LegendPart.ZOOM_OUT in parts || LegendPart.ZOOM_IN in parts) {
                    Text(
                        "${camera.percent}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = OverMap.ParchmentDim,
                        modifier = Modifier.widthIn(min = 34.dp)
                    )
                }
                if (LegendPart.ZOOM_OUT in parts) {
                    ZoomButton("−") { camera.step(1f / MapCamera.STEP) }
                }
                if (LegendPart.ZOOM_IN in parts) {
                    ZoomButton("+") { camera.step(MapCamera.STEP) }
                }
                if (LegendPart.FIT in parts) {
                    ZoomButton("Fit") { camera.fit() }
                }
            }
        }
    }
}

/** Deliberately plain: these sit over the map and should not compete with it. */
@Composable
private fun ZoomButton(label: String, onClick: () -> Unit) {
    val minimum = LocalTouchTargets.current.minTarget
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(2.dp),
        color = Color.Transparent,
        contentColor = OverMap.ParchmentDim,
        border = BorderStroke(1.dp, OverMap.Rule),
        modifier = Modifier.sizeIn(minWidth = minimum, minHeight = minimum)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
            )
        }
    }
}

/**
 * Where the map is being looked at from: how far in, and how far across.
 *
 * Hoisted out of the map canvas because F3 puts the zoom readout and its three buttons in the
 * legend, which is a sibling of the canvas rather than a child of it. Plain Compose state in a
 * plain class, so the arithmetic — which is the only part that can be wrong — is testable without
 * a composition, and so that a trip to the atlas and back does not reset the view.
 */
internal class MapCamera {
    var zoom by mutableStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)

    val percent: Int get() = (zoom * 100).roundToInt()

    /**
     * Zoom about a point rather than about the origin: whatever is under the cursor, or between
     * the fingers, has to stay under it, or the map slides away from whatever is being examined.
     */
    fun about(anchor: Offset, factor: Float, panChange: Offset = Offset.Zero) {
        val next = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        pan = (pan - anchor) * (next / zoom) + anchor + panChange
        zoom = next
    }

    /** A press of − or +. No anchor to work from down in the legend, so the pan is left alone. */
    fun step(factor: Float) {
        zoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    fun fit() {
        zoom = 1f
        pan = Offset.Zero
    }

    companion object {
        const val MIN_ZOOM = 0.2f
        const val MAX_ZOOM = 40f

        /** One wheel notch, or one press of a button. Compounds, so it is a modest step. */
        const val STEP = 1.15f
    }
}
