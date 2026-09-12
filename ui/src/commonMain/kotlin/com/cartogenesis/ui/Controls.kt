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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
                    .border(1.dp, scheme.onSurface.copy(alpha = 0.45f), CircleShape)
            )
        },
        track = { state ->
            Box(Modifier.fillMaxWidth().height(2.dp), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.fillMaxSize().background(rail.copy(alpha = 0.5f)))
                Box(
                    Modifier.fillMaxWidth(state.coercedValueAsFraction)
                        .fillMaxHeight()
                        .background(accent)
                )
            }
        }
    )
}

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
            contentColor = scheme.primary,
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
 * The panel has six headings and every one of them used to draw a [HorizontalDivider] at the call
 * site. Two of F6's chromes want something else there — Hallowed's hairline doubled in gold leaf,
 * Baroque's double hairline with a lozenge centred on each end, which is the rule the author's site
 * draws in CSS — and the point of putting the choice in [ChromeDetail] is that the six call sites
 * do not change and cannot disagree. A chrome that says nothing gets the hairline it always had.
 */
@Composable
internal fun SectionRule(modifier: Modifier = Modifier) {
    val detail = LocalChromeDetail.current
    if (detail.sectionRule == SectionRuleStyle.PLAIN) {
        HorizontalDivider(modifier)
        return
    }
    val ink = detail.rule(MaterialTheme.colorScheme)
    val diamonds = detail.sectionRule == SectionRuleStyle.DOUBLED_WITH_DIAMONDS
    Canvas(modifier.fillMaxWidth().height(7.dp)) {
        val weight = 1.dp.toPx()
        val gap = 3.dp.toPx()
        val top = (size.height - (gap + weight)) / 2f
        // A lozenge is as tall as the pair of rules is deep, so the ornament reads as one object
        // rather than as two lines with something stuck on the end.
        val half = if (diamonds) (gap + weight) else 0f
        drawRect(ink, Offset(half, top), androidx.compose.ui.geometry.Size(size.width - half * 2, weight))
        drawRect(
            ink,
            Offset(half, top + gap),
            androidx.compose.ui.geometry.Size(size.width - half * 2, weight)
        )
        if (!diamonds) return@Canvas
        val middle = top + gap / 2f + weight / 2f
        listOf(half, size.width - half).forEach { x ->
            drawPath(
                Path().apply {
                    moveTo(x, middle - half)
                    lineTo(x + half, middle)
                    lineTo(x, middle + half)
                    lineTo(x - half, middle)
                    close()
                },
                ink
            )
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
