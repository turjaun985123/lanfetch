package com.lanfetch.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.lanfetch.app.R
import com.lanfetch.app.data.CatalogFile
import com.lanfetch.app.data.Facets
import com.lanfetch.app.data.LiveTitle
import com.lanfetch.app.download.Format
import com.lanfetch.app.filter.CatalogFilter
import com.lanfetch.app.filter.SortBy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: LibraryViewModel) {
    val searchQuery by vm.searchQuery.collectAsState()
    val activeSearchQuery by vm.activeSearchQuery.collectAsState()
    val categories by vm.categories.collectAsState()
    val discoveringCategories by vm.discoveringCategories.collectAsState()
    val selectedCategory by vm.selectedCategory.collectAsState()
    val filter by vm.filter.collectAsState()
    val facets by vm.facets.collectAsState()
    val items by vm.items.collectAsState()
    val isLoadingInitial by vm.isLoadingInitial.collectAsState()
    val isRefreshing by vm.isRefreshing.collectAsState()
    val isLoadingMore by vm.isLoadingMore.collectAsState()
    val hasMore by vm.hasMore.collectAsState()
    val errorMessage by vm.errorMessage.collectAsState()
    val statusText by vm.statusText.collectAsState()
    val downloadedUrls by vm.downloadedUrls.collectAsState()

    var showFilterSheet by remember { mutableStateOf(false) }
    var showGroup by remember { mutableStateOf(false) }
    val selectedTitle by vm.selectedTitle.collectAsState()
    val selectedFiles by vm.selectedFiles.collectAsState()
    val selectedFilesLoading by vm.selectedFilesLoading.collectAsState()
    val selectedFilesError by vm.selectedFilesError.collectAsState()

    val keyboard = LocalSoftwareKeyboardController.current
    val gridState = rememberLazyGridState()

    val currentHasMore by rememberUpdatedState(hasMore)
    val currentLoadingMore by rememberUpdatedState(isLoadingMore)
    val currentLoadingInitial by rememberUpdatedState(isLoadingInitial)

    LaunchedEffect(gridState) {
        snapshotFlow {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible to info.totalItemsCount
        }.collect { (lastVisible, total) ->
            if (total > 0 && lastVisible >= total - 4 &&
                currentHasMore && !currentLoadingMore && !currentLoadingInitial
            ) {
                vm.loadMore()
            }
        }
    }

    LaunchedEffect(items.size, hasMore, isLoadingMore, isLoadingInitial) {
        val info = gridState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 2 &&
            hasMore && !isLoadingMore && !isLoadingInitial
        ) {
            vm.loadMore()
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Library",
            subtitle = statusText,
            action = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Grid.ChipGap),
                ) {
                    Surface(
                        onClick = { vm.refresh() },
                        shape = RoundedCornerShape(Grid.RadiusBadge),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                    ) {
                        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "Refresh live feed",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Surface(
                        onClick = { showFilterSheet = true },
                        shape = RoundedCornerShape(Grid.RadiusBadge),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                    ) {
                        Text(
                            text = sortLabel(filter.sort),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        )
                    }
                }
            },
        )

        Spacer(Modifier.height(Grid.ItemGap))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { vm.setQuery(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Grid.PageHorizontal),
            placeholder = {
                Text(
                    "Search servers directly (Hindi, English, Series…)",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { vm.setQuery("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    vm.submitSearch()
                    keyboard?.hide()
                },
            ),
            shape = RoundedCornerShape(Grid.RadiusControl),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
            ),
        )

        Spacer(Modifier.height(8.dp))

        // YouTube-style live category chip bar discovered directly from enabled servers
        LazyRow(
            contentPadding = PaddingValues(horizontal = Grid.PageHorizontal),
            horizontalArrangement = Arrangement.spacedBy(Grid.ChipGap),
            modifier = Modifier.fillMaxWidth(),
        ) {
            item(key = "__ALL__") {
                CategoryPill(
                    selected = activeSearchQuery.isEmpty() && selectedCategory == null,
                    label = "All",
                    onClick = { vm.selectCategory(null) },
                )
            }
            item(key = LibraryViewModel.FAVORITES_CATEGORY) {
                CategoryPill(
                    selected = activeSearchQuery.isEmpty() &&
                        selectedCategory == LibraryViewModel.FAVORITES_CATEGORY,
                    label = "Favorites",
                    onClick = { vm.selectCategory(LibraryViewModel.FAVORITES_CATEGORY) },
                )
            }
            items(categories, key = { it.name }) { cat ->
                CategoryPill(
                    selected = activeSearchQuery.isEmpty() && selectedCategory == cat.name,
                    label = cat.name,
                    onClick = { vm.selectCategory(cat.name) },
                )
            }
            if (discoveringCategories && categories.isEmpty()) {
                item(key = "__DISCOVERING__") {
                    Surface(
                        shape = RoundedCornerShape(Grid.RadiusControl),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                            Text(
                                "Loading categories…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Grid.PageHorizontal, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Grid.ChipGap),
        ) {
            KindChip(
                selected = filter.kinds.isEmpty(),
                label = "All",
                onClick = { vm.setFilter(filter.copy(kinds = emptySet())) },
            )
            KindChip(
                selected = filter.kinds == setOf("movie"),
                label = "Movies",
                onClick = { vm.setFilter(filter.copy(kinds = setOf("movie"))) },
            )
            KindChip(
                selected = filter.kinds == setOf("series"),
                label = "Shows",
                onClick = { vm.setFilter(filter.copy(kinds = setOf("series"))) },
            )
            Spacer(Modifier.weight(1f))
            BadgedBox(
                badge = {
                    if (filter.activeCount > 0) Badge { Text("${filter.activeCount}") }
                },
            ) {
                Surface(
                    onClick = { showFilterSheet = true },
                    shape = RoundedCornerShape(Grid.RadiusControl),
                    color = if (filter.activeCount > 0) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        1.dp,
                        if (filter.activeCount > 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                    ),
                ) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(R.drawable.ic_filter_list),
                            contentDescription = "Filters",
                            modifier = Modifier.size(18.dp),
                            tint = if (filter.activeCount > 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        GridRuleDivider()
        if (isRefreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
        }

        when {
            isLoadingInitial -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            if (activeSearchQuery.isNotEmpty()) "Searching LAN servers…"
                            else "Fetching live catalog page…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items.isEmpty() && !errorMessage.isNullOrBlank() -> {
                EmptyState(
                    painter = painterResource(R.drawable.ic_error),
                    title = "Could not load from servers",
                    subtitle = errorMessage ?: "Check Wi-Fi connection and enabled Sources.",
                    action = { OutlinedButton(onClick = { vm.refresh() }) { Text("Retry") } },
                )
            }

            items.isEmpty() -> {
                val hasActiveQuery = filter.activeCount > 0 || searchQuery.isNotEmpty() || selectedCategory != null
                EmptyState(
                    painter = painterResource(R.drawable.ic_movie),
                    title = when {
                        activeSearchQuery.isNotEmpty() -> "No live matches"
                        selectedCategory == LibraryViewModel.FAVORITES_CATEGORY -> "No favorites yet"
                        else -> "Nothing found here"
                    },
                    subtitle = when {
                        activeSearchQuery.isNotEmpty() -> "Try another spelling or clear filters."
                        selectedCategory == LibraryViewModel.FAVORITES_CATEGORY ->
                            "Tap the heart on any title card to save it here."
                        else -> "Try choosing another category or refreshing."
                    },
                    action = if (hasActiveQuery && selectedCategory != LibraryViewModel.FAVORITES_CATEGORY) {
                        { OutlinedButton(onClick = { vm.clearFilters() }) { Text("Reset view") } }
                    } else null,
                )
            }

            else -> {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 156.dp),
                    contentPadding = PaddingValues(Grid.PageHorizontal),
                    horizontalArrangement = Arrangement.spacedBy(Grid.ItemGap),
                    verticalArrangement = Arrangement.spacedBy(Grid.ItemGap),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        items = items,
                        key = { it.folderUrl },
                    ) { title ->
                        TitleCard(
                            title = title,
                            onClick = {
                                vm.selectTitle(title)
                                showGroup = true
                            },
                            onDownload = { vm.downloadTitle(title) },
                            onFavorite = { vm.toggleFavorite(title) },
                        )
                    }

                    if (isLoadingMore) {
                        item(key = "__LOADING_MORE__", span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(Grid.SectionGap),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "Loading next page…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else if (hasMore) {
                        item(key = "__LOAD_MORE_BTN__", span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                OutlinedButton(onClick = { vm.loadMore() }) {
                                    Text("Load more")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFilterSheet) {
        FilterSheet(
            filter = filter,
            facets = facets,
            onChange = { vm.setFilter(it) },
            onClear = { vm.clearFilters() },
            onDismiss = { showFilterSheet = false },
        )
    }

    if (showGroup) {
        val title = selectedTitle
        if (title == null) {
            showGroup = false
        } else {
            GroupDetailSheet(
                title = title,
                files = selectedFiles,
                filesLoading = selectedFilesLoading,
                filesError = selectedFilesError,
                downloadedUrls = downloadedUrls,
                onDismiss = {
                    showGroup = false
                    vm.closeTitle()
                },
                onRetry = { vm.retrySelectedFiles() },
                onDownload = { vm.download(it, title.title) },
                onDownloadAll = {
                    vm.downloadAll(selectedFiles, title.title)
                    showGroup = false
                    vm.closeTitle()
                },
                onFavorite = { vm.toggleFavorite(title) },
            )
        }
    }
}

@Composable
private fun CategoryPill(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(Grid.RadiusControl),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun KindChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.height(34.dp),
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        shape = RoundedCornerShape(Grid.RadiusControl),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.primary,
            containerColor = MaterialTheme.colorScheme.surface,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
            selectedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
        ),
    )
}

@Composable
private fun TitleCard(
    title: LiveTitle,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onFavorite: () -> Unit,
) {
    val initials = remember(title.title) {
        title.title.split(' ')
            .asSequence()
            .filter { it.isNotBlank() }
            .take(2)
            .map { it.first().uppercaseChar() }
            .joinToString("")
            .ifEmpty { "?" }
    }
    val metaLine = remember(
        title.year, title.kind, title.seasonCount, title.bestSourceTag, title.bestCodec, title.maxSizeBytes,
    ) {
        listOfNotNull(
            title.year?.toString(),
            if (title.kind == "series" && title.seasonCount > 0) {
                if (title.seasonCount == 1) "1 season" else "${title.seasonCount} seasons"
            } else null,
            title.bestSourceTag,
            title.bestCodec,
            if (title.maxSizeBytes > 0) Format.bytes(title.maxSizeBytes) else null,
        ).joinToString(" • ").ifEmpty { if (title.kind == "series") "Series" else "—" }
    }
    val countBadge = remember(title.seasonCount, title.fileCount) {
        when {
            title.seasonCount > 1 -> "${title.seasonCount} seasons"
            title.seasonCount == 1 -> "1 season"
            title.fileCount > 1 -> "${title.fileCount} files"
            else -> null
        }
    }

    SurfaceCard(onClick = onClick) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .background(titleColor(title.title)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                initials,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.9f),
            )
            if (title.posterUrl != null) {
                AsyncImage(
                    model = title.posterUrl,
                    contentDescription = title.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (title.downloadedCount > 0) {
                BadgePill(
                    text = if (title.fileCount > 0 && title.downloadedCount >= title.fileCount) "Saved"
                    else if (title.fileCount > 0) "${title.downloadedCount}/${title.fileCount}"
                    else "${title.downloadedCount} saved",
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                )
            }
            if (title.bestQuality != null) {
                BadgePill(
                    text = title.bestQuality,
                    containerColor = Color.Black.copy(alpha = 0.68f),
                    contentColor = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                )
            }
            if (countBadge != null) {
                BadgePill(
                    text = countBadge,
                    containerColor = Color.Black.copy(alpha = 0.68f),
                    contentColor = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                )
            }
        }

        Column(Modifier.padding(Grid.CompactCardPadding)) {
            Text(
                text = title.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = metaLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            GridRuleDivider()
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth().height(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title.category,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onFavorite, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (title.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = "Favorite",
                        modifier = Modifier.size(18.dp),
                        tint = if (title.favorite) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDownload, modifier = Modifier.size(32.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_download),
                        contentDescription = "Download",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(
    filter: CatalogFilter,
    facets: Facets,
    onChange: (CatalogFilter) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Grid.PageHorizontal)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(Grid.ItemGap),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Filters", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onClear) { Text("Clear all") }
            }
            GridRuleDivider()

            Text("Sort by", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Grid.ChipGap),
                verticalArrangement = Arrangement.spacedBy(Grid.ChipGap),
            ) {
                SortBy.entries.forEach { sort ->
                    FilterChip(
                        selected = filter.sort == sort,
                        onClick = { onChange(filter.copy(sort = sort)) },
                        label = { Text(sortLabel(sort)) },
                    )
                }
            }

            if (facets.qualities.isNotEmpty()) {
                FacetChipSection("Quality", facets.qualities, filter.qualities) {
                    onChange(filter.copy(qualities = filter.qualities.toggle(it)))
                }
            }
            if (facets.categories.size > 1) {
                FacetChipSection("Category", facets.categories, filter.categories) {
                    onChange(filter.copy(categories = filter.categories.toggle(it)))
                }
            }
            if (facets.sourceNames.size > 1) {
                FacetChipSection("Server", facets.sourceNames, filter.sourceNames) {
                    onChange(filter.copy(sourceNames = filter.sourceNames.toggle(it)))
                }
            }
            if (facets.languages.isNotEmpty()) {
                FacetChipSection("Language", facets.languages, filter.languages) {
                    onChange(filter.copy(languages = filter.languages.toggle(it)))
                }
            }
            if (facets.sourceTags.isNotEmpty()) {
                FacetChipSection("Release type", facets.sourceTags, filter.sourceTags) {
                    onChange(filter.copy(sourceTags = filter.sourceTags.toggle(it)))
                }
            }

            if (facets.years.size > 1) {
                val minYear = facets.years.first().toFloat()
                val maxYear = facets.years.last().toFloat()
                var range by remember(filter.yearMin, filter.yearMax, minYear, maxYear) {
                    mutableStateOf(
                        (filter.yearMin?.toFloat() ?: minYear)..(filter.yearMax?.toFloat() ?: maxYear)
                    )
                }
                Text(
                    "Year: ${range.start.toInt()} – ${range.endInclusive.toInt()}",
                    style = MaterialTheme.typography.titleSmall,
                )
                RangeSlider(
                    value = range,
                    onValueChange = { range = it },
                    onValueChangeFinished = {
                        onChange(
                            filter.copy(
                                yearMin = range.start.toInt(),
                                yearMax = range.endInclusive.toInt(),
                            )
                        )
                    },
                    valueRange = minYear..maxYear,
                )
            }

            Text("Minimum size", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Grid.ChipGap),
                verticalArrangement = Arrangement.spacedBy(Grid.ChipGap),
            ) {
                val sizes = listOf(
                    null to "Any",
                    700_000_000L to "700 MB+",
                    1_500_000_000L to "1.5 GB+",
                    4_000_000_000L to "4 GB+",
                )
                sizes.forEach { (bytes, label) ->
                    FilterChip(
                        selected = filter.minSizeBytes == bytes,
                        onClick = { onChange(filter.copy(minSizeBytes = bytes)) },
                        label = { Text(label) },
                    )
                }
            }

            GridRuleDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Favorites only", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(
                    checked = filter.favoritesOnly,
                    onCheckedChange = { onChange(filter.copy(favoritesOnly = it)) },
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Dual / multi audio only", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(
                    checked = filter.dualAudioOnly,
                    onCheckedChange = { onChange(filter.copy(dualAudioOnly = it)) },
                )
            }

            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(Grid.RadiusControl),
            ) { Text("Show results") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FacetChipSection(
    title: String,
    options: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Grid.ChipGap)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Grid.ChipGap),
            verticalArrangement = Arrangement.spacedBy(Grid.ChipGap),
        ) {
            options.forEach { value ->
                FilterChip(
                    selected = value in selected,
                    onClick = { onToggle(value) },
                    label = { Text(value) },
                )
            }
        }
    }
}

private fun Set<String>.toggle(value: String): Set<String> =
    if (value in this) this - value else this + value

private fun sortLabel(sort: SortBy): String = when (sort) {
    SortBy.NEWEST -> "Feed"
    SortBy.TITLE -> "Title A–Z"
    SortBy.YEAR_DESC -> "Year"
    SortBy.SIZE_DESC -> "Largest"
    SortBy.SIZE_ASC -> "Smallest"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupDetailSheet(
    title: LiveTitle,
    files: List<CatalogFile>,
    filesLoading: Boolean,
    filesError: String?,
    downloadedUrls: Set<String>,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onDownload: (CatalogFile) -> Unit,
    onDownloadAll: () -> Unit,
    onFavorite: () -> Unit,
) {
    val sorted = remember(files) {
        files.sortedWith(
            compareBy({ it.season ?: 0 }, { it.episode ?: 0 }, { it.fileName.lowercase() })
        )
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Grid.PageHorizontal)
                .padding(bottom = 28.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (title.posterUrl != null) {
                    AsyncImage(
                        model = title.posterUrl,
                        contentDescription = title.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                    Spacer(Modifier.width(Grid.ItemGap))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        title.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        listOfNotNull(
                            title.year?.toString(), title.category, title.language, title.sourceName,
                        ).joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onFavorite) {
                    Icon(
                        if (title.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (title.favorite) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (title.downloadedCount > 0) {
                Spacer(Modifier.height(8.dp))
                BadgePill(
                    text = if (files.isNotEmpty()) "${title.downloadedCount} of ${files.size} ${if (files.size == 1) "file" else "files"} downloaded"
                    else "${title.downloadedCount} ${if (title.downloadedCount == 1) "file" else "files"} downloaded",
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(Grid.ItemGap))
            Button(
                onClick = onDownloadAll,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                enabled = files.isNotEmpty() && !filesLoading,
                shape = RoundedCornerShape(Grid.RadiusControl),
            ) {
                Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (filesLoading && files.isEmpty()) "Loading files…"
                    else "Download all (${files.size} ${if (files.size == 1) "file" else "files"})"
                )
            }

            Spacer(Modifier.height(Grid.ItemGap))
            GridRuleDivider()
            Spacer(Modifier.height(Grid.ItemGap))

            when {
                filesLoading && files.isEmpty() -> {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            "Fetching episodes & files from ${title.sourceName}…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                !filesLoading && files.isEmpty() -> {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            filesError ?: "No video files found in this folder.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = onRetry) { Text("Retry") }
                    }
                }

                else -> {
                    if (filesLoading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))
                    }
                    LazyColumn(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(Grid.ChipGap),
                    ) {
                        items(sorted, key = { it.fileUrl }) { file ->
                            val isSaved = file.fileUrl in downloadedUrls
                            Surface(
                                shape = RoundedCornerShape(Grid.RadiusControl),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = cardBorder(),
                            ) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { onDownload(file) }
                                        .padding(horizontal = Grid.CompactCardPadding, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            if (file.episode != null) {
                                                "S${file.season ?: "?"} E${file.episode} — ${file.fileName}"
                                            } else if (file.season != null) {
                                                "S${file.season} — ${file.fileName}"
                                            } else {
                                                file.fileName
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            listOfNotNull(
                                                file.quality, file.sourceTag, file.codec, file.audio,
                                                if (file.sizeBytes > 0) Format.bytes(file.sizeBytes) else null,
                                            ).joinToString(" • "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    IconButton(onClick = { onDownload(file) }, modifier = Modifier.size(36.dp)) {
                                        if (isSaved) {
                                            Icon(
                                                Icons.Filled.CheckCircle,
                                                contentDescription = "Already downloaded",
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        } else {
                                            Icon(
                                                painterResource(R.drawable.ic_download),
                                                contentDescription = "Download this file",
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
