package com.lanfetch.app.ui.library

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lanfetch.app.LanFetchApp
import com.lanfetch.app.data.CatalogFile
import com.lanfetch.app.data.LiveCategory
import com.lanfetch.app.data.LiveTitle
import com.lanfetch.app.data.Source
import com.lanfetch.app.filter.CatalogFilter
import com.lanfetch.app.filter.Facets
import com.lanfetch.app.filter.SortBy
import com.lanfetch.app.filter.TitleFilterEngine
import com.lanfetch.app.net.CrawlRules
import com.lanfetch.app.net.LiveFeedSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

@Immutable
data class CategoryState(
    val categories: List<LiveCategory> = emptyList(),
    val discovering: Boolean = true,
    val fromCache: Boolean = false,
)

@Immutable
data class DetailSheetState(
    val title: LiveTitle? = null,
    val files: List<CatalogFile> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

private data class FeedKey(
    val selectedCategory: String?,
    val kindFilter: String?,
)

private data class CachedFeedMemoryState(
    val items: List<LiveTitle>,
    val session: LiveFeedSession?,
    val hasMore: Boolean,
    val cachedAt: Long,
    val fromCache: Boolean,
)

private data class FeedTriggerKey(
    val signature: String,
    val selectedCategory: String?,
    val kindFilter: String?,
    val refreshToken: Int,
)

/**
 * Live YouTube-style library controller backed by 3-tier caching:
 * 1. Persistent Room stale-while-revalidate caches (`cached_categories`, `cached_feed_items`)
 * 2. Per-tab in-memory `LiveFeedSession` cursor cache
 * 3. Bounded TTL caches inside [com.lanfetch.app.net.LiveServerClient]
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as LanFetchApp).container
    private val liveClient = container.liveClient
    private val catalog = container.catalogRepository
    private val sourceRepo = container.sourceRepository
    private val downloads = container.downloadCoordinator

    private val _refreshToken = MutableStateFlow(0)
    private var handledRefreshToken = 0

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _submittedQuery = MutableStateFlow<String?>(null)

    private val _selectedCategory = MutableStateFlow<String?>(null)
    val selectedCategory: StateFlow<String?> = _selectedCategory.asStateFlow()

    private val _filter = MutableStateFlow(CatalogFilter(sort = SortBy.NEWEST))
    val filter: StateFlow<CatalogFilter> = _filter.asStateFlow()

    private val sourcesOrNull: StateFlow<List<Source>?> = sourceRepo.observeAll()
        .map<List<Source>, List<Source>?> { rows -> rows.filter { it.enabled } }
        .distinctUntilChanged { old, new ->
            old?.map { Triple(it.id, it.rootUrl, it.name) } == new?.map { Triple(it.id, it.rootUrl, it.name) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val enabledSources: StateFlow<List<Source>> = sourcesOrNull
        .map { it.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hasEnabledSources: StateFlow<Boolean> = sourcesOrNull
        .map { it == null || it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val categoryState: StateFlow<CategoryState> = combine(sourcesOrNull, _refreshToken) { sources, token ->
        sources to token
    }.flatMapLatest { (sources, token) ->
        flow {
            if (sources == null) {
                emit(CategoryState(emptyList(), discovering = true))
                return@flow
            }
            if (sources.isEmpty()) {
                emit(CategoryState(emptyList(), discovering = false))
                return@flow
            }
            val force = token > 0 && token != handledRefreshToken
            val cached = if (force) null else catalog.loadCachedCategories(sources)
            var latestCats = cached?.categories ?: emptyList()
            if (cached != null && cached.categories.isNotEmpty()) {
                val fresh = System.currentTimeMillis() - cached.cachedAt < CATEGORY_CACHE_TTL_MS
                emit(CategoryState(cached.categories, discovering = !fresh, fromCache = true))
                if (fresh) return@flow
            } else if (!force) {
                emit(CategoryState(emptyList(), discovering = true))
            }

            liveClient.discoverCategories(sources, forceRefresh = force).collect { cats ->
                if (cats.isNotEmpty()) {
                    latestCats = cats
                    emit(CategoryState(cats, discovering = true, fromCache = false))
                }
            }
            if (latestCats.isNotEmpty()) {
                catalog.saveCachedCategories(latestCats)
            }
            emit(CategoryState(latestCats, discovering = false, fromCache = false))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoryState())

    val categories: StateFlow<List<LiveCategory>> = categoryState
        .map { state -> state.categories.filterNot { CrawlRules.isNonMediaCategory(it.name) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val discoveringCategories: StateFlow<Boolean> = categoryState
        .map { it.discovering }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val activeSearchQuery: StateFlow<String> = combine(
        _searchQuery
            .map { it.trim() }
            .debounce { q -> if (q.isEmpty()) 0L else 320L }
            .map { q -> if (q.length >= 2) q else "" },
        _submittedQuery.map { it?.trim().orEmpty() },
    ) { debounced, submitted ->
        submitted.ifEmpty { debounced }
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private val favoriteTitles: StateFlow<List<LiveTitle>> = catalog.observeFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val favoriteUrls: StateFlow<Set<String>> = catalog.observeFavoriteUrls()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val downloadedUrls: StateFlow<Set<String>> = catalog.completedDownloadUrls()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    // --- Feed paging state ---
    private val feedMemoryCache = ConcurrentHashMap<FeedKey, CachedFeedMemoryState>()
    private val _rawFeedItems = MutableStateFlow<List<LiveTitle>>(emptyList())
    private val _isLoadingFeedInitial = MutableStateFlow(true)
    private val _isRefreshingFeed = MutableStateFlow(false)
    private val _isLoadingFeedMore = MutableStateFlow(false)
    private val _hasMoreFeed = MutableStateFlow(false)
    private val _feedFromCache = MutableStateFlow(false)
    private val _feedError = MutableStateFlow<String?>(null)

    private var activeFeedKey: FeedKey? = null
    private var activeFeedSession: LiveFeedSession? = null
    private var feedLoadMoreJob: Job? = null

    // --- Search + lazy hydration ---
    private val _rawSearchResults = MutableStateFlow<List<LiveTitle>>(emptyList())
    private val _revealedSearchCount = MutableStateFlow(PAGE_SIZE)
    private val _isSearching = MutableStateFlow(false)
    private val _searchError = MutableStateFlow<String?>(null)

    private val hydratedSearchCache = ConcurrentHashMap<String, LiveTitle>()
    private val deadSearchUrls = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private var hydrateSearchJob: Job? = null

    // --- Detail sheet state ---
    private val _detailTitle = MutableStateFlow<LiveTitle?>(null)
    private val _detailFiles = MutableStateFlow<List<CatalogFile>>(emptyList())
    private val _detailLoading = MutableStateFlow(false)
    private val _detailError = MutableStateFlow<String?>(null)
    private var loadFilesJob: Job? = null

    val detailSheet: StateFlow<DetailSheetState> = combine(
        _detailTitle,
        _detailFiles,
        _detailLoading,
        _detailError,
        combine(favoriteUrls, downloadedUrls) { f, d -> f to d },
    ) { title, files, loading, err, (favs, dlUrls) ->
        DetailSheetState(
            title = title?.let { TitleFilterEngine.enrich(it, favs, dlUrls) },
            files = files,
            loading = loading,
            error = err,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailSheetState())

    private val activeRawPool: StateFlow<List<LiveTitle>> = combine(
        _rawFeedItems,
        _rawSearchResults,
        favoriteTitles,
        activeSearchQuery,
        _selectedCategory,
    ) { feed, search, favs, query, selCat ->
        when {
            query.isNotEmpty() -> search
            selCat == FAVORITES_CATEGORY -> favs
            else -> feed
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val filteredSourcePool: StateFlow<List<LiveTitle>> = combine(
        activeRawPool,
        _filter,
        favoriteUrls,
        downloadedUrls,
    ) { pool, flt, favSet, dlSet ->
        TitleFilterEngine.filterAndSort(pool, flt, favSet, dlSet)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val items: StateFlow<List<LiveTitle>> = combine(
        filteredSourcePool,
        activeSearchQuery,
        _revealedSearchCount,
    ) { pool, query, revealed ->
        if (query.isNotEmpty()) pool.take(revealed) else pool
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val facets: StateFlow<Facets> = activeRawPool
        .map { TitleFilterEngine.buildFacets(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Facets())

    val isLoadingInitial: StateFlow<Boolean> = combine(
        activeSearchQuery,
        _selectedCategory,
        discoveringCategories,
        _isLoadingFeedInitial,
        _isSearching,
    ) { query, selCat, discovering, loadingFeed, searching ->
        when {
            query.isNotEmpty() -> searching && items.value.isEmpty()
            selCat == FAVORITES_CATEGORY -> false
            else -> items.value.isEmpty() && (discovering || loadingFeed)
        }
    }.combine(items) { loading, visible ->
        if (visible.isNotEmpty()) false else loading
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val isRefreshing: StateFlow<Boolean> = combine(
        activeSearchQuery,
        _selectedCategory,
        _isRefreshingFeed,
        _isSearching,
        items,
    ) { query, selCat, refreshingFeed, searching, visible ->
        when {
            query.isNotEmpty() -> searching && visible.isNotEmpty()
            selCat == FAVORITES_CATEGORY -> false
            else -> refreshingFeed && visible.isNotEmpty()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val isLoadingMore: StateFlow<Boolean> = combine(
        activeSearchQuery,
        _selectedCategory,
        _isLoadingFeedMore,
    ) { query, selCat, loadingMore ->
        query.isEmpty() && selCat != FAVORITES_CATEGORY && loadingMore
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val hasMore: StateFlow<Boolean> = combine(
        activeSearchQuery,
        _selectedCategory,
        _hasMoreFeed,
        _revealedSearchCount,
        filteredSourcePool,
    ) { query, selCat, moreFeed, revealed, pool ->
        when {
            query.isNotEmpty() -> revealed < pool.size
            selCat == FAVORITES_CATEGORY -> false
            else -> moreFeed
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val errorMessage: StateFlow<String?> = combine(
        hasEnabledSources,
        activeSearchQuery,
        _selectedCategory,
        _feedError,
        _searchError,
    ) { enabled, query, selCat, feedErr, searchErr ->
        when {
            !enabled -> "No enabled LAN servers. Enable at least one server in Servers."
            query.isNotEmpty() -> searchErr
            selCat == FAVORITES_CATEGORY -> null
            else -> feedErr
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val statusText: StateFlow<String> = combine(
        activeSearchQuery,
        _selectedCategory,
        combine(categories, items, filteredSourcePool) { c, v, t -> Triple(c, v, t) },
        combine(isLoadingInitial, hasMore, _isSearching) { i, m, s -> Triple(i, m, s) },
        combine(_isRefreshingFeed, _feedFromCache, discoveringCategories) { r, c, d -> Triple(r, c, d) },
    ) { query, selCat, (cats, visible, total), (loadingInit, more, searching), (refreshing, cachedFlag, discovering) ->
        when {
            query.isNotEmpty() && searching && total.isEmpty() ->
                "Searching LAN servers for \"$query\"…"

            query.isNotEmpty() ->
                "${visible.size} of ${total.size} results"

            selCat == FAVORITES_CATEGORY ->
                if (visible.size == 1) "1 saved favorite" else "${visible.size} saved favorites"

            loadingInit -> when {
                cats.isEmpty() && discovering -> "Discovering LAN servers…"
                selCat != null -> "Loading $selCat…"
                else -> "Fetching live catalog…"
            }

            refreshing ->
                if (selCat != null) "$selCat • updating…" else "${visible.size} titles • updating…"

            selCat != null ->
                "${visible.size} ${if (cachedFlag) "cached" else "loaded"} • $selCat"

            else ->
                "${visible.size} ${if (cachedFlag) "cached" else "loaded"} • ${cats.size} categories" +
                    if (more) " • scroll for more" else ""
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "Connecting to LAN servers…")

    init {
        observeFeedDriver()
        observeDirectSearchDriver()
        observeLazySearchHydration()
        viewModelScope.launch {
            var seenEpoch = container.cacheClearedToken.value
            container.cacheClearedToken.collect { epoch ->
                if (epoch != seenEpoch) {
                    seenEpoch = epoch
                    refresh()
                }
            }
        }
    }

    private fun observeFeedDriver() {
        viewModelScope.launch {
            combine(
                categoryState.debounce { state ->
                    if (state.fromCache || state.categories.isEmpty() || !state.discovering) 0L else 180L
                },
                _selectedCategory,
                _filter.map { it.kinds.singleOrNull() }.distinctUntilChanged(),
                activeSearchQuery,
                _refreshToken,
            ) { catState, selCat, kindFilter, query, token ->
                val activeCats = if (selCat == null) {
                    catState.categories
                } else {
                    catState.categories.filter { it.name.equals(selCat, ignoreCase = true) }
                }
                val sig = if (query.isNotEmpty() || selCat == FAVORITES_CATEGORY) {
                    "INACTIVE"
                } else if (catState.categories.isEmpty() && catState.discovering) {
                    "DISCOVERING"
                } else {
                    activeCats.joinToString("|") { c ->
                        c.name + ":" + c.locations.joinToString(",") { it.url }
                    }
                }
                FeedTriggerKey(sig, selCat, kindFilter, token) to Pair(catState, activeCats)
            }
                .distinctUntilChanged { a, b -> a.first == b.first }
                .collectLatest { (key, pair) ->
                    feedLoadMoreJob?.cancel()
                    val (catState, activeCats) = pair
                    if (key.signature == "INACTIVE") return@collectLatest

                    val feedKey = FeedKey(key.selectedCategory, key.kindFilter)
                    activeFeedKey = feedKey
                    val roomKey = roomFeedKey(feedKey)
                    val isExplicitRefresh = key.refreshToken != handledRefreshToken
                    val enabledIds = enabledSources.value.mapTo(HashSet()) { it.id }

                    if (key.signature == "DISCOVERING") {
                        if (isExplicitRefresh) {
                            _isRefreshingFeed.value = _rawFeedItems.value.isNotEmpty()
                            _isLoadingFeedInitial.value = _rawFeedItems.value.isEmpty()
                            return@collectLatest
                        }
                        val mem = feedMemoryCache[feedKey]
                        if (mem != null) {
                            applyMemorySnapshot(mem)
                        } else {
                            val roomSnap = catalog.loadCachedFeed(roomKey, enabledIds)
                            if (roomSnap != null && roomSnap.items.isNotEmpty()) {
                                val snap = CachedFeedMemoryState(
                                    items = roomSnap.items,
                                    session = null,
                                    hasMore = true,
                                    cachedAt = roomSnap.cachedAt,
                                    fromCache = true,
                                )
                                feedMemoryCache[feedKey] = snap
                                applyMemorySnapshot(snap)
                            } else {
                                _isLoadingFeedInitial.value = true
                            }
                        }
                        return@collectLatest
                    }

                    val mem = if (isExplicitRefresh) null else feedMemoryCache[feedKey]
                    if (mem != null) {
                        val ageMs = System.currentTimeMillis() - mem.cachedAt
                        applyMemorySnapshot(mem)
                        if (ageMs < FEED_CACHE_TTL_MS) return@collectLatest
                    }

                    val roomSnap = if (isExplicitRefresh || mem != null) null
                    else catalog.loadCachedFeed(roomKey, enabledIds)
                    if (roomSnap != null && roomSnap.items.isNotEmpty()) {
                        val snap = CachedFeedMemoryState(
                            items = roomSnap.items,
                            session = null,
                            hasMore = true,
                            cachedAt = roomSnap.cachedAt,
                            fromCache = true,
                        )
                        feedMemoryCache[feedKey] = snap
                        applyMemorySnapshot(snap)
                        if (System.currentTimeMillis() - roomSnap.cachedAt < FEED_CACHE_TTL_MS) {
                            return@collectLatest
                        }
                    }

                    if (catState.categories.isEmpty() && !catState.discovering) {
                        if (_rawFeedItems.value.isEmpty()) {
                            activeFeedSession = null
                            _hasMoreFeed.value = false
                            _isLoadingFeedInitial.value = false
                            _feedError.value = if (hasEnabledSources.value) {
                                "Could not reach any LAN server right now."
                            } else null
                        }
                        _isRefreshingFeed.value = false
                        handledRefreshToken = key.refreshToken
                        return@collectLatest
                    }

                    if (key.selectedCategory != null && activeCats.isEmpty()) {
                        activeFeedSession = null
                        _rawFeedItems.value = emptyList()
                        _hasMoreFeed.value = false
                        _isLoadingFeedInitial.value = false
                        _isRefreshingFeed.value = false
                        handledRefreshToken = key.refreshToken
                        return@collectLatest
                    }

                    val hasVisibleCache = (mem != null || roomSnap != null || isExplicitRefresh) &&
                        _rawFeedItems.value.isNotEmpty()
                    val session = LiveFeedSession(
                        client = liveClient,
                        categories = catState.categories,
                        selectedCategory = key.selectedCategory,
                        kindFilter = key.kindFilter,
                    )
                    activeFeedSession = session
                    _feedError.value = null
                    _isLoadingFeedMore.value = false
                    if (hasVisibleCache) {
                        _isRefreshingFeed.value = true
                        _isLoadingFeedInitial.value = false
                    } else {
                        _rawFeedItems.value = emptyList()
                        _hasMoreFeed.value = session.hasMore
                        _isLoadingFeedInitial.value = true
                    }

                    val firstPage = try {
                        session.loadNextPage(PAGE_SIZE)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                    if (activeFeedSession === session && activeFeedKey == feedKey) {
                        if (firstPage.isNotEmpty()) {
                            val now = System.currentTimeMillis()
                            _rawFeedItems.value = firstPage
                            _hasMoreFeed.value = session.hasMore
                            _feedFromCache.value = false
                            val updatedMem = CachedFeedMemoryState(
                                items = firstPage,
                                session = session,
                                hasMore = session.hasMore,
                                cachedAt = now,
                                fromCache = false,
                            )
                            feedMemoryCache[feedKey] = updatedMem
                            catalog.saveCachedFeed(roomKey, firstPage, now)
                        } else if (_rawFeedItems.value.isEmpty() || isExplicitRefresh) {
                            _rawFeedItems.value = emptyList()
                            _hasMoreFeed.value = false
                            _feedError.value = if (hasEnabledSources.value) {
                                "Could not load titles from the enabled servers right now."
                            } else null
                        }
                        _isLoadingFeedInitial.value = false
                        _isRefreshingFeed.value = false
                        handledRefreshToken = key.refreshToken
                    }
                }
        }
    }

    private fun applyMemorySnapshot(snap: CachedFeedMemoryState) {
        activeFeedSession = snap.session
        _rawFeedItems.value = snap.items
        _hasMoreFeed.value = snap.hasMore
        _feedFromCache.value = snap.fromCache
        _feedError.value = null
        _isLoadingFeedInitial.value = false
        _isRefreshingFeed.value = false
        _isLoadingFeedMore.value = false
    }

    private fun observeDirectSearchDriver() {
        viewModelScope.launch {
            combine(activeSearchQuery, enabledSources, _refreshToken) { query, sources, token ->
                Triple(query, sources, token)
            }.collectLatest { (query, sources, token) ->
                hydrateSearchJob?.cancel()
                if (query.isEmpty()) {
                    _rawSearchResults.value = emptyList()
                    _revealedSearchCount.value = PAGE_SIZE
                    _isSearching.value = false
                    _searchError.value = null
                    return@collectLatest
                }
                if (sources.isEmpty()) {
                    _rawSearchResults.value = emptyList()
                    _isSearching.value = false
                    return@collectLatest
                }

                val force = token > 0 && token != handledRefreshToken
                _rawSearchResults.value = emptyList()
                _revealedSearchCount.value = PAGE_SIZE
                _isSearching.value = true
                _searchError.value = null

                liveClient.searchDirect(sources, query, forceRefresh = force)
                    .catch { err ->
                        _searchError.value = err.message ?: "Live search failed"
                    }
                    .collect { hits ->
                        val merged = hits
                            .filterNot { it.folderUrl in deadSearchUrls }
                            .map { hit ->
                                val cached = hydratedSearchCache[hit.folderUrl]
                                if (cached != null) cached.copy(
                                    cachedFiles = (cached.cachedFiles + hit.cachedFiles).distinctBy { it.fileUrl },
                                    fileCount = maxOf(cached.fileCount, hit.fileCount),
                                ) else hit
                            }
                        _rawSearchResults.value = merged
                    }
                _isSearching.value = false
            }
        }
    }

    private fun observeLazySearchHydration() {
        viewModelScope.launch {
            combine(items, activeSearchQuery) { visible, query ->
                if (query.isEmpty()) emptyList()
                else visible.filter { !it.posterResolved }
            }
                .map { pending -> pending.take(PAGE_SIZE) }
                .distinctUntilChanged { a, b -> a.map { it.folderUrl } == b.map { it.folderUrl } }
                .collect { pending ->
                    if (pending.isEmpty()) return@collect
                    hydrateSearchJob?.cancel()
                    hydrateSearchJob = viewModelScope.launch {
                        val sem = Semaphore(4)
                        coroutineScope {
                            pending.map { card ->
                                async {
                                    sem.withPermit {
                                        val updated = liveClient.hydrateTitle(card)
                                        if (updated == null) {
                                            deadSearchUrls.add(card.folderUrl)
                                            _rawSearchResults.update { list ->
                                                list.filterNot { it.folderUrl == card.folderUrl }
                                            }
                                        } else {
                                            val mergedFiles = (updated.cachedFiles + card.cachedFiles)
                                                .distinctBy { it.fileUrl }
                                            val resolved = updated.copy(
                                                cachedFiles = mergedFiles,
                                                fileCount = maxOf(updated.fileCount, mergedFiles.size),
                                                posterResolved = true,
                                            )
                                            hydratedSearchCache[card.folderUrl] = resolved
                                            _rawSearchResults.update { list ->
                                                list.map {
                                                    if (it.folderUrl == card.folderUrl) resolved else it
                                                }
                                            }
                                        }
                                    }
                                }
                            }.awaitAll()
                        }
                    }
                }
        }
    }

    fun setQuery(query: String) {
        _searchQuery.value = query
        if (query.trim().isEmpty()) {
            _submittedQuery.value = null
        }
    }

    fun submitSearch() {
        val trimmed = _searchQuery.value.trim()
        _submittedQuery.value = trimmed.ifEmpty { null }
    }

    fun selectCategory(categoryName: String?) {
        val changed = _selectedCategory.value != categoryName
        _selectedCategory.value = categoryName
        if (_searchQuery.value.isNotEmpty() || _submittedQuery.value != null) {
            _searchQuery.value = ""
            _submittedQuery.value = null
        }
        if (changed && categoryName != FAVORITES_CATEGORY) {
            feedLoadMoreJob?.cancel()
            _isLoadingFeedMore.value = false
            _feedError.value = null
            val key = FeedKey(categoryName, _filter.value.kinds.singleOrNull())
            val mem = feedMemoryCache[key]
            if (mem != null) {
                applyMemorySnapshot(mem)
            } else {
                activeFeedSession = null
                _rawFeedItems.value = emptyList()
                _hasMoreFeed.value = false
                _isLoadingFeedInitial.value = true
            }
        }
    }

    fun setFilter(filter: CatalogFilter) {
        _filter.value = filter
    }

    fun clearFilters() {
        _searchQuery.value = ""
        _submittedQuery.value = null
        _selectedCategory.value = null
        _filter.update { CatalogFilter(sort = it.sort) }
    }

    fun setSort(sort: SortBy) {
        _filter.update { it.copy(sort = sort) }
    }

    fun refresh() {
        liveClient.clearAllCaches()
        feedMemoryCache.clear()
        hydratedSearchCache.clear()
        deadSearchUrls.clear()
        _refreshToken.update { it + 1 }
    }

    fun loadMore() {
        val query = activeSearchQuery.value
        if (query.isNotEmpty()) {
            val total = filteredSourcePool.value.size
            if (_revealedSearchCount.value < total) {
                _revealedSearchCount.update { minOf(it + PAGE_SIZE, total) }
            }
            return
        }
        val selCat = _selectedCategory.value
        if (selCat == FAVORITES_CATEGORY) return
        if (_isLoadingFeedInitial.value || feedLoadMoreJob?.isActive == true) return

        val key = FeedKey(selCat, _filter.value.kinds.singleOrNull())
        val existingItems = _rawFeedItems.value

        val session = activeFeedSession ?: run {
            val cats = categoryState.value.categories
            if (cats.isEmpty()) return
            LiveFeedSession(
                client = liveClient,
                categories = cats,
                selectedCategory = selCat,
                kindFilter = key.kindFilter,
                initialEmittedUrls = existingItems.mapTo(HashSet()) { it.folderUrl },
            ).also { activeFeedSession = it }
        }
        if (!session.hasMore) {
            _hasMoreFeed.value = false
            return
        }

        feedLoadMoreJob = viewModelScope.launch {
            _isLoadingFeedMore.value = true
            val page = try {
                session.loadNextPage(PAGE_SIZE)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            if (activeFeedSession === session && activeFeedKey == key) {
                val combined = if (page.isNotEmpty()) {
                    val seen = _rawFeedItems.value.mapTo(HashSet()) { it.folderUrl }
                    _rawFeedItems.value + page.filter { seen.add(it.folderUrl) }
                } else {
                    _rawFeedItems.value
                }
                _rawFeedItems.value = combined
                _hasMoreFeed.value = session.hasMore
                _feedFromCache.value = false
                val now = System.currentTimeMillis()
                feedMemoryCache[key] = CachedFeedMemoryState(
                    items = combined,
                    session = session,
                    hasMore = session.hasMore,
                    cachedAt = now,
                    fromCache = false,
                )
                if (page.isNotEmpty()) {
                    catalog.saveCachedFeed(roomFeedKey(key), combined, now)
                }
            }
            _isLoadingFeedMore.value = false
        }
    }

    fun selectTitle(title: LiveTitle) {
        _detailTitle.value = title
        _detailError.value = null
        loadFilesJob?.cancel()

        if (title.filesLoaded && title.cachedFiles.isNotEmpty()) {
            _detailFiles.value = title.cachedFiles
            _detailLoading.value = false
            return
        }

        _detailFiles.value = title.cachedFiles
        _detailLoading.value = true
        loadFilesJob = viewModelScope.launch {
            val loaded = runCatching { liveClient.loadFiles(title) }
            loaded.onSuccess { files ->
                _detailFiles.value = files
                _detailLoading.value = false
                if (files.isEmpty()) {
                    _detailError.value = "No video files found in this folder."
                }
                updateTitleFilesCache(title.folderUrl, files)
            }.onFailure { err ->
                _detailLoading.value = false
                if (_detailFiles.value.isEmpty()) {
                    _detailError.value = err.message ?: "Failed to load files from server."
                }
            }
        }
    }

    fun retrySelectedFiles() {
        val current = _detailTitle.value ?: return
        loadFilesJob?.cancel()
        _detailError.value = null
        _detailLoading.value = true
        loadFilesJob = viewModelScope.launch {
            val loaded = runCatching { liveClient.loadFiles(current.copy(filesLoaded = false), forceRefresh = true) }
            loaded.onSuccess { files ->
                _detailFiles.value = files
                _detailLoading.value = false
                updateTitleFilesCache(current.folderUrl, files)
            }.onFailure { err ->
                _detailLoading.value = false
                _detailError.value = err.message ?: "Failed to load files from server."
            }
        }
    }

    fun closeTitle() {
        loadFilesJob?.cancel()
        _detailTitle.value = null
        _detailFiles.value = emptyList()
        _detailLoading.value = false
        _detailError.value = null
    }

    fun toggleFavorite(title: LiveTitle) {
        viewModelScope.launch { catalog.toggleFavorite(title) }
    }

    fun download(file: CatalogFile, title: String) {
        viewModelScope.launch {
            downloads.enqueue(file, title)
            container.message("Added to Downloads queue: ${file.fileName}")
        }
    }

    fun downloadAll(files: List<CatalogFile>, title: String) {
        viewModelScope.launch {
            val count = downloads.enqueueAll(files, title)
            container.message(
                if (count > 0) "Added $count files for $title. Track progress in Downloads."
                else "These files are already queued or saved. Check Downloads for status.",
            )
        }
    }

    /** Audit B12: which file to queue when a title has several. */
    enum class QualityPick { SMALLEST, BALANCED, BEST }

    data class QualityChoiceRequest(val title: LiveTitle, val files: List<CatalogFile>)

    private val _qualityChoice = MutableStateFlow<QualityChoiceRequest?>(null)
    val qualityChoice: StateFlow<QualityChoiceRequest?> = _qualityChoice.asStateFlow()

    private fun qualityRank(quality: String?): Int = when (quality) {
        "2160p" -> 5
        "1080p" -> 4
        "720p" -> 3
        "480p" -> 2
        "360p" -> 1
        "CAM" -> 0
        else -> 3
    }

    fun pickFileFor(files: List<CatalogFile>, pick: QualityPick): CatalogFile = when (pick) {
        QualityPick.BEST -> files.maxByOrNull { it.sizeBytes } ?: files.first()
        QualityPick.SMALLEST -> files.minByOrNull { it.sizeBytes } ?: files.first()
        QualityPick.BALANCED -> {
            val capped = files.filter { qualityRank(it.quality) <= qualityRank("1080p") }
            capped.maxByOrNull { it.sizeBytes } ?: files.minByOrNull { it.sizeBytes } ?: files.first()
        }
    }

    fun chooseQuality(pick: QualityPick) {
        val request = _qualityChoice.value ?: return
        _qualityChoice.value = null
        val chosen = pickFileFor(request.files, pick)
        viewModelScope.launch {
            downloads.enqueue(chosen, request.title.title)
            container.message("Added to Downloads queue: ${chosen.fileName}")
        }
    }

    fun dismissQualityChoice() {
        _qualityChoice.value = null
    }

    fun downloadTitle(title: LiveTitle) {
        viewModelScope.launch {
            val files = if (title.filesLoaded && title.cachedFiles.isNotEmpty()) {
                title.cachedFiles
            } else {
                container.message("Fetching files for ${title.title}…")
                runCatching { liveClient.loadFiles(title) }.getOrElse {
                    container.message("Could not reach server for ${title.title}")
                    return@launch
                }.also { fetched ->
                    if (fetched.isNotEmpty()) updateTitleFilesCache(title.folderUrl, fetched)
                }
            }
            if (files.isEmpty()) {
                container.message("No downloadable video files found")
                return@launch
            }
            if (title.kind == "series" && files.size > 1) {
                val count = downloads.enqueueAll(files, title.title)
                container.message(
                    if (count > 0) "Added $count files for ${title.title}. Track progress in Downloads."
                    else "${title.title} already queued or saved",
                )
            } else if (files.size == 1) {
                val chosen = files.first()
                downloads.enqueue(chosen, title.title)
                container.message("Added to Downloads queue: ${chosen.fileName}")
            } else {
                // Audit B12: never silently queue the largest file (a 2160p
                // remux can be tens of GB) — ask, with sizes shown.
                _qualityChoice.value = QualityChoiceRequest(title, files)
            }
        }
    }

    private fun updateTitleFilesCache(folderUrl: String, files: List<CatalogFile>) {
        fun LiveTitle.applyFiles(): LiveTitle {
            val seasons = files.mapNotNull { it.season }.distinct().size
            return copy(
                cachedFiles = files,
                filesLoaded = true,
                fileCount = files.size,
                seasonCount = if (seasons > 0) seasons else seasonCount,
                maxSizeBytes = files.maxOfOrNull { it.sizeBytes } ?: maxSizeBytes,
            )
        }
        hydratedSearchCache[folderUrl]?.let { hydratedSearchCache[folderUrl] = it.applyFiles() }
        for ((k, state) in feedMemoryCache) {
            feedMemoryCache[k] = state.copy(items = state.items.map { if (it.folderUrl == folderUrl) it.applyFiles() else it })
        }
        _rawFeedItems.update { list -> list.map { if (it.folderUrl == folderUrl) it.applyFiles() else it } }
        _rawSearchResults.update { list -> list.map { if (it.folderUrl == folderUrl) it.applyFiles() else it } }
        _detailTitle.update { cur -> if (cur?.folderUrl == folderUrl) cur.applyFiles() else cur }
    }

    private fun roomFeedKey(key: FeedKey): String =
        "${key.selectedCategory ?: "ALL"}|${key.kindFilter ?: "ANY"}"

    companion object {
        const val FAVORITES_CATEGORY = "__FAVORITES__"
        private const val PAGE_SIZE = 18
        private const val CATEGORY_CACHE_TTL_MS = 15 * 60_000L
        private const val FEED_CACHE_TTL_MS = 10 * 60_000L
    }
}
