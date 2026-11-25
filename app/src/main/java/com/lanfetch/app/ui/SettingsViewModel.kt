package com.lanfetch.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lanfetch.app.LanFetchApp
import com.lanfetch.app.data.AppSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val store = (app as LanFetchApp).container.settingsStore

    val settings: StateFlow<AppSettings> = store.flow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setSegments(value: Int) {
        viewModelScope.launch { store.setSegments(value) }
    }

    fun setConcurrency(value: Int) {
        viewModelScope.launch { store.setConcurrency(value) }
    }

    fun setWifiOnly(value: Boolean) {
        viewModelScope.launch { store.setWifiOnly(value) }
    }
}
