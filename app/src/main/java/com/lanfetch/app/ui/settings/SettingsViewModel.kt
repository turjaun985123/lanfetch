package com.lanfetch.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import com.lanfetch.app.LanFetchApp
import com.lanfetch.app.data.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as LanFetchApp).container
    private val store = container.settingsStore

    val settings: StateFlow<AppSettings> = store.flow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _updateCheck = MutableStateFlow(UpdateCheckState())
    val updateCheck: StateFlow<UpdateCheckState> = _updateCheck.asStateFlow()
    private val updateChecker = UpdateChecker(container.httpClient)
    private var lastUpdateCheckAt = 0L

    fun checkForUpdates(force: Boolean = false) {
        if (_updateCheck.value.status == UpdateStatus.CHECKING) return
        val now = System.currentTimeMillis()
        if (!force && _updateCheck.value.status != UpdateStatus.NOT_CHECKED &&
            now - lastUpdateCheckAt < UPDATE_CHECK_COOLDOWN_MS
        ) return

        lastUpdateCheckAt = now
        _updateCheck.value = UpdateCheckState(status = UpdateStatus.CHECKING)
        viewModelScope.launch {
            _updateCheck.value = try {
                updateChecker.check()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                UpdateCheckState(status = UpdateStatus.FAILED)
            }
        }
    }

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

    private companion object {
        const val UPDATE_CHECK_COOLDOWN_MS = 30 * 60 * 1000L
    }
}
