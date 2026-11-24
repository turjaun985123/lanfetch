package com.lanfetch.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lanfetch.app.download.DlState
import com.lanfetch.app.download.DownloadUi
import com.lanfetch.app.download.Format
import com.lanfetch.app.download.Segment

@Composable
fun DownloadsScreen(vm: MainViewModel) {
    val downloads by vm.downloads.collectAsState()
    val active = downloads.count { it.state == DlState.RUNNING || it.state == DlState.QUEUED }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Downloads", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    if (downloads.isEmpty()) "Nothing downloaded yet"
                    else "$active active • ${downloads.count { it.state == DlState.COMPLETED }} completed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (active > 0) {
                OutlinedButton(onClick = { vm.pauseAllDownloads() }) {
                    Icon(Icons.Filled.Pause, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Pause all")
                }
            }
        }

        if (downloads.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Download, contentDescription = null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Text("Downloads you start will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(downloads, key = { it.id }) { dl ->
                    DownloadCard(
                        dl = dl,
                        onPause = { vm.pauseDownload(dl.id) },
                        onResume = { vm.resumeDownload(dl.id) },
                        onRetry = { vm.retryDownload(dl.id) },
                        onCancel = { vm.cancelDownload(dl.id) },
                        onRemove = { vm.removeDownloadRecord(dl.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadCard(
    dl: DownloadUi,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateIcon(dl.state)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        dl.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        dl.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    dl.state.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelMedium,
                    color = when (dl.state) {
                        DlState.RUNNING -> MaterialTheme.colorScheme.primary
                        DlState.COMPLETED -> MaterialTheme.colorScheme.primary
                        DlState.FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            if (dl.segments.size > 1) {
                SegmentBar(dl.segments)
            } else {
                LinearProgressIndicator(
                    progress = { dl.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text(
                buildString {
                    append(Format.bytes(dl.downloadedBytes))
                    if (dl.totalBytes > 0) {
                        append(" / ").append(Format.bytes(dl.totalBytes))
                        append("  •  ").append((dl.fraction * 100).toInt()).append("%")
                    }
                    if (dl.state == DlState.RUNNING && dl.speedBps > 1) {
                        append("  •  ").append(Format.speed(dl.speedBps))
                        val eta = Format.eta(dl.etaSeconds)
                        if (eta.isNotEmpty()) append("  •  ").append(eta)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (dl.state == DlState.FAILED && !dl.error.isNullOrBlank()) {
                Text(dl.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (dl.state == DlState.PAUSED && !dl.error.isNullOrBlank()) {
                Text(dl.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (dl.state == DlState.COMPLETED) {
                if (!dl.savedPath.isNullOrBlank()) {
                    Text("Saved to ${dl.savedPath}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                if (!dl.sha256.isNullOrBlank()) {
                    Text("SHA-256 ${dl.sha256.take(16)}…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (dl.state) {
                    DlState.RUNNING -> {
                        Button(onClick = onPause) {
                            Icon(Icons.Filled.Pause, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text("Pause")
                        }
                        OutlinedButton(onClick = onCancel) { Text("Cancel") }
                    }
                    DlState.PAUSED -> {
                        Button(onClick = onResume) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text("Resume")
                        }
                        OutlinedButton(onClick = onCancel) { Text("Remove") }
                    }
                    DlState.FAILED -> {
                        Button(onClick = onRetry) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text("Retry")
                        }
                        OutlinedButton(onClick = onCancel) { Text("Remove") }
                    }
                    DlState.QUEUED -> {
                        OutlinedButton(onClick = onCancel) {
                            Icon(Icons.Filled.Cancel, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text("Cancel")
                        }
                    }
                    DlState.COMPLETED -> {
                        OutlinedButton(onClick = onRemove) {
                            Icon(Icons.Filled.Delete, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text("Clear")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StateIcon(state: DlState) {
    val (icon, tint) = when (state) {
        DlState.COMPLETED -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.primary
        DlState.FAILED -> Icons.Filled.Error to MaterialTheme.colorScheme.error
        DlState.RUNNING -> Icons.Filled.Download to MaterialTheme.colorScheme.primary
        else -> Icons.Filled.Pause to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Icon(icon, contentDescription = null, tint = tint)
}

/** Per-segment progress: every block is one parallel connection's byte range. */
@Composable
private fun SegmentBar(segments: List<Segment>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        segments.forEach { seg ->
            val frac = if (seg.length > 0) (seg.downloaded.toDouble() / seg.length).toFloat() else 0f
            Box(
                Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    Modifier.fillMaxWidth(frac).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}
