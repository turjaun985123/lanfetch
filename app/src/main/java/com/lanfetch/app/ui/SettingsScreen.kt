package com.lanfetch.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lanfetch.app.data.AppSettings

@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val settings: AppSettings by vm.settings.collectAsState()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Settings",
            subtitle = "Download engine & app preferences",
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Grid.PageHorizontal),
            verticalArrangement = Arrangement.spacedBy(Grid.SectionGap),
        ) {
            SurfaceCard {
                Column(
                    Modifier.padding(Grid.CardPadding),
                    verticalArrangement = Arrangement.spacedBy(Grid.ItemGap),
                ) {
                    Text("Download engine", style = MaterialTheme.typography.titleMedium)
                    GridRuleDivider()

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Connections per download",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            BadgePill(
                                text = "${settings.segmentsPerDownload}",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Slider(
                            value = settings.segmentsPerDownload.toFloat(),
                            onValueChange = { vm.setSegments(it.toInt()) },
                            valueRange = 1f..16f,
                            steps = 14,
                        )
                        Text(
                            "Each file is split into parallel byte-range segments for faster LAN transfers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    GridRuleDivider()

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Simultaneous downloads",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            BadgePill(
                                text = "${settings.maxConcurrent}",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Slider(
                            value = settings.maxConcurrent.toFloat(),
                            onValueChange = { vm.setConcurrency(it.toInt()) },
                            valueRange = 1f..5f,
                            steps = 3,
                        )
                    }

                    GridRuleDivider()

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Wi-Fi only", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Pause downloads on cellular and resume automatically on Wi-Fi.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(Grid.ItemGap))
                        Switch(checked = settings.wifiOnly, onCheckedChange = { vm.setWifiOnly(it) })
                    }
                }
            }

            SurfaceCard {
                Column(
                    Modifier.padding(Grid.CardPadding),
                    verticalArrangement = Arrangement.spacedBy(Grid.ItemGap),
                ) {
                    Text("Live cache", style = MaterialTheme.typography.titleMedium)
                    GridRuleDivider()
                    Text(
                        "Feed pages, category lists, directory listings, and movie posters are cached " +
                            "automatically for instant opening and fast category switching.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { vm.clearCaches() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Clear cached feeds & posters")
                    }
                }
            }

            SurfaceCard {
                Column(
                    Modifier.padding(Grid.CardPadding),
                    verticalArrangement = Arrangement.spacedBy(Grid.ItemGap),
                ) {
                    Text("About LanFetch", style = MaterialTheme.typography.titleMedium)
                    GridRuleDivider()
                    Text(
                        "Download-only companion for LAN HTTP directory servers. Segmented parallel transfers, " +
                            "byte-exact resume across restarts, and direct writes into Downloads/LanFetch.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    GridRuleDivider()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Version",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        BadgePill(
                            text = "1.3.0",
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
