package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
data class RotaSemanticColors(
    val green: Color,
    val greenContainer: Color,
    val onGreenContainer: Color,
    val yellow: Color,
    val yellowContainer: Color,
    val onYellowContainer: Color,
    val red: Color,
    val redContainer: Color,
    val onRedContainer: Color,
    val routeCyan: Color
)

private val DarkSemanticColors = RotaSemanticColors(
    green = TrafficGreenBright,
    greenContainer = TrafficGreenContainer,
    onGreenContainer = Color(0xFFA7F3D0),
    yellow = TrafficYellowBright,
    yellowContainer = TrafficYellowContainer,
    onYellowContainer = Color(0xFFFDE68A),
    red = TrafficRedBright,
    redContainer = TrafficRedContainer,
    onRedContainer = Color(0xFFFECACA),
    routeCyan = RouteCyan
)

private val LightSemanticColors = RotaSemanticColors(
    green = Color(0xFF059669),
    greenContainer = Color(0xFFD1FAE5),
    onGreenContainer = Color(0xFF064E3B),
    yellow = Color(0xFFD97706),
    yellowContainer = Color(0xFFFEF3C7),
    onYellowContainer = Color(0xFF78350F),
    red = Color(0xFFDC2626),
    redContainer = Color(0xFFFEE2E2),
    onRedContainer = Color(0xFF7F1D1D),
    routeCyan = RouteCyanDark
)

val LocalRotaColors = staticCompositionLocalOf { DarkSemanticColors }

private val DarkColorScheme = darkColorScheme(
    primary = TrafficGreenBright,
    onPrimary = Color(0xFF022C22),
    primaryContainer = TrafficGreenContainer,
    onPrimaryContainer = Color(0xFFA7F3D0),
    secondary = RouteCyan,
    onSecondary = Color(0xFF082F49),
    secondaryContainer = Color(0xFF0C4A6E),
    onSecondaryContainer = Color(0xFFBAE6FD),
    tertiary = TrafficYellowBright,
    onTertiary = Color(0xFF451A03),
    background = ObsidianBg,
    onBackground = TextPrimaryDark,
    surface = SlateSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = SlateSurfaceElevated,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerLow = SlateSurface,
    surfaceContainer = SlateSurfaceElevated,
    surfaceContainerHigh = SlateSurfaceHighest,
    outline = SlateOutline,
    outlineVariant = SlateOutlineSoft,
    error = TrafficRedBright,
    onError = Color(0xFF450A0A),
    errorContainer = TrafficRedContainer,
    onErrorContainer = Color(0xFFFECACA)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF059669),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1FAE5),
    onPrimaryContainer = Color(0xFF064E3B),
    secondary = RouteCyanDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF0C4A6E),
    tertiary = Color(0xFFD97706),
    onTertiary = Color.White,
    background = CloudBg,
    onBackground = InkPrimary,
    surface = PaperSurface,
    onSurface = InkPrimary,
    surfaceVariant = PaperSurfaceVariant,
    onSurfaceVariant = InkSecondary,
    surfaceContainerLow = PaperSurface,
    surfaceContainer = Color(0xFFF8FAFC),
    surfaceContainerHigh = PaperSurfaceVariant,
    outline = LightOutline,
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
)

val RotaShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun RotaInteligenteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val semanticColors = if (darkTheme) DarkSemanticColors else LightSemanticColors

    CompositionLocalProvider(LocalRotaColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = RotaTypography,
            shapes = RotaShapes,
            content = content
        )
    }
}
