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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lanfetch.app.BuildConfig
import com.lanfetch.app.R
import com.lanfetch.app.data.AppSettings
import com.lanfetch.app.ui.components.LanesBadge
import com.lanfetch.app.ui.components.LanesDivider
import com.lanfetch.app.ui.components.LanesScreenHeader
import com.lanfetch.app.ui.components.LanesSectionLabel
import com.lanfetch.app.ui.theme.TabularLabelSmall
import com.lanfetch.app.ui.theme.LanesPalette
import com.lanfetch.app.ui.theme.LanesRadii
import com.lanfetch.app.ui.theme.LanesSpacing
import com.lanfetch.app.ui.theme.ThemeMode

@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val settings: AppSettings by vm.settings.collectAsState()
    val updateCheck by vm.updateCheck.collectAsState()
    val releaseUrl = updateCheck.releaseUrl
    val uriHandler = LocalUriHandler.current
    var segmentsDraft by remember(settings.segmentsPerDownload) { mutableFloatStateOf(settings.segmentsPerDownload.toFloat()) }
    var concurrencyDraft by remember(settings.maxConcurrent) { mutableFloatStateOf(settings.maxConcurrent.toFloat()) }

    LaunchedEffect(vm) { vm.checkForUpdates() }

    Column(Modifier.fillMaxSize().background(LanesPalette.Canvas)) {
        LanesScreenHeader(
            title = {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.titleLarge,
                    color = LanesPalette.InkPrimary,
                )
            },
            subtitle = "Transfers, storage & cache configuration",
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = LanesSpacing.BottomListBreath),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = LanesSpacing.ScreenHorizontal, vertical = LanesSpacing.Base),
                verticalArrangement = Arrangement.spacedBy(LanesSpacing.Md),
            ) {
                LanesSectionLabel("Transfer engine")

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Connections per download",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LanesPalette.InkPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        LanesBadge(
                            text = "${settings.segmentsPerDownload}",
                            containerColor = LanesPalette.AccentContainer,
                            contentColor = LanesPalette.Accent,
                        )
                    }
                    Slider(
                        value = segmentsDraft,
                        onValueChange = { segmentsDraft = it },
                        onValueChangeFinished = { vm.setSegments(segmentsDraft.toInt()) },
                        valueRange = 1f..16f,
                        steps = 14,
                    )
                    Text(
                        "Splits supported files into parallel HTTP byte ranges to maximize LAN Wi-Fi speed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LanesPalette.InkSecondary,
                    )
                }

                LanesDivider(subtle = true)

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Simultaneous downloads",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LanesPalette.InkPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        LanesBadge(
                            text = "${settings.maxConcurrent}",
                            containerColor = LanesPalette.AccentContainer,
                            contentColor = LanesPalette.Accent,
                        )
                    }
                    Slider(
                        value = concurrencyDraft,
                        onValueChange = { concurrencyDraft = it },
                        onValueChangeFinished = { vm.setConcurrency(concurrencyDraft.toInt()) },
                        valueRange = 1f..5f,
                        steps = 3,
                    )
                }

                LanesDivider(subtle = true)

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Wi-Fi / LAN only",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LanesPalette.InkPrimary,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Automatically pause transfers on cellular data and resume when Wi-Fi returns.",
                            style = MaterialTheme.typography.bodySmall,
                            color = LanesPalette.InkSecondary,
                        )
                    }
                    Spacer(Modifier.width(LanesSpacing.Md))
                    Switch(checked = settings.wifiOnly, onCheckedChange = vm::setWifiOnly)
                }
            }

            LanesDivider()

            Column(
                modifier = Modifier.padding(horizontal = LanesSpacing.ScreenHorizontal, vertical = LanesSpacing.Base),
                verticalArrangement = Arrangement.spacedBy(LanesSpacing.Md),
            ) {
                LanesSectionLabel("Appearance")
                ChoiceStrip(
                    options = ThemeMode.entries.map { it.name to it.name.lowercase().replaceFirstChar(Char::uppercase) },
                    selected = settings.themeMode,
                    onSelect = vm::setThemeMode,
                )
                LanesSectionLabel("Language")
                ChoiceStrip(
                    options = listOf("system" to "System", "en" to "English", "bn" to "বাংলা"),
                    selected = settings.language,
                    onSelect = vm::setLanguage,
                )
            }

            LanesDivider()

            Column(
                modifier = Modifier.padding(horizontal = LanesSpacing.ScreenHorizontal, vertical = LanesSpacing.Base),
                verticalArrangement = Arrangement.spacedBy(LanesSpacing.Md),
            ) {
                LanesSectionLabel("Storage & cache")

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Save destination",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LanesPalette.InkPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "Downloads/LanFetch",
                        style = TabularLabelSmall,
                        color = LanesPalette.Accent,
                    )
                }

                Text(
                    "Directory listings, feed snapshots, and poster thumbnails are cached locally so startup and tab switching stay fast.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LanesPalette.InkSecondary,
                )

                OutlinedButton(
                    onClick = vm::clearCaches,
                    shape = RoundedCornerShape(LanesRadii.Control),
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    Text("Clear cached feeds & posters")
                }
            }

            LanesDivider()

            Column(
                modifier = Modifier.padding(horizontal = LanesSpacing.ScreenHorizontal, vertical = LanesSpacing.Base),
                verticalArrangement = Arrangement.spacedBy(LanesSpacing.Sm),
            ) {
                LanesSectionLabel("About")

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "LanFetch",
                        style = MaterialTheme.typography.titleMedium,
                        color = LanesPalette.InkPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    LanesBadge(text = "v1.3.0")
                }

                Text(
                    "High-speed HTTP LAN media browser and resumable multi-segment downloader supporting Android 6 through Android 15+.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LanesPalette.InkSecondary,
                )

                Text(
                    stringResource(R.string.made_by_turjaun),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LanesPalette.InkPrimary,
                )

                LanesDivider(subtle = true)

                Text(
                    stringResource(R.string.update_check_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LanesPalette.InkPrimary,
                )
                val updateMessage = when (updateCheck.status) {
                    UpdateStatus.NOT_CHECKED -> stringResource(R.string.check_for_updates)
                    UpdateStatus.CHECKING -> stringResource(R.string.checking_updates)
                    UpdateStatus.UP_TO_DATE -> stringResource(
                        R.string.app_up_to_date,
                        updateCheck.latestVersion ?: "v${BuildConfig.VERSION_NAME}",
                    )
                    UpdateStatus.AVAILABLE -> stringResource(
                        R.string.update_available,
                        updateCheck.latestVersion ?: "",
                    )
                    UpdateStatus.FAILED -> stringResource(R.string.update_check_failed)
                }
                Text(
                    updateMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (updateCheck.status == UpdateStatus.AVAILABLE) {
                        LanesPalette.Accent
                    } else {
                        LanesPalette.InkSecondary
                    },
                )
                OutlinedButton(
                    onClick = { vm.checkForUpdates(force = true) },
                    enabled = updateCheck.status != UpdateStatus.CHECKING,
                    shape = RoundedCornerShape(LanesRadii.Control),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.check_for_updates))
                }
                if (updateCheck.status == UpdateStatus.AVAILABLE && releaseUrl != null) {
                    OutlinedButton(
                        onClick = { uriHandler.openUri(releaseUrl) },
                        shape = RoundedCornerShape(LanesRadii.Control),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.open_release))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceStrip(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            Surface(
                onClick = { onSelect(value) },
                shape = RoundedCornerShape(999.dp),
                color = if (selected == value) LanesPalette.AccentContainer else LanesPalette.SurfaceElevated,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected == value) LanesPalette.Accent else LanesPalette.InkSecondary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                )
            }
        }
    }
}
