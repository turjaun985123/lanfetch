package com.lanfetch.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import com.lanfetch.app.LanFetchApp
import com.lanfetch.app.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as LanFetchApp).container
    private val store = container.settingsStore

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

    fun setThemeMode(value: String) {
        viewModelScope.launch { store.setThemeMode(value) }
    }

    fun setLanguage(value: String) {
        viewModelScope.launch { store.setLanguage(value) }
    }

    @OptIn(ExperimentalCoilApi::class)
    fun clearCaches() {
        viewModelScope.launch(Dispatchers.IO) {
            container.liveClient.clearAllCaches()
            container.catalogRepository.clearAllLiveCache()
            val loader = getApplication<Application>().imageLoader
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
            container.notifyCacheCleared()
            container.message("Cleared live feed & poster cache")
        }
    }
}
