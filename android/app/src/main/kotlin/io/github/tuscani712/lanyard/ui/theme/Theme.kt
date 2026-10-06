package io.github.tuscani712.lanyard.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Dark palette mirrors internal/uiserver/web/style.css.
private val DarkBackground = Color(0xFF0A0E14)
private val DarkSurface = Color(0xFF10161F)
private val DarkSurfaceVariant = Color(0xFF1A2230)
private val Accent = Color(0xFF2F6FEB)
private val DarkOn = Color(0xFFE8ECF3)
private val DarkMuted = Color(0xFF9AA7B8)
private val DarkOutline = Color(0xFF2A3444)

private val LightBackground = Color(0xFFF5F7FA)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurfaceVariant = Color(0xFFE7ECF3)
private val LightOn = Color(0xFF10161F)
private val LightMuted = Color(0xFF55627A)
private val LightOutline = Color(0xFFCBD4E1)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondaryContainer = Accent,
    onSecondaryContainer = Color.White,
    background = DarkBackground,
    onBackground = DarkOn,
    surface = DarkSurface,
    onSurface = DarkOn,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkMuted,
    outline = DarkOutline,
)

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondaryContainer = Accent,
    onSecondaryContainer = Color.White,
    background = LightBackground,
    onBackground = LightOn,
    surface = LightSurface,
    onSurface = LightOn,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightMuted,
    outline = LightOutline,
)

@Composable
fun LanyardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
