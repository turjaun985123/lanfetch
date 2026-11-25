package com.lanfetch.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.absoluteValue

private val LanFetchColors = darkColorScheme(
    primary = Color(0xFF2DD4BF),
    onPrimary = Color(0xFF04201C),
    primaryContainer = Color(0xFF114B46),
    onPrimaryContainer = Color(0xFF99F6E4),
    secondary = Color(0xFF60A5FA),
    onSecondary = Color(0xFF072142),
    background = Color(0xFF090E1A),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF101829),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF172238),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF283652),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
)

private val LanFetchTypography = Typography(
    titleLarge = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
    ),
)

private val LanFetchShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(14.dp),
)

/** Standard grid tokens shared across all screens for strict alignment. */
object Grid {
    val PageHorizontal = 16.dp
    val SectionGap = 16.dp
    val ItemGap = 12.dp
    val ChipGap = 8.dp
    val CardPadding = 16.dp
    val CompactCardPadding = 12.dp
    val RadiusCard = 14.dp
    val RadiusControl = 12.dp
    val RadiusBadge = 6.dp
}

@Composable
fun LanFetchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LanFetchColors,
        typography = LanFetchTypography,
        shapes = LanFetchShapes,
        content = content,
    )
}

/** Deterministic accent color for a title's placeholder artwork. */
fun titleColor(title: String): Color {
    val palette = listOf(
        Color(0xFF0F5E57), Color(0xFF1E3A8A), Color(0xFF4C1D95), Color(0xFF78350F),
        Color(0xFF831843), Color(0xFF14532D), Color(0xFF164E63), Color(0xFF701A75),
    )
    return palette[title.hashCode().absoluteValue % palette.size]
}
