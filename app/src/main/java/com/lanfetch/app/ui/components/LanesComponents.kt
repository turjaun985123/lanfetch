package com.lanfetch.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.lanfetch.app.ui.theme.TabularLabelSmall
import com.lanfetch.app.ui.theme.LanesPalette
import com.lanfetch.app.ui.theme.LanesRadii
import com.lanfetch.app.ui.theme.LanesSizes
import com.lanfetch.app.ui.theme.LanesSpacing
import com.lanfetch.app.ui.theme.fallbackPosterTone
import com.lanfetch.app.data.DownloadState
import com.lanfetch.app.download.Segment
import com.lanfetch.app.ui.brand.rememberReducedMotion

@Composable
fun LanesDivider(
    modifier: Modifier = Modifier,
    subtle: Boolean = false,
) {
    HorizontalDivider(
        modifier = modifier,
        thickness = if (subtle) LanesSizes.Hairline else LanesSizes.Divider,
        color = if (subtle) LanesPalette.RuleSubtle else LanesPalette.Rule,
    )
}

@Composable
fun SegmentedRing(
    state: DownloadState,
    lanes: () -> List<Segment>,
    totalBytes: Long,
    downloadedBytes: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 40.dp,
) {
    val reducedMotion = rememberReducedMotion()
    val progress = if (state == DownloadState.RUNNING && totalBytes <= 0 && !reducedMotion) {
        val transition = rememberInfiniteTransition(label = "unknown_transfer")
        val turn by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400)),
            label = "unknown_transfer_turn",
        )
        turn
    } else 0.25f
    val percent = if (totalBytes > 0) (downloadedBytes * 100 / totalBytes).toInt().coerceIn(0, 100) else null
    val label = when (state) {
        DownloadState.RUNNING -> "Downloading${percent?.let { ", $it percent" } ?: ""}. Tap to pause."
        DownloadState.QUEUED -> "Queued. Tap to manage."
        DownloadState.PAUSED -> "Paused. Tap to resume."
        DownloadState.FAILED -> "Failed. Tap to retry."
        DownloadState.COMPLETED -> "Finished. Tap to open."
    }
    val color = when (state) {
        DownloadState.PAUSED -> LanesPalette.Amber
        DownloadState.FAILED -> LanesPalette.Error
        DownloadState.COMPLETED -> androidx.compose.ui.graphics.Color(0xFF4CC380)
        else -> LanesPalette.Accent
    }
    val trackColor = LanesPalette.SurfaceElevated
    Box(
        modifier = modifier
            .size(48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke)
            val segments = lanes()
            if (state == DownloadState.COMPLETED) {
                drawCircle(color, style = Stroke(stroke))
            } else if (state == DownloadState.RUNNING && totalBytes <= 0) {
                drawCircle(trackColor, style = Stroke(stroke))
                drawArc(
                    color = color,
                    startAngle = -90f + progress * 360f,
                    sweepAngle = 100f,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            } else {
                val count = segments.size.coerceAtLeast(1)
                val gap = if (count == 1) 0f else 6f
                val step = 360f / count
                repeat(count) { i ->
                    val start = -90f + i * step + gap / 2
                    val sweep = step - gap
                    drawArc(trackColor, start, sweep, false,
                        androidx.compose.ui.geometry.Offset(inset, inset), arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round))
                    val segment = segments.getOrNull(i)
                    val fill = if (state == DownloadState.COMPLETED) 1f else if (segment == null) 0f
                    else (segment.downloaded.toDouble() / segment.length).toFloat().coerceIn(0f, 1f)
                    if (fill > 0f) drawArc(color, start, sweep * fill, false,
                        androidx.compose.ui.geometry.Offset(inset, inset), arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
        }
        Text(
            text = when (state) {
                DownloadState.RUNNING -> "Ⅱ"
                DownloadState.QUEUED -> "·"
                DownloadState.PAUSED -> "▶"
                DownloadState.FAILED -> "↻"
                DownloadState.COMPLETED -> "✓"
            },
            color = color,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier,
        )
    }
}

@Composable
fun turjaunRuleBorder(): BorderStroke =
    BorderStroke(LanesSizes.Divider, LanesPalette.Rule)

/**
 * Compact header anchored to the 16dp horizontal frame across top-level tabs.
 */
@Composable
fun LanesScreenHeader(
    title: @Composable () -> Unit,
    subtitle: String? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().background(LanesPalette.Canvas)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LanesSpacing.ScreenHorizontal, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).semantics { heading() }) {
                title()
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = TabularLabelSmall,
                        color = LanesPalette.InkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (actions != null) {
                Row(
                    modifier = Modifier.padding(start = LanesSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(LanesSpacing.Xs),
                ) {
                    actions()
                }
            }
        }
        LanesDivider()
    }
}

@Composable
fun LanesSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = LanesPalette.InkMuted,
        letterSpacing = 0.9.sp,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
fun LanesSurface(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    elevated: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(LanesRadii.Surface)
    val bg = if (elevated) LanesPalette.SurfaceElevated else LanesPalette.Surface
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            color = bg,
            border = turjaunRuleBorder(),
        ) {
            Column(content = content)
        }
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = bg,
            border = turjaunRuleBorder(),
        ) {
            Column(content = content)
        }
    }
}

@Composable
fun LanesBadge(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = LanesPalette.SurfaceElevated,
    contentColor: Color = LanesPalette.InkPrimary,
    borderColor: Color? = null,
) {
    val mod = modifier
        .clip(RoundedCornerShape(LanesRadii.Badge))
        .background(containerColor)
        .let { m -> if (borderColor != null) m.border(0.5.dp, borderColor, RoundedCornerShape(LanesRadii.Badge)) else m }
        .padding(horizontal = 6.dp, vertical = 2.dp)

    Box(modifier = mod) {
        Text(
            text = text,
            style = TabularLabelSmall,
            color = contentColor,
            maxLines = 1,
        )
    }
}

@Composable
fun LanesEmptyState(
    painter: Painter,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(LanesRadii.Surface))
                    .background(LanesPalette.SurfaceElevated)
                    .border(LanesSizes.Divider, LanesPalette.Rule, RoundedCornerShape(LanesRadii.Surface)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painter,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = LanesPalette.InkSecondary,
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = LanesPalette.InkPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = LanesPalette.InkSecondary,
                textAlign = TextAlign.Center,
            )
            if (action != null) {
                Spacer(Modifier.height(16.dp))
                action()
            }
        }
    }
}

/**
 * Constrained poster artwork loader: decodes posters to compact target dimensions
 * via Precision.INEXACT so scrolling large grids stays smooth and low-memory.
 */
@Composable
fun LanesPoster(
    title: String,
    posterUrl: String?,
    modifier: Modifier = Modifier,
    targetWidthPx: Int = 360,
    targetHeightPx: Int = 520,
    allowCrossfade: Boolean = false,
) {
    val context = LocalContext.current
    val initials = remember(title) {
        title.split(' ')
            .asSequence()
            .filter { it.isNotBlank() }
            .take(2)
            .map { it.first().uppercaseChar() }
            .joinToString("")
            .ifEmpty { "LF" }
    }
    val fallbackBg = remember(title) { fallbackPosterTone(title) }
    val imageRequest = remember(posterUrl, targetWidthPx, targetHeightPx, allowCrossfade) {
        if (posterUrl == null) null
        else ImageRequest.Builder(context)
            .data(posterUrl)
            .size(targetWidthPx, targetHeightPx)
            .precision(Precision.INEXACT)
            .crossfade(if (allowCrossfade) 140 else 0)
            .build()
    }

    Box(
        modifier = modifier.background(fallbackBg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = LanesPalette.InkMuted,
        )
        if (imageRequest != null) {
            AsyncImage(
                model = imageRequest,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
