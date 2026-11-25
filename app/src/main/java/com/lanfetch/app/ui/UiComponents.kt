package com.lanfetch.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun GridRuleDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
    )
}

@Composable
fun cardBorder(): BorderStroke =
    BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.38f))

/** Uniform page header anchored to the 16dp grid across every tab. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Grid.PageHorizontal, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (action != null) {
                Box(Modifier.padding(start = Grid.ItemGap)) {
                    action()
                }
            }
        }
        GridRuleDivider()
    }
}

@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Grid.RadiusCard)
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            border = cardBorder(),
        ) {
            Column(content = content)
        }
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            border = cardBorder(),
        ) {
            Column(content = content)
        }
    }
}

@Composable
fun BadgePill(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Grid.RadiusBadge))
            .background(containerColor)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            maxLines = 1,
        )
    }
}

@Composable
fun EmptyState(
    painter: Painter,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = modifier.fillMaxSize().padding(Grid.PageHorizontal * 2),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painter,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (action != null) {
                Spacer(Modifier.height(14.dp))
                action()
            }
        }
    }
}
