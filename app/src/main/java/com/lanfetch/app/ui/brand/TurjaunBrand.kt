package com.lanfetch.app.ui.brand

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lanfetch.app.ui.theme.LanFetchFontFamily
import com.lanfetch.app.ui.theme.TabularBodySmall
import com.lanfetch.app.ui.theme.TurjaunMotion
import com.lanfetch.app.ui.theme.TurjaunPalette
import com.lanfetch.app.ui.theme.TurjaunRadii
import com.lanfetch.app.ui.theme.TurjaunSpacing

@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
}

/**
 * Editorial LanFetch header brand mark used in the main Library top bar.
 */
@Composable
fun TurjaunHeaderBrand(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.semantics { contentDescription = "LanFetch" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(TurjaunPalette.Accent),
        )
        Text(
            text = "LanFetch",
            style = TextStyle(
                fontFamily = LanFetchFontFamily,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.2).sp,
                color = TurjaunPalette.InkPrimary,
            ),
            maxLines = 1,
        )
    }
}

/**
 * Opening-only TURJAUN wordmark with a staggered stretch-and-fade reveal.
 */
@Composable
fun TurjaunWordmark(
    modifier: Modifier = Modifier,
    fontSizeSp: Float = 44f,
    letterSpacingSp: Float = 1.5f,
) {
    val reducedMotion = rememberReducedMotion()
    val timeline = remember { Animatable(if (reducedMotion) 1f else 0f) }

    LaunchedEffect(reducedMotion) {
        if (reducedMotion) {
            timeline.snapTo(1f)
        } else {
            timeline.snapTo(0f)
            timeline.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = TurjaunMotion.WordmarkRevealMs,
                    easing = LinearEasing,
                ),
            )
        }
    }

    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = "TURJAUN" },
        horizontalArrangement = Arrangement.spacedBy(letterSpacingSp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        "TURJAUN".forEachIndexed { index, character ->
            Text(
                text = character.toString(),
                modifier = Modifier.graphicsLayer {
                    val elapsedMs = timeline.value * TurjaunMotion.WordmarkRevealMs
                    val letterStartMs = index * TurjaunMotion.WordmarkStaggerMs
                    val rawProgress = (
                        (elapsedMs - letterStartMs) / TurjaunMotion.WordmarkLetterMs
                    ).coerceIn(0f, 1f)
                    val progress = TurjaunMotion.EaseWordmark.transform(rawProgress)
                    alpha = (progress / 0.55f).coerceIn(0f, 1f)
                    scaleX = 2.5f - (1.5f * progress)
                    scaleY = 0.5f + (0.5f * progress)
                },
                style = TextStyle(
                    fontFamily = LanFetchFontFamily,
                    fontSize = fontSizeSp.sp,
                    fontWeight = FontWeight.Black,
                    color = TurjaunPalette.InkPrimary,
                ),
                maxLines = 1,
            )
        }
    }
}

/**
 * Opening-only animated TURJAUN wordmark bound to initial startup readiness.
 * Automatically leaves composition once LanFetch finishes opening so no idle
 * animation loop runs after entry.
 */
@Composable
fun TurjaunLoadingExperience(
    modifier: Modifier = Modifier,
    errorText: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TurjaunPalette.Canvas)
            .padding(horizontal = TurjaunSpacing.Xl),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            TurjaunWordmark()

            if (errorText != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = errorText,
                    style = TabularBodySmall,
                    color = TurjaunPalette.Error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            if (errorText != null && onRetry != null) {
                Spacer(Modifier.height(TurjaunSpacing.Base))
                OutlinedButton(
                    onClick = onRetry,
                    shape = RoundedCornerShape(TurjaunRadii.Control),
                ) {
                    Text("Retry connection")
                }
            }
        }
    }
}
