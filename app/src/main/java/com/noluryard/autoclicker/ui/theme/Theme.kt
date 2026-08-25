package com.noluryard.autoclicker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF4CC2FF)
val AccentDark = Color(0xFF0E7FB5)
val Danger = Color(0xFFFF6B6B)
val Warn = Color(0xFFFFB84C)
val Ok = Color(0xFF54D18C)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF042434),
    secondary = Color(0xFF9AA4B2),
    background = Color(0xFF0E121A),
    surface = Color(0xFF161B24),
    surfaceVariant = Color(0xFF1E242F),
    error = Danger,
)

private val LightColors = lightColorScheme(
    primary = AccentDark,
    secondary = Color(0xFF4A5565),
    background = Color(0xFFF6F8FB),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE7ECF3),
    error = Color(0xFFC62828),
)

@Composable
fun AutoClickerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
