package com.lanfetch.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lanfetch.app.LanFetchApp
import com.lanfetch.app.data.CatalogFile
import com.lanfetch.app.data.Facets
import com.lanfetch.app.data.LiveCategory
import com.lanfetch.app.data.LiveTitle
import com.lanfetch.app.data.Source
import com.lanfetch.app.filter.CatalogFilter
import com.lanfetch.app.filter.SortBy
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

data class CategoryState(
    val categories: List<LiveCategory> = emptyList(),
    val discovering: Boolean = true,
    val fromCache: Boolean = false,
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
 * Live YouTube-style library controller with 3-tier caching:
 * 1. Room stale-while-revalidate cache (`cached_categories`, `cached_feed_items`)
 *    for instant (<50 ms) cold startup without blocking spinners.
 * 2. In-memory category session cache so switching `All <-> Hindi Movies <-> Search`
 *    resumes instantaneously without losing scroll pages or hitting the network.
 * 3. Directory/file/search TTL caches inside [LiveServerClient].
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

    private val _selectedCategory = MutableStateFlow<String?>(null) // null = All, FAVORITES_CATEGORY = Favorites
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
            .debounce { q -> if (q.isEmpty()) 0L else 350L }
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

    // --- Live Feed state + in-memory per-category session cache ---
    private val feedMemoryCache = ConcurrentHashMap<FeedKey, CachedFeedMemoryState>()
    private val _rawFeedItems = MutableStateFlow<List<LiveTitle>>(emptyList())
    private val _isLoadingFeedInitial = MutableStateFlow(false)
    private val _isRefreshingFeed = MutableStateFlow(false)
    private val _isLoadingFeedMore = MutableStateFlow(false)
    private val _hasMoreFeed = MutableStateFlow(false)
    private val _feedFromCache = MutableStateFlow(false)
    private val _feedError = MutableStateFlow<String?>(null)

    private var activeFeedKey: FeedKey? = null
    private var activeFeedSession: LiveFeedSession? = null
    private var feedLoadMoreJob: Job? = null

    // --- Live Direct Search state + lazy poster hydration ---
    private val _rawSearchResults = MutableStateFlow<List<LiveTitle>>(emptyList())
    private val _revealedSearchCount = MutableStateFlow(PAGE_SIZE)
    private val _isSearching = MutableStateFlow(false)
    private val _searchError = MutableStateFlow<String?>(null)

    private val hydratedSearchCache = ConcurrentHashMap<String, LiveTitle>()
    private val deadSearchUrls = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private var hydrateSearchJob: Job? = null

    // --- Detail sheet state (on-demand file/episode fetch) ---
    private val _selectedTitle = MutableStateFlow<LiveTitle?>(null)
    val selectedTitle: StateFlow<LiveTitle?> = combine(_selectedTitle, favoriteUrls, downloadedUrls) { title, favs, dlUrls ->
        title?.enrich(favs, dlUrls)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _selectedFiles = MutableStateFlow<List<CatalogFile>>(emptyList())
    val selectedFiles: StateFlow<List<CatalogFile>> = _selectedFiles.asStateFlow()

    private val _selectedFilesLoading = MutableStateFlow(false)
    val selectedFilesLoading: StateFlow<Boolean> = _selectedFilesLoading.asStateFlow()

    private val _selectedFilesError = MutableStateFlow<String?>(null)
    val selectedFilesError: StateFlow<String?> = _selectedFilesError.asStateFlow()

    private var loadFilesJob: Job? = null

    private val filteredSourcePool: StateFlow<List<LiveTitle>> = combine(
        combine(_rawFeedItems, _rawSearchResults, favoriteTitles) { feed, search, favs ->
            Triple(feed, search, favs)
        },
        activeSearchQuery,
        _selectedCategory,
        _filter,
        favoriteUrls,
        downloadedUrls,
    ) { arrayOfValues ->
        @Suppress("UNCHECKED_CAST")
        val triple = arrayOfValues[0] as Triple<List<LiveTitle>, List<LiveTitle>, List<LiveTitle>>
        val query = arrayOfValues[1] as String
        val selCat = arrayOfValues[2] as String?
        val flt = arrayOfValues[3] as CatalogFilter
        @Suppress("UNCHECKED_CAST")
        val favSet = arrayOfValues[4] as Set<String>
        @Suppress("UNCHECKED_CAST")
        val dlSet = arrayOfValues[5] as Set<String>

        val pool = when {
            query.isNotEmpty() -> triple.second
            selCat == FAVORITES_CATEGORY -> triple.third
            else -> triple.first
        }
        applyFilterAndSort(pool, flt, favSet, dlSet)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val items: StateFlow<List<LiveTitle>> = combine(
        filteredSourcePool,
        activeSearchQuery,
        _revealedSearchCount,
    ) { pool, query, revealed ->
        if (query.isNotEmpty()) pool.take(revealed) else pool
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val facets: StateFlow<Facets> = combine(
        _rawFeedItems,
        _rawSearchResults,
        favoriteTitles,
        activeSearchQuery,
        _selectedCategory,
    ) { feed, search, favs, query, selCat ->
        val pool = when {
            query.isNotEmpty() -> search
            selCat == FAVORITES_CATEGORY -> favs
            else -> feed
        }
        buildFacets(pool)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Facets())

    val isLoadingInitial: StateFlow<Boolean> = combine(
        activeSearchQuery,
        _selectedCategory,
        discoveringCategories,
        _isLoadingFeedInitial,
        _isSearching,
        items,
    ) { arrayOfValues ->
        val query = arrayOfValues[0] as String
        val selCat = arrayOfValues[1] as String?
        val discovering = arrayOfValues[2] as Boolean
        val loadingFeed = arrayOfValues[3] as Boolean
        val searching = arrayOfValues[4] as Boolean
        @Suppress("UNCHECKED_CAST")
        val visible = arrayOfValues[5] as List<LiveTitle>

        when {
            query.isNotEmpty() -> searching && visible.isEmpty()
            selCat == FAVORITES_CATEGORY -> false
            else -> visible.isEmpty() && (discovering || loadingFeed)
        }
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
            !enabled -> "No enabled LAN servers. Enable at least one server in Sources."
            query.isNotEmpty() -> searchErr
            selCat == FAVORITES_CATEGORY -> null
            else -> feedErr
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val statusText: StateFlow<String> = combine(
        activeSearchQuery,
        _selectedCategory,
        categories,
        items,
        filteredSourcePool,
        isLoadingInitial,
        hasMore,
        _isSearching,
        _isRefreshingFeed,
        _feedFromCache,
    ) { arrayOfValues ->
        val query = arrayOfValues[0] as String
        val selCat = arrayOfValues[1] as String?
        @Suppress("UNCHECKED_CAST")
        val cats = arrayOfValues[2] as List<LiveCategory>
        @Suppress("UNCHECKED_CAST")
        val visible = arrayOfValues[3] as List<LiveTitle>
        @Suppress("UNCHECKED_CAST")
        val total = arrayOfValues[4] as List<LiveTitle>
        val loadingInit = arrayOfValues[5] as Boolean
        val more = arrayOfValues[6] as Boolean
        val searching = arrayOfValues[7] as Boolean
        val refreshing = arrayOfValues[8] as Boolean
        val cachedFlag = arrayOfValues[9] as Boolean

        when {
            query.isNotEmpty() && searching && total.isEmpty() ->
                "Searching servers for \"$query\"…"

            query.isNotEmpty() ->
                "${visible.size} of ${total.size} results for \"$query\""

            selCat == FAVORITES_CATEGORY ->
                if (visible.size == 1) "1 favorite title" else "${visible.size} favorite titles"

            loadingInit ->
                if (selCat == null) "Loading live feed from LAN…" else "Loading $selCat…"

            refreshing ->
                if (selCat != null) "${visible.size} cached • $selCat • updating…"
                else "${visible.size} cached • ${cats.size} categories • updating…"

            selCat != null -> {
                val verb = if (cachedFlag) "cached" else "loaded"
                if (more) "${visible.size} $verb • $selCat • scroll for more"
                else "${visible.size} titles • $selCat"
            }

            else -> {
                val verb = if (cachedFlag) "cached" else "loaded"
                "${visible.size} $verb • ${cats.size} categories" + if (more) " • scroll for more" else ""
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "Loading cached library…")

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

                    // 1. Check Room cache BEFORE category discovery even finishes so cold reopen is <50 ms!
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

                    // 2. Fast in-memory session cache restore (zero network calls on tab switch!)
                    val mem = if (isExplicitRefresh) null else feedMemoryCache[feedKey]
                    if (mem != null) {
                        val ageMs = System.currentTimeMillis() - mem.cachedAt
                        applyMemorySnapshot(mem)
                        if (ageMs < FEED_CACHE_TTL_MS) {
                            return@collectLatest
                        }
                    }

                    // 3. Persistent Room cache restore (stale-while-revalidate on cold launch or category switch)
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

                    // 4. Empty or missing categories check
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

                    // 5. Fetch live page 1 (showing background update indicator if cached cards are visible)
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
                                "Could not load items from the selected servers right now."
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
            }.collectLatest { (query, sources, _) ->
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

                _rawSearchResults.value = emptyList()
                _revealedSearchCount.value = PAGE_SIZE
                _isSearching.value = true
                _searchError.value = null

                liveClient.searchDirect(sources, query)
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
        _selectedCategory.value = categoryName
        if (_searchQuery.value.isNotEmpty() || _submittedQuery.value != null) {
            _searchQuery.value = ""
            _submittedQuery.value = null
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

        // Lazily initialize a live LiveFeedSession skipping items already loaded from Room cache!
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
                    val uniqueNew = page.filter { seen.add(it.folderUrl) }
                    _rawFeedItems.value + uniqueNew
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
        _selectedTitle.value = title
        _selectedFilesError.value = null
        loadFilesJob?.cancel()

        if (title.filesLoaded && title.cachedFiles.isNotEmpty()) {
            _selectedFiles.value = title.cachedFiles
            _selectedFilesLoading.value = false
            return
        }

        _selectedFiles.value = title.cachedFiles
        _selectedFilesLoading.value = true
        loadFilesJob = viewModelScope.launch {
            val loaded = runCatching { liveClient.loadFiles(title) }
            loaded.onSuccess { files ->
                _selectedFiles.value = files
                _selectedFilesLoading.value = false
                if (files.isEmpty()) {
                    _selectedFilesError.value = "No video files found in this folder."
                }
                updateTitleFilesCache(title.folderUrl, files)
            }.onFailure { err ->
                _selectedFilesLoading.value = false
                if (_selectedFiles.value.isEmpty()) {
                    _selectedFilesError.value = err.message ?: "Failed to load files from server."
                }
            }
        }
    }

    fun retrySelectedFiles() {
        val current = _selectedTitle.value ?: return
        loadFilesJob?.cancel()
        _selectedFilesError.value = null
        _selectedFilesLoading.value = true
        loadFilesJob = viewModelScope.launch {
            val loaded = runCatching { liveClient.loadFiles(current.copy(filesLoaded = false), forceRefresh = true) }
            loaded.onSuccess { files ->
                _selectedFiles.value = files
                _selectedFilesLoading.value = false
                updateTitleFilesCache(current.folderUrl, files)
            }.onFailure { err ->
                _selectedFilesLoading.value = false
                _selectedFilesError.value = err.message ?: "Failed to load files from server."
            }
        }
    }

    fun closeTitle() {
        loadFilesJob?.cancel()
        _selectedTitle.value = null
        _selectedFiles.value = emptyList()
        _selectedFilesLoading.value = false
        _selectedFilesError.value = null
    }

    fun toggleFavorite(title: LiveTitle) {
        viewModelScope.launch { catalog.toggleFavorite(title) }
    }

    fun download(file: CatalogFile, title: String) {
        viewModelScope.launch {
            downloads.enqueue(file, title)
            container.message("Queued ${file.fileName}")
        }
    }

    fun downloadAll(files: List<CatalogFile>, title: String) {
        viewModelScope.launch {
            val count = downloads.enqueueAll(files, title)
            container.message(if (count > 0) "Queued $count files for $title" else "All files already queued or downloaded")
        }
    }

    /** Card quick-download: fetches episodes/files on demand if not yet loaded, then queues. */
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
                    if (count > 0) "Queued $count files for ${title.title}"
                    else "${title.title} already queued or saved"
                )
            } else {
                val chosen = files.maxByOrNull { it.sizeBytes } ?: files.first()
                downloads.enqueue(chosen, title.title)
                container.message("Queued ${chosen.fileName}")
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
        _selectedTitle.update { cur -> if (cur?.folderUrl == folderUrl) cur.applyFiles() else cur }
    }

    private fun applyFilterAndSort(
        pool: List<LiveTitle>,
        f: CatalogFilter,
        favoriteUrls: Set<String>,
        downloadedUrls: Set<String>,
    ): List<LiveTitle> {
        val enriched = pool.asSequence()
            .map { it.enrich(favoriteUrls, downloadedUrls) }
            .filter { item ->
                (f.kinds.isEmpty() || item.kind in f.kinds) &&
                    (f.categories.isEmpty() || item.category in f.categories) &&
                    (f.sourceNames.isEmpty() || item.sourceName in f.sourceNames) &&
                    (f.qualities.isEmpty() || item.bestQuality in f.qualities) &&
                    (f.languages.isEmpty() || item.language in f.languages) &&
                    (f.sourceTags.isEmpty() || item.bestSourceTag in f.sourceTags) &&
                    (f.yearMin == null || (item.year != null && item.year >= f.yearMin)) &&
                    (f.yearMax == null || (item.year != null && item.year <= f.yearMax)) &&
                    (f.minSizeBytes == null || item.maxSizeBytes >= f.minSizeBytes) &&
                    (!f.favoritesOnly || item.favorite) &&
                    (!f.dualAudioOnly || item.hasDualAudio)
            }
            .toList()

        return when (f.sort) {
            SortBy.NEWEST -> enriched
            SortBy.TITLE -> enriched.sortedBy { it.title.lowercase() }
            SortBy.YEAR_DESC -> enriched.sortedWith(
                compareByDescending<LiveTitle> { it.year ?: Int.MIN_VALUE }.thenBy { it.title.lowercase() }
            )
            SortBy.SIZE_DESC -> enriched.sortedWith(
                compareByDescending<LiveTitle> { it.maxSizeBytes }.thenBy { it.title.lowercase() }
            )
            SortBy.SIZE_ASC -> enriched.sortedWith(
                compareBy<LiveTitle> { if (it.maxSizeBytes < 0L) Long.MAX_VALUE else it.maxSizeBytes }
                    .thenBy { it.title.lowercase() }
            )
        }
    }

    private fun buildFacets(pool: List<LiveTitle>): Facets {
        val qualityOrder = listOf("2160p", "1080p", "720p", "480p", "360p", "CAM")
        return Facets(
            categories = pool.map { it.category }.filter { it.isNotBlank() }.distinct().sorted(),
            sourceNames = pool.map { it.sourceName }.filter { it.isNotBlank() }.distinct().sorted(),
            qualities = pool.mapNotNull { it.bestQuality }.distinct()
                .sortedBy { q -> qualityOrder.indexOf(q).let { if (it < 0) qualityOrder.size else it } },
            languages = pool.mapNotNull { it.language }.distinct().sorted(),
            sourceTags = pool.mapNotNull { it.bestSourceTag }.distinct().sorted(),
            years = pool.mapNotNull { it.year }.distinct().sorted(),
        )
    }

    private fun LiveTitle.enrich(favoriteUrls: Set<String>, downloadedUrls: Set<String>): LiveTitle {
        val dlCount = if (cachedFiles.isEmpty()) downloadedCount else cachedFiles.count { it.fileUrl in downloadedUrls }
        return copy(
            favorite = folderUrl in favoriteUrls,
            downloadedCount = dlCount,
        )
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
