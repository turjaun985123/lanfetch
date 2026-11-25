package com.lanfetch.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lanfetch.app.R
import kotlin.math.absoluteValue

/**
 * Centralized Obsidian Studio design system tokens for TURJAUN.
 * All screens consume these tokens rather than local magic numbers.
 */
object TurjaunPalette {
    val Canvas = Color(0xFF07090F)
    val Surface = Color(0xFF0E131D)
    val SurfaceElevated = Color(0xFF151C2B)
    val SurfaceInteractive = Color(0xFF1C2538)
    val Rule = Color(0xFF222C40)
    val RuleSubtle = Color(0xFF172033)

    val InkPrimary = Color(0xFFF4F6FA)
    val InkSecondary = Color(0xFF96A3B8)
    val InkMuted = Color(0xFF637089)

    val Accent = Color(0xFF36C9B4)
    val AccentDim = Color(0xFF289C8B)
    val AccentContainer = Color(0xFF103836)
    val OnAccent = Color(0xFF041C18)
    val OnAccentContainer = Color(0xFFA7F3E7)

    val Amber = Color(0xFFE5A93C)
    val AmberContainer = Color(0xFF34260D)
    val Error = Color(0xFFE56B6F)
    val ErrorContainer = Color(0xFF3A181B)
    val OnError = Color(0xFF2A090B)
    val Scrim = Color(0xB804060A)
}

object TurjaunSpacing {
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Base = 16.dp
    val Lg = 20.dp
    val Xl = 24.dp
    val ScreenHorizontal = 16.dp
    val BottomListBreath = 28.dp
}

object TurjaunRadii {
    val Badge = 4.dp
    val Control = 8.dp
    val Poster = 8.dp
    val Surface = 10.dp
    val Sheet = 16.dp
    val Pill = 999.dp
}

object TurjaunSizes {
    val MinTouchTarget = 48.dp
    val IconSm = 16.dp
    val IconMd = 20.dp
    val IconLg = 24.dp
    val Divider = 1.dp
    val Hairline = 0.5.dp
    const val PosterAspectRatio = 0.70f
}

object TurjaunMotion {
    const val FastMs = 160
    const val StandardMs = 260
    const val EntranceMs = 420
    const val SweepDurationMs = 1750
    const val WordmarkLetterMs = 560
    const val WordmarkStaggerMs = 42
    const val WordmarkRevealMs = 812
    const val OpeningMinimumMs = 900
    val EaseStandard: Easing = FastOutSlowInEasing
    val EaseDecel: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val EaseWordmark: Easing = CubicBezierEasing(0.16f, 1.0f, 0.3f, 1.0f)
}

/** Bundled Inter family so text looks consistent across Android system fonts. */
val LanFetchFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private val ColorScheme = darkColorScheme(
    primary = TurjaunPalette.Accent,
    onPrimary = TurjaunPalette.OnAccent,
    primaryContainer = TurjaunPalette.AccentContainer,
    onPrimaryContainer = TurjaunPalette.OnAccentContainer,
    secondary = TurjaunPalette.Amber,
    onSecondary = TurjaunPalette.Canvas,
    background = TurjaunPalette.Canvas,
    onBackground = TurjaunPalette.InkPrimary,
    surface = TurjaunPalette.Surface,
    onSurface = TurjaunPalette.InkPrimary,
    surfaceVariant = TurjaunPalette.SurfaceElevated,
    onSurfaceVariant = TurjaunPalette.InkSecondary,
    outline = TurjaunPalette.Rule,
    outlineVariant = TurjaunPalette.RuleSubtle,
    error = TurjaunPalette.Error,
    onError = TurjaunPalette.OnError,
    errorContainer = TurjaunPalette.ErrorContainer,
    scrim = TurjaunPalette.Scrim,
)

private val MaterialTypography = Typography()

private val AppTypography = Typography(
    displayLarge = MaterialTypography.displayLarge.copy(fontFamily = LanFetchFontFamily),
    displayMedium = MaterialTypography.displayMedium.copy(fontFamily = LanFetchFontFamily),
    displaySmall = TextStyle(
        fontFamily = LanFetchFontFamily,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 6.sp,
    ),
    headlineLarge = MaterialTypography.headlineLarge.copy(fontFamily = LanFetchFontFamily),
    headlineMedium = MaterialTypography.headlineMedium.copy(fontFamily = LanFetchFontFamily),
    headlineSmall = MaterialTypography.headlineSmall.copy(fontFamily = LanFetchFontFamily),
    titleLarge = TextStyle(
        fontFamily = LanFetchFontFamily,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = LanFetchFontFamily,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = TextStyle(
        fontFamily = LanFetchFontFamily,
        fontSize = 13.5.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
    ),
    bodyLarge = TextStyle(
        fontFamily = LanFetchFontFamily,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = LanFetchFontFamily,
        fontSize = 13.5.sp,
        lineHeight = 19.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = LanFetchFontFamily,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = LanFetchFontFamily,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelMedium = TextStyle(
        fontFamily = LanFetchFontFamily,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelSmall = TextStyle(
        fontFamily = LanFetchFontFamily,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp,
    ),
)

val TabularBodySmall: TextStyle = TextStyle(
    fontFamily = LanFetchFontFamily,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    fontFeatureSettings = "tnum",
)

val TabularLabelSmall: TextStyle = TextStyle(
    fontFamily = LanFetchFontFamily,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    fontWeight = FontWeight.Medium,
    fontFeatureSettings = "tnum",
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(TurjaunRadii.Badge),
    small = RoundedCornerShape(TurjaunRadii.Control),
    medium = RoundedCornerShape(TurjaunRadii.Surface),
    large = RoundedCornerShape(TurjaunRadii.Sheet),
)

@Composable
fun TurjaunTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ColorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

/** Subdued studio tones for poster-less fallback plates so grids look editorial rather than loud. */
fun fallbackPosterTone(title: String): Color {
    val tones = listOf(
        Color(0xFF102027), Color(0xFF141B2D), Color(0xFF1A182B),
        Color(0xFF1C2321), Color(0xFF211924), Color(0xFF16222C),
    )
    return tones[title.hashCode().absoluteValue % tones.size]
}
