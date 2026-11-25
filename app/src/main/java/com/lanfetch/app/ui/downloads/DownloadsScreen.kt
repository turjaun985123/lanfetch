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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.lanfetch.app.ui.components.LanesBadge
import com.lanfetch.app.ui.components.LanesDivider
import com.lanfetch.app.ui.components.LanesEmptyState
import com.lanfetch.app.ui.components.LanesScreenHeader
import com.lanfetch.app.ui.components.LanesSurface
import com.lanfetch.app.ui.theme.TabularBodySmall
import com.lanfetch.app.ui.theme.LanesPalette
import com.lanfetch.app.ui.theme.LanesRadii
import com.lanfetch.app.ui.theme.LanesSpacing
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

    // Download focus view (redesign spec): tap a row for the big ring + lanes.
    var focusedId by remember { mutableStateOf<Long?>(null) }
    val focused = focusedId?.let { id -> downloads.firstOrNull { it.id == id } }

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

    Column(Modifier.fillMaxSize().background(LanesPalette.Canvas)) {
        LanesScreenHeader(
            title = {
                Text(
                    "Downloads",
                    style = MaterialTheme.typography.titleLarge,
                    color = LanesPalette.InkPrimary,
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
                            shape = RoundedCornerShape(LanesRadii.Control),
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
                            shape = RoundedCornerShape(LanesRadii.Control),
                        ) {
                            Text("Open folder")
                        }
                    }
                }
            },
        )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !hasPublicStorage) {
            Box(Modifier.padding(horizontal = LanesSpacing.ScreenHorizontal, vertical = 10.dp)) {
                LanesSurface(elevated = true) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(LanesSpacing.Base),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(LanesSpacing.Md),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Storage permission needed",
                                style = MaterialTheme.typography.titleSmall,
                                color = LanesPalette.InkPrimary,
                            )
                            Text(
                                "Grant storage access so completed files save directly to Phone storage / Download / LanFetch.",
                                style = MaterialTheme.typography.bodySmall,
                                color = LanesPalette.InkSecondary,
                            )
                        }
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) },
                            shape = RoundedCornerShape(LanesRadii.Control),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = LanesPalette.Accent,
                                contentColor = LanesPalette.OnAccent,
                            ),
                        ) {
                            Text("Allow")
                        }
                    }
                }
            }
        }

        // Audit B6: verify completed files still exist when Transfers opens.
        LaunchedEffect(Unit) { vm.checkMissing() }

        focused?.let { download ->
            DownloadFocusSheet(
                download = download,
                onDismiss = { focusedId = null },
                onPause = { vm.pause(download.id) },
                onResume = { vm.resume(download.id) },
                onRetry = { vm.retry(download.id) },
                onRedownload = { vm.redownload(download.id) },
                onRemove = { vm.remove(download.id); focusedId = null },
                onOpen = { openDownloadedFile(context, download)?.let(vm::notifyMessage) },
            )
        }

        if (downloads.isEmpty()) {
            LanesEmptyState(
                painter = painterResource(R.drawable.ic_download),
                title = "No transfers",
                subtitle = "Movies and episodes you download appear here with live multi-segment progress and speed.",
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = LanesSpacing.BottomListBreath),
            ) {
                items(downloads, key = { it.id }, contentType = { "download_row" }) { download ->
                    DownloadItemRow(
                        download = download,
                        onOpen = { openDownloadedFile(context, download)?.let(vm::notifyMessage) },
                        onPause = { vm.pause(download.id) },
                        onResume = { vm.resume(download.id) },
                        onRetry = { vm.retry(download.id) },
                        onRedownload = { vm.redownload(download.id) },
                        onFocus = { focusedId = download.id },
                        onCancel = { vm.cancel(download.id) },
                        onRemove = { vm.remove(download.id) },
                    )
                    LanesDivider()
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
    onRedownload: () -> Unit,
    onFocus: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    // Tapping a row opens the focus view; Open lives on its button (and in the sheet).
    val rowModifier = Modifier.fillMaxWidth().clickable(onClick = onFocus)

    Column(
        modifier = rowModifier
            .padding(horizontal = LanesSpacing.ScreenHorizontal, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StateGlyph(download.state)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = download.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = LanesPalette.InkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = download.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = LanesPalette.InkSecondary,
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
                color = if (download.state == DownloadState.FAILED) LanesPalette.Error else LanesPalette.Accent,
                trackColor = LanesPalette.SurfaceInteractive,
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
                color = LanesPalette.InkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (rightMetrics.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = rightMetrics,
                    style = TabularBodySmall,
                    color = LanesPalette.Accent,
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
                color = if (download.state == DownloadState.FAILED) LanesPalette.Error
                else LanesPalette.Amber,
            )
        }

        if (download.state == DownloadState.MISSING) {
            Text(
                text = "File not found on this phone. It may have been deleted.",
                style = MaterialTheme.typography.bodySmall,
                color = LanesPalette.Error,
                maxLines = 2,
            )
        }

        if (download.state == DownloadState.COMPLETED && !download.savedPath.isNullOrBlank()) {
            Text(
                text = "Saved in ${download.savedPath}",
                style = MaterialTheme.typography.bodySmall,
                color = LanesPalette.Accent,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LanesSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (download.state) {
                DownloadState.RUNNING -> {
                    OutlinedButton(onClick = onPause, shape = RoundedCornerShape(LanesRadii.Control)) {
                        Icon(painterResource(R.drawable.ic_pause), contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pause")
                    }
                    OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(LanesRadii.Control)) {
                        Text("Cancel")
                    }
                }
                DownloadState.PAUSED -> {
                    Button(
                        onClick = onResume,
                        shape = RoundedCornerShape(LanesRadii.Control),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LanesPalette.Accent,
                            contentColor = LanesPalette.OnAccent,
                        ),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Resume")
                    }
                    OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(LanesRadii.Control)) {
                        Text("Remove")
                    }
                }
                DownloadState.FAILED -> {
                    Button(
                        onClick = onRetry,
                        shape = RoundedCornerShape(LanesRadii.Control),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LanesPalette.Accent,
                            contentColor = LanesPalette.OnAccent,
                        ),
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Retry")
                    }
                    OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(LanesRadii.Control)) {
                        Text("Remove")
                    }
                }
                DownloadState.QUEUED -> {
                    OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(LanesRadii.Control)) {
                        Icon(painterResource(R.drawable.ic_cancel), contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Cancel")
                    }
                }
                DownloadState.MISSING -> {
                    Button(
                        onClick = onRedownload,
                        shape = RoundedCornerShape(LanesRadii.Control),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LanesPalette.Accent,
                            contentColor = LanesPalette.OnAccent,
                        ),
                    ) {
                        Icon(painterResource(R.drawable.ic_download), contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Download again")
                    }
                    OutlinedButton(onClick = onRemove, shape = RoundedCornerShape(LanesRadii.Control)) {
                        Text("Clear")
                    }
                }
                DownloadState.COMPLETED -> {
                    Button(
                        onClick = onOpen,
                        shape = RoundedCornerShape(LanesRadii.Control),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LanesPalette.Accent,
                            contentColor = LanesPalette.OnAccent,
                        ),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open")
                    }
                    OutlinedButton(onClick = onRemove, shape = RoundedCornerShape(LanesRadii.Control)) {
                        Icon(Icons.Filled.Delete, contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Clear")
                    }
                }
            }
        }
    }
}

/**
 * Download focus view (redesign spec): the segmented ring at 96 dp, one bar
 * per lane with its own fill, and the facts — server, file, saved location.
 */
@Composable
private fun DownloadFocusSheet(
    download: DownloadUi,
    onDismiss: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onRedownload: () -> Unit,
    onRemove: () -> Unit,
    onOpen: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.lanfetch.app.ui.components.SegmentedRing(
                    state = download.state,
                    lanes = { download.segments },
                    totalBytes = download.totalBytes,
                    downloadedBytes = download.downloadedBytes,
                    onClick = {
                        when (download.state) {
                            DownloadState.RUNNING -> onPause()
                            DownloadState.PAUSED -> onResume()
                            DownloadState.FAILED -> onRetry()
                            DownloadState.MISSING -> onRedownload()
                            DownloadState.COMPLETED -> onOpen()
                            DownloadState.QUEUED -> Unit
                        }
                    },
                    size = 96.dp,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = download.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = LanesPalette.InkPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val percent = if (download.totalBytes > 0) {
                        (download.downloadedBytes * 100 / download.totalBytes).toInt()
                    } else null
                    Text(
                        text = buildString {
                            append(Format.bytes(download.downloadedBytes))
                            if (download.totalBytes > 0) {
                                append(" of ").append(Format.bytes(download.totalBytes))
                                percent?.let { append(" · ").append(it).append("%") }
                            }
                            if (download.state == DownloadState.RUNNING && download.speedBps > 1) {
                                append(" · ").append(Format.speed(download.speedBps))
                                Format.eta(download.etaSeconds).takeIf { it.isNotEmpty() }
                                    ?.let { append(" · ").append(it).append(" left") }
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = LanesPalette.InkSecondary,
                    )
                }
            }

            if (download.segments.size > 1) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Lanes",
                    style = MaterialTheme.typography.titleSmall,
                    color = LanesPalette.InkPrimary,
                )
                Spacer(Modifier.height(8.dp))
                download.segments.forEach { segment ->
                    val fraction = if (segment.length > 0) {
                        (segment.downloaded.toDouble() / segment.length).toFloat().coerceIn(0f, 1f)
                    } else 0f
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    ) {
                        Text(
                            text = "Lane ${segment.index + 1}",
                            style = MaterialTheme.typography.bodySmall,
                            color = LanesPalette.InkSecondary,
                            modifier = Modifier.width(56.dp),
                        )
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.weight(1f),
                            color = LanesPalette.Accent,
                            trackColor = LanesPalette.SurfaceElevated,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${Format.bytes(segment.downloaded)} / ${Format.bytes(segment.length)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = LanesPalette.InkMuted,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "File: ${download.fileName}",
                style = MaterialTheme.typography.bodySmall,
                color = LanesPalette.InkSecondary,
            )
            Text(
                text = "Server: ${android.net.Uri.parse(download.url).host ?: download.url}",
                style = MaterialTheme.typography.bodySmall,
                color = LanesPalette.InkSecondary,
            )
            if (!download.savedPath.isNullOrBlank()) {
                Text(
                    text = "Saved: ${download.savedPath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = LanesPalette.InkSecondary,
                )
            }
            if (!download.error.isNullOrBlank()) {
                Text(
                    text = download.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = LanesPalette.Error,
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (download.state) {
                    DownloadState.RUNNING -> OutlinedButton(onClick = onPause) { Text("Pause") }
                    DownloadState.PAUSED -> Button(onClick = onResume) { Text("Resume") }
                    DownloadState.FAILED -> Button(onClick = onRetry) { Text("Retry") }
                    DownloadState.MISSING -> Button(onClick = onRedownload) { Text("Download again") }
                    DownloadState.COMPLETED -> Button(onClick = onOpen) { Text("Open") }
                    DownloadState.QUEUED -> Unit
                }
                OutlinedButton(onClick = onRemove) { Text("Remove") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StateBadge(state: DownloadState) {
    val (label, bg, fg) = when (state) {
        DownloadState.RUNNING -> Triple("Running", LanesPalette.AccentContainer, LanesPalette.Accent)
        DownloadState.COMPLETED -> Triple("Saved", LanesPalette.AccentContainer, LanesPalette.Accent)
        DownloadState.FAILED -> Triple("Failed", LanesPalette.ErrorContainer, LanesPalette.Error)
        DownloadState.QUEUED -> Triple("Queued", LanesPalette.SurfaceElevated, LanesPalette.InkSecondary)
        DownloadState.PAUSED -> Triple("Paused", LanesPalette.AmberContainer, LanesPalette.Amber)
        DownloadState.MISSING -> Triple("Missing", LanesPalette.ErrorContainer, LanesPalette.Error)
    }
    LanesBadge(text = label, containerColor = bg, contentColor = fg)
}

@Composable
private fun StateGlyph(state: DownloadState) {
    when (state) {
        DownloadState.COMPLETED -> Icon(
            Icons.Filled.CheckCircle,
            contentDescription = "Completed",
            modifier = Modifier.size(20.dp),
            tint = LanesPalette.Accent,
        )
        DownloadState.FAILED -> Icon(
            painterResource(R.drawable.ic_error),
            contentDescription = "Failed",
            modifier = Modifier.size(20.dp),
            tint = LanesPalette.Error,
        )
        DownloadState.RUNNING -> Icon(
            painterResource(R.drawable.ic_download),
            contentDescription = "Running",
            modifier = Modifier.size(20.dp),
            tint = LanesPalette.Accent,
        )
        else -> Icon(
            painterResource(R.drawable.ic_pause),
            contentDescription = "Paused or queued",
            modifier = Modifier.size(20.dp),
            tint = if (state == DownloadState.PAUSED) LanesPalette.Amber else LanesPalette.InkMuted,
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
                    .background(LanesPalette.SurfaceInteractive)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(LanesPalette.Accent)
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
