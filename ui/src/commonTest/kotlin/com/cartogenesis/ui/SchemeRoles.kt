package com.cartogenesis.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb

/**
 * Every Material role of [scheme] the application reads, as one line of ARGB hex in a fixed order.
 *
 * What two schemes are compared by. A `ColorScheme` does not define `equals`, so two schemes of
 * identical colours are two objects to a set; this line is the same for the same colours and for
 * nothing else. `ChromeContrastTest` records the older chromes in it, and `SettingsTest` tells the
 * chromes apart by it.
 */
internal fun rolesOf(scheme: ColorScheme): String = listOf(
    scheme.primary, scheme.onPrimary, scheme.primaryContainer, scheme.onPrimaryContainer,
    scheme.inversePrimary,
    scheme.secondary, scheme.onSecondary, scheme.secondaryContainer,
    scheme.onSecondaryContainer,
    scheme.tertiary, scheme.onTertiary, scheme.tertiaryContainer,
    scheme.onTertiaryContainer,
    scheme.background, scheme.onBackground, scheme.surface, scheme.onSurface,
    scheme.surfaceVariant, scheme.onSurfaceVariant, scheme.surfaceTint,
    scheme.inverseSurface, scheme.inverseOnSurface,
    scheme.error, scheme.onError, scheme.errorContainer, scheme.onErrorContainer,
    scheme.outline, scheme.outlineVariant, scheme.scrim,
    scheme.surfaceBright, scheme.surfaceDim,
    scheme.surfaceContainerLowest, scheme.surfaceContainerLow, scheme.surfaceContainer,
    scheme.surfaceContainerHigh, scheme.surfaceContainerHighest
).joinToString(",") { it.toArgb().toUInt().toString(16).padStart(8, '0') }
