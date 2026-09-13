package com.cartogenesis.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cartogenesis.cartography.MapScale
import com.cartogenesis.cartography.MapStyle
import com.cartogenesis.cartography.MapView
import com.cartogenesis.cartography.RenderOptions
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The two strips that turn the map into the instrument.
 *
 * Everything a reader does *to the picture* — which style it is drawn in, which layer of the world
 * it shows, how far in it is zoomed — now happens on the picture, and everything the picture says
 * about itself is said along its bottom edge. The settings panel keeps what makes a world; the map
 * keeps what looks at one. As rows of chips in a side panel, choosing between Vellum and Nautical
 * meant reading two words in a column and watching something change three hundred pixels away.
 *
 * Both strips are drawn in [OverMap]'s colours rather than the theme's, for the reason that object
 * gives: they lie on a rendered chart whose paper is the *style's*, and a paper-coloured strip over
 * Vellum would disappear. They are translucent ink either way, in daylight and after dark alike.
 */

/**
 * What a reader who cannot see the strip is told it is, and what a test asks for by name.
 *
 * The two toolbars are a row of unlabelled glyphs on a translucent wash, which is a shape with no
 * name unless one is given: a screen reader announcing "Menu, Style, View" says nothing about what
 * those three belong to. It also makes the strip a thing a guard can look for, which is the whole
 * of how `PhoneAtlasTest` asks whether the map's chrome is being drawn over a page of text.
 */
internal const val MAP_TOOLBAR: String = "Map toolbar"

/**
 * Along the top edge of the map, inside it: the styles as a segmented control, the views as a menu.
 *
 * Why the two are drawn differently is a matter of arithmetic at the width this application is
 * designed for. With Export in the header panel rather than a column of its own, the map is about
 * 1080 dp wide at a 1440 dp window. The twelve style names — Atlas, Vellum, Ink wash, Nautical,
 * Midnight, Schoolroom, Verdant, Scroll, Pen and ink, Mars, Natural and Colour-blind — measure some
 * 800 dp set as cells, so they still fit on one row with room left for the small
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
    Surface(
        color = LocalChromeDetail.current.strip(),
        contentColor = OverMap.Parchment,
        modifier = Modifier.semantics { contentDescription = MAP_TOOLBAR }
    ) {
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
 * The same toolbar on a phone: three targets and one name, instead of twelve names and a menu.
 *
 * The segmented row above is a chart's key — every style named, the current one inked — and it
 * needs about 800 dp to be that. At 390 dp the same information has to be a menu, so the row
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
    Surface(
        color = LocalChromeDetail.current.strip(),
        contentColor = OverMap.Parchment,
        modifier = Modifier.semantics { contentDescription = MAP_TOOLBAR }
    ) {
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
 * choice of twelve is offered as a menu rather than as a key — which is a loss, and is why the current
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
                    topLeft = Offset(0f, size.height - CHOSEN_CELL_RULE_PIXELS),
                    size = Size(size.width, CHOSEN_CELL_RULE_PIXELS)
                )
            }
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colour, maxLines = 1)
    }
}

/**
 * The rule under the chosen style's name, in device pixels.
 *
 * Half a pixel heavier than the hairlines beside it, which is what makes it read as underlining
 * the word rather than as one more division of the strip.
 */
private const val CHOSEN_CELL_RULE_PIXELS = 1.5f

/** The hairline between two cells of the segmented control. */
@Composable
private fun CellRule() {
    Box(Modifier.width(1.dp).height(13.dp).background(OverMap.Rule))
}

/**
 * A box ruled on all four sides, which is what a control over the chart looks like here.
 *
 * Written once rather than inline in [ViewMenu], so that the style menu beside it is plainly the
 * same object and not a second one that happens to look similar. The minimum size is zero under a
 * mouse — so the wide toolbar's View button is exactly the pixels it was drawn at — and a
 * fingertip's worth under a coarse pointer.
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
 * With no world on screen there is nothing to name, so the left side falls back to the one line of
 * instruction for the empty canvas, which is the only instruction the application ever gives.
 */
@Composable
internal fun ChartLegend(
    cartouche: Cartouche?,
    prompt: String,
    /**
     * What the interface is doing, if it is doing something, in the cartouche's place.
     *
     * Null in the wide arrangement, where the progress banner along the map's top edge is in plain
     * view beside a panel that is also visibly busy. On a phone the sheet takes two thirds of the
     * screen the moment Generate is pressed — Generate lives in the sheet — and the banner is then
     * a strip at the top of what is left, a long way from where the reader's thumb and eye are. So
     * the foot of the map says it too. It is the same sentence the banner is showing: the stage the
     * engine last reported, or the size of the export being drawn.
     */
    progress: String? = null,
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
        // Measured rather than assumed: the scale bar's length is a share of the frame, and the
        // legend spans the map's own width, so this box is the frame the bar is a share of.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val frameWidth = maxWidth
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                // Dressed where the chrome asks — Allied's map-margin box, Hessian's sewn label,
                // Roman's double rule, Hitchcock's spiral. Every one of them is drawn in [OverMap]'s
                // ink, because this lies on a chart whose paper belongs to the style.
                val shape = LocalChromeDetail.current.cartouche
                Column(Modifier.weight(1f).cartoucheFrame(shape)) {
                    if (progress != null) {
                        Text(
                            progress,
                            style = MaterialTheme.typography.bodySmall,
                            color = OverMap.Parchment,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else if (cartouche == null) {
                        Text(
                            prompt,
                            style = MaterialTheme.typography.bodySmall,
                            color = OverMap.ParchmentDim
                        )
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (shape == CartoucheStyle.SPIRAL) Spiral()
                            Text(
                                cartouche.worldName,
                                style = MaterialTheme.typography.titleMedium,
                                color = OverMap.Parchment,
                                maxLines = 1
                            )
                        }
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
                        // The scale, under the facts it is a fact about: it is quoted for the size the
                        // line above states, which is the size an export of this world comes out at.
                        Text(
                            cartouche.scale,
                            style = MaterialTheme.typography.labelSmall,
                            color = OverMap.ParchmentFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (LegendPart.SCALE in parts && cartouche != null && progress == null) {
                    ScaleBarStrip(cartouche.kilometresPerCellWidth, camera, frameWidth)
                }

                // The wheel and the pinch are both invisible, so the same thing is offered where it
                // can be seen: the right-hand half of the legend, rather than floating loose over
                // the map's bottom-right corner.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 12.dp)
                ) {
                    if (LegendPart.ZOOM_OUT in parts || LegendPart.ZOOM_IN in parts) {
                        Text(
                            "${camera.zoomPercent}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = OverMap.ParchmentDim,
                            modifier = Modifier.widthIn(min = 34.dp)
                        )
                    }
                    if (LegendPart.ZOOM_OUT in parts) {
                        ZoomButton("−") { camera.step(1f / MapCamera.ZOOM_STEP) }
                    }
                    if (LegendPart.ZOOM_IN in parts) {
                        ZoomButton("+") { camera.step(MapCamera.ZOOM_STEP) }
                    }
                    if (LegendPart.FIT in parts) {
                        ZoomButton("Fit") { camera.fit() }
                    }
                }
            }
        }
    }
}

/**
 * The scale bar in the legend: a round distance, and how far it reaches on the screen right now.
 *
 * The bar on an exported sheet is drawn onto the paper and is fixed once and for all; this one is
 * the same arithmetic taken at the zoom the reader is at, so it restates itself as they zoom in and
 * the distance it names comes down. [MapScale] chooses the number, from the 1-2-5 series and a
 * quarter of [frameWidth]; nothing here decides anything but where to put the ink.
 *
 * Nothing at all until the map pane has measured itself, because until then there is no honest
 * answer to how far a screen pixel reaches.
 */
@Composable
private fun ScaleBarStrip(kilometresPerCellWidth: Double, camera: MapCamera, frameWidth: Dp) {
    val pixelsPerCell = camera.pixelsPerCell
    if (pixelsPerCell <= 0f) return
    val density = LocalDensity.current
    val bar = MapScale.longestBarThatFits(
        kilometresPerCellWidth / pixelsPerCell,
        with(density) { frameWidth.toPx() }
    )
    if (bar.lengthPixels <= 0f || !bar.lengthPixels.isFinite()) return

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(start = 12.dp)
    ) {
        Text(
            bar.label,
            style = MaterialTheme.typography.labelSmall,
            color = OverMap.ParchmentDim,
            maxLines = 1
        )
        Canvas(
            Modifier
                .width(with(density) { bar.lengthPixels.toDp() })
                .height(SCALE_BAR_HEIGHT)
        ) {
            val rule = 1.dp.toPx()
            // The bar itself along the bottom, with a tick standing up at each end: two ends and a
            // length is the whole of what a scale bar has to show.
            drawRect(OverMap.Parchment, Offset(0f, size.height - rule), Size(size.width, rule))
            drawRect(OverMap.Parchment, Offset(0f, 0f), Size(rule, size.height))
            drawRect(OverMap.Parchment, Offset(size.width - rule, 0f), Size(rule, size.height))
        }
    }
}

/** How tall the legend's scale bar stands: a cap height, so it reads as a bracket. */
private val SCALE_BAR_HEIGHT = 7.dp

/**
 * The frame round the title block, as this chrome frames one.
 *
 * Four of the seventeen chromes ask for something and thirteen ask for nothing, and the thirteen
 * get the modifier back untouched — no border, no padding, no draw node — which is what keeps
 * their legends pixel-identical. The stitched and doubled forms are drawn rather than bordered
 * because `Modifier.border` takes one stroke and neither of those is one stroke.
 */
private fun Modifier.cartoucheFrame(shape: CartoucheStyle): Modifier = when (shape) {
    CartoucheStyle.PLAIN, CartoucheStyle.SPIRAL -> this
    CartoucheStyle.BOXED ->
        this.border(1.dp, OverMap.Rule).padding(horizontal = 7.dp, vertical = 4.dp)

    // A sewn label: the same box, its edge a running stitch of 4 dp and 3 dp — the panel's rules
    // and the cartouche's border are the same thread.
    CartoucheStyle.STITCHED -> this
        .drawBehind {
            val weight = 1.dp.toPx()
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
            drawRect(
                color = OverMap.Rule,
                topLeft = Offset(weight / 2f, weight / 2f),
                size = Size(size.width - weight, size.height - weight),
                style = Stroke(width = weight, pathEffect = dash)
            )
        }
        .padding(horizontal = 7.dp, vertical = 4.dp)

    // Boxed twice, a hair apart: the frame of an inscription, where the inner rule is the one the
    // letters are measured from and the outer one is the edge of the stone.
    CartoucheStyle.DOUBLE_RULE -> this
        .border(1.dp, OverMap.Rule)
        .padding(2.dp)
        .border(1.dp, OverMap.Rule)
        .padding(horizontal = 6.dp, vertical = 3.dp)
}

/**
 * Vertigo's spiral, at the size of a capital letter, beside the world's name.
 *
 * An Archimedean spiral — radius growing linearly with angle — drawn as one stroked path over two
 * turns. Saul Bass's is a Lissajous figure drawn on a pendulum harmonograph, which is a lovely
 * thing and quite illegible at 14 dp; this is the shape everyone remembers it as.
 */
@Composable
private fun Spiral() {
    Canvas(Modifier.size(SPIRAL_SIZE)) {
        val outerRadius = size.minDimension / 2f - SPIRAL_MARGIN.toPx()
        val segments = SPIRAL_TURNS * SEGMENTS_PER_TURN
        val path = Path()
        for (segment in 0..segments) {
            val alongSpiral = segment / segments.toFloat()
            val angle = alongSpiral * SPIRAL_TURNS * 2f * PI.toFloat()
            val radius = alongSpiral * outerRadius
            val x = size.width / 2f + radius * cos(angle)
            val y = size.height / 2f + radius * sin(angle)
            if (segment == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, OverMap.Parchment, style = Stroke(width = 1.dp.toPx()))
    }
}

/** A capital's worth of spiral, so it sets beside the world's name rather than beneath it. */
private val SPIRAL_SIZE = 15.dp

/** Half a stroke of air outside the outermost turn, so it does not touch the box's edge. */
private val SPIRAL_MARGIN = 0.5.dp

/** Two turns read as a spiral at this size; one reads as a comma and three as a smudge. */
private const val SPIRAL_TURNS = 2

/** Ten degrees a segment, which is below the point where the curve shows its corners at 15 dp. */
private const val SEGMENTS_PER_TURN = 36

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
 * Held here rather than inside the map canvas, because the zoom readout and its three buttons are
 * in the legend, which is a sibling of the canvas rather than a child of it. Plain Compose state
 * in a plain class, so the arithmetic — which is the only part that can be wrong — is testable
 * without a composition, and so that a trip to the atlas and back does not reset the view.
 */
internal class MapCamera {
    var zoom by mutableStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)

    /**
     * Screen pixels one cell of the world covers when the whole sheet is fitted into the pane.
     *
     * Written by the map pane as it measures, because the pane is the only thing that knows how big
     * it is; one for a sheet no larger than the pane, and a fraction for the usual case of a 2048
     * world in a window. Everything that has to say how far a distance on the screen reaches —
     * [pixelsPerCell], the legend's scale bar, the generalisation the overlay is drawn at — comes
     * off this and the zoom.
     */
    var fitScale by mutableStateOf(1f)

    /** Screen pixels one cell covers right now. See [fitScale]. */
    val pixelsPerCell: Float get() = fitScale * zoom

    val zoomPercent: Int get() = (zoom * 100).roundToInt()

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
        const val ZOOM_STEP = 1.15f
    }
}
