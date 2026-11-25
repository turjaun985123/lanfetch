package com.lanfetch.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
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
 * Lanes design system. The segmented transfer ring is the signature element;
 * the rest of the interface stays quiet, neutral, and readable.
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

@Immutable
data class LanesColors(
    val canvas: Color,
    val surface: Color,
    val raised: Color,
    val hairline: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val paused: Color,
    val pausedContainer: Color,
    val failed: Color,
    val failedContainer: Color,
    val done: Color,
    val scrim: Color,
) {
    val surfaceInteractive: Color get() = raised
    val rule: Color get() = hairline
    val ruleSubtle: Color get() = hairline
    val error: Color get() = failed
    val errorContainer: Color get() = failedContainer
    val onError: Color get() = Color.White
}

val LocalLanesColors = staticCompositionLocalOf<LanesColors> { error("LanesTheme is missing") }

object LanesPalette {
    val Canvas: Color @Composable get() = LocalLanesColors.current.canvas
    val Surface: Color @Composable get() = LocalLanesColors.current.surface
    val SurfaceElevated: Color @Composable get() = LocalLanesColors.current.raised
    val SurfaceInteractive: Color @Composable get() = LocalLanesColors.current.surfaceInteractive
    val Rule: Color @Composable get() = LocalLanesColors.current.hairline
    val RuleSubtle: Color @Composable get() = LocalLanesColors.current.ruleSubtle
    val InkPrimary: Color @Composable get() = LocalLanesColors.current.ink
    val InkSecondary: Color @Composable get() = LocalLanesColors.current.ink2
    val InkMuted: Color @Composable get() = LocalLanesColors.current.ink3
    val Accent: Color @Composable get() = LocalLanesColors.current.accent
    val AccentDim: Color @Composable get() = LocalLanesColors.current.ink3
    val AccentContainer: Color @Composable get() = LocalLanesColors.current.accentContainer
    val OnAccent: Color @Composable get() = LocalLanesColors.current.onAccent
    val OnAccentContainer: Color @Composable get() = LocalLanesColors.current.onAccentContainer
    val Amber: Color @Composable get() = LocalLanesColors.current.paused
    val AmberContainer: Color @Composable get() = LocalLanesColors.current.pausedContainer
    val Error: Color @Composable get() = LocalLanesColors.current.failed
    val ErrorContainer: Color @Composable get() = LocalLanesColors.current.failedContainer
    val OnError: Color @Composable get() = LocalLanesColors.current.onError
    val Done: Color @Composable get() = LocalLanesColors.current.done
    val Scrim: Color @Composable get() = LocalLanesColors.current.scrim
}

private val LightLanes = LanesColors(
    canvas = Color(0xFFF2F4F3), surface = Color(0xFFFFFFFF), raised = Color(0xFFE7EBEA),
    hairline = Color(0xFFD5DBD9), ink = Color(0xFF14181A), ink2 = Color(0xFF4A5457),
    ink3 = Color(0xFF5F6A69), accent = Color(0xFF2B39F0), onAccent = Color.White,
    accentContainer = Color(0xFFE1E4FF), onAccentContainer = Color(0xFF171F99),
    paused = Color(0xFF8A5F00), pausedContainer = Color(0xFFF2B632),
    failed = Color(0xFFB8322C), failedContainer = Color(0xFFFFE3E0),
    done = Color(0xFF1B7340), scrim = Color(0x66000000),
)
private val DarkLanes = LanesColors(
    canvas = Color(0xFF101214), surface = Color(0xFF17191C), raised = Color(0xFF202327),
    hairline = Color(0xFF2A2E32), ink = Color(0xFFECEFEF), ink2 = Color(0xFFA5ADAC),
    ink3 = Color(0xFF8A9392), accent = Color(0xFF8E98FF), onAccent = Color(0xFF0B1038),
    accentContainer = Color(0xFF1E2470), onAccentContainer = Color(0xFFD9DDFF),
    paused = Color(0xFFF2B632), pausedContainer = Color(0xFF493A19),
    failed = Color(0xFFFF7B72), failedContainer = Color(0xFF4B211F),
    done = Color(0xFF4CC380), scrim = Color(0x99000000),
)
private val AmoledLanes = DarkLanes.copy(canvas = Color.Black, surface = Color(0xFF08090A), raised = Color(0xFF17191C))

object LanesSpacing {
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Base = 16.dp
    val Lg = 20.dp
    val Xl = 24.dp
    val ScreenHorizontal = 16.dp
    val BottomListBreath = 28.dp
}

object LanesRadii {
    val Badge = 8.dp
    val Control = 24.dp
    val Poster = 14.dp
    val Surface = 16.dp
    val Sheet = 28.dp
    val Pill = 999.dp
}

object LanesSizes {
    val MinTouchTarget = 48.dp
    val IconSm = 16.dp
    val IconMd = 20.dp
    val IconLg = 24.dp
    val Divider = 1.dp
    val Hairline = 0.5.dp
    const val PosterAspectRatio = 0.70f
}

object LanesMotion {
    const val FastMs = 160
    const val StandardMs = 260
    const val PressMs = 90
    val EaseStandard: Easing = FastOutSlowInEasing
    val EaseDecel: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

/** Bundled Inter family so text looks consistent across Android system fonts. */
val LanFetchFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
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
    fontSize = 13.sp,
    lineHeight = 18.sp,
    fontFeatureSettings = "tnum",
)

val TabularLabelSmall: TextStyle = TextStyle(
    fontFamily = LanFetchFontFamily,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    fontWeight = FontWeight.Medium,
    fontFeatureSettings = "tnum",
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(LanesRadii.Badge),
    small = RoundedCornerShape(LanesRadii.Control),
    medium = RoundedCornerShape(LanesRadii.Surface),
    large = RoundedCornerShape(LanesRadii.Sheet),
)

@Composable
fun LanesTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    val colors = when (mode) {
        ThemeMode.AMOLED -> AmoledLanes
        ThemeMode.LIGHT -> LightLanes
        ThemeMode.DARK -> DarkLanes
        ThemeMode.SYSTEM -> if (dark) DarkLanes else LightLanes
    }
    val scheme = if (dark) darkColorScheme(
        primary = colors.accent, onPrimary = colors.onAccent,
        primaryContainer = colors.accentContainer, onPrimaryContainer = colors.onAccentContainer,
        secondary = colors.paused, onSecondary = colors.ink,
        background = colors.canvas, onBackground = colors.ink,
        surface = colors.surface, onSurface = colors.ink,
        surfaceVariant = colors.raised, onSurfaceVariant = colors.ink2,
        outline = colors.hairline, outlineVariant = colors.hairline,
        error = colors.failed, onError = Color.White, errorContainer = colors.failedContainer,
        scrim = colors.scrim,
    ) else lightColorScheme(
        primary = colors.accent, onPrimary = colors.onAccent,
        primaryContainer = colors.accentContainer, onPrimaryContainer = colors.onAccentContainer,
        secondary = colors.paused, onSecondary = Color.White,
        background = colors.canvas, onBackground = colors.ink,
        surface = colors.surface, onSurface = colors.ink,
        surfaceVariant = colors.raised, onSurfaceVariant = colors.ink2,
        outline = colors.hairline, outlineVariant = colors.hairline,
        error = colors.failed, onError = Color.White, errorContainer = colors.failedContainer,
        scrim = colors.scrim,
    )
    CompositionLocalProvider(LocalLanesColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

/** Subdued studio tones for poster-less fallback plates so grids look editorial rather than loud. */
fun fallbackPosterTone(title: String): Color {
    val tones = listOf(
        Color(0xFF102027), Color(0xFF141B2D), Color(0xFF1A182B),
        Color(0xFF1C2321), Color(0xFF211924), Color(0xFF16222C),
    )
    return tones[title.hashCode().absoluteValue % tones.size]
}
