package io.github.lrosa2.singletrail.ui.ride

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The ride screen's own theme (spec §4.5, D22): light and high-contrast whatever the system theme,
 * because the G15's LCD washes out in sunlight and a dark screen is hard to read there.
 */
private val RideColors = lightColorScheme(
    primary = Color(0xFF0B5D2E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8EED6),
    onPrimaryContainer = Color(0xFF00210E),
    secondary = Color(0xFF9C1F5A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9E6),
    onSecondaryContainer = Color(0xFF3E0021),
    tertiary = Color(0xFFB34700),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCBE),
    onTertiaryContainer = Color(0xFF331200),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFE9ECE9),
    onSurfaceVariant = Color(0xFF262A27),
    surfaceTint = Color(0xFF0B5D2E),
    outline = Color(0xFF6E736F),
    outlineVariant = Color(0xFFC4C9C5),
    inverseSurface = Color(0xFF1C211E),
    inverseOnSurface = Color(0xFFEFF2EF),
    inversePrimary = Color(0xFF8FD8A9),
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFDDE1DD),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F7F5),
    surfaceContainer = Color(0xFFEFF2EF),
    surfaceContainerHigh = Color(0xFFE9ECE9),
    surfaceContainerHighest = Color(0xFFE3E6E3),
)

@Composable
fun RideTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RideColors, content = content)
}
