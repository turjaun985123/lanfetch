package com.lanfetch.app.ui.servers

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lanfetch.app.LanFetchApp
import com.lanfetch.app.data.Source
import com.lanfetch.app.net.ServerProbeResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.URI

@Immutable
data class ServerConnectionUi(
    val source: Source,
    val probe: ServerProbeResult? = null,
    val checking: Boolean = false,
)

class SourcesViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as LanFetchApp).container
    private val sources = container.sourceRepository
    private val liveClient = container.liveClient

    val all: StateFlow<List<Source>> = sources.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _probes = MutableStateFlow<Map<Long, ServerProbeResult>>(emptyMap())
    val probes: StateFlow<Map<Long, ServerProbeResult>> = _probes.asStateFlow()

    private val _probingAll = MutableStateFlow(false)
    val probingAll: StateFlow<Boolean> = _probingAll.asStateFlow()

    fun addSource(name: String, rawUrl: String): String? {
        val clean = rawUrl.trim()
        val uri = runCatching { URI(clean) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        if (scheme !in setOf("http", "https") || uri?.host.isNullOrBlank()) {
            return "Enter a valid http:// or https:// server URL"
        }
        viewModelScope.launch {
            runCatching { sources.add(name, clean) }
                .onSuccess {
                    container.message("Added ${name.trim().ifBlank { uri.host }}")
                    checkConnections(all.value)
                }
                .onFailure { container.message("Could not add server: ${it.message}") }
        }
        return null
    }

    fun deleteSource(source: Source) {
        viewModelScope.launch {
            runCatching { sources.delete(source.id) }
                .onSuccess { container.message("Removed ${source.name}") }
                .onFailure { container.message("Could not remove server: ${it.message}") }
        }
    }

    fun setSourceEnabled(source: Source, enabled: Boolean) {
        viewModelScope.launch {
            sources.setEnabled(source, enabled)
            if (enabled) checkSingle(source)
        }
    }

    fun checkSingle(source: Source) {
        viewModelScope.launch {
            val res = liveClient.probeServer(source)
            _probes.value = _probes.value + (source.id to res)
        }
    }

    fun checkConnections(list: List<Source> = all.value) {
        if (_probingAll.value) return
        val enabled = list.filter { it.enabled }
        if (enabled.isEmpty()) return
        viewModelScope.launch {
            _probingAll.value = true
            try {
                val results = coroutineScope {
                    enabled.map { src -> async { liveClient.probeServer(src) } }.awaitAll()
                }
                val map = _probes.value.toMutableMap()
                for (r in results) map[r.sourceId] = r
                _probes.value = map
            } finally {
                _probingAll.value = false
            }
        }
    }
}
