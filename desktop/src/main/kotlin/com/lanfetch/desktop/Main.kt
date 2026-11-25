package com.lanfetch.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.lanfetch.app.data.Source
import com.lanfetch.app.download.DownloadState
import com.lanfetch.app.download.Format
import com.lanfetch.app.net.CleartextHostPolicy
import com.lanfetch.app.net.DirEntry
import com.lanfetch.app.net.LiveServerClient
import com.lanfetch.app.net.SeedSources
import com.lanfetch.app.net.ServerProbeResult
import com.lanfetch.app.net.defaultHttpClient
import kotlinx.coroutines.launch
import okhttp3.Dispatcher
import java.io.File

private val Accent = Color(0xFF2B39F0)
private val Paddy = Color(0xFF4CC380)
private val Lal = Color(0xFFE5534B)

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "LanFetch",
        state = rememberWindowState(width = 1120.dp, height = 760.dp),
    ) {
        MaterialTheme(colorScheme = darkColorScheme(primary = Accent)) {
            Surface(Modifier.fillMaxSize()) {
                LanFetchDesktop()
            }
        }
    }
}

@Composable
private fun LanFetchDesktop() {
    val scope = rememberCoroutineScope()

    val servers = remember {
        SeedSources.defaults.mapIndexed { i, s -> s.copy(id = (i + 1).toLong()) }
    }
    val clients = remember {
        val policy = CleartextHostPolicy()
        policy.updateSources(servers.map { it.rootUrl })
        val browse = defaultHttpClient(policy)
        val transfer = browse.newBuilder()
            .dispatcher(Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 32
            })
            .build()
        browse to transfer
    }
    val liveClient = remember { LiveServerClient(clients.first) }
    val downloadDir = remember {
        File(System.getProperty("user.home"), "Downloads/LanFetch").apply { mkdirs() }
    }
    val manager = remember {
        DesktopDownloadManager(
            transferClient = clients.second,
            downloadDir = downloadDir,
            stateFile = File(
                System.getProperty("user.home"),
                ".local/share/lanfetch/downloads.tsv",
            ),
        )
    }
    val downloads by manager.downloads.collectAsState()

    val probes = remember { mutableStateMapOf<Long, ServerProbeResult>() }
    var selected by remember { mutableStateOf<Source?>(null) }
    var pathStack by remember { mutableStateOf<List<String>>(emptyList()) }
    var entries by remember { mutableStateOf<List<DirEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun browse(url: String) {
        scope.launch {
            loading = true
            error = null
            try {
                entries = liveClient.fetchListing(url, selected?.id, forceRefresh = true)
            } catch (e: Exception) {
                entries = emptyList()
                error = e.message ?: "Could not load this folder"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        for (server in servers) {
            launch { probes[server.id] = liveClient.probeServer(server) }
        }
    }

    Row(Modifier.fillMaxSize()) {
        // ---- Servers
        Column(Modifier.width(250.dp).fillMaxHeight().padding(12.dp)) {
            Text("Servers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            for (server in servers) {
                val probe = probes[server.id]
                val isSel = selected?.id == server.id
                Surface(
                    color = if (isSel) Accent.copy(alpha = 0.18f) else Color.Transparent,
                    modifier = Modifier.fillMaxWidth().clickable {
                        selected = server
                        pathStack = listOf(server.rootUrl)
                        browse(server.rootUrl)
                    },
                ) {
                    Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = when {
                                probe == null -> "…"
                                probe.reachable -> "●"
                                else -> "●"
                            },
                            color = when {
                                probe == null -> Color.Gray
                                probe.reachable -> Paddy
                                else -> Lal
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(server.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = probe?.detail ?: "Checking…",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Downloads go to\n${downloadDir.absolutePath}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
        }

        Divider(Modifier.fillMaxHeight().width(1.dp))

        // ---- Browse + downloads
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        if (pathStack.size > 1) {
                            pathStack = pathStack.dropLast(1)
                            browse(pathStack.last())
                        }
                    },
                    enabled = pathStack.size > 1,
                ) { Text("Back") }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = pathStack.lastOrNull()?.removePrefix("http://") ?: "Pick a server",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (loading) CircularProgressIndicator(Modifier.height(20.dp))
            }
            Divider()

            if (error != null) {
                Text(
                    error!!,
                    color = Lal,
                    modifier = Modifier.padding(12.dp),
                )
            }

            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(entries) { entry ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = entry.isDir) {
                                pathStack = pathStack + entry.url
                                browse(entry.url)
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (entry.isDir) entry.name + "/" else entry.name,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (entry.isDir) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        if (entry.isDir) {
                            Text("Open", color = Accent)
                        } else {
                            Text(
                                text = if (entry.sizeBytes >= 0) Format.bytes(entry.sizeBytes) else "",
                                color = Color.Gray,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.width(10.dp))
                            Button(onClick = { manager.enqueue(entry.url, entry.name) }) {
                                Text("Download")
                            }
                        }
                    }
                    Divider()
                }
            }

            Divider()
            Text(
                "Downloads",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(12.dp, 10.dp, 12.dp, 4.dp),
            )
            LazyColumn(Modifier.height(190.dp).fillMaxWidth()) {
                items(downloads, key = { it.id }) { d ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                d.fileName,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            when (d.state) {
                                DownloadState.RUNNING -> OutlinedButton(onClick = { manager.pause(d.id) }) { Text("Pause") }
                                DownloadState.PAUSED, DownloadState.FAILED ->
                                    Button(onClick = { manager.resume(d.id) }) { Text("Resume") }
                                DownloadState.COMPLETED ->
                                    OutlinedButton(onClick = {
                                        d.savedPath?.let { path ->
                                            runCatching {
                                                java.awt.Desktop.getDesktop()
                                                    .open(File(path).parentFile)
                                            }
                                        }
                                    }) { Text("Open folder") }
                                else -> Unit
                            }
                            Spacer(Modifier.width(6.dp))
                            OutlinedButton(onClick = { manager.remove(d.id) }) { Text("Remove") }
                        }
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { d.fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = buildString {
                                append(Format.bytes(d.downloadedBytes))
                                if (d.totalBytes > 0) append(" / ").append(Format.bytes(d.totalBytes))
                                append(" · ").append(d.state.name.lowercase())
                                if (d.state == DownloadState.RUNNING && d.speedBps > 1) {
                                    append(" · ").append(Format.speed(d.speedBps))
                                }
                                d.error?.let { append(" · ").append(it) }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                        )
                    }
                    Divider()
                }
            }
        }
    }
}
