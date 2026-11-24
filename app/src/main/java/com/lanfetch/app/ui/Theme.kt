package com.lanfetch.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LanFetchColors = darkColorScheme(
    primary = Color(0xFF2DD4BF),
    onPrimary = Color(0xFF06231F),
    primaryContainer = Color(0xFF0F766E),
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFF7DD3FC),
    onSecondary = Color(0xFF082F49),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF111C30),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1B2A44),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF334155),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
)

@Composable
fun LanFetchTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LanFetchColors, content = content)
}

/** Deterministic accent color for a title's placeholder artwork. */
fun titleColor(title: String): Color {
    val palette = listOf(
        Color(0xFF0F766E), Color(0xFF1D4ED8), Color(0xFF7C3AED), Color(0xFFB45309),
        Color(0xFFBE185D), Color(0xFF15803D), Color(0xFF0E7490), Color(0xFF9D174D),
    )
    return palette[(title.hashCode().mod(palette.size))]
}
