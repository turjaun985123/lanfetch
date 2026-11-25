package com.lanfetch.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lanfetch.app.LanFetchApp
import com.lanfetch.app.data.Source
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SourcesViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as LanFetchApp).container
    private val sources = container.sourceRepository

    val all: StateFlow<List<Source>> = sources.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun addSource(name: String, url: String) {
        viewModelScope.launch {
            runCatching { sources.add(name, url) }
                .onFailure { container.message("Could not add server: ${it.message}") }
        }
    }

    fun deleteSource(source: Source) {
        viewModelScope.launch {
            runCatching { sources.delete(source.id) }
                .onFailure { container.message("Could not remove server: ${it.message}") }
        }
    }

    fun setSourceEnabled(source: Source, enabled: Boolean) {
        viewModelScope.launch { sources.setEnabled(source, enabled) }
    }
}
