package com.lanfetch.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.res.painterResource
import com.lanfetch.app.R
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lanfetch.app.data.Source
import com.lanfetch.app.net.SyncProgress
import java.text.DateFormat
import java.util.Date

@Composable
fun SourcesScreen(vm: SourcesViewModel) {
    val sources by vm.all.collectAsState()
    val syncing by vm.syncing.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Source?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Servers", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Directory servers on your LAN. Sync crawls them and builds your catalog.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = { vm.syncAll() }, enabled = syncing.isEmpty()) {
                Icon(painterResource(R.drawable.ic_sync), contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("Sync all")
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("Add")
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(sources, key = { it.id }) { source ->
                SourceCard(
                    source = source,
                    syncProgress = syncing[source.id],
                    onSync = { vm.syncSource(source) },
                    onCancelSync = { vm.cancelSync(source) },
                    onDelete = { confirmDelete = source },
                    onEnabled = { vm.setSourceEnabled(source, it) },
                    onCategories = { vm.openCategoryPicker(source) },
                )
            }
        }
    }

    val pickerLoading by vm.pickerLoading.collectAsState()
    if (pickerLoading) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Loading categories…") },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = { },
        )
    }

    val picker by vm.picker.collectAsState()
    val pickerData = picker
    if (pickerData != null) {
        var selected by remember(pickerData.source.id) {
            mutableStateOf(
                if (pickerData.source.selectedCategories.isEmpty()) pickerData.categories.toSet()
                else pickerData.source.selectedCategories
            )
        }
        AlertDialog(
            onDismissRequest = { vm.closeCategoryPicker() },
            title = { Text("Categories — ${pickerData.source.name}") },
            text = {
                Column {
                    Text(
                        "Only selected categories are crawled. Untick the ones you never watch — this is the biggest sync speedup.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { selected = pickerData.categories.toSet() }) { Text("All") }
                        OutlinedButton(onClick = { selected = emptySet() }) { Text("None") }
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
                        items(pickerData.categories) { category ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    selected = if (category in selected) selected - category
                                    else selected + category
                                }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = category in selected,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + category else selected - category
                                    },
                                )
                                Text(category, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    vm.saveCategories(pickerData.source, selected, pickerData.categories)
                }) { Text("Save (${selected.size})") }
            },
            dismissButton = { TextButton(onClick = { vm.closeCategoryPicker() }) { Text("Cancel") } },
        )
    }

    val deleting = confirmDelete
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Remove ${deleting.name}?") },
            text = { Text("This deletes the server and the catalog entries it contributed. Downloaded files are kept.") },
            confirmButton = {
                Button(onClick = {
                    vm.deleteSource(deleting)
                    confirmDelete = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("http://") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add directory server") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Name") }, singleLine = true,
                    )
                    OutlinedTextField(
                        value = url, onValueChange = { url = it },
                        label = { Text("Root URL (ends with /)") }, singleLine = true,
                    )
                    Text(
                        "Any HTTP server with directory listing enabled (Apache / nginx autoindex).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.addSource(name, url.trim())
                        showAdd = false
                    },
                    enabled = url.startsWith("http"),
                ) { Text("Add server") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SourceCard(
    source: Source,
    syncProgress: SyncProgress?,
    onSync: () -> Unit,
    onCancelSync: () -> Unit,
    onDelete: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onCategories: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(source.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        source.rootUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = source.enabled, onCheckedChange = onEnabled)
            }
            Text(
                when {
                    source.itemCount > 0 && source.lastSynced > 0 ->
                        "${source.itemCount} files • last synced " +
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(Date(source.lastSynced))
                    source.lastSynced > 0 -> "Synced, no video files found"
                    else -> "Never synced"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (source.selectedCategories.isNotEmpty()) {
                Text(
                    "Syncing ${source.selectedCategories.size} selected categories: " +
                        source.selectedCategories.take(3).joinToString(", ") +
                        if (source.selectedCategories.size > 3) "…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (!source.lastError.isNullOrBlank()) {
                Text(source.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (syncProgress != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    "Scanning… ${syncProgress.foldersScanned} folders, ${syncProgress.itemsFound} files",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (syncProgress == null) {
                    Button(onClick = onSync, enabled = source.enabled) {
                        Icon(painterResource(R.drawable.ic_sync), contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text("Sync")
                    }
                } else {
                    OutlinedButton(onClick = onCancelSync) {
                        Icon(painterResource(R.drawable.ic_cancel), contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text("Cancel sync")
                    }
                }
                OutlinedButton(onClick = onCategories) { Text("Categories") }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove server")
                }
            }
        }
    }
}
