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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    val accent = if (enabled) scheme.primary else scheme.outline
    val rail = if (enabled) scheme.outline else scheme.outlineVariant
    MaterialSlider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().height(26.dp),
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        thumb = {
            Box(
                Modifier.size(13.dp)
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
    MaterialSwitch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = scheme.primary,
            checkedTrackColor = scheme.secondaryContainer,
            checkedBorderColor = scheme.primary,
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
        border = BorderStroke(1.dp, if (enabled) scheme.primary else scheme.outlineVariant),
        contentPadding = contentPadding,
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
        border = BorderStroke(1.dp, if (enabled) scheme.outline else scheme.outlineVariant),
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
    MaterialFilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
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
            1.dp,
            when {
                !enabled -> scheme.outlineVariant
                selected -> scheme.primary
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
        border = BorderStroke(1.dp, scheme.outlineVariant)
    )
}

/** A ruled line, at the weight a pen would draw it. */
@Composable
internal fun HorizontalDivider(modifier: Modifier = Modifier) {
    MaterialHorizontalDivider(
        modifier = modifier,
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
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
        border = BorderStroke(1.dp, scheme.outlineVariant),
        content = content
    )
}
