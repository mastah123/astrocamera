package com.astrocam.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class NightTheme { Dark, Red }

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DB4FF),
    onPrimary = Color(0xFF0B0D10),
    background = Color(0xFF0B0D10),
    onBackground = Color(0xFFE3E6EB),
    surface = Color(0xFF14171C),
    onSurface = Color(0xFFE3E6EB),
    surfaceVariant = Color(0xFF1C2027),
    onSurfaceVariant = Color(0xFFA9AFBA),
    outline = Color(0xFF3A4048),
)

// Red-only palette to preserve dark adaptation outdoors.
private val RedColors = darkColorScheme(
    primary = Color(0xFFFF3B30),
    onPrimary = Color(0xFF000000),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFF4A40),
    surface = Color(0xFF120404),
    onSurface = Color(0xFFFF4A40),
    surfaceVariant = Color(0xFF1C0606),
    onSurfaceVariant = Color(0xFFB83228),
    outline = Color(0xFF4A1410),
)

@Composable
fun AstroTheme(night: NightTheme, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (night == NightTheme.Red) RedColors else DarkColors,
        content = content,
    )
}
