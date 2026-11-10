package io.github.lrosa2.singletrail.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Topo-map palette: forest green for guidance, magenta for the route line, amber for warnings and
// stars. Every container and surface tone is set explicitly, so no Material default purple shows.
// The ride screen will use a high-contrast light scheme for sunlit LCDs (spec D22); dark is for
// browsing the library at night.
val Forest = Color(0xFF2C6A4A)
val ForestLight = Color(0xFF7DD3A2)
val RouteMagenta = Color(0xFFB02F6B)
val RouteMagentaLight = Color(0xFFFFB0CB)
val Amber = Color(0xFFA86400)
val AmberLight = Color(0xFFF5B95C)

private val LightColors = lightColorScheme(
    primary = Forest,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB4F1CA),
    onPrimaryContainer = Color(0xFF002113),
    secondary = RouteMagenta,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9E4),
    onSecondaryContainer = Color(0xFF3E0021),
    tertiary = Amber,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB5),
    onTertiaryContainer = Color(0xFF2A1800),
    background = Color(0xFFF6FAF4),
    onBackground = Color(0xFF17211B),
    surface = Color(0xFFF6FAF4),
    onSurface = Color(0xFF17211B),
    surfaceVariant = Color(0xFFDCE5DC),
    onSurfaceVariant = Color(0xFF404943),
    surfaceTint = Forest,
    outline = Color(0xFF707971),
    outlineVariant = Color(0xFFC0C9C0),
    inverseSurface = Color(0xFF2C322D),
    inverseOnSurface = Color(0xFFEDF2EB),
    inversePrimary = Color(0xFF99D5AF),
    surfaceBright = Color(0xFFF6FAF4),
    surfaceDim = Color(0xFFD6DBD5),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F5EE),
    surfaceContainer = Color(0xFFEAEFE8),
    surfaceContainerHigh = Color(0xFFE5EAE3),
    surfaceContainerHighest = Color(0xFFDFE4DD),
)

private val DarkColors = darkColorScheme(
    primary = ForestLight,
    onPrimary = Color(0xFF00391F),
    primaryContainer = Color(0xFF0F5133),
    onPrimaryContainer = Color(0xFFB4F1CA),
    secondary = RouteMagentaLight,
    onSecondary = Color(0xFF5F1138),
    secondaryContainer = Color(0xFF7E1E50),
    onSecondaryContainer = Color(0xFFFFD9E4),
    tertiary = AmberLight,
    onTertiary = Color(0xFF462A00),
    tertiaryContainer = Color(0xFF5F3B00),
    onTertiaryContainer = Color(0xFFFFDDB5),
    background = Color(0xFF0F1411),
    onBackground = Color(0xFFDFE4DD),
    surface = Color(0xFF0F1411),
    onSurface = Color(0xFFDFE4DD),
    surfaceVariant = Color(0xFF404943),
    onSurfaceVariant = Color(0xFFC0C9C0),
    surfaceTint = ForestLight,
    outline = Color(0xFF8A938B),
    outlineVariant = Color(0xFF404943),
    inverseSurface = Color(0xFFDFE4DD),
    inverseOnSurface = Color(0xFF2C322D),
    inversePrimary = Forest,
    surfaceBright = Color(0xFF353A36),
    surfaceDim = Color(0xFF0F1411),
    surfaceContainerLowest = Color(0xFF0A0F0C),
    surfaceContainerLow = Color(0xFF171D19),
    surfaceContainer = Color(0xFF1B211D),
    surfaceContainerHigh = Color(0xFF252B27),
    surfaceContainerHighest = Color(0xFF303632),
)

@Composable
fun SingletrailTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
