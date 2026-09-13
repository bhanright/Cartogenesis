package com.cartogenesis.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AssistChip as MaterialAssistChip
import androidx.compose.material3.Button as MaterialButton
import androidx.compose.material3.Card as MaterialCard
import androidx.compose.material3.FilterChip as MaterialFilterChip
import androidx.compose.material3.HorizontalDivider as MaterialHorizontalDivider
import androidx.compose.material3.OutlinedButton as MaterialOutlinedButton
import androidx.compose.material3.Slider as MaterialSlider
import androidx.compose.material3.Switch as MaterialSwitch

/**
 * The controls, dressed once.
 *
 * Material's own components are the right machinery — the gestures, the keyboard handling and the
 * accessibility are all there and none of it should be rewritten — but their default clothes are
 * unmistakable: a 20dp pill thumb on a 16dp track, filled buttons in tonal lilac, chips that look
 * like a phone's. What is wrong with them is only ever the dressing, so this file is the dressing,
 * in one place.
 *
 * Each function here shadows the Material component of the same name for everything in this
 * package: a call site writes `Slider(value = …, onValueChange = …)` exactly as before and gets the
 * atlas's slider, because the `androidx.compose.material3` import is simply not there any more.
 * That is the whole mechanism, and it is why no control in the application is styled where it is
 * used — there is nowhere else a control *could* be styled.
 *
 * Colours are never named here. Everything comes from [CartogenesisTheme]'s scheme, so the same
 * definitions carry both the daylight paper and the dark brass without a second set of rules.
 */

/**
 * A hairline rail with a small ink-ringed thumb, in place of Material's lozenge.
 *
 * Drawn from the slider's own state rather than from `SliderDefaults.Track`, which insists on a
 * thick rounded rail with a gap cut around the thumb.
 */
// The slot form of Slider, and the fraction its state reports, are still marked experimental;
// they are the only way to replace the rail and the thumb rather than merely recolour them.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Slider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0
) {
    val scheme = MaterialTheme.colorScheme
    // The accent as a *mark* rather than as a word. Identical to `primary` in every chrome but
    // High contrast, which keeps the saturated blue here and a lifted one for text — see
    // [ChromeDetail.markAccent].
    val accent = if (enabled) LocalChromeDetail.current.mark(scheme) else scheme.outline
    val rail = if (enabled) scheme.outline else scheme.outlineVariant
    // The two numbers that used to be literals here. Under a mouse they are still 26 and 13; under
    // a fingertip they are 44 and 20, and no call site knows the difference. See [TouchTargets].
    val targets = LocalTouchTargets.current
    MaterialSlider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().height(targets.sliderHeight),
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        thumb = {
            Box(
                Modifier.size(targets.sliderThumb)
                    .background(accent, CircleShape)
                    .border(1.dp, scheme.onSurface.copy(alpha = THUMB_RING_ALPHA), CircleShape)
            )
        },
        track = { state ->
            Box(
                Modifier.fillMaxWidth().height(RAIL_HEIGHT),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(Modifier.fillMaxSize().background(rail.copy(alpha = UNFILLED_RAIL_ALPHA)))
                Box(
                    Modifier.fillMaxWidth(state.coercedValueAsFraction)
                        .fillMaxHeight()
                        .background(accent)
                )
            }
        }
    )
}

/** The rail a slider's thumb runs along: a hairline, not Material's rounded bar. */
private val RAIL_HEIGHT = 2.dp

/**
 * The ink ring round the thumb, as a share of the scheme's own text colour.
 *
 * Enough to read the bead against the paper it sits on, not so much that a row of sliders is a row
 * of full-strength dots: the thumb is the accent and the ring is only its edge.
 */
private const val THUMB_RING_ALPHA = 0.45f

/** The part of the rail the value has not reached, at half the outline's strength. */
private const val UNFILLED_RAIL_ALPHA = 0.5f

/**
 * A ruled slot with an inked bead in it, at either end.
 *
 * Material fills the whole track with the accent when a switch is on, which made the four feature
 * toggles the loudest marks on a page whose subject is a map. Here the track is a wash between
 * hairlines either way, and only the bead is inked, so a row of switches reads at a glance without
 * shouting.
 */
@Composable
internal fun Switch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    val mark = LocalChromeDetail.current.mark(scheme)
    // Boxed rather than stretched: a Material switch draws itself at a fixed size and forcing a
    // taller one distorts the track, so what grows under a fingertip is the *target* around it.
    // With a mouse the minimum is zero, so the box wraps the switch exactly and nothing moves.
    Box(
        modifier.heightIn(min = LocalTouchTargets.current.minTarget),
        contentAlignment = Alignment.Center
    ) {
        MaterialSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = mark,
                checkedTrackColor = scheme.secondaryContainer,
                checkedBorderColor = mark,
                uncheckedThumbColor = scheme.outline,
                uncheckedTrackColor = Color.Transparent,
                uncheckedBorderColor = scheme.outline,
                disabledCheckedTrackColor = scheme.outlineVariant,
                disabledUncheckedThumbColor = scheme.outlineVariant,
                disabledUncheckedTrackColor = Color.Transparent,
                disabledUncheckedBorderColor = scheme.outlineVariant
            )
        )
    }
}

/**
 * The emphatic button: a wash of the accent behind an accent hairline, rather than a solid block.
 *
 * Even the loudest control on the page is still ink on paper. The difference between this and
 * [OutlinedButton] is a stain and a darker rule, which is as much as a printed page ever had.
 */
@Composable
internal fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val detail = LocalChromeDetail.current
    MaterialButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(2.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = scheme.primaryContainer,
            // The accent, unless the chrome has made this button a block rather than a stain —
            // Matrix, Hessian and Hitchcock have, and then the label is the ground. See
            // [ChromeDetail.label].
            contentColor = detail.label(scheme),
            disabledContainerColor = Color.Transparent,
            disabledContentColor = scheme.outline
        ),
        elevation = null,
        border = BorderStroke(
            detail.stroke,
            if (enabled) detail.mark(scheme) else scheme.outlineVariant
        ),
        contentPadding = contentPadding,
        // Armed is otherwise a wash and a darker rule — two hues. Under the Colorblind chrome it
        // is also a rule under the word, which is the only cue that survives every simulation.
        content = { Underlined(detail.shapeCues && enabled) { content() } }
    )
}

/** Underlines everything drawn inside it, by moving the text style rather than the words. */
@Composable
private fun Underlined(on: Boolean, content: @Composable () -> Unit) {
    if (!on) {
        content()
        return
    }
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(
            textDecoration = TextDecoration.Underline
        ),
        content = content
    )
}

/** Strikes everything drawn inside it. The Colorblind chrome's cue for "you cannot have this". */
@Composable
private fun Struck(on: Boolean, content: @Composable () -> Unit) {
    if (!on) {
        content()
        return
    }
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(
            textDecoration = TextDecoration.LineThrough
        ),
        content = content
    )
}

/** The quiet button: a hairline and the text, nothing behind it. */
@Composable
internal fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    MaterialOutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(2.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = scheme.onSurface,
            disabledContentColor = scheme.outline
        ),
        border = BorderStroke(
            LocalChromeDetail.current.stroke,
            if (enabled) scheme.outline else scheme.outlineVariant
        ),
        contentPadding = contentPadding,
        content = content
    )
}

/**
 * A choice, marked the way a chart marks one: the chosen cell is stained and ruled in ink, the
 * others are outlines. Material's version fills the selection with a tonal block and drops the
 * border, which is the single most recognisable thing about an unstyled Compose application.
 */
@Composable
internal fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    val detail = LocalChromeDetail.current
    MaterialFilterChip(
        selected = selected,
        onClick = onClick,
        // The chosen one is ruled twice as heavily and the unavailable one is struck through, so
        // that neither state is told by colour alone. Off in every chrome but Colorblind.
        label = { Struck(detail.shapeCues && !enabled) { label() } },
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(2.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = scheme.onSurfaceVariant,
            selectedContainerColor = scheme.secondaryContainer,
            selectedLabelColor = scheme.onSecondaryContainer,
            disabledContainerColor = Color.Transparent,
            disabledLabelColor = scheme.outline
        ),
        border = BorderStroke(
            if (detail.shapeCues && selected && enabled) detail.stroke * 2 else detail.stroke,
            when {
                !enabled -> scheme.outlineVariant
                selected -> detail.mark(scheme)
                else -> scheme.outlineVariant
            }
        )
    )
}

/** A read-only tag — a biome share, a fact about a realm. Ruled, never filled. */
@Composable
internal fun AssistChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    MaterialAssistChip(
        onClick = onClick,
        label = label,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(2.dp),
        colors = AssistChipDefaults.assistChipColors(
            containerColor = Color.Transparent,
            labelColor = scheme.onSurfaceVariant
        ),
        border = BorderStroke(LocalChromeDetail.current.stroke, scheme.outlineVariant)
    )
}

/** A ruled line, at the weight a pen would draw it — or at the chrome's, where it asks. */
@Composable
internal fun HorizontalDivider(modifier: Modifier = Modifier) {
    MaterialHorizontalDivider(
        modifier = modifier,
        thickness = LocalChromeDetail.current.stroke,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

/**
 * The rule under a section heading, drawn the way this chrome rules a section.
 *
 * The panel has six headings, and a divider written at each of the six call sites could not carry
 * what some chromes want there — Hallowed's hairline doubled in gold leaf, Baroque's double
 * hairline with a lozenge centred on each end, which is the rule the author's site draws in CSS.
 * The point of putting the choice in [ChromeDetail] is that the six call sites do not change and
 * cannot disagree. A chrome that says nothing gets the plain hairline.
 */
@Composable
internal fun SectionRule(modifier: Modifier = Modifier) {
    val detail = LocalChromeDetail.current
    if (detail.sectionRule == SectionRuleStyle.PLAIN) {
        HorizontalDivider(modifier)
        return
    }
    val ink = detail.rule(MaterialTheme.colorScheme)
    val style = detail.sectionRule
    // Every ornamental rule takes the same 7 dp band, so switching chrome cannot make the panel
    // taller or shorter — only the drawing inside the band changes. The meander is the exception
    // and says why.
    val band = if (style == SectionRuleStyle.MEANDER) 12.dp else 7.dp
    Canvas(modifier.fillMaxWidth().height(band)) {
        when (style) {
            SectionRuleStyle.PLAIN -> Unit
            SectionRuleStyle.DOUBLED, SectionRuleStyle.DOUBLED_WITH_DIAMONDS ->
                doubled(ink, style == SectionRuleStyle.DOUBLED_WITH_DIAMONDS)
            SectionRuleStyle.STITCHED -> runningStitch(ink)
            SectionRuleStyle.MEANDER -> meander(ink)
            SectionRuleStyle.CUT_BAR -> cutBar(ink)
        }
    }
}

/** Hallowed's and Baroque's: two hairlines, and for Baroque a lozenge centred on each end. */
private fun DrawScope.doubled(ink: Color, diamonds: Boolean) {
    val weight = 1.dp.toPx()
    val gap = 3.dp.toPx()
    val top = (size.height - (gap + weight)) / 2f
    // A lozenge is as tall as the pair of rules is deep, so the ornament reads as one object
    // rather than as two lines with something stuck on the end — and the rules are inset by the
    // same amount, so they meet its points instead of running through it.
    val lozengeRadius = if (diamonds) (gap + weight) else 0f
    val ruleWidth = size.width - lozengeRadius * 2
    drawRect(ink, Offset(lozengeRadius, top), Size(ruleWidth, weight))
    drawRect(ink, Offset(lozengeRadius, top + gap), Size(ruleWidth, weight))
    if (!diamonds) return
    val middle = top + gap / 2f + weight / 2f
    listOf(lozengeRadius, size.width - lozengeRadius).forEach { centreX ->
        drawPath(
            Path().apply {
                moveTo(centreX, middle - lozengeRadius)
                lineTo(centreX + lozengeRadius, middle)
                lineTo(centreX, middle + lozengeRadius)
                lineTo(centreX - lozengeRadius, middle)
                close()
            },
            ink
        )
    }
}

/**
 * Hessian's: a running stitch, 4 dp of thread and 3 dp of cloth.
 *
 * A dash effect rather than a loop drawing little rectangles, so the phase is the renderer's and
 * the stitch does not resample when the panel is resized.
 */
private fun DrawScope.runningStitch(ink: Color) {
    val y = size.height / 2f
    drawLine(
        color = ink,
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = 1.dp.toPx(),
        cap = StrokeCap.Butt,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
    )
}

/**
 * Roman's: a Greek key, repeating over a continuous base rail.
 *
 * The unit is 12 dp rather than the 6 the spec suggested, and the band 12 rather than 7, because
 * the first draft was drawn at 6 and photographed: at 6 dp a single meander has three arms and two
 * returns inside six pixels, and what comes out is a comb. A meander is a *drawing*, not a line;
 * twelve is the smallest unit at which the key reads as a key, and the figure was chosen by looking
 * rather than by arithmetic, which is what the ground rules ask of anything no guard can measure.
 *
 * The last partial unit at the right margin is drawn and clipped by the canvas rather than dropped,
 * which is what a painted border on a wall does when it meets a corner.
 */
private fun DrawScope.meander(ink: Color) {
    val weight = 1.dp.toPx()
    val unitWidth = 12.dp.toPx()
    val inset = 3.dp.toPx()
    val base = size.height - weight / 2f
    val top = weight / 2f
    drawLine(ink, Offset(0f, base), Offset(size.width, base), weight)
    val key = Path()
    var unitLeft = 0f
    while (unitLeft < size.width) {
        // Up from the rail, across the top, down the far side, back along the middle and up into
        // the centre: one turn of the spiral, which is the whole of the classical single meander.
        key.moveTo(unitLeft + weight / 2f, base)
        key.lineTo(unitLeft + weight / 2f, top)
        key.lineTo(unitLeft + unitWidth - inset, top)
        key.lineTo(unitLeft + unitWidth - inset, base - inset)
        key.lineTo(unitLeft + inset, base - inset)
        key.lineTo(unitLeft + inset, top + inset)
        key.lineTo(unitLeft + unitWidth - 2f * inset, top + inset)
        unitLeft += unitWidth
    }
    drawPath(key, ink, style = Stroke(width = weight))
}

/**
 * Hitchcock's: one bar, cut in three, the pieces slipped past one another.
 *
 * The Psycho titles are a name sliced into bands that never line up, and the whole gesture is that
 * the eye keeps trying to read them as one line. The three displacements are 1, 3 and 2 dp from the
 * top of the band, which is the 1-2 dp the spec asks for between neighbours.
 */
private fun DrawScope.cutBar(ink: Color) {
    val weight = 2.dp.toPx()
    val gap = 3.dp.toPx()
    val shares = listOf(0.42f, 0.33f, 0.25f)
    val topOffsets = listOf(1.dp.toPx(), 3.dp.toPx(), 2.dp.toPx())
    val span = size.width - gap * (shares.size - 1)
    var left = 0f
    shares.forEachIndexed { index, share ->
        val pieceWidth = span * share
        drawRect(ink, Offset(left, topOffsets[index]), Size(pieceWidth, weight))
        left += pieceWidth + gap
    }
}

/**
 * The weave a chrome draws behind whatever this modifier is attached to.
 *
 * Hessian's, and nothing else has ever wanted one — but it goes here rather than at the three
 * places a panel is drawn, for the same reason every other ornament does: a texture that had to be
 * applied by hand wherever a panel happens to be is not a theme. A chrome that asks for no texture
 * gets the modifier back untouched, so there is not even a draw node in the other fourteen.
 */
@Composable
internal fun Modifier.chromeWeave(): Modifier {
    val detail = LocalChromeDetail.current
    if (detail.panelTexture == PanelTexture.NONE) return this
    val ink = detail.textureInk
    return this.drawBehind {
        val step = 6.dp.toPx()
        val weight = 1.dp.toPx()
        // Two families at ±45°, drawn as intercepts stepped along the top edge: a line of slope +1
        // through (intercept, 0) leaves the box at (intercept + height, height), and its mirror at
        // (intercept - height, height). Starting a screen-height to the left of the origin is what
        // fills the corners.
        var intercept = -size.height
        while (intercept <= size.width + size.height) {
            drawLine(
                ink, Offset(intercept, 0f), Offset(intercept + size.height, size.height), weight
            )
            drawLine(
                ink, Offset(intercept, 0f), Offset(intercept - size.height, size.height), weight
            )
            intercept += step
        }
    }
}

/** A card is a bordered patch of the same paper: no shadow, no tint, no lift. */
@Composable
internal fun Card(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    MaterialCard(
        modifier = modifier,
        shape = RoundedCornerShape(3.dp),
        colors = CardDefaults.cardColors(
            containerColor = scheme.surface,
            contentColor = scheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(LocalChromeDetail.current.stroke, scheme.outlineVariant),
        content = content
    )
}
