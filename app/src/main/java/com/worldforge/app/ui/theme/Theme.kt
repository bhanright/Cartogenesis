package com.worldforge.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Parchment = Color(0xFFF2E4C6)
private val Ink = Color(0xFF23282D)
private val DeepSea = Color(0xFF1B3A4B)
private val Moss = Color(0xFF6B8E4E)
private val Sand = Color(0xFFD9C79B)

private val LightColors = lightColorScheme(
    primary = DeepSea,
    onPrimary = Parchment,
    secondary = Moss,
    onSecondary = Color.White,
    tertiary = Sand,
    background = Color(0xFFFBF6EA),
    onBackground = Ink,
    surface = Parchment,
    onSurface = Ink
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FC0D9),
    onPrimary = Color(0xFF0E1B24),
    secondary = Moss,
    onSecondary = Color(0xFF10180C),
    tertiary = Sand,
    background = Color(0xFF14181C),
    onBackground = Color(0xFFE6E1D6),
    surface = Color(0xFF1D2328),
    onSurface = Color(0xFFE6E1D6)
)

@Composable
fun WorldforgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
