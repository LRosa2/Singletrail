package io.github.lrosa2.singletrail.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Topo-map palette: forest green for guidance, magenta for the route line, amber for warnings.
// The ride screen will use a high-contrast light scheme for sunlit LCDs (spec D22); dark is for
// browsing the library at night.
val Forest = Color(0xFF2C6A4A)
val ForestLight = Color(0xFF66BF8F)
val RouteMagenta = Color(0xFFB02F6B)
val RouteMagentaLight = Color(0xFFE86CA4)
val Amber = Color(0xFFA86400)
val AmberLight = Color(0xFFEAA64A)

private val LightColors = lightColorScheme(
    primary = Forest,
    onPrimary = Color.White,
    secondary = RouteMagenta,
    onSecondary = Color.White,
    tertiary = Amber,
    background = Color(0xFFF1F4EE),
    surface = Color.White,
    onBackground = Color(0xFF17211B),
    onSurface = Color(0xFF17211B),
)

private val DarkColors = darkColorScheme(
    primary = ForestLight,
    onPrimary = Color(0xFF0F1411),
    secondary = RouteMagentaLight,
    onSecondary = Color(0xFF0F1411),
    tertiary = AmberLight,
    background = Color(0xFF0F1411),
    surface = Color(0xFF161D19),
    onBackground = Color(0xFFE3EAE5),
    onSurface = Color(0xFFE3EAE5),
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
