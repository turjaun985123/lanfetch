package com.lanfetch.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.lanfetch.app.AppContainer
import com.lanfetch.app.net.ServerProbeResult
import com.lanfetch.app.ui.theme.LanesPalette
import kotlinx.coroutines.launch

/**
 * First run (redesign spec): explain the app in one line, then check the
 * seeded servers live so "the DhakaFlix servers only work on your ISP's
 * network" is discovered here — not as an empty library later. Permissions
 * are NOT requested here; they are asked in context at the first download.
 */
@Composable
fun FirstRunCheck(container: AppContainer, onDone: () -> Unit) {
    val sources by container.sourceRepository.observeAll().collectAsState(initial = emptyList())
    val enabled = sources.filter { it.enabled }
    val probes = remember { mutableStateMapOf<Long, ServerProbeResult>() }
    var probeRound by remember { androidx.compose.runtime.mutableStateOf(0) }

    LaunchedEffect(enabled.map { it.id }, probeRound) {
        for (source in enabled) {
            launch { probes[source.id] = container.liveClient.probeServer(source) }
        }
    }

    val answered = enabled.count { probes.containsKey(it.id) }
    val reachable = enabled.count { probes[it.id]?.reachable == true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LanesPalette.Canvas)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(
            text = "lanfetch",
            style = MaterialTheme.typography.displaySmall,
            color = LanesPalette.InkPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Download from your ISP's media servers, fast.",
            style = MaterialTheme.typography.bodyLarge,
            color = LanesPalette.InkSecondary,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Checking your servers",
            style = MaterialTheme.typography.titleMedium,
            color = LanesPalette.InkPrimary,
        )
        Spacer(Modifier.height(12.dp))

        for (source in enabled) {
            val probe = probes[source.id]
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    probe == null -> CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = LanesPalette.Accent,
                    )
                    probe.reachable -> Box(
                        Modifier.size(12.dp).clip(CircleShape)
                            .background(Color(0xFF4CC380)),
                    )
                    else -> Box(
                        Modifier.size(12.dp).clip(CircleShape)
                            .background(LanesPalette.Error),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = source.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LanesPalette.InkPrimary,
                    )
                    Text(
                        text = source.rootUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = LanesPalette.InkMuted,
                    )
                }
                Text(
                    text = when {
                        probe == null -> "Checking…"
                        probe.reachable -> probe.detail.ifBlank { "${probe.latencyMs} ms" }
                        else -> "No answer"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = LanesPalette.InkSecondary,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        when {
            enabled.isEmpty() -> Text(
                text = "No servers are enabled yet. You can add one from the Servers tab.",
                style = MaterialTheme.typography.bodyMedium,
                color = LanesPalette.InkSecondary,
            )
            answered == enabled.size && reachable == 0 -> Text(
                text = "None of your servers answered. The DhakaFlix servers only work on your " +
                    "ISP's network. Connect to its Wi-Fi, then check again.",
                style = MaterialTheme.typography.bodyMedium,
                color = LanesPalette.Error,
            )
            answered == enabled.size -> Text(
                text = "$reachable of ${enabled.size} servers are ready.",
                style = MaterialTheme.typography.bodyMedium,
                color = LanesPalette.InkSecondary,
            )
            else -> Text(
                text = "Checking… $answered of ${enabled.size} answered.",
                style = MaterialTheme.typography.bodyMedium,
                color = LanesPalette.InkSecondary,
            )
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = LanesPalette.Accent,
                contentColor = LanesPalette.OnAccent,
            ),
        ) {
            Text("Start browsing")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { probeRound++ },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Check again")
        }
    }
}
