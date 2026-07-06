package org.bibletranslationtools.docscanner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF4B8EFF),
    secondary = Color(0xFFFFB655),
    tertiary = Color(0xFF7EE588),
    background = Color(0xFF141516),
    surface = Color(0xFF3B3B3B),
    error = Color(0xFFFF6B62),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFFC9C9C9),
    onSurface = Color(0xFFC9C9C9)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0056D1),
    secondary = Color(0xFFE99A2E),
    tertiary = Color(0xFF63C76C),
    background = Color(0xFFF2F2F2),
    surface = Color(0xFFFFFFFF),
    error = Color(0xFFC3362D),
    onPrimary = Color(0xFFF3F3F3),
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF444444),
    onSurface = Color(0xFF444444)
)

@Composable
fun DocScannerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    colorScheme: ColorScheme? = null,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = when {
        colorScheme != null -> colorScheme
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}