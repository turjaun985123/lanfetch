package com.lanfetch.app.ui.servers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lanfetch.app.data.Source
import com.lanfetch.app.net.ServerProbeResult
import com.lanfetch.app.ui.components.TurjaunDivider
import com.lanfetch.app.ui.components.TurjaunScreenHeader
import com.lanfetch.app.ui.theme.TabularLabelSmall
import com.lanfetch.app.ui.theme.LanFetchFontFamily
import com.lanfetch.app.ui.theme.TurjaunPalette
import com.lanfetch.app.ui.theme.TurjaunRadii
import com.lanfetch.app.ui.theme.TurjaunSizes
import com.lanfetch.app.ui.theme.TurjaunSpacing

@Composable
fun SourcesScreen(vm: SourcesViewModel) {
    val sources by vm.all.collectAsState()
    val probes by vm.probes.collectAsState()
    val probingAll by vm.probingAll.collectAsState()

    var showAdd by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Source?>(null) }

    LaunchedEffect(sources.size) {
        if (sources.isNotEmpty() && probes.isEmpty()) {
            vm.checkConnections(sources)
        }
    }

    val enabledCount = sources.count { it.enabled }
    val onlineCount = sources.count { it.enabled && probes[it.id]?.reachable == true }

    Column(Modifier.fillMaxSize().background(TurjaunPalette.Canvas)) {
        TurjaunScreenHeader(
            title = {
                Text(
                    "Servers",
                    style = MaterialTheme.typography.titleLarge,
                    color = TurjaunPalette.InkPrimary,
                )
            },
            subtitle = if (probes.isNotEmpty()) "$onlineCount online • $enabledCount of ${sources.size} enabled"
            else "$enabledCount of ${sources.size} enabled",
            actions = {
                OutlinedButton(
                    onClick = { vm.checkConnections() },
                    enabled = !probingAll && enabledCount > 0,
                    shape = RoundedCornerShape(TurjaunRadii.Control),
                ) {
                    if (probingAll) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = TurjaunPalette.Accent,
                        )
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("Test")
                }
                Button(
                    onClick = { showAdd = true },
                    shape = RoundedCornerShape(TurjaunRadii.Control),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TurjaunPalette.Accent,
                        contentColor = TurjaunPalette.OnAccent,
                    ),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add")
                }
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = TurjaunSpacing.BottomListBreath),
        ) {
            items(sources, key = { it.id }, contentType = { "server_row" }) { source ->
                ServerRow(
                    source = source,
                    probe = probes[source.id],
                    onCheck = { vm.checkSingle(source) },
                    onEnabled = { vm.setSourceEnabled(source, it) },
                    onDelete = { confirmDelete = source },
                )
                TurjaunDivider()
            }
        }
    }

    val deleting = confirmDelete
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = TurjaunPalette.Surface,
            title = { Text("Remove ${deleting.name}?", color = TurjaunPalette.InkPrimary) },
            text = {
                Text(
                    "Removes this server and clears its favorites & cached entries. Downloaded files in phone storage remain untouched.",
                    color = TurjaunPalette.InkSecondary,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.deleteSource(deleting)
                        confirmDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TurjaunPalette.Error,
                        contentColor = TurjaunPalette.OnError,
                    ),
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            },
        )
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("http://") }
        var error by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showAdd = false },
            containerColor = TurjaunPalette.Surface,
            title = { Text("Add media server", color = TurjaunPalette.InkPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(TurjaunSpacing.Md)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Display name") },
                        placeholder = { Text("DHAKA-FLIX-14") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = {
                            url = it
                            error = null
                        },
                        label = { Text("Root directory URL") },
                        isError = error != null,
                        supportingText = error?.let { msg -> { Text(msg, color = TurjaunPalette.Error) } },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Compatible with h5ai, Apache mod_autoindex, and nginx HTTP directory indexes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TurjaunPalette.InkSecondary,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val validationError = vm.addSource(name, url)
                        if (validationError == null) {
                            showAdd = false
                        } else {
                            error = validationError
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TurjaunPalette.Accent,
                        contentColor = TurjaunPalette.OnAccent,
                    ),
                ) {
                    Text("Add server")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ServerRow(
    source: Source,
    probe: ServerProbeResult?,
    onCheck: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val statusDotColor = when {
        !source.enabled -> TurjaunPalette.InkMuted
        probe == null -> TurjaunPalette.InkSecondary
        probe.reachable -> TurjaunPalette.Accent
        else -> TurjaunPalette.Error
    }
    val statusLabel = when {
        !source.enabled -> "Disabled"
        probe == null -> "Checking…"
        probe.reachable -> "Online • ${probe.detail}"
        else -> probe.detail.ifBlank { "Unreachable" }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TurjaunSpacing.ScreenHorizontal, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusDotColor),
                )
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (source.enabled) TurjaunPalette.InkPrimary else TurjaunPalette.InkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = source.rootUrl,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = LanFetchFontFamily),
                color = TurjaunPalette.InkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = statusLabel,
                style = TabularLabelSmall,
                color = statusDotColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(TurjaunSpacing.Sm))

        if (source.enabled && probe != null && !probe.reachable) {
            IconButton(
                onClick = onCheck,
                modifier = Modifier.size(TurjaunSizes.MinTouchTarget),
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Retry ${source.name}",
                    tint = TurjaunPalette.InkSecondary,
                )
            }
        }

        Switch(checked = source.enabled, onCheckedChange = onEnabled)

        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(TurjaunSizes.MinTouchTarget),
        ) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Remove ${source.name}",
                tint = TurjaunPalette.InkMuted,
            )
        }
    }
}
