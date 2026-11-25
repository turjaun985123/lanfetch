package com.lanfetch.app.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lanfetch.app.data.Source

@Composable
fun SourcesScreen(vm: SourcesViewModel) {
    val sources by vm.all.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Source?>(null) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Servers",
            subtitle = "${sources.count { it.enabled }} of ${sources.size} enabled • HTTP directory servers",
            action = {
                Button(
                    onClick = { showAdd = true },
                    shape = RoundedCornerShape(Grid.RadiusControl),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add")
                }
            },
        )

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(Grid.PageHorizontal),
            verticalArrangement = Arrangement.spacedBy(Grid.ItemGap),
        ) {
            items(sources, key = { it.id }) { source ->
                SourceCard(
                    source = source,
                    onDelete = { confirmDelete = source },
                    onEnabled = { vm.setSourceEnabled(source, it) },
                )
            }
        }
    }

    val deleting = confirmDelete
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Remove ${deleting.name}?") },
            text = { Text("This deletes the server and any catalog entries it contributed. Downloaded files are kept.") },
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
                Column(verticalArrangement = Arrangement.spacedBy(Grid.ItemGap)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Root URL (ends with /)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
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
    onDelete: () -> Unit,
    onEnabled: (Boolean) -> Unit,
) {
    SurfaceCard {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(Grid.CardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    source.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    source.rootUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(Grid.ItemGap))
            Switch(checked = source.enabled, onCheckedChange = onEnabled)
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Remove server",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
