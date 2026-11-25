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
import com.lanfetch.app.ui.theme.TurjaunPalette
import com.lanfetch.app.ui.theme.TurjaunRadii
import com.lanfetch.app.ui.theme.TurjaunSizes
import com.lanfetch.app.ui.theme.TurjaunSpacing
import com.lanfetch.app.ui.theme.fallbackPosterTone

@Composable
fun TurjaunDivider(
    modifier: Modifier = Modifier,
    subtle: Boolean = false,
) {
    HorizontalDivider(
        modifier = modifier,
        thickness = if (subtle) TurjaunSizes.Hairline else TurjaunSizes.Divider,
        color = if (subtle) TurjaunPalette.RuleSubtle else TurjaunPalette.Rule,
    )
}

@Composable
fun turjaunRuleBorder(): BorderStroke =
    BorderStroke(TurjaunSizes.Divider, TurjaunPalette.Rule)

/**
 * Compact header anchored to the 16dp horizontal frame across top-level tabs.
 */
@Composable
fun TurjaunScreenHeader(
    title: @Composable () -> Unit,
    subtitle: String? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().background(TurjaunPalette.Canvas)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TurjaunSpacing.ScreenHorizontal, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).semantics { heading() }) {
                title()
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = TabularLabelSmall,
                        color = TurjaunPalette.InkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (actions != null) {
                Row(
                    modifier = Modifier.padding(start = TurjaunSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TurjaunSpacing.Xs),
                ) {
                    actions()
                }
            }
        }
        TurjaunDivider()
    }
}

@Composable
fun TurjaunSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = TurjaunPalette.InkMuted,
        letterSpacing = 0.9.sp,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
fun TurjaunSurface(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    elevated: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(TurjaunRadii.Surface)
    val bg = if (elevated) TurjaunPalette.SurfaceElevated else TurjaunPalette.Surface
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
fun TurjaunBadge(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = TurjaunPalette.SurfaceElevated,
    contentColor: Color = TurjaunPalette.InkPrimary,
    borderColor: Color? = null,
) {
    val mod = modifier
        .clip(RoundedCornerShape(TurjaunRadii.Badge))
        .background(containerColor)
        .let { m -> if (borderColor != null) m.border(0.5.dp, borderColor, RoundedCornerShape(TurjaunRadii.Badge)) else m }
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
fun TurjaunEmptyState(
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
                    .clip(RoundedCornerShape(TurjaunRadii.Surface))
                    .background(TurjaunPalette.SurfaceElevated)
                    .border(TurjaunSizes.Divider, TurjaunPalette.Rule, RoundedCornerShape(TurjaunRadii.Surface)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painter,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = TurjaunPalette.InkSecondary,
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = TurjaunPalette.InkPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TurjaunPalette.InkSecondary,
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
fun TurjaunPoster(
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
            color = TurjaunPalette.InkMuted,
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
