package com.lanfetch.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.res.painterResource
import com.lanfetch.app.R
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
import com.lanfetch.app.data.DownloadState
import com.lanfetch.app.download.DownloadUi
import com.lanfetch.app.download.Format
import com.lanfetch.app.download.Segment

@Composable
fun DownloadsScreen(vm: DownloadsViewModel) {
    val downloads by vm.downloads.collectAsState()
    val active = downloads.count {
        it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Downloads", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    if (downloads.isEmpty()) "Nothing downloaded yet"
                    else "$active active • ${downloads.count { it.state == DownloadState.COMPLETED }} completed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (active > 0) {
                OutlinedButton(onClick = { vm.pauseAll() }) {
                    Icon(painterResource(R.drawable.ic_pause), contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Pause all")
                }
            }
        }

        if (downloads.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        painterResource(R.drawable.ic_download), contentDescription = null,
                        Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Downloads you start will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(downloads, key = { it.id }) { download ->
                    DownloadCard(
                        download = download,
                        onPause = { vm.pause(download.id) },
                        onResume = { vm.resume(download.id) },
                        onRetry = { vm.retry(download.id) },
                        onCancel = { vm.cancel(download.id) },
                        onRemove = { vm.remove(download.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadCard(
    download: DownloadUi,
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
                StateIcon(download.state)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        download.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        download.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    download.state.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelMedium,
                    color = when (download.state) {
                        DownloadState.RUNNING, DownloadState.COMPLETED -> MaterialTheme.colorScheme.primary
                        DownloadState.FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            if (download.segments.size > 1) {
                SegmentBar(download.segments)
            } else {
                LinearProgressIndicator(
                    progress = { download.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text(
                buildString {
                    append(Format.bytes(download.downloadedBytes))
                    if (download.totalBytes > 0) {
                        append(" / ").append(Format.bytes(download.totalBytes))
                        append("  •  ").append((download.fraction * 100).toInt()).append("%")
                    }
                    if (download.state == DownloadState.RUNNING && download.speedBps > 1) {
                        append("  •  ").append(Format.speed(download.speedBps))
                        val eta = Format.eta(download.etaSeconds)
                        if (eta.isNotEmpty()) append("  •  ").append(eta)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (download.state == DownloadState.FAILED && !download.error.isNullOrBlank()) {
                Text(
                    download.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (download.state == DownloadState.PAUSED && !download.error.isNullOrBlank()) {
                Text(
                    download.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (download.state == DownloadState.COMPLETED && !download.savedPath.isNullOrBlank()) {
                Text(
                    "Saved to ${download.savedPath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (download.state) {
                    DownloadState.RUNNING -> {
                        Button(onClick = onPause) {
                            Icon(painterResource(R.drawable.ic_pause), contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text("Pause")
                        }
                        OutlinedButton(onClick = onCancel) { Text("Cancel") }
                    }
                    DownloadState.PAUSED -> {
                        Button(onClick = onResume) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text("Resume")
                        }
                        OutlinedButton(onClick = onCancel) { Text("Remove") }
                    }
                    DownloadState.FAILED -> {
                        Button(onClick = onRetry) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text("Retry")
                        }
                        OutlinedButton(onClick = onCancel) { Text("Remove") }
                    }
                    DownloadState.QUEUED -> {
                        OutlinedButton(onClick = onCancel) {
                            Icon(painterResource(R.drawable.ic_cancel), contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text("Cancel")
                        }
                    }
                    DownloadState.COMPLETED -> {
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
private fun StateIcon(state: DownloadState) {
    when (state) {
        DownloadState.COMPLETED -> Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        DownloadState.FAILED -> Icon(
            painterResource(R.drawable.ic_error),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        DownloadState.RUNNING -> Icon(
            painterResource(R.drawable.ic_download),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        else -> Icon(
            painterResource(R.drawable.ic_pause),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Per-segment progress: every block is one parallel connection's byte range. */
@Composable
private fun SegmentBar(segments: List<Segment>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        segments.forEach { segment ->
            val fraction = if (segment.length > 0) {
                (segment.downloaded.toDouble() / segment.length).toFloat()
            } else 0f
            Box(
                Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    Modifier.fillMaxWidth(fraction).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}
