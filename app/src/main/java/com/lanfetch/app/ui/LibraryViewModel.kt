package com.lanfetch.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.lanfetch.app.LanFetchApp
import com.lanfetch.app.data.CatalogCounts
import com.lanfetch.app.data.CatalogFile
import com.lanfetch.app.data.CatalogTitle
import com.lanfetch.app.data.Facets
import com.lanfetch.app.filter.CatalogFilter
import com.lanfetch.app.filter.SortBy
import com.lanfetch.app.net.SyncProgress
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Library screen state. The catalog is paged in SQL; this ViewModel only keeps
 * the current filter, the selected title and sync progress.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, FlowPreview::class)
class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as LanFetchApp).container
    private val catalog = container.catalogRepository
    private val downloads = container.downloadCoordinator

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _filter = MutableStateFlow(CatalogFilter())
    val filter: StateFlow<CatalogFilter> = _filter.asStateFlow()

    val titles: Flow<PagingData<CatalogTitle>> = combine(
        _filter,
        _searchQuery
            .map { it.trim() }
            .distinctUntilChanged()
            .debounce { if (it.isEmpty()) 0L else 250L },
    ) { filter, query ->
        filter.copy(query = query)
    }
        .distinctUntilChanged()
        .flatMapLatest { catalog.titles(it) }
        .cachedIn(viewModelScope)

    val facets: StateFlow<Facets> = catalog.facets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Facets())

    val counts: StateFlow<CatalogCounts> = catalog.counts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CatalogCounts())

    val downloadedUrls: StateFlow<Set<String>> = catalog.completedDownloadUrls()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val syncing: StateFlow<Map<Long, SyncProgress>> = container.syncManager.progress

    private val _selectedTitleId = MutableStateFlow<Long?>(null)
    val selectedTitle: StateFlow<CatalogTitle?> = _selectedTitleId
        .flatMapLatest { id -> if (id == null) flowOf(null) else catalog.observeTitle(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val selectedFiles: StateFlow<List<CatalogFile>> = _selectedTitleId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else catalog.files(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(query: String) {
        _searchQuery.value = query
    }

    fun setFilter(filter: CatalogFilter) {
        _filter.value = filter
    }

    fun clearFilters() {
        _searchQuery.value = ""
        _filter.update { CatalogFilter(sort = it.sort) }
    }

    fun setSort(sort: SortBy) {
        _filter.update { it.copy(sort = sort) }
    }

    fun selectTitle(title: CatalogTitle) {
        _selectedTitleId.value = title.id
    }

    fun closeTitle() {
        _selectedTitleId.value = null
    }

    fun toggleFavorite(title: CatalogTitle) {
        viewModelScope.launch { catalog.toggleFavorite(title.id) }
    }

    fun download(file: CatalogFile, title: String) {
        viewModelScope.launch { downloads.enqueue(file, title) }
    }

    fun downloadAll(files: List<CatalogFile>, title: String) {
        viewModelScope.launch { downloads.enqueueAll(files, title) }
    }

    /** Card action: the largest file for a movie, every episode for a show. */
    fun downloadTitle(title: CatalogTitle) {
        viewModelScope.launch {
            val files = catalog.filesOnce(title.id)
            if (files.isEmpty()) return@launch
            if (title.kind == "series") {
                downloads.enqueueAll(files, title.title)
            } else {
                downloads.enqueue(files.maxByOrNull { it.sizeBytes } ?: files.first(), title.title)
            }
        }
    }

    fun syncAll() = container.syncManager.syncAll()
}
