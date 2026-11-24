package com.lanfetch.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lanfetch.app.LanFetchApp
import com.lanfetch.app.data.CatalogItem
import com.lanfetch.app.data.ItemEntity
import com.lanfetch.app.data.Source
import com.lanfetch.app.data.SourceEntity
import com.lanfetch.app.download.DownloadUi
import com.lanfetch.app.filter.CatalogFilter
import com.lanfetch.app.filter.FilterEngine
import com.lanfetch.app.net.Scraper
import com.lanfetch.app.net.SeedSources
import com.lanfetch.app.net.SyncProgress
import com.lanfetch.app.net.SyncResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val lanApp = app as LanFetchApp
    private val db = lanApp.db
    private val scraper = Scraper()

    val sources: StateFlow<List<Source>> = db.sources().observeAll()
        .map { list -> list.map { it.toModel() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val items: StateFlow<List<CatalogItem>> = db.items().observeAll()
        .map { list -> list.map { it.toModel() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filter = MutableStateFlow(CatalogFilter())

    val filtered: StateFlow<List<CatalogItem>> = combine(items, filter) { list, f ->
        FilterEngine.apply(list, f)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val facets: StateFlow<FilterEngine.Facets> = items
        .map { FilterEngine.facets(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FilterEngine.facets(emptyList()))

    val downloads: StateFlow<List<DownloadUi>> = lanApp.downloadManager.observeUi()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings = lanApp.settingsStore.flow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.lanfetch.app.data.AppSettings())

    /** sourceId -> progress while syncing */
    val syncing = MutableStateFlow<Map<Long, SyncProgress>>(emptyMap())
    val syncMessage = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            if (db.sources().count() == 0) {
                SeedSources.defaults.forEach { db.sources().upsert(SourceEntity.from(it)) }
            }
        }
    }

    fun setFilter(f: CatalogFilter) { filter.value = f }
    fun clearFilter() { filter.value = CatalogFilter(sort = filter.value.sort) }

    fun toggleFavorite(item: CatalogItem) {
        viewModelScope.launch { db.items().setFavorite(item.id, !item.favorite) }
    }

    fun syncSource(source: Source) {
        if (syncing.value.containsKey(source.id)) return
        viewModelScope.launch {
            syncing.value = syncing.value + (source.id to SyncProgress(0, 0, source.rootUrl))
            val entity = SourceEntity.from(source)
            when (val result = scraper.syncSource(source) { p ->
                syncing.value = syncing.value + (source.id to p)
            }) {
                is SyncResult.Ok -> {
                    val favorites = db.items().forSource(source.id)
                        .filter { it.favorite }.associate { it.fileUrl to true }
                    db.items().replaceForSource(
                        source.id,
                        result.items.map { ItemEntity.from(it) },
                        favorites,
                    )
                    db.sources().update(
                        entity.copy(
                            lastSynced = System.currentTimeMillis(),
                            lastError = null,
                            itemCount = result.items.size,
                        )
                    )
                    syncMessage.value = "${source.name}: ${result.items.size} files found"
                }
                is SyncResult.Failed -> {
                    db.sources().update(entity.copy(lastError = result.message))
                    syncMessage.value = "${source.name}: ${result.message}"
                }
            }
            syncing.value = syncing.value - source.id
        }
    }

    fun syncAll() {
        viewModelScope.launch {
            sources.value.filter { it.enabled }.forEach { syncSource(it) }
        }
    }

    fun addSource(name: String, url: String) {
        viewModelScope.launch {
            val clean = if (url.endsWith("/")) url else "$url/"
            db.sources().upsert(SourceEntity(name = name.ifBlank { clean }, rootUrl = clean))
        }
    }

    fun deleteSource(source: Source) {
        viewModelScope.launch {
            db.items().deleteForSource(source.id)
            db.sources().delete(source.id)
        }
    }

    fun setSourceEnabled(source: Source, enabled: Boolean) {
        viewModelScope.launch {
            db.sources().update(SourceEntity.from(source.copy(enabled = enabled)))
        }
    }

    fun download(item: CatalogItem) {
        viewModelScope.launch { lanApp.downloadManager.enqueue(item) }
    }

    fun downloadGroup(items: List<CatalogItem>) {
        viewModelScope.launch { items.forEach { lanApp.downloadManager.enqueue(it) } }
    }

    fun pauseDownload(id: Long) = lanApp.downloadManager.pause(id)
    fun resumeDownload(id: Long) = lanApp.downloadManager.resume(id)
    fun retryDownload(id: Long) = lanApp.downloadManager.retry(id)
    fun cancelDownload(id: Long) = lanApp.downloadManager.cancelAndDelete(id)
    fun removeDownloadRecord(id: Long) = lanApp.downloadManager.removeRecord(id)
    fun pauseAllDownloads() = lanApp.downloadManager.pauseAll()

    fun setSegments(v: Int) { viewModelScope.launch { lanApp.settingsStore.setSegments(v) } }
    fun setConcurrency(v: Int) { viewModelScope.launch { lanApp.settingsStore.setConcurrency(v) } }
    fun setWifiOnly(v: Boolean) { viewModelScope.launch { lanApp.settingsStore.setWifiOnly(v) } }
}
