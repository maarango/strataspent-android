package com.strataspent.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Indigo600,
    onPrimary = Color.White,
    primaryContainer = Indigo100,
    onPrimaryContainer = Indigo900,
    secondary = Indigo500,
    onSecondary = Color.White,
    tertiary = Emerald500,
    background = Slate100,
    onBackground = Slate900,
    surface = Color.White,
    onSurface = Slate900,
    surfaceVariant = Slate200,
    onSurfaceVariant = Slate600,
    error = Rose500,
)

private val DarkColors = darkColorScheme(
    primary = Indigo400,
    onPrimary = Slate900,
    primaryContainer = Indigo800,
    onPrimaryContainer = Indigo100,
    secondary = Indigo300,
    onSecondary = Slate900,
    tertiary = Emerald500,
    background = Slate950,
    onBackground = Slate100,
    surface = Slate900,
    onSurface = Slate100,
    surfaceVariant = Slate800,
    onSurfaceVariant = Slate400,
    error = Rose500,
)

@Composable
fun StrataSpentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // Window.statusBarColor / navigationBarColor were deprecated in
            // API 35; we rely on enableEdgeToEdge() in MainActivity for the
            // system-bar background. Here we just sync the *icon* appearance
            // (dark icons on light theme, light icons on dark theme).
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = StrataTypography,
        content = content,
    )
}
