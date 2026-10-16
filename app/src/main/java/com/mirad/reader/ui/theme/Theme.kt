package com.mirad.reader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val LightScheme = lightColorScheme(
    primary = Ink.Green40,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = Ink.Green90,
    onPrimaryContainer = Ink.Green10,
    inversePrimary = Ink.Green80,
    secondary = Ink.Sage40,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = Ink.Sage90,
    onSecondaryContainer = Ink.Sage30,
    tertiary = Ink.Bronze40,
    onTertiary = androidx.compose.ui.graphics.Color.White,
    tertiaryContainer = androidx.compose.ui.graphics.Color(0xFFF3E3C8),
    onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFF2C2010),
    background = Ink.PaperDim,
    onBackground = Ink.InkText,
    surface = Ink.Paper,
    onSurface = Ink.InkText,
    surfaceVariant = Ink.PaperContainer,
    onSurfaceVariant = Ink.InkTextMuted,
    surfaceTint = Ink.Green40,
    surfaceBright = Ink.PaperLowest,
    surfaceDim = Ink.PaperContainerHigh,
    surfaceContainerLowest = Ink.PaperLowest,
    surfaceContainerLow = Ink.Paper,
    surfaceContainer = Ink.PaperDim,
    surfaceContainerHigh = Ink.PaperContainer,
    surfaceContainerHighest = Ink.PaperContainerHigh,
    outline = Ink.Outline,
    outlineVariant = Ink.OutlineVariant,
    inverseSurface = Ink.Sage30,
    inverseOnSurface = Ink.Sage90,
    scrim = androidx.compose.ui.graphics.Color(0xFF000000),
    error = Ink.Error,
    onError = androidx.compose.ui.graphics.Color.White,
    errorContainer = androidx.compose.ui.graphics.Color(0xFFF9DEDC),
    onErrorContainer = androidx.compose.ui.graphics.Color(0xFF410E0B)
)

private val DarkScheme = darkColorScheme(
    primary = Ink.Green80,
    onPrimary = Ink.Green10,
    primaryContainer = Ink.Green20,
    onPrimaryContainer = Ink.Green90,
    inversePrimary = Ink.Green40,
    secondary = Ink.Sage80,
    onSecondary = Ink.Sage30,
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF37443D),
    onSecondaryContainer = Ink.Sage90,
    tertiary = Ink.Bronze80,
    onTertiary = androidx.compose.ui.graphics.Color(0xFF3A2A12),
    tertiaryContainer = androidx.compose.ui.graphics.Color(0xFF584014),
    onTertiaryContainer = androidx.compose.ui.graphics.Color(0xFFF6E0B8),
    background = Ink.NightDim,
    onBackground = Ink.NightText,
    surface = Ink.Night,
    onSurface = Ink.NightText,
    surfaceVariant = Ink.NightContainer,
    onSurfaceVariant = Ink.NightTextMuted,
    surfaceTint = Ink.Green80,
    surfaceBright = Ink.NightContainerHigh,
    surfaceDim = Ink.NightLowest,
    surfaceContainerLowest = Ink.NightLowest,
    surfaceContainerLow = Ink.Night,
    surfaceContainer = Ink.NightContainer,
    surfaceContainerHigh = Ink.NightContainerHigh,
    surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF30383A),
    outline = Ink.NightOutline,
    outlineVariant = Ink.NightOutlineVariant,
    inverseSurface = Ink.NightText,
    inverseOnSurface = Ink.Night,
    scrim = androidx.compose.ui.graphics.Color(0xFF000000),
    error = Ink.ErrorDark,
    onError = androidx.compose.ui.graphics.Color(0xFF601410),
    errorContainer = androidx.compose.ui.graphics.Color(0xFF8C1D18),
    onErrorContainer = androidx.compose.ui.graphics.Color(0xFFF9DEDC)
)

/** 统一圆角：卡片 16dp、按钮/胶囊用 full、弹层 24dp。 */
private val MiradShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun MiradTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = MiradTypography,
        shapes = MiradShapes,
        content = content
    )
}
