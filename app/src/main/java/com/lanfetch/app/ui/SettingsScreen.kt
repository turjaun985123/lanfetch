package com.lanfetch.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsState()

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Download engine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Connections per download: ${settings.segmentsPerDownload}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = settings.segmentsPerDownload.toFloat(),
                    onValueChange = { vm.setSegments(it.toInt()) },
                    valueRange = 1f..16f,
                    steps = 14,
                )
                Text(
                    "Each file is split into this many byte-range segments downloaded in parallel. More connections = faster on LAN servers, up to what the server allows.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("Simultaneous downloads: ${settings.maxConcurrent}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = settings.maxConcurrent.toFloat(),
                    onValueChange = { vm.setConcurrency(it.toInt()) },
                    valueRange = 1f..5f,
                    steps = 3,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Wi-Fi only", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Downloads pause instead of using mobile data.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = settings.wifiOnly, onCheckedChange = { vm.setWifiOnly(it) })
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("About LanFetch", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Download-only companion for LAN media servers (DhakaFlix-style directory servers). " +
                        "No streaming, no player — just a fast, resumable download engine: segmented parallel " +
                        "downloads with per-segment retry, byte-exact resume after pause or app restart, " +
                        "SHA-256 verification, and finished files saved to Downloads/LanFetch.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Version 1.1.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
