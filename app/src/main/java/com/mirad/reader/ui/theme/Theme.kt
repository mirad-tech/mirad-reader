package com.mirad.reader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F6F5C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3EFE5),
    onPrimaryContainer = Color(0xFF06251C),
    secondary = Color(0xFF4A635B),
    surface = Color(0xFFFBFDFA),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDDE5E0),
    onSurfaceVariant = Color(0xFF414945),
    background = Color(0xFFFBFDFA),
    onBackground = Color(0xFF191C1B)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8ED3BC),
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF005141),
    onPrimaryContainer = Color(0xFFAAEFD8),
    secondary = Color(0xFFB1CCC2),
    surface = Color(0xFF111413),
    onSurface = Color(0xFFE1E3E1),
    surfaceVariant = Color(0xFF414945),
    onSurfaceVariant = Color(0xFFC1C9C4),
    background = Color(0xFF111413),
    onBackground = Color(0xFFE1E3E1)
)

@Composable
fun MiradTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
