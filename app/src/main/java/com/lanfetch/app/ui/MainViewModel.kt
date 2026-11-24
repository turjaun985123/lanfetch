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
import kotlinx.coroutines.flow.flowOn
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

    // Filtering / faceting / grouping run on a background dispatcher: with a
    // 40k-item catalog, doing this on the main thread is what made the UI jank.
    val filtered: StateFlow<List<CatalogItem>> = combine(items, filter) { list, f ->
        FilterEngine.apply(list, f)
    }.flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val facets: StateFlow<FilterEngine.Facets> = items
        .map { FilterEngine.facets(it) }
        .flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FilterEngine.facets(emptyList()))

    /** One entry per title folder: representative (largest file, poster first) + all files. */
    val groups: StateFlow<List<Pair<CatalogItem, List<CatalogItem>>>> = filtered
        .map { list ->
            list.groupBy { it.folderUrl }.values.map { group ->
                val rep = group.maxWithOrNull(
                    compareBy<CatalogItem> { it.posterUrl != null }.thenBy { it.sizeBytes }
                )!!
                rep to group
            }.sortedBy { it.first.title.lowercase() }
        }
        .flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
            syncing.value = syncing.value + (source.id to SyncProgress(0, 0, 0, source.rootUrl))
            val entity = SourceEntity.from(source)
            val existing = db.items().forSource(source.id)
            val knownMtimes = db.folders().forSource(source.id)
                .mapNotNull { row -> row.modified?.let { row.url to it } }.toMap()
            val freshUrls = java.util.Collections.synchronizedSet(HashSet<String>())

            var lastProgressUi = 0L
            val result = try {
                scraper.syncSource(
                    source = source,
                    selectedCategories = source.selectedCategories,
                    knownMtimes = knownMtimes,
                    onProgress = { p ->
                        val t = System.currentTimeMillis()
                        if (t - lastProgressUi >= 400) {
                            lastProgressUi = t
                            syncing.value = syncing.value + (source.id to p)
                        }
                    },
                    onBatch = { batch ->
                        freshUrls += batch.map { it.fileUrl }
                        db.items().insertAll(batch.map { ItemEntity.from(it) })
                    },
                )
            } catch (e: Exception) {
                com.lanfetch.app.net.SyncResult.Failed(e.message ?: "Sync failed")
            }

            when (result) {
                is SyncResult.Ok -> {
                    // Keep rows under pruned (unchanged) folders; drop rows the
                    // server no longer has.
                    val kept = existing.filter { item ->
                        result.prunedFolders.any { item.folderUrl.startsWith(it) }
                    }
                    val keepUrls = freshUrls + kept.map { it.fileUrl }
                    val staleIds = existing.filter { it.fileUrl !in keepUrls }.map { it.id }
                    staleIds.chunked(500).forEach { db.items().deleteByIds(it) }

                    // Folder index: fresh scans + pruned folders keep old stamps.
                    val merged = HashMap<String, String?>()
                    result.prunedFolders.forEach { merged[it] = knownMtimes[it] }
                    merged.putAll(result.folderMtimes)
                    db.folders().replaceForSource(
                        source.id,
                        merged.map { (url, mod) -> com.lanfetch.app.data.FolderIndexEntity(source.id, url, mod) },
                    )

                    val total = keepUrls.size
                    db.sources().update(
                        entity.copy(
                            lastSynced = System.currentTimeMillis(),
                            lastError = null,
                            itemCount = total,
                        )
                    )
                    syncMessage.value =
                        "${source.name}: $total files (${result.prunedFolders.size} folders skipped as unchanged)"
                }
                is SyncResult.Failed -> {
                    db.sources().update(entity.copy(lastError = result.message))
                    syncMessage.value = "${source.name}: ${result.message}"
                }
            }
            syncing.value = syncing.value - source.id
        }
    }

    /** Category picker state: source id -> (all categories, loading flag data) */
    val categoryPicker = MutableStateFlow<Pair<Source, List<String>>?>(null)
    val categoryPickerLoading = MutableStateFlow(false)

    fun openCategoryPicker(source: Source) {
        viewModelScope.launch {
            categoryPickerLoading.value = true
            try {
                val cats = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    scraper.fetchCategories(source)
                }
                categoryPicker.value = source to cats
            } catch (e: Exception) {
                syncMessage.value = "${source.name}: could not list categories (${e.message})"
            } finally {
                categoryPickerLoading.value = false
            }
        }
    }

    fun closeCategoryPicker() { categoryPicker.value = null }

    fun saveCategories(source: Source, selected: Set<String>) {
        viewModelScope.launch {
            db.sources().update(SourceEntity.from(source.copy(selectedCategories = selected)))
            categoryPicker.value = null
            syncMessage.value = if (selected.isEmpty()) {
                "${source.name}: syncing all categories"
            } else {
                "${source.name}: syncing ${selected.size} categories"
            }
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
