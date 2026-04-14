package io.github.jtsang4.aterm.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color

private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF0057D9),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF4F5B8A),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF171C26),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171C26),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)
private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFAFC6FF),
    onPrimary = Color(0xFF002E6A),
    secondary = Color(0xFFC1C8F2),
    onSecondary = Color(0xFF273255),
    background = Color(0xFF0F131B),
    onBackground = Color(0xFFE3E8F3),
    surface = Color(0xFF171C26),
    onSurface = Color(0xFFE3E8F3),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

val LocalAtermDarkTheme = staticCompositionLocalOf { false }

@Composable
fun AtermTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalAtermDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            content = content,
        )
    }
}
