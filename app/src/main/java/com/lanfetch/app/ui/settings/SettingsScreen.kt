package com.lanfetch.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.lanfetch.app.ui.components.TurjaunBadge
import com.lanfetch.app.ui.components.TurjaunDivider
import com.lanfetch.app.ui.components.TurjaunScreenHeader
import com.lanfetch.app.ui.components.TurjaunSectionLabel
import com.lanfetch.app.ui.theme.TabularLabelSmall
import com.lanfetch.app.ui.theme.TurjaunPalette
import com.lanfetch.app.ui.theme.TurjaunRadii
import com.lanfetch.app.ui.theme.TurjaunSpacing

@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val settings: AppSettings by vm.settings.collectAsState()

    Column(Modifier.fillMaxSize().background(TurjaunPalette.Canvas)) {
        TurjaunScreenHeader(
            title = {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.titleLarge,
                    color = TurjaunPalette.InkPrimary,
                )
            },
            subtitle = "Transfers, storage & cache configuration",
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = TurjaunSpacing.BottomListBreath),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = TurjaunSpacing.ScreenHorizontal, vertical = TurjaunSpacing.Base),
                verticalArrangement = Arrangement.spacedBy(TurjaunSpacing.Md),
            ) {
                TurjaunSectionLabel("Transfer engine")

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Connections per download",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TurjaunPalette.InkPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        TurjaunBadge(
                            text = "${settings.segmentsPerDownload}",
                            containerColor = TurjaunPalette.AccentContainer,
                            contentColor = TurjaunPalette.Accent,
                        )
                    }
                    Slider(
                        value = settings.segmentsPerDownload.toFloat(),
                        onValueChange = { vm.setSegments(it.toInt()) },
                        valueRange = 1f..16f,
                        steps = 14,
                    )
                    Text(
                        "Splits supported files into parallel HTTP byte ranges to maximize LAN Wi-Fi speed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TurjaunPalette.InkSecondary,
                    )
                }

                TurjaunDivider(subtle = true)

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Simultaneous downloads",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TurjaunPalette.InkPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        TurjaunBadge(
                            text = "${settings.maxConcurrent}",
                            containerColor = TurjaunPalette.AccentContainer,
                            contentColor = TurjaunPalette.Accent,
                        )
                    }
                    Slider(
                        value = settings.maxConcurrent.toFloat(),
                        onValueChange = { vm.setConcurrency(it.toInt()) },
                        valueRange = 1f..5f,
                        steps = 3,
                    )
                }

                TurjaunDivider(subtle = true)

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Wi-Fi / LAN only",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TurjaunPalette.InkPrimary,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Automatically pause transfers on cellular data and resume when Wi-Fi returns.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TurjaunPalette.InkSecondary,
                        )
                    }
                    Spacer(Modifier.width(TurjaunSpacing.Md))
                    Switch(checked = settings.wifiOnly, onCheckedChange = vm::setWifiOnly)
                }
            }

            TurjaunDivider()

            Column(
                modifier = Modifier.padding(horizontal = TurjaunSpacing.ScreenHorizontal, vertical = TurjaunSpacing.Base),
                verticalArrangement = Arrangement.spacedBy(TurjaunSpacing.Md),
            ) {
                TurjaunSectionLabel("Storage & cache")

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Save destination",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TurjaunPalette.InkPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "Downloads/LanFetch",
                        style = TabularLabelSmall,
                        color = TurjaunPalette.Accent,
                    )
                }

                Text(
                    "Directory listings, feed snapshots, and poster thumbnails are cached locally so startup and tab switching stay fast.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TurjaunPalette.InkSecondary,
                )

                OutlinedButton(
                    onClick = vm::clearCaches,
                    shape = RoundedCornerShape(TurjaunRadii.Control),
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    Text("Clear cached feeds & posters")
                }
            }

            TurjaunDivider()

            Column(
                modifier = Modifier.padding(horizontal = TurjaunSpacing.ScreenHorizontal, vertical = TurjaunSpacing.Base),
                verticalArrangement = Arrangement.spacedBy(TurjaunSpacing.Sm),
            ) {
                TurjaunSectionLabel("About")

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "LanFetch",
                        style = MaterialTheme.typography.titleMedium,
                        color = TurjaunPalette.InkPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    TurjaunBadge(text = "v1.3.0")
                }

                Text(
                    "High-speed HTTP LAN media browser and resumable multi-segment downloader supporting Android 6 through Android 15+.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TurjaunPalette.InkSecondary,
                )
            }
        }
    }
}
