package com.lanfetch.app.ui.downloads

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lanfetch.app.R
import com.lanfetch.app.data.DownloadState
import com.lanfetch.app.download.DiskSpace
import com.lanfetch.app.download.DownloadCoordinator
import com.lanfetch.app.download.DownloadUi
import com.lanfetch.app.download.Format
import com.lanfetch.app.download.Segment
import com.lanfetch.app.ui.components.TurjaunBadge
import com.lanfetch.app.ui.components.TurjaunDivider
import com.lanfetch.app.ui.components.TurjaunEmptyState
import com.lanfetch.app.ui.components.TurjaunScreenHeader
import com.lanfetch.app.ui.components.TurjaunSurface
import com.lanfetch.app.ui.theme.TabularBodySmall
import com.lanfetch.app.ui.theme.TurjaunPalette
import com.lanfetch.app.ui.theme.TurjaunRadii
import com.lanfetch.app.ui.theme.TurjaunSpacing
import java.io.File

@Composable
fun DownloadsScreen(vm: DownloadsViewModel) {
    val downloads by vm.downloads.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val running = downloads.count { it.state == DownloadState.RUNNING }
    val queued = downloads.count { it.state == DownloadState.QUEUED }
    val completed = downloads.count { it.state == DownloadState.COMPLETED }
    val active = running + queued

    var hasPublicStorage by remember {
        mutableStateOf(DiskSpace.hasPublicStoragePermission(context))
    }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPublicStorage = DiskSpace.hasPublicStoragePermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPublicStorage = granted || DiskSpace.hasPublicStoragePermission(context)
        if (granted) {
            vm.notifyMessage("Downloads will save to Downloads/LanFetch")
        }
    }

    Column(Modifier.fillMaxSize().background(TurjaunPalette.Canvas)) {
        TurjaunScreenHeader(
            title = {
                Text(
                    "Downloads",
                    style = MaterialTheme.typography.titleLarge,
                    color = TurjaunPalette.InkPrimary,
                )
            },
            subtitle = when {
                downloads.isEmpty() -> "Saves to Downloads/LanFetch"
                active > 0 -> "$running running • $queued queued • $completed saved"
                else -> "$completed completed"
            },
            actions = {
                when {
                    active > 0 -> {
                        OutlinedButton(
                            onClick = vm::pauseAll,
                            shape = RoundedCornerShape(TurjaunRadii.Control),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_pause),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Pause all")
                        }
                    }
                    completed > 0 -> {
                        OutlinedButton(
                            onClick = { openSystemDownloadsFolder(context)?.let(vm::notifyMessage) },
                            shape = RoundedCornerShape(TurjaunRadii.Control),
                        ) {
                            Text("Open folder")
                        }
                    }
                }
            },
        )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !hasPublicStorage) {
            Box(Modifier.padding(horizontal = TurjaunSpacing.ScreenHorizontal, vertical = 10.dp)) {
                TurjaunSurface(elevated = true) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(TurjaunSpacing.Base),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(TurjaunSpacing.Md),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Storage permission needed",
                                style = MaterialTheme.typography.titleSmall,
                                color = TurjaunPalette.InkPrimary,
                            )
                            Text(
                                "Grant storage access so completed files save directly to Phone storage / Download / LanFetch.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TurjaunPalette.InkSecondary,
                            )
                        }
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) },
                            shape = RoundedCornerShape(TurjaunRadii.Control),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TurjaunPalette.Accent,
                                contentColor = TurjaunPalette.OnAccent,
                            ),
                        ) {
                            Text("Allow")
                        }
                    }
                }
            }
        }

        if (downloads.isEmpty()) {
            TurjaunEmptyState(
                painter = painterResource(R.drawable.ic_download),
                title = "No transfers",
                subtitle = "Movies and episodes you download appear here with live multi-segment progress and speed.",
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = TurjaunSpacing.BottomListBreath),
            ) {
                items(downloads, key = { it.id }, contentType = { "download_row" }) { download ->
                    DownloadItemRow(
                        download = download,
                        onOpen = { openDownloadedFile(context, download)?.let(vm::notifyMessage) },
                        onPause = { vm.pause(download.id) },
                        onResume = { vm.resume(download.id) },
                        onRetry = { vm.retry(download.id) },
                        onCancel = { vm.cancel(download.id) },
                        onRemove = { vm.remove(download.id) },
                    )
                    TurjaunDivider()
                }
            }
        }
    }
}

@Composable
private fun DownloadItemRow(
    download: DownloadUi,
    onOpen: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    val rowModifier = if (download.state == DownloadState.COMPLETED) {
        Modifier.fillMaxWidth().clickable(onClick = onOpen)
    } else {
        Modifier.fillMaxWidth()
    }

    Column(
        modifier = rowModifier
            .padding(horizontal = TurjaunSpacing.ScreenHorizontal, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StateGlyph(download.state)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = download.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = TurjaunPalette.InkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = download.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = TurjaunPalette.InkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            StateBadge(download.state)
        }

        if (download.segments.size > 1) {
            SegmentProgressTrack(download.segments)
        } else {
            LinearProgressIndicator(
                progress = { download.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (download.state == DownloadState.FAILED) TurjaunPalette.Error else TurjaunPalette.Accent,
                trackColor = TurjaunPalette.SurfaceInteractive,
            )
        }

        val leftMetrics = remember(download.downloadedBytes, download.totalBytes, download.fraction) {
            buildString {
                append(Format.bytes(download.downloadedBytes))
                if (download.totalBytes > 0) {
                    append(" / ").append(Format.bytes(download.totalBytes))
                    append(" • ").append((download.fraction * 100).toInt()).append("%")
                }
            }
        }
        val rightMetrics = remember(download.state, download.speedBps, download.etaSeconds) {
            if (download.state == DownloadState.RUNNING && download.speedBps > 1) {
                listOfNotNull(
                    Format.speed(download.speedBps).takeIf { it.isNotEmpty() },
                    Format.eta(download.etaSeconds).takeIf { it.isNotEmpty() },
                ).joinToString(" • ")
            } else ""
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = leftMetrics,
                style = TabularBodySmall,
                color = TurjaunPalette.InkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (rightMetrics.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = rightMetrics,
                    style = TabularBodySmall,
                    color = TurjaunPalette.Accent,
                    maxLines = 1,
                )
            }
        }

        if (!download.error.isNullOrBlank() &&
            (download.state == DownloadState.FAILED || download.state == DownloadState.PAUSED)
        ) {
            Text(
                text = download.error,
                style = MaterialTheme.typography.bodySmall,
                color = if (download.state == DownloadState.FAILED) TurjaunPalette.Error
                else TurjaunPalette.Amber,
            )
        }

        if (download.state == DownloadState.COMPLETED && !download.savedPath.isNullOrBlank()) {
            Text(
                text = "Saved in ${download.savedPath}",
                style = MaterialTheme.typography.bodySmall,
                color = TurjaunPalette.Accent,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TurjaunSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (download.state) {
                DownloadState.RUNNING -> {
                    OutlinedButton(onClick = onPause, shape = RoundedCornerShape(TurjaunRadii.Control)) {
                        Icon(painterResource(R.drawable.ic_pause), contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pause")
                    }
                    OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(TurjaunRadii.Control)) {
                        Text("Cancel")
                    }
                }
                DownloadState.PAUSED -> {
                    Button(
                        onClick = onResume,
                        shape = RoundedCornerShape(TurjaunRadii.Control),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TurjaunPalette.Accent,
                            contentColor = TurjaunPalette.OnAccent,
                        ),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Resume")
                    }
                    OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(TurjaunRadii.Control)) {
                        Text("Remove")
                    }
                }
                DownloadState.FAILED -> {
                    Button(
                        onClick = onRetry,
                        shape = RoundedCornerShape(TurjaunRadii.Control),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TurjaunPalette.Accent,
                            contentColor = TurjaunPalette.OnAccent,
                        ),
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Retry")
                    }
                    OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(TurjaunRadii.Control)) {
                        Text("Remove")
                    }
                }
                DownloadState.QUEUED -> {
                    OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(TurjaunRadii.Control)) {
                        Icon(painterResource(R.drawable.ic_cancel), contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Cancel")
                    }
                }
                DownloadState.COMPLETED -> {
                    Button(
                        onClick = onOpen,
                        shape = RoundedCornerShape(TurjaunRadii.Control),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TurjaunPalette.Accent,
                            contentColor = TurjaunPalette.OnAccent,
                        ),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open")
                    }
                    OutlinedButton(onClick = onRemove, shape = RoundedCornerShape(TurjaunRadii.Control)) {
                        Icon(Icons.Filled.Delete, contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Clear")
                    }
                }
            }
        }
    }
}

@Composable
private fun StateBadge(state: DownloadState) {
    val (label, bg, fg) = when (state) {
        DownloadState.RUNNING -> Triple("Running", TurjaunPalette.AccentContainer, TurjaunPalette.Accent)
        DownloadState.COMPLETED -> Triple("Saved", TurjaunPalette.AccentContainer, TurjaunPalette.Accent)
        DownloadState.FAILED -> Triple("Failed", TurjaunPalette.ErrorContainer, TurjaunPalette.Error)
        DownloadState.QUEUED -> Triple("Queued", TurjaunPalette.SurfaceElevated, TurjaunPalette.InkSecondary)
        DownloadState.PAUSED -> Triple("Paused", TurjaunPalette.AmberContainer, TurjaunPalette.Amber)
    }
    TurjaunBadge(text = label, containerColor = bg, contentColor = fg)
}

@Composable
private fun StateGlyph(state: DownloadState) {
    when (state) {
        DownloadState.COMPLETED -> Icon(
            Icons.Filled.CheckCircle,
            contentDescription = "Completed",
            modifier = Modifier.size(20.dp),
            tint = TurjaunPalette.Accent,
        )
        DownloadState.FAILED -> Icon(
            painterResource(R.drawable.ic_error),
            contentDescription = "Failed",
            modifier = Modifier.size(20.dp),
            tint = TurjaunPalette.Error,
        )
        DownloadState.RUNNING -> Icon(
            painterResource(R.drawable.ic_download),
            contentDescription = "Running",
            modifier = Modifier.size(20.dp),
            tint = TurjaunPalette.Accent,
        )
        else -> Icon(
            painterResource(R.drawable.ic_pause),
            contentDescription = "Paused or queued",
            modifier = Modifier.size(20.dp),
            tint = if (state == DownloadState.PAUSED) TurjaunPalette.Amber else TurjaunPalette.InkMuted,
        )
    }
}

@Composable
private fun SegmentProgressTrack(segments: List<Segment>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        segments.forEach { segment ->
            val fraction = if (segment.length > 0) {
                (segment.downloaded.toDouble() / segment.length).toFloat().coerceIn(0f, 1f)
            } else 0f
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(TurjaunPalette.SurfaceInteractive)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(TurjaunPalette.Accent)
                )
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
}

private fun openSystemDownloadsFolder(context: Context): String? {
    val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (runCatching { context.startActivity(intent) }.isSuccess) return null
    return "Saved in Phone Downloads/LanFetch — open via your Files or Video Player app"
}
