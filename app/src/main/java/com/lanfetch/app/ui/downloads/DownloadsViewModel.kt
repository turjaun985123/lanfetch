package com.lanfetch.app.ui.downloads

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lanfetch.app.LanFetchApp
import com.lanfetch.app.download.DownloadUi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class DownloadsViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as LanFetchApp).container
    private val coordinator = container.downloadCoordinator

    val downloads: StateFlow<List<DownloadUi>> = coordinator.observeUi()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeCount: StateFlow<Int> = coordinator.activeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun pause(id: Long) = coordinator.pause(id)

    fun resume(id: Long) = coordinator.resume(id)

    fun retry(id: Long) = coordinator.retry(id)

    fun cancel(id: Long) = coordinator.cancelAndDelete(id)

    fun remove(id: Long) = coordinator.removeRecord(id)

    fun pauseAll() = coordinator.pauseAll()

    /** Audit B6: flip COMPLETED rows whose file was deleted to MISSING. */
    fun checkMissing() = coordinator.refreshMissingStates()

    fun redownload(id: Long) = coordinator.redownload(id)

    fun notifyMessage(text: String) = container.message(text)
}
