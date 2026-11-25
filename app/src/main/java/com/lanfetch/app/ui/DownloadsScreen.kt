package com.lanfetch.app.ui

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.lanfetch.app.R
import com.lanfetch.app.data.DownloadState
import com.lanfetch.app.download.DiskSpace
import com.lanfetch.app.download.DownloadCoordinator
import com.lanfetch.app.download.DownloadUi
import com.lanfetch.app.download.Format
import com.lanfetch.app.download.Segment
import java.io.File

@Composable
fun DownloadsScreen(vm: DownloadsViewModel) {
    val downloads by vm.downloads.collectAsState()
    val context = LocalContext.current
    val active = downloads.count {
        it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED
    }
    val completed = downloads.count { it.state == DownloadState.COMPLETED }

    var hasPublicStorage by remember {
        mutableStateOf(DiskSpace.hasPublicStoragePermission(context))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPublicStorage = granted || DiskSpace.hasPublicStoragePermission(context)
        if (granted) {
            vm.notifyMessage("Downloads will save to Downloads/LanFetch")
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Downloads",
            subtitle = if (downloads.isEmpty()) "Saves to Downloads/LanFetch"
            else "$active active • $completed completed",
            action = when {
                active > 0 -> {
                    {
                        OutlinedButton(
                            onClick = { vm.pauseAll() },
                            shape = RoundedCornerShape(Grid.RadiusControl),
                        ) {
                            Icon(painterResource(R.drawable.ic_pause), contentDescription = null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Pause all")
                        }
                    }
                }
                completed > 0 -> {
                    {
                        OutlinedButton(
                            onClick = {
                                openSystemDownloadsFolder(context)?.let { vm.notifyMessage(it) }
                            },
                            shape = RoundedCornerShape(Grid.RadiusControl),
                        ) {
                            Text("Open folder")
                        }
                    }
                }
                else -> null
            },
        )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !hasPublicStorage) {
            Box(Modifier.padding(horizontal = Grid.PageHorizontal, vertical = 8.dp)) {
                SurfaceCard {
                    Row(
                        Modifier.fillMaxWidth().padding(Grid.CardPadding),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Grid.ItemGap),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Storage permission needed",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                "Grant storage access so files save to Phone storage / Download / LanFetch.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = {
                                permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            },
                            shape = RoundedCornerShape(Grid.RadiusControl),
                        ) {
                            Text("Allow")
                        }
                    }
                }
            }
        }

        if (downloads.isEmpty()) {
            EmptyState(
                painter = painterResource(R.drawable.ic_download),
                title = "No downloads yet",
                subtitle = "Completed movies and episodes save to Downloads/LanFetch on your phone.",
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Grid.PageHorizontal),
                verticalArrangement = Arrangement.spacedBy(Grid.ItemGap),
            ) {
                items(downloads, key = { it.id }) { download ->
                    DownloadCard(
                        download = download,
                        onOpen = {
                            openDownloadedFile(context, download)?.let { vm.notifyMessage(it) }
                        },
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
    onOpen: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    SurfaceCard(
        onClick = if (download.state == DownloadState.COMPLETED) onOpen else null,
    ) {
        Column(
            Modifier.padding(Grid.CardPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StateIcon(download.state)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        download.title,
                        style = MaterialTheme.typography.titleSmall,
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
                Spacer(Modifier.width(10.dp))
                StateBadge(download.state)
            }

            if (download.segments.size > 1) {
                SegmentBar(download.segments)
            } else {
                LinearProgressIndicator(
                    progress = { download.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }

            val leftMetrics = buildString {
                append(Format.bytes(download.downloadedBytes))
                if (download.totalBytes > 0) {
                    append(" / ").append(Format.bytes(download.totalBytes))
                    append(" • ").append((download.fraction * 100).toInt()).append("%")
                }
            }
            val rightMetrics = if (download.state == DownloadState.RUNNING && download.speedBps > 1) {
                listOfNotNull(
                    Format.speed(download.speedBps).takeIf { it.isNotEmpty() },
                    Format.eta(download.etaSeconds).takeIf { it.isNotEmpty() },
                ).joinToString(" • ")
            } else ""

            val tnumStyle: TextStyle = MaterialTheme.typography.bodySmall.copy(
                fontFeatureSettings = "tnum",
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    leftMetrics,
                    style = tnumStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (rightMetrics.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        rightMetrics,
                        style = tnumStyle,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }

            if (!download.error.isNullOrBlank() &&
                (download.state == DownloadState.FAILED || download.state == DownloadState.PAUSED)
            ) {
                Text(
                    download.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (download.state == DownloadState.FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (download.state == DownloadState.COMPLETED && !download.savedPath.isNullOrBlank()) {
                Text(
                    "Saved in ${download.savedPath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            GridRuleDivider()

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Grid.ChipGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (download.state) {
                    DownloadState.RUNNING -> {
                        Button(onClick = onPause, shape = RoundedCornerShape(Grid.RadiusControl)) {
                            Icon(painterResource(R.drawable.ic_pause), contentDescription = null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp)); Text("Pause")
                        }
                        OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(Grid.RadiusControl)) {
                            Text("Cancel")
                        }
                    }
                    DownloadState.PAUSED -> {
                        Button(onClick = onResume, shape = RoundedCornerShape(Grid.RadiusControl)) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp)); Text("Resume")
                        }
                        OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(Grid.RadiusControl)) {
                            Text("Remove")
                        }
                    }
                    DownloadState.FAILED -> {
                        Button(onClick = onRetry, shape = RoundedCornerShape(Grid.RadiusControl)) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp)); Text("Retry")
                        }
                        OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(Grid.RadiusControl)) {
                            Text("Remove")
                        }
                    }
                    DownloadState.QUEUED -> {
                        OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(Grid.RadiusControl)) {
                            Icon(painterResource(R.drawable.ic_cancel), contentDescription = null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp)); Text("Cancel")
                        }
                    }
                    DownloadState.COMPLETED -> {
                        Button(onClick = onOpen, shape = RoundedCornerShape(Grid.RadiusControl)) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp)); Text("Open")
                        }
                        OutlinedButton(onClick = onRemove, shape = RoundedCornerShape(Grid.RadiusControl)) {
                            Icon(Icons.Filled.Delete, contentDescription = null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp)); Text("Clear")
                        }
                    }
                }
            }
        }
    }
}

private fun openDownloadedFile(context: Context, download: DownloadUi): String? {
    val mime = download.mimeType?.takeIf { it.isNotBlank() }
        ?: DownloadCoordinator.mimeFor(download.fileName)
    val contentUri: Uri? = when {
        !download.mediaUri.isNullOrBlank() -> Uri.parse(download.mediaUri)
        !download.savedPath.isNullOrBlank() && download.savedPath.startsWith("/") -> {
            val file = File(download.savedPath)
            if (!file.exists()) return "File no longer exists at ${download.savedPath}"
            runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }.getOrNull()
        }
        else -> null
    }
    if (contentUri != null) {
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(viewIntent) }.isSuccess) return null

        val genericIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(genericIntent, "Open with").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(chooser) }.isSuccess) return null
    }
    return openSystemDownloadsFolder(context)
        ?: return null
}

private fun openSystemDownloadsFolder(context: Context): String? {
    val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (runCatching { context.startActivity(intent) }.isSuccess) return null
    return "Saved in Phone Downloads/LanFetch — open via your Files or Video Player app"
}

@Composable
private fun StateBadge(state: DownloadState) {
    val (label, bg, fg) = when (state) {
        DownloadState.RUNNING -> Triple("Running", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary)
        DownloadState.COMPLETED -> Triple("Saved", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary)
        DownloadState.FAILED -> Triple("Failed", MaterialTheme.colorScheme.error.copy(alpha = 0.16f), MaterialTheme.colorScheme.error)
        DownloadState.QUEUED -> Triple("Queued", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        DownloadState.PAUSED -> Triple("Paused", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    BadgePill(text = label, containerColor = bg, contentColor = fg)
}

@Composable
private fun StateIcon(state: DownloadState) {
    when (state) {
        DownloadState.COMPLETED -> Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        DownloadState.FAILED -> Icon(
            painterResource(R.drawable.ic_error),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        DownloadState.RUNNING -> Icon(
            painterResource(R.drawable.ic_download),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        else -> Icon(
            painterResource(R.drawable.ic_pause),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
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
                (segment.downloaded.toDouble() / segment.length).toFloat().coerceIn(0f, 1f)
            } else 0f
            Box(
                Modifier
                    .weight(1f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}
